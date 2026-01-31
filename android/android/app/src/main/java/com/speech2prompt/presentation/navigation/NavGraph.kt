package com.speech2prompt.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.speech2prompt.presentation.screens.bluetooth_test.BluetoothTestScreen
import com.speech2prompt.presentation.screens.connection.ConnectionScreen
import com.speech2prompt.presentation.screens.home.HomeScreen
import com.speech2prompt.presentation.screens.settings.SettingsScreen
import com.speech2prompt.presentation.screens.speech_test.SpeechTestScreen

/**
 * Main navigation graph for the Speech2Prompt app
 * Defines all navigation routes and their associated screens
 */
@Composable
fun NavGraph(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: String = Screen.Home.route
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        // Home Screen - Main screen with speech recognition
        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateToConnection = { navController.navigateToConnection() },
                onNavigateToSettings = { navController.navigateToSettings() },
                viewModel = hiltViewModel()
            )
        }
        
        // Connection Screen - BLE device scanning and pairing
        composable(Screen.Connection.route) {
            ConnectionScreen(
                onNavigateBack = { navController.popBackStack() },
                viewModel = hiltViewModel()
            )
        }
        
        // Settings Screen - App preferences
        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                viewModel = hiltViewModel()
            )
        }
        
        // Speech Test Screen - Test speech recognition
        composable(Screen.SpeechTest.route) {
            SpeechTestScreen(
                onNavigateBack = { navController.popBackStack() },
                viewModel = hiltViewModel()
            )
        }
        
        // Bluetooth Test Screen - Test BLE connection
        composable(Screen.BluetoothTest.route) {
            BluetoothTestScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToConnection = { navController.navigateToConnection() },
                viewModel = hiltViewModel()
            )
        }
    }
}

/**
 * Extension functions for NavController navigation
 */
fun NavHostController.navigateToHome() {
    navigate(Screen.Home.route) {
        popUpTo(Screen.Home.route) { inclusive = true }
    }
}

fun NavHostController.navigateToConnection() {
    navigate(Screen.Connection.route)
}

fun NavHostController.navigateToSettings() {
    navigate(Screen.Settings.route)
}

fun NavHostController.navigateToSpeechTest() {
    navigate(Screen.SpeechTest.route)
}

fun NavHostController.navigateToBluetoothTest() {
    navigate(Screen.BluetoothTest.route)
}

fun NavHostController.navigateToHistory() {
    navigate(Screen.History.route)
}

fun NavHostController.navigateToPermissions() {
    navigate(Screen.Permissions.route)
}
