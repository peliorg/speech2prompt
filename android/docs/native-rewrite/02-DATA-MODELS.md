# Phase 2: Data Models & Protocol Layer

## Goal
Implement all data models and protocol classes matching the Flutter implementation.

## Estimated Time: 4-6 hours

---

## Tasks

### 2.1 Connection State (domain/model/ConnectionState.kt)

```kotlin
package com.example.speech2prompt.domain.model

/**
 * Bluetooth connection state enumeration.
 * Matches Flutter's ConnectionState enum.
 */
enum class BtConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    AWAITING_PAIRING,
    RECONNECTING,
    FAILED;

    companion object {
        /**
         * Parse connection state from string (for serialization)
         */
        fun fromString(value: String): BtConnectionState {
            return entries.find { it.name.equals(value, ignoreCase = true) }
                ?: DISCONNECTED
        }
    }
}

/**
 * Extension: Check if currently connected
 */
val BtConnectionState.isConnected: Boolean
    get() = this == BtConnectionState.CONNECTED

/**
 * Extension: Check if connection is in progress
 */
val BtConnectionState.isConnecting: Boolean
    get() = this == BtConnectionState.CONNECTING ||
            this == BtConnectionState.RECONNECTING ||
            this == BtConnectionState.AWAITING_PAIRING

/**
 * Extension: Check if connection attempt is allowed
 */
val BtConnectionState.canConnect: Boolean
    get() = this == BtConnectionState.DISCONNECTED ||
            this == BtConnectionState.FAILED

/**
 * Extension: Human-readable display text
 */
val BtConnectionState.displayText: String
    get() = when (this) {
        BtConnectionState.DISCONNECTED -> "Disconnected"
        BtConnectionState.CONNECTING -> "Connecting..."
        BtConnectionState.CONNECTED -> "Connected"
        BtConnectionState.AWAITING_PAIRING -> "Awaiting Pairing..."
        BtConnectionState.RECONNECTING -> "Reconnecting..."
        BtConnectionState.FAILED -> "Connection Failed"
    }

/**
 * Extension: Icon resource name (for UI binding)
 */
val BtConnectionState.iconName: String
    get() = when (this) {
        BtConnectionState.DISCONNECTED -> "bluetooth_disabled"
        BtConnectionState.CONNECTING -> "bluetooth_searching"
        BtConnectionState.CONNECTED -> "bluetooth_connected"
        BtConnectionState.AWAITING_PAIRING -> "bluetooth_searching"
        BtConnectionState.RECONNECTING -> "bluetooth_searching"
        BtConnectionState.FAILED -> "bluetooth_disabled"
    }

/**
 * Extension: Whether to show loading indicator
 */
val BtConnectionState.showProgress: Boolean
    get() = isConnecting
```

---

### 2.2 Message Protocol (domain/model/Message.kt)

```kotlin
package com.example.speech2prompt.domain.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Protocol version for message compatibility
 */
const val PROTOCOL_VERSION = 1

/**
 * JSON configuration for message serialization
 */
val messageJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
}

/**
 * Message type enumeration with wire values
 */
@Serializable(with = MessageTypeSerializer::class)
enum class MessageType(val value: String) {
    TEXT("TEXT"),
    COMMAND("COMMAND"),
    HEARTBEAT("HEARTBEAT"),
    ACK("ACK"),
    PAIR_REQ("PAIR_REQ"),
    PAIR_ACK("PAIR_ACK");

    companion object {
        fun fromValue(value: String): MessageType {
            return entries.find { it.value.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown message type: $value")
        }
    }
}

/**
 * Custom serializer for MessageType to use value field
 */
object MessageTypeSerializer : KSerializer<MessageType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("MessageType", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: MessageType) {
        encoder.encodeString(value.value)
    }

    override fun deserialize(decoder: Decoder): MessageType {
        return MessageType.fromValue(decoder.decodeString())
    }
}

/**
 * Protocol message for BLE communication.
 * Matches Flutter's Message class exactly.
 */
@Serializable
data class Message(
    @SerialName("v")
    val version: Int = PROTOCOL_VERSION,

    @SerialName("t")
    val messageType: MessageType,

    @SerialName("p")
    var payload: String,

    @SerialName("ts")
    val timestamp: Long = System.currentTimeMillis(),

    @SerialName("cs")
    var checksum: String = ""
) {
    /**
     * Whether this message type should be encrypted
     */
    val shouldEncrypt: Boolean
        get() = when (messageType) {
            MessageType.PAIR_REQ,
            MessageType.PAIR_ACK -> false
            else -> true
        }

    /**
     * Serialize message to JSON string
     */
    fun toJson(): String {
        // Compute checksum before serialization if empty
        if (checksum.isEmpty()) {
            checksum = computeChecksum()
        }
        return messageJson.encodeToString(serializer(), this)
    }

    /**
     * Compute MD5 checksum of message content
     */
    fun computeChecksum(): String {
        val content = "$version|${messageType.value}|$payload|$timestamp"
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(content.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Verify message checksum
     */
    fun verifyChecksum(): Boolean {
        if (checksum.isEmpty()) return true
        return checksum == computeChecksum()
    }

    companion object {
        /**
         * Parse message from JSON string
         */
        fun fromJson(json: String): Message {
            return messageJson.decodeFromString(serializer(), json)
        }

        /**
         * Create a text message
         */
        fun text(content: String): Message {
            return Message(
                messageType = MessageType.TEXT,
                payload = content
            )
        }

        /**
         * Create a command message
         */
        fun command(cmd: String): Message {
            return Message(
                messageType = MessageType.COMMAND,
                payload = cmd
            )
        }

        /**
         * Create a heartbeat message
         */
        fun heartbeat(): Message {
            return Message(
                messageType = MessageType.HEARTBEAT,
                payload = ""
            )
        }

        /**
         * Create an acknowledgment message
         */
        fun ack(originalTimestamp: Long): Message {
            return Message(
                messageType = MessageType.ACK,
                payload = originalTimestamp.toString()
            )
        }

        /**
         * Create a pairing request message
         */
        fun pairRequest(payload: PairRequestPayload): Message {
            return Message(
                messageType = MessageType.PAIR_REQ,
                payload = messageJson.encodeToString(PairRequestPayload.serializer(), payload)
            )
        }

        /**
         * Create a pairing acknowledgment message
         */
        fun pairAck(payload: PairAckPayload): Message {
            return Message(
                messageType = MessageType.PAIR_ACK,
                payload = messageJson.encodeToString(PairAckPayload.serializer(), payload)
            )
        }
    }
}

/**
 * Extension to parse payload as PairRequestPayload
 */
fun Message.parsePairRequestPayload(): PairRequestPayload? {
    if (messageType != MessageType.PAIR_REQ) return null
    return try {
        messageJson.decodeFromString(PairRequestPayload.serializer(), payload)
    } catch (e: Exception) {
        null
    }
}

/**
 * Extension to parse payload as PairAckPayload
 */
fun Message.parsePairAckPayload(): PairAckPayload? {
    if (messageType != MessageType.PAIR_ACK) return null
    return try {
        messageJson.decodeFromString(PairAckPayload.serializer(), payload)
    } catch (e: Exception) {
        null
    }
}
```

---

### 2.3 Pairing Payloads (domain/model/PairingPayloads.kt)

```kotlin
package com.example.speech2prompt.domain.model

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
     */
    val isSuccess: Boolean
        get() = pairStatus == PairStatus.OK && sharedSecret != null

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
```

---

### 2.4 Voice Commands (domain/model/VoiceCommand.kt)

```kotlin
package com.example.speech2prompt.domain.model

/**
 * Command codes for voice-triggered actions.
 * These are sent to the Linux host for execution.
 */
enum class CommandCode(val code: String) {
    ENTER("ENTER"),
    SELECT_ALL("SELECT_ALL"),
    COPY("COPY"),
    PASTE("PASTE"),
    CUT("CUT"),
    CANCEL("CANCEL");

    companion object {
        fun fromCode(code: String): CommandCode? {
            return entries.find { it.code.equals(code, ignoreCase = true) }
        }
    }
}

/**
 * Result of processing speech text for commands
 */
data class ProcessedSpeech(
    val textBefore: String?,
    val command: CommandCode?,
    val textAfter: String?
) {
    /**
     * Whether there is any text content (before or after command)
     */
    val hasText: Boolean
        get() = !textBefore.isNullOrBlank() || !textAfter.isNullOrBlank()

    /**
     * Whether a command was detected
     */
    val hasCommand: Boolean
        get() = command != null

    /**
     * Whether there is text after the command
     */
    val hasRemainder: Boolean
        get() = !textAfter.isNullOrBlank()

    /**
     * Combined text (before + after), null if no text
     */
    val combinedText: String?
        get() {
            val before = textBefore?.trim() ?: ""
            val after = textAfter?.trim() ?: ""
            val combined = "$before $after".trim()
            return combined.ifEmpty { null }
        }

    companion object {
        /**
         * Create result with only text (no command)
         */
        fun textOnly(text: String): ProcessedSpeech {
            return ProcessedSpeech(
                textBefore = text.trim().ifEmpty { null },
                command = null,
                textAfter = null
            )
        }

        /**
         * Create result with only command (no text)
         */
        fun commandOnly(command: CommandCode): ProcessedSpeech {
            return ProcessedSpeech(
                textBefore = null,
                command = command,
                textAfter = null
            )
        }

        /**
         * Empty result
         */
        val EMPTY = ProcessedSpeech(null, null, null)
    }
}

/**
 * Parser for detecting voice commands in speech text.
 * Matches Flutter's CommandParser implementation.
 */
object CommandParser {
    /**
     * Command patterns mapped to command codes.
     * Patterns are matched case-insensitively.
     */
    private val patterns: Map<String, CommandCode> = mapOf(
        // Enter/newline variations
        "new line" to CommandCode.ENTER,
        "newline" to CommandCode.ENTER,
        "enter" to CommandCode.ENTER,
        "new paragraph" to CommandCode.ENTER,
        "next line" to CommandCode.ENTER,

        // Selection
        "select all" to CommandCode.SELECT_ALL,
        "select everything" to CommandCode.SELECT_ALL,

        // Copy variations
        "copy that" to CommandCode.COPY,
        "copy this" to CommandCode.COPY,
        "copy" to CommandCode.COPY,
        "copy text" to CommandCode.COPY,

        // Paste variations
        "paste" to CommandCode.PASTE,
        "paste that" to CommandCode.PASTE,
        "paste text" to CommandCode.PASTE,

        // Cut variations
        "cut that" to CommandCode.CUT,
        "cut this" to CommandCode.CUT,
        "cut" to CommandCode.CUT,
        "cut text" to CommandCode.CUT,

        // Cancel variations
        "cancel" to CommandCode.CANCEL,
        "cancel that" to CommandCode.CANCEL,
        "never mind" to CommandCode.CANCEL,
        "nevermind" to CommandCode.CANCEL
    )

    /**
     * Sorted patterns for matching (longest first to avoid partial matches)
     */
    private val sortedPatterns: List<Pair<String, CommandCode>> =
        patterns.entries
            .sortedByDescending { it.key.length }
            .map { it.key to it.value }

    /**
     * Parse text to find a command.
     * Returns the first matching command or null.
     */
    fun parse(text: String): CommandCode? {
        val normalized = text.lowercase().trim()
        
        for ((pattern, command) in sortedPatterns) {
            if (normalized == pattern) {
                return command
            }
        }
        return null
    }

    /**
     * Process text to extract command and surrounding text.
     * Handles cases like "hello new line world" -> text="hello", cmd=ENTER, after="world"
     */
    fun processText(text: String): ProcessedSpeech {
        if (text.isBlank()) {
            return ProcessedSpeech.EMPTY
        }

        val normalized = text.lowercase()

        // Try to find a command pattern in the text
        for ((pattern, command) in sortedPatterns) {
            val index = normalized.indexOf(pattern)
            if (index != -1) {
                // Found a command - extract text before and after
                val before = text.substring(0, index).trim()
                val after = text.substring(index + pattern.length).trim()

                return ProcessedSpeech(
                    textBefore = before.ifEmpty { null },
                    command = command,
                    textAfter = after.ifEmpty { null }
                )
            }
        }

        // No command found - return as plain text
        return ProcessedSpeech.textOnly(text)
    }

    /**
     * Check if text contains any command pattern
     */
    fun containsCommand(text: String): Boolean {
        val normalized = text.lowercase()
        return sortedPatterns.any { (pattern, _) ->
            normalized.contains(pattern)
        }
    }

    /**
     * Get all matching commands in text (for complex utterances)
     */
    fun findAllCommands(text: String): List<Pair<CommandCode, IntRange>> {
        val normalized = text.lowercase()
        val results = mutableListOf<Pair<CommandCode, IntRange>>()
        
        for ((pattern, command) in sortedPatterns) {
            var startIndex = 0
            while (true) {
                val index = normalized.indexOf(pattern, startIndex)
                if (index == -1) break
                
                // Check word boundaries to avoid partial matches
                val beforeOk = index == 0 || !normalized[index - 1].isLetter()
                val afterOk = index + pattern.length >= normalized.length ||
                        !normalized[index + pattern.length].isLetter()
                
                if (beforeOk && afterOk) {
                    results.add(command to IntRange(index, index + pattern.length - 1))
                }
                startIndex = index + 1
            }
        }
        
        return results.sortedBy { it.second.first }
    }
}
```

---

### 2.5 BLE Device (domain/model/BleDevice.kt)

```kotlin
package com.example.speech2prompt.domain.model

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice

/**
 * Speech2Prompt BLE service UUID
 */
const val S2P_SERVICE_UUID = "12345678-1234-5678-1234-56789abcdef0"

/**
 * BLE device information for scanning and display
 */
data class BleDeviceInfo(
    val name: String,
    val address: String,
    val rssi: Int,
    val hasS2PService: Boolean,
    val device: BluetoothDevice
) {
    /**
     * Display name (use address if name is empty)
     */
    val displayName: String
        get() = name.ifBlank { address }

    /**
     * Whether this is a Speech2Prompt service device
     */
    val isSpeech2Prompt: Boolean
        get() = hasS2PService || name.contains("Speech2Prompt", ignoreCase = true)

    /**
     * Signal strength description
     */
    val signalStrength: SignalStrength
        get() = when {
            rssi >= -50 -> SignalStrength.EXCELLENT
            rssi >= -60 -> SignalStrength.GOOD
            rssi >= -70 -> SignalStrength.FAIR
            else -> SignalStrength.WEAK
        }

    /**
     * Signal strength as percentage (0-100)
     */
    val signalPercent: Int
        get() = ((100 + rssi).coerceIn(0, 100))

    /**
     * Formatted RSSI string
     */
    val rssiDisplay: String
        get() = "$rssi dBm"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BleDeviceInfo) return false
        return address == other.address
    }

    override fun hashCode(): Int {
        return address.hashCode()
    }

    companion object {
        /**
         * Create from BluetoothDevice with scan result
         */
        @SuppressLint("MissingPermission")
        fun fromDevice(
            device: BluetoothDevice,
            rssi: Int = 0,
            hasS2PService: Boolean = false
        ): BleDeviceInfo {
            return BleDeviceInfo(
                name = device.name ?: "",
                address = device.address,
                rssi = rssi,
                hasS2PService = hasS2PService,
                device = device
            )
        }
    }
}

/**
 * Signal strength categories for UI display
 */
enum class SignalStrength(val bars: Int, val description: String) {
    EXCELLENT(4, "Excellent"),
    GOOD(3, "Good"),
    FAIR(2, "Fair"),
    WEAK(1, "Weak");
}

/**
 * BLE scan result with timestamp
 */
data class BleScanResult(
    val device: BleDeviceInfo,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Age of scan result in milliseconds
     */
    val age: Long
        get() = System.currentTimeMillis() - timestamp

    /**
     * Whether result is stale (older than 10 seconds)
     */
    val isStale: Boolean
        get() = age > 10_000
}
```

---

### 2.6 Paired Device (data/model/PairedDevice.kt)

```kotlin
package com.example.speech2prompt.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Date

/**
 * Represents a device that has been paired with the app.
 * Stored in encrypted preferences.
 */
@Serializable
data class PairedDevice(
    @SerialName("address")
    val address: String,

    @SerialName("name")
    val name: String,

    @SerialName("linux_device_id")
    val linuxDeviceId: String,

    @SerialName("shared_secret")
    val sharedSecret: String, // Base64 encoded AES key

    @SerialName("paired_at")
    val pairedAt: Long,

    @SerialName("last_connected")
    val lastConnected: Long? = null
) {
    /**
     * Display name for UI
     */
    val displayName: String
        get() = name.ifBlank { address }

    /**
     * Pairing date as Date object
     */
    val pairedDate: Date
        get() = Date(pairedAt)

    /**
     * Last connected date as Date object (null if never connected)
     */
    val lastConnectedDate: Date?
        get() = lastConnected?.let { Date(it) }

    /**
     * Whether device has been connected recently (within 24 hours)
     */
    val isRecentlyConnected: Boolean
        get() {
            val last = lastConnected ?: return false
            return System.currentTimeMillis() - last < 24 * 60 * 60 * 1000
        }

    /**
     * Days since last connection
     */
    val daysSinceConnection: Int?
        get() {
            val last = lastConnected ?: return null
            val diff = System.currentTimeMillis() - last
            return (diff / (24 * 60 * 60 * 1000)).toInt()
        }

    /**
     * Create a copy with updated last connected time
     */
    fun withLastConnected(timestamp: Long = System.currentTimeMillis()): PairedDevice {
        return copy(lastConnected = timestamp)
    }

    /**
     * Create a copy with updated name
     */
    fun withName(newName: String): PairedDevice {
        return copy(name = newName)
    }

    companion object {
        /**
         * Create a new paired device entry
         */
        fun create(
            address: String,
            name: String,
            linuxDeviceId: String,
            sharedSecret: String
        ): PairedDevice {
            return PairedDevice(
                address = address,
                name = name,
                linuxDeviceId = linuxDeviceId,
                sharedSecret = sharedSecret,
                pairedAt = System.currentTimeMillis()
            )
        }
    }
}

/**
 * List of paired devices (for serialization)
 */
@Serializable
data class PairedDeviceList(
    @SerialName("devices")
    val devices: List<PairedDevice> = emptyList()
) {
    /**
     * Find device by address
     */
    fun findByAddress(address: String): PairedDevice? {
        return devices.find { it.address == address }
    }

    /**
     * Find device by Linux device ID
     */
    fun findByLinuxId(linuxDeviceId: String): PairedDevice? {
        return devices.find { it.linuxDeviceId == linuxDeviceId }
    }

    /**
     * Add or update device
     */
    fun upsert(device: PairedDevice): PairedDeviceList {
        val updated = devices.filterNot { it.address == device.address } + device
        return copy(devices = updated)
    }

    /**
     * Remove device by address
     */
    fun remove(address: String): PairedDeviceList {
        return copy(devices = devices.filterNot { it.address == address })
    }

    /**
     * Get devices sorted by last connected (most recent first)
     */
    val sortedByRecent: List<PairedDevice>
        get() = devices.sortedByDescending { it.lastConnected ?: it.pairedAt }

    /**
     * Number of paired devices
     */
    val count: Int
        get() = devices.size

    /**
     * Whether any devices are paired
     */
    val isEmpty: Boolean
        get() = devices.isEmpty()
}
```

---

## Unit Tests

### ConnectionStateTest.kt

```kotlin
package com.example.speech2prompt.domain.model

import org.junit.Assert.*
import org.junit.Test

class ConnectionStateTest {

    @Test
    fun `isConnected returns true only for CONNECTED state`() {
        assertTrue(BtConnectionState.CONNECTED.isConnected)
        assertFalse(BtConnectionState.DISCONNECTED.isConnected)
        assertFalse(BtConnectionState.CONNECTING.isConnected)
        assertFalse(BtConnectionState.AWAITING_PAIRING.isConnected)
        assertFalse(BtConnectionState.RECONNECTING.isConnected)
        assertFalse(BtConnectionState.FAILED.isConnected)
    }

    @Test
    fun `isConnecting returns true for in-progress states`() {
        assertTrue(BtConnectionState.CONNECTING.isConnecting)
        assertTrue(BtConnectionState.RECONNECTING.isConnecting)
        assertTrue(BtConnectionState.AWAITING_PAIRING.isConnecting)
        assertFalse(BtConnectionState.CONNECTED.isConnecting)
        assertFalse(BtConnectionState.DISCONNECTED.isConnecting)
        assertFalse(BtConnectionState.FAILED.isConnecting)
    }

    @Test
    fun `canConnect returns true for disconnected or failed states`() {
        assertTrue(BtConnectionState.DISCONNECTED.canConnect)
        assertTrue(BtConnectionState.FAILED.canConnect)
        assertFalse(BtConnectionState.CONNECTED.canConnect)
        assertFalse(BtConnectionState.CONNECTING.canConnect)
        assertFalse(BtConnectionState.RECONNECTING.canConnect)
        assertFalse(BtConnectionState.AWAITING_PAIRING.canConnect)
    }

    @Test
    fun `displayText returns human readable strings`() {
        assertEquals("Connected", BtConnectionState.CONNECTED.displayText)
        assertEquals("Disconnected", BtConnectionState.DISCONNECTED.displayText)
        assertEquals("Connecting...", BtConnectionState.CONNECTING.displayText)
        assertEquals("Awaiting Pairing...", BtConnectionState.AWAITING_PAIRING.displayText)
        assertEquals("Reconnecting...", BtConnectionState.RECONNECTING.displayText)
        assertEquals("Connection Failed", BtConnectionState.FAILED.displayText)
    }

    @Test
    fun `fromString parses valid state names`() {
        assertEquals(BtConnectionState.CONNECTED, BtConnectionState.fromString("CONNECTED"))
        assertEquals(BtConnectionState.CONNECTED, BtConnectionState.fromString("connected"))
        assertEquals(BtConnectionState.DISCONNECTED, BtConnectionState.fromString("DISCONNECTED"))
    }

    @Test
    fun `fromString returns DISCONNECTED for invalid input`() {
        assertEquals(BtConnectionState.DISCONNECTED, BtConnectionState.fromString("invalid"))
        assertEquals(BtConnectionState.DISCONNECTED, BtConnectionState.fromString(""))
    }
}
```

### MessageTest.kt

```kotlin
package com.example.speech2prompt.domain.model

import org.junit.Assert.*
import org.junit.Test

class MessageTest {

    @Test
    fun `text message creation`() {
        val msg = Message.text("Hello world")
        assertEquals(MessageType.TEXT, msg.messageType)
        assertEquals("Hello world", msg.payload)
        assertEquals(PROTOCOL_VERSION, msg.version)
        assertTrue(msg.shouldEncrypt)
    }

    @Test
    fun `command message creation`() {
        val msg = Message.command("ENTER")
        assertEquals(MessageType.COMMAND, msg.messageType)
        assertEquals("ENTER", msg.payload)
        assertTrue(msg.shouldEncrypt)
    }

    @Test
    fun `heartbeat message creation`() {
        val msg = Message.heartbeat()
        assertEquals(MessageType.HEARTBEAT, msg.messageType)
        assertEquals("", msg.payload)
        assertTrue(msg.shouldEncrypt)
    }

    @Test
    fun `ack message creation`() {
        val originalTs = 1234567890L
        val msg = Message.ack(originalTs)
        assertEquals(MessageType.ACK, msg.messageType)
        assertEquals("1234567890", msg.payload)
    }

    @Test
    fun `pair request should not encrypt`() {
        val payload = PairRequestPayload("device123", "My Phone")
        val msg = Message.pairRequest(payload)
        assertEquals(MessageType.PAIR_REQ, msg.messageType)
        assertFalse(msg.shouldEncrypt)
    }

    @Test
    fun `pair ack should not encrypt`() {
        val payload = PairAckPayload.success("device123", "secret123")
        val msg = Message.pairAck(payload)
        assertEquals(MessageType.PAIR_ACK, msg.messageType)
        assertFalse(msg.shouldEncrypt)
    }

    @Test
    fun `toJson and fromJson round trip`() {
        val original = Message.text("Test message")
        val json = original.toJson()
        val parsed = Message.fromJson(json)
        
        assertEquals(original.version, parsed.version)
        assertEquals(original.messageType, parsed.messageType)
        assertEquals(original.payload, parsed.payload)
        assertEquals(original.timestamp, parsed.timestamp)
    }

    @Test
    fun `checksum verification succeeds for valid message`() {
        val msg = Message.text("Test")
        msg.checksum = msg.computeChecksum()
        assertTrue(msg.verifyChecksum())
    }

    @Test
    fun `checksum verification fails for tampered message`() {
        val msg = Message.text("Test")
        msg.checksum = msg.computeChecksum()
        msg.payload = "Tampered"
        assertFalse(msg.verifyChecksum())
    }

    @Test
    fun `MessageType fromValue parses correctly`() {
        assertEquals(MessageType.TEXT, MessageType.fromValue("TEXT"))
        assertEquals(MessageType.COMMAND, MessageType.fromValue("COMMAND"))
        assertEquals(MessageType.HEARTBEAT, MessageType.fromValue("HEARTBEAT"))
        assertEquals(MessageType.ACK, MessageType.fromValue("ACK"))
        assertEquals(MessageType.PAIR_REQ, MessageType.fromValue("PAIR_REQ"))
        assertEquals(MessageType.PAIR_ACK, MessageType.fromValue("PAIR_ACK"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `MessageType fromValue throws for unknown type`() {
        MessageType.fromValue("UNKNOWN")
    }
}
```

### CommandParserTest.kt

```kotlin
package com.example.speech2prompt.domain.model

import org.junit.Assert.*
import org.junit.Test

class CommandParserTest {

    @Test
    fun `parse recognizes enter commands`() {
        assertEquals(CommandCode.ENTER, CommandParser.parse("new line"))
        assertEquals(CommandCode.ENTER, CommandParser.parse("newline"))
        assertEquals(CommandCode.ENTER, CommandParser.parse("enter"))
        assertEquals(CommandCode.ENTER, CommandParser.parse("NEW LINE"))
        assertEquals(CommandCode.ENTER, CommandParser.parse("Enter"))
    }

    @Test
    fun `parse recognizes select all command`() {
        assertEquals(CommandCode.SELECT_ALL, CommandParser.parse("select all"))
        assertEquals(CommandCode.SELECT_ALL, CommandParser.parse("SELECT ALL"))
    }

    @Test
    fun `parse recognizes copy commands`() {
        assertEquals(CommandCode.COPY, CommandParser.parse("copy that"))
        assertEquals(CommandCode.COPY, CommandParser.parse("copy this"))
        assertEquals(CommandCode.COPY, CommandParser.parse("copy"))
    }

    @Test
    fun `parse recognizes paste commands`() {
        assertEquals(CommandCode.PASTE, CommandParser.parse("paste"))
        assertEquals(CommandCode.PASTE, CommandParser.parse("paste that"))
    }

    @Test
    fun `parse recognizes cut commands`() {
        assertEquals(CommandCode.CUT, CommandParser.parse("cut that"))
        assertEquals(CommandCode.CUT, CommandParser.parse("cut this"))
        assertEquals(CommandCode.CUT, CommandParser.parse("cut"))
    }

    @Test
    fun `parse recognizes cancel commands`() {
        assertEquals(CommandCode.CANCEL, CommandParser.parse("cancel"))
        assertEquals(CommandCode.CANCEL, CommandParser.parse("cancel that"))
        assertEquals(CommandCode.CANCEL, CommandParser.parse("never mind"))
        assertEquals(CommandCode.CANCEL, CommandParser.parse("nevermind"))
    }

    @Test
    fun `parse returns null for non-command text`() {
        assertNull(CommandParser.parse("hello world"))
        assertNull(CommandParser.parse("testing"))
        assertNull(CommandParser.parse(""))
    }

    @Test
    fun `processText extracts command with text before`() {
        val result = CommandParser.processText("hello new line")
        assertEquals("hello", result.textBefore)
        assertEquals(CommandCode.ENTER, result.command)
        assertNull(result.textAfter)
        assertTrue(result.hasText)
        assertTrue(result.hasCommand)
        assertFalse(result.hasRemainder)
    }

    @Test
    fun `processText extracts command with text after`() {
        val result = CommandParser.processText("new line world")
        assertNull(result.textBefore)
        assertEquals(CommandCode.ENTER, result.command)
        assertEquals("world", result.textAfter)
        assertTrue(result.hasText)
        assertTrue(result.hasCommand)
        assertTrue(result.hasRemainder)
    }

    @Test
    fun `processText extracts command with text before and after`() {
        val result = CommandParser.processText("hello new line world")
        assertEquals("hello", result.textBefore)
        assertEquals(CommandCode.ENTER, result.command)
        assertEquals("world", result.textAfter)
        assertTrue(result.hasText)
        assertTrue(result.hasCommand)
        assertTrue(result.hasRemainder)
    }

    @Test
    fun `processText returns text only when no command`() {
        val result = CommandParser.processText("hello world")
        assertEquals("hello world", result.textBefore)
        assertNull(result.command)
        assertNull(result.textAfter)
        assertTrue(result.hasText)
        assertFalse(result.hasCommand)
        assertFalse(result.hasRemainder)
    }

    @Test
    fun `processText returns empty for blank input`() {
        val result = CommandParser.processText("")
        assertNull(result.textBefore)
        assertNull(result.command)
        assertNull(result.textAfter)
        assertFalse(result.hasText)
        assertFalse(result.hasCommand)
    }

    @Test
    fun `processText handles command only`() {
        val result = CommandParser.processText("new line")
        assertNull(result.textBefore)
        assertEquals(CommandCode.ENTER, result.command)
        assertNull(result.textAfter)
        assertFalse(result.hasText)
        assertTrue(result.hasCommand)
    }

    @Test
    fun `containsCommand detects commands in text`() {
        assertTrue(CommandParser.containsCommand("please enter something"))
        assertTrue(CommandParser.containsCommand("copy that now"))
        assertFalse(CommandParser.containsCommand("hello world"))
    }

    @Test
    fun `combinedText joins before and after text`() {
        val result = ProcessedSpeech("hello", CommandCode.ENTER, "world")
        assertEquals("hello world", result.combinedText)
    }

    @Test
    fun `combinedText returns null for command-only`() {
        val result = ProcessedSpeech.commandOnly(CommandCode.ENTER)
        assertNull(result.combinedText)
    }
}
```

### PairedDeviceTest.kt

```kotlin
package com.example.speech2prompt.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PairedDeviceTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `create sets current timestamp`() {
        val before = System.currentTimeMillis()
        val device = PairedDevice.create(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Test Device",
            linuxDeviceId = "linux123",
            sharedSecret = "c2VjcmV0"
        )
        val after = System.currentTimeMillis()

        assertTrue(device.pairedAt in before..after)
        assertNull(device.lastConnected)
    }

    @Test
    fun `withLastConnected creates updated copy`() {
        val device = PairedDevice.create(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Test",
            linuxDeviceId = "linux123",
            sharedSecret = "secret"
        )
        
        val updated = device.withLastConnected(1234567890L)
        
        assertEquals(1234567890L, updated.lastConnected)
        assertEquals(device.address, updated.address)
        assertEquals(device.pairedAt, updated.pairedAt)
    }

    @Test
    fun `displayName falls back to address when name is blank`() {
        val device = PairedDevice(
            address = "AA:BB:CC:DD:EE:FF",
            name = "",
            linuxDeviceId = "linux123",
            sharedSecret = "secret",
            pairedAt = 0
        )
        
        assertEquals("AA:BB:CC:DD:EE:FF", device.displayName)
    }

    @Test
    fun `serialization round trip`() {
        val original = PairedDevice.create(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Test Device",
            linuxDeviceId = "linux123",
            sharedSecret = "c2VjcmV0"
        )
        
        val jsonString = json.encodeToString(PairedDevice.serializer(), original)
        val parsed = json.decodeFromString(PairedDevice.serializer(), jsonString)
        
        assertEquals(original.address, parsed.address)
        assertEquals(original.name, parsed.name)
        assertEquals(original.linuxDeviceId, parsed.linuxDeviceId)
        assertEquals(original.sharedSecret, parsed.sharedSecret)
        assertEquals(original.pairedAt, parsed.pairedAt)
    }

    @Test
    fun `PairedDeviceList findByAddress works`() {
        val device1 = PairedDevice.create("AA:AA:AA:AA:AA:AA", "D1", "l1", "s1")
        val device2 = PairedDevice.create("BB:BB:BB:BB:BB:BB", "D2", "l2", "s2")
        val list = PairedDeviceList(listOf(device1, device2))
        
        assertEquals(device1, list.findByAddress("AA:AA:AA:AA:AA:AA"))
        assertEquals(device2, list.findByAddress("BB:BB:BB:BB:BB:BB"))
        assertNull(list.findByAddress("CC:CC:CC:CC:CC:CC"))
    }

    @Test
    fun `PairedDeviceList upsert adds new device`() {
        val device1 = PairedDevice.create("AA:AA:AA:AA:AA:AA", "D1", "l1", "s1")
        val list = PairedDeviceList()
        
        val updated = list.upsert(device1)
        
        assertEquals(1, updated.count)
        assertEquals(device1, updated.findByAddress("AA:AA:AA:AA:AA:AA"))
    }

    @Test
    fun `PairedDeviceList upsert updates existing device`() {
        val device1 = PairedDevice.create("AA:AA:AA:AA:AA:AA", "D1", "l1", "s1")
        val device1Updated = device1.withName("Updated Name")
        val list = PairedDeviceList(listOf(device1))
        
        val updated = list.upsert(device1Updated)
        
        assertEquals(1, updated.count)
        assertEquals("Updated Name", updated.findByAddress("AA:AA:AA:AA:AA:AA")?.name)
    }

    @Test
    fun `PairedDeviceList remove works`() {
        val device1 = PairedDevice.create("AA:AA:AA:AA:AA:AA", "D1", "l1", "s1")
        val device2 = PairedDevice.create("BB:BB:BB:BB:BB:BB", "D2", "l2", "s2")
        val list = PairedDeviceList(listOf(device1, device2))
        
        val updated = list.remove("AA:AA:AA:AA:AA:AA")
        
        assertEquals(1, updated.count)
        assertNull(updated.findByAddress("AA:AA:AA:AA:AA:AA"))
        assertNotNull(updated.findByAddress("BB:BB:BB:BB:BB:BB"))
    }
}
```

---

## Verification Checklist

- [ ] Unit tests for CommandParser.parse() with all patterns
- [ ] Unit tests for CommandParser.processText() with mixed input
- [ ] Unit tests for Message.toJson() / fromJson() round-trip
- [ ] Unit tests for all enum extensions
- [ ] All models compile without errors
- [ ] JSON serialization works correctly for all data classes
- [ ] Checksum computation and verification works
- [ ] PairedDeviceList operations (find, upsert, remove) work correctly

---

## File Structure

```
app/src/main/java/com/example/speech2prompt/
├── domain/
│   └── model/
│       ├── ConnectionState.kt      # BtConnectionState enum + extensions
│       ├── Message.kt              # Message protocol + MessageType
│       ├── PairingPayloads.kt      # PairRequestPayload, PairAckPayload
│       ├── VoiceCommand.kt         # CommandCode, CommandParser, ProcessedSpeech
│       └── BleDevice.kt            # BleDeviceInfo, SignalStrength, BleScanResult
└── data/
    └── model/
        └── PairedDevice.kt         # PairedDevice, PairedDeviceList

app/src/test/java/com/example/speech2prompt/
├── domain/
│   └── model/
│       ├── ConnectionStateTest.kt
│       ├── MessageTest.kt
│       └── CommandParserTest.kt
└── data/
    └── model/
        └── PairedDeviceTest.kt
```

---

## Dependencies Required

Add to `app/build.gradle.kts`:

```kotlin
dependencies {
    // Kotlin Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    
    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:1.9.0")
}
```

Add to module-level `build.gradle.kts`:

```kotlin
plugins {
    kotlin("plugin.serialization") version "1.9.0"
}
```

---

## Notes

1. **Serialization**: Using kotlinx.serialization for JSON handling to match Flutter's json_serializable approach
2. **Extension Properties**: Kotlin idiom for adding computed properties to enums
3. **Companion Objects**: Factory methods in companion objects for message creation
4. **Null Safety**: Explicit handling of nullable fields with safe defaults
5. **Checksum**: MD5 checksum for message integrity (matches Flutter implementation)
