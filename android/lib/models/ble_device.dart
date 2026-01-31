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

import 'package:flutter_blue_plus/flutter_blue_plus.dart';

/// Wrapper for BLE device info from scan results.
class BleDeviceInfo {
  final String name;
  final String deviceId;
  final int rssi;
  final bool hasS2CService;
  final BluetoothDevice device;

  BleDeviceInfo({
    required this.name,
    required this.deviceId,
    required this.rssi,
    required this.hasS2CService,
    required this.device,
  });

  /// Create from flutter_blue_plus ScanResult.
  factory BleDeviceInfo.fromScanResult(ScanResult result) {
    final device = result.device;
    final advertisementData = result.advertisementData;
    
    // Check if Speech2Code service is advertised
    final hasS2CService = advertisementData.serviceUuids
        .any((uuid) => uuid.toString().toLowerCase() == 
            'a1b2c3d4-e5f6-7890-abcd-ef1234567890');
    
    return BleDeviceInfo(
      name: advertisementData.advName.isNotEmpty 
          ? advertisementData.advName 
          : device.platformName.isNotEmpty
              ? device.platformName
              : 'Unknown Device',
      deviceId: device.remoteId.str,
      rssi: result.rssi,
      hasS2CService: hasS2CService,
      device: device,
    );
  }

  /// Display name for UI.
  String get displayName => name.isNotEmpty ? name : deviceId;

  /// Check if this looks like a Speech2Code server.
  bool get isSpeech2Code =>
      hasS2CService ||
      name.toLowerCase().contains('speech2code') ||
      name.toLowerCase().contains('speech 2 code');

  @override
  String toString() => 'BleDeviceInfo($displayName, $deviceId, rssi: $rssi dBm)';

  @override
  bool operator ==(Object other) =>
      other is BleDeviceInfo && other.deviceId == deviceId;

  @override
  int get hashCode => deviceId.hashCode;
}
