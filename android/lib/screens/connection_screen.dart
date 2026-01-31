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

import '../models/ble_device.dart';
import '../models/connection_state.dart';
import '../services/ble_service.dart';
import '../services/permission_service.dart';
import '../services/secure_storage_service.dart';

/// Screen for managing Bluetooth connections.
class ConnectionScreen extends StatefulWidget {
  const ConnectionScreen({super.key});

  @override
  State<ConnectionScreen> createState() => _ConnectionScreenState();
}

class _ConnectionScreenState extends State<ConnectionScreen> {
  List<BleDeviceInfo> _devices = [];
  bool _isLoading = false;
  bool _bluetoothEnabled = false;

  @override
  void initState() {
    super.initState();
    _checkBluetoothAndLoadDevices();
  }

  Future<void> _checkBluetoothAndLoadDevices() async {
    final bluetooth = context.read<BleService>();

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
      _devices = [];
    });

    final bluetooth = context.read<BleService>();

    try {
      // Start BLE scan and listen to results
      final scanStream = await bluetooth.startScan();

      // Collect devices for a few seconds
      final subscription = scanStream.listen((devices) {
        if (mounted) {
          setState(() {
            _devices = devices;
            // Sort: Speech2Prompt devices first, then by name
            _devices.sort((a, b) {
              if (a.isSpeech2Prompt && !b.isSpeech2Prompt) return -1;
              if (!a.isSpeech2Prompt && b.isSpeech2Prompt) return 1;
              return a.displayName.compareTo(b.displayName);
            });
          });
        }
      });

      // Scan for 10 seconds
      await Future.delayed(const Duration(seconds: 10));
      await subscription.cancel();
      await bluetooth.stopScan();
    } catch (e) {
      debugPrint('Error scanning: $e');
    }

    if (mounted) {
      setState(() {
        _isLoading = false;
      });
    }
  }

  Future<void> _enableBluetooth() async {
    final bluetooth = context.read<BleService>();
    final enabled = await bluetooth.requestEnableBluetooth();
    if (enabled) {
      _bluetoothEnabled = true;
      await _loadDevices();
    }
  }

  Future<void> _connectToDevice(BleDeviceInfo device) async {
    final bluetooth = context.read<BleService>();

    if (bluetooth.isConnected &&
        bluetooth.connectedDevice?.deviceId == device.deviceId) {
      Navigator.pop(context);
      return;
    }

    if (bluetooth.isConnected) {
      await bluetooth.disconnect();
    }

    // Check for stored pairing
    final deviceAddress = device.device.remoteId.toString();
    final storedPairing = await SecureStorageService.getPairedDevice(
      deviceAddress,
    );

    if (storedPairing != null) {
      // Connect first - PAIR_REQ will be sent during connection
      // Then set crypto context after connection succeeds
      final success = await bluetooth.connect(device);
      if (success && mounted) {
        // Check if desktop is requesting fresh pairing
        if (bluetooth.state == BtConnectionState.awaitingPairing) {
          // Desktop doesn't recognize us or wants fresh pairing
          // Clear stale crypto and show pairing dialog for fresh pairing
          bluetooth.clearCryptoContext();
          final paired = await _showPairingDialog(bluetooth);
          if (paired && mounted) {
            Navigator.pop(context);
          }
        } else {
          // Desktop accepted reconnection without fresh pairing
          bluetooth.setPairingPin('', storedPairing.linuxDeviceId);
          Navigator.pop(context);
        }
      }
    } else {
      // New pairing needed
      final success = await bluetooth.connect(device);

      if (success && mounted) {
        if (bluetooth.state == BtConnectionState.awaitingPairing) {
          final paired = await _showPairingDialog(bluetooth);
          if (paired && bluetooth.isConnected && mounted) {
            Navigator.pop(context);
          }
        } else if (bluetooth.isConnected) {
          Navigator.pop(context);
        }
      }
    }
  }

  Future<bool> _showPairingDialog(BleService bluetooth) async {
    final pinController = TextEditingController();
    final formKey = GlobalKey<FormState>();

    final result = await showDialog<Map<String, String>>(
      context: context,
      barrierDismissible: false,
      builder: (context) => AlertDialog(
        title: const Text('Secure Pairing'),
        content: Form(
          key: formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'To establish a secure connection, enter the same 6-digit PIN on both devices.',
                style: TextStyle(fontSize: 14),
              ),
              const SizedBox(height: 8),
              const Text(
                'Choose any 6-digit number and enter it here and on your computer.',
                style: TextStyle(fontSize: 12, color: Colors.grey),
              ),
              const SizedBox(height: 20),
              TextFormField(
                controller: pinController,
                keyboardType: TextInputType.number,
                maxLength: 6,
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 28, letterSpacing: 12),
                decoration: const InputDecoration(
                  hintText: '000000',
                  counterText: '',
                  border: OutlineInputBorder(),
                ),
                validator: (value) {
                  if (value == null || value.length != 6) {
                    return 'Enter 6 digits';
                  }
                  if (!RegExp(r'^\d{6}$').hasMatch(value)) {
                    return 'Numbers only';
                  }
                  return null;
                },
                autofocus: true,
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel'),
          ),
          ElevatedButton(
            onPressed: () {
              if (formKey.currentState!.validate()) {
                Navigator.pop(context, {'pin': pinController.text});
              }
            },
            child: const Text('Pair'),
          ),
        ],
      ),
    );

    if (result == null) {
      await bluetooth.disconnect();
      return false;
    }

    final pin = result['pin']!;

    // Show waiting indicator
    if (mounted) {
      showDialog(
        context: context,
        barrierDismissible: false,
        builder: (context) => const AlertDialog(
          content: Row(
            children: [
              CircularProgressIndicator(),
              SizedBox(width: 20),
              Text('Waiting for computer to confirm...'),
            ],
          ),
        ),
      );
    }

    // Store the PIN - actual pairing will complete when PAIR_ACK arrives
    // with the Linux device ID (which is needed to derive the shared key)
    bluetooth.storePendingPairingPin(pin);

    // Wait for PAIR_ACK to arrive and complete the pairing
    // The _handlePairAck method in BleService will handle the actual pairing
    await Future.delayed(const Duration(seconds: 5));

    if (mounted) {
      Navigator.pop(context); // Close waiting dialog
    }

    // Check if pairing succeeded
    return bluetooth.isConnected;
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
      body: Consumer<BleService>(
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
            const Text('Bluetooth is disabled', style: TextStyle(fontSize: 18)),
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
              'No Speech2Prompt servers found',
              style: TextStyle(fontSize: 18),
            ),
            const SizedBox(height: 8),
            const Text(
              'Make sure the Speech2Prompt server is running on your computer and try scanning again.',
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 24),
            ElevatedButton.icon(
              onPressed: _loadDevices,
              icon: const Icon(Icons.refresh),
              label: const Text('Scan Again'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildDeviceList(BleService bluetooth) {
    return ListView.builder(
      itemCount: _devices.length,
      itemBuilder: (context, index) {
        final device = _devices[index];
        final isConnected =
            bluetooth.connectedDevice?.deviceId == device.deviceId;
        final isConnecting = bluetooth.state.isConnecting && isConnected;

        return ListTile(
          leading: Icon(
            device.isSpeech2Prompt ? Icons.computer : Icons.bluetooth,
            color: isConnected ? Colors.green : null,
          ),
          title: Text(device.displayName),
          subtitle: Text(
            isConnected ? bluetooth.state.displayText : device.deviceId,
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
