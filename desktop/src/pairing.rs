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

//! Pairing management for handling PIN entry.

use anyhow::Result;
use base64::{engine::general_purpose::STANDARD, Engine};
use tokio::sync::{mpsc, oneshot};
use tracing::{info, warn};

use crate::crypto::CryptoContext;
use crate::storage::{PairedDevice, SecureStorage};

/// Pairing request from a connection.
#[derive(Debug)]
pub struct PairingRequest {
    pub android_device_id: String,
    pub device_name: Option<String>,
    pub response_tx: oneshot::Sender<PairingResponse>,
}

/// Response to a pairing request.
#[derive(Debug)]
pub enum PairingResponse {
    /// User accepted with PIN.
    Accepted { pin: String },
    /// User rejected.
    Rejected,
}

/// Pairing manager handles pairing requests and storage.
pub struct PairingManager {
    linux_device_id: String,
    storage: SecureStorage,
    request_tx: mpsc::Sender<PairingRequest>,
    request_rx: mpsc::Receiver<PairingRequest>,
}

impl PairingManager {
    /// Create a new pairing manager.
    pub fn new(linux_device_id: String, storage: SecureStorage) -> Self {
        let (request_tx, request_rx) = mpsc::channel(8);
        
        Self {
            linux_device_id,
            storage,
            request_tx,
            request_rx,
        }
    }

    /// Get sender for pairing requests.
    pub fn get_request_sender(&self) -> mpsc::Sender<PairingRequest> {
        self.request_tx.clone()
    }

    /// Check if a device is already paired.
    pub fn is_paired(&self, android_device_id: &str) -> bool {
        self.storage.get_device_by_android_id(android_device_id).is_some()
    }

    /// Get stored crypto context for a paired device.
    pub fn get_crypto_context(&self, android_device_id: &str) -> Option<CryptoContext> {
        let device = self.storage.get_device_by_android_id(android_device_id)?;
        let secret = STANDARD.decode(&device.shared_secret).ok()?;
        
        if secret.len() != 32 {
            warn!("Invalid shared secret length for device {}", android_device_id);
            return None;
        }
        
        let mut key = [0u8; 32];
        key.copy_from_slice(&secret);
        Some(CryptoContext::new(key))
    }

    /// Store a newly paired device.
    pub fn store_pairing(
        &mut self,
        address: &str,
        name: &str,
        android_device_id: &str,
        crypto: &CryptoContext,
    ) -> Result<()> {
        let device = PairedDevice {
            address: address.to_string(),
            name: name.to_string(),
            android_device_id: android_device_id.to_string(),
            shared_secret: STANDARD.encode(crypto.key()),
            paired_at: chrono::Local::now(),
        };
        
        self.storage.store_device(device)?;
        info!("Stored pairing for device {}", android_device_id);
        Ok(())
    }

    /// Process pairing requests from UI.
    pub async fn process_request(&mut self) -> Option<PairingRequest> {
        self.request_rx.recv().await
    }

    /// Get Linux device ID.
    pub fn linux_device_id(&self) -> &str {
        &self.linux_device_id
    }
}
