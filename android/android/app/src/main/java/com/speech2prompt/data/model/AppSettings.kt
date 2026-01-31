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
    val reconnectDelayMs: Long = 2000L,
    val maxReconnectAttempts: Int = 3,
    
    // Speech Recognition Settings
    val continuousListening: Boolean = false,
    val partialResults: Boolean = true,
    val     language: String = "cs-CZ",
    val offlineRecognition: Boolean = false,
    
    // UI Settings
    val theme: Theme = Theme.SYSTEM,
    val showNotifications: Boolean = true,
    val keepScreenOn: Boolean = true,
    val hapticFeedback: Boolean = true,
    
    // Advanced Settings
    val encryptMessages: Boolean = true,
    val logVerbose: Boolean = false,
    val heartbeatIntervalMs: Long = 5000L,
    val connectionTimeoutMs: Long = 10000L
) {
    /**
     * Validate settings and return list of validation errors
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        
        if (reconnectDelayMs < 500 || reconnectDelayMs > 30000) {
            errors.add("Reconnect delay must be between 500ms and 30000ms")
        }
        
        if (maxReconnectAttempts < 0 || maxReconnectAttempts > 10) {
            errors.add("Max reconnect attempts must be between 0 and 10")
        }
        
        if (heartbeatIntervalMs < 1000 || heartbeatIntervalMs > 60000) {
            errors.add("Heartbeat interval must be between 1000ms and 60000ms")
        }
        
        if (connectionTimeoutMs < 5000 || connectionTimeoutMs > 60000) {
            errors.add("Connection timeout must be between 5000ms and 60000ms")
        }
        
        return errors
    }
    
    /**
     * Check if settings are valid
     */
    val isValid: Boolean
        get() = validate().isEmpty()
    
    companion object {
        /**
         * Default settings instance
         */
        val DEFAULT = AppSettings()
        
        /**
         * Create settings with performance optimizations
         */
        fun performance(): AppSettings {
            return AppSettings(
                autoReconnect = true,
                reconnectDelayMs = 1000L,
                maxReconnectAttempts = 5,
                continuousListening = false,
                partialResults = false,
                keepScreenOn = false,
                heartbeatIntervalMs = 10000L
            )
        }
        
        /**
         * Create settings for battery saving
         */
        fun batterySaving(): AppSettings {
            return AppSettings(
                autoReconnect = false,
                continuousListening = false,
                partialResults = false,
                keepScreenOn = false,
                showNotifications = false,
                heartbeatIntervalMs = 15000L
            )
        }
    }
}

/**
 * Theme options for the app
 */
@Serializable
enum class Theme {
    LIGHT,
    DARK,
    SYSTEM;
    
    companion object {
        fun fromString(value: String): Theme {
            return entries.find { it.name.equals(value, ignoreCase = true) }
                ?: SYSTEM
        }
    }
}
