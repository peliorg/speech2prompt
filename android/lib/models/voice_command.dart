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
