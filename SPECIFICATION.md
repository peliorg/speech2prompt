# Speech2Code - Application Specification

## Executive Summary

**Speech2Code** is a voice-to-text bridge application that enables seamless dictation directly into any application on a Linux desktop. It consists of a Flutter-based Android app that captures voice input using Android's built-in speech recognition, and a Rust-based Linux system tray application that receives transcribed text via Bluetooth and types it at the current cursor position.

---

## Problem Statement

Developers and power users often need to:
- Quickly draft prompts for AI tools without breaking their keyboard workflow
- Input natural language descriptions while coding
- Reduce repetitive typing strain
- Combine voice input with precise keyboard input for technical terms, code, and class names

Current solutions either require constant app switching, don't integrate well with Linux, or lack the precision needed for technical workflows.

---

## Solution Overview

### Architecture

```
┌─────────────────────┐         Bluetooth          ┌─────────────────────┐
│   Android Device    │◄──────────────────────────►│    Linux Desktop    │
│                     │      (RFCOMM/SPP)          │                     │
│  ┌───────────────┐  │                            │  ┌───────────────┐  │
│  │  Flutter App  │  │                            │  │   Rust App    │  │
│  │               │  │                            │  │               │  │
│  │ - Speech Rec  │  │      Transcribed Text      │  │ - BT Server   │  │
│  │ - BT Client   │──┼──────────────────────────►─┼──│ - Key Inject  │  │
│  │ - Voice Cmds  │  │                            │  │ - Sys Tray    │  │
│  └───────────────┘  │                            │  │ - History Log │  │
│                     │                            │  └───────────────┘  │
└─────────────────────┘                            └─────────────────────┘
```

---

## Component 1: Android App (Flutter)

### Core Features

| Feature | Description |
|---------|-------------|
| **Continuous Speech Recognition** | Uses Android's `SpeechRecognizer` API via Flutter plugin for always-on voice capture |
| **Visual Feedback** | Real-time display of recognized text, connection status, and audio level indicator |
| **Bluetooth Client** | Connects to paired Linux PC via Bluetooth Serial Port Profile (SPP) |
| **Voice Command Processing** | Detects and processes special voice commands before transmission |
| **Pairing Flow** | Simple device discovery and pairing with PIN confirmation |

### Voice Commands

| Command Phrase | Action | Transmitted Code |
|----------------|--------|------------------|
| "new line" / "enter" | Insert line break | `{CMD:ENTER}` |
| "select all" | Select all text | `{CMD:SELECT_ALL}` |
| "copy that" | Copy selection | `{CMD:COPY}` |
| "paste" | Paste clipboard | `{CMD:PASTE}` |
| "cut that" | Cut selection | `{CMD:CUT}` |
| "cancel" / "clear" | Discard current buffer | `{CMD:CANCEL}` |
| "stop listening" | Pause recognition | (local action) |
| "start listening" | Resume recognition | (local action) |

### UI Screens

1. **Main Screen**
   - Large microphone indicator (listening/paused state)
   - Real-time transcription preview
   - Connection status badge
   - Audio level visualizer
   - Pause/Resume button

2. **Settings Screen**
   - Bluetooth device selection
   - Language selection for speech recognition
   - Voice command customization (enable/disable specific commands)
   - Text transmission delay setting

3. **Connection Screen**
   - Discovered devices list
   - Pairing status
   - Connection history

---

## Component 2: Linux Desktop App (Rust + GTK4)

### Core Features

| Feature | Description |
|---------|-------------|
| **Bluetooth Server** | Listens for incoming SPP connections from paired Android device |
| **Text Injection** | Simulates keyboard input at current cursor position using `libxdo` (X11) or `ydotool` (Wayland) |
| **System Tray Integration** | Minimal tray icon with status indicator and quick actions |
| **Command Execution** | Interprets received commands and executes appropriate keyboard shortcuts |
| **Text History** | Maintains searchable log of all transcribed text with timestamps |
| **Basic Encryption** | AES-encrypted transmission with shared secret established during pairing |

### System Tray Menu

```
┌─────────────────────────┐
│ ● Connected to Phone    │
├─────────────────────────┤
│ ○ Enable Input          │
│ ○ Pause Input           │
├─────────────────────────┤
│ View History...         │
│ Settings...             │
├─────────────────────────┤
│ Quit                    │
└─────────────────────────┘
```

### Command Mapping

| Received Command | Linux Action |
|------------------|--------------|
| `{CMD:ENTER}` | Simulate Enter keypress |
| `{CMD:SELECT_ALL}` | Ctrl+A |
| `{CMD:COPY}` | Ctrl+C |
| `{CMD:PASTE}` | Ctrl+V |
| `{CMD:CUT}` | Ctrl+X |
| `{CMD:CANCEL}` | Discard buffer, no output |
| Plain text | Type text at cursor |

### History Feature

- SQLite database storing:
  - Timestamp
  - Raw text received
  - Commands executed
  - Application context (optional: active window name)
- Simple GTK window to browse/search history
- Export to text file option

### Display Server Support

- **X11**: Primary support via `libxdo`/`x11rb`
- **Wayland**: Support via `ydotool` daemon
- Auto-detection of display server at runtime

### Autostart

- Systemd user service for background operation
- XDG autostart entry in `~/.config/autostart/`
- Configurable via settings

---

## Communication Protocol

### Bluetooth Profile
- **Protocol**: RFCOMM over Bluetooth Classic (SPP - Serial Port Profile)
- **Why**: Reliable, low-latency, well-supported on both platforms, no internet required

### Message Format

```json
{
  "v": 1,
  "t": "TEXT|COMMAND|HEARTBEAT|ACK|PAIR_REQ|PAIR_ACK",
  "p": "payload string",
  "ts": 1234567890123,
  "cs": "abc12345"
}
```

| Field | Description |
|-------|-------------|
| `v` | Protocol version |
| `t` | Message type |
| `p` | Payload (text or command code) |
| `ts` | Unix timestamp (milliseconds) |
| `cs` | Checksum (first 8 chars of SHA-256) |

### Security

1. **Pairing**: Standard Bluetooth pairing with PIN confirmation
2. **Session Key**: Derived from shared secret via PBKDF2
3. **Encryption**: AES-256-GCM for message payload
4. **Integrity**: SHA-256 checksum for message verification

---

## User Workflows

### Initial Setup

1. Install Android app, grant microphone and Bluetooth permissions
2. Install Linux app, ensure Bluetooth is enabled
3. Pair devices via standard Bluetooth pairing
4. Enter matching PIN on both devices to establish encrypted channel
5. Android app connects to Linux app automatically when in range

### Daily Usage

1. Linux app starts on boot, runs in system tray
2. User opens Android app when voice input is needed
3. App automatically connects to PC
4. User speaks; text appears at cursor position on PC
5. User says "new line" for line breaks, continues typing with keyboard as needed
6. Voice commands for clipboard operations when needed

### Example Use Case: Writing an AI Prompt

```
User (speaking): "Write a Python function that takes a list of"
User (typing): Customer
User (speaking): "objects and returns the total revenue, use type hints"
User (speaking): "new line, new line"

Result at cursor:
Write a Python function that takes a list of Customer objects and returns 
the total revenue, use type hints

```

---

## Technical Requirements

### Android App
- **Min SDK**: 21 (Android 5.0)
- **Target SDK**: 34 (Android 14)
- **Permissions**: 
  - `RECORD_AUDIO`
  - `BLUETOOTH`, `BLUETOOTH_ADMIN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`
- **Framework**: Flutter

### Linux App
- **Display Servers**: X11, Wayland
- **Framework**: Rust with GTK4
- **Dependencies**:
  - `bluez` for Bluetooth
  - `libxdo` (X11) / `ydotool` (Wayland) for key injection
  - `libappindicator` or `ksni` for system tray

---

## Non-Functional Requirements

| Requirement | Target |
|-------------|--------|
| Latency (voice to text on screen) | < 500ms after recognition complete |
| Bluetooth reconnection time | < 3 seconds |
| Memory usage (Linux app idle) | < 50MB |
| Battery impact (Android, active) | Comparable to standard voice recorder |
| Supported languages | All languages supported by Android Speech Recognition |

---

## Future Enhancements (Out of Scope for V1)

- Windows/macOS desktop support
- iOS companion app
- Custom wake word detection
- Offline speech recognition option
- Multi-device support (multiple phones to one PC)
- Plugin system for custom commands
- Integration with specific IDEs (VS Code extension)
