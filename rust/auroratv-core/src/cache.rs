//! Atomic SQLite cache. Provider credentials never go into this database.
//! Raw M3U links must be sealed by Android's credential vault first.
use crate::catalog::{MediaItem, MediaKind};
use crate::guide::{Channel, Programme};
use rusqlite::{params, Connection, OptionalExtension};
use std::error::Error;
use std::path::Path;

pub struct Cache { db: Connection }

impl Cache {
    pub fn open(path: impl AsRef<Path>) -> Result<Self, rusqlite::Error> {
        let db=Connection::open(path)?;
        db.pragma_update(None,"journal_mode","WAL")?;
        Self::initialize(db)
    }
    pub fn memory() -> Result<Self, rusqlite::Error> { Self::initialize(Connection::open_in_memory()?) }
    fn initialize(db: Connection) -> Result<Self, rusqlite::Error> {
        db.execute_batch("
            PRAGMA busy_timeout=5000;
            PRAGMA foreign_keys=ON;
            CREATE TABLE IF NOT EXISTS media(
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                category TEXT NOT NULL,
                kind TEXT NOT NULL,
                stream_ref TEXT NOT NULL,
                epg_id TEXT NOT NULL DEFAULT '',
                language TEXT NOT NULL DEFAULT '',
                artwork TEXT NOT NULL DEFAULT '',
                release_year INTEGER,
                rating REAL,
                added_at INTEGER
            );
            CREATE INDEX IF NOT EXISTS media_kind_name ON media(kind,name COLLATE NOCASE);
            CREATE INDEX IF NOT EXISTS media_category ON media(kind,category);
            CREATE TABLE IF NOT EXISTS preferences(
                id TEXT PRIMARY KEY,
                hidden INTEGER NOT NULL DEFAULT 0,
                favorite INTEGER NOT NULL DEFAULT 0,
                progress_ms INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS epg_channels(
                source TEXT NOT NULL,
                id TEXT NOT NULL,
                display_name TEXT NOT NULL,
                PRIMARY KEY(source,id)
            );
            CREATE TABLE IF NOT EXISTS programmes(
                source TEXT NOT NULL,
                channel TEXT NOT NULL,
                starts INTEGER NOT NULL,
                ends INTEGER NOT NULL,
                title TEXT NOT NULL,
                description TEXT NOT NULL,
                PRIMARY KEY(source,channel,starts)
            );
            CREATE INDEX IF NOT EXISTS epg_now ON programmes(source,channel,ends,starts);
            CREATE TABLE IF NOT EXISTS epg_manual(
                media_id TEXT PRIMARY KEY,
                source TEXT NOT NULL,
                channel TEXT NOT NULL
            );
        ")?;
        Ok(Self{db})
    }
    pub fn replace_catalog(&mut self, items: &[MediaItem]) -> Result<usize, Box<dyn Error>> {
        // Never persist a raw credential-bearing HTTP stream URL from M3U.
        // The caller must seal it using the Android Keystore-backed vault first.
        if items.iter().any(|item| !item.stream_ref.starts_with("aurora-xtream:")
            && !item.stream_ref.starts_with("vault:")) {
            return Err("A raw M3U URL must be encrypted before caching".into());
        }
        let tx=self.db.transaction()?;
        tx.execute("DELETE FROM media",[])?;
        {
            let mut insert=tx.prepare_cached("INSERT INTO media(id,name,category,kind,stream_ref,epg_id,language,artwork,release_year,rating,added_at)
                VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11)")?;
            for item in items {
                insert.execute(params![item.id,item.name,item.category,item.kind.as_str(),
                    item.stream_ref,item.epg_id,item.language,item.artwork,item.release_year,item.rating,item.added_at])?;
            }
        }
        tx.commit()?;
        Ok(items.len())
    }
    pub fn count(&self) -> Result<i64, rusqlite::Error> {
        self.db.query_row("SELECT COUNT(*) FROM media",[],|row|row.get(0))
    }
    pub fn set_hidden(&self, id:&str, hidden:bool) -> Result<(),rusqlite::Error> {
        self.db.execute("INSERT INTO preferences(id,hidden) VALUES (?1,?2)
            ON CONFLICT(id) DO UPDATE SET hidden=excluded.hidden",params![id,hidden as i32])?;
        Ok(())
    }
    pub fn set_favorite(&self, id:&str, favorite:bool) -> Result<(),rusqlite::Error> {
        self.db.execute("INSERT INTO preferences(id,favorite) VALUES (?1,?2)
            ON CONFLICT(id) DO UPDATE SET favorite=excluded.favorite",params![id,favorite as i32])?;
        Ok(())
    }
    pub fn set_progress(&self, id:&str, ms:i64) -> Result<(),rusqlite::Error> {
        self.db.execute("INSERT INTO preferences(id,progress_ms) VALUES (?1,?2)
            ON CONFLICT(id) DO UPDATE SET progress_ms=excluded.progress_ms",
            params![id,ms.max(0)])?;
        Ok(())
    }
    pub fn preference(&self,id:&str)->Result<(bool,bool,i64),rusqlite::Error> {
        Ok(self.db.query_row("SELECT hidden,favorite,progress_ms FROM preferences WHERE id=?1",
            params![id],|row|Ok((row.get::<_,i64>(0)?!=0,row.get::<_,i64>(1)?!=0,row.get(2)?)))
            .optional()?.unwrap_or((false,false,0)))
    }
    pub fn search(&self, needle:&str, kind:MediaKind, limit:usize) -> Result<Vec<MediaItem>,rusqlite::Error> {
        let mut statement=self.db.prepare("SELECT id,name,category,kind,stream_ref,epg_id,language,
            artwork,release_year,rating,added_at FROM media
            WHERE kind=?1 AND instr(lower(name),lower(?2))>0
            AND COALESCE((SELECT hidden FROM preferences WHERE preferences.id=media.id),0)=0
            ORDER BY name COLLATE NOCASE LIMIT ?3")?;
        let rows=statement.query_map(params![kind.as_str(),needle,limit.clamp(1,500) as i64],|row| {
            let raw_kind:String=row.get(3)?;
            let kind=match raw_kind.as_str() {
                "live"=>MediaKind::Live,"movie"=>MediaKind::Movie,
                "series"=>MediaKind::Series,_=>MediaKind::Episode
            };
            Ok(MediaItem{
                id:row.get(0)?,name:row.get(1)?,category:row.get(2)?,kind,
                stream_ref:row.get(4)?,epg_id:row.get(5)?,
                language:row.get(6)?,artwork:row.get(7)?,
                release_year:row.get(8)?,rating:row.get(9)?,added_at:row.get(10)?
            })
        })?;
        rows.collect()
    }
    pub fn replace_guide(&mut self, source:&str, channels:&[Channel], programmes:&[Programme]) -> Result<(),rusqlite::Error> {
        let tx=self.db.transaction()?;
        tx.execute("DELETE FROM programmes WHERE source=?1",params![source])?;
        tx.execute("DELETE FROM epg_channels WHERE source=?1",params![source])?;
        {
            let mut ch=tx.prepare_cached("INSERT OR REPLACE INTO epg_channels(source,id,display_name) VALUES (?1,?2,?3)")?;
            for item in channels {ch.execute(params![source,item.id,item.display_name])?;}
        }
        {
            let mut pg=tx.prepare_cached("INSERT OR REPLACE INTO programmes(source,channel,starts,ends,title,description)
                VALUES (?1,?2,?3,?4,?5,?6)")?;
            for item in programmes {
                pg.execute(params![source,item.channel_id,item.start_utc,item.stop_utc,item.title,item.description])?;
            }
        }
        tx.commit()
    }
    pub fn set_guide_mapping(&self,media_id:&str,source:&str,channel:&str)->Result<(),rusqlite::Error>{
        self.db.execute("INSERT INTO epg_manual(media_id,source,channel) VALUES (?1,?2,?3)
            ON CONFLICT(media_id) DO UPDATE SET source=excluded.source,channel=excluded.channel",
            params![media_id,source,channel])?;
        Ok(())
    }
    pub fn guide_mapping(&self, media_id:&str)->Result<Option<(String,String)>,rusqlite::Error>{
        self.db.query_row("SELECT source,channel FROM epg_manual WHERE media_id=?1",params![media_id],
            |row|Ok((row.get(0)?,row.get(1)?))).optional()
    }
    pub fn now_next(&self,source:&str,channel:&str,now:i64)
        -> Result<(Option<Programme>,Option<Programme>),rusqlite::Error> {
        let get=|predicate:&str| -> Result<Option<Programme>,rusqlite::Error> {
            let sql=if predicate=="now" {
                "SELECT channel,starts,ends,title,description FROM programmes
                    WHERE source=?1 AND channel=?2 AND starts<=?3 AND ends>?3
                    ORDER BY starts DESC LIMIT 1"
            } else {
                "SELECT channel,starts,ends,title,description FROM programmes
                    WHERE source=?1 AND channel=?2 AND starts>?3 ORDER BY starts ASC LIMIT 1"
            };
            self.db.query_row(sql,params![source,channel,now],|row|Ok(Programme {
                channel_id:row.get(0)?,start_utc:row.get(1)?,stop_utc:row.get(2)?,
                title:row.get(3)?,description:row.get(4)?
            })).optional()
        };
        Ok((get("now")?,get("next")?))
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    fn demo(name:&str)->MediaItem {
        MediaItem{
            id:name.into(),name:name.into(),category:"US".into(),kind:MediaKind::Live,
            stream_ref:"aurora-xtream:live:5:ts".into(),epg_id:"espn.us".into(),
            language:"en".into(),artwork:String::new(),
            release_year:None,rating:None,added_at:None,
        }
    }
    #[test] fn atomic_catalog_and_retained_preferences() {
        let mut cache=Cache::memory().unwrap();
        cache.replace_catalog(&[demo("ESPN"),demo("CNN")]).unwrap();
        cache.set_hidden("ESPN",true).unwrap();
        cache.set_favorite("CNN",true).unwrap();
        assert_eq!(cache.search("",MediaKind::Live,100).unwrap().len(),1);
        cache.replace_catalog(&[demo("CNN"),demo("ESPN")]).unwrap();
        assert_eq!(cache.preference("ESPN").unwrap().0,true);
        assert_eq!(cache.preference("CNN").unwrap().1,true);
        let mut bad=demo("BAD");
        bad.stream_ref="https://tv.example/get.php?username=secret".into();
        assert!(cache.replace_catalog(&[bad]).is_err());
        assert_eq!(cache.count().unwrap(),2);
    }
    #[test] fn guide_and_mapping() {
        let mut cache=Cache::memory().unwrap();
        cache.replace_guide("provider",&[Channel{id:"espn.us".into(),display_name:"ESPN".into()}],
            &[Programme{channel_id:"espn.us".into(),start_utc:100,stop_utc:200,
              title:"Match".into(),description:String::new()}]).unwrap();
        cache.set_guide_mapping("media1","provider","espn.us").unwrap();
        assert_eq!(cache.guide_mapping("media1").unwrap().unwrap().1,"espn.us");
        assert_eq!(cache.now_next("provider","espn.us",150).unwrap().0.unwrap().title,"Match");
    }
}
