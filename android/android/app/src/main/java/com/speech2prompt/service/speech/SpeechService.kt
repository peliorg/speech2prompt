package com.speech2prompt.service.speech

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.speech2prompt.R
import com.speech2prompt.MainActivity
import com.speech2prompt.service.ble.BleManager
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Foreground service for continuous speech recognition.
 * 
 * Features:
 * - Runs as foreground service with notification
 * - Integrates SpeechRecognitionManager
 * - Integrates with BLE manager for message transmission
 * - Handles service commands (START, STOP, PAUSE, RESUME)
 * - Automatic cleanup on service destruction
 * 
 * Note: Manual dependency injection used instead of Hilt due to Service limitations
 */
class SpeechService : Service() {
    
    /**
     * Hilt entry point for accessing BleManager from non-Hilt injected service
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SpeechServiceEntryPoint {
        fun bleManager(): BleManager
    }
    
    companion object {
        private const val TAG = "SpeechService"
        
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "speech_recognition_channel"
        private const val CHANNEL_NAME = "Speech Recognition"
        
        // Service actions
        const val ACTION_START_LISTENING = "com.speech2prompt.ACTION_START_LISTENING"
        const val ACTION_STOP_LISTENING = "com.speech2prompt.ACTION_STOP_LISTENING"
        const val ACTION_PAUSE_LISTENING = "com.speech2prompt.ACTION_PAUSE_LISTENING"
        const val ACTION_RESUME_LISTENING = "com.speech2prompt.ACTION_RESUME_LISTENING"
        
        /**
         * Start the speech service
         */
        fun start(context: Context) {
            val intent = Intent(context, SpeechService::class.java)
            intent.action = ACTION_START_LISTENING
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        /**
         * Stop the speech service
         */
        fun stop(context: Context) {
            val intent = Intent(context, SpeechService::class.java)
            context.stopService(intent)
        }
    }
    
    private lateinit var speechRecognitionManager: SpeechRecognitionManager
    private lateinit var voiceCommandProcessor: VoiceCommandProcessor
    private lateinit var errorHandler: SpeechErrorHandler
    private lateinit var bleManager: BleManager
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val binder = LocalBinder()
    
    private var isInitialized = false
    
    /**
     * Binder for local service binding
     */
    inner class LocalBinder : Binder() {
        fun getService(): SpeechService = this@SpeechService
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Speech service created")
        
        // Manual dependency injection
        errorHandler = SpeechErrorHandler()
        speechRecognitionManager = SpeechRecognitionManager(applicationContext, errorHandler)
        voiceCommandProcessor = VoiceCommandProcessor()
        
        // Get BleManager from Hilt
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            SpeechServiceEntryPoint::class.java
        )
        bleManager = entryPoint.bleManager()
        
        // Create notification channel
        createNotificationChannel()
        
        // Start as foreground service
        startForeground(NOTIFICATION_ID, createNotification(isListening = false))
        
        // Initialize speech recognition
        serviceScope.launch {
            if (speechRecognitionManager.initialize()) {
                isInitialized = true
                setupFlows()
                Log.d(TAG, "Speech service initialized successfully")
            } else {
                Log.e(TAG, "Failed to initialize speech service")
                stopSelf()
            }
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")
        
        when (intent?.action) {
            ACTION_START_LISTENING -> {
                serviceScope.launch {
                    speechRecognitionManager.startListening()
                }
            }
            ACTION_STOP_LISTENING -> {
                serviceScope.launch {
                    speechRecognitionManager.stopListening()
                }
            }
            ACTION_PAUSE_LISTENING -> {
                serviceScope.launch {
                    speechRecognitionManager.pauseListening()
                }
            }
            ACTION_RESUME_LISTENING -> {
                serviceScope.launch {
                    speechRecognitionManager.resumeListening()
                }
            }
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onDestroy() {
        Log.d(TAG, "Speech service destroyed")
        
        serviceScope.launch {
            speechRecognitionManager.stopListening()
            speechRecognitionManager.destroy()
        }
        
        serviceScope.cancel()
        super.onDestroy()
    }
    
    // ==================== Private Methods ====================
    
    private fun setupFlows() {
        // Listen to speech recognition state changes
        speechRecognitionManager.isListening
            .onEach { isListening ->
                // Update notification
                val notification = createNotification(isListening)
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(NOTIFICATION_ID, notification)
            }
            .launchIn(serviceScope)
        
        // Listen to recognized text
        speechRecognitionManager.recognizedText
            .onEach { text ->
                Log.d(TAG, "Recognized text: $text")
                voiceCommandProcessor.processRecognizedText(text)
            }
            .launchIn(serviceScope)
        
        // Listen to recognized commands
        speechRecognitionManager.recognizedCommand
            .onEach { command ->
                Log.d(TAG, "Recognized command: $command")
                voiceCommandProcessor.processCommand(command)
            }
            .launchIn(serviceScope)
        
        // Connect voiceCommandProcessor text messages to BLE manager
        voiceCommandProcessor.textMessages
            .onEach { message ->
                try {
                    Log.d(TAG, "Sending text message via BLE: ${message.payload}")
                    val success = bleManager.sendMessage(message)
                    if (success) {
                        Log.d(TAG, "Text message sent successfully")
                    } else {
                        Log.w(TAG, "Failed to send text message - may be queued")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending text message: ${e.message}", e)
                }
            }
            .launchIn(serviceScope)
        
        // Connect voiceCommandProcessor command messages to BLE manager
        voiceCommandProcessor.commandMessages
            .onEach { message ->
                try {
                    Log.d(TAG, "Sending command message via BLE: ${message.payload}")
                    val success = bleManager.sendMessage(message)
                    if (success) {
                        Log.d(TAG, "Command message sent successfully")
                    } else {
                        Log.w(TAG, "Failed to send command message - may be queued")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending command message: ${e.message}", e)
                }
            }
            .launchIn(serviceScope)
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing speech recognition"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(isListening: Boolean): Notification {
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Action button for pause/resume
        val actionIntent = if (isListening) {
            Intent(this, SpeechService::class.java).apply {
                action = ACTION_PAUSE_LISTENING
            }
        } else {
            Intent(this, SpeechService::class.java).apply {
                action = ACTION_RESUME_LISTENING
            }
        }
        
        val actionPendingIntent = PendingIntent.getService(
            this,
            1,
            actionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val actionTitle = if (isListening) "Pause" else "Resume"
        val contentText = if (isListening) "Listening for speech..." else "Speech recognition paused"
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Speech2Prompt")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_microphone)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(0, actionTitle, actionPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
