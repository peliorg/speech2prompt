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

// BLE service and characteristic UUIDs for Speech2Code.

/// Speech2Code GATT service UUID.
const String speech2codeServiceUuid = 'a1b2c3d4-e5f6-7890-abcd-ef1234567890';

/// Command RX characteristic UUID (Android writes commands here).
/// Properties: Write, Write Without Response
const String commandRxCharUuid = 'a1b2c3d4-e5f6-7890-abcd-ef1234567891';

/// Response TX characteristic UUID (Linux sends responses here).
/// Properties: Notify
const String responseTxCharUuid = 'a1b2c3d4-e5f6-7890-abcd-ef1234567892';

/// Status characteristic UUID (Connection and pairing status).
/// Properties: Read, Notify
const String statusCharUuid = 'a1b2c3d4-e5f6-7890-abcd-ef1234567893';

/// MTU Info characteristic UUID (Current negotiated MTU).
/// Properties: Read
const String mtuInfoCharUuid = 'a1b2c3d4-e5f6-7890-abcd-ef1234567894';

/// BLE packet flags.
class BlePacketFlags {
  static const int first = 0x08; // First packet of message
  static const int last = 0x04; // Last packet of message
  static const int ackReq = 0x02; // Request acknowledgment
}

/// BLE status codes (for Status characteristic).
class BleStatusCode {
  static const int idle = 0x00; // Not paired
  static const int awaitingPairing = 0x01; // Awaiting pairing
  static const int paired = 0x02; // Paired and ready
  static const int busy = 0x03; // Processing command
  static const int error = 0xFF; // Error state
}

/// BLE configuration.
class BleConfig {
  /// Default MTU (minimum for all BLE devices).
  static const int defaultMtu = 23;

  /// Target MTU to negotiate (512 bytes allows ~508 byte payloads).
  static const int targetMtu = 512;

  /// Minimum acceptable MTU.
  static const int minMtu = 23;

  /// ATT protocol overhead (3 bytes).
  static const int attOverhead = 3;

  /// Packet header size (4 bytes for first packet, 2 for continuation).
  static const int headerSizeFirst = 4;
  static const int headerSizeContinuation = 2;

  /// Scan timeout duration.
  static const Duration scanTimeout = Duration(seconds: 15);

  /// Connection timeout.
  static const Duration connectionTimeout = Duration(seconds: 15);

  /// ACK timeout.
  static const Duration ackTimeout = Duration(seconds: 5);

  /// Calculate effective payload size for a given MTU.
  static int effectivePayloadSize(int mtu, {bool isFirst = false}) {
    return mtu -
        attOverhead -
        (isFirst ? headerSizeFirst : headerSizeContinuation);
  }
}
