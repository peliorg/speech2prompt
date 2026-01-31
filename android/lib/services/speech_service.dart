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
import 'dart:math';
import 'package:flutter/foundation.dart';
import 'package:speech_to_text/speech_recognition_result.dart';
import 'package:speech_to_text/speech_to_text.dart';

import '../models/voice_command.dart';

/// State machine for the speech recognizer to prevent race conditions.
enum RecognizerState { idle, starting, listening, stopping }

/// Callback for when final text is recognized.
typedef OnTextRecognized = void Function(String text);

/// Callback for when a command is detected.
typedef OnCommandDetected = void Function(CommandCode command);

/// Callback for when partial results are available.
typedef OnPartialResult = void Function(String partial);

/// Service for managing continuous speech recognition.
class SpeechService extends ChangeNotifier {
  final SpeechToText _speech = SpeechToText();

  // Recognizer state machine to prevent race conditions
  RecognizerState _recognizerState = RecognizerState.idle;

  // Restart debouncing and backoff
  DateTime? _lastRestartAttempt;
  static const _minRestartInterval = Duration(seconds: 2);
  int _consecutiveErrors = 0;
  static const _maxBackoffSeconds = 30;
  static const _maxConsecutiveErrors = 10;
  bool _restartScheduled = false;
  int _busyRetryCount = 0;

  // Watchdog timer for long-running stability
  Timer? _watchdogTimer;
  static const _watchdogTimeout = Duration(seconds: 20);
  static const _stateStuckTimeout = Duration(seconds: 10);
  DateTime? _lastSuccessfulListening;
  DateTime? _stateChangeTime;

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
  /// Duration to wait for speech before timing out
  Duration _pauseFor = const Duration(seconds: 3);

  /// Maximum duration for a single listening session
  Duration _listenFor = const Duration(seconds: 30);

  bool _autoRestart = true;

  /// Setter to allow configuration of pause duration
  void setPauseFor(Duration duration) {
    _pauseFor = duration;
  }

  /// Setter to allow configuration of listen duration
  void setListenFor(Duration duration) {
    _listenFor = duration;
  }

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

    // State machine: only allow starting if idle
    if (_recognizerState != RecognizerState.idle) {
      debugPrint(
        'SpeechService: Cannot start - recognizer state is $_recognizerState',
      );
      return;
    }

    if (_isListening) return;

    _recognizerState = RecognizerState.starting;
    _stateChangeTime = DateTime.now();
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
        listenOptions: SpeechListenOptions(
          partialResults: true,
          cancelOnError: false,
          listenMode: ListenMode.dictation,
        ),
      );

      _recognizerState = RecognizerState.listening;
      _stateChangeTime = DateTime.now();
      _isListening = true;
      _resetWatchdog();
      notifyListeners();
      debugPrint('SpeechService: Started listening');
    } catch (e) {
      _recognizerState = RecognizerState.idle;
      _stateChangeTime = DateTime.now();
      _errorMessage = 'Failed to start: $e';
      debugPrint('SpeechService: Error starting: $e');
      notifyListeners();
    }
  }

  /// Stop listening for speech.
  Future<void> stopListening() async {
    if (!_isListening) return;

    _watchdogTimer?.cancel();
    _recognizerState = RecognizerState.stopping;
    _stateChangeTime = DateTime.now();
    await _speech.stop();

    // Wait for Android to fully release the recognizer
    await Future.delayed(const Duration(milliseconds: 300));

    _recognizerState = RecognizerState.idle;
    _stateChangeTime = DateTime.now();
    _isListening = false;
    _soundLevel = 0.0;
    notifyListeners();
    debugPrint('SpeechService: Stopped listening');
  }

  /// Pause listening (will not auto-restart).
  Future<void> pauseListening() async {
    _isPaused = true;
    _autoRestart = false;
    _watchdogTimer?.cancel();
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

    // Reset consecutive errors on successful recognition
    _consecutiveErrors = 0;

    _currentText = text;
    notifyListeners();

    if (result.finalResult) {
      debugPrint('SpeechService: Final result: $text');
      _lastSuccessfulListening = DateTime.now();
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
      final beforeCommand = text
          .substring(
            0,
            text
                .toLowerCase()
                .lastIndexOf('stop listening')
                .clamp(0, text.length),
          )
          .trim();

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

    // Transient errors that should trigger immediate restart without backoff:
    // - no_match: user was silent during recognition window
    // - speech_timeout: user didn't speak (not a real error)
    // - error_client: often occurs when restarting recognizer quickly
    if (errorStr.contains('no match') ||
        errorStr.contains('no_match') ||
        errorStr.contains('speech_timeout') ||
        errorStr.contains('error_client')) {
      debugPrint('SpeechService: Transient error, restarting...');
      _recognizerState = RecognizerState.idle;
      _stateChangeTime = DateTime.now();
      _scheduleRestart();
      return;
    }

    // Handle error_busy specially - silent recovery, no UI changes
    if (errorStr.contains('error_busy') || errorStr.contains('busy')) {
      debugPrint(
        'SpeechService: Recognizer busy, waiting silently for release',
      );
      _recognizerState = RecognizerState.stopping;
      _stateChangeTime = DateTime.now();
      _speech.stop();

      // Wait longer for busy errors (1 second) then set to idle and retry
      // Do NOT change _isListening or _errorMessage - keep UI stable
      Future.delayed(const Duration(milliseconds: 1000), () {
        _recognizerState = RecognizerState.idle;
        _stateChangeTime = DateTime.now();
        if (_autoRestart && !_isPaused) {
          debugPrint('SpeechService: Retrying after busy error');
          // Clear rate limiter state before retry
          _lastRestartAttempt = null;
          _restartScheduled = false;
          _scheduleRestart();
        }
      });
      return;
    }

    // Real errors that should trigger exponential backoff:
    // - error_network: network connectivity issues
    // - error_server: server-side issues
    // - error_audio: microphone/audio issues
    // - error_permission: permission denied
    if (errorStr.contains('network') || errorStr.contains('error_network')) {
      _errorMessage = 'Network error. Check internet connection.';
    } else if (errorStr.contains('audio') || errorStr.contains('error_audio')) {
      _errorMessage = 'Microphone error. Check permissions.';
    } else if (errorStr.contains('server') ||
        errorStr.contains('error_server')) {
      _errorMessage = 'Server error. Try again later.';
    } else if (errorStr.contains('permission') ||
        errorStr.contains('error_permission')) {
      _errorMessage = 'Permission denied. Check app settings.';
    } else {
      _errorMessage = 'Speech error: $error';
    }

    _recognizerState = RecognizerState.idle;
    _stateChangeTime = DateTime.now();
    _isListening = false;
    notifyListeners();

    // Auto-restart with exponential backoff for real errors only
    if (_autoRestart && !_isPaused) {
      _consecutiveErrors++;

      // Check if max errors reached
      if (_consecutiveErrors >= _maxConsecutiveErrors) {
        debugPrint(
          'SpeechService: Max consecutive errors reached ($_maxConsecutiveErrors), stopping auto-restart',
        );
        _errorMessage = 'Too many errors. Tap to restart.';
        notifyListeners();
        return;
      }

      final delaySeconds = min(
        pow(2, _consecutiveErrors).toInt(),
        _maxBackoffSeconds,
      );
      debugPrint(
        'SpeechService: Real error occurred, waiting ${delaySeconds}s before retry (attempt $_consecutiveErrors)',
      );

      Future.delayed(Duration(seconds: delaySeconds), () {
        _scheduleRestart();
      });
    }
  }

  /// Handle speech recognition status changes.
  void _handleStatus(String status) {
    debugPrint('SpeechService: Status: $status');

    if (status == 'done' || status == 'notListening') {
      _recognizerState = RecognizerState.idle;
      _stateChangeTime = DateTime.now();
      _isListening = false;
      _soundLevel = 0.0;
      notifyListeners();

      // Auto-restart for continuous listening (successful completion, skip debounce)
      _scheduleRestart(wasSuccessful: true);
    } else if (status == 'listening') {
      _recognizerState = RecognizerState.listening;
      _stateChangeTime = DateTime.now();
      _isListening = true;
      notifyListeners();
    }
  }

  /// Schedule a restart with debouncing to prevent rapid restart loops.
  /// When [wasSuccessful] is true (e.g., after normal completion), skip debounce
  /// to enable continuous listening without delays.
  void _scheduleRestart({bool wasSuccessful = false}) {
    if (!_autoRestart || _isPaused || _isListening) {
      return;
    }

    // State machine: only schedule restart if recognizer is idle
    if (_recognizerState != RecognizerState.idle) {
      debugPrint(
        'SpeechService: Cannot restart - recognizer state is $_recognizerState, scheduling delayed check',
      );
      // Schedule a delayed check to try again
      Future.delayed(const Duration(milliseconds: 500), () {
        _scheduleRestart(wasSuccessful: wasSuccessful);
      });
      return;
    }

    // Check if restart already scheduled
    if (_restartScheduled) {
      debugPrint('SpeechService: Restart already scheduled, skipping');
      return;
    }

    // Only apply debounce for error restarts, not successful completions
    if (!wasSuccessful) {
      // Check if enough time has passed since last restart attempt (debouncing)
      final now = DateTime.now();
      if (_lastRestartAttempt != null &&
          now.difference(_lastRestartAttempt!) < _minRestartInterval) {
        debugPrint('SpeechService: Skipping restart - too soon');
        return;
      }
    }

    // Check if max errors reached
    if (_consecutiveErrors >= _maxConsecutiveErrors) {
      debugPrint(
        'SpeechService: Max consecutive errors reached ($_maxConsecutiveErrors), stopping auto-restart',
      );
      return;
    }

    _restartScheduled = true;
    _lastRestartAttempt = DateTime.now();

    debugPrint('SpeechService: Auto-restarting...');
    Future.delayed(const Duration(milliseconds: 100), () {
      _restartScheduled = false;
      if (!_isListening &&
          _autoRestart &&
          !_isPaused &&
          _recognizerState == RecognizerState.idle) {
        startListening();
      }
    });
  }

  /// Start or reset the watchdog timer
  void _resetWatchdog() {
    _watchdogTimer?.cancel();
    _lastSuccessfulListening = DateTime.now();
    _stateChangeTime = DateTime.now();
    _watchdogTimer = Timer.periodic(const Duration(seconds: 5), _checkWatchdog);
  }

  /// Check if we're stuck and need recovery
  void _checkWatchdog(Timer timer) {
    if (!_autoRestart || _isPaused) return;

    final now = DateTime.now();

    // Check if state is stuck in 'starting' or 'stopping' for too long
    if (_recognizerState == RecognizerState.starting ||
        _recognizerState == RecognizerState.stopping) {
      if (_stateChangeTime != null &&
          now.difference(_stateChangeTime!) > _stateStuckTimeout) {
        debugPrint(
          'SpeechService: Watchdog - state stuck in $_recognizerState, forcing restart',
        );
        _forceFullRestart();
        return;
      }
    }

    // Check if no successful listening for too long
    if (_lastSuccessfulListening != null &&
        now.difference(_lastSuccessfulListening!) > _watchdogTimeout) {
      debugPrint(
        'SpeechService: Watchdog - no successful listening for ${_watchdogTimeout.inSeconds}s, forcing restart',
      );
      _forceFullRestart();
      return;
    }

    // Check if we should be listening but aren't
    if (_recognizerState == RecognizerState.idle &&
        !_isListening &&
        _autoRestart &&
        !_isPaused &&
        !_restartScheduled) {
      debugPrint(
        'SpeechService: Watchdog - should be listening but idle, triggering restart',
      );
      _scheduleRestart(wasSuccessful: false);
    }
  }

  /// Force a full restart of the speech service
  Future<void> _forceFullRestart() async {
    debugPrint('SpeechService: Forcing full restart');

    // Cancel any pending operations
    _watchdogTimer?.cancel();

    // Force stop
    _recognizerState = RecognizerState.stopping;
    _stateChangeTime = DateTime.now();

    try {
      await _speech.stop();
    } catch (e) {
      debugPrint('SpeechService: Error stopping during force restart: $e');
    }

    // Wait for Android to release resources
    await Future.delayed(const Duration(milliseconds: 500));

    // Reset all state
    _recognizerState = RecognizerState.idle;
    _stateChangeTime = DateTime.now();
    _consecutiveErrors = 0;
    _lastRestartAttempt = null;
    _restartScheduled = false;
    _busyRetryCount = 0;

    // Restart watchdog
    _resetWatchdog();

    // Start listening again
    if (_autoRestart && !_isPaused) {
      await startListening();
    }
  }

  /// Clear any error state.
  void clearError() {
    _errorMessage = null;
    notifyListeners();
  }

  /// Reset error tracking and allow auto-restart again.
  void resetErrorState() {
    _consecutiveErrors = 0;
    _lastRestartAttempt = null;
    _restartScheduled = false;
    _errorMessage = null;
    _recognizerState = RecognizerState.idle;
    _stateChangeTime = DateTime.now();
    _lastSuccessfulListening = null;
    notifyListeners();
  }

  /// Configure listening parameters.
  void configure({Duration? pauseFor, Duration? listenFor, bool? autoRestart}) {
    if (pauseFor != null) _pauseFor = pauseFor;
    if (listenFor != null) _listenFor = listenFor;
    if (autoRestart != null) _autoRestart = autoRestart;
  }

  @override
  void dispose() {
    _watchdogTimer?.cancel();
    _autoRestart = false;
    _recognizerState = RecognizerState.stopping;
    _stateChangeTime = DateTime.now();
    _speech.stop();
    _recognizerState = RecognizerState.idle;
    _stateChangeTime = DateTime.now();
    super.dispose();
  }
}
