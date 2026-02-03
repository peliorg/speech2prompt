package com.speech2prompt.data.model

import kotlinx.serialization.Serializable

/**
 * Application settings data class.
 * Stores user preferences and configuration.
 */
@Serializable
data class AppSettings(
    // BLE Settings
    val autoReconnect: Boolean = true,
    
    // Speech Recognition Settings
    val partialResults: Boolean = true,
    val language: String = "cs-CZ",
    
    // UI Settings
    val keepScreenOn: Boolean = true
) {
    companion object {
        /**
         * Default settings instance
         */
        val DEFAULT = AppSettings()
    }
}


