//! AuroraTV 3.0 shared Rust engine. No UI toolkit or Android dependency.
pub mod catalog;
pub mod epg;
pub mod matcher;
pub mod model;
pub mod m3u;
pub mod tmdb;
pub mod xtream;
pub mod playback;

pub use catalog::Catalog;
pub use model::{Entry, Kind, Programme, Episode, Metadata, CastMember};

#[derive(Debug, thiserror::Error)]
pub enum AuroraError {
    #[error("network error: {0}")]
    Network(#[from] ureq::Error),
    #[error("invalid URL: {0}")]
    Url(#[from] url::ParseError),
    #[error("JSON error: {0}")]
    Json(#[from] serde_json::Error),
    #[error("database error: {0}")]
    Database(#[from] rusqlite::Error),
    #[error("XML error: {0}")]
    Xml(#[from] quick_xml::Error),
    #[error("{0}")]
    Invalid(String),
}
pub type Result<T> = std::result::Result<T, AuroraError>;
