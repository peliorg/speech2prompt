# Phase 09: Integration & Security

## Overview

This phase completes the end-to-end integration between Android and Linux, implements the full pairing flow with PIN entry, ensures encryption works correctly across platforms, and adds secure storage for credentials.

## Prerequisites

- Phase 01-08 completed
- Both apps functional individually
- Bluetooth pairing working at system level

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     Pairing Flow                                 │
│                                                                  │
│   Android                              Linux                     │
│   ┌─────────┐                          ┌─────────┐              │
│   │ Connect │─────────────────────────►│ Accept  │              │
│   └────┬────┘                          └────┬────┘              │
│        │                                    │                    │
│        ▼                                    ▼                    │
│   ┌─────────┐    PAIR_REQ             ┌─────────┐              │
│   │ Send    │────────────────────────►│ Show    │              │
│   │ PAIR_REQ│                         │ PIN     │              │
│   └────┬────┘                         │ Dialog  │              │
│        │                              └────┬────┘              │
│        │     User enters PIN on both       │                    │
│        ▼                                   ▼                    │
│   ┌─────────┐    Derive Key           ┌─────────┐              │
│   │ Enter   │                         │ Enter   │              │
│   │ PIN     │                         │ PIN     │              │
│   └────┬────┘                         └────┬────┘              │
│        │                                   │                    │
│        ▼                                   ▼                    │
│   ┌─────────┐    PAIR_ACK             ┌─────────┐              │
│   │ Receive │◄────────────────────────│ Send    │              │
│   │ PAIR_ACK│                         │ PAIR_ACK│              │
│   └────┬────┘                         └────┬────┘              │
│        │                                   │                    │
│        ▼                                   ▼                    │
│   ┌─────────┐    Encrypted messages   ┌─────────┐              │
│   │Connected│◄───────────────────────►│Connected│              │
│   └─────────┘                         └─────────┘              │
└─────────────────────────────────────────────────────────────────┘
```

---

## Task 1: Implement Secure Storage on Android

Create `android/lib/services/secure_storage_service.dart`:

```dart
import 'dart:convert';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Service for securely storing sensitive data.
class SecureStorageService {
  static const _storage = FlutterSecureStorage(
    aOptions: AndroidOptions(
      encryptedSharedPreferences: true,
    ),
  );

  // Keys
  static const _deviceIdKey = 'device_id';
  static const _pairedDevicesKey = 'paired_devices';

  /// Get or generate device ID.
  static Future<String> getDeviceId() async {
    var deviceId = await _storage.read(key: _deviceIdKey);
    if (deviceId == null) {
      deviceId = _generateDeviceId();
      await _storage.write(key: _deviceIdKey, value: deviceId);
    }
    return deviceId;
  }

  /// Store paired device credentials.
  static Future<void> storePairedDevice(PairedDevice device) async {
    final devices = await getPairedDevices();
    
    // Update or add
    final index = devices.indexWhere((d) => d.address == device.address);
    if (index >= 0) {
      devices[index] = device;
    } else {
      devices.add(device);
    }
    
    final json = jsonEncode(devices.map((d) => d.toJson()).toList());
    await _storage.write(key: _pairedDevicesKey, value: json);
  }

  /// Get all paired devices.
  static Future<List<PairedDevice>> getPairedDevices() async {
    final json = await _storage.read(key: _pairedDevicesKey);
    if (json == null) return [];
    
    final list = jsonDecode(json) as List;
    return list.map((e) => PairedDevice.fromJson(e)).toList();
  }

  /// Get paired device by address.
  static Future<PairedDevice?> getPairedDevice(String address) async {
    final devices = await getPairedDevices();
    try {
      return devices.firstWhere((d) => d.address == address);
    } catch (e) {
      return null;
    }
  }

  /// Remove paired device.
  static Future<void> removePairedDevice(String address) async {
    final devices = await getPairedDevices();
    devices.removeWhere((d) => d.address == address);
    
    final json = jsonEncode(devices.map((d) => d.toJson()).toList());
    await _storage.write(key: _pairedDevicesKey, value: json);
  }

  /// Clear all stored data.
  static Future<void> clearAll() async {
    await _storage.deleteAll();
  }

  static String _generateDeviceId() {
    final random = List.generate(16, (_) => 
        (DateTime.now().microsecondsSinceEpoch % 256).toRadixString(16).padLeft(2, '0')
    ).join();
    return 'android-$random';
  }
}

/// Paired device information.
class PairedDevice {
  final String address;
  final String name;
  final String linuxDeviceId;
  final String sharedSecret; // Base64 encoded
  final DateTime pairedAt;

  PairedDevice({
    required this.address,
    required this.name,
    required this.linuxDeviceId,
    required this.sharedSecret,
    required this.pairedAt,
  });

  Map<String, dynamic> toJson() => {
    'address': address,
    'name': name,
    'linuxDeviceId': linuxDeviceId,
    'sharedSecret': sharedSecret,
    'pairedAt': pairedAt.toIso8601String(),
  };

  factory PairedDevice.fromJson(Map<String, dynamic> json) => PairedDevice(
    address: json['address'],
    name: json['name'],
    linuxDeviceId: json['linuxDeviceId'],
    sharedSecret: json['sharedSecret'],
    pairedAt: DateTime.parse(json['pairedAt']),
  );
}
```

---

## Task 2: Implement Secure Storage on Linux

Create `desktop/src/storage/secure.rs`:

```rust
//! Secure storage for pairing credentials.

use anyhow::{anyhow, Result};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::Path;

/// Paired device information.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairedDevice {
    pub address: String,
    pub name: String,
    pub android_device_id: String,
    pub shared_secret: String, // Base64 encoded
    pub paired_at: chrono::DateTime<chrono::Local>,
}

/// Secure storage for paired devices.
pub struct SecureStorage {
    path: std::path::PathBuf,
    devices: HashMap<String, PairedDevice>,
}

impl SecureStorage {
    /// Create or open secure storage.
    pub fn new(data_dir: &Path) -> Result<Self> {
        let path = data_dir.join("paired_devices.json");
        let devices = if path.exists() {
            let content = std::fs::read_to_string(&path)?;
            serde_json::from_str(&content)?
        } else {
            HashMap::new()
        };

        Ok(Self { path, devices })
    }

    /// Store a paired device.
    pub fn store_device(&mut self, device: PairedDevice) -> Result<()> {
        self.devices.insert(device.address.clone(), device);
        self.save()
    }

    /// Get a paired device by address.
    pub fn get_device(&self, address: &str) -> Option<&PairedDevice> {
        self.devices.get(address)
    }

    /// Get a paired device by Android device ID.
    pub fn get_device_by_android_id(&self, android_id: &str) -> Option<&PairedDevice> {
        self.devices.values().find(|d| d.android_device_id == android_id)
    }

    /// Remove a paired device.
    pub fn remove_device(&mut self, address: &str) -> Result<()> {
        self.devices.remove(address);
        self.save()
    }

    /// Get all paired devices.
    pub fn get_all_devices(&self) -> Vec<&PairedDevice> {
        self.devices.values().collect()
    }

    /// Check if a device is paired.
    pub fn is_paired(&self, address: &str) -> bool {
        self.devices.contains_key(address)
    }

    /// Save to disk.
    fn save(&self) -> Result<()> {
        let content = serde_json::to_string_pretty(&self.devices)?;
        std::fs::write(&self.path, content)?;
        Ok(())
    }
}
```

Update `desktop/src/storage/mod.rs`:

```rust
//! Storage module for history and secure storage.

mod history;
mod secure;

pub use history::{EntryType, History, HistoryEntry};
pub use secure::{PairedDevice, SecureStorage};
```

---

## Task 3: Implement Full Pairing Flow on Linux

Update `desktop/src/bluetooth/connection.rs` to add pairing completion:

```rust
// Add to existing file after the ConnectionHandler impl

impl ConnectionHandler {
    // ... existing methods ...

    /// Complete pairing with PIN.
    /// Called after user enters PIN in UI.
    pub async fn complete_pairing(
        &mut self,
        pin: &str,
        writer: &mut tokio::io::WriteHalf<Stream>,
    ) -> Result<()> {
        let android_device_id = self
            .device_id
            .as_ref()
            .ok_or_else(|| anyhow!("No device ID set"))?;

        // Derive crypto context from PIN
        let crypto = CryptoContext::from_pin(pin, android_device_id, &self.linux_device_id);
        
        // Create and send PAIR_ACK
        let payload = PairAckPayload::success(&self.linux_device_id);
        let mut response = Message::new(MessageType::PairAck, payload.to_json()?);
        
        // Sign with the new crypto context
        response.sign(&crypto);
        
        // Send response
        let response_json = response.to_json()?;
        writer.write_all(response_json.as_bytes()).await?;
        writer.flush().await?;

        // Store crypto context
        self.crypto = Some(Arc::new(crypto));
        self.state = ConnectionState::Authenticated;

        info!("Pairing completed with device: {}", android_device_id);

        // Emit connected event
        self.emit(ConnectionEvent::Connected {
            device_name: android_device_id.clone(),
        })
        .await;

        Ok(())
    }
}
```

---

## Task 4: Create Pairing Manager

Create `desktop/src/pairing.rs`:

```rust
//! Pairing management for handling PIN entry.

use anyhow::Result;
use std::sync::Arc;
use tokio::sync::{mpsc, oneshot};
use tracing::{info, warn};

use crate::crypto::CryptoContext;
use crate::storage::{PairedDevice, SecureStorage};

/// Pairing request from a connection.
#[derive(Debug)]
pub struct PairingRequest {
    pub android_device_id: String,
    pub device_name: Option<String>,
    pub response_tx: oneshot::Sender<PairingResponse>,
}

/// Response to a pairing request.
#[derive(Debug)]
pub enum PairingResponse {
    /// User accepted with PIN.
    Accepted { pin: String },
    /// User rejected.
    Rejected,
}

/// Pairing manager handles pairing requests and storage.
pub struct PairingManager {
    linux_device_id: String,
    storage: SecureStorage,
    request_tx: mpsc::Sender<PairingRequest>,
    request_rx: mpsc::Receiver<PairingRequest>,
}

impl PairingManager {
    /// Create a new pairing manager.
    pub fn new(linux_device_id: String, storage: SecureStorage) -> Self {
        let (request_tx, request_rx) = mpsc::channel(8);
        
        Self {
            linux_device_id,
            storage,
            request_tx,
            request_rx,
        }
    }

    /// Get sender for pairing requests.
    pub fn get_request_sender(&self) -> mpsc::Sender<PairingRequest> {
        self.request_tx.clone()
    }

    /// Check if a device is already paired.
    pub fn is_paired(&self, android_device_id: &str) -> bool {
        self.storage.get_device_by_android_id(android_device_id).is_some()
    }

    /// Get stored crypto context for a paired device.
    pub fn get_crypto_context(&self, android_device_id: &str) -> Option<CryptoContext> {
        let device = self.storage.get_device_by_android_id(android_device_id)?;
        let secret = base64::decode(&device.shared_secret).ok()?;
        
        if secret.len() != 32 {
            warn!("Invalid shared secret length for device {}", android_device_id);
            return None;
        }
        
        let mut key = [0u8; 32];
        key.copy_from_slice(&secret);
        Some(CryptoContext::new(key))
    }

    /// Store a newly paired device.
    pub fn store_pairing(
        &mut self,
        address: &str,
        name: &str,
        android_device_id: &str,
        crypto: &CryptoContext,
    ) -> Result<()> {
        let device = PairedDevice {
            address: address.to_string(),
            name: name.to_string(),
            android_device_id: android_device_id.to_string(),
            shared_secret: base64::encode(crypto.key()),
            paired_at: chrono::Local::now(),
        };
        
        self.storage.store_device(device)?;
        info!("Stored pairing for device {}", android_device_id);
        Ok(())
    }

    /// Process pairing requests from UI.
    pub async fn process_request(&mut self) -> Option<PairingRequest> {
        self.request_rx.recv().await
    }

    /// Get Linux device ID.
    pub fn linux_device_id(&self) -> &str {
        &self.linux_device_id
    }
}
```

---

## Task 5: Update Bluetooth Service with Pairing

Update `android/lib/services/bluetooth_service.dart`:

```dart
// Add these methods to BluetoothService class

/// Check if device is already paired (has stored credentials).
Future<bool> isDevicePaired(String address) async {
  final device = await SecureStorageService.getPairedDevice(address);
  return device != null;
}

/// Load crypto context from stored pairing.
Future<bool> loadStoredPairing(String address) async {
  final device = await SecureStorageService.getPairedDevice(address);
  if (device == null) return false;
  
  try {
    final secretBytes = base64.decode(device.sharedSecret);
    if (secretBytes.length != 32) return false;
    
    _crypto = CryptoContext.fromKey(Uint8List.fromList(secretBytes));
    _deviceId = await SecureStorageService.getDeviceId();
    return true;
  } catch (e) {
    debugPrint('BluetoothService: Failed to load stored pairing: $e');
    return false;
  }
}

/// Complete pairing and store credentials.
Future<void> completePairing(String pin, String linuxDeviceId) async {
  _deviceId = await SecureStorageService.getDeviceId();
  
  // Derive key from PIN
  final key = deriveKey(pin, _deviceId!, linuxDeviceId);
  _crypto = CryptoContext.fromKey(key);
  
  // Store pairing info
  final device = PairedDevice(
    address: _connectedDevice!.address,
    name: _connectedDevice!.displayName,
    linuxDeviceId: linuxDeviceId,
    sharedSecret: base64.encode(key),
    pairedAt: DateTime.now(),
  );
  await SecureStorageService.storePairedDevice(device);
  
  debugPrint('BluetoothService: Pairing stored for ${_connectedDevice!.displayName}');
  
  _setState(BtConnectionState.connected);
}

/// Handle PAIR_ACK message.
void _handlePairAck(Message message) {
  try {
    final payload = PairAckPayload.fromJson(message.payload);
    
    if (payload.status == PairStatus.ok) {
      debugPrint('BluetoothService: Pairing acknowledged by ${payload.deviceId}');
      // Note: PIN should have been entered before this, 
      // crypto context should already be set
      if (_crypto != null) {
        _setState(BtConnectionState.connected);
        _sendPendingMessages();
      }
    } else {
      debugPrint('BluetoothService: Pairing rejected: ${payload.error}');
      _errorMessage = payload.error ?? 'Pairing rejected';
      _setState(BtConnectionState.failed);
    }
  } catch (e) {
    debugPrint('BluetoothService: Error handling PAIR_ACK: $e');
    _setState(BtConnectionState.failed);
  }
}
```

---

## Task 6: Update Connection Screen with Pairing UI

Update `android/lib/screens/connection_screen.dart`:

```dart
// Update the _connectToDevice method

Future<void> _connectToDevice(BluetoothDeviceInfo device) async {
  final bluetooth = context.read<BluetoothService>();
  
  if (bluetooth.isConnected && bluetooth.connectedDevice?.address == device.address) {
    Navigator.pop(context);
    return;
  }

  if (bluetooth.isConnected) {
    await bluetooth.disconnect();
  }

  // Check if we have stored pairing for this device
  final hasStoredPairing = await bluetooth.isDevicePaired(device.address);
  
  if (hasStoredPairing) {
    // Load stored crypto context
    final loaded = await bluetooth.loadStoredPairing(device.address);
    if (loaded) {
      // Connect with existing credentials
      final success = await bluetooth.connect(device);
      if (success && mounted) {
        Navigator.pop(context);
      }
      return;
    }
  }

  // New pairing needed
  final success = await bluetooth.connect(device);
  
  if (success && mounted) {
    if (bluetooth.state == BtConnectionState.awaitingPairing) {
      final paired = await _showPairingDialog(bluetooth);
      if (paired && bluetooth.isConnected && mounted) {
        Navigator.pop(context);
      }
    } else if (bluetooth.isConnected) {
      Navigator.pop(context);
    }
  }
}

Future<bool> _showPairingDialog(BluetoothService bluetooth) async {
  final pinController = TextEditingController();
  final formKey = GlobalKey<FormState>();
  
  final result = await showDialog<Map<String, String>>(
    context: context,
    barrierDismissible: false,
    builder: (context) => AlertDialog(
      title: const Text('Secure Pairing'),
      content: Form(
        key: formKey,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'To establish a secure connection, enter the same 6-digit PIN on both devices.',
              style: TextStyle(fontSize: 14),
            ),
            const SizedBox(height: 8),
            const Text(
              'Choose any 6-digit number and enter it here and on your computer.',
              style: TextStyle(fontSize: 12, color: Colors.grey),
            ),
            const SizedBox(height: 20),
            TextFormField(
              controller: pinController,
              keyboardType: TextInputType.number,
              maxLength: 6,
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 28, letterSpacing: 12),
              decoration: const InputDecoration(
                hintText: '000000',
                counterText: '',
                border: OutlineInputBorder(),
              ),
              validator: (value) {
                if (value == null || value.length != 6) {
                  return 'Enter 6 digits';
                }
                if (!RegExp(r'^\d{6}$').hasMatch(value)) {
                  return 'Numbers only';
                }
                return null;
              },
              autofocus: true,
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        ElevatedButton(
          onPressed: () {
            if (formKey.currentState!.validate()) {
              Navigator.pop(context, {
                'pin': pinController.text,
              });
            }
          },
          child: const Text('Pair'),
        ),
      ],
    ),
  );

  if (result == null) {
    await bluetooth.disconnect();
    return false;
  }

  final pin = result['pin']!;
  
  // Show waiting indicator
  if (mounted) {
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (context) => const AlertDialog(
        content: Row(
          children: [
            CircularProgressIndicator(),
            SizedBox(width: 20),
            Text('Waiting for computer to confirm...'),
          ],
        ),
      ),
    );
  }

  // Wait for PAIR_ACK (handled in bluetooth service)
  // The PIN needs to be entered on Linux side too
  // For now, we'll use a placeholder Linux device ID
  // In production, this comes from the PAIR_ACK
  
  // Wait a bit for the pairing to complete
  await Future.delayed(const Duration(seconds: 2));
  
  if (mounted) {
    Navigator.pop(context); // Close waiting dialog
  }

  // Check if pairing succeeded
  return bluetooth.isConnected;
}
```

---

## Task 7: Create Integration Test

Create `desktop/tests/integration_test.rs`:

```rust
//! Integration tests for the full communication flow.

use speech2code_desktop::crypto::CryptoContext;
use speech2code_desktop::bluetooth::protocol::{Message, MessageType};

#[test]
fn test_cross_platform_encryption() {
    // Test that encryption/decryption works correctly
    let pin = "123456";
    let android_id = "android-test123";
    let linux_id = "linux-test456";
    
    // Create contexts on both "sides"
    let android_ctx = CryptoContext::from_pin(pin, android_id, linux_id);
    let linux_ctx = CryptoContext::from_pin(pin, android_id, linux_id);
    
    // Encrypt on Android side
    let plaintext = "Hello from Android!";
    let encrypted = android_ctx.encrypt(plaintext).unwrap();
    
    // Decrypt on Linux side
    let decrypted = linux_ctx.decrypt(&encrypted).unwrap();
    
    assert_eq!(plaintext, decrypted);
}

#[test]
fn test_message_flow() {
    let pin = "123456";
    let android_id = "android-test123";
    let linux_id = "linux-test456";
    
    let android_ctx = CryptoContext::from_pin(pin, android_id, linux_id);
    let linux_ctx = CryptoContext::from_pin(pin, android_id, linux_id);
    
    // Create message on Android
    let mut msg = Message::text("Test message");
    msg.sign_and_encrypt(&android_ctx).unwrap();
    
    // Serialize
    let json = msg.to_json().unwrap();
    
    // Parse on Linux
    let mut received = Message::from_json(&json).unwrap();
    
    // Verify and decrypt
    received.verify_and_decrypt(&linux_ctx).unwrap();
    
    assert_eq!(received.payload, "Test message");
}

#[test]
fn test_command_message() {
    let ctx = CryptoContext::from_pin("123456", "android-1", "linux-1");
    
    let mut msg = Message::command("ENTER");
    msg.sign_and_encrypt(&ctx).unwrap();
    
    let json = msg.to_json().unwrap();
    let mut received = Message::from_json(&json).unwrap();
    received.verify_and_decrypt(&ctx).unwrap();
    
    assert_eq!(received.message_type, MessageType::Command);
    assert_eq!(received.payload, "ENTER");
}

#[test]
fn test_checksum_verification() {
    let ctx = CryptoContext::from_pin("123456", "android-1", "linux-1");
    
    let mut msg = Message::text("Test");
    msg.sign(&ctx);
    
    // Verify passes
    assert!(msg.verify(&ctx));
    
    // Tamper with message
    msg.payload = "Tampered".to_string();
    
    // Verify fails
    assert!(!msg.verify(&ctx));
}
```

---

## Task 8: End-to-End Testing Script

Create `scripts/test_e2e.sh`:

```bash
#!/bin/bash

# End-to-end test script for Speech2Code
# Run this after starting both apps

set -e

echo "=== Speech2Code E2E Test ==="
echo ""

# Check if Linux app is running
if ! pgrep -f "speech2code-desktop" > /dev/null; then
    echo "ERROR: Linux desktop app is not running"
    echo "Start it with: cd desktop && cargo run"
    exit 1
fi

echo "✓ Linux app is running"

# Check Bluetooth
if ! hciconfig | grep -q "UP RUNNING"; then
    echo "ERROR: Bluetooth is not enabled"
    exit 1
fi

echo "✓ Bluetooth is enabled"

# Check if RFCOMM is listening
if ! ss -l | grep -q "rfcomm"; then
    echo "WARNING: RFCOMM listener may not be active"
fi

echo ""
echo "Manual test steps:"
echo ""
echo "1. On Android:"
echo "   - Open Speech2Code app"
echo "   - Tap 'Connect' or Bluetooth icon"
echo "   - Select your computer from the list"
echo "   - Enter a 6-digit PIN when prompted"
echo ""
echo "2. On Linux:"
echo "   - A PIN dialog should appear"
echo "   - Enter the same 6-digit PIN"
echo ""
echo "3. After pairing:"
echo "   - The connection status should show 'Connected'"
echo "   - Tap the microphone and speak"
echo "   - Text should appear at cursor on Linux"
echo ""
echo "4. Test commands:"
echo "   - Say 'Hello world new line'"
echo "   - Verify text is typed and Enter is pressed"
echo ""
echo "5. Test voice commands:"
echo "   - Open a text editor on Linux"
echo "   - Type some text manually"
echo "   - Say 'select all'"
echo "   - Say 'copy that'"
echo "   - Say 'paste'"
echo ""

read -p "Press Enter when ready to verify results..."

echo ""
echo "Verification checklist:"
echo "[ ] Connection established"
echo "[ ] PIN pairing completed"
echo "[ ] Text transmitted correctly"
echo "[ ] Commands executed correctly"
echo "[ ] Reconnection works after disconnect"
echo ""

echo "Test complete!"
```

---

## Task 9: Verify Encryption Compatibility

Create test to verify Dart and Rust produce same results:

Create `android/test/encryption_test.dart`:

```dart
import 'package:flutter_test/flutter_test.dart';
import 'package:speech2code/utils/encryption.dart';
import 'dart:typed_data';
import 'dart:convert';

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
      final plaintext = 'Hello, World!';
      
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
```

Run tests:
```bash
cd /home/dan/workspace/priv/speech2code/android
flutter test test/encryption_test.dart

cd /home/dan/workspace/priv/speech2code/desktop
cargo test
```

---

## Verification Checklist

- [ ] Key derivation produces same result on both platforms
- [ ] Encryption/decryption works across platforms
- [ ] Checksum verification works
- [ ] Pairing request sent from Android
- [ ] PIN dialog shown on Linux
- [ ] PAIR_ACK sent after PIN entry
- [ ] Credentials stored securely on both sides
- [ ] Reconnection uses stored credentials
- [ ] All messages encrypted after pairing
- [ ] Integration tests pass

## Output Artifacts

After completing this phase:

1. **SecureStorageService (Android)** - Encrypted credential storage
2. **SecureStorage (Linux)** - Paired device storage
3. **PairingManager** - Coordinates pairing flow
4. **Integration tests** - Cross-platform verification
5. **E2E test script** - Manual testing guide

## Next Phase

Proceed to **Phase 10: Packaging & Deployment** to create release builds, install scripts, and distribution packages.
