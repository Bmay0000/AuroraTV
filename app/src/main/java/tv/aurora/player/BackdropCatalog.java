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