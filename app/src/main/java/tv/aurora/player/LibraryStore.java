package tv.aurora.player;

import android.content.Context;
import android.util.Base64;
import android.util.Base64InputStream;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.JsonWriter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Streams the full catalog to an encrypted file and reads it one item at a time.
 * No full-library JSONArray, String or List is ever required.
 */
public final class LibraryStore {
    private static final byte[] MAGIC = {'A', 'U', 'R', '2'};
    private final File target;
    private final File temp;

    public LibraryStore(Context context) {
        target = new File(context.getFilesDir(), "library.enc");
        temp = new File(context.getFilesDir(), "library.enc.tmp");
    }

    public boolean hasLibrary() {
        return target.isFile() && target.length() > 16;
    }

    public void clear() {
        target.delete();
        temp.delete();
    }

    public interface Visitor {
        /** Return false to stop scanning. */
        boolean visit(LibraryCore.Item item);
    }

    public void forEach(Visitor visitor) throws Exception {
        if (!hasLibrary()) return;
        try (JsonReader reader = new JsonReader(new BufferedReader(
                new InputStreamReader(decrypted(), StandardCharsets.UTF_8), 32768))) {
            reader.beginArray();
            while (reader.hasNext()) {
                LibraryCore.Item item = readItem(reader);
                if (!visitor.visit(item)) return;
            }
            reader.endArray();
        }
    }

    private InputStream decrypted() throws Exception {
        PushbackInputStream in = new PushbackInputStream(
                new BufferedInputStream(new FileInputStream(target), 32768), 4);
        try {
            byte[] header = new byte[4];
            if (in.read(header) != 4) throw new IOException("Saved library is incomplete");
            byte[] iv;
            InputStream ciphertext;
            if (Arrays.equals(header, MAGIC)) {
                iv = new byte[12];
                DataInputStream data = new DataInputStream(in);
                data.readFully(iv);
                ciphertext = in;
            } else {
                // Compatible with AuroraTV's previous ivBase64:ciphertextBase64 file.
                in.unread(header);
                ByteArrayOutputStream prefix = new ByteArrayOutputStream(32);
                int b;
                while ((b = in.read()) != -1 && b != ':') {
                    if (prefix.size() >= 100) throw new IOException("Unrecognized library format");
                    prefix.write(b);
                }
                if (b != ':') throw new IOException("Unrecognized library format");
                iv = Base64.decode(prefix.toByteArray(), Base64.NO_WRAP);
                ciphertext = new Base64InputStream(in, Base64.NO_WRAP);
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, Vault.key(), new GCMParameterSpec(128, iv));
            return new CipherInputStream(ciphertext, cipher);
        } catch (Exception error) {
            in.close();
            throw error;
        }
    }

    private static LibraryCore.Item readItem(JsonReader reader) throws IOException {
        String id = "", name = "", category = "", url = "", type = "", epg = "", language = "";
        reader.beginObject();
        while (reader.hasNext()) {
            String key = reader.nextName();
            if (reader.peek() == JsonToken.NULL) {
                reader.nextNull();
                continue;
            }
            switch (key) {
                case "id": id = reader.nextString(); break;
                case "name": name = reader.nextString(); break;
                case "category": category = reader.nextString(); break;
                case "url": url = reader.nextString(); break;
                case "type": type = reader.nextString(); break;
                case "epgId": epg = reader.nextString(); break;
                case "language": language = reader.nextString(); break;
                default: reader.skipValue();
            }
        }
        reader.endObject();
        return new LibraryCore.Item(id, name, category, url, type, epg, language);
    }

    public Writer writer() throws Exception { return new Writer(); }

    public final class Writer implements AutoCloseable {
        private JsonWriter json;
        private int count;
        private boolean committed;

        private Writer() throws Exception {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, Vault.key());
            FileOutputStream file = new FileOutputStream(temp);
            try {
                file.write(MAGIC);
                file.write(cipher.getIV());
                json = new JsonWriter(new BufferedWriter(new OutputStreamWriter(
                        new CipherOutputStream(file, cipher), StandardCharsets.UTF_8), 32768));
                json.beginArray();
            } catch (Exception error) {
                file.close();
                temp.delete();
                throw error;
            }
        }

        public void add(LibraryCore.Item item) throws Exception {
            json.beginObject();
            json.name("id").value(item.id);
            json.name("name").value(item.name);
            json.name("category").value(item.category);
            json.name("url").value(item.url);
            json.name("type").value(item.type);
            json.name("epgId").value(item.epgId);
            json.name("language").value(item.language);
            json.endObject();
            count++;
        }

        public int count() { return count; }

        public void commit() throws Exception {
            if (count == 0) throw new IOException("No supported titles found");
            json.endArray();
            json.close();
            json = null;
            if (!temp.renameTo(target)) throw new IOException("Could not save imported library");
            committed = true;
        }

        @Override public void close() throws Exception {
            try { if (json != null) json.close(); }
            finally { if (!committed) temp.delete(); }
        }
    }
}
