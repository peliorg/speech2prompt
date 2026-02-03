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

//! System tray implementation using ksni.

use anyhow::Result;
use ksni::{self, menu::StandardItem, Handle, MenuItem, Tray, TrayService};
use std::sync::Arc;
use tokio::sync::mpsc;
use tracing::info;

use crate::state::{AppState, ConnectionStatus};

/// Actions that can be triggered from the tray menu.
#[derive(Debug, Clone)]
pub enum TrayAction {
    ToggleInput,
    ManageCommands,
    ShowSettings,
    Quit,
}

/// System tray icon and menu.
pub struct Speech2PromptTray {
    state: Arc<AppState>,
    action_tx: mpsc::UnboundedSender<TrayAction>,
}

impl Speech2PromptTray {
    pub fn new(state: Arc<AppState>, action_tx: mpsc::UnboundedSender<TrayAction>) -> Self {
        Self { state, action_tx }
    }
}

impl Tray for Speech2PromptTray {
    fn icon_name(&self) -> String {
        let status = self.state.get_status();
        status.icon_name().to_string()
    }

    fn title(&self) -> String {
        "Speech2Prompt".to_string()
    }

    fn tool_tip(&self) -> ksni::ToolTip {
        let status = self.state.get_status();
        let title = "Speech2Prompt".to_string();

        let description = match status {
            ConnectionStatus::Connected => {
                let device = self.state.get_device_name().unwrap_or_default();
                let enabled = if self.state.is_input_enabled() {
                    "Input enabled"
                } else {
                    "Input disabled"
                };
                format!("Connected to {}\n{}", device, enabled)
            }
            ConnectionStatus::Disconnected => "Waiting for connection...".to_string(),
            ConnectionStatus::Connecting => "Connecting...".to_string(),
            ConnectionStatus::Error => "Connection error".to_string(),
        };

        ksni::ToolTip {
            icon_name: String::new(),
            icon_pixmap: Vec::new(),
            title,
            description,
        }
    }

    fn menu(&self) -> Vec<MenuItem<Self>> {
        let status = self.state.get_status();
        let input_enabled = self.state.is_input_enabled();

        let mut items = vec![];

        // Status header
        let status_text = match status {
            ConnectionStatus::Connected => {
                let device = self
                    .state
                    .get_device_name()
                    .unwrap_or_else(|| "Unknown".to_string());
                format!("● Connected: {}", device)
            }
            ConnectionStatus::Disconnected => "○ Disconnected".to_string(),
            ConnectionStatus::Connecting => "◐ Connecting...".to_string(),
            ConnectionStatus::Error => "✕ Error".to_string(),
        };

        items.push(MenuItem::Standard(StandardItem {
            label: status_text,
            enabled: false,
            ..Default::default()
        }));

        items.push(MenuItem::Separator);

        // Input toggle
        let input_label = if input_enabled {
            "✓ Input Enabled"
        } else {
            "○ Input Disabled"
        };

        items.push(MenuItem::Standard(StandardItem {
            label: input_label.to_string(),
            activate: Box::new(|tray: &mut Self| {
                let _ = tray.action_tx.send(TrayAction::ToggleInput);
            }),
            ..Default::default()
        }));

        items.push(MenuItem::Separator);

        // Manage Commands
        items.push(MenuItem::Standard(StandardItem {
            label: "Manage Commands...".to_string(),
            activate: Box::new(|tray: &mut Self| {
                let _ = tray.action_tx.send(TrayAction::ManageCommands);
            }),
            ..Default::default()
        }));

        // Settings
        items.push(MenuItem::Standard(StandardItem {
            label: "Settings...".to_string(),
            activate: Box::new(|tray: &mut Self| {
                let _ = tray.action_tx.send(TrayAction::ShowSettings);
            }),
            ..Default::default()
        }));

        items.push(MenuItem::Separator);

        // Quit
        items.push(MenuItem::Standard(StandardItem {
            label: "Quit".to_string(),
            activate: Box::new(|tray: &mut Self| {
                let _ = tray.action_tx.send(TrayAction::Quit);
            }),
            ..Default::default()
        }));

        items
    }

    fn id(&self) -> String {
        "speech2prompt".to_string()
    }

    fn category(&self) -> ksni::Category {
        ksni::Category::Communications
    }
}

/// Run the system tray service.
pub fn run_tray(
    state: Arc<AppState>,
) -> Result<(
    mpsc::UnboundedReceiver<TrayAction>,
    Handle<Speech2PromptTray>,
)> {
    let (action_tx, action_rx) = mpsc::unbounded_channel();

    let tray = Speech2PromptTray::new(state, action_tx);
    let service = TrayService::new(tray);
    let handle = service.handle();

    // Spawn the tray service
    std::thread::spawn(move || {
        let _ = service.run();
    });

    info!("System tray started");

    Ok((action_rx, handle))
}
