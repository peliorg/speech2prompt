package com.speech2prompt.presentation.screens.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speech2prompt.domain.model.BleDeviceInfo
import com.speech2prompt.domain.model.BtConnectionState
import com.speech2prompt.service.ble.BleManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Connection screen.
 * Manages BLE device scanning, connection, and pairing.
 * 
 * Features:
 * - Scan for BLE devices
 * - Connect to selected device
 * - Handle pairing flow with PIN entry
 * - Display scanned devices sorted by relevance
 */
@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val bleManager: BleManager
) : ViewModel() {
    
    // ==================== UI State ====================
    
    /**
     * List of scanned BLE devices
     */
    val scannedDevices: StateFlow<List<BleDeviceInfo>> = bleManager.scannedDevices
    
    /**
     * Current BLE connection state
     */
    val connectionState: StateFlow<BtConnectionState> = bleManager.connectionState
    
    /**
     * Currently connected device (if any)
     */
    val connectedDevice: StateFlow<BleDeviceInfo?> = bleManager.connectedDevice
    
    /**
     * Whether scanning is in progress
     */
    val isScanning: StateFlow<Boolean> = bleManager.isScanning
    
    /**
     * Whether Bluetooth is enabled
     */
    private val _bluetoothEnabled = MutableStateFlow(true)
    val bluetoothEnabled: StateFlow<Boolean> = _bluetoothEnabled.asStateFlow()
    
    /**
     * Whether to show pairing dialog
     */
    private val _showPairingDialog = MutableStateFlow(false)
    val showPairingDialog: StateFlow<Boolean> = _showPairingDialog.asStateFlow()
    
    /**
     * Error message for display
     */
    val error: StateFlow<String?> = bleManager.error
    
    // ==================== Private State ====================
    
    private var pendingDevice: BleDeviceInfo? = null
    
    // Flag to track if we're actively waiting for user PIN input
    // This is separate from connection state to prevent race conditions
    private var awaitingUserPinInput = false
    
    // ==================== Initialization ====================
    
    init {
        // Check if Bluetooth is enabled
        updateBluetoothState()
        
        // Observe connection state to show/hide pairing dialog
        // The dialog has its own lifecycle:
        // - Show dialog when entering AWAITING_PAIRING (only if not already shown)
        // - Close dialog ONLY when:
        //   a) User submits PIN (handled in submitPairingPin)
        //   b) User cancels (handled in cancelPairing)
        //   c) Connection is lost (DISCONNECTED/FAILED)
        //   d) Pairing succeeds via PAIR_ACK (will transition through AWAITING_PAIRING -> CONNECTED properly)
        // - Do NOT close dialog just because state changes to CONNECTED (desktop may send premature status)
        viewModelScope.launch {
            connectionState.collect { state ->
                when (state) {
                    BtConnectionState.AWAITING_PAIRING -> {
                        // Only show dialog if not already waiting for PIN
                        // This prevents flickering from multiple state emissions
                        if (!awaitingUserPinInput) {
                            awaitingUserPinInput = true
                            _showPairingDialog.value = true
                        }
                    }
                    BtConnectionState.DISCONNECTED, BtConnectionState.FAILED -> {
                        // Connection lost - always close dialog and reset flag
                        awaitingUserPinInput = false
                        _showPairingDialog.value = false
                    }
                    BtConnectionState.CONNECTED -> {
                        // Only close dialog if we were waiting for PIN and pairing completed
                        // The awaitingUserPinInput flag is cleared in submitPairingPin/cancelPairing
                        // So if it's still true here, don't close the dialog yet
                        // (desktop may send CONNECTED status before PIN is verified)
                    }
                    else -> { 
                        // CONNECTING, RECONNECTING - don't change dialog state
                    }
                }
            }
        }
    }
    
    // ==================== Actions ====================
    
    /**
     * Start scanning for BLE devices
     */
    fun startScan() {
        updateBluetoothState()
        
        if (!bleManager.isBluetoothEnabled()) {
            _bluetoothEnabled.value = false
            return
        }
        
        _bluetoothEnabled.value = true
        bleManager.startScan()
    }
    
    /**
     * Stop scanning for BLE devices
     */
    fun stopScan() {
        bleManager.stopScan()
    }
    
    /**
     * Connect to a BLE device
     */
    fun connectToDevice(device: BleDeviceInfo) {
        viewModelScope.launch {
            stopScan()
            pendingDevice = device
            bleManager.connect(device)
        }
    }
    
    /**
     * Submit pairing PIN
     */
    fun submitPairingPin(pin: String) {
        viewModelScope.launch {
            if (pin.length == 6 && pin.all { it.isDigit() }) {
                bleManager.setPairingPin(pin)
                // Close dialog immediately after PIN submission
                // The result (success/failure) will be handled by PAIR_ACK
                awaitingUserPinInput = false
                _showPairingDialog.value = false
            }
        }
    }
    
    /**
     * Cancel pairing process
     */
    fun cancelPairing() {
        awaitingUserPinInput = false
        _showPairingDialog.value = false
        bleManager.disconnect()
        pendingDevice = null
    }
    
    /**
     * Disconnect from current device
     */
    fun disconnect() {
        bleManager.disconnect()
    }
    
    /**
     * Clear current error
     */
    fun clearError() {
        // BleManager doesn't expose error clearing, so we'll just dismiss locally
        // The error will be cleared on next successful operation
    }
    
    // ==================== Private Methods ====================
    
    private fun updateBluetoothState() {
        _bluetoothEnabled.value = bleManager.isBluetoothEnabled()
    }
    
    // ==================== Lifecycle ====================
    
    override fun onCleared() {
        super.onCleared()
        // Stop scanning when ViewModel is cleared
        stopScan()
    }
}
