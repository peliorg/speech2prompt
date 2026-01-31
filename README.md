# Speech2Code

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

A voice-to-keyboard bridge that lets you dictate text directly into any application on your Linux desktop using your Android phone.

## Features

- **Seamless Dictation**: Speak on your phone, text appears at cursor on your PC
- **Voice Commands**: Control your desktop with commands like "new line", "select all", "copy"
- **Secure Connection**: AES-256 encrypted Bluetooth communication
- **Works Everywhere**: Types into any application - IDEs, browsers, terminals
- **Low Latency**: Direct Bluetooth connection, no internet required
- **History Log**: Searchable history of all dictated text

## Components

| Component | Technology | Description |
|-----------|------------|-------------|
| Android App | Flutter | Speech recognition, Bluetooth client |
| Linux Desktop | Rust + GTK4 | Bluetooth server, keyboard simulation, system tray |

## Quick Start

### Linux Desktop

```bash
# Install
cd desktop
./scripts/install.sh

# Start
speech2code-desktop
# Or: systemctl --user start speech2code
```

### Android

1. Install the APK from releases
2. Grant microphone and Bluetooth permissions
3. Pair with your computer in Bluetooth settings
4. Open Speech2Code and connect

### First-Time Pairing

1. Start the desktop app
2. Open the Android app and tap Connect
3. Select your computer from the list
4. Enter the same 6-digit PIN on both devices
5. Start speaking!

## Voice Commands

| Say | Action |
|-----|--------|
| "new line" or "enter" | Press Enter |
| "select all" | Ctrl+A |
| "copy that" | Ctrl+C |
| "paste" | Ctrl+V |
| "cut that" | Ctrl+X |
| "cancel" | Discard current text |

## Building from Source

### Prerequisites

- **Android**: Flutter SDK 3.16+, Android SDK
- **Linux**: Rust 1.70+, GTK4 dev libraries, BlueZ

### Build

```bash
# Android
cd android
flutter pub get
flutter build apk

# Linux
cd desktop
cargo build --release
```

## System Requirements

### Android
- Android 5.0 (API 21) or higher
- Bluetooth support
- Microphone

### Linux
- X11 or Wayland (with ydotool)
- Bluetooth adapter
- BlueZ 5.x

## Configuration

Desktop config: `~/.config/speech2code/config.toml`

```toml
[bluetooth]
device_name = "Speech2Code"
auto_accept = true

[input]
typing_delay_ms = 10
prefer_backend = "auto"

[history]
enabled = true
max_entries = 10000
```

## Troubleshooting

### Bluetooth not connecting
```bash
# Check Bluetooth status
sudo systemctl status bluetooth

# Restart Bluetooth
sudo systemctl restart bluetooth

# Check if device is paired
bluetoothctl paired-devices
```

### Text not typing (Wayland)
```bash
# Install ydotool
sudo apt install ydotool

# Start ydotool daemon
systemctl --user enable ydotoold
systemctl --user start ydotoold
```

### Permission issues on Android
- Go to Settings > Apps > Speech2Code > Permissions
- Enable Microphone and Nearby devices (Bluetooth)

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

### Patent Grant

The Apache 2.0 license includes an explicit patent grant, which means:
- You receive patent rights for any patentable aspects of the software
- Contributors grant you patent licenses for their contributions
- If someone sues claiming patent infringement, their license terminates

For the full license terms, see [http://www.apache.org/licenses/LICENSE-2.0](http://www.apache.org/licenses/LICENSE-2.0)

## Contributing

Contributions welcome! Please read CONTRIBUTING.md first.