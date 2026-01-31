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

//! PIN entry dialog for pairing.

use gtk4::prelude::*;
use gtk4::{Application, ApplicationWindow, Box as GtkBox, Button, Entry, Label, Orientation};
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
pub fn show_pin_dialog(app: &Application, device_id: &str) -> oneshot::Receiver<PinDialogResult> {
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

    // Auto-close after 60 seconds
    let window_timeout = window.clone();
    let tx_timeout = tx.clone();
    glib::timeout_add_seconds_local_once(60, move || {
        if let Some(tx) = tx_timeout.lock().unwrap().take() {
            let _ = tx.send(PinDialogResult::Cancelled);
        }
        window_timeout.close();
    });

    window.present();
    pin_entry.grab_focus();

    rx
}
