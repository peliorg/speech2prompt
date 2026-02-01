package com.speech2prompt.presentation.screens.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speech2prompt.domain.model.*
import com.speech2prompt.service.ble.BleManager
import com.speech2prompt.service.speech.SpeechRecognitionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        
        // Debounce interval for partial results (ms)
        private const val PARTIAL_DEBOUNCE_MS = 100L
        
        // Minimum number of new characters before sending partial
        private const val MIN_NEW_CHARS = 2
    }
    
    // ==================== UI State ====================
    
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    
    // ==================== One-Time Events ====================
    
    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events: Flow<HomeEvent> = _events.receiveAsFlow()
    
    // ==================== Partial Results Tracking ====================
    
    // Track what text has been sent to avoid duplicates
    private var lastSentText = ""
    
    // Track the last text actually transmitted (updated immediately on send, not after debounce)
    private var lastActuallySentText = ""
    
    // Track all sent segments to detect duplicates when recognizer changes text
    private val sentSegments = mutableSetOf<String>()
    
    // Track text that is pending send (in debounce)
    private var pendingSendText = ""
    
    // Debounce job for partial results
    private var partialDebounceJob: Job? = null
    
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
        // Handle partial results (word-by-word sending)
        viewModelScope.launch {
            speechRecognitionManager.partialResults.collect { text ->
                handlePartialResult(text)
            }
        }
        
        // Handle final recognized text
        viewModelScope.launch {
            speechRecognitionManager.recognizedText.collect { text ->
                if (text.isNotBlank()) {
                    handleFinalResult(text)
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
    
    /**
     * Handle partial speech recognition result.
     * Sends incremental text (only new words) with debouncing.
     */
    private fun handlePartialResult(text: String) {
        if (!isConnected() || text.isBlank()) return
        
        // Check if we have new content beyond what was already sent
        val newText = getNewText(text)
        if (newText.isBlank() || newText.length < MIN_NEW_CHARS) {
            return
        }
        
        // DUPLICATE CHECK: Skip if this exact text was just sent
        // This prevents "nice nice nice" when recognizer keeps returning same partial
        if (newText.trim() == lastActuallySentText.trim()) {
            Log.d(TAG, "Skipping duplicate partial: '$newText'")
            return
        }
        
        // Also check if we've already sent this exact segment
        if (sentSegments.contains(newText.trim())) {
            Log.d(TAG, "Skipping already-sent segment: '$newText'")
            return
        }
        
        // Track what we're about to send (for duplicate detection with final result)
        pendingSendText = text
        
        // Cancel previous debounce job and start a new one
        partialDebounceJob?.cancel()
        partialDebounceJob = viewModelScope.launch {
            delay(PARTIAL_DEBOUNCE_MS)
            
            // Re-check new text after debounce (may have changed)
            val currentNewText = getNewText(text)
            if (currentNewText.isNotBlank() && currentNewText.length >= MIN_NEW_CHARS) {
                // Final duplicate check before sending
                if (currentNewText.trim() != lastActuallySentText.trim() && 
                    !sentSegments.contains(currentNewText.trim())) {
                    Log.d(TAG, "Sending partial: '$currentNewText' (lastSent='$lastSentText')")
                    
                    // Use NonCancellable context to ensure send completes even if parent job is cancelled
                    // This prevents race conditions where rapid partial results cancel in-flight sends
                    withContext(NonCancellable) {
                        sendPartialText(currentNewText)
                    }
                    
                    lastActuallySentText = currentNewText
                    sentSegments.add(currentNewText.trim())
                    lastSentText = text
                    pendingSendText = ""
                } else {
                    Log.d(TAG, "Skipping duplicate after debounce: '$currentNewText'")
                }
            }
        }
    }
    
    /**
     * Handle final speech recognition result.
     * Sends any remaining text that wasn't sent as partial.
     */
    private fun handleFinalResult(text: String) {
        // Cancel any pending partial send
        partialDebounceJob?.cancel()
        partialDebounceJob = null
        
        // Check against both lastSentText and pendingSendText to avoid duplicates
        // If the final text matches what we were about to send (or already sent), skip
        val effectiveLastSent = if (pendingSendText.isNotBlank() && text.trim().startsWith(pendingSendText.trim())) {
            // The pending partial was about to send this same text, treat it as already sent
            pendingSendText
        } else {
            lastSentText
        }
        
        // Get any new text that wasn't sent as partial
        val newText = getNewTextComparedTo(text, effectiveLastSent)
        
        // Also check if the new text (or its significant parts) were already sent
        val filteredNewText = filterAlreadySentSegments(newText)
        
        if (filteredNewText.isNotBlank()) {
            Log.d(TAG, "Sending final: '$filteredNewText' (full='$text', lastSent='$lastSentText', pending='$pendingSendText')")
            viewModelScope.launch {
                sendText(filteredNewText)
            }
        } else {
            Log.d(TAG, "Final result skipped (already sent): '$text'")
        }
        
        // Reset tracking for next speech session
        lastSentText = ""
        lastActuallySentText = ""
        pendingSendText = ""
        sentSegments.clear()
    }
    
    /**
     * Filter out any segments from text that were already sent.
     * This handles cases where the recognizer reorders or includes previously sent text.
     */
    private fun filterAlreadySentSegments(text: String): String {
        if (text.isBlank() || sentSegments.isEmpty()) return text
        
        var result = text.trim()
        
        // Check if the entire text was already sent
        if (sentSegments.contains(result)) {
            return ""
        }
        
        // Check if any sent segment is contained in the new text
        // Remove it if found at the beginning (most common case)
        for (segment in sentSegments) {
            if (result.startsWith(segment)) {
                result = result.substring(segment.length).trimStart()
            }
        }
        
        return result
    }
    
    /**
     * Get the new portion of text that hasn't been sent yet.
     * 
     * Handles the case where partial results build up incrementally:
     * "hello" -> "hello world" -> "hello world how" -> etc.
     */
    private fun getNewText(fullText: String): String {
        return getNewTextComparedTo(fullText, lastSentText)
    }
    
    /**
     * Get the new portion of text compared to a reference (what was already sent).
     * 
     * Handles several cases:
     * 1. Simple append: "hello" -> "hello world" -> returns "world"
     * 2. Exact match: returns ""
     * 3. Recognizer correction: "Pane jo" -> "Planeo" -> uses word-level comparison
     */
    private fun getNewTextComparedTo(fullText: String, sentText: String): String {
        val trimmedFull = fullText.trim()
        val trimmedSent = sentText.trim()
        
        if (trimmedSent.isEmpty()) {
            return trimmedFull
        }
        
        // If text is exactly what we sent, nothing new
        if (trimmedFull == trimmedSent) {
            return ""
        }
        
        // Check if the full text starts with what we already sent (simple append case)
        if (trimmedFull.startsWith(trimmedSent)) {
            return trimmedFull.substring(trimmedSent.length).trimStart()
        }
        
        // Word-level comparison for cases where recognizer modifies earlier text
        // e.g., "speak English A můžu mluvit" sent, then "English A můžu mluvit i česky" comes in
        val fullWords = trimmedFull.split(Regex("\\s+"))
        val sentWords = trimmedSent.split(Regex("\\s+"))
        
        // Find where the sent words appear in full words (allowing for modifications)
        // Strategy: find the longest suffix of sentWords that matches a suffix in the middle of fullWords
        var matchEndIndex = -1
        
        // Try to find where sentWords content ends in fullWords
        for (i in fullWords.indices) {
            // Check if sentWords ends at position i
            val potentialMatchStart = i - sentWords.size + 1
            if (potentialMatchStart >= 0) {
                val subList = fullWords.subList(potentialMatchStart, i + 1)
                if (subList == sentWords) {
                    matchEndIndex = i
                }
            }
        }
        
        // If we found a match, return everything after it
        if (matchEndIndex >= 0 && matchEndIndex < fullWords.size - 1) {
            return fullWords.subList(matchEndIndex + 1, fullWords.size).joinToString(" ")
        }
        
        // Fallback: check if any significant portion of sent text appears in full text
        // This handles cases like "English A můžu mluvit" appearing in the middle
        val sentIndex = trimmedFull.indexOf(trimmedSent)
        if (sentIndex >= 0) {
            val afterSent = trimmedFull.substring(sentIndex + trimmedSent.length).trimStart()
            return afterSent
        }
        
        // Check individual sent segments
        for (segment in sentSegments) {
            val segmentIndex = trimmedFull.indexOf(segment)
            if (segmentIndex >= 0) {
                // Found a sent segment, return text after it (if at reasonable position)
                val afterSegment = trimmedFull.substring(segmentIndex + segment.length).trimStart()
                if (afterSegment.isNotBlank()) {
                    return afterSegment
                }
            }
        }
        
        // Last resort: use common prefix (original logic)
        val commonPrefix = trimmedFull.commonPrefixWith(trimmedSent)
        return if (commonPrefix.isNotEmpty() && trimmedFull.length > commonPrefix.length) {
            trimmedFull.substring(commonPrefix.length).trimStart()
        } else {
            // Completely different - might be a reset
            // But don't send if it's shorter or similar length (likely a correction)
            if (trimmedFull.length > trimmedSent.length + MIN_NEW_CHARS) {
                trimmedFull
            } else {
                ""
            }
        }
    }
    
    /**
     * Send partial text (incremental words during speech).
     * Adds trailing space so words don't concatenate on the receiver.
     */
    private suspend fun sendPartialText(text: String) {
        if (!isConnected()) return
        
        // Add trailing space so words don't run together on receiver
        val textWithSpace = if (text.endsWith(" ")) text else "$text "
        val message = Message.text(textWithSpace)
        val success = bleManager.sendMessage(message)
        
        if (!success) {
            Log.w(TAG, "Failed to send partial text: $text")
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
            
            // Reset partial result tracking for new session
            lastSentText = ""
            lastActuallySentText = ""
            pendingSendText = ""
            sentSegments.clear()
            partialDebounceJob?.cancel()
            partialDebounceJob = null
            
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
     * Send text message via BLE.
     * Adds trailing space so words don't concatenate on the receiver.
     */
    private suspend fun sendText(text: String) {
        if (!isConnected()) {
            _events.send(HomeEvent.MessageFailed("Not connected"))
            return
        }
        
        // Add trailing space so words don't run together on receiver
        val textWithSpace = if (text.endsWith(" ")) text else "$text "
        val message = Message.text(textWithSpace)
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
