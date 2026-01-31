# Phase 7: Permission Handling

### Goal
Runtime permission management for BLE and microphone.

### Required Permissions
```xml
<!-- Bluetooth -->
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30"/>
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30"/>
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT"/>
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"/>

<!-- Microphone -->
<uses-permission android:name="android.permission.RECORD_AUDIO"/>

<!-- Location (required for BLE scanning on Android 11 and below) -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION"/>
```

### PermissionManager (service/PermissionManager.kt)
```kotlin
@Singleton
class PermissionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun hasMicrophonePermission(): Boolean
    fun hasBluetoothPermissions(): Boolean
    fun hasLocationPermission(): Boolean
    fun getMissingPermissions(): List<String>
    
    companion object {
        val BLUETOOTH_PERMISSIONS = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        
        val MICROPHONE_PERMISSIONS = arrayOf(Manifest.permission.RECORD_AUDIO)
    }
}
```

### Compose Permission Handling
```kotlin
@Composable
fun PermissionHandler(
    permissions: List<String>,
    onAllGranted: @Composable () -> Unit,
    onDenied: @Composable (missingPermissions: List<String>, onRequest: () -> Unit) -> Unit
) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> ... }
    
    // Check and request permissions
}

@Composable  
fun PermissionDeniedContent(
    missingPermissions: List<String>,
    onRequestPermissions: () -> Unit,
    onOpenSettings: () -> Unit
)
```

### Verification
- [ ] Microphone permission requested before speech
- [ ] Bluetooth permissions requested before scanning
- [ ] Rationale shown when denied once
- [ ] Settings link when permanently denied
