package com.speech2prompt.presentation.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.speech2prompt.presentation.components.ConfirmDialog
import com.speech2prompt.presentation.theme.*
import java.util.Locale

/**
 * Settings screen for app configuration.
 * 
 * Features:
 * - Speech settings section (locale, partial results)
 * - Connection settings (auto-reconnect)
 * - Paired devices management
 * - Clear all data option
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val selectedLocale by viewModel.selectedLocale.collectAsState()
    val availableLocales by viewModel.availableLocales.collectAsState()
    val showPartialResults by viewModel.showPartialResults.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    
    val autoReconnect by viewModel.autoReconnect.collectAsState()
    val pairedDevices by viewModel.pairedDevices.collectAsState()
    
    var showLocaleDialog by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Speech Settings Section
            SettingsSection(title = "Speech Recognition") {
                // Locale Selection
                SettingsItem(
                    title = "Language",
                    subtitle = getLocaleDisplayName(selectedLocale),
                    icon = Icons.Default.Language,
                    onClick = { showLocaleDialog = true }
                )
                
                // Partial Results Toggle
                SettingsToggleItem(
                    title = "Show Partial Results",
                    subtitle = "Display text as you speak",
                    icon = Icons.Default.TextFields,
                    checked = showPartialResults,
                    onCheckedChange = viewModel::setShowPartialResults
                )
                
                // Keep Screen On Toggle
                SettingsToggleItem(
                    title = "Keep Screen On",
                    subtitle = "Prevent screen from sleeping while listening",
                    icon = Icons.Default.ScreenLockPortrait,
                    checked = keepScreenOn,
                    onCheckedChange = viewModel::setKeepScreenOn
                )
            }
            
            // Connection Settings Section
            SettingsSection(title = "Connection") {
                SettingsToggleItem(
                    title = "Auto Reconnect",
                    subtitle = "Automatically reconnect to last device",
                    icon = Icons.Default.BluetoothConnected,
                    checked = autoReconnect,
                    onCheckedChange = viewModel::setAutoReconnect
                )
            }
            
            // Paired Devices Section
            if (pairedDevices.isNotEmpty()) {
                SettingsSection(title = "Paired Devices") {
                    pairedDevices.forEach { device ->
                        PairedDeviceItem(
                            deviceName = device.name,
                            deviceAddress = device.address,
                            onForget = { viewModel.forgetPairedDevice(device.address) }
                        )
                    }
                }
            }
            
            // Data Management Section
            SettingsSection(title = "Data Management") {
                SettingsItem(
                    title = "Clear All Data",
                    subtitle = "Remove all paired devices and reset settings",
                    icon = Icons.Default.DeleteForever,
                    onClick = { showClearDataDialog = true },
                    isDestructive = true
                )
            }
        }
    }
    
    // Locale Selection Dialog
    if (showLocaleDialog) {
        LocaleSelectionDialog(
            currentLocale = selectedLocale,
            availableLocales = availableLocales,
            onSelectLocale = { locale ->
                viewModel.setLocale(locale)
                showLocaleDialog = false
            },
            onDismiss = { showLocaleDialog = false }
        )
    }
    
    // Clear Data Confirmation Dialog
    if (showClearDataDialog) {
        ConfirmDialog(
            title = "Clear All Data?",
            message = "This will remove all paired devices and reset all settings to defaults. This action cannot be undone.",
            confirmText = "Clear",
            cancelText = "Cancel",
            onConfirm = {
                viewModel.forgetAllPairedDevices()
                showClearDataDialog = false
            },
            onDismiss = { showClearDataDialog = false },
            isDestructive = true
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Primary,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingsItem(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    isDestructive: Boolean = false
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = androidx.compose.ui.graphics.Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isDestructive) Error else OnBackgroundSubtle,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (isDestructive) Error else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnBackgroundSubtle
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = OnBackgroundSubtle,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun SettingsToggleItem(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = OnBackgroundSubtle,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = OnBackgroundSubtle
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = OnPrimary,
                checkedTrackColor = Primary
            )
        )
    }
}

@Composable
private fun PairedDeviceItem(
    deviceName: String,
    deviceAddress: String,
    onForget: () -> Unit
) {
    var showConfirmDialog by remember { mutableStateOf(false) }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Bluetooth,
            contentDescription = null,
            tint = Primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = deviceName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = deviceAddress,
                style = MaterialTheme.typography.bodySmall,
                color = OnBackgroundSubtle
            )
        }
        TextButton(
            onClick = { showConfirmDialog = true },
            colors = ButtonDefaults.textButtonColors(
                contentColor = Error
            )
        ) {
            Text("Forget")
        }
    }
    
    if (showConfirmDialog) {
        ConfirmDialog(
            title = "Forget Device?",
            message = "This will remove $deviceName from your paired devices. You'll need to pair again to reconnect.",
            confirmText = "Forget",
            cancelText = "Cancel",
            onConfirm = {
                onForget()
                showConfirmDialog = false
            },
            onDismiss = { showConfirmDialog = false },
            isDestructive = true
        )
    }
}

@Composable
private fun LocaleSelectionDialog(
    currentLocale: String,
    availableLocales: List<Locale>,
    onSelectLocale: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Select Language",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                availableLocales.forEach { locale ->
                    val localeCode = "${locale.language}-${locale.country}"
                    val isSelected = localeCode == currentLocale
                    
                    Surface(
                        onClick = { onSelectLocale(localeCode) },
                        modifier = Modifier.fillMaxWidth(),
                        color = if (isSelected) Primary.copy(alpha = 0.1f) else androidx.compose.ui.graphics.Color.Transparent,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = locale.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                                color = if (isSelected) Primary else MaterialTheme.colorScheme.onSurface
                            )
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = Primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Primary
                )
            ) {
                Text("Close")
            }
        },
        shape = MaterialTheme.shapes.large,
        containerColor = Surface,
        tonalElevation = 8.dp
    )
}

private fun getLocaleDisplayName(localeCode: String): String {
    val parts = localeCode.split("-")
    return if (parts.size == 2) {
        val locale = Locale(parts[0], parts[1])
        locale.displayName
    } else {
        localeCode
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    Speech2PromptTheme {
        // Preview would need mock ViewModel
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("Settings Screen Preview")
        }
    }
}
