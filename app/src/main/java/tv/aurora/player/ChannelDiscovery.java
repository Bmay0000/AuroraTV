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
        {"77","METV"},{"80","COZI TV"},{"81","GRIT"},{"82","BOUNCE TV"},
        {"84","START TV"},{"85","TRUE CRIME"},{"88","ION MYSTERY"},
        {"200","NEWS MIX"},{"202","CNN"},{"204","HLN"},{"206","ESPN"},
        {"207","ESPNEWS"},{"208","ESPNU"},{"209","ESPN2"},{"210","ESPN+"},
        {"211","NFL REDZONE"},{"212","NFL NETWORK"},{"213","MLB NETWORK"},
        {"214","RACER NETWORK"},{"215","NHL NETWORK"},{"216","NBA TV"},{"217","TENNIS CHANNEL"},
        {"218","GOLF CHANNEL"},{"219","FS1"},{"221","CBS SPORTS NETWORK"},
        {"229","HGTV"},{"230","MAGNOLIA NETWORK"},{"231","FOOD NETWORK"},
        {"232","COOKING CHANNEL"},{"233","GAME SHOW NETWORK"},
        {"235","TASTEMADE"},{"236","E!"},{"237","BRAVO"},{"238","REELZ"},
        {"239","SUNDANCE TV"},{"240","HSN"},
        {"241","PARAMOUNT NETWORK"},{"242","USA NETWORK"},
        {"244","SYFY"},{"245","TNT"},{"246","TRUTV"},{"247","TBS"},
        {"248","FX"},{"249","COMEDY CENTRAL"},{"251","OXYGEN"},
        {"252","LIFETIME"},{"253","LMN"},{"254","AMC"},{"256","TCM"},
        {"258","FXM"},{"259","FXX"},{"260","WE TV"},{"264","BBC AMERICA"},
        {"265","A&E"},{"269","HISTORY"},{"271","VICE"},
        {"272","LOGO"},{"274","OVATION"},{"275","QVC"},
        {"276","NATIONAL GEOGRAPHIC"},
        {"277","TRAVEL CHANNEL"},{"278","DISCOVERY"},{"280","TLC"},
        {"282","ANIMAL PLANET"},{"283","NAT GEO WILD"},
        {"284","SCIENCE CHANNEL"},{"285","INVESTIGATION DISCOVERY"},
        {"287","AMERICAN HEROES CHANNEL"},
        {"288","PBS KIDS"},{"289","DISNEY JUNIOR"},{"290","DISNEY CHANNEL"},
        {"292","DISNEY XD"},{"293","BABYFIRST"},{"294","DISCOVERY FAMILY"},
        {"296","CARTOON NETWORK"},
        {"299","NICKELODEON"},{"300","NICKELODEON WEST"},
        {"301","NICK JR"},{"302","NICKTOONS"},
        {"304","TV LAND"},{"305","ION"},{"307","NEWSNATION"},
        {"311","FREEFORM"},{"312","HALLMARK CHANNEL"},
        {"314","FMC"},{"318","QVC3"},
        {"323","FETV"},{"326","GREAT AMERICAN FAMILY"},
        {"327","CMT"},{"328","TV ONE"},{"329","BET"},{"330","BET HER"},
        {"331","MTV"},{"333","IFC"},
        {"335","VH1"},{"340","AXS TV"},{"342","THEGRIO"},
        {"343","I24 NEWS"},{"344","SONLIFE BROADCASTING NETWORK"},
        {"346","BBC NEWS"},{"347","THE FIRST"},{"348","FREE SPEECH TV"},
        {"349","NEWSMAX"},
        {"350","C-SPAN"},{"351","C-SPAN2"},{"353","BLOOMBERG"},
        {"354","CHEDDAR NEWS"},
        {"355","CNBC"},{"356","MS NOW"},{"357","CNBC WORLD"},
        {"358","CNN INTERNATIONAL"},{"359","FOX BUSINESS"},
        {"360","FOX NEWS"},{"361","ACCUWEATHER"},{"362","WEATHER CHANNEL"},
        {"363","FOX WEATHER"},{"364","INSP"},{"369","DAYSTAR"},
        {"370","EWTN"},{"371","TBN INSPIRE"},{"372","TBN"},
        {"373","THE WORD NETWORK"},{"374","BYUTV"},{"381","ASPIRE"},
        {"385","HEROES AND ICONS"},{"388","JBS"},
        {"501","HBO"},{"515","CINEMAX"},
        {"525","STARZ"},{"545","SHOWTIME"},
        {"610","BIG TEN NETWORK"},{"611","SEC NETWORK"},
        {"612","ACC NETWORK"},{"618","FS2"}
    };
    private static final Map<String,Integer> LINEUP = new HashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String,Pattern> TOPIC_PATTERNS = new java.util.concurrent.ConcurrentHashMap<>();
    static{for(String[] record:NATIONAL)
        LINEUP.put(record[1],Integer.parseInt(record[0]));}

    private static final Pattern QUALITY=Pattern.compile(
        "(?i)\\b(?:4K\\+?|8K|UHD|FHD|HD|SD|HDR|HEVC|H265|60FPS|1080P|720P|LIVE)\\b");
    private static final Pattern LEADING=Pattern.compile(
        "^(?:USA?|CAN|CANADA|CA|NA|NORTH AMERICA|UK|GB|EN|ENG|4K|8K)\\s+",Pattern.CASE_INSENSITIVE);
    private static final Pattern NONLATIN=Pattern.compile("[\\p{IsArabic}\\p{IsCyrillic}\\p{IsHan}\\p{IsHangul}]");
    // Satellite lists a handful of ESPN+ subfeeds near channel 210, not every
    // numbered event in a third-party IPTV catalog.
    private static final Pattern ESPN_PLUS=Pattern.compile(
        "(?i)ESPN\\s*\\+\\s*([1-7])?(?!\\d)");
    private static final Pattern SYMBOLS=Pattern.compile("[\\[\\](){}|:/+.,]");
    private static final Pattern WHITESPACE=Pattern.compile("\\s+");
    private static final Pattern TRAILING_REGION=Pattern.compile(
        "\\s+(?:EAST|WEST|USA|US|CA|CANADA)$");
    private static final Pattern LOCAL_NAME=Pattern.compile(
        "^(?:ABC|CBS|NBC|FOX|PBS)(?: [0-9]{1,2})?$");
    private static final Pattern CALL_SIGN=Pattern.compile(
        "^(?:W|K)[A-Z]{3}(?: [0-9]{1,2})?(?: ABC| CBS| NBC| FOX| PBS)?$");
    private static final Pattern FHD=Pattern.compile("(?:FHD|1080P|1080I)");
    private static final Pattern UHD=Pattern.compile("(?:UHD|4K)");
    private static final Pattern HD=Pattern.compile("(?:^|[^A-Z])HD(?:$|[^A-Z])");
    private static final Pattern BACKUP=Pattern.compile("(?:BACKUP|BKP|TEST|ALT|DUMMY|OFFLINE)");
    private static final Pattern HEVC=Pattern.compile("(?:HEVC|H265)");
    private static final Pattern NUMBERED_EVENT=Pattern.compile(
        "(?:ESPN|SPORTS|SPORT|FOX SPORTS|PPV|EVENT|GAME|MATCH|FEED|MULTIVIEW|EXTRA|ALT)\\s*\\+?\\s*[0-9]{1,5}");
    private static final Pattern EVENT_MARKER=Pattern.compile(
        "(?:PPV|EVENT|MATCH|GAME|BACKUP|TEST|FEED|MULTIVIEW|EXTRA|ALTERNATE)(?:\\s|$)");
    private static final Pattern NA_CATEGORY=Pattern.compile(
        "(?i)(?:^|[^A-Z])(?:USA?|UNITED STATES|CANADA|CANADIAN|NORTH AMERICA|NA|US SPORTS|US NEWS)(?:$|[^A-Z])");

    public static String canonicalName(String raw){
        if(raw==null)return "";
        String name=raw.toUpperCase(Locale.ROOT);
        name=name.replace("&"," AND ").replace("É","E");
        name=SYMBOLS.matcher(name).replaceAll(" ");
        name=QUALITY.matcher(name).replaceAll(" ");
        name=WHITESPACE.matcher(name).replaceAll(" ").trim();
        for(int n=0;n<4;n++){
            Matcher m=LEADING.matcher(name);
            if(!m.find())break;
            name=name.substring(m.end()).trim();
        }
        name=TRAILING_REGION.matcher(name).replaceFirst("");
        return name;
    }

    public static int satelliteNumber(LibraryCore.Item channel){
        if(channel==null||channel.name==null)return 0;
        if(ESPN_PLUS.matcher(channel.name).find())return 210;
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
        if(c.equals("SYFYHD"))c="SYFY";
        if(c.equals("CSPAN"))c="C-SPAN";
        if(c.equals("CSPAN 2"))c="C-SPAN2";
        if(c.equals("BLOOMBERG TV"))c="BLOOMBERG";
        if(c.equals("NEWSMAX TV"))c="NEWSMAX";
        if(c.equals("THE WEATHER CHANNEL"))c="WEATHER CHANNEL";
        if(c.equals("THE HISTORY CHANNEL"))c="HISTORY";
        if(c.equals("HOME SHOPPING NETWORK"))c="HSN";
        if(c.equals("BET HER TV"))c="BET HER";
        if(c.equals("REELZ CHANNEL")||c.equals("REELZCHANNEL"))c="REELZ";
        if(c.equals("FOX SPORTS ONE"))c="FS1";
        if(c.equals("FOX SPORTS TWO"))c="FS2";
        if(c.equals("ESPN 2"))c="ESPN2";
        if(c.equals("ESPN NEWS"))c="ESPNEWS";
        if(c.equals("ESPN U"))c="ESPNU";
        if(c.equals("FOX SPORTS 1"))c="FS1";
        if(c.equals("FOX SPORTS 2"))c="FS2";
        if(c.equals("NFL NETWORK"))c="NFL NETWORK";
        if(c.equals("NBA TELEVISION"))c="NBA TV";
        if(c.equals("NAT GEO"))c="NATIONAL GEOGRAPHIC";
        if(c.equals("MSNBC"))c="MS NOW";
        if(c.equals("NICK JR."))c="NICK JR";
        if(c.equals("ION TELEVISION"))c="ION";
        if(c.equals("PARAMOUNT PLUS WITH SHOWTIME"))c="SHOWTIME";
        Integer number=LINEUP.get(c);
        return number==null?0:number;
    }

    private static boolean localNetwork(String normalized){
        if(normalized==null)return false;
        if(LOCAL_NAME.matcher(normalized).matches())return true;
        return CALL_SIGN.matcher(normalized).matches();
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

    /** Stable identity shared by duplicate HD, FHD, and 4K renditions.
     * Different networks (ESPN vs ESPN2) and local affiliates stay separate. */
    public static String lineupIdentity(LibraryCore.Item item) {
        if(item==null)return "";
        int network=satelliteNumber(item);
        if(network>0)return "network:"+network;
        String name=canonicalName(item.name);
        if(name.isEmpty())return "";
        if(localNetwork(name))return "local:"+name;
        return "other:"+name;
    }

    /** Provider quality affects primary choice, not availability of alternates. */
    public static int renditionScore(LibraryCore.Item item){
        if(item==null)return Integer.MIN_VALUE;
        String name=item.name==null?"":item.name.toUpperCase(Locale.ROOT);
        String category=item.category==null?"":item.category;
        int quality=0;
        if(NA_CATEGORY.matcher(category).find())quality+=25;
        if("en".equals(LibraryCore.language(item)))quality+=20;
        if(item.epgId!=null&&!item.epgId.trim().isEmpty())quality+=15;
        if(FHD.matcher(name).find())quality+=20;
        else if(UHD.matcher(name).find())quality+=12;
        else if(HD.matcher(name).find())quality+=11;
        if(BACKUP.matcher(name).find())quality-=80;
        if(HEVC.matcher(name).find())quality-=5;
        return quality;
    }

    /** Numbered event feeds are not the ordinary ESPN/FOX/other networks.
     * Keep all of them under All Streams, not in the primary satellite row. */
    public static boolean eventFeed(LibraryCore.Item item){
        if(item==null)return false;
        String name=canonicalName(item.name);
        return (name.startsWith("ESPN ") && satelliteNumber(item)==0)
            || NUMBERED_EVENT.matcher(name).find()
            || EVENT_MARKER.matcher(name).find();
    }

    /** Deduplicate network renditions for the default guide and genre tabs,
     * without deleting provider entries. All Streams and explicit search keep
     * every option available. */
    public static List<LibraryCore.Item> curate(List<LibraryCore.Item> source,
                                                String section,boolean searchActive){
        if(source==null||source.isEmpty())return new ArrayList<>();
        if(searchActive||"All".equals(section)||"International".equals(section)
                ||"Other".equals(section)||"My Channels".equals(section)
                ||"More North America".equals(section)){
            List<LibraryCore.Item> all=new ArrayList<>(source);
            // One normalization pass rather than recomputing dozens of regex
            // matches during O(N log N) sorting of 10k+ provider streams.
            final Map<String,Integer> priorities=new HashMap<>(all.size()*2+1);
            final Map<String,String> names=new HashMap<>(all.size()*2+1);
            for(LibraryCore.Item row:all){
                priorities.put(row.id,priority(row));
                names.put(row.id,canonicalName(row.name));
            }
            all.sort((a,b)->{
                int n=Integer.compare(priorities.get(a.id),priorities.get(b.id));
                return n!=0?n:names.get(a.id).compareTo(names.get(b.id));
            });
            return all;
        }
        Map<String,LibraryCore.Item> primary=new LinkedHashMap<>();
        for(LibraryCore.Item item:source){
            int directv=satelliteNumber(item);
            boolean local=localNetwork(canonicalName(item.name));
            if(eventFeed(item) && directv==0 && !local)continue;
            String key=lineupIdentity(item);
            if(key.isEmpty())continue;
            LibraryCore.Item previous=primary.get(key);
            if(previous==null||renditionScore(item)>renditionScore(previous))
                primary.put(key,item);
        }
        List<LibraryCore.Item> sorted=new ArrayList<>(primary.values());
        sorted.sort(ChannelDiscovery::compare);
        if("North America".equals(section)){
            // Lead with the curated lineup; only then show distinct regional
            // networks. Excess and all alternate sources remain in the
            // separate More North America / All Streams views.
            List<LibraryCore.Item> result=new ArrayList<>();
            int additional=0;
            for(LibraryCore.Item item:sorted){
                if(satelliteNumber(item)>0||localNetwork(canonicalName(item.name))){
                    result.add(item);continue;
                }
                if(additional++<45)result.add(item);
            }
            return result;
        }
        return sorted;
    }

    public static boolean matchesGuideSection(LibraryCore.Item item,String section){
        if(item==null)return false;
        if(section==null||section.equals("All"))return true;
        switch(section){
            case "North America":return group(item).equals("North America");
            case "More North America":return group(item).equals("North America")&&satelliteNumber(item)==0;
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
        String raw=canonicalName(item.name)+" "+
            (item.category==null?"":item.category.toUpperCase(Locale.ROOT));
        Pattern matcher=TOPIC_PATTERNS.computeIfAbsent(tokens,words->{
            StringBuilder regex=new StringBuilder("(?<![A-Z0-9])(?:");
            boolean first=true;
            for(String term:words.split("\\|")){
                if(!first)regex.append('|');
                regex.append(Pattern.quote(term));
                first=false;
            }
            return Pattern.compile(regex.append(")(?![A-Z0-9])").toString());
        });
        return matcher.matcher(raw).find();
    }
}
