# Complete Verification Checklist

## Phase 1: Project Setup
- [ ] `./gradlew clean` succeeds
- [ ] `./gradlew assembleDebug` succeeds
- [ ] App launches showing empty Compose screen
- [ ] No Flutter references remain
- [ ] Hilt Application compiles

## Phase 2: Data Models
- [ ] Unit tests for CommandParser.parse()
- [ ] Unit tests for CommandParser.processText()
- [ ] Unit tests for Message.toJson() / fromJson()
- [ ] All models compile without errors

## Phase 3: Cryptography
- [ ] deriveKey produces same output as Flutter
- [ ] encrypt/decrypt round-trip works
- [ ] Checksum format correct (8 hex chars)
- [ ] signAndEncrypt + verifyAndDecrypt round-trip
- [ ] SecureStorage persists data

## Phase 4: BLE Packets
- [ ] Chunk and reassemble large messages
- [ ] Handle single-packet messages
- [ ] MTU boundary cases
- [ ] Sequence number wrapping

## Phase 5: BLE Service
- [ ] Scan discovers devices
- [ ] Connect and discover services
- [ ] MTU negotiation works
- [ ] Send message, receive ACK
- [ ] Pairing flow with PIN
- [ ] Reconnection on disconnect
- [ ] Heartbeat keeps connection alive

## Phase 6: Speech Service
- [ ] Initialize successfully
- [ ] Continuous listening with auto-restart
- [ ] Partial results in real-time
- [ ] Voice commands detected
- [ ] Sound level updates
- [ ] Error recovery with backoff
- [ ] Watchdog detects stuck states

## Phase 7: Permissions
- [ ] Microphone permission flow
- [ ] Bluetooth permission flow
- [ ] Rationale when denied once
- [ ] Settings link when permanently denied

## Phase 8: Theme
- [ ] Dark theme applies
- [ ] Colors match design
- [ ] Status bar styled correctly

## Phase 9: UI Components
- [ ] AudioVisualizer pulses
- [ ] WaveformVisualizer responds
- [ ] ConnectionBadge states correct
- [ ] TranscriptionDisplay animates

## Phase 10: Home Screen
- [ ] Connection badge works
- [ ] Visualizer toggles listening
- [ ] Status text updates
- [ ] Transcription updates

## Phase 11: Connection Screen
- [ ] Permission request
- [ ] Scanning works
- [ ] Device list sorted
- [ ] Connection flow
- [ ] Pairing dialog

## Phase 12: Settings Screen
- [ ] Settings persist
- [ ] Language selection works
- [ ] Command toggles work
- [ ] Paired devices listed

## Phase 13: Navigation & DI
- [ ] All navigation works
- [ ] Back button correct
- [ ] DI no errors
- [ ] Singletons correct

## Phase 14: Test Screens
- [ ] Speech test functional
- [ ] Bluetooth test functional

## Phase 15: Integration
- [ ] Full end-to-end flow
- [ ] All edge cases handled
- [ ] Performance acceptable
- [ ] No crashes

## Final Sign-Off
- [ ] All functionality matches Flutter app
- [ ] No regressions
- [ ] Ready for production

---

## Detailed Test Scenarios

### Speech Recognition Tests

| Test Case | Steps | Expected Result |
|-----------|-------|-----------------|
| Basic dictation | Say "hello world" | Text appears in transcription |
| Continuous speech | Speak for 30+ seconds | Auto-restart, no gaps |
| Voice command | Say "new line" | ENTER command sent |
| Cancel command | Say "cancel" | Buffer cleared |
| Stop/Start | Say "stop listening" then "start listening" | Pauses and resumes |
| Background/foreground | Switch apps | Pauses, resumes on return |
| No speech | Stay silent for 30s | Remains listening, no crash |
| Rapid speech | Speak very quickly | Captures most words |

### Bluetooth Tests

| Test Case | Steps | Expected Result |
|-----------|-------|-----------------|
| Scan | Enable BT, start scan | Devices appear in list |
| Connect | Tap device | Connection established |
| Pair | Enter PIN when prompted | Pairing successful |
| Send text | Dictate text | Text received on desktop |
| Send command | Say voice command | Command received on desktop |
| Disconnect | Turn off desktop app | Shows disconnected state |
| Reconnect | Turn on desktop app | Auto-reconnects |
| Manual reconnect | Tap "Connect" button | Reconnects successfully |
| Multiple devices | Connect to different device | Switches cleanly |

### Permission Tests

| Test Case | Steps | Expected Result |
|-----------|-------|-----------------|
| First launch | Fresh install, open app | Permission dialogs appear |
| Grant all | Allow all permissions | App functions normally |
| Deny microphone | Deny mic permission | Shows rationale, retry option |
| Deny BT | Deny Bluetooth permission | Shows rationale, retry option |
| Permanent deny | "Don't ask again" + deny | Shows settings link |
| Revoke in settings | Remove permission in settings | App detects, requests again |

### Edge Case Tests

| Test Case | Steps | Expected Result |
|-----------|-------|-----------------|
| BT off during use | Turn off Bluetooth | Shows error, no crash |
| BT on again | Turn Bluetooth back on | Can reconnect |
| Low battery | Use until low battery warning | Graceful degradation |
| Airplane mode | Enable airplane mode | Shows appropriate error |
| Screen rotation | Rotate during use | State preserved |
| Process death | Force kill app | Restores state on relaunch |
| Memory pressure | Open many other apps | Handles gracefully |

---

## Performance Benchmarks

### Target Metrics

| Metric | Target | Measurement Method |
|--------|--------|-------------------|
| Cold start | < 2s | Stopwatch from tap to ready |
| Speech latency | < 500ms | Time from speech end to text |
| BLE latency | < 200ms | Time from send to ACK |
| Battery drain | < 5%/hour | 1 hour continuous use |
| Memory usage | < 100MB | Android Studio profiler |
| APK size | < 20MB | Build output |

### Profiling Checklist

- [ ] No memory leaks (LeakCanary clean)
- [ ] No ANRs in testing
- [ ] No jank in animations (< 16ms frames)
- [ ] CPU usage reasonable during listening
- [ ] Battery impact acceptable

---

## Regression Tests

When making changes, verify:

1. **Core functionality still works**
   - [ ] Speech recognition
   - [ ] BLE communication
   - [ ] Voice commands

2. **No new crashes**
   - [ ] All screens accessible
   - [ ] All buttons functional
   - [ ] All flows complete

3. **Performance not degraded**
   - [ ] Startup time
   - [ ] Responsiveness
   - [ ] Battery usage

---

## Sign-Off Template

```
## Release Sign-Off

Version: _______________
Date: _______________
Tester: _______________

### Test Summary
- Phases 1-15: [ ] PASS / [ ] FAIL
- Edge cases: [ ] PASS / [ ] FAIL
- Performance: [ ] PASS / [ ] FAIL
- Regression: [ ] PASS / [ ] FAIL

### Issues Found
1. _______________
2. _______________

### Notes
_______________

### Approval
[ ] Ready for release
[ ] Needs fixes (see issues)

Signed: _______________
```
