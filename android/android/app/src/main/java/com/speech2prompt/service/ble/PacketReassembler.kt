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
