# Native Android Architecture

## Overview

This document describes the architecture for the native Android rewrite of Speech2Prompt. The app follows **MVVM with Unidirectional Data Flow (UDF)** using a **three-layer architecture**.

```
┌─────────────────────────────────────────────────────────────────┐
│                         UI LAYER                                │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐  │
│  │  Composable │  │  Composable │  │       Composable        │  │
│  │   Screens   │──│  Components │──│    State Hoisting       │  │
│  └──────┬──────┘  └─────────────┘  └─────────────────────────┘  │
│         │ collectAsStateWithLifecycle()                         │
│  ┌──────┴──────┐                                                │
│  │  ViewModel  │ StateFlow<UiState>                             │
│  └──────┬──────┘                                                │
└─────────┼───────────────────────────────────────────────────────┘
          │
┌─────────┼───────────────────────────────────────────────────────┐
│         │              DOMAIN LAYER                             │
│  ┌──────┴──────┐  ┌─────────────┐  ┌─────────────────────────┐  │
│  │  Use Cases  │  │   Domain    │  │      Repository         │  │
│  │  (optional) │──│   Models    │──│      Interfaces         │  │
│  └──────┬──────┘  └─────────────┘  └─────────────────────────┘  │
└─────────┼───────────────────────────────────────────────────────┘
          │
┌─────────┼───────────────────────────────────────────────────────┐
│         │              DATA LAYER                               │
│  ┌──────┴──────┐  ┌─────────────┐  ┌─────────────────────────┐  │
│  │ Repository  │  │    Data     │  │      Data Sources       │  │
│  │   Impls     │──│   Models    │──│  (Local/BLE/Network)    │  │
│  └─────────────┘  └─────────────┘  └─────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

---

## Architecture Pattern

### MVVM with Unidirectional Data Flow

```
┌──────────────────────────────────────────────────────────────────┐
│                    UNIDIRECTIONAL DATA FLOW                      │
│                                                                  │
│    ┌─────────┐      Events       ┌─────────────┐                 │
│    │         │ ─────────────────▶│             │                 │
│    │   UI    │                   │  ViewModel  │                 │
│    │         │◀───────────────── │             │                 │
│    └─────────┘      State        └──────┬──────┘                 │
│         │                               │                        │
│         │ User Actions                  │ Business Logic         │
│         ▼                               ▼                        │
│    ┌─────────┐                   ┌─────────────┐                 │
│    │ Intent  │                   │   State     │                 │
│    │ (Event) │                   │  (UiState)  │                 │
│    └─────────┘                   └─────────────┘                 │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

**Key Principles:**
- State flows down from ViewModel to UI via `StateFlow`
- Events flow up from UI to ViewModel via function calls
- UI is a pure function of state: `UI = f(State)`
- Single source of truth for each piece of data

---

## Package Structure

```
app/src/main/java/com/speech2prompt/
│
├── Speech2PromptApplication.kt      # Application class with Hilt
├── MainActivity.kt                   # Single Activity, Compose host
│
├── di/                               # Dependency Injection
│   ├── AppModule.kt                  # App-wide dependencies
│   ├── RepositoryModule.kt           # Repository bindings
│   └── ServiceModule.kt              # Service singletons
│
├── data/                             # Data Layer
│   ├── model/                        # Data transfer objects
│   │   ├── BleDeviceDto.kt
│   │   ├── PairedDeviceEntity.kt
│   │   └── PreferencesData.kt
│   ├── repository/                   # Repository implementations
│   │   ├── SecureStorageRepository.kt
│   │   ├── PreferencesRepository.kt
│   │   └── DeviceRepository.kt
│   └── source/                       # Data sources
│       ├── local/
│       │   ├── SecureStorage.kt
│       │   └── DataStoreSource.kt
│       └── ble/
│           └── BleDataSource.kt
│
├── domain/                           # Domain Layer
│   ├── model/                        # Domain models (clean)
│   │   ├── BleDevice.kt
│   │   ├── Message.kt
│   │   ├── ConnectionState.kt
│   │   ├── VoiceCommand.kt
│   │   └── PairedDevice.kt
│   └── usecase/                      # Use cases (optional)
│       ├── ConnectDeviceUseCase.kt
│       ├── SendMessageUseCase.kt
│       └── ProcessVoiceCommandUseCase.kt
│
├── service/                          # Services Layer
│   ├── ble/
│   │   ├── BleManager.kt             # Central BLE management
│   │   ├── BleConstants.kt           # UUIDs, timeouts
│   │   ├── PacketAssembler.kt        # Outgoing packet chunking
│   │   └── PacketReassembler.kt      # Incoming packet assembly
│   ├── speech/
│   │   ├── SpeechService.kt          # Speech recognition
│   │   └── CommandProcessor.kt       # Voice command parsing
│   ├── crypto/
│   │   ├── CryptoUtils.kt            # Encryption utilities
│   │   └── CryptoContext.kt          # Session crypto state
│   └── PermissionManager.kt          # Runtime permissions
│
├── presentation/                     # UI Layer
│   ├── navigation/
│   │   └── AppNavigation.kt          # NavHost & routes
│   ├── theme/
│   │   ├── Color.kt
│   │   ├── Theme.kt
│   │   └── Typography.kt
│   ├── components/                   # Shared UI components
│   │   ├── AudioVisualizer.kt
│   │   ├── WaveformVisualizer.kt
│   │   ├── ConnectionBadge.kt
│   │   ├── TranscriptionDisplay.kt
│   │   └── LoadingIndicator.kt
│   ├── home/
│   │   ├── HomeScreen.kt
│   │   ├── HomeViewModel.kt
│   │   └── HomeUiState.kt
│   ├── connection/
│   │   ├── ConnectionScreen.kt
│   │   ├── ConnectionViewModel.kt
│   │   ├── ConnectionUiState.kt
│   │   └── PairingDialog.kt
│   ├── settings/
│   │   ├── SettingsScreen.kt
│   │   ├── SettingsViewModel.kt
│   │   └── SettingsUiState.kt
│   └── debug/
│       ├── SpeechTestScreen.kt
│       └── BluetoothTestScreen.kt
│
└── util/
    ├── Extensions.kt                 # Kotlin extensions
    ├── Constants.kt                  # App constants
    └── Result.kt                     # Result wrapper
```

---

## State Management

### UI State Modeling with Sealed Interfaces

```kotlin
// presentation/home/HomeUiState.kt

sealed interface HomeUiState {
    data object Loading : HomeUiState
    
    data class Ready(
        val connectionState: ConnectionState,
        val transcription: String,
        val isListening: Boolean,
        val audioLevel: Float,
        val recentMessages: List<Message>
    ) : HomeUiState
    
    data class Error(
        val message: String,
        val retryAction: (() -> Unit)?
    ) : HomeUiState
}

// Nested states for complex UI
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Scanning : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val device: BleDevice) : ConnectionState
    data class Error(val reason: String) : ConnectionState
}
```

### ViewModel Pattern

```kotlin
// presentation/home/HomeViewModel.kt

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val bleManager: BleManager,
    private val speechService: SpeechService,
    private val preferencesRepository: PreferencesRepository
) : ViewModel() {

    // Private mutable state
    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    
    // Public immutable state
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    
    // One-time events (navigation, snackbars)
    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events: Flow<HomeEvent> = _events.receiveAsFlow()

    init {
        observeConnectionState()
        observeSpeechResults()
    }

    private fun observeConnectionState() {
        bleManager.connectionState
            .onEach { state -> updateConnectionState(state) }
            .launchIn(viewModelScope)
    }

    // Event handlers (called from UI)
    fun onStartListening() {
        viewModelScope.launch {
            speechService.startListening()
            updateState { copy(isListening = true) }
        }
    }

    fun onStopListening() {
        speechService.stopListening()
        updateState { copy(isListening = false) }
    }

    fun onSendMessage(text: String) {
        viewModelScope.launch {
            bleManager.sendMessage(text)
                .onSuccess { _events.send(HomeEvent.MessageSent) }
                .onFailure { _events.send(HomeEvent.SendFailed(it.message)) }
        }
    }

    private inline fun updateState(transform: HomeUiState.Ready.() -> HomeUiState.Ready) {
        _uiState.update { current ->
            when (current) {
                is HomeUiState.Ready -> current.transform()
                else -> current
            }
        }
    }
}

// One-time events
sealed interface HomeEvent {
    data object MessageSent : HomeEvent
    data class SendFailed(val reason: String?) : HomeEvent
    data class NavigateTo(val route: String) : HomeEvent
}
```

### Composable State Collection

```kotlin
// presentation/home/HomeScreen.kt

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onNavigateToSettings: () -> Unit
) {
    // Collect state with lifecycle awareness
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    
    // Handle one-time events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is HomeEvent.NavigateTo -> onNavigateToSettings()
                is HomeEvent.SendFailed -> { /* Show snackbar */ }
                is HomeEvent.MessageSent -> { /* Haptic feedback */ }
            }
        }
    }

    // Render based on state
    when (val state = uiState) {
        is HomeUiState.Loading -> LoadingScreen()
        is HomeUiState.Ready -> HomeContent(
            state = state,
            onStartListening = viewModel::onStartListening,
            onStopListening = viewModel::onStopListening,
            onSendMessage = viewModel::onSendMessage
        )
        is HomeUiState.Error -> ErrorScreen(
            message = state.message,
            onRetry = state.retryAction
        )
    }
}

@Composable
private fun HomeContent(
    state: HomeUiState.Ready,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onSendMessage: (String) -> Unit
) {
    // Stateless composable - easy to preview and test
    Column(modifier = Modifier.fillMaxSize()) {
        ConnectionBadge(state = state.connectionState)
        
        TranscriptionDisplay(
            text = state.transcription,
            isListening = state.isListening
        )
        
        if (state.isListening) {
            AudioVisualizer(level = state.audioLevel)
        }
        
        // ... rest of UI
    }
}
```

---

## Dependency Injection with Hilt

### Application Setup

```kotlin
// Speech2PromptApplication.kt

@HiltAndroidApp
class Speech2PromptApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize any app-wide services
    }
}
```

### Module Definitions

```kotlin
// di/AppModule.kt

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideContext(@ApplicationContext context: Context): Context = context

    @Provides
    @Singleton
    fun provideBluetoothAdapter(@ApplicationContext context: Context): BluetoothAdapter? {
        val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
        return bluetoothManager?.adapter
    }

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return PreferenceDataStoreFactory.create {
            context.preferencesDataStoreFile("settings")
        }
    }

    @Provides
    @Singleton
    fun provideEncryptedSharedPreferences(
        @ApplicationContext context: Context
    ): SharedPreferences {
        return EncryptedSharedPreferences.create(
            context,
            "secure_prefs",
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}
```

```kotlin
// di/ServiceModule.kt

@Module
@InstallIn(SingletonComponent::class)
object ServiceModule {

    @Provides
    @Singleton
    fun provideBleManager(
        @ApplicationContext context: Context,
        bluetoothAdapter: BluetoothAdapter?,
        cryptoUtils: CryptoUtils
    ): BleManager {
        return BleManager(context, bluetoothAdapter, cryptoUtils)
    }

    @Provides
    @Singleton
    fun provideSpeechService(
        @ApplicationContext context: Context
    ): SpeechService {
        return SpeechService(context)
    }

    @Provides
    @Singleton
    fun provideCryptoUtils(): CryptoUtils {
        return CryptoUtils()
    }

    @Provides
    @Singleton
    fun providePermissionManager(
        @ApplicationContext context: Context
    ): PermissionManager {
        return PermissionManager(context)
    }
}
```

```kotlin
// di/RepositoryModule.kt

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindPreferencesRepository(
        impl: PreferencesRepositoryImpl
    ): PreferencesRepository

    @Binds
    @Singleton
    abstract fun bindSecureStorageRepository(
        impl: SecureStorageRepositoryImpl
    ): SecureStorageRepository

    @Binds
    @Singleton
    abstract fun bindDeviceRepository(
        impl: DeviceRepositoryImpl
    ): DeviceRepository
}
```

### Component Scopes

```
┌─────────────────────────────────────────────────────────────────┐
│                    HILT COMPONENT HIERARCHY                     │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │              SingletonComponent (@Singleton)              │  │
│  │  ┌─────────────┐ ┌─────────────┐ ┌─────────────────────┐  │  │
│  │  │ BleManager  │ │SpeechService│ │   Repositories      │  │  │
│  │  │ (singleton) │ │ (singleton) │ │   (singleton)       │  │  │
│  │  └─────────────┘ └─────────────┘ └─────────────────────┘  │  │
│  └───────────────────────────┬───────────────────────────────┘  │
│                              │                                  │
│  ┌───────────────────────────┴───────────────────────────────┐  │
│  │              ActivityComponent (@ActivityScoped)          │  │
│  │  ┌─────────────────────────────────────────────────────┐  │  │
│  │  │              MainActivity                           │  │  │
│  │  └─────────────────────────────────────────────────────┘  │  │
│  └───────────────────────────┬───────────────────────────────┘  │
│                              │                                  │
│  ┌───────────────────────────┴───────────────────────────────┐  │
│  │            ViewModelComponent (@ViewModelScoped)          │  │
│  │  ┌───────────────┐ ┌───────────────┐ ┌─────────────────┐  │  │
│  │  │ HomeViewModel │ │ConnectionVM   │ │ SettingsVM      │  │  │
│  │  └───────────────┘ └───────────────┘ └─────────────────┘  │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Data Flow Diagram

### Complete Flow: User Action to UI Update

```
┌──────────────────────────────────────────────────────────────────────────┐
│                           COMPLETE DATA FLOW                             │
│                                                                          │
│  ┌──────────┐    User taps     ┌─────────────┐    Calls      ┌────────┐  │
│  │    UI    │ ────"Connect"───▶│  ViewModel  │ ─────────────▶│UseCase │  │
│  │ (Screen) │                  │             │               │(opt.)  │  │
│  └────▲─────┘                  └─────────────┘               └───┬────┘  │
│       │                                                          │       │
│       │ Recompose                                                │       │
│       │                                                          ▼       │
│  ┌────┴─────┐                                             ┌────────────┐ │
│  │StateFlow │◀────────────────────────────────────────────│ Repository │ │
│  │ emission │           State updates flow back           │            │ │
│  └──────────┘                                             └─────┬──────┘ │
│                                                                 │        │
│                                                                 ▼        │
│                                                          ┌────────────┐  │
│                                                          │DataSource  │  │
│                                                          │(BLE/Local) │  │
│                                                          └────────────┘  │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

### BLE Message Flow

```
┌──────────────────────────────────────────────────────────────────────────┐
│                        BLE MESSAGE FLOW                                  │
│                                                                          │
│  OUTGOING (Send Message):                                                │
│  ┌──────────┐   ┌──────────┐   ┌──────────┐   ┌──────────┐   ┌────────┐  │
│  │ViewModel │──▶│BleManager│──▶│ Crypto   │──▶│ Packet   │──▶│  BLE   │  │
│  │sendMsg() │   │.send()   │   │ encrypt  │   │Assembler │   │ Write  │  │
│  └──────────┘   └──────────┘   └──────────┘   └──────────┘   └────────┘  │
│                                                                          │
│  INCOMING (Receive Message):                                             │
│  ┌────────┐   ┌──────────┐   ┌──────────┐   ┌──────────┐   ┌──────────┐  │
│  │  BLE   │──▶│ Packet   │──▶│ Crypto   │──▶│BleManager│──▶│ViewModel │  │
│  │ Notify │   │Reassemble│   │ decrypt  │   │.messages │   │ observe  │  │
│  └────────┘   └──────────┘   └──────────┘   └──────────┘   └──────────┘  │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

### Speech Recognition Flow

```
┌──────────────────────────────────────────────────────────────────────────┐
│                     SPEECH RECOGNITION FLOW                              │
│                                                                          │
│  ┌─────────┐   ┌─────────────┐   ┌───────────────┐   ┌────────────────┐  │
│  │   Mic   │──▶│SpeechService│──▶│CommandProcessor│──▶│   ViewModel    │  │
│  │ Input   │   │ (Android    │   │ (parse voice  │   │ (update state) │  │
│  │         │   │  Speech API)│   │  commands)    │   │                │  │
│  └─────────┘   └──────┬──────┘   └───────────────┘   └────────────────┘  │
│                       │                                                  │
│                       │ Emits Flow<SpeechResult>                         │
│                       ▼                                                  │
│                ┌──────────────┐                                          │
│                │ SpeechResult │                                          │
│                │ - Partial    │                                          │
│                │ - Final      │                                          │
│                │ - Error      │                                          │
│                └──────────────┘                                          │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## Key Design Decisions

### 1. Why Hilt over Koin

| Aspect | Hilt | Koin |
|--------|------|------|
| **Validation** | Compile-time | Runtime |
| **Performance** | No reflection overhead | Uses reflection |
| **Error Detection** | Build fails on missing deps | Crashes at runtime |
| **Android Integration** | First-party (Google) | Third-party |
| **Learning Curve** | Steeper (annotations) | Gentler (DSL) |

**Decision:** Hilt provides compile-time safety, which catches dependency issues before they reach users. The annotations are more verbose but self-documenting.

```kotlin
// Hilt: Compile-time validated
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val bleManager: BleManager  // Missing binding = compile error
) : ViewModel()

// Koin: Runtime validated
val viewModelModule = module {
    viewModel { HomeViewModel(get()) }  // Missing binding = runtime crash
}
```

### 2. Why StateFlow over LiveData

| Aspect | StateFlow | LiveData |
|--------|-----------|----------|
| **Language** | Kotlin-first | Java-first |
| **Null Safety** | Non-null by design | Nullable |
| **Initial Value** | Required | Optional |
| **Operators** | Full Flow operators | Limited |
| **Testing** | Turbine library | InstantTaskExecutor |
| **Thread Safety** | Built-in | Requires postValue |

**Decision:** StateFlow is Kotlin-native, integrates seamlessly with coroutines and Flow, and provides better null-safety guarantees.

```kotlin
// StateFlow: Kotlin-first, non-null, rich operators
private val _state = MutableStateFlow(HomeUiState.Loading)
val state: StateFlow<HomeUiState> = _state
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeUiState.Loading
    )

// Combine multiple flows easily
val combinedState = combine(
    bleManager.connectionState,
    speechService.isListening,
    preferencesRepository.settings
) { connection, listening, settings ->
    HomeUiState.Ready(connection, listening, settings)
}.stateIn(viewModelScope, SharingStarted.Lazily, HomeUiState.Loading)
```

### 3. Why Separate Domain Layer

```
┌─────────────────────────────────────────────────────────────────┐
│                    WITHOUT DOMAIN LAYER                         │
│                                                                 │
│  ┌──────────────┐         ┌──────────────┐                      │
│  │  ViewModel   │────────▶│  Repository  │                      │
│  │              │         │              │                      │
│  │ - UI Logic   │         │ - Data Logic │                      │
│  │ - Biz Logic  │ ◀ ◀ ◀   │ - Mapping    │                      │
│  │ - Mapping    │  Tight  │              │                      │
│  └──────────────┘ Coupling└──────────────┘                      │
│                                                                 │
│  Problems:                                                      │
│  - ViewModel becomes bloated                                    │
│  - Business logic duplicated across ViewModels                  │
│  - Hard to test business logic in isolation                     │
│  - UI changes affect business logic                             │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                    WITH DOMAIN LAYER                            │
│                                                                 │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────┐         │
│  │  ViewModel   │──▶│   UseCase    │──▶│  Repository  │         │
│  │              │   │              │   │              │         │
│  │ - UI Logic   │   │ - Biz Logic  │   │ - Data Logic │         │
│  │ - State Mgmt │   │ - Validation │   │ - Caching    │         │
│  └──────────────┘   └──────────────┘   └──────────────┘         │
│         │                  │                   │                │
│         ▼                  ▼                   ▼                │
│    Presentation        Domain              Data                 │
│      Models            Models             Models                │
│                                                                 │
│  Benefits:                                                      │
│  - Single Responsibility                                        │
│  - Reusable business logic                                      │
│  - Easy to unit test                                            │
│  - Clean boundaries                                             │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Decision:** The domain layer provides a clean separation of concerns. Business logic lives in use cases, making it reusable across features and easy to test without Android dependencies.

```kotlin
// Domain layer enables clean, testable business logic
class ConnectDeviceUseCase @Inject constructor(
    private val bleManager: BleManager,
    private val deviceRepository: DeviceRepository,
    private val cryptoUtils: CryptoUtils
) {
    suspend operator fun invoke(deviceId: String): Result<Connection> {
        // Validate
        val device = deviceRepository.getDevice(deviceId)
            ?: return Result.failure(DeviceNotFoundError())
        
        // Check preconditions
        if (!bleManager.isBluetoothEnabled()) {
            return Result.failure(BluetoothDisabledError())
        }
        
        // Execute
        return bleManager.connect(device)
            .map { connection ->
                deviceRepository.markAsRecent(deviceId)
                connection
            }
    }
}

// Easy to test without Android framework
@Test
fun `connect fails when bluetooth disabled`() = runTest {
    whenever(bleManager.isBluetoothEnabled()).thenReturn(false)
    
    val result = connectDeviceUseCase("device-123")
    
    assertThat(result.isFailure).isTrue()
    assertThat(result.exceptionOrNull()).isInstanceOf(BluetoothDisabledError::class)
}
```

### 4. BLE Service as Singleton

```
┌─────────────────────────────────────────────────────────────────┐
│                  BLE SINGLETON PATTERN                          │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │                    BleManager                           │    │
│  │                   (@Singleton)                          │    │
│  │                                                         │    │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────────┐  │    │
│  │  │ Connection  │  │   Message   │  │    Crypto       │  │    │
│  │  │   State     │  │    Queue    │  │   Context       │  │    │
│  │  │ StateFlow   │  │   Channel   │  │  (session key)  │  │    │
│  │  └─────────────┘  └─────────────┘  └─────────────────┘  │    │
│  │                                                         │    │
│  └────────────────────────┬────────────────────────────────┘    │
│                           │                                     │
│           ┌───────────────┼───────────────┐                     │
│           │               │               │                     │
│           ▼               ▼               ▼                     │
│    ┌────────────┐  ┌────────────┐  ┌────────────┐               │
│    │ HomeVM     │  │ConnectionVM│  │ SettingsVM │               │
│    │ (observes) │  │ (connects) │  │ (observes) │               │
│    └────────────┘  └────────────┘  └────────────┘               │
│                                                                 │
│  Reasons for Singleton:                                         │
│  1. Single BLE connection at a time                             │
│  2. Shared connection state across screens                      │
│  3. Crypto context must persist                                 │
│  4. Message queue coordination                                  │
│  5. Prevents duplicate GATT connections                         │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Decision:** BLE requires careful management of a single connection. A singleton ensures:
- Only one GATT connection exists (Android limitation)
- Connection state is shared across all screens
- Crypto session persists through navigation
- No race conditions on connect/disconnect

```kotlin
@Singleton
class BleManager @Inject constructor(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter?,
    private val cryptoUtils: CryptoUtils
) {
    // Single source of truth for connection
    private val _connectionState = MutableStateFlow<ConnectionState>(Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    // Active GATT connection (only one allowed)
    private var gatt: BluetoothGatt? = null
    
    // Crypto context persists through navigation
    private var cryptoContext: CryptoContext? = null
    
    // Message queue prevents concurrent writes
    private val messageQueue = Channel<OutgoingMessage>(Channel.BUFFERED)
    
    init {
        // Process message queue sequentially
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for (message in messageQueue) {
                sendMessageInternal(message)
            }
        }
    }
    
    suspend fun connect(device: BleDevice): Result<Unit> {
        // Ensure only one connection
        disconnect()
        
        return withContext(Dispatchers.IO) {
            _connectionState.value = Connecting
            
            try {
                gatt = device.bluetoothDevice.connectGatt(
                    context,
                    false,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE
                )
                // ... connection logic
            } catch (e: Exception) {
                _connectionState.value = Error(e.message)
                Result.failure(e)
            }
        }
    }
}
```

---

## Testing Strategy

### Layer-Specific Testing

```
┌─────────────────────────────────────────────────────────────────┐
│                      TESTING PYRAMID                            │
│                                                                 │
│                         ┌─────┐                                 │
│                        /  E2E  \                                │
│                       /─────────\                               │
│                      / UI Tests  \                              │
│                     /─────────────\                             │
│                    / Integration   \                            │
│                   /─────────────────\                           │
│                  /    Unit Tests     \                          │
│                 /─────────────────────\                         │
│                                                                 │
│  Unit Tests (80%):                                              │
│  - ViewModels with fake repositories                            │
│  - Use cases with mock dependencies                             │
│  - Utility functions                                            │
│  - State transformations                                        │
│                                                                 │
│  Integration Tests (15%):                                       │
│  - Repository + DataSource                                      │
│  - ViewModel + UseCase                                          │
│  - Navigation flows                                             │
│                                                                 │
│  E2E/UI Tests (5%):                                             │
│  - Critical user journeys                                       │
│  - BLE connection flow (with mock)                              │
│  - Voice command flow                                           │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Test Dependencies

```kotlin
// build.gradle.kts (app)
dependencies {
    // Unit testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("app.cash.turbine:turbine:1.0.0")  // Flow testing
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("com.google.truth:truth:1.1.5")
    
    // Hilt testing
    testImplementation("com.google.dagger:hilt-android-testing:2.48")
    kaptTest("com.google.dagger:hilt-android-compiler:2.48")
    
    // Android instrumented tests
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
```

---

## Error Handling

### Result Wrapper Pattern

```kotlin
// util/Result.kt (or use kotlin.Result)

sealed class AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>()
    data class Error(val exception: AppException) : AppResult<Nothing>()
    data object Loading : AppResult<Nothing>()
}

sealed class AppException(message: String) : Exception(message) {
    class NetworkError(message: String) : AppException(message)
    class BleError(message: String) : AppException(message)
    class CryptoError(message: String) : AppException(message)
    class PermissionDenied(val permission: String) : AppException("Permission denied: $permission")
}

// Extension functions
inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) action(data)
    return this
}

inline fun <T> AppResult<T>.onError(action: (AppException) -> Unit): AppResult<T> {
    if (this is AppResult.Error) action(exception)
    return this
}
```

---

## Navigation

### Type-Safe Navigation with Compose

```kotlin
// presentation/navigation/AppNavigation.kt

@Serializable sealed interface Screen {
    @Serializable data object Home : Screen
    @Serializable data object Connection : Screen
    @Serializable data object Settings : Screen
    @Serializable data class DeviceDetails(val deviceId: String) : Screen
    @Serializable data object SpeechTest : Screen
    @Serializable data object BluetoothTest : Screen
}

@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home
    ) {
        composable<Screen.Home> {
            HomeScreen(
                onNavigateToConnection = { navController.navigate(Screen.Connection) },
                onNavigateToSettings = { navController.navigate(Screen.Settings) }
            )
        }
        
        composable<Screen.Connection> {
            ConnectionScreen(
                onDeviceSelected = { deviceId ->
                    navController.navigate(Screen.DeviceDetails(deviceId))
                },
                onBack = { navController.popBackStack() }
            )
        }
        
        composable<Screen.DeviceDetails> { backStackEntry ->
            val details: Screen.DeviceDetails = backStackEntry.toRoute()
            DeviceDetailsScreen(
                deviceId = details.deviceId,
                onBack = { navController.popBackStack() }
            )
        }
        
        composable<Screen.Settings> {
            SettingsScreen(
                onNavigateToSpeechTest = { navController.navigate(Screen.SpeechTest) },
                onNavigateToBluetoothTest = { navController.navigate(Screen.BluetoothTest) },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
```

---

## Summary

This architecture provides:

1. **Clear Separation of Concerns** - Each layer has a single responsibility
2. **Testability** - Business logic is isolated and easy to unit test
3. **Scalability** - New features can be added without touching existing code
4. **Type Safety** - Compile-time validation via Hilt and sealed classes
5. **Reactive Updates** - StateFlow ensures UI always reflects current state
6. **Single Source of Truth** - BLE and speech services are singletons with shared state

The architecture is intentionally simple for a small app while providing the foundation for growth. Use cases are optional and can be introduced as business logic complexity increases.
