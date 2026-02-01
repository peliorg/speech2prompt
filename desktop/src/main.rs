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

//! Speech2Prompt Desktop Application

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
use gtk4::prelude::*;
use std::sync::Arc;
use tokio::sync::Mutex;
use tracing::{error, info};
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};

use bluetooth::GattServer;
use events::EventProcessor;
use state::AppState;
use storage::History;

/// Request to show confirmation dialog for pairing.
#[derive(Debug, Clone)]
struct PairingRequest {
    device_id: String,
    device_name: Option<String>,
}

#[tokio::main]
async fn main() -> Result<()> {
    // Initialize logging
    tracing_subscriber::registry()
        .with(tracing_subscriber::fmt::layer())
        .with(
            tracing_subscriber::EnvFilter::from_default_env()
                .add_directive("speech2prompt_desktop=info".parse().unwrap()),
        )
        .init();

    info!(
        "Starting Speech2Prompt Desktop v{}...",
        env!("CARGO_PKG_VERSION")
    );

    // Load configuration
    let config = config::Config::load()?;
    info!("Configuration loaded");

    // Initialize storage
    let history = Arc::new(History::new(&config.data_dir)?);
    info!("History storage initialized");

    // Initialize GTK (required for PIN dialog)
    gtk4::init().expect("Failed to initialize GTK");
    let gtk_app = gtk4::Application::builder()
        .application_id("com.speech2prompt.desktop")
        .build();
    // Register the application so windows can be created
    gtk_app.register(None::<&gtk4::gio::Cancellable>)?;
    info!("GTK initialized");

    // Initialize input injector
    let injector = input::create_injector()?;
    info!("Input injector: {}", injector.backend_name());

    // Create application state
    let state = AppState::new();

    // Initialize BLE GATT server
    info!("Initializing BLE GATT server...");
    let (gatt_event_tx, gatt_event_rx) = tokio::sync::mpsc::channel::<bluetooth::ConnectionEvent>(32);
    let gatt_server = Arc::new(Mutex::new(GattServer::new(gatt_event_tx).await?));
    {
        let mut server = gatt_server.lock().await;
        server.set_name(&config.bluetooth.device_name).await?;
        server.start().await?;
    }
    info!(
        "BLE GATT server started and advertising as '{}'",
        config.bluetooth.device_name
    );

    // Create channel for pairing requests
    let (pairing_tx, mut pairing_rx) = tokio::sync::mpsc::channel::<PairingRequest>(8);

    // Create event processor
    let processor = EventProcessor::new(injector, (*history).clone());

    // Handle BLE GATT events
    let state_gatt = state.clone();
    let mut gatt_event_rx_state = gatt_event_rx;
    
    tokio::spawn(async move {
        let mut processor_gatt = processor;
        
        while let Some(event) = gatt_event_rx_state.recv().await {
            // Sync input_enabled state before processing each event
            processor_gatt.set_input_enabled(state_gatt.is_input_enabled());
            
            // Update state
            match &event {
                bluetooth::ConnectionEvent::Connected { device_name } => {
                    info!("BLE device connected: {}", device_name);
                    state_gatt.set_connected(device_name.clone());
                }
                bluetooth::ConnectionEvent::Disconnected => {
                    info!("BLE device disconnected");
                    state_gatt.set_disconnected();
                }
                bluetooth::ConnectionEvent::Error(e) => {
                    error!("BLE error: {}", e);
                    state_gatt.set_error();
                }
                bluetooth::ConnectionEvent::TextReceived(text) => {
                    info!("BLE text received: {}", text);
                    state_gatt.set_last_text(text.clone());
                }
                bluetooth::ConnectionEvent::PairRequested { device_id, device_name } => {
                    info!("BLE pairing requested by: {}", device_id);
                    // Send to main loop for confirmation dialog handling
                    let _ = pairing_tx.send(PairingRequest { 
                        device_id: device_id.clone(),
                        device_name: device_name.clone(),
                    }).await;
                }
                bluetooth::ConnectionEvent::CommandReceived(_) => {
                    // Will be processed below
                }
            }
            
            // Process event
            if let Err(e) = processor_gatt.process_event(event).await {
                error!("Error processing BLE event: {}", e);
            }
        }
    });

    // Start system tray
    let mut action_rx = ui::run_tray(state.clone())?;
    
    info!("Ready. System tray active.");

    // Handle tray actions and pairing requests
    loop {
        // Process any pending GTK events (non-blocking)
        while gtk4::glib::MainContext::default().pending() {
            gtk4::glib::MainContext::default().iteration(false);
        }
        
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
            Some(request) = pairing_rx.recv() => {
                let display_name = request.device_name.unwrap_or_else(|| request.device_id.clone());
                info!("Showing confirmation dialog for device: {}", display_name);
                
                // Show confirmation dialog
                let mut confirm_rx = ui::show_confirmation_dialog(&gtk_app, &display_name);
                
                // Process GTK events until dialog closes
                let result = loop {
                    while gtk4::glib::MainContext::default().pending() {
                        gtk4::glib::MainContext::default().iteration(false);
                    }
                    
                    match confirm_rx.try_recv() {
                        Ok(result) => break result,
                        Err(tokio::sync::oneshot::error::TryRecvError::Empty) => {
                            tokio::time::sleep(tokio::time::Duration::from_millis(50)).await;
                        }
                        Err(tokio::sync::oneshot::error::TryRecvError::Closed) => {
                            break ui::ConfirmationResult::Rejected;
                        }
                    }
                };
                
                // Handle result
                let server = gatt_server.lock().await;
                match result {
                    ui::ConfirmationResult::Approved => {
                        info!("User approved pairing");
                        if let Err(e) = server.complete_pairing().await {
                            error!("Pairing failed: {}", e);
                        }
                    }
                    ui::ConfirmationResult::Rejected => {
                        info!("User rejected pairing");
                        if let Err(e) = server.reject_pairing("User rejected").await {
                            error!("Failed to send rejection: {}", e);
                        }
                    }
                }
            }
            _ = tokio::signal::ctrl_c() => {
                info!("Shutdown signal received");
                break;
            }
        }
    }

    info!("Speech2Prompt Desktop stopped");
    Ok(())
}
