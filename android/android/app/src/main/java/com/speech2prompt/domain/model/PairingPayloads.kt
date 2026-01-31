package com.speech2prompt.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Pairing request payload sent from Android to Linux
 */
@Serializable
data class PairRequestPayload(
    @SerialName("device_id")
    val deviceId: String,

    @SerialName("device_name")
    val deviceName: String? = null
) {
    companion object {
        /**
         * Create payload from device info
         */
        fun create(deviceId: String, deviceName: String? = null): PairRequestPayload {
            return PairRequestPayload(
                deviceId = deviceId,
                deviceName = deviceName ?: "Android Device"
            )
        }
    }
}

/**
 * Pairing acknowledgment payload received from Linux
 */
@Serializable
data class PairAckPayload(
    @SerialName("device_id")
    val deviceId: String,

    @SerialName("status")
    val status: String,

    @SerialName("error")
    val error: String? = null,

    @SerialName("shared_secret")
    val sharedSecret: String? = null
) {
    /**
     * Parsed status enum
     */
    val pairStatus: PairStatus
        get() = PairStatus.fromString(status)

    /**
     * Check if pairing was successful
     * Note: Desktop doesn't send sharedSecret - success is determined by status only
     */
    val isSuccess: Boolean
        get() = pairStatus == PairStatus.OK

    companion object {
        /**
         * Create success response
         */
        fun success(deviceId: String, sharedSecret: String): PairAckPayload {
            return PairAckPayload(
                deviceId = deviceId,
                status = PairStatus.OK.value,
                sharedSecret = sharedSecret
            )
        }

        /**
         * Create error response
         */
        fun error(deviceId: String, errorMessage: String): PairAckPayload {
            return PairAckPayload(
                deviceId = deviceId,
                status = PairStatus.ERROR.value,
                error = errorMessage
            )
        }
    }
}

/**
 * Pairing status enumeration
 */
enum class PairStatus(val value: String) {
    OK("OK"),
    ERROR("ERROR");

    companion object {
        fun fromString(value: String): PairStatus {
            return entries.find { it.value.equals(value, ignoreCase = true) }
                ?: ERROR
        }
    }
}
