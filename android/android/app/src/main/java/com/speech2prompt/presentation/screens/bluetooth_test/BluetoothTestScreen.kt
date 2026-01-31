package com.speech2prompt.presentation.screens.bluetooth_test

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.speech2prompt.domain.model.BtConnectionState
import com.speech2prompt.domain.model.CommandCode
import com.speech2prompt.domain.model.displayText
import com.speech2prompt.presentation.theme.*

/**
 * Bluetooth test screen for isolated BLE connection testing.
 * 
 * Features:
 * - Test BLE connection
 * - Connect/disconnect button
 * - Send test message button
 * - Message log (sent/received)
 * - Connection status
 * - Clear log button
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BluetoothTestScreen(
    onNavigateBack: () -> Unit,
    onNavigateToConnection: () -> Unit,
    viewModel: BluetoothTestViewModel = hiltViewModel()
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val connectedDevice by viewModel.connectedDevice.collectAsState()
    val messageLog by viewModel.messageLog.collectAsState()
    
    var testMessage by remember { mutableStateOf("") }
    var showCommandDialog by remember { mutableStateOf(false) }
    
    val listState = rememberLazyListState()
    
    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messageLog.size) {
        if (messageLog.isNotEmpty()) {
            listState.animateScrollToItem(messageLog.size - 1)
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bluetooth Test") },
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
                        onClick = viewModel::clearLog,
                        enabled = messageLog.isNotEmpty()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Clear Log"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = Background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Connection Status Panel
            ConnectionStatusPanel(
                connectionState = connectionState,
                connectedDevice = connectedDevice,
                onNavigateToConnection = onNavigateToConnection
            )
            
            // Control Panel
            if (connectionState == BtConnectionState.CONNECTED) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    tonalElevation = 2.dp
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Text Message Input
                        OutlinedTextField(
                            value = testMessage,
                            onValueChange = { testMessage = it },
                            label = { Text("Test Message") },
                            placeholder = { Text("Enter text to send...") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    if (testMessage.isNotBlank()) {
                                        viewModel.sendText(testMessage)
                                        testMessage = ""
                                    }
                                }
                            ),
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        if (testMessage.isNotBlank()) {
                                            viewModel.sendText(testMessage)
                                            testMessage = ""
                                        }
                                    },
                                    enabled = testMessage.isNotBlank()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Send,
                                        contentDescription = "Send"
                                    )
                                }
                            }
                        )
                        
                        // Quick Actions
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { showCommandDialog = true },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Secondary,
                                    contentColor = OnSecondary
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Code,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Send Command")
                            }
                            
                            Button(
                                onClick = { viewModel.sendText("Hello from Android!") },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Primary,
                                    contentColor = OnPrimary
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Message,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Quick Test")
                            }
                        }
                    }
                }
            }
            
            // Message Log
            MessageLogList(
                messageLog = messageLog,
                listState = listState,
                modifier = Modifier.weight(1f)
            )
        }
    }
    
    // Command Selection Dialog
    if (showCommandDialog) {
        CommandSelectionDialog(
            onSelectCommand = { command ->
                viewModel.sendCommand(command)
                showCommandDialog = false
            },
            onDismiss = { showCommandDialog = false }
        )
    }
}

@Composable
private fun ConnectionStatusPanel(
    connectionState: BtConnectionState,
    connectedDevice: com.speech2prompt.domain.model.BleDeviceInfo?,
    onNavigateToConnection: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = when (connectionState) {
            BtConnectionState.CONNECTED -> Primary.copy(alpha = 0.1f)
            BtConnectionState.DISCONNECTED, BtConnectionState.FAILED -> Error.copy(alpha = 0.1f)
            else -> OnBackgroundSubtle.copy(alpha = 0.1f)
        },
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status Icon
            val icon = when (connectionState) {
                BtConnectionState.CONNECTED -> Icons.Default.BluetoothConnected
                BtConnectionState.CONNECTING, BtConnectionState.RECONNECTING -> Icons.Default.BluetoothSearching
                else -> Icons.Default.Bluetooth
            }
            
            val iconColor = when (connectionState) {
                BtConnectionState.CONNECTED -> Primary
                BtConnectionState.DISCONNECTED, BtConnectionState.FAILED -> Error
                else -> OnBackgroundMuted
            }
            
            Icon(
                imageVector = icon,
                contentDescription = "Connection Status",
                tint = iconColor,
                modifier = Modifier.size(32.dp)
            )
            
            Spacer(Modifier.width(16.dp))
            
            // Status Text
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = connectionState.displayText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (connectedDevice != null) {
                    Text(
                        text = connectedDevice.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnBackgroundSubtle
                    )
                } else {
                    Text(
                        text = "No device connected",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnBackgroundSubtle
                    )
                }
            }
            
            // Connect Button
            if (connectionState != BtConnectionState.CONNECTED) {
                Button(
                    onClick = onNavigateToConnection,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Primary,
                        contentColor = OnPrimary
                    )
                ) {
                    Text("Connect")
                }
            }
        }
    }
}

@Composable
private fun MessageLogList(
    messageLog: List<String>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier
) {
    if (messageLog.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Message,
                    contentDescription = null,
                    tint = OnBackgroundSubtle,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "No Messages",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = OnBackgroundMuted
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Messages sent and received will appear here",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnBackgroundSubtle,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                Text(
                    text = "Message Log",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            
            items(messageLog) { message ->
                LogMessageItem(message = message)
            }
        }
    }
}

@Composable
private fun LogMessageItem(message: String) {
    val (timestamp, content) = message.split("] ", limit = 2).let {
        if (it.size == 2) it[0].removePrefix("[") to it[1]
        else "" to message
    }
    
    val color = when {
        content.startsWith("SENT:") -> Primary
        content.startsWith("RECV:") -> Secondary
        content.startsWith("ERROR:") -> Error
        else -> OnBackgroundMuted
    }
    
    val backgroundColor = when {
        content.startsWith("SENT:") -> Primary.copy(alpha = 0.05f)
        content.startsWith("RECV:") -> Secondary.copy(alpha = 0.05f)
        content.startsWith("ERROR:") -> Error.copy(alpha = 0.05f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    }
    
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = backgroundColor
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = timestamp,
                style = MaterialTheme.typography.labelSmall,
                color = OnBackgroundSubtle,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = content,
                style = MaterialTheme.typography.bodySmall,
                color = color,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun CommandSelectionDialog(
    onSelectCommand: (CommandCode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Select Command",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CommandCode.entries.forEach { command ->
                    Surface(
                        onClick = { onSelectCommand(command) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Code,
                                contentDescription = null,
                                tint = Secondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = command.code,
                                style = MaterialTheme.typography.bodyLarge,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = OnBackgroundSubtle,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = OnBackgroundMuted
                )
            ) {
                Text("Cancel")
            }
        },
        shape = MaterialTheme.shapes.large,
        containerColor = Surface,
        tonalElevation = 8.dp
    )
}

@Preview(showBackground = true)
@Composable
private fun BluetoothTestScreenPreview() {
    Speech2PromptTheme {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("Bluetooth Test Screen Preview")
        }
    }
}
