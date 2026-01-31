//! Integration tests for the full communication flow.

use speech2code_desktop::crypto::CryptoContext;
use speech2code_desktop::bluetooth::protocol::{Message, MessageType};

#[test]
fn test_cross_platform_encryption() {
    // Test that encryption/decryption works correctly
    let pin = "123456";
    let android_id = "android-test123";
    let linux_id = "linux-test456";
    
    // Create contexts on both "sides"
    let android_ctx = CryptoContext::from_pin(pin, android_id, linux_id);
    let linux_ctx = CryptoContext::from_pin(pin, android_id, linux_id);
    
    // Encrypt on Android side
    let plaintext = "Hello from Android!";
    let encrypted = android_ctx.encrypt(plaintext).unwrap();
    
    // Decrypt on Linux side
    let decrypted = linux_ctx.decrypt(&encrypted).unwrap();
    
    assert_eq!(plaintext, decrypted);
}

#[test]
fn test_message_flow() {
    let pin = "123456";
    let android_id = "android-test123";
    let linux_id = "linux-test456";
    
    let android_ctx = CryptoContext::from_pin(pin, android_id, linux_id);
    let linux_ctx = CryptoContext::from_pin(pin, android_id, linux_id);
    
    // Create message on Android
    let mut msg = Message::text("Test message");
    msg.sign_and_encrypt(&android_ctx).unwrap();
    
    // Serialize
    let json = msg.to_json().unwrap();
    
    // Parse on Linux
    let mut received = Message::from_json(&json).unwrap();
    
    // Verify and decrypt
    received.verify_and_decrypt(&linux_ctx).unwrap();
    
    assert_eq!(received.payload, "Test message");
}

#[test]
fn test_command_message() {
    let ctx = CryptoContext::from_pin("123456", "android-1", "linux-1");
    
    let mut msg = Message::command("ENTER");
    msg.sign_and_encrypt(&ctx).unwrap();
    
    let json = msg.to_json().unwrap();
    let mut received = Message::from_json(&json).unwrap();
    received.verify_and_decrypt(&ctx).unwrap();
    
    assert_eq!(received.message_type, MessageType::Command);
    assert_eq!(received.payload, "ENTER");
}

#[test]
fn test_checksum_verification() {
    let ctx = CryptoContext::from_pin("123456", "android-1", "linux-1");
    
    let mut msg = Message::text("Test");
    msg.sign(&ctx);
    
    // Verify passes
    assert!(msg.verify(&ctx));
    
    // Tamper with message
    msg.payload = "Tampered".to_string();
    
    // Verify fails
    assert!(!msg.verify(&ctx));
}
