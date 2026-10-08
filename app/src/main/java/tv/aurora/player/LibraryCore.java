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

 // IPTV providers frequently put language identifiers in the title even when
 // the category is a broad "UK" or "4K" bucket. Title tags override category
 // country cues, but plain language-like words in movie names do not.
 private static final Pattern QUALITY_PREFIX=Pattern.compile(
   "(?i)^(?:(?:\\[|\\|)\\s*(?:4K|8K|UHD|FHD|HD|SD|HDR)\\s*(?:\\]|\\|)\\s*)+");
 private static final Pattern TITLE_TAG=Pattern.compile(
   "(?i)(?:^\\s*[|\\[(]\\s*([A-Z]{2,3})\\s*[|\\])]\\s*|" +
   "^\\s*([A-Z]{2,3})\\s*[:|\\-]\\s*|" +
   "\\s*[|\\[(]\\s*([A-Z]{2,3})\\s*[|\\])]\\s*$|" +
   "\\s*\\(([A-Z]{2,3})\\)\\s*$)");
 private static final Map<String,String> CODES=new HashMap<>();
 static{
  String[][] shortCodes={
   {"en","EN","ENG","UK","GB","US","USA","CA","CAN","NZ","AU","AUS","IE"},
   {"fr","FR","FRA"},{"ar","AR","ARA"},{"de","DE","GER","DEU"},
   {"es","ES","ESP"},{"pt","PT","POR","BR"},{"it","IT","ITA"},
   {"ru","RU","RUS"},{"hi","HI","HIN"}
  };
  for(String[] group:shortCodes)for(int i=1;i<group.length;i++)CODES.put(group[i],group[0]);
 }
 private static final Pattern NON_ENGLISH_CHANNEL=Pattern.compile(
  "(?i)^(?:(?:[|\\[(]?\\s*4K\\s*[|\\])]?)\\s*)?" +
  "(?:TF1|M6|FRANCE\\s*[2345]|TV5\\s*MONDE|TV5MONDE|CANAL\\s*PLUS|" +
  "AL\\s*JAZEERA\\s*ARABIC|AL\\s*ARABIYA)(?=$|[\\s:|/\\[(])");
 private static final Pattern ENGLISH_CHANNEL=Pattern.compile(
  "(?i)^(?:NESN|BBC(?:\\s|$)|ITV(?:\\s|$)|ESPN(?:\\s|$)|PBS(?:\\s|$)|" +
  "NBC(?:\\s|$)|CBS(?:\\s|$)|ABC(?:\\s|$)|FOX\\s*SPORTS|" +
  "SKY\\s*SPORTS|TVNZ(?:\\s|$))");
 static String titleLanguageMarker(String title){
  if(title==null)return "unknown";
  // Check leading and trailing marked tokens, where "FR" is a tag rather than
  // an incidental fragment of a title. Avoid guessing from e.g. "Star Wars".
  String taggedTitle=QUALITY_PREFIX.matcher(title.trim()).replaceFirst("");
  Matcher matcher=TITLE_TAG.matcher(taggedTitle);
  if(matcher.find()){
   for(int i=1;i<=matcher.groupCount();i++){
    String group=matcher.group(i);
    if(group!=null){
     String mapped=CODES.get(group.toUpperCase(Locale.ROOT));
     if(mapped!=null)return mapped;
    }
   }
  }
  return "unknown";
 }
 static String channelBrand(String title){
  if(title==null)return "unknown";
  String cleaned=title.replaceFirst("(?i)^\\s*(?:[|\\[(]\\s*4K\\s*[|\\])]\\s*)+","").trim();
  if(NON_ENGLISH_CHANNEL.matcher(cleaned).find()){
   if(cleaned.matches("(?i)^(?:AL\\s*JAZEERA\\s*ARABIC|AL\\s*ARABIYA).*"))return "ar";
   return "fr";
  }
  if(ENGLISH_CHANNEL.matcher(cleaned).find())return "en";
  return "unknown";
 }
 private static String explicitLanguage(String input){
  String explicit=input==null?"":input.toLowerCase(Locale.ROOT).trim();
  if(explicit.isEmpty())return "unknown";
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
 public static String language(Item item){
  String tagged=titleLanguageMarker(item.name);
  if(!tagged.equals("unknown"))return tagged;
  String explicit=explicitLanguage(item.language);
  if(!explicit.equals("unknown"))return explicit;
  // Recognize a few unmistakably branded LIVE stations; this is not used for
  // films (e.g. the film "France" is not automatically a French-language film).
  if("live".equals(item.type)){
   String brand=channelBrand(item.name);
   if(!brand.equals("unknown"))return brand;
  }
  String category=infer(item.category);
  if(!category.equals("unknown"))return category;
  // Ordinary film titles often contain country names; don't assume those are
  // spoken languages. Channels can still have language hints in their names.
  return "live".equals(item.type)?infer(item.name):"unknown";
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