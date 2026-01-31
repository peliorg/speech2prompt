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

import 'dart:convert';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Service for securely storing sensitive data.
class SecureStorageService {
  static const FlutterSecureStorage _storage = FlutterSecureStorage(
    aOptions: AndroidOptions(),
  );

  // Keys
  static const _deviceIdKey = 'device_id';
  static const _pairedDevicesKey = 'paired_devices';

  /// Get or generate device ID.
  static Future<String> getDeviceId() async {
    var deviceId = await _storage.read(key: _deviceIdKey);
    if (deviceId == null) {
      deviceId = _generateDeviceId();
      await _storage.write(key: _deviceIdKey, value: deviceId);
    }
    return deviceId;
  }

  /// Store paired device credentials.
  static Future<void> storePairedDevice(PairedDevice device) async {
    final devices = await getPairedDevices();

    // Update or add
    final index = devices.indexWhere((d) => d.address == device.address);
    if (index >= 0) {
      devices[index] = device;
    } else {
      devices.add(device);
    }

    final json = jsonEncode(devices.map((d) => d.toJson()).toList());
    await _storage.write(key: _pairedDevicesKey, value: json);
  }

  /// Get all paired devices.
  static Future<List<PairedDevice>> getPairedDevices() async {
    final json = await _storage.read(key: _pairedDevicesKey);
    if (json == null) return [];

    final list = jsonDecode(json) as List;
    return list.map((e) => PairedDevice.fromJson(e)).toList();
  }

  /// Get paired device by address.
  static Future<PairedDevice?> getPairedDevice(String address) async {
    final devices = await getPairedDevices();
    try {
      return devices.firstWhere((d) => d.address == address);
    } catch (e) {
      return null;
    }
  }

  /// Remove paired device.
  static Future<void> removePairedDevice(String address) async {
    final devices = await getPairedDevices();
    devices.removeWhere((d) => d.address == address);

    final json = jsonEncode(devices.map((d) => d.toJson()).toList());
    await _storage.write(key: _pairedDevicesKey, value: json);
  }

  /// Clear all stored data.
  static Future<void> clearAll() async {
    await _storage.deleteAll();
  }

  static String _generateDeviceId() {
    final random = List.generate(
        16,
        (_) => (DateTime.now().microsecondsSinceEpoch % 256)
            .toRadixString(16)
            .padLeft(2, '0')).join();
    return 'android-$random';
  }
}

/// Paired device information.
class PairedDevice {
  final String address;
  final String name;
  final String linuxDeviceId;
  final String sharedSecret; // Base64 encoded
  final DateTime pairedAt;

  PairedDevice({
    required this.address,
    required this.name,
    required this.linuxDeviceId,
    required this.sharedSecret,
    required this.pairedAt,
  });

  Map<String, dynamic> toJson() => {
        'address': address,
        'name': name,
        'linuxDeviceId': linuxDeviceId,
        'sharedSecret': sharedSecret,
        'pairedAt': pairedAt.toIso8601String(),
      };

  factory PairedDevice.fromJson(Map<String, dynamic> json) => PairedDevice(
        address: json['address'],
        name: json['name'],
        linuxDeviceId: json['linuxDeviceId'],
        sharedSecret: json['sharedSecret'],
        pairedAt: DateTime.parse(json['pairedAt']),
      );
}
