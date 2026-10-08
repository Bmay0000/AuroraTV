package tv.aurora.player;

import java.util.*;
import java.util.regex.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class LibraryCore {
 public static class Item {
  public String id,name,category,url,type,epgId,language,artwork="";
  public Item(String id,String name,String category,String url,String type,String epgId,String language){
   this.id=id;this.name=name;this.category=category;this.url=url;this.type=type;this.epgId=epgId;this.language=language;
  }
 }
 public static String key(String text){
  try{
   byte[] b=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
   StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format("%02x",x));return s.toString();
  }catch(Exception e){throw new IllegalStateException(e);}
 }
 static String attr(String line,String key){
  Matcher m=Pattern.compile("(?:^|\\s)"+Pattern.quote(key)+"=\"([^\"]*)\"").matcher(line);
  return m.find()?m.group(1):"";
 }
 public static List<Item> m3u(String text,String source){
  List<Item> out=new ArrayList<>();String meta=null;
  for(String raw:text.split("\\r?\\n")){
   String l=raw.trim();
   if(l.startsWith("#EXTINF:"))meta=l;
   else if(!l.isEmpty()&&!l.startsWith("#")&&meta!=null){
    int comma=-1;boolean quote=false;
    for(int i=0;i<meta.length();i++){
     if(meta.charAt(i)=='"')quote=!quote;
     if(meta.charAt(i)==','&&!quote){comma=i;break;}
    }
    String name=comma<0?"Untitled":meta.substring(comma+1).trim();
    String group=attr(meta,"group-title"),type=attr(meta,"media-type");
    if(!type.equals("movie")&&!type.equals("series"))
     type=l.matches("(?i).*/movie/.*")?"movie":l.matches("(?i).*/series/.*")?"series":"live";
    String epg=attr(meta,"tvg-id");
    out.add(new Item(key(source+"|"+type+"|"+epg+"|"+name+"|"+group),name,
      group.isEmpty()?"Uncategorized":group,l,type,epg,attr(meta,"tvg-language")));
    meta=null;
   }
  }
  return out;
 }

 // Category metadata is normally more reliable than the name of an individual
 // station. Match whole country/language tokens; never substring-match "US"
 // in e.g. "Music", or "IN" in an English title.
 private static final String[][] CLUES={
  {"en","UK|GB|GREAT BRITAIN|UNITED KINGDOM|BRITISH|ENGLISH|EN|USA|US|UNITED STATES|NORTH AMERICA|NA|CANADA|CANADIAN|CA|NZ|NEW ZEALAND|AU|AUS|AUSTRALIA|IRISH|IRELAND"},
  {"fr","FR|FRA|FRANCE|FRENCH|FRANCAIS|FRANÇAIS|QUEBEC|QUÉBEC|BELGIQUE"},
  {"de","DE|GER|GERMANY|GERMAN|DEUTSCH|DEUTSCHLAND|AUSTRIA|ÖSTERREICH"},
  {"es","ES|ESP|SPAIN|SPANISH|ESPAÑOL|ESPANOL|MEXICO|MÉXICO|MX|LATINO|LATAM"},
  {"ar","AR|ARABIC|ARAB|ARABIA|SAUDI|UAE|EMIRATES|EGYPT|MENA"},
  {"pt","PT|PORTUGAL|PORTUGUESE|PORTUGUÊS|BR|BRAZIL|BRASIL"},
  {"it","ITALY|ITALIAN|ITALIANO|ITALIA|IT"},
  {"ru","RU|RUSSIA|RUSSIAN|РУССКИЙ"},
  {"hi","INDIA|HINDI|HINDUSTANI|BHARAT"},
  {"other","TURKEY|TURKISH|TÜRKIYE|TR|POLAND|POLISH|PL|NETHERLANDS|DUTCH|NL|SWEDEN|SWEDISH|SE|NORWAY|NORWEGIAN|NO|DENMARK|DANISH|DK|FINLAND|FINNISH|FI|GREECE|GREEK|GR|JAPAN|JAPANESE|JP|KOREA|KOREAN|KR|CHINA|CHINESE|CN|TAIWAN|VIETNAM|VIETNAMESE|THAILAND|THAI|INDONESIA|INDONESIAN|PHILIPPINES|PHILIPPINE|UKRAINE|UKRAINIAN|UA|PAKISTAN|URDU|BENGALI|BANGLADESH|ALBANIA|ALBANIAN|SERBIA|CROATIA|ROMANIA|ROMANIAN|BULGARIA|ISRAEL|HEBREW"}
 };
 private static final Pattern[] REGIONS = new Pattern[CLUES.length];
 static{
  for(int n=0;n<CLUES.length;n++)
   REGIONS[n]=Pattern.compile("(?<![\\p{L}])(?:"+CLUES[n][1]+")(?![\\p{L}])");
 }

 static String infer(String value){
  if(value==null||value.isEmpty())return "unknown";
  String cleaned=value.toUpperCase(Locale.ROOT);
  String found=null;
  for(int n=0;n<REGIONS.length;n++){
   if(REGIONS[n].matcher(cleaned).find()){
    if(found!=null&&!found.equals(CLUES[n][0]))return "unknown";
    found=CLUES[n][0];
   }
  }
  return found==null?"unknown":found;
 }
 public static String language(Item item){
  String explicit=item.language==null?"":item.language.toLowerCase(Locale.ROOT).trim();
  if(!explicit.isEmpty()){
   if(explicit.matches("en|eng|english|en[-_].*"))return "en";
   if(explicit.matches("fr|fra|french|français|francais|fr[-_].*"))return "fr";
   if(explicit.matches("de|ger|deu|german|deutsch|de[-_].*"))return "de";
   if(explicit.matches("es|spa|spanish|español|espanol|es[-_].*"))return "es";
   if(explicit.matches("ar|ara|arabic|ar[-_].*"))return "ar";
   if(explicit.matches("pt|por|portuguese|português|pt[-_].*"))return "pt";
   if(explicit.matches("it|ita|italian|it[-_].*"))return "it";
   if(explicit.matches("ru|rus|russian|ru[-_].*"))return "ru";
   if(explicit.matches("hi|hin|hindi|hi[-_].*"))return "hi";
   return "other";
  }
  String category=infer(item.category);
  return category.equals("unknown")?infer(item.name):category;
 }
 // Existing callers (including standalone CoreTest) use no explicit overrides.
 public static boolean visible(Item i,Set<String> hidden,Set<String> categories,Set<String> favorites,
    Set<String> allowed,boolean hideUnknown){
  return visible(i,hidden,categories,favorites,allowed,hideUnknown,
   Collections.emptySet(),Collections.emptySet());
 }
 public static boolean visible(Item i,Set<String> hidden,Set<String> categories,Set<String> favorites,
    Set<String> allowed,boolean hideUnknown,Set<String> visibleItems,Set<String> visibleCategories){
  if(hidden.contains(i.id))return false; // Explicit Hide always wins
  if(visibleItems.contains(i.id)||visibleCategories.contains(i.type+"|"+i.category))return true;
  if(favorites.contains(i.id))return true; // protect favorites
  if(categories.contains(i.type+"|"+i.category))return false;
  // Apply the same language rules to live channels, films and series.
  // Type-specific hidden/restored categories are still keyed by item.type.
  if(allowed.isEmpty())return true;
  String lang=language(i);
  return lang.equals("unknown")?!hideUnknown:allowed.contains(lang);
 }
}