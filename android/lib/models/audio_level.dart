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
