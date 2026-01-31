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

import '../models/message.dart';
import '../models/connection_state.dart';
import '../services/ble_service.dart';

/// Test screen for Bluetooth functionality.
class BluetoothTestScreen extends StatefulWidget {
  const BluetoothTestScreen({super.key});

  @override
  State<BluetoothTestScreen> createState() => _BluetoothTestScreenState();
}

class _BluetoothTestScreenState extends State<BluetoothTestScreen> {
  final _textController = TextEditingController();
  final List<String> _log = [];

  @override
  void initState() {
    super.initState();
    
    final bluetooth = context.read<BleService>();
    bluetooth.onMessageReceived = (message) {
      _addLog('RX: ${message.messageType.value} - ${message.payload}');
    };
    bluetooth.onStateChanged = (state) {
      _addLog('State: ${state.displayText}');
    };
  }

  void _addLog(String message) {
    setState(() {
      _log.insert(0, '${DateTime.now().toString().substring(11, 19)} $message');
      if (_log.length > 100) {
        _log.removeLast();
      }
    });
  }

  Future<void> _sendText() async {
    final text = _textController.text.trim();
    if (text.isEmpty) return;

    final bluetooth = context.read<BleService>();
    final message = Message.text(text);
    
    _addLog('TX: TEXT - $text');
    final success = await bluetooth.sendMessage(message);
    _addLog(success ? 'Sent successfully' : 'Send failed');
    
    _textController.clear();
  }

  Future<void> _sendCommand(String command) async {
    final bluetooth = context.read<BleService>();
    final message = Message.command(command);
    
    _addLog('TX: COMMAND - $command');
    final success = await bluetooth.sendMessage(message);
    _addLog(success ? 'Sent successfully' : 'Send failed');
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Bluetooth Test'),
        actions: [
          IconButton(
            icon: const Icon(Icons.bluetooth_searching),
            onPressed: () {
              Navigator.pushNamed(context, '/connection');
            },
          ),
        ],
      ),
      body: Consumer<BleService>(
        builder: (context, bluetooth, child) {
          return Column(
            children: [
              // Status bar
              Container(
                padding: const EdgeInsets.all(12),
                color: bluetooth.isConnected
                    ? Colors.green.withOpacity(0.2)
                    : Colors.red.withOpacity(0.2),
                child: Row(
                  children: [
                    Icon(
                      bluetooth.isConnected
                          ? Icons.bluetooth_connected
                          : Icons.bluetooth_disabled,
                      color: bluetooth.isConnected ? Colors.green : Colors.red,
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            bluetooth.state.displayText,
                            style: const TextStyle(fontWeight: FontWeight.bold),
                          ),
                          if (bluetooth.connectedDevice != null)
                            Text(
                              bluetooth.connectedDevice!.displayName,
                              style: const TextStyle(fontSize: 12),
                            ),
                        ],
                      ),
                    ),
                    if (bluetooth.isConnected)
                      TextButton(
                        onPressed: () => bluetooth.disconnect(),
                        child: const Text('Disconnect'),
                      ),
                  ],
                ),
              ),

              // Text input
              Padding(
                padding: const EdgeInsets.all(12),
                child: Row(
                  children: [
                    Expanded(
                      child: TextField(
                        controller: _textController,
                        decoration: const InputDecoration(
                          hintText: 'Enter text to send',
                          border: OutlineInputBorder(),
                        ),
                        onSubmitted: (_) => _sendText(),
                      ),
                    ),
                    const SizedBox(width: 8),
                    ElevatedButton(
                      onPressed: bluetooth.isConnected ? _sendText : null,
                      child: const Text('Send'),
                    ),
                  ],
                ),
              ),

              // Command buttons
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 12),
                child: Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    _commandButton('ENTER', bluetooth),
                    _commandButton('SELECT_ALL', bluetooth),
                    _commandButton('COPY', bluetooth),
                    _commandButton('PASTE', bluetooth),
                    _commandButton('CUT', bluetooth),
                  ],
                ),
              ),

              const Divider(),

              // Log
              Expanded(
                child: ListView.builder(
                  itemCount: _log.length,
                  itemBuilder: (context, index) {
                    return Padding(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 12,
                        vertical: 2,
                      ),
                      child: Text(
                        _log[index],
                        style: const TextStyle(
                          fontFamily: 'monospace',
                          fontSize: 12,
                        ),
                      ),
                    );
                  },
                ),
              ),
            ],
          );
        },
      ),
    );
  }

  Widget _commandButton(String command, BleService bluetooth) {
    return ElevatedButton(
      onPressed: bluetooth.isConnected ? () => _sendCommand(command) : null,
      child: Text(command),
    );
  }

  @override
  void dispose() {
    _textController.dispose();
    super.dispose();
  }
}
