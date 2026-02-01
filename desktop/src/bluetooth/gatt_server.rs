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

//! BLE GATT server implementation for Speech2Prompt.

use anyhow::{anyhow, Result};
use bluer::gatt::local::{
    characteristic_control, Application, ApplicationHandle, Characteristic, CharacteristicNotify,
    CharacteristicNotifyMethod, CharacteristicRead, CharacteristicReadRequest,
    CharacteristicWrite, CharacteristicWriteMethod, CharacteristicWriteRequest, Service,
};
use bluer::adv::{Advertisement, AdvertisementHandle};
use bluer::{Adapter, Address};
use std::sync::Arc;
use tokio::sync::{mpsc, Mutex, RwLock};
use tracing::{debug, error, info, warn};

use super::ble_constants::*;
use super::protocol::{Message, MessageType, PairAckPayload, PairRequestPayload};
use super::reassembler::{chunk_message, MessageReassembler};
use crate::crypto::CryptoContext;
use crate::crypto::ecdh::EcdhKeypair;

/// Events emitted by the GATT server.
#[derive(Debug, Clone)]
pub enum ConnectionEvent {
    /// Text received from the Android app.
    TextReceived(String),
    /// Command received from the Android app.
    CommandReceived(String),
    /// Connection established.
    Connected { device_name: String },
    /// Connection closed.
    Disconnected,
    /// Pairing requested.
    PairRequested { 
        device_id: String,
        device_name: Option<String>,
    },
    /// Error occurred.
    Error(String),
}

/// State of the connection.
#[derive(Debug, Clone, PartialEq)]
pub enum ConnectionState {
    /// Waiting for pairing.
    AwaitingPair,
    /// Paired and authenticated.
    Authenticated,
    /// Disconnected.
    Disconnected,
}

/// Pending pairing state during ECDH exchange.
struct PendingPairing {
    android_device_id: String,
    android_device_name: Option<String>,
    android_public_key: String,
    desktop_keypair: EcdhKeypair,
}

/// Shared state for the GATT server.
struct ServerState {
    reassembler: MessageReassembler,
    crypto: Option<Arc<CryptoContext>>,
    device_id: Option<String>,
    state: ConnectionState,
    negotiated_mtu: usize,
    status_code: StatusCode,
    pending_pairing: Option<PendingPairing>,
}

impl ServerState {
    fn new() -> Self {
        Self {
            reassembler: MessageReassembler::new(),
            crypto: None,
            device_id: None,
            state: ConnectionState::AwaitingPair,
            negotiated_mtu: config::DEFAULT_MTU,
            status_code: StatusCode::Idle,
            pending_pairing: None,
        }
    }
}

/// GATT server for Speech2Prompt.
pub struct GattServer {
    adapter: Adapter,
    linux_device_id: String,
    event_tx: mpsc::Sender<ConnectionEvent>,
    state: Arc<RwLock<ServerState>>,
    response_tx: Arc<Mutex<Option<mpsc::Sender<Vec<u8>>>>>,
    status_tx: Arc<Mutex<Option<mpsc::Sender<Vec<u8>>>>>,
    _adv_handle: Option<AdvertisementHandle>,
    _app_handle: Option<ApplicationHandle>,
}

impl GattServer {
    /// Create a new GATT server.
    pub async fn new(event_tx: mpsc::Sender<ConnectionEvent>) -> Result<Self> {
        info!("Initializing BLE GATT server...");

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

        // Get adapter address as device ID
        let address = adapter.address().await?;
        let linux_device_id = format!("linux-{}", address.to_string().replace(':', ""));
        info!("Linux device ID: {}", linux_device_id);

        Ok(Self {
            adapter,
            linux_device_id,
            event_tx,
            state: Arc::new(RwLock::new(ServerState::new())),
            response_tx: Arc::new(Mutex::new(None)),
            status_tx: Arc::new(Mutex::new(None)),
            _adv_handle: None,
            _app_handle: None,
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

    /// Start the GATT server and advertising.
    pub async fn start(&mut self) -> Result<()> {
        // Register GATT service
        self.register_gatt_service().await?;

        // Start advertising
        self.start_advertising().await?;

        info!("GATT server started successfully");
        Ok(())
    }

    /// Register the GATT service with BlueZ.
    async fn register_gatt_service(&mut self) -> Result<()> {
        let state = self.state.clone();
        let event_tx = self.event_tx.clone();
        let linux_device_id = self.linux_device_id.clone();
        let response_tx = self.response_tx.clone();
        let status_tx = self.status_tx.clone();

        // Build Command RX characteristic
        let (_cmd_rx_control, cmd_rx_control_handle) = characteristic_control();
        let cmd_rx_char = {
            let state = state.clone();
            let event_tx = event_tx.clone();
            let linux_device_id = linux_device_id.clone();
            let response_tx = response_tx.clone();

            Characteristic {
                uuid: COMMAND_RX_UUID,
                write: Some(CharacteristicWrite {
                    write: true,
                    write_without_response: true,
                method: CharacteristicWriteMethod::Fun(Box::new(move |data: Vec<u8>, req: CharacteristicWriteRequest| {
                    let state = state.clone();
                    let event_tx = event_tx.clone();
                    let linux_device_id = linux_device_id.clone();
                    let response_tx = response_tx.clone();

                    Box::pin(async move {
                        Self::handle_command_write(
                            data,
                            req,
                            state,
                            event_tx,
                            linux_device_id,
                            response_tx,
                        )
                        .await
                    })
                })),
                ..Default::default()
            }),
            control_handle: cmd_rx_control_handle,
            ..Default::default()
        }
    };

        // Build Response TX characteristic (notify)
        let (_resp_tx_control, resp_tx_control_handle) = characteristic_control();
        let (resp_notify_tx, resp_notify_rx) = mpsc::channel::<Vec<u8>>(32);
        let resp_notify_rx = Arc::new(Mutex::new(resp_notify_rx));
        *response_tx.lock().await = Some(resp_notify_tx);

        let resp_tx_char = Characteristic {
            uuid: RESPONSE_TX_UUID,
            notify: Some(CharacteristicNotify {
                notify: true,
                method: CharacteristicNotifyMethod::Fun(Box::new(move |mut notifier| {
                    let resp_notify_rx = resp_notify_rx.clone();
                    
                    Box::pin(async move {
                        debug!("Response TX notification loop started");
                        loop {
                            let data = {
                                let mut rx = resp_notify_rx.lock().await;
                                rx.recv().await
                            };
                            
                            match data {
                                Some(data) => {
                                    debug!("Sending notification: {} bytes", data.len());
                                    if let Err(e) = notifier.notify(data).await {
                                        error!("Failed to send notification: {}", e);
                                        break;
                                    }
                                    debug!("Notification sent successfully");
                                }
                                None => {
                                    info!("Response TX channel closed, exiting notification loop");
                                    break;
                                }
                            }
                        }
                        info!("Response TX notification loop exited");
                    })
                })),
            ..Default::default()
        }),
        control_handle: resp_tx_control_handle,
        ..Default::default()
    };

        // Build Status characteristic (read + notify)
        let (_status_control, status_control_handle) = characteristic_control();
        let (status_notify_tx, status_notify_rx) = mpsc::channel::<Vec<u8>>(32);
        let status_notify_rx = Arc::new(Mutex::new(status_notify_rx));
        *status_tx.lock().await = Some(status_notify_tx);

        let status_char = {
            let state = state.clone();

            Characteristic {
                uuid: STATUS_UUID,
                read: Some(CharacteristicRead {
                    read: true,
                    fun: Box::new(move |_req: CharacteristicReadRequest| {
                        let state = state.clone();
                        Box::pin(async move {
                            let state = state.read().await;
                            Ok(state.status_code.as_bytes())
                        })
                    }),
                    ..Default::default()
                }),
                notify: Some(CharacteristicNotify {
                    notify: true,
                    method: CharacteristicNotifyMethod::Fun(Box::new(move |mut notifier| {
                        let status_notify_rx = status_notify_rx.clone();
                        
                        Box::pin(async move {
                            loop {
                                let data = {
                                    let mut rx = status_notify_rx.lock().await;
                                    rx.recv().await
                                };
                                
                                match data {
                                    Some(data) => {
                                        if let Err(e) = notifier.notify(data).await {
                                            error!("Failed to send status notification: {}", e);
                                            break;
                                        }
                                    }
                                    None => break,
                                }
                            }
                        })
                    })),
            ..Default::default()
        }),
        control_handle: status_control_handle,
        ..Default::default()
    }
};

        // Build MTU Info characteristic (read only)
        let mtu_char = {
            let state = state.clone();

            Characteristic {
                uuid: MTU_INFO_UUID,
                read: Some(CharacteristicRead {
                    read: true,
                    fun: Box::new(move |_req: CharacteristicReadRequest| {
                        let state = state.clone();
                        Box::pin(async move {
                            let state = state.read().await;
                            let mtu_bytes = (state.negotiated_mtu as u16).to_le_bytes();
                            Ok(mtu_bytes.to_vec())
                        })
                    }),
                    ..Default::default()
                }),
                ..Default::default()
            }
        };

        // Build service
        let service = Service {
            uuid: SERVICE_UUID,
            primary: true,
            characteristics: vec![
                cmd_rx_char,
                resp_tx_char,
                status_char,
                mtu_char,
            ],
            ..Default::default()
        };

        // Build application
        let app = Application {
            services: vec![service],
            ..Default::default()
        };

        // Register with BlueZ
        self._app_handle = Some(self.adapter.serve_gatt_application(app).await?);

        info!("GATT service registered");

        Ok(())
    }

    /// Handle writes to Command RX characteristic.
    async fn handle_command_write(
        data: Vec<u8>,
        req: CharacteristicWriteRequest,
        state: Arc<RwLock<ServerState>>,
        event_tx: mpsc::Sender<ConnectionEvent>,
        _linux_device_id: String,
        response_tx: Arc<Mutex<Option<mpsc::Sender<Vec<u8>>>>>,
    ) -> Result<(), bluer::gatt::local::ReqError> {
        let mut state_guard = state.write().await;
        
        // Update MTU if this write indicates a larger negotiated MTU
        // The MTU in the write request is the effective ATT MTU negotiated with the client
        let write_mtu = req.mtu as usize;
        if write_mtu > state_guard.negotiated_mtu {
            info!("MTU updated: {} -> {} bytes", state_guard.negotiated_mtu, write_mtu);
            state_guard.negotiated_mtu = write_mtu;
        }

        // Process packet through reassembler
        if let Some(complete_message) = state_guard.reassembler.process_packet(&data) {
            // Parse JSON message
            let json = match String::from_utf8(complete_message) {
                Ok(s) => s,
                Err(e) => {
                    error!("Invalid UTF-8 in message: {}", e);
                    return Ok(());
                }
            };

            debug!("Received complete message: {}", json.trim());

            let mut message = match Message::from_json(&json) {
                Ok(m) => m,
                Err(e) => {
                    error!("Failed to parse message: {}", e);
                    return Ok(());
                }
            };

            // Verify and decrypt if we have crypto context
            // Note: Only verify messages that should be signed (not PAIR_REQ, PAIR_ACK, HEARTBEAT, ACK before auth)
            if let Some(ref crypto) = state_guard.crypto {
                // Only verify messages after authentication for types that require it
                let should_verify = matches!(
                    message.message_type,
                    MessageType::Text | MessageType::Command
                );
                
                if should_verify {
                    if let Err(e) = message.verify_and_decrypt(crypto) {
                        error!("Message verification failed: {}", e);
                        return Ok(());
                    }
                }
            }

            // Handle message based on type
            match message.message_type {
                MessageType::PairReq => {
                    info!("📱 PAIR_REQ message received!");
                    
                    // Handle pairing request
                    let payload = match PairRequestPayload::from_json(&message.payload) {
                        Ok(p) => p,
                        Err(e) => {
                            error!("Failed to parse PAIR_REQ: {}", e);
                            return Ok(());
                        }
                    };

                    info!("🔐 Pairing request from device: {} ({})", 
                          payload.device_name.as_deref().unwrap_or("Unknown"),
                          payload.device_id);
                    info!("🔑 Android public key length: {} bytes", payload.public_key.len());

                    // Validate public key is present
                    if payload.public_key.is_empty() {
                        error!("❌ PAIR_REQ missing public key");
                        return Ok(());
                    }

                    // Generate desktop ECDH keypair
                    info!("🔐 Generating desktop ECDH keypair...");
                    let desktop_keypair = EcdhKeypair::generate();
                    info!("✅ Desktop ECDH keypair generated");

                    // Store pending pairing data
                    state_guard.device_id = Some(payload.device_id.clone());
                    state_guard.status_code = StatusCode::AwaitingPairing;
                    state_guard.pending_pairing = Some(PendingPairing {
                        android_device_id: payload.device_id.clone(),
                        android_device_name: payload.device_name.clone(),
                        android_public_key: payload.public_key,
                        desktop_keypair,
                    });

                    // Emit pairing requested event with device name
                    info!("📤 Sending PairRequested event to main loop...");
                    let _ = event_tx
                        .send(ConnectionEvent::PairRequested {
                            device_id: payload.device_id,
                            device_name: payload.device_name,
                        })
                        .await;
                    info!("✅ PairRequested event sent");

                    // Send ACK immediately to prevent Android timeout
                    let ack = Message::ack(message.timestamp);
                    Self::send_response_internal(ack, &state_guard, response_tx.clone()).await;
                    info!("✅ ACK sent to Android");
                }
                MessageType::Text => {
                    if state_guard.state != ConnectionState::Authenticated {
                        warn!("Received TEXT before authentication");
                        return Ok(());
                    }

                    debug!("Text received: {}", message.payload);
                    let _ = event_tx
                        .send(ConnectionEvent::TextReceived(message.payload.clone()))
                        .await;

                    // Send ACK
                    let ack = Message::ack(message.timestamp);
                    Self::send_response_internal(
                        ack,
                        &state_guard,
                        response_tx.clone(),
                    )
                    .await;
                }
                MessageType::Command => {
                    if state_guard.state != ConnectionState::Authenticated {
                        warn!("Received COMMAND before authentication");
                        return Ok(());
                    }

                    debug!("Command received: {}", message.payload);
                    let _ = event_tx
                        .send(ConnectionEvent::CommandReceived(message.payload.clone()))
                        .await;

                    // Send ACK
                    let ack = Message::ack(message.timestamp);
                    Self::send_response_internal(
                        ack,
                        &state_guard,
                        response_tx.clone(),
                    )
                    .await;
                }
                MessageType::Heartbeat => {
                    // Respond with ACK
                    let ack = Message::ack(message.timestamp);
                    Self::send_response_internal(
                        ack,
                        &state_guard,
                        response_tx.clone(),
                    )
                    .await;
                }
                _ => {
                    debug!("Ignoring message type: {:?}", message.message_type);
                }
            }
        }

        Ok(())
    }

    /// Send a response via the Response TX characteristic.
    async fn send_response_internal(
        mut message: Message,
        state: &ServerState,
        response_tx: Arc<Mutex<Option<mpsc::Sender<Vec<u8>>>>>,
    ) {
        // Sign and encrypt if we have crypto
        if let Some(ref crypto) = state.crypto {
            if let Err(e) = message.sign_and_encrypt(crypto) {
                error!("Failed to encrypt response: {}", e);
                return;
            }
        }

        // Serialize to JSON
        let json = match message.to_json() {
            Ok(j) => j,
            Err(e) => {
                error!("Failed to serialize response: {}", e);
                return;
            }
        };

        // Chunk the message
        let packets = chunk_message(json.as_bytes(), state.negotiated_mtu);

        // Send each packet as a notification
        let tx_guard = response_tx.lock().await;
        if let Some(ref tx) = *tx_guard {
            for packet in packets {
                if let Err(e) = tx.send(packet).await {
                    error!("Failed to queue notification: {}", e);
                    break;
                }
            }
        }
    }

    /// Complete pairing after user approval (ECDH key exchange).
    pub async fn complete_pairing(&self) -> Result<()> {
        let mut state = self.state.write().await;

        let pending = state.pending_pairing.take()
            .ok_or_else(|| anyhow!("No pending pairing request"))?;

        // Get desktop public key before consuming keypair
        let desktop_public_key = pending.desktop_keypair.public_key_base64();

        // Compute ECDH shared secret
        let shared_secret = pending.desktop_keypair
            .compute_shared_secret_base64(&pending.android_public_key)?;

        // Derive crypto context from ECDH shared secret
        let crypto = CryptoContext::from_ecdh(
            &shared_secret,
            &pending.android_device_id,
            &self.linux_device_id,
        );

        // Create PAIR_ACK with desktop's public key
        let payload = PairAckPayload::success_with_key(&self.linux_device_id, desktop_public_key);
        let response = Message::new(MessageType::PairAck, payload.to_json()?);

        // Update state
        state.crypto = Some(Arc::new(crypto));
        state.state = ConnectionState::Authenticated;
        state.status_code = StatusCode::Paired;

        info!("Pairing completed with device: {}", pending.android_device_id);

        // Send PAIR_ACK
        let json = response.to_json()?;
        let packets = chunk_message(json.as_bytes(), state.negotiated_mtu);

        let tx_guard = self.response_tx.lock().await;
        if let Some(ref tx) = *tx_guard {
            for packet in packets {
                tx.send(packet).await?;
            }
        }

        // Notify status change
        let status_tx_guard = self.status_tx.lock().await;
        if let Some(ref tx) = *status_tx_guard {
            let _ = tx.send(StatusCode::Paired.as_bytes()).await;
        }

        // Emit connected event
        let _ = self.event_tx
            .send(ConnectionEvent::Connected {
                device_name: pending.android_device_name.unwrap_or(pending.android_device_id),
            })
            .await;

        Ok(())
    }

    /// Reject pairing request.
    pub async fn reject_pairing(&self, reason: &str) -> Result<()> {
        let state = self.state.read().await;
        
        let device_id = state.device_id.as_ref()
            .map(|s| s.as_str())
            .unwrap_or("unknown");
        
        // Create PAIR_ACK with error status
        let payload = PairAckPayload::error(&self.linux_device_id, reason);
        let response = Message::new(MessageType::PairAck, payload.to_json()?);
        
        // Send PAIR_ACK (no signing since pairing failed)
        let json = response.to_json()?;
        let packets = chunk_message(json.as_bytes(), state.negotiated_mtu);
        
        let tx_guard = self.response_tx.lock().await;
        if let Some(ref tx) = *tx_guard {
            for packet in packets {
                tx.send(packet).await?;
            }
        }
        
        info!("Pairing rejected for device {}: {}", device_id, reason);
        Ok(())
    }

    /// Start BLE advertising.
    async fn start_advertising(&mut self) -> Result<()> {
        let adv = Advertisement {
            service_uuids: vec![SERVICE_UUID].into_iter().collect(),
            discoverable: Some(true),
            local_name: Some("Speech2Prompt".to_string()),
            ..Default::default()
        };

        let handle = self.adapter.advertise(adv).await?;
        self._adv_handle = Some(handle);

        info!("BLE advertising started");
        Ok(())
    }

    /// Send a notification (for testing or manual responses).
    pub async fn send_notification(&self, data: Vec<u8>) -> Result<()> {
        let tx_guard = self.response_tx.lock().await;
        if let Some(ref tx) = *tx_guard {
            tx.send(data).await?;
        }
        Ok(())
    }
}
