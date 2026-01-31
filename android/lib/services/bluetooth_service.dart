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
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter_bluetooth_serial/flutter_bluetooth_serial.dart';

import '../models/bluetooth_device.dart';
import '../models/connection_state.dart';
import '../models/message.dart';
import '../utils/encryption.dart';

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

  /// Check if device is already paired (has stored credentials).
  Future<bool> isDevicePaired(String address) async {
    // Import needed: import 'secure_storage_service.dart';
    // This will be implemented when secure_storage_service is imported
    // For now, return false to always require pairing
    return false;
  }

  /// Load crypto context from stored pairing.
  Future<bool> loadStoredPairing(String address) async {
    // Import needed: import 'secure_storage_service.dart';
    // This will be implemented when secure_storage_service is imported
    return false;
  }

  /// Complete pairing and store credentials.
  Future<void> completePairing(String pin, String linuxDeviceId) async {
    // Import needed: import 'secure_storage_service.dart';
    _deviceId ??= generateDeviceId();
    
    // Derive key from PIN
    final key = deriveKey(pin, _deviceId!, linuxDeviceId);
    _crypto = CryptoContext.fromKey(key);
    
    // TODO: Store pairing info once SecureStorageService is imported
    // final device = PairedDevice(
    //   address: _connectedDevice!.address,
    //   name: _connectedDevice!.displayName,
    //   linuxDeviceId: linuxDeviceId,
    //   sharedSecret: base64.encode(key),
    //   pairedAt: DateTime.now(),
    // );
    // await SecureStorageService.storePairedDevice(device);
    
    debugPrint('BluetoothService: Pairing stored for ${_connectedDevice?.displayName}');
    
    _setState(BtConnectionState.connected);
  }

  @override
  void dispose() {
    disconnect();
    super.dispose();
  }
}
