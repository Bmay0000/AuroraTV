use crate::{model::Programme, AuroraError, Result};
use quick_xml::{Reader,events::Event};
use std::{io::Read,time::Duration};
use chrono::{NaiveDateTime, TimeZone, Utc};
const MAX_PROGRAMMES:usize=160_000;
pub fn parse_xmltv(data:&str)->Result<Vec<Programme>>{
    let mut reader=Reader::from_str(data);
    reader.config_mut().trim_text(true);
    let mut output=Vec::new();
    let mut current:Option<Programme>=None;
    let mut field="";
    loop {
        match reader.read_event() {
            Ok(Event::Start(e))=>{
                let name=e.name().as_ref().to_vec();
                match name.as_slice() {
                    b"programme"=>{
                        let mut p=Programme::default();
                        for a in e.attributes().flatten() {
                            let value=String::from_utf8_lossy(a.value.as_ref()).into_owned();
                            match a.key.as_ref() {
                                b"channel"=>p.channel=value,
                                b"start"=>p.start_unix=parse_xmltv_time(&value).unwrap_or(0),
                                b"stop"=>p.end_unix=parse_xmltv_time(&value).unwrap_or(0),
                                _=>{}
                            }
                        }
                        current=Some(p);
                    }
                    b"title"=>field="title",
                    b"desc"=>field="desc",
                    _=>field="",
                }
            }
            Ok(Event::Text(text))=>{
                if let Some(p)=current.as_mut() {
                    let value=text.unescape().map(|s|s.to_string()).unwrap_or_default();
                    if field=="title" {p.title.push_str(&value);}
                    if field=="desc" {p.description.push_str(&value);}
                }
            }
            Ok(Event::End(e))=>{
                if e.name().as_ref()==b"programme" {
                    if let Some(p)=current.take() {
                        if !p.channel.is_empty() && !p.title.is_empty()
                          && p.end_unix>p.start_unix { output.push(p); }
                    }
                    if output.len()>=MAX_PROGRAMMES {break;}
                }
                field="";
            }
            Ok(Event::Eof)=>break,
            Err(err)=>return Err(AuroraError::Xml(err)),
            _=>{}
        }
    }
    Ok(output)
}
fn parse_xmltv_time(text:&str)->Option<i64>{
    let s=text.trim();
    if s.len()<14 {return None;}
    let dt=NaiveDateTime::parse_from_str(&s[..14],"%Y%m%d%H%M%S").ok()?;
    let mut timestamp=Utc.from_utc_datetime(&dt).timestamp();
    if s.len()>=20 {
        let tz=s[15..].trim();
        let bytes=tz.as_bytes();
        if bytes.len()>=5 && (bytes[0]==b'+'||bytes[0]==b'-') {
            let h=tz[1..3].parse::<i64>().ok()?;
            let m=tz[3..5].parse::<i64>().ok()?;
            let offset=(h*60+m)*60;
            timestamp-=if bytes[0]==b'+'{offset}else{-offset};
        }
    }
    Some(timestamp)
}
/// Bounded network XMLTV download. Every byte cap is enforced before parse.
pub fn fetch_xmltv(url:&str)->Result<Vec<Programme>>{
    let response=ureq::get(url).timeout(Duration::from_secs(35)).call()?;
    let mut bytes=Vec::new();
    response.into_reader().take(30_000_001).read_to_end(&mut bytes)
        .map_err(|e|AuroraError::Invalid(format!("EPG read: {e}")))?;
    if bytes.len()>30_000_000 {
        return Err(AuroraError::Invalid("EPG compressed file too large".into()));
    }
    let data=if bytes.starts_with(&[0x1f,0x8b]) {
        let mut decoder=flate2::read::GzDecoder::new(bytes.as_slice());
        let mut expanded=Vec::new();
        decoder.by_ref().take(75_000_001).read_to_end(&mut expanded)
            .map_err(|e|AuroraError::Invalid(format!("EPG gzip: {e}")))?;
        if expanded.len()>75_000_000 {
            return Err(AuroraError::Invalid("EPG XML exceeds safe expansion limit".into()));
        }
        expanded
    } else {bytes};
    let text=String::from_utf8(data)
        .map_err(|_|AuroraError::Invalid("EPG XML not UTF-8".into()))?;
    parse_xmltv(&text)
}
/// Enforce a single EPG network request at a time with an adjustable delay.
pub fn paced_channel_epg<T,F>(
    channel_ids:&[String],delay:Duration,limit:usize,mut fetch:F
)->Vec<(String,T)>
where F:FnMut(&str)->Result<Option<T>> {
    let mut out=Vec::new();
    for (i,id) in channel_ids.iter().take(limit.min(32)).enumerate(){
        if i>0 {std::thread::sleep(delay);}
        if let Ok(Some(item))=fetch(id){out.push((id.clone(),item));}
    }
    out
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn timezone_is_not_ignored(){
        let data=r#"<tv><programme start="20261108190000 +0100" stop="20261108200000 +0100" channel="cnn.us"><title>Evening News</title><desc>Live &amp; direct</desc></programme></tv>"#;
        let p=parse_xmltv(data).unwrap();
        assert_eq!(p.len(),1);
        assert_eq!(p[0].title,"Evening News");
        assert_eq!(p[0].description,"Live & direct");
        assert_eq!(p[0].end_unix-p[0].start_unix,3600);
        assert_eq!(p[0].start_unix,parse_xmltv_time("20261108180000 +0000").unwrap());
    }
    #[test] fn bounded_guide_requests(){
        let ids=(0..80).map(|i|i.to_string()).collect::<Vec<_>>();
        let mut count=0;
        let got=paced_channel_epg(&ids,Duration::ZERO,5,|_|{
            count+=1;Ok::<_,AuroraError>(Some(count))
        });
        assert_eq!(count,5);assert_eq!(got.len(),5);
    }
}
