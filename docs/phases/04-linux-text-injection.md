# Phase 04: Linux Text Injection

## Overview

This phase implements text injection (simulating keyboard input) on Linux, supporting both X11 and Wayland display servers. The injector will type text at the current cursor position and execute keyboard shortcuts for voice commands.

## Prerequisites

- Phase 01-03 completed
- Display server running (X11 or Wayland)
- Required system libraries installed

## System Setup

### X11 Dependencies

```bash
# Ubuntu/Debian
sudo apt install libxtst-dev libx11-dev

# Fedora
sudo dnf install libXtst-devel libX11-devel

# Arch
sudo pacman -S libxtst libx11
```

### Wayland Dependencies (ydotool)

```bash
# Ubuntu/Debian (22.04+)
sudo apt install ydotool

# Fedora
sudo dnf install ydotool

# Arch
sudo pacman -S ydotool

# Start ydotool daemon (required for Wayland)
sudo systemctl enable --now ydotool
# Or run as user (requires uinput permissions)
```

### Wayland: uinput Permissions

For ydotool to work without root:

```bash
# Add user to input group
sudo usermod -a -G input $USER

# Create udev rule
echo 'KERNEL=="uinput", GROUP="input", MODE="0660"' | sudo tee /etc/udev/rules.d/99-uinput.rules
sudo udevadm control --reload-rules
sudo udevadm trigger

# Log out and back in
```

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        InputInjector                             │
│                          (trait)                                 │
│  - type_text(&str)                                               │
│  - press_key(Key)                                                │
│  - key_combo(&[Modifier], Key)                                   │
└─────────────────────────────────────────────────────────────────┘
                     │                      │
                     ▼                      ▼
        ┌───────────────────┐    ┌───────────────────┐
        │   X11Injector     │    │  WaylandInjector  │
        │                   │    │                   │
        │  - XTest/enigo   │    │  - ydotool       │
        │  - Direct keysym │    │  - wtype fallback│
        └───────────────────┘    └───────────────────┘
```

---

## Task 1: Update Cargo Dependencies

Add to `desktop/Cargo.toml`:

```toml
[dependencies]
# ... existing dependencies ...

# Input simulation
enigo = "0.1"

# For ydotool communication
tokio-process = "0.2"

# X11 detection
x11rb = { version = "0.13", features = ["allow-unsafe-code"], optional = true }

[features]
default = ["x11"]
x11 = ["x11rb"]
wayland = []
```

---

## Task 2: Create Key Definitions

Create `desktop/src/input/keys.rs`:

```rust
//! Key and modifier definitions.

/// Keyboard modifiers.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Modifier {
    Ctrl,
    Alt,
    Shift,
    Super,
}

impl Modifier {
    /// Get the enigo key for this modifier.
    #[cfg(feature = "x11")]
    pub fn to_enigo(&self) -> enigo::Key {
        match self {
            Modifier::Ctrl => enigo::Key::Control,
            Modifier::Alt => enigo::Key::Alt,
            Modifier::Shift => enigo::Key::Shift,
            Modifier::Super => enigo::Key::Meta,
        }
    }

    /// Get the ydotool key name for this modifier.
    pub fn to_ydotool(&self) -> &'static str {
        match self {
            Modifier::Ctrl => "LEFTCTRL",
            Modifier::Alt => "LEFTALT",
            Modifier::Shift => "LEFTSHIFT",
            Modifier::Super => "LEFTMETA",
        }
    }
}

/// Special keys.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Key {
    // Letters
    A, B, C, D, E, F, G, H, I, J, K, L, M,
    N, O, P, Q, R, S, T, U, V, W, X, Y, Z,
    
    // Special keys
    Enter,
    Backspace,
    Tab,
    Escape,
    Space,
    Delete,
    Home,
    End,
    PageUp,
    PageDown,
    Left,
    Right,
    Up,
    Down,
    
    // Function keys
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
}

impl Key {
    /// Get the enigo key.
    #[cfg(feature = "x11")]
    pub fn to_enigo(&self) -> enigo::Key {
        use enigo::Key as EKey;
        match self {
            Key::A => EKey::Layout('a'),
            Key::B => EKey::Layout('b'),
            Key::C => EKey::Layout('c'),
            Key::D => EKey::Layout('d'),
            Key::E => EKey::Layout('e'),
            Key::F => EKey::Layout('f'),
            Key::G => EKey::Layout('g'),
            Key::H => EKey::Layout('h'),
            Key::I => EKey::Layout('i'),
            Key::J => EKey::Layout('j'),
            Key::K => EKey::Layout('k'),
            Key::L => EKey::Layout('l'),
            Key::M => EKey::Layout('m'),
            Key::N => EKey::Layout('n'),
            Key::O => EKey::Layout('o'),
            Key::P => EKey::Layout('p'),
            Key::Q => EKey::Layout('q'),
            Key::R => EKey::Layout('r'),
            Key::S => EKey::Layout('s'),
            Key::T => EKey::Layout('t'),
            Key::U => EKey::Layout('u'),
            Key::V => EKey::Layout('v'),
            Key::W => EKey::Layout('w'),
            Key::X => EKey::Layout('x'),
            Key::Y => EKey::Layout('y'),
            Key::Z => EKey::Layout('z'),
            Key::Enter => EKey::Return,
            Key::Backspace => EKey::Backspace,
            Key::Tab => EKey::Tab,
            Key::Escape => EKey::Escape,
            Key::Space => EKey::Space,
            Key::Delete => EKey::Delete,
            Key::Home => EKey::Home,
            Key::End => EKey::End,
            Key::PageUp => EKey::PageUp,
            Key::PageDown => EKey::PageDown,
            Key::Left => EKey::LeftArrow,
            Key::Right => EKey::RightArrow,
            Key::Up => EKey::UpArrow,
            Key::Down => EKey::DownArrow,
            Key::F1 => EKey::F1,
            Key::F2 => EKey::F2,
            Key::F3 => EKey::F3,
            Key::F4 => EKey::F4,
            Key::F5 => EKey::F5,
            Key::F6 => EKey::F6,
            Key::F7 => EKey::F7,
            Key::F8 => EKey::F8,
            Key::F9 => EKey::F9,
            Key::F10 => EKey::F10,
            Key::F11 => EKey::F11,
            Key::F12 => EKey::F12,
        }
    }

    /// Get the ydotool key name.
    pub fn to_ydotool(&self) -> &'static str {
        match self {
            Key::A => "A",
            Key::B => "B",
            Key::C => "C",
            Key::D => "D",
            Key::E => "E",
            Key::F => "F",
            Key::G => "G",
            Key::H => "H",
            Key::I => "I",
            Key::J => "J",
            Key::K => "K",
            Key::L => "L",
            Key::M => "M",
            Key::N => "N",
            Key::O => "O",
            Key::P => "P",
            Key::Q => "Q",
            Key::R => "R",
            Key::S => "S",
            Key::T => "T",
            Key::U => "U",
            Key::V => "V",
            Key::W => "W",
            Key::X => "X",
            Key::Y => "Y",
            Key::Z => "Z",
            Key::Enter => "ENTER",
            Key::Backspace => "BACKSPACE",
            Key::Tab => "TAB",
            Key::Escape => "ESC",
            Key::Space => "SPACE",
            Key::Delete => "DELETE",
            Key::Home => "HOME",
            Key::End => "END",
            Key::PageUp => "PAGEUP",
            Key::PageDown => "PAGEDOWN",
            Key::Left => "LEFT",
            Key::Right => "RIGHT",
            Key::Up => "UP",
            Key::Down => "DOWN",
            Key::F1 => "F1",
            Key::F2 => "F2",
            Key::F3 => "F3",
            Key::F4 => "F4",
            Key::F5 => "F5",
            Key::F6 => "F6",
            Key::F7 => "F7",
            Key::F8 => "F8",
            Key::F9 => "F9",
            Key::F10 => "F10",
            Key::F11 => "F11",
            Key::F12 => "F12",
        }
    }
}
```

---

## Task 3: Create X11 Injector

Create `desktop/src/input/x11.rs`:

```rust
//! X11 text injection using enigo.

use anyhow::Result;
use enigo::{Enigo, KeyboardControllable};
use std::sync::Mutex;
use std::thread;
use std::time::Duration;
use tracing::{debug, trace};

use super::keys::{Key, Modifier};
use super::InputInjector;

/// Delay between keystrokes in milliseconds.
const KEYSTROKE_DELAY_MS: u64 = 10;

/// X11 input injector using enigo.
pub struct X11Injector {
    enigo: Mutex<Enigo>,
    delay: Duration,
}

impl X11Injector {
    /// Create a new X11 injector.
    pub fn new() -> Result<Self> {
        let enigo = Enigo::new();
        Ok(Self {
            enigo: Mutex::new(enigo),
            delay: Duration::from_millis(KEYSTROKE_DELAY_MS),
        })
    }

    /// Set the keystroke delay.
    pub fn set_delay(&mut self, delay_ms: u64) {
        self.delay = Duration::from_millis(delay_ms);
    }

    /// Small delay between operations.
    fn pause(&self) {
        if !self.delay.is_zero() {
            thread::sleep(self.delay);
        }
    }
}

impl InputInjector for X11Injector {
    fn backend_name(&self) -> &'static str {
        "X11 (enigo)"
    }

    fn type_text(&self, text: &str) -> Result<()> {
        debug!("Typing text: {} chars", text.len());
        
        let mut enigo = self.enigo.lock().unwrap();
        
        for c in text.chars() {
            trace!("Typing char: {:?}", c);
            enigo.key_sequence(&c.to_string());
            self.pause();
        }
        
        Ok(())
    }

    fn press_key(&self, key: Key) -> Result<()> {
        debug!("Pressing key: {:?}", key);
        
        let mut enigo = self.enigo.lock().unwrap();
        let enigo_key = key.to_enigo();
        
        enigo.key_click(enigo_key);
        self.pause();
        
        Ok(())
    }

    fn key_combo(&self, modifiers: &[Modifier], key: Key) -> Result<()> {
        debug!("Key combo: {:?} + {:?}", modifiers, key);
        
        let mut enigo = self.enigo.lock().unwrap();
        
        // Press modifiers
        for modifier in modifiers {
            let enigo_key = modifier.to_enigo();
            enigo.key_down(enigo_key);
            self.pause();
        }
        
        // Press and release the main key
        let enigo_key = key.to_enigo();
        enigo.key_click(enigo_key);
        self.pause();
        
        // Release modifiers in reverse order
        for modifier in modifiers.iter().rev() {
            let enigo_key = modifier.to_enigo();
            enigo.key_up(enigo_key);
            self.pause();
        }
        
        Ok(())
    }
}
```

---

## Task 4: Create Wayland Injector

Create `desktop/src/input/wayland.rs`:

```rust
//! Wayland text injection using ydotool.

use anyhow::{anyhow, Result};
use std::process::Command;
use tracing::{debug, warn};

use super::keys::{Key, Modifier};
use super::InputInjector;

/// Wayland input injector using ydotool.
pub struct WaylandInjector {
    /// Whether ydotool daemon is available.
    ydotool_available: bool,
}

impl WaylandInjector {
    /// Create a new Wayland injector.
    pub fn new() -> Result<Self> {
        // Check if ydotool is available
        let ydotool_available = Command::new("which")
            .arg("ydotool")
            .output()
            .map(|o| o.status.success())
            .unwrap_or(false);

        if !ydotool_available {
            warn!("ydotool not found in PATH");
        }

        // Check if ydotoold is running
        let daemon_running = Command::new("pgrep")
            .arg("ydotoold")
            .output()
            .map(|o| o.status.success())
            .unwrap_or(false);

        if !daemon_running {
            warn!("ydotoold daemon not running. Start with: sudo systemctl start ydotool");
        }

        Ok(Self { ydotool_available })
    }

    /// Run ydotool command.
    fn run_ydotool(&self, args: &[&str]) -> Result<()> {
        if !self.ydotool_available {
            return Err(anyhow!("ydotool not available"));
        }

        debug!("Running: ydotool {:?}", args);

        let output = Command::new("ydotool")
            .args(args)
            .output()
            .map_err(|e| anyhow!("Failed to run ydotool: {}", e))?;

        if !output.status.success() {
            let stderr = String::from_utf8_lossy(&output.stderr);
            return Err(anyhow!("ydotool failed: {}", stderr));
        }

        Ok(())
    }
}

impl InputInjector for WaylandInjector {
    fn backend_name(&self) -> &'static str {
        "Wayland (ydotool)"
    }

    fn type_text(&self, text: &str) -> Result<()> {
        debug!("Typing text: {} chars", text.len());
        
        // ydotool type command
        self.run_ydotool(&["type", "--clearmodifiers", text])
    }

    fn press_key(&self, key: Key) -> Result<()> {
        debug!("Pressing key: {:?}", key);
        
        let key_name = key.to_ydotool();
        self.run_ydotool(&["key", key_name])
    }

    fn key_combo(&self, modifiers: &[Modifier], key: Key) -> Result<()> {
        debug!("Key combo: {:?} + {:?}", modifiers, key);
        
        // Build key combo string: "CTRL+A"
        let mut combo = String::new();
        for modifier in modifiers {
            combo.push_str(modifier.to_ydotool());
            combo.push('+');
        }
        combo.push_str(key.to_ydotool());
        
        self.run_ydotool(&["key", &combo])
    }
}
```

---

## Task 5: Update Injector Module

Replace `desktop/src/input/injector.rs`:

```rust
//! Text injection abstraction and factory.

use anyhow::{anyhow, Result};
use std::env;
use tracing::{info, warn};

use super::keys::{Key, Modifier};

#[cfg(feature = "x11")]
use super::x11::X11Injector;

use super::wayland::WaylandInjector;

/// Trait for input injection backends.
pub trait InputInjector: Send + Sync {
    /// Get the backend name (e.g., "X11", "Wayland").
    fn backend_name(&self) -> &'static str;

    /// Type text at current cursor position.
    fn type_text(&self, text: &str) -> Result<()>;

    /// Press a single key.
    fn press_key(&self, key: Key) -> Result<()>;

    /// Press a key combination (modifiers + key).
    fn key_combo(&self, modifiers: &[Modifier], key: Key) -> Result<()>;
}

/// Detected display server type.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DisplayServer {
    X11,
    Wayland,
    Unknown,
}

impl DisplayServer {
    /// Detect the current display server.
    pub fn detect() -> Self {
        // Check XDG_SESSION_TYPE first
        if let Ok(session_type) = env::var("XDG_SESSION_TYPE") {
            match session_type.to_lowercase().as_str() {
                "x11" => return DisplayServer::X11,
                "wayland" => return DisplayServer::Wayland,
                _ => {}
            }
        }

        // Check WAYLAND_DISPLAY
        if env::var("WAYLAND_DISPLAY").is_ok() {
            return DisplayServer::Wayland;
        }

        // Check DISPLAY (X11)
        if env::var("DISPLAY").is_ok() {
            return DisplayServer::X11;
        }

        DisplayServer::Unknown
    }
}

/// Create the appropriate input injector for the current display server.
pub fn create_injector() -> Result<Box<dyn InputInjector>> {
    create_injector_with_preference("auto")
}

/// Create input injector with a preference.
///
/// - "auto": Auto-detect display server
/// - "x11": Force X11 backend
/// - "wayland": Force Wayland backend
pub fn create_injector_with_preference(preference: &str) -> Result<Box<dyn InputInjector>> {
    let display_server = match preference.to_lowercase().as_str() {
        "x11" => DisplayServer::X11,
        "wayland" => DisplayServer::Wayland,
        "auto" | _ => DisplayServer::detect(),
    };

    info!("Display server: {:?}", display_server);

    match display_server {
        DisplayServer::X11 => {
            #[cfg(feature = "x11")]
            {
                info!("Using X11 input injector");
                Ok(Box::new(X11Injector::new()?))
            }
            #[cfg(not(feature = "x11"))]
            {
                Err(anyhow!("X11 support not compiled in"))
            }
        }
        DisplayServer::Wayland => {
            info!("Using Wayland input injector");
            Ok(Box::new(WaylandInjector::new()?))
        }
        DisplayServer::Unknown => {
            warn!("Unknown display server, trying X11 first...");
            #[cfg(feature = "x11")]
            {
                match X11Injector::new() {
                    Ok(injector) => {
                        info!("Using X11 input injector (fallback)");
                        Ok(Box::new(injector))
                    }
                    Err(_) => {
                        info!("Trying Wayland input injector...");
                        Ok(Box::new(WaylandInjector::new()?))
                    }
                }
            }
            #[cfg(not(feature = "x11"))]
            {
                info!("Using Wayland input injector (no X11 support)");
                Ok(Box::new(WaylandInjector::new()?))
            }
        }
    }
}

/// Stub injector for testing without display server.
pub struct StubInjector;

impl InputInjector for StubInjector {
    fn backend_name(&self) -> &'static str {
        "Stub (no-op)"
    }

    fn type_text(&self, text: &str) -> Result<()> {
        info!("[STUB] Would type: {}", text);
        Ok(())
    }

    fn press_key(&self, key: Key) -> Result<()> {
        info!("[STUB] Would press: {:?}", key);
        Ok(())
    }

    fn key_combo(&self, modifiers: &[Modifier], key: Key) -> Result<()> {
        info!("[STUB] Would press: {:?} + {:?}", modifiers, key);
        Ok(())
    }
}
```

---

## Task 6: Update Input Module Exports

Replace `desktop/src/input/mod.rs`:

```rust
//! Input injection module.
//!
//! Handles simulating keyboard input on X11 and Wayland.

mod injector;
mod keys;
mod wayland;

#[cfg(feature = "x11")]
mod x11;

pub use injector::{
    create_injector, create_injector_with_preference, DisplayServer, InputInjector, StubInjector,
};
pub use keys::{Key, Modifier};
```

---

## Task 7: Update Commands Module

Replace `desktop/src/commands/mod.rs`:

```rust
//! Voice command execution module.
//!
//! Maps received commands to keyboard actions.

use anyhow::Result;
use tracing::debug;

use crate::input::{InputInjector, Key, Modifier};

/// Voice command types.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VoiceCommand {
    /// Press Enter key.
    Enter,
    /// Select all (Ctrl+A).
    SelectAll,
    /// Copy (Ctrl+C).
    Copy,
    /// Paste (Ctrl+V).
    Paste,
    /// Cut (Ctrl+X).
    Cut,
    /// Cancel/discard (no action).
    Cancel,
}

impl VoiceCommand {
    /// Parse from string code.
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

    /// Get string code.
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

/// Execute a voice command using the given injector.
pub fn execute(command: &VoiceCommand, injector: &dyn InputInjector) -> Result<()> {
    debug!("Executing command: {:?}", command);

    match command {
        VoiceCommand::Enter => {
            injector.press_key(Key::Enter)
        }
        VoiceCommand::SelectAll => {
            injector.key_combo(&[Modifier::Ctrl], Key::A)
        }
        VoiceCommand::Copy => {
            injector.key_combo(&[Modifier::Ctrl], Key::C)
        }
        VoiceCommand::Paste => {
            injector.key_combo(&[Modifier::Ctrl], Key::V)
        }
        VoiceCommand::Cut => {
            injector.key_combo(&[Modifier::Ctrl], Key::X)
        }
        VoiceCommand::Cancel => {
            debug!("Cancel command - no action taken");
            Ok(())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_voice_command_parse() {
        assert_eq!(VoiceCommand::parse("ENTER"), Some(VoiceCommand::Enter));
        assert_eq!(VoiceCommand::parse("enter"), Some(VoiceCommand::Enter));
        assert_eq!(VoiceCommand::parse("SELECT_ALL"), Some(VoiceCommand::SelectAll));
        assert_eq!(VoiceCommand::parse("COPY"), Some(VoiceCommand::Copy));
        assert_eq!(VoiceCommand::parse("PASTE"), Some(VoiceCommand::Paste));
        assert_eq!(VoiceCommand::parse("CUT"), Some(VoiceCommand::Cut));
        assert_eq!(VoiceCommand::parse("CANCEL"), Some(VoiceCommand::Cancel));
        assert_eq!(VoiceCommand::parse("INVALID"), None);
    }
}
```

---

## Task 8: Create Test Utility

Create `desktop/src/bin/test_input.rs` for manual testing:

```rust
//! Test utility for input injection.
//!
//! Usage: cargo run --bin test_input -- [text|key|combo]

use anyhow::Result;
use speech2code_desktop::input::{create_injector, Key, Modifier};
use std::env;
use std::thread;
use std::time::Duration;

fn main() -> Result<()> {
    // Initialize logging
    tracing_subscriber::fmt::init();

    let args: Vec<String> = env::args().collect();
    let mode = args.get(1).map(|s| s.as_str()).unwrap_or("text");

    println!("Creating input injector...");
    let injector = create_injector()?;
    println!("Using backend: {}", injector.backend_name());

    println!("You have 3 seconds to focus a text editor...");
    thread::sleep(Duration::from_secs(3));

    match mode {
        "text" => {
            println!("Typing test text...");
            injector.type_text("Hello from Speech2Code!")?;
            println!("Done!");
        }
        "key" => {
            println!("Pressing Enter...");
            injector.press_key(Key::Enter)?;
            println!("Done!");
        }
        "combo" => {
            println!("Pressing Ctrl+A (select all)...");
            injector.key_combo(&[Modifier::Ctrl], Key::A)?;
            println!("Done!");
        }
        "full" => {
            println!("Running full test sequence...");
            
            // Type some text
            injector.type_text("Line 1")?;
            thread::sleep(Duration::from_millis(100));
            
            // Press Enter
            injector.press_key(Key::Enter)?;
            thread::sleep(Duration::from_millis(100));
            
            // Type more text
            injector.type_text("Line 2")?;
            thread::sleep(Duration::from_millis(100));
            
            // Select all
            injector.key_combo(&[Modifier::Ctrl], Key::A)?;
            thread::sleep(Duration::from_millis(500));
            
            println!("Done! Text should be selected.");
        }
        _ => {
            println!("Unknown mode: {}", mode);
            println!("Usage: test_input [text|key|combo|full]");
        }
    }

    Ok(())
}
```

Add to `desktop/Cargo.toml`:

```toml
[[bin]]
name = "test_input"
path = "src/bin/test_input.rs"
```

---

## Task 9: Update Library Exports

Create `desktop/src/lib.rs` for library exports (needed for test binary):

```rust
//! Speech2Code Desktop Library
//!
//! This library provides the core functionality for the Speech2Code
//! desktop application.

pub mod bluetooth;
pub mod commands;
pub mod config;
pub mod crypto;
pub mod events;
pub mod input;
pub mod storage;
pub mod ui;
```

---

## Testing

### Test X11 Injection

```bash
cd /home/dan/workspace/priv/speech2code/desktop

# Build and run test utility
cargo build --bin test_input

# Open a text editor, then run:
cargo run --bin test_input -- text

# Test Enter key
cargo run --bin test_input -- key

# Test Ctrl+A
cargo run --bin test_input -- combo

# Full test
cargo run --bin test_input -- full
```

### Test Wayland Injection

```bash
# Ensure ydotool daemon is running
sudo systemctl status ydotool
# Or start it:
sudo systemctl start ydotool

# Force Wayland backend
XDG_SESSION_TYPE=wayland cargo run --bin test_input -- text
```

### Test in Main Application

```bash
# Run the main app
cargo run

# Connect from Android Bluetooth terminal and send:
{"v":1,"t":"TEXT","p":"Hello from Android!","ts":1234567890123,"cs":"00000000"}
```

---

## Troubleshooting

### X11: "Cannot open display"

```bash
# Check DISPLAY is set
echo $DISPLAY

# Should output something like :0 or :1
export DISPLAY=:0
```

### Wayland: "ydotool failed"

```bash
# Check ydotool daemon
pgrep ydotoold

# Start daemon
sudo systemctl start ydotool

# Or run manually
sudo ydotoold &
```

### Wayland: "Permission denied" for uinput

```bash
# Add user to input group
sudo usermod -a -G input $USER

# Check udev rules
cat /etc/udev/rules.d/99-uinput.rules

# Reload rules
sudo udevadm control --reload-rules
sudo udevadm trigger

# Log out and back in
```

### Both: Text appears garbled

This usually happens with non-ASCII characters. Ensure:
- System locale is UTF-8
- Application receiving input supports Unicode

---

## Verification Checklist

- [ ] X11 injector compiles and initializes
- [ ] Wayland injector compiles and initializes  
- [ ] Display server auto-detection works
- [ ] `test_input text` types text correctly
- [ ] `test_input key` presses Enter
- [ ] `test_input combo` performs Ctrl+A
- [ ] Commands work in main application
- [ ] Unicode text works correctly

## Output Artifacts

After completing this phase:

1. **X11Injector** - Text injection via enigo/XTest
2. **WaylandInjector** - Text injection via ydotool
3. **Key/Modifier definitions** - Cross-platform key mappings
4. **DisplayServer detection** - Automatic backend selection
5. **test_input binary** - Manual testing utility

## Next Phase

Proceed to **Phase 05: Linux UI (System Tray & History)** to implement the system tray icon, menu, and history window.
