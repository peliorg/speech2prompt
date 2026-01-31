# ViewModels Usage Guide

Quick reference for using the Speech2Prompt ViewModels in Composable screens.

## HomeViewModel

### Basic Usage
```kotlin
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onNavigateToSettings: () -> Unit,
    onNavigateToConnection: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Handle one-time events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is HomeEvent.ShowSnackbar -> {
                    snackbarHostState.showSnackbar(event.message)
                }
                is HomeEvent.NavigateTo -> {
                    if (event.route == "settings") onNavigateToSettings()
                    else if (event.route == "connection") onNavigateToConnection()
                }
                is HomeEvent.MessageSent -> {
                    // Optional: haptic feedback
                }
                is HomeEvent.MessageFailed -> {
                    snackbarHostState.showSnackbar(event.reason)
                }
            }
        }
    }
    
    // UI based on state
    when {
        uiState.connectionState == BtConnectionState.CONNECTED -> {
            ConnectedUI(
                isListening = uiState.isListening,
                currentText = uiState.currentText,
                soundLevel = uiState.soundLevel,
                onToggleListening = viewModel::toggleListening
            )
        }
        else -> {
            DisconnectedUI(
                connectionState = uiState.connectionState,
                onNavigateToConnection = viewModel::navigateToConnection
            )
        }
    }
}
```

### Available Actions
```kotlin
viewModel.startListening()      // Start speech recognition
viewModel.stopListening()       // Stop speech recognition
viewModel.pauseListening()      // Pause (can resume)
viewModel.resumeListening()     // Resume from pause
viewModel.toggleListening()     // Toggle listening state
viewModel.navigateToConnection() // Navigate to connection screen
viewModel.navigateToSettings()  // Navigate to settings screen
viewModel.clearError()          // Clear error message
viewModel.sendManualText(text)  // Manually send text (debug)
```

### State Properties
```kotlin
uiState.connectionState  // BtConnectionState
uiState.connectedDevice  // BleDeviceInfo?
uiState.isListening      // Boolean
uiState.isPaused         // Boolean
uiState.currentText      // String
uiState.soundLevel       // Float (0.0 to 1.0)
uiState.errorMessage     // String?
uiState.isInitialized    // Boolean
```

## ConnectionViewModel

### Basic Usage
```kotlin
@Composable
fun ConnectionScreen(
    viewModel: ConnectionViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val scannedDevices by viewModel.scannedDevices.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val showPairingDialog by viewModel.showPairingDialog.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    
    // Auto-navigate back on connection
    LaunchedEffect(connectionState) {
        if (connectionState == BtConnectionState.CONNECTED) {
            delay(500)
            onNavigateBack()
        }
    }
    
    // Start scanning on entry
    LaunchedEffect(Unit) {
        viewModel.startScan()
    }
    
    LazyColumn {
        items(scannedDevices) { device ->
            DeviceItem(
                device = device,
                onClick = { viewModel.connectToDevice(device) }
            )
        }
    }
    
    if (showPairingDialog) {
        PairingDialog(
            onSubmit = { pin -> viewModel.submitPairingPin(pin) },
            onDismiss = { viewModel.cancelPairing() }
        )
    }
}
```

### Available Actions
```kotlin
viewModel.startScan()            // Start BLE scanning
viewModel.stopScan()             // Stop BLE scanning
viewModel.connectToDevice(device) // Connect to device
viewModel.submitPairingPin(pin)  // Submit 6-digit PIN
viewModel.cancelPairing()        // Cancel pairing
viewModel.disconnect()           // Disconnect from device
```

### State Properties
```kotlin
scannedDevices     // List<BleDeviceInfo>
connectionState    // BtConnectionState
connectedDevice    // BleDeviceInfo?
isScanning         // Boolean
bluetoothEnabled   // Boolean
showPairingDialog  // Boolean
error              // String?
```

## SettingsViewModel

### Basic Usage
```kotlin
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val selectedLocale by viewModel.selectedLocale.collectAsStateWithLifecycle()
    val showPartialResults by viewModel.showPartialResults.collectAsStateWithLifecycle()
    val enterEnabled by viewModel.enterEnabled.collectAsStateWithLifecycle()
    val pairedDevices by viewModel.pairedDevices.collectAsStateWithLifecycle()
    
    LazyColumn {
        item {
            LocaleSelector(
                selectedLocale = selectedLocale,
                onLocaleSelected = { viewModel.setLocale(it) }
            )
        }
        
        item {
            SwitchPreference(
                title = "Show partial results",
                checked = showPartialResults,
                onCheckedChange = { viewModel.setShowPartialResults(it) }
            )
        }
        
        item {
            CommandToggle(
                title = "Enter / New line",
                enabled = enterEnabled,
                onEnabledChange = { viewModel.setEnterEnabled(it) }
            )
        }
        
        items(pairedDevices) { device ->
            PairedDeviceItem(
                device = device,
                onForget = { viewModel.forgetPairedDevice(device.address) }
            )
        }
    }
}
```

### Available Actions
```kotlin
// Speech settings
viewModel.setLocale(locale)
viewModel.setShowPartialResults(enabled)
viewModel.setKeepScreenOn(enabled)

// Command toggles
viewModel.setEnterEnabled(enabled)
viewModel.setSelectAllEnabled(enabled)
viewModel.setCopyEnabled(enabled)
viewModel.setPasteEnabled(enabled)
viewModel.setCutEnabled(enabled)
viewModel.setCancelEnabled(enabled)

// Connection settings
viewModel.setAutoReconnect(enabled)

// Device management
viewModel.forgetPairedDevice(address)
viewModel.forgetAllPairedDevices()
```

### State Properties
```kotlin
// Speech
selectedLocale       // String
availableLocales     // List<Locale>
showPartialResults   // Boolean
keepScreenOn         // Boolean

// Commands
enterEnabled         // Boolean
selectAllEnabled     // Boolean
copyEnabled          // Boolean
pasteEnabled         // Boolean
cutEnabled           // Boolean
cancelEnabled        // Boolean

// Connection
autoReconnect        // Boolean
pairedDevices        // List<PairedDevice>
```

## SpeechTestViewModel

### Basic Usage
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
    
    Column {
        // Sound level indicator
        LinearProgressIndicator(progress = soundLevel)
        
        // Controls
        Row {
            Button(
                onClick = { viewModel.startListening() },
                enabled = !isListening
            ) { Text("Start") }
            
            Button(
                onClick = { viewModel.stopListening() },
                enabled = isListening
            ) { Text("Stop") }
            
            Button(onClick = { viewModel.clearHistory() }) {
                Text("Clear")
            }
        }
        
        // History
        LazyColumn {
            items(recognizedTexts.reversed()) { text ->
                Text("• $text")
            }
        }
    }
}
```

### Available Actions
```kotlin
viewModel.startListening()  // Start recognition
viewModel.stopListening()   // Stop recognition
viewModel.clearHistory()    // Clear logs
```

### State Properties
```kotlin
isListening        // Boolean
soundLevel         // Float (0.0 to 1.0)
currentText        // String
errorMessage       // String?
recognizedTexts    // List<String>
detectedCommands   // List<CommandCode>
```

## BluetoothTestViewModel

### Basic Usage
```kotlin
@Composable
fun BluetoothTestScreen(
    viewModel: BluetoothTestViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val messageLog by viewModel.messageLog.collectAsStateWithLifecycle()
    var textInput by remember { mutableStateOf("") }
    
    Column {
        // Connection status
        ConnectionBadge(state = connectionState)
        
        // Text input
        OutlinedTextField(
            value = textInput,
            onValueChange = { textInput = it },
            label = { Text("Text to send") }
        )
        
        Button(
            onClick = {
                viewModel.sendText(textInput)
                textInput = ""
            },
            enabled = connectionState.isConnected
        ) { Text("Send") }
        
        // Command buttons
        FlowRow {
            CommandCode.entries.forEach { cmd ->
                OutlinedButton(
                    onClick = { viewModel.sendCommand(cmd) },
                    enabled = connectionState.isConnected
                ) { Text(cmd.code) }
            }
        }
        
        // Message log
        LazyColumn {
            items(messageLog.reversed()) { entry ->
                Text(entry, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
```

### Available Actions
```kotlin
viewModel.sendText(text)      // Send text message
viewModel.sendCommand(command) // Send command code
viewModel.clearLog()          // Clear message log
```

### State Properties
```kotlin
connectionState   // BtConnectionState
connectedDevice   // BleDeviceInfo?
messageLog        // List<String> (timestamped)
```

## Common Patterns

### Collecting State with Lifecycle
```kotlin
// Recommended: Automatically handles lifecycle
val state by viewModel.stateFlow.collectAsStateWithLifecycle()

// Alternative: Manual lifecycle handling
val state by viewModel.stateFlow.collectAsState()
```

### Handling One-Time Events
```kotlin
// Use LaunchedEffect for events that should trigger once
LaunchedEffect(Unit) {
    viewModel.events.collect { event ->
        when (event) {
            is MyEvent.Navigate -> navigate(event.route)
            is MyEvent.ShowMessage -> showSnackbar(event.message)
        }
    }
}
```

### Error Handling
```kotlin
val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

errorMessage?.let { error ->
    Snackbar(
        action = {
            TextButton(onClick = { viewModel.clearError() }) {
                Text("Dismiss")
            }
        }
    ) {
        Text(error)
    }
}
```

### Permission Requests
```kotlin
val permissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
) { results ->
    if (results.values.all { it }) {
        viewModel.startListening()
    } else {
        // Show permission denied message
    }
}

Button(onClick = {
    permissionLauncher.launch(REQUIRED_PERMISSIONS)
}) {
    Text("Start Listening")
}
```

## Testing Examples

### ViewModel Unit Test
```kotlin
@Test
fun `toggleListening starts listening when idle`() = runTest {
    val viewModel = HomeViewModel(fakeBleManager, fakeSpeechManager)
    
    viewModel.toggleListening()
    
    val state = viewModel.uiState.value
    assertThat(state.isListening).isTrue()
}
```

### Flow Testing with Turbine
```kotlin
@Test
fun `events emit MessageSent on successful send`() = runTest {
    val viewModel = HomeViewModel(fakeBleManager, fakeSpeechManager)
    
    viewModel.events.test {
        viewModel.sendManualText("test")
        val event = awaitItem()
        assertThat(event).isInstanceOf<HomeEvent.MessageSent>()
    }
}
```
