package com.speech2prompt.util.crypto

import android.os.Build
import android.util.Base64
import android.util.Log
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.*
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ECDH key exchange manager using X25519.
 * 
 * On API 33+, uses native X25519 support.
 * On older APIs, uses BouncyCastle provider.
 */
@Singleton
class EcdhManager @Inject constructor() {

    companion object {
        private const val TAG = "EcdhManager"
        const val PUBLIC_KEY_SIZE = 32
        const val SHARED_SECRET_SIZE = 32
        
        // X25519 algorithm names
        private const val KEY_ALGORITHM_X25519 = "X25519"
        private const val KEY_ALGORITHM_XDH = "XDH"
        private const val KEY_AGREEMENT_ALGORITHM = "XDH"
        
        // X25519 public key ASN.1 prefix (12 bytes)
        // SEQUENCE { SEQUENCE { OID 1.3.101.110 } BIT STRING }
        private val X25519_PUBLIC_KEY_PREFIX = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00
        )
        
        init {
            // Register BouncyCastle provider if not already registered
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
                Log.d(TAG, "BouncyCastle provider registered")
            }
        }
    }

    /**
     * Generate an ECDH keypair for key exchange.
     * 
     * @return Result containing the keypair
     */
    fun generateKeyPair(): Result<KeyPair> {
        return try {
            val keyPairGenerator = if (Build.VERSION.SDK_INT >= 33) {
                try {
                    KeyPairGenerator.getInstance(KEY_ALGORITHM_X25519)
                } catch (e: NoSuchAlgorithmException) {
                    // Fallback to XDH
                    KeyPairGenerator.getInstance(KEY_ALGORITHM_XDH)
                }
            } else {
                // Use BouncyCastle for older APIs
                KeyPairGenerator.getInstance(KEY_ALGORITHM_X25519, BouncyCastleProvider.PROVIDER_NAME)
            }
            
            keyPairGenerator.initialize(256) // X25519 is always 256-bit
            Result.success(keyPairGenerator.generateKeyPair())
        } catch (e: Exception) {
            Result.failure(CryptoException("Failed to generate ECDH keypair: ${e.message}", e))
        }
    }

    /**
     * Extract raw 32-byte public key from Java KeyPair.
     * 
     * Java's X25519 public keys are in SubjectPublicKeyInfo format.
     * We need the raw 32 bytes for cross-platform compatibility.
     */
    fun extractRawPublicKey(keyPair: KeyPair): Result<ByteArray> {
        return try {
            val encoded = keyPair.public.encoded
            
            // X25519 SubjectPublicKeyInfo is 44 bytes: 12-byte prefix + 32-byte key
            if (encoded.size == 44 && 
                encoded.take(X25519_PUBLIC_KEY_PREFIX.size).toByteArray()
                    .contentEquals(X25519_PUBLIC_KEY_PREFIX)) {
                Result.success(encoded.copyOfRange(12, 44))
            } else if (encoded.size == PUBLIC_KEY_SIZE) {
                // Already raw format
                Result.success(encoded)
            } else {
                Result.failure(CryptoException(
                    "Unexpected public key format: ${encoded.size} bytes"
                ))
            }
        } catch (e: Exception) {
            Result.failure(CryptoException("Failed to extract public key: ${e.message}", e))
        }
    }

    /**
     * Get public key as Base64 string (raw 32 bytes encoded).
     */
    fun getPublicKeyBase64(keyPair: KeyPair): Result<String> {
        return extractRawPublicKey(keyPair).map { rawKey ->
            Base64.encodeToString(rawKey, Base64.NO_WRAP)
        }
    }

    /**
     * Compute shared secret using ECDH.
     * 
     * @param myKeyPair Our keypair (with private key)
     * @param peerPublicKeyBase64 Peer's raw public key as Base64
     * @return 32-byte shared secret
     */
    fun computeSharedSecret(myKeyPair: KeyPair, peerPublicKeyBase64: String): Result<ByteArray> {
        return try {
            // Decode peer's raw public key
            val peerRawKey = Base64.decode(peerPublicKeyBase64, Base64.NO_WRAP)
            if (peerRawKey.size != PUBLIC_KEY_SIZE) {
                return Result.failure(CryptoException(
                    "Invalid peer public key size: expected $PUBLIC_KEY_SIZE, got ${peerRawKey.size}"
                ))
            }

            // Wrap raw key in SubjectPublicKeyInfo format for Java
            val peerEncoded = X25519_PUBLIC_KEY_PREFIX + peerRawKey
            
            val keyFactory = if (Build.VERSION.SDK_INT >= 33) {
                try {
                    KeyFactory.getInstance(KEY_ALGORITHM_X25519)
                } catch (e: NoSuchAlgorithmException) {
                    KeyFactory.getInstance(KEY_ALGORITHM_XDH)
                }
            } else {
                KeyFactory.getInstance(KEY_ALGORITHM_X25519, BouncyCastleProvider.PROVIDER_NAME)
            }
            
            val peerPublicKey = keyFactory.generatePublic(X509EncodedKeySpec(peerEncoded))

            // Perform ECDH key agreement
            val keyAgreement = if (Build.VERSION.SDK_INT >= 33) {
                KeyAgreement.getInstance(KEY_AGREEMENT_ALGORITHM)
            } else {
                KeyAgreement.getInstance(KEY_ALGORITHM_X25519, BouncyCastleProvider.PROVIDER_NAME)
            }
            
            keyAgreement.init(myKeyPair.private)
            keyAgreement.doPhase(peerPublicKey, true)
            
            val sharedSecret = keyAgreement.generateSecret()
            
            if (sharedSecret.size != SHARED_SECRET_SIZE) {
                return Result.failure(CryptoException(
                    "Unexpected shared secret size: ${sharedSecret.size}"
                ))
            }
            
            Result.success(sharedSecret)
        } catch (e: Exception) {
            Result.failure(CryptoException("Failed to compute shared secret: ${e.message}", e))
        }
    }
}
