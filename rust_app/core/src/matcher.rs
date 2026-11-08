use crate::model::Entry;
use std::collections::HashMap;

/// Conservative normalization removes provider prefixes, common quality labels
/// and trailing years, without dropping distinctive title words.
pub fn match_key(title: &str) -> String {
    let mut text = title.trim().to_lowercase();
    for _ in 0..5 {
        let t = text.trim_start().to_string();
        let mut replaced = false;
        for p in ["[en]", "[eng]", "[us]", "[usa]", "[uk]", "[ca]", "[au]",
                  "[nz]", "[4k]", "[hd]", "[fhd]", "usa:", "us:", "uk:",
                  "en:", "eng:", "vod:", "4k:", "hd:", "fhd:"] {
            if let Some(rest) = t.strip_prefix(p) {
                text = rest.trim_start_matches([' ', '-', '|', ':']).to_string();
                replaced = true;
                break;
            }
        }
        if !replaced { break; }
    }
    for suffix in [" vod", " english", " 4k", " uhd", " fhd", " hd", " hevc", " h265"] {
        if let Some(prefix) = text.strip_suffix(suffix) { text = prefix.into(); }
    }
    // Strip a terminal (1989) or (2026), without altering internal numbers.
    if text.len() >= 6 {
        let bytes = text.as_bytes();
        let n = bytes.len();
        if bytes[n-6] == b'(' && bytes[n-1] == b')'
            && bytes[n-5..n-1].iter().all(|b| b.is_ascii_digit()) {
            text.truncate(n - 6);
        }
    }
    text.chars().filter(|c| c.is_ascii_alphanumeric()).collect()
}
pub fn canonical_channel(title: &str) -> String {
    let mut s = title.to_ascii_lowercase();
    for prefix in ["us:", "usa:", "uk:", "ca:", "au:", "nz:", "us |", "usa |"] {
        if let Some(t) = s.strip_prefix(prefix) { s = t.trim().to_string(); }
    }
    s = s.replace("hd", "").replace("fhd", "").replace("4k", "")
         .replace("east", "").replace("west", "");
    s.chars().filter(|x| x.is_ascii_alphanumeric()).collect()
}
pub fn preferred_english(category: &str, title: &str) -> bool {
    let t = format!(" {} {} ", category.to_lowercase(), title.to_lowercase());
    let good = ["usa", "us |", "united states", "english", "eng", "uk |",
                "united kingdom", "au |", "australia", "nz |", "new zealand"];
    let bad = [" arabic", " hindi", " urdu", " dutch", " spanish", " french", " german",
               " portugal", " italy", " turkey", " brazil", " latino"];
    if bad.iter().any(|v| t.contains(v)) { return false; }
    if good.iter().any(|v| t.contains(v)) { return true; }
    // Unknown language is not automatically discarded. Provider metadata
    // often omits language even for English-language titles.
    true
}

/// Map TMDB suggestions to genuine IPTV streams. No fabricated playable entries.
pub fn match_available<'a>(
    suggested: &[String], provider: &'a [Entry]
) -> Vec<&'a Entry> {
    let mut by_name: HashMap<String, &Entry> = HashMap::new();
    for entry in provider {
        if entry.hidden { continue; }
        let key = match_key(&entry.title);
        if key.is_empty() { continue; }
        by_name.entry(key).and_modify(|prev| {
            let prefer = preferred_english(&entry.category, &entry.title);
            if prefer && !preferred_english(&prev.category, &prev.title) { *prev = entry; }
        }).or_insert(entry);
    }
    suggested.iter().filter_map(|name| by_name.get(&match_key(name)).copied()).collect()
}
#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::Kind;
    #[test] fn strips_quality_not_identity() {
        assert_eq!(match_key("US: The Simpsons (1989)"), "thesimpsons");
        assert_eq!(match_key("Succession (2026) VOD - English 4K"), "succession2026vodenglish");
        assert_ne!(match_key("The Simpsons Shorts"), match_key("The Simpsons"));
    }
    #[test] fn never_returns_fake_vod() {
        let names = vec!["The Simpsons".to_string(),"No Such Movie".into()];
        let lib = vec![Entry::empty(Kind::Series,"s1","The Simpsons (1989)")];
        let matched = match_available(&names,&lib);
        assert_eq!(matched.len(),1);
        assert_eq!(matched[0].id,"s1");
    }
}
