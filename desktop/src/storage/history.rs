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

//! History storage using SQLite.

use anyhow::Result;
use chrono::{DateTime, Local, TimeZone};
use rusqlite::{params, Connection};
use std::path::Path;
use std::sync::{Arc, Mutex};
use tracing::info;

/// A single history entry.
#[allow(dead_code)]
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

    #[allow(dead_code)]
    fn from_str(s: &str) -> Self {
        match s {
            "COMMAND" => EntryType::Command,
            _ => EntryType::Text,
        }
    }
}

/// History database manager.
#[derive(Clone)]
pub struct History {
    conn: Arc<Mutex<Connection>>,
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
            conn: Arc::new(Mutex::new(conn)),
            max_entries: 10000,
        })
    }

    /// Set maximum number of entries to keep.
    #[allow(dead_code)]
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
    #[allow(dead_code)]
    pub fn get_recent(&self, limit: u32) -> Result<Vec<HistoryEntry>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare(
            "SELECT id, timestamp, entry_type, content, active_window 
             FROM history 
             ORDER BY timestamp DESC, id DESC 
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
    #[allow(dead_code)]
    pub fn search(&self, query: &str, limit: u32) -> Result<Vec<HistoryEntry>> {
        let conn = self.conn.lock().unwrap();
        let pattern = format!("%{}%", query);
        let mut stmt = conn.prepare(
            "SELECT id, timestamp, entry_type, content, active_window 
             FROM history 
             WHERE content LIKE ?1
             ORDER BY timestamp DESC, id DESC 
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
    #[allow(dead_code)]
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
    #[allow(dead_code)]
    pub fn clear(&self) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute("DELETE FROM history", [])?;
        info!("History cleared");
        Ok(())
    }

    /// Get total entry count.
    #[allow(dead_code)]
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
