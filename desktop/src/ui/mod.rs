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

//! UI module for system tray and windows.

mod history_window;
mod pin_dialog;
mod tray;

pub use history_window::show_history_window;
pub use pin_dialog::{show_pin_dialog, PinDialogResult};
pub use tray::{run_tray, TrayAction, TrayHandle};

