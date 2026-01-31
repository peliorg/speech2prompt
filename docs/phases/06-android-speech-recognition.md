# Phase 06: Android Speech Recognition

## Overview

This phase implements continuous speech recognition on the Android app using the `speech_to_text` Flutter package, which wraps Android's native `SpeechRecognizer` API. The service will handle continuous listening, automatic restart, and voice command detection.

## Prerequisites

- Phase 01-02 completed (Flutter project and protocol)
- Android device or emulator with Google Speech services
- Microphone permission granted

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                       SpeechService                              │
│  ┌─────────────────┐  ┌──────────────────┐  ┌───────────────┐  │
│  │ SpeechToText    │  │ Command          │  │ State         │  │
│  │ Plugin          │──│ Processor        │──│ Management    │  │
│  │                 │  │                  │  │               │  │
│  │ - Initialize    │  │ - Pattern match  │  │ - isListening │  │
│  │ - Listen        │  │ - Extract text   │  │ - currentText │  │
│  │ - Stop          │  │ - Emit commands  │  │ - soundLevel  │  │
│  └─────────────────┘  └──────────────────┘  └───────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
                    ┌───────────────────────┐
                    │   Speech Callbacks    │
                    │   - onResult          │
                    │   - onPartialResult   │
                    │   - onError           │
                    │   - onSoundLevel      │
                    └───────────────────────┘
```

---

## Task 1: Update Dependencies

Ensure `android/pubspec.yaml` has the required dependencies:

```yaml
dependencies:
  flutter:
    sdk: flutter
  
  # Speech recognition
  speech_to_text: ^6.6.0
  
  # State management
  provider: ^6.1.1
  
  # Permissions
  permission_handler: ^11.1.0
  
  # ... other dependencies
```

Run:
```bash
cd /home/dan/workspace/priv/speech2code/android
flutter pub get
```

---

## Task 2: Create Speech Recognition Service

Replace `android/lib/services/speech_service.dart`:

```dart
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
```

---

## Task 3: Create Command Processor Service

Create `android/lib/services/command_processor.dart`:

```dart
import 'dart:async';
import 'package:flutter/foundation.dart';

import '../models/voice_command.dart';
import '../models/message.dart';

/// Callback for sending messages.
typedef MessageSender = Future<void> Function(Message message);

/// Service for processing speech and sending appropriate messages.
class CommandProcessor {
  final MessageSender _sendMessage;
  
  // Buffer for accumulating text before sending
  String _textBuffer = '';
  Timer? _sendTimer;
  
  // Delay before sending text (allows for corrections)
  final Duration _sendDelay;
  
  // Commands that have been executed
  final List<String> _commandHistory = [];

  CommandProcessor({
    required MessageSender sendMessage,
    Duration sendDelay = const Duration(milliseconds: 300),
  })  : _sendMessage = sendMessage,
        _sendDelay = sendDelay;

  /// Process recognized text.
  void processText(String text) {
    debugPrint('CommandProcessor: Processing text: $text');
    
    // Cancel any pending send
    _sendTimer?.cancel();
    
    // Add to buffer
    if (_textBuffer.isNotEmpty) {
      _textBuffer += ' ';
    }
    _textBuffer += text;
    
    // Schedule send after delay
    _sendTimer = Timer(_sendDelay, () {
      _sendBufferedText();
    });
  }

  /// Process a detected command.
  void processCommand(CommandCode command) {
    debugPrint('CommandProcessor: Processing command: ${command.code}');
    
    // Send any buffered text first
    _sendBufferedText();
    
    // Handle cancel specially
    if (command == CommandCode.cancel) {
      _textBuffer = '';
      debugPrint('CommandProcessor: Buffer cleared (cancel)');
      return;
    }
    
    // Send the command
    final message = Message.command(command.code);
    _sendMessage(message);
    
    _commandHistory.add(command.code);
  }

  /// Send any buffered text immediately.
  void flush() {
    _sendTimer?.cancel();
    _sendBufferedText();
  }

  /// Send buffered text.
  void _sendBufferedText() {
    _sendTimer?.cancel();
    
    if (_textBuffer.isEmpty) return;
    
    final text = _textBuffer.trim();
    _textBuffer = '';
    
    if (text.isEmpty) return;
    
    debugPrint('CommandProcessor: Sending text: $text');
    final message = Message.text(text);
    _sendMessage(message);
  }

  /// Clear the buffer without sending.
  void clear() {
    _sendTimer?.cancel();
    _textBuffer = '';
  }

  /// Get command history.
  List<String> get commandHistory => List.unmodifiable(_commandHistory);

  /// Dispose resources.
  void dispose() {
    _sendTimer?.cancel();
  }
}
```

---

## Task 4: Create Permission Handler

Create `android/lib/services/permission_service.dart`:

```dart
import 'package:permission_handler/permission_handler.dart';

/// Service for handling app permissions.
class PermissionService {
  /// Check if microphone permission is granted.
  static Future<bool> hasMicrophonePermission() async {
    return await Permission.microphone.isGranted;
  }

  /// Request microphone permission.
  static Future<bool> requestMicrophonePermission() async {
    final status = await Permission.microphone.request();
    return status.isGranted;
  }

  /// Check if Bluetooth permissions are granted.
  static Future<bool> hasBluetoothPermissions() async {
    final connect = await Permission.bluetoothConnect.isGranted;
    final scan = await Permission.bluetoothScan.isGranted;
    return connect && scan;
  }

  /// Request Bluetooth permissions.
  static Future<bool> requestBluetoothPermissions() async {
    final statuses = await [
      Permission.bluetoothConnect,
      Permission.bluetoothScan,
      Permission.bluetooth,
    ].request();

    return statuses.values.every((status) => status.isGranted);
  }

  /// Request all required permissions.
  static Future<PermissionStatus> requestAllPermissions() async {
    final mic = await requestMicrophonePermission();
    final bt = await requestBluetoothPermissions();

    if (mic && bt) {
      return PermissionStatus.allGranted;
    } else if (!mic && !bt) {
      return PermissionStatus.allDenied;
    } else if (!mic) {
      return PermissionStatus.microphoneDenied;
    } else {
      return PermissionStatus.bluetoothDenied;
    }
  }

  /// Open app settings.
  static Future<bool> openSettings() async {
    return await openAppSettings();
  }
}

/// Permission status result.
enum PermissionStatus {
  allGranted,
  allDenied,
  microphoneDenied,
  bluetoothDenied,
}
```

---

## Task 5: Create Audio Level Indicator Model

Create `android/lib/models/audio_level.dart`:

```dart
import 'dart:math' as math;

/// Model for audio level visualization.
class AudioLevel {
  final double level;
  final DateTime timestamp;

  AudioLevel({
    required this.level,
    DateTime? timestamp,
  }) : timestamp = timestamp ?? DateTime.now();

  /// Create from raw dB level.
  factory AudioLevel.fromDb(double db) {
    // Normalize dB level (-2 to 10) to 0-1 range
    final normalized = ((db + 2) / 12).clamp(0.0, 1.0);
    return AudioLevel(level: normalized);
  }

  /// Create a silent level.
  factory AudioLevel.silent() => AudioLevel(level: 0.0);

  /// Get smoothed level for visualization.
  double get smoothedLevel => math.sqrt(level);

  /// Get amplitude for wave visualization.
  double get amplitude => level * 0.5 + 0.1;
}

/// Buffer for smoothing audio levels.
class AudioLevelBuffer {
  final int size;
  final List<double> _buffer = [];
  
  AudioLevelBuffer({this.size = 5});

  /// Add a new level and get smoothed value.
  double add(double level) {
    _buffer.add(level);
    if (_buffer.length > size) {
      _buffer.removeAt(0);
    }
    return average;
  }

  /// Get average level.
  double get average {
    if (_buffer.isEmpty) return 0.0;
    return _buffer.reduce((a, b) => a + b) / _buffer.length;
  }

  /// Clear the buffer.
  void clear() {
    _buffer.clear();
  }
}
```

---

## Task 6: Update Voice Command Model

Update `android/lib/models/voice_command.dart` with local commands:

```dart
/// Voice command codes that can be sent to the desktop.
enum CommandCode {
  enter('ENTER'),
  selectAll('SELECT_ALL'),
  copy('COPY'),
  paste('PASTE'),
  cut('CUT'),
  cancel('CANCEL');

  final String code;
  const CommandCode(this.code);

  /// Parse from spoken text.
  /// Returns null if no command is detected.
  static CommandCode? parse(String text) {
    final lower = text.toLowerCase().trim();
    
    // Enter / new line
    if (_matches(lower, ['new line', 'newline', 'enter', 'next line', 'line break'])) {
      return CommandCode.enter;
    }
    
    // Select all
    if (_matches(lower, ['select all', 'select everything', 'highlight all'])) {
      return CommandCode.selectAll;
    }
    
    // Copy
    if (_matches(lower, ['copy that', 'copy this', 'copy it', 'copy selection'])) {
      return CommandCode.copy;
    }
    
    // Paste
    if (_matches(lower, ['paste', 'paste that', 'paste it', 'paste here'])) {
      return CommandCode.paste;
    }
    
    // Cut
    if (_matches(lower, ['cut that', 'cut this', 'cut it', 'cut selection'])) {
      return CommandCode.cut;
    }
    
    // Cancel
    if (_matches(lower, ['cancel', 'clear', 'never mind', 'nevermind', 'discard'])) {
      return CommandCode.cancel;
    }
    
    return null;
  }

  static bool _matches(String text, List<String> patterns) {
    for (final pattern in patterns) {
      if (text.contains(pattern)) {
        return true;
      }
    }
    return false;
  }
}

/// Local commands that affect the app but aren't sent to desktop.
enum LocalCommand {
  stopListening,
  startListening,
}

/// Check for local commands in text.
LocalCommand? parseLocalCommand(String text) {
  final lower = text.toLowerCase().trim();
  
  if (lower.endsWith('stop listening') || lower.endsWith('pause listening')) {
    return LocalCommand.stopListening;
  }
  
  if (lower.endsWith('start listening') || lower.endsWith('resume listening')) {
    return LocalCommand.startListening;
  }
  
  return null;
}

/// Result of processing spoken text for commands.
class ProcessedSpeech {
  /// Text before any detected command (to be sent as TEXT).
  final String? textBefore;
  
  /// Detected command (to be sent as COMMAND).
  final CommandCode? command;
  
  /// Text after command (to be processed in next iteration).
  final String? textAfter;

  ProcessedSpeech({
    this.textBefore,
    this.command,
    this.textAfter,
  });

  /// Process spoken text to extract commands.
  factory ProcessedSpeech.process(String text) {
    final lower = text.toLowerCase();
    
    // Command patterns with their codes
    final patterns = <String, CommandCode>{
      'new line': CommandCode.enter,
      'newline': CommandCode.enter,
      'enter': CommandCode.enter,
      'next line': CommandCode.enter,
      'line break': CommandCode.enter,
      'select all': CommandCode.selectAll,
      'select everything': CommandCode.selectAll,
      'copy that': CommandCode.copy,
      'copy this': CommandCode.copy,
      'copy it': CommandCode.copy,
      'paste': CommandCode.paste,
      'paste that': CommandCode.paste,
      'cut that': CommandCode.cut,
      'cut this': CommandCode.cut,
      'cancel': CommandCode.cancel,
      'clear': CommandCode.cancel,
      'never mind': CommandCode.cancel,
      'nevermind': CommandCode.cancel,
    };

    // Find the first command in the text
    int firstIndex = -1;
    String? firstPattern;
    CommandCode? firstCommand;

    for (final entry in patterns.entries) {
      final index = lower.indexOf(entry.key);
      if (index != -1 && (firstIndex == -1 || index < firstIndex)) {
        firstIndex = index;
        firstPattern = entry.key;
        firstCommand = entry.value;
      }
    }

    if (firstIndex == -1) {
      // No command found
      return ProcessedSpeech(textBefore: text);
    }

    final textBefore = text.substring(0, firstIndex).trim();
    final afterCommand = firstIndex + firstPattern!.length;
    final textAfter = afterCommand < text.length 
        ? text.substring(afterCommand).trim() 
        : null;

    return ProcessedSpeech(
      textBefore: textBefore.isEmpty ? null : textBefore,
      command: firstCommand,
      textAfter: textAfter?.isEmpty == true ? null : textAfter,
    );
  }

  bool get hasText => textBefore != null && textBefore!.isNotEmpty;
  bool get hasCommand => command != null;
  bool get hasRemainder => textAfter != null && textAfter!.isNotEmpty;
}
```

---

## Task 7: Create Test Screen for Speech

Create `android/lib/screens/speech_test_screen.dart` for testing:

```dart
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../services/speech_service.dart';
import '../services/permission_service.dart';

/// Test screen for speech recognition.
class SpeechTestScreen extends StatefulWidget {
  const SpeechTestScreen({super.key});

  @override
  State<SpeechTestScreen> createState() => _SpeechTestScreenState();
}

class _SpeechTestScreenState extends State<SpeechTestScreen> {
  final List<String> _recognizedTexts = [];
  final List<String> _commands = [];
  bool _permissionsGranted = false;

  @override
  void initState() {
    super.initState();
    _checkPermissions();
  }

  Future<void> _checkPermissions() async {
    final hasMic = await PermissionService.hasMicrophonePermission();
    if (!hasMic) {
      final granted = await PermissionService.requestMicrophonePermission();
      setState(() {
        _permissionsGranted = granted;
      });
    } else {
      setState(() {
        _permissionsGranted = true;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Speech Test'),
      ),
      body: Consumer<SpeechService>(
        builder: (context, speech, child) {
          // Set up callbacks
          speech.onTextRecognized = (text) {
            setState(() {
              _recognizedTexts.add(text);
            });
          };
          speech.onCommandDetected = (cmd) {
            setState(() {
              _commands.add(cmd.code);
            });
          };

          return Column(
            children: [
              // Status
              Container(
                padding: const EdgeInsets.all(16),
                color: speech.isListening ? Colors.green[100] : Colors.grey[200],
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          speech.isListening ? 'Listening...' : 'Not listening',
                          style: const TextStyle(fontWeight: FontWeight.bold),
                        ),
                        Text('Locale: ${speech.selectedLocale}'),
                        if (speech.hasError)
                          Text(
                            speech.errorMessage!,
                            style: const TextStyle(color: Colors.red),
                          ),
                      ],
                    ),
                    // Sound level indicator
                    Container(
                      width: 50,
                      height: 50,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: Colors.blue.withOpacity(speech.soundLevel),
                      ),
                    ),
                  ],
                ),
              ),

              // Current text
              Container(
                padding: const EdgeInsets.all(16),
                width: double.infinity,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      'Current:',
                      style: TextStyle(fontWeight: FontWeight.bold),
                    ),
                    Text(
                      speech.currentText.isEmpty ? '...' : speech.currentText,
                      style: const TextStyle(fontSize: 18),
                    ),
                  ],
                ),
              ),

              // Recognized texts
              Expanded(
                child: Row(
                  children: [
                    // Texts
                    Expanded(
                      child: Column(
                        children: [
                          const Padding(
                            padding: EdgeInsets.all(8.0),
                            child: Text(
                              'Recognized Text',
                              style: TextStyle(fontWeight: FontWeight.bold),
                            ),
                          ),
                          Expanded(
                            child: ListView.builder(
                              itemCount: _recognizedTexts.length,
                              itemBuilder: (context, index) {
                                final text = _recognizedTexts[
                                    _recognizedTexts.length - 1 - index];
                                return ListTile(
                                  dense: true,
                                  title: Text(text),
                                );
                              },
                            ),
                          ),
                        ],
                      ),
                    ),
                    // Commands
                    Expanded(
                      child: Column(
                        children: [
                          const Padding(
                            padding: EdgeInsets.all(8.0),
                            child: Text(
                              'Commands',
                              style: TextStyle(fontWeight: FontWeight.bold),
                            ),
                          ),
                          Expanded(
                            child: ListView.builder(
                              itemCount: _commands.length,
                              itemBuilder: (context, index) {
                                final cmd =
                                    _commands[_commands.length - 1 - index];
                                return ListTile(
                                  dense: true,
                                  leading: const Icon(Icons.keyboard_command_key),
                                  title: Text(cmd),
                                );
                              },
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),

              // Controls
              Padding(
                padding: const EdgeInsets.all(16),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                  children: [
                    ElevatedButton.icon(
                      onPressed: _permissionsGranted
                          ? () => speech.toggleListening()
                          : null,
                      icon: Icon(
                        speech.isListening ? Icons.stop : Icons.mic,
                      ),
                      label: Text(
                        speech.isListening ? 'Stop' : 'Start',
                      ),
                    ),
                    ElevatedButton.icon(
                      onPressed: () {
                        setState(() {
                          _recognizedTexts.clear();
                          _commands.clear();
                        });
                      },
                      icon: const Icon(Icons.clear),
                      label: const Text('Clear'),
                    ),
                  ],
                ),
              ),
            ],
          );
        },
      ),
    );
  }
}
```

---

## Task 8: Update Main App for Testing

Update `android/lib/main.dart` to include test navigation:

```dart
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'screens/home_screen.dart';
import 'screens/speech_test_screen.dart';
import 'services/speech_service.dart';
import 'services/bluetooth_service.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const Speech2CodeApp());
}

class Speech2CodeApp extends StatelessWidget {
  const Speech2CodeApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MultiProvider(
      providers: [
        ChangeNotifierProvider(create: (_) => SpeechService()),
        ChangeNotifierProvider(create: (_) => BluetoothService()),
      ],
      child: MaterialApp(
        title: 'Speech2Code',
        debugShowCheckedModeBanner: false,
        theme: ThemeData(
          colorScheme: ColorScheme.fromSeed(
            seedColor: Colors.blue,
            brightness: Brightness.dark,
          ),
          useMaterial3: true,
        ),
        initialRoute: '/',
        routes: {
          '/': (context) => const HomeScreen(),
          '/speech-test': (context) => const SpeechTestScreen(),
        },
      ),
    );
  }
}
```

---

## Testing

### Build and Run on Device

```bash
cd /home/dan/workspace/priv/speech2code/android
flutter run
```

### Test Speech Recognition

1. Grant microphone permission when prompted
2. Tap the microphone button to start listening
3. Speak some text
4. Verify text appears in the UI
5. Say "new line" and verify command is detected
6. Say "stop listening" and verify recognition pauses

### Test Commands

- Say: "Hello world new line" → Should emit "Hello world" + ENTER command
- Say: "Select all" → Should emit SELECT_ALL command
- Say: "Copy that" → Should emit COPY command
- Say: "Cancel" → Should clear buffer

---

## Troubleshooting

### "Speech recognition not available"

- Ensure Google app is installed and updated
- Check internet connection (Google Speech requires network)
- Try a different device

### Recognition stops after a few seconds

- This is normal - Android limits continuous listening
- The service auto-restarts when listening ends
- Use `listenFor` parameter to extend duration

### Poor recognition quality

- Check microphone permissions
- Reduce background noise
- Speak clearly and at normal pace
- Try changing locale

---

## Verification Checklist

- [ ] Microphone permission requested and granted
- [ ] Speech recognition initializes successfully
- [ ] Start/stop listening works
- [ ] Continuous listening auto-restarts
- [ ] Partial results appear in real-time
- [ ] Final results trigger callbacks
- [ ] Voice commands detected correctly
- [ ] "Stop listening" pauses recognition
- [ ] Sound level indicator updates
- [ ] Error handling works

## Output Artifacts

After completing this phase:

1. **SpeechService** - Continuous speech recognition with callbacks
2. **CommandProcessor** - Text/command processing and buffering
3. **PermissionService** - Permission handling utilities
4. **Voice command parsing** - Pattern matching for commands
5. **Test screen** - For verifying speech recognition

## Next Phase

Proceed to **Phase 07: Android Bluetooth Client** to implement the Bluetooth connection to the Linux desktop.
