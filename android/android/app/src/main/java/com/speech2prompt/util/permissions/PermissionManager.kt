package com.speech2prompt.util.permissions

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages runtime permissions for the app.
 * Handles checking, requesting, and monitoring permission state across different Android versions.
 *
 * Key responsibilities:
 * - Check current permission status
 * - Request permissions using Activity Result API
 * - Detect "Don't ask again" state
 * - Provide app settings intent for permanently denied permissions
 * - Handle API level variations (API 31+ vs older)
 */
@Singleton
class PermissionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    /**
     * Checks if a specific permission is granted.
     *
     * @param permission The permission to check
     * @return true if the permission is granted, false otherwise
     */
    fun isPermissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Checks if all specified permissions are granted.
     *
     * @param permissions List of permissions to check
     * @return true if all permissions are granted, false otherwise
     */
    fun arePermissionsGranted(permissions: List<String>): Boolean {
        return permissions.all { isPermissionGranted(it) }
    }
    
    /**
     * Checks if all Bluetooth permissions are granted.
     * The required permissions vary by API level:
     * - API 31+: BLUETOOTH_SCAN, BLUETOOTH_CONNECT
     * - API < 31: ACCESS_FINE_LOCATION (required for BLE scanning)
     *
     * @return true if all required Bluetooth permissions are granted
     */
    fun hasBluetoothPermissions(): Boolean {
        val requiredPermissions = PermissionState.getRequiredBluetoothPermissions()
        val locationPermissions = PermissionState.getRequiredLocationPermissions()
        return arePermissionsGranted(requiredPermissions + locationPermissions)
    }
    
    /**
     * Checks if audio recording permission is granted.
     *
     * @return true if RECORD_AUDIO permission is granted
     */
    fun hasAudioPermission(): Boolean {
        val requiredPermissions = PermissionState.getRequiredAudioPermissions()
        return arePermissionsGranted(requiredPermissions)
    }
    
    /**
     * Checks if location permissions are granted.
     * Only required for BLE scanning on API < 31.
     *
     * @return true if location permissions are granted (or not required)
     */
    fun hasLocationPermission(): Boolean {
        val requiredPermissions = PermissionState.getRequiredLocationPermissions()
        return requiredPermissions.isEmpty() || arePermissionsGranted(requiredPermissions)
    }
    
    /**
     * Checks if all required permissions for the app are granted.
     *
     * @return true if all required permissions are granted
     */
    fun hasAllRequiredPermissions(): Boolean {
        val allPermissions = PermissionState.getAllRequiredPermissions()
        return arePermissionsGranted(allPermissions)
    }
    
    /**
     * Gets the current permission state for all app permissions.
     *
     * @param activity The activity to check "should show rationale" state
     * @return PermissionState containing the status of all permissions
     */
    fun getPermissionState(activity: Activity?): PermissionState {
        val bluetoothPermissions = PermissionState.getRequiredBluetoothPermissions()
        val audioPermissions = PermissionState.getRequiredAudioPermissions()
        val locationPermissions = PermissionState.getRequiredLocationPermissions()
        
        return PermissionState(
            bluetooth = bluetoothPermissions.associateWith { getPermissionStatus(it, activity) },
            audio = audioPermissions.associateWith { getPermissionStatus(it, activity) },
            location = locationPermissions.associateWith { getPermissionStatus(it, activity) }
        )
    }
    
    /**
     * Gets the status of a single permission.
     *
     * @param permission The permission to check
     * @param activity The activity to check "should show rationale" state
     * @return PermissionStatus indicating whether the permission is granted, denied, or permanently denied
     */
    private fun getPermissionStatus(permission: String, activity: Activity?): PermissionStatus {
        return when {
            isPermissionGranted(permission) -> {
                PermissionStatus.GRANTED
            }
            
            activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, permission) -> {
                // User has denied the permission before but can still be asked
                PermissionStatus.DENIED
            }
            
            else -> {
                // Either first request or permanently denied
                // We need to check if permission was requested before
                val wasRequestedBefore = wasPermissionRequestedBefore(permission)
                if (wasRequestedBefore) {
                    PermissionStatus.DENIED_PERMANENTLY
                } else {
                    PermissionStatus.DENIED
                }
            }
        }
    }
    
    /**
     * Checks if a permission was requested before.
     * This is used to distinguish between "never asked" and "don't ask again" states.
     *
     * @param permission The permission to check
     * @return true if the permission was requested before
     */
    private fun wasPermissionRequestedBefore(permission: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean("permission_requested_$permission", false)
    }
    
    /**
     * Marks a permission as having been requested.
     * This is called after requesting a permission to track its state.
     *
     * @param permission The permission that was requested
     */
    fun markPermissionAsRequested(permission: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("permission_requested_$permission", true).apply()
    }
    
    /**
     * Marks multiple permissions as having been requested.
     *
     * @param permissions List of permissions that were requested
     */
    fun markPermissionsAsRequested(permissions: List<String>) {
        permissions.forEach { markPermissionAsRequested(it) }
    }
    
    /**
     * Gets a list of missing permissions (not granted).
     *
     * @return List of permissions that are not granted
     */
    fun getMissingPermissions(): List<String> {
        val allPermissions = PermissionState.getAllRequiredPermissions()
        return allPermissions.filter { !isPermissionGranted(it) }
    }
    
    /**
     * Gets a list of missing Bluetooth permissions.
     *
     * @return List of Bluetooth permissions that are not granted
     */
    fun getMissingBluetoothPermissions(): List<String> {
        val bluetoothPermissions = PermissionState.getRequiredBluetoothPermissions()
        val locationPermissions = PermissionState.getRequiredLocationPermissions()
        return (bluetoothPermissions + locationPermissions).filter { !isPermissionGranted(it) }
    }
    
    /**
     * Gets a list of missing audio permissions.
     *
     * @return List of audio permissions that are not granted
     */
    fun getMissingAudioPermissions(): List<String> {
        val audioPermissions = PermissionState.getRequiredAudioPermissions()
        return audioPermissions.filter { !isPermissionGranted(it) }
    }
    
    /**
     * Creates an intent to open the app's settings page.
     * This is useful when permissions are permanently denied.
     *
     * @return Intent to open app settings
     */
    fun createAppSettingsIntent(): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }
    
    /**
     * Opens the app's settings page.
     * This is useful when permissions are permanently denied.
     */
    fun openAppSettings() {
        context.startActivity(createAppSettingsIntent())
    }
    
    /**
     * Creates a permission launcher for requesting multiple permissions.
     * This uses the Activity Result API which is the modern way to request permissions.
     *
     * Usage in a ComponentActivity:
     * ```
     * val launcher = permissionManager.createPermissionLauncher(this) { results ->
     *     // Handle permission results
     * }
     * launcher.launch(permissionsToRequest)
     * ```
     *
     * @param activity The ComponentActivity to register the launcher with
     * @param onResult Callback to handle permission results
     * @return ActivityResultLauncher for requesting permissions
     */
    fun createPermissionLauncher(
        activity: ComponentActivity,
        onResult: (Map<String, Boolean>) -> Unit
    ): ActivityResultLauncher<Array<String>> {
        return activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            // Mark all requested permissions as having been requested
            markPermissionsAsRequested(results.keys.toList())
            onResult(results)
        }
    }
    
    /**
     * Checks if the device supports Bluetooth LE.
     *
     * @return true if Bluetooth LE is supported
     */
    fun isBluetoothLeSupported(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    }
    
    /**
     * Checks if the device has a microphone.
     *
     * @return true if a microphone is available
     */
    fun hasMicrophone(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
    }
    
    companion object {
        /**
         * SharedPreferences name for storing permission request history.
         */
        private const val PREFS_NAME = "permission_manager_prefs"
        
        /**
         * Gets all Bluetooth permissions required for the current API level.
         */
        fun getRequiredBluetoothPermissions(): List<String> {
            return PermissionState.getRequiredBluetoothPermissions()
        }
        
        /**
         * Gets all audio permissions required.
         */
        fun getRequiredAudioPermissions(): List<String> {
            return PermissionState.getRequiredAudioPermissions()
        }
        
        /**
         * Gets all location permissions required for the current API level.
         */
        fun getRequiredLocationPermissions(): List<String> {
            return PermissionState.getRequiredLocationPermissions()
        }
        
        /**
         * Gets all permissions required by the app.
         */
        fun getAllRequiredPermissions(): List<String> {
            return PermissionState.getAllRequiredPermissions()
        }
    }
}
