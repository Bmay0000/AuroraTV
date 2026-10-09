import tv.aurora.player.MediaDiscovery;
import tv.aurora.player.LibraryCore;

public final class MediaDiscoveryTest {
 private static int cases;
 static void check(boolean value,String why){
  cases++;
  if(!value)throw new AssertionError(why);
 }
 static LibraryCore.Item movie(String name,String category,String language){
  return new LibraryCore.Item("id",name,category,"","movie","",language);
 }
 public static void main(String[] args){
  final int today=2026;
  check(MediaDiscovery.releaseYear("2026-10-02",today)==2026,"explicit release date");
  check(MediaDiscovery.releaseYear("2025",today)==2025,"release year");
  check(MediaDiscovery.releaseYear("2027",today)==2027,"near-future announced release");
  check(MediaDiscovery.releaseYear("2029",today)==0,"reject invalid distant date");
  check(MediaDiscovery.releaseYear("unknown",today)==0,"reject unknown release");
  check(MediaDiscovery.releaseYear("0",today)==0,"reject zero");
  check(MediaDiscovery.yearFromTitle("An Example Movie (2026)",today)==2026,"terminal parenthesized year");
  check(MediaDiscovery.yearFromTitle("Another Film [2025]",today)==2025,"terminal bracket year");
  check(MediaDiscovery.yearFromTitle("Another Film [2025] HD",today)==2025,"quality suffix");
  check(MediaDiscovery.yearFromTitle("A Movie 2026",today)==2026,"plain release year suffix");
  check(MediaDiscovery.yearFromTitle("[EN] Great Film 2025 HD",today)==2025,"older category title style");
  check(MediaDiscovery.yearFromTitle("2001: A Space Odyssey",today)==0,"do not infer year from film title");
  check(MediaDiscovery.yearFromTitle("Movie 2026 Remaster",today)==0,"plain year in title not release evidence");
  check(MediaDiscovery.yearFromTitle("A 2026.",today)==0,"no unbracketed year");
  check(MediaDiscovery.recent(2026,today),"current-year recent");
  check(MediaDiscovery.recent(2025,today),"previous year recent");
  check(MediaDiscovery.recent(2024,today),"two-year window recent");
  check(!MediaDiscovery.recent(2023,today),"old release excluded");
  check(!MediaDiscovery.recent(2027,today),"upcoming movie not released");
  check(MediaDiscovery.confirmedEnglish(movie("Movie (2026)","UK | ACTION","")),"UK category is English evidence");
  check(MediaDiscovery.confirmedEnglish(movie("Movie (2026)","Comedies","eng")),"explicit English language");
  check(!MediaDiscovery.confirmedEnglish(movie("Movie (2026)","Movies","")),"English must not be assumed");
  check(MediaDiscovery.knownForeign(movie("Film (2026)","[FR] Movies","")),"foreign code recognition");
  check(!MediaDiscovery.knownForeign(movie("Movie (2026)","Drama","")),"unknown is not foreign");
  check(MediaDiscovery.genre("[US] ACTION MOVIES").equals("ACTION"),"provider action genre");
  check(MediaDiscovery.genre("UK • Romantic Comedies").equals("COMEDY"),"comedy category");
  check(MediaDiscovery.genre("HD | Science Fiction").equals("SCI-FI"),"sci-fi category");
  check(MediaDiscovery.genre("Spanish Movies").isEmpty(),"no invented genre");
  check(MediaDiscovery.genre("Distraction").isEmpty(),"avoid substring genre false positives");
  check(MediaDiscovery.parseRating("8.1")==8.1,"rating parser");
  check(MediaDiscovery.parseRating("14.4")==0,"reject invalid rating");
  check(MediaDiscovery.parseRating("not rated")==0,"reject unknown rating");
  System.out.println(cases+" media discovery recommendations tests passed");
 }
}
