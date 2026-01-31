import 'package:flutter_test/flutter_test.dart';
import 'package:speech2code/models/message.dart';
import 'package:speech2code/utils/encryption.dart';

void main() {
  group('Message', () {
    test('creates text message correctly', () {
      final msg = Message.text('Hello, World!');
      
      expect(msg.version, 1);
      expect(msg.messageType, MessageType.text);
      expect(msg.payload, 'Hello, World!');
      expect(msg.timestamp, greaterThan(0));
    });

    test('serializes to JSON with newline', () {
      final msg = Message.text('test');
      final json = msg.toJson();
      
      expect(json, contains('"v":1'));
      expect(json, contains('"t":"TEXT"'));
      expect(json, contains('"p":"test"'));
      expect(json, endsWith('\n'));
    });

    test('parses from JSON', () {
      final original = Message.text('test');
      final json = original.toJson();
      final parsed = Message.fromJson(json);
      
      expect(parsed.version, original.version);
      expect(parsed.messageType, original.messageType);
      expect(parsed.payload, original.payload);
    });

    test('shouldEncrypt returns true for sensitive types', () {
      expect(Message.text('').shouldEncrypt, true);
      expect(Message.command('').shouldEncrypt, true);
      expect(Message.heartbeat().shouldEncrypt, false);
      expect(Message.ack(123).shouldEncrypt, false);
    });
  });

  group('PairRequestPayload', () {
    test('serializes and deserializes', () {
      final payload = PairRequestPayload(
        deviceId: 'android-123',
        deviceName: 'My Phone',
      );
      
      final json = payload.toJson();
      final parsed = PairRequestPayload.fromJson(json);
      
      expect(parsed.deviceId, 'android-123');
      expect(parsed.deviceName, 'My Phone');
    });
  });

  group('PairAckPayload', () {
    test('creates success payload', () {
      final payload = PairAckPayload.success('linux-456');
      
      expect(payload.deviceId, 'linux-456');
      expect(payload.status, PairStatus.ok);
      expect(payload.error, isNull);
    });

    test('creates error payload', () {
      final payload = PairAckPayload.error('linux-456', 'Connection failed');
      
      expect(payload.deviceId, 'linux-456');
      expect(payload.status, PairStatus.error);
      expect(payload.error, 'Connection failed');
    });
  });
}
