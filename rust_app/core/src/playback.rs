use crate::model::{Entry, Kind};
use crate::xtream::Xtream;
use std::collections::HashSet;
/// All candidates are derived solely from the provider stream ID, never TMDB.
pub fn candidates(item: &Entry, source: Option<&Xtream>) -> Vec<String> {
    if !item.direct_url.is_empty() { return vec![item.direct_url.clone()]; }
    let Some(source)=source else { return vec![]; };
    let extensions: &[&str] = match item.kind {
        Kind::Live => &["ts","m3u8"],
        Kind::Movie => &["mkv","mp4","ts"],
        Kind::Series => &["mp4","mkv","ts"],
    };
    let mut urls=Vec::new();
    let mut seen=HashSet::new();
    if !item.container.is_empty() {
        let ext=item.container.to_ascii_lowercase();
        if ext.chars().all(|c| c.is_ascii_alphanumeric()) && ext.len()<=5 {
            let u=source.stream_url(item.kind,&item.stream_id,&ext);
            if seen.insert(u.clone()) { urls.push(u); }
        }
    }
    for ext in extensions {
        let u=source.stream_url(item.kind,&item.stream_id,ext);
        if seen.insert(u.clone()) { urls.push(u); }
    }
    urls
}
#[derive(Debug,Clone,Copy,PartialEq,Eq)]
pub enum PlaybackFailure { Network, Unauthorized, UnsupportedCodec, UnsupportedContainer, Unknown }
pub fn classify(error: &str, status: Option<u16>) -> PlaybackFailure {
    if matches!(status,Some(401|403|404)) { return PlaybackFailure::Unauthorized; }
    let s=error.to_ascii_lowercase();
    if s.contains("exceeds_capabilities") || s.contains("mediacodecvideorenderer")
       || s.contains("decoder init") || s.contains("unsupported hevc") {
        return PlaybackFailure::UnsupportedCodec;
    }
    if s.contains("matroska") || s.contains("parser error") {
        return PlaybackFailure::UnsupportedContainer;
    }
    if s.contains("timed out") || s.contains("source error") ||
       s.contains("connection") { return PlaybackFailure::Network; }
    PlaybackFailure::Unknown
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn preserves_source_for_4k_vod() {
        let x=Xtream::new("http://iptv.test","user","pass").unwrap();
        let mut e=Entry::empty(Kind::Movie,"1","Movie (2026)");
        e.stream_id="11880".into();e.container="mkv".into();
        let result=candidates(&e,Some(&x));
        assert!(result[0].contains("/11880.mkv"));
        assert_eq!(classify("NO_EXCEEDS_CAPABILITIES",None),PlaybackFailure::UnsupportedCodec);
    }
}
