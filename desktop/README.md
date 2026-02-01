# Speech2Prompt Desktop

Linux desktop application for Speech2Prompt voice-to-keyboard bridge, built with Rust and GTK4.

## Overview

This is the desktop component that receives transcribed text from the Android app via Bluetooth Low Energy (BLE) and injects it as keyboard input into the active application window.

## Technology Stack

- **Language**: Rust 1.70+
- **UI Framework**: GTK4 + libadwaita
- **Async Runtime**: Tokio
- **Bluetooth**: BlueZ (BLE GATT server)
- **Database**: SQLite (via rusqlite)
- **Encryption**: AES-256-GCM

## Key Features

- BLE GATT server for low-power communication
- X11 and Wayland input injection support
- System tray integration
- History logging with SQLite
- AES-256-GCM encryption with ECDH key exchange
- Configurable via TOML file
- Systemd service integration

## Building

### Prerequisites

#### Debian/Ubuntu
```bash
sudo apt install \
    build-essential \
    pkg-config \
    libgtk-4-dev \
    libadwaita-1-dev \
    libdbus-1-dev \
    libbluetooth-dev \
    libsqlite3-dev \
    libxdo-dev
```

#### Fedora/RHEL
```bash
sudo dnf install \
    gcc \
    pkg-config \
    gtk4-devel \
    libadwaita-devel \
    dbus-devel \
    bluez-libs-devel \
    sqlite-devel \
    xdotool-devel
```

#### Arch Linux
```bash
sudo pacman -S \
    base-devel \
    gtk4 \
    libadwaita \
    dbus \
    bluez \
    sqlite \
    xdotool
```

### Build Commands

```bash
# Debug build
cargo build

# Release build (optimized)
cargo build --release

# Run tests
cargo test

# Run with logging
RUST_LOG=debug cargo run --release
```

## Installation

### Option 1: Install Script (Recommended)

```bash
cd desktop
./scripts/install.sh --user    # Install to ~/.local/bin
# Or:
sudo ./scripts/install.sh --system  # Install to /usr/local/bin
```

### Option 2: Manual Installation

```bash
# Build release binary
cargo build --release

# Copy to user bin
mkdir -p ~/.local/bin
cp target/release/speech2prompt-desktop ~/.local/bin/

# Install systemd service
mkdir -p ~/.config/systemd/user
cp systemd/speech2prompt.service ~/.config/systemd/user/
systemctl --user enable speech2prompt
systemctl --user start speech2prompt
```

### Option 3: Package Installation

Download pre-built packages from the [Releases page](https://github.com/peliorg/speech2prompt/releases):

```bash
# Debian/Ubuntu (.deb)
sudo dpkg -i speech2prompt-desktop_0.4.1_amd64.deb

# Fedora/RHEL (.rpm)
sudo rpm -i speech2prompt-desktop-0.4.1.x86_64.rpm

# AppImage (universal)
chmod +x Speech2Prompt-0.4.1-x86_64.AppImage
./Speech2Prompt-0.4.1-x86_64.AppImage
```

## Configuration

Config file location: `~/.config/speech2prompt/config.toml`

```toml
[bluetooth]
device_name = "Speech2Prompt"
auto_accept = true

[input]
typing_delay_ms = 10
prefer_backend = "auto"  # "auto", "x11", or "wayland"

[history]
enabled = true
max_entries = 10000
database_path = "~/.local/share/speech2prompt/history.db"

[logging]
level = "info"  # "error", "warn", "info", "debug", "trace"
```

## Usage

### Starting the Application

```bash
# Run directly
speech2prompt-desktop

# Run with debug logging
RUST_LOG=debug speech2prompt-desktop

# Run as systemd service
systemctl --user start speech2prompt
systemctl --user status speech2prompt

# View logs
journalctl --user -u speech2prompt -f
```

### System Tray

The app runs in the system tray with the following options:
- **Show History** - View dictation history
- **Settings** - Configure preferences
- **About** - Version and license information
- **Quit** - Exit application

### Pairing

When an Android device attempts to connect for the first time:
1. A GTK dialog will appear asking to accept or reject the connection
2. Click "Accept" to complete pairing
3. The connection is secured via ECDH key exchange
4. Keys are securely stored for automatic reconnection

## System Requirements

### Linux Distribution
- Any modern Linux distribution with:
  - GTK4 (4.0+)
  - BlueZ (5.50+)
  - X11 or Wayland display server

### Hardware
- Bluetooth adapter with BLE support (Bluetooth 4.0+)
- Any CPU architecture supported by Rust (x86_64, aarch64, etc.)

### Display Server Support
- **X11**: Full support via xdotool/libxdo
- **Wayland**: Requires ydotool daemon

## Wayland Setup

If you're using Wayland, install and enable ydotool:

```bash
# Install ydotool
sudo apt install ydotool  # Debian/Ubuntu
sudo dnf install ydotool  # Fedora

# Enable and start daemon
systemctl --user enable ydotoold
systemctl --user start ydotoold

# Verify it's running
systemctl --user status ydotoold
```

## Troubleshooting

### Bluetooth Issues

```bash
# Check Bluetooth service
sudo systemctl status bluetooth

# Restart Bluetooth
sudo systemctl restart bluetooth

# Check adapter supports BLE
hciconfig -a | grep -i "le"

# List paired devices
bluetoothctl devices
```

### Permission Denied Errors

```bash
# Add user to bluetooth group
sudo usermod -aG bluetooth $USER

# Log out and back in for group changes to take effect
```

### Text Not Appearing

**For X11:**
```bash
# Install xdotool
sudo apt install xdotool libxdo-dev
```

**For Wayland:**
```bash
# Install and start ydotool
sudo apt install ydotool
systemctl --user enable --now ydotoold
```

### System Tray Icon Not Visible

Some desktop environments hide tray icons by default:

- **GNOME**: Install "AppIndicator Support" extension
- **KDE Plasma**: Tray icons work by default
- **XFCE**: Check Panel → Panel Preferences → Items

### Logs and Debugging

```bash
# View application logs
journalctl --user -u speech2prompt -n 100

# Run with debug logging
RUST_LOG=debug speech2prompt-desktop

# Check BLE GATT server
bluetoothctl
# In bluetoothctl:
# gatt-services
```

## Development

### Project Structure

```
desktop/
├── src/
│   ├── main.rs           # Entry point
│   ├── bluetooth/        # BLE GATT server
│   ├── commands/         # Voice command processor
│   ├── config/           # Configuration management
│   ├── crypto/           # AES-256-GCM, ECDH
│   ├── database/         # SQLite history
│   ├── input/            # X11/Wayland input injection
│   ├── protocol/         # Message protocol
│   ├── tray/             # System tray integration
│   └── ui/               # GTK4 dialogs
├── Cargo.toml
├── scripts/
│   └── install.sh
└── systemd/
    └── speech2prompt.service
```

### Running Tests

```bash
# Run all tests
cargo test

# Run with output
cargo test -- --nocapture

# Run specific test
cargo test test_name

# Run with coverage (requires cargo-tarpaulin)
cargo tarpaulin --out Html
```

### Code Style

The project follows Rust standard conventions:
- Run `cargo fmt` before committing
- Run `cargo clippy` to check for warnings
- Write documentation for public APIs
- Add tests for new functionality

### Building Packages

```bash
# Build .deb package (requires cargo-deb)
cargo install cargo-deb
cargo deb

# Build .rpm package (requires cargo-rpm)
cargo install cargo-generate-rpm
cargo build --release
cargo generate-rpm

# Build AppImage (requires appimage-builder)
# See .github/workflows/release.yml for details
```

## Performance

- **Startup Time**: < 1 second
- **Memory Usage**: ~30-50 MB
- **CPU Usage**: < 1% idle, ~5% during text injection
- **Latency**: ~50-200ms from Android transmission to text injection

## Security

- All communication encrypted with AES-256-GCM
- AES-256-GCM encryption with ECDH key exchange
- Keys stored securely in user's home directory
- No network communication (BLE only)
- No telemetry or data collection

## Contributing

When contributing to the desktop app:

1. Follow Rust style guidelines (`cargo fmt`, `cargo clippy`)
2. Write tests for new functionality
3. Update documentation
4. Keep dependencies minimal and well-maintained
5. Test on both X11 and Wayland

## License

Licensed under the Apache License 2.0. See [LICENSE](../LICENSE) file for details.

## Resources

- [Rust Documentation](https://doc.rust-lang.org/)
- [GTK4 Rust Bindings](https://gtk-rs.org/)
- [BlueZ Documentation](http://www.bluez.org/)
- [Tokio Async Runtime](https://tokio.rs/)
- [BlueR (Rust BLE Library)](https://github.com/bluez/bluer)
