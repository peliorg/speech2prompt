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
            ConnectionEvent::PairRequested { device_id, device_name } => {
                info!("Pairing requested by: {} ({})", 
                      device_name.as_deref().unwrap_or("Unknown"), 
                      device_id);
                // Handled by main event loop
            }
            ConnectionEvent::Error(e) => {
                error!("Connection error: {}", e);
            }
        }
        Ok(())
    }

    /// Handle received text.
    async fn handle_text(&mut self, text: &str) -> Result<()> {
        info!("Processing text: {} chars", text.len());

        // Log to history
        if let Err(e) = self.history.add_text(text) {
            warn!("Failed to log to history: {}", e);
        }

        // Inject text if enabled
        if self.input_enabled {
            info!("Injecting text into active window: {} chars", text.len());
            if let Err(e) = self.injector.type_text(text) {
                error!("Failed to inject text: {}", e);
            } else {
                info!("Text injection successful");
            }
        } else {
            info!("Input disabled, ignoring text: {}", text);
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
