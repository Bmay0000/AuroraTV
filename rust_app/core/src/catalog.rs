use crate::{model::{Entry,Kind,Programme}, Result};
use rusqlite::{params,Connection,OptionalExtension};
use std::{path::Path,sync::{Arc,Mutex}};
/// SQLite-backed library. All reads are indexed and results are bounded.
/// UI threads should call these functions from a worker, never from rendering.
#[derive(Clone)]
pub struct Catalog { db: Arc<Mutex<Connection>> }
impl Catalog {
    pub fn open(path: impl AsRef<Path>) -> Result<Self> {
        let connection=Connection::open(path)?;
        Self::from_connection(connection)
    }
    pub fn memory() -> Result<Self> { Self::from_connection(Connection::open_in_memory()?) }
    fn from_connection(c: Connection) -> Result<Self> {
        c.execute_batch(r#"
            PRAGMA journal_mode=WAL;
            PRAGMA busy_timeout=6000;
            CREATE TABLE IF NOT EXISTS media (
                id TEXT PRIMARY KEY, kind TEXT NOT NULL, source_id TEXT NOT NULL,
                title TEXT NOT NULL, category TEXT NOT NULL,
                favorite INTEGER NOT NULL DEFAULT 0,
                hidden INTEGER NOT NULL DEFAULT 0,
                json TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS media_kind_title ON media(kind,title COLLATE NOCASE);
            CREATE INDEX IF NOT EXISTS media_kind_cat ON media(kind,category);
            CREATE INDEX IF NOT EXISTS media_favorite ON media(favorite,kind);
            CREATE TABLE IF NOT EXISTS history (
                id TEXT PRIMARY KEY, visited INTEGER NOT NULL, position INTEGER NOT NULL DEFAULT 0
            );
            CREATE INDEX IF NOT EXISTS history_recent ON history(visited DESC);
            CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY,value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS epg (
                channel TEXT NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL,
                title TEXT NOT NULL, description TEXT NOT NULL DEFAULT '',
                PRIMARY KEY(channel,start)
            );
            CREATE INDEX IF NOT EXISTS epg_window ON epg(channel,start,end);
        "#)?;
        Ok(Self {db: Arc::new(Mutex::new(c))})
    }
    fn lock(&self) -> std::sync::MutexGuard<'_,Connection> {
        self.db.lock().unwrap_or_else(|poisoned|poisoned.into_inner())
    }
    pub fn import(&self, items: &[Entry]) -> Result<usize> {
        let mut c=self.lock();
        let tx=c.transaction()?;
        {
            let mut statement=tx.prepare(r#"
                INSERT INTO media(id,kind,source_id,title,category,json,favorite,hidden)
                VALUES(?1,?2,?3,?4,?5,?6,?7,?8)
                ON CONFLICT(id) DO UPDATE SET
                    kind=excluded.kind,source_id=excluded.source_id,
                    title=excluded.title,category=excluded.category,
                    json=excluded.json
            "#)?;
            for item in items {
                statement.execute(params![item.id,item.kind.as_str(),item.source_id,
                    item.title,item.category,serde_json::to_string(item)?,
                    item.favorite as i64,item.hidden as i64])?;
            }
        }
        tx.commit()?;
        Ok(items.len())
    }
    pub fn list(&self, kind:Option<Kind>, search:&str, limit:usize,
                favorites_only:bool) -> Result<Vec<Entry>> {
        let c=self.lock();
        let pattern=format!("%{}%",search.trim().replace('%',"\\%").replace('_',"\\_"));
        let mut stmt=c.prepare(r#"SELECT json,favorite,hidden FROM media
            WHERE hidden=0
             AND (?1='' OR kind=?1)
             AND (?2='' OR title LIKE ?3 ESCAPE '\')
             AND (?4=0 OR favorite=1)
            ORDER BY favorite DESC,title COLLATE NOCASE LIMIT ?5"#)?;
        let rows=stmt.query_map(params![kind.map_or("",Kind::as_str),search.trim(),pattern,
           if favorites_only {1} else {0},limit.clamp(1,500) as i64],|r|{
            let json:String=r.get(0)?;
            let favorite:i64=r.get(1)?;
            let hidden:i64=r.get(2)?;
            Ok((json,favorite,hidden))
        })?;
        let mut out=Vec::new();
        for row in rows {
            let (json,favorite,hidden)=row?;
            let mut entry:Entry=serde_json::from_str(&json)?;
            entry.favorite=favorite!=0; entry.hidden=hidden!=0;
            out.push(entry);
        }
        Ok(out)
    }
    pub fn by_id(&self,id:&str)-> Result<Option<Entry>> {
        let c=self.lock();
        let row:Option<(String,i64,i64)>=c.query_row(
          "SELECT json,favorite,hidden FROM media WHERE id=?1",params![id],
          |r|Ok((r.get(0)?,r.get(1)?,r.get(2)?))).optional()?;
        Ok(row.map(|(s,f,h)|{
            let mut item:Entry=serde_json::from_str(&s).unwrap_or_else(|_|
              Entry::empty(Kind::Movie,id,"Invalid catalog entry"));
            item.favorite=f!=0;item.hidden=h!=0;item
        }))
    }
    pub fn favorite(&self,id:&str,on:bool)->Result<()> {
        self.lock().execute("UPDATE media SET favorite=?2 WHERE id=?1",params![id,on])?;
        Ok(())
    }
    pub fn hide(&self,id:&str,on:bool)->Result<()> {
        self.lock().execute("UPDATE media SET hidden=?2 WHERE id=?1",params![id,on])?;
        Ok(())
    }
    pub fn hide_category(&self,category:&str,kind:Kind,on:bool)->Result<usize> {
        Ok(self.lock().execute(
          "UPDATE media SET hidden=?1 WHERE category=?2 AND kind=?3",
          params![on,category,kind.as_str()])?)
    }
    pub fn recently_watched(&self, limit:usize)->Result<Vec<Entry>> {
        let c=self.lock();
        let mut stmt=c.prepare(r#"SELECT m.json,m.favorite,m.hidden
          FROM history h JOIN media m ON m.id=h.id WHERE m.hidden=0
          ORDER BY h.visited DESC LIMIT ?1"#)?;
        let mut rows=stmt.query(params![limit.clamp(1,50) as i64])?;
        let mut result=Vec::new();
        while let Some(row)=rows.next()? {
            let json:String=row.get(0)?;
            let mut item:Entry=serde_json::from_str(&json)?;
            item.favorite=row.get::<_,i64>(1)?!=0;
            item.hidden=row.get::<_,i64>(2)?!=0;
            result.push(item);
        }
        Ok(result)
    }
    pub fn watched(&self,id:&str,position:i64)->Result<()> {
        let when=chrono::Utc::now().timestamp();
        self.lock().execute(r#"INSERT INTO history (id,visited,position)
          VALUES(?1,?2,?3) ON CONFLICT(id) DO UPDATE SET
          visited=excluded.visited,position=excluded.position"#,
          params![id,when,position])?;
        Ok(())
    }
    pub fn setting(&self,key:&str)->Result<Option<String>> {
        Ok(self.lock().query_row("SELECT value FROM settings WHERE key=?1",
          [key],|r|r.get(0)).optional()?)
    }
    pub fn save_setting(&self,key:&str,value:&str)->Result<()> {
        self.lock().execute(r#"INSERT INTO settings(key,value) VALUES(?1,?2)
          ON CONFLICT(key) DO UPDATE SET value=excluded.value"#,params![key,value])?;
        Ok(())
    }
    pub fn write_epg(&self,items:&[Programme])->Result<usize> {
        let mut c=self.lock();let tx=c.transaction()?;
        {
            let mut stmt=tx.prepare(r#"INSERT INTO epg(channel,start,end,title,description)
              VALUES(?1,?2,?3,?4,?5) ON CONFLICT(channel,start)
              DO UPDATE SET title=excluded.title,end=excluded.end,
              description=excluded.description"#)?;
            for p in items {
                stmt.execute(params![p.channel,p.start_unix,p.end_unix,p.title,p.description])?;
            }
        }
        tx.execute("DELETE FROM epg WHERE end < ?1",
          [chrono::Utc::now().timestamp() - 3600*48])?;
        tx.commit()?;Ok(items.len())
    }
    pub fn epg_window(&self, channels:&[String],begin:i64,end:i64)->Result<Vec<Programme>> {
        if channels.is_empty(){return Ok(vec![]);}
        let c=self.lock();
        let mut q=c.prepare(r#"SELECT channel,start,end,title,description FROM epg
          WHERE channel=?1 AND start<?3 AND end>?2 ORDER BY start LIMIT 15"#)?;
        let mut result=Vec::new();
        for channel in channels.iter().take(24) {
            let rows=q.query_map(params![channel,begin,end],|r|Ok(Programme{
                channel:r.get(0)?,start_unix:r.get(1)?,end_unix:r.get(2)?,
                title:r.get(3)?,description:r.get(4)?
            }))?;
            for item in rows { result.push(item?); }
        }
        Ok(result)
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn persistent_flags_survive_reimports() {
        let db=Catalog::memory().unwrap();
        let mut m=Entry::empty(Kind::Movie,"m1","Obsession");
        m.source_id="x".into();
        db.import(&[m.clone()]).unwrap();
        db.favorite("m1",true).unwrap();
        db.import(&[m]).unwrap();
        assert_eq!(db.list(Some(Kind::Movie),"obs",10,true).unwrap().len(),1);
        db.hide("m1",true).unwrap();
        assert!(db.list(Some(Kind::Movie),"",10,false).unwrap().is_empty());
    }
    #[test] fn epg_is_bounded_by_time() {
        let db=Catalog::memory().unwrap();
        db.write_epg(&[Programme{channel:"cnn".into(),
          start_unix:2_000_000_000,end_unix:2_000_003_600,title:"News".into(),
          description:String::new()}]).unwrap();
        assert_eq!(db.epg_window(&["cnn".into()],2_000_000_010,2_000_000_100).unwrap().len(),1);
        assert!(db.epg_window(&["cnn".into()],2_000_005_000,2_000_005_100).unwrap().is_empty());
    }
}
