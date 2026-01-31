# Phase 02: Communication Protocol Implementation

## Overview

This phase implements the shared communication protocol used between the Android app and Linux desktop. Both applications must serialize, encrypt, and validate messages identically. After completing this phase, both apps can encode/decode protocol messages and perform encryption.

## Prerequisites

- Phase 01 completed (project structure in place)
- Both projects build successfully

## Protocol Specification

### Message Structure

```json
{
  "v": 1,
  "t": "TEXT|COMMAND|HEARTBEAT|ACK|PAIR_REQ|PAIR_ACK",
  "p": "payload",
  "ts": 1234567890123,
  "cs": "abc12345"
}
```

### Message Types

| Type | Description | Payload |
|------|-------------|---------|
| `TEXT` | Plain text to type | The text content |
| `COMMAND` | Voice command | Command code (e.g., "ENTER") |
| `HEARTBEAT` | Keep-alive | Empty string |
| `ACK` | Acknowledgment | Timestamp of acknowledged message |
| `PAIR_REQ` | Pairing request | JSON with device_id and public_key |
| `PAIR_ACK` | Pairing response | JSON with device_id, public_key, status |

### Security

- **Key Derivation**: PBKDF2-HMAC-SHA256 (100,000 iterations)
- **Encryption**: AES-256-GCM
- **Checksum**: First 8 characters of SHA-256

---

## Part A: Rust Implementation (Linux Desktop)

### Task 1: Update Crypto Module

Replace `desktop/src/crypto/mod.rs`:

```rust
//! Cryptography module for message encryption and verification.

use aes_gcm::{
    aead::{Aead, KeyInit, OsRng},
    Aes256Gcm, Nonce,
};
use anyhow::{anyhow, Result};
use base64::{engine::general_purpose::STANDARD as BASE64, Engine};
use pbkdf2::pbkdf2_hmac;
use rand::RngCore;
use sha2::{Digest, Sha256};

const PBKDF2_ITERATIONS: u32 = 100_000;
const SALT: &[u8] = b"speech2code_v1";
const NONCE_SIZE: usize = 12;
const KEY_SIZE: usize = 32;

/// Cryptographic context for a paired session.
#[derive(Clone)]
pub struct CryptoContext {
    key: [u8; KEY_SIZE],
}

impl CryptoContext {
    /// Create a new crypto context from a shared secret.
    pub fn new(key: [u8; KEY_SIZE]) -> Self {
        Self { key }
    }

    /// Derive a crypto context from PIN and device IDs.
    pub fn from_pin(pin: &str, android_id: &str, linux_id: &str) -> Self {
        let key = derive_key(pin, android_id, linux_id);
        Self { key }
    }

    /// Encrypt a plaintext message.
    pub fn encrypt(&self, plaintext: &str) -> Result<String> {
        encrypt(plaintext, &self.key)
    }

    /// Decrypt a ciphertext message.
    pub fn decrypt(&self, ciphertext: &str) -> Result<String> {
        decrypt(ciphertext, &self.key)
    }

    /// Calculate checksum for a message.
    pub fn checksum(&self, version: u8, msg_type: &str, payload: &str, timestamp: u64) -> String {
        checksum(version, msg_type, payload, timestamp, &self.key)
    }

    /// Verify a message checksum.
    pub fn verify_checksum(
        &self,
        version: u8,
        msg_type: &str,
        payload: &str,
        timestamp: u64,
        expected: &str,
    ) -> bool {
        let calculated = self.checksum(version, msg_type, payload, timestamp);
        calculated == expected
    }
}

/// Derive a 256-bit key from PIN and device identifiers.
pub fn derive_key(pin: &str, android_id: &str, linux_id: &str) -> [u8; KEY_SIZE] {
    let password = format!("{}{}{}", pin, android_id, linux_id);
    let mut key = [0u8; KEY_SIZE];
    
    pbkdf2_hmac::<Sha256>(
        password.as_bytes(),
        SALT,
        PBKDF2_ITERATIONS,
        &mut key,
    );
    
    key
}

/// Encrypt plaintext using AES-256-GCM.
/// Returns base64(nonce || ciphertext || tag).
pub fn encrypt(plaintext: &str, key: &[u8; KEY_SIZE]) -> Result<String> {
    let cipher = Aes256Gcm::new_from_slice(key)
        .map_err(|e| anyhow!("Failed to create cipher: {}", e))?;
    
    // Generate random nonce
    let mut nonce_bytes = [0u8; NONCE_SIZE];
    OsRng.fill_bytes(&mut nonce_bytes);
    let nonce = Nonce::from_slice(&nonce_bytes);
    
    // Encrypt
    let ciphertext = cipher
        .encrypt(nonce, plaintext.as_bytes())
        .map_err(|e| anyhow!("Encryption failed: {}", e))?;
    
    // Combine nonce and ciphertext
    let mut combined = Vec::with_capacity(NONCE_SIZE + ciphertext.len());
    combined.extend_from_slice(&nonce_bytes);
    combined.extend_from_slice(&ciphertext);
    
    Ok(BASE64.encode(combined))
}

/// Decrypt ciphertext using AES-256-GCM.
/// Expects base64(nonce || ciphertext || tag).
pub fn decrypt(ciphertext: &str, key: &[u8; KEY_SIZE]) -> Result<String> {
    let combined = BASE64
        .decode(ciphertext)
        .map_err(|e| anyhow!("Base64 decode failed: {}", e))?;
    
    if combined.len() < NONCE_SIZE {
        return Err(anyhow!("Ciphertext too short"));
    }
    
    let (nonce_bytes, ciphertext_bytes) = combined.split_at(NONCE_SIZE);
    let nonce = Nonce::from_slice(nonce_bytes);
    
    let cipher = Aes256Gcm::new_from_slice(key)
        .map_err(|e| anyhow!("Failed to create cipher: {}", e))?;
    
    let plaintext = cipher
        .decrypt(nonce, ciphertext_bytes)
        .map_err(|e| anyhow!("Decryption failed: {}", e))?;
    
    String::from_utf8(plaintext).map_err(|e| anyhow!("UTF-8 decode failed: {}", e))
}

/// Calculate SHA-256 checksum (first 8 hex characters).
pub fn checksum(version: u8, msg_type: &str, payload: &str, timestamp: u64, secret: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(version.to_string().as_bytes());
    hasher.update(msg_type.as_bytes());
    hasher.update(payload.as_bytes());
    hasher.update(timestamp.to_string().as_bytes());
    hasher.update(secret);
    
    let hash = hasher.finalize();
    hex::encode(&hash[..4]) // First 4 bytes = 8 hex chars
}

/// Generate a random device ID.
pub fn generate_device_id() -> String {
    let mut bytes = [0u8; 16];
    OsRng.fill_bytes(&mut bytes);
    format!("linux-{}", hex::encode(bytes))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_key_derivation() {
        let key1 = derive_key("123456", "android-abc", "linux-xyz");
        let key2 = derive_key("123456", "android-abc", "linux-xyz");
        let key3 = derive_key("654321", "android-abc", "linux-xyz");
        
        assert_eq!(key1, key2);
        assert_ne!(key1, key3);
    }

    #[test]
    fn test_encrypt_decrypt() {
        let key = derive_key("123456", "android-abc", "linux-xyz");
        let plaintext = "Hello, World!";
        
        let encrypted = encrypt(plaintext, &key).unwrap();
        let decrypted = decrypt(&encrypted, &key).unwrap();
        
        assert_eq!(plaintext, decrypted);
        assert_ne!(plaintext, encrypted);
    }

    #[test]
    fn test_checksum() {
        let key = derive_key("123456", "android-abc", "linux-xyz");
        let cs1 = checksum(1, "TEXT", "hello", 1234567890, &key);
        let cs2 = checksum(1, "TEXT", "hello", 1234567890, &key);
        let cs3 = checksum(1, "TEXT", "world", 1234567890, &key);
        
        assert_eq!(cs1.len(), 8);
        assert_eq!(cs1, cs2);
        assert_ne!(cs1, cs3);
    }

    #[test]
    fn test_crypto_context() {
        let ctx = CryptoContext::from_pin("123456", "android-abc", "linux-xyz");
        
        let encrypted = ctx.encrypt("test message").unwrap();
        let decrypted = ctx.decrypt(&encrypted).unwrap();
        
        assert_eq!("test message", decrypted);
        
        let cs = ctx.checksum(1, "TEXT", "payload", 12345);
        assert!(ctx.verify_checksum(1, "TEXT", "payload", 12345, &cs));
        assert!(!ctx.verify_checksum(1, "TEXT", "different", 12345, &cs));
    }
}
```

### Task 2: Update Protocol Module

Replace `desktop/src/bluetooth/protocol.rs`:

```rust
//! Message protocol definitions and serialization.

use anyhow::{anyhow, Result};
use serde::{Deserialize, Serialize};
use std::time::{SystemTime, UNIX_EPOCH};

use crate::crypto::CryptoContext;

/// Protocol version.
pub const PROTOCOL_VERSION: u8 = 1;

/// Message types supported by the protocol.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum MessageType {
    #[serde(rename = "TEXT")]
    Text,
    #[serde(rename = "COMMAND")]
    Command,
    #[serde(rename = "HEARTBEAT")]
    Heartbeat,
    #[serde(rename = "ACK")]
    Ack,
    #[serde(rename = "PAIR_REQ")]
    PairReq,
    #[serde(rename = "PAIR_ACK")]
    PairAck,
}

impl MessageType {
    /// Convert to string representation.
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Text => "TEXT",
            Self::Command => "COMMAND",
            Self::Heartbeat => "HEARTBEAT",
            Self::Ack => "ACK",
            Self::PairReq => "PAIR_REQ",
            Self::PairAck => "PAIR_ACK",
        }
    }
}

/// Protocol message structure.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Message {
    /// Protocol version
    #[serde(rename = "v")]
    pub version: u8,

    /// Message type
    #[serde(rename = "t")]
    pub message_type: MessageType,

    /// Payload content (may be encrypted)
    #[serde(rename = "p")]
    pub payload: String,

    /// Timestamp in milliseconds
    #[serde(rename = "ts")]
    pub timestamp: u64,

    /// Checksum (first 8 chars of SHA-256)
    #[serde(rename = "cs")]
    pub checksum: String,
}

impl Message {
    /// Create a new message with automatic timestamp.
    pub fn new(message_type: MessageType, payload: impl Into<String>) -> Self {
        let timestamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_millis() as u64;

        Self {
            version: PROTOCOL_VERSION,
            message_type,
            payload: payload.into(),
            timestamp,
            checksum: String::new(),
        }
    }

    /// Create a TEXT message.
    pub fn text(content: impl Into<String>) -> Self {
        Self::new(MessageType::Text, content)
    }

    /// Create a COMMAND message.
    pub fn command(cmd: impl Into<String>) -> Self {
        Self::new(MessageType::Command, cmd)
    }

    /// Create a HEARTBEAT message.
    pub fn heartbeat() -> Self {
        Self::new(MessageType::Heartbeat, "")
    }

    /// Create an ACK message for a given timestamp.
    pub fn ack(original_timestamp: u64) -> Self {
        Self::new(MessageType::Ack, original_timestamp.to_string())
    }

    /// Sign the message with a crypto context.
    pub fn sign(&mut self, ctx: &CryptoContext) {
        self.checksum = ctx.checksum(
            self.version,
            self.message_type.as_str(),
            &self.payload,
            self.timestamp,
        );
    }

    /// Sign and optionally encrypt the message payload.
    pub fn sign_and_encrypt(&mut self, ctx: &CryptoContext) -> Result<()> {
        // Encrypt payload for sensitive message types
        if matches!(
            self.message_type,
            MessageType::Text | MessageType::Command | MessageType::PairReq | MessageType::PairAck
        ) {
            self.payload = ctx.encrypt(&self.payload)?;
        }

        self.sign(ctx);
        Ok(())
    }

    /// Verify the message checksum.
    pub fn verify(&self, ctx: &CryptoContext) -> bool {
        ctx.verify_checksum(
            self.version,
            self.message_type.as_str(),
            &self.payload,
            self.timestamp,
            &self.checksum,
        )
    }

    /// Verify and decrypt the message payload.
    pub fn verify_and_decrypt(&mut self, ctx: &CryptoContext) -> Result<()> {
        if !self.verify(ctx) {
            return Err(anyhow!("Checksum verification failed"));
        }

        // Decrypt payload for sensitive message types
        if matches!(
            self.message_type,
            MessageType::Text | MessageType::Command | MessageType::PairReq | MessageType::PairAck
        ) {
            self.payload = ctx.decrypt(&self.payload)?;
        }

        Ok(())
    }

    /// Serialize to JSON string with newline delimiter.
    pub fn to_json(&self) -> Result<String> {
        let json = serde_json::to_string(self)?;
        Ok(format!("{}\n", json))
    }

    /// Parse from JSON string.
    pub fn from_json(json: &str) -> Result<Self> {
        let trimmed = json.trim();
        let msg: Self = serde_json::from_str(trimmed)?;
        Ok(msg)
    }
}

/// Pairing request payload.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairRequestPayload {
    pub device_id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub device_name: Option<String>,
}

impl PairRequestPayload {
    pub fn new(device_id: impl Into<String>) -> Self {
        Self {
            device_id: device_id.into(),
            device_name: None,
        }
    }

    pub fn with_name(mut self, name: impl Into<String>) -> Self {
        self.device_name = Some(name.into());
        self
    }

    pub fn to_json(&self) -> Result<String> {
        Ok(serde_json::to_string(self)?)
    }

    pub fn from_json(json: &str) -> Result<Self> {
        Ok(serde_json::from_str(json)?)
    }
}

/// Pairing acknowledgment payload.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairAckPayload {
    pub device_id: String,
    pub status: PairStatus,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum PairStatus {
    Ok,
    Error,
}

impl PairAckPayload {
    pub fn success(device_id: impl Into<String>) -> Self {
        Self {
            device_id: device_id.into(),
            status: PairStatus::Ok,
            error: None,
        }
    }

    pub fn error(device_id: impl Into<String>, error: impl Into<String>) -> Self {
        Self {
            device_id: device_id.into(),
            status: PairStatus::Error,
            error: Some(error.into()),
        }
    }

    pub fn to_json(&self) -> Result<String> {
        Ok(serde_json::to_string(self)?)
    }

    pub fn from_json(json: &str) -> Result<Self> {
        Ok(serde_json::from_str(json)?)
    }
}

/// Voice command codes.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CommandCode {
    Enter,
    SelectAll,
    Copy,
    Paste,
    Cut,
    Cancel,
}

impl CommandCode {
    /// Parse from string.
    pub fn parse(s: &str) -> Option<Self> {
        match s.trim().to_uppercase().as_str() {
            "ENTER" => Some(Self::Enter),
            "SELECT_ALL" => Some(Self::SelectAll),
            "COPY" => Some(Self::Copy),
            "PASTE" => Some(Self::Paste),
            "CUT" => Some(Self::Cut),
            "CANCEL" => Some(Self::Cancel),
            _ => None,
        }
    }

    /// Convert to string.
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Enter => "ENTER",
            Self::SelectAll => "SELECT_ALL",
            Self::Copy => "COPY",
            Self::Paste => "PASTE",
            Self::Cut => "CUT",
            Self::Cancel => "CANCEL",
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_message_serialization() {
        let msg = Message::text("Hello, World!");
        let json = msg.to_json().unwrap();

        assert!(json.contains("\"v\":1"));
        assert!(json.contains("\"t\":\"TEXT\""));
        assert!(json.contains("\"p\":\"Hello, World!\""));
        assert!(json.ends_with('\n'));

        let parsed = Message::from_json(&json).unwrap();
        assert_eq!(parsed.version, 1);
        assert_eq!(parsed.message_type, MessageType::Text);
        assert_eq!(parsed.payload, "Hello, World!");
    }

    #[test]
    fn test_message_signing() {
        let ctx = CryptoContext::from_pin("123456", "android-abc", "linux-xyz");

        let mut msg = Message::text("test");
        msg.sign(&ctx);

        assert!(!msg.checksum.is_empty());
        assert!(msg.verify(&ctx));

        // Tamper with payload
        msg.payload = "tampered".to_string();
        assert!(!msg.verify(&ctx));
    }

    #[test]
    fn test_message_encryption() {
        let ctx = CryptoContext::from_pin("123456", "android-abc", "linux-xyz");

        let mut msg = Message::text("secret message");
        let original_payload = msg.payload.clone();

        msg.sign_and_encrypt(&ctx).unwrap();
        assert_ne!(msg.payload, original_payload);

        msg.verify_and_decrypt(&ctx).unwrap();
        assert_eq!(msg.payload, original_payload);
    }

    #[test]
    fn test_command_codes() {
        assert_eq!(CommandCode::parse("ENTER"), Some(CommandCode::Enter));
        assert_eq!(CommandCode::parse("enter"), Some(CommandCode::Enter));
        assert_eq!(CommandCode::parse("SELECT_ALL"), Some(CommandCode::SelectAll));
        assert_eq!(CommandCode::parse("invalid"), None);
    }

    #[test]
    fn test_pair_payloads() {
        let req = PairRequestPayload::new("android-123")
            .with_name("My Phone");
        let json = req.to_json().unwrap();
        let parsed = PairRequestPayload::from_json(&json).unwrap();
        
        assert_eq!(parsed.device_id, "android-123");
        assert_eq!(parsed.device_name, Some("My Phone".to_string()));

        let ack = PairAckPayload::success("linux-456");
        let json = ack.to_json().unwrap();
        let parsed = PairAckPayload::from_json(&json).unwrap();
        
        assert_eq!(parsed.device_id, "linux-456");
        assert_eq!(parsed.status, PairStatus::Ok);
    }
}
```

### Task 3: Update Bluetooth Module Exports

Replace `desktop/src/bluetooth/mod.rs`:

```rust
//! Bluetooth communication module.
//!
//! Handles RFCOMM server for receiving messages from Android app.

mod protocol;
mod server;

pub use protocol::{
    CommandCode, Message, MessageType, PairAckPayload, PairRequestPayload, PairStatus,
    PROTOCOL_VERSION,
};
pub use server::BluetoothServer;
```

---

## Part B: Dart Implementation (Android App)

### Task 4: Create Protocol Models

Create `android/lib/models/message.dart`:

```dart
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
```

### Task 5: Create Voice Command Model

Create `android/lib/models/voice_command.dart`:

```dart
/// Voice command codes that can be sent to the desktop.
enum CommandCode {
  enter('ENTER'),
  selectAll('SELECT_ALL'),
  copy('COPY'),
  paste('PASTE'),
  cut('CUT'),
  cancel('CANCEL');

  final String code;
  const CommandCode(this.code);

  /// Parse from spoken text.
  /// Returns null if no command is detected.
  static CommandCode? parse(String text) {
    final lower = text.toLowerCase().trim();
    
    // Enter / new line
    if (_matches(lower, ['new line', 'newline', 'enter', 'next line', 'line break'])) {
      return CommandCode.enter;
    }
    
    // Select all
    if (_matches(lower, ['select all', 'select everything', 'highlight all'])) {
      return CommandCode.selectAll;
    }
    
    // Copy
    if (_matches(lower, ['copy that', 'copy this', 'copy it', 'copy selection'])) {
      return CommandCode.copy;
    }
    
    // Paste
    if (_matches(lower, ['paste', 'paste that', 'paste it', 'paste here'])) {
      return CommandCode.paste;
    }
    
    // Cut
    if (_matches(lower, ['cut that', 'cut this', 'cut it', 'cut selection'])) {
      return CommandCode.cut;
    }
    
    // Cancel
    if (_matches(lower, ['cancel', 'clear', 'never mind', 'nevermind', 'discard'])) {
      return CommandCode.cancel;
    }
    
    return null;
  }

  static bool _matches(String text, List<String> patterns) {
    for (final pattern in patterns) {
      if (text.contains(pattern)) {
        return true;
      }
    }
    return false;
  }
}

/// Result of processing spoken text for commands.
class ProcessedSpeech {
  /// Text before any detected command (to be sent as TEXT).
  final String? textBefore;
  
  /// Detected command (to be sent as COMMAND).
  final CommandCode? command;
  
  /// Text after command (to be processed in next iteration).
  final String? textAfter;

  ProcessedSpeech({
    this.textBefore,
    this.command,
    this.textAfter,
  });

  /// Process spoken text to extract commands.
  factory ProcessedSpeech.process(String text) {
    final lower = text.toLowerCase();
    
    // Command patterns with their codes
    final patterns = <String, CommandCode>{
      'new line': CommandCode.enter,
      'newline': CommandCode.enter,
      'enter': CommandCode.enter,
      'next line': CommandCode.enter,
      'select all': CommandCode.selectAll,
      'copy that': CommandCode.copy,
      'copy this': CommandCode.copy,
      'paste': CommandCode.paste,
      'cut that': CommandCode.cut,
      'cut this': CommandCode.cut,
      'cancel': CommandCode.cancel,
      'clear': CommandCode.cancel,
      'never mind': CommandCode.cancel,
    };

    // Find the first command in the text
    int firstIndex = -1;
    String? firstPattern;
    CommandCode? firstCommand;

    for (final entry in patterns.entries) {
      final index = lower.indexOf(entry.key);
      if (index != -1 && (firstIndex == -1 || index < firstIndex)) {
        firstIndex = index;
        firstPattern = entry.key;
        firstCommand = entry.value;
      }
    }

    if (firstIndex == -1) {
      // No command found
      return ProcessedSpeech(textBefore: text);
    }

    final textBefore = text.substring(0, firstIndex).trim();
    final afterCommand = firstIndex + firstPattern!.length;
    final textAfter = afterCommand < text.length 
        ? text.substring(afterCommand).trim() 
        : null;

    return ProcessedSpeech(
      textBefore: textBefore.isEmpty ? null : textBefore,
      command: firstCommand,
      textAfter: textAfter?.isEmpty == true ? null : textAfter,
    );
  }

  bool get hasText => textBefore != null && textBefore!.isNotEmpty;
  bool get hasCommand => command != null;
  bool get hasRemainder => textAfter != null && textAfter!.isNotEmpty;
}
```

### Task 6: Create Encryption Utilities

Create `android/lib/utils/encryption.dart`:

```dart
import 'dart:convert';
import 'dart:typed_data';
import 'package:crypto/crypto.dart';
import 'package:encrypt/encrypt.dart';
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
      int version, String msgType, String payload, int timestamp, String expected) {
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

/// Encrypt plaintext using AES-256-GCM.
/// Returns base64(nonce || ciphertext || tag).
String encryptAesGcm(String plaintext, Uint8List key) {
  // Generate random 12-byte nonce
  final iv = IV.fromSecureRandom(12);
  
  final encrypter = Encrypter(AES(Key(key), mode: AESMode.gcm));
  final encrypted = encrypter.encrypt(plaintext, iv: iv);

  // Combine nonce and ciphertext
  final combined = Uint8List(iv.bytes.length + encrypted.bytes.length);
  combined.setAll(0, iv.bytes);
  combined.setAll(iv.bytes.length, encrypted.bytes);

  return base64.encode(combined);
}

/// Decrypt ciphertext using AES-256-GCM.
/// Expects base64(nonce || ciphertext || tag).
String decryptAesGcm(String ciphertext, Uint8List key) {
  final combined = base64.decode(ciphertext);
  
  if (combined.length < 12) {
    throw ArgumentError('Ciphertext too short');
  }

  final iv = IV(Uint8List.fromList(combined.sublist(0, 12)));
  final encryptedBytes = Uint8List.fromList(combined.sublist(12));

  final encrypter = Encrypter(AES(Key(key), mode: AESMode.gcm));
  final encrypted = Encrypted(encryptedBytes);

  return encrypter.decrypt(encrypted, iv: iv);
}

/// Calculate SHA-256 checksum (first 8 hex characters).
String calculateChecksum(
    int version, String msgType, String payload, int timestamp, Uint8List secret) {
  final data = '$version$msgType$payload$timestamp${String.fromCharCodes(secret)}';
  final bytes = utf8.encode(data);
  final digest = sha256.convert(bytes);
  
  // Return first 8 hex characters (4 bytes)
  return digest.toString().substring(0, 8);
}

/// Generate a random device ID.
String generateDeviceId() {
  final random = SecureRandom('Fortuna');
  random.seed(KeyParameter(Uint8List(32)..setAll(0, List.generate(32, (_) => DateTime.now().microsecond))));
  
  final bytes = random.nextBytes(16);
  final hex = bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
  return 'android-$hex';
}
```

### Task 7: Create Constants File

Create `android/lib/utils/constants.dart`:

```dart
/// Bluetooth SPP UUID.
const String sppUuid = '00001101-0000-1000-8000-00805F9B34FB';

/// Heartbeat interval in milliseconds.
const int heartbeatIntervalMs = 5000;

/// Connection timeout in milliseconds.
const int connectionTimeoutMs = 15000;

/// Maximum reconnection attempts.
const int maxReconnectAttempts = 5;

/// Reconnection base delay in milliseconds.
const int reconnectBaseDelayMs = 1000;

/// App name.
const String appName = 'Speech2Code';
```

### Task 8: Update Model Exports

Create `android/lib/models/models.dart`:

```dart
export 'message.dart';
export 'voice_command.dart';
```

### Task 9: Update Utils Exports

Create `android/lib/utils/utils.dart`:

```dart
export 'constants.dart';
export 'encryption.dart';
```

---

## Verification

### Run Rust Tests

```bash
cd /home/dan/workspace/priv/speech2code/desktop
cargo test
```

Expected output: All tests pass.

### Build Flutter App

```bash
cd /home/dan/workspace/priv/speech2code/android
flutter pub get
flutter analyze
```

Expected output: No analysis issues.

---

## Verification Checklist

- [ ] Rust crypto module compiles and tests pass
- [ ] Rust protocol module compiles and tests pass
- [ ] Dart message model created with serialization
- [ ] Dart voice command parser created
- [ ] Dart encryption utilities created
- [ ] Key derivation produces identical output on both platforms
- [ ] Message serialization is compatible between platforms

## Cross-Platform Compatibility Test

To verify both implementations produce identical output, run this test manually:

**Input:**
- PIN: `123456`
- Android ID: `android-test123`
- Linux ID: `linux-test456`

Both platforms should derive the same key (verify by encrypting the same message and checking that decryption works cross-platform in Phase 07).

## Output Artifacts

After completing this phase:

1. **Rust crypto module** with key derivation, AES-GCM encryption, and checksum
2. **Rust protocol module** with message types and serialization
3. **Dart models** for messages and commands
4. **Dart encryption utilities** matching Rust implementation

## Next Phase

Proceed to **Phase 03: Linux Bluetooth Server** to implement the RFCOMM server that listens for incoming connections from the Android app.
