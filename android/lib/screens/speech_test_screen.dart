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
