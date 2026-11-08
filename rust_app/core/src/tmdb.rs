use crate::{model::{Entry,Kind,Metadata,CastMember},matcher::match_key,Result};
use serde_json::Value;
use std::{io::Read,time::Duration};
use url::Url;

/// TMDB is metadata only. It must never supply or alter a playback URL.
/// Titles and years must match before a provider entry is decorated.
pub struct Tmdb { pub api_key:String }
impl Tmdb {
    fn request(&self,path:&str,parameters:&[(&str,&str)])->Result<Value>{
        let mut uri=Url::parse(&format!("https://api.themoviedb.org/3/{path}"))?;
        uri.query_pairs_mut().append_pair("api_key",&self.api_key)
            .append_pair("language","en-US").extend_pairs(parameters.iter().copied());
        let response=ureq::get(uri.as_str()).timeout(Duration::from_secs(10)).call()?;
        let mut data=Vec::new();
        response.into_reader().take(2_000_001).read_to_end(&mut data)
            .map_err(|_|crate::AuroraError::Invalid("TMDB response interrupted".into()))?;
        if data.len()>2_000_000{
            return Err(crate::AuroraError::Invalid("TMDB response exceeds safe limit".into()));
        }
        Ok(serde_json::from_slice(&data)?)
    }
    pub fn lookup(&self,item:&Entry)->Result<Option<Metadata>>{
        if self.api_key.is_empty(){return Ok(None);}
        let kind=match item.kind{Kind::Series=>"tv",Kind::Movie=>"movie",Kind::Live=>return Ok(None)};
        let title=clean_title(&item.title);
        let data=self.request(&format!("search/{kind}"),&[("query",&title)])?;
        let target=match_key(&title);
        let found=data.get("results").and_then(Value::as_array)
            .and_then(|arr|arr.iter().take(20).find(|row|{
                let name=row.get(if kind=="movie"{"title"}else{"name"})
                    .and_then(Value::as_str).unwrap_or("");
                let release=row.get(if kind=="movie"{"release_date"}else{"first_air_date"})
                    .and_then(Value::as_str).unwrap_or("");
                match_key(name)==target && (item.year<=0||
                    release.get(0..4).and_then(|s|s.parse::<i32>().ok())==Some(item.year))
            }));
        let Some(row)=found else{return Ok(None)};
        let Some(tmdb_id)=row.get("id").and_then(Value::as_u64) else{return Ok(None)};
        let details=self.request(&format!("{kind}/{tmdb_id}"),
            &[("append_to_response","credits,recommendations,similar")])?;
        let mut m=Metadata{
            tmdb_id:Some(tmdb_id),title:title.clone(),
            overview:text(&details,"overview"),
            backdrop:image(&details,"backdrop_path","w1280"),
            poster:image(&details,"poster_path","w500"),
            rating:details.get("vote_average").and_then(Value::as_f64)
                .unwrap_or(0.0) as f32,
            runtime_minutes:details.get("runtime").and_then(Value::as_u64)
                .unwrap_or(0) as u32,
            release_year:item.year,
            ..Metadata::default()
        };
        if let Some(genres)=details.get("genres").and_then(Value::as_array){
            m.genres=genres.iter().filter_map(|v|v.get("name").and_then(Value::as_str))
                .take(5).map(str::to_string).collect();
        }
        if let Some(people)=details.pointer("/credits/cast").and_then(Value::as_array){
            m.cast=people.iter().take(16).map(|p|CastMember{
                name:text(p,"name"),character:text(p,"character"),
                photo:image(p,"profile_path","w185"),
            }).collect();
        }
        let recommendations=details.pointer("/recommendations/results")
            .and_then(Value::as_array)
            .or_else(||details.pointer("/similar/results").and_then(Value::as_array));
        if let Some(rows)=recommendations{
            m.related=rows.iter().filter(|r|
                r.get("original_language").and_then(Value::as_str)==Some("en"))
                .take(25).map(|r|(
                    text(r,if kind=="movie"{"title"}else{"name"}),
                    image(r,"poster_path","w342")
                )).filter(|(title,_)|!title.is_empty()).collect();
        }
        Ok(Some(m))
    }
    pub fn trending_titles(&self,kind:Kind)->Result<Vec<String>>{
        if self.api_key.is_empty(){return Ok(vec![])}
        let media=if kind==Kind::Series{"tv"}else{"movie"};
        let data=self.request(&format!("trending/{media}/week"),&[])?;
        Ok(data.get("results").and_then(Value::as_array).into_iter().flatten()
            .filter(|row|row.get("original_language").and_then(Value::as_str)==Some("en"))
            .take(32).filter_map(|row|row.get(if media=="tv"{"name"}else{"title"})
                .and_then(Value::as_str)).map(str::to_string).collect())
    }
}
fn text(v:&Value,key:&str)->String{
    v.get(key).and_then(Value::as_str).unwrap_or("").to_string()
}
fn image(v:&Value,key:&str,size:&str)->String{
    match v.get(key).and_then(Value::as_str){
        Some(p) if p.starts_with('/')=>format!("https://image.tmdb.org/t/p/{size}{p}"),
        _=>String::new(),
    }
}
pub fn clean_title(name:&str)->String{
    let mut title=name.to_string();
    if let Some(index)=title.rfind(" ("){
        let rest=&title[index+2..];
        if rest.len()>=5 && rest.as_bytes()[..4].iter().all(|b|b.is_ascii_digit())
            && rest.as_bytes()[4]==b')'{
            title.truncate(index);
        }
    }
    for suffix in [" - English 4K"," - English HD"," VOD"," 4K"," FHD"]{
        if let Some(t)=title.strip_suffix(suffix){title=t.into()}
    }
    title.trim().to_string()
}
#[cfg(test)]
mod tests{
    use super::*;
    #[test] fn movie_2026_title_does_not_contaminate_stream(){
        assert_eq!(clean_title("Obsession (2026)"),"Obsession");
        assert_eq!(clean_title("The Simpsons (1989)"),"The Simpsons");
    }
}
