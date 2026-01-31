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
