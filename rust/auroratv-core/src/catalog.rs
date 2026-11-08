//! Streaming catalog parsers: bounded per-entry allocations, no giant JSON DOM.
use serde::{Deserialize, Serialize};
use serde::de::{self, SeqAccess, Visitor, Deserializer};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::fmt;
use std::io::{self, BufRead, Read};

use crate::streams::reference_of;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum MediaKind { Live, Movie, Series, Episode }

impl MediaKind {
    pub fn as_str(&self) -> &'static str {
        match self { Self::Live=>"live", Self::Movie=>"movie", Self::Series=>"series", Self::Episode=>"episode" }
    }
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MediaItem {
    pub id: String,
    pub name: String,
    pub category: String,
    pub kind: MediaKind,
    /// Xtream: compact credential-free reference. M3U: transient raw URL; must
    /// be encrypted by the Android credential vault BEFORE persistence.
    pub stream_ref: String,
    pub epg_id: String,
    pub language: String,
    pub artwork: String,
    pub release_year: Option<i32>,
    pub rating: Option<f32>,
    pub added_at: Option<i64>,
}

fn hash_key(value: &str) -> String {
    let hash = Sha256::digest(value.as_bytes());
    hash.iter().map(|v| format!("{v:02x}")).collect()
}
fn json_string(v: Option<&Value>) -> String {
    match v {
        Some(Value::String(s)) => s.clone(),
        Some(Value::Number(n)) => n.to_string(),
        _ => String::new(),
    }
}
fn first_nonempty(row: &Value, keys: &[&str]) -> String {
    keys.iter().map(|k| json_string(row.get(*k)))
        .find(|s| !s.is_empty()).unwrap_or_default()
}
fn parse_row(server: &str, username: &str, kind: MediaKind, row: &Value, categories: &HashMap<String,String>) -> Option<MediaItem> {
    let id = match kind {
        MediaKind::Series => first_nonempty(row,&["series_id"]),
        _ => first_nonempty(row,&["stream_id"]),
    };
    if id.is_empty() { return None; }
    let raw_name = first_nonempty(row,&["name","title"]);
    let name = if raw_name.is_empty() { "Untitled".to_owned() } else { raw_name };
    let category_id = json_string(row.get("category_id"));
    let category = categories.get(&category_id).cloned()
        .unwrap_or_else(|| "Uncategorized".into());
    let ext = if kind == MediaKind::Live { "ts".to_owned() }
        else { let e = json_string(row.get("container_extension"));
            if e.is_empty() { "mp4".to_owned() } else { e }
        };
    let stream_ref = reference_of(kind.as_str(),&id,&ext)?;
    let key = format!("{server}|{username}|{}|{id}",kind.as_str());
    let artwork = first_nonempty(row, &["stream_icon","cover","cover_big","movie_image","poster","thumbnail"]);
    let release_year = first_nonempty(row,&["year","releaseDate","releasedate","release_date"])
        .chars().take(4).collect::<String>().parse::<i32>().ok()
        .filter(|year| (1888..=2100).contains(year));
    let rating = first_nonempty(row,&["rating_10","rating"])
        .parse::<f32>().ok().filter(|v| (0.0..=10.0).contains(v));
    let added_at = first_nonempty(row,&["added","added_at"])
        .parse::<i64>().ok().filter(|v| (1_000_000_000..5_000_000_000).contains(v));
    Some(MediaItem {
        id: hash_key(&key), name, category, kind, stream_ref,
        epg_id: json_string(row.get("epg_channel_id")),
        language: first_nonempty(row,&["language","tvg_language"]),
        artwork, release_year, rating, added_at,
    })
}
struct JsonRows<'a, F> {
    server: &'a str,
    username: &'a str,
    kind: MediaKind,
    categories: &'a HashMap<String,String>,
    sink: &'a mut F,
}
impl<'de, F: FnMut(MediaItem)> Visitor<'de> for JsonRows<'_, F> {
    type Value = usize;
    fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
        f.write_str("Xtream JSON array of media objects")
    }
    fn visit_seq<A: SeqAccess<'de>>(self, mut seq: A) -> Result<usize, A::Error> {
        let mut total=0;
        while let Some(row) = seq.next_element::<Value>()? {
            if let Some(item) = parse_row(self.server,self.username,self.kind.clone(),&row,self.categories) {
                (self.sink)(item);
                total+=1;
            }
        }
        Ok(total)
    }
}
/// Stream each item directly to sink (e.g. to an SQLite transaction). A single
/// row is materialized at a time; it never constructs a Vec of the full JSON.
pub fn stream_xtream<R: Read, F: FnMut(MediaItem)>(
    reader: R, server: &str, username: &str, kind: MediaKind,
    categories: &HashMap<String,String>, mut sink: F
) -> Result<usize, serde_json::Error> {
    let mut d = serde_json::Deserializer::from_reader(reader);
    let count = d.deserialize_seq(JsonRows {
        server, username, kind, categories, sink: &mut sink,
    })?;
    d.end()?;
    Ok(count)
}
fn attribute(line: &str, key: &str) -> String {
    let search = format!("{key}=\"");
    let Some(start) = line.find(&search) else { return String::new(); };
    let value = &line[start+search.len()..];
    value.split('"').next().unwrap_or("").to_owned()
}
fn extinf_title(line: &str) -> &str {
    let mut quoted=false;
    for (idx, ch) in line.char_indices() {
        if ch=='"' { quoted=!quoted; }
        else if ch==',' && !quoted { return line[idx+1..].trim(); }
    }
    "Untitled"
}
pub fn stream_m3u<R: BufRead, F: FnMut(MediaItem)>(
    reader: R, source: &str, mut sink: F
) -> io::Result<(usize,Option<String>)> {
    let mut meta: Option<String> = None;
    let mut guide_url=None;
    let mut total=0;
    for line in reader.lines() {
        let line=line?;
        let entry=line.trim();
        if entry.starts_with("#EXTM3U") {
            let xmltv = ["x-tvg-url","url-tvg"].iter()
                .map(|key| attribute(entry,key)).find(|v| !v.is_empty());
            guide_url=xmltv.and_then(|v| v.split(',').next().map(str::trim).map(str::to_owned))
                .filter(|v| v.starts_with("http://") || v.starts_with("https://"));
        } else if entry.starts_with("#EXTINF:") {
            meta=Some(entry.to_owned());
        } else if entry.is_empty() || entry.starts_with('#') {
            continue;
        } else if let Some(details) = meta.take() {
            if !entry.starts_with("http://") && !entry.starts_with("https://") { continue; }
            let name=extinf_title(&details).to_owned();
            let name=if name.is_empty() { "Untitled".into() } else { name };
            let category=attribute(&details,"group-title");
            let category=if category.is_empty() { "Uncategorized".into() } else { category };
            let epg_id=attribute(&details,"tvg-id");
            let language=attribute(&details,"tvg-language");
            let artwork=attribute(&details,"tvg-logo");
            let hint=attribute(&details,"media-type").to_ascii_lowercase();
            let kind = if hint=="movie" || entry.contains("/movie/") { MediaKind::Movie }
                else if hint=="series" || entry.contains("/series/") { MediaKind::Series }
                else { MediaKind::Live };
            let id=hash_key(&format!("{source}|{}|{epg_id}|{name}|{category}",kind.as_str()));
            sink(MediaItem {
                id,name,category,kind,stream_ref:entry.to_owned(),
                epg_id,language,artwork,release_year:None,rating:None,added_at:None,
            });
            total+=1;
        }
    }
    Ok((total,guide_url))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn streams_xtream_without_secret_urls() {
        let json = br#"[{"stream_id":123,"name":"US: ESPN HD","category_id":"1","epg_channel_id":"ESPN.us"},
            {"stream_id":"124","name":"US: Comedy Central","category_id":"1"},{}]"#;
        let cat=HashMap::from([("1".to_owned(),"United States".to_owned())]);
        let mut out=Vec::new();
        let count=stream_xtream(&json[..],"https://tv.example","username",MediaKind::Live,&cat,|x|out.push(x)).unwrap();
        assert_eq!(count,2);
        assert_eq!(out[0].epg_id,"ESPN.us");
        assert_eq!(out[0].stream_ref,"aurora-xtream:live:123:ts");
        assert!(!out[0].stream_ref.contains("username"));
        assert_eq!(out[1].category,"United States");
    }
    #[test] fn m3u_attrs_with_quoted_commas() {
        let playlist=r#"#EXTM3U x-tvg-url="https://guide.example/epg.xml"
#EXTINF:-1 tvg-id="espn.us" group-title="US, Sports" tvg-logo="https://images.example/a.jpg",US: ESPN HD
https://tv.example/live/1.ts
#EXTINF:-1 group-title="Films" media-type="movie",A Film
https://tv.example/movie/3.mp4
"#;
        let mut out=Vec::new();
        let (count,epg)=stream_m3u(playlist.as_bytes(),"https://tv.example/playlist.m3u",|x|out.push(x)).unwrap();
        assert_eq!(count,2);
        assert_eq!(epg.as_deref(),Some("https://guide.example/epg.xml"));
        assert_eq!(out[0].category,"US, Sports");
        assert_eq!(out[1].kind,MediaKind::Movie);
    }
}
