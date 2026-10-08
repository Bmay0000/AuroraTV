package tv.aurora.player;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.util.Xml;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Aurora's local, hybrid EPG. Stores real programme data, not estimates.
 * Source priorities: manually mapped guide -> provider -> external XMLTV ->
 * per-stream short Xtream EPG. Fresh programme coverage outranks source rank.
 *
 * SQL indexes and streaming XMLTV imports keep large guides off the UI thread.
 */
public final class GuideEngine extends SQLiteOpenHelper {
    private static final long DAY=24L*60*60*1000;
    public static final long REFRESH_INTERVAL=12*60*60*1000L;
    private static final int MAX_PROGRAMMES=350000;
    private static final int MAX_XML_BYTES=160*1024*1024;
    private static final String[] SOURCES={"provider","external1","external2","short"};
    private final Context app;
    private static final Pattern STREAM_ID=Pattern.compile("/live/[^/]+/[^/]+/(\\d+)(?:\\.[^/]*)?$");

    public GuideEngine(Context c){super(c,"aurora_epg.db",null,1);app=c.getApplicationContext();setWriteAheadLoggingEnabled(true);}

    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE programs(source TEXT NOT NULL,channel TEXT NOT NULL,start INTEGER NOT NULL,"+
                "end INTEGER NOT NULL,title TEXT NOT NULL,description TEXT,PRIMARY KEY(source,channel,start))");
        db.execSQL("CREATE INDEX programs_time ON programs(source,channel,end,start)");
        db.execSQL("CREATE TABLE guide_channels(source TEXT NOT NULL,id TEXT NOT NULL,name TEXT NOT NULL,"+
                "norm TEXT NOT NULL,PRIMARY KEY(source,id))");
        db.execSQL("CREATE INDEX guide_name ON guide_channels(source,norm)");
        db.execSQL("CREATE TABLE manual(item TEXT PRIMARY KEY,source TEXT NOT NULL,channel TEXT NOT NULL)");
        db.execSQL("CREATE TABLE short_checked(item TEXT PRIMARY KEY,checked INTEGER NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int old,int current){
        throw new IllegalStateException("Unsupported guide database version");
    }

    public static final class Program {
        public final String title,description,source,channel;
        public final long start,end;
        Program(String title,String description,String source,String channel,long start,long end){
            this.title=title;this.description=description;this.source=source;
            this.channel=channel;this.start=start;this.end=end;
        }
    }
    public static final class Slot {
        public final Program now,next;
        public final String source;
        Slot(Program now,Program next,String source){
            this.now=now;this.next=next;this.source=source;
        }
        public boolean hasData(){return now!=null||next!=null;}
    }
    public static final class Match {
        public final String source,id,name;
        Match(String source,String id,String name){this.source=source;this.id=id;this.name=name;}
    }

    /** Resolve exact XMLTV identifiers before conservative name matching. */
    static String normalized(String name){
        if(name==null)return "";
        String s=Normalizer.normalize(name,Normalizer.Form.NFD)
              .replaceAll("\\p{M}+","").toUpperCase(Locale.ROOT);
        s=s.replaceFirst("^(UK|GB|US|USA|NZ|AU|CA)\\s*[|:/-]\\s*","");
        s=s.replaceAll("(?<![A-Z0-9])(SD|HD|FHD|UHD|4K|HEVC|H265)(?![A-Z0-9])","");
        return s.replaceAll("[^A-Z0-9]","");
    }
    private static boolean supportedSource(String s){
        return "provider".equals(s)||"external1".equals(s)||"external2".equals(s);
    }
    private static boolean validUrl(String raw){
        try{
            URL u=new URL(raw);
            return u.getProtocol().matches("https?")&&u.getHost()!=null&&!u.getHost().isEmpty();
        }catch(Exception ex){return false;}
    }

    /** Up to 7 days of XMLTV listings kept; failed imports roll back atomically. */
    public int importXmltv(String address,String source) throws Exception {
        if(!supportedSource(source))throw new IllegalArgumentException("Unsupported EPG source");
        if(!validUrl(address))throw new IOException("Enter a valid HTTP(S) XMLTV URL");
        HttpURLConnection conn=(HttpURLConnection)new URL(address).openConnection();
        conn.setConnectTimeout(12000);conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("Accept-Encoding","gzip");
        conn.setRequestProperty("User-Agent","AuroraTV/EPG");
        int count=0;
        try{
            int code=conn.getResponseCode();
            if(code!=200)throw new IOException("EPG returned HTTP "+code);
            try(InputStream plain=conn.getInputStream();
                PushbackInputStream sniff=new PushbackInputStream(new BufferedInputStream(plain,32768),2)){
                byte[] magic=new byte[2];
                int n=sniff.read(magic);
                if(n>0)sniff.unread(magic,0,n);
                boolean gz=n==2&&(magic[0]&255)==31&&(magic[1]&255)==139;
                InputStream decoded=gz?new GZIPInputStream(sniff,32768):sniff;
                try(InputStream limited=new MaxInputStream(decoded,MAX_XML_BYTES)){
                    XmlPullParser xml=Xml.newPullParser();
                    xml.setInput(limited,null); // Respect XML declaration (UTF-8 or UTF-16)
                    SQLiteDatabase db=getWritableDatabase();
                    db.beginTransaction();
                    try{
                        db.execSQL("DELETE FROM programs WHERE source=?",new Object[]{source});
                        db.execSQL("DELETE FROM guide_channels WHERE source=?",new Object[]{source});
                        SQLiteStatement prog=db.compileStatement("INSERT OR REPLACE INTO programs"+
                           "(source,channel,start,end,title,description) VALUES(?,?,?,?,?,?)");
                        SQLiteStatement channel=db.compileStatement("INSERT OR REPLACE INTO guide_channels"+
                           "(source,id,name,norm) VALUES(?,?,?,?)");
                        try{
                            long earliest=System.currentTimeMillis()-6*60*60*1000L;
                            long latest=System.currentTimeMillis()+7*DAY;
                            String currentChannel=null,currentName=null,programChannel=null;
                            String title=null,description=null;
                            long start=0,end=0;
                            for(int event=xml.getEventType();event!=XmlPullParser.END_DOCUMENT;event=xml.next()){
                                if(Thread.currentThread().isInterrupted())throw new IOException("Guide refresh cancelled");
                                if(event==XmlPullParser.START_TAG){
                                    String tag=xml.getName();
                                    if("channel".equals(tag)){
                                        currentChannel=xml.getAttributeValue(null,"id");currentName=null;
                                    }else if("programme".equals(tag)){
                                        programChannel=xml.getAttributeValue(null,"channel");
                                        start=parseXmlTime(xml.getAttributeValue(null,"start"));
                                        end=parseXmlTime(xml.getAttributeValue(null,"stop"));
                                        title=null;description=null;
                                    }else if("display-name".equals(tag)&&currentChannel!=null&&currentName==null){
                                        currentName=xml.nextText();
                                    }else if("title".equals(tag)&&programChannel!=null&&title==null){
                                        title=xml.nextText();
                                    }else if("desc".equals(tag)&&programChannel!=null&&description==null){
                                        description=xml.nextText();
                                    }
                                }else if(event==XmlPullParser.END_TAG){
                                    String tag=xml.getName();
                                    if("channel".equals(tag)){
                                        if(currentChannel!=null&&!currentChannel.isEmpty()){
                                            String label=currentName==null?currentChannel:currentName;
                                            channel.clearBindings();
                                            channel.bindString(1,source);
                                            channel.bindString(2,currentChannel);
                                            channel.bindString(3,label);
                                            channel.bindString(4,normalized(label));
                                            channel.executeInsert();
                                        }
                                        currentChannel=null;
                                    }else if("programme".equals(tag)){
                                        if(programChannel!=null&&title!=null&&start>0&&end>start
                                               &&end>earliest&&start<latest){
                                            prog.clearBindings();
                                            prog.bindString(1,source);
                                            prog.bindString(2,programChannel);
                                            prog.bindLong(3,start);prog.bindLong(4,end);
                                            prog.bindString(5,title);
                                            prog.bindString(6,description==null?"":description);
                                            prog.executeInsert();
                                            if(++count>MAX_PROGRAMMES)
                                                throw new IOException("EPG exceeded safe programme limit");
                                        }
                                        programChannel=null;
                                    }
                                }
                            }
                        }finally{
                            prog.close();channel.close();
                        }
                        if(count==0)throw new IOException("EPG contains no current or upcoming programmes");
                        db.setTransactionSuccessful();
                    }finally{db.endTransaction();}
                }
            }
        }finally{conn.disconnect();}
        return count;
    }

    private static long parseXmlTime(String value){
        if(value==null)return 0;
        String text=value.trim();
        if(text.length()<14)return 0;
        try{
            boolean timezone=text.length()>14;
            SimpleDateFormat f=new SimpleDateFormat(timezone?"yyyyMMddHHmmss Z":"yyyyMMddHHmmss",Locale.US);
            f.setLenient(false);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(text).getTime();
        }catch(Exception ignored){return 0;}
    }

    private static final class MaxInputStream extends FilterInputStream{
        private final long max;private long used;
        MaxInputStream(InputStream in,long max){super(in);this.max=max;}
        private void add(long n)throws IOException{
            if(n>0 && (used+=n)>max)throw new IOException("EPG file exceeds safe download limit");
        }
        @Override public int read()throws IOException{int b=super.read();if(b>=0)add(1);return b;}
        @Override public int read(byte[] b,int off,int len)throws IOException{
            int n=super.read(b,off,len);add(n);return n;
        }
    }

    private Program readProgram(Cursor c){
        return new Program(c.getString(0),c.getString(1),c.getString(2),
                c.getString(3),c.getLong(4),c.getLong(5));
    }
    private Slot lookup(SQLiteDatabase db,String source,String channel,long now){
        if(channel==null||channel.isEmpty())return new Slot(null,null,source);
        Program playing=null,upcoming=null;
        try(Cursor c=db.rawQuery(
            "SELECT title,description,source,channel,start,end FROM programs "+
             "WHERE source=? AND channel=? AND end>? AND start<? ORDER BY start LIMIT 12",
            new String[]{source,channel,String.valueOf(now),String.valueOf(now+8*60*60*1000L)})){
            while(c.moveToNext()){
                Program p=readProgram(c);
                if(p.start<=now&&p.end>now)playing=p;
                else if(p.start>now&&(upcoming==null||p.start<upcoming.start))upcoming=p;
            }
        }
        return new Slot(playing,upcoming,source);
    }
    private String channel(SQLiteDatabase db,String source,LibraryCore.Item item){
        if("short".equals(source))return item.id;
        if(item.epgId!=null&&!item.epgId.isEmpty()){
            try(Cursor c=db.rawQuery(
                "SELECT 1 FROM programs WHERE source=? AND channel=? LIMIT 1",
                new String[]{source,item.epgId})){
                if(c.moveToFirst())return item.epgId;
            }
        }
        // Use exact normalized-name matches only if unambiguous.
        String norm=normalized(item.name);
        if(norm.isEmpty())return null;
        try(Cursor c=db.rawQuery("SELECT id FROM guide_channels WHERE source=? AND norm=? LIMIT 2",
                new String[]{source,norm})){
            if(!c.moveToFirst())return null;
            String candidate=c.getString(0);
            return c.moveToNext()?null:candidate;
        }
    }
    public Slot nowNext(LibraryCore.Item item){
        long now=System.currentTimeMillis();
        SQLiteDatabase db=getReadableDatabase();
        try(Cursor manual=db.rawQuery("SELECT source,channel FROM manual WHERE item=?",new String[]{item.id})){
            if(manual.moveToFirst()){
                Slot mapped=lookup(db,manual.getString(0),manual.getString(1),now);
                if(mapped.hasData())return mapped;
            }
        }
        Slot best=new Slot(null,null,"none");
        int highest=-1;
        for(String source:SOURCES){
            Slot found=lookup(db,source,channel(db,source,item),now);
            int score=found.now!=null?100:found.next!=null?25:0;
            if(found.next!=null)score+=8;
            if(source.equals("provider"))score+=3;
            if(source.startsWith("external"))score+=2;
            if(score>highest&&found.hasData()){
                highest=score;best=found;
            }
        }
        return best;
    }

    public List<Match> findCandidates(String title,int limit){
        String name=normalized(title);
        String loose=title==null?"":title.replaceAll("[%_]","").trim();
        ArrayList<Match> found=new ArrayList<>();
        SQLiteDatabase db=getReadableDatabase();
        try(Cursor c=db.rawQuery(
            "SELECT source,id,name FROM guide_channels WHERE norm=? OR name LIKE ? "+
            "ORDER BY CASE WHEN norm=? THEN 0 ELSE 1 END, name LIMIT ?",
            new String[]{name,"%"+loose+"%",name,String.valueOf(limit)})){
            while(c.moveToNext())found.add(new Match(c.getString(0),c.getString(1),c.getString(2)));
        }
        return found;
    }
    public void setManual(String item,String source,String channel){
        if(!supportedSource(source))throw new IllegalArgumentException("Invalid EPG source");
        android.content.ContentValues data=new android.content.ContentValues();
        data.put("item",item);data.put("source",source);data.put("channel",channel);
        getWritableDatabase().insertWithOnConflict("manual",null,data,SQLiteDatabase.CONFLICT_REPLACE);
    }
    public void clearManual(String item){
        getWritableDatabase().delete("manual","item=?",new String[]{item});
    }

    /**
     * On-demand fallback for providers whose XMLTV feed fails. Never enumerate
     * the full IPTV catalog: caller decides a small set of visible channels.
     */
    public boolean fetchShort(LibraryCore.Item item,String host,String user,String password){
        if(item==null||item.type==null||!item.type.equals("live"))return false;
        SQLiteDatabase db=getWritableDatabase();
        long now=System.currentTimeMillis();
        try(Cursor c=db.rawQuery("SELECT checked FROM short_checked WHERE item=?",
                new String[]{item.id})){
            if(c.moveToFirst()&&now-c.getLong(0)<30*60*1000L)return false;
        }
        android.content.ContentValues attempt=new android.content.ContentValues();
        attempt.put("item",item.id);attempt.put("checked",now);
        db.insertWithOnConflict("short_checked",null,attempt,SQLiteDatabase.CONFLICT_REPLACE);
        try{
            String streamUrl=item.url;
            if(streamUrl==null||streamUrl.isEmpty())return false;
            Matcher m=STREAM_ID.matcher(new URL(streamUrl).getPath());
            if(!m.find())return false;
            String id=m.group(1);
            String api=Provider.base(host)+"/player_api.php?username="+Provider.enc(user)+
              "&password="+Provider.enc(password)+"&action=get_short_epg&stream_id="+id+"&limit=10";
            JSONObject result=new JSONObject(smallGet(api));
            JSONArray entries=result.optJSONArray("epg_listings");
            if(entries==null||entries.length()==0)return false;
            int added=0;
            db.beginTransaction();
            try{
                SQLiteStatement insert=db.compileStatement("INSERT OR REPLACE INTO programs"+
                  "(source,channel,start,end,title,description) VALUES(?,?,?,?,?,?)");
                try{
                    db.execSQL("DELETE FROM programs WHERE source='short' AND channel=?",new Object[]{item.id});
                    for(int n=0;n<Math.min(entries.length(),20);n++){
                        JSONObject p=entries.optJSONObject(n);
                        if(p==null)continue;
                        long start=p.optLong("start_timestamp",0)*1000L;
                        long end=p.optLong("stop_timestamp",0)*1000L;
                        if(start<=0||end<=start||end<now-60*60*1000L)continue;
                        String title=maybeBase64(p.optString("title",""));
                        if(title.isEmpty())continue;
                        insert.clearBindings();
                        insert.bindString(1,"short");insert.bindString(2,item.id);
                        insert.bindLong(3,start);insert.bindLong(4,end);
                        insert.bindString(5,title);
                        insert.bindString(6,maybeBase64(p.optString("description","")));
                        insert.executeInsert();added++;
                    }
                }finally{insert.close();}
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
            return added>0;
        }catch(Exception ignored){return false;}
    }
    private static String maybeBase64(String input){
        if(input==null)return "";
        if(input.length()<12||input.length()%4!=0||!input.matches("[A-Za-z0-9+/]+={0,2}"))return input;
        try{
            byte[] binary=android.util.Base64.decode(input,android.util.Base64.DEFAULT);
            String decoded=new String(binary,StandardCharsets.UTF_8);
            if(decoded.matches(".*[\\p{L}].*")&&!decoded.contains("\uFFFD"))return decoded;
        }catch(Exception ignored){}
        return input;
    }
    private static String smallGet(String url)throws Exception{
        HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();
        connection.setConnectTimeout(6000);connection.setReadTimeout(9000);
        connection.setRequestProperty("User-Agent","AuroraTV/EPG");
        try{
            if(connection.getResponseCode()!=200)throw new IOException("Xtream guide unavailable");
            try(InputStream in=connection.getInputStream();
                ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buffer=new byte[4096];int n;
                while((n=in.read(buffer))!=-1){
                    out.write(buffer,0,n);
                    if(out.size()>512*1024)throw new IOException("Xtream guide response too large");
                }
                return out.toString("UTF-8");
            }
        }finally{connection.disconnect();}
    }
    public void clearSource(String source){
        if(!supportedSource(source))return;
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try{
            db.execSQL("DELETE FROM programs WHERE source=?",new Object[]{source});
            db.execSQL("DELETE FROM guide_channels WHERE source=?",new Object[]{source});
            db.execSQL("DELETE FROM manual WHERE source=?",new Object[]{source});
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public void clearProvider(){
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try{
            db.execSQL("DELETE FROM programs WHERE source IN ('provider','short')");
            db.execSQL("DELETE FROM guide_channels WHERE source='provider'");
            db.execSQL("DELETE FROM short_checked");
            db.execSQL("DELETE FROM manual WHERE source='provider'");
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public void clearAll(){
        close();
        app.deleteDatabase("aurora_epg.db");
    }
}
