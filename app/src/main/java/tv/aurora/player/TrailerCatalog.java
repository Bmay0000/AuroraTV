package tv.aurora.player;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** Fetches only official, publicly embeddable YouTube trailers listed by TMDB. */
public final class TrailerCatalog {
 private TrailerCatalog(){}
 static JSONObject get(String url) throws Exception{
  HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();
  try{
   connection.setConnectTimeout(3000);
   connection.setReadTimeout(3500);
   connection.setRequestProperty("Accept","application/json");
   if(connection.getResponseCode()!=200)return null;
   ByteArrayOutputStream out=new ByteArrayOutputStream();
   byte[] buf=new byte[4096];int n;
   try(InputStream in=connection.getInputStream()){
    while((n=in.read(buf))!=-1){
     if(out.size()+n>120000)return null;
     out.write(buf,0,n);
    }
   }
   return new JSONObject(out.toString("UTF-8"));
  }finally{connection.disconnect();}
 }
 public static String find(Context context,String apiKey,String title,int year,boolean series){
  if(apiKey==null||apiKey.isEmpty()||title==null||title.isEmpty())return "";
  String clean=title.replaceFirst("(?i)^(?:EN|ENG|US|UK|AU|NZ)\\s*[-|:]\\s*","")
    .replaceFirst("(?i)\\s*\\(\\d{4}\\)\\s*$","");
  String cacheKey=LibraryCore.key("trailer|"+series+"|"+clean+"|"+year+"|"+apiKey);
  SharedPreferences pref=context.getSharedPreferences("aurora_trailers",Context.MODE_PRIVATE);
  String previous=pref.getString(cacheKey,null);
  if(previous!=null)return previous;
  String found="";
  try{
   String media=series?"tv":"movie";
   String root="https://api.themoviedb.org/3/";
   String auth="?api_key="+URLEncoder.encode(apiKey,"UTF-8")+"&language=en-US";
   JSONObject search=get(root+"search/"+media+auth+"&query="+URLEncoder.encode(clean,"UTF-8")+
       (year>0?(series?"&first_air_date_year=":"&year=")+year:""));
   if(search!=null){
    JSONArray rows=search.optJSONArray("results");
    if(rows!=null)for(int i=0;i<Math.min(6,rows.length());i++){
     JSONObject row=rows.optJSONObject(i);if(row==null)continue;
     String norm=TrendingCatalog.normalize(clean);
     if(!norm.equals(TrendingCatalog.normalize(row.optString(series?"name":"title","")))
       &&!norm.equals(TrendingCatalog.normalize(row.optString(series?"original_name":"original_title",""))))
      continue;
     int id=row.optInt("id",0);if(id<=0)continue;
     JSONObject videos=get(root+media+"/"+id+"/videos"+auth);
     JSONArray clips=videos==null?null:videos.optJSONArray("results");
     if(clips==null)break;
     for(int j=0;j<clips.length();j++){
      JSONObject clip=clips.optJSONObject(j);if(clip==null)continue;
      if(!"YouTube".equalsIgnoreCase(clip.optString("site","")))continue;
      if(!"Trailer".equalsIgnoreCase(clip.optString("type","")))continue;
      String key=clip.optString("key","");
      if(key.matches("[a-zA-Z0-9_-]{11}")){found=key;break;}
     }
     break;
    }
   }
  }catch(Exception ignored){}
  pref.edit().putString(cacheKey,found).apply();
  return found;
 }
}