#!/bin/bash
set -e

# Speech2Prompt Desktop Uninstallation Script

INSTALL_MODE="${1:---user}"

echo "=========================================="
echo " Speech2Prompt Desktop Uninstaller"
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
systemctl --user stop speech2prompt 2>/dev/null || true
systemctl --user disable speech2prompt 2>/dev/null || true

# Remove files
echo "Removing files..."
rm -f "$BIN_DIR/speech2prompt-desktop"
rm -f "$DESKTOP_DIR/speech2prompt.desktop"
rm -f "$ICON_DIR/speech2prompt.png"
rm -f "$SYSTEMD_DIR/speech2prompt.service"
[ -n "$AUTOSTART_DIR" ] && rm -f "$AUTOSTART_DIR/speech2prompt.desktop"

# Reload systemd
systemctl --user daemon-reload 2>/dev/null || true

echo ""
echo "Uninstallation complete!"
echo ""
echo "Note: Configuration and data files were preserved in:"
echo "  ~/.config/speech2prompt/"
echo "  ~/.local/share/speech2prompt/"
echo ""
echo "To remove all data, run:"
echo "  rm -rf ~/.config/speech2prompt ~/.local/share/speech2prompt"
