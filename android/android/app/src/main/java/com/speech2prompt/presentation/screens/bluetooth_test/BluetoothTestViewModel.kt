package com.speech2prompt.presentation.screens.bluetooth_test

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speech2prompt.domain.model.*
import com.speech2prompt.service.ble.BleManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

/**
 * ViewModel for the Bluetooth Test screen.
 * Provides isolated testing of BLE connection without speech dependency.
 * 
 * Features:
 * - Display connection status
 * - Send test messages
 * - Send command codes
 * - Log all sent/received messages
 */
@HiltViewModel
class BluetoothTestViewModel @Inject constructor(
    private val bleManager: BleManager
) : ViewModel() {
    
    companion object {
        private const val TAG = "BluetoothTestViewModel"
    }
    
    // ==================== UI State ====================
    
    /**
     * Current BLE connection state
     */
    val connectionState: StateFlow<BtConnectionState> = bleManager.connectionState
    
    /**
     * Currently connected device (if any)
     */
    val connectedDevice: StateFlow<BleDeviceInfo?> = bleManager.connectedDevice
    
    /**
     * Message log for display
     */
    private val _messageLog = MutableStateFlow<List<String>>(emptyList())
    val messageLog: StateFlow<List<String>> = _messageLog.asStateFlow()
    
    // ==================== Initialization ====================
    
    init {
        // Observe received messages
        viewModelScope.launch {
            bleManager.receivedMessages.collect { message ->
                log("RECV: ${message.messageType} | ${message.payload}")
            }
        }
        
        // Observe connection state changes
        viewModelScope.launch {
            connectionState.collect { state ->
                log("Connection state: ${state.displayText}")
            }
        }
    }
    
    // ==================== Actions ====================
    
    /**
     * Send a text message
     */
    fun sendText(text: String) {
        if (text.isBlank()) return
        
        viewModelScope.launch {
            log("Sending text: $text")
            val message = Message.text(text)
            val success = bleManager.sendMessage(message)
            
            if (success) {
                log("SENT: TEXT | $text")
            } else {
                log("ERROR: Failed to send text")
            }
        }
    }
    
    /**
     * Send a command code
     */
    fun sendCommand(command: CommandCode) {
        viewModelScope.launch {
            log("Sending command: ${command.code}")
            val message = Message.command(command.code)
            val success = bleManager.sendMessage(message)
            
            if (success) {
                log("SENT: COMMAND | ${command.code}")
            } else {
                log("ERROR: Failed to send command")
            }
        }
    }
    
    /**
     * Clear message log
     */
    fun clearLog() {
        _messageLog.value = emptyList()
    }
    
    // ==================== Private Methods ====================
    
    /**
     * Add an entry to the message log with timestamp
     */
    private fun log(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        _messageLog.update { it + "[$timestamp] $message" }
    }
}
