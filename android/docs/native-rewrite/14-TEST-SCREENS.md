# Phase 14: Test Screens (Debug)

## Goal
Implement debug screens for testing speech and BLE independently.

## SpeechTestScreen (presentation/debug/SpeechTestScreen.kt)

```kotlin
@Composable
fun SpeechTestScreen(
    viewModel: SpeechTestViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val isListening by viewModel.isListening.collectAsStateWithLifecycle()
    val soundLevel by viewModel.soundLevel.collectAsStateWithLifecycle()
    val recognizedTexts by viewModel.recognizedTexts.collectAsStateWithLifecycle()
    val detectedCommands by viewModel.detectedCommands.collectAsStateWithLifecycle()
    val currentText by viewModel.currentText.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Speech Test") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.clearHistory() }) {
                        Icon(Icons.Default.Delete, "Clear")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Sound level indicator
            Text("Sound Level: ${(soundLevel * 100).toInt()}%")
            LinearProgressIndicator(
                progress = soundLevel,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(Modifier.height(16.dp))
            
            // Current text
            Text("Current: ${currentText.ifEmpty { "(silence)" }}")
            
            // Error
            errorMessage?.let {
                Text(it, color = Error)
            }
            
            Spacer(Modifier.height(16.dp))
            
            // Controls
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.startListening() }, enabled = !isListening) {
                    Text("Start")
                }
                Button(onClick = { viewModel.stopListening() }, enabled = isListening) {
                    Text("Stop")
                }
            }
            
            Spacer(Modifier.height(16.dp))
            
            // Recognized texts
            Text("Recognized Texts:", fontWeight = FontWeight.Bold)
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(recognizedTexts.reversed()) { text ->
                    Text("• $text", fontSize = 14.sp)
                }
            }
            
            // Detected commands
            Text("Detected Commands:", fontWeight = FontWeight.Bold)
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(detectedCommands.reversed()) { cmd ->
                    Text("-> ${cmd.code}", color = Primary, fontSize = 14.sp)
                }
            }
        }
    }
}
```

## SpeechTestViewModel (presentation/debug/SpeechTestViewModel.kt)

```kotlin
@HiltViewModel
class SpeechTestViewModel @Inject constructor(
    private val speechService: SpeechService
) : ViewModel() {
    
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()
    
    private val _soundLevel = MutableStateFlow(0f)
    val soundLevel: StateFlow<Float> = _soundLevel.asStateFlow()
    
    private val _recognizedTexts = MutableStateFlow<List<String>>(emptyList())
    val recognizedTexts: StateFlow<List<String>> = _recognizedTexts.asStateFlow()
    
    private val _detectedCommands = MutableStateFlow<List<CommandCode>>(emptyList())
    val detectedCommands: StateFlow<List<CommandCode>> = _detectedCommands.asStateFlow()
    
    private val _currentText = MutableStateFlow("")
    val currentText: StateFlow<String> = _currentText.asStateFlow()
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    init {
        observeSpeechEvents()
    }
    
    private fun observeSpeechEvents() {
        viewModelScope.launch {
            speechService.events.collect { event ->
                when (event) {
                    is SpeechEvent.PartialResult -> {
                        _currentText.value = event.text
                    }
                    is SpeechEvent.FinalResult -> {
                        _currentText.value = ""
                        _recognizedTexts.update { it + event.text }
                    }
                    is SpeechEvent.CommandDetected -> {
                        _detectedCommands.update { it + event.command }
                    }
                    is SpeechEvent.SoundLevel -> {
                        _soundLevel.value = event.level
                    }
                    is SpeechEvent.Error -> {
                        _errorMessage.value = event.message
                    }
                    is SpeechEvent.Started -> {
                        _isListening.value = true
                        _errorMessage.value = null
                    }
                    is SpeechEvent.Stopped -> {
                        _isListening.value = false
                    }
                }
            }
        }
    }
    
    fun startListening() {
        speechService.startListening()
    }
    
    fun stopListening() {
        speechService.stopListening()
    }
    
    fun clearHistory() {
        _recognizedTexts.value = emptyList()
        _detectedCommands.value = emptyList()
        _currentText.value = ""
        _errorMessage.value = null
    }
    
    override fun onCleared() {
        super.onCleared()
        speechService.stopListening()
    }
}
```

## BluetoothTestScreen (presentation/debug/BluetoothTestScreen.kt)

```kotlin
@Composable
fun BluetoothTestScreen(
    viewModel: BluetoothTestViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val connectedDevice by viewModel.connectedDevice.collectAsStateWithLifecycle()
    val messageLog by viewModel.messageLog.collectAsStateWithLifecycle()
    
    var textToSend by remember { mutableStateOf("") }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bluetooth Test") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Connection status
            ConnectionBadge(state = connectionState, deviceName = connectedDevice?.displayName)
            
            Spacer(Modifier.height(16.dp))
            
            // Text input
            OutlinedTextField(
                value = textToSend,
                onValueChange = { textToSend = it },
                label = { Text("Text to send") },
                modifier = Modifier.fillMaxWidth()
            )
            
            Button(
                onClick = { 
                    viewModel.sendText(textToSend)
                    textToSend = ""
                },
                enabled = connectionState.isConnected && textToSend.isNotEmpty()
            ) {
                Text("Send Text")
            }
            
            Spacer(Modifier.height(16.dp))
            
            // Command buttons
            Text("Commands:", fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CommandCode.entries.forEach { cmd ->
                    OutlinedButton(
                        onClick = { viewModel.sendCommand(cmd) },
                        enabled = connectionState.isConnected
                    ) {
                        Text(cmd.code)
                    }
                }
            }
            
            Spacer(Modifier.height(16.dp))
            
            // Message log
            Text("Message Log:", fontWeight = FontWeight.Bold)
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(messageLog.reversed()) { entry ->
                    Text(
                        entry,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
```

## BluetoothTestViewModel (presentation/debug/BluetoothTestViewModel.kt)

```kotlin
@HiltViewModel
class BluetoothTestViewModel @Inject constructor(
    private val bleService: BleService
) : ViewModel() {
    
    val connectionState: StateFlow<ConnectionState> = bleService.connectionState
    val connectedDevice: StateFlow<BleDevice?> = bleService.connectedDevice
    
    private val _messageLog = MutableStateFlow<List<String>>(emptyList())
    val messageLog: StateFlow<List<String>> = _messageLog.asStateFlow()
    
    init {
        observeBleEvents()
    }
    
    private fun observeBleEvents() {
        viewModelScope.launch {
            bleService.events.collect { event ->
                val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
                val logEntry = when (event) {
                    is BleEvent.Connected -> "[$timestamp] CONNECTED: ${event.device.displayName}"
                    is BleEvent.Disconnected -> "[$timestamp] DISCONNECTED"
                    is BleEvent.MessageSent -> "[$timestamp] SENT: ${event.message.type}"
                    is BleEvent.MessageReceived -> "[$timestamp] RECV: ${event.message.type}"
                    is BleEvent.Error -> "[$timestamp] ERROR: ${event.message}"
                    else -> "[$timestamp] ${event::class.simpleName}"
                }
                _messageLog.update { it + logEntry }
            }
        }
    }
    
    fun sendText(text: String) {
        viewModelScope.launch {
            log("Sending text: $text")
            bleService.sendMessage(Message.text(text))
        }
    }
    
    fun sendCommand(command: CommandCode) {
        viewModelScope.launch {
            log("Sending command: ${command.code}")
            bleService.sendMessage(Message.command(command))
        }
    }
    
    private fun log(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        _messageLog.update { it + "[$timestamp] $message" }
    }
}
```

## Navigation Integration

Add routes to NavHost for debug screens:

```kotlin
// In Navigation.kt
sealed class Screen(val route: String) {
    // ... existing screens
    object SpeechTest : Screen("speech_test")
    object BluetoothTest : Screen("bluetooth_test")
}

// In NavHost
composable(Screen.SpeechTest.route) {
    SpeechTestScreen(
        onNavigateBack = { navController.popBackStack() }
    )
}

composable(Screen.BluetoothTest.route) {
    BluetoothTestScreen(
        onNavigateBack = { navController.popBackStack() }
    )
}
```

## Debug Menu in Settings

Add a debug section to settings screen (debug builds only):

```kotlin
@Composable
fun SettingsScreen(...) {
    // ... existing content
    
    if (BuildConfig.DEBUG) {
        Spacer(Modifier.height(24.dp))
        
        Text(
            "Debug Tools",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(Modifier.height(8.dp))
        
        OutlinedButton(
            onClick = { onNavigateToSpeechTest() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Speech Test")
        }
        
        OutlinedButton(
            onClick = { onNavigateToBluetoothTest() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Bluetooth Test")
        }
    }
}
```

## Verification

- [ ] Speech test shows real-time sound level
- [ ] Recognized texts logged
- [ ] Commands detected and logged
- [ ] BLE test can send manual text
- [ ] BLE test can send each command
- [ ] Message log shows sent/received
- [ ] Debug screens only accessible in debug builds
- [ ] Back navigation works correctly
