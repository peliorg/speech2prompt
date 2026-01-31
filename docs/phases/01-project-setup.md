# Phase 01: Project Setup & Repository Structure

## Overview

This phase establishes the complete project structure for Speech2Code, including both the Flutter Android app and the Rust Linux desktop application. After completing this phase, you will have a buildable skeleton for both applications.

## Prerequisites

- Linux development machine
- Git installed
- Flutter SDK installed (3.16+)
- Rust toolchain installed (rustup)
- Android SDK with platform tools

## Directory Structure

Create the following directory structure:

```
speech2code/
├── android/                    # Flutter Android app
│   ├── lib/
│   │   ├── main.dart
│   │   ├── services/
│   │   ├── models/
│   │   ├── screens/
│   │   ├── widgets/
│   │   └── utils/
│   ├── android/
│   ├── pubspec.yaml
│   └── README.md
│
├── desktop/                    # Rust Linux app
│   ├── src/
│   │   ├── main.rs
│   │   ├── bluetooth/
│   │   ├── input/
│   │   ├── commands/
│   │   ├── ui/
│   │   ├── storage/
│   │   ├── crypto/
│   │   └── config/
│   ├── Cargo.toml
│   └── README.md
│
├── protocol/
│   └── PROTOCOL.md
│
├── docs/
│   └── phases/
│
└── README.md
```

---

## Task 1: Initialize Git Repository

```bash
cd /home/dan/workspace/priv/speech2code
git init
```

Create `.gitignore`:

```gitignore
# Rust
desktop/target/
**/*.rs.bk
Cargo.lock

# Flutter
android/.dart_tool/
android/.packages
android/.pub-cache/
android/.pub/
android/build/
android/.flutter-plugins
android/.flutter-plugins-dependencies
android/*.iml
android/.idea/
android/.metadata

# IDE
.vscode/
*.swp
*.swo
*~

# OS
.DS_Store
Thumbs.db

# Environment
.env
.env.local

# Logs
*.log
```

---

## Task 2: Create Flutter Android Project

```bash
cd /home/dan/workspace/priv/speech2code
flutter create --org com.speech2code --project-name speech2code --platforms android android
cd android
```

### Update `pubspec.yaml`

Replace the contents of `android/pubspec.yaml`:

```yaml
name: speech2code
description: Voice-to-keyboard bridge for seamless dictation to Linux desktop.
publish_to: 'none'
version: 1.0.0+1

environment:
  sdk: '>=3.2.0 <4.0.0'

dependencies:
  flutter:
    sdk: flutter
  
  # Speech recognition
  speech_to_text: ^6.6.0
  
  # Bluetooth
  flutter_bluetooth_serial: ^0.4.0
  
  # State management
  provider: ^6.1.1
  
  # Storage
  shared_preferences: ^2.2.2
  flutter_secure_storage: ^9.0.0
  
  # Encryption
  encrypt: ^5.0.3
  crypto: ^3.0.3
  pointycastle: ^3.7.4
  
  # Permissions
  permission_handler: ^11.1.0
  
  # UI utilities
  cupertino_icons: ^1.0.6

dev_dependencies:
  flutter_test:
    sdk: flutter
  flutter_lints: ^3.0.1

flutter:
  uses-material-design: true
  
  assets:
    - assets/icons/
```

### Create Flutter Directory Structure

```bash
cd /home/dan/workspace/priv/speech2code/android
mkdir -p lib/services
mkdir -p lib/models
mkdir -p lib/screens
mkdir -p lib/widgets
mkdir -p lib/utils
mkdir -p assets/icons
```

### Create Placeholder Files

**`lib/main.dart`**:
```dart
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'screens/home_screen.dart';
import 'services/speech_service.dart';
import 'services/bluetooth_service.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const Speech2CodeApp());
}

class Speech2CodeApp extends StatelessWidget {
  const Speech2CodeApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MultiProvider(
      providers: [
        ChangeNotifierProvider(create: (_) => SpeechService()),
        ChangeNotifierProvider(create: (_) => BluetoothService()),
      ],
      child: MaterialApp(
        title: 'Speech2Code',
        debugShowCheckedModeBanner: false,
        theme: ThemeData(
          colorScheme: ColorScheme.fromSeed(
            seedColor: Colors.blue,
            brightness: Brightness.dark,
          ),
          useMaterial3: true,
        ),
        home: const HomeScreen(),
      ),
    );
  }
}
```

**`lib/services/speech_service.dart`**:
```dart
import 'package:flutter/foundation.dart';

/// Service for managing speech recognition.
/// Full implementation in Phase 06.
class SpeechService extends ChangeNotifier {
  bool _isListening = false;
  String _currentText = '';
  
  bool get isListening => _isListening;
  String get currentText => _currentText;
  
  Future<void> initialize() async {
    // TODO: Initialize speech recognition
  }
  
  Future<void> startListening() async {
    _isListening = true;
    notifyListeners();
  }
  
  Future<void> stopListening() async {
    _isListening = false;
    notifyListeners();
  }
}
```

**`lib/services/bluetooth_service.dart`**:
```dart
import 'package:flutter/foundation.dart';

/// Service for managing Bluetooth connection.
/// Full implementation in Phase 07.
class BluetoothService extends ChangeNotifier {
  bool _isConnected = false;
  String? _connectedDeviceName;
  
  bool get isConnected => _isConnected;
  String? get connectedDeviceName => _connectedDeviceName;
  
  Future<void> initialize() async {
    // TODO: Initialize Bluetooth
  }
  
  Future<void> connect(String address) async {
    // TODO: Connect to device
  }
  
  Future<void> disconnect() async {
    _isConnected = false;
    _connectedDeviceName = null;
    notifyListeners();
  }
  
  Future<void> sendMessage(String message) async {
    // TODO: Send message over Bluetooth
  }
}
```

**`lib/screens/home_screen.dart`**:
```dart
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../services/speech_service.dart';
import '../services/bluetooth_service.dart';

/// Main screen with microphone control and status.
/// Full implementation in Phase 08.
class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final speechService = context.watch<SpeechService>();
    final bluetoothService = context.watch<BluetoothService>();
    
    return Scaffold(
      appBar: AppBar(
        title: const Text('Speech2Code'),
        actions: [
          IconButton(
            icon: const Icon(Icons.settings),
            onPressed: () {
              // TODO: Navigate to settings
            },
          ),
        ],
      ),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            // Connection status
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              decoration: BoxDecoration(
                color: bluetoothService.isConnected
                    ? Colors.green.withOpacity(0.2)
                    : Colors.red.withOpacity(0.2),
                borderRadius: BorderRadius.circular(20),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(
                    bluetoothService.isConnected
                        ? Icons.bluetooth_connected
                        : Icons.bluetooth_disabled,
                    color: bluetoothService.isConnected
                        ? Colors.green
                        : Colors.red,
                  ),
                  const SizedBox(width: 8),
                  Text(
                    bluetoothService.isConnected
                        ? 'Connected'
                        : 'Disconnected',
                  ),
                ],
              ),
            ),
            
            const SizedBox(height: 48),
            
            // Microphone button
            GestureDetector(
              onTap: () {
                if (speechService.isListening) {
                  speechService.stopListening();
                } else {
                  speechService.startListening();
                }
              },
              child: Container(
                width: 150,
                height: 150,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: speechService.isListening
                      ? Colors.red
                      : Colors.blue,
                  boxShadow: [
                    BoxShadow(
                      color: (speechService.isListening
                              ? Colors.red
                              : Colors.blue)
                          .withOpacity(0.5),
                      blurRadius: 20,
                      spreadRadius: 5,
                    ),
                  ],
                ),
                child: Icon(
                  speechService.isListening ? Icons.mic : Icons.mic_none,
                  size: 64,
                  color: Colors.white,
                ),
              ),
            ),
            
            const SizedBox(height: 24),
            
            Text(
              speechService.isListening ? 'Listening...' : 'Tap to speak',
              style: Theme.of(context).textTheme.titleLarge,
            ),
            
            const SizedBox(height: 48),
            
            // Transcription preview
            Container(
              margin: const EdgeInsets.symmetric(horizontal: 24),
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: Colors.white.withOpacity(0.1),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Text(
                speechService.currentText.isEmpty
                    ? 'Your speech will appear here...'
                    : speechService.currentText,
                style: Theme.of(context).textTheme.bodyLarge,
                textAlign: TextAlign.center,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
```

### Update Android Permissions

Edit `android/android/app/src/main/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    
    <!-- Bluetooth permissions -->
    <uses-permission android:name="android.permission.BLUETOOTH" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
    
    <!-- Microphone permission -->
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    
    <!-- Internet for speech recognition -->
    <uses-permission android:name="android.permission.INTERNET" />
    
    <!-- Location for Bluetooth scanning (Android 10+) -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    
    <application
        android:label="Speech2Code"
        android:name="${applicationName}"
        android:icon="@mipmap/ic_launcher">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTop"
            android:theme="@style/LaunchTheme"
            android:configChanges="orientation|keyboardHidden|keyboard|screenSize|smallestScreenSize|locale|layoutDirection|fontScale|screenLayout|density|uiMode"
            android:hardwareAccelerated="true"
            android:windowSoftInputMode="adjustResize">
            <meta-data
              android:name="io.flutter.embedding.android.NormalTheme"
              android:resource="@style/NormalTheme"
              />
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>
        <meta-data
            android:name="flutterEmbedding"
            android:value="2" />
    </application>
</manifest>
```

---

## Task 3: Create Rust Desktop Project

```bash
cd /home/dan/workspace/priv/speech2code
mkdir -p desktop/src
cd desktop
```

### Create `Cargo.toml`

```toml
[package]
name = "speech2code-desktop"
version = "0.1.0"
edition = "2021"
authors = ["Speech2Code Team"]
description = "Linux desktop companion for Speech2Code voice-to-keyboard bridge"
license = "MIT"
repository = "https://github.com/yourusername/speech2code"

[dependencies]
# Async runtime
tokio = { version = "1.35", features = ["full"] }

# Bluetooth
bluer = { version = "0.17", features = ["rfcomm"] }

# GUI
gtk4 = "0.7"
libadwaita = "0.5"

# System tray
ksni = "0.2"

# Database
rusqlite = { version = "0.30", features = ["bundled"] }

# Serialization
serde = { version = "1.0", features = ["derive"] }
serde_json = "1.0"
toml = "0.8"

# Cryptography
aes-gcm = "0.10"
sha2 = "0.10"
pbkdf2 = { version = "0.12", features = ["simple"] }
rand = "0.8"
base64 = "0.21"
hex = "0.4"

# Utilities
dirs = "5.0"
anyhow = "1.0"
thiserror = "1.0"
tracing = "0.1"
tracing-subscriber = { version = "0.3", features = ["env-filter"] }
chrono = { version = "0.4", features = ["serde"] }

# X11 input simulation
# x11rb = { version = "0.13", optional = true }
enigo = "0.1"

# Async channels
async-channel = "2.1"

[features]
default = ["x11"]
x11 = []
wayland = []

[profile.release]
strip = true
lto = true
codegen-units = 1
```

### Create Rust Directory Structure

```bash
mkdir -p src/bluetooth
mkdir -p src/input
mkdir -p src/commands
mkdir -p src/ui
mkdir -p src/storage
mkdir -p src/crypto
mkdir -p src/config
mkdir -p resources/icons
```

### Create Placeholder Rust Files

**`src/main.rs`**:
```rust
//! Speech2Code Desktop Application
//!
//! A Linux system tray application that receives transcribed text from
//! an Android device via Bluetooth and types it at the current cursor position.

mod bluetooth;
mod commands;
mod config;
mod crypto;
mod input;
mod storage;
mod ui;

use anyhow::Result;
use tracing::info;
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};

#[tokio::main]
async fn main() -> Result<()> {
    // Initialize logging
    tracing_subscriber::registry()
        .with(tracing_subscriber::fmt::layer())
        .with(tracing_subscriber::EnvFilter::from_default_env())
        .init();

    info!("Starting Speech2Code Desktop...");

    // Load configuration
    let config = config::Config::load()?;
    info!("Configuration loaded");

    // Initialize storage
    let storage = storage::History::new(&config.data_dir)?;
    info!("History storage initialized");

    // Initialize input injector
    let injector = input::create_injector()?;
    info!("Input injector initialized for {}", injector.backend_name());

    // Start system tray
    info!("Starting system tray...");
    ui::run_tray(config, storage, injector).await?;

    Ok(())
}
```

**`src/bluetooth/mod.rs`**:
```rust
//! Bluetooth communication module.
//!
//! Handles RFCOMM server for receiving messages from Android app.
//! Full implementation in Phase 03.

mod server;
mod protocol;

pub use server::BluetoothServer;
pub use protocol::{Message, MessageType};
```

**`src/bluetooth/server.rs`**:
```rust
//! Bluetooth RFCOMM server implementation.

use anyhow::Result;
use tokio::sync::mpsc;
use super::protocol::Message;

/// Bluetooth server that listens for incoming connections.
pub struct BluetoothServer {
    // TODO: Add bluer session and adapter
}

impl BluetoothServer {
    /// Create a new Bluetooth server.
    pub async fn new() -> Result<Self> {
        // TODO: Initialize BlueZ session
        Ok(Self {})
    }

    /// Start listening for connections.
    pub async fn listen(&self, _tx: mpsc::Sender<Message>) -> Result<()> {
        // TODO: Implement RFCOMM listener
        Ok(())
    }
}
```

**`src/bluetooth/protocol.rs`**:
```rust
//! Message protocol definitions.
//!
//! Full implementation in Phase 02.

use serde::{Deserialize, Serialize};

/// Message types supported by the protocol.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum MessageType {
    Text,
    Command,
    Heartbeat,
    Ack,
    PairReq,
    PairAck,
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
    
    /// Payload content
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
    /// Create a new message.
    pub fn new(message_type: MessageType, payload: String) -> Self {
        let timestamp = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_millis() as u64;
        
        Self {
            version: 1,
            message_type,
            payload,
            timestamp,
            checksum: String::new(), // TODO: Calculate checksum
        }
    }
}
```

**`src/input/mod.rs`**:
```rust
//! Input injection module.
//!
//! Handles simulating keyboard input on X11 and Wayland.
//! Full implementation in Phase 04.

mod injector;

pub use injector::{create_injector, InputInjector};
```

**`src/input/injector.rs`**:
```rust
//! Text injection abstraction.

use anyhow::Result;

/// Keyboard modifiers.
#[derive(Debug, Clone, Copy)]
pub enum Modifier {
    Ctrl,
    Alt,
    Shift,
    Super,
}

/// Special keys.
#[derive(Debug, Clone, Copy)]
pub enum Key {
    Enter,
    Backspace,
    Tab,
    Escape,
    // Add more as needed
}

/// Trait for input injection backends.
pub trait InputInjector: Send + Sync {
    /// Get the backend name (e.g., "X11", "Wayland").
    fn backend_name(&self) -> &'static str;
    
    /// Type text at current cursor position.
    fn type_text(&self, text: &str) -> Result<()>;
    
    /// Press a single key.
    fn press_key(&self, key: Key) -> Result<()>;
    
    /// Press a key combination.
    fn key_combo(&self, modifiers: &[Modifier], key: Key) -> Result<()>;
}

/// Create the appropriate input injector for the current display server.
pub fn create_injector() -> Result<Box<dyn InputInjector>> {
    // TODO: Detect display server and create appropriate backend
    Ok(Box::new(StubInjector))
}

/// Stub injector for initial development.
struct StubInjector;

impl InputInjector for StubInjector {
    fn backend_name(&self) -> &'static str {
        "Stub"
    }

    fn type_text(&self, text: &str) -> Result<()> {
        tracing::info!("Would type: {}", text);
        Ok(())
    }

    fn press_key(&self, key: Key) -> Result<()> {
        tracing::info!("Would press: {:?}", key);
        Ok(())
    }

    fn key_combo(&self, modifiers: &[Modifier], key: Key) -> Result<()> {
        tracing::info!("Would press combo: {:?} + {:?}", modifiers, key);
        Ok(())
    }
}
```

**`src/commands/mod.rs`**:
```rust
//! Voice command execution module.
//!
//! Maps received commands to keyboard actions.
//! Full implementation in Phase 04.

use anyhow::Result;
use crate::input::{InputInjector, Key, Modifier};

/// Voice command types.
#[derive(Debug, Clone, PartialEq)]
pub enum VoiceCommand {
    Enter,
    SelectAll,
    Copy,
    Paste,
    Cut,
    Cancel,
}

impl VoiceCommand {
    /// Parse a command from string.
    pub fn parse(s: &str) -> Option<Self> {
        match s.to_uppercase().as_str() {
            "ENTER" => Some(Self::Enter),
            "SELECT_ALL" => Some(Self::SelectAll),
            "COPY" => Some(Self::Copy),
            "PASTE" => Some(Self::Paste),
            "CUT" => Some(Self::Cut),
            "CANCEL" => Some(Self::Cancel),
            _ => None,
        }
    }
}

/// Execute a voice command.
pub fn execute(command: &VoiceCommand, injector: &dyn InputInjector) -> Result<()> {
    match command {
        VoiceCommand::Enter => injector.press_key(Key::Enter),
        VoiceCommand::SelectAll => injector.key_combo(&[Modifier::Ctrl], Key::Enter), // TODO: 'a' key
        VoiceCommand::Copy => injector.key_combo(&[Modifier::Ctrl], Key::Enter), // TODO: 'c' key
        VoiceCommand::Paste => injector.key_combo(&[Modifier::Ctrl], Key::Enter), // TODO: 'v' key
        VoiceCommand::Cut => injector.key_combo(&[Modifier::Ctrl], Key::Enter), // TODO: 'x' key
        VoiceCommand::Cancel => Ok(()), // Do nothing, just acknowledge
    }
}
```

**`src/ui/mod.rs`**:
```rust
//! UI module for system tray and windows.
//!
//! Full implementation in Phase 05.

mod tray;

pub use tray::run_tray;
```

**`src/ui/tray.rs`**:
```rust
//! System tray implementation.

use anyhow::Result;
use crate::config::Config;
use crate::storage::History;
use crate::input::InputInjector;

/// Run the system tray application.
pub async fn run_tray(
    _config: Config,
    _storage: History,
    _injector: Box<dyn InputInjector>,
) -> Result<()> {
    // TODO: Implement system tray with ksni
    tracing::info!("System tray placeholder - press Ctrl+C to exit");
    
    // Keep running
    tokio::signal::ctrl_c().await?;
    
    Ok(())
}
```

**`src/storage/mod.rs`**:
```rust
//! Storage module for history and configuration persistence.
//!
//! Full implementation in Phase 05.

mod history;

pub use history::History;
```

**`src/storage/history.rs`**:
```rust
//! History storage using SQLite.

use anyhow::Result;
use std::path::Path;

/// History database manager.
pub struct History {
    // TODO: Add rusqlite connection
}

impl History {
    /// Create or open history database.
    pub fn new(data_dir: &Path) -> Result<Self> {
        let db_path = data_dir.join("history.db");
        tracing::info!("History database: {:?}", db_path);
        
        // TODO: Initialize SQLite database
        Ok(Self {})
    }

    /// Add a text entry to history.
    pub fn add_text(&self, _text: &str) -> Result<()> {
        // TODO: Insert into database
        Ok(())
    }

    /// Add a command entry to history.
    pub fn add_command(&self, _command: &str) -> Result<()> {
        // TODO: Insert into database
        Ok(())
    }
}
```

**`src/crypto/mod.rs`**:
```rust
//! Cryptography module for message encryption.
//!
//! Full implementation in Phase 02.

use anyhow::Result;

/// Derive shared secret from PIN and device IDs.
pub fn derive_key(pin: &str, android_id: &str, linux_id: &str) -> Result<[u8; 32]> {
    // TODO: Implement PBKDF2 key derivation
    let _ = (pin, android_id, linux_id);
    Ok([0u8; 32])
}

/// Encrypt a message payload.
pub fn encrypt(plaintext: &str, _key: &[u8; 32]) -> Result<String> {
    // TODO: Implement AES-256-GCM encryption
    Ok(plaintext.to_string())
}

/// Decrypt a message payload.
pub fn decrypt(ciphertext: &str, _key: &[u8; 32]) -> Result<String> {
    // TODO: Implement AES-256-GCM decryption
    Ok(ciphertext.to_string())
}

/// Calculate message checksum.
pub fn checksum(version: u8, msg_type: &str, payload: &str, timestamp: u64, secret: &[u8]) -> String {
    // TODO: Implement SHA-256 checksum
    let _ = (version, msg_type, payload, timestamp, secret);
    "00000000".to_string()
}
```

**`src/config/mod.rs`**:
```rust
//! Configuration module.
//!
//! Handles loading and saving application settings.

use anyhow::Result;
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

/// Application configuration.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    /// Data directory for history and settings.
    #[serde(skip)]
    pub data_dir: PathBuf,
    
    /// Bluetooth settings.
    pub bluetooth: BluetoothConfig,
    
    /// Input settings.
    pub input: InputConfig,
    
    /// History settings.
    pub history: HistoryConfig,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BluetoothConfig {
    /// Device name advertised over Bluetooth.
    pub device_name: String,
    
    /// Auto-accept connections from paired devices.
    pub auto_accept: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InputConfig {
    /// Delay between keystrokes in milliseconds.
    pub typing_delay_ms: u32,
    
    /// Preferred backend: "auto", "x11", or "wayland".
    pub prefer_backend: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HistoryConfig {
    /// Enable history logging.
    pub enabled: bool,
    
    /// Maximum number of history entries.
    pub max_entries: u32,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            data_dir: dirs::data_dir()
                .unwrap_or_else(|| PathBuf::from("."))
                .join("speech2code"),
            bluetooth: BluetoothConfig {
                device_name: "Speech2Code".to_string(),
                auto_accept: true,
            },
            input: InputConfig {
                typing_delay_ms: 10,
                prefer_backend: "auto".to_string(),
            },
            history: HistoryConfig {
                enabled: true,
                max_entries: 10000,
            },
        }
    }
}

impl Config {
    /// Load configuration from file or create default.
    pub fn load() -> Result<Self> {
        let config_dir = dirs::config_dir()
            .unwrap_or_else(|| PathBuf::from("."))
            .join("speech2code");
        
        std::fs::create_dir_all(&config_dir)?;
        
        let config_path = config_dir.join("config.toml");
        
        let mut config = if config_path.exists() {
            let content = std::fs::read_to_string(&config_path)?;
            toml::from_str(&content)?
        } else {
            let config = Self::default();
            let content = toml::to_string_pretty(&config)?;
            std::fs::write(&config_path, content)?;
            config
        };
        
        // Set data directory
        config.data_dir = dirs::data_dir()
            .unwrap_or_else(|| PathBuf::from("."))
            .join("speech2code");
        std::fs::create_dir_all(&config.data_dir)?;
        
        Ok(config)
    }
    
    /// Save configuration to file.
    pub fn save(&self) -> Result<()> {
        let config_dir = dirs::config_dir()
            .unwrap_or_else(|| PathBuf::from("."))
            .join("speech2code");
        
        let config_path = config_dir.join("config.toml");
        let content = toml::to_string_pretty(self)?;
        std::fs::write(config_path, content)?;
        
        Ok(())
    }
}
```

---

## Task 4: Verify Builds

### Build Flutter App

```bash
cd /home/dan/workspace/priv/speech2code/android
flutter pub get
flutter build apk --debug
```

### Build Rust Desktop App

```bash
cd /home/dan/workspace/priv/speech2code/desktop
cargo build
```

---

## Verification Checklist

- [ ] Git repository initialized with `.gitignore`
- [ ] Flutter project created and builds successfully
- [ ] All Flutter placeholder files in place
- [ ] Android permissions configured in manifest
- [ ] Rust project created and compiles successfully
- [ ] All Rust module stubs in place
- [ ] Configuration module creates default config file

## Output Artifacts

After completing this phase:

1. **Flutter app** compiles and shows placeholder UI
2. **Rust app** compiles and runs (exits immediately)
3. **Config file** created at `~/.config/speech2code/config.toml`
4. **Data directory** created at `~/.local/share/speech2code/`

## Next Phase

Proceed to **Phase 02: Communication Protocol Implementation** to implement the message serialization, encryption, and protocol handling on both platforms.
