# Phase 15: Final Integration & Polish

## Goal
Complete integration testing, edge case handling, and polish.

## End-to-End Testing Checklist

### Happy Path
- [ ] Fresh install -> Grant permissions -> Scan -> Connect -> Pair -> Dictate -> Text appears on desktop
- [ ] Voice command "new line" -> ENTER sent -> New line on desktop
- [ ] Voice command "copy that" -> COPY sent -> Text copied on desktop
- [ ] Voice command "paste" -> PASTE sent -> Text pasted on desktop
- [ ] Voice command "select all" -> SELECT_ALL sent
- [ ] Voice command "cancel" -> Buffer cleared, nothing sent
- [ ] App restart -> Auto-reconnect to paired device
- [ ] "stop listening" -> Speech pauses
- [ ] "start listening" -> Speech resumes

### Edge Cases
- [ ] Bluetooth turned off during use -> Shows appropriate error, no crash
- [ ] Bluetooth turned back on -> Can reconnect
- [ ] App backgrounded while listening -> Pauses gracefully
- [ ] App foregrounded -> Resumes or ready to resume
- [ ] Permission denied -> Clear guidance to settings
- [ ] No internet (for Google Speech) -> Shows offline error
- [ ] Desktop disconnects -> Reconnection attempts
- [ ] Max reconnection attempts -> Shows "tap to reconnect"
- [ ] Long speech (>30s) -> Auto-restart maintains continuity
- [ ] Rapid start/stop -> No crashes or stuck states
- [ ] Low memory -> Graceful degradation
- [ ] Screen rotation -> State preserved

## Performance Optimization

### Battery

```kotlin
// Use appropriate lifecycle scope
lifecycleScope.launchWhenResumed {
    // Only run when visible
}

// Cancel speech when backgrounded
override fun onPause() {
    super.onPause()
    speechService.pauseListening()
}

// In ViewModel - respect lifecycle
class HomeViewModel @Inject constructor(
    private val speechService: SpeechService,
    private val bleService: BleService
) : ViewModel() {
    
    private var isActive = false
    
    fun onResume() {
        isActive = true
        // Resume operations
    }
    
    fun onPause() {
        isActive = false
        speechService.pauseListening()
    }
}
```

### BLE Optimization

```kotlin
// Batch writes when possible
suspend fun sendBatchedMessages(messages: List<Message>) {
    messages.forEach { message ->
        sendMessage(message)
        // Small delay between messages to avoid overwhelming BLE
        delay(50)
    }
}

// Use write without response for non-critical messages
private fun shouldUseWriteWithoutResponse(message: Message): Boolean {
    return when (message.type) {
        MessageType.HEARTBEAT -> true
        MessageType.TEXT -> false  // Need confirmation
        MessageType.COMMAND -> false  // Need confirmation
        else -> true
    }
}

// Clean disconnect
suspend fun disconnect() {
    try {
        // Send disconnect message if connected
        if (isConnected) {
            sendMessage(Message.disconnect())
            delay(100)  // Allow message to send
        }
    } finally {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }
}
```

### Memory Optimization

```kotlin
// Clear large buffers after processing
class PacketAssembler {
    private val receivedChunks = mutableMapOf<Int, ByteArray>()
    
    fun assemble(...): ByteArray? {
        // ... assembly logic
        
        // Clear after successful assembly
        receivedChunks.clear()
        return result
    }
    
    fun clearStaleChunks(maxAgeMs: Long = 30_000) {
        // Periodically clear old incomplete messages
        val now = System.currentTimeMillis()
        // Implementation depends on tracking timestamps
    }
}

// Use weak references for callbacks where appropriate
class BleService {
    private var connectionCallback: WeakReference<ConnectionCallback>? = null
    
    fun setCallback(callback: ConnectionCallback) {
        connectionCallback = WeakReference(callback)
    }
}
```

### Profiling

Use Android Studio tools:
- **Memory Profiler**: Watch for leaks, especially during screen transitions
- **CPU Profiler**: Check for main thread blocking during BLE operations
- **Energy Profiler**: Verify BLE scanning doesn't drain battery
- **Network Profiler**: If using any network features

## Polish

### Haptic Feedback

```kotlin
@Composable
fun AudioVisualizer(
    soundLevel: Float,
    isListening: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val wasListening = remember { mutableStateOf(isListening) }
    
    // Haptic on state change
    LaunchedEffect(isListening) {
        if (isListening != wasListening.value) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            wasListening.value = isListening
        }
    }
    
    Box(
        modifier = modifier
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
    ) {
        // Visualizer content
    }
}

// Command detection haptic
@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
    val haptic = LocalHapticFeedback.current
    val lastCommand by viewModel.lastCommand.collectAsStateWithLifecycle()
    
    LaunchedEffect(lastCommand) {
        lastCommand?.let {
            // Double tap haptic for command detection
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            delay(100)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
}
```

### Smooth Animations

```kotlin
@Composable
fun SoundLevelIndicator(level: Float) {
    // Smooth animation for sound level
    val animatedLevel by animateFloatAsState(
        targetValue = level,
        animationSpec = tween(durationMillis = 100, easing = LinearEasing),
        label = "soundLevel"
    )
    
    LinearProgressIndicator(
        progress = animatedLevel,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun ConnectionStatus(state: ConnectionState) {
    // Animated visibility for status changes
    AnimatedVisibility(
        visible = state == ConnectionState.CONNECTING,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Text("Connecting...")
    }
}

@Composable
fun TranscriptionDisplay(text: String) {
    // Animate content size changes
    Surface(
        modifier = Modifier.animateContentSize(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        )
    ) {
        Text(text)
    }
}
```

### Loading States

```kotlin
@Composable
fun DeviceList(
    isScanning: Boolean,
    devices: List<BleDevice>,
    onDeviceClick: (BleDevice) -> Unit
) {
    if (isScanning && devices.isEmpty()) {
        // Shimmer loading effect
        repeat(3) {
            ShimmerDeviceItem()
        }
    } else if (devices.isEmpty()) {
        Text("No devices found")
    } else {
        devices.forEach { device ->
            DeviceItem(
                device = device,
                onClick = { onDeviceClick(device) }
            )
        }
    }
}

@Composable
fun ShimmerDeviceItem() {
    val shimmerColors = listOf(
        Color.LightGray.copy(alpha = 0.6f),
        Color.LightGray.copy(alpha = 0.2f),
        Color.LightGray.copy(alpha = 0.6f)
    )
    
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )
    
    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnim - 200f, 0f),
        end = Offset(translateAnim, 0f)
    )
    
    Row(modifier = Modifier.padding(16.dp)) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(brush, CircleShape)
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Box(
                modifier = Modifier
                    .height(16.dp)
                    .width(150.dp)
                    .background(brush, RoundedCornerShape(4.dp))
            )
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .height(12.dp)
                    .width(100.dp)
                    .background(brush, RoundedCornerShape(4.dp))
            )
        }
    }
}
```

### Error Messages

```kotlin
@Composable
fun ErrorSnackbar(
    message: String?,
    onDismiss: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    
    LaunchedEffect(message) {
        message?.let {
            val result = snackbarHostState.showSnackbar(
                message = userFriendlyMessage(it),
                actionLabel = "Dismiss",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.Dismissed || result == SnackbarResult.ActionPerformed) {
                onDismiss()
            }
        }
    }
    
    SnackbarHost(hostState = snackbarHostState)
}

private fun userFriendlyMessage(error: String): String {
    return when {
        error.contains("GATT") -> "Bluetooth connection lost. Tap to reconnect."
        error.contains("permission", ignoreCase = true) -> "Permission required. Please grant in settings."
        error.contains("timeout", ignoreCase = true) -> "Connection timed out. Please try again."
        error.contains("speech", ignoreCase = true) -> "Speech recognition unavailable. Check your connection."
        else -> error
    }
}
```

## Release Checklist

### Build Configuration

```kotlin
// In build.gradle.kts
android {
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}
```

### ProGuard Rules

```proguard
# proguard-rules.pro

# Keep data classes for serialization
-keep class com.speech2prompt.data.model.** { *; }
-keep class com.speech2prompt.domain.model.** { *; }

# Keep Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Keep BLE classes
-keep class android.bluetooth.** { *; }

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
```

### Crash Reporting

```kotlin
// Example with Firebase Crashlytics
class CrashReportingTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority >= Log.ERROR) {
            FirebaseCrashlytics.getInstance().apply {
                log(message)
                t?.let { recordException(it) }
            }
        }
    }
}

// In Application.onCreate()
if (!BuildConfig.DEBUG) {
    Timber.plant(CrashReportingTree())
}
```

### Testing Matrix

| Device Category | Android Version | Test Priority |
|----------------|-----------------|---------------|
| Low-end phone  | Android 10      | High          |
| Mid-range phone| Android 12      | High          |
| Flagship phone | Android 14      | High          |
| Tablet         | Android 13      | Medium        |
| Foldable       | Android 14      | Low           |

### Accessibility

```kotlin
@Composable
fun AudioVisualizer(
    soundLevel: Float,
    isListening: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .semantics {
                contentDescription = if (isListening) {
                    "Listening. Sound level ${(soundLevel * 100).toInt()} percent. Tap to stop."
                } else {
                    "Not listening. Tap to start."
                }
                role = Role.Button
            }
            .clickable(onClick = onClick)
    ) {
        // Visual content
    }
}
```

### Final Checks

- [ ] Remove debug screens from release build (or hide behind flag)
- [ ] ProGuard/R8 rules for serialization
- [ ] Crashlytics or similar crash reporting
- [ ] Test on multiple devices (different Android versions)
- [ ] Test on different screen sizes
- [ ] Accessibility check (TalkBack, font scaling)
- [ ] App bundle size optimization
- [ ] Verify no sensitive data in logs
- [ ] Check all TODO comments resolved
- [ ] Update version code and version name
- [ ] Generate signed release APK/AAB
- [ ] Test signed release build
