package tv.aurora.player;

import java.util.*;
import java.util.regex.*;

/**
 * Channel discovery order inspired by publicly listed North American cable/
 * satellite network order. This does NOT suggest a DIRECTV subscription or
 * copy network/lineup artwork, and never invents channels or channel numbers.
 */
public final class ChannelDiscovery {
    private ChannelDiscovery(){}

    // Selected, factual national network ordering from the published
    // DIRECTV via Satellite English-channel table (effective July 29, 2026).
    // Only the user's own provider entries can ever appear.
    private static final String[][] NATIONAL = {
        {"200","NEWS MIX"},{"202","CNN"},{"204","HLN"},{"206","ESPN"},
        {"207","ESPNEWS"},{"208","ESPNU"},{"209","ESPN2"},
        {"211","NFL REDZONE"},{"212","NFL NETWORK"},{"213","MLB NETWORK"},
        {"215","NHL NETWORK"},{"216","NBA TV"},{"217","TENNIS CHANNEL"},
        {"218","GOLF CHANNEL"},{"219","FS1"},{"221","CBS SPORTS NETWORK"},
        {"229","HGTV"},{"230","MAGNOLIA NETWORK"},{"231","FOOD NETWORK"},
        {"232","COOKING CHANNEL"},{"233","GAME SHOW NETWORK"},
        {"236","E!"},{"237","BRAVO"},{"239","SUNDANCE TV"},
        {"241","PARAMOUNT NETWORK"},{"242","USA NETWORK"},
        {"244","SYFY"},{"245","TNT"},{"246","TRUTV"},{"247","TBS"},
        {"248","FX"},{"249","COMEDY CENTRAL"},{"251","OXYGEN"},
        {"252","LIFETIME"},{"253","LMN"},{"254","AMC"},{"256","TCM"},
        {"258","FXM"},{"259","FXX"},{"260","WE TV"},{"264","BBC AMERICA"},
        {"265","A&E"},{"269","HISTORY"},{"276","NATIONAL GEOGRAPHIC"},
        {"277","TRAVEL CHANNEL"},{"278","DISCOVERY"},{"280","TLC"},
        {"282","ANIMAL PLANET"},{"283","NAT GEO WILD"},
        {"284","SCIENCE CHANNEL"},{"285","INVESTIGATION DISCOVERY"},
        {"288","PBS KIDS"},{"289","DISNEY JUNIOR"},{"290","DISNEY CHANNEL"},
        {"292","DISNEY XD"},{"296","CARTOON NETWORK"},
        {"299","NICKELODEON"},{"301","NICK JR"},{"302","NICKTOONS"},
        {"304","TV LAND"},{"305","ION"},{"307","NEWSNATION"},
        {"311","FREEFORM"},{"312","HALLMARK CHANNEL"},
        {"327","CMT"},{"329","BET"},{"331","MTV"},{"333","IFC"},
        {"335","VH1"},{"346","BBC NEWS"},{"349","NEWSMAX"},
        {"350","C-SPAN"},{"351","C-SPAN2"},{"353","BLOOMBERG"},
        {"355","CNBC"},{"356","MS NOW"},{"359","FOX BUSINESS"},
        {"360","FOX NEWS"},{"361","ACCUWEATHER"},{"362","WEATHER CHANNEL"},
        {"363","FOX WEATHER"},{"501","HBO"},{"515","CINEMAX"},
        {"525","STARZ"},{"545","SHOWTIME"},
        {"610","BIG TEN NETWORK"},{"611","SEC NETWORK"},
        {"612","ACC NETWORK"},{"618","FS2"}
    };
    private static final Map<String,Integer> LINEUP = new HashMap<>();
    static{for(String[] record:NATIONAL)
        LINEUP.put(record[1],Integer.parseInt(record[0]));}

    private static final Pattern QUALITY=Pattern.compile(
        "(?i)\\b(?:4K\\+?|8K|UHD|FHD|HD|SD|HDR|HEVC|H265|60FPS|1080P|720P|LIVE)\\b");
    private static final Pattern LEADING=Pattern.compile(
        "^(?:USA?|CAN|CANADA|CA|NA|NORTH AMERICA|UK|GB|EN|ENG|4K|8K)\\s+",Pattern.CASE_INSENSITIVE);
    private static final Pattern NONLATIN=Pattern.compile("[\\p{IsArabic}\\p{IsCyrillic}\\p{IsHan}\\p{IsHangul}]");
    private static final Pattern NA_CATEGORY=Pattern.compile(
        "(?i)(?:^|[^A-Z])(?:USA?|UNITED STATES|CANADA|CANADIAN|NORTH AMERICA|NA|US SPORTS|US NEWS)(?:$|[^A-Z])");

    public static String canonicalName(String raw){
        if(raw==null)return "";
        String name=raw.toUpperCase(Locale.ROOT);
        name=name.replace("&"," AND ").replace("É","E");
        name=name.replaceAll("[\\[\\](){}|:/+.,]"," ");
        name=QUALITY.matcher(name).replaceAll(" ");
        name=name.replaceAll("\\s+"," ").trim();
        for(int n=0;n<4;n++){
            Matcher m=LEADING.matcher(name);
            if(!m.find())break;
            name=name.substring(m.end()).trim();
        }
        name=name.replaceFirst("\\s+(?:EAST|WEST|USA|US|CA|CANADA)$","");
        return name;
    }

    public static int satelliteNumber(LibraryCore.Item channel){
        if(channel==null||channel.name==null)return 0;
        String c=canonicalName(channel.name);
        if(c.isEmpty())return 0;
        // Explicit distinctive aliases only. Avoid collapsing FS1 into FOX,
        // CNN International into CNN, ESPN 2 into ESPN, etc.
        if(c.equals("A AND E"))c="A&E";
        if(c.equals("FOX NEWS CHANNEL"))c="FOX NEWS";
        if(c.equals("FOX BUSINESS NETWORK"))c="FOX BUSINESS";
        if(c.equals("NATIONAL GEOGRAPHIC CHANNEL"))c="NATIONAL GEOGRAPHIC";
        if(c.equals("SCIENCE"))c="SCIENCE CHANNEL";
        if(c.equals("FX MOVIE CHANNEL"))c="FXM";
        if(c.equals("MSNBC"))c="MS NOW";
        if(c.equals("NICK JR."))c="NICK JR";
        if(c.equals("ION TELEVISION"))c="ION";
        if(c.equals("PARAMOUNT PLUS WITH SHOWTIME"))c="SHOWTIME";
        Integer number=LINEUP.get(c);
        return number==null?0:number;
    }

    private static boolean localNetwork(String normalized){
        if(normalized==null)return false;
        if(normalized.matches("^(?:ABC|CBS|NBC|FOX|PBS)(?: [0-9]{1,2})?$"))return true;
        return normalized.matches("^(?:W|K)[A-Z]{3}(?: [0-9]{1,2})?(?: ABC| CBS| NBC| FOX| PBS)?$");
    }

    public static boolean northAmerica(LibraryCore.Item item){
        if(item==null)return false;
        String category=item.category==null?"":item.category;
        if(NA_CATEGORY.matcher(category).find())return true;
        String canon=canonicalName(item.name);
        if(satelliteNumber(item)>0)return true;
        if(localNetwork(canon) ||
            canon.matches("(?:NESN|MSG NETWORK|YES NETWORK|WGN|CW|MYNETWORKTV)"))
            return true;
        return false;
    }

    public static String group(LibraryCore.Item item){
        if(item==null)return "Other";
        String language=LibraryCore.language(item);
        if(!language.equals("en")&&!language.equals("unknown"))return "International";
        if(northAmerica(item))return "North America";
        if(language.equals("en"))return "Other English";
        return "Other / Unverified";
    }

    /** Channel score order: NA known lineup -> NA extras -> other English ->
     * unknown -> foreign, with provider stable ordering within each group. */
    public static int priority(LibraryCore.Item item){
        String lang=LibraryCore.language(item);
        if(!lang.equals("en")&&!lang.equals("unknown"))return 500000;
        if(localNetwork(canonicalName(item.name)))return 100;
        int line=satelliteNumber(item);
        if(line>0)return line;
        if(northAmerica(item))return 10000;
        if("en".equals(lang))return 20000;
        return 30000;
    }

    public static int compare(LibraryCore.Item a,LibraryCore.Item b){
        int p=Integer.compare(priority(a),priority(b));
        if(p!=0)return p;
        String ca=canonicalName(a.name),cb=canonicalName(b.name);
        return ca.compareTo(cb);
    }

    public static boolean matchesGuideSection(LibraryCore.Item item,String section){
        if(item==null)return false;
        if(section==null||section.equals("All"))return true;
        switch(section){
            case "North America":return group(item).equals("North America");
            case "English":return LibraryCore.language(item).equals("en");
            case "International":return group(item).equals("International");
            case "Other":return group(item).equals("Other / Unverified");
            case "News":return matches(item,"NEWS|CNN|FOX|MS NOW|NBC|CBS|ABC|BBC|CNBC|BLOOMBERG|WEATHER|C-SPAN");
            case "Sports":return matches(item,"SPORT|ESPN|FS1|FS2|NFL|NBA|NHL|MLB|GOLF|TENNIS|NESN|SEC|ACC|BIG TEN|REDZONE");
            case "Movies":return matches(item,"MOVIE|HBO|STARZ|CINEMAX|SHOWTIME|TCM|AMC|FXM");
            case "Kids":return matches(item,"DISNEY|NICK|CARTOON|BOOMERANG|PBS KIDS|BABYFIRST");
            case "Entertainment":return matches(item,"TNT|TBS|FX|SYFY|BRAVO|USA NETWORK|COMEDY|PARAMOUNT|HISTORY|DISCOVERY|TLC|HGTV");
            default:return item.category!=null && section.equals(item.category);
        }
    }
    private static boolean matches(LibraryCore.Item item,String tokens){
        String raw=canonicalName(item.name)+" "+canonicalName(item.category);
        for(String token:tokens.split("\\|")){
            if(Pattern.compile("(?<![A-Z0-9])"+Pattern.quote(token)+"(?![A-Z0-9])")
                .matcher(raw).find())return true;
        }
        return false;
    }
}
