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
    Duration sendDelay = const Duration(milliseconds: 50),
  }) : _sendMessage = sendMessage,
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
    // Append trailing space so consecutive messages don't create joined words
    final message = Message.text('$text ');
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
