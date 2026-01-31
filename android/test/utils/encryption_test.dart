import 'package:flutter_test/flutter_test.dart';
import 'package:speech2code/utils/encryption.dart';
import 'package:speech2code/models/message.dart';

void main() {
  group('Key Derivation', () {
    test('derives same key from same inputs', () {
      final key1 = deriveKey('123456', 'android-abc', 'linux-xyz');
      final key2 = deriveKey('123456', 'android-abc', 'linux-xyz');

      expect(key1, equals(key2));
    });

    test('derives different keys from different inputs', () {
      final key1 = deriveKey('123456', 'android-abc', 'linux-xyz');
      final key2 = deriveKey('654321', 'android-abc', 'linux-xyz');

      expect(key1, isNot(equals(key2)));
    });

    test('derives 32-byte key', () {
      final key = deriveKey('123456', 'android-abc', 'linux-xyz');
      expect(key.length, 32);
    });
  });

  group('AES-GCM Encryption', () {
    test('encrypts and decrypts successfully', () {
      final key = deriveKey('123456', 'android-abc', 'linux-xyz');
      const plaintext = 'Hello, World!';

      final encrypted = encryptAesGcm(plaintext, key);
      final decrypted = decryptAesGcm(encrypted, key);

      expect(decrypted, plaintext);
      expect(encrypted, isNot(equals(plaintext)));
    });

    test('produces different ciphertext each time', () {
      final key = deriveKey('123456', 'android-abc', 'linux-xyz');
      const plaintext = 'test';

      final encrypted1 = encryptAesGcm(plaintext, key);
      final encrypted2 = encryptAesGcm(plaintext, key);

      // Should be different due to random nonce
      expect(encrypted1, isNot(equals(encrypted2)));

      // But both should decrypt to same plaintext
      expect(decryptAesGcm(encrypted1, key), plaintext);
      expect(decryptAesGcm(encrypted2, key), plaintext);
    });
  });

  group('Checksum', () {
    test('calculates consistent checksum', () {
      final key = deriveKey('123456', 'android-abc', 'linux-xyz');
      final cs1 = calculateChecksum(1, 'TEXT', 'hello', 1234567890, key);
      final cs2 = calculateChecksum(1, 'TEXT', 'hello', 1234567890, key);

      expect(cs1, cs2);
      expect(cs1.length, 8);
    });

    test('produces different checksum for different data', () {
      final key = deriveKey('123456', 'android-abc', 'linux-xyz');
      final cs1 = calculateChecksum(1, 'TEXT', 'hello', 1234567890, key);
      final cs2 = calculateChecksum(1, 'TEXT', 'world', 1234567890, key);

      expect(cs1, isNot(equals(cs2)));
    });
  });

  group('CryptoContext', () {
    test('encrypts and decrypts message', () {
      final ctx = CryptoContext.fromPin('123456', 'android-abc', 'linux-xyz');

      final encrypted = ctx.encrypt('test message');
      final decrypted = ctx.decrypt(encrypted);

      expect(decrypted, 'test message');
    });

    test('signs message correctly', () {
      final ctx = CryptoContext.fromPin('123456', 'android-abc', 'linux-xyz');

      final msg = Message.text('test');
      ctx.sign(msg);

      expect(msg.checksum, isNotEmpty);
      expect(ctx.verify(msg), true);
    });

    test('detects tampered message', () {
      final ctx = CryptoContext.fromPin('123456', 'android-abc', 'linux-xyz');

      final msg = Message.text('test');
      ctx.sign(msg);

      // Tamper with payload
      msg.payload = 'tampered';

      expect(ctx.verify(msg), false);
    });

    test('signs and encrypts message', () {
      final ctx = CryptoContext.fromPin('123456', 'android-abc', 'linux-xyz');

      final msg = Message.text('secret message');
      final originalPayload = msg.payload;

      ctx.signAndEncrypt(msg);

      expect(msg.payload, isNot(equals(originalPayload)));
      expect(msg.checksum, isNotEmpty);
    });

    test('verifies and decrypts message', () {
      final ctx = CryptoContext.fromPin('123456', 'android-abc', 'linux-xyz');

      final msg = Message.text('secret message');
      final originalPayload = msg.payload;

      ctx.signAndEncrypt(msg);
      ctx.verifyAndDecrypt(msg);

      expect(msg.payload, originalPayload);
    });
  });
}
