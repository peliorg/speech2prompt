# Phase 05: Linux UI (System Tray & History)

## Overview

This phase implements the user interface for the Linux desktop app: a system tray icon with status indicators and menu, and a history window to browse past transcriptions.

## Prerequisites

- Phase 01-04 completed
- GTK4 development libraries installed
- System tray support (KDE, GNOME with extension, or other DE with StatusNotifierItem support)

## System Setup

### Install GTK4 Development Libraries

```bash
# Ubuntu/Debian
sudo apt install libgtk-4-dev libadwaita-1-dev

# Fedora
sudo dnf install gtk4-devel libadwaita-devel

# Arch
sudo pacman -S gtk4 libadwaita
```

### System Tray Support

- **KDE Plasma**: Built-in support
- **GNOME**: Install "AppIndicator and KStatusNotifierItem Support" extension
- **XFCE**: Install `xfce4-statusnotifier-plugin`
- **Other**: Most modern DEs support StatusNotifierItem

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                          UI Layer                                │
│                                                                  │
│  ┌─────────────────┐  ┌──────────────────┐  ┌───────────────┐  │
│  │   System Tray   │  │  History Window  │  │   PIN Dialog  │  │
│  │   (ksni)        │  │  (GTK4)          │  │   (GTK4)      │  │
│  │                 │  │                  │  │               │  │
│  │  - Status icon  │  │  - Search        │  │  - 6-digit    │  │
│  │  - Menu         │  │  - List view     │  │    entry      │  │
│  │  - Tooltips     │  │  - Export        │  │  - Confirm    │  │
│  └─────────────────┘  └──────────────────┘  └───────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                     │
                     ▼
         ┌───────────────────────┐
         │   Application State   │
         │   (shared via Arc)    │
         └───────────────────────┘
```

---

## Task 1: Update Cargo Dependencies

Add to `desktop/Cargo.toml`:

```toml
[dependencies]
# ... existing dependencies ...

# GUI
gtk4 = "0.7"
libadwaita = { version = "0.5", features = ["v1_4"] }

# System tray
ksni = "0.2"

# State management
parking_lot = "0.12"
```

---

## Task 2: Implement History Storage

Replace `desktop/src/storage/history.rs`:

```rust
//! History storage using SQLite.

use anyhow::Result;
use chrono::{DateTime, Local, TimeZone};
use rusqlite::{params, Connection};
use std::path::Path;
use std::sync::Mutex;
use tracing::info;

/// A single history entry.
#[derive(Debug, Clone)]
pub struct HistoryEntry {
    pub id: i64,
    pub timestamp: DateTime<Local>,
    pub entry_type: EntryType,
    pub content: String,
    pub active_window: Option<String>,
}

/// Type of history entry.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EntryType {
    Text,
    Command,
}

impl EntryType {
    fn as_str(&self) -> &'static str {
        match self {
            EntryType::Text => "TEXT",
            EntryType::Command => "COMMAND",
        }
    }

    fn from_str(s: &str) -> Self {
        match s {
            "COMMAND" => EntryType::Command,
            _ => EntryType::Text,
        }
    }
}

/// History database manager.
pub struct History {
    conn: Mutex<Connection>,
    max_entries: u32,
}

impl History {
    /// Create or open history database.
    pub fn new(data_dir: &Path) -> Result<Self> {
        std::fs::create_dir_all(data_dir)?;
        let db_path = data_dir.join("history.db");
        info!("Opening history database: {:?}", db_path);

        let conn = Connection::open(&db_path)?;

        // Create tables
        conn.execute(
            "CREATE TABLE IF NOT EXISTS history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                entry_type TEXT NOT NULL,
                content TEXT NOT NULL,
                active_window TEXT
            )",
            [],
        )?;

        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_timestamp ON history(timestamp DESC)",
            [],
        )?;

        Ok(Self {
            conn: Mutex::new(conn),
            max_entries: 10000,
        })
    }

    /// Set maximum number of entries to keep.
    pub fn set_max_entries(&mut self, max: u32) {
        self.max_entries = max;
    }

    /// Add a text entry to history.
    pub fn add_text(&self, text: &str) -> Result<()> {
        self.add_entry(EntryType::Text, text, None)
    }

    /// Add a command entry to history.
    pub fn add_command(&self, command: &str) -> Result<()> {
        self.add_entry(EntryType::Command, command, None)
    }

    /// Add an entry with optional active window.
    pub fn add_entry(
        &self,
        entry_type: EntryType,
        content: &str,
        active_window: Option<&str>,
    ) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        let timestamp = Local::now().timestamp();

        conn.execute(
            "INSERT INTO history (timestamp, entry_type, content, active_window) VALUES (?1, ?2, ?3, ?4)",
            params![timestamp, entry_type.as_str(), content, active_window],
        )?;

        // Cleanup old entries
        self.cleanup_old_entries(&conn)?;

        Ok(())
    }

    /// Get recent history entries.
    pub fn get_recent(&self, limit: u32) -> Result<Vec<HistoryEntry>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare(
            "SELECT id, timestamp, entry_type, content, active_window 
             FROM history 
             ORDER BY timestamp DESC 
             LIMIT ?1",
        )?;

        let entries = stmt
            .query_map([limit], |row| {
                let timestamp_secs: i64 = row.get(1)?;
                let entry_type_str: String = row.get(2)?;

                Ok(HistoryEntry {
                    id: row.get(0)?,
                    timestamp: Local.timestamp_opt(timestamp_secs, 0).unwrap(),
                    entry_type: EntryType::from_str(&entry_type_str),
                    content: row.get(3)?,
                    active_window: row.get(4)?,
                })
            })?
            .collect::<Result<Vec<_>, _>>()?;

        Ok(entries)
    }

    /// Search history by content.
    pub fn search(&self, query: &str, limit: u32) -> Result<Vec<HistoryEntry>> {
        let conn = self.conn.lock().unwrap();
        let pattern = format!("%{}%", query);
        let mut stmt = conn.prepare(
            "SELECT id, timestamp, entry_type, content, active_window 
             FROM history 
             WHERE content LIKE ?1
             ORDER BY timestamp DESC 
             LIMIT ?2",
        )?;

        let entries = stmt
            .query_map(params![pattern, limit], |row| {
                let timestamp_secs: i64 = row.get(1)?;
                let entry_type_str: String = row.get(2)?;

                Ok(HistoryEntry {
                    id: row.get(0)?,
                    timestamp: Local.timestamp_opt(timestamp_secs, 0).unwrap(),
                    entry_type: EntryType::from_str(&entry_type_str),
                    content: row.get(3)?,
                    active_window: row.get(4)?,
                })
            })?
            .collect::<Result<Vec<_>, _>>()?;

        Ok(entries)
    }

    /// Export history to text file.
    pub fn export(&self, path: &Path) -> Result<()> {
        let entries = self.get_recent(self.max_entries)?;
        let mut content = String::new();

        for entry in entries.iter().rev() {
            let type_str = match entry.entry_type {
                EntryType::Text => "TEXT",
                EntryType::Command => "CMD ",
            };
            content.push_str(&format!(
                "[{}] {}: {}\n",
                entry.timestamp.format("%Y-%m-%d %H:%M:%S"),
                type_str,
                entry.content
            ));
        }

        std::fs::write(path, content)?;
        info!("Exported {} entries to {:?}", entries.len(), path);
        Ok(())
    }

    /// Clear all history.
    pub fn clear(&self) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute("DELETE FROM history", [])?;
        info!("History cleared");
        Ok(())
    }

    /// Get total entry count.
    pub fn count(&self) -> Result<u32> {
        let conn = self.conn.lock().unwrap();
        let count: u32 = conn.query_row("SELECT COUNT(*) FROM history", [], |row| row.get(0))?;
        Ok(count)
    }

    /// Remove old entries beyond max_entries.
    fn cleanup_old_entries(&self, conn: &Connection) -> Result<()> {
        conn.execute(
            "DELETE FROM history WHERE id NOT IN (
                SELECT id FROM history ORDER BY timestamp DESC LIMIT ?1
            )",
            [self.max_entries],
        )?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    #[test]
    fn test_history_basic() {
        let dir = tempdir().unwrap();
        let history = History::new(dir.path()).unwrap();

        history.add_text("Hello world").unwrap();
        history.add_command("ENTER").unwrap();

        let entries = history.get_recent(10).unwrap();
        assert_eq!(entries.len(), 2);
        assert_eq!(entries[0].content, "ENTER");
        assert_eq!(entries[0].entry_type, EntryType::Command);
        assert_eq!(entries[1].content, "Hello world");
        assert_eq!(entries[1].entry_type, EntryType::Text);
    }

    #[test]
    fn test_history_search() {
        let dir = tempdir().unwrap();
        let history = History::new(dir.path()).unwrap();

        history.add_text("Hello world").unwrap();
        history.add_text("Hello there").unwrap();
        history.add_text("Goodbye").unwrap();

        let results = history.search("Hello", 10).unwrap();
        assert_eq!(results.len(), 2);
    }
}
```

---

## Task 3: Create Application State

Create `desktop/src/state.rs`:

```rust
//! Application state management.

use parking_lot::RwLock;
use std::sync::Arc;

/// Connection status.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConnectionStatus {
    Disconnected,
    Connecting,
    Connected,
    Error,
}

impl ConnectionStatus {
    pub fn as_str(&self) -> &'static str {
        match self {
            ConnectionStatus::Disconnected => "Disconnected",
            ConnectionStatus::Connecting => "Connecting...",
            ConnectionStatus::Connected => "Connected",
            ConnectionStatus::Error => "Error",
        }
    }

    pub fn icon_name(&self) -> &'static str {
        match self {
            ConnectionStatus::Disconnected => "network-offline",
            ConnectionStatus::Connecting => "network-idle",
            ConnectionStatus::Connected => "network-transmit-receive",
            ConnectionStatus::Error => "network-error",
        }
    }
}

/// Shared application state.
#[derive(Debug)]
pub struct AppState {
    /// Current connection status.
    pub connection_status: RwLock<ConnectionStatus>,
    
    /// Whether input injection is enabled.
    pub input_enabled: RwLock<bool>,
    
    /// Connected device name.
    pub connected_device: RwLock<Option<String>>,
    
    /// Last received text (for tooltip).
    pub last_text: RwLock<Option<String>>,
}

impl Default for AppState {
    fn default() -> Self {
        Self {
            connection_status: RwLock::new(ConnectionStatus::Disconnected),
            input_enabled: RwLock::new(true),
            connected_device: RwLock::new(None),
            last_text: RwLock::new(None),
        }
    }
}

impl AppState {
    pub fn new() -> Arc<Self> {
        Arc::new(Self::default())
    }

    pub fn set_connected(&self, device_name: String) {
        *self.connection_status.write() = ConnectionStatus::Connected;
        *self.connected_device.write() = Some(device_name);
    }

    pub fn set_disconnected(&self) {
        *self.connection_status.write() = ConnectionStatus::Disconnected;
        *self.connected_device.write() = None;
    }

    pub fn set_error(&self) {
        *self.connection_status.write() = ConnectionStatus::Error;
    }

    pub fn set_input_enabled(&self, enabled: bool) {
        *self.input_enabled.write() = enabled;
    }

    pub fn is_input_enabled(&self) -> bool {
        *self.input_enabled.read()
    }

    pub fn get_status(&self) -> ConnectionStatus {
        *self.connection_status.read()
    }

    pub fn get_device_name(&self) -> Option<String> {
        self.connected_device.read().clone()
    }

    pub fn set_last_text(&self, text: String) {
        *self.last_text.write() = Some(text);
    }

    pub fn get_last_text(&self) -> Option<String> {
        self.last_text.read().clone()
    }
}
```

---

## Task 4: Implement System Tray

Replace `desktop/src/ui/tray.rs`:

```rust
//! System tray implementation using ksni.

use anyhow::Result;
use ksni::{self, menu::StandardItem, Icon, MenuItem, Tray, TrayService};
use std::sync::Arc;
use tokio::sync::mpsc;
use tracing::info;

use crate::state::{AppState, ConnectionStatus};

/// Actions that can be triggered from the tray menu.
#[derive(Debug, Clone)]
pub enum TrayAction {
    ToggleInput,
    ShowHistory,
    ShowSettings,
    Quit,
}

/// System tray icon and menu.
pub struct Speech2CodeTray {
    state: Arc<AppState>,
    action_tx: mpsc::UnboundedSender<TrayAction>,
}

impl Speech2CodeTray {
    pub fn new(state: Arc<AppState>, action_tx: mpsc::UnboundedSender<TrayAction>) -> Self {
        Self { state, action_tx }
    }
}

impl Tray for Speech2CodeTray {
    fn icon_name(&self) -> String {
        let status = self.state.get_status();
        status.icon_name().to_string()
    }

    fn title(&self) -> String {
        "Speech2Code".to_string()
    }

    fn tool_tip(&self) -> ksni::ToolTip {
        let status = self.state.get_status();
        let title = "Speech2Code".to_string();
        
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
                let device = self.state.get_device_name().unwrap_or_else(|| "Unknown".to_string());
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
        
        // History
        items.push(MenuItem::Standard(StandardItem {
            label: "View History...".to_string(),
            activate: Box::new(|tray: &mut Self| {
                let _ = tray.action_tx.send(TrayAction::ShowHistory);
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
        "speech2code".to_string()
    }

    fn category(&self) -> ksni::Category {
        ksni::Category::Communications
    }
}

/// Run the system tray service.
pub fn run_tray(state: Arc<AppState>) -> Result<mpsc::UnboundedReceiver<TrayAction>> {
    let (action_tx, action_rx) = mpsc::unbounded_channel();
    
    let tray = Speech2CodeTray::new(state, action_tx);
    let service = TrayService::new(tray);
    let handle = service.handle();
    
    // Spawn the tray service
    std::thread::spawn(move || {
        service.run();
    });
    
    info!("System tray started");
    
    Ok(action_rx)
}

/// Handle for updating tray state.
#[derive(Clone)]
pub struct TrayHandle {
    // ksni doesn't expose a direct update method, so we rely on
    // periodic menu regeneration. For immediate updates, we'd need
    // to use a different approach or fork ksni.
}

impl TrayHandle {
    pub fn update(&self) {
        // Tray will update on next menu access
    }
}
```

---

## Task 5: Create History Window

Create `desktop/src/ui/history_window.rs`:

```rust
//! History window using GTK4.

use gtk4::prelude::*;
use gtk4::{
    Application, ApplicationWindow, Box as GtkBox, Button, Entry, Label, ListBox, ListBoxRow,
    Orientation, ScrolledWindow, SelectionMode,
};
use std::sync::Arc;
use tracing::info;

use crate::storage::{EntryType, History, HistoryEntry};

/// Create and show the history window.
pub fn show_history_window(app: &Application, history: Arc<History>) {
    let window = ApplicationWindow::builder()
        .application(app)
        .title("Speech2Code - History")
        .default_width(600)
        .default_height(400)
        .build();

    let main_box = GtkBox::new(Orientation::Vertical, 8);
    main_box.set_margin_top(8);
    main_box.set_margin_bottom(8);
    main_box.set_margin_start(8);
    main_box.set_margin_end(8);

    // Search bar
    let search_box = GtkBox::new(Orientation::Horizontal, 8);
    let search_entry = Entry::builder()
        .placeholder_text("Search history...")
        .hexpand(true)
        .build();
    let search_button = Button::with_label("Search");
    let clear_button = Button::with_label("Clear Search");
    
    search_box.append(&search_entry);
    search_box.append(&search_button);
    search_box.append(&clear_button);
    main_box.append(&search_box);

    // List box in scrolled window
    let scrolled = ScrolledWindow::builder()
        .hexpand(true)
        .vexpand(true)
        .build();

    let list_box = ListBox::new();
    list_box.set_selection_mode(SelectionMode::None);
    scrolled.set_child(Some(&list_box));
    main_box.append(&scrolled);

    // Action buttons
    let button_box = GtkBox::new(Orientation::Horizontal, 8);
    button_box.set_halign(gtk4::Align::End);
    
    let export_button = Button::with_label("Export...");
    let clear_history_button = Button::with_label("Clear History");
    let close_button = Button::with_label("Close");
    
    button_box.append(&export_button);
    button_box.append(&clear_history_button);
    button_box.append(&close_button);
    main_box.append(&button_box);

    window.set_child(Some(&main_box));

    // Load initial history
    let history_clone = history.clone();
    let list_box_clone = list_box.clone();
    populate_history(&list_box_clone, &history_clone, None);

    // Search handler
    let history_search = history.clone();
    let list_box_search = list_box.clone();
    let search_entry_clone = search_entry.clone();
    search_button.connect_clicked(move |_| {
        let query = search_entry_clone.text().to_string();
        let query_opt = if query.is_empty() { None } else { Some(query.as_str()) };
        populate_history(&list_box_search, &history_search, query_opt);
    });

    // Clear search handler
    let history_clear = history.clone();
    let list_box_clear = list_box.clone();
    let search_entry_clear = search_entry.clone();
    clear_button.connect_clicked(move |_| {
        search_entry_clear.set_text("");
        populate_history(&list_box_clear, &history_clear, None);
    });

    // Enter key in search
    let history_enter = history.clone();
    let list_box_enter = list_box.clone();
    search_entry.connect_activate(move |entry| {
        let query = entry.text().to_string();
        let query_opt = if query.is_empty() { None } else { Some(query.as_str()) };
        populate_history(&list_box_enter, &history_enter, query_opt);
    });

    // Export handler
    let history_export = history.clone();
    let window_export = window.clone();
    export_button.connect_clicked(move |_| {
        let dialog = gtk4::FileChooserDialog::new(
            Some("Export History"),
            Some(&window_export),
            gtk4::FileChooserAction::Save,
            &[
                ("Cancel", gtk4::ResponseType::Cancel),
                ("Save", gtk4::ResponseType::Accept),
            ],
        );
        dialog.set_current_name("speech2code_history.txt");
        
        let history_dialog = history_export.clone();
        dialog.connect_response(move |dialog, response| {
            if response == gtk4::ResponseType::Accept {
                if let Some(file) = dialog.file() {
                    if let Some(path) = file.path() {
                        if let Err(e) = history_dialog.export(&path) {
                            eprintln!("Export failed: {}", e);
                        } else {
                            info!("History exported to {:?}", path);
                        }
                    }
                }
            }
            dialog.close();
        });
        
        dialog.show();
    });

    // Clear history handler
    let history_clear_all = history.clone();
    let list_box_clear_all = list_box.clone();
    let window_clear = window.clone();
    clear_history_button.connect_clicked(move |_| {
        let dialog = gtk4::MessageDialog::new(
            Some(&window_clear),
            gtk4::DialogFlags::MODAL,
            gtk4::MessageType::Warning,
            gtk4::ButtonsType::YesNo,
            "Clear all history?",
        );
        dialog.set_secondary_text(Some("This action cannot be undone."));
        
        let history_confirm = history_clear_all.clone();
        let list_box_confirm = list_box_clear_all.clone();
        dialog.connect_response(move |dialog, response| {
            if response == gtk4::ResponseType::Yes {
                if let Err(e) = history_confirm.clear() {
                    eprintln!("Failed to clear history: {}", e);
                } else {
                    populate_history(&list_box_confirm, &history_confirm, None);
                }
            }
            dialog.close();
        });
        
        dialog.show();
    });

    // Close handler
    let window_close = window.clone();
    close_button.connect_clicked(move |_| {
        window_close.close();
    });

    window.present();
}

/// Populate the list box with history entries.
fn populate_history(list_box: &ListBox, history: &History, query: Option<&str>) {
    // Remove existing rows
    while let Some(child) = list_box.first_child() {
        list_box.remove(&child);
    }

    let entries = match query {
        Some(q) => history.search(q, 500).unwrap_or_default(),
        None => history.get_recent(500).unwrap_or_default(),
    };

    if entries.is_empty() {
        let row = ListBoxRow::new();
        let label = Label::new(Some("No history entries"));
        label.set_margin_top(16);
        label.set_margin_bottom(16);
        row.set_child(Some(&label));
        list_box.append(&row);
        return;
    }

    for entry in entries {
        let row = create_history_row(&entry);
        list_box.append(&row);
    }
}

/// Create a list box row for a history entry.
fn create_history_row(entry: &HistoryEntry) -> ListBoxRow {
    let row = ListBoxRow::new();
    
    let hbox = GtkBox::new(Orientation::Horizontal, 8);
    hbox.set_margin_top(4);
    hbox.set_margin_bottom(4);
    hbox.set_margin_start(8);
    hbox.set_margin_end(8);

    // Timestamp
    let time_label = Label::new(Some(&entry.timestamp.format("%H:%M:%S").to_string()));
    time_label.add_css_class("dim-label");
    time_label.set_width_chars(10);
    hbox.append(&time_label);

    // Type indicator
    let type_label = match entry.entry_type {
        EntryType::Text => Label::new(Some("TXT")),
        EntryType::Command => Label::new(Some("CMD")),
    };
    type_label.add_css_class("caption");
    type_label.set_width_chars(4);
    hbox.append(&type_label);

    // Content
    let content_label = Label::new(Some(&entry.content));
    content_label.set_hexpand(true);
    content_label.set_halign(gtk4::Align::Start);
    content_label.set_ellipsize(gtk4::pango::EllipsizeMode::End);
    content_label.set_selectable(true);
    hbox.append(&content_label);

    row.set_child(Some(&hbox));
    row
}
```

---

## Task 6: Create PIN Dialog

Create `desktop/src/ui/pin_dialog.rs`:

```rust
//! PIN entry dialog for pairing.

use gtk4::prelude::*;
use gtk4::{
    Application, ApplicationWindow, Box as GtkBox, Button, Entry, Label, Orientation,
};
use std::sync::Arc;
use tokio::sync::oneshot;
use tracing::info;

/// Result of PIN dialog.
pub enum PinDialogResult {
    /// User entered a PIN.
    Pin(String),
    /// User cancelled.
    Cancelled,
}

/// Show PIN entry dialog.
///
/// Returns the entered PIN or None if cancelled.
pub fn show_pin_dialog(
    app: &Application,
    device_id: &str,
) -> oneshot::Receiver<PinDialogResult> {
    let (tx, rx) = oneshot::channel();
    let tx = Arc::new(std::sync::Mutex::new(Some(tx)));

    let window = ApplicationWindow::builder()
        .application(app)
        .title("Speech2Code - Pairing")
        .default_width(300)
        .default_height(200)
        .modal(true)
        .resizable(false)
        .build();

    let main_box = GtkBox::new(Orientation::Vertical, 16);
    main_box.set_margin_top(24);
    main_box.set_margin_bottom(24);
    main_box.set_margin_start(24);
    main_box.set_margin_end(24);

    // Title
    let title = Label::new(Some("Pairing Request"));
    title.add_css_class("title-2");
    main_box.append(&title);

    // Device info
    let device_label = Label::new(Some(&format!("Device: {}", device_id)));
    device_label.add_css_class("dim-label");
    main_box.append(&device_label);

    // Instructions
    let instructions = Label::new(Some("Enter the 6-digit PIN shown on your phone:"));
    instructions.set_wrap(true);
    main_box.append(&instructions);

    // PIN entry
    let pin_entry = Entry::builder()
        .max_length(6)
        .placeholder_text("000000")
        .input_purpose(gtk4::InputPurpose::Pin)
        .build();
    pin_entry.set_halign(gtk4::Align::Center);
    main_box.append(&pin_entry);

    // Buttons
    let button_box = GtkBox::new(Orientation::Horizontal, 8);
    button_box.set_halign(gtk4::Align::Center);

    let cancel_button = Button::with_label("Cancel");
    let pair_button = Button::with_label("Pair");
    pair_button.add_css_class("suggested-action");

    button_box.append(&cancel_button);
    button_box.append(&pair_button);
    main_box.append(&button_box);

    window.set_child(Some(&main_box));

    // Cancel handler
    let window_cancel = window.clone();
    let tx_cancel = tx.clone();
    cancel_button.connect_clicked(move |_| {
        if let Some(tx) = tx_cancel.lock().unwrap().take() {
            let _ = tx.send(PinDialogResult::Cancelled);
        }
        window_cancel.close();
    });

    // Pair handler
    let window_pair = window.clone();
    let pin_entry_pair = pin_entry.clone();
    let tx_pair = tx.clone();
    pair_button.connect_clicked(move |_| {
        let pin = pin_entry_pair.text().to_string();
        if pin.len() == 6 && pin.chars().all(|c| c.is_ascii_digit()) {
            info!("PIN entered: {}", pin);
            if let Some(tx) = tx_pair.lock().unwrap().take() {
                let _ = tx.send(PinDialogResult::Pin(pin));
            }
            window_pair.close();
        } else {
            // Show error - PIN must be 6 digits
            pin_entry_pair.add_css_class("error");
        }
    });

    // Enter key in PIN entry
    let window_enter = window.clone();
    let tx_enter = tx.clone();
    pin_entry.connect_activate(move |entry| {
        let pin = entry.text().to_string();
        if pin.len() == 6 && pin.chars().all(|c| c.is_ascii_digit()) {
            if let Some(tx) = tx_enter.lock().unwrap().take() {
                let _ = tx.send(PinDialogResult::Pin(pin));
            }
            window_enter.close();
        } else {
            entry.add_css_class("error");
        }
    });

    // Window close handler
    let tx_close = tx.clone();
    window.connect_close_request(move |_| {
        if let Some(tx) = tx_close.lock().unwrap().take() {
            let _ = tx.send(PinDialogResult::Cancelled);
        }
        glib::Propagation::Proceed
    });

    window.present();
    pin_entry.grab_focus();

    rx
}
```

---

## Task 7: Update UI Module

Replace `desktop/src/ui/mod.rs`:

```rust
//! UI module for system tray and windows.

mod history_window;
mod pin_dialog;
mod tray;

pub use history_window::show_history_window;
pub use pin_dialog::{show_pin_dialog, PinDialogResult};
pub use tray::{run_tray, TrayAction, TrayHandle};

use anyhow::Result;
use gtk4::prelude::*;
use gtk4::Application;
use std::sync::Arc;
use tokio::sync::mpsc;
use tracing::{error, info};

use crate::config::Config;
use crate::input::InputInjector;
use crate::state::AppState;
use crate::storage::History;

/// Run the UI event loop.
pub async fn run_ui(
    config: Config,
    history: Arc<History>,
    injector: Box<dyn InputInjector>,
    state: Arc<AppState>,
) -> Result<()> {
    // Initialize GTK
    gtk4::init()?;

    // Create GTK application
    let app = Application::builder()
        .application_id("com.speech2code.desktop")
        .build();

    let history_clone = history.clone();
    let state_clone = state.clone();

    // Start system tray
    let mut action_rx = run_tray(state.clone())?;

    // Handle tray actions
    let app_clone = app.clone();
    tokio::spawn(async move {
        while let Some(action) = action_rx.recv().await {
            match action {
                TrayAction::ToggleInput => {
                    let enabled = !state_clone.is_input_enabled();
                    state_clone.set_input_enabled(enabled);
                    info!("Input {}", if enabled { "enabled" } else { "disabled" });
                }
                TrayAction::ShowHistory => {
                    let history = history_clone.clone();
                    let app = app_clone.clone();
                    glib::idle_add_local_once(move || {
                        show_history_window(&app, history);
                    });
                }
                TrayAction::ShowSettings => {
                    info!("Settings requested (not implemented)");
                }
                TrayAction::Quit => {
                    info!("Quit requested");
                    std::process::exit(0);
                }
            }
        }
    });

    // Run GTK main loop (in a separate thread)
    info!("UI ready");

    // Keep running
    loop {
        tokio::time::sleep(tokio::time::Duration::from_secs(1)).await;
    }
}
```

---

## Task 8: Update Main Application

Replace `desktop/src/main.rs`:

```rust
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
```

---

## Task 9: Update Library Exports

Update `desktop/src/lib.rs`:

```rust
//! Speech2Code Desktop Library

pub mod bluetooth;
pub mod commands;
pub mod config;
pub mod crypto;
pub mod events;
pub mod input;
pub mod state;
pub mod storage;
pub mod ui;
```

---

## Testing

### Build and Run

```bash
cd /home/dan/workspace/priv/speech2code/desktop
cargo build
cargo run
```

### Verify System Tray

1. Application should appear in system tray
2. Click tray icon to see menu
3. Test "Enable/Disable Input" toggle
4. Test "Quit" action

### Test History

```bash
# Add some test entries via Bluetooth terminal
# Then check history via tray menu
```

---

## Verification Checklist

- [ ] System tray icon appears
- [ ] Tray menu opens on click
- [ ] Status indicator updates (connected/disconnected)
- [ ] Input toggle works
- [ ] History window opens
- [ ] History entries display correctly
- [ ] History search works
- [ ] History export works
- [ ] Clear history works
- [ ] Quit action closes application

## Output Artifacts

After completing this phase:

1. **System tray** with status icon and menu
2. **History storage** with SQLite database
3. **History window** with search and export
4. **Application state** shared between components
5. **PIN dialog** (ready for Phase 09)

## Next Phase

Proceed to **Phase 06: Android Speech Recognition** to implement continuous speech recognition on the Android app.
