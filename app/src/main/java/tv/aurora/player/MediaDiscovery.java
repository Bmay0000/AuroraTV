package tv.aurora.player;

import java.util.Locale;
import java.util.Calendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Selection rules for personalized, metadata-grounded home shelves. */
public final class MediaDiscovery {
    private MediaDiscovery(){}

    // Title inference is conservative: use a terminal four-digit release year,
    // not numbers in the middle of a film name ("2001: A Space Odyssey").
    private static final Pattern TRAILING_YEAR=Pattern.compile(
        "(?:\\s[\\[(](19\\d{2}|20\\d{2})[\\])])(?:\\s(?:4K|UHD|FHD|HD|SD))?$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPLICIT_YEAR=Pattern.compile(
        "^(19\\d{2}|20\\d{2})(?:$|[-/ ])");
    private static final String[][] GENRES={
        {"ACTION","ACTION","ADVENTURE"},
        {"COMEDY","COMEDY","COMEDIES"},
        {"THRILLER","THRILLER","CRIME","MYSTERY"},
        {"HORROR","HORROR"},
        {"DRAMA","DRAMA"},
        {"SCI-FI","SCI-FI","SCI FI","SCIENCE FICTION","FANTASY"},
        {"DOCUMENTARY","DOCUMENTARY","DOCUMENTARIES"},
        {"FAMILY","FAMILY","ANIMATION","ANIMATED","KIDS"}
    };

    public static int releaseYear(String value,int currentYear){
        if(value==null)return 0;
        String text=value.trim();
        // Xtream commonly supplies releaseDate=YYYY-MM-DD or a year number.
        Matcher explicit=EXPLICIT_YEAR.matcher(text);
        if(!explicit.find())return 0;
        int year=Integer.parseInt(explicit.group(1));
        return year>=1900 && year<=currentYear+1?year:0;
    }

    public static int yearFromTitle(String title,int currentYear){
        if(title==null)return 0;
        Matcher matcher=TRAILING_YEAR.matcher(title.trim());
        if(!matcher.find())return 0;
        int year=Integer.parseInt(matcher.group(1));
        return year>=1900 && year<=currentYear+1?year:0;
    }

    public static int currentYear(){
        return Calendar.getInstance().get(Calendar.YEAR);
    }

    public static boolean recent(int year,int currentYear){
        return year>=currentYear-2 && year<=currentYear;
    }

    public static boolean confirmedEnglish(LibraryCore.Item item){
        return item!=null && "en".equals(LibraryCore.language(item));
    }

    public static boolean knownForeign(LibraryCore.Item item){
        if(item==null)return false;
        String language=LibraryCore.language(item);
        return !"en".equals(language)&&!"unknown".equals(language);
    }

    /** A real genre from a provider category; never invent genre from a title. */
    public static String genre(String category){
        if(category==null)return "";
        String name=" "+category.toUpperCase(Locale.ROOT).replaceAll("[^\\p{L}0-9]+"," ")+" ";
        for(String[] row:GENRES){
            for(int i=1;i<row.length;i++){
                String token=row[i].replace('-',' ');
                if(name.contains(" "+token+" "))return row[0];
            }
        }
        return "";
    }

    public static String displayGenre(String category){
        String genre=genre(category);
        return genre.isEmpty()?"":genre+" MOVIES";
    }

    public static double parseRating(String raw){
        if(raw==null||raw.trim().isEmpty())return 0d;
        try{
            double n=Double.parseDouble(raw.trim());
            if(n>0d && n<=10d)return n;
        }catch(NumberFormatException ignored){}
        return 0d;
    }
}
