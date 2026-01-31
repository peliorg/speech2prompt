// Copyright 2026 Daniel Pelikan
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:speech_to_text/speech_recognition_result.dart';
import 'package:speech_to_text/speech_to_text.dart';

import '../models/voice_command.dart';

/// Callback for when final text is recognized.
typedef OnTextRecognized = void Function(String text);

/// Callback for when a command is detected.
typedef OnCommandDetected = void Function(CommandCode command);

/// Callback for when partial results are available.
typedef OnPartialResult = void Function(String partial);

/// Service for managing continuous speech recognition.
class SpeechService extends ChangeNotifier {
  final SpeechToText _speech = SpeechToText();

  // State
  bool _isInitialized = false;
  bool _isListening = false;
  bool _isPaused = false;
  String _currentText = '';
  String _lastFinalText = '';
  double _soundLevel = 0.0;
  String _selectedLocale = 'en_US';
  List<LocaleName> _availableLocales = [];
  String? _errorMessage;

  // Callbacks
  OnTextRecognized? onTextRecognized;
  OnCommandDetected? onCommandDetected;
  OnPartialResult? onPartialResult;

  // Configuration
  Duration _pauseFor = const Duration(seconds: 3);
  Duration _listenFor = const Duration(seconds: 30);
  bool _autoRestart = true;

  // Getters
  bool get isInitialized => _isInitialized;
  bool get isListening => _isListening;
  bool get isPaused => _isPaused;
  String get currentText => _currentText;
  String get lastFinalText => _lastFinalText;
  double get soundLevel => _soundLevel;
  String get selectedLocale => _selectedLocale;
  List<LocaleName> get availableLocales => _availableLocales;
  String? get errorMessage => _errorMessage;
  bool get hasError => _errorMessage != null;

  /// Initialize the speech recognition service.
  Future<bool> initialize() async {
    if (_isInitialized) return true;

    try {
      _isInitialized = await _speech.initialize(
        onError: _handleError,
        onStatus: _handleStatus,
        debugLogging: kDebugMode,
      );

      if (_isInitialized) {
        // Load available locales
        _availableLocales = await _speech.locales();
        
        // Find system locale or use default
        final systemLocale = await _speech.systemLocale();
        if (systemLocale != null) {
          _selectedLocale = systemLocale.localeId;
        }
        
        _errorMessage = null;
        debugPrint('SpeechService: Initialized with locale $_selectedLocale');
      } else {
        _errorMessage = 'Speech recognition not available';
        debugPrint('SpeechService: Initialization failed');
      }

      notifyListeners();
      return _isInitialized;
    } catch (e) {
      _errorMessage = 'Failed to initialize: $e';
      debugPrint('SpeechService: Error initializing: $e');
      notifyListeners();
      return false;
    }
  }

  /// Set the locale for speech recognition.
  void setLocale(String localeId) {
    if (_availableLocales.any((l) => l.localeId == localeId)) {
      _selectedLocale = localeId;
      notifyListeners();
      debugPrint('SpeechService: Locale changed to $localeId');
    }
  }

  /// Start listening for speech.
  Future<void> startListening() async {
    if (!_isInitialized) {
      final success = await initialize();
      if (!success) return;
    }

    if (_isListening) return;

    _errorMessage = null;
    _isPaused = false;
    _currentText = '';

    try {
      await _speech.listen(
        onResult: _handleResult,
        onSoundLevelChange: _handleSoundLevel,
        localeId: _selectedLocale,
        listenFor: _listenFor,
        pauseFor: _pauseFor,
        partialResults: true,
        cancelOnError: false,
        listenMode: ListenMode.dictation,
      );

      _isListening = true;
      notifyListeners();
      debugPrint('SpeechService: Started listening');
    } catch (e) {
      _errorMessage = 'Failed to start: $e';
      debugPrint('SpeechService: Error starting: $e');
      notifyListeners();
    }
  }

  /// Stop listening for speech.
  Future<void> stopListening() async {
    if (!_isListening) return;

    await _speech.stop();
    _isListening = false;
    _soundLevel = 0.0;
    notifyListeners();
    debugPrint('SpeechService: Stopped listening');
  }

  /// Pause listening (will not auto-restart).
  Future<void> pauseListening() async {
    _isPaused = true;
    _autoRestart = false;
    await stopListening();
    debugPrint('SpeechService: Paused');
  }

  /// Resume listening after pause.
  Future<void> resumeListening() async {
    _isPaused = false;
    _autoRestart = true;
    await startListening();
    debugPrint('SpeechService: Resumed');
  }

  /// Toggle listening state.
  Future<void> toggleListening() async {
    if (_isListening) {
      await pauseListening();
    } else {
      await resumeListening();
    }
  }

  /// Handle speech recognition results.
  void _handleResult(SpeechRecognitionResult result) {
    final text = result.recognizedWords;
    
    if (text.isEmpty) return;

    _currentText = text;
    notifyListeners();

    if (result.finalResult) {
      debugPrint('SpeechService: Final result: $text');
      _processFinalResult(text);
    } else {
      debugPrint('SpeechService: Partial result: $text');
      onPartialResult?.call(text);
    }
  }

  /// Process a final recognition result.
  void _processFinalResult(String text) {
    // Check for local commands (stop/start listening)
    final lowerText = text.toLowerCase().trim();
    if (lowerText.endsWith('stop listening') || 
        lowerText.endsWith('pause listening')) {
      // Extract text before command
      final beforeCommand = text.substring(
        0, 
        text.toLowerCase().lastIndexOf('stop listening').clamp(0, text.length)
      ).trim();
      
      if (beforeCommand.isNotEmpty) {
        _emitText(beforeCommand);
      }
      
      pauseListening();
      return;
    }

    // Process for remote commands
    final processed = ProcessedSpeech.process(text);

    // Send text before command
    if (processed.hasText) {
      _emitText(processed.textBefore!);
    }

    // Send command
    if (processed.hasCommand) {
      _emitCommand(processed.command!);
    }

    // If there's remaining text, process it
    if (processed.hasRemainder) {
      _processFinalResult(processed.textAfter!);
    }

    _lastFinalText = text;
    _currentText = '';
    notifyListeners();
  }

  /// Emit recognized text.
  void _emitText(String text) {
    debugPrint('SpeechService: Emitting text: $text');
    onTextRecognized?.call(text);
  }

  /// Emit detected command.
  void _emitCommand(CommandCode command) {
    debugPrint('SpeechService: Emitting command: ${command.code}');
    onCommandDetected?.call(command);
  }

  /// Handle sound level changes.
  void _handleSoundLevel(double level) {
    // Normalize to 0-1 range (level is typically -2 to 10 dB)
    _soundLevel = ((level + 2) / 12).clamp(0.0, 1.0);
    notifyListeners();
  }

  /// Handle speech recognition errors.
  void _handleError(dynamic error) {
    debugPrint('SpeechService: Error: $error');
    
    final errorStr = error.toString().toLowerCase();
    
    // Ignore "no match" errors (user was silent)
    if (errorStr.contains('no match') || errorStr.contains('no_match')) {
      debugPrint('SpeechService: No speech detected, restarting...');
      _restartIfNeeded();
      return;
    }
    
    // Handle network errors
    if (errorStr.contains('network')) {
      _errorMessage = 'Network error. Check internet connection.';
    } else if (errorStr.contains('audio')) {
      _errorMessage = 'Microphone error. Check permissions.';
    } else if (errorStr.contains('busy')) {
      _errorMessage = 'Speech service busy. Try again.';
    } else {
      _errorMessage = 'Speech error: $error';
    }
    
    _isListening = false;
    notifyListeners();
    
    // Auto-restart after a delay
    if (_autoRestart && !_isPaused) {
      Future.delayed(const Duration(seconds: 2), () {
        _restartIfNeeded();
      });
    }
  }

  /// Handle speech recognition status changes.
  void _handleStatus(String status) {
    debugPrint('SpeechService: Status: $status');
    
    if (status == 'done' || status == 'notListening') {
      _isListening = false;
      _soundLevel = 0.0;
      notifyListeners();
      
      // Auto-restart for continuous listening
      _restartIfNeeded();
    } else if (status == 'listening') {
      _isListening = true;
      notifyListeners();
    }
  }

  /// Restart listening if auto-restart is enabled.
  void _restartIfNeeded() {
    if (_autoRestart && !_isPaused && !_isListening) {
      debugPrint('SpeechService: Auto-restarting...');
      Future.delayed(const Duration(milliseconds: 100), () {
        if (!_isListening && _autoRestart && !_isPaused) {
          startListening();
        }
      });
    }
  }

  /// Clear any error state.
  void clearError() {
    _errorMessage = null;
    notifyListeners();
  }

  /// Configure listening parameters.
  void configure({
    Duration? pauseFor,
    Duration? listenFor,
    bool? autoRestart,
  }) {
    if (pauseFor != null) _pauseFor = pauseFor;
    if (listenFor != null) _listenFor = listenFor;
    if (autoRestart != null) _autoRestart = autoRestart;
  }

  @override
  void dispose() {
    _autoRestart = false;
    _speech.stop();
    super.dispose();
  }
}
