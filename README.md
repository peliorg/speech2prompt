# Speech2Prompt

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

A voice-to-keyboard bridge that lets you dictate text directly into any application on your Linux desktop using your Android phone.

## Features

- **Seamless Dictation**: Speak on your phone, text appears at cursor on your PC
- **Voice Commands**: Control your desktop with commands like "new line", "select all", "copy"
- **Secure Connection**: AES-256 encrypted Bluetooth Low Energy (BLE) communication
- **Works Everywhere**: Types into any application - IDEs, browsers, terminals
- **Low Latency**: Direct BLE connection, no internet required
- **History Log**: Searchable history of all dictated text

## Components

| Component | Technology | Description |
|-----------|------------|-------------|
| Android App | Kotlin + Jetpack Compose | Speech recognition, BLE GATT client |
| Linux Desktop | Rust + GTK4 | BLE GATT server, keyboard simulation, system tray |

## Quick Start

### Linux Desktop

```bash
# Install
cd desktop
./scripts/install.sh

# Start
speech2prompt-desktop
# Or: systemctl --user start speech2prompt
```

### Android

1. Install the APK from releases
2. Grant microphone and Bluetooth permissions
3. Open Speech2Prompt and scan for devices
4. Select your computer and connect

### First-Time Pairing

1. Start the desktop app (it will appear in system tray)
2. Open the Android app and tap "Scan for Devices"
3. Select your computer from the list and tap "Connect"
4. Accept the connection on your desktop when prompted
5. Start speaking!

## Voice Commands

| Say | Action |
|-----|--------|
| "new line", "enter", "new paragraph" | Press Enter |
| "go back", "backspace" | Press Backspace |
| "select all" | Ctrl+A |
| "copy", "copy that" | Ctrl+C |
| "paste", "paste that" | Ctrl+V |
| "cut", "cut that" | Ctrl+X |
| "cancel" | Discard current text |

### Custom Voice Commands

You can customize voice command phrases by editing `~/.config/speech2prompt/voice_commands.json`:

```json
{
  "ENTER": ["new line", "enter", "new paragraph"],
  "BACKSPACE": ["go back", "backspace"],
  "SELECT_ALL": ["select all"],
  "COPY": ["copy", "copy that"],
  "PASTE": ["paste", "paste that"],
  "CUT": ["cut", "cut that"]
}
```

The desktop app will reload custom phrases automatically when the file changes.

## Building from Source

### Prerequisites

- **Android**: Android Studio, JDK 17+, Android SDK (API 35 for target, API 26+ minimum)
- **Linux**: Rust 1.70+, GTK4 dev libraries, BlueZ 5.50+

### Build

```bash
# Android
cd android/android
./gradlew assembleDebug
# Or for release: ./gradlew assembleRelease

# Linux
cd desktop
cargo build --release
```

## System Requirements

### Android
- Android 8.0 (API 26) or higher
- Bluetooth Low Energy (BLE) support
- Microphone

### Linux
- X11 or Wayland (with ydotool)
- Bluetooth adapter with BLE support
- BlueZ 5.x with BLE GATT support

## Configuration

Desktop config: `~/.config/speech2prompt/config.toml`

```toml
[bluetooth]
device_name = "Speech2Prompt"
auto_accept = true  # Auto-accept reconnections from previously paired devices

[input]
typing_delay_ms = 10  # Delay between keystrokes
prefer_backend = "auto"  # Options: "auto", "x11", "wayland"

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

# Ensure your adapter supports BLE
hciconfig -a | grep -i "le"

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
- Go to Settings > Apps > Speech2Prompt > Permissions
- Enable Microphone and Nearby devices (Bluetooth)

## Releases

### Installing Pre-built Packages

Download the latest release from the [Releases page](https://github.com/peliorg/speech2prompt/releases).

**Linux:**
- `.deb` - For Ubuntu/Debian: `sudo dpkg -i speech2prompt-*.deb`
- `.rpm` - For Fedora/RHEL: `sudo rpm -i speech2prompt-*.rpm`
- `.AppImage` - Universal: `chmod +x Speech2Prompt-*.AppImage && ./Speech2Prompt-*.AppImage`

**Android:**
- `.apk` - Enable "Install from Unknown Sources", then install

### Creating a Release

For maintainers: Releases are automated via GitHub Actions.

1. Ensure all tests pass and changes are committed
2. Go to Actions → Release Build → Run workflow
3. Enter the version number (e.g., `1.2.3`)
4. The workflow will:
   - Update version numbers
   - Create a git tag
   - Build all packages (deb, rpm, AppImage, APK)
   - Create a GitHub Release with all artifacts

See [.github/workflows/README.md](.github/workflows/README.md) for detailed setup instructions.

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

### Patent Grant

The Apache 2.0 license includes an explicit patent grant, which means:
- You receive patent rights for any patentable aspects of the software
- Contributors grant you patent licenses for their contributions
- If someone sues claiming patent infringement, their license terminates

For the full license terms, see [http://www.apache.org/licenses/LICENSE-2.0](http://www.apache.org/licenses/LICENSE-2.0)

## Contributing

Contributions welcome! Please:
1. Fork the repository
2. Create a feature branch
3. Make your changes with tests
4. Submit a pull request

For bug reports and feature requests, please open an issue on GitHub.