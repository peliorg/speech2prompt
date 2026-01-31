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
import 'package:flutter/material.dart';

/// Animated audio visualizer that responds to sound levels.
class AudioVisualizer extends StatefulWidget {
  final double soundLevel;
  final bool isActive;
  final Color activeColor;
  final Color inactiveColor;
  final double size;

  const AudioVisualizer({
    super.key,
    required this.soundLevel,
    required this.isActive,
    this.activeColor = Colors.red,
    this.inactiveColor = Colors.blue,
    this.size = 200,
  });

  @override
  State<AudioVisualizer> createState() => _AudioVisualizerState();
}

class _AudioVisualizerState extends State<AudioVisualizer>
    with TickerProviderStateMixin {
  late AnimationController _pulseController;
  late AnimationController _waveController;
  late Animation<double> _pulseAnimation;

  @override
  void initState() {
    super.initState();

    _pulseController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1000),
    );

    _waveController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 2000),
    )..repeat();

    _pulseAnimation = Tween<double>(begin: 1.0, end: 1.15).animate(
      CurvedAnimation(parent: _pulseController, curve: Curves.easeInOut),
    );

    if (widget.isActive) {
      _pulseController.repeat(reverse: true);
    }
  }

  @override
  void didUpdateWidget(AudioVisualizer oldWidget) {
    super.didUpdateWidget(oldWidget);

    if (widget.isActive && !oldWidget.isActive) {
      _pulseController.repeat(reverse: true);
    } else if (!widget.isActive && oldWidget.isActive) {
      _pulseController.stop();
      _pulseController.reset();
    }
  }

  @override
  void dispose() {
    _pulseController.dispose();
    _waveController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final color = widget.isActive ? widget.activeColor : widget.inactiveColor;

    return SizedBox(
      width: widget.size,
      height: widget.size,
      child: Stack(
        alignment: Alignment.center,
        children: [
          // Outer ripple waves
          if (widget.isActive) ...[
            AnimatedBuilder(
              animation: _waveController,
              builder: (context, child) {
                return CustomPaint(
                  size: Size(widget.size, widget.size),
                  painter: _RipplePainter(
                    progress: _waveController.value,
                    color: color.withOpacity(0.3),
                    soundLevel: widget.soundLevel,
                  ),
                );
              },
            ),
          ],

          // Sound level rings
          ...List.generate(3, (index) {
            final delay = index * 0.15;
            final level = (widget.soundLevel - delay).clamp(0.0, 1.0);
            final ringSize = widget.size * 0.6 + (level * widget.size * 0.4);

            return AnimatedContainer(
              duration: const Duration(milliseconds: 100),
              width: ringSize,
              height: ringSize,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                border: Border.all(
                  color: color.withOpacity(0.2 + (level * 0.3)),
                  width: 2,
                ),
              ),
            );
          }),

          // Main circle with pulse
          AnimatedBuilder(
            animation: _pulseAnimation,
            builder: (context, child) {
              final scale = widget.isActive ? _pulseAnimation.value : 1.0;
              return Transform.scale(
                scale: scale,
                child: Container(
                  width: widget.size * 0.5,
                  height: widget.size * 0.5,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: color,
                    boxShadow: [
                      BoxShadow(
                        color: color.withOpacity(0.5),
                        blurRadius: 20 + (widget.soundLevel * 30),
                        spreadRadius: 5 + (widget.soundLevel * 10),
                      ),
                    ],
                  ),
                  child: Icon(
                    widget.isActive ? Icons.mic : Icons.mic_none,
                    size: widget.size * 0.2,
                    color: Colors.white,
                  ),
                ),
              );
            },
          ),
        ],
      ),
    );
  }
}

/// Custom painter for ripple effect.
class _RipplePainter extends CustomPainter {
  final double progress;
  final Color color;
  final double soundLevel;

  _RipplePainter({
    required this.progress,
    required this.color,
    required this.soundLevel,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    final maxRadius = size.width / 2;

    // Draw multiple ripples
    for (int i = 0; i < 3; i++) {
      final rippleProgress = (progress + (i * 0.33)) % 1.0;
      final radius = maxRadius * 0.3 + (maxRadius * 0.7 * rippleProgress);
      final opacity = (1.0 - rippleProgress) * (0.3 + soundLevel * 0.3);

      final paint = Paint()
        ..color = color.withOpacity(opacity.clamp(0.0, 1.0))
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2;

      canvas.drawCircle(center, radius, paint);
    }
  }

  @override
  bool shouldRepaint(_RipplePainter oldDelegate) {
    return progress != oldDelegate.progress ||
        soundLevel != oldDelegate.soundLevel;
  }
}

/// Simple waveform visualizer.
class WaveformVisualizer extends StatelessWidget {
  final double soundLevel;
  final bool isActive;
  final Color color;
  final int barCount;
  final double height;

  const WaveformVisualizer({
    super.key,
    required this.soundLevel,
    required this.isActive,
    this.color = Colors.blue,
    this.barCount = 20,
    this.height = 60,
  });

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: height,
      child: Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: List.generate(barCount, (index) {
          // Create varied heights based on position and sound level
          final position = (index - barCount / 2).abs() / (barCount / 2);
          final baseHeight = 0.3 + (1 - position) * 0.5;
          final variance = math.sin(index * 0.5 + soundLevel * 10) * 0.3;
          final heightFactor = isActive
              ? (baseHeight + variance + soundLevel * 0.5).clamp(0.1, 1.0)
              : 0.1;

          return AnimatedContainer(
            duration: const Duration(milliseconds: 50),
            width: 4,
            height: height * heightFactor,
            margin: const EdgeInsets.symmetric(horizontal: 1),
            decoration: BoxDecoration(
              color: isActive
                  ? color.withOpacity(0.5 + soundLevel * 0.5)
                  : color.withOpacity(0.3),
              borderRadius: BorderRadius.circular(2),
            ),
          );
        }),
      ),
    );
  }
}
