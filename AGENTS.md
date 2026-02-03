# AGENTS.md - Speech2Prompt Developer Guide

## Build & Compile

### Desktop (Linux - Rust)
```bash
cd desktop
cargo build --release    # Release build
cargo build              # Debug build
```
Binary: `desktop/target/release/speech2prompt-desktop`

### Android (Kotlin)
```bash
cd android/android
./gradlew assembleDebug     # Debug APK
./gradlew assembleRelease   # Release APK
```
APK: `android/android/app/build/outputs/apk/debug/app-debug.apk`

## Run with Debug Script

```bash
./run-speech2prompt.sh
```

This script:
1. Clears old logs
2. Kills existing desktop instances
3. Builds/starts desktop app with `RUST_LOG=debug`
4. Installs APK on connected Android device
5. Launches Android app and streams logs

**Requires**: Android device connected via ADB

## Log Locations

| Component | Log File |
|-----------|----------|
| Desktop   | `/tmp/speech2prompt-desktop.log` |
| Android   | `/tmp/speech2prompt-android.log` |

### Quick Log Commands
```bash
tail -f /tmp/speech2prompt-desktop.log    # Follow desktop logs
tail -f /tmp/speech2prompt-android.log    # Follow Android logs
grep -i error /tmp/speech2prompt-*.log    # Find errors
```
