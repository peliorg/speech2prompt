# Phase 9: Reusable UI Components

## Goal
Build custom Compose components for visualizers and badges matching Flutter widgets.

## Components

### 9.1 AudioVisualizer (presentation/components/AudioVisualizer.kt)
```kotlin
package com.example.speech2prompt.presentation.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun AudioVisualizer(
    soundLevel: Float, // 0.0 to 1.0
    isActive: Boolean,
    modifier: Modifier = Modifier,
    activeColor: Color = Color.Red,
    inactiveColor: Color = MaterialTheme.colorScheme.primary,
    size: Dp = 200.dp,
    onClick: () -> Unit = {}
) {
    val color = if (isActive) activeColor else inactiveColor
    
    // Pulse animation
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    
    // Wave animation
    val waveProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing)
        ),
        label = "waveProgress"
    )
    
    Box(
        modifier = modifier
            .size(size)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // Ripple waves (when active)
        if (isActive) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRipples(waveProgress, color, soundLevel)
            }
        }
        
        // Sound level rings (3 rings)
        repeat(3) { index ->
            val delay = index * 0.15f
            val level = (soundLevel - delay).coerceIn(0f, 1f)
            val ringSize = size * 0.6f + (level * size * 0.4f)
            
            Box(
                modifier = Modifier
                    .size(ringSize)
                    .border(
                        width = 2.dp,
                        color = color.copy(alpha = 0.2f + level * 0.3f),
                        shape = CircleShape
                    )
            )
        }
        
        // Main circle with pulse
        Box(
            modifier = Modifier
                .size(size * 0.5f)
                .scale(if (isActive) pulseScale else 1f)
                .shadow(
                    elevation = (20 + soundLevel * 30).dp,
                    shape = CircleShape,
                    ambientColor = color,
                    spotColor = color
                )
                .background(color, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isActive) Icons.Default.Mic else Icons.Default.MicNone,
                contentDescription = if (isActive) "Listening" else "Not listening",
                tint = Color.White,
                modifier = Modifier.size(size * 0.2f)
            )
        }
    }
}

private fun DrawScope.drawRipples(
    waveProgress: Float,
    color: Color,
    soundLevel: Float
) {
    val center = Offset(size.width / 2, size.height / 2)
    val maxRadius = size.minDimension / 2
    
    // Draw 3 expanding ripples
    repeat(3) { index ->
        val rippleProgress = (waveProgress + index * 0.33f) % 1f
        val radius = maxRadius * rippleProgress * (0.8f + soundLevel * 0.4f)
        val alpha = (1f - rippleProgress) * 0.3f * (0.5f + soundLevel * 0.5f)
        
        drawCircle(
            color = color.copy(alpha = alpha),
            radius = radius,
            center = center,
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun AudioVisualizerPreview() {
    AudioVisualizer(
        soundLevel = 0.6f,
        isActive = true
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun AudioVisualizerInactivePreview() {
    AudioVisualizer(
        soundLevel = 0f,
        isActive = false
    )
}
```

### 9.2 WaveformVisualizer (presentation/components/WaveformVisualizer.kt)
```kotlin
package com.example.speech2prompt.presentation.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sin

@Composable
fun WaveformVisualizer(
    soundLevel: Float,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    barCount: Int = 20,
    height: Dp = 60.dp
) {
    Row(
        modifier = modifier.height(height),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(barCount) { index ->
            val position = abs(index - barCount / 2f) / (barCount / 2f)
            val baseHeight = 0.3f + (1 - position) * 0.5f
            val variance = sin(index * 0.5f + soundLevel * 10) * 0.3f
            val heightFactor = if (isActive) {
                (baseHeight + variance + soundLevel * 0.5f).coerceIn(0.1f, 1f)
            } else 0.1f
            
            Box(
                modifier = Modifier
                    .padding(horizontal = 1.dp)
                    .width(4.dp)
                    .fillMaxHeight(heightFactor)
                    .background(
                        color = color.copy(
                            alpha = if (isActive) 0.5f + soundLevel * 0.5f else 0.3f
                        ),
                        shape = RoundedCornerShape(2.dp)
                    )
                    .animateContentSize()
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun WaveformVisualizerActivePreview() {
    WaveformVisualizer(
        soundLevel = 0.7f,
        isActive = true
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun WaveformVisualizerInactivePreview() {
    WaveformVisualizer(
        soundLevel = 0f,
        isActive = false
    )
}
```

### 9.3 ConnectionBadge (presentation/components/ConnectionBadge.kt)
```kotlin
package com.example.speech2prompt.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.speech2prompt.domain.model.BtConnectionState
import com.example.speech2prompt.presentation.theme.*

@Composable
fun ConnectionBadge(
    state: BtConnectionState,
    deviceName: String? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    val color = when (state) {
        BtConnectionState.CONNECTED -> Connected
        BtConnectionState.CONNECTING, 
        BtConnectionState.RECONNECTING,
        BtConnectionState.AWAITING_PAIRING -> Connecting
        BtConnectionState.FAILED -> Error
        BtConnectionState.DISCONNECTED -> Disconnected
    }
    
    val icon: ImageVector = when (state) {
        BtConnectionState.CONNECTED -> Icons.Default.BluetoothConnected
        BtConnectionState.CONNECTING, 
        BtConnectionState.RECONNECTING -> Icons.Default.BluetoothSearching
        BtConnectionState.AWAITING_PAIRING -> Icons.Default.Lock
        BtConnectionState.FAILED -> Icons.Default.BluetoothDisabled
        BtConnectionState.DISCONNECTED -> Icons.Default.Bluetooth
    }
    
    val text = state.displayText
    
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = color.copy(alpha = 0.15f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text = text,
                    color = color,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp
                )
                if (deviceName != null && state.isConnected) {
                    Text(
                        text = deviceName,
                        color = color.copy(alpha = 0.7f),
                        fontSize = 11.sp
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            if (state.isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = color
                )
            } else {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = color.copy(alpha = 0.7f)
                )
            }
        }
    }
}

// Extension properties for BtConnectionState
val BtConnectionState.displayText: String
    get() = when (this) {
        BtConnectionState.CONNECTED -> "Connected"
        BtConnectionState.CONNECTING -> "Connecting..."
        BtConnectionState.RECONNECTING -> "Reconnecting..."
        BtConnectionState.AWAITING_PAIRING -> "Awaiting Pairing"
        BtConnectionState.FAILED -> "Connection Failed"
        BtConnectionState.DISCONNECTED -> "Disconnected"
    }

val BtConnectionState.isConnected: Boolean
    get() = this == BtConnectionState.CONNECTED

val BtConnectionState.isConnecting: Boolean
    get() = this in listOf(
        BtConnectionState.CONNECTING,
        BtConnectionState.RECONNECTING,
        BtConnectionState.AWAITING_PAIRING
    )

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun ConnectionBadgeConnectedPreview() {
    ConnectionBadge(
        state = BtConnectionState.CONNECTED,
        deviceName = "HC-05 Module"
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun ConnectionBadgeConnectingPreview() {
    ConnectionBadge(
        state = BtConnectionState.CONNECTING
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun ConnectionBadgeDisconnectedPreview() {
    ConnectionBadge(
        state = BtConnectionState.DISCONNECTED
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun ConnectionBadgeFailedPreview() {
    ConnectionBadge(
        state = BtConnectionState.FAILED
    )
}
```

### 9.4 TranscriptionDisplay (presentation/components/TranscriptionDisplay.kt)
```kotlin
package com.example.speech2prompt.presentation.components

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.speech2prompt.presentation.theme.OnBackgroundSubtle

@Composable
fun TranscriptionDisplay(
    text: String,
    isListening: Boolean,
    modifier: Modifier = Modifier,
    placeholder: String = "Your speech will appear here..."
) {
    val hasText = text.isNotEmpty()
    
    Surface(
        modifier = modifier.padding(horizontal = 24.dp),
        shape = RoundedCornerShape(16.dp),
        color = if (hasText) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        } else {
            Color.White.copy(alpha = 0.05f)
        },
        border = BorderStroke(
            1.dp,
            if (hasText) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
            else Color.White.copy(alpha = 0.1f)
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isListening) Icons.Default.Hearing else Icons.Default.TextFields,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = OnBackgroundSubtle
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isListening) "Listening..." else "Last transcription",
                    color = OnBackgroundSubtle,
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.height(12.dp))
            AnimatedContent(
                targetState = if (hasText) text else placeholder,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "transcriptionContent"
            ) { displayText ->
                Text(
                    text = displayText,
                    style = if (hasText) {
                        MaterialTheme.typography.bodyLarge.copy(color = Color.White)
                    } else {
                        MaterialTheme.typography.bodyMedium.copy(
                            color = OnBackgroundSubtle,
                            fontStyle = FontStyle.Italic
                        )
                    },
                    textAlign = TextAlign.Center,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun TranscriptionDisplayEmptyPreview() {
    TranscriptionDisplay(
        text = "",
        isListening = false
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun TranscriptionDisplayListeningPreview() {
    TranscriptionDisplay(
        text = "",
        isListening = true
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun TranscriptionDisplayWithTextPreview() {
    TranscriptionDisplay(
        text = "Hello, this is a test transcription that shows how the component looks with actual text content.",
        isListening = false
    )
}
```

### 9.5 StatusIndicator (presentation/components/StatusIndicator.kt)
```kotlin
package com.example.speech2prompt.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.speech2prompt.presentation.theme.*

enum class StatusType {
    SUCCESS,
    WARNING,
    ERROR,
    INFO,
    NEUTRAL
}

@Composable
fun StatusIndicator(
    type: StatusType,
    label: String,
    modifier: Modifier = Modifier,
    showPulse: Boolean = false,
    dotSize: Dp = 8.dp
) {
    val color by animateColorAsState(
        targetValue = when (type) {
            StatusType.SUCCESS -> Connected
            StatusType.WARNING -> Connecting
            StatusType.ERROR -> Error
            StatusType.INFO -> MaterialTheme.colorScheme.primary
            StatusType.NEUTRAL -> Disconnected
        },
        label = "statusColor"
    )
    
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(dotSize)
                .scale(if (showPulse) pulseScale else 1f)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            color = color,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun StatusIndicatorSuccessPreview() {
    StatusIndicator(
        type = StatusType.SUCCESS,
        label = "Connected"
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun StatusIndicatorWarningPreview() {
    StatusIndicator(
        type = StatusType.WARNING,
        label = "Connecting...",
        showPulse = true
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun StatusIndicatorErrorPreview() {
    StatusIndicator(
        type = StatusType.ERROR,
        label = "Error"
    )
}
```

### 9.6 ActionButton (presentation/components/ActionButton.kt)
```kotlin
package com.example.speech2prompt.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send

enum class ActionButtonStyle {
    PRIMARY,
    SECONDARY,
    OUTLINE,
    DESTRUCTIVE
}

@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ActionButtonStyle = ActionButtonStyle.PRIMARY,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false
) {
    val colors = when (style) {
        ActionButtonStyle.PRIMARY -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
        ActionButtonStyle.SECONDARY -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary
        )
        ActionButtonStyle.OUTLINE -> ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.primary
        )
        ActionButtonStyle.DESTRUCTIVE -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError
        )
    }
    
    val border = if (style == ActionButtonStyle.OUTLINE) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
    } else null
    
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        enabled = enabled && !loading,
        colors = colors,
        shape = RoundedCornerShape(12.dp),
        border = border
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = LocalContentColor.current
            )
            Spacer(Modifier.width(8.dp))
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(text)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun ActionButtonPrimaryPreview() {
    ActionButton(
        text = "Send",
        onClick = {},
        icon = Icons.Default.Send
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun ActionButtonOutlinePreview() {
    ActionButton(
        text = "Cancel",
        onClick = {},
        style = ActionButtonStyle.OUTLINE
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun ActionButtonLoadingPreview() {
    ActionButton(
        text = "Sending...",
        onClick = {},
        loading = true
    )
}
```

### 9.7 HistoryItem (presentation/components/HistoryItem.kt)
```kotlin
package com.example.speech2prompt.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.speech2prompt.domain.model.TranscriptionRecord
import com.example.speech2prompt.presentation.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HistoryItem(
    record: TranscriptionRecord,
    onClick: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = Color.White.copy(alpha = 0.05f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Timestamp
                Text(
                    text = formatTimestamp(record.timestamp),
                    color = OnBackgroundSubtle,
                    fontSize = 12.sp
                )
                
                // Sent status
                if (record.sentToBluetooth) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.BluetoothConnected,
                            contentDescription = "Sent via Bluetooth",
                            modifier = Modifier.size(14.dp),
                            tint = Connected
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Sent",
                            color = Connected,
                            fontSize = 11.sp
                        )
                    }
                }
            }
            
            Spacer(Modifier.height(8.dp))
            
            // Transcription text
            Text(
                text = record.text,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            
            Spacer(Modifier.height(12.dp))
            
            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(
                    onClick = onCopy,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        modifier = Modifier.size(18.dp),
                        tint = OnBackgroundSubtle
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        modifier = Modifier.size(18.dp),
                        tint = Error.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val instant = Instant.ofEpochMilli(timestamp)
    val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a")
        .withZone(ZoneId.systemDefault())
    return formatter.format(instant)
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun HistoryItemPreview() {
    HistoryItem(
        record = TranscriptionRecord(
            id = 1,
            text = "This is a sample transcription that demonstrates how the history item looks with actual content.",
            timestamp = System.currentTimeMillis(),
            sentToBluetooth = true
        ),
        onClick = {},
        onCopy = {},
        onDelete = {}
    )
}
```

### 9.8 EmptyState (presentation/components/EmptyState.kt)
```kotlin
package com.example.speech2prompt.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.speech2prompt.presentation.theme.OnBackgroundSubtle

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = OnBackgroundSubtle.copy(alpha = 0.5f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = OnBackgroundSubtle,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = OnBackgroundSubtle.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
        if (action != null) {
            Spacer(Modifier.height(24.dp))
            action()
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun EmptyStatePreview() {
    EmptyState(
        icon = Icons.Default.History,
        title = "No History Yet",
        message = "Your transcription history will appear here after you start recording."
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun EmptyStateWithActionPreview() {
    EmptyState(
        icon = Icons.Default.History,
        title = "No History Yet",
        message = "Start recording to see your transcriptions here."
    ) {
        ActionButton(
            text = "Start Recording",
            onClick = {}
        )
    }
}
```

## Required Theme Colors

Add these colors to your theme (presentation/theme/Color.kt):
```kotlin
// Status colors
val Connected = Color(0xFF4CAF50)
val Connecting = Color(0xFFFFC107)
val Disconnected = Color(0xFF9E9E9E)
val Error = Color(0xFFF44336)

// Text colors
val OnBackgroundSubtle = Color(0xFFB0B0B0)
```

## Verification
- [ ] AudioVisualizer pulses when active
- [ ] Sound level affects ring sizes and glow
- [ ] WaveformVisualizer bars respond to sound
- [ ] ConnectionBadge shows correct state/color/icon
- [ ] TranscriptionDisplay animates text changes
- [ ] StatusIndicator shows pulse animation when enabled
- [ ] ActionButton shows loading state correctly
- [ ] HistoryItem displays formatted timestamp
- [ ] EmptyState renders with optional action
- [ ] All components work in Preview

## Estimated Time: 1-2 days
