use crate::{model::{Entry,Episode,Kind,Programme}, AuroraError,Result};
use serde::{Serialize,Deserialize};
use serde_json::{Value,Map};
use std::{collections::HashMap,io::Read,time::Duration};
use url::Url;
use base64::Engine;
#[derive(Debug,Clone,Serialize,Deserialize)]
pub struct Xtream {
    pub base:String,
    pub username:String,
    pub password:String,
}
impl Xtream {
    pub fn new(base:&str,username:&str,password:&str)->Result<Self>{
        let parsed=Url::parse(base)?;
        if !matches!(parsed.scheme(),"http"|"https") || parsed.host_str().is_none(){
            return Err(AuroraError::Invalid("Xtream server must be HTTP(S)".into()));
        }
        if username.is_empty() || password.is_empty(){
            return Err(AuroraError::Invalid("Xtream credentials required".into()));
        }
        let clean=parsed.as_str().trim_end_matches('/').to_string();
        Ok(Self{base:clean,username:username.into(),password:password.into()})
    }
    pub fn api_url(&self,action:&str,params:&[(&str,&str)]) -> Result<String> {
        let mut url=Url::parse(&format!("{}/player_api.php",self.base))?;
        url.query_pairs_mut().append_pair("username",&self.username)
            .append_pair("password",&self.password)
            .append_pair("action",action)
            .extend_pairs(params.iter().copied());
        Ok(url.to_string())
    }
    pub fn xmltv_url(&self)->Result<String>{
        let mut url=Url::parse(&format!("{}/xmltv.php",self.base))?;
        url.query_pairs_mut().append_pair("username",&self.username)
            .append_pair("password",&self.password);
        Ok(url.into())
    }
    pub fn stream_url(&self,kind:Kind,id:&str,ext:&str)->String {
        let part=match kind{Kind::Live=>"live",Kind::Movie=>"movie",Kind::Series=>"series"};
        // The URL library escapes usernames, passwords, and path segments.
        let mut url=match Url::parse(&self.base){
            Ok(u)=>u,Err(_)=>return String::new()
        };
        if let Ok(mut segments)=url.path_segments_mut(){
            segments.pop_if_empty()
                .push(part).push(&self.username).push(&self.password)
                .push(&format!("{id}.{ext}"));
        }
        url.to_string()
    }
    pub fn json(&self,action:&str,params:&[(&str,&str)])->Result<Value>{
        let url=self.api_url(action,params)?;
        let response=ureq::get(&url).timeout(Duration::from_secs(25)).call()?;
        let mut bytes=Vec::new();
        response.into_reader().take(48_000_001).read_to_end(&mut bytes)
          .map_err(|_|AuroraError::Invalid("Xtream response read failed".into()))?;
        if bytes.len()>48_000_000{
            return Err(AuroraError::Invalid("Xtream catalog exceeds 48MB safety limit".into()));
        }
        Ok(serde_json::from_slice(&bytes)?)
    }
    pub fn authenticate(&self)->Result<bool>{
        let json=self.json("",&[])?;
        Ok(json.get("user_info").and_then(|x|x.get("auth"))
           .and_then(Value::as_i64)==Some(1))
    }
    fn categories(&self,kind:Kind)->HashMap<String,String>{
        let action=match kind{
            Kind::Live=>"get_live_categories",
            Kind::Movie=>"get_vod_categories",
            Kind::Series=>"get_series_categories",
        };
        self.json(action,&[]).ok().and_then(|j|j.as_array().cloned())
            .unwrap_or_default().iter().filter_map(|item|{
                let id=value_text(item,"category_id");
                let name=value_text(item,"category_name");
                if id.is_empty(){None}else{Some((id,name))}
            }).collect()
    }
    pub fn catalog(&self,source_id:&str,kind:Kind)->Result<Vec<Entry>>{
        let action=match kind{
            Kind::Live=>"get_live_streams",
            Kind::Movie=>"get_vod_streams",
            Kind::Series=>"get_series",
        };
        let cats=self.categories(kind);
        let data=self.json(action,&[])?;
        let rows=data.as_array()
            .ok_or_else(||AuroraError::Invalid("Xtream catalog must be an array".into()))?;
        let mut out=Vec::new();
        for row in rows.iter().take(180_000){
            let id=value_text(row,if kind==Kind::Series{"series_id"}else{"stream_id"});
            if id.is_empty(){continue;}
            let title=value_text(row,"name");
            if title.is_empty(){continue;}
            let mut item=Entry::empty(kind,format!("{source_id}:{}:{id}",kind.as_str()),title);
            item.stream_id=id;
            item.source_id=source_id.into();
            item.epg_id=value_text(row,"epg_channel_id");
            item.poster=if kind==Kind::Movie{value_text(row,"stream_icon")}else{
                let icon=value_text(row,"cover");
                if icon.is_empty(){value_text(row,"stream_icon")}else{icon}
            };
            item.direct_url=value_text(row,"direct_source");
            item.container=value_text(row,"container_extension");
            let category_id=value_text(row,"category_id");
            item.category=cats.get(&category_id).cloned()
                .unwrap_or_else(||category_id.clone());
            item.rating=row.get("rating_5based").or_else(||row.get("rating"))
                .and_then(|v|v.as_f64().or_else(||v.as_str().and_then(|v|v.parse().ok())))
                .unwrap_or(0.0) as f32;
            item.year=value_text(row,"year").parse().unwrap_or(0);
            out.push(item);
        }
        Ok(out)
    }
    pub fn episodes(&self,show:&Entry)->Result<Vec<Episode>>{
        let json=self.json("get_series_info",&[("series_id",&show.stream_id)])?;
        let mut out=Vec::new();
        let Some(collection)=json.get("episodes") else {return Ok(out)};
        let mut seasons:Vec<(String,&Vec<Value>)>=Vec::new();
        if let Some(map)=collection.as_object(){
            for (season,episodes) in map {
                if let Some(list)=episodes.as_array(){seasons.push((season.clone(),list));}
            }
        }else if let Some(list)=collection.as_array(){seasons.push(("1".into(),list));}
        for (season,list) in seasons {
            for (index,row) in list.iter().enumerate(){
                let id={
                    let primary=value_text(row,"id");
                    if primary.is_empty(){value_text(row,"stream_id")}else{primary}
                };
                if id.is_empty(){continue;}
                let num=value_text(row,"episode_num").parse::<u32>().unwrap_or(index as u32+1);
                let season_no=season.parse::<u32>().unwrap_or(0);
                let mut video=Entry::empty(Kind::Series,
                    format!("{}:episode:{id}",show.source_id),
                    {
                        let label=value_text(row,"title");
                        if label.is_empty(){format!("Episode {num}")}else{label}
                    });
                video.source_id=show.source_id.clone();
                video.stream_id=id;
                video.container=value_text(row,"container_extension");
                if video.container.is_empty(){video.container="mp4".into();}
                video.category=format!("Season {season_no}");
                video.poster=show.poster.clone();
                out.push(Episode{season:season_no,number:num,
                    title:video.title.clone(),duration_seconds:0,stream:video});
            }
        }
        out.sort_by_key(|e|(e.season,e.number));
        Ok(out)
    }
    pub fn short_epg(&self,stream_id:&str)->Result<Vec<Programme>>{
        let json=self.json("get_short_epg",&[("stream_id",stream_id)])?;
        let rows=json.get("epg_listings").and_then(Value::as_array);
        let mut out=Vec::new();
        for row in rows.into_iter().flatten().take(20){
            let start=value_text(row,"start_timestamp").parse().unwrap_or(0);
            let end=value_text(row,"stop_timestamp").parse().unwrap_or(0);
            if end<=start{continue;}
            let mut title=value_text(row,"title");
            let mut desc=value_text(row,"description");
            title=decode_base64_or_plain(title);
            desc=decode_base64_or_plain(desc);
            out.push(Programme{channel:format!("stream:{stream_id}"),
                title,description:desc,start_unix:start,end_unix:end});
        }
        Ok(out)
    }
}
fn value_text(obj:&Value,key:&str)->String{
    let Some(value)=obj.get(key) else{return String::new()};
    match value {
        Value::String(s)=>s.clone(),
        Value::Number(n)=>n.to_string(),
        _=>String::new(),
    }
}
fn decode_base64_or_plain(text:String)->String{
    if text.is_empty(){return text;}
    base64::engine::general_purpose::STANDARD.decode(&text)
        .ok().and_then(|bytes|String::from_utf8(bytes).ok())
        .filter(|result|result.chars().all(|c|!c.is_control()||c=='\n'))
        .unwrap_or(text)
}
#[cfg(test)]
mod tests{
    use super::*;
    #[test] fn source_ids_are_kept_not_faked(){
        let src=Xtream::new("http://example.org:8080","u","p").unwrap();
        let url=src.stream_url(Kind::Movie,"891","mkv");
        assert!(url.ends_with("/movie/u/p/891.mkv"));
        assert!(src.api_url("get_vod_info",&[("vod_id","891")]).unwrap()
            .contains("action=get_vod_info"));
    }
    #[test] fn rejects_non_http_and_missing_credentials(){
        assert!(Xtream::new("file:///etc/passwd","u","p").is_err());
        assert!(Xtream::new("https://x.org","","p").is_err());
    }
}
