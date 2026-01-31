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

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import '../services/speech_service.dart';
import '../services/ble_service.dart';
import '../services/command_processor.dart';
import '../widgets/audio_visualizer.dart';
import '../widgets/connection_badge.dart';
import '../widgets/transcription_display.dart';

/// Main screen with microphone control and status.
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with WidgetsBindingObserver {
  CommandProcessor? _commandProcessor;
  final bool _keepScreenOn = true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _setupServices();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _commandProcessor?.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    final speechService = context.read<SpeechService>();

    if (state == AppLifecycleState.paused) {
      // Pause listening when app goes to background
      if (speechService.isListening) {
        speechService.pauseListening();
      }
    }
  }

  void _setupServices() {
    final bluetoothService = context.read<BleService>();
    final speechService = context.read<SpeechService>();

    // Create command processor
    _commandProcessor = CommandProcessor(
      sendMessage: (message) => bluetoothService.sendMessage(message),
    );

    // Wire up speech callbacks
    speechService.onTextRecognized = (text) {
      _commandProcessor?.processText(text);
    };
    speechService.onCommandDetected = (command) {
      _commandProcessor?.processCommand(command);
    };

    // Initialize speech service
    speechService.initialize();
  }

  void _toggleListening() {
    final speechService = context.read<SpeechService>();
    final bluetoothService = context.read<BleService>();

    if (!bluetoothService.isConnected) {
      _showConnectionRequired();
      return;
    }

    HapticFeedback.mediumImpact();
    speechService.toggleListening();
  }

  void _showConnectionRequired() {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: const Text('Connect to a computer first'),
        action: SnackBarAction(
          label: 'Connect',
          onPressed: () => Navigator.pushNamed(context, '/connection'),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Consumer2<SpeechService, BleService>(
          builder: (context, speech, bluetooth, child) {
            // Keep screen on when listening
            if (_keepScreenOn && speech.isListening) {
              // Note: Requires wakelock package for actual implementation
            }

            return Column(
              children: [
                // App bar area
                _buildAppBar(context),

                // Main content
                Expanded(
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      // Connection badge
                      ConnectionBadge(
                        state: bluetooth.state,
                        deviceName: bluetooth.connectedDevice?.displayName,
                        onTap: () =>
                            Navigator.pushNamed(context, '/connection'),
                      ),

                      const SizedBox(height: 40),

                      // Audio visualizer
                      GestureDetector(
                        onTap: _toggleListening,
                        child: AudioVisualizer(
                          soundLevel: speech.soundLevel,
                          isActive: speech.isListening,
                          activeColor: Colors.red,
                          inactiveColor: bluetooth.isConnected
                              ? Colors.blue
                              : Colors.grey,
                          size: 200,
                        ),
                      ),

                      const SizedBox(height: 24),

                      // Status text
                      Text(
                        _getStatusText(speech, bluetooth),
                        style: Theme.of(
                          context,
                        ).textTheme.titleLarge?.copyWith(color: Colors.white70),
                      ),

                      // Error message
                      if (speech.hasError) ...[
                        const SizedBox(height: 8),
                        Padding(
                          padding: const EdgeInsets.symmetric(horizontal: 24),
                          child: Text(
                            speech.errorMessage!,
                            style: const TextStyle(color: Colors.red),
                            textAlign: TextAlign.center,
                          ),
                        ),
                      ],

                      const Spacer(),

                      // Transcription display
                      TranscriptionDisplay(
                        text: speech.currentText,
                        isListening: speech.isListening,
                      ),

                      const SizedBox(height: 24),

                      // Waveform at bottom
                      WaveformVisualizer(
                        soundLevel: speech.soundLevel,
                        isActive: speech.isListening,
                        color: speech.isListening ? Colors.red : Colors.blue,
                        height: 40,
                        barCount: 30,
                      ),

                      const SizedBox(height: 24),
                    ],
                  ),
                ),
              ],
            );
          },
        ),
      ),
    );
  }

  Widget _buildAppBar(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(8.0),
      child: Row(
        children: [
          const SizedBox(width: 8),
          Text('Speech2Prompt', style: Theme.of(context).textTheme.titleLarge),
          const Spacer(),
          IconButton(
            icon: const Icon(Icons.settings),
            onPressed: () => Navigator.pushNamed(context, '/settings'),
          ),
        ],
      ),
    );
  }

  String _getStatusText(SpeechService speech, BleService bluetooth) {
    if (!bluetooth.isConnected) {
      return 'Connect to start';
    }
    if (speech.isListening) {
      return 'Listening...';
    }
    if (speech.isPaused) {
      return 'Paused - Tap to resume';
    }
    return 'Tap to speak';
  }
}
