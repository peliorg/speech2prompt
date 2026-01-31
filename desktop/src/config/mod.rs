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

//! Configuration module.
//!
//! Handles loading and saving application settings.

use anyhow::Result;
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

/// Application configuration.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    /// Data directory for history and settings.
    #[serde(skip)]
    pub data_dir: PathBuf,
    
    /// Bluetooth settings.
    pub bluetooth: BluetoothConfig,
    
    /// Input settings.
    pub input: InputConfig,
    
    /// History settings.
    pub history: HistoryConfig,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BluetoothConfig {
    /// Device name advertised over Bluetooth.
    pub device_name: String,
    
    /// Auto-accept connections from paired devices.
    pub auto_accept: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InputConfig {
    /// Delay between keystrokes in milliseconds.
    pub typing_delay_ms: u32,
    
    /// Preferred backend: "auto", "x11", or "wayland".
    pub prefer_backend: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HistoryConfig {
    /// Enable history logging.
    pub enabled: bool,
    
    /// Maximum number of history entries.
    pub max_entries: u32,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            data_dir: dirs::data_dir()
                .unwrap_or_else(|| PathBuf::from("."))
                .join("speech2code"),
            bluetooth: BluetoothConfig {
                device_name: "Speech2Code".to_string(),
                auto_accept: true,
            },
            input: InputConfig {
                typing_delay_ms: 10,
                prefer_backend: "auto".to_string(),
            },
            history: HistoryConfig {
                enabled: true,
                max_entries: 10000,
            },
        }
    }
}

impl Config {
    /// Load configuration from file or create default.
    pub fn load() -> Result<Self> {
        let config_dir = dirs::config_dir()
            .unwrap_or_else(|| PathBuf::from("."))
            .join("speech2code");
        
        std::fs::create_dir_all(&config_dir)?;
        
        let config_path = config_dir.join("config.toml");
        
        let mut config = if config_path.exists() {
            let content = std::fs::read_to_string(&config_path)?;
            toml::from_str(&content)?
        } else {
            let config = Self::default();
            let content = toml::to_string_pretty(&config)?;
            std::fs::write(&config_path, content)?;
            config
        };
        
        // Set data directory
        config.data_dir = dirs::data_dir()
            .unwrap_or_else(|| PathBuf::from("."))
            .join("speech2code");
        std::fs::create_dir_all(&config.data_dir)?;
        
        Ok(config)
    }
    
    /// Save configuration to file.
    pub fn save(&self) -> Result<()> {
        let config_dir = dirs::config_dir()
            .unwrap_or_else(|| PathBuf::from("."))
            .join("speech2code");
        
        let config_path = config_dir.join("config.toml");
        let content = toml::to_string_pretty(self)?;
        std::fs::write(config_path, content)?;
        
        Ok(())
    }
}
