//! XMLTV schedule parser; programme data is independent of the channel lineup.
use chrono::{FixedOffset, NaiveDateTime, TimeZone};
use quick_xml::{events::Event, Reader};
use serde::{Deserialize, Serialize};
use std::error::Error;
use std::io::BufRead;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Channel { pub id: String, pub display_name: String }
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Programme {
    pub channel_id: String,
    pub start_utc: i64,
    pub stop_utc: i64,
    pub title: String,
    pub description: String,
}
#[derive(Debug, Default)]
pub struct GuideStats { pub channels: usize, pub programmes: usize, pub skipped: usize }

pub fn xmltv_timestamp(input: &str) -> Option<i64> {
    let mut parts=input.split_whitespace();
    let digits=parts.next()?;
    if digits.len()<14 || !digits[..14].bytes().all(|x|x.is_ascii_digit()) { return None; }
    let naive=NaiveDateTime::parse_from_str(&digits[..14],"%Y%m%d%H%M%S").ok()?;
    let Some(offset)=parts.next() else { return Some(naive.and_utc().timestamp()); };
    if offset.len()!=5 { return None; }
    let sign = match offset.as_bytes()[0] { b'+' => 1, b'-' => -1, _ => return None };
    let hours=offset[1..3].parse::<i32>().ok()?;
    let minutes=offset[3..5].parse::<i32>().ok()?;
    if hours>23 || minutes>59 {return None;}
    let off=FixedOffset::east_opt(sign*(hours*3600+minutes*60))?;
    Some(off.from_local_datetime(&naive).single()?.timestamp())
}
fn xml_attr(event:&quick_xml::events::BytesStart<'_>, key: &[u8]) -> Result<String, Box<dyn Error>> {
    for pair in event.attributes() {
        let value=pair?;
        if value.key.as_ref()==key {
            return Ok(value.unescape_value()?.into_owned());
        }
    }
    Ok(String::new())
}
/// Streaming parser. No provider data is invented for absent channels.
/// Callers can insert into a single SQLite transaction and roll back on error.
pub fn stream_xmltv<R: BufRead, FC: FnMut(Channel), FP: FnMut(Programme)>(
    input: R, mut channel_sink: FC, mut programme_sink: FP,
) -> Result<GuideStats, Box<dyn Error>> {
    let mut xml=Reader::from_reader(input);
    xml.config_mut().trim_text(true);
    let mut buffer=Vec::with_capacity(16*1024);
    let mut channel: Option<Channel> = None;
    let mut programme: Option<Programme> = None;
    let mut field: Vec<u8>=Vec::new();
    let mut stats=GuideStats::default();
    loop {
        match xml.read_event_into(&mut buffer)? {
            Event::Start(e) => match e.name().as_ref() {
                b"channel" => channel=Some(Channel {
                    id:xml_attr(&e,b"id")?,display_name:String::new()
                }),
                b"programme" => programme=Some(Programme {
                    channel_id:xml_attr(&e,b"channel")?,
                    start_utc:xmltv_timestamp(&xml_attr(&e,b"start")?).unwrap_or(0),
                    stop_utc:xmltv_timestamp(&xml_attr(&e,b"stop")?).unwrap_or(0),
                    title:String::new(),description:String::new()
                }),
                b"display-name" | b"title" | b"desc" => field=e.name().as_ref().to_vec(),
                _=>{}
            },
            Event::Text(t) => {
                let value=t.unescape()?.into_owned();
                match field.as_slice() {
                    b"display-name" if channel.is_some() => {
                        let c=channel.as_mut().unwrap();
                        if c.display_name.is_empty() { c.display_name=value; }
                    },
                    b"title" if programme.is_some() => {
                        let p=programme.as_mut().unwrap();
                        if p.title.is_empty() { p.title=value; }
                    },
                    b"desc" if programme.is_some() => {
                        let p=programme.as_mut().unwrap();
                        if p.description.is_empty() { p.description=value; }
                    },
                    _=>{}
                }
            },
            Event::End(e) => match e.name().as_ref() {
                b"channel" => if let Some(mut c)=channel.take() {
                    if c.display_name.is_empty() { c.display_name=c.id.clone(); }
                    if !c.id.is_empty() { channel_sink(c); stats.channels+=1; }
                },
                b"programme" => if let Some(p)=programme.take() {
                    if !p.channel_id.is_empty() && !p.title.is_empty()
                        && p.start_utc>0 && p.stop_utc>p.start_utc {
                        programme_sink(p); stats.programmes+=1;
                    } else { stats.skipped+=1; }
                },
                b"display-name" | b"title" | b"desc" => field.clear(),
                _=>{}
            },
            Event::Eof => break,
            _=>{}
        }
        buffer.clear();
    }
    Ok(stats)
}
pub fn now_next<'a>(items: &'a [Programme], channel_id: &str, now_utc: i64) -> (Option<&'a Programme>,Option<&'a Programme>) {
    let mut current=None;
    let mut next=None;
    for p in items.iter().filter(|p|p.channel_id==channel_id) {
        if p.start_utc<=now_utc && now_utc<p.stop_utc {
            if current.is_none_or(|old: &Programme| old.start_utc<p.start_utc) { current=Some(p); }
        } else if p.start_utc>now_utc &&
            next.is_none_or(|old: &Programme| old.start_utc>p.start_utc) {next=Some(p);}
    }
    (current,next)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn xmltv_tz_and_schedule() {
        let sample=r#"<tv>
<channel id="espn.us"><display-name>ESPN</display-name></channel>
<programme channel="espn.us" start="20261108120000 -0500" stop="20261108130000 -0500"><title>Live Sports</title><desc>Game &amp; highlights</desc></programme>
<programme channel="espn.us" start="20261108130000 -0500" stop="20261108140000 -0500"><title>SportsCenter</title></programme>
</tv>"#;
        let mut channels=Vec::new();let mut shows=Vec::new();
        let stats=stream_xmltv(sample.as_bytes(),|c|channels.push(c),|p|shows.push(p)).unwrap();
        assert_eq!(stats.channels,1);assert_eq!(stats.programmes,2);
        assert_eq!(shows[0].description,"Game & highlights");
        let now=xmltv_timestamp("20261108123000 -0500").unwrap();
        let (cur,next)=now_next(&shows,"espn.us",now);
        assert_eq!(cur.unwrap().title,"Live Sports");
        assert_eq!(next.unwrap().title,"SportsCenter");
    }
    #[test] fn invalid_time_not_fabricated() {
        assert!(xmltv_timestamp("unknown").is_none());
        assert!(xmltv_timestamp("20261108120000 +9900").is_none());
    }
}
