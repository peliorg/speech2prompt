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
