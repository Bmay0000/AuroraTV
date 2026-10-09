package tv.aurora.player;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** TMDB daily rankings; network/JSON work must run off the UI thread. */
public final class TrendingCatalog {
    private static final long TTL = 24L * 60L * 60L * 1000L;
    public static final class Entry {
        public final String title, originalTitle;
        public final int year, rank;
        Entry(String t,String o,int y,int r){title=t;originalTitle=o;year=y;rank=r;}
    }
    private TrendingCatalog(){}
    public static String normalize(String name){
        if(name==null)return "";
        return name.toLowerCase(Locale.ROOT).replaceAll("\\s*\\((19|20)\\d{2}\\)\\s*$","")
            .replaceAll("\\s*\\[(19|20)\\d{2}\\]\\s*$","")
            .replaceAll("\\s+(4k|uhd|fhd|hd|sd)$","")
            .replaceAll("[^\\p{L}\\p{N}]","").trim();
    }
    public static boolean matches(Entry entry,LibraryCore.Item item){
        if(item==null || entry==null)return false;
        String candidate=normalize(item.name);
        if(candidate.isEmpty() || !(candidate.equals(normalize(entry.title))
            ||candidate.equals(normalize(entry.originalTitle))))return false;
        return entry.year==0 || item.releaseYear==0 || Math.abs(item.releaseYear-entry.year)<=1;
    }
    public static List<Entry> parse(String json,boolean movies) throws Exception {
        JSONArray results=new JSONObject(json).getJSONArray("results");
        List<Entry> entries=new ArrayList<>();
        for(int i=0;i<results.length() && entries.size()<20;i++){
            JSONObject row=results.optJSONObject(i);
            if(row==null)continue;
            String lang=row.optString("original_language","");
            if(!"en".equals(lang))continue;
            String title=row.optString(movies?"title":"name","");
            if(title.isEmpty())continue;
            String original=row.optString(movies?"original_title":"original_name",title);
            String date=row.optString(movies?"release_date":"first_air_date","");
            int year=0;
            if(date.length()>=4)try{year=Integer.parseInt(date.substring(0,4));}
                catch(NumberFormatException ignored){}
            entries.add(new Entry(title,original,year,i+1));
        }
        return entries;
    }
    public static List<Entry> load(Context context,String type,String apiKey)throws Exception{
        boolean movies="movie".equals(type);
        if(apiKey==null || apiKey.trim().isEmpty())return new ArrayList<>();
        String key=movies?"movie":"tv";
        SharedPreferences p=context.getSharedPreferences("trending",Context.MODE_PRIVATE);
        String cached=p.getString(key+".json","");
        long fetched=p.getLong(key+".fetched",0L);
        if(!cached.isEmpty() && System.currentTimeMillis()-fetched<TTL)
            try{return parse(cached,movies);}catch(Exception ignored){}
        HttpURLConnection connection=null;
        try{
            URL url=new URL("https://api.themoviedb.org/3/trending/"+key+"/day?language=en-US&api_key="
                +java.net.URLEncoder.encode(apiKey.trim(),"UTF-8"));
            connection=(HttpURLConnection)url.openConnection();
            connection.setConnectTimeout(4500);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("Accept","application/json");
            if(connection.getResponseCode()!=200)throw new java.io.IOException("TMDB HTTP "+connection.getResponseCode());
            try(InputStream in=connection.getInputStream()){
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                byte[] buf=new byte[4096];int count;
                while((count=in.read(buf))!=-1){
                    if(bytes.size()+count>300000)throw new java.io.IOException("Response too large");
                    bytes.write(buf,0,count);
                }
                String body=bytes.toString("UTF-8");
                List<Entry> parsed=parse(body,movies);
                p.edit().putString(key+".json",body)
                    .putLong(key+".fetched",System.currentTimeMillis()).apply();
                return parsed;
            }
        }catch(Exception ex){
            if(!cached.isEmpty())try{return parse(cached,movies);}catch(Exception ignored){}
            throw ex;
        }finally{if(connection!=null)connection.disconnect();}
    }
}