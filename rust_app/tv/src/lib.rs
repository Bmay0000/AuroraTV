//! AuroraTV 3.0 — Slint UI controlled by Rust state and background workers.
//! Slint never performs network calls or large SQLite imports on its UI thread.
use aurora_core::{
    catalog::Catalog, matcher, m3u, model::{Entry,Kind,Episode,Metadata},
    playback, tmdb::Tmdb, xtream::Xtream,
};
use slint::{ComponentHandle,ModelRc,VecModel};
use std::{collections::HashMap, fs, io::Read, path::PathBuf,
          sync::{Arc,Mutex,atomic::{AtomicU64,Ordering}}, time::Duration};

slint::include_modules!();
#[cfg(target_os="android")] mod android;

#[derive(Default)]
struct UiData {
    tab: usize,
    selected: i32, // -1 = navigation bar; >= 0 = selected tile
    offset: usize,
    visible: Vec<Entry>,
    details: Option<Entry>,
    detail_info: Option<Metadata>,
    episodes: Option<Vec<Episode>>,
    search: String,
    connected: bool,
    source: Option<Xtream>,
    m3u_url: Option<String>,
    tmdb_key: String,
    status: String,
    guide_cache: HashMap<String,String>,
}
struct AppState {
    db: Catalog,
    ui: UiData,
    generation: Arc<AtomicU64>,
}
type State = Arc<Mutex<AppState>>;
fn lock(state:&State)->std::sync::MutexGuard<'_,AppState>{
    state.lock().unwrap_or_else(|poisoned|poisoned.into_inner())
}
fn data_dir()->PathBuf{
    #[cfg(target_os="android")] { return android::data_dir(); }
    #[cfg(not(target_os="android"))] {
        let base=std::env::var_os("XDG_DATA_HOME").map(PathBuf::from)
            .or_else(||std::env::var_os("HOME").map(|h|PathBuf::from(h).join(".local/share")))
            .unwrap_or_else(||std::env::temp_dir());
        base.join("AuroraTV")
    }
}
fn init_state()->Result<State,String>{
    let dir=data_dir();
    fs::create_dir_all(&dir).map_err(|e|e.to_string())?;
    let db=Catalog::open(dir.join("catalog.sqlite"))
        .map_err(|e|e.to_string())?;
    let source=db.setting("xtream_login").ok().flatten()
        .and_then(|s|serde_json::from_str::<Xtream>(&s).ok());
    let m3u_url=db.setting("m3u_url").ok().flatten();
    let tmdb_key=db.setting("tmdb_key").ok().flatten().unwrap_or_default();
    let connected=source.is_some()||m3u_url.is_some();
    Ok(Arc::new(Mutex::new(AppState{
        db,
        ui:UiData{
            connected,source,m3u_url,tmdb_key,selected:-1,
            status:if connected{"Restoring your IPTV library…".into()}
              else{"Connect an Xtream or M3U provider to start.".into()},
            ..UiData::default()
        },
        generation:Arc::new(AtomicU64::new(0)),
    })))
}
fn set_status(window:&AuroraWindow,message:&str){window.set_status_text(message.into());}
fn to_tile(item:&Entry,db_dir:&PathBuf)->MediaTile{
    let image_path=db_dir.join("posters").join(format!("{}.png",safe_key(&item.id)));
    let poster=if image_path.exists(){
        slint::Image::load_from_path(&image_path).unwrap_or_default()
    }else{slint::Image::default()};
    MediaTile{
        id:item.id.clone().into(),
        title:item.title.clone().into(),
        subtitle:format!("{}  •  {}",item.kind.as_str().to_uppercase(),item.category).into(),
        poster,
        kind:item.kind.as_str().into(),
    }
}
fn safe_key(s:&str)->String{
    // FNV-1a stable identifier, for nonsecret cache file names.
    let mut hash:u64=0xcbf29ce484222325;
    for byte in s.bytes(){hash^=u64::from(byte);hash=hash.wrapping_mul(0x100000001b3);}
    format!("{hash:016x}")
}
fn list_for_tab(db:&Catalog,data:&UiData)->Vec<Entry>{
    let result=match data.tab{
        0=> {
            let mut recent=db.recently_watched(50).unwrap_or_default();
            let mut movies=db.list(Some(Kind::Movie),"",180,false).unwrap_or_default();
            let mut shows=db.list(Some(Kind::Series),"",140,false).unwrap_or_default();
            recent.append(&mut movies);
            recent.append(&mut shows);
            let mut seen=std::collections::HashSet::new();
            recent.retain(|e|seen.insert(e.id.clone()));
            recent
        },
        1=>db.list(Some(Kind::Live),"",500,false).unwrap_or_default(),
        2=>db.list(Some(Kind::Movie),"",500,false).unwrap_or_default(),
        3=>db.list(Some(Kind::Series),"",500,false).unwrap_or_default(),
        4=>db.list(None,"",400,true).unwrap_or_default(),
        5=>db.list(None,&data.search,500,false).unwrap_or_default(),
        6=>db.list(None,"",500,false).unwrap_or_default(),
        _=>vec![],
    };
    result
}
fn render(window:&AuroraWindow,state:&State){
    let mut s=lock(state);
    let list=if let Some(episodes)=&s.ui.episodes {
        episodes.iter().map(|ep|ep.stream.clone()).collect::<Vec<_>>()
    }else{list_for_tab(&s.db,&s.ui)};
    let rows=list.len();
    if s.ui.offset>=rows {s.ui.offset=rows.saturating_sub(12);}
    // 12 tiles is intentional: no giant Flutter-style widget tree.
    let visible=list.into_iter().skip(s.ui.offset).take(12).collect::<Vec<_>>();
    let heading=match s.ui.tab {
        0=>"RECENTLY WATCHED & AVAILABLE NOW",
        1=>"LIVE TV · PROVIDER CHANNELS",
        2=>"MOVIES · AVAILABLE TO WATCH",
        3=>"TV SHOWS · AVAILABLE TO WATCH",
        4=>"MY LIST · FAVORITES",
        5=>"SEARCH RESULTS",
        6=>"EDIT LIBRARY · SELECT TO HIDE",
        _=>"SETTINGS",
    };
    s.ui.visible=visible.clone();
    let selected=s.ui.selected;
    let featured=if selected>=0{visible.get(selected as usize)}
                 else{visible.first()};
    let hero_title=featured.map(|e|e.title.as_str())
        .unwrap_or("Choose an IPTV channel, movie, or show.");
    let hero_subtitle=featured.map(|e|e.category.as_str())
        .unwrap_or("Import a provider or choose another section.");
    let detail=s.ui.details.clone();
    let detail_info=s.ui.detail_info.clone();
    let dir=data_dir();
    let row_one:Vec<MediaTile>=visible.iter().take(6).map(|e|to_tile(e,&dir)).collect();
    let row_two:Vec<MediaTile>=visible.iter().skip(6).map(|e|to_tile(e,&dir)).collect();
    window.set_first_row(ModelRc::new(VecModel::from(row_one)));
    window.set_second_row(ModelRc::new(VecModel::from(row_two)));
    window.set_selected_tab(s.ui.tab as i32);
    window.set_selected_index(selected);
    window.set_section_title(heading.into());
    window.set_hero_title(hero_title.into());
    window.set_hero_subtitle(hero_subtitle.into());
    window.set_status_text(format!("{}    •    {}–{} / {}",
        s.ui.status,if rows==0{0}else{s.ui.offset+1},s.ui.offset+visible.len(),rows).into());
    window.set_guide_state(if s.ui.tab==1{
        format!("EPG cached for {} stations",s.ui.guide_cache.len()).into()
    }else{"Rust engine · Indexed library".into()});
    window.set_login_visible(!s.ui.connected);
    window.set_details_visible(detail.is_some());
    window.set_search_visible(s.ui.tab==5 && s.ui.connected);
    if let Some(item)=detail {
        window.set_details_title(item.title.into());
        if let Some(m)=detail_info {
            window.set_details_overview(m.overview.into());
            window.set_details_cast(m.cast.iter().take(12)
                .map(|c|format!("{} as {}",c.name,c.character))
                .collect::<Vec<_>>().join("     ·     ").into());
            window.set_details_related(m.related.iter().take(12)
                .map(|(title,_)|title.as_str()).collect::<Vec<_>>().join("    •    ").into());
        }else{
            window.set_details_overview(
                "Loading verified TMDB information… Playback always uses your IPTV provider.".into());
            window.set_details_cast("".into());
            window.set_details_related("".into());
        }
    }
}
fn tab(window:&AuroraWindow,state:&State,index:usize){
    {
        let mut s=lock(state);
        s.ui.tab=index.min(7);s.ui.selected=-1;s.ui.offset=0;
        s.ui.details=None;s.ui.detail_info=None;s.ui.episodes=None;
        s.ui.status=if index==7{"Settings: connect another provider or configure TMDB.".into()}
                   else{"Browse with your TV remote. Select to open.".into()};
    }
    render(window,state);
}
fn navigate(window:&AuroraWindow,state:&State,direction:&str){
    let do_tab={
        let mut s=lock(state);
        if s.ui.details.is_some(){
            if direction=="left"||direction=="right" {return;}
            return;
        }
        let available=s.ui.visible.len() as i32;
        if s.ui.selected<0 {
            match direction {
                "left"=>Some(s.ui.tab.saturating_sub(1)),
                "right"=>Some((s.ui.tab+1).min(7)),
                "down"=>{if available>0{s.ui.selected=0;}None},
                _=>None,
            }
        }else{
            let current=s.ui.selected;
            match direction{
                "left"=>if current>0{s.ui.selected-=1;}
                  else{s.ui.selected=-1;},
                "right"=>if current+1<available{s.ui.selected+=1;}
                  else if available==12 {
                    s.ui.offset=s.ui.offset.saturating_add(6);
                    s.ui.selected=0;
                  },
                "up"=>if current<6{s.ui.selected=-1;}
                  else{s.ui.selected-=6;},
                "down"=>if current+6<available{s.ui.selected+=6;}
                  else if available==12 {
                    s.ui.offset=s.ui.offset.saturating_add(6);
                    s.ui.selected=0;
                  },
                _=>{},
            }
            None
        }
    };
    if let Some(next)=do_tab{tab(window,state,next);}else{render(window,state);}
}
fn open_selected(window:&AuroraWindow,state:&State,index:usize){
    let entry={lock(state).ui.visible.get(index).cloned()};
    if let Some(entry)=entry {
        if lock(state).ui.tab==6 {
            let db={lock(state).db.clone()};
            let _=db.hide(&entry.id,true);
            set_status(window,"Hidden. Manage hidden titles from the provider catalog.");
            render(window,state);return;
        }
        if entry.kind==Kind::Live {
            play(window,state,&entry);return;
        }
        if entry.kind==Kind::Series {
            show_episodes(window,state,&entry);return;
        }
        present_detail(window,state,&entry);
    }
}
fn present_detail(window:&AuroraWindow,state:&State,entry:&Entry){
    {
        let mut s=lock(state);
        s.ui.details=Some(entry.clone());
        s.ui.detail_info=None;
    }
    render(window,state);
    let (db,key)={let s=lock(state);(s.db.clone(),s.ui.tmdb_key.clone())};
    if key.is_empty() {window.set_details_overview(
        "Configure a TMDB key to display cast and descriptions. "
        .to_string().into());return;}
    let item=entry.clone();
    let weak=window.as_weak();
    let state=state.clone();
    std::thread::spawn(move||{
        let tmdb=Tmdb{api_key:key};
        let metadata=tmdb.lookup(&item).ok().flatten();
        let _=weak.upgrade_in_event_loop(move|window|{
            {
                let mut s=lock(&state);
                if s.ui.details.as_ref().is_some_and(|x|x.id==item.id){
                    s.ui.detail_info=metadata;
                }else{return;}
            }
            render(&window,&state);
        });
        let _=db; // repository keeps the real stream ID throughout.
    });
}
fn show_episodes(window:&AuroraWindow,state:&State,show:&Entry){
    let source={lock(state).ui.source.clone()};
    let Some(source)=source else{
        set_status(window,"M3U series playback requires an individual episode URL.");
        return;
    };
    let item=show.clone();let state=state.clone();let weak=window.as_weak();
    set_status(window,"Loading seasons and episodes from your IPTV provider…");
    std::thread::spawn(move||{
        let episodes=source.episodes(&item).unwrap_or_default();
        let _=weak.upgrade_in_event_loop(move|window|{
            {
                let mut s=lock(&state);
                s.ui.episodes=Some(episodes);
                s.ui.details=None;
                s.ui.offset=0;
                s.ui.selected=0;
                s.ui.status="SEASONS AND EPISODES · Select an episode".into();
            }
            render(&window,&state);
        });
    });
}
fn play(window:&AuroraWindow,state:&State,item:&Entry){
    if item.kind==Kind::Series && !item.id.contains(":episode:"){
        show_episodes(window,state,item);return;
    }
    let (source,db)={let s=lock(state);(s.ui.source.clone(),s.db.clone())};
    let urls=playback::candidates(item,source.as_ref());
    if urls.is_empty(){set_status(window,"Provider did not supply a playable stream URL.");return;}
    let _=db.watched(&item.id,0);
    #[cfg(target_os="android")]
    match android::launch_player(&urls[0],&item.title,item.kind==Kind::Live){
        Ok(())=>set_status(window,"Launching built-in Android playback."),
        Err(e)=>set_status(window,&format!("Playback launch error: {e}")),
    }
    #[cfg(not(target_os="android"))]
    {
        // Desktop is primarily a UI development target. The Android APK has
        // an in-app fullscreen player; this opens the OS handler for preview.
        let opener=if cfg!(target_os="macos"){"open"}else{"xdg-open"};
        match std::process::Command::new(opener).arg(&urls[0]).spawn(){
            Ok(_)=>set_status(window,"Opened stream with the desktop media handler."),
            Err(_)=>set_status(window,"Install a desktop video handler for playback."),
        }
    }
}
fn download_poster_batch(state:&State,window:&AuroraWindow){
    let items={lock(state).ui.visible.clone()};
    let dir=data_dir().join("posters");
    let weak=window.as_weak();
    std::thread::spawn(move||{
        let _=fs::create_dir_all(&dir);
        let mut changed=false;
        for item in items.iter().take(12){
            if item.poster.is_empty(){continue;}
            if !item.poster.starts_with("https://"){continue;}
            let path=dir.join(format!("{}.png",safe_key(&item.id)));
            if path.exists(){continue;}
            let response=match ureq::get(&item.poster)
                .timeout(Duration::from_secs(7)).call(){Ok(r)=>r,Err(_)=>continue};
            let mut buf=Vec::new();
            if response.into_reader().take(700_001).read_to_end(&mut buf).is_err()
               ||buf.len()>700_000{continue;}
            // Use actual image extension. A future release will use a unified
            // decoded thumbnail cache rather than network PNG-only assets.
            if buf.starts_with(&[0x89,0x50,0x4e,0x47])
                && fs::write(&path,&buf).is_ok() {changed=true;}
        }
        if changed {let _=weak.upgrade_in_event_loop(|_|{});}
    });
}
fn connect_xtream(window:&AuroraWindow,state:&State,base:&str,user:&str,password:&str){
    let source=match Xtream::new(base,user,password){
        Ok(s)=>s,Err(e)=>{set_status(window,&e.to_string());return;}
    };
    set_status(window,"Connecting to Xtream and importing your IPTV library…");
    let source_copy=source.clone();
    let weak=window.as_weak();let state=state.clone();
    std::thread::spawn(move||{
        let result=(||->aurora_core::Result<usize>{
            if !source_copy.authenticate()?{
                return Err(aurora_core::AuroraError::Invalid(
                    "Xtream rejected this username or password".into()));
            }
            let db={lock(&state).db.clone()};
            let mut total=0;
            for kind in [Kind::Live,Kind::Movie,Kind::Series] {
                let rows=source_copy.catalog("main",kind)?;
                total+=db.import(&rows)?;
            }
            db.save_setting("xtream_login",&serde_json::to_string(&source_copy)?)?;
            Ok(total)
        })();
        let _=weak.upgrade_in_event_loop(move|window|{
            match result {
                Ok(count)=>{
                    {
                        let mut s=lock(&state);
                        s.ui.source=Some(source);
                        s.ui.m3u_url=None;
                        s.ui.connected=true;
                        s.ui.status=format!("Imported {count} provider channels, movies and shows");
                    }
                    render(&window,&state);
                    download_poster_batch(&state,&window);
                    request_xmltv(&window,&state);
                }
                Err(e)=>set_status(&window,&format!("Provider import failed: {e}")),
            }
        });
    });
}
fn connect_m3u(window:&AuroraWindow,state:&State,url:&str){
    if !url.starts_with("http://")&&!url.starts_with("https://"){
        set_status(window,"M3U playlist must be an HTTP(S) URL.");return;
    }
    let uri=url.to_string();let state=state.clone();let weak=window.as_weak();
    set_status(window,"Downloading and indexing M3U playlist…");
    std::thread::spawn(move||{
        let result=(||->aurora_core::Result<usize>{
            let response=ureq::get(&uri).timeout(Duration::from_secs(30)).call()?;
            let mut bytes=Vec::new();
            response.into_reader().take(40_000_001).read_to_end(&mut bytes)
                .map_err(|_|aurora_core::AuroraError::Invalid("M3U read failed".into()))?;
            if bytes.len()>40_000_000{
                return Err(aurora_core::AuroraError::Invalid("Playlist exceeds size limit".into()));
            }
            let text=String::from_utf8_lossy(&bytes);
            let items=m3u::parse("main",&text);
            let db={lock(&state).db.clone()};
            let count=db.import(&items)?;
            db.save_setting("m3u_url",&uri)?;
            Ok(count)
        })();
        let _=weak.upgrade_in_event_loop(move|window|{
            match result {
                Ok(count)=>{
                    let mut s=lock(&state);
                    s.ui.m3u_url=Some(uri);
                    s.ui.source=None;
                    s.ui.connected=true;
                    s.ui.status=format!("Imported {count} playlist entries");
                    drop(s);
                    render(&window,&state);
                }
                Err(e)=>set_status(&window,&format!("M3U import failed: {e}")),
            }
        });
    });
}
fn request_xmltv(window:&AuroraWindow,state:&State){
    let (source,db,generation)={
        let s=lock(state);
        (s.ui.source.clone(),s.db.clone(),s.generation.clone())
    };
    let Some(source)=source else{return};
    let epoch=generation.fetch_add(1,Ordering::AcqRel)+1;
    let weak=window.as_weak();
    std::thread::spawn(move||{
        let result=source.xmltv_url().ok()
            .and_then(|url|aurora_core::epg::fetch_xmltv(&url).ok())
            .and_then(|items|db.write_epg(&items).ok());
        if generation.load(Ordering::Acquire)!=epoch{return;}
        let _=weak.upgrade_in_event_loop(move|window|{
            if let Some(count)=result{
                window.set_guide_state(format!("EPG: {count} programmes cached").into());
            }else{
                window.set_guide_state("EPG unavailable · Provider fallback ready".into());
            }
        });
    });
}
fn handle_activation(window:&AuroraWindow,state:&State){
    let (selected,tab,entry,detail)={
        let s=lock(state);
        (s.ui.selected,s.ui.tab,
         if s.ui.selected>=0{s.ui.visible.get(s.ui.selected as usize).cloned()}else{None},
         s.ui.details.clone())
    };
    if let Some(item)=detail{play(window,state,&item);return;}
    if selected<0{self::tab(window,state,tab);}
    else if let Some(entry)=entry{open_selected(window,state,selected as usize);let _=entry;}
}
fn go_back(window:&AuroraWindow,state:&State){
    {
        let mut s=lock(state);
        if s.ui.details.is_some(){s.ui.details=None;s.ui.detail_info=None;}
        else if s.ui.episodes.is_some(){s.ui.episodes=None;s.ui.offset=0;s.ui.selected=0;}
        else{s.ui.selected=-1;}
    }
    render(window,state);
}
pub fn launch()->Result<(),String>{
    let state=init_state()?;
    let window=AuroraWindow::new().map_err(|e|e.to_string())?;
    render(&window,&state);
    {
        let s=state.clone();let weak=window.as_weak();
        window.on_switch_tab(move|index|{
            if let Some(window)=weak.upgrade(){tab(&window,&s,index.max(0) as usize)}
        });
    }
    {
        let s=state.clone();let weak=window.as_weak();
        window.on_move_selection(move|direction|{
            if let Some(window)=weak.upgrade(){navigate(&window,&s,&direction);}
        });
    }
    {
        let s=state.clone();let weak=window.as_weak();
        window.on_activate_selection(move||{
            if let Some(window)=weak.upgrade(){handle_activation(&window,&s);}
        });
    }
    {
        let s=state.clone();let weak=window.as_weak();
        window.on_open_item(move|index|{
            if let Some(window)=weak.upgrade(){
                {
                    let mut x=lock(&s);
                    x.ui.selected=index;
                }
                open_selected(&window,&s,index.max(0) as usize)
            }
        });
    }
    {
        let s=state.clone();let weak=window.as_weak();
        window.on_play_featured(move||{
            if let Some(window)=weak.upgrade(){
                let item={
                    let x=lock(&s);
                    x.ui.details.clone().or_else(||x.ui.visible
                        .get(x.ui.selected.max(0) as usize).cloned())
                };
                if let Some(item)=item{play(&window,&s,&item);}
            }
        });
    }
    {
        let s=state.clone();let weak=window.as_weak();
        window.on_details_featured(move||{
            if let Some(window)=weak.upgrade(){
                let item={let x=lock(&s);
                    x.ui.visible.get(x.ui.selected.max(0) as usize).cloned()};
                if let Some(item)=item{present_detail(&window,&s,&item);}
            }
        });
    }
    {
        let s=state.clone();let weak=window.as_weak();
        window.on_go_back(move||{
            if let Some(window)=weak.upgrade(){go_back(&window,&s);}
        });
    }
    {
        let s=state.clone();let weak=window.as_weak();
        window.on_favorite_selected(move||{
            if let Some(window)=weak.upgrade(){
                let (entry,db)={let x=lock(&s);
                    (x.ui.visible.get(x.ui.selected.max(0) as usize).cloned(),x.db.clone())};
                if let Some(item)=entry{
                    let _=db.favorite(&item.id,!item.favorite);
                    render(&window,&s);
                }
            }
        });
    }
    {
        let s=state.clone();let weak=window.as_weak();
        window.on_search_library(move|query|{
            if let Some(window)=weak.upgrade(){
                {
                    let mut x=lock(&s);
                    x.ui.tab=5;x.ui.search=query.to_string();
                    x.ui.offset=0;x.ui.selected=0;
                }
                render(&window,&s);
            }
        });
    }
    {
        let s=state.clone();let weak=window.as_weak();
        window.on_connect_xtream(move|base,user,password|{
            if let Some(window)=weak.upgrade(){
                connect_xtream(&window,&s,&base,&user,&password);
            }
        });
    }
    {
        let s=state.clone();let weak=window.as_weak();
        window.on_connect_m3u(move|url|{
            if let Some(window)=weak.upgrade(){connect_m3u(&window,&s,&url);}
        });
    }
    {
        let s=state.clone();let weak=window.as_weak();
        window.on_save_tmdb_key(move|key|{
            if let Some(window)=weak.upgrade(){
                let db={lock(&s).db.clone()};
                if db.save_setting("tmdb_key",&key).is_ok(){
                    lock(&s).ui.tmdb_key=key.into();
                    set_status(&window,"TMDB key saved in app-private settings.");
                }
            }
        });
    }
    window.run().map_err(|e|e.to_string())
}

#[cfg(target_os="android")]
#[unsafe(no_mangle)]
fn android_main(app:slint::android::AndroidApp){
    slint::android::init(app).expect("Initialize Slint Android");
    if let Err(error)=launch(){
        eprintln!("AuroraTV 3.0: {error}");
    }
}
