# Phase 10: Packaging & Deployment

## Overview

This phase covers creating release builds, packaging for distribution, installation scripts, and deployment documentation for both the Android and Linux applications.

## Prerequisites

- Phase 01-09 completed
- Both apps fully functional and tested
- Release signing keys prepared

---

## Task 1: Linux Desktop - Systemd Service

Create `desktop/resources/speech2code.service`:

```ini
[Unit]
Description=Speech2Code Desktop - Voice-to-keyboard bridge
Documentation=https://github.com/yourusername/speech2code
After=graphical-session.target bluetooth.target
Wants=bluetooth.target

[Service]
Type=simple
ExecStart=/usr/local/bin/speech2code-desktop
Restart=on-failure
RestartSec=5
Environment=DISPLAY=:0
Environment=XDG_RUNTIME_DIR=/run/user/%U

# Security hardening
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=read-only
ReadWritePaths=%h/.config/speech2code %h/.local/share/speech2code
PrivateTmp=yes

[Install]
WantedBy=default.target
```

---

## Task 2: Linux Desktop - XDG Autostart Entry

Create `desktop/resources/speech2code.desktop`:

```ini
[Desktop Entry]
Type=Application
Name=Speech2Code
Comment=Voice-to-keyboard bridge
Exec=/usr/local/bin/speech2code-desktop
Icon=speech2code
Terminal=false
Categories=Utility;Accessibility;
Keywords=voice;speech;dictation;bluetooth;
StartupNotify=false
X-GNOME-Autostart-enabled=true
X-GNOME-Autostart-Phase=Applications
```

---

## Task 3: Linux Desktop - AppImage Packaging

Create `desktop/appimage/AppImageBuilder.yml`:

```yaml
version: 1
AppDir:
  path: ./AppDir
  app_info:
    id: com.speech2code.desktop
    name: Speech2Code
    icon: speech2code
    version: !ENV ${VERSION}
    exec: usr/bin/speech2code-desktop
    exec_args: $@
  
  apt:
    arch: amd64
    sources:
      - sourceline: 'deb http://archive.ubuntu.com/ubuntu jammy main universe'
        key_url: 'https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x871920D1991BC93C'
    include:
      - libbluetooth3
      - libgtk-4-1
      - libadwaita-1-0
      - libsqlite3-0
    exclude: []
  
  files:
    include: []
    exclude:
      - usr/share/man
      - usr/share/doc
  
  runtime:
    env:
      APPDIR_LIBRARY_PATH: $APPDIR/usr/lib/x86_64-linux-gnu

AppImage:
  arch: x86_64
  comp: gzip
  update-information: gh-releases-zsync|yourusername|speech2code|latest|Speech2Code-*x86_64.AppImage.zsync
```

Create `desktop/appimage/build-appimage.sh`:

```bash
#!/bin/bash
set -e

VERSION=${1:-"0.1.0"}
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo "Building Speech2Code Desktop AppImage v${VERSION}"

# Build release binary
cd "$PROJECT_DIR"
cargo build --release

# Prepare AppDir
rm -rf "$SCRIPT_DIR/AppDir"
mkdir -p "$SCRIPT_DIR/AppDir/usr/bin"
mkdir -p "$SCRIPT_DIR/AppDir/usr/share/applications"
mkdir -p "$SCRIPT_DIR/AppDir/usr/share/icons/hicolor/256x256/apps"

# Copy binary
cp "$PROJECT_DIR/target/release/speech2code-desktop" "$SCRIPT_DIR/AppDir/usr/bin/"

# Copy desktop file
cp "$PROJECT_DIR/resources/speech2code.desktop" "$SCRIPT_DIR/AppDir/usr/share/applications/"

# Copy icon (create a placeholder if not exists)
if [ -f "$PROJECT_DIR/resources/icons/speech2code.png" ]; then
    cp "$PROJECT_DIR/resources/icons/speech2code.png" "$SCRIPT_DIR/AppDir/usr/share/icons/hicolor/256x256/apps/"
else
    echo "Warning: Icon not found, creating placeholder"
    convert -size 256x256 xc:blue -fill white -gravity center -pointsize 48 -annotate 0 "S2C" "$SCRIPT_DIR/AppDir/usr/share/icons/hicolor/256x256/apps/speech2code.png" 2>/dev/null || true
fi

# Symlink for AppImage
cd "$SCRIPT_DIR/AppDir"
ln -sf usr/share/applications/speech2code.desktop .
ln -sf usr/share/icons/hicolor/256x256/apps/speech2code.png .

# Build AppImage using appimagetool
cd "$SCRIPT_DIR"
if command -v appimagetool &> /dev/null; then
    ARCH=x86_64 VERSION=$VERSION appimagetool AppDir "Speech2Code-${VERSION}-x86_64.AppImage"
    echo "AppImage created: Speech2Code-${VERSION}-x86_64.AppImage"
else
    echo "appimagetool not found. Install it from:"
    echo "https://github.com/AppImage/AppImageKit/releases"
    exit 1
fi
```

---

## Task 4: Linux Desktop - Install Script

Create `desktop/scripts/install.sh`:

```bash
#!/bin/bash
set -e

# Speech2Code Desktop Installation Script
# Usage: ./install.sh [--user|--system]

INSTALL_MODE="${1:---user}"
VERSION="0.1.0"

echo "=========================================="
echo " Speech2Code Desktop Installer"
echo " Version: $VERSION"
echo "=========================================="
echo ""

# Check dependencies
check_dependency() {
    if ! command -v "$1" &> /dev/null; then
        echo "Error: $1 is required but not installed."
        echo "Install it with: $2"
        exit 1
    fi
}

check_dependency "bluetoothctl" "sudo apt install bluez"

# Check display server
if [ -n "$WAYLAND_DISPLAY" ]; then
    echo "Detected: Wayland"
    if ! command -v ydotoold &> /dev/null; then
        echo "Warning: ydotool not found. Install for Wayland support:"
        echo "  sudo apt install ydotool"
        echo "  systemctl --user enable ydotoold"
        echo ""
    fi
else
    echo "Detected: X11"
fi

# Determine installation paths
if [ "$INSTALL_MODE" = "--system" ]; then
    if [ "$EUID" -ne 0 ]; then
        echo "System installation requires root privileges."
        echo "Run with: sudo $0 --system"
        exit 1
    fi
    BIN_DIR="/usr/local/bin"
    DESKTOP_DIR="/usr/share/applications"
    ICON_DIR="/usr/share/icons/hicolor/256x256/apps"
    SYSTEMD_DIR="/etc/systemd/user"
else
    BIN_DIR="$HOME/.local/bin"
    DESKTOP_DIR="$HOME/.local/share/applications"
    ICON_DIR="$HOME/.local/share/icons/hicolor/256x256/apps"
    SYSTEMD_DIR="$HOME/.config/systemd/user"
    AUTOSTART_DIR="$HOME/.config/autostart"
fi

echo "Installation mode: $INSTALL_MODE"
echo "Binary directory: $BIN_DIR"
echo ""

# Create directories
mkdir -p "$BIN_DIR"
mkdir -p "$DESKTOP_DIR"
mkdir -p "$ICON_DIR"
mkdir -p "$SYSTEMD_DIR"
[ -n "$AUTOSTART_DIR" ] && mkdir -p "$AUTOSTART_DIR"

# Determine source directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# Build if needed
if [ ! -f "$PROJECT_DIR/target/release/speech2code-desktop" ]; then
    echo "Building release binary..."
    cd "$PROJECT_DIR"
    cargo build --release
fi

# Install binary
echo "Installing binary..."
cp "$PROJECT_DIR/target/release/speech2code-desktop" "$BIN_DIR/"
chmod +x "$BIN_DIR/speech2code-desktop"

# Install desktop file
echo "Installing desktop entry..."
sed "s|Exec=.*|Exec=$BIN_DIR/speech2code-desktop|g" \
    "$PROJECT_DIR/resources/speech2code.desktop" > "$DESKTOP_DIR/speech2code.desktop"

# Install icon
if [ -f "$PROJECT_DIR/resources/icons/speech2code.png" ]; then
    echo "Installing icon..."
    cp "$PROJECT_DIR/resources/icons/speech2code.png" "$ICON_DIR/"
fi

# Install systemd service
echo "Installing systemd service..."
sed "s|ExecStart=.*|ExecStart=$BIN_DIR/speech2code-desktop|g" \
    "$PROJECT_DIR/resources/speech2code.service" > "$SYSTEMD_DIR/speech2code.service"

# Install autostart entry (user mode only)
if [ -n "$AUTOSTART_DIR" ]; then
    echo "Installing autostart entry..."
    cp "$DESKTOP_DIR/speech2code.desktop" "$AUTOSTART_DIR/"
fi

# Update desktop database
if command -v update-desktop-database &> /dev/null; then
    update-desktop-database "$DESKTOP_DIR" 2>/dev/null || true
fi

# Reload systemd
systemctl --user daemon-reload 2>/dev/null || true

# Create config directory
mkdir -p "$HOME/.config/speech2code"
mkdir -p "$HOME/.local/share/speech2code"

echo ""
echo "=========================================="
echo " Installation Complete!"
echo "=========================================="
echo ""
echo "Next steps:"
echo ""
echo "1. Start the service:"
echo "   systemctl --user start speech2code"
echo ""
echo "2. Enable autostart:"
echo "   systemctl --user enable speech2code"
echo ""
echo "3. Or run manually:"
echo "   $BIN_DIR/speech2code-desktop"
echo ""
echo "4. Make sure Bluetooth is enabled:"
echo "   sudo systemctl start bluetooth"
echo ""
```

Create `desktop/scripts/uninstall.sh`:

```bash
#!/bin/bash
set -e

# Speech2Code Desktop Uninstallation Script

INSTALL_MODE="${1:---user}"

echo "=========================================="
echo " Speech2Code Desktop Uninstaller"
echo "=========================================="
echo ""

# Determine installation paths
if [ "$INSTALL_MODE" = "--system" ]; then
    if [ "$EUID" -ne 0 ]; then
        echo "System uninstallation requires root privileges."
        echo "Run with: sudo $0 --system"
        exit 1
    fi
    BIN_DIR="/usr/local/bin"
    DESKTOP_DIR="/usr/share/applications"
    ICON_DIR="/usr/share/icons/hicolor/256x256/apps"
    SYSTEMD_DIR="/etc/systemd/user"
else
    BIN_DIR="$HOME/.local/bin"
    DESKTOP_DIR="$HOME/.local/share/applications"
    ICON_DIR="$HOME/.local/share/icons/hicolor/256x256/apps"
    SYSTEMD_DIR="$HOME/.config/systemd/user"
    AUTOSTART_DIR="$HOME/.config/autostart"
fi

# Stop service
echo "Stopping service..."
systemctl --user stop speech2code 2>/dev/null || true
systemctl --user disable speech2code 2>/dev/null || true

# Remove files
echo "Removing files..."
rm -f "$BIN_DIR/speech2code-desktop"
rm -f "$DESKTOP_DIR/speech2code.desktop"
rm -f "$ICON_DIR/speech2code.png"
rm -f "$SYSTEMD_DIR/speech2code.service"
[ -n "$AUTOSTART_DIR" ] && rm -f "$AUTOSTART_DIR/speech2code.desktop"

# Reload systemd
systemctl --user daemon-reload 2>/dev/null || true

echo ""
echo "Uninstallation complete!"
echo ""
echo "Note: Configuration and data files were preserved in:"
echo "  ~/.config/speech2code/"
echo "  ~/.local/share/speech2code/"
echo ""
echo "To remove all data, run:"
echo "  rm -rf ~/.config/speech2code ~/.local/share/speech2code"
```

---

## Task 5: Android - Release Build Configuration

Update `android/android/app/build.gradle`:

```gradle
plugins {
    id "com.android.application"
    id "kotlin-android"
    id "dev.flutter.flutter-gradle-plugin"
}

def keystoreProperties = new Properties()
def keystorePropertiesFile = rootProject.file('key.properties')
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(new FileInputStream(keystorePropertiesFile))
}

android {
    namespace "com.speech2code.app"
    compileSdkVersion 34
    ndkVersion flutter.ndkVersion

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = '1.8'
    }

    sourceSets {
        main.java.srcDirs += 'src/main/kotlin'
    }

    defaultConfig {
        applicationId "com.speech2code.app"
        minSdkVersion 21
        targetSdkVersion 34
        versionCode flutterVersionCode.toInteger()
        versionName flutterVersionName
    }

    signingConfigs {
        release {
            if (keystorePropertiesFile.exists()) {
                keyAlias keystoreProperties['keyAlias']
                keyPassword keystoreProperties['keyPassword']
                storeFile file(keystoreProperties['storeFile'])
                storePassword keystoreProperties['storePassword']
            }
        }
    }

    buildTypes {
        release {
            signingConfig signingConfigs.release
            minifyEnabled true
            shrinkResources true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }
}

flutter {
    source '../..'
}

dependencies {}
```

Create `android/android/app/proguard-rules.pro`:

```proguard
# Flutter
-keep class io.flutter.app.** { *; }
-keep class io.flutter.plugin.** { *; }
-keep class io.flutter.util.** { *; }
-keep class io.flutter.view.** { *; }
-keep class io.flutter.** { *; }
-keep class io.flutter.plugins.** { *; }

# Bluetooth Serial
-keep class com.github.nickspo.flutter_bluetooth_serial.** { *; }

# Speech to Text
-keep class com.csdcorp.speech_to_text.** { *; }

# Secure Storage
-keep class com.it_nomads.fluttersecurestorage.** { *; }

# Crypto libraries
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# General Android
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception
```

---

## Task 6: Android - Key Generation Script

Create `android/scripts/generate-keystore.sh`:

```bash
#!/bin/bash

# Generate release keystore for Speech2Code Android app

KEYSTORE_DIR="$HOME/.android/keystores"
KEYSTORE_FILE="$KEYSTORE_DIR/speech2code.jks"
KEY_ALIAS="speech2code"

echo "=========================================="
echo " Speech2Code Keystore Generator"
echo "=========================================="
echo ""

if [ -f "$KEYSTORE_FILE" ]; then
    echo "Keystore already exists at: $KEYSTORE_FILE"
    read -p "Overwrite? (y/N): " confirm
    if [ "$confirm" != "y" ] && [ "$confirm" != "Y" ]; then
        echo "Aborted."
        exit 0
    fi
fi

mkdir -p "$KEYSTORE_DIR"

echo ""
echo "Enter keystore information:"
echo ""

read -p "Organization Name: " ORG_NAME
read -p "City/Locality: " CITY
read -p "State/Province: " STATE
read -p "Country Code (2 letters): " COUNTRY

echo ""
echo "Enter passwords (minimum 6 characters):"
read -sp "Keystore Password: " STORE_PASS
echo ""
read -sp "Key Password: " KEY_PASS
echo ""

# Generate keystore
keytool -genkey -v \
    -keystore "$KEYSTORE_FILE" \
    -alias "$KEY_ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -storepass "$STORE_PASS" \
    -keypass "$KEY_PASS" \
    -dname "CN=Speech2Code, O=$ORG_NAME, L=$CITY, ST=$STATE, C=$COUNTRY"

echo ""
echo "Keystore created at: $KEYSTORE_FILE"
echo ""

# Create key.properties
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
KEY_PROPS="$PROJECT_DIR/android/key.properties"

cat > "$KEY_PROPS" << EOF
storePassword=$STORE_PASS
keyPassword=$KEY_PASS
keyAlias=$KEY_ALIAS
storeFile=$KEYSTORE_FILE
EOF

echo "key.properties created at: $KEY_PROPS"
echo ""
echo "IMPORTANT: Add these files to your backup:"
echo "  - $KEYSTORE_FILE"
echo "  - $KEY_PROPS"
echo ""
echo "DO NOT commit key.properties to git!"
```

---

## Task 7: Android - Build Release APK

Create `android/scripts/build-release.sh`:

```bash
#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo "=========================================="
echo " Speech2Code Android Release Build"
echo "=========================================="
echo ""

cd "$PROJECT_DIR"

# Check for key.properties
if [ ! -f "android/key.properties" ]; then
    echo "Error: android/key.properties not found"
    echo "Run ./scripts/generate-keystore.sh first"
    exit 1
fi

# Clean previous builds
echo "Cleaning previous builds..."
flutter clean

# Get dependencies
echo "Getting dependencies..."
flutter pub get

# Run tests
echo "Running tests..."
flutter test || {
    echo "Tests failed! Continue anyway? (y/N)"
    read confirm
    if [ "$confirm" != "y" ]; then
        exit 1
    fi
}

# Build release APK
echo ""
echo "Building release APK..."
flutter build apk --release

# Build App Bundle (for Play Store)
echo ""
echo "Building App Bundle..."
flutter build appbundle --release

echo ""
echo "=========================================="
echo " Build Complete!"
echo "=========================================="
echo ""
echo "APK: $PROJECT_DIR/build/app/outputs/flutter-apk/app-release.apk"
echo "AAB: $PROJECT_DIR/build/app/outputs/bundle/release/app-release.aab"
echo ""

# Show APK info
if command -v aapt &> /dev/null; then
    echo "APK Info:"
    aapt dump badging "$PROJECT_DIR/build/app/outputs/flutter-apk/app-release.apk" | grep -E "package:|versionCode|versionName"
fi
```

---

## Task 8: Create Main README

Create/Update `README.md` at project root:

```markdown
# Speech2Code

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

MIT License - see LICENSE file

## Contributing

Contributions welcome! Please read CONTRIBUTING.md first.
```

---

## Task 9: Create Release Checklist

Create `RELEASE.md`:

```markdown
# Release Checklist

## Pre-Release

- [ ] All tests passing
  ```bash
  cd android && flutter test
  cd desktop && cargo test
  ```
- [ ] Version numbers updated
  - [ ] `android/pubspec.yaml` (version)
  - [ ] `desktop/Cargo.toml` (version)
  - [ ] `README.md`
- [ ] CHANGELOG.md updated
- [ ] Documentation reviewed

## Linux Desktop Release

- [ ] Build release binary
  ```bash
  cd desktop
  cargo build --release
  ```
- [ ] Test installation script
  ```bash
  ./scripts/install.sh
  ```
- [ ] Build AppImage
  ```bash
  cd appimage
  VERSION=x.y.z ./build-appimage.sh
  ```
- [ ] Test AppImage on clean system
- [ ] Verify systemd service starts
- [ ] Verify autostart works

## Android Release

- [ ] Keystore available
- [ ] Build release APK
  ```bash
  cd android
  ./scripts/build-release.sh
  ```
- [ ] Test APK on physical device
- [ ] Test fresh install (uninstall first)
- [ ] Verify permissions work
- [ ] Test connection to desktop
- [ ] Test speech recognition

## Integration Testing

- [ ] Fresh pairing flow works
- [ ] Reconnection with saved credentials works
- [ ] Text transmission verified
- [ ] Voice commands work
- [ ] History is recorded

## Release

- [ ] Create git tag
  ```bash
  git tag -a vX.Y.Z -m "Release vX.Y.Z"
  git push origin vX.Y.Z
  ```
- [ ] Create GitHub release
- [ ] Upload artifacts:
  - [ ] `Speech2Code-X.Y.Z-x86_64.AppImage`
  - [ ] `speech2code-X.Y.Z.apk`
- [ ] Update release notes

## Post-Release

- [ ] Announce release
- [ ] Monitor for issues
- [ ] Update version numbers for next development cycle
```

---

## Task 10: Create Icon Assets

Create `desktop/scripts/generate-icons.sh`:

```bash
#!/bin/bash

# Generate icon assets for Speech2Code

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
ICON_DIR="$PROJECT_DIR/resources/icons"

mkdir -p "$ICON_DIR"

# Check for ImageMagick
if ! command -v convert &> /dev/null; then
    echo "ImageMagick is required. Install with: sudo apt install imagemagick"
    exit 1
fi

# Generate simple icon (blue circle with microphone symbol)
# In production, replace with actual icon design

echo "Generating placeholder icons..."

# Create SVG source
cat > "$ICON_DIR/speech2code.svg" << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<svg width="256" height="256" viewBox="0 0 256 256" xmlns="http://www.w3.org/2000/svg">
  <defs>
    <linearGradient id="bg" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" style="stop-color:#4A90D9"/>
      <stop offset="100%" style="stop-color:#2E5B8B"/>
    </linearGradient>
  </defs>
  <circle cx="128" cy="128" r="120" fill="url(#bg)"/>
  <!-- Microphone body -->
  <rect x="104" y="60" width="48" height="80" rx="24" fill="white"/>
  <!-- Microphone stand arc -->
  <path d="M 80 140 Q 80 180 128 180 Q 176 180 176 140" 
        stroke="white" stroke-width="12" fill="none" stroke-linecap="round"/>
  <!-- Stand -->
  <line x1="128" y1="180" x2="128" y2="210" stroke="white" stroke-width="12" stroke-linecap="round"/>
  <line x1="100" y1="210" x2="156" y2="210" stroke="white" stroke-width="12" stroke-linecap="round"/>
</svg>
EOF

# Generate PNG sizes
for size in 16 32 48 64 128 256 512; do
    convert -background none "$ICON_DIR/speech2code.svg" -resize ${size}x${size} "$ICON_DIR/speech2code-${size}.png"
done

# Create standard icon
cp "$ICON_DIR/speech2code-256.png" "$ICON_DIR/speech2code.png"

# Create ICO for potential Windows support
convert "$ICON_DIR/speech2code-16.png" "$ICON_DIR/speech2code-32.png" "$ICON_DIR/speech2code-48.png" "$ICON_DIR/speech2code.ico" 2>/dev/null || true

echo "Icons generated in: $ICON_DIR"
ls -la "$ICON_DIR"
```

---

## Verification Checklist

- [ ] Systemd service file created and works
- [ ] XDG desktop entry created
- [ ] AppImage builds successfully
- [ ] Install script works on clean system
- [ ] Uninstall script removes all files
- [ ] Android release APK builds
- [ ] Android APK installs and runs
- [ ] ProGuard rules don't break functionality
- [ ] Icons display correctly
- [ ] README is complete and accurate

## Output Artifacts

After completing this phase:

1. **Linux Distribution**
   - `desktop/resources/speech2code.service` - Systemd user service
   - `desktop/resources/speech2code.desktop` - Desktop entry
   - `desktop/scripts/install.sh` - Installation script
   - `desktop/scripts/uninstall.sh` - Uninstallation script
   - `desktop/appimage/build-appimage.sh` - AppImage builder
   - `Speech2Code-X.Y.Z-x86_64.AppImage` - Portable AppImage

2. **Android Distribution**
   - `android/android/app/build.gradle` - Release configuration
   - `android/android/app/proguard-rules.pro` - ProGuard rules
   - `android/scripts/generate-keystore.sh` - Key generator
   - `android/scripts/build-release.sh` - Release build script
   - `app-release.apk` - Signed release APK

3. **Documentation**
   - `README.md` - Project overview and usage
   - `RELEASE.md` - Release checklist

## Next Steps

With Phase 10 complete, Speech2Code is ready for:

1. **Alpha Testing**: Install on target systems and test real-world usage
2. **Bug Fixing**: Address issues found during testing
3. **Beta Release**: Distribute to early adopters
4. **Production Release**: Publish to GitHub Releases

---

## Appendix: Complete File Structure

```
speech2code/
├── README.md
├── RELEASE.md
├── LICENSE
├── .gitignore
│
├── android/                          # Flutter Android app
│   ├── lib/
│   ├── android/
│   │   ├── app/
│   │   │   ├── build.gradle
│   │   │   ├── proguard-rules.pro
│   │   │   └── src/
│   │   └── key.properties           # (gitignored)
│   ├── scripts/
│   │   ├── generate-keystore.sh
│   │   └── build-release.sh
│   └── pubspec.yaml
│
├── desktop/                          # Rust Linux app
│   ├── src/
│   ├── resources/
│   │   ├── speech2code.service
│   │   ├── speech2code.desktop
│   │   └── icons/
│   │       ├── speech2code.svg
│   │       └── speech2code.png
│   ├── scripts/
│   │   ├── install.sh
│   │   ├── uninstall.sh
│   │   └── generate-icons.sh
│   ├── appimage/
│   │   ├── AppImageBuilder.yml
│   │   └── build-appimage.sh
│   └── Cargo.toml
│
├── protocol/
│   └── PROTOCOL.md
│
├── scripts/
│   └── test_e2e.sh
│
└── docs/
    └── phases/
        ├── 01-project-setup.md
        ├── 02-protocol-implementation.md
        ├── ...
        └── 10-packaging-deployment.md
```
