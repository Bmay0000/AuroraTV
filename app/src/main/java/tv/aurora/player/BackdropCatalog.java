package tv.aurora.player;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

/** Bounded background-only lookup for real widescreen TMDB backdrops. */
public final class BackdropCatalog {
 private BackdropCatalog(){}
 public static final class Info {
  public final String backdrop,overview,genres; public final double rating;
  Info(String image,String description,String tags,double score){
   backdrop=image;overview=description;genres=tags;rating=score;
  }
 }
 public static Info info(Context ctx,String key,String title,int year,boolean series){
  if(key==null||key.trim().isEmpty()||title==null||title.isEmpty())
   return new Info("","","",0);
  String clean=title.replaceFirst("(?i)^(?:EN|ENG|US|UK|AU|NZ)\\s*[-|:]\\s*","")
   .replaceFirst("(?i)\\s*\\(\\d{4}\\)\\s*$","");
  String id=LibraryCore.key("detail2|"+(series?"tv":"movie")+"|"+clean+"|"+year+"|"+key);
  SharedPreferences prefs=ctx.getSharedPreferences("aurora_backdrops",Context.MODE_PRIVATE);
  String cached=prefs.getString(id,null);
  if(cached!=null)try{return parseInfo(new JSONObject(cached));}catch(Exception ignored){}
  HttpURLConnection con=null;JSONObject match=null;
  try{
   String media=series?"tv":"movie";
   String address="https://api.themoviedb.org/3/search/"+media+
    "?api_key="+URLEncoder.encode(key,"UTF-8")+"&language=en-US&query="+
    URLEncoder.encode(clean,"UTF-8")+
    (year>0?(series?"&first_air_date_year=":"&year=")+year:"");
   con=(HttpURLConnection)new URL(address).openConnection();
   con.setConnectTimeout(2900);con.setReadTimeout(3700);
   if(con.getResponseCode()==200){
    byte[] buf=new byte[2048];int n;ByteArrayOutputStream out=new ByteArrayOutputStream();
    try(InputStream in=con.getInputStream()){
     while((n=in.read(buf))!=-1&&out.size()+n<=130000)out.write(buf,0,n);
    }
    JSONArray rows=new JSONObject(out.toString("UTF-8")).optJSONArray("results");
    if(rows!=null)for(int i=0;i<Math.min(8,rows.length());i++){
     JSONObject row=rows.optJSONObject(i);if(row==null)continue;
     String norm=TrendingCatalog.normalize(clean);
     if(!norm.equals(TrendingCatalog.normalize(row.optString(series?"name":"title","")))
       &&!norm.equals(TrendingCatalog.normalize(row.optString(series?"original_name":"original_title",""))))continue;
     match=row;break;
    }
   }
  }catch(Exception ignored){}finally{if(con!=null)con.disconnect();}
  if(match==null)match=new JSONObject();
  prefs.edit().putString(id,match.toString()).apply();
  return parseInfo(match);
 }
 private static Info parseInfo(JSONObject row){
  String path=row.optString("backdrop_path","");
  String backdrop=path.startsWith("/")&&!path.contains("..")?
      "https://image.tmdb.org/t/p/w1280"+path:"";
  String plot=row.optString("overview","");
  double rating=row.optDouble("vote_average",0);
  JSONArray ids=row.optJSONArray("genre_ids");StringBuilder tags=new StringBuilder();
  if(ids!=null)for(int i=0;i<ids.length();i++){
   int id=ids.optInt(i,0);String name="";
   switch(id){
    case 28:name="Action";break;case 12:name="Adventure";break;
    case 18:name="Drama";break;case 35:name="Comedy";break;
    case 878:name="Science Fiction";break;case 9648:name="Mystery";break;
    case 53:name="Thriller";break;case 10765:name="Sci-Fi & Fantasy";break;
    case 80:name="Crime";break;case 27:name="Horror";break;
    case 16:name="Animation";break;case 10751:name="Family";break;
    case 10759:name="Action & Adventure";break;case 99:name="Documentary";break;
   }
   if(!name.isEmpty()&&tags.length()<38){
    if(tags.length()>0)tags.append("  ·  ");
    tags.append(name);
   }
  }
  return new Info(backdrop,plot,tags.toString(),rating);
 }
 public static String lookup(Context ctx, String key,String title,int year,boolean series){
  if(key==null||key.trim().isEmpty()||title==null||title.isEmpty())return "";
  String clean=title.replaceFirst("(?i)^(?:EN|ENG|US|UK|AU|NZ)\\s*[-|:]\\s*","")
     .replaceFirst("(?i)\\s*\\(\\d{4}\\)\\s*$","");
  String id=LibraryCore.key((series?"tv":"movie")+"|"+clean+"|"+year+"|"+key);
  SharedPreferences prefs=ctx.getSharedPreferences("aurora_backdrops",Context.MODE_PRIVATE);
  String cached=prefs.getString(id,null);
  if(cached!=null)return cached;
  String backdrop="";
  HttpURLConnection con=null;
  try{
   String media=series?"tv":"movie";
   String address="https://api.themoviedb.org/3/search/"+media+
      "?api_key="+URLEncoder.encode(key,"UTF-8")+
      "&language=en-US&query="+URLEncoder.encode(clean,"UTF-8")+
      (year>0?(series?"&first_air_date_year=":"&year=")+year:"");
   con=(HttpURLConnection)new URL(address).openConnection();
   con.setConnectTimeout(3200);con.setReadTimeout(3500);
   if(con.getResponseCode()==200){
    byte[] buf=new byte[2048];int n;ByteArrayOutputStream out=new ByteArrayOutputStream();
    try(InputStream in=con.getInputStream()){
     while((n=in.read(buf))!=-1&&out.size()<130000)out.write(buf,0,n);
    }
    JSONArray results=new JSONObject(out.toString("UTF-8")).optJSONArray("results");
    if(results!=null)for(int i=0;i<Math.min(5,results.length());i++){
     JSONObject row=results.optJSONObject(i);if(row==null)continue;
     String name=row.optString(series?"name":"title","");
     String original=row.optString(series?"original_name":"original_title","");
     String norm=TrendingCatalog.normalize(clean);
     if(!norm.equals(TrendingCatalog.normalize(name))&&
        !norm.equals(TrendingCatalog.normalize(original)))continue;
     String path=row.optString("backdrop_path","");
     if(path.startsWith("/")&&!path.contains("..")){
      backdrop="https://image.tmdb.org/t/p/w780"+path;break;
     }
    }
   }
  }catch(Exception ignored){}finally{if(con!=null)con.disconnect();}
  prefs.edit().putString(id,backdrop).apply();
  return backdrop;
 }
}