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
import 'package:flutter_blue_plus/flutter_blue_plus.dart';

import '../models/ble_device.dart';
import '../models/connection_state.dart';
import '../models/message.dart';
import '../utils/ble_constants.dart';
import '../utils/encryption.dart';
import '../utils/packet_assembler.dart';
import 'secure_storage_service.dart';

/// Callback when connection state changes.
typedef ConnectionStateCallback = void Function(BtConnectionState state);

/// Callback when a message is received.
typedef MessageReceivedCallback = void Function(Message message);

/// Service for managing BLE connection to desktop.
class BleService extends ChangeNotifier {
  // BLE state
  BluetoothDevice? _connectedDevice;
  BtConnectionState _state = BtConnectionState.disconnected;
  BleDeviceInfo? _connectedDeviceInfo;
  String? _errorMessage;

  // GATT characteristics
  BluetoothCharacteristic? _commandRxChar;
  BluetoothCharacteristic? _responseTxChar;
  BluetoothCharacteristic? _statusChar;

  // BLE configuration
  int _negotiatedMtu = BleConfig.defaultMtu;

  // Message handling
  final PacketReassembler _packetReassembler = PacketReassembler();
  final List<Message> _pendingMessages = [];
  final Map<int, Completer<bool>> _ackWaiters = {};
  Timer? _heartbeatTimer;
  Timer? _reconnectTimer;

  // Crypto context (set after pairing)
  CryptoContext? _crypto;
  String? _deviceId;

  // Pending PIN for pairing (stored temporarily until PAIR_ACK arrives)
  String? _pendingPairingPin;

  // Configuration
  int _reconnectAttempts = 0;
  static const int _maxReconnectAttempts = 5;
  static const Duration _heartbeatInterval = Duration(seconds: 5);

  // Callbacks
  ConnectionStateCallback? onStateChanged;
  MessageReceivedCallback? onMessageReceived;

  // Stream subscriptions
  StreamSubscription<BluetoothConnectionState>? _connectionStateSubscription;
  StreamSubscription<List<int>>? _responseTxSubscription;
  StreamSubscription<List<int>>? _statusSubscription;

  // Getters
  BtConnectionState get state => _state;
  bool get isConnected => _state == BtConnectionState.connected;
  BleDeviceInfo? get connectedDevice => _connectedDeviceInfo;
  String? get errorMessage => _errorMessage;
  bool get hasError => _errorMessage != null;
  bool get isPaired => _crypto != null;
  String? get deviceId => _deviceId;
  int get negotiatedMtu => _negotiatedMtu;

  /// Initialize the service.
  Future<void> initialize() async {
    // Generate device ID if not already set
    _deviceId ??= generateDeviceId();
    debugPrint('BleService: Device ID: $_deviceId');

    // Check if BLE is supported
    if (await FlutterBluePlus.isSupported == false) {
      throw Exception('BLE is not supported on this device');
    }
  }

  /// Check if Bluetooth is enabled.
  Future<bool> isBluetoothEnabled() async {
    final state = await FlutterBluePlus.adapterState.first;
    return state == BluetoothAdapterState.on;
  }

  /// Request to enable Bluetooth.
  Future<bool> requestEnableBluetooth() async {
    try {
      if (defaultTargetPlatform == TargetPlatform.android) {
        await FlutterBluePlus.turnOn();
      }
      return true;
    } catch (e) {
      debugPrint('BleService: Error enabling Bluetooth: $e');
      return false;
    }
  }

  /// Start scanning for BLE devices with Speech2Code service.
  Future<Stream<List<BleDeviceInfo>>> startScan() async {
    debugPrint('BleService: Starting BLE scan...');

    // Check if Bluetooth is on
    if (!await isBluetoothEnabled()) {
      throw Exception('Bluetooth is not enabled');
    }

    // Start scanning with service filter
    await FlutterBluePlus.startScan(
      withServices: [Guid(speech2codeServiceUuid)],
      timeout: BleConfig.scanTimeout,
    );

    // Return transformed stream
    return FlutterBluePlus.scanResults.map((results) {
      return results
          .map((r) => BleDeviceInfo.fromScanResult(r))
          .where((d) => d.hasS2CService) // Only Speech2Code devices
          .toList();
    });
  }

  /// Stop scanning.
  Future<void> stopScan() async {
    await FlutterBluePlus.stopScan();
    debugPrint('BleService: Scan stopped');
  }

  /// Connect to a BLE device.
  Future<bool> connect(BleDeviceInfo deviceInfo) async {
    if (_state == BtConnectionState.connecting) {
      debugPrint('BleService: Already connecting');
      return false;
    }

    _setState(BtConnectionState.connecting);
    _errorMessage = null;
    _reconnectAttempts = 0;

    try {
      debugPrint('BleService: Connecting to ${deviceInfo.displayName}...');

      final device = deviceInfo.device;

      // Connect with timeout (flutter_blue_plus 2.x requires license param)
      await device.connect(
        license: License.free,
        timeout: BleConfig.connectionTimeout,
        autoConnect: false,
        mtu: null, // Request MTU separately after connection
      );

      // Wait for connection to be established
      await device.connectionState
          .where((state) => state == BluetoothConnectionState.connected)
          .first
          .timeout(BleConfig.connectionTimeout);

      _connectedDevice = device;
      _connectedDeviceInfo = deviceInfo;

      // Request higher MTU
      try {
        _negotiatedMtu = await device.requestMtu(BleConfig.targetMtu);
        debugPrint('BleService: Negotiated MTU: $_negotiatedMtu');
      } catch (e) {
        debugPrint('BleService: MTU negotiation failed, using default: $e');
        _negotiatedMtu = BleConfig.defaultMtu;
      }

      // Discover services
      debugPrint('BleService: Discovering services...');
      final services = await device.discoverServices();

      // Find Speech2Code service
      final s2cService = services.firstWhere(
        (s) =>
            s.uuid.toString().toLowerCase() ==
            speech2codeServiceUuid.toLowerCase(),
        orElse: () => throw Exception('Speech2Code service not found'),
      );

      // Get characteristics
      for (final char in s2cService.characteristics) {
        final uuid = char.uuid.toString().toLowerCase();
        switch (uuid) {
          case commandRxCharUuid:
            _commandRxChar = char;
            debugPrint('BleService: Found Command RX characteristic');
            break;
          case responseTxCharUuid:
            _responseTxChar = char;
            debugPrint('BleService: Found Response TX characteristic');
            break;
          case statusCharUuid:
            _statusChar = char;
            debugPrint('BleService: Found Status characteristic');
            break;
        }
      }

      // Verify we have required characteristics
      if (_commandRxChar == null || _responseTxChar == null) {
        throw Exception('Required characteristics not found');
      }

      // Subscribe to notifications
      await _setupNotifications();

      // Monitor connection state
      _setupConnectionMonitoring();

      _setState(BtConnectionState.connected);
      _startHeartbeat();

      debugPrint('BleService: Connected to ${deviceInfo.displayName}');

      // Always send pairing request - desktop requires PAIR_REQ to authenticate
      await _sendPairingRequest();

      return true;
    } catch (e) {
      debugPrint('BleService: Connection error: $e');
      _errorMessage = e.toString();
      _setState(BtConnectionState.failed);
      await _cleanup();
      return false;
    }
  }

  /// Set up notification subscriptions.
  Future<void> _setupNotifications() async {
    // Subscribe to Response TX characteristic
    if (_responseTxChar != null) {
      await _responseTxChar!.setNotifyValue(true);
      _responseTxSubscription = _responseTxChar!.lastValueStream.listen(
        _onNotificationReceived,
        onError: (error) {
          debugPrint('BleService: Response TX error: $error');
        },
      );
      debugPrint('BleService: Subscribed to Response TX');
    }

    // Subscribe to Status characteristic
    if (_statusChar != null) {
      await _statusChar!.setNotifyValue(true);
      _statusSubscription = _statusChar!.lastValueStream.listen(
        _onStatusChanged,
        onError: (error) {
          debugPrint('BleService: Status error: $error');
        },
      );
      debugPrint('BleService: Subscribed to Status');
    }
  }

  /// Set up connection state monitoring.
  void _setupConnectionMonitoring() {
    _connectionStateSubscription?.cancel();
    _connectionStateSubscription = _connectedDevice!.connectionState.listen(
      (state) {
        debugPrint('BleService: Connection state changed to $state');
        if (state == BluetoothConnectionState.disconnected) {
          _handleDisconnection();
        }
      },
      onError: (error) {
        debugPrint('BleService: Connection state error: $error');
        _handleDisconnection();
      },
    );
  }

  /// Handle incoming notifications from Response TX characteristic.
  void _onNotificationReceived(List<int> data) {
    final completeMessage = _packetReassembler.addPacket(
      Uint8List.fromList(data),
    );

    if (completeMessage != null) {
      try {
        final json = utf8.decode(completeMessage);
        final message = Message.fromJson(json);
        _handleReceivedMessage(message);
      } catch (e) {
        debugPrint('BleService: Parse error: $e');
      }
    }
  }

  /// Handle status characteristic changes.
  void _onStatusChanged(List<int> data) {
    if (data.isEmpty) return;

    final statusCode = data[0];
    debugPrint(
      'BleService: Status changed to 0x${statusCode.toRadixString(16)}',
    );

    // Map status codes
    switch (statusCode) {
      case 0x00: // Idle
        break;
      case 0x01: // Awaiting pairing
        _setState(BtConnectionState.awaitingPairing);
        break;
      case 0x02: // Paired
        if (_state == BtConnectionState.awaitingPairing) {
          _setState(BtConnectionState.connected);
        }
        break;
      case 0x03: // Busy
        break;
      case 0xFF: // Error
        break;
    }
  }

  /// Handle a received message.
  void _handleReceivedMessage(Message message) {
    debugPrint('BleService: Received: ${message.messageType.value}');

    // Verify and decrypt if we have crypto
    // Note: ACK and PAIR_ACK are excluded - ACK has no payload to verify,
    // and PAIR_ACK contains the Linux device ID needed to derive the key
    if (_crypto != null &&
        message.messageType != MessageType.ack &&
        message.messageType != MessageType.pairAck) {
      try {
        _crypto!.verifyAndDecrypt(message);
      } catch (e) {
        debugPrint('BleService: Verification failed: $e');
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
  /// NOTE: PAIR_ACK is NOT signed because Android needs the Linux device ID
  /// from the payload to derive the shared key (chicken-and-egg problem).
  void _handlePairAck(Message message) {
    try {
      final payload = PairAckPayload.fromJson(message.payload);
      if (payload.status == PairStatus.ok) {
        final linuxDeviceId = payload.deviceId;
        if (linuxDeviceId.isEmpty) {
          debugPrint('BleService: PAIR_ACK missing Linux device ID');
          _errorMessage = 'Invalid pairing response';
          _setState(BtConnectionState.failed);
          return;
        }

        // Clear any stale crypto context before deriving new key
        _crypto = null;

        // Complete pairing using the stored PIN and received Linux device ID
        if (_pendingPairingPin != null) {
          _completePairingWithLinuxId(_pendingPairingPin!, linuxDeviceId);
          _pendingPairingPin = null;
        } else {
          debugPrint('BleService: No pending PIN for pairing');
          _errorMessage = 'Pairing PIN not set';
          _setState(BtConnectionState.failed);
          return;
        }

        debugPrint('BleService: Pairing successful with $linuxDeviceId');
        _setState(BtConnectionState.connected);
        _sendPendingMessages();
      } else {
        debugPrint('BleService: Pairing failed: ${payload.error}');
        _errorMessage = payload.error ?? 'Pairing failed';
        _pendingPairingPin = null;
        _setState(BtConnectionState.failed);
      }
    } catch (e) {
      debugPrint('BleService: Error parsing PAIR_ACK: $e');
      _pendingPairingPin = null;
    }
  }

  /// Internal method to complete pairing with the Linux device ID from PAIR_ACK.
  Future<void> _completePairingWithLinuxId(
    String pin,
    String linuxDeviceId,
  ) async {
    _deviceId ??= generateDeviceId();

    // Derive key from PIN
    final key = deriveKey(pin, _deviceId!, linuxDeviceId);
    _crypto = CryptoContext.fromKey(key);

    // Store pairing info in secure storage
    if (_connectedDeviceInfo != null) {
      final pairedDevice = PairedDevice(
        address: _connectedDeviceInfo!.device.remoteId.toString(),
        name: _connectedDeviceInfo!.displayName,
        linuxDeviceId: linuxDeviceId,
        sharedSecret: base64Encode(key),
        pairedAt: DateTime.now(),
      );
      await SecureStorageService.storePairedDevice(pairedDevice);
      debugPrint(
        'BleService: Pairing stored for ${_connectedDeviceInfo?.displayName}',
      );
    }
  }

  /// Send a message to the desktop.
  Future<bool> sendMessage(Message message) async {
    if (!isConnected || _commandRxChar == null) {
      debugPrint('BleService: Not connected, queueing message');
      _pendingMessages.add(message);
      return false;
    }

    try {
      // Sign and encrypt if we have crypto context
      if (_crypto != null) {
        _crypto!.signAndEncrypt(message);
      }

      final json = message.toJson();
      debugPrint('BleService: Sending: ${json.trim()}');

      final jsonBytes = utf8.encode(json);

      // Chunk into BLE packets
      final packets = PacketAssembler.chunkMessage(jsonBytes, _negotiatedMtu);

      // Send each packet
      final useWriteWithoutResponse = message.messageType == MessageType.text;
      for (final packet in packets) {
        await _commandRxChar!.write(
          packet,
          withoutResponse: useWriteWithoutResponse,
        );
      }

      // Wait for ACK if needed
      if (message.messageType != MessageType.ack &&
          message.messageType != MessageType.heartbeat) {
        return await _waitForAck(message.timestamp);
      }

      return true;
    } catch (e) {
      debugPrint('BleService: Send error: $e');
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

  /// Wait for ACK with timeout.
  Future<bool> _waitForAck(int timestamp) async {
    final completer = Completer<bool>();
    _ackWaiters[timestamp] = completer;

    try {
      return await completer.future.timeout(
        BleConfig.ackTimeout,
        onTimeout: () {
          _ackWaiters.remove(timestamp);
          debugPrint('BleService: ACK timeout for $timestamp');
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

    final wasConnected = _connectedDevice != null;

    if (wasConnected && _reconnectAttempts < _maxReconnectAttempts) {
      _setState(BtConnectionState.reconnecting);
      _scheduleReconnect();
    } else {
      _cleanup();
      _setState(BtConnectionState.disconnected);
    }
  }

  /// Schedule a reconnection attempt.
  void _scheduleReconnect() {
    _reconnectTimer?.cancel();

    final delay = Duration(
      seconds: 1 << _reconnectAttempts,
    ); // Exponential backoff
    debugPrint('BleService: Reconnecting in ${delay.inSeconds}s...');

    _reconnectTimer = Timer(delay, () async {
      if (_connectedDeviceInfo != null) {
        _reconnectAttempts++;
        await connect(_connectedDeviceInfo!);
      }
    });
  }

  /// Disconnect from current device.
  Future<void> disconnect() async {
    _reconnectTimer?.cancel();
    _heartbeatTimer?.cancel();

    await _cleanup();

    if (_connectedDevice != null) {
      try {
        await _connectedDevice!.disconnect();
      } catch (e) {
        debugPrint('BleService: Error disconnecting: $e');
      }
    }

    _connectedDevice = null;
    _connectedDeviceInfo = null;
    _setState(BtConnectionState.disconnected);

    debugPrint('BleService: Disconnected');
  }

  /// Clean up subscriptions and characteristics.
  Future<void> _cleanup() async {
    await _connectionStateSubscription?.cancel();
    await _responseTxSubscription?.cancel();
    await _statusSubscription?.cancel();

    _connectionStateSubscription = null;
    _responseTxSubscription = null;
    _statusSubscription = null;

    _commandRxChar = null;
    _responseTxChar = null;
    _statusChar = null;

    _packetReassembler.reset();
  }

  /// Set crypto context after PIN entry.
  void setPairingPin(String pin, String linuxDeviceId) {
    _crypto = CryptoContext.fromPin(pin, _deviceId!, linuxDeviceId);
    debugPrint('BleService: Crypto context set');
  }

  /// Clear the crypto context (used when fresh pairing is needed).
  void clearCryptoContext() {
    _crypto = null;
    debugPrint('BleService: Crypto context cleared');
  }

  /// Store the PIN temporarily for pairing completion.
  /// The actual pairing will be completed when PAIR_ACK arrives with the Linux device ID.
  void storePendingPairingPin(String pin) {
    _pendingPairingPin = pin;
    debugPrint('BleService: Pending pairing PIN stored');
  }

  /// Complete pairing and store credentials.
  /// This is now called internally by _handlePairAck when it receives the Linux device ID.
  Future<void> completePairing(String pin, String linuxDeviceId) async {
    _deviceId ??= generateDeviceId();

    // Derive key from PIN
    final key = deriveKey(pin, _deviceId!, linuxDeviceId);
    _crypto = CryptoContext.fromKey(key);

    // Store pairing info in secure storage
    if (_connectedDeviceInfo != null) {
      final pairedDevice = PairedDevice(
        address: _connectedDeviceInfo!.device.remoteId.toString(),
        name: _connectedDeviceInfo!.displayName,
        linuxDeviceId: linuxDeviceId,
        sharedSecret: base64Encode(key),
        pairedAt: DateTime.now(),
      );
      await SecureStorageService.storePairedDevice(pairedDevice);
      debugPrint(
        'BleService: Pairing stored for ${_connectedDeviceInfo?.displayName}',
      );
    }

    _setState(BtConnectionState.connected);
  }

  /// Set state and notify listeners.
  void _setState(BtConnectionState newState) {
    if (_state == newState) return;

    _state = newState;
    notifyListeners();
    onStateChanged?.call(newState);

    debugPrint('BleService: State changed to $newState');
  }

  @override
  void dispose() {
    disconnect();
    super.dispose();
  }
}
