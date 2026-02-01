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

//! Secure storage for pairing credentials.

use anyhow::Result;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::Path;

/// Paired device information.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairedDevice {
    pub address: String,
    pub name: String,
    pub android_device_id: String,
    pub shared_secret: String, // Base64 encoded
    pub paired_at: chrono::DateTime<chrono::Local>,
}

/// Secure storage for paired devices.
pub struct SecureStorage {
    path: std::path::PathBuf,
    devices: HashMap<String, PairedDevice>,
}

impl SecureStorage {
    /// Create or open secure storage.
    pub fn new(data_dir: &Path) -> Result<Self> {
        let path = data_dir.join("paired_devices.json");
        let devices = if path.exists() {
            let content = std::fs::read_to_string(&path)?;
            serde_json::from_str(&content)?
        } else {
            HashMap::new()
        };

        Ok(Self { path, devices })
    }

    /// Store a paired device.
    pub fn store_device(&mut self, device: PairedDevice) -> Result<()> {
        self.devices.insert(device.address.clone(), device);
        self.save()
    }

    /// Get a paired device by address.
    pub fn get_device(&self, address: &str) -> Option<&PairedDevice> {
        self.devices.get(address)
    }

    /// Get a paired device by Android device ID.
    pub fn get_device_by_android_id(&self, android_id: &str) -> Option<&PairedDevice> {
        self.devices
            .values()
            .find(|d| d.android_device_id == android_id)
    }

    /// Remove a paired device.
    pub fn remove_device(&mut self, address: &str) -> Result<()> {
        self.devices.remove(address);
        self.save()
    }

    /// Get all paired devices.
    pub fn get_all_devices(&self) -> Vec<&PairedDevice> {
        self.devices.values().collect()
    }

    /// Check if a device is paired.
    pub fn is_paired(&self, address: &str) -> bool {
        self.devices.contains_key(address)
    }

    /// Save to disk.
    fn save(&self) -> Result<()> {
        let content = serde_json::to_string_pretty(&self.devices)?;
        std::fs::write(&self.path, content)?;
        Ok(())
    }
}
