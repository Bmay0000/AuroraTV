package tv.aurora.player;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Deterministic normalization of noisy IPTV channel and EPG display names. */
public final class GuideName {
 private GuideName(){}
 private static final Pattern DECORATION=Pattern.compile(
   "(?i)(?<![A-Z0-9])(?:UHD|FHD|HD|SD|4K|8K|HDR10\\+?|HDR|DV|DOLBY\\s*VISION|" +
   "HEVC|H\\.?26[45]|1080[PI]|720P|2160P|50\\s*FPS|60\\s*FPS|[0-9]{3,4}P)(?![A-Z0-9])");
 private static final Pattern PREFIX=Pattern.compile(
   "(?i)^\\s*(?:[\\[(|]?\\s*(?:UK|GB|US|USA|AU|NZ|CA|CAN|EN|ENG|FR|FRA|" +
   "AR|DE|ES|PT|IT|RU)\\s*[\\]|):/\\-]\\s*)+");
 private static final Pattern SUFFIX=Pattern.compile(
   "(?i)\\s*(?:[|\\[(]\\s*(?:UK|US|USA|CA|EN|FR|AR|DE|ES|PT|IT|RU)\\s*[|\\])]\\s*)+$");
 private static final Pattern EPISODE=Pattern.compile(
   "(?i)(?<![A-Z0-9])(?:BACKUP|ALT|SOURCE\\s*[0-9]+|VIP)(?![A-Z0-9])");
 public static String normalized(String title){
  if(title==null||title.trim().isEmpty())return "";
  String s=Normalizer.normalize(title,Normalizer.Form.NFD)
     .replaceAll("\\p{M}+","").toUpperCase(Locale.ROOT);
  s=PREFIX.matcher(s).replaceFirst("");
  s=SUFFIX.matcher(s).replaceAll("");
  s=DECORATION.matcher(s).replaceAll(" ");
  s=EPISODE.matcher(s).replaceAll(" ");
  // Normalize ampersands to avoid "A & E" and "A&E" becoming distinct.
  s=s.replace("&","AND");
  return s.replaceAll("[^A-Z0-9]","");
 }
 public static String cleanedId(String xmltvId){
  if(xmltvId==null)return "";
  // XMLTV id "BBCOne.uk" is not the same as the channel BBCOne, but we
  // allow a conservative fallback by removing a known country suffix.
  String s=xmltvId.replaceFirst("(?i)\\.(?:uk|us|ca|au|nz|fr|de|es|pt|it|ru)$","");
  return normalized(s);
 }
}