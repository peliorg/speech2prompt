import 'package:flutter_test/flutter_test.dart';
import 'package:speech2code/utils/encryption.dart';

void main() {
  group('Encryption Tests', () {
    test('Key derivation produces 32 bytes', () {
      final key = deriveKey('123456', 'android-test', 'linux-test');
      expect(key.length, 32);
    });

    test('Same inputs produce same key', () {
      final key1 = deriveKey('123456', 'android-test', 'linux-test');
      final key2 = deriveKey('123456', 'android-test', 'linux-test');
      expect(key1, key2);
    });

    test('Different inputs produce different keys', () {
      final key1 = deriveKey('123456', 'android-test', 'linux-test');
      final key2 = deriveKey('654321', 'android-test', 'linux-test');
      expect(key1, isNot(key2));
    });

    test('Encrypt and decrypt roundtrip', () {
      final key = deriveKey('123456', 'android-test', 'linux-test');
      const plaintext = 'Hello, World!';

      final encrypted = encryptAesGcm(plaintext, key);
      final decrypted = decryptAesGcm(encrypted, key);

      expect(decrypted, plaintext);
    });

    test('Checksum is 8 characters', () {
      final key = deriveKey('123456', 'android-test', 'linux-test');
      final checksum = calculateChecksum(1, 'TEXT', 'payload', 12345, key);
      expect(checksum.length, 8);
    });

    test('Checksum is deterministic', () {
      final key = deriveKey('123456', 'android-test', 'linux-test');
      final cs1 = calculateChecksum(1, 'TEXT', 'payload', 12345, key);
      final cs2 = calculateChecksum(1, 'TEXT', 'payload', 12345, key);
      expect(cs1, cs2);
    });
  });
}
