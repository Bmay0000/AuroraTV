import tv.aurora.player.XtreamReference;
import tv.aurora.player.LibraryCore;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class XtreamReferenceTest{
 private static int checks;
 static void require(boolean ok,String message){
  checks++;
  if(!ok)throw new AssertionError(message);
 }
 public static void main(String[] args)throws Exception{
  String tv=XtreamReference.of("live","19021","ts");
  require(tv.equals("aurora-xtream:live:19021:ts"),"Plain reference must not contain credentials");
  require(XtreamReference.url(tv,"https://iptv.example:8080","alice","p+a ss")
    .equals("https://iptv.example:8080/live/alice/p%2Ba%20ss/19021.ts"),
    "Live URL must be reconstructed with encoded credentials");
  require(XtreamReference.url(XtreamReference.of("movie","408","mkv"),
    "http://iptv.example/","a@b","secret")
    .equals("http://iptv.example/movie/a%40b/secret/408.mkv"),"Movie URL");
  require(XtreamReference.url(XtreamReference.of("series","123","ignored"),
    "https://iptv.example","usr","pwd")
    .equals("https://iptv.example/player_api.php?username=usr&password=pwd&action=get_series_info&series_id=123"),
    "Series URL and episodes endpoint");
  require(XtreamReference.of("movie","!bad!","mp4")==null,"Reject unsafe stream id");
  require(XtreamReference.of("live","23","../../etc")==null,"Reject unsafe extension");
  require(XtreamReference.of("unknown","23","mp4")==null,"Reject unknown media type");
  boolean rejected=false;
  try{XtreamReference.url(tv,"file:///tmp","user","pass");}
  catch(IllegalArgumentException expected){rejected=true;}
  require(rejected,"Reject local URL protocol");
  rejected=false;
  try{XtreamReference.url("aurora-xtream:live:123:../../bad","https://s","u","p");}
  catch(IllegalArgumentException expected){rejected=true;}
  require(rejected,"Cannot inject file paths into internal reference");
  String[] examples={"foo","https://example/live/1","",null};
  for(String e:examples)require(!XtreamReference.isReference(e),"Only tagged metadata is recognized");
  // 151,394 title fixture: validate that each reference is short and safe to
  // persist without encrypting a full credential-bearing URL per item.
  long started=System.nanoTime();
  for(int n=0;n<151394;n++){
   String kind=n%3==0?"live":n%3==1?"movie":"series";
   String reference=XtreamReference.of(kind,Integer.toString(n+1),"mp4");
   require(reference!=null && reference.length()<90,"Compact reference "+n);
   require(!reference.contains("password"),"Must never store password");
  }
  long duration=(System.nanoTime()-started)/1000000;
  System.out.println(checks+" reference correctness and large-library tests passed ("+
    duration+" ms for 151394 simulated titles)");
 }
}