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
      Navigator.pop(context);
      return;
    }

    if (bluetooth.isConnected) {
      await bluetooth.disconnect();
    }

    // Check if we have stored pairing for this device
    final hasStoredPairing = await bluetooth.isDevicePaired(device.address);
    
    if (hasStoredPairing) {
      // Load stored crypto context
      final loaded = await bluetooth.loadStoredPairing(device.address);
      if (loaded) {
        // Connect with existing credentials
        final success = await bluetooth.connect(device);
        if (success && mounted) {
          Navigator.pop(context);
        }
        return;
      }
    }

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

  Future<bool> _showPairingDialog(BluetoothService bluetooth) async {
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
                Navigator.pop(context, {
                  'pin': pinController.text,
                });
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

    // Complete pairing with PIN
    // The Linux device ID will come from PAIR_ACK in real implementation
    await bluetooth.completePairing(pin, 'linux-placeholder');
    
    // Wait a bit for the pairing to complete
    await Future.delayed(const Duration(seconds: 2));
    
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
