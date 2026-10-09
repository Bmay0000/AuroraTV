import tv.aurora.player.LibraryCore;
import tv.aurora.player.ChannelDiscovery;

import java.util.*;

public class ChannelDiscoveryTest{
 static int checks;
 static LibraryCore.Item channel(String name,String category,String language){
  return new LibraryCore.Item(LibraryCore.key(name+"|"+category),
      name,category,"","live","",language);
 }
 static void check(boolean ok,String reason){
  checks++;if(!ok)throw new AssertionError(reason);
 }
 public static void main(String[] args){
  LibraryCore.Item cnn=channel("|US| CNN HD","USA | NEWS","");
  LibraryCore.Item espn=channel("ESPN HD","NA | SPORTS","");
  LibraryCore.Item espn2=channel("|US| ESPN2 FHD","USA SPORTS","");
  LibraryCore.Item nba=channel("NBA TV","US Sports","");
  LibraryCore.Item fox=channel("FOX News Channel","USA NEWS","");
  LibraryCore.Item other=channel("NESN New England Sports Network","US Sports","");
  LibraryCore.Item uk=channel("BBC One HD","UK","");
  LibraryCore.Item persian=channel("IR - Salam Amadim Nabood","VOD IRAN","");
  LibraryCore.Item french=channel("[FR] TF1","FR","fr");
  check(ChannelDiscovery.satelliteNumber(cnn)==202,"US CNN number");
  check(ChannelDiscovery.satelliteNumber(espn)==206,"ESPN reference");
  check(ChannelDiscovery.satelliteNumber(espn2)==209,"ESPN2 not ESPN");
  check(ChannelDiscovery.satelliteNumber(nba)==216,"NBA TV network preserved");
  check(ChannelDiscovery.satelliteNumber(fox)==360,"FOX News reference");
  check(ChannelDiscovery.satelliteNumber(persian)==0,"No invented satellite number");
  check(ChannelDiscovery.group(cnn).equals("North America"),"North America primary");
  check(ChannelDiscovery.group(other).equals("North America"),"NA regional add-on");
  check(ChannelDiscovery.group(uk).equals("Other English"),"UK after NA");
  check(ChannelDiscovery.group(persian).equals("International"),"IR Farsi international");
  check(ChannelDiscovery.group(french).equals("International"),"French international");
  List<LibraryCore.Item> channels=new ArrayList<>(Arrays.asList(
      persian,uk,other,fox,nba,espn2,espn,cnn,french));
  channels.sort(ChannelDiscovery::compare);
  check(channels.get(0)==cnn,"First national station by published channel order");
  check(channels.get(1)==espn && channels.get(2)==espn2,"ESPN family order");
  check(channels.indexOf(other)>channels.indexOf(fox),"NA regional beyond main national lineup");
  check(channels.indexOf(uk)>channels.indexOf(other),"English UK after North America");
  check(channels.indexOf(french)>channels.indexOf(uk),"Foreign stations follow English");
  check(ChannelDiscovery.matchesGuideSection(cnn,"News"),"News chip works");
  check(ChannelDiscovery.matchesGuideSection(espn,"Sports"),"Sports chip works");
  check(!ChannelDiscovery.matchesGuideSection(french,"North America"),"Foreign excluded from NA");
  check(ChannelDiscovery.matchesGuideSection(persian,"International"),"Persian in International");
  check(ChannelDiscovery.matchesGuideSection(cnn,"All"),"All permits any language");
  check(ChannelDiscovery.matchesGuideSection(other,"North America"),"Other NA channels at end");
  check(ChannelDiscovery.matchesGuideSection(uk,"English"),"English-only tab");
  check(!ChannelDiscovery.matchesGuideSection(persian,"English"),"No foreign in English");
  check(LibraryCore.language(persian).equals("other"),"IR category/title detected as foreign");
  check(LibraryCore.language(channel("Movie","IR - MOVIES","")).equals("other"),"IR region clue");
  // Stress an actual provider pattern: 1,200 numbered ESPN+ events mixed
  // with multiple HD/FHD/4K renditions of the *same* ESPN network.
  List<LibraryCore.Item> crowded=new ArrayList<>();
  crowded.add(channel("USA | ESPN HD","USA SPORTS","en"));
  LibraryCore.Item best=channel("USA | ESPN FHD","USA SPORTS","en");
  best.epgId="espn.us";
  crowded.add(best);
  crowded.add(channel("[US] ESPN UHD/4K","US SPORTS","en"));
  crowded.add(cnn);
  crowded.add(espn2);
  crowded.add(channel("ESPN 2 HD","USA SPORTS","en"));
  crowded.add(fox);
  crowded.add(nba);
  crowded.add(other);
  for(int number=1;number<=1200;number++){
   crowded.add(channel("[US] ESPN+ "+number+" HD","USA SPORTS","en"));
  }
  List<LibraryCore.Item> curated=ChannelDiscovery.curate(crowded,"North America",false);
  int primaryEspn=0,plusEvents=0,espn2Count=0;
  for(LibraryCore.Item item:curated){
   if(ChannelDiscovery.satelliteNumber(item)==206){
    primaryEspn++;
    check(item==best,"Prefer reliable FHD stream with EPG over 4K or HD duplicate");
   }
   if(ChannelDiscovery.satelliteNumber(item)==209)espn2Count++;
   if(item.name.contains("ESPN+"))plusEvents++;
  }
  check(primaryEspn==1,"Exactly one primary ESPN channel");
  check(espn2Count==1,"ESPN2 is separate but its HD duplicates are collapsed");
  check(plusEvents==1,"Only one ESPN+ service entry from the official limited 210 feeds");
  check(ChannelDiscovery.satelliteNumber(
    channel("ESPN+ 6 HD","US SPORTS","en"))==210,"DIRECTV ESPN+ subfeed near 210");
  check(ChannelDiscovery.satelliteNumber(
    channel("ESPN+ 1200 HD","US SPORTS","en"))==0,
    "Numbered provider event must not steal the 210 reference");
  check(curated.size()<=90,"Default satellite guide is not thousands of provider event feeds");
  List<LibraryCore.Item> allStreams=ChannelDiscovery.curate(crowded,"All",false);
  check(allStreams.size()==crowded.size(),"All Streams preserves every alternate");
  List<LibraryCore.Item> searched=ChannelDiscovery.curate(crowded,"North America",true);
  check(searched.size()==crowded.size(),"Explicit search exposes alternates");
  check(ChannelDiscovery.renditionScore(best)>
    ChannelDiscovery.renditionScore(crowded.get(0)),"Stream quality and EPG affect default selection");
  check(ChannelDiscovery.lineupIdentity(best).equals(
    ChannelDiscovery.lineupIdentity(crowded.get(0))),"HD and FHD share one network identity");
  check(!ChannelDiscovery.lineupIdentity(best).equals(
    ChannelDiscovery.lineupIdentity(espn2)),"ESPN and ESPN2 never share identity");
  System.out.println(checks+" channel lineup/region ordering tests passed");
 }
}
