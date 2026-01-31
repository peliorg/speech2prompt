package com.speech2prompt

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp

/**
 * Main Application class for Speech2Prompt.
 * 
 * Annotated with @HiltAndroidApp to enable Hilt dependency injection
 * throughout the application. This serves as the root of the dependency
 * graph and must be declared in AndroidManifest.xml.
 */
@HiltAndroidApp
class Speech2PromptApplication : Application() {

    companion object {
        private const val TAG = "Speech2PromptApp"
    }

    override fun onCreate() {
        super.onCreate()
        
        Log.d(TAG, "Application onCreate - initializing Speech2Prompt")
        
        // Initialize any app-wide configurations here
        initializeLogging()
    }

    private fun initializeLogging() {
        // Configure logging based on build type
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Debug build - verbose logging enabled")
        }
    }
}
