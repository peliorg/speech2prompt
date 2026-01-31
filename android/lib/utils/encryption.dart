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
import 'dart:math';
import 'dart:typed_data';
import 'package:crypto/crypto.dart';
import 'package:pointycastle/export.dart';
import '../models/message.dart';

/// Number of PBKDF2 iterations.
const int _pbkdf2Iterations = 100000;

/// Salt for key derivation.
final Uint8List _salt = utf8.encode('speech2code_v1');

/// Cryptographic context for a paired session.
class CryptoContext {
  final Uint8List _key;

  CryptoContext._(this._key);

  /// Create from raw key bytes.
  factory CryptoContext.fromKey(Uint8List key) {
    if (key.length != 32) {
      throw ArgumentError('Key must be 32 bytes');
    }
    return CryptoContext._(key);
  }

  /// Derive from PIN and device IDs.
  factory CryptoContext.fromPin(String pin, String androidId, String linuxId) {
    final key = deriveKey(pin, androidId, linuxId);
    return CryptoContext._(key);
  }

  /// Encrypt a plaintext message.
  String encrypt(String plaintext) {
    return encryptAesGcm(plaintext, _key);
  }

  /// Decrypt a ciphertext message.
  String decrypt(String ciphertext) {
    return decryptAesGcm(ciphertext, _key);
  }

  /// Calculate checksum for message fields.
  String checksum(int version, String msgType, String payload, int timestamp) {
    return calculateChecksum(version, msgType, payload, timestamp, _key);
  }

  /// Verify a message checksum.
  bool verifyChecksum(
    int version,
    String msgType,
    String payload,
    int timestamp,
    String expected,
  ) {
    final calculated = checksum(version, msgType, payload, timestamp);
    return calculated == expected;
  }

  /// Sign a message (sets checksum).
  void sign(Message message) {
    message.checksum = checksum(
      message.version,
      message.messageType.value,
      message.payload,
      message.timestamp,
    );
  }

  /// Sign and encrypt a message.
  void signAndEncrypt(Message message) {
    if (message.shouldEncrypt) {
      message.payload = encrypt(message.payload);
    }
    sign(message);
  }

  /// Verify message checksum.
  bool verify(Message message) {
    return verifyChecksum(
      message.version,
      message.messageType.value,
      message.payload,
      message.timestamp,
      message.checksum,
    );
  }

  /// Verify and decrypt a message.
  /// Throws if verification fails.
  void verifyAndDecrypt(Message message) {
    if (!verify(message)) {
      throw Exception('Checksum verification failed');
    }
    if (message.shouldEncrypt) {
      message.payload = decrypt(message.payload);
    }
  }
}

/// Derive a 256-bit key from PIN and device identifiers using PBKDF2.
Uint8List deriveKey(String pin, String androidId, String linuxId) {
  final password = '$pin$androidId$linuxId';
  final passwordBytes = utf8.encode(password);

  final derivator = PBKDF2KeyDerivator(HMac(SHA256Digest(), 64));
  derivator.init(Pbkdf2Parameters(_salt, _pbkdf2Iterations, 32));

  return derivator.process(Uint8List.fromList(passwordBytes));
}

/// Generate cryptographically secure random bytes.
Uint8List _generateSecureRandom(int length) {
  final secureRandom = FortunaRandom();
  final seedSource = Uint8List(32);
  final random = Random.secure();
  for (int i = 0; i < 32; i++) {
    seedSource[i] = random.nextInt(256);
  }
  secureRandom.seed(KeyParameter(seedSource));
  return secureRandom.nextBytes(length);
}

/// Encrypt plaintext using AES-256-GCM.
/// Returns base64(nonce || ciphertext || tag).
String encryptAesGcm(String plaintext, Uint8List key) {
  final nonce = _generateSecureRandom(12);

  final cipher = GCMBlockCipher(AESEngine())
    ..init(true, AEADParameters(KeyParameter(key), 128, nonce, Uint8List(0)));

  final plaintextBytes = Uint8List.fromList(utf8.encode(plaintext));
  final ciphertext = cipher.process(plaintextBytes);

  final combined = Uint8List(nonce.length + ciphertext.length);
  combined.setAll(0, nonce);
  combined.setAll(nonce.length, ciphertext);

  return base64.encode(combined);
}

/// Decrypt ciphertext using AES-256-GCM.
/// Expects base64(nonce || ciphertext || tag).
String decryptAesGcm(String ciphertext, Uint8List key) {
  final combined = base64.decode(ciphertext);

  if (combined.length < 12 + 16) {
    throw ArgumentError('Ciphertext too short');
  }

  final nonce = Uint8List.fromList(combined.sublist(0, 12));
  final encryptedWithTag = Uint8List.fromList(combined.sublist(12));

  final cipher = GCMBlockCipher(AESEngine())
    ..init(false, AEADParameters(KeyParameter(key), 128, nonce, Uint8List(0)));

  final plaintext = cipher.process(encryptedWithTag);

  return utf8.decode(plaintext);
}

/// Calculate SHA-256 checksum (first 8 hex characters).
String calculateChecksum(
  int version,
  String msgType,
  String payload,
  int timestamp,
  Uint8List secret,
) {
  final data =
      '$version$msgType$payload$timestamp${String.fromCharCodes(secret)}';
  final bytes = utf8.encode(data);
  final digest = sha256.convert(bytes);

  // Return first 8 hex characters (4 bytes)
  return digest.toString().substring(0, 8);
}

/// Generate a random device ID.
String generateDeviceId() {
  final secureRandom = FortunaRandom();
  final seedSource = Uint8List(32);
  for (int i = 0; i < 32; i++) {
    seedSource[i] = DateTime.now().microsecondsSinceEpoch % 256;
  }
  secureRandom.seed(KeyParameter(seedSource));

  final bytes = secureRandom.nextBytes(16);
  final hex = bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
  return 'android-$hex';
}
