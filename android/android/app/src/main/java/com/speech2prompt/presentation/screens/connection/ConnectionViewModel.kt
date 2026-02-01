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
 * - Handle pairing flow with ECDH
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
     * Error message for display
     */
    val error: StateFlow<String?> = bleManager.error
    
    // ==================== Private State ====================
    
    private var pendingDevice: BleDeviceInfo? = null
    
    // ==================== Initialization ====================
    
    init {
        // Check if Bluetooth is enabled
        updateBluetoothState()
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
     * Cancel pairing process
     */
    fun cancelPairing() {
        bleManager.disconnect()
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
