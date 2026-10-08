package tv.aurora.player;
import java.util.*;
import java.util.regex.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
public final class LibraryCore {
 public static class Item {
  public String id,name,category,url,type,epgId,language;
  public Item(String id,String name,String category,String url,String type,String epgId,String language){this.id=id;this.name=name;this.category=category;this.url=url;this.type=type;this.epgId=epgId;this.language=language;}
 }
 public static String key(String text){try{byte[] b=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format("%02x",x));return s.toString();}catch(Exception e){throw new IllegalStateException(e);}}
 static String attr(String line,String key){Matcher m=Pattern.compile("(?:^|\\s)"+Pattern.quote(key)+"=\"([^\"]*)\"").matcher(line);return m.find()?m.group(1):"";}
 public static List<Item> m3u(String text,String source){List<Item> out=new ArrayList<>();String meta=null;for(String raw:text.split("\\r?\\n")){String l=raw.trim();if(l.startsWith("#EXTINF:"))meta=l;else if(!l.isEmpty()&&!l.startsWith("#")&&meta!=null){int comma=-1;boolean quote=false;for(int i=0;i<meta.length();i++){if(meta.charAt(i)=='\"')quote=!quote;if(meta.charAt(i)==','&&!quote){comma=i;break;}}String name=comma<0?"Untitled":meta.substring(comma+1).trim();String group=attr(meta,"group-title");String type=attr(meta,"media-type");if(!type.equals("movie")&&!type.equals("series"))type=l.matches("(?i).*/movie/.*")?"movie":l.matches("(?i).*/series/.*")?"series":"live";String epg=attr(meta,"tvg-id");out.add(new Item(key(source+"|"+type+"|"+epg+"|"+name+"|"+group),name,group.isEmpty()?"Uncategorized":group,l,type,epg,attr(meta,"tvg-language")));meta=null;}}return out;}
 public static String language(Item i){String explicit=i.language.toLowerCase(Locale.ROOT);if(!explicit.isEmpty()){if(explicit.matches("english|en|eng"))return "en";if(explicit.matches("french|fr|fra"))return "fr";if(explicit.matches("german|de|deu"))return "de";if(explicit.matches("spanish|es|spa"))return "es";if(explicit.matches("arabic|ar|ara"))return "ar";return "other";}
 String s=(i.category+" "+i.name).toUpperCase(Locale.ROOT);String[][] clues={{"fr","FR|FRANCE|FRENCH"},{"de","DE|GERMANY|GERMAN"},{"es","ES|SPAIN|SPANISH"},{"ar","ARABIC|ARAB"},{"en","UK|GB|US|USA|ENGLISH|NZ|AU|AUSTRALIA|NA"}};Set<String> found=new HashSet<>();for(String[] c:clues)if(Pattern.compile("(?<![A-Z])(?:"+c[1]+")(?![A-Z])").matcher(s).find())found.add(c[0]);return found.size()==1?found.iterator().next():"unknown";}
 public static boolean visible(Item i,Set<String> hidden,Set<String> categories,Set<String> favorites,Set<String> allowed,boolean hideUnknown){if(hidden.contains(i.id))return false;if(favorites.contains(i.id))return true;if(categories.contains(i.type+"|"+i.category))return false;if(!i.type.equals("live")||allowed.isEmpty())return true;String lang=language(i);return lang.equals("unknown")?!hideUnknown:allowed.contains(lang);}
}
