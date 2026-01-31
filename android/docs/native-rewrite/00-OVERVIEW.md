# Phase 00: Overview & Application Analysis

> Comprehensive analysis of the Speech2Prompt Flutter application and architectural decisions for the native Android rewrite.

---

## Table of Contents

- [Application Analysis](#application-analysis)
- [Current Flutter Implementation](#current-flutter-implementation)
- [Features to Migrate](#features-to-migrate)
- [Architecture Decisions](#architecture-decisions)
- [Risk Assessment](#risk-assessment)

---

## Application Analysis

### What is Speech2Prompt?

Speech2Prompt is a **voice-to-keyboard bridge** application that:

1. **Captures speech** on an Android device using continuous speech recognition
2. **Processes voice input** to detect text and special commands
3. **Transmits text securely** to a Linux desktop via Bluetooth Low Energy (BLE)
4. **Injects keystrokes** on the desktop, enabling hands-free typing in any application

### Core Use Case

```
┌──────────────┐         BLE          ┌──────────────┐
│   Android    │ ───────────────────► │    Linux     │
│    Phone     │   Encrypted Text     │   Desktop    │
│              │   & Commands         │              │
│  [Speech]    │                      │  [Keyboard]  │
└──────────────┘                      └──────────────┘
         │                                    │
         ▼                                    ▼
   User speaks                         Text appears in
   into phone                          active application
```

### Key Characteristics

| Aspect | Description |
|--------|-------------|
| **Real-time** | Low-latency speech-to-text transmission |
| **Secure** | AES-256-GCM encryption with PIN-based pairing |
| **Reliable** | Auto-reconnection, error recovery, packet chunking |
| **Hands-free** | Voice commands for common keyboard operations |
| **Continuous** | Persistent listening with automatic restart |

---

## Current Flutter Implementation

### Speech Recognition System

The Flutter app implements a robust speech recognition system with:

#### Continuous Listening
- Speech recognition runs indefinitely while the app is active
- Automatically restarts after natural pauses in speech
- Handles silence timeouts gracefully

#### Auto-Restart Mechanism
```
┌─────────────┐     speech ends      ┌─────────────┐
│  Listening  │ ──────────────────► │   Paused    │
└─────────────┘                      └──────┬──────┘
       ▲                                    │
       │         auto-restart delay         │
       └────────────────────────────────────┘
```

#### Error Recovery
- Handles `error_no_match` (silence) - restarts listening
- Handles `error_speech_timeout` - restarts with backoff
- Handles `error_network` - queues for retry when online
- Handles `error_audio` - requests microphone restart

#### Partial Results
- Streams partial transcription results for real-time UI feedback
- Final results trigger text transmission

### BLE Communication

#### Connection Management
- Scans for devices advertising the Speech2Prompt service UUID
- Maintains persistent connection with auto-reconnect
- Handles connection state transitions cleanly

#### MTU Negotiation
```kotlin
// Negotiation flow
1. Request maximum MTU (517 bytes)
2. Receive negotiated MTU from peripheral
3. Calculate payload size = MTU - 3 (ATT overhead)
4. Use payload size for packet chunking
```

#### Packet Chunking Protocol
Large messages are split into chunks with a simple protocol:

```
┌─────────────────────────────────────────────────┐
│ Packet Header (1 byte)                          │
├─────────────────────────────────────────────────┤
│ Bit 7: HAS_MORE (1 = more packets follow)       │
│ Bits 0-6: Reserved                              │
├─────────────────────────────────────────────────┤
│ Payload (up to MTU - 4 bytes)                   │
└─────────────────────────────────────────────────┘
```

**Transmission Example:**
```
Message: "Hello, World!" (encrypted = 64 bytes)
MTU: 23 bytes (minimum)
Payload size: 20 bytes

Packet 1: [0x80] + bytes[0..19]   (HAS_MORE = 1)
Packet 2: [0x80] + bytes[20..39]  (HAS_MORE = 1)
Packet 3: [0x00] + bytes[40..63]  (HAS_MORE = 0, final)
```

### Encryption Layer

#### Algorithm: AES-256-GCM
- **Key Size:** 256 bits
- **IV/Nonce:** 12 bytes, randomly generated per message
- **Auth Tag:** 16 bytes (128 bits)
- **Mode:** Galois/Counter Mode (authenticated encryption)

#### Key Derivation: PBKDF2
```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  User PIN   │ ──► │   PBKDF2    │ ──► │  256-bit    │
│  (6 digits) │     │  + Salt     │     │    Key      │
└─────────────┘     └─────────────┘     └─────────────┘

Parameters:
- Algorithm: PBKDF2-HMAC-SHA256
- Iterations: 100,000
- Salt: Device-specific, stored securely
- Output: 32 bytes (256 bits)
```

#### Encrypted Message Format
```
┌──────────────────────────────────────────────────┐
│ IV (12 bytes) │ Ciphertext (N bytes) │ Tag (16)  │
└──────────────────────────────────────────────────┘
```

### Voice Commands

The app recognizes special voice commands that map to keyboard operations:

| Voice Command | Variants | Action |
|---------------|----------|--------|
| **ENTER** | "enter", "new line", "return" | Sends Enter key |
| **SELECT_ALL** | "select all", "highlight all" | Sends Ctrl+A |
| **COPY** | "copy", "copy that" | Sends Ctrl+C |
| **PASTE** | "paste", "paste that" | Sends Ctrl+V |
| **CUT** | "cut", "cut that" | Sends Ctrl+X |
| **CANCEL** | "cancel", "undo", "never mind" | Cancels current input |

#### Command Detection Logic
```
1. Receive transcription text
2. Normalize: lowercase, trim whitespace
3. Check against command dictionary
4. If match found:
   - Send command code to desktop
   - Clear transcription buffer
5. If no match:
   - Send as regular text
```

### PIN-Based Pairing

#### Pairing Flow
```
┌─────────────────────────────────────────────────────────┐
│                    PAIRING FLOW                          │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  Android                              Linux Desktop      │
│  ────────                             ─────────────      │
│      │                                      │            │
│      │  1. Scan & Connect                   │            │
│      │─────────────────────────────────────►│            │
│      │                                      │            │
│      │  2. Desktop displays PIN             │            │
│      │◄─────────────────────────────────────│            │
│      │                                      │            │
│      │  3. User enters PIN on Android       │            │
│      │                                      │            │
│      │  4. Android derives key from PIN     │            │
│      │                                      │            │
│      │  5. Send encrypted test message      │            │
│      │─────────────────────────────────────►│            │
│      │                                      │            │
│      │  6. Desktop validates & confirms     │            │
│      │◄─────────────────────────────────────│            │
│      │                                      │            │
│      │  7. Store pairing (device ID + salt) │            │
│      │                                      │            │
└─────────────────────────────────────────────────────────┘
```

#### Stored Pairing Data
```json
{
  "deviceId": "AA:BB:CC:DD:EE:FF",
  "deviceName": "dan-desktop",
  "salt": "base64-encoded-salt",
  "pairedAt": "2024-01-15T10:30:00Z"
}
```

---

## Features to Migrate

### Screens (5 Total)

#### 1. Home Screen
**Primary Function:** Main dashboard showing connection status and active transcription

| Element | Description |
|---------|-------------|
| Connection Badge | Shows BLE connection state (connected/disconnected/connecting) |
| Device Info | Displays connected device name and signal strength |
| Transcription Display | Real-time speech-to-text with partial results |
| Audio Visualizer | Waveform visualization of microphone input |
| Quick Actions | Start/stop listening, disconnect |

#### 2. Connection Screen
**Primary Function:** Device discovery and pairing management

| Element | Description |
|---------|-------------|
| Device Scanner | List of discovered BLE devices |
| Scan Controls | Start/stop scanning, refresh |
| Paired Devices | List of previously paired devices |
| Pairing Dialog | PIN entry for new device pairing |
| Connection Actions | Connect, forget device |

#### 3. Settings Screen
**Primary Function:** App configuration

| Setting | Type | Description |
|---------|------|-------------|
| Auto-connect | Toggle | Connect to last device on app start |
| Speech Language | Dropdown | Recognition language selection |
| Continuous Mode | Toggle | Auto-restart listening |
| Haptic Feedback | Toggle | Vibrate on command recognition |
| Theme | Selection | Light/Dark/System |
| About | Info | App version, licenses |

#### 4. Speech Test Screen
**Primary Function:** Debug and test speech recognition in isolation

| Element | Description |
|---------|-------------|
| Recognition Status | Current state of speech recognizer |
| Confidence Meter | Recognition confidence percentage |
| Result Log | Scrollable log of all recognition results |
| Partial Results | Real-time partial transcription display |
| Controls | Start, stop, clear log |

#### 5. Bluetooth Test Screen
**Primary Function:** Debug and test BLE communication in isolation

| Element | Description |
|---------|-------------|
| Connection State | Detailed BLE state information |
| MTU Info | Negotiated MTU and payload size |
| Service/Characteristic UUIDs | Display discovered services |
| Send Test | Manual message sending for testing |
| Receive Log | Log of received notifications |
| Raw Data View | Hex dump of sent/received bytes |

---

### Services (5 Total)

#### 1. BleService
**Responsibility:** All Bluetooth Low Energy operations

```kotlin
interface BleService {
    // Connection
    val connectionState: StateFlow<ConnectionState>
    suspend fun connect(deviceId: String): Result<Unit>
    suspend fun disconnect()
    
    // Discovery
    val discoveredDevices: StateFlow<List<BleDevice>>
    suspend fun startScan()
    suspend fun stopScan()
    
    // Data Transfer
    suspend fun send(data: ByteArray): Result<Unit>
    val receivedData: SharedFlow<ByteArray>
    
    // MTU
    val mtu: StateFlow<Int>
    suspend fun requestMtu(size: Int): Result<Int>
}
```

#### 2. SpeechService
**Responsibility:** Speech recognition lifecycle and results

```kotlin
interface SpeechService {
    // State
    val isListening: StateFlow<Boolean>
    val speechState: StateFlow<SpeechState>
    
    // Results
    val partialResults: SharedFlow<String>
    val finalResults: SharedFlow<SpeechResult>
    val errors: SharedFlow<SpeechError>
    
    // Control
    suspend fun startListening(language: String = "en-US")
    suspend fun stopListening()
    
    // Audio Level
    val audioLevel: StateFlow<Float>
}
```

#### 3. CommandProcessor
**Responsibility:** Parse transcriptions and detect voice commands

```kotlin
interface CommandProcessor {
    fun process(transcription: String): ProcessedInput
    fun registerCustomCommand(trigger: String, command: VoiceCommand)
    fun getAvailableCommands(): List<VoiceCommand>
}

sealed class ProcessedInput {
    data class Text(val content: String) : ProcessedInput()
    data class Command(val command: VoiceCommand) : ProcessedInput()
    data class Mixed(val text: String, val command: VoiceCommand) : ProcessedInput()
}
```

#### 4. PermissionService
**Responsibility:** Runtime permission requests and state

```kotlin
interface PermissionService {
    val permissionState: StateFlow<PermissionState>
    
    suspend fun requestPermissions(permissions: List<String>): PermissionResult
    fun hasPermission(permission: String): Boolean
    fun shouldShowRationale(permission: String): Boolean
    fun openAppSettings()
}

data class PermissionState(
    val microphone: PermissionStatus,
    val bluetooth: PermissionStatus,
    val location: PermissionStatus  // Required for BLE scanning on older Android
)
```

#### 5. SecureStorageService
**Responsibility:** Encrypted storage for sensitive data

```kotlin
interface SecureStorageService {
    // Key-Value Storage
    suspend fun putString(key: String, value: String)
    suspend fun getString(key: String): String?
    suspend fun putBytes(key: String, value: ByteArray)
    suspend fun getBytes(key: String): ByteArray?
    suspend fun remove(key: String)
    suspend fun clear()
    
    // Pairing Data
    suspend fun savePairing(device: PairedDevice)
    suspend fun getPairings(): List<PairedDevice>
    suspend fun removePairing(deviceId: String)
}
```

---

### Domain Models

#### Message
```kotlin
data class Message(
    val id: String = UUID.randomUUID().toString(),
    val type: MessageType,
    val content: String,
    val timestamp: Instant = Instant.now(),
    val status: MessageStatus = MessageStatus.PENDING
)

enum class MessageType {
    TEXT,
    COMMAND
}

enum class MessageStatus {
    PENDING,
    SENDING,
    SENT,
    FAILED
}
```

#### BleDevice
```kotlin
data class BleDevice(
    val id: String,          // MAC address
    val name: String?,
    val rssi: Int,           // Signal strength
    val isConnectable: Boolean,
    val serviceUuids: List<UUID>,
    val lastSeen: Instant
)
```

#### VoiceCommand
```kotlin
enum class VoiceCommand(
    val code: Byte,
    val triggers: List<String>,
    val description: String
) {
    ENTER(0x01, listOf("enter", "new line", "return"), "Press Enter key"),
    SELECT_ALL(0x02, listOf("select all", "highlight all"), "Select all text"),
    COPY(0x03, listOf("copy", "copy that"), "Copy selection"),
    PASTE(0x04, listOf("paste", "paste that"), "Paste clipboard"),
    CUT(0x05, listOf("cut", "cut that"), "Cut selection"),
    CANCEL(0x06, listOf("cancel", "undo", "never mind"), "Cancel input");
    
    companion object {
        fun fromTranscription(text: String): VoiceCommand? {
            val normalized = text.lowercase().trim()
            return values().find { cmd ->
                cmd.triggers.any { trigger ->
                    normalized == trigger || normalized.endsWith(trigger)
                }
            }
        }
    }
}
```

#### ConnectionState
```kotlin
sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Scanning : ConnectionState()
    data class Connecting(val device: BleDevice) : ConnectionState()
    data class Connected(val device: BleDevice, val mtu: Int) : ConnectionState()
    data class Error(val message: String, val cause: Throwable?) : ConnectionState()
}
```

#### PairedDevice
```kotlin
data class PairedDevice(
    val deviceId: String,
    val deviceName: String,
    val salt: ByteArray,
    val pairedAt: Instant,
    val lastConnected: Instant?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairedDevice) return false
        return deviceId == other.deviceId
    }
    
    override fun hashCode(): Int = deviceId.hashCode()
}
```

---

### UI Components (Widgets)

#### 1. AudioVisualizer
**Purpose:** Real-time visualization of microphone audio levels

```kotlin
@Composable
fun AudioVisualizer(
    audioLevel: Float,           // 0.0 to 1.0
    modifier: Modifier = Modifier,
    barCount: Int = 20,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.surfaceVariant
)
```

#### 2. WaveformVisualizer
**Purpose:** Animated waveform display showing audio input patterns

```kotlin
@Composable
fun WaveformVisualizer(
    samples: List<Float>,        // Audio sample buffer
    modifier: Modifier = Modifier,
    waveColor: Color = MaterialTheme.colorScheme.primary,
    backgroundColor: Color = Color.Transparent
)
```

#### 3. ConnectionBadge
**Purpose:** Compact connection status indicator

```kotlin
@Composable
fun ConnectionBadge(
    state: ConnectionState,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
)
```

#### 4. TranscriptionDisplay
**Purpose:** Shows current and partial transcription with styling

```kotlin
@Composable
fun TranscriptionDisplay(
    finalText: String,
    partialText: String,
    isListening: Boolean,
    modifier: Modifier = Modifier,
    onClear: () -> Unit
)
```

---

## Architecture Decisions

### Pattern: MVVM + Unidirectional Data Flow (UDF)

#### Why MVVM?
- **Official Recommendation:** Google's recommended architecture for Android
- **Lifecycle Awareness:** ViewModels survive configuration changes
- **Testability:** Business logic is easily unit tested
- **Separation:** Clear boundaries between UI and business logic

#### Unidirectional Data Flow
```
┌─────────────────────────────────────────────────────────┐
│                     UI LAYER                             │
│  ┌──────────────────────────────────────────────────┐   │
│  │                   Compose UI                      │   │
│  │  • Renders state                                  │   │
│  │  • Emits events/intents                          │   │
│  └──────────────────────────────────────────────────┘   │
│              │                        ▲                  │
│              │ Events                 │ State            │
│              ▼                        │                  │
│  ┌──────────────────────────────────────────────────┐   │
│  │                  ViewModel                        │   │
│  │  • Processes events                              │   │
│  │  • Updates state                                 │   │
│  │  • Coordinates use cases                         │   │
│  └──────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
                        │
                        │ Use Cases
                        ▼
┌─────────────────────────────────────────────────────────┐
│                   DOMAIN LAYER                           │
│  ┌──────────────────┐    ┌──────────────────────────┐   │
│  │    Use Cases     │    │  Repository Interfaces   │   │
│  │  (Interactors)   │    │                          │   │
│  └──────────────────┘    └──────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
                        │
                        │ Repository Implementations
                        ▼
┌─────────────────────────────────────────────────────────┐
│                    DATA LAYER                            │
│  ┌─────────────┐  ┌─────────────┐  ┌────────────────┐   │
│  │Repositories │  │  Services   │  │  Data Sources  │   │
│  └─────────────┘  └─────────────┘  └────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

### Repository Pattern

#### Purpose
- Abstract data sources from business logic
- Enable easy swapping of implementations (testing, different backends)
- Centralize data access logic

#### Example
```kotlin
// Domain layer - interface
interface DeviceRepository {
    fun getPairedDevices(): Flow<List<PairedDevice>>
    suspend fun savePairing(device: PairedDevice)
    suspend fun removePairing(deviceId: String)
}

// Data layer - implementation
class DeviceRepositoryImpl @Inject constructor(
    private val secureStorage: SecureStorageService,
    private val dispatcher: CoroutineDispatcher
) : DeviceRepository {
    // Implementation details
}
```

### Use Cases (Interactors)

#### Purpose
- Encapsulate single business operations
- Reusable across ViewModels
- Easy to test in isolation

#### Example
```kotlin
class SendTextUseCase @Inject constructor(
    private val bleService: BleService,
    private val encryptionService: EncryptionService,
    private val deviceRepository: DeviceRepository
) {
    suspend operator fun invoke(text: String): Result<Unit> {
        val pairedDevice = deviceRepository.getCurrentDevice()
            ?: return Result.failure(NotPairedException())
        
        val encrypted = encryptionService.encrypt(
            text.toByteArray(),
            pairedDevice.salt
        )
        
        return bleService.send(encrypted)
    }
}
```

### State Management

#### UI State Pattern
```kotlin
data class HomeUiState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val isListening: Boolean = false,
    val transcription: String = "",
    val partialTranscription: String = "",
    val audioLevel: Float = 0f,
    val error: String? = null
)

sealed class HomeEvent {
    object StartListening : HomeEvent()
    object StopListening : HomeEvent()
    object Connect : HomeEvent()
    object Disconnect : HomeEvent()
    data class ErrorShown(val error: String) : HomeEvent()
}
```

#### ViewModel Implementation
```kotlin
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val speechService: SpeechService,
    private val bleService: BleService,
    private val sendTextUseCase: SendTextUseCase
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    
    fun onEvent(event: HomeEvent) {
        when (event) {
            is HomeEvent.StartListening -> startListening()
            is HomeEvent.StopListening -> stopListening()
            // ...
        }
    }
}
```

---

## Risk Assessment

### High Risk Areas

| Area | Risk | Mitigation |
|------|------|------------|
| **BLE Reliability** | Android BLE stack is notoriously buggy | Use Nordic BLE library, extensive testing on multiple devices |
| **Speech Recognition** | Varies by device/manufacturer | Fallback mechanisms, clear error messages |
| **Background Operation** | Android kills background services aggressively | Foreground service with notification, WorkManager for reliability |
| **Encryption Compatibility** | Must be byte-compatible with Linux side | Extensive cross-platform testing, shared test vectors |

### Medium Risk Areas

| Area | Risk | Mitigation |
|------|------|------------|
| **Permission Changes** | Android permission model evolves | Target latest SDK, handle all permission scenarios |
| **Compose Stability** | Some Compose APIs still evolving | Pin library versions, avoid experimental APIs |
| **Memory Management** | Audio/BLE buffers can grow | Proper cleanup, lifecycle-aware collection |

### Migration Risks

| Risk | Impact | Mitigation |
|------|--------|------------|
| **Data Migration** | Users lose paired devices | Export/import feature, or keep Flutter storage format |
| **Feature Parity** | Missing features frustrate users | Complete feature audit before release |
| **Regression** | Bugs in reimplementation | Comprehensive test suite, beta testing period |

---

## Success Criteria

The native rewrite will be considered successful when:

1. **Feature Parity** - All Flutter features work identically
2. **Performance** - App startup < 1 second, speech latency < 200ms
3. **Reliability** - BLE connection stable over 8+ hour sessions
4. **Test Coverage** - >80% unit test coverage on business logic
5. **User Experience** - No regressions reported in beta testing
6. **Maintainability** - Code follows modern Android best practices

---

## Next Steps

Proceed to [Phase 01: Project Setup](./01-PROJECT-SETUP.md) to begin implementation.
