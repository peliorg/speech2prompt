# Speech2Code Quick Start Guide

This guide will walk you through setting up and testing Speech2Code on your Linux desktop and Android device.

## Prerequisites Check

Before starting, verify you have everything installed:

```bash
# Check Flutter
flutter --version

# Check Rust
rustc --version

# Check Android SDK
echo $ANDROID_HOME

# Check system libraries
dpkg -l | grep -E "libgtk-4-dev|libadwaita-1-dev|libdbus-1-dev|libbluetooth-dev"
```

## Step 1: Build the Linux Desktop App

### Install Missing Dependencies

```bash
# Install libxdo-dev (required for X11 input injection)
sudo apt install libxdo-dev

# Optional: Install ydotool for Wayland support
sudo apt install ydotool
```

### Build the Desktop Application

```bash
cd ~/workspace/priv/speech2code/desktop
cargo build --release
```

This will take several minutes the first time as Cargo downloads and compiles dependencies.

### Install the Desktop Application

Choose one of these options:

**Option A: User Installation (Recommended for testing)**
```bash
./scripts/install.sh --user
```
This installs to `~/.local/bin/speech2code-desktop`

**Option B: System Installation**
```bash
sudo ./scripts/install.sh --system
```
This installs to `/usr/local/bin/speech2code-desktop`

**Option C: Run Without Installing**
```bash
cargo run --release
```

## Step 2: Start the Desktop App

### Start the Application

```bash
# If installed:
speech2code-desktop

# If running from source:
cd ~/workspace/priv/speech2code/desktop
cargo run --release
```

### What to Expect

- A system tray icon should appear (look for it in your system tray)
- The app will start a Bluetooth server and wait for connections
- Check the terminal output for any errors

### Enable Bluetooth

Make sure Bluetooth is enabled and visible:

```bash
# Check Bluetooth status
sudo systemctl status bluetooth

# If not running, start it
sudo systemctl start bluetooth

# Make your computer discoverable (optional, only if pairing fails)
bluetoothctl
# In bluetoothctl:
power on
discoverable on
pairable on
agent on
default-agent
```

## Step 3: Build the Android App

### Option A: Debug Build (Fast, for Testing)

```bash
cd ~/workspace/priv/speech2code/android
flutter pub get
flutter build apk --debug
```

The APK will be at: `android/build/app/outputs/flutter-apk/app-debug.apk`

### Option B: Release Build (Optimized)

```bash
cd ~/workspace/priv/speech2code/android

# Generate a keystore (one-time setup)
./scripts/generate-keystore.sh
# Follow the prompts to create a keystore

# Build release APK
./scripts/build-release.sh
```

The APK will be at: `android/build/app/outputs/flutter-apk/app-release.apk`

## Step 4: Install the Android App

### Transfer APK to Your Phone

**Option A: USB Transfer**
```bash
# Connect your phone via USB and enable File Transfer mode
# Copy the APK:
cp android/build/app/outputs/flutter-apk/app-debug.apk ~/Downloads/
# Then use your file manager to transfer to phone
```

**Option B: ADB Install (Fastest)**
```bash
# Enable USB Debugging on your Android phone:
# Settings > About Phone > Tap "Build Number" 7 times
# Settings > Developer Options > Enable "USB Debugging"

# Connect via USB and install:
cd ~/workspace/priv/speech2code/android
flutter install
```

**Option C: Web Transfer**
Upload to Google Drive, Dropbox, or email to yourself, then download on your phone.

### Install on Phone

1. On your Android phone, go to **Settings > Security**
2. Enable **Install from Unknown Sources** or **Install Unknown Apps** (varies by Android version)
3. Open the APK file in your file manager
4. Tap **Install**

## Step 5: Pair Your Devices

### On Your Phone

1. Open **Settings > Bluetooth**
2. Make sure Bluetooth is ON
3. Find your computer in the available devices list
4. Tap to pair
5. Note: Some phones may ask for a pairing code - accept it

### Verify Pairing

On Linux:
```bash
bluetoothctl devices
# You should see your phone listed
```

## Step 6: Connect and Test

### Grant Permissions on Android

1. Open the **Speech2Code** app
2. Grant **Microphone** permission when prompted
3. Grant **Bluetooth** and **Nearby Devices** permissions when prompted

### Connect to Your Desktop

1. In the Speech2Code app, tap the **Connect** button
2. You should see your computer in the device list
3. Tap your computer's name
4. **Enter the PIN** when prompted:
   - The desktop app will display a 6-digit PIN in the terminal
   - Enter this same PIN on your phone
   - Both devices must use the same PIN

### Start Dictating

1. After successful pairing, the app shows the home screen
2. Open any application on your Linux desktop (text editor, terminal, browser, etc.)
3. Click to position the cursor where you want text to appear
4. **Tap the microphone button** in the Speech2Code app
5. **Start speaking!**

The text should appear at your cursor position in real-time.

## Step 7: Testing Voice Commands

Try these voice commands while dictating:

| Say This | What Happens |
|----------|-------------|
| "new line" or "enter" | Inserts a new line (Enter key) |
| "select all" | Selects all text (Ctrl+A) |
| "copy that" | Copies selected text (Ctrl+C) |
| "paste" | Pastes clipboard (Ctrl+V) |
| "cut that" | Cuts selected text (Ctrl+X) |
| "cancel" | Discards the current text buffer |

You can also say:
- "stop listening" - Stops speech recognition
- "start listening" - Resumes speech recognition

## Troubleshooting

### Desktop App Won't Start

```bash
# Check for missing libraries
ldd ~/.local/bin/speech2code-desktop | grep "not found"

# Check Bluetooth service
sudo systemctl status bluetooth

# Check logs
journalctl --user -u speech2code -f
```

### Android App Won't Build

```bash
# Clean and rebuild
cd ~/workspace/priv/speech2code/android
flutter clean
flutter pub get
flutter build apk --debug
```

### Connection Issues

1. **Make sure Bluetooth is enabled** on both devices
2. **Check pairing** in system Bluetooth settings
3. **Restart both apps**
4. **Check desktop app logs** in the terminal
5. **Try unpairing and re-pairing** the devices

### Bluetooth Pairing Fails

```bash
# On Linux, remove old pairing:
bluetoothctl
remove <PHONE_MAC_ADDRESS>

# Then pair again from scratch
```

### Text Not Appearing on Desktop

1. **Check the desktop app is running** (look for system tray icon)
2. **Verify connection status** in Android app (should show "Connected")
3. **Test with a simple text editor first** (like gedit or kate)
4. **For Wayland users**: Make sure ydotool is installed and running
   ```bash
   sudo apt install ydotool
   systemctl --user enable ydotoold
   systemctl --user start ydotoold
   ```

### Speech Recognition Not Working

1. **Check microphone permissions** in Android Settings > Apps > Speech2Code
2. **Test microphone** in Android Settings > Sound to verify it works
3. **Check internet connection** - Android speech recognition may need internet initially
4. **Restart the app**

### Can't Find System Tray Icon

Some desktop environments hide system tray icons by default:

- **GNOME**: Install "AppIndicator Support" extension
- **KDE**: System tray icons work by default
- **XFCE**: Check Panel > Panel Preferences > Items

If you can't see the tray icon, you can still use the app - just control it via the terminal.

## Testing Features

### Test Speech Recognition

1. Open a text editor on your desktop
2. Tap the mic button in the app
3. Say: "Hello world this is a test"
4. Watch the text appear on your desktop

### Test Voice Commands

1. Dictate some text
2. Say "select all" - all text should be selected
3. Say "copy that" - text is copied
4. Say "new line" - cursor moves to next line
5. Say "paste" - text is pasted

### View History

1. Click the system tray icon on your desktop
2. Select "Show History"
3. Browse your dictation history

### Test Connection Stability

1. Leave the app connected for a few minutes
2. Lock/unlock your phone
3. Move away from and back to your computer
4. The app should maintain connection or auto-reconnect

## Performance Tips

1. **Keep your phone and computer close** (within 10 meters)
2. **Minimize Bluetooth interference** (turn off other Bluetooth devices)
3. **Use a good microphone** or speak clearly
4. **Dictate in a quiet environment** for best recognition

## Advanced Testing

### Test Encryption

Both devices encrypt all communication with AES-256-GCM. To verify:
```bash
# Check the desktop logs for encryption messages:
grep "Encrypted" ~/.local/share/speech2code/logs/*
```

### Test with Different Applications

Try dictating into:
- Text editors (gedit, kate, VS Code)
- Terminals
- Web browsers (Gmail, Google Docs, etc.)
- IDEs (IntelliJ, Eclipse)
- Any application with text input

### Monitor Bluetooth Connection

```bash
# Watch Bluetooth logs
journalctl -f | grep -i bluetooth

# Check RFCOMM connections
sudo rfcomm
```

## Next Steps

Once everything works:

1. **Enable autostart**: `systemctl --user enable speech2code`
2. **Configure settings** in Android app (Settings screen)
3. **Customize voice commands** (planned feature)
4. **Build release versions** for daily use

## Getting Help

If you encounter issues:

1. Check the terminal output for error messages
2. Review the logs: `~/.local/share/speech2code/logs/`
3. Check Android logcat: `adb logcat | grep Speech2Code`
4. Read the troubleshooting section above
5. Check the GitHub issues

## Success Criteria

You'll know it's working when:
- ✅ Desktop app starts without errors
- ✅ System tray icon appears
- ✅ Android app connects successfully
- ✅ Speaking into your phone makes text appear on your desktop
- ✅ Voice commands work correctly

Enjoy seamless voice-to-keyboard dictation!
