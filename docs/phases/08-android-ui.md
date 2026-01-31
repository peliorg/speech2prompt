# Phase 08: Android UI Implementation

## Overview

This phase polishes the Android app UI with professional visualizations, settings screen, and a cohesive user experience. We'll create an audio visualizer, improve the home screen, and add a settings page.

## Prerequisites

- Phase 01-07 completed
- App functional with speech recognition and Bluetooth

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        UI Components                             │
│  ┌─────────────────┐  ┌──────────────────┐  ┌───────────────┐  │
│  │ Audio Visualizer│  │ Connection       │  │ Settings      │  │
│  │                 │  │ Badge            │  │ Screen        │  │
│  │ - Waveform      │  │                  │  │               │  │
│  │ - Pulse         │  │ - Status color   │  │ - Language    │  │
│  │ - Levels        │  │ - Device name    │  │ - Commands    │  │
│  └─────────────────┘  └──────────────────┘  │ - Appearance  │  │
│                                              └───────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

---

## Task 1: Create Audio Visualizer Widget

Create `android/lib/widgets/audio_visualizer.dart`:

```dart
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
```

---

## Task 2: Create Connection Badge Widget

Create `android/lib/widgets/connection_badge.dart`:

```dart
import 'package:flutter/material.dart';

import '../models/connection_state.dart';

/// Badge showing Bluetooth connection status.
class ConnectionBadge extends StatelessWidget {
  final BtConnectionState state;
  final String? deviceName;
  final VoidCallback? onTap;

  const ConnectionBadge({
    super.key,
    required this.state,
    this.deviceName,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final color = _getColor();
    final icon = _getIcon();
    final text = _getText();

    return GestureDetector(
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 200),
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
        decoration: BoxDecoration(
          color: color.withOpacity(0.15),
          borderRadius: BorderRadius.circular(24),
          border: Border.all(
            color: color.withOpacity(0.3),
            width: 1,
          ),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            // Status indicator dot
            Container(
              width: 8,
              height: 8,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: color,
                boxShadow: [
                  BoxShadow(
                    color: color.withOpacity(0.5),
                    blurRadius: 4,
                    spreadRadius: 1,
                  ),
                ],
              ),
            ),
            const SizedBox(width: 8),
            // Icon
            Icon(icon, color: color, size: 18),
            const SizedBox(width: 8),
            // Text
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  text,
                  style: TextStyle(
                    color: color,
                    fontWeight: FontWeight.w500,
                    fontSize: 13,
                  ),
                ),
                if (deviceName != null && state.isConnected)
                  Text(
                    deviceName!,
                    style: TextStyle(
                      color: color.withOpacity(0.7),
                      fontSize: 11,
                    ),
                  ),
              ],
            ),
            const SizedBox(width: 4),
            // Arrow or spinner
            if (state.isConnecting)
              SizedBox(
                width: 14,
                height: 14,
                child: CircularProgressIndicator(
                  strokeWidth: 2,
                  valueColor: AlwaysStoppedAnimation(color),
                ),
              )
            else
              Icon(
                Icons.chevron_right,
                color: color.withOpacity(0.7),
                size: 18,
              ),
          ],
        ),
      ),
    );
  }

  Color _getColor() {
    switch (state) {
      case BtConnectionState.connected:
        return Colors.green;
      case BtConnectionState.connecting:
      case BtConnectionState.reconnecting:
      case BtConnectionState.awaitingPairing:
        return Colors.orange;
      case BtConnectionState.failed:
        return Colors.red;
      case BtConnectionState.disconnected:
        return Colors.grey;
    }
  }

  IconData _getIcon() {
    switch (state) {
      case BtConnectionState.connected:
        return Icons.bluetooth_connected;
      case BtConnectionState.connecting:
      case BtConnectionState.reconnecting:
        return Icons.bluetooth_searching;
      case BtConnectionState.awaitingPairing:
        return Icons.lock_outline;
      case BtConnectionState.failed:
        return Icons.bluetooth_disabled;
      case BtConnectionState.disconnected:
        return Icons.bluetooth;
    }
  }

  String _getText() {
    switch (state) {
      case BtConnectionState.connected:
        return 'Connected';
      case BtConnectionState.connecting:
        return 'Connecting';
      case BtConnectionState.reconnecting:
        return 'Reconnecting';
      case BtConnectionState.awaitingPairing:
        return 'Pairing';
      case BtConnectionState.failed:
        return 'Failed';
      case BtConnectionState.disconnected:
        return 'Tap to connect';
    }
  }
}
```

---

## Task 3: Create Transcription Display Widget

Create `android/lib/widgets/transcription_display.dart`:

```dart
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
```

---

## Task 4: Create Settings Screen

Create `android/lib/screens/settings_screen.dart`:

```dart
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../services/speech_service.dart';
import '../services/bluetooth_service.dart';

/// Settings screen for app configuration.
class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  // Voice command toggles
  bool _enterEnabled = true;
  bool _selectAllEnabled = true;
  bool _copyEnabled = true;
  bool _pasteEnabled = true;
  bool _cutEnabled = true;
  bool _cancelEnabled = true;

  // Other settings
  bool _keepScreenOn = true;
  bool _showPartialResults = true;
  bool _autoReconnect = true;

  @override
  void initState() {
    super.initState();
    _loadSettings();
  }

  Future<void> _loadSettings() async {
    final prefs = await SharedPreferences.getInstance();
    setState(() {
      _enterEnabled = prefs.getBool('cmd_enter') ?? true;
      _selectAllEnabled = prefs.getBool('cmd_select_all') ?? true;
      _copyEnabled = prefs.getBool('cmd_copy') ?? true;
      _pasteEnabled = prefs.getBool('cmd_paste') ?? true;
      _cutEnabled = prefs.getBool('cmd_cut') ?? true;
      _cancelEnabled = prefs.getBool('cmd_cancel') ?? true;
      _keepScreenOn = prefs.getBool('keep_screen_on') ?? true;
      _showPartialResults = prefs.getBool('show_partial') ?? true;
      _autoReconnect = prefs.getBool('auto_reconnect') ?? true;
    });
  }

  Future<void> _saveSetting(String key, bool value) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(key, value);
  }

  @override
  Widget build(BuildContext context) {
    final speechService = context.read<SpeechService>();

    return Scaffold(
      appBar: AppBar(
        title: const Text('Settings'),
      ),
      body: ListView(
        children: [
          // Speech Recognition Section
          _buildSectionHeader('Speech Recognition'),
          _buildLanguageTile(speechService),
          SwitchListTile(
            title: const Text('Show partial results'),
            subtitle: const Text('Display text as you speak'),
            value: _showPartialResults,
            onChanged: (value) {
              setState(() => _showPartialResults = value);
              _saveSetting('show_partial', value);
            },
          ),
          SwitchListTile(
            title: const Text('Keep screen on'),
            subtitle: const Text('Prevent screen from sleeping while listening'),
            value: _keepScreenOn,
            onChanged: (value) {
              setState(() => _keepScreenOn = value);
              _saveSetting('keep_screen_on', value);
            },
          ),

          const Divider(),

          // Voice Commands Section
          _buildSectionHeader('Voice Commands'),
          _buildCommandTile(
            'New line / Enter',
            'Say "new line" or "enter"',
            _enterEnabled,
            (value) {
              setState(() => _enterEnabled = value);
              _saveSetting('cmd_enter', value);
            },
          ),
          _buildCommandTile(
            'Select all',
            'Say "select all"',
            _selectAllEnabled,
            (value) {
              setState(() => _selectAllEnabled = value);
              _saveSetting('cmd_select_all', value);
            },
          ),
          _buildCommandTile(
            'Copy',
            'Say "copy that"',
            _copyEnabled,
            (value) {
              setState(() => _copyEnabled = value);
              _saveSetting('cmd_copy', value);
            },
          ),
          _buildCommandTile(
            'Paste',
            'Say "paste"',
            _pasteEnabled,
            (value) {
              setState(() => _pasteEnabled = value);
              _saveSetting('cmd_paste', value);
            },
          ),
          _buildCommandTile(
            'Cut',
            'Say "cut that"',
            _cutEnabled,
            (value) {
              setState(() => _cutEnabled = value);
              _saveSetting('cmd_cut', value);
            },
          ),
          _buildCommandTile(
            'Cancel',
            'Say "cancel" or "clear"',
            _cancelEnabled,
            (value) {
              setState(() => _cancelEnabled = value);
              _saveSetting('cmd_cancel', value);
            },
          ),

          const Divider(),

          // Connection Section
          _buildSectionHeader('Connection'),
          SwitchListTile(
            title: const Text('Auto-reconnect'),
            subtitle: const Text('Automatically reconnect if connection is lost'),
            value: _autoReconnect,
            onChanged: (value) {
              setState(() => _autoReconnect = value);
              _saveSetting('auto_reconnect', value);
            },
          ),
          ListTile(
            title: const Text('Paired device'),
            subtitle: Consumer<BluetoothService>(
              builder: (context, bt, _) {
                return Text(
                  bt.connectedDevice?.displayName ?? 'Not connected',
                );
              },
            ),
            trailing: const Icon(Icons.chevron_right),
            onTap: () => Navigator.pushNamed(context, '/connection'),
          ),

          const Divider(),

          // About Section
          _buildSectionHeader('About'),
          ListTile(
            title: const Text('Version'),
            subtitle: const Text('1.0.0'),
          ),
          ListTile(
            title: const Text('Help & Feedback'),
            trailing: const Icon(Icons.open_in_new),
            onTap: () {
              // Open help URL
            },
          ),
        ],
      ),
    );
  }

  Widget _buildSectionHeader(String title) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
      child: Text(
        title,
        style: TextStyle(
          color: Theme.of(context).colorScheme.primary,
          fontWeight: FontWeight.w600,
          fontSize: 14,
        ),
      ),
    );
  }

  Widget _buildLanguageTile(SpeechService speechService) {
    return ListTile(
      title: const Text('Language'),
      subtitle: Text(speechService.selectedLocale),
      trailing: const Icon(Icons.chevron_right),
      onTap: () => _showLanguagePicker(speechService),
    );
  }

  Future<void> _showLanguagePicker(SpeechService speechService) async {
    final locales = speechService.availableLocales;
    
    final selected = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Select Language'),
        content: SizedBox(
          width: double.maxFinite,
          child: ListView.builder(
            shrinkWrap: true,
            itemCount: locales.length,
            itemBuilder: (context, index) {
              final locale = locales[index];
              final isSelected = locale.localeId == speechService.selectedLocale;
              return ListTile(
                title: Text(locale.name),
                subtitle: Text(locale.localeId),
                trailing: isSelected ? const Icon(Icons.check) : null,
                onTap: () => Navigator.pop(context, locale.localeId),
              );
            },
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel'),
          ),
        ],
      ),
    );

    if (selected != null) {
      speechService.setLocale(selected);
    }
  }

  Widget _buildCommandTile(
    String title,
    String subtitle,
    bool value,
    ValueChanged<bool> onChanged,
  ) {
    return SwitchListTile(
      title: Text(title),
      subtitle: Text(subtitle),
      value: value,
      onChanged: onChanged,
    );
  }
}
```

---

## Task 5: Update Home Screen with New Widgets

Replace `android/lib/screens/home_screen.dart`:

```dart
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import '../services/speech_service.dart';
import '../services/bluetooth_service.dart';
import '../services/command_processor.dart';
import '../models/message.dart';
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
  bool _keepScreenOn = true;

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
    final bluetoothService = context.read<BluetoothService>();
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
    final bluetoothService = context.read<BluetoothService>();

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
        child: Consumer2<SpeechService, BluetoothService>(
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
                        onTap: () => Navigator.pushNamed(context, '/connection'),
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
                        style: Theme.of(context).textTheme.titleLarge?.copyWith(
                              color: Colors.white70,
                            ),
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
          Text(
            'Speech2Code',
            style: Theme.of(context).textTheme.titleLarge,
          ),
          const Spacer(),
          IconButton(
            icon: const Icon(Icons.settings),
            onPressed: () => Navigator.pushNamed(context, '/settings'),
          ),
        ],
      ),
    );
  }

  String _getStatusText(SpeechService speech, BluetoothService bluetooth) {
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
```

---

## Task 6: Update Routes and App Theme

Update `android/lib/main.dart`:

```dart
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import 'screens/home_screen.dart';
import 'screens/speech_test_screen.dart';
import 'screens/connection_screen.dart';
import 'screens/bluetooth_test_screen.dart';
import 'screens/settings_screen.dart';
import 'services/speech_service.dart';
import 'services/bluetooth_service.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  
  // Set preferred orientations
  SystemChrome.setPreferredOrientations([
    DeviceOrientation.portraitUp,
    DeviceOrientation.portraitDown,
  ]);
  
  // Set system UI overlay style
  SystemChrome.setSystemUIOverlayStyle(
    const SystemUiOverlayStyle(
      statusBarColor: Colors.transparent,
      statusBarIconBrightness: Brightness.light,
      systemNavigationBarColor: Color(0xFF1A1A2E),
      systemNavigationBarIconBrightness: Brightness.light,
    ),
  );
  
  runApp(const Speech2CodeApp());
}

class Speech2CodeApp extends StatelessWidget {
  const Speech2CodeApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MultiProvider(
      providers: [
        ChangeNotifierProvider(create: (_) => SpeechService()),
        ChangeNotifierProvider(create: (_) => BluetoothService()..initialize()),
      ],
      child: MaterialApp(
        title: 'Speech2Code',
        debugShowCheckedModeBanner: false,
        theme: _buildTheme(),
        initialRoute: '/',
        routes: {
          '/': (context) => const HomeScreen(),
          '/speech-test': (context) => const SpeechTestScreen(),
          '/connection': (context) => const ConnectionScreen(),
          '/bluetooth-test': (context) => const BluetoothTestScreen(),
          '/settings': (context) => const SettingsScreen(),
        },
      ),
    );
  }

  ThemeData _buildTheme() {
    const primaryColor = Color(0xFF4A90D9);
    const backgroundColor = Color(0xFF1A1A2E);
    const surfaceColor = Color(0xFF252541);
    const errorColor = Color(0xFFE94560);

    return ThemeData(
      useMaterial3: true,
      brightness: Brightness.dark,
      colorScheme: ColorScheme.dark(
        primary: primaryColor,
        secondary: const Color(0xFF64B5F6),
        surface: surfaceColor,
        error: errorColor,
        onPrimary: Colors.white,
        onSecondary: Colors.white,
        onSurface: Colors.white,
        onError: Colors.white,
      ),
      scaffoldBackgroundColor: backgroundColor,
      appBarTheme: const AppBarTheme(
        backgroundColor: Colors.transparent,
        elevation: 0,
        centerTitle: false,
        titleTextStyle: TextStyle(
          fontSize: 20,
          fontWeight: FontWeight.w600,
          color: Colors.white,
        ),
      ),
      cardTheme: CardTheme(
        color: surfaceColor,
        elevation: 4,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(12),
        ),
      ),
      listTileTheme: const ListTileThemeData(
        iconColor: Colors.white70,
        textColor: Colors.white,
      ),
      switchTheme: SwitchThemeData(
        thumbColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.selected)) {
            return primaryColor;
          }
          return Colors.grey;
        }),
        trackColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.selected)) {
            return primaryColor.withOpacity(0.5);
          }
          return Colors.grey.withOpacity(0.3);
        }),
      ),
      elevatedButtonTheme: ElevatedButtonThemeData(
        style: ElevatedButton.styleFrom(
          backgroundColor: primaryColor,
          foregroundColor: Colors.white,
          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(8),
          ),
        ),
      ),
      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(
          foregroundColor: primaryColor,
        ),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: surfaceColor,
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(8),
          borderSide: BorderSide.none,
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(8),
          borderSide: const BorderSide(color: primaryColor, width: 2),
        ),
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      ),
      dividerTheme: const DividerThemeData(
        color: Colors.white12,
        thickness: 1,
      ),
      snackBarTheme: SnackBarThemeData(
        backgroundColor: surfaceColor,
        contentTextStyle: const TextStyle(color: Colors.white),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(8),
        ),
        behavior: SnackBarBehavior.floating,
      ),
    );
  }
}
```

---

## Task 7: Create Widget Exports

Create `android/lib/widgets/widgets.dart`:

```dart
export 'audio_visualizer.dart';
export 'connection_badge.dart';
export 'transcription_display.dart';
```

---

## Testing

### Build and Run

```bash
cd /home/dan/workspace/priv/speech2code/android
flutter run
```

### Test UI Components

1. **Home Screen**
   - Verify audio visualizer animates when speaking
   - Verify connection badge shows correct status
   - Verify transcription display updates

2. **Settings Screen**
   - Toggle voice commands on/off
   - Change language
   - Verify settings persist

3. **Visual Polish**
   - Check animations are smooth
   - Verify colors and theme are consistent
   - Test in light and dark environments

---

## Verification Checklist

- [ ] Audio visualizer responds to sound levels
- [ ] Connection badge shows correct states
- [ ] Transcription display updates in real-time
- [ ] Settings screen loads and saves preferences
- [ ] Language picker works
- [ ] Voice command toggles function
- [ ] Theme is consistent across screens
- [ ] Animations are smooth
- [ ] Screen orientation locked to portrait

## Output Artifacts

After completing this phase:

1. **AudioVisualizer** - Animated microphone button with ripples
2. **WaveformVisualizer** - Horizontal bar waveform
3. **ConnectionBadge** - Status indicator widget
4. **TranscriptionDisplay** - Text preview widget
5. **SettingsScreen** - Full settings UI
6. **Polished theme** - Consistent dark theme

## Next Phase

Proceed to **Phase 09: Integration & Security** to complete the end-to-end pairing flow and ensure encryption works correctly.
