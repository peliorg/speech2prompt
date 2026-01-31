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
