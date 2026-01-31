package com.speech2prompt.presentation.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speech2prompt.domain.model.*
import com.speech2prompt.service.ble.BleManager
import com.speech2prompt.service.speech.SpeechRecognitionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the Home screen
 */
data class HomeUiState(
    val connectionState: BtConnectionState = BtConnectionState.DISCONNECTED,
    val connectedDevice: BleDeviceInfo? = null,
    val isListening: Boolean = false,
    val isPaused: Boolean = false,
    val currentText: String = "",
    val soundLevel: Float = 0f,
    val errorMessage: String? = null,
    val isInitialized: Boolean = false
)

/**
 * One-time events for the Home screen
 */
sealed interface HomeEvent {
    data object MessageSent : HomeEvent
    data class MessageFailed(val reason: String) : HomeEvent
    data class NavigateTo(val route: String) : HomeEvent
    data class ShowSnackbar(val message: String) : HomeEvent
    data object RequestMicrophonePermission : HomeEvent
}

/**
 * ViewModel for the Home screen.
 * Main screen orchestrating speech recognition and BLE communication.
 * 
 * Features:
 * - Speech control (start/stop/pause/resume)
 * - Real-time transcription display
 * - Sound level visualization
 * - Send text and commands via BLE
 * - Connection status monitoring
 * - Permission checking
 * 
 * Follows MVVM + UDF (Unidirectional Data Flow) pattern:
 * - Immutable UI state exposed via StateFlow
 * - One-time events via Channel/Flow
 * - User actions via public methods
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val bleManager: BleManager,
    private val speechRecognitionManager: SpeechRecognitionManager
) : ViewModel() {
    
    companion object {
        private const val TAG = "HomeViewModel"
    }
    
    // ==================== UI State ====================
    
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    
    // ==================== One-Time Events ====================
    
    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events: Flow<HomeEvent> = _events.receiveAsFlow()
    
    // ==================== Initialization ====================
    
    init {
        initializeServices()
        observeConnectionState()
        observeSpeechState()
        observeSpeechResults()
    }
    
    /**
     * Initialize all required services
     */
    private fun initializeServices() {
        viewModelScope.launch {
            val initialized = speechRecognitionManager.initialize()
            _uiState.update { it.copy(isInitialized = initialized) }
        }
    }
    
    /**
     * Observe BLE connection state changes
     */
    private fun observeConnectionState() {
        viewModelScope.launch {
            bleManager.connectionState.collect { state ->
                _uiState.update { 
                    it.copy(connectionState = state) 
                }
            }
        }
        
        viewModelScope.launch {
            bleManager.connectedDevice.collect { device ->
                _uiState.update { 
                    it.copy(connectedDevice = device) 
                }
            }
        }
    }
    
    /**
     * Observe speech recognition state changes
     */
    private fun observeSpeechState() {
        viewModelScope.launch {
            speechRecognitionManager.isListening.collect { listening ->
                _uiState.update { 
                    it.copy(isListening = listening) 
                }
            }
        }
        
        viewModelScope.launch {
            speechRecognitionManager.isPaused.collect { paused ->
                _uiState.update { 
                    it.copy(isPaused = paused) 
                }
            }
        }
        
        viewModelScope.launch {
            speechRecognitionManager.currentText.collect { text ->
                _uiState.update { 
                    it.copy(currentText = text) 
                }
            }
        }
        
        viewModelScope.launch {
            speechRecognitionManager.soundLevel.collect { level ->
                _uiState.update { 
                    it.copy(soundLevel = level) 
                }
            }
        }
        
        viewModelScope.launch {
            speechRecognitionManager.errorMessage.collect { error ->
                _uiState.update { 
                    it.copy(errorMessage = error) 
                }
            }
        }
    }
    
    /**
     * Observe recognized speech and send via BLE
     */
    private fun observeSpeechResults() {
        // Handle recognized text
        viewModelScope.launch {
            speechRecognitionManager.recognizedText.collect { text ->
                if (text.isNotBlank()) {
                    sendText(text)
                }
            }
        }
        
        // Handle recognized commands
        viewModelScope.launch {
            speechRecognitionManager.recognizedCommand.collect { command ->
                sendCommand(command)
            }
        }
    }
    
    // ==================== Speech Control Actions ====================
    
    /**
     * Start listening for speech
     */
    fun startListening() {
        viewModelScope.launch {
            if (!canListen()) {
                _events.send(HomeEvent.ShowSnackbar("Connect to a device first"))
                return@launch
            }
            
            // Check if we have microphone permission
            if (!speechRecognitionManager.hasMicrophonePermission()) {
                _events.send(HomeEvent.RequestMicrophonePermission)
                return@launch
            }
            
            speechRecognitionManager.startListening()
        }
    }
    
    /**
     * Stop listening for speech
     */
    fun stopListening() {
        viewModelScope.launch {
            speechRecognitionManager.stopListening()
        }
    }
    
    /**
     * Pause listening (can be resumed)
     */
    fun pauseListening() {
        viewModelScope.launch {
            speechRecognitionManager.pauseListening()
        }
    }
    
    /**
     * Resume listening from paused state
     */
    fun resumeListening() {
        viewModelScope.launch {
            if (!canListen()) {
                _events.send(HomeEvent.ShowSnackbar("Connect to a device first"))
                return@launch
            }
            
            // Check if we have microphone permission
            if (!speechRecognitionManager.hasMicrophonePermission()) {
                _events.send(HomeEvent.RequestMicrophonePermission)
                return@launch
            }
            
            speechRecognitionManager.resumeListening()
        }
    }
    
    /**
     * Toggle listening state
     */
    fun toggleListening() {
        viewModelScope.launch {
            if (_uiState.value.isListening) {
                stopListening()
            } else {
                startListening()
            }
        }
    }
    
    // ==================== BLE Communication Actions ====================
    
    /**
     * Send text message via BLE
     */
    private suspend fun sendText(text: String) {
        if (!isConnected()) {
            _events.send(HomeEvent.MessageFailed("Not connected"))
            return
        }
        
        val message = Message.text(text)
        val success = bleManager.sendMessage(message)
        
        if (success) {
            _events.send(HomeEvent.MessageSent)
        } else {
            _events.send(HomeEvent.MessageFailed("Failed to send message"))
        }
    }
    
    /**
     * Send command via BLE
     */
    private suspend fun sendCommand(command: CommandCode) {
        if (!isConnected()) {
            _events.send(HomeEvent.MessageFailed("Not connected"))
            return
        }
        
        val message = Message.command(command.code)
        val success = bleManager.sendMessage(message)
        
        if (success) {
            _events.send(HomeEvent.MessageSent)
        } else {
            _events.send(HomeEvent.MessageFailed("Failed to send command"))
        }
    }
    
    /**
     * Manually send text (for testing/debug)
     */
    fun sendManualText(text: String) {
        viewModelScope.launch {
            sendText(text)
        }
    }
    
    // ==================== Navigation Actions ====================
    
    /**
     * Navigate to connection screen
     */
    fun navigateToConnection() {
        viewModelScope.launch {
            _events.send(HomeEvent.NavigateTo("connection"))
        }
    }
    
    /**
     * Navigate to settings screen
     */
    fun navigateToSettings() {
        viewModelScope.launch {
            _events.send(HomeEvent.NavigateTo("settings"))
        }
    }
    
    // ==================== Error Handling ====================
    
    /**
     * Clear current error message
     */
    fun clearError() {
        speechRecognitionManager.clearError()
        _uiState.update { it.copy(errorMessage = null) }
    }
    
    // ==================== State Checks ====================
    
    /**
     * Check if speech listening is allowed
     */
    private fun canListen(): Boolean {
        return isConnected() && _uiState.value.isInitialized
    }
    
    /**
     * Check if connected to BLE device
     */
    private fun isConnected(): Boolean {
        return _uiState.value.connectionState == BtConnectionState.CONNECTED
    }
    
    // ==================== Lifecycle ====================
    
    override fun onCleared() {
        super.onCleared()
        // Stop listening when ViewModel is cleared
        viewModelScope.launch {
            speechRecognitionManager.stopListening()
        }
    }
}
