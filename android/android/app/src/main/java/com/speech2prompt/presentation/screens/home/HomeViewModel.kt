package com.speech2prompt.presentation.screens.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speech2prompt.domain.model.*
import com.speech2prompt.service.ble.BleManager
import com.speech2prompt.service.speech.SpeechRecognitionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
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
 * - Send individual words via BLE
 * - Connection status monitoring
 * - Permission checking
 * 
 * Word Sending Protocol:
 * - Each word is sent individually as a WORD message
 * - Session ID changes when listening starts (new UUID)
 * - Desktop handles command matching and text assembly
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
    
    // ==================== Word Queue State ====================
    //
    // Simple word-by-word sending.
    // Desktop handles command matching and text assembly.
    //
    
    // Session ID - new UUID each time listening starts
    private var currentSession = UUID.randomUUID().toString()
    
    // Track how many words have been sent from the current accumulated text
    // This allows repeated words (e.g., "the cat sat on the mat" - "the" appears twice)
    // while still avoiding re-sending on recognition restart.
    private var sentWordCount = 0
    
    // Track the last partial result to detect when it's genuinely new speech
    private var lastPartialText = ""
    
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
            // Track previous state properly to avoid race conditions with other StateFlow collectors.
            // We use a local variable that persists across emissions, rather than reading from
            // _uiState which could be modified by other coroutines between our read and update.
            var previousState: BtConnectionState? = null
            
            bleManager.connectionState.collect { state ->
                // Use the tracked previous state (null on first emission)
                val wasConnected = previousState == BtConnectionState.CONNECTED
                val isNowConnected = state == BtConnectionState.CONNECTED
                
                Log.d(TAG, "Connection state change: $previousState -> $state (wasConnected=$wasConnected, isNowConnected=$isNowConnected)")
                
                // Update UI state
                _uiState.update { 
                    it.copy(connectionState = state) 
                }
                
                // Stop listening when connection is lost - no point listening if we can't send words
                // This must happen BEFORE clearing sentWordCount, so we don't lose track of what was sent
                if (wasConnected && !isNowConnected) {
                    Log.d(TAG, "Connection lost (was CONNECTED, now $state) - stopping speech recognition")
                    stopListening()
                    // Also clear word tracking immediately when disconnecting
                    // This ensures clean state even if reconnect happens quickly
                    sentWordCount = 0
                    lastPartialText = ""
                    Log.d(TAG, "Cleared word tracking state on disconnect")
                }
                
                // Clear word tracking state when connection is established/re-established
                // This prevents stale sentWordCount from blocking new words after reconnect
                // Note: This handles the case where we reconnect without going through stopListening
                // (e.g., if disconnect detection was delayed)
                if (isNowConnected && previousState != null && previousState != BtConnectionState.CONNECTED) {
                    Log.d(TAG, "Connection established (was $previousState), clearing word tracking state")
                    sentWordCount = 0
                    lastPartialText = ""
                    // Note: Don't reset session/sequence here - let startListening handle that
                    // This only clears the "already sent" tracking to allow re-sending words
                }
                
                // Update tracked previous state for next emission
                previousState = state
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
     * Observe recognized speech and send words via BLE.
     * 
     * Each word is sent individually with a sequence number.
     * Desktop handles command matching and text assembly.
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
        
        // Note: We ignore recognizedCommand - desktop handles all command matching now
    }
    
    /**
     * Handle partial speech recognition result.
     * 
     * Approach: Split into words, send words at positions we haven't sent yet.
     * We track the count of sent words, not word content, so repeated words work.
     * 
     * Example: User says "the cat sat on the mat"
     * - Partial 1: "the" → 1 word, sentWordCount=0, send "the", sentWordCount=1
     * - Partial 2: "the cat" → 2 words, sentWordCount=1, send "cat", sentWordCount=2
     * - Partial 3: "the cat sat on the mat" → 6 words, sentWordCount=2, send "sat","on","the","mat", sentWordCount=6
     * 
     * Recognition restart handling:
     * - If text no longer starts with what we had before, reset sentWordCount
     * - This handles error 7 (no speech) restarts
     */
    private fun handlePartialResult(text: String) {
        if (!isConnected() || text.isBlank()) return
        
        // Split into words
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        
        // Check if this looks like genuinely new speech (text doesn't start with last partial)
        // This helps detect when user starts a completely new utterance
        val isNewUtterance = lastPartialText.isNotEmpty() && 
            !text.startsWith(lastPartialText.take(lastPartialText.length / 2 + 1))
        
        if (isNewUtterance) {
            // User started speaking something completely different - reset tracking
            Log.d(TAG, "New utterance detected, resetting sent word count. Old: '$lastPartialText', New: '$text'")
            sentWordCount = 0
        }
        
        lastPartialText = text
        
        // Send words at positions we haven't sent yet
        // This allows repeated words like "the" to be sent multiple times
        for (i in sentWordCount until words.size) {
            sendWord(words[i])
        }
        sentWordCount = words.size
    }
    
    /**
     * Handle final speech recognition result.
     * 
     * The final result may differ from partials in important ways:
     * - Words that appeared in partials may be removed (e.g., "Aha" disappears)
     * - New words may be added at the beginning or end (e.g., "vidíš" prepended)
     * - Words may be reordered
     * 
     * Example scenarios that can cause word loss:
     * 1. Partial: "nerozumíš" (1 word) -> Final: "vidíš nerozumíš" (2 words)
     *    - "vidíš" appears at position 0, but sentWordCount=1, so standard loop
     *      only sends position 1 onwards -> "vidíš" never sent!
     * 
     * 2. Partial: "Aha to bylo" (3 words) -> Final: "to bylo Koh" (3 words)
     *    - sentWordCount=3, finalWords.size=3, standard loop sends nothing
     *    - But "Koh" is new and wasn't in partials -> "Koh" never sent!
     * 
     * Fix: Compare final words with the last partial words and send any words
     * from the final that weren't in the partial. This handles:
     * - New words at the beginning (like "vidíš")
     * - New words at the end (like "Koh")
     * - Replaced words anywhere in the text
     */
    private fun handleFinalResult(text: String) {
        if (!isConnected() || text.isBlank()) return
        
        val finalWords = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val partialWords = lastPartialText.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        
        // Find words in final that need to be sent
        // Strategy: Build a "budget" of each word from partials, then find final words
        // that exceed that budget (weren't in partials or appear more times than in partials)
        
        // Count occurrences of each word in partials (these were already sent)
        val partialWordCounts = mutableMapOf<String, Int>()
        for (word in partialWords) {
            partialWordCounts[word] = (partialWordCounts[word] ?: 0) + 1
        }
        
        // Find words in final that weren't fully covered by partials
        val wordsToSend = mutableListOf<String>()
        val usedFromPartial = mutableMapOf<String, Int>()
        
        for (word in finalWords) {
            val available = partialWordCounts[word] ?: 0
            val used = usedFromPartial[word] ?: 0
            
            if (used < available) {
                // This word was in partials (already sent), mark it as used
                usedFromPartial[word] = used + 1
            } else {
                // This word exceeds what was in partials - needs to be sent
                wordsToSend.add(word)
            }
        }
        
        if (wordsToSend.isNotEmpty()) {
            Log.d(TAG, "Final result has ${wordsToSend.size} words not in partial: $wordsToSend (final='$text', partial='$lastPartialText')")
            for (word in wordsToSend) {
                sendWord(word)
            }
        }
        
        // Update sentWordCount to final word count for consistency
        sentWordCount = finalWords.size
        
        // Note: We intentionally do NOT reset sentWordCount here.
        // Android speech recognition auto-restarts and may produce overlapping results.
        // sentWordCount is only reset when:
        // 1. A new session starts (startListening)
        // 2. A genuinely new utterance is detected (in handlePartialResult)
    }
    
    /**
     * Send a single word with retry logic.
     * Retries up to 2 more times if the initial send fails.
     */
    private fun sendWord(word: String) {
        viewModelScope.launch {
            var success = false
            var attempts = 0
            val maxAttempts = 3
            
            while (!success && attempts < maxAttempts) {
                attempts++
                val message = Message.word(word, currentSession)
                success = bleManager.sendMessage(message)
                
                if (success) {
                    Log.d(TAG, "Sent word: '$word' session=$currentSession (attempt $attempts)")
                } else if (attempts < maxAttempts) {
                    Log.w(TAG, "Failed to send word: '$word' (attempt $attempts/$maxAttempts), retrying...")
                    delay(50)
                }
            }
            
            if (!success) {
                Log.e(TAG, "Failed to send word: '$word' after $maxAttempts attempts")
            }
        }
    }
    
    // ==================== Speech Control Actions ====================
    
    /**
     * Start listening for speech.
     * Creates a new session.
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
            
            // New session - reset all word tracking state
            currentSession = UUID.randomUUID().toString()
            sentWordCount = 0
            lastPartialText = ""
            
            Log.d(TAG, "Starting new session: $currentSession")
            
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
     * Manually send text (for testing/debug).
     * Sends as individual words with sequence numbers.
     */
    fun sendManualText(text: String) {
        if (text.isBlank()) return
        
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        for (word in words) {
            sendWord(word)
        }
    }
    
    // ==================== Connection Actions ====================
    
    /**
     * Disconnect from the currently connected device
     */
    fun disconnect() {
        viewModelScope.launch {
            bleManager.disconnect()
            _uiState.update { it.copy(connectionState = BtConnectionState.DISCONNECTED) }
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
