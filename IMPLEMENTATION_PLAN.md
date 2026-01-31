# Speech2Code - Implementation Plan

## Project Overview

**Goal**: Build a voice-to-keyboard bridge with Android (Flutter) and Linux (Rust/GTK4) components communicating via Bluetooth.

---

## Phase 1: Project Setup & Foundation

### 1.1 Repository Structure

```
speech2code/
├── android/                    # Flutter Android app
│   ├── lib/
│   │   ├── main.dart
│   │   ├── services/
│   │   │   ├── speech_service.dart
│   │   │   ├── bluetooth_service.dart
│   │   │   └── command_processor.dart
│   │   ├── models/
│   │   │   ├── message.dart
│   │   │   └── voice_command.dart
│   │   ├── screens/
│   │   │   ├── home_screen.dart
│   │   │   ├── settings_screen.dart
│   │   │   └── connection_screen.dart
│   │   ├── widgets/
│   │   │   ├── audio_visualizer.dart
│   │   │   ├── connection_status.dart
│   │   │   └── transcription_preview.dart
│   │   └── utils/
│   │       ├── encryption.dart
│   │       └── constants.dart
│   ├── android/
│   ├── pubspec.yaml
│   └── README.md
│
├── desktop/                    # Rust Linux app
│   ├── src/
│   │   ├── main.rs
│   │   ├── bluetooth/
│   │   │   ├── mod.rs
│   │   │   ├── server.rs
│   │   │   └── protocol.rs
│   │   ├── input/
│   │   │   ├── mod.rs
│   │   │   ├── x11.rs
│   │   │   ├── wayland.rs
│   │   │   └── injector.rs
│   │   ├── commands/
│   │   │   ├── mod.rs
│   │   │   └── executor.rs
│   │   ├── ui/
│   │   │   ├── mod.rs
│   │   │   ├── tray.rs
│   │   │   └── history_window.rs
│   │   ├── storage/
│   │   │   ├── mod.rs
│   │   │   └── history.rs
│   │   ├── crypto/
│   │   │   └── mod.rs
│   │   └── config/
│   │       └── mod.rs
│   ├── Cargo.toml
│   ├── resources/
│   │   ├── icons/
│   │   └── speech2code.desktop
│   └── README.md
│
├── protocol/                   # Shared protocol documentation
│   └── PROTOCOL.md
│
├── scripts/
│   ├── install-desktop.sh
│   └── setup-bluetooth.sh
│
├── IMPLEMENTATION_PLAN.md
├── SPECIFICATION.md
└── README.md
```

### 1.2 Tasks

| Task | Priority | Estimated Time |
|------|----------|----------------|
| Create repository structure | High | 1 hour |
| Initialize Flutter project | High | 30 min |
| Initialize Rust project with Cargo | High | 30 min |
| Define shared protocol specification | High | 2 hours |
| Setup CI/CD basics (optional) | Low | 2 hours |

---

## Phase 2: Communication Protocol & Shared Components

### 2.1 Protocol Definition

**Message Types**:
```
TEXT      - Plain text to be typed
COMMAND   - Voice command to execute
HEARTBEAT - Keep-alive ping
ACK       - Acknowledgment
PAIR_REQ  - Pairing request with encryption key exchange
PAIR_ACK  - Pairing acknowledgment
```

**Message Structure** (JSON over Bluetooth RFCOMM):
```json
{
  "v": 1,
  "t": "TEXT|COMMAND|HEARTBEAT|ACK|PAIR_REQ|PAIR_ACK",
  "p": "payload string",
  "ts": 1234567890123,
  "cs": "abc12345"
}
```

- `v`: Protocol version
- `t`: Message type
- `p`: Payload (text content or command code)
- `ts`: Unix timestamp in milliseconds
- `cs`: First 8 characters of SHA-256 hash of `v+t+p+ts+shared_secret`

**Command Codes**:
```
ENTER       - Press Enter key
SELECT_ALL  - Ctrl+A
COPY        - Ctrl+C
PASTE       - Ctrl+V
CUT         - Ctrl+X
CANCEL      - Discard pending text
```

### 2.2 Encryption Scheme

1. **Initial Pairing**:
   - User enters 6-digit PIN on both devices
   - PIN + device identifiers used to derive shared secret via PBKDF2
   - Shared secret stored securely on both devices

2. **Session Encryption**:
   - Each session generates random IV
   - AES-256-GCM for payload encryption
   - Checksum computed over encrypted payload

### 2.3 Tasks

| Task | Priority | Estimated Time |
|------|----------|----------------|
| Document protocol in PROTOCOL.md | High | 2 hours |
| Implement message serialization (Rust) | High | 3 hours |
| Implement message serialization (Dart) | High | 3 hours |
| Implement encryption utils (Rust) | High | 4 hours |
| Implement encryption utils (Dart) | High | 4 hours |
| Unit tests for protocol | Medium | 3 hours |

---

## Phase 3: Linux Desktop App - Core

### 3.1 Bluetooth Server

**Dependencies**:
- `bluer` - Official BlueZ Rust bindings
- `tokio` - Async runtime
- `tokio-serial` - For RFCOMM streams

**Implementation**:
1. Register SPP (Serial Port Profile) service with BlueZ
2. Listen for incoming RFCOMM connections
3. Handle connection lifecycle (connect, authenticate, disconnect)
4. Parse incoming messages and dispatch to handlers

### 3.2 Text Injection

**X11 Implementation**:
- Use `x11rb` or `xdotool` crate
- Simulate XTest keyboard events
- Handle Unicode input via XIM or direct keysym mapping

**Wayland Implementation**:
- Communicate with `ydotoold` daemon via socket
- Fall back to `wtype` if available
- Detect compositor and adapt (wlroots-based, GNOME, KDE)

**Unified Interface**:
```rust
pub trait TextInjector {
    fn type_text(&self, text: &str) -> Result<()>;
    fn press_key(&self, key: Key) -> Result<()>;
    fn key_combo(&self, modifiers: &[Modifier], key: Key) -> Result<()>;
}
```

### 3.3 Tasks

| Task | Priority | Estimated Time |
|------|----------|----------------|
| Setup Rust project with dependencies | High | 1 hour |
| Implement BlueZ service registration | High | 4 hours |
| Implement RFCOMM listener | High | 4 hours |
| Implement X11 text injection | High | 6 hours |
| Implement Wayland text injection | High | 6 hours |
| Create injector abstraction layer | High | 2 hours |
| Display server auto-detection | Medium | 2 hours |
| Integration testing | Medium | 4 hours |

---

## Phase 4: Linux Desktop App - UI & Features

### 4.1 System Tray

**Dependencies**:
- `ksni` - KDE StatusNotifierItem for modern tray support
- `gtk4` - For settings/history windows

**Tray Features**:
- Status icon (connected/disconnected/error states)
- Tooltip showing connection status
- Menu: Enable/Disable, History, Settings, Quit

### 4.2 History Storage

**Dependencies**:
- `rusqlite` - SQLite bindings

**Schema**:
```sql
CREATE TABLE history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp INTEGER NOT NULL,
    message_type TEXT NOT NULL,
    content TEXT NOT NULL,
    executed INTEGER DEFAULT 1,
    active_window TEXT
);

CREATE INDEX idx_timestamp ON history(timestamp);
CREATE INDEX idx_content ON history(content);
```

### 4.3 History Window

- GTK4 window with search box
- List view showing timestamp, content, type
- Export to text file option
- Clear history option

### 4.4 Configuration

**Config File**: `~/.config/speech2code/config.toml`

```toml
[bluetooth]
device_name = "Speech2Code"
auto_accept = true

[input]
typing_delay_ms = 10
prefer_backend = "auto"  # auto, x11, wayland

[security]
# Stored encrypted
paired_devices = []

[history]
enabled = true
max_entries = 10000
```

### 4.5 Tasks

| Task | Priority | Estimated Time |
|------|----------|----------------|
| Implement system tray with ksni | High | 4 hours |
| Design tray menu and actions | High | 2 hours |
| Implement SQLite history storage | High | 3 hours |
| Create history window UI | Medium | 4 hours |
| Implement configuration loading/saving | High | 3 hours |
| Create settings dialog | Medium | 4 hours |

---

## Phase 5: Android App - Core

### 5.1 Speech Recognition

**Dependencies**:
- `speech_to_text` package

**Implementation**:
1. Initialize speech recognizer with locale
2. Handle continuous listening mode
3. Process partial and final results
4. Restart recognition automatically after timeout
5. Handle errors gracefully (no internet, permission denied, etc.)

### 5.2 Bluetooth Client

**Dependencies**:
- `flutter_bluetooth_serial` or `flutter_blue_plus`

**Implementation**:
1. Scan for paired devices with SPP
2. Connect to selected device
3. Maintain connection with heartbeat
4. Reconnect automatically on disconnect
5. Queue messages during brief disconnections

### 5.3 Voice Command Processor

**Pattern Matching**:
```dart
final commandPatterns = {
  RegExp(r'\b(new line|enter|next line)\b', caseSensitive: false): Command.enter,
  RegExp(r'\b(select all|select everything)\b', caseSensitive: false): Command.selectAll,
  RegExp(r'\b(copy that|copy this|copy it)\b', caseSensitive: false): Command.copy,
  RegExp(r'\b(paste|paste it|paste that)\b', caseSensitive: false): Command.paste,
  RegExp(r'\b(cut that|cut this|cut it)\b', caseSensitive: false): Command.cut,
  RegExp(r'\b(cancel|clear|never mind)\b', caseSensitive: false): Command.cancel,
};
```

**Processing Flow**:
1. Receive recognized text
2. Check for command patterns
3. If command found at end of text:
   - Send preceding text
   - Send command
4. Else send as plain text

### 5.4 Tasks

| Task | Priority | Estimated Time |
|------|----------|----------------|
| Setup Flutter project | High | 1 hour |
| Implement speech recognition service | High | 6 hours |
| Implement continuous listening mode | High | 4 hours |
| Implement Bluetooth connection service | High | 6 hours |
| Implement message queue and retry logic | High | 3 hours |
| Implement voice command processor | High | 4 hours |
| Unit tests for command processor | Medium | 2 hours |

---

## Phase 6: Android App - UI

### 6.1 Home Screen

**Components**:
- Large microphone button (tap to pause/resume)
- Animated audio level visualizer
- Real-time transcription preview (last ~100 chars)
- Connection status indicator (dot + text)
- Settings icon in app bar

**States**:
- Listening (green pulse animation)
- Paused (gray, static)
- Disconnected (red warning)
- Connecting (yellow spinner)

### 6.2 Connection Screen

**Components**:
- List of paired Bluetooth devices
- Currently connected device highlight
- Manual scan button
- Connection status for each device

### 6.3 Settings Screen

**Options**:
- Language selection for speech recognition
- Voice commands toggle (individual commands)
- Text transmission delay slider
- Auto-reconnect toggle
- Keep screen on while listening toggle

### 6.4 Tasks

| Task | Priority | Estimated Time |
|------|----------|----------------|
| Design app theme and colors | Medium | 2 hours |
| Implement home screen layout | High | 4 hours |
| Create audio visualizer widget | Medium | 4 hours |
| Implement connection screen | High | 3 hours |
| Implement settings screen | High | 3 hours |
| Add state management (Provider/Riverpod) | High | 4 hours |
| Polish animations and transitions | Low | 3 hours |

---

## Phase 7: Integration & Security

### 7.1 Pairing Flow

**Android Side**:
1. Display pairing code input screen
2. Generate device fingerprint
3. Send PAIR_REQ with encrypted fingerprint
4. Wait for PAIR_ACK
5. Store paired device info

**Linux Side**:
1. Show notification of pairing request
2. Display PIN input dialog
3. Verify PAIR_REQ fingerprint
4. Send PAIR_ACK
5. Store paired device info

### 7.2 Security Implementation

- PBKDF2 key derivation from PIN + device IDs
- AES-256-GCM encryption for all messages
- Secure storage: Android Keystore, Linux secret-tool/keyring

### 7.3 Tasks

| Task | Priority | Estimated Time |
|------|----------|----------------|
| Implement pairing flow (Android) | High | 4 hours |
| Implement pairing flow (Linux) | High | 4 hours |
| Secure key storage (Android) | High | 3 hours |
| Secure key storage (Linux) | High | 3 hours |
| End-to-end encryption testing | High | 4 hours |

---

## Phase 8: Polish & Packaging

### 8.1 Linux Packaging

**Components**:
- Systemd user service file
- XDG desktop entry for autostart
- AppImage for distribution
- Install script

**Files**:
```
/usr/local/bin/speech2code
/usr/share/applications/speech2code.desktop
/usr/share/icons/hicolor/*/apps/speech2code.png
~/.config/systemd/user/speech2code.service
~/.config/autostart/speech2code.desktop
```

### 8.2 Android Release

- Generate release keystore
- Configure ProGuard/R8
- Create Play Store assets (if publishing)
- Build signed APK/AAB

### 8.3 Tasks

| Task | Priority | Estimated Time |
|------|----------|----------------|
| Create Linux install script | High | 3 hours |
| Create systemd service file | High | 1 hour |
| Create desktop entries | High | 1 hour |
| Build AppImage | Medium | 4 hours |
| Android release build setup | High | 2 hours |
| Create app icons (all sizes) | Medium | 2 hours |
| Write user documentation | Medium | 4 hours |

---

## Phase 9: Testing & Quality Assurance

### 9.1 Test Categories

| Category | Scope |
|----------|-------|
| Unit Tests | Protocol, encryption, command parsing |
| Integration Tests | Bluetooth communication, text injection |
| End-to-End Tests | Full workflow on real devices |
| Performance Tests | Latency measurement, battery impact |

### 9.2 Test Scenarios

1. **Basic text transmission**: Speak text, verify it appears on screen
2. **Voice commands**: Test each command in various contexts
3. **Reconnection**: Disconnect Bluetooth, verify auto-reconnect
4. **Long text**: Dictate several paragraphs continuously
5. **Special characters**: Test punctuation, numbers, symbols
6. **Multi-language**: Test with different speech recognition languages
7. **X11 vs Wayland**: Verify injection works on both
8. **Different apps**: Test in terminal, browser, IDE, text editor

### 9.3 Tasks

| Task | Priority | Estimated Time |
|------|----------|----------------|
| Write unit tests (Rust) | High | 6 hours |
| Write unit tests (Dart) | High | 4 hours |
| Manual E2E testing | High | 8 hours |
| Performance benchmarking | Medium | 4 hours |
| Fix bugs from testing | High | 8 hours |

---

## Timeline Summary

| Phase | Duration | Dependencies |
|-------|----------|--------------|
| Phase 1: Setup | 1 day | None |
| Phase 2: Protocol | 2 days | Phase 1 |
| Phase 3: Linux Core | 4 days | Phase 2 |
| Phase 4: Linux UI | 3 days | Phase 3 |
| Phase 5: Android Core | 4 days | Phase 2 |
| Phase 6: Android UI | 3 days | Phase 5 |
| Phase 7: Integration | 3 days | Phase 4, 6 |
| Phase 8: Packaging | 2 days | Phase 7 |
| Phase 9: Testing | 3 days | Phase 8 |

**Total Estimated Time**: ~25 days (single developer, full-time)

---

## Risk Mitigation

| Risk | Mitigation |
|------|------------|
| Bluetooth compatibility issues | Test early on multiple devices; have fallback to WiFi |
| Wayland key injection limitations | Support X11 first; use ydotool as primary Wayland solution |
| Android speech recognition reliability | Implement robust error handling and auto-restart |
| Battery drain on Android | Optimize recognition intervals; add power-saving mode |
| BlueZ API complexity | Use high-level `bluer` crate; reference existing projects |

---

## Dependencies Summary

### Rust (Linux Desktop)

```toml
[dependencies]
tokio = { version = "1", features = ["full"] }
bluer = { version = "0.17", features = ["rfcomm"] }
gtk4 = "0.7"
ksni = "0.2"
rusqlite = { version = "0.30", features = ["bundled"] }
serde = { version = "1", features = ["derive"] }
serde_json = "1"
aes-gcm = "0.10"
sha2 = "0.10"
pbkdf2 = "0.12"
rand = "0.8"
dirs = "5"
toml = "0.8"
tracing = "0.1"
tracing-subscriber = "0.3"
anyhow = "1"
thiserror = "1"

# For X11
x11rb = { version = "0.13", features = ["allow-unsafe-code"] }

# For Wayland (ydotool communication)
tokio-unix = "0.1"
```

### Flutter (Android)

```yaml
dependencies:
  flutter:
    sdk: flutter
  speech_to_text: ^6.6.0
  flutter_bluetooth_serial: ^0.4.0
  provider: ^6.1.0
  shared_preferences: ^2.2.0
  flutter_secure_storage: ^9.0.0
  encrypt: ^5.0.3
  crypto: ^3.0.3
  permission_handler: ^11.0.0
```

---

## Next Steps

Ready to begin implementation. Recommended starting point:

1. **Create project structure** (Phase 1)
2. **Define protocol** (Phase 2) - needed by both apps
3. **Start Linux Bluetooth server** (Phase 3) - can test with generic BT terminal app
4. **Start Android speech + BT** (Phase 5) - parallel development

Would you like me to begin with Phase 1 (project setup)?
