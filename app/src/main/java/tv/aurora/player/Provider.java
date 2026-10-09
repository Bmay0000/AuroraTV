package tv.aurora.player;
import org.json.*;
import java.net.*;
import java.io.*;
import java.util.*;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import java.text.SimpleDateFormat;
public final class Provider {
 static String enc(String s)throws Exception{return URLEncoder.encode(s,"UTF-8");}
 static String segment(String s)throws Exception{return enc(s).replace("+","%20");}
 static String get(String url)throws Exception{
  HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
  c.setConnectTimeout(12000);c.setReadTimeout(18000);
  c.setRequestProperty("User-Agent","AuroraTV/0.4");
  c.setRequestProperty("Accept-Encoding","gzip");
  try{
   if(c.getResponseCode()!=200)throw new IOException("Provider returned HTTP "+c.getResponseCode());
   try(InputStream in=responseStream(c);ByteArrayOutputStream b=new ByteArrayOutputStream()){
    byte[] buf=new byte[16384];int n;
    while((n=in.read(buf))!=-1){
     if(b.size()+n>16*1024*1024)throw new IOException("Provider metadata too large");
     b.write(buf,0,n);
    }
    return b.toString("UTF-8");
   }
  }finally{c.disconnect();}
 }
 static InputStream responseStream(HttpURLConnection connection)throws IOException{
  InputStream stream=connection.getInputStream();
  if("gzip".equalsIgnoreCase(connection.getContentEncoding()))
   return new java.util.zip.GZIPInputStream(stream,32768);
  return new BufferedInputStream(stream,32768);
 }
 static String base(String s)throws Exception{URL u=new URL(s);if(!u.getProtocol().matches("https?"))throw new IOException("Use an http:// or https:// address");return s.replaceAll("/+$","");}
 public static List<LibraryCore.Item> xtream(String host,String user,String pass)throws Exception{host=base(host);String api=host+"/player_api.php?username="+enc(user)+"&password="+enc(pass);JSONObject auth=new JSONObject(get(api));if(auth.optJSONObject("user_info")==null||auth.getJSONObject("user_info").optInt("auth")!=1)throw new IOException("Login rejected");List<LibraryCore.Item> out=new ArrayList<>();String[] kinds={"live","vod","series"};for(String kind:kinds){JSONArray cats;try{cats=new JSONArray(get(api+"&action=get_"+kind+"_categories"));}catch(JSONException ex){cats=new JSONArray();}Map<String,String> names=new HashMap<>();for(int j=0;j<cats.length();j++){JSONObject c=cats.optJSONObject(j);if(c==null)continue;names.put(c.optString("category_id"),c.optString("category_name"));}JSONArray rows=new JSONArray(get(api+"&action=get_"+kind+(kind.equals("series")?"":"_streams")));for(int j=0;j<rows.length();j++){JSONObject r=rows.optJSONObject(j);if(r==null)continue;String id=r.optString(kind.equals("series")?"series_id":"stream_id");if(id.isEmpty()||id.equals("null"))continue;String type=kind.equals("vod")?"movie":kind;String ext=kind.equals("live")?"ts":r.optString("container_extension","mp4");if(ext.isEmpty()||ext.equals("null"))ext="mp4";String url=kind.equals("series")?api+"&action=get_series_info&series_id="+enc(id):host+"/"+(kind.equals("vod")?"movie":kind)+"/"+segment(user)+"/"+segment(pass)+"/"+id+"."+ext;out.add(new LibraryCore.Item(LibraryCore.key(host+"|"+user+"|"+type+"|"+id),r.optString("name"),names.getOrDefault(r.optString("category_id"),"Uncategorized"),url,type,r.optString("epg_channel_id"),""));}}return out;}

 // Xtream's large get_live_streams/get_vod_streams/get_series arrays must be
 // parsed as a stream. Reading them as Strings/JSONArrays duplicates memory.
 public interface ImportProgress { void update(String stage,int count); }
 public static int xtreamStream(String host,String user,String pass,LibraryStore.Writer output)throws Exception{
  return xtreamStream(host,user,pass,output,(stage,count)->{});
 }
 public static int xtreamStream(String host,String user,String pass,LibraryStore.Writer output,ImportProgress progress)throws Exception{
  return xtreamStreamKinds(host,user,pass,output,progress,"live","vod","series");
 }
 /** Import only selected media types, allowing a usable Live TV library first. */
 public static int xtreamStreamKinds(String host,String user,String pass,
     LibraryStore.Writer output,ImportProgress progress,String... kinds)throws Exception{
  host=base(host);
  String api=host+"/player_api.php?username="+enc(user)+"&password="+enc(pass);
  JSONObject auth=new JSONObject(get(api));
  if(auth.optJSONObject("user_info")==null||auth.getJSONObject("user_info").optInt("auth")!=1)
   throw new IOException("Login rejected");
  int total=0;
  for(String kind:kinds){
   if(!kind.equals("live")&&!kind.equals("vod")&&!kind.equals("series"))
    throw new IOException("Invalid catalogue type");
   if(Thread.currentThread().isInterrupted())throw new IOException("Import cancelled");
   progress.update(kind,total);
   Map<String,String> names=new HashMap<>();
   try{
    JSONArray cats=new JSONArray(get(api+"&action=get_"+kind+"_categories"));
    for(int j=0;j<cats.length();j++){
     JSONObject c=cats.optJSONObject(j);
     if(c!=null)names.put(c.optString("category_id"),c.optString("category_name"));
    }
   }catch(JSONException badCategories){/* Some providers omit category endpoints. */}
   String endpoint=api+"&action=get_"+kind+(kind.equals("series")?"":"_streams");
   HttpURLConnection connection=(HttpURLConnection)new URL(endpoint).openConnection();
   connection.setConnectTimeout(12000);connection.setReadTimeout(22000);
   connection.setRequestProperty("User-Agent","AuroraTV/0.4");
   connection.setRequestProperty("Accept-Encoding","gzip");
   try{
    int status=connection.getResponseCode();
    if(status!=200)throw new IOException("Provider "+kind+" request returned HTTP "+status);
    try(android.util.JsonReader reader=new android.util.JsonReader(new InputStreamReader(responseStream(connection),"UTF-8"))){
     reader.setLenient(true);
     if(reader.peek()!=android.util.JsonToken.BEGIN_ARRAY)
      throw new IOException("Provider returned invalid "+kind+" list");
     reader.beginArray();
     while(reader.hasNext()){
      if(Thread.currentThread().isInterrupted())throw new IOException("Import cancelled");
      if(reader.peek()!=android.util.JsonToken.BEGIN_OBJECT){reader.skipValue();continue;}
      String id="",name="",cat="",epg="",ext="",artwork="",language="";
      reader.beginObject();
      while(reader.hasNext()){
       String key=reader.nextName();
       switch(key){
        case "stream_id":case "series_id":
         if(reader.peek()==android.util.JsonToken.STRING||reader.peek()==android.util.JsonToken.NUMBER)id=reader.nextString();else reader.skipValue();break;
        case "name":
         if(reader.peek()==android.util.JsonToken.STRING)name=reader.nextString();else reader.skipValue();break;
        case "category_id":
         if(reader.peek()==android.util.JsonToken.STRING||reader.peek()==android.util.JsonToken.NUMBER)cat=reader.nextString();else reader.skipValue();break;
        case "epg_channel_id":
         if(reader.peek()==android.util.JsonToken.STRING)epg=reader.nextString();else reader.skipValue();break;
        case "stream_icon":case "cover":case "cover_big":case "movie_image":case "poster":case "thumbnail":
         if(reader.peek()==android.util.JsonToken.STRING){String value=reader.nextString();if(artwork.isEmpty()&&value.startsWith("http"))artwork=value;}else reader.skipValue();break;
        case "language":case "tvg_language":
         if(reader.peek()==android.util.JsonToken.STRING)language=reader.nextString();else reader.skipValue();break;
        case "container_extension":
         if(reader.peek()==android.util.JsonToken.STRING)ext=reader.nextString();else reader.skipValue();break;
        default:reader.skipValue();
       }
      }
      reader.endObject();
      if(id.isEmpty()||id.equals("null"))continue;
      String type=kind.equals("vod")?"movie":kind;
      if(ext.isEmpty()||!ext.matches("[a-zA-Z0-9]{1,6}"))ext=kind.equals("live")?"ts":"mp4";
      String streamKind=kind.equals("vod")?"movie":kind;
      String streamUrl=XtreamReference.of(streamKind,id,ext);
      if(streamUrl==null)
       streamUrl=kind.equals("series")?api+"&action=get_series_info&series_id="+enc(id)
        :host+"/"+streamKind+"/"+segment(user)+"/"+segment(pass)+"/"+id+"."+ext;
      LibraryCore.Item item=new LibraryCore.Item(LibraryCore.key(host+"|"+user+"|"+type+"|"+id),
        name.isEmpty()?"Untitled":name,names.getOrDefault(cat,"Uncategorized"),
        streamUrl,type,epg,language);
      item.artwork=artwork;
      output.add(item);
      total++;
      if(total%3000==0)progress.update(kind,total);
     }
     reader.endArray();
    }
   }finally{connection.disconnect();}
  }
  return total;
 }

 /** Read M3U incrementally instead of creating a huge String and List. */
 public interface PlaylistGuideConsumer { void accept(String xmltvUrl); }
 public static int m3uStream(String address,LibraryStore.Writer writer,ImportProgress progress)throws Exception{
  return m3uStream(address,writer,progress,xmltvUrl->{});
 }
 public static int m3uStream(String address,LibraryStore.Writer writer,ImportProgress progress,PlaylistGuideConsumer guide)throws Exception{
  URL url=new URL(address);
  if(!url.getProtocol().matches("https?"))throw new IOException("Use an HTTP or HTTPS playlist URL");
  HttpURLConnection connection=(HttpURLConnection)url.openConnection();
  connection.setConnectTimeout(15000);
  connection.setReadTimeout(45000);
  connection.setRequestProperty("User-Agent","AuroraTV/0.2");
  int count=0;
  try{
   if(connection.getResponseCode()!=200)
    throw new IOException("Playlist request returned HTTP "+connection.getResponseCode());
   try(BufferedReader reader=new BufferedReader(new InputStreamReader(connection.getInputStream(),"UTF-8"),32768)){
    String line,meta=null;
    while((line=reader.readLine())!=null){
     String entry=line.trim();
     if(entry.startsWith("#EXTM3U")){
      String epgUrl=LibraryCore.attr(entry,"x-tvg-url");
      if(epgUrl.isEmpty())epgUrl=LibraryCore.attr(entry,"url-tvg");
      if(epgUrl.startsWith("http://")||epgUrl.startsWith("https://")){
       // Comma-separated URLs can be included; pick the first valid feed.
       guide.accept(epgUrl.split(",")[0].trim());
      }
      continue;
     }
     if(entry.startsWith("#EXTINF:")){meta=entry;continue;}
     if(entry.isEmpty()||entry.startsWith("#")||meta==null)continue;
     boolean quoted=false;int comma=-1;
     for(int x=0;x<meta.length();x++){
      if(meta.charAt(x)=='"')quoted=!quoted;
      else if(meta.charAt(x)==','&&!quoted){comma=x;break;}
     }
     String name=comma<0?"Untitled":meta.substring(comma+1).trim();
     String group=LibraryCore.attr(meta,"group-title");
     String type=LibraryCore.attr(meta,"media-type");
     if(!type.equals("movie")&&!type.equals("series"))
      type=entry.matches("(?i).*/movie/.*")?"movie":
           entry.matches("(?i).*/series/.*")?"series":"live";
     String epg=LibraryCore.attr(meta,"tvg-id");
     LibraryCore.Item item=new LibraryCore.Item(
       LibraryCore.key(address+"|"+type+"|"+epg+"|"+name+"|"+group),
       name,group.isEmpty()?"Uncategorized":group,entry,type,epg,
       LibraryCore.attr(meta,"tvg-language"));
     item.artwork=LibraryCore.attr(meta,"tvg-logo");
     writer.add(item);
     if(++count%3000==0)progress.update("m3u",count);
     meta=null;
    }
   }
  }finally{connection.disconnect();}
  progress.update("m3u",count);
  return count;
 }
 /** Fetch a concise, real synopsis only when a user opens a title preview.
  *  IPTV providers do not all supply film metadata; empty means unavailable. */
 public static String mediaSummary(LibraryCore.Item item,String host,String user,String password)
         throws Exception {
  if(item==null || item.url==null || !("movie".equals(item.type)||
          "series".equals(item.type)))return "";
  String endpoint;
  if("series".equals(item.type)) {
   if(!item.url.contains("action=get_series_info"))return "";
   endpoint=item.url;
  } else {
   java.util.regex.Matcher match=java.util.regex.Pattern.compile(
     "/movie/[^/]+/[^/]+/(\\d+)\\.[A-Za-z0-9]{1,6}(?:\\?.*)?$")
     .matcher(item.url);
   if(!match.find())return "";
   endpoint=base(host)+"/player_api.php?username="+enc(user)+
     "&password="+enc(password)+"&action=get_vod_info&vod_id="+enc(match.group(1));
  }
  // Provider info may contain episode arrays; protect Fire TV from massive
  // responses. The preview is optional if metadata exceeds our budget.
  HttpURLConnection connection=(HttpURLConnection)new URL(endpoint).openConnection();
  connection.setConnectTimeout(6500);connection.setReadTimeout(7500);
  connection.setRequestProperty("User-Agent","AuroraTV/0.3");
  try{
   if(connection.getResponseCode()!=200)return "";
   try(InputStream input=connection.getInputStream();
       ByteArrayOutputStream buffer=new ByteArrayOutputStream()){
    byte[] chunk=new byte[4096];int read;
    while((read=input.read(chunk))!=-1){
     if(buffer.size()+read>2*1024*1024)return "";
     buffer.write(chunk,0,read);
    }
    JSONObject response=new JSONObject(buffer.toString("UTF-8"));
    JSONObject info=response.optJSONObject("info");
    if(info==null)info=response.optJSONObject("movie_data");
    if(info==null)info=response;
    String plot=info.optString("plot","");
    if(plot.isEmpty())plot=info.optString("description","");
    if(plot.isEmpty())plot=info.optString("overview","");
    StringBuilder detail=new StringBuilder();
    String year=info.optString("releasedate",info.optString("releaseDate",""));
    String genre=info.optString("genre","");
    String rating=info.optString("rating","");
    if(!year.isEmpty())detail.append(year.length()>10?year.substring(0,10):year);
    if(!genre.isEmpty()){
     if(detail.length()>0)detail.append("  ·  ");
     detail.append(genre);
    }
    if(!rating.isEmpty()){
     if(detail.length()>0)detail.append("  ·  ");
     detail.append("Rating ").append(rating);
    }
    if(!plot.isEmpty()){
     if(detail.length()>0)detail.append("\n\n");
     detail.append(plot.substring(0,Math.min(750,plot.length())));
    }
    return detail.toString().trim();
   }
  }finally{connection.disconnect();}
 }
 public static List<LibraryCore.Item> episodes(LibraryCore.Item series)throws Exception{JSONObject root=new JSONObject(get(series.url));JSONObject seasons=root.getJSONObject("episodes");List<LibraryCore.Item> out=new ArrayList<>();List<String> keys=new ArrayList<>();seasons.keys().forEachRemaining(keys::add);Collections.sort(keys,(a,b)->{try{return Integer.compare(Integer.parseInt(a),Integer.parseInt(b));}catch(NumberFormatException ex){return a.compareTo(b);}});URL u=new URL(series.url);Map<String,String> args=new HashMap<>();for(String q:u.getQuery().split("&")){String[] p=q.split("=",2);if(p.length==2)args.put(p[0],URLDecoder.decode(p[1],"UTF-8"));}String host=series.url.substring(0,series.url.indexOf("/player_api.php"));for(String key:keys){JSONArray eps=seasons.getJSONArray(key);for(int i=0;i<eps.length();i++){JSONObject e=eps.getJSONObject(i);String id=e.optString("id");out.add(new LibraryCore.Item(series.id+"|"+id,"S"+key+" · E"+e.optString("episode_num")+"  "+e.optString("title"),series.category,host+"/series/"+segment(args.get("username"))+"/"+segment(args.get("password"))+"/"+id+"."+e.optString("container_extension","mp4"),"episode","",""));}}return out;}
 public static class Program { public String channel,title;public long start,end; }
 public static List<Program> epg(String url)throws Exception{String xml=get(base(url));XmlPullParser p=Xml.newPullParser();p.setInput(new StringReader(xml));List<Program> out=new ArrayList<>();Program current=null;for(int event=p.getEventType();event!=XmlPullParser.END_DOCUMENT;event=p.next()){if(event==XmlPullParser.START_TAG&&p.getName().equals("programme")){current=new Program();current.channel=p.getAttributeValue(null,"channel");current.start=date(p.getAttributeValue(null,"start"));current.end=date(p.getAttributeValue(null,"stop"));}else if(event==XmlPullParser.START_TAG&&p.getName().equals("title")&&current!=null)current.title=p.nextText();else if(event==XmlPullParser.END_TAG&&p.getName().equals("programme")&&current!=null){out.add(current);current=null;}}return out;}
 static long date(String s)throws Exception{SimpleDateFormat f=new SimpleDateFormat(s.length()>14?"yyyyMMddHHmmss Z":"yyyyMMddHHmmss",Locale.US);f.setTimeZone(TimeZone.getTimeZone("UTC"));return f.parse(s).getTime();}
}
