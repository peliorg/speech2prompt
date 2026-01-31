# Phase 10: Home Screen

### Goal
Main screen with microphone control and status display.

### HomeViewModel (presentation/home/HomeViewModel.kt)
```kotlin
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val bleManager: BleManager,
    private val speechService: SpeechService,
    private val commandProcessor: CommandProcessor
) : ViewModel() {
    
    val connectionState = bleManager.connectionState
    val connectedDevice = bleManager.connectedDevice
    val isListening = speechService.isListening
    val isPaused = speechService.isPaused
    val soundLevel = speechService.soundLevel
    val currentText = speechService.currentText
    val errorMessage = speechService.errorMessage
    
    init {
        setupSpeechCallbacks()
    }
    
    private fun setupSpeechCallbacks() {
        speechService.onTextRecognized = { text ->
            commandProcessor.processText(text)
        }
        speechService.onCommandDetected = { command ->
            commandProcessor.processCommand(command)
        }
    }
    
    fun toggleListening() {
        viewModelScope.launch {
            if (connectionState.value != BtConnectionState.CONNECTED) {
                // Cannot listen without connection
                return@launch
            }
            speechService.toggleListening()
        }
    }
    
    fun clearError() {
        speechService.clearError()
    }
    
    fun initializeServices() {
        viewModelScope.launch {
            speechService.initialize()
        }
    }
}
```

### HomeScreen (presentation/home/HomeScreen.kt)
```kotlin
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onNavigateToConnection: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val connectedDevice by viewModel.connectedDevice.collectAsStateWithLifecycle()
    val isListening by viewModel.isListening.collectAsStateWithLifecycle()
    val soundLevel by viewModel.soundLevel.collectAsStateWithLifecycle()
    val currentText by viewModel.currentText.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    
    LaunchedEffect(Unit) {
        viewModel.initializeServices()
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Speech2Prompt") },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))
            
            // Connection badge
            ConnectionBadge(
                state = connectionState,
                deviceName = connectedDevice?.displayName,
                onClick = onNavigateToConnection
            )
            
            Spacer(Modifier.weight(1f))
            
            // Main visualizer
            AudioVisualizer(
                soundLevel = soundLevel,
                isActive = isListening,
                onClick = { viewModel.toggleListening() }
            )
            
            Spacer(Modifier.height(24.dp))
            
            // Status text
            Text(
                text = when {
                    !connectionState.isConnected -> "Connect to start"
                    isListening -> "Tap to pause"
                    else -> "Tap to listen"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = OnBackgroundMuted
            )
            
            // Error message
            errorMessage?.let { error ->
                Spacer(Modifier.height(8.dp))
                Text(error, color = Error, fontSize = 12.sp)
            }
            
            Spacer(Modifier.weight(1f))
            
            // Transcription
            TranscriptionDisplay(
                text = currentText,
                isListening = isListening
            )
            
            Spacer(Modifier.height(24.dp))
            
            // Bottom waveform
            WaveformVisualizer(
                soundLevel = soundLevel,
                isActive = isListening
            )
            
            Spacer(Modifier.height(32.dp))
        }
    }
}
```

### Verification
- [ ] Connection badge shows correct state
- [ ] Tap badge navigates to connection screen
- [ ] Tap visualizer toggles listening (when connected)
- [ ] Shows "Connect to start" when disconnected
- [ ] Transcription updates in real-time
- [ ] Sound level reflects actual audio
- [ ] Error messages display and clear
