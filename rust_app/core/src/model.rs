use serde::{Serialize, Deserialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Kind { Live, Movie, Series }
impl Kind {
    pub fn as_str(self) -> &'static str {
        match self { Kind::Live => "live", Kind::Movie => "movie", Kind::Series => "series" }
    }
    pub fn parse(text: &str) -> Self {
        match text.to_ascii_lowercase().as_str() {
            "movie" | "vod" => Kind::Movie, "series" | "tv" => Kind::Series, _ => Kind::Live
        }
    }
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Entry {
    pub id: String,
    pub source_id: String,
    pub stream_id: String,
    pub title: String,
    pub kind: Kind,
    #[serde(default)] pub category: String,
    #[serde(default)] pub poster: String,
    #[serde(default)] pub direct_url: String,
    #[serde(default)] pub container: String,
    #[serde(default)] pub epg_id: String,
    #[serde(default)] pub year: i32,
    #[serde(default)] pub rating: f32,
    #[serde(default)] pub favorite: bool,
    #[serde(default)] pub hidden: bool,
}
impl Entry {
    pub fn empty(kind: Kind, id: impl Into<String>, title: impl Into<String>) -> Self {
        Self {
            id: id.into(), source_id: String::new(), stream_id: String::new(),
            title: title.into(), kind, category: String::new(), poster: String::new(),
            direct_url: String::new(), container: String::new(), epg_id: String::new(),
            year: 0, rating: 0.0, favorite: false, hidden: false,
        }
    }
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Episode {
    pub season: u32,
    pub number: u32,
    pub title: String,
    pub stream: Entry,
    pub duration_seconds: u32,
}
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct Programme {
    pub channel: String,
    pub title: String,
    pub description: String,
    pub start_unix: i64,
    pub end_unix: i64,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CastMember {
    pub name: String,
    pub character: String,
    pub photo: String,
}
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct Metadata {
    pub tmdb_id: Option<u64>,
    pub title: String,
    pub overview: String,
    pub backdrop: String,
    pub poster: String,
    pub release_year: i32,
    pub rating: f32,
    pub runtime_minutes: u32,
    pub genres: Vec<String>,
    pub cast: Vec<CastMember>,
    pub related: Vec<(String, String)>,
}
