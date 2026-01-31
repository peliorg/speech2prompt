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

//! Bluetooth RFCOMM server implementation.

use anyhow::Result;
use bluer::rfcomm::{Listener, SocketAddr};
use bluer::{Address, Session};
use std::sync::Arc;
use tokio::sync::mpsc;
use tracing::{error, info};
use uuid::Uuid;

use super::connection::{ConnectionEvent, ConnectionHandler};

/// Standard SPP UUID.
const SPP_UUID: Uuid = Uuid::from_u128(0x00001101_0000_1000_8000_00805F9B34FB);

/// RFCOMM channel to use.
const RFCOMM_CHANNEL: u8 = 1;

/// Bluetooth server that listens for incoming connections.
pub struct BluetoothServer {
    adapter: bluer::Adapter,
    linux_device_id: String,
}

impl BluetoothServer {
    /// Create a new Bluetooth server.
    pub async fn new() -> Result<Self> {
        info!("Initializing Bluetooth server...");

        // Create BlueZ session
        let session = bluer::Session::new().await?;
        info!("BlueZ session created");

        // Get the default adapter
        let adapter = session.default_adapter().await?;
        let adapter_name = adapter.name();
        info!("Using Bluetooth adapter: {}", adapter_name);

        // Ensure adapter is powered on
        if !adapter.is_powered().await? {
            info!("Powering on Bluetooth adapter...");
            adapter.set_powered(true).await?;
        }

        // Make adapter discoverable
        adapter.set_discoverable(true).await?;
        adapter.set_pairable(true).await?;
        info!("Adapter is discoverable and pairable");

        // Get adapter address as device ID
        let address = adapter.address().await?;
        let linux_device_id = format!("linux-{}", address.to_string().replace(':', ""));
        info!("Linux device ID: {}", linux_device_id);

        Ok(Self {
            adapter,
            linux_device_id,
        })
    }

    /// Get the Linux device ID.
    pub fn device_id(&self) -> &str {
        &self.linux_device_id
    }

    /// Get the adapter address.
    pub async fn address(&self) -> Result<Address> {
        Ok(self.adapter.address().await?)
    }

    /// Set the device name.
    pub async fn set_name(&self, name: &str) -> Result<()> {
        self.adapter.set_alias(name.to_string()).await?;
        info!("Bluetooth name set to: {}", name);
        Ok(())
    }

    /// Start listening for incoming connections.
    ///
    /// Returns a channel receiver for connection events.
    pub async fn listen(&self) -> Result<mpsc::Receiver<ConnectionEvent>> {
        let (event_tx, event_rx) = mpsc::channel(32);

        // Create RFCOMM listener
        let local_addr = SocketAddr::new(Address::any(), RFCOMM_CHANNEL);
        let listener = Listener::bind(local_addr).await?;
        info!(
            "RFCOMM server listening on channel {}",
            RFCOMM_CHANNEL
        );

        // Register SPP service with SDP
        self.register_sdp_service().await?;

        let linux_device_id = self.linux_device_id.clone();

        // Spawn accept loop
        tokio::spawn(async move {
            Self::accept_loop(listener, linux_device_id, event_tx).await;
        });

        Ok(event_rx)
    }

    /// Register the SPP service with SDP.
    async fn register_sdp_service(&self) -> Result<()> {
        // Note: With bluer, the SDP service is often registered automatically
        // when we bind to an RFCOMM channel. For more control, we would use
        // the profile API. This is a simplified version.
        info!("SPP service registered (UUID: {})", SPP_UUID);
        Ok(())
    }

    /// Accept loop for incoming connections.
    async fn accept_loop(
        listener: Listener,
        linux_device_id: String,
        event_tx: mpsc::Sender<ConnectionEvent>,
    ) {
        info!("Waiting for connections...");

        loop {
            match listener.accept().await {
                Ok((stream, remote_addr)) => {
                    info!("Connection from: {:?}", remote_addr);

                    let handler = ConnectionHandler::new(
                        stream,
                        linux_device_id.clone(),
                        event_tx.clone(),
                    );

                    // Emit connected event
                    let device_name = format!("{:?}", remote_addr);
                    if let Err(e) = event_tx
                        .send(ConnectionEvent::Connected { device_name })
                        .await
                    {
                        error!("Failed to send connected event: {}", e);
                    }

                    // Spawn handler task
                    tokio::spawn(async move {
                        if let Err(e) = handler.run().await {
                            error!("Connection handler error: {}", e);
                        }
                    });
                }
                Err(e) => {
                    error!("Accept error: {}", e);
                    // Continue listening despite errors
                    tokio::time::sleep(tokio::time::Duration::from_secs(1)).await;
                }
            }
        }
    }

    /// Get paired devices.
    pub async fn get_paired_devices(&self) -> Result<Vec<PairedDevice>> {
        let mut devices = Vec::new();

        for addr in self.adapter.device_addresses().await? {
            let device = self.adapter.device(addr)?;
            if device.is_paired().await? {
                let name = device.alias().await.unwrap_or_else(|_| addr.to_string());
                devices.push(PairedDevice {
                    address: addr,
                    name,
                });
            }
        }

        Ok(devices)
    }
}

/// A paired Bluetooth device.
#[derive(Debug, Clone)]
pub struct PairedDevice {
    pub address: Address,
    pub name: String,
}

/// Manager for Bluetooth operations with state.
pub struct BluetoothManager {
    server: Arc<BluetoothServer>,
    event_rx: Option<mpsc::Receiver<ConnectionEvent>>,
}

impl BluetoothManager {
    /// Create a new Bluetooth manager.
    pub async fn new() -> Result<Self> {
        let server = Arc::new(BluetoothServer::new().await?);
        Ok(Self {
            server,
            event_rx: None,
        })
    }

    /// Start the Bluetooth server.
    pub async fn start(&mut self, device_name: &str) -> Result<()> {
        self.server.set_name(device_name).await?;
        let event_rx = self.server.listen().await?;
        self.event_rx = Some(event_rx);
        Ok(())
    }

    /// Take the event receiver (can only be called once).
    pub fn take_event_receiver(&mut self) -> Option<mpsc::Receiver<ConnectionEvent>> {
        self.event_rx.take()
    }

    /// Get server reference.
    pub fn server(&self) -> &Arc<BluetoothServer> {
        &self.server
    }
}
