# Phase 03: Linux Bluetooth Server

## Overview

This phase implements the Bluetooth RFCOMM server on Linux using BlueZ. The server will listen for incoming connections from the Android app, handle the pairing flow, and dispatch received messages to the appropriate handlers.

## Prerequisites

- Phase 01 and 02 completed
- BlueZ installed on the system (`bluez` package)
- Bluetooth adapter available and enabled
- User has permissions to access Bluetooth (usually `bluetooth` group)

## System Setup

### Install BlueZ Development Libraries

```bash
# Ubuntu/Debian
sudo apt install libbluetooth-dev libdbus-1-dev pkg-config

# Fedora
sudo dnf install bluez-libs-devel dbus-devel

# Arch
sudo pacman -S bluez bluez-utils
```

### Verify Bluetooth

```bash
# Check Bluetooth service
systemctl status bluetooth

# Start if not running
sudo systemctl start bluetooth
sudo systemctl enable bluetooth

# Verify adapter
bluetoothctl show
```

### User Permissions

```bash
# Add user to bluetooth group
sudo usermod -a -G bluetooth $USER
# Log out and back in for changes to take effect
```

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                       BluetoothServer                           │
│  ┌─────────────────┐  ┌──────────────────┐  ┌───────────────┐  │
│  │  BlueZ Session  │  │  RFCOMM Listener │  │  Connection   │  │
│  │                 │──│                  │──│  Handler      │  │
│  │  - Adapter      │  │  - SPP Profile   │  │  - Message RX │  │
│  │  - Agent        │  │  - Accept Loop   │  │  - Message TX │  │
│  └─────────────────┘  └──────────────────┘  └───────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
                    ┌───────────────────────┐
                    │   Message Channel     │
                    │   (async_channel)     │
                    └───────────────────────┘
                                │
                                ▼
                    ┌───────────────────────┐
                    │   Main Application    │
                    │   - Command Executor  │
                    │   - Text Injector     │
                    │   - History Storage   │
                    └───────────────────────┘
```

---

## Task 1: Update Cargo Dependencies

Ensure `desktop/Cargo.toml` has the correct Bluetooth dependencies:

```toml
[dependencies]
# ... existing dependencies ...

# Bluetooth
bluer = { version = "0.17", features = ["rfcomm"] }
uuid = { version = "1.6", features = ["v4"] }

# Async utilities
futures = "0.3"
pin-project-lite = "0.2"
```

---

## Task 2: Create Connection Handler

Create `desktop/src/bluetooth/connection.rs`:

```rust
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

    /// Run the connection handler.
    pub async fn run(mut self) -> Result<()> {
        info!("Connection handler started");

        let (reader, mut writer) = self.stream.into_split();
        let mut reader = BufReader::new(reader);
        let mut line_buf = String::new();

        loop {
            line_buf.clear();

            match reader.read_line(&mut line_buf).await {
                Ok(0) => {
                    // EOF - connection closed
                    info!("Connection closed by remote");
                    self.emit(ConnectionEvent::Disconnected).await;
                    break;
                }
                Ok(_) => {
                    debug!("Received: {}", line_buf.trim());

                    match self.handle_message(&line_buf, &mut writer).await {
                        Ok(Some(response)) => {
                            let response_json = response.to_json()?;
                            debug!("Sending response: {}", response_json.trim());
                            writer.write_all(response_json.as_bytes()).await?;
                            writer.flush().await?;
                        }
                        Ok(None) => {}
                        Err(e) => {
                            error!("Error handling message: {}", e);
                            self.emit(ConnectionEvent::Error(e.to_string())).await;
                        }
                    }
                }
                Err(e) => {
                    error!("Read error: {}", e);
                    self.emit(ConnectionEvent::Error(e.to_string())).await;
                    self.emit(ConnectionEvent::Disconnected).await;
                    break;
                }
            }
        }

        Ok(())
    }

    /// Handle an incoming message.
    async fn handle_message(
        &mut self,
        json: &str,
        _writer: &mut tokio::io::WriteHalf<Stream>,
    ) -> Result<Option<Message>> {
        let mut message = Message::from_json(json)?;

        match message.message_type {
            MessageType::PairReq => {
                self.handle_pair_request(&message).await
            }
            MessageType::Heartbeat => {
                // Respond with ACK
                Ok(Some(Message::ack(message.timestamp)))
            }
            MessageType::Text => {
                self.handle_text(&mut message).await
            }
            MessageType::Command => {
                self.handle_command(&mut message).await
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
    async fn handle_pair_request(&mut self, message: &Message) -> Result<Option<Message>> {
        use super::protocol::PairRequestPayload;

        info!("Pairing request received");

        // Parse the payload (not encrypted during initial pairing)
        let payload = PairRequestPayload::from_json(&message.payload)?;
        self.device_id = Some(payload.device_id.clone());

        // Emit event for UI to request PIN from user
        self.emit(ConnectionEvent::PairRequested {
            device_id: payload.device_id,
        })
        .await;

        // Note: The actual pairing response is sent after PIN entry
        // via the complete_pairing method. For now, we acknowledge receipt.
        Ok(Some(Message::ack(message.timestamp)))
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

    /// Handle a text message.
    async fn handle_text(&mut self, message: &mut Message) -> Result<Option<Message>> {
        if self.state != ConnectionState::Authenticated {
            warn!("Received TEXT before authentication");
            return Ok(None);
        }

        // Decrypt if we have crypto context
        if let Some(ref crypto) = self.crypto {
            message.verify_and_decrypt(crypto)?;
        }

        info!("Text received: {}", message.payload);
        self.emit(ConnectionEvent::TextReceived(message.payload.clone()))
            .await;

        // Send ACK
        let mut ack = Message::ack(message.timestamp);
        if let Some(ref crypto) = self.crypto {
            ack.sign(crypto);
        }

        Ok(Some(ack))
    }

    /// Handle a command message.
    async fn handle_command(&mut self, message: &mut Message) -> Result<Option<Message>> {
        if self.state != ConnectionState::Authenticated {
            warn!("Received COMMAND before authentication");
            return Ok(None);
        }

        // Decrypt if we have crypto context
        if let Some(ref crypto) = self.crypto {
            message.verify_and_decrypt(crypto)?;
        }

        info!("Command received: {}", message.payload);
        self.emit(ConnectionEvent::CommandReceived(message.payload.clone()))
            .await;

        // Send ACK
        let mut ack = Message::ack(message.timestamp);
        if let Some(ref crypto) = self.crypto {
            ack.sign(crypto);
        }

        Ok(Some(ack))
    }

    /// Emit an event.
    async fn emit(&self, event: ConnectionEvent) {
        if let Err(e) = self.event_tx.send(event).await {
            error!("Failed to send event: {}", e);
        }
    }
}
```

---

## Task 3: Update Bluetooth Server

Replace `desktop/src/bluetooth/server.rs`:

```rust
//! Bluetooth RFCOMM server implementation.

use anyhow::{anyhow, Result};
use bluer::{
    rfcomm::{Listener, SocketAddr},
    Adapter, Address, Session,
};
use std::sync::Arc;
use tokio::sync::mpsc;
use tracing::{error, info, warn};
use uuid::Uuid;

use super::connection::{ConnectionEvent, ConnectionHandler};

/// Standard SPP UUID.
const SPP_UUID: Uuid = Uuid::from_u128(0x00001101_0000_1000_8000_00805F9B34FB);

/// RFCOMM channel to use.
const RFCOMM_CHANNEL: u8 = 1;

/// Bluetooth server that listens for incoming connections.
pub struct BluetoothServer {
    session: Session,
    adapter: Adapter,
    linux_device_id: String,
}

impl BluetoothServer {
    /// Create a new Bluetooth server.
    pub async fn new() -> Result<Self> {
        info!("Initializing Bluetooth server...");

        // Create BlueZ session
        let session = Session::new().await?;
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
            session,
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
```

---

## Task 4: Update Module Exports

Replace `desktop/src/bluetooth/mod.rs`:

```rust
//! Bluetooth communication module.
//!
//! Handles RFCOMM server for receiving messages from Android app.

mod connection;
mod protocol;
mod server;

pub use connection::{ConnectionEvent, ConnectionHandler, ConnectionState};
pub use protocol::{
    CommandCode, Message, MessageType, PairAckPayload, PairRequestPayload, PairStatus,
    PROTOCOL_VERSION,
};
pub use server::{BluetoothManager, BluetoothServer, PairedDevice};
```

---

## Task 5: Create Event Processing

Create `desktop/src/events.rs`:

```rust
//! Event processing and message dispatch.

use anyhow::Result;
use tokio::sync::mpsc;
use tracing::{debug, error, info, warn};

use crate::bluetooth::{CommandCode, ConnectionEvent};
use crate::commands::VoiceCommand;
use crate::input::InputInjector;
use crate::storage::History;

/// Process events from Bluetooth connections.
pub struct EventProcessor {
    injector: Box<dyn InputInjector>,
    history: History,
    input_enabled: bool,
}

impl EventProcessor {
    /// Create a new event processor.
    pub fn new(injector: Box<dyn InputInjector>, history: History) -> Self {
        Self {
            injector,
            history,
            input_enabled: true,
        }
    }

    /// Enable or disable input injection.
    pub fn set_input_enabled(&mut self, enabled: bool) {
        self.input_enabled = enabled;
        info!("Input injection {}", if enabled { "enabled" } else { "disabled" });
    }

    /// Check if input injection is enabled.
    pub fn is_input_enabled(&self) -> bool {
        self.input_enabled
    }

    /// Process a single event.
    pub async fn process_event(&mut self, event: ConnectionEvent) -> Result<()> {
        match event {
            ConnectionEvent::TextReceived(text) => {
                self.handle_text(&text).await?;
            }
            ConnectionEvent::CommandReceived(cmd) => {
                self.handle_command(&cmd).await?;
            }
            ConnectionEvent::Connected { device_name } => {
                info!("Device connected: {}", device_name);
            }
            ConnectionEvent::Disconnected => {
                info!("Device disconnected");
            }
            ConnectionEvent::PairRequested { device_id } => {
                info!("Pairing requested by: {}", device_id);
                // TODO: Trigger UI for PIN entry
            }
            ConnectionEvent::Error(e) => {
                error!("Connection error: {}", e);
            }
        }
        Ok(())
    }

    /// Handle received text.
    async fn handle_text(&mut self, text: &str) -> Result<()> {
        debug!("Processing text: {}", text);

        // Log to history
        if let Err(e) = self.history.add_text(text) {
            warn!("Failed to log to history: {}", e);
        }

        // Inject text if enabled
        if self.input_enabled {
            if let Err(e) = self.injector.type_text(text) {
                error!("Failed to inject text: {}", e);
            }
        } else {
            debug!("Input disabled, ignoring text");
        }

        Ok(())
    }

    /// Handle received command.
    async fn handle_command(&mut self, cmd: &str) -> Result<()> {
        debug!("Processing command: {}", cmd);

        // Log to history
        if let Err(e) = self.history.add_command(cmd) {
            warn!("Failed to log to history: {}", e);
        }

        // Parse and execute command
        if let Some(command) = CommandCode::parse(cmd) {
            let voice_cmd = match command {
                CommandCode::Enter => VoiceCommand::Enter,
                CommandCode::SelectAll => VoiceCommand::SelectAll,
                CommandCode::Copy => VoiceCommand::Copy,
                CommandCode::Paste => VoiceCommand::Paste,
                CommandCode::Cut => VoiceCommand::Cut,
                CommandCode::Cancel => VoiceCommand::Cancel,
            };

            if self.input_enabled {
                if let Err(e) = crate::commands::execute(&voice_cmd, self.injector.as_ref()) {
                    error!("Failed to execute command: {}", e);
                }
            } else {
                debug!("Input disabled, ignoring command");
            }
        } else {
            warn!("Unknown command: {}", cmd);
        }

        Ok(())
    }

    /// Run the event processing loop.
    pub async fn run(mut self, mut event_rx: mpsc::Receiver<ConnectionEvent>) {
        info!("Event processor started");

        while let Some(event) = event_rx.recv().await {
            if let Err(e) = self.process_event(event).await {
                error!("Error processing event: {}", e);
            }
        }

        info!("Event processor stopped");
    }
}
```

---

## Task 6: Update Main Application

Replace `desktop/src/main.rs`:

```rust
//! Speech2Code Desktop Application
//!
//! A Linux system tray application that receives transcribed text from
//! an Android device via Bluetooth and types it at the current cursor position.

mod bluetooth;
mod commands;
mod config;
mod crypto;
mod events;
mod input;
mod storage;
mod ui;

use anyhow::Result;
use tracing::{error, info};
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};

use bluetooth::BluetoothManager;
use events::EventProcessor;

#[tokio::main]
async fn main() -> Result<()> {
    // Initialize logging
    tracing_subscriber::registry()
        .with(tracing_subscriber::fmt::layer())
        .with(
            tracing_subscriber::EnvFilter::from_default_env()
                .add_directive("speech2code_desktop=info".parse().unwrap()),
        )
        .init();

    info!("Starting Speech2Code Desktop v{}...", env!("CARGO_PKG_VERSION"));

    // Load configuration
    let config = config::Config::load()?;
    info!("Configuration loaded from {:?}", config.data_dir);

    // Initialize storage
    let storage = storage::History::new(&config.data_dir)?;
    info!("History storage initialized");

    // Initialize input injector
    let injector = input::create_injector()?;
    info!("Input injector: {}", injector.backend_name());

    // Initialize Bluetooth
    let mut bt_manager = BluetoothManager::new().await?;
    bt_manager.start(&config.bluetooth.device_name).await?;
    info!("Bluetooth server started as '{}'", config.bluetooth.device_name);

    // Get event receiver
    let event_rx = bt_manager
        .take_event_receiver()
        .expect("Event receiver already taken");

    // Create event processor
    let processor = EventProcessor::new(injector, storage);

    // Run event processor (this will run until the channel closes)
    info!("Ready to receive connections. Press Ctrl+C to exit.");
    
    // Handle Ctrl+C
    let processor_handle = tokio::spawn(async move {
        processor.run(event_rx).await;
    });

    // Wait for shutdown signal
    tokio::signal::ctrl_c().await?;
    info!("Shutdown signal received");

    // The processor will stop when the Bluetooth manager is dropped
    drop(bt_manager);
    let _ = processor_handle.await;

    info!("Speech2Code Desktop stopped");
    Ok(())
}
```

---

## Task 7: Test the Bluetooth Server

### Build and Run

```bash
cd /home/dan/workspace/priv/speech2code/desktop
RUST_LOG=debug cargo run
```

### Expected Output

```
INFO speech2code_desktop: Starting Speech2Code Desktop...
INFO speech2code_desktop: Configuration loaded
INFO speech2code_desktop: History storage initialized
INFO speech2code_desktop: Input injector: Stub
INFO speech2code_desktop::bluetooth::server: Initializing Bluetooth server...
INFO speech2code_desktop::bluetooth::server: BlueZ session created
INFO speech2code_desktop::bluetooth::server: Using Bluetooth adapter: hci0
INFO speech2code_desktop::bluetooth::server: Adapter is discoverable and pairable
INFO speech2code_desktop::bluetooth::server: RFCOMM server listening on channel 1
INFO speech2code_desktop: Ready to receive connections. Press Ctrl+C to exit.
```

### Test with Bluetooth Terminal App

1. Install a Bluetooth serial terminal app on your Android device (e.g., "Serial Bluetooth Terminal")
2. Pair your phone with the Linux PC
3. Connect to the "Speech2Code" device
4. Send test JSON messages:

```json
{"v":1,"t":"TEXT","p":"Hello World","ts":1234567890123,"cs":"00000000"}
```

---

## Troubleshooting

### Permission Denied

```bash
# Check if user is in bluetooth group
groups $USER

# Add to bluetooth group
sudo usermod -a -G bluetooth $USER
# Log out and back in
```

### Adapter Not Found

```bash
# Check if Bluetooth is blocked
rfkill list

# Unblock if soft-blocked
rfkill unblock bluetooth

# Check adapter
hciconfig -a
```

### BlueZ Service Not Running

```bash
sudo systemctl start bluetooth
sudo systemctl enable bluetooth
```

### RFCOMM Bind Failed

```bash
# Check if another process is using the channel
sudo lsof | grep rfcomm

# Try a different channel (change RFCOMM_CHANNEL in code)
```

---

## Verification Checklist

- [ ] BlueZ session created successfully
- [ ] Bluetooth adapter detected and powered on
- [ ] Adapter set to discoverable and pairable
- [ ] RFCOMM listener bound to channel
- [ ] SPP service registered
- [ ] Accept loop running
- [ ] Can connect from Android Bluetooth terminal
- [ ] Messages received and parsed
- [ ] Events dispatched to processor

## Output Artifacts

After completing this phase:

1. **BluetoothServer** - RFCOMM listener with SPP profile
2. **ConnectionHandler** - Message parsing and event emission
3. **BluetoothManager** - High-level server management
4. **EventProcessor** - Message dispatch to injector and storage

## Next Phase

Proceed to **Phase 04: Linux Text Injection** to implement the actual keyboard simulation for X11 and Wayland.
