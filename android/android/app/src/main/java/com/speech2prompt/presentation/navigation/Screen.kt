package com.speech2prompt.presentation.navigation

/**
 * Sealed class representing all navigation destinations in the app
 * Each screen has a route string used by Compose Navigation
 */
sealed class Screen(val route: String) {
    /**
     * Home screen - Main screen with speech recognition start/stop
     */
    object Home : Screen("home")
    
    /**
     * Connection screen - BLE device scanning and pairing
     */
    object Connection : Screen("connection")
    
    /**
     * Settings screen - App preferences and configuration
     */
    object Settings : Screen("settings")
    
    /**
     * Speech Test screen - Test speech recognition functionality
     */
    object SpeechTest : Screen("speech_test")
    
    /**
     * Bluetooth Test screen - Test BLE connection functionality
     */
    object BluetoothTest : Screen("bluetooth_test")
    
    /**
     * History screen - View transcription history
     */
    object History : Screen("history")
    
    /**
     * Permissions screen - Request and manage app permissions
     */
    object Permissions : Screen("permissions")
    
    companion object {
        /**
         * Get all screen routes for navigation graph setup
         * Uses lazy initialization to avoid class initialization order issues
         */
        val allRoutes: List<String> by lazy {
            listOf(
                Home.route,
                Connection.route,
                Settings.route,
                SpeechTest.route,
                BluetoothTest.route,
                History.route,
                Permissions.route
            )
        }
        
        /**
         * Find Screen by route string
         */
        fun fromRoute(route: String?): Screen {
            return when (route) {
                Home.route -> Home
                Connection.route -> Connection
                Settings.route -> Settings
                SpeechTest.route -> SpeechTest
                BluetoothTest.route -> BluetoothTest
                History.route -> History
                Permissions.route -> Permissions
                else -> Home
            }
        }
    }
}
