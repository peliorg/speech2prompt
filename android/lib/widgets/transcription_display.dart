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

/// Widget to display current transcription with animation.
class TranscriptionDisplay extends StatelessWidget {
  final String text;
  final bool isListening;
  final String placeholder;

  const TranscriptionDisplay({
    super.key,
    required this.text,
    required this.isListening,
    this.placeholder = 'Your speech will appear here...',
  });

  @override
  Widget build(BuildContext context) {
    final hasText = text.isNotEmpty;

    return AnimatedContainer(
      duration: const Duration(milliseconds: 200),
      margin: const EdgeInsets.symmetric(horizontal: 24),
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: hasText
            ? Theme.of(context).colorScheme.primaryContainer.withOpacity(0.3)
            : Colors.white.withOpacity(0.05),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: hasText
              ? Theme.of(context).colorScheme.primary.withOpacity(0.3)
              : Colors.white.withOpacity(0.1),
          width: 1,
        ),
      ),
      child: Column(
        children: [
          // Label
          Row(
            children: [
              Icon(
                isListening ? Icons.hearing : Icons.text_fields,
                size: 16,
                color: Colors.white54,
              ),
              const SizedBox(width: 8),
              Text(
                isListening ? 'Listening...' : 'Last transcription',
                style: const TextStyle(
                  color: Colors.white54,
                  fontSize: 12,
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          // Text content
          AnimatedSwitcher(
            duration: const Duration(milliseconds: 150),
            child: Text(
              hasText ? text : placeholder,
              key: ValueKey(text),
              style: TextStyle(
                fontSize: hasText ? 18 : 16,
                color: hasText ? Colors.white : Colors.white38,
                fontStyle: hasText ? FontStyle.normal : FontStyle.italic,
              ),
              textAlign: TextAlign.center,
              maxLines: 5,
              overflow: TextOverflow.ellipsis,
            ),
          ),
        ],
      ),
    );
  }
}

/// Compact version for displaying in app bar or elsewhere.
class CompactTranscription extends StatelessWidget {
  final String text;
  final int maxLength;

  const CompactTranscription({
    super.key,
    required this.text,
    this.maxLength = 50,
  });

  @override
  Widget build(BuildContext context) {
    if (text.isEmpty) return const SizedBox.shrink();

    final displayText = text.length > maxLength
        ? '${text.substring(0, maxLength)}...'
        : text;

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      decoration: BoxDecoration(
        color: Colors.black26,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Text(
        displayText,
        style: const TextStyle(fontSize: 12),
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
      ),
    );
  }
}
