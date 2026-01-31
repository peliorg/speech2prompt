package com.speech2prompt.util

import java.util.UUID

/**
 * Application-wide constants.
 * Contains BLE UUIDs, timeouts, encryption settings, and other configuration values.
 */
object Constants {
    
    // ========================================
    // BLE Service & Characteristic UUIDs
    // ========================================
    
    /**
     * Speech2Prompt BLE service UUID
     * Main service identifier for the app
     */
    val SERVICE_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0")
    
    /**
     * RX characteristic UUID (Android -> Linux)
     * Used for sending data from Android to Linux
     */
    val RX_CHAR_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef1")
    
    /**
     * TX characteristic UUID (Linux -> Android)
     * Used for receiving data from Linux to Android
     */
    val TX_CHAR_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef2")
    
    /**
     * Client Characteristic Configuration Descriptor UUID
     * Standard UUID for enabling notifications
     */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    
    
    // ========================================
    // BLE Connection Settings
    // ========================================
    
    /**
     * BLE scan timeout in milliseconds
     */
    const val BLE_SCAN_TIMEOUT_MS: Long = 10_000L
    
    /**
     * BLE connection timeout in milliseconds
     */
    const val BLE_CONNECTION_TIMEOUT_MS: Long = 10_000L
    
    /**
     * BLE reconnection delay in milliseconds
     */
    const val BLE_RECONNECT_DELAY_MS: Long = 2_000L
    
    /**
     * Maximum number of reconnection attempts
     */
    const val BLE_MAX_RECONNECT_ATTEMPTS: Int = 3
    
    /**
     * BLE operation timeout (for write/read operations)
     */
    const val BLE_OPERATION_TIMEOUT_MS: Long = 5_000L
    
    /**
     * MTU size for BLE communication (maximum transmission unit)
     */
    const val BLE_MTU_SIZE: Int = 512
    
    /**
     * Minimum required MTU size
     */
    const val BLE_MIN_MTU_SIZE: Int = 23
    
    
    // ========================================
    // Protocol & Message Settings
    // ========================================
    
    /**
     * Protocol version number
     */
    const val PROTOCOL_VERSION: Int = 1
    
    /**
     * Heartbeat interval in milliseconds
     */
    const val HEARTBEAT_INTERVAL_MS: Long = 5_000L
    
    /**
     * Message acknowledgment timeout in milliseconds
     */
    const val ACK_TIMEOUT_MS: Long = 3_000L
    
    /**
     * Maximum message payload size in bytes
     */
    const val MAX_MESSAGE_SIZE_BYTES: Int = 4096
    
    /**
     * Maximum chunk size for splitting large messages
     */
    const val MESSAGE_CHUNK_SIZE: Int = 400 // Leave room for protocol overhead
    
    
    // ========================================
    // Encryption Settings
    // ========================================
    
    /**
     * AES key size in bits
     */
    const val AES_KEY_SIZE_BITS: Int = 256
    
    /**
     * AES key size in bytes
     */
    const val AES_KEY_SIZE_BYTES: Int = AES_KEY_SIZE_BITS / 8
    
    /**
     * AES-GCM nonce/IV size in bytes
     */
    const val GCM_NONCE_SIZE_BYTES: Int = 12
    
    /**
     * AES-GCM authentication tag size in bytes
     */
    const val GCM_TAG_SIZE_BYTES: Int = 16
    
    /**
     * Encryption algorithm name
     */
    const val ENCRYPTION_ALGORITHM: String = "AES/GCM/NoPadding"
    
    /**
     * Key derivation algorithm
     */
    const val KEY_DERIVATION_ALGORITHM: String = "PBKDF2WithHmacSHA256"
    
    /**
     * PBKDF2 iteration count
     */
    const val PBKDF2_ITERATIONS: Int = 10_000
    
    
    // ========================================
    // Speech Recognition Settings
    // ========================================
    
    /**
     * Default language for speech recognition
     */
    const val DEFAULT_SPEECH_LANGUAGE: String = "en-US"
    
    /**
     * Speech recognition timeout in milliseconds
     */
    const val SPEECH_TIMEOUT_MS: Long = 10_000L
    
    /**
     * Maximum speech duration in milliseconds
     */
    const val MAX_SPEECH_DURATION_MS: Long = 60_000L
    
    /**
     * Silence timeout for speech recognition in milliseconds
     */
    const val SPEECH_SILENCE_TIMEOUT_MS: Long = 2_000L
    
    
    // ========================================
    // Notification Settings
    // ========================================
    
    /**
     * Notification channel ID for foreground service
     */
    const val NOTIFICATION_CHANNEL_ID: String = "speech2prompt_service"
    
    /**
     * Notification channel name
     */
    const val NOTIFICATION_CHANNEL_NAME: String = "Speech2Prompt Service"
    
    /**
     * Notification ID for foreground service
     */
    const val FOREGROUND_SERVICE_NOTIFICATION_ID: Int = 1001
    
    
    // ========================================
    // Preferences Keys
    // ========================================
    
    /**
     * Shared preferences file name
     */
    const val PREFS_NAME: String = "speech2prompt_prefs"
    
    /**
     * Encrypted preferences file name
     */
    const val ENCRYPTED_PREFS_NAME: String = "speech2prompt_encrypted_prefs"
    
    /**
     * Key for storing paired devices list
     */
    const val PREFS_KEY_PAIRED_DEVICES: String = "paired_devices"
    
    /**
     * Key for storing app settings
     */
    const val PREFS_KEY_APP_SETTINGS: String = "app_settings"
    
    /**
     * Key for storing last connected device address
     */
    const val PREFS_KEY_LAST_DEVICE: String = "last_device_address"
    
    
    // ========================================
    // Validation & Limits
    // ========================================
    
    /**
     * Minimum device name length
     */
    const val MIN_DEVICE_NAME_LENGTH: Int = 1
    
    /**
     * Maximum device name length
     */
    const val MAX_DEVICE_NAME_LENGTH: Int = 50
    
    /**
     * Maximum number of paired devices
     */
    const val MAX_PAIRED_DEVICES: Int = 10
    
    /**
     * BLE address regex pattern
     */
    const val BLE_ADDRESS_PATTERN: String = "^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$"
    
    
    // ========================================
    // Logging Tags
    // ========================================
    
    const val LOG_TAG_BLE: String = "S2P_BLE"
    const val LOG_TAG_SPEECH: String = "S2P_SPEECH"
    const val LOG_TAG_ENCRYPTION: String = "S2P_ENCRYPTION"
    const val LOG_TAG_PROTOCOL: String = "S2P_PROTOCOL"
    const val LOG_TAG_UI: String = "S2P_UI"
    const val LOG_TAG_SERVICE: String = "S2P_SERVICE"
    
    
    // ========================================
    // Error Codes
    // ========================================
    
    const val ERROR_CODE_BLE_NOT_SUPPORTED: Int = 1001
    const val ERROR_CODE_BLE_NOT_ENABLED: Int = 1002
    const val ERROR_CODE_BLE_PERMISSION_DENIED: Int = 1003
    const val ERROR_CODE_BLE_CONNECTION_FAILED: Int = 1004
    const val ERROR_CODE_BLE_DISCONNECTED: Int = 1005
    
    const val ERROR_CODE_SPEECH_NOT_AVAILABLE: Int = 2001
    const val ERROR_CODE_SPEECH_PERMISSION_DENIED: Int = 2002
    const val ERROR_CODE_SPEECH_RECOGNITION_FAILED: Int = 2003
    
    const val ERROR_CODE_ENCRYPTION_FAILED: Int = 3001
    const val ERROR_CODE_DECRYPTION_FAILED: Int = 3002
    const val ERROR_CODE_INVALID_KEY: Int = 3003
    
    const val ERROR_CODE_PAIRING_FAILED: Int = 4001
    const val ERROR_CODE_PAIRING_TIMEOUT: Int = 4002
    const val ERROR_CODE_PAIRING_REJECTED: Int = 4003
    
    const val ERROR_CODE_MESSAGE_SEND_FAILED: Int = 5001
    const val ERROR_CODE_MESSAGE_INVALID: Int = 5002
    const val ERROR_CODE_MESSAGE_TOO_LARGE: Int = 5003
}
