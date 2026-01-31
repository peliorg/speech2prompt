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

/// Bluetooth SPP UUID.
const String sppUuid = '00001101-0000-1000-8000-00805F9B34FB';

/// Heartbeat interval in milliseconds.
const int heartbeatIntervalMs = 5000;

/// Connection timeout in milliseconds.
const int connectionTimeoutMs = 15000;

/// Maximum reconnection attempts.
const int maxReconnectAttempts = 5;

/// Reconnection base delay in milliseconds.
const int reconnectBaseDelayMs = 1000;

/// App name.
const String appName = 'Speech2Prompt';
