use crate::model::{Entry, Kind};
use std::collections::HashMap;

/// Parse M3U without relying on provider-specific channel order.
pub fn parse(source_id: &str, contents: &str) -> Vec<Entry> {
    let mut out = Vec::new();
    let mut attributes: HashMap<String,String> = HashMap::new();
    let mut name = String::new();
    for raw in contents.lines() {
        let line = raw.trim();
        if line.starts_with("#EXTINF:") {
            attributes = attributes_from_extinf(line);
            name = line.rsplit_once(',').map(|(_,t)| t.trim().to_string()).unwrap_or_default();
        } else if line.starts_with('#') || line.is_empty() {
            continue;
        } else if line.starts_with("http://") || line.starts_with("https://") {
            let mut kind = Kind::Live;
            if line.contains("/movie/") { kind=Kind::Movie; }
            if line.contains("/series/") { kind=Kind::Series; }
            let id = attributes.get("tvg-id").filter(|s| !s.is_empty())
                .cloned().unwrap_or_else(|| format!("m3u-{}", out.len()));
            let title=if name.is_empty() { id.clone() } else { name.clone() };
            let mut item=Entry::empty(kind,format!("{source_id}:{id}:{}",out.len()),title);
            item.source_id=source_id.into();
            item.direct_url=line.into();
            item.epg_id=attributes.get("tvg-id").cloned().unwrap_or_default();
            item.poster=attributes.get("tvg-logo").cloned().unwrap_or_default();
            item.category=attributes.get("group-title").cloned().unwrap_or_default();
            out.push(item);
            name.clear();
            attributes.clear();
        }
    }
    out
}
fn attributes_from_extinf(line: &str) -> HashMap<String,String> {
    let mut result=HashMap::new();
    for key in ["tvg-id","tvg-name","tvg-logo","group-title","tvg-language"] {
        if let Some(i)=line.find(&format!("{key}=\"")) {
            let rest=&line[i+key.len()+2..];
            if let Some(end)=rest.find('"') {
                result.insert(key.into(),rest[..end].into());
            }
        }
    }
    result
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn parses_provider_metadata_and_individual_streams() {
        let s="#EXTM3U\n#EXTINF:-1 tvg-id=\"abc.us\" tvg-logo=\"x.jpg\" group-title=\"USA\",ABC News\nhttp://host/live/u/p/42.ts\n#EXTINF:-1,Film\nhttp://host/movie/u/p/2.mkv\n";
        let items=parse("provider",s);
        assert_eq!(items.len(),2);
        assert_eq!(items[0].epg_id,"abc.us");
        assert_eq!(items[0].category,"USA");
        assert_eq!(items[1].kind,Kind::Movie);
    }
}
