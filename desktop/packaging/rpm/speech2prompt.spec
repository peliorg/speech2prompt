Name:           speech2prompt
Version:        VERSION
Release:        1%{?dist}
Summary:        Voice-to-keyboard bridge for seamless dictation

License:        Apache-2.0
URL:            https://github.com/peliorg/speech2prompt
Source0:        %{name}-%{version}.tar.gz

# BuildRequires are not needed since we're packaging a pre-built binary
# created on Ubuntu in GitHub Actions. The --nodeps flag is used during
# rpmbuild to skip dependency checking. If building from source on
# Fedora/RHEL, uncomment these:
# BuildRequires:  gtk4-devel
# BuildRequires:  libadwaita-devel
# BuildRequires:  bluez-libs-devel
# BuildRequires:  dbus-devel
# BuildRequires:  sqlite-devel
# BuildRequires:  pkgconfig
# BuildRequires:  systemd-rpm-macros

Requires:       gtk4
Requires:       libadwaita
Requires:       bluez
Requires:       dbus-libs
Requires:       sqlite-libs

Recommends:     ydotool

%description
Speech2Prompt is a voice-to-keyboard bridge application that allows
Android devices to transmit speech-to-text to a Linux desktop via
Bluetooth for seamless dictation and voice commands.

Features:
- Real-time speech-to-text transmission via Bluetooth
- Encrypted communication with AES-256-GCM
- Voice commands for navigation and editing
- System tray integration
- History tracking with SQLite
- Support for both X11 and Wayland

%prep
%setup -q

%build
# Binary is pre-built in GitHub Actions
echo "Using pre-built binary"

%install
rm -rf %{buildroot}

# Create directories
install -d %{buildroot}%{_bindir}
install -d %{buildroot}%{_datadir}/applications
install -d %{buildroot}%{_datadir}/icons/hicolor/256x256/apps
install -d %{buildroot}%{_prefix}/lib/systemd/user

# Install binary
install -m 755 speech2prompt-desktop %{buildroot}%{_bindir}/speech2prompt-desktop

# Install desktop file
install -m 644 speech2prompt.desktop %{buildroot}%{_datadir}/applications/

# Install systemd service
install -m 644 speech2prompt.service %{buildroot}%{_prefix}/lib/systemd/user/

%files
%{_bindir}/speech2prompt-desktop
%{_datadir}/applications/speech2prompt.desktop
%{_prefix}/lib/systemd/user/speech2prompt.service

%post
# Update desktop database
if [ $1 -eq 1 ] ; then
    /usr/bin/update-desktop-database &> /dev/null || :
fi

# Update icon cache
if [ -x /usr/bin/gtk-update-icon-cache ]; then
    /usr/bin/gtk-update-icon-cache -f -t %{_datadir}/icons/hicolor &> /dev/null || :
fi

cat <<EOF

==========================================
 Speech2Prompt Desktop installed!
==========================================

To start Speech2Prompt:
  systemctl --user start speech2prompt

To enable autostart:
  systemctl --user enable speech2prompt

Or run from application menu or command line:
  speech2prompt-desktop

Make sure Bluetooth is enabled:
  sudo systemctl enable bluetooth
  sudo systemctl start bluetooth

EOF

%preun
# Stop and disable service before removal
if [ $1 -eq 0 ] ; then
    systemctl --user stop speech2prompt &> /dev/null || :
    systemctl --user disable speech2prompt &> /dev/null || :
fi

%postun
# Update desktop database
if [ $1 -eq 0 ] ; then
    /usr/bin/update-desktop-database &> /dev/null || :
fi

# Update icon cache
if [ $1 -eq 0 ] && [ -x /usr/bin/gtk-update-icon-cache ]; then
    /usr/bin/gtk-update-icon-cache -f -t %{_datadir}/icons/hicolor &> /dev/null || :
fi

%changelog
* Fri Jan 31 2026 Daniel Pelikan <daniel@example.com> - 0.2.0-1
- BREAKING: Migrated from Bluetooth Classic (RFCOMM) to BLE
- Improved connection speed and power efficiency
- Better Android 12+ compatibility
