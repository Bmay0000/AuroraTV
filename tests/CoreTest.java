import tv.aurora.player.LibraryCore;
import java.util.*;

public class CoreTest {
 static int cases=0;
 static void check(boolean ok,String message){
  cases++;if(!ok)throw new AssertionError("Test "+cases+": "+message);
 }
 static LibraryCore.Item item(String id,String name,String category,String language){
  return new LibraryCore.Item(id,name,category,"https://example.com/"+id,"live","",language);
 }
 public static void main(String[] args){
  String playlist="#EXTM3U\n"+
   "#EXTINF:-1 tvg-id=\"bbc\" group-title=\"UK | News\",BBC News HD\nhttps://example.com/live/a.m3u8\n"+
   "#EXTINF:-1 group-title=\"FR | News\" tvg-language=\"French\",France 24\nhttps://example.com/live/b\n"+
   "#EXTINF:-1 group-title=\"Movies, Drama\" media-type=\"movie\",A film\nhttps://example.com/movie/test.mp4\n";
  List<LibraryCore.Item> entries=LibraryCore.m3u(playlist,"provider");
  check(entries.size()==3,"parse M3U");
  check(entries.get(2).category.equals("Movies, Drama"),"comma in group");
  check(entries.get(2).type.equals("movie"),"movie metadata");
  check(LibraryCore.language(entries.get(0)).equals("en"),"UK metadata English");
  check(LibraryCore.language(entries.get(1)).equals("fr"),"explicit French");
  check(LibraryCore.language(item("x","ITV","UK | TV","")).equals("en"),"UK category English");
  check(LibraryCore.language(item("x","News","Germany | Channels","")).equals("de"),"Germany category");
  check(LibraryCore.language(item("x","News","Italy | Sports","")).equals("it"),"Italian filter");
  check(LibraryCore.language(item("x","News","Portugal","")).equals("pt"),"Portuguese filter");
  check(LibraryCore.language(item("x","News","Poland","")).equals("other"),"recognized foreign group");
  check(LibraryCore.language(item("x","News","Russia","")).equals("ru"),"Russian group");
  check(LibraryCore.language(item("x","News","Spain","")).equals("es"),"Spanish group");
  check(LibraryCore.language(item("x","News","Argentina","es")).equals("es"),"explicit language");
  check(LibraryCore.language(item("x","UK French","UK","fr")).equals("fr"),"explicit overrides inferred");
  check(LibraryCore.language(item("x","Classical Music","Uncategorized","")).equals("unknown"),"don't match US in music");
  check(LibraryCore.language(item("x","International Cinema","International","")).equals("unknown"),"don't match IN in International");
  check(LibraryCore.language(item("x","Global TV","Uncategorized","")).equals("unknown"),"unrecognized title");
  Set<String> none=Collections.emptySet(),english=Set.of("en");
  check(LibraryCore.visible(entries.get(0),none,none,none,english,true),"English shown");
  check(!LibraryCore.visible(entries.get(1),none,none,none,english,true),"French hidden by English");
  check(!LibraryCore.visible(item("x","Global TV","Uncategorized",""),none,none,none,english,true),"unknown hidden in strict");
  check(LibraryCore.visible(item("x","Global TV","Uncategorized",""),none,none,none,english,false),"unknown shown with relaxed setting");
  check(LibraryCore.visible(entries.get(1),none,none,Set.of(entries.get(1).id),english,true),"favorites protected");
  check(!LibraryCore.visible(entries.get(1),Set.of(entries.get(1).id),none,Set.of(entries.get(1).id),english,true),"explicit hide defeats favorite");
  check(!LibraryCore.visible(entries.get(0),none,Set.of("live|UK | News"),none,english,true),"hidden category");
  check(LibraryCore.visible(entries.get(0),none,Set.of("live|UK | News"),none,english,true,
      Set.of(entries.get(0).id),none),"manual item visibility defeats hidden category");
  check(LibraryCore.visible(entries.get(1),none,none,none,english,true,
      Set.of(entries.get(1).id),none),"manual restore defeats French filter");
  check(!LibraryCore.visible(entries.get(1),Set.of(entries.get(1).id),none,none,english,true,
      Set.of(entries.get(1).id),none),"explicit title hide always wins");
  check(LibraryCore.visible(entries.get(1),none,Set.of("live|FR | News"),none,english,true,
      none,Set.of("live|FR | News")),"manual category restore defeats both filters");
  check(LibraryCore.visible(entries.get(1),none,none,none,none,true),"no language choice disables automatic filter");
  check(!LibraryCore.visible(entries.get(1),none,Set.of("live|FR | News"),none,none,true),"manual hidden category applies even without filter");
  check(!LibraryCore.visible(entries.get(2),none,none,none,english,true),"unclassified movies hidden by strict English");
  LibraryCore.Item ukMovie=new LibraryCore.Item("mv-en","The Film","UK | Films","https://example.com/vod","movie","","");
  LibraryCore.Item foreignMovie=new LibraryCore.Item("mv-fr","Le Film","FR | Films","https://example.com/vod","movie","","");
  LibraryCore.Item ukSeries=new LibraryCore.Item("tv-en","The Show","US | Series","https://example.com/series","series","","");
  LibraryCore.Item foreignSeries=new LibraryCore.Item("tv-es","El Show","Spain | Series","https://example.com/series","series","","");
  check(LibraryCore.visible(ukMovie,none,none,none,english,true),"English movie kept");
  check(!LibraryCore.visible(foreignMovie,none,none,none,english,true),"French movie hidden");
  check(LibraryCore.visible(ukSeries,none,none,none,english,true),"English series kept");
  check(!LibraryCore.visible(foreignSeries,none,none,none,english,true),"Spanish series hidden");
  check(LibraryCore.visible(foreignMovie,none,none,none,english,true,
      Set.of(foreignMovie.id),none),"restore one French movie without changing whole library");
  check(LibraryCore.visible(foreignSeries,none,none,none,english,true,
      none,Set.of("series|Spain | Series")),"restore full foreign TV series category");
  check(!LibraryCore.visible(ukMovie,none,Set.of("movie|UK | Films"),none,english,true),
      "hide English movie category manually");
  check(!LibraryCore.visible(foreignMovie,none,none,none,english,false),
      "identified French movies stay hidden when unknown are permitted");
  check(LibraryCore.visible(entries.get(2),none,none,none,english,false),
      "unknown movie remains discoverable when strict mode is disabled");
  check(LibraryCore.visible(entries.get(2),none,none,none,none,true),
      "language filtering off displays movies");
  check(LibraryCore.m3u(playlist.replace("https://example.com/live/b","https://example.com/changed"),
     "provider").get(1).id.equals(entries.get(1).id),"stable ids");
  System.out.println(cases+" AuroraTV core tests passed");
 }
}