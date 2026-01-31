# Phase 07: Android Bluetooth Client

## Overview

This phase implements the Bluetooth client on Android using the `flutter_bluetooth_serial` package. The client connects to the Linux desktop's RFCOMM server and sends transcribed text and commands.

## Prerequisites

- Phase 01-02, 06 completed
- Android device with Bluetooth
- Linux desktop with Bluetooth server running (Phase 03)
- Devices paired via Android Bluetooth settings

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                      BluetoothService                            │
│  ┌─────────────────┐  ┌──────────────────┐  ┌───────────────┐  │
│  │ Device Scanner  │  │ Connection       │  │ Message       │  │
│  │                 │──│ Manager          │──│ Queue         │  │
│  │ - Paired list   │  │                  │  │               │  │
│  │ - Discovery     │  │ - Connect        │  │ - Send        │  │
│  │                 │  │ - Reconnect      │  │ - Retry       │  │
│  └─────────────────┘  │ - Heartbeat      │  │ - Ack wait    │  │
│                       └──────────────────┘  └───────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
                    ┌───────────────────────┐
                    │   Protocol Layer      │
                    │   - Encrypt/Decrypt   │
                    │   - Sign/Verify       │
                    │   - Serialize         │
                    └───────────────────────┘
```

---

## Task 1: Verify Dependencies

Ensure `android/pubspec.yaml` has:

```yaml
dependencies:
  # Bluetooth
  flutter_bluetooth_serial: ^0.4.0
  
  # ... other dependencies
```

Run:
```bash
cd /home/dan/workspace/priv/speech2code/android
flutter pub get
```

---

## Task 2: Create Bluetooth Device Model

Create `android/lib/models/bluetooth_device.dart`:

```dart
import 'package:flutter_bluetooth_serial/flutter_bluetooth_serial.dart';

/// Wrapper for Bluetooth device info.
class BluetoothDeviceInfo {
  final String name;
  final String address;
  final bool isPaired;
  final bool isConnected;
  final BluetoothDeviceType type;

  BluetoothDeviceInfo({
    required this.name,
    required this.address,
    this.isPaired = false,
    this.isConnected = false,
    this.type = BluetoothDeviceType.unknown,
  });

  factory BluetoothDeviceInfo.fromDevice(BluetoothDevice device) {
    return BluetoothDeviceInfo(
      name: device.name ?? 'Unknown',
      address: device.address,
      isPaired: device.isBonded,
      isConnected: device.isConnected,
      type: device.type,
    );
  }

  /// Display name (name or address if name is unknown).
  String get displayName => name.isNotEmpty ? name : address;

  /// Check if this looks like a Speech2Code server.
  bool get isSpeech2Code => 
      name.toLowerCase().contains('speech2code') ||
      name.toLowerCase().contains('speech 2 code');

  @override
  String toString() => 'BluetoothDeviceInfo($displayName, $address)';

  @override
  bool operator ==(Object other) =>
      other is BluetoothDeviceInfo && other.address == address;

  @override
  int get hashCode => address.hashCode;
}
```

---

## Task 3: Create Connection State

Create `android/lib/models/connection_state.dart`:

```dart
/// Bluetooth connection state.
enum BtConnectionState {
  /// Not connected, not trying to connect.
  disconnected,
  
  /// Currently trying to connect.
  connecting,
  
  /// Connected and ready.
  connected,
  
  /// Waiting for pairing PIN entry.
  awaitingPairing,
  
  /// Connection lost, will retry.
  reconnecting,
  
  /// Connection failed, not retrying.
  failed,
}

extension BtConnectionStateExtension on BtConnectionState {
  bool get isConnected => this == BtConnectionState.connected;
  bool get isDisconnected => this == BtConnectionState.disconnected;
  bool get isConnecting => 
      this == BtConnectionState.connecting || 
      this == BtConnectionState.reconnecting;
  bool get canConnect => 
      this == BtConnectionState.disconnected || 
      this == BtConnectionState.failed;
  
  String get displayText {
    switch (this) {
      case BtConnectionState.disconnected:
        return 'Disconnected';
      case BtConnectionState.connecting:
        return 'Connecting...';
      case BtConnectionState.connected:
        return 'Connected';
      case BtConnectionState.awaitingPairing:
        return 'Pairing...';
      case BtConnectionState.reconnecting:
        return 'Reconnecting...';
      case BtConnectionState.failed:
        return 'Connection Failed';
    }
  }
}
```

---

## Task 4: Implement Bluetooth Service

Replace `android/lib/services/bluetooth_service.dart`:

```dart
import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/foundation.dart';
import 'package:flutter_bluetooth_serial/flutter_bluetooth_serial.dart';

import '../models/bluetooth_device.dart';
import '../models/connection_state.dart';
import '../models/message.dart';
import '../utils/encryption.dart';
import '../utils/constants.dart';

/// Callback when connection state changes.
typedef ConnectionStateCallback = void Function(BtConnectionState state);

/// Callback when a message is received.
typedef MessageReceivedCallback = void Function(Message message);

/// Service for managing Bluetooth connection to desktop.
class BluetoothService extends ChangeNotifier {
  final FlutterBluetoothSerial _bluetooth = FlutterBluetoothSerial.instance;
  
  // Connection state
  BluetoothConnection? _connection;
  BtConnectionState _state = BtConnectionState.disconnected;
  BluetoothDeviceInfo? _connectedDevice;
  String? _errorMessage;
  
  // Crypto context (set after pairing)
  CryptoContext? _crypto;
  String? _deviceId;
  
  // Message handling
  final List<Message> _pendingMessages = [];
  final Map<int, Completer<bool>> _ackWaiters = {};
  Timer? _heartbeatTimer;
  Timer? _reconnectTimer;
  
  // Configuration
  int _reconnectAttempts = 0;
  static const int _maxReconnectAttempts = 5;
  static const Duration _heartbeatInterval = Duration(seconds: 5);
  static const Duration _ackTimeout = Duration(seconds: 5);
  
  // Callbacks
  ConnectionStateCallback? onStateChanged;
  MessageReceivedCallback? onMessageReceived;
  
  // Stream for incoming data
  StreamSubscription<Uint8List>? _dataSubscription;
  String _receiveBuffer = '';

  // Getters
  BtConnectionState get state => _state;
  bool get isConnected => _state == BtConnectionState.connected;
  BluetoothDeviceInfo? get connectedDevice => _connectedDevice;
  String? get errorMessage => _errorMessage;
  bool get hasError => _errorMessage != null;
  bool get isPaired => _crypto != null;
  String? get deviceId => _deviceId;

  /// Initialize the service.
  Future<void> initialize() async {
    // Generate device ID if not already set
    _deviceId ??= generateDeviceId();
    debugPrint('BluetoothService: Device ID: $_deviceId');
  }

  /// Get list of paired Bluetooth devices.
  Future<List<BluetoothDeviceInfo>> getPairedDevices() async {
    try {
      final devices = await _bluetooth.getBondedDevices();
      return devices
          .map((d) => BluetoothDeviceInfo.fromDevice(d))
          .toList();
    } catch (e) {
      debugPrint('BluetoothService: Error getting paired devices: $e');
      return [];
    }
  }

  /// Check if Bluetooth is enabled.
  Future<bool> isBluetoothEnabled() async {
    return await _bluetooth.isEnabled ?? false;
  }

  /// Request to enable Bluetooth.
  Future<bool> requestEnableBluetooth() async {
    return await _bluetooth.requestEnable() ?? false;
  }

  /// Connect to a device.
  Future<bool> connect(BluetoothDeviceInfo device) async {
    if (_state == BtConnectionState.connecting) {
      debugPrint('BluetoothService: Already connecting');
      return false;
    }

    _setState(BtConnectionState.connecting);
    _errorMessage = null;
    _reconnectAttempts = 0;

    try {
      debugPrint('BluetoothService: Connecting to ${device.address}...');
      
      _connection = await BluetoothConnection.toAddress(device.address)
          .timeout(
            const Duration(seconds: 10),
            onTimeout: () => throw TimeoutException('Connection timeout'),
          );

      if (_connection == null || !_connection!.isConnected) {
        throw Exception('Connection failed');
      }

      _connectedDevice = device;
      _setupDataListener();
      _startHeartbeat();
      _setState(BtConnectionState.connected);
      
      debugPrint('BluetoothService: Connected to ${device.displayName}');
      
      // Send pairing request if not yet paired
      if (_crypto == null) {
        await _sendPairingRequest();
      }
      
      return true;
    } catch (e) {
      debugPrint('BluetoothService: Connection error: $e');
      _errorMessage = e.toString();
      _setState(BtConnectionState.failed);
      return false;
    }
  }

  /// Disconnect from current device.
  Future<void> disconnect() async {
    _reconnectTimer?.cancel();
    _heartbeatTimer?.cancel();
    _dataSubscription?.cancel();
    
    try {
      await _connection?.close();
    } catch (e) {
      debugPrint('BluetoothService: Error closing connection: $e');
    }
    
    _connection = null;
    _connectedDevice = null;
    _setState(BtConnectionState.disconnected);
    
    debugPrint('BluetoothService: Disconnected');
  }

  /// Set crypto context after PIN entry.
  void setPairingPin(String pin, String linuxDeviceId) {
    _crypto = CryptoContext.fromPin(pin, _deviceId!, linuxDeviceId);
    debugPrint('BluetoothService: Crypto context set');
  }

  /// Send a message to the desktop.
  Future<bool> sendMessage(Message message) async {
    if (!isConnected || _connection == null) {
      debugPrint('BluetoothService: Not connected, queueing message');
      _pendingMessages.add(message);
      return false;
    }

    try {
      // Sign and encrypt if we have crypto context
      if (_crypto != null) {
        _crypto!.signAndEncrypt(message);
      }

      final json = message.toJson();
      debugPrint('BluetoothService: Sending: ${json.trim()}');
      
      _connection!.output.add(Uint8List.fromList(utf8.encode(json)));
      await _connection!.output.allSent;

      // Wait for ACK
      if (message.messageType != MessageType.ack &&
          message.messageType != MessageType.heartbeat) {
        return await _waitForAck(message.timestamp);
      }

      return true;
    } catch (e) {
      debugPrint('BluetoothService: Send error: $e');
      _handleDisconnection();
      return false;
    }
  }

  /// Send queued messages.
  Future<void> _sendPendingMessages() async {
    while (_pendingMessages.isNotEmpty && isConnected) {
      final message = _pendingMessages.removeAt(0);
      await sendMessage(message);
    }
  }

  /// Send a pairing request.
  Future<void> _sendPairingRequest() async {
    final payload = PairRequestPayload(
      deviceId: _deviceId!,
      deviceName: 'Android Device',
    );
    final message = Message.pairRequest(payload);
    await sendMessage(message);
    _setState(BtConnectionState.awaitingPairing);
  }

  /// Set up listener for incoming data.
  void _setupDataListener() {
    _dataSubscription?.cancel();
    _receiveBuffer = '';

    _dataSubscription = _connection!.input!.listen(
      (data) {
        _receiveBuffer += utf8.decode(data);
        _processReceivedData();
      },
      onDone: () {
        debugPrint('BluetoothService: Connection closed');
        _handleDisconnection();
      },
      onError: (error) {
        debugPrint('BluetoothService: Receive error: $error');
        _handleDisconnection();
      },
    );
  }

  /// Process received data buffer.
  void _processReceivedData() {
    while (_receiveBuffer.contains('\n')) {
      final newlineIndex = _receiveBuffer.indexOf('\n');
      final line = _receiveBuffer.substring(0, newlineIndex);
      _receiveBuffer = _receiveBuffer.substring(newlineIndex + 1);

      if (line.trim().isEmpty) continue;

      try {
        final message = Message.fromJson(line);
        _handleReceivedMessage(message);
      } catch (e) {
        debugPrint('BluetoothService: Parse error: $e');
      }
    }
  }

  /// Handle a received message.
  void _handleReceivedMessage(Message message) {
    debugPrint('BluetoothService: Received: ${message.messageType.value}');

    // Verify and decrypt if we have crypto
    if (_crypto != null && message.messageType != MessageType.ack) {
      try {
        _crypto!.verifyAndDecrypt(message);
      } catch (e) {
        debugPrint('BluetoothService: Verification failed: $e');
        return;
      }
    }

    switch (message.messageType) {
      case MessageType.ack:
        _handleAck(message);
        break;
      case MessageType.pairAck:
        _handlePairAck(message);
        break;
      case MessageType.heartbeat:
        // Respond with ACK
        sendMessage(Message.ack(message.timestamp));
        break;
      default:
        onMessageReceived?.call(message);
    }
  }

  /// Handle ACK message.
  void _handleAck(Message message) {
    final timestamp = int.tryParse(message.payload);
    if (timestamp != null && _ackWaiters.containsKey(timestamp)) {
      _ackWaiters[timestamp]!.complete(true);
      _ackWaiters.remove(timestamp);
    }
  }

  /// Handle pairing acknowledgment.
  void _handlePairAck(Message message) {
    try {
      final payload = PairAckPayload.fromJson(message.payload);
      if (payload.status == PairStatus.ok) {
        debugPrint('BluetoothService: Pairing successful');
        _setState(BtConnectionState.connected);
        _sendPendingMessages();
      } else {
        debugPrint('BluetoothService: Pairing failed: ${payload.error}');
        _errorMessage = payload.error ?? 'Pairing failed';
        _setState(BtConnectionState.failed);
      }
    } catch (e) {
      debugPrint('BluetoothService: Error parsing PAIR_ACK: $e');
    }
  }

  /// Wait for ACK with timeout.
  Future<bool> _waitForAck(int timestamp) async {
    final completer = Completer<bool>();
    _ackWaiters[timestamp] = completer;

    try {
      return await completer.future.timeout(
        _ackTimeout,
        onTimeout: () {
          _ackWaiters.remove(timestamp);
          debugPrint('BluetoothService: ACK timeout for $timestamp');
          return false;
        },
      );
    } catch (e) {
      _ackWaiters.remove(timestamp);
      return false;
    }
  }

  /// Start heartbeat timer.
  void _startHeartbeat() {
    _heartbeatTimer?.cancel();
    _heartbeatTimer = Timer.periodic(_heartbeatInterval, (_) {
      if (isConnected) {
        sendMessage(Message.heartbeat());
      }
    });
  }

  /// Handle disconnection.
  void _handleDisconnection() {
    _heartbeatTimer?.cancel();
    _dataSubscription?.cancel();
    
    final wasConnected = _connectedDevice != null;
    _connection = null;
    
    if (wasConnected && _reconnectAttempts < _maxReconnectAttempts) {
      _setState(BtConnectionState.reconnecting);
      _scheduleReconnect();
    } else {
      _connectedDevice = null;
      _setState(BtConnectionState.disconnected);
    }
  }

  /// Schedule a reconnection attempt.
  void _scheduleReconnect() {
    _reconnectTimer?.cancel();
    
    final delay = Duration(seconds: 1 << _reconnectAttempts); // Exponential backoff
    debugPrint('BluetoothService: Reconnecting in ${delay.inSeconds}s...');
    
    _reconnectTimer = Timer(delay, () async {
      if (_connectedDevice != null) {
        _reconnectAttempts++;
        await connect(_connectedDevice!);
      }
    });
  }

  /// Set state and notify listeners.
  void _setState(BtConnectionState newState) {
    if (_state == newState) return;
    
    _state = newState;
    notifyListeners();
    onStateChanged?.call(newState);
    
    debugPrint('BluetoothService: State changed to $newState');
  }

  @override
  void dispose() {
    disconnect();
    super.dispose();
  }
}
```

---

## Task 5: Create Connection Screen

Create `android/lib/screens/connection_screen.dart`:

```dart
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/bluetooth_device.dart';
import '../models/connection_state.dart';
import '../services/bluetooth_service.dart';
import '../services/permission_service.dart';

/// Screen for managing Bluetooth connections.
class ConnectionScreen extends StatefulWidget {
  const ConnectionScreen({super.key});

  @override
  State<ConnectionScreen> createState() => _ConnectionScreenState();
}

class _ConnectionScreenState extends State<ConnectionScreen> {
  List<BluetoothDeviceInfo> _devices = [];
  bool _isLoading = false;
  bool _bluetoothEnabled = false;

  @override
  void initState() {
    super.initState();
    _checkBluetoothAndLoadDevices();
  }

  Future<void> _checkBluetoothAndLoadDevices() async {
    final bluetooth = context.read<BluetoothService>();
    
    // Check permissions
    final hasPermission = await PermissionService.hasBluetoothPermissions();
    if (!hasPermission) {
      await PermissionService.requestBluetoothPermissions();
    }

    // Check if Bluetooth is enabled
    _bluetoothEnabled = await bluetooth.isBluetoothEnabled();
    if (!_bluetoothEnabled) {
      setState(() {});
      return;
    }

    await _loadDevices();
  }

  Future<void> _loadDevices() async {
    setState(() {
      _isLoading = true;
    });

    final bluetooth = context.read<BluetoothService>();
    _devices = await bluetooth.getPairedDevices();

    // Sort: Speech2Code devices first, then by name
    _devices.sort((a, b) {
      if (a.isSpeech2Code && !b.isSpeech2Code) return -1;
      if (!a.isSpeech2Code && b.isSpeech2Code) return 1;
      return a.displayName.compareTo(b.displayName);
    });

    setState(() {
      _isLoading = false;
    });
  }

  Future<void> _enableBluetooth() async {
    final bluetooth = context.read<BluetoothService>();
    final enabled = await bluetooth.requestEnableBluetooth();
    if (enabled) {
      _bluetoothEnabled = true;
      await _loadDevices();
    }
  }

  Future<void> _connectToDevice(BluetoothDeviceInfo device) async {
    final bluetooth = context.read<BluetoothService>();
    
    if (bluetooth.isConnected && bluetooth.connectedDevice?.address == device.address) {
      // Already connected to this device
      Navigator.pop(context);
      return;
    }

    // Disconnect if connected to another device
    if (bluetooth.isConnected) {
      await bluetooth.disconnect();
    }

    // Connect
    final success = await bluetooth.connect(device);
    
    if (success && mounted) {
      // Check if pairing is needed
      if (bluetooth.state == BtConnectionState.awaitingPairing) {
        await _showPairingDialog(bluetooth);
      }
      
      if (bluetooth.isConnected && mounted) {
        Navigator.pop(context);
      }
    }
  }

  Future<void> _showPairingDialog(BluetoothService bluetooth) async {
    final pinController = TextEditingController();
    
    final pin = await showDialog<String>(
      context: context,
      barrierDismissible: false,
      builder: (context) => AlertDialog(
        title: const Text('Pairing'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text('Enter the 6-digit PIN shown on your computer:'),
            const SizedBox(height: 16),
            TextField(
              controller: pinController,
              keyboardType: TextInputType.number,
              maxLength: 6,
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 24, letterSpacing: 8),
              decoration: const InputDecoration(
                hintText: '000000',
                counterText: '',
              ),
              autofocus: true,
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel'),
          ),
          ElevatedButton(
            onPressed: () {
              final pin = pinController.text;
              if (pin.length == 6) {
                Navigator.pop(context, pin);
              }
            },
            child: const Text('Pair'),
          ),
        ],
      ),
    );

    if (pin != null && pin.length == 6) {
      // For now, use a placeholder Linux device ID
      // In real implementation, this comes from the PAIR_ACK
      bluetooth.setPairingPin(pin, 'linux-placeholder');
    } else {
      await bluetooth.disconnect();
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Connect to Computer'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: _bluetoothEnabled ? _loadDevices : null,
          ),
        ],
      ),
      body: Consumer<BluetoothService>(
        builder: (context, bluetooth, child) {
          if (!_bluetoothEnabled) {
            return _buildBluetoothDisabled();
          }

          if (_isLoading) {
            return const Center(child: CircularProgressIndicator());
          }

          if (_devices.isEmpty) {
            return _buildNoDevices();
          }

          return _buildDeviceList(bluetooth);
        },
      ),
    );
  }

  Widget _buildBluetoothDisabled() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(Icons.bluetooth_disabled, size: 64),
            const SizedBox(height: 16),
            const Text(
              'Bluetooth is disabled',
              style: TextStyle(fontSize: 18),
            ),
            const SizedBox(height: 8),
            const Text(
              'Enable Bluetooth to connect to your computer.',
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 24),
            ElevatedButton.icon(
              onPressed: _enableBluetooth,
              icon: const Icon(Icons.bluetooth),
              label: const Text('Enable Bluetooth'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildNoDevices() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(Icons.devices, size: 64),
            const SizedBox(height: 16),
            const Text(
              'No paired devices',
              style: TextStyle(fontSize: 18),
            ),
            const SizedBox(height: 8),
            const Text(
              'Pair your computer in Android Bluetooth settings, then return here.',
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 24),
            ElevatedButton.icon(
              onPressed: () async {
                await PermissionService.openSettings();
              },
              icon: const Icon(Icons.settings),
              label: const Text('Open Settings'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildDeviceList(BluetoothService bluetooth) {
    return ListView.builder(
      itemCount: _devices.length,
      itemBuilder: (context, index) {
        final device = _devices[index];
        final isConnected = bluetooth.connectedDevice?.address == device.address;
        final isConnecting = bluetooth.state.isConnecting && isConnected;

        return ListTile(
          leading: Icon(
            device.isSpeech2Code ? Icons.computer : Icons.bluetooth,
            color: isConnected ? Colors.green : null,
          ),
          title: Text(device.displayName),
          subtitle: Text(
            isConnected
                ? bluetooth.state.displayText
                : device.address,
          ),
          trailing: isConnecting
              ? const SizedBox(
                  width: 24,
                  height: 24,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : isConnected
                  ? const Icon(Icons.check_circle, color: Colors.green)
                  : null,
          onTap: isConnecting ? null : () => _connectToDevice(device),
        );
      },
    );
  }
}
```

---

## Task 6: Create Bluetooth Test Screen

Create `android/lib/screens/bluetooth_test_screen.dart`:

```dart
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/message.dart';
import '../services/bluetooth_service.dart';

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
    
    final bluetooth = context.read<BluetoothService>();
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

    final bluetooth = context.read<BluetoothService>();
    final message = Message.text(text);
    
    _addLog('TX: TEXT - $text');
    final success = await bluetooth.sendMessage(message);
    _addLog(success ? 'Sent successfully' : 'Send failed');
    
    _textController.clear();
  }

  Future<void> _sendCommand(String command) async {
    final bluetooth = context.read<BluetoothService>();
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
      body: Consumer<BluetoothService>(
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

  Widget _commandButton(String command, BluetoothService bluetooth) {
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
```

---

## Task 7: Update Routes

Update `android/lib/main.dart`:

```dart
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'screens/home_screen.dart';
import 'screens/speech_test_screen.dart';
import 'screens/connection_screen.dart';
import 'screens/bluetooth_test_screen.dart';
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
        ChangeNotifierProvider(create: (_) => BluetoothService()..initialize()),
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
          '/connection': (context) => const ConnectionScreen(),
          '/bluetooth-test': (context) => const BluetoothTestScreen(),
        },
      ),
    );
  }
}
```

---

## Task 8: Update Home Screen

Update `android/lib/screens/home_screen.dart` to include Bluetooth status:

```dart
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../services/speech_service.dart';
import '../services/bluetooth_service.dart';

/// Main screen with microphone control and status.
class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final speechService = context.watch<SpeechService>();
    final bluetoothService = context.watch<BluetoothService>();

    return Scaffold(
      appBar: AppBar(
        title: const Text('Speech2Code'),
        actions: [
          // Connection status
          IconButton(
            icon: Icon(
              bluetoothService.isConnected
                  ? Icons.bluetooth_connected
                  : Icons.bluetooth,
              color: bluetoothService.isConnected ? Colors.green : null,
            ),
            onPressed: () {
              Navigator.pushNamed(context, '/connection');
            },
          ),
          // Settings
          PopupMenuButton<String>(
            onSelected: (value) {
              switch (value) {
                case 'speech-test':
                  Navigator.pushNamed(context, '/speech-test');
                  break;
                case 'bluetooth-test':
                  Navigator.pushNamed(context, '/bluetooth-test');
                  break;
              }
            },
            itemBuilder: (context) => [
              const PopupMenuItem(
                value: 'speech-test',
                child: Text('Speech Test'),
              ),
              const PopupMenuItem(
                value: 'bluetooth-test',
                child: Text('Bluetooth Test'),
              ),
            ],
          ),
        ],
      ),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            // Connection status
            GestureDetector(
              onTap: () => Navigator.pushNamed(context, '/connection'),
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                decoration: BoxDecoration(
                  color: bluetoothService.isConnected
                      ? Colors.green.withOpacity(0.2)
                      : Colors.red.withOpacity(0.2),
                  borderRadius: BorderRadius.circular(20),
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(
                      bluetoothService.isConnected
                          ? Icons.bluetooth_connected
                          : Icons.bluetooth_disabled,
                      color: bluetoothService.isConnected
                          ? Colors.green
                          : Colors.red,
                    ),
                    const SizedBox(width: 8),
                    Text(
                      bluetoothService.isConnected
                          ? bluetoothService.connectedDevice?.displayName ?? 'Connected'
                          : 'Tap to connect',
                    ),
                  ],
                ),
              ),
            ),

            const SizedBox(height: 48),

            // Microphone button with sound level indicator
            Stack(
              alignment: Alignment.center,
              children: [
                // Sound level ring
                if (speechService.isListening)
                  TweenAnimationBuilder<double>(
                    tween: Tween(begin: 0, end: speechService.soundLevel),
                    duration: const Duration(milliseconds: 100),
                    builder: (context, value, child) {
                      return Container(
                        width: 150 + (value * 50),
                        height: 150 + (value * 50),
                        decoration: BoxDecoration(
                          shape: BoxShape.circle,
                          border: Border.all(
                            color: Colors.blue.withOpacity(0.3),
                            width: 3,
                          ),
                        ),
                      );
                    },
                  ),
                // Microphone button
                GestureDetector(
                  onTap: () {
                    if (!bluetoothService.isConnected) {
                      ScaffoldMessenger.of(context).showSnackBar(
                        const SnackBar(
                          content: Text('Connect to a computer first'),
                        ),
                      );
                      return;
                    }
                    speechService.toggleListening();
                  },
                  child: Container(
                    width: 150,
                    height: 150,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: speechService.isListening
                          ? Colors.red
                          : bluetoothService.isConnected
                              ? Colors.blue
                              : Colors.grey,
                      boxShadow: [
                        BoxShadow(
                          color: (speechService.isListening
                                  ? Colors.red
                                  : Colors.blue)
                              .withOpacity(0.5),
                          blurRadius: 20,
                          spreadRadius: 5,
                        ),
                      ],
                    ),
                    child: Icon(
                      speechService.isListening ? Icons.mic : Icons.mic_none,
                      size: 64,
                      color: Colors.white,
                    ),
                  ),
                ),
              ],
            ),

            const SizedBox(height: 24),

            Text(
              speechService.isListening
                  ? 'Listening...'
                  : bluetoothService.isConnected
                      ? 'Tap to speak'
                      : 'Connect to start',
              style: Theme.of(context).textTheme.titleLarge,
            ),

            if (speechService.hasError) ...[
              const SizedBox(height: 8),
              Text(
                speechService.errorMessage!,
                style: const TextStyle(color: Colors.red),
              ),
            ],

            const SizedBox(height: 48),

            // Transcription preview
            Container(
              margin: const EdgeInsets.symmetric(horizontal: 24),
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: Colors.white.withOpacity(0.1),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Text(
                speechService.currentText.isEmpty
                    ? 'Your speech will appear here...'
                    : speechService.currentText,
                style: Theme.of(context).textTheme.bodyLarge,
                textAlign: TextAlign.center,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
```

---

## Testing

### Prerequisites

1. Run Linux desktop app (Phase 03-05)
2. Pair Android device with Linux PC via Bluetooth settings
3. Note the PC shows up as "Speech2Code"

### Test Bluetooth Connection

```bash
cd /home/dan/workspace/priv/speech2code/android
flutter run
```

1. Open app on Android
2. Tap Bluetooth icon or connection status
3. Select "Speech2Code" from list
4. Enter PIN if prompted
5. Verify connection establishes

### Test Message Sending

1. Go to Bluetooth Test screen
2. Enter text and tap "Send"
3. Verify text appears on Linux terminal/logs
4. Test command buttons

### Integration Test

1. Connect to Linux PC
2. Tap microphone to start listening
3. Speak "Hello world new line"
4. Verify:
   - "Hello world" typed on PC
   - Enter key pressed
5. Say "Stop listening"
6. Verify recognition pauses

---

## Troubleshooting

### "Connection failed"

- Ensure devices are paired in Android settings
- Check Linux app is running
- Try unpairing and re-pairing
- Restart Bluetooth on both devices

### "No paired devices"

- Pair via Android Settings > Bluetooth
- Ensure Linux adapter is discoverable

### "Send failed"

- Check connection is established
- Verify Linux app is receiving
- Check for encryption mismatches

---

## Verification Checklist

- [ ] Paired devices listed correctly
- [ ] Connect to Linux desktop works
- [ ] Connection state updates correctly
- [ ] Reconnection works after disconnect
- [ ] TEXT messages sent successfully
- [ ] COMMAND messages sent successfully
- [ ] Heartbeat keeps connection alive
- [ ] Pairing flow works
- [ ] Error handling works

## Output Artifacts

After completing this phase:

1. **BluetoothService** - Connection management and message sending
2. **ConnectionScreen** - Device selection UI
3. **BluetoothTestScreen** - Manual testing interface
4. **Message queue** - Handles offline buffering
5. **Reconnection logic** - Auto-reconnect on disconnect

## Next Phase

Proceed to **Phase 08: Android UI Implementation** to polish the user interface with visualizations and settings.
