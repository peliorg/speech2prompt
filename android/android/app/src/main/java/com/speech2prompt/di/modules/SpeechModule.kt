package com.speech2prompt.di.modules

import android.content.Context
import android.speech.SpeechRecognizer
import com.speech2prompt.service.speech.SpeechErrorHandler
import com.speech2prompt.service.speech.SpeechRecognitionManager
import com.speech2prompt.service.speech.VoiceCommandProcessor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for speech recognition dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object SpeechModule {
    
    /**
     * Provide SpeechErrorHandler singleton.
     */
    @Provides
    @Singleton
    fun provideSpeechErrorHandler(): SpeechErrorHandler {
        return SpeechErrorHandler()
    }
    
    /**
     * Provide SpeechRecognitionManager singleton.
     */
    @Provides
    @Singleton
    fun provideSpeechRecognitionManager(
        @ApplicationContext context: Context,
        errorHandler: SpeechErrorHandler
    ): SpeechRecognitionManager {
        return SpeechRecognitionManager(context, errorHandler)
    }
    
    /**
     * Provide VoiceCommandProcessor singleton.
     */
    @Provides
    @Singleton
    fun provideVoiceCommandProcessor(): VoiceCommandProcessor {
        return VoiceCommandProcessor()
    }
}
