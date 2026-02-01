package com.speech2prompt.util.permissions

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build

/**
 * Provides user-friendly rationale messages for permission requests.
 * These messages explain why the app needs each permission and what functionality
 * will be unavailable without it.
 */
object PermissionRationale {
    
    /**
     * Gets the rationale message for a specific permission.
     *
     * @param permission The permission to get rationale for
     * @return User-friendly explanation of why the permission is needed
     */
    fun getRationale(permission: String): String {
        return when (permission) {
            Manifest.permission.BLUETOOTH_SCAN ->
                "Bluetooth scanning is required to discover and connect to your Speech2Prompt device."
            
            Manifest.permission.BLUETOOTH_CONNECT ->
                "Bluetooth connection permission is required to communicate with your Speech2Prompt device."
            
            Manifest.permission.RECORD_AUDIO ->
                "Microphone access is required to capture your voice commands and send them to your computer."
            
            Manifest.permission.ACCESS_FINE_LOCATION ->
                "Location permission is required for Bluetooth scanning on your device's Android version. " +
                "This app does not track your location."
            
            Manifest.permission.ACCESS_COARSE_LOCATION ->
                "Location permission is required for Bluetooth scanning on your device's Android version. " +
                "This app does not track your location."
            
            Manifest.permission.BLUETOOTH ->
                "Bluetooth permission is required to communicate with your Speech2Prompt device."
            
            Manifest.permission.BLUETOOTH_ADMIN ->
                "Bluetooth administration permission is required to manage connections with your Speech2Prompt device."
            
            else ->
                "This permission is required for the app to function properly."
        }
    }
    
    /**
     * Gets the rationale message for a permission group.
     *
     * @param group The permission group to get rationale for
     * @return User-friendly explanation of why the permission group is needed
     */
    fun getGroupRationale(group: PermissionGroup): String {
        return when (group) {
            PermissionGroup.BLUETOOTH -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    "Bluetooth permissions are required to discover and connect to your Speech2Prompt device."
                } else {
                    "Location permission is required for Bluetooth scanning on your device's Android version. " +
                    "This app does not track your location - it's only needed for discovering Bluetooth devices."
                }
            }
            
            PermissionGroup.AUDIO ->
                "Microphone permission is required to capture your voice commands and send them to your computer."
            
            PermissionGroup.LOCATION ->
                "Location permission is required for Bluetooth scanning on your device's Android version. " +
                "This app does not track your location - it's only needed for discovering Bluetooth devices."
        }
    }
    
    /**
     * Gets the message to display when permissions are denied.
     *
     * @param permissions List of denied permissions
     * @return Message explaining what functionality will be unavailable
     */
    @SuppressLint("InlinedApi")
    fun getDeniedMessage(permissions: List<String>): String {
        val hasBluetooth = permissions.any {
            it == Manifest.permission.BLUETOOTH_SCAN ||
            it == Manifest.permission.BLUETOOTH_CONNECT ||
            it == Manifest.permission.ACCESS_FINE_LOCATION
        }
        val hasAudio = permissions.contains(Manifest.permission.RECORD_AUDIO)
        
        return when {
            hasBluetooth && hasAudio ->
                "Without Bluetooth and microphone permissions, this app cannot connect to your device or capture voice commands."
            
            hasBluetooth ->
                "Without Bluetooth permissions, this app cannot discover or connect to your Speech2Prompt device."
            
            hasAudio ->
                "Without microphone permission, this app cannot capture your voice commands."
            
            else ->
                "Some functionality may be unavailable without these permissions."
        }
    }
    
    /**
     * Gets the message to display when permissions are permanently denied.
     *
     * @param permissions List of permanently denied permissions
     * @return Message explaining how to enable permissions in settings
     */
    @SuppressLint("InlinedApi")
    fun getPermanentlyDeniedMessage(permissions: List<String>): String {
        val hasBluetooth = permissions.any {
            it == Manifest.permission.BLUETOOTH_SCAN ||
            it == Manifest.permission.BLUETOOTH_CONNECT ||
            it == Manifest.permission.ACCESS_FINE_LOCATION
        }
        val hasAudio = permissions.contains(Manifest.permission.RECORD_AUDIO)
        
        val permissionNames = when {
            hasBluetooth && hasAudio -> "Bluetooth and microphone"
            hasBluetooth -> "Bluetooth"
            hasAudio -> "Microphone"
            else -> "Required"
        }
        
        return "$permissionNames permissions have been permanently denied. " +
               "Please enable them in the app settings to use this app."
    }
    
    /**
     * Gets the title for the permission settings dialog.
     */
    fun getSettingsDialogTitle(): String {
        return "Permissions Required"
    }
    
    /**
     * Gets the message for the permission settings dialog.
     *
     * @param permissions List of permanently denied permissions
     * @return Message to display in the settings dialog
     */
    fun getSettingsDialogMessage(permissions: List<String>): String {
        return getPermanentlyDeniedMessage(permissions) + "\n\n" +
               "Tap 'Open Settings' below to manage app permissions."
    }
    
    /**
     * Gets the settings button text.
     */
    fun getSettingsButtonText(): String {
        return "Open Settings"
    }
    
    /**
     * Gets the cancel button text for permission dialogs.
     */
    fun getCancelButtonText(): String {
        return "Not Now"
    }
    
    /**
     * Gets the retry button text for permission requests.
     */
    fun getRetryButtonText(): String {
        return "Grant Permissions"
    }
    
    /**
     * Gets a short title for a permission group.
     *
     * @param group The permission group
     * @return Short title for the group
     */
    fun getGroupTitle(group: PermissionGroup): String {
        return when (group) {
            PermissionGroup.BLUETOOTH -> "Bluetooth Access"
            PermissionGroup.AUDIO -> "Microphone Access"
            PermissionGroup.LOCATION -> "Location Access"
        }
    }
    
    /**
     * Gets a description of what the app does with each permission.
     * This is useful for permission request screens.
     */
    fun getPermissionUsageDescription(permission: String): String {
        return when (permission) {
            Manifest.permission.BLUETOOTH_SCAN ->
                "Discover nearby Speech2Prompt devices"
            
            Manifest.permission.BLUETOOTH_CONNECT ->
                "Connect and communicate with your device"
            
            Manifest.permission.RECORD_AUDIO ->
                "Capture voice commands to send to your computer"
            
            Manifest.permission.ACCESS_FINE_LOCATION ->
                "Enable Bluetooth device discovery (Android requirement)"
            
            Manifest.permission.ACCESS_COARSE_LOCATION ->
                "Enable Bluetooth device discovery (Android requirement)"
            
            else ->
                "Required for app functionality"
        }
    }
}
