package com.speech2prompt.presentation.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speech2prompt.data.model.PairedDevice
import com.speech2prompt.data.repository.PreferencesRepository
import com.speech2prompt.service.ble.BleManager
import com.speech2prompt.service.speech.SpeechRecognitionManager
import com.speech2prompt.util.crypto.SecureStorageManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * ViewModel for the Settings screen.
 * Manages app preferences and configuration.
 * 
 * Features:
 * - Speech recognition settings
 * - Connection settings
 * - Paired devices management
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val speechRecognitionManager: SpeechRecognitionManager,
    private val bleManager: BleManager,
    private val secureStorage: SecureStorageManager
) : ViewModel() {
    
    // ==================== Speech Settings ====================
    
    val selectedLocale: StateFlow<String> = preferencesRepository.selectedLocale
    val availableLocales: StateFlow<List<Locale>> = speechRecognitionManager.availableLocales
    val showPartialResults: StateFlow<Boolean> = preferencesRepository.showPartialResults
    val keepScreenOn: StateFlow<Boolean> = preferencesRepository.keepScreenOn
    
    // ==================== Connection Settings ====================
    
    val autoReconnect: StateFlow<Boolean> = preferencesRepository.autoReconnect
    
    // ==================== Paired Devices ====================
    
    private val _pairedDevices = MutableStateFlow<List<PairedDevice>>(emptyList())
    val pairedDevices: StateFlow<List<PairedDevice>> = _pairedDevices.asStateFlow()
    
    // ==================== Initialization ====================
    
    init {
        loadPairedDevices()
    }
    
    // ==================== Speech Settings Actions ====================
    
    fun setLocale(locale: String) {
        preferencesRepository.setSelectedLocale(locale)
        speechRecognitionManager.setLocale(locale)
    }
    
    fun setShowPartialResults(enabled: Boolean) {
        preferencesRepository.setShowPartialResults(enabled)
    }
    
    fun setKeepScreenOn(enabled: Boolean) {
        preferencesRepository.setKeepScreenOn(enabled)
    }
    
    // ==================== Connection Actions ====================
    
    fun setAutoReconnect(enabled: Boolean) {
        preferencesRepository.setAutoReconnect(enabled)
    }
    
    // ==================== Paired Devices Actions ====================
    
    fun forgetPairedDevice(address: String) {
        viewModelScope.launch {
            secureStorage.removePairedDevice(address)
            loadPairedDevices()
        }
    }
    
    fun forgetAllPairedDevices() {
        viewModelScope.launch {
            val devices = _pairedDevices.value
            devices.forEach { device ->
                secureStorage.removePairedDevice(device.address)
            }
            loadPairedDevices()
        }
    }
    
    // ==================== Private Methods ====================
    
    private fun loadPairedDevices() {
        viewModelScope.launch {
            // Get all paired devices from secure storage
            val result = secureStorage.getPairedDevices()
            result.onSuccess { devices ->
                _pairedDevices.value = devices
            }.onFailure {
                _pairedDevices.value = emptyList()
            }
        }
    }
}
