//! Conservative network matching. A weak match is *not* a playable channel.
//! Never synthesize a channel entry without a real provider stream.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Region { UnitedStates, Canada, UnitedKingdom, Other, Unknown }

fn tokens(text: &str) -> Vec<String> {
    text.to_ascii_uppercase()
        .split(|c: char| !c.is_ascii_alphanumeric())
        .filter(|s| !s.is_empty())
        .map(str::to_owned).collect()
}
pub fn region(text: &str) -> Region {
    let t = tokens(text);
    if t.iter().any(|v| matches!(v.as_str(), "US" | "USA")) ||
        t.windows(2).any(|w| w[0]=="UNITED" && w[1]=="STATES") { Region::UnitedStates }
    else if t.iter().any(|v| matches!(v.as_str(), "CA" | "CAN" | "CANADA")) { Region::Canada }
    else if t.iter().any(|v| matches!(v.as_str(), "UK" | "GB" | "BRITISH")) { Region::UnitedKingdom }
    else if t.iter().any(|v| matches!(v.as_str(), "FR" | "FRA" | "AR" | "DE" | "ES" | "RU")) { Region::Other }
    else { Region::Unknown }
}
fn token_is_noise(token: &str) -> bool {
    matches!(token, "US" | "USA" | "CA" | "CAN" | "CANADA" | "UK" | "GB" | "EN"
        | "ENG" | "HD" | "FHD" | "SD" | "UHD" | "4K" | "8K" | "HEVC" | "H264"
        | "H265" | "HDR" | "1080P" | "720P" | "60FPS" | "BACKUP" | "ALT")
}
pub fn normalize_channel(title: &str) -> String {
    let parts = tokens(title);
    let mut cleaned: Vec<&str> = parts.iter().map(String::as_str)
        .filter(|part| !token_is_noise(part)).collect();
    if cleaned.len() >= 2 && cleaned[0] == "A" && cleaned[1] == "E" {
        cleaned.splice(0..2, ["A", "AND", "E"]);
    }
    cleaned.join("")
}
fn quality(name: &str) -> i32 {
    let t=tokens(name);
    if t.iter().any(|s| s=="4K" || s=="UHD") { 16 }
    else if t.iter().any(|s| s=="FHD" || s=="1080P") { 18 }
    else if t.iter().any(|s| s=="HD" || s=="720P") { 12 }
    else if t.iter().any(|s| s=="SD") { 2 }
    else { 6 }
}
fn is_alternate(original: &str, expected: &str) -> bool {
    // No prefix matching for ESPN vs ESPN2, nor CNN vs CNN INTERNATIONAL.
    original == expected
}

/// Positive scores require a real exact network match after removing known
/// quality/country tags. US sources outrank Canadian sources for a US lineup.
/// The caller must display an unavailable state if all candidates score <= 0.
pub fn score_us_network(network: &str, candidate: &str, category: &str) -> i32 {
    let expected=normalize_channel(network);
    let actual=normalize_channel(candidate);
    if expected.is_empty() || !is_alternate(&actual, &expected) { return 0; }
    let r = match region(&format!("{candidate} {category}")) {
        Region::UnitedStates => 70,
        Region::Canada => -40,
        Region::UnitedKingdom => -15,
        Region::Other => -80,
        Region::Unknown => 0
    };
    (100 + quality(candidate) + r).max(1)
}
pub fn choose_us_network<'a>(network: &str, candidates: &'a [(&'a str, &'a str)]) -> Option<usize> {
    let mut choices: Vec<(usize,i32)> = candidates.iter().enumerate()
        .map(|(i,(name,category))| (i,score_us_network(network,name,category)))
        .filter(|(_,score)| *score > 0).collect();
    choices.sort_by(|a,b| b.1.cmp(&a.1).then(a.0.cmp(&b.0)));
    choices.first().map(|(i,_)| *i)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn us_preferred_over_ca() {
        let c=[("CA: Comedy Central HD","Canada"),("US: Comedy Central FHD","United States")];
        assert_eq!(choose_us_network("Comedy Central",&c),Some(1));
    }
    #[test] fn never_match_a_different_network() {
        assert_eq!(score_us_network("ESPN","ESPN 2 HD","US"),0);
        assert_eq!(score_us_network("ESPN","ESPN NEWS","US"),0);
        assert_eq!(score_us_network("CNN","CNN International","US"),0);
        assert_eq!(score_us_network("Comedy Central","Comet TV","US"),0);
    }
    #[test] fn unavailable_is_really_unavailable() {
        assert_eq!(choose_us_network("Comedy Central",&[("Cartoon Network","US")]),None);
    }
}
