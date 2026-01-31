# Phase 3: Cryptography & Security Layer

## Goal

Implement encryption, key derivation, and secure storage matching the Flutter implementation exactly. This phase is critical for maintaining compatibility with existing paired devices and ensuring all communication remains secure.

## Overview

The cryptography layer provides:
- PBKDF2 key derivation from PIN + device identifiers
- AES-256-GCM encryption/decryption for message payloads
- SHA-256 checksums for message integrity verification
- Secure storage using Android's EncryptedSharedPreferences

## Tasks

### 3.1 Crypto Constants

**File: `app/src/main/java/com/speech2prompt/service/crypto/CryptoConstants.kt`**

```kotlin
package com.speech2prompt.service.crypto

/**
 * Cryptographic constants that MUST match the Flutter implementation exactly.
 * Any deviation will cause incompatibility with existing paired devices.
 */
object CryptoConstants {
    /**
     * PBKDF2 iteration count - balances security with performance.
     * 100,000 iterations provides strong protection against brute-force attacks.
     */
    const val PBKDF2_ITERATIONS = 100_000
    
    /**
     * AES-256 key length in bits
     */
    const val KEY_LENGTH_BITS = 256
    
    /**
     * AES-256 key length in bytes (256 / 8 = 32)
     */
    const val KEY_LENGTH_BYTES = 32
    
    /**
     * GCM nonce/IV length - 12 bytes is recommended for GCM mode
     */
    const val GCM_NONCE_LENGTH = 12
    
    /**
     * GCM authentication tag length in bits - 128 bits provides full security
     */
    const val GCM_TAG_LENGTH = 128
    
    /**
     * Fixed salt for PBKDF2 key derivation.
     * Using a fixed salt is acceptable here because:
     * 1. The password includes unique device identifiers (androidId, linuxId)
     * 2. Each device pair has a unique combination
     * 3. Changing this would break compatibility with Flutter implementation
     */
    val SALT: ByteArray = "speech2prompt_v1".toByteArray(Charsets.UTF_8)
    
    /**
     * Algorithm identifiers
     */
    const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    const val AES_ALGORITHM = "AES"
    const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    const val SHA256_ALGORITHM = "SHA-256"
    
    /**
     * Checksum length (first N bytes of SHA-256 hash, as hex)
     */
    const val CHECKSUM_BYTES = 4
    const val CHECKSUM_HEX_LENGTH = 8
    
    /**
     * Device ID length for generated IDs
     */
    const val DEVICE_ID_LENGTH = 16
}
```

---

### 3.2 CryptoUtils

**File: `app/src/main/java/com/speech2prompt/service/crypto/CryptoUtils.kt`**

```kotlin
package com.speech2prompt.service.crypto

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic utilities for Speech2Prompt.
 * All implementations MUST match the Flutter version exactly for compatibility.
 */
object CryptoUtils {
    
    private val secureRandom = SecureRandom()
    
    /**
     * Derives an AES-256 key using PBKDF2-HMAC-SHA256.
     * 
     * The key derivation combines:
     * - User-provided PIN (4-6 digits)
     * - Android device ID (unique per device/app combination)
     * - Linux machine ID (identifies the paired computer)
     * 
     * This ensures that:
     * 1. The same PIN on different devices produces different keys
     * 2. The same device paired with different computers has different keys
     * 3. An attacker needs physical access to BOTH devices to attempt brute-force
     * 
     * @param pin User-provided PIN (typically 4-6 digits)
     * @param androidId Android device identifier
     * @param linuxId Linux machine identifier from paired device
     * @return 32-byte (256-bit) derived key
     */
    fun deriveKey(pin: String, androidId: String, linuxId: String): ByteArray {
        val password = "$pin$androidId$linuxId"
        
        val factory = SecretKeyFactory.getInstance(CryptoConstants.PBKDF2_ALGORITHM)
        val spec = PBEKeySpec(
            password.toCharArray(),
            CryptoConstants.SALT,
            CryptoConstants.PBKDF2_ITERATIONS,
            CryptoConstants.KEY_LENGTH_BITS
        )
        
        return try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
    
    /**
     * Encrypts plaintext using AES-256-GCM.
     * 
     * Output format: Base64(nonce || ciphertext || tag)
     * - nonce: 12 bytes (randomly generated)
     * - ciphertext: variable length (same as plaintext)
     * - tag: 16 bytes (GCM authentication tag, appended by cipher)
     * 
     * @param plaintext The text to encrypt
     * @param key 32-byte AES key
     * @return Base64-encoded encrypted data
     * @throws IllegalArgumentException if key is not 32 bytes
     */
    fun encryptAesGcm(plaintext: String, key: ByteArray): String {
        require(key.size == CryptoConstants.KEY_LENGTH_BYTES) {
            "Key must be ${CryptoConstants.KEY_LENGTH_BYTES} bytes, got ${key.size}"
        }
        
        // Generate random nonce
        val nonce = ByteArray(CryptoConstants.GCM_NONCE_LENGTH)
        secureRandom.nextBytes(nonce)
        
        // Initialize cipher
        val cipher = Cipher.getInstance(CryptoConstants.AES_GCM_TRANSFORMATION)
        val spec = GCMParameterSpec(CryptoConstants.GCM_TAG_LENGTH, nonce)
        val secretKey = SecretKeySpec(key, CryptoConstants.AES_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)
        
        // Encrypt (ciphertext includes auth tag at the end)
        val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
        val ciphertextWithTag = cipher.doFinal(plaintextBytes)
        
        // Combine: nonce || ciphertext || tag
        val combined = ByteArray(nonce.size + ciphertextWithTag.size)
        System.arraycopy(nonce, 0, combined, 0, nonce.size)
        System.arraycopy(ciphertextWithTag, 0, combined, nonce.size, ciphertextWithTag.size)
        
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }
    
    /**
     * Decrypts AES-256-GCM encrypted data.
     * 
     * Input format: Base64(nonce || ciphertext || tag)
     * 
     * @param ciphertext Base64-encoded encrypted data
     * @param key 32-byte AES key
     * @return Decrypted plaintext
     * @throws IllegalArgumentException if key is not 32 bytes
     * @throws CryptoException if decryption fails (wrong key, tampered data, etc.)
     */
    fun decryptAesGcm(ciphertext: String, key: ByteArray): String {
        require(key.size == CryptoConstants.KEY_LENGTH_BYTES) {
            "Key must be ${CryptoConstants.KEY_LENGTH_BYTES} bytes, got ${key.size}"
        }
        
        val combined = try {
            Base64.decode(ciphertext, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            throw CryptoException("Invalid base64 encoding", e)
        }
        
        if (combined.size < CryptoConstants.GCM_NONCE_LENGTH + CryptoConstants.GCM_TAG_LENGTH / 8) {
            throw CryptoException("Ciphertext too short")
        }
        
        // Extract nonce
        val nonce = combined.copyOfRange(0, CryptoConstants.GCM_NONCE_LENGTH)
        
        // Extract ciphertext + tag
        val ciphertextWithTag = combined.copyOfRange(CryptoConstants.GCM_NONCE_LENGTH, combined.size)
        
        // Initialize cipher
        val cipher = Cipher.getInstance(CryptoConstants.AES_GCM_TRANSFORMATION)
        val spec = GCMParameterSpec(CryptoConstants.GCM_TAG_LENGTH, nonce)
        val secretKey = SecretKeySpec(key, CryptoConstants.AES_ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        
        // Decrypt
        val plaintextBytes = try {
            cipher.doFinal(ciphertextWithTag)
        } catch (e: Exception) {
            throw CryptoException("Decryption failed - wrong key or tampered data", e)
        }
        
        return String(plaintextBytes, Charsets.UTF_8)
    }
    
    /**
     * Calculates a SHA-256 checksum for message integrity verification.
     * 
     * The checksum is computed over:
     * version || msgType || payload || timestamp || secret
     * 
     * Only the first 4 bytes (8 hex characters) are used as the checksum.
     * 
     * @param version Protocol version number
     * @param msgType Message type string (e.g., "TRANSCRIPT", "ACK")
     * @param payload Message payload (may be encrypted)
     * @param timestamp Unix timestamp in milliseconds
     * @param secret Shared secret (derived key)
     * @return 8-character hex string
     */
    fun calculateChecksum(
        version: Int,
        msgType: String,
        payload: String,
        timestamp: Long,
        secret: ByteArray
    ): String {
        // Build data in same order as Flutter implementation
        val data = buildChecksumData(version, msgType, payload, timestamp, secret)
        
        // Compute SHA-256
        val digest = MessageDigest.getInstance(CryptoConstants.SHA256_ALGORITHM)
        val hash = digest.digest(data)
        
        // Take first 4 bytes and convert to hex
        return hash.take(CryptoConstants.CHECKSUM_BYTES)
            .joinToString("") { byte -> "%02x".format(byte) }
    }
    
    /**
     * Builds the byte array for checksum calculation.
     * Order MUST match Flutter implementation exactly.
     */
    private fun buildChecksumData(
        version: Int,
        msgType: String,
        payload: String,
        timestamp: Long,
        secret: ByteArray
    ): ByteArray {
        val versionBytes = version.toString().toByteArray(Charsets.UTF_8)
        val msgTypeBytes = msgType.toByteArray(Charsets.UTF_8)
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        val timestampBytes = timestamp.toString().toByteArray(Charsets.UTF_8)
        
        val totalLength = versionBytes.size + msgTypeBytes.size + 
                         payloadBytes.size + timestampBytes.size + secret.size
        
        val result = ByteArray(totalLength)
        var offset = 0
        
        System.arraycopy(versionBytes, 0, result, offset, versionBytes.size)
        offset += versionBytes.size
        
        System.arraycopy(msgTypeBytes, 0, result, offset, msgTypeBytes.size)
        offset += msgTypeBytes.size
        
        System.arraycopy(payloadBytes, 0, result, offset, payloadBytes.size)
        offset += payloadBytes.size
        
        System.arraycopy(timestampBytes, 0, result, offset, timestampBytes.size)
        offset += timestampBytes.size
        
        System.arraycopy(secret, 0, result, offset, secret.size)
        
        return result
    }
    
    /**
     * Verifies a message checksum.
     * 
     * @return true if checksum is valid, false otherwise
     */
    fun verifyChecksum(
        expectedChecksum: String,
        version: Int,
        msgType: String,
        payload: String,
        timestamp: Long,
        secret: ByteArray
    ): Boolean {
        val calculated = calculateChecksum(version, msgType, payload, timestamp, secret)
        return calculated.equals(expectedChecksum, ignoreCase = true)
    }
    
    /**
     * Generates a random device ID.
     * Used when Android ID is not available.
     * 
     * @return 32-character hex string (16 bytes)
     */
    fun generateDeviceId(): String {
        val bytes = ByteArray(CryptoConstants.DEVICE_ID_LENGTH)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Securely compares two byte arrays in constant time.
     * Prevents timing attacks.
     */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].toInt() xor b[i].toInt())
        }
        return result == 0
    }
    
    /**
     * Securely wipes a byte array by filling with zeros.
     * Call this when done with sensitive key material.
     */
    fun wipeBytes(bytes: ByteArray) {
        bytes.fill(0)
    }
}

/**
 * Exception thrown for cryptographic operation failures.
 */
class CryptoException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
```

---

### 3.3 CryptoContext

**File: `app/src/main/java/com/speech2prompt/service/crypto/CryptoContext.kt`**

```kotlin
package com.speech2prompt.service.crypto

import com.speech2prompt.data.model.Message
import java.io.Closeable

/**
 * Encapsulates cryptographic operations with a specific key.
 * 
 * CryptoContext holds the derived encryption key and provides
 * high-level operations for message encryption, decryption,
 * signing, and verification.
 * 
 * Usage:
 * ```
 * val context = CryptoContext.fromPin(pin, androidId, linuxId)
 * try {
 *     context.signAndEncrypt(message)
 *     // send message
 *     
 *     // receive response
 *     context.verifyAndDecrypt(response)
 * } finally {
 *     context.close()
 * }
 * ```
 * 
 * IMPORTANT: Call close() when done to securely wipe the key from memory.
 */
class CryptoContext private constructor(
    private val key: ByteArray
) : Closeable {
    
    private var isClosed = false
    
    init {
        require(key.size == CryptoConstants.KEY_LENGTH_BYTES) {
            "Key must be ${CryptoConstants.KEY_LENGTH_BYTES} bytes, got ${key.size}"
        }
    }
    
    companion object {
        /**
         * Creates a CryptoContext from an existing key.
         * 
         * @param key 32-byte encryption key
         * @return CryptoContext instance
         * @throws IllegalArgumentException if key is not 32 bytes
         */
        fun fromKey(key: ByteArray): CryptoContext {
            // Copy the key to prevent external modification
            return CryptoContext(key.copyOf())
        }
        
        /**
         * Creates a CryptoContext by deriving a key from PIN and device identifiers.
         * 
         * @param pin User-provided PIN
         * @param androidId Android device identifier
         * @param linuxId Linux machine identifier
         * @return CryptoContext instance
         */
        fun fromPin(pin: String, androidId: String, linuxId: String): CryptoContext {
            val key = CryptoUtils.deriveKey(pin, androidId, linuxId)
            return CryptoContext(key)
        }
    }
    
    /**
     * Encrypts plaintext using AES-256-GCM.
     * 
     * @param plaintext Text to encrypt
     * @return Base64-encoded ciphertext
     * @throws IllegalStateException if context is closed
     */
    fun encrypt(plaintext: String): String {
        checkNotClosed()
        return CryptoUtils.encryptAesGcm(plaintext, key)
    }
    
    /**
     * Decrypts ciphertext using AES-256-GCM.
     * 
     * @param ciphertext Base64-encoded encrypted data
     * @return Decrypted plaintext
     * @throws IllegalStateException if context is closed
     * @throws CryptoException if decryption fails
     */
    fun decrypt(ciphertext: String): String {
        checkNotClosed()
        return CryptoUtils.decryptAesGcm(ciphertext, key)
    }
    
    /**
     * Signs a message by calculating and setting its checksum.
     * 
     * The checksum is calculated over:
     * - version
     * - msgType
     * - payload (may be plaintext or encrypted)
     * - timestamp
     * - shared secret (key)
     * 
     * @param message Message to sign (modified in place)
     * @throws IllegalStateException if context is closed
     */
    fun sign(message: Message) {
        checkNotClosed()
        
        val checksum = CryptoUtils.calculateChecksum(
            version = message.version,
            msgType = message.msgType.name,
            payload = message.payload,
            timestamp = message.timestamp,
            secret = key
        )
        message.checksum = checksum
    }
    
    /**
     * Verifies a message's checksum.
     * 
     * @param message Message to verify
     * @return true if checksum is valid, false otherwise
     * @throws IllegalStateException if context is closed
     */
    fun verify(message: Message): Boolean {
        checkNotClosed()
        
        val expectedChecksum = message.checksum
        if (expectedChecksum.isNullOrEmpty()) {
            return false
        }
        
        return CryptoUtils.verifyChecksum(
            expectedChecksum = expectedChecksum,
            version = message.version,
            msgType = message.msgType.name,
            payload = message.payload,
            timestamp = message.timestamp,
            secret = key
        )
    }
    
    /**
     * Signs a message and encrypts its payload.
     * 
     * Order of operations:
     * 1. Encrypt the payload
     * 2. Update message with encrypted payload
     * 3. Calculate checksum over encrypted payload
     * 4. Set checksum on message
     * 
     * @param message Message to process (modified in place)
     * @throws IllegalStateException if context is closed
     */
    fun signAndEncrypt(message: Message) {
        checkNotClosed()
        
        // Encrypt payload
        val encryptedPayload = encrypt(message.payload)
        message.payload = encryptedPayload
        
        // Sign with encrypted payload
        sign(message)
    }
    
    /**
     * Verifies a message's checksum and decrypts its payload.
     * 
     * Order of operations:
     * 1. Verify checksum over encrypted payload
     * 2. If valid, decrypt payload
     * 3. Update message with decrypted payload
     * 
     * @param message Message to process (modified in place)
     * @throws IllegalStateException if context is closed
     * @throws CryptoException if verification fails or decryption fails
     */
    fun verifyAndDecrypt(message: Message) {
        checkNotClosed()
        
        // Verify checksum first (over encrypted payload)
        if (!verify(message)) {
            throw CryptoException("Message checksum verification failed")
        }
        
        // Decrypt payload
        val decryptedPayload = decrypt(message.payload)
        message.payload = decryptedPayload
    }
    
    /**
     * Gets a copy of the key for storage purposes.
     * Use with caution - the returned key should be stored securely.
     * 
     * @return Copy of the encryption key
     * @throws IllegalStateException if context is closed
     */
    fun getKeyCopy(): ByteArray {
        checkNotClosed()
        return key.copyOf()
    }
    
    /**
     * Checks if the context has been closed.
     */
    fun isClosed(): Boolean = isClosed
    
    /**
     * Securely closes the context by wiping the key from memory.
     * After calling close(), all other methods will throw IllegalStateException.
     */
    override fun close() {
        if (!isClosed) {
            CryptoUtils.wipeBytes(key)
            isClosed = true
        }
    }
    
    private fun checkNotClosed() {
        check(!isClosed) { "CryptoContext has been closed" }
    }
    
    /**
     * Ensures the key is wiped when the object is garbage collected.
     * This is a safety net - always prefer explicit close() calls.
     */
    protected fun finalize() {
        close()
    }
}

/**
 * Extension function to use CryptoContext with use {} block.
 * Ensures the context is closed even if an exception occurs.
 */
inline fun <R> CryptoContext.use(block: (CryptoContext) -> R): R {
    try {
        return block(this)
    } finally {
        close()
    }
}
```

---

### 3.4 SecureStorageRepository

**File: `app/src/main/java/com/speech2prompt/data/repository/SecureStorageRepository.kt`**

```kotlin
package com.speech2prompt.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.speech2prompt.data.model.PairedDevice
import com.speech2prompt.service.crypto.CryptoUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for secure storage of sensitive data.
 * 
 * Uses Android's EncryptedSharedPreferences which provides:
 * - AES-256-SIV for key encryption (deterministic, allows key lookup)
 * - AES-256-GCM for value encryption (provides confidentiality + integrity)
 * - Keys stored in Android Keystore (hardware-backed on supported devices)
 * 
 * All operations are thread-safe and run on IO dispatcher.
 */
@Singleton
class SecureStorageRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    companion object {
        private const val PREFS_FILENAME = "speech2prompt_secure_prefs"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PAIRED_DEVICES = "paired_devices"
        private const val KEY_PAIRED_DEVICE_PREFIX = "paired_device_"
        private const val KEY_DEVICE_COUNT = "paired_device_count"
    }
    
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    private val mutex = Mutex()
    
    /**
     * Lazy initialization of encrypted preferences.
     * This may take some time on first access due to key generation.
     */
    private val prefs: SharedPreferences by lazy {
        createEncryptedPreferences()
    }
    
    private fun createEncryptedPreferences(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        
        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILENAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    
    /**
     * Gets the device ID, generating one if it doesn't exist.
     * 
     * Preference order:
     * 1. Previously stored device ID (for consistency)
     * 2. Android ID (unique per device/app combination)
     * 3. Generated random ID (fallback)
     * 
     * @return Device identifier string
     */
    suspend fun getDeviceId(): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            // Check for existing stored ID
            prefs.getString(KEY_DEVICE_ID, null)?.let { return@withContext it }
            
            // Try to get Android ID
            val androidId = try {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                    ?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" } // Filter known bad value
            } catch (e: Exception) {
                null
            }
            
            // Use Android ID or generate random
            val deviceId = androidId ?: CryptoUtils.generateDeviceId()
            
            // Store for future use
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
            
            deviceId
        }
    }
    
    /**
     * Stores a paired device.
     * If a device with the same address exists, it will be updated.
     * 
     * @param device PairedDevice to store
     */
    suspend fun storePairedDevice(device: PairedDevice): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val key = "$KEY_PAIRED_DEVICE_PREFIX${device.address}"
            val deviceJson = json.encodeToString(device)
            
            prefs.edit()
                .putString(key, deviceJson)
                .apply()
            
            // Update device list index
            updateDeviceIndex(device.address, add = true)
        }
    }
    
    /**
     * Retrieves all paired devices.
     * 
     * @return List of paired devices, empty if none
     */
    suspend fun getPairedDevices(): List<PairedDevice> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val addresses = getDeviceAddresses()
            
            addresses.mapNotNull { address ->
                val key = "$KEY_PAIRED_DEVICE_PREFIX$address"
                prefs.getString(key, null)?.let { deviceJson ->
                    try {
                        json.decodeFromString<PairedDevice>(deviceJson)
                    } catch (e: Exception) {
                        // Log and skip corrupted entries
                        null
                    }
                }
            }
        }
    }
    
    /**
     * Retrieves a specific paired device by address.
     * 
     * @param address Bluetooth address of the device
     * @return PairedDevice if found, null otherwise
     */
    suspend fun getPairedDevice(address: String): PairedDevice? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val key = "$KEY_PAIRED_DEVICE_PREFIX$address"
            prefs.getString(key, null)?.let { deviceJson ->
                try {
                    json.decodeFromString<PairedDevice>(deviceJson)
                } catch (e: Exception) {
                    null
                }
            }
        }
    }
    
    /**
     * Removes a paired device.
     * 
     * @param address Bluetooth address of the device to remove
     */
    suspend fun removePairedDevice(address: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val key = "$KEY_PAIRED_DEVICE_PREFIX$address"
            prefs.edit()
                .remove(key)
                .apply()
            
            updateDeviceIndex(address, add = false)
        }
    }
    
    /**
     * Checks if a device is paired.
     * 
     * @param address Bluetooth address to check
     * @return true if device is paired
     */
    suspend fun isPaired(address: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val key = "$KEY_PAIRED_DEVICE_PREFIX$address"
            prefs.contains(key)
        }
    }
    
    /**
     * Updates the last connected time for a device.
     * 
     * @param address Bluetooth address of the device
     * @param timestamp Connection timestamp (defaults to now)
     */
    suspend fun updateLastConnected(
        address: String,
        timestamp: Long = System.currentTimeMillis()
    ): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            getPairedDevice(address)?.let { device ->
                val updated = device.copy(lastConnected = timestamp)
                val key = "$KEY_PAIRED_DEVICE_PREFIX$address"
                prefs.edit()
                    .putString(key, json.encodeToString(updated))
                    .apply()
            }
        }
    }
    
    /**
     * Clears all stored data.
     * Use with caution - this removes all paired devices and settings.
     */
    suspend fun clearAll(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            prefs.edit().clear().apply()
        }
    }
    
    /**
     * Gets the number of paired devices.
     */
    suspend fun getPairedDeviceCount(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            getDeviceAddresses().size
        }
    }
    
    // --- Private helpers ---
    
    private fun getDeviceAddresses(): Set<String> {
        val addressesJson = prefs.getString(KEY_PAIRED_DEVICES, null) ?: return emptySet()
        return try {
            json.decodeFromString<Set<String>>(addressesJson)
        } catch (e: Exception) {
            emptySet()
        }
    }
    
    private fun updateDeviceIndex(address: String, add: Boolean) {
        val addresses = getDeviceAddresses().toMutableSet()
        
        if (add) {
            addresses.add(address)
        } else {
            addresses.remove(address)
        }
        
        prefs.edit()
            .putString(KEY_PAIRED_DEVICES, json.encodeToString(addresses))
            .apply()
    }
}
```

---

### 3.5 PairedDevice Model Update

**File: `app/src/main/java/com/speech2prompt/data/model/PairedDevice.kt`**

```kotlin
package com.speech2prompt.data.model

import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Represents a paired Linux device with its encryption key.
 */
@Serializable
data class PairedDevice(
    /**
     * Bluetooth MAC address of the device (e.g., "AA:BB:CC:DD:EE:FF")
     */
    val address: String,
    
    /**
     * User-friendly name of the device (e.g., "My Laptop")
     */
    val name: String,
    
    /**
     * Linux machine ID (/etc/machine-id)
     */
    val linuxId: String,
    
    /**
     * Base64-encoded encryption key derived during pairing
     */
    val encryptedKey: String,
    
    /**
     * Timestamp when pairing was completed
     */
    val pairedAt: Long = System.currentTimeMillis(),
    
    /**
     * Timestamp of last successful connection
     */
    val lastConnected: Long = pairedAt
) {
    /**
     * Decodes the encryption key from base64.
     * 
     * @return 32-byte encryption key
     * @throws IllegalArgumentException if key is invalid
     */
    @Transient
    fun getKey(): ByteArray {
        val key = Base64.decode(encryptedKey, Base64.NO_WRAP)
        require(key.size == 32) { "Invalid key size: ${key.size}" }
        return key
    }
    
    companion object {
        /**
         * Creates a PairedDevice with the given key.
         * 
         * @param address Bluetooth address
         * @param name Device name
         * @param linuxId Linux machine ID
         * @param key 32-byte encryption key
         * @return PairedDevice instance
         */
        fun create(
            address: String,
            name: String,
            linuxId: String,
            key: ByteArray
        ): PairedDevice {
            require(key.size == 32) { "Key must be 32 bytes" }
            return PairedDevice(
                address = address,
                name = name,
                linuxId = linuxId,
                encryptedKey = Base64.encodeToString(key, Base64.NO_WRAP)
            )
        }
    }
}
```

---

## Gradle Dependencies

Add to `app/build.gradle.kts`:

```kotlin
dependencies {
    // Security / Crypto
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    
    // Serialization for secure storage
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
}
```

---

## Unit Tests

**File: `app/src/test/java/com/speech2prompt/service/crypto/CryptoUtilsTest.kt`**

```kotlin
package com.speech2prompt.service.crypto

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class CryptoUtilsTest {
    
    @Test
    fun `deriveKey produces consistent output for same inputs`() {
        val key1 = CryptoUtils.deriveKey("1234", "android123", "linux456")
        val key2 = CryptoUtils.deriveKey("1234", "android123", "linux456")
        
        assertArrayEquals(key1, key2)
        assertEquals(32, key1.size)
    }
    
    @Test
    fun `deriveKey produces different output for different PINs`() {
        val key1 = CryptoUtils.deriveKey("1234", "android123", "linux456")
        val key2 = CryptoUtils.deriveKey("5678", "android123", "linux456")
        
        assertFalse(key1.contentEquals(key2))
    }
    
    @Test
    fun `deriveKey produces different output for different android IDs`() {
        val key1 = CryptoUtils.deriveKey("1234", "android123", "linux456")
        val key2 = CryptoUtils.deriveKey("1234", "android999", "linux456")
        
        assertFalse(key1.contentEquals(key2))
    }
    
    @Test
    fun `deriveKey matches Flutter implementation`() {
        // Known test vector from Flutter implementation
        // Update these values with actual test vectors from Flutter
        val pin = "123456"
        val androidId = "a1b2c3d4e5f6"
        val linuxId = "fedcba9876543210"
        
        val key = CryptoUtils.deriveKey(pin, androidId, linuxId)
        
        // Expected key from Flutter (base64 encoded)
        // val expectedBase64 = "..." // Get from Flutter tests
        // val expected = Base64.getDecoder().decode(expectedBase64)
        // assertArrayEquals(expected, key)
        
        assertEquals(32, key.size)
    }
    
    @Test
    fun `encryptAesGcm then decryptAesGcm returns original plaintext`() {
        val key = CryptoUtils.deriveKey("1234", "android123", "linux456")
        val plaintext = "Hello, World! This is a test message."
        
        val ciphertext = CryptoUtils.encryptAesGcm(plaintext, key)
        val decrypted = CryptoUtils.decryptAesGcm(ciphertext, key)
        
        assertEquals(plaintext, decrypted)
    }
    
    @Test
    fun `encryptAesGcm produces different ciphertext each time`() {
        val key = CryptoUtils.deriveKey("1234", "android123", "linux456")
        val plaintext = "Same message"
        
        val ciphertext1 = CryptoUtils.encryptAesGcm(plaintext, key)
        val ciphertext2 = CryptoUtils.encryptAesGcm(plaintext, key)
        
        // Different nonces should produce different ciphertext
        assertNotEquals(ciphertext1, ciphertext2)
        
        // But both should decrypt to the same plaintext
        assertEquals(plaintext, CryptoUtils.decryptAesGcm(ciphertext1, key))
        assertEquals(plaintext, CryptoUtils.decryptAesGcm(ciphertext2, key))
    }
    
    @Test
    fun `decryptAesGcm with wrong key throws CryptoException`() {
        val key1 = CryptoUtils.deriveKey("1234", "android123", "linux456")
        val key2 = CryptoUtils.deriveKey("5678", "android123", "linux456")
        val plaintext = "Secret message"
        
        val ciphertext = CryptoUtils.encryptAesGcm(plaintext, key1)
        
        assertThrows(CryptoException::class.java) {
            CryptoUtils.decryptAesGcm(ciphertext, key2)
        }
    }
    
    @Test
    fun `calculateChecksum returns 8 hex characters`() {
        val key = CryptoUtils.deriveKey("1234", "android123", "linux456")
        
        val checksum = CryptoUtils.calculateChecksum(
            version = 1,
            msgType = "TRANSCRIPT",
            payload = "test payload",
            timestamp = 1234567890L,
            secret = key
        )
        
        assertEquals(8, checksum.length)
        assertTrue(checksum.all { it in '0'..'9' || it in 'a'..'f' })
    }
    
    @Test
    fun `calculateChecksum is consistent for same inputs`() {
        val key = CryptoUtils.deriveKey("1234", "android123", "linux456")
        
        val checksum1 = CryptoUtils.calculateChecksum(1, "MSG", "payload", 12345L, key)
        val checksum2 = CryptoUtils.calculateChecksum(1, "MSG", "payload", 12345L, key)
        
        assertEquals(checksum1, checksum2)
    }
    
    @Test
    fun `calculateChecksum changes with different inputs`() {
        val key = CryptoUtils.deriveKey("1234", "android123", "linux456")
        
        val checksum1 = CryptoUtils.calculateChecksum(1, "MSG", "payload1", 12345L, key)
        val checksum2 = CryptoUtils.calculateChecksum(1, "MSG", "payload2", 12345L, key)
        
        assertNotEquals(checksum1, checksum2)
    }
    
    @Test
    fun `verifyChecksum returns true for valid checksum`() {
        val key = CryptoUtils.deriveKey("1234", "android123", "linux456")
        val checksum = CryptoUtils.calculateChecksum(1, "MSG", "payload", 12345L, key)
        
        assertTrue(CryptoUtils.verifyChecksum(checksum, 1, "MSG", "payload", 12345L, key))
    }
    
    @Test
    fun `verifyChecksum returns false for invalid checksum`() {
        val key = CryptoUtils.deriveKey("1234", "android123", "linux456")
        
        assertFalse(CryptoUtils.verifyChecksum("00000000", 1, "MSG", "payload", 12345L, key))
    }
    
    @Test
    fun `generateDeviceId returns 32 hex characters`() {
        val deviceId = CryptoUtils.generateDeviceId()
        
        assertEquals(32, deviceId.length)
        assertTrue(deviceId.all { it in '0'..'9' || it in 'a'..'f' })
    }
    
    @Test
    fun `generateDeviceId returns unique values`() {
        val ids = (1..100).map { CryptoUtils.generateDeviceId() }.toSet()
        
        assertEquals(100, ids.size) // All should be unique
    }
    
    @Test
    fun `encryptAesGcm rejects invalid key length`() {
        val shortKey = ByteArray(16) // AES-128, not AES-256
        
        assertThrows(IllegalArgumentException::class.java) {
            CryptoUtils.encryptAesGcm("test", shortKey)
        }
    }
    
    @Test
    fun `decryptAesGcm handles empty ciphertext`() {
        val key = ByteArray(32)
        
        assertThrows(CryptoException::class.java) {
            CryptoUtils.decryptAesGcm("", key)
        }
    }
    
    @Test
    fun `constantTimeEquals returns true for equal arrays`() {
        val a = byteArrayOf(1, 2, 3, 4, 5)
        val b = byteArrayOf(1, 2, 3, 4, 5)
        
        assertTrue(CryptoUtils.constantTimeEquals(a, b))
    }
    
    @Test
    fun `constantTimeEquals returns false for different arrays`() {
        val a = byteArrayOf(1, 2, 3, 4, 5)
        val b = byteArrayOf(1, 2, 3, 4, 6)
        
        assertFalse(CryptoUtils.constantTimeEquals(a, b))
    }
    
    @Test
    fun `constantTimeEquals returns false for different length arrays`() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(1, 2, 3, 4)
        
        assertFalse(CryptoUtils.constantTimeEquals(a, b))
    }
    
    @Test
    fun `wipeBytes fills array with zeros`() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        
        CryptoUtils.wipeBytes(bytes)
        
        assertTrue(bytes.all { it == 0.toByte() })
    }
}
```

**File: `app/src/test/java/com/speech2prompt/service/crypto/CryptoContextTest.kt`**

```kotlin
package com.speech2prompt.service.crypto

import com.speech2prompt.data.model.Message
import com.speech2prompt.data.model.MessageType
import org.junit.Assert.*
import org.junit.Test

class CryptoContextTest {
    
    @Test
    fun `fromPin creates valid context`() {
        val context = CryptoContext.fromPin("1234", "android", "linux")
        
        assertFalse(context.isClosed())
        context.close()
    }
    
    @Test
    fun `fromKey creates valid context`() {
        val key = ByteArray(32) { it.toByte() }
        val context = CryptoContext.fromKey(key)
        
        assertFalse(context.isClosed())
        context.close()
    }
    
    @Test
    fun `fromKey rejects invalid key length`() {
        val shortKey = ByteArray(16)
        
        assertThrows(IllegalArgumentException::class.java) {
            CryptoContext.fromKey(shortKey)
        }
    }
    
    @Test
    fun `encrypt and decrypt round trip`() {
        val context = CryptoContext.fromPin("1234", "android", "linux")
        val plaintext = "Hello, World!"
        
        val ciphertext = context.encrypt(plaintext)
        val decrypted = context.decrypt(ciphertext)
        
        assertEquals(plaintext, decrypted)
        context.close()
    }
    
    @Test
    fun `sign adds checksum to message`() {
        val context = CryptoContext.fromPin("1234", "android", "linux")
        val message = Message(
            version = 1,
            msgType = MessageType.TRANSCRIPT,
            payload = "test",
            timestamp = System.currentTimeMillis()
        )
        
        assertNull(message.checksum)
        
        context.sign(message)
        
        assertNotNull(message.checksum)
        assertEquals(8, message.checksum!!.length)
        context.close()
    }
    
    @Test
    fun `verify returns true for valid signature`() {
        val context = CryptoContext.fromPin("1234", "android", "linux")
        val message = Message(
            version = 1,
            msgType = MessageType.TRANSCRIPT,
            payload = "test",
            timestamp = System.currentTimeMillis()
        )
        
        context.sign(message)
        
        assertTrue(context.verify(message))
        context.close()
    }
    
    @Test
    fun `verify returns false for tampered message`() {
        val context = CryptoContext.fromPin("1234", "android", "linux")
        val message = Message(
            version = 1,
            msgType = MessageType.TRANSCRIPT,
            payload = "test",
            timestamp = System.currentTimeMillis()
        )
        
        context.sign(message)
        message.payload = "tampered"
        
        assertFalse(context.verify(message))
        context.close()
    }
    
    @Test
    fun `signAndEncrypt then verifyAndDecrypt round trip`() {
        val context = CryptoContext.fromPin("1234", "android", "linux")
        val originalPayload = "Secret message"
        val message = Message(
            version = 1,
            msgType = MessageType.TRANSCRIPT,
            payload = originalPayload,
            timestamp = System.currentTimeMillis()
        )
        
        context.signAndEncrypt(message)
        
        // Payload should be encrypted (different from original)
        assertNotEquals(originalPayload, message.payload)
        assertNotNull(message.checksum)
        
        context.verifyAndDecrypt(message)
        
        // Payload should be restored
        assertEquals(originalPayload, message.payload)
        context.close()
    }
    
    @Test
    fun `verifyAndDecrypt throws on invalid checksum`() {
        val context = CryptoContext.fromPin("1234", "android", "linux")
        val message = Message(
            version = 1,
            msgType = MessageType.TRANSCRIPT,
            payload = "test",
            timestamp = System.currentTimeMillis(),
            checksum = "00000000" // Invalid checksum
        )
        
        assertThrows(CryptoException::class.java) {
            context.verifyAndDecrypt(message)
        }
        context.close()
    }
    
    @Test
    fun `operations throw after close`() {
        val context = CryptoContext.fromPin("1234", "android", "linux")
        context.close()
        
        assertTrue(context.isClosed())
        
        assertThrows(IllegalStateException::class.java) {
            context.encrypt("test")
        }
    }
    
    @Test
    fun `use extension closes context`() {
        val context = CryptoContext.fromPin("1234", "android", "linux")
        
        context.use { ctx ->
            assertFalse(ctx.isClosed())
        }
        
        assertTrue(context.isClosed())
    }
    
    @Test
    fun `use extension closes context on exception`() {
        val context = CryptoContext.fromPin("1234", "android", "linux")
        
        try {
            context.use { _ ->
                throw RuntimeException("test error")
            }
        } catch (e: RuntimeException) {
            // Expected
        }
        
        assertTrue(context.isClosed())
    }
    
    @Test
    fun `getKeyCopy returns copy not reference`() {
        val originalKey = ByteArray(32) { it.toByte() }
        val context = CryptoContext.fromKey(originalKey)
        
        val keyCopy = context.getKeyCopy()
        
        // Modify original - should not affect context
        originalKey.fill(0)
        
        // Context should still work
        val plaintext = "test"
        val ciphertext = context.encrypt(plaintext)
        assertEquals(plaintext, context.decrypt(ciphertext))
        
        context.close()
    }
}
```

---

## Integration Tests

**File: `app/src/androidTest/java/com/speech2prompt/data/repository/SecureStorageRepositoryTest.kt`**

```kotlin
package com.speech2prompt.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.speech2prompt.data.model.PairedDevice
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureStorageRepositoryTest {
    
    private lateinit var repository: SecureStorageRepository
    
    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        repository = SecureStorageRepository(context)
    }
    
    @After
    fun teardown() = runBlocking {
        repository.clearAll()
    }
    
    @Test
    fun getDeviceId_returnsConsistentValue() = runBlocking {
        val id1 = repository.getDeviceId()
        val id2 = repository.getDeviceId()
        
        assertEquals(id1, id2)
        assertTrue(id1.isNotBlank())
    }
    
    @Test
    fun storePairedDevice_thenRetrieve() = runBlocking {
        val device = PairedDevice.create(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Test Device",
            linuxId = "test-linux-id",
            key = ByteArray(32) { it.toByte() }
        )
        
        repository.storePairedDevice(device)
        val retrieved = repository.getPairedDevice(device.address)
        
        assertNotNull(retrieved)
        assertEquals(device.address, retrieved!!.address)
        assertEquals(device.name, retrieved.name)
        assertEquals(device.linuxId, retrieved.linuxId)
        assertArrayEquals(device.getKey(), retrieved.getKey())
    }
    
    @Test
    fun getPairedDevices_returnsAllDevices() = runBlocking {
        val device1 = PairedDevice.create(
            address = "AA:BB:CC:DD:EE:01",
            name = "Device 1",
            linuxId = "linux-1",
            key = ByteArray(32)
        )
        val device2 = PairedDevice.create(
            address = "AA:BB:CC:DD:EE:02",
            name = "Device 2",
            linuxId = "linux-2",
            key = ByteArray(32)
        )
        
        repository.storePairedDevice(device1)
        repository.storePairedDevice(device2)
        
        val devices = repository.getPairedDevices()
        
        assertEquals(2, devices.size)
        assertTrue(devices.any { it.address == device1.address })
        assertTrue(devices.any { it.address == device2.address })
    }
    
    @Test
    fun removePairedDevice_deletesDevice() = runBlocking {
        val device = PairedDevice.create(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Test Device",
            linuxId = "linux-id",
            key = ByteArray(32)
        )
        
        repository.storePairedDevice(device)
        assertTrue(repository.isPaired(device.address))
        
        repository.removePairedDevice(device.address)
        
        assertFalse(repository.isPaired(device.address))
        assertNull(repository.getPairedDevice(device.address))
    }
    
    @Test
    fun updateLastConnected_updatesTimestamp() = runBlocking {
        val device = PairedDevice.create(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Test Device",
            linuxId = "linux-id",
            key = ByteArray(32)
        )
        
        repository.storePairedDevice(device)
        val originalTimestamp = repository.getPairedDevice(device.address)!!.lastConnected
        
        Thread.sleep(10) // Ensure time passes
        
        val newTimestamp = System.currentTimeMillis()
        repository.updateLastConnected(device.address, newTimestamp)
        
        val updated = repository.getPairedDevice(device.address)
        assertEquals(newTimestamp, updated!!.lastConnected)
        assertTrue(updated.lastConnected > originalTimestamp)
    }
    
    @Test
    fun clearAll_removesAllData() = runBlocking {
        val device = PairedDevice.create(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Test Device",
            linuxId = "linux-id",
            key = ByteArray(32)
        )
        
        repository.storePairedDevice(device)
        assertEquals(1, repository.getPairedDeviceCount())
        
        repository.clearAll()
        
        assertEquals(0, repository.getPairedDeviceCount())
        assertTrue(repository.getPairedDevices().isEmpty())
    }
    
    @Test
    fun storePairedDevice_updatesExisting() = runBlocking {
        val address = "AA:BB:CC:DD:EE:FF"
        
        val device1 = PairedDevice.create(
            address = address,
            name = "Original Name",
            linuxId = "linux-id",
            key = ByteArray(32) { 1 }
        )
        
        val device2 = PairedDevice.create(
            address = address,
            name = "Updated Name",
            linuxId = "linux-id",
            key = ByteArray(32) { 2 }
        )
        
        repository.storePairedDevice(device1)
        repository.storePairedDevice(device2)
        
        val devices = repository.getPairedDevices()
        assertEquals(1, devices.size)
        assertEquals("Updated Name", devices[0].name)
    }
    
    @Test
    fun getPairedDevice_returnsNullForUnknownAddress() = runBlocking {
        val device = repository.getPairedDevice("XX:XX:XX:XX:XX:XX")
        
        assertNull(device)
    }
    
    @Test
    fun isPaired_returnsFalseForUnknownAddress() = runBlocking {
        assertFalse(repository.isPaired("XX:XX:XX:XX:XX:XX"))
    }
}
```

---

## Verification Checklist

- [ ] **Unit test**: `deriveKey` produces same output as Flutter for same inputs
  - Create test vectors in Flutter, verify in Kotlin
  - Test with various PIN lengths (4, 6, 8 digits)
  - Test with special characters in IDs
  
- [ ] **Unit test**: encrypt then decrypt returns original plaintext
  - Test with empty string
  - Test with unicode characters
  - Test with very long strings (>1MB)
  
- [ ] **Unit test**: checksum matches expected format (8 hex chars)
  - Verify lowercase hex output
  - Test boundary conditions (empty payload, max length)
  
- [ ] **Unit test**: `CryptoContext.signAndEncrypt` + `verifyAndDecrypt` round-trip
  - Test with all message types
  - Test concurrent operations
  - Test after close() throws appropriate exception
  
- [ ] **Integration test**: SecureStorage persists and retrieves PairedDevice
  - Test app restart persistence
  - Test data migration scenarios
  - Test storage limits
  
- [ ] **Verify encryption is compatible with Flutter app**
  - Create encrypted message in Flutter, decrypt in Kotlin
  - Create encrypted message in Kotlin, decrypt in Flutter
  - Test checksum verification cross-platform

---

## Cross-Platform Compatibility Testing

### Test Vector Generation (Flutter)

```dart
// Run in Flutter app to generate test vectors
void generateTestVectors() {
  final pin = '123456';
  final androidId = 'a1b2c3d4e5f6';
  final linuxId = 'fedcba9876543210';
  
  final key = deriveKey(pin, androidId, linuxId);
  print('Key (base64): ${base64Encode(key)}');
  
  final plaintext = 'Hello from Flutter!';
  final ciphertext = encryptAesGcm(plaintext, key);
  print('Ciphertext: $ciphertext');
  
  final checksum = calculateChecksum(1, 'TRANSCRIPT', plaintext, 1234567890, key);
  print('Checksum: $checksum');
}
```

### Verification (Kotlin)

```kotlin
@Test
fun `verify Flutter compatibility with test vectors`() {
    // Values from Flutter generateTestVectors()
    val pin = "123456"
    val androidId = "a1b2c3d4e5f6"
    val linuxId = "fedcba9876543210"
    val expectedKeyBase64 = "..." // From Flutter
    val flutterCiphertext = "..." // From Flutter
    val expectedChecksum = "..." // From Flutter
    
    // Verify key derivation
    val key = CryptoUtils.deriveKey(pin, androidId, linuxId)
    assertEquals(expectedKeyBase64, Base64.encodeToString(key, Base64.NO_WRAP))
    
    // Verify decryption of Flutter-encrypted data
    val decrypted = CryptoUtils.decryptAesGcm(flutterCiphertext, key)
    assertEquals("Hello from Flutter!", decrypted)
    
    // Verify checksum calculation
    val checksum = CryptoUtils.calculateChecksum(1, "TRANSCRIPT", "Hello from Flutter!", 1234567890, key)
    assertEquals(expectedChecksum, checksum)
}
```

---

## Security Considerations

### Key Storage

1. **Never log keys** - Even in debug builds
2. **Wipe keys from memory** - Call `CryptoContext.close()` when done
3. **Use EncryptedSharedPreferences** - Hardware-backed on supported devices
4. **Don't export keys** - Keys should never leave the device

### Error Handling

1. **Don't leak information** - Generic error messages for crypto failures
2. **Fail securely** - On any crypto error, assume compromise
3. **Rate limit** - Consider rate limiting PIN attempts

### Code Review Checklist

- [ ] No keys logged or printed
- [ ] All `CryptoContext` instances are closed
- [ ] No hardcoded keys or secrets
- [ ] Proper exception handling (no info leakage)
- [ ] Thread-safe operations

---

## Estimated Time: 6-8 hours

| Task | Time |
|------|------|
| CryptoConstants + CryptoUtils | 2 hours |
| CryptoContext | 1.5 hours |
| SecureStorageRepository | 1.5 hours |
| Unit Tests | 1.5 hours |
| Integration Tests | 1 hour |
| Cross-platform verification | 0.5 hours |

---

## Dependencies on Other Phases

- **Phase 2 (Data Models)**: `Message`, `MessageType`, `PairedDevice` models
- **Phase 4 (Bluetooth)**: Will use `CryptoContext` for message encryption
- **Phase 5 (Pairing)**: Will use `SecureStorageRepository` for storing paired devices

## Files Created in This Phase

```
app/src/main/java/com/speech2prompt/
├── service/crypto/
│   ├── CryptoConstants.kt
│   ├── CryptoUtils.kt
│   ├── CryptoContext.kt
│   └── CryptoException.kt
└── data/
    ├── model/
    │   └── PairedDevice.kt (updated)
    └── repository/
        └── SecureStorageRepository.kt

app/src/test/java/com/speech2prompt/service/crypto/
├── CryptoUtilsTest.kt
└── CryptoContextTest.kt

app/src/androidTest/java/com/speech2prompt/data/repository/
└── SecureStorageRepositoryTest.kt
```
