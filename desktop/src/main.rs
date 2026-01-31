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

//! Speech2Code Desktop Application

mod bluetooth;
mod commands;
mod config;
mod crypto;
mod events;
mod input;
mod state;
mod storage;
mod ui;

use anyhow::Result;
use std::sync::Arc;
use tracing::{error, info};
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};

use bluetooth::BluetoothManager;
use events::EventProcessor;
use state::AppState;
use storage::History;

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

    info!(
        "Starting Speech2Code Desktop v{}...",
        env!("CARGO_PKG_VERSION")
    );

    // Load configuration
    let config = config::Config::load()?;
    info!("Configuration loaded");

    // Initialize storage
    let history = Arc::new(History::new(&config.data_dir)?);
    info!("History storage initialized");

    // Initialize input injector
    let injector = input::create_injector()?;
    info!("Input injector: {}", injector.backend_name());

    // Create application state
    let state = AppState::new();

    // Initialize Bluetooth
    let mut bt_manager = BluetoothManager::new().await?;
    bt_manager.start(&config.bluetooth.device_name).await?;
    info!(
        "Bluetooth server started as '{}'",
        config.bluetooth.device_name
    );

    // Get event receiver
    let event_rx = bt_manager
        .take_event_receiver()
        .expect("Event receiver already taken");

    // Create event processor
    let processor = EventProcessor::new(injector, (*history).clone());

    // Update state from events
    let state_events = state.clone();
    let mut event_rx_state = event_rx;
    
    // Run event processor
    tokio::spawn(async move {
        let mut processor = processor;
        while let Some(event) = event_rx_state.recv().await {
            // Update state
            match &event {
                bluetooth::ConnectionEvent::Connected { device_name } => {
                    state_events.set_connected(device_name.clone());
                }
                bluetooth::ConnectionEvent::Disconnected => {
                    state_events.set_disconnected();
                }
                bluetooth::ConnectionEvent::Error(_) => {
                    state_events.set_error();
                }
                bluetooth::ConnectionEvent::TextReceived(text) => {
                    state_events.set_last_text(text.clone());
                }
                _ => {}
            }
            
            // Process event
            if let Err(e) = processor.process_event(event).await {
                error!("Error processing event: {}", e);
            }
        }
    });

    // Start system tray
    let mut action_rx = ui::run_tray(state.clone())?;
    
    info!("Ready. System tray active.");

    // Handle tray actions
    loop {
        tokio::select! {
            Some(action) = action_rx.recv() => {
                match action {
                    ui::TrayAction::ToggleInput => {
                        let enabled = !state.is_input_enabled();
                        state.set_input_enabled(enabled);
                        info!("Input {}", if enabled { "enabled" } else { "disabled" });
                    }
                    ui::TrayAction::ShowHistory => {
                        info!("History window requested");
                        // TODO: Open GTK window
                    }
                    ui::TrayAction::ShowSettings => {
                        info!("Settings requested");
                    }
                    ui::TrayAction::Quit => {
                        info!("Quit requested");
                        break;
                    }
                }
            }
            _ = tokio::signal::ctrl_c() => {
                info!("Shutdown signal received");
                break;
            }
        }
    }

    info!("Speech2Code Desktop stopped");
    Ok(())
}
