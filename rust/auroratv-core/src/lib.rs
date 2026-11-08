//! AuroraTV's platform-independent Rust domain and data engine.
//! No IPTV subscriptions, channels, credentials, or third-party artwork are bundled.
//! The Android shell currently owns Media3 decoding, encrypted preferences, and D-pad UI.
pub mod cache;
pub mod catalog;
pub mod guide;
pub mod matching;
pub mod streams;

pub use catalog::{MediaItem, MediaKind};
pub use guide::{Channel, Programme};
