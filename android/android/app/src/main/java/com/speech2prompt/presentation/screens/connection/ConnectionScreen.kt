package com.speech2prompt.presentation.screens.connection

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.speech2prompt.domain.model.BtConnectionState
import com.speech2prompt.domain.model.displayText
import com.speech2prompt.presentation.components.*
import com.speech2prompt.presentation.theme.*
import com.speech2prompt.util.permissions.PermissionState
import com.speech2prompt.util.permissions.rememberPermissionState

/**
 * Connection screen for device scanning and pairing.
 * 
 * Features:
 * - Device scanning with refresh button
 * - List of discovered devices (using DeviceListItem)
 * - Connection state display
 * - Automatic pairing via ECDH
 * - Navigate back when connected
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    onNavigateBack: () -> Unit,
    viewModel: ConnectionViewModel = hiltViewModel()
) {
    val scannedDevices by viewModel.scannedDevices.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val connectedDevice by viewModel.connectedDevice.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val bluetoothEnabled by viewModel.bluetoothEnabled.collectAsState()
    val error by viewModel.error.collectAsState()
    
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Bluetooth permission state
    val bluetoothPermissions = remember {
        PermissionState.getRequiredBluetoothPermissions() + 
        PermissionState.getRequiredLocationPermissions()
    }
    
    val permissionState = rememberPermissionState(
        permissions = bluetoothPermissions,
        onPermissionsResult = { allGranted ->
            if (allGranted) {
                viewModel.startScan()
            }
        }
    )
    
    // Auto-navigate back when connected (immediately, don't show "Connected" on this screen)
    LaunchedEffect(connectionState) {
        if (connectionState == BtConnectionState.CONNECTED) {
            onNavigateBack()
        }
    }
    
    // Show error snackbar
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }
    
    // Start scanning on first composition (only if permissions granted)
    LaunchedEffect(permissionState.allGranted) {
        if (permissionState.allGranted) {
            viewModel.startScan()
        } else if (bluetoothPermissions.isNotEmpty()) {
            // Request permissions if not granted
            permissionState.launchPermissionRequest()
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connect Device") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { 
                            if (permissionState.allGranted) {
                                viewModel.startScan()
                            } else {
                                permissionState.launchPermissionRequest()
                            }
                        },
                        enabled = !isScanning && bluetoothEnabled
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ConnectionContent(
                scannedDevices = scannedDevices,
                connectionState = connectionState,
                connectedDevice = connectedDevice,
                isScanning = isScanning,
                bluetoothEnabled = bluetoothEnabled,
                hasBluetoothPermissions = permissionState.allGranted,
                onConnectDevice = viewModel::connectToDevice,
                onStartScan = {
                    if (permissionState.allGranted) {
                        viewModel.startScan()
                    } else {
                        permissionState.launchPermissionRequest()
                    }
                },
                onRequestPermissions = { permissionState.launchPermissionRequest() }
            )
        }
    }
}

@Composable
private fun ConnectionContent(
    scannedDevices: List<com.speech2prompt.domain.model.BleDeviceInfo>,
    connectionState: BtConnectionState,
    connectedDevice: com.speech2prompt.domain.model.BleDeviceInfo?,
    isScanning: Boolean,
    bluetoothEnabled: Boolean,
    hasBluetoothPermissions: Boolean,
    onConnectDevice: (com.speech2prompt.domain.model.BleDeviceInfo) -> Unit,
    onStartScan: () -> Unit,
    onRequestPermissions: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Bluetooth Disabled Warning
        if (!bluetoothEnabled) {
            StatusCard(
                title = "Bluetooth Disabled",
                message = "Please enable Bluetooth to scan for devices",
                icon = Icons.Default.BluetoothDisabled,
                iconTint = Error
            )
            return
        }
        
        // Bluetooth Permission Required
        if (!hasBluetoothPermissions) {
            StatusCard(
                title = "Bluetooth Permission Required",
                message = "Grant Bluetooth permission to scan for nearby devices",
                icon = Icons.Default.Security,
                iconTint = Warning,
                action = {
                    Button(
                        onClick = onRequestPermissions,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Primary,
                            contentColor = OnPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Grant Permission")
                    }
                }
            )
            return
        }
        
        // Connection Status (only show during active connection states, not when connected since we navigate away)
        if (connectionState == BtConnectionState.CONNECTING || 
            connectionState == BtConnectionState.PAIRING ||
            connectionState == BtConnectionState.RECONNECTING) {
            ConnectionStatusSection(
                connectionState = connectionState,
                connectedDevice = connectedDevice
            )
            Spacer(Modifier.height(16.dp))
        }
        
        // Scanning Indicator
        if (isScanning) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 3.dp,
                    color = Primary
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Scanning for devices...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnBackgroundMuted
                )
            }
            Spacer(Modifier.height(16.dp))
        }
        
        // Device List
        Text(
            text = "Available Devices",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        
        if (scannedDevices.isEmpty() && !isScanning) {
            EmptyDeviceList(onStartScan = onStartScan)
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(scannedDevices, key = { it.address }) { device ->
                    // Determine if this specific device is currently being connected/paired
                    // Animation should show during CONNECTING, PAIRING, AWAITING_PAIRING, and RECONNECTING states
                    // but only for the device that is being connected
                    val isThisDeviceConnecting = connectedDevice?.address == device.address &&
                        (connectionState == BtConnectionState.CONNECTING ||
                         connectionState == BtConnectionState.PAIRING ||
                         connectionState == BtConnectionState.AWAITING_PAIRING ||
                         connectionState == BtConnectionState.RECONNECTING)
                    
                    DeviceListItem(
                        device = device,
                        onConnect = { onConnectDevice(device) },
                        isConnecting = isThisDeviceConnecting
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatusSection(
    connectionState: BtConnectionState,
    connectedDevice: com.speech2prompt.domain.model.BleDeviceInfo?
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = Primary.copy(alpha = 0.1f)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = connectionState.displayText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Primary
                    )
                    if (connectedDevice != null) {
                        Text(
                            text = connectedDevice.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = OnBackgroundMuted
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyDeviceList(onStartScan: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        StatusCard(
            title = "No Devices Found",
            message = "Make sure your device is in pairing mode and nearby",
            icon = Icons.Default.BluetoothSearching,
            iconTint = OnBackgroundSubtle,
            action = {
                Button(
                    onClick = onStartScan,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Primary,
                        contentColor = OnPrimary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Scan Again")
                }
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectionScreenPreview() {
    Speech2PromptTheme {
        // Preview would need mock ViewModel
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("Connection Screen Preview")
        }
    }
}
