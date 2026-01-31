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

//! Message protocol definitions and serialization.

use anyhow::{anyhow, Result};
use serde::{Deserialize, Serialize};
use std::time::{SystemTime, UNIX_EPOCH};

use crate::crypto::CryptoContext;

/// Protocol version.
pub const PROTOCOL_VERSION: u8 = 1;

/// Message types supported by the protocol.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum MessageType {
    #[serde(rename = "TEXT")]
    Text,
    #[serde(rename = "COMMAND")]
    Command,
    #[serde(rename = "HEARTBEAT")]
    Heartbeat,
    #[serde(rename = "ACK")]
    Ack,
    #[serde(rename = "PAIR_REQ")]
    PairReq,
    #[serde(rename = "PAIR_ACK")]
    PairAck,
}

impl MessageType {
    /// Convert to string representation.
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Text => "TEXT",
            Self::Command => "COMMAND",
            Self::Heartbeat => "HEARTBEAT",
            Self::Ack => "ACK",
            Self::PairReq => "PAIR_REQ",
            Self::PairAck => "PAIR_ACK",
        }
    }
}

/// Protocol message structure.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Message {
    /// Protocol version
    #[serde(rename = "v")]
    pub version: u8,

    /// Message type
    #[serde(rename = "t")]
    pub message_type: MessageType,

    /// Payload content (may be encrypted)
    #[serde(rename = "p")]
    pub payload: String,

    /// Timestamp in milliseconds
    #[serde(rename = "ts")]
    pub timestamp: u64,

    /// Checksum (first 8 chars of SHA-256)
    #[serde(rename = "cs")]
    pub checksum: String,
}

impl Message {
    /// Create a new message with automatic timestamp.
    pub fn new(message_type: MessageType, payload: impl Into<String>) -> Self {
        let timestamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_millis() as u64;

        Self {
            version: PROTOCOL_VERSION,
            message_type,
            payload: payload.into(),
            timestamp,
            checksum: String::new(),
        }
    }

    /// Create a TEXT message.
    pub fn text(content: impl Into<String>) -> Self {
        Self::new(MessageType::Text, content)
    }

    /// Create a COMMAND message.
    pub fn command(cmd: impl Into<String>) -> Self {
        Self::new(MessageType::Command, cmd)
    }

    /// Create a HEARTBEAT message.
    pub fn heartbeat() -> Self {
        Self::new(MessageType::Heartbeat, "")
    }

    /// Create an ACK message for a given timestamp.
    pub fn ack(original_timestamp: u64) -> Self {
        Self::new(MessageType::Ack, original_timestamp.to_string())
    }

    /// Sign the message with a crypto context.
    pub fn sign(&mut self, ctx: &CryptoContext) {
        self.checksum = ctx.checksum(
            self.version,
            self.message_type.as_str(),
            &self.payload,
            self.timestamp,
        );
    }

    /// Sign and optionally encrypt the message payload.
    pub fn sign_and_encrypt(&mut self, ctx: &CryptoContext) -> Result<()> {
        // Encrypt payload for sensitive message types
        if matches!(
            self.message_type,
            MessageType::Text | MessageType::Command | MessageType::PairReq | MessageType::PairAck
        ) {
            self.payload = ctx.encrypt(&self.payload)?;
        }

        self.sign(ctx);
        Ok(())
    }

    /// Verify the message checksum.
    pub fn verify(&self, ctx: &CryptoContext) -> bool {
        ctx.verify_checksum(
            self.version,
            self.message_type.as_str(),
            &self.payload,
            self.timestamp,
            &self.checksum,
        )
    }

    /// Verify and decrypt the message payload.
    pub fn verify_and_decrypt(&mut self, ctx: &CryptoContext) -> Result<()> {
        if !self.verify(ctx) {
            return Err(anyhow!("Checksum verification failed"));
        }

        // Decrypt payload for sensitive message types
        if matches!(
            self.message_type,
            MessageType::Text | MessageType::Command | MessageType::PairReq | MessageType::PairAck
        ) {
            self.payload = ctx.decrypt(&self.payload)?;
        }

        Ok(())
    }

    /// Serialize to JSON string with newline delimiter.
    pub fn to_json(&self) -> Result<String> {
        let json = serde_json::to_string(self)?;
        Ok(format!("{}\n", json))
    }

    /// Parse from JSON string.
    pub fn from_json(json: &str) -> Result<Self> {
        let trimmed = json.trim();
        let msg: Self = serde_json::from_str(trimmed)?;
        Ok(msg)
    }
}

/// Pairing request payload.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairRequestPayload {
    pub device_id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub device_name: Option<String>,
}

impl PairRequestPayload {
    pub fn new(device_id: impl Into<String>) -> Self {
        Self {
            device_id: device_id.into(),
            device_name: None,
        }
    }

    pub fn with_name(mut self, name: impl Into<String>) -> Self {
        self.device_name = Some(name.into());
        self
    }

    pub fn to_json(&self) -> Result<String> {
        Ok(serde_json::to_string(self)?)
    }

    pub fn from_json(json: &str) -> Result<Self> {
        Ok(serde_json::from_str(json)?)
    }
}

/// Pairing acknowledgment payload.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairAckPayload {
    pub device_id: String,
    pub status: PairStatus,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum PairStatus {
    Ok,
    Error,
}

impl PairAckPayload {
    pub fn success(device_id: impl Into<String>) -> Self {
        Self {
            device_id: device_id.into(),
            status: PairStatus::Ok,
            error: None,
        }
    }

    pub fn error(device_id: impl Into<String>, error: impl Into<String>) -> Self {
        Self {
            device_id: device_id.into(),
            status: PairStatus::Error,
            error: Some(error.into()),
        }
    }

    pub fn to_json(&self) -> Result<String> {
        Ok(serde_json::to_string(self)?)
    }

    pub fn from_json(json: &str) -> Result<Self> {
        Ok(serde_json::from_str(json)?)
    }
}

/// Voice command codes.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CommandCode {
    Enter,
    SelectAll,
    Copy,
    Paste,
    Cut,
    Cancel,
}

impl CommandCode {
    /// Parse from string.
    pub fn parse(s: &str) -> Option<Self> {
        match s.trim().to_uppercase().as_str() {
            "ENTER" => Some(Self::Enter),
            "SELECT_ALL" => Some(Self::SelectAll),
            "COPY" => Some(Self::Copy),
            "PASTE" => Some(Self::Paste),
            "CUT" => Some(Self::Cut),
            "CANCEL" => Some(Self::Cancel),
            _ => None,
        }
    }

    /// Convert to string.
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Enter => "ENTER",
            Self::SelectAll => "SELECT_ALL",
            Self::Copy => "COPY",
            Self::Paste => "PASTE",
            Self::Cut => "CUT",
            Self::Cancel => "CANCEL",
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_message_serialization() {
        let msg = Message::text("Hello, World!");
        let json = msg.to_json().unwrap();

        assert!(json.contains("\"v\":1"));
        assert!(json.contains("\"t\":\"TEXT\""));
        assert!(json.contains("\"p\":\"Hello, World!\""));
        assert!(json.ends_with('\n'));

        let parsed = Message::from_json(&json).unwrap();
        assert_eq!(parsed.version, 1);
        assert_eq!(parsed.message_type, MessageType::Text);
        assert_eq!(parsed.payload, "Hello, World!");
    }

    #[test]
    fn test_message_signing() {
        let ctx = CryptoContext::from_pin("123456", "android-abc", "linux-xyz");

        let mut msg = Message::text("test");
        msg.sign(&ctx);

        assert!(!msg.checksum.is_empty());
        assert!(msg.verify(&ctx));

        // Tamper with payload
        msg.payload = "tampered".to_string();
        assert!(!msg.verify(&ctx));
    }

    #[test]
    fn test_message_encryption() {
        let ctx = CryptoContext::from_pin("123456", "android-abc", "linux-xyz");

        let mut msg = Message::text("secret message");
        let original_payload = msg.payload.clone();

        msg.sign_and_encrypt(&ctx).unwrap();
        assert_ne!(msg.payload, original_payload);

        msg.verify_and_decrypt(&ctx).unwrap();
        assert_eq!(msg.payload, original_payload);
    }

    #[test]
    fn test_command_codes() {
        assert_eq!(CommandCode::parse("ENTER"), Some(CommandCode::Enter));
        assert_eq!(CommandCode::parse("enter"), Some(CommandCode::Enter));
        assert_eq!(CommandCode::parse("SELECT_ALL"), Some(CommandCode::SelectAll));
        assert_eq!(CommandCode::parse("invalid"), None);
    }

    #[test]
    fn test_pair_payloads() {
        let req = PairRequestPayload::new("android-123")
            .with_name("My Phone");
        let json = req.to_json().unwrap();
        let parsed = PairRequestPayload::from_json(&json).unwrap();
        
        assert_eq!(parsed.device_id, "android-123");
        assert_eq!(parsed.device_name, Some("My Phone".to_string()));

        let ack = PairAckPayload::success("linux-456");
        let json = ack.to_json().unwrap();
        let parsed = PairAckPayload::from_json(&json).unwrap();
        
        assert_eq!(parsed.device_id, "linux-456");
        assert_eq!(parsed.status, PairStatus::Ok);
    }
}
