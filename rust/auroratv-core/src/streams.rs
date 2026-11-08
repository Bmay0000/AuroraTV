use percent_encoding::{utf8_percent_encode, NON_ALPHANUMERIC};
use url::Url;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum StreamError {
    InvalidServer,
    InvalidReference,
}

impl std::fmt::Display for StreamError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", match self {
            Self::InvalidServer => "Server must be an HTTP(S) URL with a hostname",
            Self::InvalidReference => "Invalid Xtream media reference",
        })
    }
}
impl std::error::Error for StreamError {}

fn valid_id(id: &str) -> bool {
    !id.is_empty() && id.len() <= 64
        && id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}
fn valid_ext(ext: &str) -> bool {
    !ext.is_empty() && ext.len() <= 8 && ext.bytes().all(|b| b.is_ascii_alphanumeric())
}

/// Compact reference, with no credentials in the media library.
pub fn reference_of(kind: &str, id: &str, ext: &str) -> Option<String> {
    if !valid_id(id) { return None; }
    match kind {
        "series" => Some(format!("aurora-xtream:series:{id}:none")),
        "live" | "movie" if valid_ext(ext) => {
            Some(format!("aurora-xtream:{kind}:{id}:{ext}"))
        }
        _ => None
    }
}

fn server_base(server: &str) -> Result<String, StreamError> {
    let parsed = Url::parse(server).map_err(|_| StreamError::InvalidServer)?;
    if !matches!(parsed.scheme(), "http" | "https")
        || parsed.host_str().is_none()
        || parsed.username() != ""
        || parsed.password().is_some()
        || parsed.query().is_some()
        || parsed.fragment().is_some() {
        return Err(StreamError::InvalidServer);
    }
    Ok(parsed.as_str().trim_end_matches('/').to_owned())
}
fn query_api(server: &str, username: &str, password: &str, action: Option<(&str, &str)>) -> Result<String, StreamError> {
    let base = server_base(server)?;
    let mut api = Url::parse(&format!("{base}/player_api.php")).map_err(|_| StreamError::InvalidServer)?;
    {
        let mut q = api.query_pairs_mut();
        q.append_pair("username", username).append_pair("password", password);
        if let Some((key, value)) = action { q.append_pair(key, value); }
    }
    Ok(api.to_string())
}
pub fn authentication_url(server: &str, username: &str, password: &str) -> Result<String, StreamError> {
    query_api(server, username, password, None)
}
pub fn catalog_url(server: &str, username: &str, password: &str, kind: &str) -> Result<String, StreamError> {
    let action = match kind {
        "live" => "get_live_streams",
        "movie" | "vod" => "get_vod_streams",
        "series" => "get_series",
        _ => return Err(StreamError::InvalidReference)
    };
    query_api(server, username, password, Some(("action", action)))
}
pub fn resolve_reference(reference: &str, server: &str, username: &str, password: &str) -> Result<String, StreamError> {
    let parts: Vec<_> = reference.split(':').collect();
    if parts.len() != 4 || parts[0] != "aurora-xtream"
        || reference_of(parts[1], parts[2], parts[3]).as_deref() != Some(reference) {
        return Err(StreamError::InvalidReference);
    }
    if parts[1] == "series" {
        let base = server_base(server)?;
        let mut api = Url::parse(&format!("{base}/player_api.php")).map_err(|_| StreamError::InvalidServer)?;
        api.query_pairs_mut().append_pair("username", username)
            .append_pair("password", password)
            .append_pair("action", "get_series_info")
            .append_pair("series_id", parts[2]);
        return Ok(api.to_string());
    }
    let base = server_base(server)?;
    let username = utf8_percent_encode(username, NON_ALPHANUMERIC);
    let password = utf8_percent_encode(password, NON_ALPHANUMERIC);
    Ok(format!("{base}/{}/{username}/{password}/{}.{}", parts[1], parts[2], parts[3]))
}

/// Media3 stays responsible for actual decoding/recovery. These hints avoid
/// treating every IPTV URL as an MPEG-TS file when an HLS or DASH manifest is supplied.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SourceHint { Hls, Dash, TransportStream, Progressive, Unknown }
pub fn source_hint(url: &str) -> SourceHint {
    let path = url.split(['?', '#']).next().unwrap_or("").to_ascii_lowercase();
    if path.ends_with(".m3u8") { SourceHint::Hls }
    else if path.ends_with(".mpd") { SourceHint::Dash }
    else if path.ends_with(".ts") || path.ends_with(".m2ts") { SourceHint::TransportStream }
    else if [".mp4", ".mkv", ".avi", ".mov"].iter().any(|ext| path.ends_with(ext)) { SourceHint::Progressive }
    else { SourceHint::Unknown }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn urls_and_credential_encoding() {
        let r = reference_of("live", "1234", "m3u8").unwrap();
        assert_eq!(r, "aurora-xtream:live:1234:m3u8");
        let url = resolve_reference(&r, "https://tv.example/subpath/", "hello world", "a/b?c").unwrap();
        assert!(url.contains("/live/hello%20world/a%2Fb%3Fc/1234.m3u8"));
        assert_eq!(source_hint(&url), SourceHint::Hls);
        assert!(url.starts_with("https://tv.example/subpath/"));
    }
    #[test] fn bad_references_are_rejected() {
        assert!(reference_of("live", "../123", "ts").is_none());
        assert!(reference_of("live", "1", "../ts").is_none());
        assert!(resolve_reference("aurora-xtream:live:../12:ts", "https://tv.example", "u", "p").is_err());
        assert!(authentication_url("file:///tmp/db", "u", "p").is_err());
        assert!(authentication_url("https://u:p@tv.example", "u", "p").is_err());
    }
    #[test] fn catalog_endpoint_and_series() {
        assert!(catalog_url("https://tv.example", "u", "p", "vod").unwrap().contains("get_vod_streams"));
        let r = reference_of("series", "99", "").unwrap();
        let url = resolve_reference(&r, "https://tv.example", "u", "p").unwrap();
        assert!(url.contains("get_series_info"));
        assert!(url.contains("series_id=99"));
    }
}
