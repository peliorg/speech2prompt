# Phase 4: BLE Packet Assembly Layer

> Implement BLE packet chunking and reassembly for MTU limitations.

## Goal

BLE has MTU (Maximum Transmission Unit) limitations, typically 23-512 bytes. Messages larger than the MTU must be chunked into multiple packets with proper framing for reliable transmission and reassembly.

---

## BLE Constants (service/ble/BleConstants.kt)

```kotlin
package com.speech2prompt.service.ble

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * BLE service and characteristic UUIDs for Speech2Prompt.
 */
object BleConstants {
    /** Speech2Prompt GATT service UUID */
    const val SERVICE_UUID = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
    
    /** Command RX characteristic UUID (Android writes commands here) */
    const val COMMAND_RX_UUID = "a1b2c3d4-e5f6-7890-abcd-ef1234567891"
    
    /** Response TX characteristic UUID (Linux sends responses here) */
    const val RESPONSE_TX_UUID = "a1b2c3d4-e5f6-7890-abcd-ef1234567892"
    
    /** Status characteristic UUID (Connection and pairing status) */
    const val STATUS_UUID = "a1b2c3d4-e5f6-7890-abcd-ef1234567893"
    
    /** MTU Info characteristic UUID (Current negotiated MTU) */
    const val MTU_INFO_UUID = "a1b2c3d4-e5f6-7890-abcd-ef1234567894"
    
    /** Default MTU (minimum for all BLE devices) */
    const val DEFAULT_MTU = 23
    
    /** Target MTU to negotiate (512 bytes allows ~508 byte payloads) */
    const val TARGET_MTU = 512
    
    /** Minimum acceptable MTU */
    const val MIN_MTU = 23
    
    /** ATT protocol overhead (3 bytes) */
    const val ATT_OVERHEAD = 3
    
    /** Packet header size for first packet: [Flags:1][Seq:1][TotalLen:2] */
    const val HEADER_SIZE_FIRST = 4
    
    /** Packet header size for continuation packets: [Flags:1][Seq:1] */
    const val HEADER_SIZE_CONTINUATION = 2
    
    /** Scan timeout duration */
    val SCAN_TIMEOUT: Duration = 15.seconds
    
    /** Connection timeout */
    val CONNECTION_TIMEOUT: Duration = 15.seconds
    
    /** ACK timeout */
    val ACK_TIMEOUT: Duration = 10.seconds
    
    /** Heartbeat interval */
    val HEARTBEAT_INTERVAL: Duration = 5.seconds
    
    /** Maximum reconnection attempts */
    const val MAX_RECONNECT_ATTEMPTS = 5
}

/**
 * BLE packet flags for message framing.
 * 
 * Flags are stored in the first byte of each packet:
 * - Bit 3 (0x08): FIRST - First packet of a message
 * - Bit 2 (0x04): LAST - Last packet of a message  
 * - Bit 1 (0x02): ACK_REQ - Request acknowledgment (reserved for future use)
 */
object BlePacketFlags {
    /** First packet of message */
    const val FIRST = 0x08
    
    /** Last packet of message */
    const val LAST = 0x04
    
    /** Request acknowledgment */
    const val ACK_REQ = 0x02
}

/**
 * BLE status codes for the Status characteristic.
 * 
 * The Status characteristic is read/notify and indicates the current
 * state of the Linux server:
 */
object BleStatusCode {
    /** Not paired, idle state */
    const val IDLE = 0x00
    
    /** Awaiting pairing PIN entry on Linux side */
    const val AWAITING_PAIRING = 0x01
    
    /** Paired and ready for communication */
    const val PAIRED = 0x02
    
    /** Currently processing a command */
    const val BUSY = 0x03
    
    /** Error state */
    const val ERROR = 0xFF
}
```

---

## PacketAssembler (service/ble/PacketAssembler.kt)

Handles chunking outgoing messages into BLE-sized packets.

### Packet Format

```
First Packet:
┌─────────┬─────────┬───────────────┬─────────────────┐
│ Flags   │ Seq     │ TotalLen      │ Payload         │
│ (1 byte)│ (1 byte)│ (2 bytes, LE) │ (N bytes)       │
└─────────┴─────────┴───────────────┴─────────────────┘

Continuation Packet:
┌─────────┬─────────┬─────────────────────────────────┐
│ Flags   │ Seq     │ Payload                         │
│ (1 byte)│ (1 byte)│ (N bytes)                       │
└─────────┴─────────┴─────────────────────────────────┘
```

### Implementation

```kotlin
package com.speech2prompt.service.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Handles BLE packet chunking for outgoing messages.
 * 
 * BLE has MTU limitations (typically 23-512 bytes), so messages must be
 * chunked into multiple packets with proper framing.
 * 
 * Packet format:
 * - First packet: [Flags:1] [Seq:1] [TotalLen:2 LE] [Payload:N]
 * - Continuation: [Flags:1] [Seq:1] [Payload:N]
 */
object PacketAssembler {
    
    /**
     * Calculate effective payload size for a given MTU.
     * 
     * @param mtu The negotiated MTU size
     * @param isFirst True if this is the first packet (larger header)
     * @return Maximum payload bytes that fit in this packet
     */
    fun effectivePayloadSize(mtu: Int, isFirst: Boolean): Int {
        val headerSize = if (isFirst) {
            BleConstants.HEADER_SIZE_FIRST
        } else {
            BleConstants.HEADER_SIZE_CONTINUATION
        }
        return mtu - BleConstants.ATT_OVERHEAD - headerSize
    }
    
    /**
     * Chunk a message into BLE packets.
     * 
     * @param data The complete message bytes to send
     * @param mtu The negotiated MTU size
     * @return List of packets ready to send over BLE
     * @throws IllegalArgumentException if data is empty or MTU is too small
     */
    fun chunkMessage(data: ByteArray, mtu: Int): List<ByteArray> {
        require(data.isNotEmpty()) { "Cannot chunk empty data" }
        require(mtu >= BleConstants.MIN_MTU) { 
            "MTU must be at least ${BleConstants.MIN_MTU}, got $mtu" 
        }
        
        val packets = mutableListOf<ByteArray>()
        var offset = 0
        var seq = 0
        
        while (offset < data.size) {
            val isFirst = offset == 0
            val remaining = data.size - offset
            
            // Calculate payload size for this packet
            val effectivePayload = effectivePayloadSize(mtu, isFirst)
            require(effectivePayload > 0) {
                "MTU $mtu too small for packet headers"
            }
            
            val chunkSize = minOf(remaining, effectivePayload)
            val isLast = offset + chunkSize >= data.size
            
            // Build packet
            val headerSize = if (isFirst) {
                BleConstants.HEADER_SIZE_FIRST
            } else {
                BleConstants.HEADER_SIZE_CONTINUATION
            }
            
            val packet = ByteArray(headerSize + chunkSize)
            val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
            
            // Flags byte
            var flags = 0
            if (isFirst) flags = flags or BlePacketFlags.FIRST
            if (isLast) flags = flags or BlePacketFlags.LAST
            buffer.put(flags.toByte())
            
            // Sequence number (wraps at 255)
            buffer.put((seq and 0xFF).toByte())
            seq++
            
            // Total length (only in first packet, little-endian)
            if (isFirst) {
                buffer.putShort(data.size.toShort())
            }
            
            // Payload chunk
            buffer.put(data, offset, chunkSize)
            
            packets.add(packet)
            offset += chunkSize
        }
        
        return packets
    }
    
    /**
     * Create a single packet for small messages that fit in one MTU.
     * 
     * This is an optimization for common case where message fits in one packet.
     * 
     * @param data The complete message bytes
     * @param mtu The negotiated MTU size
     * @return Single packet if message fits, null otherwise
     */
    fun createSinglePacket(data: ByteArray, mtu: Int): ByteArray? {
        val maxPayload = effectivePayloadSize(mtu, isFirst = true)
        if (data.size > maxPayload) return null
        
        val packet = ByteArray(BleConstants.HEADER_SIZE_FIRST + data.size)
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        
        // Flags: FIRST | LAST (single packet message)
        buffer.put((BlePacketFlags.FIRST or BlePacketFlags.LAST).toByte())
        
        // Sequence: 0
        buffer.put(0.toByte())
        
        // Total length
        buffer.putShort(data.size.toShort())
        
        // Payload
        buffer.put(data)
        
        return packet
    }
}
```

---

## PacketReassembler (service/ble/PacketReassembler.kt)

Handles reassembly of incoming BLE packets into complete messages.

### Implementation

```kotlin
package com.speech2prompt.service.ble

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Handles reassembly of received BLE packets into complete messages.
 * 
 * This class is NOT thread-safe. Callers should ensure synchronized access
 * or use from a single thread (typically the BLE callback thread).
 */
class PacketReassembler {
    
    companion object {
        private const val TAG = "PacketReassembler"
    }
    
    private val buffer = mutableListOf<Byte>()
    private var expectedLength = 0
    private var expectedSeq = 0
    private var inProgress = false
    
    /**
     * Check if reassembly is currently in progress.
     */
    val isInProgress: Boolean
        get() = inProgress
    
    /**
     * Get current buffer size (bytes received so far).
     */
    val bufferSize: Int
        get() = buffer.size
    
    /**
     * Get expected total message length.
     * Only valid when [isInProgress] is true.
     */
    val expectedTotalLength: Int
        get() = expectedLength
    
    /**
     * Process an incoming BLE packet.
     * 
     * @param packet Raw bytes received from BLE characteristic
     * @return Complete message bytes if this packet completes a message, null otherwise
     */
    fun addPacket(packet: ByteArray): ByteArray? {
        if (packet.size < 2) {
            Log.w(TAG, "Packet too short (${packet.size} bytes)")
            return null
        }
        
        val flags = packet[0].toInt() and 0xFF
        val seq = packet[1].toInt() and 0xFF
        val isFirst = (flags and BlePacketFlags.FIRST) != 0
        val isLast = (flags and BlePacketFlags.LAST) != 0
        
        if (isFirst) {
            // Start of new message
            if (packet.size < BleConstants.HEADER_SIZE_FIRST) {
                Log.w(TAG, "First packet too short (${packet.size} bytes)")
                return null
            }
            
            // Parse total length (little-endian)
            val lengthBuffer = ByteBuffer.wrap(packet, 2, 2).order(ByteOrder.LITTLE_ENDIAN)
            expectedLength = lengthBuffer.short.toInt() and 0xFFFF
            
            // Reset state for new message
            buffer.clear()
            expectedSeq = 0
            inProgress = true
            
            // Payload starts at byte 4 for first packet
            for (i in BleConstants.HEADER_SIZE_FIRST until packet.size) {
                buffer.add(packet[i])
            }
            
            Log.d(TAG, "Started message, expecting $expectedLength bytes, got ${buffer.size}")
            
        } else if (inProgress) {
            // Continuation packet
            if (seq != expectedSeq) {
                Log.w(TAG, "Sequence error (expected $expectedSeq, got $seq)")
                reset()
                return null
            }
            
            // Payload starts at byte 2 for continuation packets
            for (i in BleConstants.HEADER_SIZE_CONTINUATION until packet.size) {
                buffer.add(packet[i])
            }
            
            Log.d(TAG, "Added continuation packet, buffer now ${buffer.size}/$expectedLength bytes")
            
        } else {
            // Received continuation without start
            Log.w(TAG, "Received continuation packet without start (seq=$seq)")
            return null
        }
        
        // Increment expected sequence (wraps at 256)
        expectedSeq = (expectedSeq + 1) and 0xFF
        
        // Check if message is complete
        if (isLast) {
            inProgress = false
            
            if (buffer.size == expectedLength) {
                Log.d(TAG, "Message complete ($expectedLength bytes)")
                val result = buffer.toByteArray()
                buffer.clear()
                return result
            } else {
                Log.w(TAG, "Length mismatch (expected $expectedLength, got ${buffer.size})")
                reset()
                return null
            }
        }
        
        return null
    }
    
    /**
     * Reset the reassembler state.
     * 
     * Call this when starting fresh or after an error condition.
     */
    fun reset() {
        buffer.clear()
        expectedLength = 0
        expectedSeq = 0
        inProgress = false
    }
}
```

---

## Unit Tests (service/ble/PacketAssemblerTest.kt)

```kotlin
package com.speech2prompt.service.ble

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class PacketAssemblerTest {
    
    @Test
    fun `effectivePayloadSize calculates correctly for first packet`() {
        // MTU 23 - 3 ATT overhead - 4 header = 16 bytes
        assertThat(PacketAssembler.effectivePayloadSize(23, isFirst = true)).isEqualTo(16)
        
        // MTU 512 - 3 ATT overhead - 4 header = 505 bytes
        assertThat(PacketAssembler.effectivePayloadSize(512, isFirst = true)).isEqualTo(505)
    }
    
    @Test
    fun `effectivePayloadSize calculates correctly for continuation packet`() {
        // MTU 23 - 3 ATT overhead - 2 header = 18 bytes
        assertThat(PacketAssembler.effectivePayloadSize(23, isFirst = false)).isEqualTo(18)
        
        // MTU 512 - 3 ATT overhead - 2 header = 507 bytes
        assertThat(PacketAssembler.effectivePayloadSize(512, isFirst = false)).isEqualTo(507)
    }
    
    @Test
    fun `chunkMessage creates single packet for small message`() {
        val data = "Hello".toByteArray()
        val packets = PacketAssembler.chunkMessage(data, mtu = 512)
        
        assertThat(packets).hasSize(1)
        
        val packet = packets[0]
        // Flags: FIRST | LAST = 0x0C
        assertThat(packet[0].toInt() and 0xFF).isEqualTo(0x0C)
        // Seq: 0
        assertThat(packet[1].toInt() and 0xFF).isEqualTo(0)
        // Length: 5 (little-endian)
        assertThat(packet[2].toInt() and 0xFF).isEqualTo(5)
        assertThat(packet[3].toInt() and 0xFF).isEqualTo(0)
        // Payload
        assertThat(packet.sliceArray(4 until packet.size)).isEqualTo(data)
    }
    
    @Test
    fun `chunkMessage creates multiple packets for large message`() {
        // Create message larger than one MTU can hold
        val data = ByteArray(50) { it.toByte() }
        val mtu = 23 // Effective payload: 16 first, 18 continuation
        
        val packets = PacketAssembler.chunkMessage(data, mtu)
        
        // 50 bytes / (16 first + 18 continuation) = 3 packets
        // First: 16 bytes, Second: 18 bytes, Third: 16 bytes
        assertThat(packets.size).isAtLeast(2)
        
        // First packet should have FIRST flag
        assertThat(packets[0][0].toInt() and BlePacketFlags.FIRST).isNotEqualTo(0)
        
        // Last packet should have LAST flag
        assertThat(packets.last()[0].toInt() and BlePacketFlags.LAST).isNotEqualTo(0)
        
        // Middle packets (if any) should have neither
        for (i in 1 until packets.size - 1) {
            val flags = packets[i][0].toInt() and 0xFF
            assertThat(flags and BlePacketFlags.FIRST).isEqualTo(0)
            assertThat(flags and BlePacketFlags.LAST).isEqualTo(0)
        }
    }
    
    @Test
    fun `chunkMessage sequence numbers increment correctly`() {
        val data = ByteArray(100) { it.toByte() }
        val packets = PacketAssembler.chunkMessage(data, mtu = 23)
        
        for ((index, packet) in packets.withIndex()) {
            assertThat(packet[1].toInt() and 0xFF).isEqualTo(index)
        }
    }
    
    @Test
    fun `chunkMessage handles exact MTU boundary`() {
        // Create message that exactly fills one packet
        val payloadSize = PacketAssembler.effectivePayloadSize(512, isFirst = true)
        val data = ByteArray(payloadSize) { it.toByte() }
        
        val packets = PacketAssembler.chunkMessage(data, mtu = 512)
        
        assertThat(packets).hasSize(1)
        assertThat(packets[0][0].toInt() and 0xFF).isEqualTo(
            BlePacketFlags.FIRST or BlePacketFlags.LAST
        )
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun `chunkMessage throws on empty data`() {
        PacketAssembler.chunkMessage(ByteArray(0), mtu = 512)
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun `chunkMessage throws on MTU below minimum`() {
        PacketAssembler.chunkMessage("test".toByteArray(), mtu = 10)
    }
    
    @Test
    fun `createSinglePacket returns null for large message`() {
        val data = ByteArray(1000) { it.toByte() }
        val result = PacketAssembler.createSinglePacket(data, mtu = 512)
        assertThat(result).isNull()
    }
    
    @Test
    fun `createSinglePacket works for small message`() {
        val data = "Hi".toByteArray()
        val result = PacketAssembler.createSinglePacket(data, mtu = 512)
        
        assertThat(result).isNotNull()
        assertThat(result!![0].toInt() and 0xFF).isEqualTo(
            BlePacketFlags.FIRST or BlePacketFlags.LAST
        )
    }
}
```

---

## Unit Tests (service/ble/PacketReassemblerTest.kt)

```kotlin
package com.speech2prompt.service.ble

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class PacketReassemblerTest {
    
    private lateinit var reassembler: PacketReassembler
    
    @Before
    fun setup() {
        reassembler = PacketReassembler()
    }
    
    @Test
    fun `addPacket reassembles single packet message`() {
        val payload = "Hello".toByteArray()
        val packet = byteArrayOf(
            0x0C.toByte(), // FIRST | LAST
            0x00,          // seq = 0
            0x05, 0x00,    // length = 5 (little-endian)
            *payload
        )
        
        val result = reassembler.addPacket(packet)
        
        assertThat(result).isNotNull()
        assertThat(result).isEqualTo(payload)
        assertThat(reassembler.isInProgress).isFalse()
    }
    
    @Test
    fun `addPacket reassembles multi-packet message`() {
        val payload = "Hello, World!".toByteArray() // 13 bytes
        
        // First packet: 5 bytes of payload
        val packet1 = byteArrayOf(
            0x08.toByte(), // FIRST only
            0x00,          // seq = 0
            0x0D, 0x00,    // length = 13
            *payload.sliceArray(0 until 5)
        )
        
        // Middle packet: 5 bytes of payload
        val packet2 = byteArrayOf(
            0x00.toByte(), // no flags
            0x01,          // seq = 1
            *payload.sliceArray(5 until 10)
        )
        
        // Last packet: 3 bytes of payload
        val packet3 = byteArrayOf(
            0x04.toByte(), // LAST only
            0x02,          // seq = 2
            *payload.sliceArray(10 until 13)
        )
        
        assertThat(reassembler.addPacket(packet1)).isNull()
        assertThat(reassembler.isInProgress).isTrue()
        assertThat(reassembler.bufferSize).isEqualTo(5)
        
        assertThat(reassembler.addPacket(packet2)).isNull()
        assertThat(reassembler.bufferSize).isEqualTo(10)
        
        val result = reassembler.addPacket(packet3)
        assertThat(result).isNotNull()
        assertThat(result).isEqualTo(payload)
        assertThat(reassembler.isInProgress).isFalse()
    }
    
    @Test
    fun `addPacket handles sequence number wrapping`() {
        reassembler = PacketReassembler()
        
        // Simulate receiving packets with sequence wrapping at 255
        val packet254 = byteArrayOf(
            0x08.toByte(), // FIRST
            0x00,          // seq = 0
            0x02, 0x00,    // length = 2
            0x41           // 'A'
        )
        
        assertThat(reassembler.addPacket(packet254)).isNull()
        
        val packet255 = byteArrayOf(
            0x04.toByte(), // LAST
            0x01,          // seq = 1
            0x42           // 'B'
        )
        
        val result = reassembler.addPacket(packet255)
        assertThat(result).isEqualTo("AB".toByteArray())
    }
    
    @Test
    fun `addPacket rejects out-of-order packet`() {
        val packet1 = byteArrayOf(
            0x08.toByte(), // FIRST
            0x00,
            0x0A, 0x00,    // length = 10
            0x01, 0x02, 0x03
        )
        
        val packetWrongSeq = byteArrayOf(
            0x04.toByte(), // LAST
            0x05,          // wrong seq (should be 1)
            0x04, 0x05, 0x06
        )
        
        reassembler.addPacket(packet1)
        val result = reassembler.addPacket(packetWrongSeq)
        
        assertThat(result).isNull()
        assertThat(reassembler.isInProgress).isFalse() // Reset due to error
    }
    
    @Test
    fun `addPacket rejects continuation without start`() {
        val continuationPacket = byteArrayOf(
            0x00.toByte(), // no flags (continuation)
            0x01,
            0x01, 0x02, 0x03
        )
        
        val result = reassembler.addPacket(continuationPacket)
        
        assertThat(result).isNull()
        assertThat(reassembler.isInProgress).isFalse()
    }
    
    @Test
    fun `addPacket detects length mismatch`() {
        val packet = byteArrayOf(
            0x0C.toByte(), // FIRST | LAST
            0x00,
            0x0A, 0x00,    // claims length = 10
            0x01, 0x02     // but only 2 bytes of payload
        )
        
        val result = reassembler.addPacket(packet)
        
        assertThat(result).isNull()
        assertThat(reassembler.isInProgress).isFalse()
    }
    
    @Test
    fun `reset clears all state`() {
        val packet = byteArrayOf(
            0x08.toByte(), // FIRST (incomplete message)
            0x00,
            0x64, 0x00,    // length = 100
            0x01, 0x02
        )
        
        reassembler.addPacket(packet)
        assertThat(reassembler.isInProgress).isTrue()
        
        reassembler.reset()
        
        assertThat(reassembler.isInProgress).isFalse()
        assertThat(reassembler.bufferSize).isEqualTo(0)
    }
    
    @Test
    fun `addPacket rejects packet too short`() {
        val shortPacket = byteArrayOf(0x0C.toByte()) // Only 1 byte
        
        val result = reassembler.addPacket(shortPacket)
        
        assertThat(result).isNull()
    }
    
    @Test
    fun `addPacket rejects first packet with incomplete header`() {
        val shortFirstPacket = byteArrayOf(
            0x08.toByte(), // FIRST
            0x00,
            0x05           // Only 1 byte of length (need 2)
        )
        
        val result = reassembler.addPacket(shortFirstPacket)
        
        assertThat(result).isNull()
        assertThat(reassembler.isInProgress).isFalse()
    }
}
```

---

## Integration Test (service/ble/PacketRoundTripTest.kt)

```kotlin
package com.speech2prompt.service.ble

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

/**
 * Integration tests verifying packet assembly and reassembly work together.
 */
class PacketRoundTripTest {
    
    @Test
    fun `round trip small message`() {
        val original = "Hello, World!".toByteArray()
        val mtu = 512
        
        val packets = PacketAssembler.chunkMessage(original, mtu)
        val reassembler = PacketReassembler()
        
        var result: ByteArray? = null
        for (packet in packets) {
            result = reassembler.addPacket(packet)
        }
        
        assertThat(result).isNotNull()
        assertThat(result).isEqualTo(original)
    }
    
    @Test
    fun `round trip large message with minimum MTU`() {
        val original = ByteArray(1000) { it.toByte() }
        val mtu = BleConstants.MIN_MTU
        
        val packets = PacketAssembler.chunkMessage(original, mtu)
        val reassembler = PacketReassembler()
        
        var result: ByteArray? = null
        for (packet in packets) {
            result = reassembler.addPacket(packet)
        }
        
        assertThat(result).isNotNull()
        assertThat(result).isEqualTo(original)
    }
    
    @Test
    fun `round trip message exactly at MTU boundary`() {
        val payloadSize = PacketAssembler.effectivePayloadSize(256, isFirst = true)
        val original = ByteArray(payloadSize) { it.toByte() }
        
        val packets = PacketAssembler.chunkMessage(original, mtu = 256)
        val reassembler = PacketReassembler()
        
        assertThat(packets).hasSize(1)
        
        val result = reassembler.addPacket(packets[0])
        assertThat(result).isEqualTo(original)
    }
    
    @Test
    fun `round trip message one byte over MTU boundary`() {
        val payloadSize = PacketAssembler.effectivePayloadSize(256, isFirst = true) + 1
        val original = ByteArray(payloadSize) { it.toByte() }
        
        val packets = PacketAssembler.chunkMessage(original, mtu = 256)
        val reassembler = PacketReassembler()
        
        assertThat(packets).hasSize(2)
        
        var result: ByteArray? = null
        for (packet in packets) {
            result = reassembler.addPacket(packet)
        }
        assertThat(result).isEqualTo(original)
    }
    
    @Test
    fun `round trip with various MTU sizes`() {
        val original = ByteArray(500) { Random.nextInt(256).toByte() }
        val mtuSizes = listOf(23, 50, 100, 185, 256, 512)
        
        for (mtu in mtuSizes) {
            val packets = PacketAssembler.chunkMessage(original, mtu)
            val reassembler = PacketReassembler()
            
            var result: ByteArray? = null
            for (packet in packets) {
                result = reassembler.addPacket(packet)
            }
            
            assertThat(result)
                .named("MTU $mtu")
                .isEqualTo(original)
        }
    }
    
    @Test
    fun `round trip JSON message`() {
        val json = """{"v":1,"t":"TEXT","p":"Hello","ts":1234567890,"cs":"abc123"}"""
        val original = json.toByteArray(Charsets.UTF_8)
        
        val packets = PacketAssembler.chunkMessage(original, mtu = 50)
        val reassembler = PacketReassembler()
        
        var result: ByteArray? = null
        for (packet in packets) {
            result = reassembler.addPacket(packet)
        }
        
        assertThat(result).isNotNull()
        assertThat(String(result!!, Charsets.UTF_8)).isEqualTo(json)
    }
}
```

---

## Verification Checklist

- [ ] Chunk large message and reassemble correctly
- [ ] Handle single-packet messages (FIRST | LAST flags)
- [ ] Handle max MTU boundary cases (exact fit, one byte over)
- [ ] Sequence number wrapping at 255
- [ ] Reject out-of-order packets
- [ ] Reject continuation packets without start
- [ ] Detect length mismatches on completion
- [ ] Handle minimum MTU (23 bytes)
- [ ] Round-trip test with various MTU sizes
- [ ] UTF-8 JSON messages preserved correctly

---

## Estimated Time: 0.5 day

The packet assembly layer is straightforward with well-defined behavior. Most time will be spent on edge case testing.
