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

//! Individual Bluetooth connection handler.

use anyhow::{anyhow, Result};
use bluer::rfcomm::Stream;
use std::sync::Arc;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::sync::mpsc;
use tracing::{debug, error, info, warn};

use super::protocol::{Message, MessageType, PairAckPayload};
use crate::crypto::CryptoContext;

/// Events emitted by a connection.
#[derive(Debug)]
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
    PairRequested { device_id: String },
    /// Error occurred.
    Error(String),
}

/// State of a connection.
#[derive(Debug, Clone, PartialEq)]
pub enum ConnectionState {
    /// Waiting for pairing.
    AwaitingPair,
    /// Paired and authenticated.
    Authenticated,
    /// Disconnected.
    Disconnected,
}

/// Handler for a single Bluetooth connection.
pub struct ConnectionHandler {
    stream: Stream,
    state: ConnectionState,
    crypto: Option<Arc<CryptoContext>>,
    device_id: Option<String>,
    linux_device_id: String,
    event_tx: mpsc::Sender<ConnectionEvent>,
}

impl ConnectionHandler {
    /// Create a new connection handler.
    pub fn new(
        stream: Stream,
        linux_device_id: String,
        event_tx: mpsc::Sender<ConnectionEvent>,
    ) -> Self {
        Self {
            stream,
            state: ConnectionState::AwaitingPair,
            crypto: None,
            device_id: None,
            linux_device_id,
            event_tx,
        }
    }

    /// Set the crypto context (after pairing).
    pub fn set_crypto(&mut self, crypto: Arc<CryptoContext>) {
        self.crypto = Some(crypto);
        self.state = ConnectionState::Authenticated;
    }

    /// Complete pairing with PIN.
    pub fn complete_pairing(&mut self, pin: &str) -> Result<Message> {
        let device_id = self
            .device_id
            .as_ref()
            .ok_or_else(|| anyhow!("No device ID set"))?;

        // Derive crypto context from PIN
        let crypto = CryptoContext::from_pin(pin, device_id, &self.linux_device_id);
        self.crypto = Some(Arc::new(crypto));
        self.state = ConnectionState::Authenticated;

        // Create signed response
        let payload = PairAckPayload::success(&self.linux_device_id);
        let mut response = Message::new(MessageType::PairAck, payload.to_json()?);

        // Sign (but don't encrypt - client needs to derive same key first)
        if let Some(ref crypto) = self.crypto {
            response.sign(crypto);
        }

        info!("Pairing completed with device: {}", device_id);
        Ok(response)
    }

    /// Complete pairing with PIN (async version).
    /// Called after user enters PIN in UI.
    pub async fn complete_pairing_async(
        &mut self,
        pin: &str,
        writer: &mut tokio::io::WriteHalf<Stream>,
    ) -> Result<()> {
        let android_device_id = self
            .device_id
            .as_ref()
            .ok_or_else(|| anyhow!("No device ID set"))?;

        // Derive crypto context from PIN
        let crypto = CryptoContext::from_pin(pin, android_device_id, &self.linux_device_id);
        
        // Create and send PAIR_ACK
        let payload = PairAckPayload::success(&self.linux_device_id);
        let mut response = Message::new(MessageType::PairAck, payload.to_json()?);
        
        // Sign with the new crypto context
        response.sign(&crypto);
        
        // Send response
        let response_json = response.to_json()?;
        writer.write_all(response_json.as_bytes()).await?;
        writer.flush().await?;

        // Store crypto context
        self.crypto = Some(Arc::new(crypto));
        self.state = ConnectionState::Authenticated;

        info!("Pairing completed with device: {}", android_device_id);

        // Emit connected event
        self.emit(ConnectionEvent::Connected {
            device_name: android_device_id.clone(),
        })
        .await;

        Ok(())
    }

    /// Emit an event.
    async fn emit(&self, event: ConnectionEvent) {
        let _ = self.event_tx.send(event).await;
    }

    /// Run the connection handler.
    pub async fn run(self) -> Result<()> {
        info!("Connection handler started");

        let (reader, mut writer) = self.stream.into_split();
        let mut reader = BufReader::new(reader);
        let mut line_buf = String::new();

        // Store fields we need after the split
        let event_tx = self.event_tx.clone();
        let mut state = self.state;
        let mut crypto = self.crypto;
        let mut device_id = self.device_id;
        let linux_device_id = self.linux_device_id;

        loop {
            line_buf.clear();

            match reader.read_line(&mut line_buf).await {
                Ok(0) => {
                    // EOF - connection closed
                    info!("Connection closed by remote");
                    let _ = event_tx.send(ConnectionEvent::Disconnected).await;
                    break;
                }
                Ok(_) => {
                    debug!("Received: {}", line_buf.trim());

                    match Self::handle_message_static(
                        &line_buf,
                        &mut writer,
                        &mut state,
                        &mut crypto,
                        &mut device_id,
                        &linux_device_id,
                        &event_tx,
                    )
                    .await
                    {
                        Ok(Some(response)) => {
                            let response_json = response.to_json()?;
                            debug!("Sending response: {}", response_json.trim());
                            writer.write_all(response_json.as_bytes()).await?;
                            writer.flush().await?;
                        }
                        Ok(None) => {}
                        Err(e) => {
                            error!("Error handling message: {}", e);
                            let _ = event_tx.send(ConnectionEvent::Error(e.to_string())).await;
                        }
                    }
                }
                Err(e) => {
                    error!("Read error: {}", e);
                    let _ = event_tx.send(ConnectionEvent::Error(e.to_string())).await;
                    let _ = event_tx.send(ConnectionEvent::Disconnected).await;
                    break;
                }
            }
        }

        Ok(())
    }

    /// Handle an incoming message.
    async fn handle_message_static(
        json: &str,
        _writer: &mut bluer::rfcomm::stream::OwnedWriteHalf,
        state: &mut ConnectionState,
        crypto: &mut Option<Arc<CryptoContext>>,
        device_id: &mut Option<String>,
        linux_device_id: &str,
        event_tx: &mpsc::Sender<ConnectionEvent>,
    ) -> Result<Option<Message>> {
        let mut message = Message::from_json(json)?;

        match message.message_type {
            MessageType::PairReq => {
                Self::handle_pair_request_static(&message, device_id, event_tx).await
            }
            MessageType::Heartbeat => {
                // Respond with ACK
                Ok(Some(Message::ack(message.timestamp)))
            }
            MessageType::Text => {
                Self::handle_text_static(&mut message, state, crypto, event_tx).await
            }
            MessageType::Command => {
                Self::handle_command_static(&mut message, state, crypto, event_tx).await
            }
            MessageType::Ack => {
                // Acknowledgment received, nothing to do
                debug!("ACK received for timestamp: {}", message.payload);
                Ok(None)
            }
            MessageType::PairAck => {
                warn!("Unexpected PAIR_ACK from client");
                Ok(None)
            }
        }
    }

    /// Handle a pairing request.
    async fn handle_pair_request_static(
        message: &Message,
        device_id: &mut Option<String>,
        event_tx: &mpsc::Sender<ConnectionEvent>,
    ) -> Result<Option<Message>> {
        use super::protocol::PairRequestPayload;

        info!("Pairing request received");

        // Parse the payload (not encrypted during initial pairing)
        let payload = PairRequestPayload::from_json(&message.payload)?;
        *device_id = Some(payload.device_id.clone());

        // Emit event for UI to request PIN from user
        let _ = event_tx
            .send(ConnectionEvent::PairRequested {
                device_id: payload.device_id,
            })
            .await;

        // Note: The actual pairing response is sent after PIN entry
        // via the complete_pairing method. For now, we acknowledge receipt.
        Ok(Some(Message::ack(message.timestamp)))
    }

    /// Handle a text message.
    async fn handle_text_static(
        message: &mut Message,
        state: &ConnectionState,
        crypto: &Option<Arc<CryptoContext>>,
        event_tx: &mpsc::Sender<ConnectionEvent>,
    ) -> Result<Option<Message>> {
        if *state != ConnectionState::Authenticated {
            warn!("Received TEXT before authentication");
            return Ok(None);
        }

        // Decrypt if we have crypto context
        if let Some(ref crypto) = crypto {
            message.verify_and_decrypt(crypto)?;
        }

        info!("Text received: {}", message.payload);
        let _ = event_tx
            .send(ConnectionEvent::TextReceived(message.payload.clone()))
            .await;

        // Send ACK
        let mut ack = Message::ack(message.timestamp);
        if let Some(ref crypto) = crypto {
            ack.sign(crypto);
        }

        Ok(Some(ack))
    }

    /// Handle a command message.
    async fn handle_command_static(
        message: &mut Message,
        state: &ConnectionState,
        crypto: &Option<Arc<CryptoContext>>,
        event_tx: &mpsc::Sender<ConnectionEvent>,
    ) -> Result<Option<Message>> {
        if *state != ConnectionState::Authenticated {
            warn!("Received COMMAND before authentication");
            return Ok(None);
        }

        // Decrypt if we have crypto context
        if let Some(ref crypto) = crypto {
            message.verify_and_decrypt(crypto)?;
        }

        info!("Command received: {}", message.payload);
        let _ = event_tx
            .send(ConnectionEvent::CommandReceived(message.payload.clone()))
            .await;

        // Send ACK
        let mut ack = Message::ack(message.timestamp);
        if let Some(ref crypto) = crypto {
            ack.sign(crypto);
        }

        Ok(Some(ack))
    }
}
