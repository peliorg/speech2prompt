# Phase 11: Connection Screen

### Goal
BLE device scanning and pairing UI.

### ConnectionViewModel (presentation/connection/ConnectionViewModel.kt)
```kotlin
@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val bleManager: BleManager,
    private val permissionManager: PermissionManager
) : ViewModel() {
    
    val devices = bleManager.scannedDevices
    val connectionState = bleManager.connectionState
    val connectedDevice = bleManager.connectedDevice
    
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning
    
    private val _showPairingDialog = MutableStateFlow(false)
    val showPairingDialog: StateFlow<Boolean> = _showPairingDialog
    
    private val _bluetoothEnabled = MutableStateFlow(true)
    val bluetoothEnabled: StateFlow<Boolean> = _bluetoothEnabled
    
    private var pendingDevice: BleDeviceInfo? = null
    
    fun startScan() {
        viewModelScope.launch {
            _isScanning.value = true
            bleManager.startScan()
                .onCompletion { _isScanning.value = false }
                .collect { /* devices updated via bleManager.scannedDevices */ }
        }
    }
    
    fun stopScan() {
        bleManager.stopScan()
        _isScanning.value = false
    }
    
    fun connectToDevice(device: BleDeviceInfo) {
        viewModelScope.launch {
            stopScan()
            pendingDevice = device
            val success = bleManager.connect(device)
            if (success && connectionState.value == BtConnectionState.AWAITING_PAIRING) {
                _showPairingDialog.value = true
            }
        }
    }
    
    fun submitPairingPin(pin: String) {
        viewModelScope.launch {
            pendingDevice?.let { device ->
                // The linuxDeviceId comes from the pairing response
                bleManager.setPairingPin(pin, "linux-device-id")
                _showPairingDialog.value = false
            }
        }
    }
    
    fun cancelPairing() {
        _showPairingDialog.value = false
        bleManager.disconnect()
    }
    
    fun disconnect() {
        viewModelScope.launch {
            bleManager.disconnect()
        }
    }
    
    fun hasBluetoothPermissions(): Boolean = permissionManager.hasBluetoothPermissions()
}
```

### ConnectionScreen (presentation/connection/ConnectionScreen.kt)
```kotlin
@Composable
fun ConnectionScreen(
    viewModel: ConnectionViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val showPairingDialog by viewModel.showPairingDialog.collectAsStateWithLifecycle()
    val connectedDevice by viewModel.connectedDevice.collectAsStateWithLifecycle()
    
    // Request permissions
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            viewModel.startScan()
        }
    }
    
    LaunchedEffect(Unit) {
        if (viewModel.hasBluetoothPermissions()) {
            viewModel.startScan()
        } else {
            permissionLauncher.launch(PermissionManager.BLUETOOTH_PERMISSIONS)
        }
    }
    
    // Navigate back on successful connection
    LaunchedEffect(connectionState) {
        if (connectionState == BtConnectionState.CONNECTED) {
            delay(500)
            onNavigateBack()
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connect to Device") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(onClick = { viewModel.startScan() }) {
                            Icon(Icons.Default.Refresh, "Scan")
                        }
                    }
                }
            )
        }
    ) { padding ->
        // Sort: Speech2Prompt devices first, then by RSSI
        val sortedDevices = devices.sortedWith(
            compareByDescending<BleDeviceInfo> { it.isSpeech2Prompt }
                .thenByDescending { it.rssi }
        )
        
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (sortedDevices.isEmpty() && !isScanning) {
                item {
                    EmptyState(
                        icon = Icons.Default.BluetoothSearching,
                        title = "No devices found",
                        subtitle = "Make sure Speech2Prompt is running on your computer"
                    )
                }
            }
            
            items(sortedDevices, key = { it.address }) { device ->
                DeviceItem(
                    device = device,
                    isConnecting = connectionState.isConnecting && 
                                   connectedDevice?.address == device.address,
                    onClick = { viewModel.connectToDevice(device) }
                )
            }
        }
    }
    
    // Pairing dialog
    if (showPairingDialog) {
        PairingDialog(
            onSubmit = { pin -> viewModel.submitPairingPin(pin) },
            onDismiss = { viewModel.cancelPairing() }
        )
    }
}
```

### PairingDialog (presentation/connection/PairingDialog.kt)
```kotlin
@Composable
fun PairingDialog(
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter Pairing PIN") },
        text = {
            Column {
                Text("Enter the 6-digit PIN shown on your computer")
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { 
                        if (it.length <= 6 && it.all { c -> c.isDigit() }) {
                            pin = it
                            isError = false
                        }
                    },
                    label = { Text("PIN") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = isError,
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (pin.length == 6) {
                        onSubmit(pin)
                    } else {
                        isError = true
                    }
                }
            ) {
                Text("Pair")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
```

### DeviceItem Component
```kotlin
@Composable
fun DeviceItem(
    device: BleDeviceInfo,
    isConnecting: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(device.displayName) },
        supportingContent = { 
            Text("${device.address} • ${device.rssi} dBm")
        },
        leadingContent = {
            Icon(
                imageVector = if (device.isSpeech2Prompt) 
                    Icons.Default.Computer else Icons.Default.Bluetooth,
                contentDescription = null,
                tint = if (device.isSpeech2Prompt) Primary else LocalContentColor.current
            )
        },
        trailingContent = {
            if (isConnecting) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else if (device.isSpeech2Prompt) {
                Icon(Icons.Default.Star, "Recommended", tint = Primary)
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}
```

### Verification
- [ ] Permission requested before scanning
- [ ] Scan runs for ~15 seconds
- [ ] Speech2Prompt devices shown at top with star
- [ ] Tap device triggers connection
- [ ] Pairing dialog appears when needed
- [ ] PIN validation (6 digits)
- [ ] Navigate back on successful connection
- [ ] Reconnection uses stored credentials
