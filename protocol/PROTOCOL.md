# Speech2Prompt Communication Protocol

## Overview

This document defines the communication protocol between the Speech2Prompt Android app and Linux desktop app over Bluetooth RFCOMM (Serial Port Profile).

## Transport Layer

- **Protocol**: Bluetooth RFCOMM (SPP - Serial Port Profile)
- **UUID**: `00001101-0000-1000-8000-00805F9B34FB` (standard SPP UUID)
- **Framing**: Newline-delimited JSON messages (`\n` terminated)
- **Encoding**: UTF-8

## Message Structure

All messages are JSON objects with the following fields:

```json
{
  "v": 1,
  "t": "MESSAGE_TYPE",
  "p": "payload",
  "ts": 1234567890123,
  "cs": "abc12345"
}
```

### Field Descriptions

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `v` | integer | Yes | Protocol version (currently `1`) |
| `t` | string | Yes | Message type (see below) |
| `p` | string | Yes | Message payload |
| `ts` | integer | Yes | Unix timestamp in milliseconds |
| `cs` | string | Yes | Checksum for integrity verification |

### Checksum Calculation

```
checksum = SHA256(v + t + p + ts + shared_secret).substring(0, 8)
```

Where `+` is string concatenation and `shared_secret` is the encryption key established during pairing.

## Message Types

### TEXT

Plain text to be typed at the cursor position.

```json
{
  "v": 1,
  "t": "TEXT",
  "p": "Hello, this is dictated text",
  "ts": 1706745600000,
  "cs": "a1b2c3d4"
}
```

**Receiver Action**: Type the payload text at current cursor position.

### COMMAND

Voice command to execute.

```json
{
  "v": 1,
  "t": "COMMAND",
  "p": "ENTER",
  "ts": 1706745600000,
  "cs": "e5f6g7h8"
}
```

**Command Codes**:

| Code | Action | Keyboard Equivalent |
|------|--------|---------------------|
| `ENTER` | Press Enter/Return | Enter |
| `SELECT_ALL` | Select all text | Ctrl+A |
| `COPY` | Copy selection | Ctrl+C |
| `PASTE` | Paste clipboard | Ctrl+V |
| `CUT` | Cut selection | Ctrl+X |
| `CANCEL` | Discard pending input | (no action) |

### HEARTBEAT

Keep-alive message sent periodically to maintain connection.

```json
{
  "v": 1,
  "t": "HEARTBEAT",
  "p": "",
  "ts": 1706745600000,
  "cs": "i9j0k1l2"
}
```

**Interval**: Every 5 seconds when connection is idle.

**Timeout**: If no message received for 15 seconds, connection is considered lost.

### ACK

Acknowledgment of received message.

```json
{
  "v": 1,
  "t": "ACK",
  "p": "1706745600000",
  "ts": 1706745600100,
  "cs": "m3n4o5p6"
}
```

**Payload**: Timestamp of the acknowledged message.

### PAIR_REQ

Pairing request sent by Android to initiate secure session.

```json
{
  "v": 1,
  "t": "PAIR_REQ",
  "p": "{\"device_id\":\"android-xxx\",\"public_key\":\"base64...\"}",
  "ts": 1706745600000,
  "cs": "q7r8s9t0"
}
```

**Payload** (JSON string):
- `device_id`: Unique identifier for the Android device
- `public_key`: Base64-encoded public key for key exchange

### PAIR_ACK

Pairing acknowledgment sent by Linux after successful pairing.

```json
{
  "v": 1,
  "t": "PAIR_ACK",
  "p": "{\"device_id\":\"linux-xxx\",\"public_key\":\"base64...\",\"status\":\"ok\"}",
  "ts": 1706745600000,
  "cs": "u1v2w3x4"
}
```

**Payload** (JSON string):
- `device_id`: Unique identifier for the Linux device
- `public_key`: Base64-encoded public key for key exchange
- `status`: `"ok"` or `"error"`

## Encryption

### Initial Pairing

1. User enters 6-digit PIN on both devices
2. Both devices derive shared secret:
   ```
   shared_secret = PBKDF2(
     password = PIN + android_device_id + linux_device_id,
     salt = "speech2prompt_v1",
     iterations = 100000,
     key_length = 32
   )
   ```
3. Shared secret is stored securely on both devices

### Message Encryption

After pairing, message payloads are encrypted:

1. Generate random 12-byte IV for each message
2. Encrypt payload with AES-256-GCM using shared secret
3. Prepend IV to ciphertext
4. Base64-encode the result

**Encrypted Message Format**:
```json
{
  "v": 1,
  "t": "TEXT",
  "p": "base64(IV + AES-GCM(payload))",
  "ts": 1706745600000,
  "cs": "checksum_of_encrypted_payload"
}
```

### Decryption

1. Base64-decode the payload
2. Extract first 12 bytes as IV
3. Decrypt remaining bytes with AES-256-GCM

## Connection Flow

### Initial Connection

```
Android                                   Linux
   |                                        |
   |  [Bluetooth RFCOMM Connect]            |
   |--------------------------------------->|
   |                                        |
   |  PAIR_REQ (if not previously paired)   |
   |--------------------------------------->|
   |                                        |
   |                          [User enters PIN]
   |                                        |
   |                              PAIR_ACK  |
   |<---------------------------------------|
   |                                        |
   |  HEARTBEAT                             |
   |--------------------------------------->|
   |                                        |
   |                              ACK       |
   |<---------------------------------------|
   |                                        |
   [Connection established, ready for text] |
```

### Normal Operation

```
Android                                   Linux
   |                                        |
   |  TEXT "Hello world"                    |
   |--------------------------------------->|
   |                              ACK       |
   |<---------------------------------------|
   |                         [Types text]   |
   |                                        |
   |  COMMAND "ENTER"                       |
   |--------------------------------------->|
   |                              ACK       |
   |<---------------------------------------|
   |                      [Presses Enter]   |
   |                                        |
   |  HEARTBEAT (every 5s when idle)        |
   |--------------------------------------->|
   |                              ACK       |
   |<---------------------------------------|
```

### Reconnection

1. Android detects disconnection
2. Wait 1 second
3. Attempt reconnection (up to 5 retries with exponential backoff)
4. If previously paired, skip PAIR_REQ/PAIR_ACK
5. Resume with HEARTBEAT

## Error Handling

### Invalid Checksum

If checksum verification fails:
1. Log the error
2. Do not process the message
3. Do not send ACK
4. Continue listening for valid messages

### Unknown Message Type

If message type is unrecognized:
1. Log the warning
2. Send ACK with error status (if protocol supports)
3. Continue processing other messages

### Decryption Failure

If decryption fails:
1. Log the error
2. Request re-pairing (send PAIR_REQ)
3. Or disconnect and notify user

## Version Compatibility

- Receivers should accept messages with `v >= 1`
- Unknown fields should be ignored (forward compatibility)
- Unsupported message types should be logged and skipped
