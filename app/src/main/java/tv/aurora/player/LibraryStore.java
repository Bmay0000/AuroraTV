package tv.aurora.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Indexed, disk-backed catalog. Metadata can be searched without decrypting
 * URLs or materializing the entire provider library in Android's heap.
 * URLs (which contain provider credentials) are encrypted per row with AES-GCM.
 */
public final class LibraryStore extends SQLiteOpenHelper {
    private static final String DATABASE = "aurora_catalog.db";
    private static final int VERSION = 2;
    private static final String COLUMNS =
            "(row_id INTEGER PRIMARY KEY, item_id TEXT NOT NULL UNIQUE, " +
            "name TEXT NOT NULL, category TEXT NOT NULL, type TEXT NOT NULL, " +
            "epg TEXT, language TEXT, url BLOB NOT NULL, artwork TEXT, " +
            "release_year INTEGER NOT NULL DEFAULT 0, added_at INTEGER NOT NULL DEFAULT 0, " +
            "rating REAL NOT NULL DEFAULT 0)";
    private static final String FIELDS =
            "item_id,name,category,type,epg,language,artwork,release_year,added_at,rating";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Context context;
    private final SharedPreferences preferences;
    private SecretKey localKey;

    public LibraryStore(Context context) {
        super(context, DATABASE, null, VERSION);
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences("aurora_catalog", Context.MODE_PRIVATE);
        // Permit catalog reads while another thread imports subsequent media types.
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE entries " + COLUMNS);
        db.execSQL("CREATE INDEX entry_type_row ON entries(type, row_id)");
        db.execSQL("CREATE INDEX entry_type_category ON entries(type,category,row_id)");
        db.execSQL("CREATE INDEX entry_type_release ON entries(type,release_year DESC,added_at DESC)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Add lightweight metadata, preserving existing libraries and logins.
        // Old titles can additionally infer a year from an explicit title tag.
        if(oldVersion<2){
            db.execSQL("ALTER TABLE entries ADD COLUMN release_year INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE entries ADD COLUMN added_at INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE entries ADD COLUMN rating REAL NOT NULL DEFAULT 0");
            db.execSQL("CREATE INDEX IF NOT EXISTS entry_type_release ON entries(type,release_year DESC,added_at DESC)");
        }
    }

    public boolean hasLibrary() {
        return preferences.getBoolean("catalog_ready", false) &&
                context.getDatabasePath(DATABASE).isFile();
    }

    public void clear() {
        close();
        context.deleteDatabase(DATABASE);
        preferences.edit().remove("catalog_ready").remove("wrapped_key").apply();
        localKey = null;
    }

    private synchronized SecretKey key() throws Exception {
        if (localKey != null) return localKey;
        String wrapped = preferences.getString("wrapped_key", "");
        byte[] bytes;
        if (wrapped.isEmpty()) {
            bytes = new byte[32];
            RANDOM.nextBytes(bytes);
            String value = Base64.encodeToString(bytes, Base64.NO_WRAP);
            if (!preferences.edit().putString("wrapped_key", Vault.seal(value)).commit())
                throw new IllegalStateException("Unable to save catalog encryption key");
        } else {
            bytes = Base64.decode(Vault.open(wrapped), Base64.NO_WRAP);
        }
        localKey = new SecretKeySpec(bytes, "AES");
        return localKey;
    }

    private byte[] encrypt(String value) throws Exception {
        byte[] iv = new byte[12];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        byte[] data = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = new byte[iv.length + data.length];
        System.arraycopy(iv, 0, encrypted, 0, iv.length);
        System.arraycopy(data, 0, encrypted, iv.length, data.length);
        return encrypted;
    }

    private String decrypt(byte[] encrypted) throws Exception {
        if (encrypted == null || encrypted.length < 29)
            throw new IllegalStateException("Corrupted media URL");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(),
                new GCMParameterSpec(128, encrypted, 0, 12));
        return new String(cipher.doFinal(encrypted, 12, encrypted.length - 12),
                StandardCharsets.UTF_8);
    }

    public interface Visitor {
        /** Return false to stop iterating. */
        boolean visit(LibraryCore.Item item);
    }

    private LibraryCore.Item item(Cursor c) {
        LibraryCore.Item value = new LibraryCore.Item(
                c.getString(0), c.getString(1), c.getString(2), "",
                c.getString(3), c.getString(4), c.getString(5));
        value.artwork = c.isNull(6) ? "" : c.getString(6);
        value.releaseYear=c.getInt(7);
        value.addedAt=c.getLong(8);
        value.rating=c.getDouble(9);
        if(value.releaseYear==0 && ("movie".equals(value.type)||"series".equals(value.type)))
            value.releaseYear=MediaDiscovery.yearFromTitle(value.name,MediaDiscovery.currentYear());
        return value;
    }

    public void forEach(Visitor visitor) throws Exception {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT " + FIELDS + " FROM entries ORDER BY row_id", null)) {
            while (cursor.moveToNext()) {
                if (Thread.currentThread().isInterrupted()) return;
                if (!visitor.visit(item(cursor))) return;
            }
        }
    }

    public static final class Page {
        public final List<LibraryCore.Item> rows;
        public final boolean more;
        Page(List<LibraryCore.Item> rows, boolean more) {
            this.rows = rows;
            this.more = more;
        }
    }

    /** Stops after finding offset+limit+1 matches, not after scanning all rows. */
    public Page page(String type, String category, String query, boolean hiddenOnly,
                     boolean favoritesOnly, Set<String> hidden, Set<String> hiddenCategories,
                     Set<String> favorites, Set<String> allowed, boolean hideUnknown,
                     int offset, int limit) throws Exception {
        return page(type, category, query, hiddenOnly, favoritesOnly,
                hidden, hiddenCategories, favorites, allowed, hideUnknown,
                java.util.Collections.emptySet(), java.util.Collections.emptySet(), offset, limit);
    }

    public Page page(String type, String category, String query, boolean hiddenOnly,
                     boolean favoritesOnly, Set<String> hidden, Set<String> hiddenCategories,
                     Set<String> favorites, Set<String> allowed, boolean hideUnknown,
                     Set<String> visibleItems, Set<String> visibleCategories,
                     int offset, int limit) throws Exception {
        StringBuilder sql = new StringBuilder("SELECT ").append(FIELDS)
                .append(" FROM entries WHERE type=?");
        List<String> args = new ArrayList<>();
        args.add(type);
        if (!"All".equals(category)) {
            sql.append(" AND category=?");
            args.add(category);
        }
        if (query != null && !query.isEmpty()) {
            sql.append(" AND name LIKE ? ESCAPE '\\'");
            args.add("%" + query.replace("\\", "\\\\").replace("%", "\\%")
                    .replace("_", "\\_") + "%");
        }
        sql.append(" ORDER BY row_id");
        List<LibraryCore.Item> result = new ArrayList<>(limit);
        int matches = 0;
        try (Cursor c = getReadableDatabase().rawQuery(sql.toString(),
                args.toArray(new String[0]))) {
            while (c.moveToNext()) {
                if (Thread.currentThread().isInterrupted()) break;
                LibraryCore.Item current = item(c);
                boolean isVisible = LibraryCore.visible(current, hidden, hiddenCategories,
                        favorites, allowed, hideUnknown, visibleItems, visibleCategories);
                if (hiddenOnly ? isVisible : !isVisible) continue;
                if (favoritesOnly && !favorites.contains(current.id)) continue;
                if (matches++ < offset) continue;
                if (result.size() == limit) return new Page(result, true);
                result.add(current);
            }
        }
        return new Page(result, false);
    }

    /**
     * Unlike categories(), this returns only categories having at least one
     * item that passes the CURRENT visibility rules. Used by Live TV and EPG,
     * so hidden foreign groups never appear in navigation.
     */
    public String[] visibleCategoryNames(String type, boolean hiddenOnly,
                    Set<String> hidden, Set<String> hiddenCategories, Set<String> favorites,
                    Set<String> allowed, boolean hideUnknown, Set<String> visibleItems,
                    Set<String> visibleCategories) {
        java.util.LinkedHashSet<String> found = new java.util.LinkedHashSet<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT " + FIELDS + " FROM entries WHERE type=? ORDER BY category COLLATE NOCASE",
                new String[]{type})) {
            while (c.moveToNext()) {
                if (Thread.currentThread().isInterrupted()) break;
                String group = c.getString(2);
                if (found.contains(group)) continue;
                LibraryCore.Item entry = item(c);
                boolean visible = LibraryCore.visible(entry, hidden, hiddenCategories,
                        favorites, allowed, hideUnknown, visibleItems, visibleCategories);
                if (hiddenOnly ? !visible : visible) found.add(group);
            }
        }
        return found.toArray(new String[0]);
    }

    public String[] categories(String type) {
        ArrayList<String> groups = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT DISTINCT category FROM entries WHERE type=? ORDER BY category COLLATE NOCASE",
                new String[]{type})) {
            while (c.moveToNext()) groups.add(c.getString(0));
        }
        return groups.toArray(new String[0]);
    }

    public int count(String type) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM entries WHERE type=?", new String[]{type})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** Fetch a handful of bookmarked/recent titles by indexed ID. No catalog scan. */
    public List<LibraryCore.Item> lookupByIds(List<String> requested){
        if(requested==null || requested.isEmpty())return new ArrayList<>();
        List<String> ids=new ArrayList<>(Math.min(20,requested.size()));
        for(String id:requested){
            if(id!=null && id.matches("[0-9a-f]{64}")&&!ids.contains(id)){
                ids.add(id);
                if(ids.size()>=20)break;
            }
        }
        if(ids.isEmpty())return new ArrayList<>();
        StringBuilder sql=new StringBuilder("SELECT ").append(FIELDS)
            .append(" FROM entries WHERE item_id IN (");
        for(int n=0;n<ids.size();n++){
            if(n>0)sql.append(',');
            sql.append('?');
        }
        sql.append(')');
        java.util.HashMap<String,LibraryCore.Item> found=new java.util.HashMap<>();
        try(Cursor rows=getReadableDatabase().rawQuery(sql.toString(),
                ids.toArray(new String[0]))){
            while(rows.moveToNext()){
                LibraryCore.Item value=item(rows);
                found.put(value.id,value);
            }
        }
        List<LibraryCore.Item> result=new ArrayList<>();
        for(String id:ids){
            LibraryCore.Item value=found.get(id);
            if(value!=null)result.add(value);
        }
        return result;
    }

    public static final class RecentMovies {
        public final List<LibraryCore.Item> english;
        public final List<LibraryCore.Item> unverified;
        public final List<LibraryCore.Item> international;
        RecentMovies(List<LibraryCore.Item> english,List<LibraryCore.Item> unverified,
                List<LibraryCore.Item> international){
            this.english=english;
            this.unverified=unverified;
            this.international=international;
        }
    }

    /**
     * Data-backed, NOT random, NOT provider alphabetic-order recommendations.
     * Movie release-year metadata takes precedence; older catalogs can use an
     * unmistakable terminal "(2026)" / "[2026]" year tag.
     */
    public RecentMovies recentMovies(Set<String> hidden,Set<String> hiddenCategories,
            Set<String> favorites,Set<String> allowed,boolean hideUnknown,
            Set<String> visibleItems,Set<String> visibleCategories){
        final int year=MediaDiscovery.currentYear();
        StringBuilder sql=new StringBuilder("SELECT ").append(FIELDS)
            .append(" FROM entries WHERE type='movie' AND (release_year>=?");
        ArrayList<String> arguments=new ArrayList<>();
        arguments.add(Integer.toString(year-2));
        sql.append(" OR (release_year=0 AND (");
        for(int y=year;y>=year-2;y--){
            if(y!=year)sql.append(" OR ");
            sql.append("name LIKE ? OR name LIKE ? OR name LIKE ? OR name LIKE ? OR name LIKE ? OR name LIKE ?");
            String str=Integer.toString(y);
            arguments.add("% ("+str+")");
            arguments.add("% ["+str+"]");
            arguments.add("% "+str);
            arguments.add("% ("+str+") HD");
            arguments.add("% ["+str+"] HD");
            arguments.add("% "+str+" HD");
        }
        sql.append("))) ORDER BY release_year DESC,rating DESC,added_at DESC,row_id DESC");
        List<LibraryCore.Item> english=new ArrayList<>();
        List<LibraryCore.Item> unknown=new ArrayList<>();
        List<LibraryCore.Item> international=new ArrayList<>();
        try(Cursor cursor=getReadableDatabase().rawQuery(sql.toString(),
                  arguments.toArray(new String[0]))){
            while(cursor.moveToNext()){
                if(Thread.currentThread().isInterrupted())break;
                LibraryCore.Item candidate=item(cursor);
                if(!MediaDiscovery.recent(candidate.releaseYear,year))continue;
                if(!LibraryCore.visible(candidate,hidden,hiddenCategories,favorites,
                        allowed,hideUnknown,visibleItems,visibleCategories))continue;
                if(MediaDiscovery.confirmedEnglish(candidate))english.add(candidate);
                else if(MediaDiscovery.knownForeign(candidate))international.add(candidate);
                else unknown.add(candidate);
                if(english.size()>=36&&unknown.size()>=36&&international.size()>=36)break;
            }
        }
        java.util.Comparator<LibraryCore.Item> ranking=(a,b)->{
            int match=Integer.compare(b.releaseYear,a.releaseYear);
            if(match!=0)return match;
            match=Double.compare(b.rating,a.rating);
            if(match!=0)return match;
            match=Long.compare(b.addedAt,a.addedAt);
            if(match!=0)return match;
            match=Boolean.compare(b.artwork!=null&&!b.artwork.isEmpty(),
                a.artwork!=null&&!a.artwork.isEmpty());
            if(match!=0)return match;
            return a.name.compareToIgnoreCase(b.name);
        };
        english.sort(ranking);
        unknown.sort(ranking);
        international.sort(ranking);
        if(english.size()>24)english=new ArrayList<>(english.subList(0,24));
        if(unknown.size()>24)unknown=new ArrayList<>(unknown.subList(0,24));
        if(international.size()>24)international=new ArrayList<>(international.subList(0,24));
        return new RecentMovies(english,unknown,international);
    }

    public static final class GenreCategory {
        public final String name;
        public final String genre;
        public final boolean verifiedEnglishCategory;
        public final int count;
        GenreCategory(String name,String genre,boolean verified,int count){
            this.name=name;this.genre=genre;verifiedEnglishCategory=verified;this.count=count;
        }
    }

    /** User's actual provider genres, not imaginary generic category links. */
    public List<GenreCategory> movieGenres(){
        List<GenreCategory> categories=new ArrayList<>();
        try(Cursor cursor=getReadableDatabase().rawQuery(
             "SELECT category,COUNT(*) FROM entries WHERE type='movie' " +
             "GROUP BY category ORDER BY COUNT(*) DESC LIMIT 250",null)){
            while(cursor.moveToNext()){
                if(Thread.currentThread().isInterrupted())break;
                String group=cursor.getString(0);
                int count=cursor.getInt(1);
                String genre=MediaDiscovery.genre(group);
                if(genre.isEmpty()||count<3)continue;
                String locale=LibraryCore.infer(group);
                if(!locale.equals("en")&&!locale.equals("unknown"))continue;
                categories.add(new GenreCategory(group,genre,locale.equals("en"),count));
            }
        }
        categories.sort((a,b)->{
            int en=Boolean.compare(b.verifiedEnglishCategory,a.verifiedEnglishCategory);
            return en!=0?en:Integer.compare(b.count,a.count);
        });
        return categories;
    }

    /** Streaming channel directory: one catalog scan; sort matched network
     *  names in a familiar NA satellite order, then other provider channels.
     *  The guide scrolls these rows without paging controls. */
    public List<LibraryCore.Item> channelDirectory(String tab,String query,
          Set<String> hidden,Set<String> hiddenCategories,Set<String> favorites,
          Set<String> allowed,boolean hideUnknown,Set<String> manual,
          Set<String> restoredCategories){
        List<LibraryCore.Item> matches=new ArrayList<>();
        String pattern=query==null?"":query.toLowerCase(java.util.Locale.ROOT).trim();
        try(Cursor cursor=getReadableDatabase().rawQuery(
                "SELECT "+FIELDS+" FROM entries WHERE type='live' ORDER BY row_id",null)){
            while(cursor.moveToNext()){
                if(Thread.currentThread().isInterrupted())break;
                LibraryCore.Item channel=item(cursor);
                if(!LibraryCore.visible(channel,hidden,hiddenCategories,favorites,
                    allowed,hideUnknown,manual,restoredCategories))continue;
                if("My Channels".equals(tab)&&!favorites.contains(channel.id))continue;
                if(!"My Channels".equals(tab)&&!ChannelDiscovery.matchesGuideSection(channel,tab))continue;
                if(!pattern.isEmpty()&&!channel.name.toLowerCase(java.util.Locale.ROOT).contains(pattern)
                        &&!channel.category.toLowerCase(java.util.Locale.ROOT).contains(pattern))continue;
                matches.add(channel);
            }
        }
        // Cache expensive normalization and language decisions once per item.
        java.util.HashMap<String,Integer> ranks=new java.util.HashMap<>(matches.size()*2+1);
        java.util.HashMap<String,String> names=new java.util.HashMap<>(matches.size()*2+1);
        for(LibraryCore.Item channel:matches){
            ranks.put(channel.id,ChannelDiscovery.priority(channel));
            names.put(channel.id,ChannelDiscovery.canonicalName(channel.name));
        }
        matches.sort((a,b)->{
            int score=Integer.compare(ranks.get(a.id),ranks.get(b.id));
            if(score!=0)return score;
            return names.get(a.id).compareTo(names.get(b.id));
        });
        return matches;
    }

    /** Prioritize real English media; unknown is a separate lower-priority
     *  shelf, never mislabeled as English. Ranked by provider year/rating/date. */
    public List<LibraryCore.Item> featuredEnglish(String type,int max,
          Set<String> hidden,Set<String> hiddenCategories,Set<String> favorites,
          Set<String> allowed,boolean hideUnknown,Set<String> manual,
          Set<String> restoredCategories){
        List<LibraryCore.Item> results=new ArrayList<>();
        String sql="SELECT "+FIELDS+" FROM entries WHERE type=? "+
            "ORDER BY release_year DESC,added_at DESC,row_id DESC";
        try(Cursor c=getReadableDatabase().rawQuery(sql,new String[]{type})){
            while(c.moveToNext()){
                if(Thread.currentThread().isInterrupted())break;
                LibraryCore.Item entry=item(c);
                if(!MediaDiscovery.confirmedEnglish(entry))continue;
                if(!LibraryCore.visible(entry,hidden,hiddenCategories,favorites,
                    allowed,hideUnknown,manual,restoredCategories))continue;
                results.add(entry);
                if(results.size()>=max)break;
            }
        }
        return results;
    }

    public List<LibraryCore.Item> featuredInternational(String type,int max,
          Set<String> hidden,Set<String> hiddenCategories,Set<String> favorites,
          Set<String> allowed,boolean hideUnknown,Set<String> manual,
          Set<String> restoredCategories){
        List<LibraryCore.Item> results=new ArrayList<>();
        try(Cursor cursor=getReadableDatabase().rawQuery(
            "SELECT "+FIELDS+" FROM entries WHERE type=? ORDER BY release_year DESC,added_at DESC,row_id DESC",
            new String[]{type})){
            while(cursor.moveToNext()){
                if(Thread.currentThread().isInterrupted())break;
                LibraryCore.Item entry=item(cursor);
                if(!MediaDiscovery.knownForeign(entry))continue;
                if(!LibraryCore.visible(entry,hidden,hiddenCategories,favorites,
                    allowed,hideUnknown,manual,restoredCategories))continue;
                results.add(entry);
                if(results.size()>=max)break;
            }
        }
        return results;
    }

    /** URLs are only decrypted when a user actually opens a title. */
    public LibraryCore.Item resolve(LibraryCore.Item item) throws Exception {
        if (item.url != null && !item.url.isEmpty()) return item;
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT url FROM entries WHERE item_id=?", new String[]{item.id})) {
            if (!c.moveToFirst()) throw new IllegalStateException("Title no longer exists");
            byte[] stored=c.getBlob(0);
            // Compact Xtream references contain no credentials; the saved
            // server login is opened only when a user chooses this title.
            String maybeReference=new String(stored,StandardCharsets.UTF_8);
            if(XtreamReference.isReference(maybeReference)){
                SharedPreferences source=context.getSharedPreferences("library",Context.MODE_PRIVATE);
                item.url=XtreamReference.url(maybeReference,
                    Vault.open(source.getString("url","")),
                    Vault.open(source.getString("user","")),
                    Vault.open(source.getString("pass","")));
            }else item.url=decrypt(stored); // Previous encrypted imports and M3U.
            return item;
        }
    }

    public Writer writer() throws Exception { return new Writer(false); }
    /** Incrementally add film/series catalogues after Live TV is already usable. */
    public Writer appendWriter() throws Exception { return new Writer(true); }

    public final class Writer implements AutoCloseable {
        private final SQLiteDatabase database;
        private final SQLiteStatement insert;
        private final boolean append;
        private Cipher writerCipher;
        private SecretKey writerKey;
        private int count;
        private boolean committed;
        private boolean closed;

        private Writer(boolean append) throws Exception {
            this.append=append;
            // Most Xtream entries are lightweight credential-free references.
            // Initialize AES only for non-Xtream URLs (M3U and fallbacks).
            database=getWritableDatabase();
            database.beginTransaction();
            try {
                if(!append){
                    database.execSQL("DROP TABLE IF EXISTS staging");
                    database.execSQL("CREATE TABLE staging " + COLUMNS);
                }
                insert=database.compileStatement(
                    "INSERT OR REPLACE INTO "+(append?"entries":"staging")+
                    "(item_id,name,category,type,epg,language,url,artwork,release_year,added_at,rating)" +
                    " VALUES (?,?,?,?,?,?,?,?,?,?,?)");
            } catch(Exception error) {
                database.endTransaction();
                throw error;
            }
        }

        private void bind(int index, String s) {
            insert.bindString(index, s == null ? "" : s);
        }

        private byte[] encryptedUrl(String value) throws Exception {
            if(writerCipher==null){
                writerKey=key();
                writerCipher=Cipher.getInstance("AES/GCM/NoPadding");
            }
            byte[] iv=new byte[12];
            RANDOM.nextBytes(iv);
            writerCipher.init(Cipher.ENCRYPT_MODE,writerKey,new GCMParameterSpec(128,iv));
            byte[] data=writerCipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] result=new byte[iv.length+data.length];
            System.arraycopy(iv,0,result,0,iv.length);
            System.arraycopy(data,0,result,iv.length,data.length);
            return result;
        }

        public void add(LibraryCore.Item item) throws Exception {
            insert.clearBindings();
            bind(1, item.id);
            bind(2, item.name);
            bind(3, item.category);
            bind(4, item.type);
            bind(5, item.epgId);
            bind(6, item.language);
            String value=item.url==null?"":item.url;
            insert.bindBlob(7,XtreamReference.isReference(value)?
                value.getBytes(StandardCharsets.UTF_8):encryptedUrl(value));
            bind(8, item.artwork);
            int year=item.releaseYear;
            if(year==0 && ("movie".equals(item.type)||"series".equals(item.type)))
                year=MediaDiscovery.yearFromTitle(item.name,MediaDiscovery.currentYear());
            insert.bindLong(9,year);
            insert.bindLong(10,Math.max(0,item.addedAt));
            insert.bindDouble(11,item.rating);
            insert.executeInsert();
            count++;
        }

        public int count() { return count; }

        public void commit() throws Exception {
            if (count == 0) throw new IllegalStateException("No supported titles found");
            if(!append){
                database.execSQL("DROP TABLE entries");
                database.execSQL("ALTER TABLE staging RENAME TO entries");
                database.execSQL("CREATE INDEX entry_type_row ON entries(type,row_id)");
                database.execSQL("CREATE INDEX entry_type_category ON entries(type,category,row_id)");
                database.execSQL("CREATE INDEX entry_type_release ON entries(type,release_year DESC,added_at DESC)");
            }
            database.setTransactionSuccessful();
            database.endTransaction();
            committed = true;
            closed = true;
            insert.close();
            if (!preferences.edit().putBoolean("catalog_ready", true).commit())
                throw new IllegalStateException("Could not mark library as ready");
        }

        @Override public void close() {
            if (closed) return;
            insert.close();
            database.endTransaction();  // rollback when not committed
            closed = true;
        }
    }
}
