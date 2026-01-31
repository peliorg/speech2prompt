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

//! Bluetooth communication module.
//!
//! Handles RFCOMM server for receiving messages from Android app.

mod connection;
mod protocol;
mod server;

pub use connection::{ConnectionEvent, ConnectionHandler, ConnectionState};
pub use protocol::{
    CommandCode, Message, MessageType, PairAckPayload, PairRequestPayload, PairStatus,
    PROTOCOL_VERSION,
};
pub use server::{BluetoothManager, BluetoothServer, PairedDevice};
