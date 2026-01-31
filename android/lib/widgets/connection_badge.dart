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

import '../models/connection_state.dart';

/// Badge showing Bluetooth connection status.
class ConnectionBadge extends StatelessWidget {
  final BtConnectionState state;
  final String? deviceName;
  final VoidCallback? onTap;

  const ConnectionBadge({
    super.key,
    required this.state,
    this.deviceName,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final color = _getColor();
    final icon = _getIcon();
    final text = _getText();

    return GestureDetector(
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 200),
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
        decoration: BoxDecoration(
          color: color.withOpacity(0.15),
          borderRadius: BorderRadius.circular(24),
          border: Border.all(
            color: color.withOpacity(0.3),
            width: 1,
          ),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            // Status indicator dot
            Container(
              width: 8,
              height: 8,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: color,
                boxShadow: [
                  BoxShadow(
                    color: color.withOpacity(0.5),
                    blurRadius: 4,
                    spreadRadius: 1,
                  ),
                ],
              ),
            ),
            const SizedBox(width: 8),
            // Icon
            Icon(icon, color: color, size: 18),
            const SizedBox(width: 8),
            // Text
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  text,
                  style: TextStyle(
                    color: color,
                    fontWeight: FontWeight.w500,
                    fontSize: 13,
                  ),
                ),
                if (deviceName != null && state.isConnected)
                  Text(
                    deviceName!,
                    style: TextStyle(
                      color: color.withOpacity(0.7),
                      fontSize: 11,
                    ),
                  ),
              ],
            ),
            const SizedBox(width: 4),
            // Arrow or spinner
            if (state.isConnecting)
              SizedBox(
                width: 14,
                height: 14,
                child: CircularProgressIndicator(
                  strokeWidth: 2,
                  valueColor: AlwaysStoppedAnimation(color),
                ),
              )
            else
              Icon(
                Icons.chevron_right,
                color: color.withOpacity(0.7),
                size: 18,
              ),
          ],
        ),
      ),
    );
  }

  Color _getColor() {
    switch (state) {
      case BtConnectionState.connected:
        return Colors.green;
      case BtConnectionState.connecting:
      case BtConnectionState.reconnecting:
      case BtConnectionState.awaitingPairing:
        return Colors.orange;
      case BtConnectionState.failed:
        return Colors.red;
      case BtConnectionState.disconnected:
        return Colors.grey;
    }
  }

  IconData _getIcon() {
    switch (state) {
      case BtConnectionState.connected:
        return Icons.bluetooth_connected;
      case BtConnectionState.connecting:
      case BtConnectionState.reconnecting:
        return Icons.bluetooth_searching;
      case BtConnectionState.awaitingPairing:
        return Icons.lock_outline;
      case BtConnectionState.failed:
        return Icons.bluetooth_disabled;
      case BtConnectionState.disconnected:
        return Icons.bluetooth;
    }
  }

  String _getText() {
    switch (state) {
      case BtConnectionState.connected:
        return 'Connected';
      case BtConnectionState.connecting:
        return 'Connecting';
      case BtConnectionState.reconnecting:
        return 'Reconnecting';
      case BtConnectionState.awaitingPairing:
        return 'Pairing';
      case BtConnectionState.failed:
        return 'Failed';
      case BtConnectionState.disconnected:
        return 'Tap to connect';
    }
  }
}
