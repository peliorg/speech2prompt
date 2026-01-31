package com.speech2prompt.presentation.screens.speech_test

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.speech2prompt.presentation.components.ErrorMessage
import com.speech2prompt.presentation.theme.*

/**
 * Speech test screen for isolated speech recognition testing.
 * 
 * Features:
 * - Test speech recognition in isolation (no BLE)
 * - Start/stop button
 * - Recognition results list
 * - Command detection display
 * - Sound level indicator
 * - Clear results button
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeechTestScreen(
    onNavigateBack: () -> Unit,
    viewModel: SpeechTestViewModel = hiltViewModel()
) {
    val isListening by viewModel.isListening.collectAsState()
    val soundLevel by viewModel.soundLevel.collectAsState()
    val currentText by viewModel.currentText.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val recognizedTexts by viewModel.recognizedTexts.collectAsState()
    val detectedCommands by viewModel.detectedCommands.collectAsState()
    
    val listState = rememberLazyListState()
    
    // Auto-scroll to bottom when new items are added
    LaunchedEffect(recognizedTexts.size, detectedCommands.size) {
        if (recognizedTexts.isNotEmpty() || detectedCommands.isNotEmpty()) {
            val totalItems = recognizedTexts.size + detectedCommands.size
            if (totalItems > 0) {
                listState.animateScrollToItem(totalItems - 1)
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Speech Test") },
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
                        onClick = viewModel::clearHistory,
                        enabled = recognizedTexts.isNotEmpty() || detectedCommands.isNotEmpty()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Clear"
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
            // Control Panel
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                tonalElevation = 2.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Sound Level Indicator
                    SoundLevelIndicator(
                        soundLevel = soundLevel,
                        isListening = isListening
                    )
                    
                    Spacer(Modifier.height(16.dp))
                    
                    // Current Text Display
                    if (isListening || currentText.isNotEmpty()) {
                        CurrentTextDisplay(
                            text = currentText,
                            isListening = isListening
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                    
                    // Control Button
                    Button(
                        onClick = {
                            if (isListening) {
                                viewModel.stopListening()
                            } else {
                                viewModel.startListening()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isListening) Error else Primary,
                            contentColor = OnPrimary
                        )
                    ) {
                        Icon(
                            imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (isListening) "Stop Listening" else "Start Listening",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    
                    // Error Message
                    errorMessage?.let { error ->
                        Spacer(Modifier.height(12.dp))
                        ErrorMessage(
                            message = error,
                            onRetry = viewModel::clearHistory
                        )
                    }
                }
            }
            
            // Results List
            ResultsList(
                recognizedTexts = recognizedTexts,
                detectedCommands = detectedCommands,
                listState = listState,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SoundLevelIndicator(
    soundLevel: Float,
    isListening: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.VolumeUp,
            contentDescription = "Sound Level",
            tint = if (isListening) Primary else OnBackgroundSubtle,
            modifier = Modifier.size(20.dp)
        )
        
        // Sound level bars
        repeat(10) { index ->
            val barHeight = 4.dp + (index * 2).dp
            val isActive = isListening && soundLevel >= (index / 10f)
            
            Box(
                modifier = Modifier
                    .width(8.dp)
                    .height(barHeight)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(
                        if (isActive) Primary else OnBackgroundSubtle.copy(alpha = 0.3f)
                    )
            )
        }
    }
}

@Composable
private fun CurrentTextDisplay(
    text: String,
    isListening: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Current",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = OnBackgroundSubtle
                )
                if (isListening) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Error)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Listening",
                            style = MaterialTheme.typography.labelSmall,
                            color = Error
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = text.ifEmpty { if (isListening) "Speak now..." else "No text" },
                style = MaterialTheme.typography.bodyMedium,
                color = if (text.isEmpty()) OnBackgroundSubtle else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun ResultsList(
    recognizedTexts: List<String>,
    detectedCommands: List<com.speech2prompt.domain.model.CommandCode>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier
) {
    if (recognizedTexts.isEmpty() && detectedCommands.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    tint = OnBackgroundSubtle,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "No Results Yet",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = OnBackgroundMuted
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Start listening to see recognition results here",
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Section header
            item {
                Text(
                    text = "Recognition History",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            
            // Interleave texts and commands in chronological order
            val allItems = (recognizedTexts.map { "text:$it" } + detectedCommands.map { "command:${it.code}" })
            
            items(allItems) { item ->
                if (item.startsWith("text:")) {
                    RecognizedTextItem(text = item.removePrefix("text:"))
                } else {
                    CommandItem(commandCode = item.removePrefix("command:"))
                }
            }
        }
    }
}

@Composable
private fun RecognizedTextItem(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.TextFields,
                contentDescription = "Text",
                tint = Primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Text",
                    style = MaterialTheme.typography.labelMedium,
                    color = Primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun CommandItem(commandCode: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = Secondary.copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Code,
                contentDescription = "Command",
                tint = Secondary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Command",
                    style = MaterialTheme.typography.labelMedium,
                    color = Secondary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = commandCode,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SpeechTestScreenPreview() {
    Speech2PromptTheme {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("Speech Test Screen Preview")
        }
    }
}
