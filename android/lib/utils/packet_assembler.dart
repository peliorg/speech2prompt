// Copyright 2026 Daniel Pelikan
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import 'dart:typed_data';
import 'package:flutter/foundation.dart';
import 'ble_constants.dart';

/// Handles BLE packet chunking and reassembly.
/// 
/// BLE has MTU limitations (typically 20-512 bytes), so messages must be
/// chunked into multiple packets with proper framing.
///
/// Packet format:
/// - First packet: [Flags:1] [Seq:1] [TotalLen:2] [Payload:N]
/// - Continuation: [Flags:1] [Seq:1] [Payload:N]
class PacketAssembler {
  /// Chunk a message into BLE packets.
  /// 
  /// [data] - The complete message bytes to send.
  /// [mtu] - The negotiated MTU size.
  /// Returns a list of packets ready to send over BLE.
  static List<Uint8List> chunkMessage(List<int> data, int mtu) {
    if (data.isEmpty) {
      throw ArgumentError('Cannot chunk empty data');
    }
    
    final packets = <Uint8List>[];
    int offset = 0;
    int seq = 0;
    
    while (offset < data.length) {
      final isFirst = offset == 0;
      final remaining = data.length - offset;
      
      // Calculate payload size for this packet
      final effectivePayload = BleConfig.effectivePayloadSize(mtu, isFirst: isFirst);
      final chunkSize = remaining < effectivePayload ? remaining : effectivePayload;
      final isLast = offset + chunkSize >= data.length;
      
      // Build packet
      final packetBuilder = BytesBuilder();
      
      // Flags byte
      int flags = 0;
      if (isFirst) flags |= BlePacketFlags.first;
      if (isLast) flags |= BlePacketFlags.last;
      packetBuilder.addByte(flags);
      
      // Sequence number
      packetBuilder.addByte(seq & 0xFF);
      seq++;
      
      // Total length (only in first packet)
      if (isFirst) {
        packetBuilder.addByte(data.length & 0xFF);
        packetBuilder.addByte((data.length >> 8) & 0xFF);
      }
      
      // Payload chunk
      packetBuilder.add(data.sublist(offset, offset + chunkSize));
      
      packets.add(packetBuilder.toBytes());
      offset += chunkSize;
    }
    
    return packets;
  }
}

/// Handles reassembly of received BLE packets into complete messages.
class PacketReassembler {
  final List<int> _buffer = [];
  int _expectedLength = 0;
  int _expectedSeq = 0;
  bool _inProgress = false;
  
  /// Process an incoming BLE packet.
  /// 
  /// Returns the complete message bytes if this packet completes a message,
  /// otherwise returns null.
  Uint8List? addPacket(Uint8List packet) {
    if (packet.length < 2) {
      debugPrint('PacketReassembler: Packet too short (${packet.length} bytes)');
      return null;
    }
    
    final flags = packet[0];
    final seq = packet[1];
    final isFirst = (flags & BlePacketFlags.first) != 0;
    final isLast = (flags & BlePacketFlags.last) != 0;
    
    if (isFirst) {
      // Start of new message
      if (packet.length < 4) {
        debugPrint('PacketReassembler: First packet too short');
        return null;
      }
      
      _buffer.clear();
      _expectedLength = packet[2] | (packet[3] << 8);
      _expectedSeq = 0;
      _inProgress = true;
      
      // Payload starts at byte 4 for first packet
      _buffer.addAll(packet.sublist(4));
      
      debugPrint('PacketReassembler: Started message, expecting $_expectedLength bytes');
    } else if (_inProgress) {
      // Continuation packet
      if (seq != _expectedSeq) {
        debugPrint('PacketReassembler: Sequence error (expected $_expectedSeq, got $seq)');
        reset();
        return null;
      }
      
      // Payload starts at byte 2 for continuation packets
      _buffer.addAll(packet.sublist(2));
    } else {
      // Received continuation without start
      debugPrint('PacketReassembler: Received continuation without start');
      return null;
    }
    
    _expectedSeq = (_expectedSeq + 1) & 0xFF;
    
    if (isLast) {
      _inProgress = false;
      
      if (_buffer.length == _expectedLength) {
        debugPrint('PacketReassembler: Message complete ($_expectedLength bytes)');
        final result = Uint8List.fromList(_buffer);
        _buffer.clear();
        return result;
      } else {
        debugPrint('PacketReassembler: Length mismatch (expected $_expectedLength, got ${_buffer.length})');
        reset();
        return null;
      }
    }
    
    return null;
  }
  
  /// Reset the reassembler state.
  void reset() {
    _buffer.clear();
    _expectedLength = 0;
    _expectedSeq = 0;
    _inProgress = false;
  }
  
  /// Check if reassembly is in progress.
  bool get isInProgress => _inProgress;
  
  /// Get current buffer size.
  int get bufferSize => _buffer.length;
}
