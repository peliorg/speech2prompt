#!/bin/bash
set -e

VERSION=${1:-"0.1.0"}
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo "Building Speech2Prompt Desktop AppImage v${VERSION}"

# Build release binary
cd "$PROJECT_DIR"
cargo build --release

# Prepare AppDir
rm -rf "$SCRIPT_DIR/AppDir"
mkdir -p "$SCRIPT_DIR/AppDir/usr/bin"
mkdir -p "$SCRIPT_DIR/AppDir/usr/share/applications"
mkdir -p "$SCRIPT_DIR/AppDir/usr/share/icons/hicolor/256x256/apps"

# Copy binary
cp "$PROJECT_DIR/target/release/speech2prompt-desktop" "$SCRIPT_DIR/AppDir/usr/bin/"

# Copy desktop file
cp "$PROJECT_DIR/resources/speech2prompt.desktop" "$SCRIPT_DIR/AppDir/usr/share/applications/"

# Copy icon (create a placeholder if not exists)
if [ -f "$PROJECT_DIR/resources/icons/speech2prompt.png" ]; then
    cp "$PROJECT_DIR/resources/icons/speech2prompt.png" "$SCRIPT_DIR/AppDir/usr/share/icons/hicolor/256x256/apps/"
else
    echo "Warning: Icon not found, creating placeholder"
    convert -size 256x256 xc:blue -fill white -gravity center -pointsize 48 -annotate 0 "S2C" "$SCRIPT_DIR/AppDir/usr/share/icons/hicolor/256x256/apps/speech2prompt.png" 2>/dev/null || true
fi

# Symlink for AppImage
cd "$SCRIPT_DIR/AppDir"
ln -sf usr/share/applications/speech2prompt.desktop .
ln -sf usr/share/icons/hicolor/256x256/apps/speech2prompt.png .

# Build AppImage using appimagetool
cd "$SCRIPT_DIR"
if command -v appimagetool &> /dev/null; then
    ARCH=x86_64 VERSION=$VERSION appimagetool AppDir "Speech2Prompt-${VERSION}-x86_64.AppImage"
    echo "AppImage created: Speech2Prompt-${VERSION}-x86_64.AppImage"
else
    echo "appimagetool not found. Install it from:"
    echo "https://github.com/AppImage/AppImageKit/releases"
    exit 1
fi
