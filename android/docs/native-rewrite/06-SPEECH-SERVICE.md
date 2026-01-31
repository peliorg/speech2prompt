# Phase 6: Speech Recognition Service

## Goal
Implement continuous speech recognition with command detection, error recovery, and auto-restart matching Flutter behavior.

## Tasks

### 6.1 Recognizer State Machine

```kotlin
// service/speech/RecognizerState.kt
package com.example.speech2prompt.service.speech

/**
 * State machine for speech recognizer lifecycle.
 * Prevents invalid state transitions and race conditions.
 */
enum class RecognizerState {
    /** Not listening, ready to start */
    IDLE,
    
    /** Transitioning to listening state */
    STARTING,
    
    /** Actively listening for speech */
    LISTENING,
    
    /** Transitioning to idle state */
    STOPPING;
    
    fun canStart(): Boolean = this == IDLE
    fun canStop(): Boolean = this == LISTENING || this == STARTING
    fun isActive(): Boolean = this == LISTENING || this == STARTING
}
```

---

### 6.2 SpeechService

```kotlin
// service/speech/SpeechService.kt
package com.example.speech2prompt.service.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.speech2prompt.service.command.CommandCode
import com.example.speech2prompt.service.command.CommandDetector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Singleton
class SpeechService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val commandDetector: CommandDetector
) {
    companion object {
        private const val TAG = "SpeechService"
        
        // Watchdog configuration
        private val WATCHDOG_INTERVAL = 5.seconds
        private val STUCK_STATE_TIMEOUT = 10.seconds
        private val NO_LISTENING_TIMEOUT = 20.seconds
        
        // Error recovery configuration
        private const val MAX_CONSECUTIVE_ERRORS = 5
        private val BASE_BACKOFF_DELAY = 1.seconds
        private val MAX_BACKOFF_DELAY = 30.seconds
        
        // Default listening configuration
        private val DEFAULT_PAUSE_FOR = 3.seconds
        private val DEFAULT_LISTEN_FOR = 30.seconds
    }
    
    // Speech recognizer - created lazily on main thread
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    
    // Coroutine scope for background operations
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // State machine
    @Volatile
    private var recognizerState = RecognizerState.IDLE
    private val stateLock = Any()
    
    // Error handling
    private var consecutiveErrors = 0
    private var lastRestartAttempt: Long? = null
    private var restartScheduled = false
    private var restartJob: Job? = null
    
    // Watchdog
    private var watchdogJob: Job? = null
    private var lastSuccessfulListening: Long? = null
    private var lastStateChange: Long = System.currentTimeMillis()
    
    // Configuration
    private var pauseFor: Duration = DEFAULT_PAUSE_FOR
    private var listenFor: Duration = DEFAULT_LISTEN_FOR
    private var autoRestart: Boolean = true
    
    // ==================== Public State Flows ====================
    
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()
    
    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()
    
    private val _currentText = MutableStateFlow("")
    val currentText: StateFlow<String> = _currentText.asStateFlow()
    
    private val _soundLevel = MutableStateFlow(0f)
    val soundLevel: StateFlow<Float> = _soundLevel.asStateFlow()
    
    private val _selectedLocale = MutableStateFlow("en-US")
    val selectedLocale: StateFlow<String> = _selectedLocale.asStateFlow()
    
    private val _availableLocales = MutableStateFlow<List<Locale>>(emptyList())
    val availableLocales: StateFlow<List<Locale>> = _availableLocales.asStateFlow()
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()
    
    // ==================== Callbacks ====================
    
    var onTextRecognized: ((String) -> Unit)? = null
    var onCommandDetected: ((CommandCode) -> Unit)? = null
    var onPartialResult: ((String) -> Unit)? = null
    var onListeningStateChanged: ((Boolean) -> Unit)? = null
    
    // ==================== Lifecycle Methods ====================
    
    /**
     * Initialize speech recognition service.
     * Must be called before any other methods.
     * @return true if initialization successful
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.Main) {
        Log.d(TAG, "Initializing speech service")
        
        if (_isInitialized.value) {
            Log.d(TAG, "Already initialized")
            return@withContext true
        }
        
        // Check if speech recognition is available
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available on this device")
            _errorMessage.value = "Speech recognition not available"
            return@withContext false
        }
        
        try {
            // Create recognizer on main thread
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(createRecognitionListener())
            }
            
            // Query available locales
            loadAvailableLocales()
            
            // Start watchdog
            startWatchdog()
            
            _isInitialized.value = true
            Log.d(TAG, "Speech service initialized successfully")
            return@withContext true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize speech service", e)
            _errorMessage.value = "Failed to initialize: ${e.message}"
            return@withContext false
        }
    }
    
    /**
     * Start listening for speech.
     */
    suspend fun startListening() {
        Log.d(TAG, "startListening() called, current state: $recognizerState")
        
        if (!_isInitialized.value) {
            Log.w(TAG, "Cannot start - not initialized")
            return
        }
        
        if (_isPaused.value) {
            Log.d(TAG, "Resuming from paused state")
            _isPaused.value = false
        }
        
        synchronized(stateLock) {
            if (!recognizerState.canStart()) {
                Log.w(TAG, "Cannot start from state: $recognizerState")
                return
            }
            transitionState(RecognizerState.STARTING)
        }
        
        withContext(Dispatchers.Main) {
            try {
                val intent = createRecognizerIntent()
                speechRecognizer?.startListening(intent)
                Log.d(TAG, "Started listening with locale: ${_selectedLocale.value}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start listening", e)
                transitionState(RecognizerState.IDLE)
                handleError(SpeechRecognizer.ERROR_CLIENT)
            }
        }
    }
    
    /**
     * Stop listening for speech.
     */
    suspend fun stopListening() {
        Log.d(TAG, "stopListening() called, current state: $recognizerState")
        
        cancelScheduledRestart()
        
        synchronized(stateLock) {
            if (!recognizerState.canStop() && recognizerState != RecognizerState.IDLE) {
                Log.w(TAG, "Cannot stop from state: $recognizerState")
                return
            }
            if (recognizerState == RecognizerState.IDLE) {
                updateListeningState(false)
                return
            }
            transitionState(RecognizerState.STOPPING)
        }
        
        withContext(Dispatchers.Main) {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping recognizer", e)
            }
            transitionState(RecognizerState.IDLE)
            updateListeningState(false)
        }
    }
    
    /**
     * Pause listening (can be resumed with voice command).
     */
    suspend fun pauseListening() {
        Log.d(TAG, "pauseListening() called")
        _isPaused.value = true
        stopListening()
    }
    
    /**
     * Resume listening from paused state.
     */
    suspend fun resumeListening() {
        Log.d(TAG, "resumeListening() called")
        if (_isPaused.value) {
            _isPaused.value = false
            startListening()
        }
    }
    
    /**
     * Toggle listening state.
     */
    suspend fun toggleListening() {
        if (_isListening.value || recognizerState.isActive()) {
            stopListening()
        } else {
            startListening()
        }
    }
    
    /**
     * Clean up resources.
     */
    fun destroy() {
        Log.d(TAG, "Destroying speech service")
        
        serviceScope.cancel()
        watchdogJob?.cancel()
        restartJob?.cancel()
        
        mainHandler.post {
            try {
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying recognizer", e)
            }
        }
        
        _isInitialized.value = false
    }
    
    // ==================== Configuration Methods ====================
    
    /**
     * Set the locale for speech recognition.
     */
    fun setLocale(localeId: String) {
        Log.d(TAG, "Setting locale: $localeId")
        _selectedLocale.value = localeId
        
        // If currently listening, restart with new locale
        if (_isListening.value) {
            serviceScope.launch {
                stopListening()
                delay(100.milliseconds)
                startListening()
            }
        }
    }
    
    /**
     * Set pause duration (silence detection).
     */
    fun setPauseFor(duration: Duration) {
        pauseFor = duration
        Log.d(TAG, "Pause duration set to: $duration")
    }
    
    /**
     * Set maximum listen duration per session.
     */
    fun setListenFor(duration: Duration) {
        listenFor = duration
        Log.d(TAG, "Listen duration set to: $duration")
    }
    
    /**
     * Configure multiple settings at once.
     */
    fun configure(
        pauseFor: Duration? = null,
        listenFor: Duration? = null,
        autoRestart: Boolean? = null
    ) {
        pauseFor?.let { this.pauseFor = it }
        listenFor?.let { this.listenFor = it }
        autoRestart?.let { this.autoRestart = it }
        Log.d(TAG, "Configured: pauseFor=${this.pauseFor}, listenFor=${this.listenFor}, autoRestart=${this.autoRestart}")
    }
    
    // ==================== Error Handling ====================
    
    /**
     * Clear current error message.
     */
    fun clearError() {
        _errorMessage.value = null
    }
    
    /**
     * Reset error state and counters.
     */
    fun resetErrorState() {
        consecutiveErrors = 0
        lastRestartAttempt = null
        clearError()
        Log.d(TAG, "Error state reset")
    }
    
    // ==================== Private Methods ====================
    
    private fun transitionState(newState: RecognizerState) {
        val oldState = recognizerState
        recognizerState = newState
        lastStateChange = System.currentTimeMillis()
        Log.d(TAG, "State transition: $oldState -> $newState")
    }
    
    private fun updateListeningState(listening: Boolean) {
        if (_isListening.value != listening) {
            _isListening.value = listening
            onListeningStateChanged?.invoke(listening)
            Log.d(TAG, "Listening state updated: $listening")
        }
    }
    
    private fun createRecognizerIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, _selectedLocale.value)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            
            // Configure silence detection
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, pauseFor.inWholeMilliseconds)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, pauseFor.inWholeMilliseconds)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
        }
    }
    
    private fun createRecognitionListener(): RecognitionListener {
        return SpeechListener()
    }
    
    private fun handleResult(results: Bundle?, isFinal: Boolean) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (matches.isNullOrEmpty()) return
        
        val text = matches[0]
        
        if (isFinal) {
            processFinalResult(text)
        } else {
            _currentText.value = text
            onPartialResult?.invoke(text)
        }
    }
    
    private fun processFinalResult(text: String) {
        Log.d(TAG, "Final result: $text")
        
        // Reset error counter on successful recognition
        consecutiveErrors = 0
        lastSuccessfulListening = System.currentTimeMillis()
        
        // Check for commands first
        val command = commandDetector.detectCommand(text)
        if (command != null) {
            Log.d(TAG, "Command detected: $command")
            handleCommand(command)
            _currentText.value = ""
            return
        }
        
        // Not a command - send as text
        _currentText.value = text
        onTextRecognized?.invoke(text)
    }
    
    private fun handleCommand(command: CommandCode) {
        serviceScope.launch {
            when (command) {
                CommandCode.STOP_LISTENING -> {
                    pauseListening()
                }
                CommandCode.START_LISTENING -> {
                    resumeListening()
                }
                else -> {
                    // Pass other commands to callback
                    onCommandDetected?.invoke(command)
                }
            }
        }
    }
    
    private fun handleError(errorCode: Int) {
        val errorInfo = classifyError(errorCode)
        Log.w(TAG, "Speech error: ${errorInfo.message} (code: $errorCode, transient: ${errorInfo.isTransient})")
        
        if (errorInfo.isTransient) {
            // Transient errors - restart without user notification
            scheduleRestart(wasSuccessful = false, delay = errorInfo.retryDelay)
        } else {
            // Real errors - notify user and apply backoff
            consecutiveErrors++
            _errorMessage.value = errorInfo.message
            
            if (consecutiveErrors < MAX_CONSECUTIVE_ERRORS && autoRestart) {
                val backoffDelay = calculateBackoffDelay()
                Log.d(TAG, "Scheduling restart with backoff: $backoffDelay")
                scheduleRestart(wasSuccessful = false, delay = backoffDelay)
            } else {
                Log.e(TAG, "Max consecutive errors reached, stopping")
                serviceScope.launch { stopListening() }
            }
        }
    }
    
    private data class ErrorInfo(
        val message: String,
        val isTransient: Boolean,
        val retryDelay: Duration
    )
    
    private fun classifyError(errorCode: Int): ErrorInfo {
        return when (errorCode) {
            SpeechRecognizer.ERROR_NO_MATCH -> ErrorInfo(
                message = "No speech detected",
                isTransient = true,
                retryDelay = Duration.ZERO
            )
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ErrorInfo(
                message = "Speech timeout",
                isTransient = true,
                retryDelay = Duration.ZERO
            )
            SpeechRecognizer.ERROR_CLIENT -> ErrorInfo(
                message = "Client error",
                isTransient = true,
                retryDelay = 100.milliseconds
            )
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> ErrorInfo(
                message = "Recognizer busy",
                isTransient = true,
                retryDelay = 1.seconds
            )
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> ErrorInfo(
                message = "Microphone permission denied",
                isTransient = false,
                retryDelay = Duration.ZERO
            )
            SpeechRecognizer.ERROR_AUDIO -> ErrorInfo(
                message = "Audio recording error",
                isTransient = false,
                retryDelay = 2.seconds
            )
            SpeechRecognizer.ERROR_NETWORK -> ErrorInfo(
                message = "Network error",
                isTransient = false,
                retryDelay = 5.seconds
            )
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> ErrorInfo(
                message = "Network timeout",
                isTransient = false,
                retryDelay = 5.seconds
            )
            SpeechRecognizer.ERROR_SERVER -> ErrorInfo(
                message = "Server error",
                isTransient = false,
                retryDelay = 5.seconds
            )
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> ErrorInfo(
                message = "Server disconnected",
                isTransient = false,
                retryDelay = 5.seconds
            )
            else -> ErrorInfo(
                message = "Unknown error ($errorCode)",
                isTransient = false,
                retryDelay = 2.seconds
            )
        }
    }
    
    private fun calculateBackoffDelay(): Duration {
        val multiplier = minOf(consecutiveErrors, 5)
        val delay = BASE_BACKOFF_DELAY * (1 shl (multiplier - 1))
        return minOf(delay, MAX_BACKOFF_DELAY)
    }
    
    private fun scheduleRestart(wasSuccessful: Boolean, delay: Duration = Duration.ZERO) {
        if (_isPaused.value || !autoRestart) {
            Log.d(TAG, "Restart skipped: paused=${_isPaused.value}, autoRestart=$autoRestart")
            return
        }
        
        if (restartScheduled) {
            Log.d(TAG, "Restart already scheduled")
            return
        }
        
        restartScheduled = true
        lastRestartAttempt = System.currentTimeMillis()
        
        restartJob?.cancel()
        restartJob = serviceScope.launch {
            if (delay > Duration.ZERO) {
                Log.d(TAG, "Waiting $delay before restart")
                delay(delay)
            }
            
            restartScheduled = false
            
            if (!_isPaused.value && autoRestart) {
                Log.d(TAG, "Auto-restarting recognition")
                transitionState(RecognizerState.IDLE)
                startListening()
            }
        }
    }
    
    private fun cancelScheduledRestart() {
        restartJob?.cancel()
        restartJob = null
        restartScheduled = false
    }
    
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL)
                checkWatchdog()
            }
        }
    }
    
    private fun checkWatchdog() {
        val now = System.currentTimeMillis()
        val timeSinceStateChange = now - lastStateChange
        
        // Check for stuck in STARTING state
        if (recognizerState == RecognizerState.STARTING && 
            timeSinceStateChange > STUCK_STATE_TIMEOUT.inWholeMilliseconds) {
            Log.w(TAG, "Watchdog: Stuck in STARTING state, forcing restart")
            serviceScope.launch { forceFullRestart() }
            return
        }
        
        // Check for stuck in STOPPING state
        if (recognizerState == RecognizerState.STOPPING && 
            timeSinceStateChange > STUCK_STATE_TIMEOUT.inWholeMilliseconds) {
            Log.w(TAG, "Watchdog: Stuck in STOPPING state, forcing restart")
            serviceScope.launch { forceFullRestart() }
            return
        }
        
        // Check for no successful listening for too long while supposedly listening
        if (_isListening.value && lastSuccessfulListening != null) {
            val timeSinceSuccess = now - (lastSuccessfulListening ?: now)
            if (timeSinceSuccess > NO_LISTENING_TIMEOUT.inWholeMilliseconds) {
                Log.w(TAG, "Watchdog: No successful recognition for ${timeSinceSuccess}ms, restarting")
                serviceScope.launch { forceFullRestart() }
            }
        }
    }
    
    private suspend fun forceFullRestart() {
        Log.d(TAG, "Force full restart initiated")
        
        withContext(Dispatchers.Main) {
            try {
                // Cancel and destroy current recognizer
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
                
                // Create new recognizer
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(createRecognitionListener())
                }
                
                transitionState(RecognizerState.IDLE)
                
                // Restart if needed
                if (!_isPaused.value && autoRestart) {
                    delay(500.milliseconds)
                    startListening()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during force restart", e)
                _errorMessage.value = "Failed to restart: ${e.message}"
            }
        }
    }
    
    private fun loadAvailableLocales() {
        // Get system locales as available options
        val locales = mutableListOf<Locale>()
        
        // Add common locales
        locales.add(Locale.US)
        locales.add(Locale.UK)
        locales.add(Locale.CANADA)
        locales.add(Locale.GERMANY)
        locales.add(Locale.FRANCE)
        locales.add(Locale.ITALY)
        locales.add(Locale.JAPAN)
        locales.add(Locale.KOREA)
        locales.add(Locale.CHINA)
        locales.add(Locale("es", "ES"))
        locales.add(Locale("pt", "BR"))
        locales.add(Locale("ru", "RU"))
        
        _availableLocales.value = locales.distinctBy { it.toLanguageTag() }
    }
    
    // ==================== Recognition Listener ====================
    
    private inner class SpeechListener : RecognitionListener {
        
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "onReadyForSpeech")
            transitionState(RecognizerState.LISTENING)
            updateListeningState(true)
            lastSuccessfulListening = System.currentTimeMillis()
            _errorMessage.value = null
        }
        
        override fun onBeginningOfSpeech() {
            Log.d(TAG, "onBeginningOfSpeech")
        }
        
        override fun onRmsChanged(rmsdB: Float) {
            // Convert dB to 0-1 range
            // rmsdB typically ranges from -2 to 10
            val normalized = ((rmsdB + 2) / 12).coerceIn(0f, 1f)
            _soundLevel.value = normalized
        }
        
        override fun onBufferReceived(buffer: ByteArray?) {
            // Not typically used
        }
        
        override fun onEndOfSpeech() {
            Log.d(TAG, "onEndOfSpeech")
        }
        
        override fun onError(error: Int) {
            Log.w(TAG, "onError: $error")
            transitionState(RecognizerState.IDLE)
            updateListeningState(false)
            _soundLevel.value = 0f
            handleError(error)
        }
        
        override fun onResults(results: Bundle?) {
            Log.d(TAG, "onResults")
            handleResult(results, isFinal = true)
            transitionState(RecognizerState.IDLE)
            
            // Auto-restart for continuous listening
            scheduleRestart(wasSuccessful = true)
        }
        
        override fun onPartialResults(partialResults: Bundle?) {
            handleResult(partialResults, isFinal = false)
        }
        
        override fun onEvent(eventType: Int, params: Bundle?) {
            Log.d(TAG, "onEvent: $eventType")
        }
    }
}
```

---

### 6.3 RecognitionListener Implementation

The `SpeechListener` inner class is implemented above within `SpeechService`. Key behaviors:

```kotlin
// Summary of RecognitionListener callbacks:

override fun onReadyForSpeech(params: Bundle?) {
    // Called when recognizer is ready
    // - Transition to LISTENING state
    // - Update isListening flow
    // - Reset watchdog timer
    // - Clear any error messages
}

override fun onBeginningOfSpeech() {
    // Called when user starts speaking
    // - Mostly informational
}

override fun onRmsChanged(rmsdB: Float) {
    // Called frequently with audio level
    // - Convert dB (-2 to 10) to 0-1 range
    // - Update soundLevel flow for UI visualization
}

override fun onBufferReceived(buffer: ByteArray?) {
    // Raw audio buffer - not typically used
}

override fun onEndOfSpeech() {
    // User stopped speaking
    // - Results will follow
}

override fun onError(error: Int) {
    // Recognition error occurred
    // - Transition to IDLE
    // - Classify error (transient vs real)
    // - Schedule restart with appropriate backoff
}

override fun onResults(results: Bundle?) {
    // Final recognition results
    // - Process text for commands
    // - Notify callbacks
    // - Schedule auto-restart
}

override fun onPartialResults(partialResults: Bundle?) {
    // Interim results during speech
    // - Update currentText flow
    // - Notify partial result callback
}

override fun onEvent(eventType: Int, params: Bundle?) {
    // Additional events - rarely used
}
```

---

### 6.4 Error Handling Strategy

```kotlin
// service/speech/SpeechErrorHandler.kt
package com.example.speech2prompt.service.speech

import android.speech.SpeechRecognizer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Centralized error classification and handling for speech recognition.
 */
object SpeechErrorHandler {
    
    /**
     * Error classification result.
     */
    data class ErrorClassification(
        val code: Int,
        val message: String,
        val isTransient: Boolean,
        val suggestedRetryDelay: Duration,
        val requiresPermissionCheck: Boolean = false,
        val requiresNetworkCheck: Boolean = false
    )
    
    /**
     * Classify an Android speech recognition error code.
     */
    fun classify(errorCode: Int): ErrorClassification {
        return when (errorCode) {
            // ============ Transient Errors ============
            // These are normal operational states, not real errors
            
            SpeechRecognizer.ERROR_NO_MATCH -> ErrorClassification(
                code = errorCode,
                message = "No speech detected",
                isTransient = true,
                suggestedRetryDelay = Duration.ZERO
            )
            
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ErrorClassification(
                code = errorCode,
                message = "Speech timeout - no audio received",
                isTransient = true,
                suggestedRetryDelay = Duration.ZERO
            )
            
            SpeechRecognizer.ERROR_CLIENT -> ErrorClassification(
                code = errorCode,
                message = "Client-side error",
                isTransient = true,
                suggestedRetryDelay = 100.milliseconds
            )
            
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> ErrorClassification(
                code = errorCode,
                message = "Recognizer busy - please wait",
                isTransient = true,
                suggestedRetryDelay = 1.seconds
            )
            
            // ============ Real Errors ============
            // These require user attention or have recovery implications
            
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> ErrorClassification(
                code = errorCode,
                message = "Microphone permission required",
                isTransient = false,
                suggestedRetryDelay = Duration.ZERO, // Don't auto-retry
                requiresPermissionCheck = true
            )
            
            SpeechRecognizer.ERROR_AUDIO -> ErrorClassification(
                code = errorCode,
                message = "Audio recording error - check microphone",
                isTransient = false,
                suggestedRetryDelay = 2.seconds,
                requiresPermissionCheck = true
            )
            
            SpeechRecognizer.ERROR_NETWORK -> ErrorClassification(
                code = errorCode,
                message = "Network unavailable",
                isTransient = false,
                suggestedRetryDelay = 5.seconds,
                requiresNetworkCheck = true
            )
            
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> ErrorClassification(
                code = errorCode,
                message = "Network request timed out",
                isTransient = false,
                suggestedRetryDelay = 5.seconds,
                requiresNetworkCheck = true
            )
            
            SpeechRecognizer.ERROR_SERVER -> ErrorClassification(
                code = errorCode,
                message = "Speech recognition server error",
                isTransient = false,
                suggestedRetryDelay = 5.seconds
            )
            
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> ErrorClassification(
                code = errorCode,
                message = "Disconnected from server",
                isTransient = false,
                suggestedRetryDelay = 5.seconds,
                requiresNetworkCheck = true
            )
            
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> ErrorClassification(
                code = errorCode,
                message = "Too many requests - rate limited",
                isTransient = false,
                suggestedRetryDelay = 30.seconds
            )
            
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> ErrorClassification(
                code = errorCode,
                message = "Selected language not supported",
                isTransient = false,
                suggestedRetryDelay = Duration.ZERO // Don't auto-retry
            )
            
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> ErrorClassification(
                code = errorCode,
                message = "Language temporarily unavailable",
                isTransient = false,
                suggestedRetryDelay = 10.seconds
            )
            
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> ErrorClassification(
                code = errorCode,
                message = "Cannot verify language support",
                isTransient = true,
                suggestedRetryDelay = 2.seconds
            )
            
            else -> ErrorClassification(
                code = errorCode,
                message = "Unknown error (code: $errorCode)",
                isTransient = false,
                suggestedRetryDelay = 2.seconds
            )
        }
    }
    
    /**
     * Get user-friendly error message for display.
     */
    fun getUserMessage(errorCode: Int): String {
        val classification = classify(errorCode)
        return when {
            classification.isTransient -> "" // Don't show transient errors
            classification.requiresPermissionCheck -> "Please grant microphone permission"
            classification.requiresNetworkCheck -> "Check your internet connection"
            else -> classification.message
        }
    }
    
    /**
     * Calculate exponential backoff delay.
     */
    fun calculateBackoff(
        attemptNumber: Int,
        baseDelay: Duration = 1.seconds,
        maxDelay: Duration = 30.seconds
    ): Duration {
        val multiplier = minOf(attemptNumber, 5)
        val delay = baseDelay * (1 shl (multiplier - 1))
        return minOf(delay, maxDelay)
    }
}
```

### Error Code Reference Table

| Error Code | Constant | Behavior | Retry Delay |
|------------|----------|----------|-------------|
| 1 | ERROR_NETWORK_TIMEOUT | Real error - check network | 5s + backoff |
| 2 | ERROR_NETWORK | Real error - check network | 5s + backoff |
| 3 | ERROR_AUDIO | Real error - check mic/permissions | 2s + backoff |
| 4 | ERROR_SERVER | Real error - server issue | 5s + backoff |
| 5 | ERROR_CLIENT | Transient - immediate restart | 100ms |
| 6 | ERROR_SPEECH_TIMEOUT | Transient - no speech heard | 0ms |
| 7 | ERROR_NO_MATCH | Transient - speech not recognized | 0ms |
| 8 | ERROR_RECOGNIZER_BUSY | Transient - wait and retry | 1s |
| 9 | ERROR_INSUFFICIENT_PERMISSIONS | Real error - need permission | No retry |
| 10 | ERROR_TOO_MANY_REQUESTS | Real error - rate limited | 30s |
| 11 | ERROR_SERVER_DISCONNECTED | Real error - reconnect | 5s + backoff |
| 12 | ERROR_LANGUAGE_NOT_SUPPORTED | Real error - change language | No retry |
| 13 | ERROR_LANGUAGE_UNAVAILABLE | Real error - try later | 10s |
| 14 | ERROR_CANNOT_CHECK_SUPPORT | Transient - retry | 2s |

---

### 6.5 Watchdog Timer

```kotlin
// service/speech/SpeechWatchdog.kt
package com.example.speech2prompt.service.speech

import android.util.Log
import kotlinx.coroutines.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Watchdog timer to detect and recover from stuck speech recognition states.
 */
class SpeechWatchdog(
    private val scope: CoroutineScope,
    private val onStuckDetected: suspend (StuckState) -> Unit
) {
    companion object {
        private const val TAG = "SpeechWatchdog"
        
        /** How often to check for stuck states */
        val CHECK_INTERVAL: Duration = 5.seconds
        
        /** Max time allowed in STARTING state */
        val STARTING_TIMEOUT: Duration = 10.seconds
        
        /** Max time allowed in STOPPING state */
        val STOPPING_TIMEOUT: Duration = 10.seconds
        
        /** Max time without successful recognition while listening */
        val NO_RESULTS_TIMEOUT: Duration = 20.seconds
        
        /** Max time between any state changes while active */
        val ACTIVITY_TIMEOUT: Duration = 60.seconds
    }
    
    enum class StuckState {
        STUCK_IN_STARTING,
        STUCK_IN_STOPPING,
        NO_RESULTS_TIMEOUT,
        ACTIVITY_TIMEOUT
    }
    
    private var watchdogJob: Job? = null
    
    // Tracked timestamps
    private var lastStateChange: Long = System.currentTimeMillis()
    private var lastSuccessfulResult: Long? = null
    private var startingStateEnteredAt: Long? = null
    private var stoppingStateEnteredAt: Long? = null
    
    // Current state (updated externally)
    private var currentState: RecognizerState = RecognizerState.IDLE
    private var isListening: Boolean = false
    
    /**
     * Start the watchdog timer.
     */
    fun start() {
        stop()
        Log.d(TAG, "Starting watchdog timer")
        
        watchdogJob = scope.launch {
            while (isActive) {
                delay(CHECK_INTERVAL)
                checkForStuckStates()
            }
        }
    }
    
    /**
     * Stop the watchdog timer.
     */
    fun stop() {
        watchdogJob?.cancel()
        watchdogJob = null
    }
    
    /**
     * Update watchdog with current state.
     */
    fun updateState(state: RecognizerState, listening: Boolean) {
        val now = System.currentTimeMillis()
        
        if (state != currentState) {
            lastStateChange = now
            
            // Track when we enter specific states
            when (state) {
                RecognizerState.STARTING -> startingStateEnteredAt = now
                RecognizerState.STOPPING -> stoppingStateEnteredAt = now
                else -> {
                    startingStateEnteredAt = null
                    stoppingStateEnteredAt = null
                }
            }
        }
        
        currentState = state
        isListening = listening
    }
    
    /**
     * Record a successful recognition result.
     */
    fun recordSuccess() {
        lastSuccessfulResult = System.currentTimeMillis()
    }
    
    /**
     * Reset all timestamps (e.g., after manual restart).
     */
    fun reset() {
        val now = System.currentTimeMillis()
        lastStateChange = now
        lastSuccessfulResult = null
        startingStateEnteredAt = null
        stoppingStateEnteredAt = null
    }
    
    private suspend fun checkForStuckStates() {
        val now = System.currentTimeMillis()
        
        // Check stuck in STARTING
        startingStateEnteredAt?.let { enteredAt ->
            if (currentState == RecognizerState.STARTING) {
                val duration = now - enteredAt
                if (duration > STARTING_TIMEOUT.inWholeMilliseconds) {
                    Log.w(TAG, "Detected stuck in STARTING state for ${duration}ms")
                    onStuckDetected(StuckState.STUCK_IN_STARTING)
                    return
                }
            }
        }
        
        // Check stuck in STOPPING
        stoppingStateEnteredAt?.let { enteredAt ->
            if (currentState == RecognizerState.STOPPING) {
                val duration = now - enteredAt
                if (duration > STOPPING_TIMEOUT.inWholeMilliseconds) {
                    Log.w(TAG, "Detected stuck in STOPPING state for ${duration}ms")
                    onStuckDetected(StuckState.STUCK_IN_STOPPING)
                    return
                }
            }
        }
        
        // Check no results while listening
        if (isListening && currentState == RecognizerState.LISTENING) {
            val lastResult = lastSuccessfulResult
            if (lastResult != null) {
                val timeSinceResult = now - lastResult
                if (timeSinceResult > NO_RESULTS_TIMEOUT.inWholeMilliseconds) {
                    Log.w(TAG, "No results for ${timeSinceResult}ms while listening")
                    onStuckDetected(StuckState.NO_RESULTS_TIMEOUT)
                    return
                }
            }
        }
        
        // Check general activity timeout
        if (currentState.isActive()) {
            val timeSinceActivity = now - lastStateChange
            if (timeSinceActivity > ACTIVITY_TIMEOUT.inWholeMilliseconds) {
                Log.w(TAG, "No state changes for ${timeSinceActivity}ms while active")
                onStuckDetected(StuckState.ACTIVITY_TIMEOUT)
            }
        }
    }
}
```

---

### 6.6 CommandProcessor

```kotlin
// service/speech/CommandProcessor.kt
package com.example.speech2prompt.service.speech

import android.util.Log
import com.example.speech2prompt.data.model.Message
import com.example.speech2prompt.service.command.CommandCode
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.milliseconds

/**
 * Processes recognized speech text and commands.
 * Handles buffering, debouncing, and command history.
 */
class CommandProcessor(
    private val scope: CoroutineScope,
    private val sendMessage: suspend (Message) -> Unit
) {
    companion object {
        private const val TAG = "CommandProcessor"
        private const val MAX_HISTORY_SIZE = 100
        private val SEND_DELAY = 50.milliseconds
    }
    
    // Text buffer for accumulating speech
    private val textBuffer = StringBuilder()
    
    // Debounce job for sending text
    private var sendJob: Job? = null
    
    // Command history for debugging/analytics
    private val _commandHistory = mutableListOf<CommandHistoryEntry>()
    val commandHistory: List<CommandHistoryEntry> get() = _commandHistory.toList()
    
    // Callback for command processing
    var onCommandProcessed: ((CommandCode) -> Unit)? = null
    
    /**
     * Entry in command history.
     */
    data class CommandHistoryEntry(
        val timestamp: Long,
        val type: EntryType,
        val content: String
    ) {
        enum class EntryType { TEXT, COMMAND }
    }
    
    /**
     * Process recognized text.
     * Buffers and debounces before sending.
     */
    fun processText(text: String) {
        Log.d(TAG, "Processing text: $text")
        
        // Cancel any pending send
        sendJob?.cancel()
        
        // Add to buffer with space separator
        if (textBuffer.isNotEmpty() && !textBuffer.endsWith(" ")) {
            textBuffer.append(" ")
        }
        textBuffer.append(text)
        
        // Schedule debounced send
        sendJob = scope.launch {
            delay(SEND_DELAY)
            flushBuffer()
        }
    }
    
    /**
     * Process a detected command.
     */
    fun processCommand(command: CommandCode) {
        Log.d(TAG, "Processing command: $command")
        
        // Flush any pending text first
        flush()
        
        // Add to history
        addToHistory(CommandHistoryEntry.EntryType.COMMAND, command.name)
        
        // Notify callback
        onCommandProcessed?.invoke(command)
    }
    
    /**
     * Immediately flush any buffered text.
     */
    fun flush() {
        sendJob?.cancel()
        sendJob = null
        
        scope.launch {
            flushBuffer()
        }
    }
    
    /**
     * Clear all buffered text without sending.
     */
    fun clear() {
        sendJob?.cancel()
        sendJob = null
        textBuffer.clear()
        Log.d(TAG, "Buffer cleared")
    }
    
    /**
     * Clear command history.
     */
    fun clearHistory() {
        _commandHistory.clear()
    }
    
    /**
     * Clean up resources.
     */
    fun dispose() {
        sendJob?.cancel()
        textBuffer.clear()
        _commandHistory.clear()
    }
    
    private suspend fun flushBuffer() {
        if (textBuffer.isEmpty()) return
        
        val text = textBuffer.toString().trim()
        textBuffer.clear()
        
        if (text.isEmpty()) return
        
        Log.d(TAG, "Flushing text: $text")
        
        // Add to history
        addToHistory(CommandHistoryEntry.EntryType.TEXT, text)
        
        // Create and send message
        val message = Message(
            id = System.currentTimeMillis().toString(),
            content = text,
            timestamp = System.currentTimeMillis(),
            isFromUser = true
        )
        
        try {
            sendMessage(message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
        }
    }
    
    private fun addToHistory(type: CommandHistoryEntry.EntryType, content: String) {
        _commandHistory.add(
            CommandHistoryEntry(
                timestamp = System.currentTimeMillis(),
                type = type,
                content = content
            )
        )
        
        // Trim history if too large
        while (_commandHistory.size > MAX_HISTORY_SIZE) {
            _commandHistory.removeAt(0)
        }
    }
}
```

---

### 6.7 Hilt Module for Speech Service

```kotlin
// di/SpeechModule.kt
package com.example.speech2prompt.di

import android.content.Context
import com.example.speech2prompt.service.command.CommandDetector
import com.example.speech2prompt.service.speech.SpeechService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SpeechModule {
    
    @Provides
    @Singleton
    fun provideCommandDetector(): CommandDetector {
        return CommandDetector()
    }
    
    @Provides
    @Singleton
    fun provideSpeechService(
        @ApplicationContext context: Context,
        commandDetector: CommandDetector
    ): SpeechService {
        return SpeechService(context, commandDetector)
    }
}
```

---

### 6.8 Integration Example

```kotlin
// Example usage in ViewModel
class MainViewModel @Inject constructor(
    private val speechService: SpeechService,
    private val messageRepository: MessageRepository
) : ViewModel() {
    
    private lateinit var commandProcessor: CommandProcessor
    
    init {
        setupSpeechService()
    }
    
    private fun setupSpeechService() {
        // Create command processor
        commandProcessor = CommandProcessor(
            scope = viewModelScope,
            sendMessage = { message ->
                messageRepository.addMessage(message)
            }
        )
        
        // Configure speech service callbacks
        speechService.onTextRecognized = { text ->
            commandProcessor.processText(text)
        }
        
        speechService.onCommandDetected = { command ->
            handleCommand(command)
        }
        
        speechService.onPartialResult = { partial ->
            // Update UI with partial results
            _partialText.value = partial
        }
        
        // Initialize
        viewModelScope.launch {
            speechService.initialize()
        }
    }
    
    private fun handleCommand(command: CommandCode) {
        viewModelScope.launch {
            when (command) {
                CommandCode.SEND_MESSAGE -> {
                    commandProcessor.flush()
                    // Trigger send to Claude
                }
                CommandCode.CLEAR_PROMPT -> {
                    commandProcessor.clear()
                    messageRepository.clearCurrentDraft()
                }
                CommandCode.NEW_CONVERSATION -> {
                    messageRepository.startNewConversation()
                }
                CommandCode.REGENERATE -> {
                    messageRepository.regenerateLastResponse()
                }
                else -> {
                    // Handle other commands
                }
            }
        }
    }
    
    fun startListening() {
        viewModelScope.launch {
            speechService.startListening()
        }
    }
    
    fun stopListening() {
        viewModelScope.launch {
            speechService.stopListening()
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        commandProcessor.dispose()
        speechService.destroy()
    }
}
```

---

## Verification Checklist

- [ ] Speech recognition initializes successfully
  - `SpeechRecognizer.isRecognitionAvailable()` returns true
  - Recognizer created without exceptions
  - `isInitialized` flow emits true

- [ ] Continuous listening with auto-restart after silence
  - Recognition restarts after `ERROR_NO_MATCH`
  - Recognition restarts after `ERROR_SPEECH_TIMEOUT`
  - Recognition restarts after successful result
  - Restart respects `autoRestart` configuration

- [ ] Partial results shown in real-time
  - `onPartialResults` callback fires during speech
  - `currentText` flow updates with partial text
  - `onPartialResult` callback invoked

- [ ] Final results trigger callbacks
  - `onResults` callback fires after speech ends
  - `onTextRecognized` callback invoked with final text
  - Text passed to `CommandProcessor`

- [ ] Voice commands detected correctly (test all 6)
  - "send message" -> `CommandCode.SEND_MESSAGE`
  - "clear prompt" -> `CommandCode.CLEAR_PROMPT`
  - "new conversation" -> `CommandCode.NEW_CONVERSATION`
  - "regenerate" -> `CommandCode.REGENERATE`
  - "stop listening" -> pauses recognition
  - "start listening" -> resumes recognition

- [ ] "stop listening" pauses, "start listening" resumes
  - `isPaused` flow updates correctly
  - Recognition stops on "stop listening"
  - Recognition resumes on "start listening"
  - Auto-restart disabled while paused

- [ ] Sound level updates (0-1 range)
  - `onRmsChanged` callback fires during listening
  - dB values converted to 0-1 range
  - `soundLevel` flow updates smoothly

- [ ] Error recovery with backoff
  - Transient errors restart immediately
  - Real errors apply exponential backoff
  - `consecutiveErrors` tracks failure count
  - Max errors triggers full stop

- [ ] Watchdog detects stuck states
  - Stuck in STARTING detected after 10s
  - Stuck in STOPPING detected after 10s
  - No results timeout detected after 20s
  - Force restart recovers from stuck states

- [ ] Locale switching works
  - `setLocale()` updates `selectedLocale` flow
  - Recognition restarts with new locale
  - `availableLocales` populated on init

---

## Test Commands

```bash
# Run speech service tests
./gradlew :app:testDebugUnitTest --tests "*SpeechService*"

# Run with coverage
./gradlew :app:testDebugUnitTest --tests "*SpeechService*" jacocoTestReport
```

### Manual Testing Script

1. **Basic Recognition**
   - Start listening
   - Speak "hello world"
   - Verify text appears in UI
   - Verify auto-restart after pause

2. **Command Detection**
   - Say "send message" - verify message sent
   - Say "clear prompt" - verify text cleared
   - Say "stop listening" - verify pause
   - Say "start listening" - verify resume

3. **Error Recovery**
   - Enable airplane mode
   - Try to speak
   - Verify error message shown
   - Disable airplane mode
   - Verify recovery

4. **Watchdog**
   - Use debugger to pause in STARTING state
   - Wait 15 seconds
   - Verify watchdog triggers restart

---

## Estimated Time: 2-3 days

| Task | Time |
|------|------|
| State machine & basic service | 4 hours |
| Recognition listener implementation | 3 hours |
| Error handling & classification | 3 hours |
| Watchdog timer | 2 hours |
| Command processor | 2 hours |
| Integration & testing | 4 hours |
| Edge cases & polish | 2 hours |
| **Total** | **~20 hours** |

---

## Dependencies

- Phase 4: Data Layer (Message model)
- Phase 5: Command Detection (CommandDetector, CommandCode)

## Next Phase

- Phase 7: Claude API Integration
