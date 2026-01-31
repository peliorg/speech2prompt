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

//! Application state management.

use parking_lot::RwLock;
use std::sync::Arc;

/// Connection status.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConnectionStatus {
    Disconnected,
    Connecting,
    Connected,
    Error,
}

impl ConnectionStatus {
    pub fn as_str(&self) -> &'static str {
        match self {
            ConnectionStatus::Disconnected => "Disconnected",
            ConnectionStatus::Connecting => "Connecting...",
            ConnectionStatus::Connected => "Connected",
            ConnectionStatus::Error => "Error",
        }
    }

    pub fn icon_name(&self) -> &'static str {
        match self {
            ConnectionStatus::Disconnected => "network-offline",
            ConnectionStatus::Connecting => "network-idle",
            ConnectionStatus::Connected => "network-transmit-receive",
            ConnectionStatus::Error => "network-error",
        }
    }
}

/// Shared application state.
#[derive(Debug)]
pub struct AppState {
    /// Current connection status.
    pub connection_status: RwLock<ConnectionStatus>,
    
    /// Whether input injection is enabled.
    pub input_enabled: RwLock<bool>,
    
    /// Connected device name.
    pub connected_device: RwLock<Option<String>>,
    
    /// Last received text (for tooltip).
    pub last_text: RwLock<Option<String>>,
}

impl Default for AppState {
    fn default() -> Self {
        Self {
            connection_status: RwLock::new(ConnectionStatus::Disconnected),
            input_enabled: RwLock::new(true),
            connected_device: RwLock::new(None),
            last_text: RwLock::new(None),
        }
    }
}

impl AppState {
    pub fn new() -> Arc<Self> {
        Arc::new(Self::default())
    }

    pub fn set_connected(&self, device_name: String) {
        *self.connection_status.write() = ConnectionStatus::Connected;
        *self.connected_device.write() = Some(device_name);
    }

    pub fn set_disconnected(&self) {
        *self.connection_status.write() = ConnectionStatus::Disconnected;
        *self.connected_device.write() = None;
    }

    pub fn set_error(&self) {
        *self.connection_status.write() = ConnectionStatus::Error;
    }

    pub fn set_input_enabled(&self, enabled: bool) {
        *self.input_enabled.write() = enabled;
    }

    pub fn is_input_enabled(&self) -> bool {
        *self.input_enabled.read()
    }

    pub fn get_status(&self) -> ConnectionStatus {
        *self.connection_status.read()
    }

    pub fn get_device_name(&self) -> Option<String> {
        self.connected_device.read().clone()
    }

    pub fn set_last_text(&self, text: String) {
        *self.last_text.write() = Some(text);
    }

    pub fn get_last_text(&self) -> Option<String> {
        self.last_text.read().clone()
    }
}
