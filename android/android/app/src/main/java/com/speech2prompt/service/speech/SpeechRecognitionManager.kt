package com.speech2prompt.service.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.speech2prompt.domain.model.CommandCode
import com.speech2prompt.domain.model.CommandParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Manages Android SpeechRecognizer with state machine, error recovery, and auto-restart.
 * 
 * Features:
 * - State machine: IDLE → STARTING → LISTENING → STOPPING
 * - Auto-restart on transient errors
 * - Exponential backoff on repeated failures
 * - Watchdog timer for stuck states (20s timeout)
 * - Emits state via StateFlow
 * - Emits results via SharedFlow
 */
@Singleton
class SpeechRecognitionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val errorHandler: SpeechErrorHandler
) {
    companion object {
        private const val TAG = "SpeechRecognitionMgr"
        
        // Watchdog configuration
        private val WATCHDOG_INTERVAL = 5.seconds
        private val STUCK_STATE_TIMEOUT = 10.seconds
        private val NO_RESULTS_TIMEOUT = 20.seconds
        
        // Error recovery configuration
        private const val MAX_CONSECUTIVE_ERRORS = 5
        
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
    
    // Flag to prevent auto-restart when stop was explicitly requested
    @Volatile
    private var stopRequested = false
    
    // Watchdog
    private var watchdogJob: Job? = null
    private var lastSuccessfulListening: Long? = null
    private var lastStateChange: Long = System.currentTimeMillis()
    
    // Configuration
    private var pauseFor: Duration = DEFAULT_PAUSE_FOR
    private var listenFor: Duration = DEFAULT_LISTEN_FOR
    private var autoRestart: Boolean = true
    
    // ==================== Public State Flows ====================
    
    private val _recognizerStateFlow = MutableStateFlow(RecognizerState.IDLE)
    val recognizerStateFlow: StateFlow<RecognizerState> = _recognizerStateFlow.asStateFlow()
    
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()
    
    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()
    
    private val _currentText = MutableStateFlow("")
    val currentText: StateFlow<String> = _currentText.asStateFlow()
    
    private val _soundLevel = MutableStateFlow(0f)
    val soundLevel: StateFlow<Float> = _soundLevel.asStateFlow()
    
    private val _selectedLocale = MutableStateFlow("cs-CZ")
    val selectedLocale: StateFlow<String> = _selectedLocale.asStateFlow()
    
    private val _availableLocales = MutableStateFlow<List<Locale>>(emptyList())
    val availableLocales: StateFlow<List<Locale>> = _availableLocales.asStateFlow()
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()
    
    // SharedFlows for events
    private val _recognizedText = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val recognizedText: SharedFlow<String> = _recognizedText.asSharedFlow()
    
    private val _recognizedCommand = MutableSharedFlow<CommandCode>(extraBufferCapacity = 10)
    val recognizedCommand: SharedFlow<CommandCode> = _recognizedCommand.asSharedFlow()
    
    private val _partialResults = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val partialResults: SharedFlow<String> = _partialResults.asSharedFlow()
    
    // ==================== Lifecycle Methods ====================
    
    /**
     * Check if microphone permission is granted.
     */
    fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Initialize speech recognition service.
     * Must be called before any other methods.
     * @return true if initialization successful
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.Main) {
        Log.d(TAG, "Initializing speech recognition manager")
        
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
            Log.d(TAG, "Speech recognition manager initialized successfully")
            return@withContext true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize speech recognition manager", e)
            _errorMessage.value = "Failed to initialize: ${e.message}"
            return@withContext false
        }
    }
    
    /**
     * Start listening for speech.
     * @return true if started successfully, false if permission not granted
     */
    suspend fun startListening(): Boolean {
        Log.d(TAG, "startListening() called, current state: $recognizerState")
        
        // Clear the stop flag when explicitly starting
        stopRequested = false
        
        if (!_isInitialized.value) {
            Log.w(TAG, "Cannot start - not initialized")
            return false
        }
        
        // Check microphone permission before starting
        if (!hasMicrophonePermission()) {
            Log.w(TAG, "Cannot start - microphone permission not granted")
            _errorMessage.value = "Microphone permission required"
            return false
        }
        
        if (_isPaused.value) {
            Log.d(TAG, "Resuming from paused state")
            _isPaused.value = false
        }
        
        synchronized(stateLock) {
            if (!recognizerState.canStart()) {
                Log.w(TAG, "Cannot start from state: $recognizerState")
                return false
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
                return@withContext
            }
        }
        return true
    }
    
    /**
     * Stop listening for speech.
     * Sets a flag to prevent auto-restart.
     */
    suspend fun stopListening() {
        Log.d(TAG, "stopListening() called, current state: $recognizerState")
        
        // Set flag to prevent auto-restart from onResults/onError callbacks
        stopRequested = true
        
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
     * Pause listening (can be resumed).
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
        Log.d(TAG, "Destroying speech recognition manager")
        
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
        _recognizerStateFlow.value = newState
        lastStateChange = System.currentTimeMillis()
        Log.d(TAG, "State transition: $oldState -> $newState")
    }
    
    private fun updateListeningState(listening: Boolean) {
        if (_isListening.value != listening) {
            _isListening.value = listening
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
            serviceScope.launch {
                _partialResults.emit(text)
            }
        }
    }
    
    private fun processFinalResult(text: String) {
        Log.d(TAG, "Final result: $text")
        
        // Reset error counter on successful recognition
        consecutiveErrors = 0
        lastSuccessfulListening = System.currentTimeMillis()
        
        // Parse text for commands
        val processed = CommandParser.processText(text)
        
        if (processed.hasCommand) {
            Log.d(TAG, "Command detected: ${processed.command}")
            serviceScope.launch {
                processed.command?.let { _recognizedCommand.emit(it) }
            }
        }
        
        // Send any text content
        if (processed.hasText) {
            val textContent = processed.combinedText ?: ""
            _currentText.value = textContent
            serviceScope.launch {
                _recognizedText.emit(textContent)
            }
        } else {
            _currentText.value = ""
        }
    }
    
    private fun handleError(errorCode: Int) {
        val classification = errorHandler.classify(errorCode)
        Log.w(TAG, "Speech error: ${classification.message} (code: $errorCode, transient: ${classification.isTransient})")
        
        if (classification.isTransient) {
            // Transient errors - restart without user notification
            scheduleRestart(wasSuccessful = false, delay = classification.suggestedRetryDelay)
        } else {
            // Real errors - notify user and apply backoff
            consecutiveErrors++
            _errorMessage.value = classification.message
            
            if (consecutiveErrors < MAX_CONSECUTIVE_ERRORS && autoRestart) {
                val backoffDelay = errorHandler.calculateBackoff(consecutiveErrors)
                Log.d(TAG, "Scheduling restart with backoff: $backoffDelay")
                scheduleRestart(wasSuccessful = false, delay = backoffDelay)
            } else {
                Log.e(TAG, "Max consecutive errors reached, stopping")
                serviceScope.launch { stopListening() }
            }
        }
    }
    
    private fun scheduleRestart(wasSuccessful: Boolean, delay: Duration = Duration.ZERO) {
        if (_isPaused.value || !autoRestart || stopRequested) {
            Log.d(TAG, "Restart skipped: paused=${_isPaused.value}, autoRestart=$autoRestart, stopRequested=$stopRequested")
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
            
            // Re-check stopRequested after delay in case stop was called during wait
            if (!_isPaused.value && autoRestart && !stopRequested) {
                Log.d(TAG, "Auto-restarting recognition")
                transitionState(RecognizerState.IDLE)
                startListening()
            } else {
                Log.d(TAG, "Restart cancelled: paused=${_isPaused.value}, autoRestart=$autoRestart, stopRequested=$stopRequested")
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
            if (timeSinceSuccess > NO_RESULTS_TIMEOUT.inWholeMilliseconds) {
                Log.w(TAG, "Watchdog: No successful recognition for ${timeSinceSuccess}ms, restarting")
                serviceScope.launch { forceFullRestart() }
            }
        }
    }
    
    private suspend fun forceFullRestart() {
        Log.d(TAG, "Force full restart initiated")
        
        // Don't restart if stop was explicitly requested
        if (stopRequested) {
            Log.d(TAG, "Force restart skipped - stop was requested")
            return
        }
        
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
                
                // Restart if needed (and stop wasn't requested)
                if (!_isPaused.value && autoRestart && !stopRequested) {
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
        locales.add(Locale("cs", "CZ"))
        
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
