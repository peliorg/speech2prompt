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

import 'dart:convert';

/// Protocol version.
const int protocolVersion = 1;

/// Message types supported by the protocol.
enum MessageType {
  text('TEXT'),
  command('COMMAND'),
  heartbeat('HEARTBEAT'),
  ack('ACK'),
  pairReq('PAIR_REQ'),
  pairAck('PAIR_ACK');

  final String value;
  const MessageType(this.value);

  static MessageType fromString(String s) {
    return MessageType.values.firstWhere(
      (e) => e.value == s,
      orElse: () => throw ArgumentError('Unknown message type: $s'),
    );
  }
}

/// Protocol message structure.
class Message {
  final int version;
  final MessageType messageType;
  String payload;
  final int timestamp;
  String checksum;

  Message({
    required this.version,
    required this.messageType,
    required this.payload,
    required this.timestamp,
    this.checksum = '',
  });

  /// Create a new message with automatic timestamp.
  factory Message.create(MessageType type, String payload) {
    return Message(
      version: protocolVersion,
      messageType: type,
      payload: payload,
      timestamp: DateTime.now().millisecondsSinceEpoch,
    );
  }

  /// Create a TEXT message.
  factory Message.text(String content) => Message.create(MessageType.text, content);

  /// Create a COMMAND message.
  factory Message.command(String cmd) => Message.create(MessageType.command, cmd);

  /// Create a HEARTBEAT message.
  factory Message.heartbeat() => Message.create(MessageType.heartbeat, '');

  /// Create an ACK message.
  factory Message.ack(int originalTimestamp) =>
      Message.create(MessageType.ack, originalTimestamp.toString());

  /// Create a PAIR_REQ message.
  factory Message.pairRequest(PairRequestPayload payload) =>
      Message.create(MessageType.pairReq, payload.toJson());

  /// Create a PAIR_ACK message.
  factory Message.pairAck(PairAckPayload payload) =>
      Message.create(MessageType.pairAck, payload.toJson());

  /// Serialize to JSON string with newline delimiter.
  String toJson() {
    final map = {
      'v': version,
      't': messageType.value,
      'p': payload,
      'ts': timestamp,
      'cs': checksum,
    };
    return '${jsonEncode(map)}\n';
  }

  /// Parse from JSON string.
  factory Message.fromJson(String json) {
    final map = jsonDecode(json.trim()) as Map<String, dynamic>;
    return Message(
      version: map['v'] as int,
      messageType: MessageType.fromString(map['t'] as String),
      payload: map['p'] as String,
      timestamp: map['ts'] as int,
      checksum: map['cs'] as String? ?? '',
    );
  }

  /// Check if payload should be encrypted.
  bool get shouldEncrypt {
    return messageType == MessageType.text ||
        messageType == MessageType.command ||
        messageType == MessageType.pairReq ||
        messageType == MessageType.pairAck;
  }
}

/// Pairing request payload.
class PairRequestPayload {
  final String deviceId;
  final String? deviceName;

  PairRequestPayload({
    required this.deviceId,
    this.deviceName,
  });

  String toJson() {
    final map = <String, dynamic>{
      'device_id': deviceId,
    };
    if (deviceName != null) {
      map['device_name'] = deviceName;
    }
    return jsonEncode(map);
  }

  factory PairRequestPayload.fromJson(String json) {
    final map = jsonDecode(json) as Map<String, dynamic>;
    return PairRequestPayload(
      deviceId: map['device_id'] as String,
      deviceName: map['device_name'] as String?,
    );
  }
}

/// Pairing acknowledgment payload.
class PairAckPayload {
  final String deviceId;
  final PairStatus status;
  final String? error;

  PairAckPayload({
    required this.deviceId,
    required this.status,
    this.error,
  });

  factory PairAckPayload.success(String deviceId) => PairAckPayload(
        deviceId: deviceId,
        status: PairStatus.ok,
      );

  factory PairAckPayload.error(String deviceId, String error) => PairAckPayload(
        deviceId: deviceId,
        status: PairStatus.error,
        error: error,
      );

  String toJson() {
    final map = <String, dynamic>{
      'device_id': deviceId,
      'status': status == PairStatus.ok ? 'ok' : 'error',
    };
    if (error != null) {
      map['error'] = error;
    }
    return jsonEncode(map);
  }

  factory PairAckPayload.fromJson(String json) {
    final map = jsonDecode(json) as Map<String, dynamic>;
    return PairAckPayload(
      deviceId: map['device_id'] as String,
      status: map['status'] == 'ok' ? PairStatus.ok : PairStatus.error,
      error: map['error'] as String?,
    );
  }
}

enum PairStatus { ok, error }
