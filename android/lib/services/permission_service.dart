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

import 'package:permission_handler/permission_handler.dart';

/// Service for handling app permissions.
class PermissionService {
  /// Check if microphone permission is granted.
  static Future<bool> hasMicrophonePermission() async {
    return await Permission.microphone.isGranted;
  }

  /// Request microphone permission.
  static Future<bool> requestMicrophonePermission() async {
    final status = await Permission.microphone.request();
    return status.isGranted;
  }

  /// Check if Bluetooth permissions are granted.
  static Future<bool> hasBluetoothPermissions() async {
    final connect = await Permission.bluetoothConnect.isGranted;
    final scan = await Permission.bluetoothScan.isGranted;
    return connect && scan;
  }

  /// Request Bluetooth permissions.
  static Future<bool> requestBluetoothPermissions() async {
    final statuses = await [
      Permission.bluetoothConnect,
      Permission.bluetoothScan,
      Permission.bluetooth,
    ].request();

    return statuses.values.every((status) => status.isGranted);
  }

  /// Request all required permissions.
  static Future<PermissionStatus> requestAllPermissions() async {
    final mic = await requestMicrophonePermission();
    final bt = await requestBluetoothPermissions();

    if (mic && bt) {
      return PermissionStatus.allGranted;
    } else if (!mic && !bt) {
      return PermissionStatus.allDenied;
    } else if (!mic) {
      return PermissionStatus.microphoneDenied;
    } else {
      return PermissionStatus.bluetoothDenied;
    }
  }

  /// Open app settings.
  static Future<bool> openSettings() async {
    return await openAppSettings();
  }
}

/// Permission status result.
enum PermissionStatus {
  allGranted,
  allDenied,
  microphoneDenied,
  bluetoothDenied,
}
