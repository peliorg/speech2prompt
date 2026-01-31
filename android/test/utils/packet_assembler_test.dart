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
import 'package:flutter_test/flutter_test.dart';
import 'package:speech2prompt/utils/packet_assembler.dart';
import 'package:speech2prompt/utils/ble_constants.dart';

void main() {
  group('PacketAssembler', () {
    test('chunks single-packet message correctly', () {
      final data = Uint8List.fromList([1, 2, 3, 4, 5]);
      const mtu = 512;

      final packets = PacketAssembler.chunkMessage(data, mtu);

      expect(packets.length, 1);
      expect(packets[0][0], 0x0C); // FIRST | LAST
      expect(packets[0][1], 0x00); // Seq 0
      expect(packets[0][2], 0x05); // Length low byte
      expect(packets[0][3], 0x00); // Length high byte
      expect(packets[0].sublist(4), data);
    });

    test('chunks multi-packet message correctly', () {
      // Create data that requires multiple packets with small MTU
      final data = Uint8List.fromList(List.generate(100, (i) => i & 0xFF));
      const mtu = 23; // Default BLE MTU

      final packets = PacketAssembler.chunkMessage(data, mtu);

      expect(packets.length, greaterThan(1));

      // First packet should have FIRST flag
      expect(packets[0][0] & BlePacketFlags.first, BlePacketFlags.first);
      expect(packets[0][1], 0); // Seq 0

      // Last packet should have LAST flag
      final lastIdx = packets.length - 1;
      expect(packets[lastIdx][0] & BlePacketFlags.last, BlePacketFlags.last);
      expect(packets[lastIdx][1], lastIdx); // Seq matches index
    });

    test('handles empty data', () {
      final data = Uint8List(0);
      const mtu = 512;

      expect(
        () => PacketAssembler.chunkMessage(data, mtu),
        throwsArgumentError,
      );
    });

    test('respects MTU boundaries', () {
      final data = Uint8List.fromList(List.generate(1000, (i) => i & 0xFF));
      const mtu = 50;

      final packets = PacketAssembler.chunkMessage(data, mtu);

      // Verify no packet exceeds MTU
      for (final packet in packets) {
        expect(packet.length, lessThanOrEqualTo(mtu - BleConfig.attOverhead));
      }
    });
  });

  group('PacketReassembler', () {
    test('reassembles single-packet message', () {
      final reassembler = PacketReassembler();

      // Flags: FIRST | LAST, Seq: 0, Length: 5
      final packet = Uint8List.fromList([
        0x0C,
        0x00,
        0x05,
        0x00,
        1,
        2,
        3,
        4,
        5,
      ]);

      final result = reassembler.addPacket(packet);

      expect(result, isNotNull);
      expect(result, Uint8List.fromList([1, 2, 3, 4, 5]));
      expect(reassembler.isInProgress, false);
    });

    test('reassembles multi-packet message', () {
      final reassembler = PacketReassembler();

      // First packet: 10 bytes total, 5 in this packet
      final packet1 = Uint8List.fromList([
        0x08, 0x00, 0x0A, 0x00, // FIRST, Seq 0, Length 10
        1, 2, 3, 4, 5,
      ]);

      var result = reassembler.addPacket(packet1);
      expect(result, isNull);
      expect(reassembler.isInProgress, true);

      // Last packet: remaining 5 bytes
      final packet2 = Uint8List.fromList([
        0x04, 0x01, // LAST, Seq 1
        6, 7, 8, 9, 10,
      ]);

      result = reassembler.addPacket(packet2);
      expect(result, isNotNull);
      expect(result, Uint8List.fromList([1, 2, 3, 4, 5, 6, 7, 8, 9, 10]));
      expect(reassembler.isInProgress, false);
    });

    test('handles three-packet message', () {
      final reassembler = PacketReassembler();

      final packet1 = Uint8List.fromList([
        0x08, 0x00, 0x0F, 0x00, // FIRST, Seq 0, Length 15
        1, 2, 3, 4, 5,
      ]);
      expect(reassembler.addPacket(packet1), isNull);

      final packet2 = Uint8List.fromList([
        0x00, 0x01, // No flags, Seq 1
        6, 7, 8, 9, 10,
      ]);
      expect(reassembler.addPacket(packet2), isNull);

      final packet3 = Uint8List.fromList([
        0x04, 0x02, // LAST, Seq 2
        11, 12, 13, 14, 15,
      ]);

      final result = reassembler.addPacket(packet3);
      expect(result, isNotNull);
      expect(result!.length, 15);
      expect(result, Uint8List.fromList(List.generate(15, (i) => i + 1)));
    });

    test('detects sequence error', () {
      final reassembler = PacketReassembler();

      final packet1 = Uint8List.fromList([
        0x08,
        0x00,
        0x0A,
        0x00,
        1,
        2,
        3,
        4,
        5,
      ]);
      expect(reassembler.addPacket(packet1), isNull);

      // Wrong sequence (should be 1, but is 2)
      final packet2 = Uint8List.fromList([0x04, 0x02, 6, 7, 8, 9, 10]);

      final result = reassembler.addPacket(packet2);
      expect(result, isNull);
      expect(reassembler.isInProgress, false);
    });

    test('detects length mismatch', () {
      final reassembler = PacketReassembler();

      // Says 10 bytes, but only provides 8 total
      final packet1 = Uint8List.fromList([
        0x08,
        0x00,
        0x0A,
        0x00,
        1,
        2,
        3,
        4,
        5,
      ]);
      expect(reassembler.addPacket(packet1), isNull);

      final packet2 = Uint8List.fromList([0x04, 0x01, 6, 7, 8]);

      final result = reassembler.addPacket(packet2);
      expect(result, isNull);
    });

    test('resets correctly', () {
      final reassembler = PacketReassembler();

      final packet1 = Uint8List.fromList([
        0x08,
        0x00,
        0x0A,
        0x00,
        1,
        2,
        3,
        4,
        5,
      ]);
      reassembler.addPacket(packet1);
      expect(reassembler.isInProgress, true);

      reassembler.reset();
      expect(reassembler.isInProgress, false);
      expect(reassembler.bufferSize, 0);
    });

    test('handles continuation without start', () {
      final reassembler = PacketReassembler();

      // Continuation packet without FIRST
      final packet = Uint8List.fromList([0x00, 0x01, 1, 2, 3]);

      final result = reassembler.addPacket(packet);
      expect(result, isNull);
    });

    test('handles short packets', () {
      final reassembler = PacketReassembler();

      // Packet too short (< 2 bytes)
      final packet = Uint8List.fromList([0x0C]);
      final result = reassembler.addPacket(packet);
      expect(result, isNull);
    });
  });

  group('Round-trip test', () {
    test('chunk and reassemble correctly', () {
      final originalData = Uint8List.fromList(
        List.generate(200, (i) => i & 0xFF),
      );
      const mtu = 23; // Small MTU to force multiple packets

      // Chunk
      final packets = PacketAssembler.chunkMessage(originalData, mtu);
      expect(packets.length, greaterThan(1));

      // Reassemble
      final reassembler = PacketReassembler();
      Uint8List? result;

      for (final packet in packets) {
        final partial = reassembler.addPacket(packet);
        if (partial != null) {
          result = partial;
        }
      }

      expect(result, isNotNull);
      expect(result, originalData);
    });

    test('handles large message', () {
      final originalData = Uint8List.fromList(
        List.generate(2000, (i) => (i * 7) & 0xFF), // Pseudo-random pattern
      );
      const mtu = 512;

      final packets = PacketAssembler.chunkMessage(originalData, mtu);
      final reassembler = PacketReassembler();
      Uint8List? result;

      for (final packet in packets) {
        final partial = reassembler.addPacket(packet);
        if (partial != null) {
          result = partial;
        }
      }

      expect(result, isNotNull);
      expect(result, originalData);
    });
  });
}
