# Phase 5: BLE Service Implementation

> Full Bluetooth Low Energy service with scanning, connection, pairing, and messaging.

## Goal

Implement a complete BLE service that handles device discovery, connection management, MTU negotiation, pairing flow, message transmission, and automatic reconnection. This is the core communication layer between the Android app and the Linux desktop.

---

## Dependencies

This phase depends on:
- **Phase 4: BLE Packet Assembly** - `PacketAssembler`, `PacketReassembler`, `BleConstants`
- **Phase 2: Core Domain Models** - `Message`, `MessageType`, `BleDeviceInfo`, `BtConnectionState`
- **Phase 5: Secure Storage** - `SecureStorageRepository` for pairing credentials
- **Phase 9: Encryption** - `CryptoContext` for message encryption/signing

---

## BleDeviceInfo (domain/model/BleDeviceInfo.kt)

```kotlin
package com.speech2prompt.domain.model

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import com.speech2prompt.service.ble.BleConstants
import java.util.UUID

/**
 * Wrapper for BLE device info from scan results.
 */
data class BleDeviceInfo(
    val name: String,
    val address: String,
    val rssi: Int,
    val hasS2PService: Boolean,
    val device: BluetoothDevice
) {
    /** Display name for UI (prefers name, falls back to address) */
    val displayName: String
        get() = name.ifEmpty { address }
    
    /** Check if this looks like a Speech2Prompt server */
    val isSpeech2Prompt: Boolean
        get() = hasS2PService || 
                name.lowercase().contains("speech2prompt") ||
                name.lowercase().contains("speech 2 prompt")
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BleDeviceInfo) return false
        return address == other.address
    }
    
    override fun hashCode(): Int = address.hashCode()
    
    companion object {
        /**
         * Create from Android BLE ScanResult.
         */
        fun fromScanResult(result: ScanResult): BleDeviceInfo {
            val device = result.device
            val scanRecord = result.scanRecord
            
            // Check if Speech2Prompt service is advertised
            val serviceUuids = scanRecord?.serviceUuids ?: emptyList()
            val hasS2PService = serviceUuids.any { 
                it.uuid.toString().equals(BleConstants.SERVICE_UUID, ignoreCase = true)
            }
            
            val name = scanRecord?.deviceName 
                ?: device.name 
                ?: ""
            
            return BleDeviceInfo(
                name = name,
                address = device.address,
                rssi = result.rssi,
                hasS2PService = hasS2PService,
                device = device
            )
        }
    }
}
```

---

## BtConnectionState (domain/model/BtConnectionState.kt)

```kotlin
package com.speech2prompt.domain.model

/**
 * Bluetooth connection state.
 */
enum class BtConnectionState {
    /** Not connected, not trying to connect */
    DISCONNECTED,
    
    /** Currently trying to connect */
    CONNECTING,
    
    /** Connected and ready */
    CONNECTED,
    
    /** Waiting for pairing PIN entry */
    AWAITING_PAIRING,
    
    /** Connection lost, will retry */
    RECONNECTING,
    
    /** Connection failed, not retrying */
    FAILED;
    
    val isConnected: Boolean
        get() = this == CONNECTED
    
    val isDisconnected: Boolean
        get() = this == DISCONNECTED
    
    val isConnecting: Boolean
        get() = this == CONNECTING || this == RECONNECTING
    
    val canConnect: Boolean
        get() = this == DISCONNECTED || this == FAILED
    
    val displayText: String
        get() = when (this) {
            DISCONNECTED -> "Disconnected"
            CONNECTING -> "Connecting..."
            CONNECTED -> "Connected"
            AWAITING_PAIRING -> "Pairing..."
            RECONNECTING -> "Reconnecting..."
            FAILED -> "Connection Failed"
        }
}
```

---

## BleManager (service/ble/BleManager.kt)

```kotlin
package com.speech2prompt.service.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.speech2prompt.data.repository.SecureStorageRepository
import com.speech2prompt.domain.model.BleDeviceInfo
import com.speech2prompt.domain.model.BtConnectionState
import com.speech2prompt.domain.model.Message
import com.speech2prompt.domain.model.MessageType
import com.speech2prompt.service.crypto.CryptoContext
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BLE service manager for Speech2Prompt communication.
 * 
 * Handles:
 * - Device scanning and discovery
 * - Connection management
 * - MTU negotiation
 * - Pairing flow
 * - Message transmission with chunking
 * - Automatic reconnection
 * - Heartbeat monitoring
 */
@Singleton
class BleManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureStorage: SecureStorageRepository
) {
    companion object {
        private const val TAG = "BleManager"
    }
    
    // Coroutine scope for BLE operations
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // State flows
    private val _connectionState = MutableStateFlow(BtConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BtConnectionState> = _connectionState.asStateFlow()
    
    private val _connectedDevice = MutableStateFlow<BleDeviceInfo?>(null)
    val connectedDevice: StateFlow<BleDeviceInfo?> = _connectedDevice.asStateFlow()
    
    private val _scannedDevices = MutableStateFlow<List<BleDeviceInfo>>(emptyList())
    val scannedDevices: StateFlow<List<BleDeviceInfo>> = _scannedDevices.asStateFlow()
    
    private val _receivedMessages = MutableSharedFlow<Message>(extraBufferCapacity = 64)
    val receivedMessages: SharedFlow<Message> = _receivedMessages.asSharedFlow()
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    // BLE components
    private val bluetoothManager: BluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager.adapter
    }
    private var bluetoothGatt: BluetoothGatt? = null
    private var bluetoothScanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    
    // GATT characteristics
    private var commandRxCharacteristic: BluetoothGattCharacteristic? = null
    private var responseTxCharacteristic: BluetoothGattCharacteristic? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null
    
    // Packet handling
    private val packetReassembler = PacketReassembler()
    private var negotiatedMtu = BleConstants.DEFAULT_MTU
    
    // Crypto context (set after pairing)
    private var cryptoContext: CryptoContext? = null
    private var deviceId: String? = null
    private var pendingPairingPin: String? = null
    
    // Pending operations
    private val pendingMessages = mutableListOf<Message>()
    private val ackWaiters = mutableMapOf<Long, CompletableDeferred<Boolean>>()
    
    // Timers
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    
    // ----- Public API -----
    
    /**
     * Check if Bluetooth is supported and enabled.
     */
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }
    
    /**
     * Start scanning for Speech2Prompt BLE devices.
     * 
     * @return Flow of discovered devices (updates as new devices are found)
     */
    @SuppressLint("MissingPermission")
    fun startScan(): Flow<List<BleDeviceInfo>> {
        Log.d(TAG, "Starting BLE scan...")
        
        _scannedDevices.value = emptyList()
        
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "BluetoothLeScanner not available")
            return flowOf(emptyList())
        }
        bluetoothScanner = scanner
        
        // Scan settings for balanced power/latency
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setReportDelay(0)
            .build()
        
        // Filter for Speech2Prompt service UUID
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(UUID.fromString(BleConstants.SERVICE_UUID)))
            .build()
        
        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleScanResult(result)
            }
            
            override fun onBatchScanResults(results: List<ScanResult>) {
                results.forEach { handleScanResult(it) }
            }
            
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed with error code: $errorCode")
                _errorMessage.value = "Scan failed: error $errorCode"
            }
        }
        
        scanner.startScan(listOf(filter), settings, scanCallback)
        
        // Auto-stop scan after timeout
        scope.launch {
            delay(BleConstants.SCAN_TIMEOUT.inWholeMilliseconds)
            stopScan()
        }
        
        return scannedDevices
    }
    
    /**
     * Stop the current BLE scan.
     */
    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanCallback?.let { callback ->
            try {
                bluetoothScanner?.stopScan(callback)
                Log.d(TAG, "Scan stopped")
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping scan: ${e.message}")
            }
        }
        scanCallback = null
    }
    
    /**
     * Connect to a BLE device.
     * 
     * @param device Device to connect to
     * @return true if connection initiated successfully
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(device: BleDeviceInfo): Boolean {
        if (_connectionState.value == BtConnectionState.CONNECTING) {
            Log.w(TAG, "Already connecting")
            return false
        }
        
        setState(BtConnectionState.CONNECTING)
        _errorMessage.value = null
        reconnectAttempts = 0
        
        try {
            Log.d(TAG, "Connecting to ${device.displayName}...")
            
            stopScan()
            
            // Connect with auto-connect disabled for faster initial connection
            bluetoothGatt = device.device.connectGatt(
                context,
                false, // autoConnect
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
            
            // Wait for connection with timeout
            withTimeout(BleConstants.CONNECTION_TIMEOUT.inWholeMilliseconds) {
                connectionState.first { 
                    it == BtConnectionState.CONNECTED || 
                    it == BtConnectionState.AWAITING_PAIRING ||
                    it == BtConnectionState.FAILED 
                }
            }
            
            return _connectionState.value != BtConnectionState.FAILED
            
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Connection timeout")
            _errorMessage.value = "Connection timeout"
            setState(BtConnectionState.FAILED)
            cleanup()
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Connection error: ${e.message}")
            _errorMessage.value = e.message
            setState(BtConnectionState.FAILED)
            cleanup()
            return false
        }
    }
    
    /**
     * Disconnect from the current device.
     */
    @SuppressLint("MissingPermission")
    suspend fun disconnect() {
        Log.d(TAG, "Disconnecting...")
        
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        
        try {
            bluetoothGatt?.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Error disconnecting: ${e.message}")
        }
        
        cleanup()
        setState(BtConnectionState.DISCONNECTED)
    }
    
    /**
     * Store the PIN for pairing completion.
     * Call this when user enters PIN on the pairing screen.
     */
    fun setPairingPin(pin: String, linuxDeviceId: String) {
        pendingPairingPin = pin
        initializeCryptoContext(pin, linuxDeviceId)
        Log.d(TAG, "Pairing PIN set, crypto context initialized")
    }
    
    /**
     * Clear the crypto context (for fresh pairing).
     */
    fun clearCryptoContext() {
        cryptoContext = null
        pendingPairingPin = null
        Log.d(TAG, "Crypto context cleared")
    }
    
    /**
     * Send a message to the connected device.
     * 
     * @param message Message to send
     * @return true if message was sent successfully (and ACKed if required)
     */
    @SuppressLint("MissingPermission")
    suspend fun sendMessage(message: Message): Boolean {
        if (_connectionState.value != BtConnectionState.CONNECTED) {
            Log.d(TAG, "Not connected, queueing message")
            pendingMessages.add(message)
            return false
        }
        
        val characteristic = commandRxCharacteristic
        val gatt = bluetoothGatt
        
        if (characteristic == null || gatt == null) {
            Log.e(TAG, "GATT or characteristic not available")
            return false
        }
        
        return try {
            // Sign and encrypt if we have crypto context
            val messageToSend = if (cryptoContext != null && message.shouldEncrypt) {
                cryptoContext!!.signAndEncrypt(message)
            } else {
                message
            }
            
            val json = messageToSend.toJson()
            Log.d(TAG, "Sending: ${json.take(100)}...")
            
            val jsonBytes = json.toByteArray(Charsets.UTF_8)
            
            // Chunk into BLE packets
            val packets = PacketAssembler.chunkMessage(jsonBytes, negotiatedMtu)
            Log.d(TAG, "Chunked into ${packets.size} packets (MTU: $negotiatedMtu)")
            
            // Send each packet
            for (packet in packets) {
                characteristic.value = packet
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                
                if (!gatt.writeCharacteristic(characteristic)) {
                    Log.e(TAG, "Write characteristic failed")
                    return false
                }
                
                // Small delay between packets to avoid overwhelming the BLE stack
                delay(10)
            }
            
            // Wait for ACK if needed
            if (message.messageType != MessageType.ACK && 
                message.messageType != MessageType.HEARTBEAT) {
                waitForAck(message.timestamp)
            } else {
                true
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Send error: ${e.message}")
            handleDisconnection()
            false
        }
    }
    
    /**
     * Generate or retrieve device ID.
     */
    suspend fun getOrCreateDeviceId(): String {
        if (deviceId == null) {
            deviceId = secureStorage.getDeviceId() ?: run {
                val newId = UUID.randomUUID().toString()
                secureStorage.saveDeviceId(newId)
                newId
            }
        }
        return deviceId!!
    }
    
    // ----- GATT Callback -----
    
    private val gattCallback = object : BluetoothGattCallback() {
        
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "Connection state changed: status=$status, newState=$newState")
            
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected, discovering services...")
                    _connectedDevice.value = BleDeviceInfo(
                        name = gatt.device.name ?: "",
                        address = gatt.device.address,
                        rssi = 0,
                        hasS2PService = true,
                        device = gatt.device
                    )
                    
                    // Request higher MTU before discovering services
                    gatt.requestMtu(BleConstants.TARGET_MTU)
                }
                
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected")
                    handleDisconnection()
                }
            }
        }
        
        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
                Log.d(TAG, "MTU negotiated: $mtu")
            } else {
                negotiatedMtu = BleConstants.DEFAULT_MTU
                Log.w(TAG, "MTU negotiation failed, using default: $negotiatedMtu")
            }
            
            // Now discover services
            gatt.discoverServices()
        }
        
        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                _errorMessage.value = "Service discovery failed"
                setState(BtConnectionState.FAILED)
                return
            }
            
            Log.d(TAG, "Services discovered")
            
            // Find Speech2Prompt service
            val service = gatt.getService(UUID.fromString(BleConstants.SERVICE_UUID))
            if (service == null) {
                Log.e(TAG, "Speech2Prompt service not found")
                _errorMessage.value = "Speech2Prompt service not found"
                setState(BtConnectionState.FAILED)
                return
            }
            
            // Get characteristics
            commandRxCharacteristic = service.getCharacteristic(
                UUID.fromString(BleConstants.COMMAND_RX_UUID)
            )
            responseTxCharacteristic = service.getCharacteristic(
                UUID.fromString(BleConstants.RESPONSE_TX_UUID)
            )
            statusCharacteristic = service.getCharacteristic(
                UUID.fromString(BleConstants.STATUS_UUID)
            )
            
            if (commandRxCharacteristic == null || responseTxCharacteristic == null) {
                Log.e(TAG, "Required characteristics not found")
                _errorMessage.value = "Required characteristics not found"
                setState(BtConnectionState.FAILED)
                return
            }
            
            Log.d(TAG, "Found all characteristics")
            
            // Subscribe to notifications
            setupNotifications(gatt)
        }
        
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            when (characteristic.uuid.toString().lowercase()) {
                BleConstants.RESPONSE_TX_UUID.lowercase() -> {
                    handleResponsePacket(value)
                }
                BleConstants.STATUS_UUID.lowercase() -> {
                    handleStatusChange(value)
                }
            }
        }
        
        // Legacy callback for older API levels
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            characteristic.value?.let { value ->
                onCharacteristicChanged(gatt, characteristic, value)
            }
        }
        
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Characteristic write failed: $status")
            }
        }
        
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Descriptor written successfully")
            } else {
                Log.w(TAG, "Descriptor write failed: $status")
            }
        }
    }
    
    // ----- Private Methods -----
    
    private fun handleScanResult(result: ScanResult) {
        val deviceInfo = BleDeviceInfo.fromScanResult(result)
        
        // Only add Speech2Prompt devices
        if (!deviceInfo.isSpeech2Prompt) return
        
        val currentList = _scannedDevices.value.toMutableList()
        val existingIndex = currentList.indexOfFirst { it.address == deviceInfo.address }
        
        if (existingIndex >= 0) {
            // Update existing device (RSSI may have changed)
            currentList[existingIndex] = deviceInfo
        } else {
            currentList.add(deviceInfo)
            Log.d(TAG, "Found device: ${deviceInfo.displayName}")
        }
        
        _scannedDevices.value = currentList
    }
    
    @SuppressLint("MissingPermission")
    private fun setupNotifications(gatt: BluetoothGatt) {
        // Subscribe to Response TX characteristic
        responseTxCharacteristic?.let { char ->
            gatt.setCharacteristicNotification(char, true)
            
            val descriptor = char.getDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb") // CCCD
            )
            descriptor?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(it)
            }
            Log.d(TAG, "Subscribed to Response TX notifications")
        }
        
        // Subscribe to Status characteristic
        statusCharacteristic?.let { char ->
            scope.launch {
                delay(100) // Small delay to let previous descriptor write complete
                gatt.setCharacteristicNotification(char, true)
                
                val descriptor = char.getDescriptor(
                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                )
                descriptor?.let {
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(it)
                }
                Log.d(TAG, "Subscribed to Status notifications")
            }
        }
        
        // Mark as connected and start heartbeat
        setState(BtConnectionState.CONNECTED)
        startHeartbeat()
        
        // Send pairing request
        scope.launch {
            sendPairingRequest()
        }
    }
    
    private fun handleResponsePacket(data: ByteArray) {
        val completeMessage = packetReassembler.addPacket(data)
        
        if (completeMessage != null) {
            try {
                val json = String(completeMessage, Charsets.UTF_8)
                val message = Message.fromJson(json)
                handleReceivedMessage(message)
            } catch (e: Exception) {
                Log.e(TAG, "Parse error: ${e.message}")
            }
        }
    }
    
    private fun handleReceivedMessage(message: Message) {
        Log.d(TAG, "Received: ${message.messageType}")
        
        // Verify and decrypt if we have crypto context
        // Note: ACK and PAIR_ACK are excluded from verification
        if (cryptoContext != null && 
            message.messageType != MessageType.ACK &&
            message.messageType != MessageType.PAIR_ACK) {
            try {
                cryptoContext!!.verifyAndDecrypt(message)
            } catch (e: Exception) {
                Log.w(TAG, "Verification failed: ${e.message}")
                return
            }
        }
        
        when (message.messageType) {
            MessageType.ACK -> handleAck(message)
            MessageType.PAIR_ACK -> handlePairAck(message)
            MessageType.HEARTBEAT -> {
                // Respond with ACK
                scope.launch {
                    sendMessage(Message.ack(message.timestamp))
                }
            }
            else -> {
                // Emit to subscribers
                scope.launch {
                    _receivedMessages.emit(message)
                }
            }
        }
    }
    
    private fun handleAck(message: Message) {
        val timestamp = message.payload.toLongOrNull() ?: return
        ackWaiters[timestamp]?.complete(true)
        ackWaiters.remove(timestamp)
    }
    
    private fun handlePairAck(message: Message) {
        try {
            val payload = Message.parsePairAckPayload(message.payload)
            
            if (payload.status == "ok") {
                val linuxDeviceId = payload.deviceId
                if (linuxDeviceId.isEmpty()) {
                    Log.e(TAG, "PAIR_ACK missing Linux device ID")
                    _errorMessage.value = "Invalid pairing response"
                    setState(BtConnectionState.FAILED)
                    return
                }
                
                // Complete pairing with received Linux device ID
                pendingPairingPin?.let { pin ->
                    scope.launch {
                        completePairing(pin, linuxDeviceId)
                    }
                    pendingPairingPin = null
                }
                
                Log.d(TAG, "Pairing successful with $linuxDeviceId")
                setState(BtConnectionState.CONNECTED)
                
                // Send any pending messages
                sendPendingMessages()
                
            } else {
                Log.e(TAG, "Pairing failed: ${payload.error}")
                _errorMessage.value = payload.error ?: "Pairing failed"
                pendingPairingPin = null
                setState(BtConnectionState.FAILED)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing PAIR_ACK: ${e.message}")
            pendingPairingPin = null
        }
    }
    
    private fun handleStatusChange(data: ByteArray) {
        if (data.isEmpty()) return
        
        val statusCode = data[0].toInt() and 0xFF
        Log.d(TAG, "Status changed: 0x${statusCode.toString(16)}")
        
        when (statusCode) {
            BleStatusCode.IDLE -> { /* Not paired */ }
            BleStatusCode.AWAITING_PAIRING -> {
                setState(BtConnectionState.AWAITING_PAIRING)
            }
            BleStatusCode.PAIRED -> {
                if (_connectionState.value == BtConnectionState.AWAITING_PAIRING) {
                    setState(BtConnectionState.CONNECTED)
                }
            }
            BleStatusCode.BUSY -> { /* Processing */ }
            BleStatusCode.ERROR -> {
                _errorMessage.value = "Device reported error"
            }
        }
    }
    
    private suspend fun sendPairingRequest() {
        val myDeviceId = getOrCreateDeviceId()
        val message = Message.pairRequest(myDeviceId, "Android Device")
        sendMessage(message)
        setState(BtConnectionState.AWAITING_PAIRING)
    }
    
    private suspend fun waitForAck(timestamp: Long): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        ackWaiters[timestamp] = deferred
        
        return try {
            withTimeout(BleConstants.ACK_TIMEOUT.inWholeMilliseconds) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "ACK timeout for $timestamp")
            ackWaiters.remove(timestamp)
            false
        }
    }
    
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && _connectionState.value == BtConnectionState.CONNECTED) {
                delay(BleConstants.HEARTBEAT_INTERVAL.inWholeMilliseconds)
                
                if (_connectionState.value == BtConnectionState.CONNECTED) {
                    val success = sendMessage(Message.heartbeat())
                    if (!success) {
                        Log.w(TAG, "Heartbeat failed")
                        // Connection might be stale, will be detected by timeout
                    }
                }
            }
        }
    }
    
    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }
    
    private fun handleDisconnection() {
        stopHeartbeat()
        
        val wasConnected = _connectedDevice.value != null
        
        if (wasConnected && reconnectAttempts < BleConstants.MAX_RECONNECT_ATTEMPTS) {
            setState(BtConnectionState.RECONNECTING)
            scheduleReconnect()
        } else {
            cleanup()
            setState(BtConnectionState.DISCONNECTED)
        }
    }
    
    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        
        // Exponential backoff: 1s, 2s, 4s, 8s, 16s
        val delayMs = (1000L shl reconnectAttempts).coerceAtMost(16_000L)
        Log.d(TAG, "Reconnecting in ${delayMs}ms (attempt ${reconnectAttempts + 1})")
        
        reconnectJob = scope.launch {
            delay(delayMs)
            
            _connectedDevice.value?.let { device ->
                reconnectAttempts++
                connect(device)
            }
        }
    }
    
    private fun sendPendingMessages() {
        scope.launch {
            while (pendingMessages.isNotEmpty() && 
                   _connectionState.value == BtConnectionState.CONNECTED) {
                val message = pendingMessages.removeAt(0)
                sendMessage(message)
            }
        }
    }
    
    private fun initializeCryptoContext(pin: String, linuxDeviceId: String) {
        scope.launch {
            val myDeviceId = getOrCreateDeviceId()
            cryptoContext = CryptoContext.fromPin(pin, myDeviceId, linuxDeviceId)
        }
    }
    
    private suspend fun completePairing(pin: String, linuxDeviceId: String) {
        val myDeviceId = getOrCreateDeviceId()
        cryptoContext = CryptoContext.fromPin(pin, myDeviceId, linuxDeviceId)
        
        // Store pairing info
        _connectedDevice.value?.let { device ->
            secureStorage.savePairedDevice(
                address = device.address,
                name = device.displayName,
                linuxDeviceId = linuxDeviceId,
                sharedSecret = cryptoContext!!.exportKey()
            )
            Log.d(TAG, "Pairing stored for ${device.displayName}")
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun cleanup() {
        packetReassembler.reset()
        ackWaiters.clear()
        
        bluetoothGatt?.let { gatt ->
            try {
                gatt.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing GATT: ${e.message}")
            }
        }
        
        bluetoothGatt = null
        commandRxCharacteristic = null
        responseTxCharacteristic = null
        statusCharacteristic = null
        _connectedDevice.value = null
    }
    
    private fun setState(state: BtConnectionState) {
        if (_connectionState.value != state) {
            Log.d(TAG, "State: ${_connectionState.value} -> $state")
            _connectionState.value = state
        }
    }
    
    /**
     * Release all resources.
     */
    fun release() {
        scope.cancel()
        stopScan()
        scope.launch {
            disconnect()
        }
    }
}
```

---

## SecureStorageRepository Interface Addition

Add these methods to your existing `SecureStorageRepository`:

```kotlin
// In data/repository/SecureStorageRepository.kt

interface SecureStorageRepository {
    // ... existing methods ...
    
    suspend fun getDeviceId(): String?
    suspend fun saveDeviceId(deviceId: String)
    
    suspend fun getPairedDevice(address: String): PairedDeviceData?
    suspend fun savePairedDevice(
        address: String,
        name: String,
        linuxDeviceId: String,
        sharedSecret: String
    )
    suspend fun removePairedDevice(address: String)
    suspend fun getAllPairedDevices(): List<PairedDeviceData>
}

data class PairedDeviceData(
    val address: String,
    val name: String,
    val linuxDeviceId: String,
    val sharedSecret: String,
    val pairedAt: Long
)
```

---

## Hilt Module (di/BleModule.kt)

```kotlin
package com.speech2prompt.di

import android.content.Context
import com.speech2prompt.data.repository.SecureStorageRepository
import com.speech2prompt.service.ble.BleManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BleModule {
    
    @Provides
    @Singleton
    fun provideBleManager(
        @ApplicationContext context: Context,
        secureStorage: SecureStorageRepository
    ): BleManager {
        return BleManager(context, secureStorage)
    }
}
```

---

## Usage Example (in ViewModel)

```kotlin
@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val bleManager: BleManager
) : ViewModel() {
    
    val connectionState = bleManager.connectionState
    val scannedDevices = bleManager.scannedDevices
    val connectedDevice = bleManager.connectedDevice
    val errorMessage = bleManager.errorMessage
    
    init {
        // Observe received messages
        viewModelScope.launch {
            bleManager.receivedMessages.collect { message ->
                handleReceivedMessage(message)
            }
        }
    }
    
    fun startScan() {
        viewModelScope.launch {
            bleManager.startScan().collect { devices ->
                // Devices are automatically exposed via scannedDevices StateFlow
            }
        }
    }
    
    fun stopScan() {
        bleManager.stopScan()
    }
    
    fun connectToDevice(device: BleDeviceInfo) {
        viewModelScope.launch {
            bleManager.connect(device)
        }
    }
    
    fun disconnect() {
        viewModelScope.launch {
            bleManager.disconnect()
        }
    }
    
    fun submitPairingPin(pin: String, linuxDeviceId: String) {
        bleManager.setPairingPin(pin, linuxDeviceId)
    }
    
    fun sendText(text: String) {
        viewModelScope.launch {
            val message = Message.text(text)
            bleManager.sendMessage(message)
        }
    }
    
    private fun handleReceivedMessage(message: Message) {
        when (message.messageType) {
            MessageType.TEXT -> {
                // Handle received text
            }
            MessageType.COMMAND -> {
                // Handle command
            }
            else -> { /* Ignore */ }
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        // Don't release BleManager here - it's a singleton
    }
}
```

---

## Android Permissions Required

Add to `AndroidManifest.xml`:

```xml
<!-- Bluetooth permissions -->
<uses-permission android:name="android.permission.BLUETOOTH" 
    android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" 
    android:maxSdkVersion="30" />

<!-- Android 12+ BLE permissions -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" 
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

<!-- Location permission (required for BLE scan on Android 11 and below) -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />

<!-- Declare BLE feature -->
<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
```

---

## Verification Checklist

- [ ] Scan discovers Speech2Prompt devices (filters by service UUID)
- [ ] Connect and discover services correctly
- [ ] MTU negotiation (request 512, handle fallback to default)
- [ ] Subscribe to Response TX and Status notifications
- [ ] Send TEXT message, receive ACK
- [ ] Pairing flow with PIN:
  - [ ] Send PAIR_REQ on connect
  - [ ] Receive PAIR_ACK with Linux device ID
  - [ ] Derive crypto key from PIN + device IDs
  - [ ] Store pairing credentials securely
- [ ] Reconnection on disconnect:
  - [ ] Exponential backoff (1s, 2s, 4s, 8s, 16s)
  - [ ] Max 5 attempts before giving up
  - [ ] Reset attempt counter on successful connection
- [ ] Heartbeat keeps connection alive:
  - [ ] Send HEARTBEAT every 5 seconds
  - [ ] Respond to received HEARTBEAT with ACK
- [ ] Large message chunking works correctly
- [ ] State transitions are correct and observable

---

## Estimated Time: 2-3 days

This is the most complex phase, involving Android BLE APIs, asynchronous callbacks, state management, and integration with crypto and storage layers.
