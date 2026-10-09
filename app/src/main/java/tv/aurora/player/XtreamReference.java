package tv.aurora.player;

import java.net.URL;
import java.net.URLEncoder;

/**
 * A compact, non-secret reference to Xtream media. The large provider catalog
 * never has to encrypt/cache a credential-bearing URL for each individual title.
 * Credentials stay sealed once in SharedPreferences and are only recovered
 * when the user opens a title.
 */
public final class XtreamReference {
    private static final String PREFIX="aurora-xtream:";
    private XtreamReference(){}

    public static String of(String kind,String streamId,String ext){
        if(!("live".equals(kind)||"movie".equals(kind)||"series".equals(kind)))return null;
        if(streamId==null||!streamId.matches("[A-Za-z0-9_-]{1,64}"))return null;
        if("series".equals(kind))return PREFIX+"series:"+streamId+":none";
        if(ext==null||!ext.matches("[A-Za-z0-9]{1,8}"))return null;
        return PREFIX+kind+":"+streamId+":"+ext;
    }
    public static boolean isReference(String text) {
        return text!=null&&text.startsWith(PREFIX);
    }
    public static String url(String reference,String server,String username,String password)throws Exception{
        if(!isReference(reference))throw new IllegalArgumentException("Not an Xtream reference");
        String[] components=reference.substring(PREFIX.length()).split(":",-1);
        if(components.length!=3)throw new IllegalArgumentException("Invalid stream reference");
        String canonical=of(components[0],components[1],components[2]);
        if(!reference.equals(canonical))throw new IllegalArgumentException("Invalid media reference");
        URL serverUrl=new URL(server);
        if(!serverUrl.getProtocol().equals("http")&&!serverUrl.getProtocol().equals("https"))
            throw new IllegalArgumentException("Invalid IPTV server protocol");
        String base=server.replaceAll("/+$","");
        String user=enc(username),pass=enc(password);
        if("series".equals(components[0])){
            return base+"/player_api.php?username="+user+"&password="+pass+
                "&action=get_series_info&series_id="+enc(components[1]);
        }
        String path="movie".equals(components[0])?"movie":"live";
        return base+"/"+path+"/"+segment(username)+"/"+segment(password)+"/"+
            components[1]+"."+components[2];
    }
    private static String enc(String input)throws Exception{
        return URLEncoder.encode(input,"UTF-8");
    }
    private static String segment(String input)throws Exception{
        return enc(input).replace("+","%20");
    }
}
