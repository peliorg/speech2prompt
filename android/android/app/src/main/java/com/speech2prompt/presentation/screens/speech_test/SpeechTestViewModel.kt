package com.speech2prompt.presentation.screens.speech_test

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speech2prompt.domain.model.CommandCode
import com.speech2prompt.service.speech.SpeechRecognitionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Speech Test screen.
 * Provides isolated testing of speech recognition without BLE dependency.
 * 
 * Features:
 * - Start/stop speech recognition
 * - Display real-time sound levels
 * - Log recognized text
 * - Log detected commands
 * - Clear history
 */
@HiltViewModel
class SpeechTestViewModel @Inject constructor(
    private val speechRecognitionManager: SpeechRecognitionManager
) : ViewModel() {
    
    // ==================== UI State ====================
    
    /**
     * Whether speech recognition is currently active
     */
    val isListening: StateFlow<Boolean> = speechRecognitionManager.isListening
    
    /**
     * Current sound level (0.0 to 1.0)
     */
    val soundLevel: StateFlow<Float> = speechRecognitionManager.soundLevel
    
    /**
     * Current partial recognition text
     */
    val currentText: StateFlow<String> = speechRecognitionManager.currentText
    
    /**
     * Error message if any
     */
    val errorMessage: StateFlow<String?> = speechRecognitionManager.errorMessage
    
    /**
     * History of recognized texts
     */
    private val _recognizedTexts = MutableStateFlow<List<String>>(emptyList())
    val recognizedTexts: StateFlow<List<String>> = _recognizedTexts.asStateFlow()
    
    /**
     * History of detected commands
     */
    private val _detectedCommands = MutableStateFlow<List<CommandCode>>(emptyList())
    val detectedCommands: StateFlow<List<CommandCode>> = _detectedCommands.asStateFlow()
    
    // ==================== Initialization ====================
    
    init {
        // Initialize speech recognition
        viewModelScope.launch {
            speechRecognitionManager.initialize()
        }
        
        // Observe recognized text
        viewModelScope.launch {
            speechRecognitionManager.recognizedText.collect { text ->
                _recognizedTexts.update { it + text }
            }
        }
        
        // Observe recognized commands
        viewModelScope.launch {
            speechRecognitionManager.recognizedCommand.collect { command ->
                _detectedCommands.update { it + command }
            }
        }
    }
    
    // ==================== Actions ====================
    
    /**
     * Start speech recognition
     */
    fun startListening() {
        viewModelScope.launch {
            speechRecognitionManager.startListening()
        }
    }
    
    /**
     * Stop speech recognition
     */
    fun stopListening() {
        viewModelScope.launch {
            speechRecognitionManager.stopListening()
        }
    }
    
    /**
     * Clear recognition history
     */
    fun clearHistory() {
        _recognizedTexts.value = emptyList()
        _detectedCommands.value = emptyList()
        speechRecognitionManager.clearError()
    }
    
    // ==================== Lifecycle ====================
    
    override fun onCleared() {
        super.onCleared()
        // Stop listening when ViewModel is cleared
        viewModelScope.launch {
            speechRecognitionManager.stopListening()
        }
    }
}
