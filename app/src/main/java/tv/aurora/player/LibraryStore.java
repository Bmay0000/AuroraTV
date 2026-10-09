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
    private static final int VERSION = 1;
    private static final String COLUMNS =
            "(row_id INTEGER PRIMARY KEY AUTOINCREMENT, item_id TEXT NOT NULL UNIQUE, " +
            "name TEXT NOT NULL, category TEXT NOT NULL, type TEXT NOT NULL, " +
            "epg TEXT, language TEXT, url BLOB NOT NULL, artwork TEXT)";
    private static final String FIELDS =
            "item_id,name,category,type,epg,language,artwork";
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
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // A future migration must preserve the user's imported library.
        throw new IllegalStateException("Unsupported catalog version");
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
                    "(item_id,name,category,type,epg,language,url,artwork)" +
                    " VALUES (?,?,?,?,?,?,?,?)");
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
