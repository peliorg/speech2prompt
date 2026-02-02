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
import kotlin.time.Duration.Companion.minutes

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
        
        // Segmented session memory management
        // After MAX_SEGMENTS, perform a hard restart to clear memory
        private const val MAX_SEGMENTS_BEFORE_RESTART = 100
        // Safety delay during hard restart to allow OS cleanup
        private const val HARD_RESTART_SAFETY_DELAY_MS = 100L
        // Time-based hard restart threshold for multi-hour sessions
        private val FULL_RESTART_TIME_THRESHOLD = 30.minutes
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
    
    // Segmented session tracking for memory management
    private var segmentCount = 0
    private var lastFullRestartTime: Long = System.currentTimeMillis()
    
    // Flag to prevent restart when stop was explicitly requested
    @Volatile
    private var stopRequested = false
    
    // Flag to indicate we're in the middle of a scheduled restart (memory management)
    @Volatile
    private var isRestarting = false
    
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
     * @param isRestart true if this is an auto-restart (keeps UI stable)
     * @return true if started successfully, false if permission not granted
     */
    suspend fun startListening(isRestart: Boolean = false): Boolean {
        Log.d(TAG, "startListening() called, current state: $recognizerState, isRestart: $isRestart")
        
        // Clear the stop flag when explicitly starting (not on restart)
        if (!isRestart) {
            stopRequested = false
            isRestarting = false
        }
        
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
                _errorMessage.value = "Failed to start: ${e.message}"
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
        
        stopRequested = true
        isRestarting = false
        
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
            _soundLevel.value = 0f
            
            // Reset segment count on explicit stop
            segmentCount = 0
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
                isRestarting = true
                stopRequested = false
                
                withContext(Dispatchers.Main) {
                    try {
                        speechRecognizer?.stopListening()
                        speechRecognizer?.cancel()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error stopping for locale change", e)
                    }
                }
                transitionState(RecognizerState.IDLE)
                
                delay(100.milliseconds)
                startListening(isRestart = true)
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
            
            // Enable segmented session for true continuous recognition (API 33+)
            // Recognition continues until explicitly stopped - no auto-termination on silence
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, true)
            
            // Configure silence detection for segment boundaries
            // These control when a "segment" ends, not when recognition stops
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, pauseFor.inWholeMilliseconds)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, pauseFor.inWholeMilliseconds)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
        }
    }
    
    private fun createRecognitionListener(): RecognitionListener {
        return SegmentedSpeechListener()
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
        }
        // Note: We intentionally do NOT clear _currentText when there's no text.
        // This prevents UI flickering when speech recognition auto-restarts.
        // The currentText will be cleared when a new session starts or when
        // the user explicitly stops listening.
    }
    
    /**
     * Schedule a restart after error or unexpected session end.
     * With segmented sessions, restarts are only needed for error recovery,
     * not for continuous listening (which is handled by the session itself).
     */
    private fun scheduleRestart(delay: Duration = Duration.ZERO) {
        if (_isPaused.value || !autoRestart || stopRequested) {
            Log.d(TAG, "Restart skipped: paused=${_isPaused.value}, autoRestart=$autoRestart, stopRequested=$stopRequested")
            isRestarting = false
            updateListeningState(false)
            _soundLevel.value = 0f
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
            
            if (!_isPaused.value && autoRestart && !stopRequested) {
                Log.d(TAG, "Restarting segmented session")
                transitionState(RecognizerState.IDLE)
                startListening(isRestart = true)
            } else {
                Log.d(TAG, "Restart cancelled")
                isRestarting = false
                updateListeningState(false)
                _soundLevel.value = 0f
            }
        }
    }
    
    private fun cancelScheduledRestart() {
        restartJob?.cancel()
        restartJob = null
        restartScheduled = false
        isRestarting = false
    }
    
    /**
     * Determine whether a hard restart is needed for memory management.
     * With segmented sessions, this is based on:
     * - Segment count threshold (MAX_SEGMENTS_BEFORE_RESTART)
     * - Time since last full restart (FULL_RESTART_TIME_THRESHOLD)
     */
    private fun shouldPerformHardRestart(): Boolean {
        // Segment count threshold
        if (segmentCount >= MAX_SEGMENTS_BEFORE_RESTART) {
            Log.d(TAG, "Hard restart needed: segment limit reached ($segmentCount >= $MAX_SEGMENTS_BEFORE_RESTART)")
            return true
        }
        
        // Time-based threshold for multi-hour sessions
        val timeSinceLastFullRestart = System.currentTimeMillis() - lastFullRestartTime
        if (timeSinceLastFullRestart > FULL_RESTART_TIME_THRESHOLD.inWholeMilliseconds) {
            Log.d(TAG, "Hard restart needed: time threshold exceeded (${timeSinceLastFullRestart}ms)")
            return true
        }
        
        return false
    }
    
    /**
     * Schedule a hard restart for memory management.
     * This stops the current session and recreates the recognizer.
     */
    private fun scheduleHardRestart() {
        if (_isPaused.value || stopRequested) {
            return
        }
        
        restartJob?.cancel()
        restartJob = serviceScope.launch {
            isRestarting = true
            forceFullRestart()
        }
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
    
    /**
     * Force a full restart by destroying and recreating the recognizer.
     * This clears any accumulated memory and resets all counters.
     */
    private suspend fun forceFullRestart() {
        Log.d(TAG, "Force full restart initiated (segment count was: $segmentCount)")
        
        if (stopRequested) {
            Log.d(TAG, "Force restart skipped - stop was requested")
            isRestarting = false
            updateListeningState(false)
            _soundLevel.value = 0f
            return
        }
        
        val wasListening = _isListening.value
        
        withContext(Dispatchers.Main) {
            try {
                // Cancel and destroy current recognizer
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
                
                // Safety delay for OS cleanup
                delay(HARD_RESTART_SAFETY_DELAY_MS)
                
                // Create new recognizer
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(createRecognitionListener())
                }
                
                // Reset counters for the new recognizer instance
                segmentCount = 0
                lastFullRestartTime = System.currentTimeMillis()
                consecutiveErrors = 0
                Log.d(TAG, "Counters reset for new recognizer instance")
                
                transitionState(RecognizerState.IDLE)
                
                if (!_isPaused.value && autoRestart && !stopRequested) {
                    startListening(isRestart = wasListening)
                } else {
                    isRestarting = false
                    updateListeningState(false)
                    _soundLevel.value = 0f
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during force restart", e)
                _errorMessage.value = "Failed to restart: ${e.message}"
                isRestarting = false
                updateListeningState(false)
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
    
    /**
     * Speech listener with segmented session support.
     * 
     * With EXTRA_SEGMENTED_SESSION enabled:
     * - onSegmentResults() is called for each segment (replaces onResults() for segments)
     * - onEndOfSegmentedSession() is called when recognition ends (stop requested or error)
     * - Recognition continues automatically between segments (no restart needed)
     * - onResults() may still be called in some cases (device-dependent)
     */
    private inner class SegmentedSpeechListener : RecognitionListener {
        
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "onReadyForSpeech")
            transitionState(RecognizerState.LISTENING)
            updateListeningState(true)
            isRestarting = false
            lastSuccessfulListening = System.currentTimeMillis()
            _errorMessage.value = null
        }
        
        override fun onBeginningOfSpeech() {
            Log.d(TAG, "onBeginningOfSpeech")
        }
        
        override fun onRmsChanged(rmsdB: Float) {
            // Convert dB to 0-1 range (rmsdB typically ranges from -2 to 10)
            val normalized = ((rmsdB + 2) / 12).coerceIn(0f, 1f)
            _soundLevel.value = normalized
        }
        
        override fun onBufferReceived(buffer: ByteArray?) {
            // Not typically used
        }
        
        override fun onEndOfSpeech() {
            // With segmented sessions, this is called at the end of each segment
            // Recognition continues automatically - no action needed
            Log.d(TAG, "onEndOfSpeech (segment boundary)")
        }
        
        override fun onError(error: Int) {
            Log.w(TAG, "onError: $error")
            
            val classification = errorHandler.classify(error)
            
            // With segmented sessions, most errors end the session
            // We need to restart if autoRestart is enabled
            transitionState(RecognizerState.IDLE)
            
            if (classification.isTransient && !stopRequested && autoRestart) {
                // Transient error during segmented session - restart
                Log.d(TAG, "Transient error in segmented session, will restart")
                isRestarting = true
                scheduleRestart(delay = classification.suggestedRetryDelay)
            } else if (!classification.isTransient) {
                // Real error - notify user
                consecutiveErrors++
                _errorMessage.value = classification.message
                
                if (consecutiveErrors < MAX_CONSECUTIVE_ERRORS && autoRestart && !stopRequested) {
                    val backoffDelay = errorHandler.calculateBackoff(consecutiveErrors)
                    Log.d(TAG, "Error with backoff restart: $backoffDelay")
                    isRestarting = true
                    scheduleRestart(delay = backoffDelay)
                } else {
                    Log.e(TAG, "Max consecutive errors or stop requested, stopping")
                    updateListeningState(false)
                    _soundLevel.value = 0f
                }
            } else {
                // Stop was requested
                updateListeningState(false)
                _soundLevel.value = 0f
            }
        }
        
        /**
         * Called when a segment completes in segmented session mode.
         * Recognition continues automatically after this.
         */
        override fun onSegmentResults(results: Bundle) {
            Log.d(TAG, "onSegmentResults (segment #$segmentCount)")
            handleResult(results, isFinal = true)
            
            // Increment segment counter for memory management
            segmentCount++
            consecutiveErrors = 0  // Reset error counter on successful segment
            lastSuccessfulListening = System.currentTimeMillis()
            
            // Check if we need a hard restart for memory management
            if (shouldPerformHardRestart()) {
                Log.d(TAG, "Scheduling hard restart for memory management (segments: $segmentCount)")
                scheduleHardRestart()
            }
            
            // Note: Recognition continues automatically with segmented sessions
            // No restart needed here
        }
        
        /**
         * Called when the segmented session ends.
         * This happens when stopListening() is called or on certain errors.
         */
        override fun onEndOfSegmentedSession() {
            Log.d(TAG, "onEndOfSegmentedSession (total segments: $segmentCount)")
            transitionState(RecognizerState.IDLE)
            
            if (!stopRequested && autoRestart && !isRestarting) {
                // Session ended unexpectedly, restart
                Log.d(TAG, "Segmented session ended unexpectedly, will restart")
                isRestarting = true
                scheduleRestart(delay = 100.milliseconds)
            } else {
                updateListeningState(false)
                _soundLevel.value = 0f
            }
        }
        
        /**
         * Legacy callback - may still be called on some devices.
         * Treat as segment result for compatibility.
         */
        override fun onResults(results: Bundle?) {
            Log.d(TAG, "onResults (legacy callback)")
            results?.let { handleResult(it, isFinal = true) }
            
            // In segmented mode, this shouldn't normally be called
            // but handle it gracefully if it is
            if (!stopRequested && autoRestart) {
                transitionState(RecognizerState.IDLE)
                isRestarting = true
                scheduleRestart(delay = Duration.ZERO)
            }
        }
        
        override fun onPartialResults(partialResults: Bundle?) {
            handleResult(partialResults, isFinal = false)
        }
        
        override fun onEvent(eventType: Int, params: Bundle?) {
            Log.d(TAG, "onEvent: $eventType")
        }
    }
}
