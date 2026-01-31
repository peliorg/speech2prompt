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
