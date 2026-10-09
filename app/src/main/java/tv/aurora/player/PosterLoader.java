package tv.aurora.player;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight poster loader: URL-aware view recycling, a small memory cache,
 * bounded image downloads, scaled decoding, and reusable on-device disk cache.
 * No third-party image library or full-size movie poster bitmaps in memory.
 */
public final class PosterLoader implements AutoCloseable {
    private static final int MAX_DOWNLOAD = 2 * 1024 * 1024;
    private static final long MAX_DISK_CACHE = 96L * 1024 * 1024;
    private final Activity activity;
    private final ExecutorService background = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(24 * 1024) {
        @Override protected int sizeOf(String key, Bitmap b) {
            return Math.max(1, b.getByteCount() / 1024);
        }
    };
    // A film may appear in Continue Watching, recent releases and several
    // genre shelves. Download/decode its poster just once, then share it
    // among all visible ImageViews.
    private final java.util.concurrent.ConcurrentHashMap<String,
        java.util.concurrent.CopyOnWriteArrayList<WeakReference<ImageView>>>
        inFlight = new java.util.concurrent.ConcurrentHashMap<>();
    private final File cacheDir;
    private final Set<String> missed = Collections.synchronizedSet(new HashSet<String>());

    public PosterLoader(Activity activity) {
        this.activity = activity;
        cacheDir = new File(activity.getCacheDir(), "posters");
        if (!cacheDir.isDirectory()) cacheDir.mkdirs();
        background.execute(this::trimDiskCache);
    }

    public void bind(ImageView image, String raw) {
        String url=raw==null?"":raw.trim();
        if(url.equals(image.getTag())&&image.getDrawable()!=null)return;
        image.setTag(url);
        image.setImageDrawable(null);
        if(!(url.startsWith("https://")||url.startsWith("http://"))
                ||url.length()>1600||missed.contains(url))return;
        Bitmap ready=memory.get(url);
        if(ready!=null){image.setImageBitmap(ready);return;}
        WeakReference<ImageView> target=new WeakReference<>(image);
        java.util.concurrent.CopyOnWriteArrayList<WeakReference<ImageView>> fresh=
            new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.CopyOnWriteArrayList<WeakReference<ImageView>> existing=
            inFlight.putIfAbsent(url,fresh);
        if(existing!=null){
            existing.add(target);
            // A finished request might have removed itself while we were
            // joining its listeners. Reuse the completed memory bitmap.
            Bitmap cached=memory.get(url);
            if(cached!=null&&url.equals(image.getTag()))image.setImageBitmap(cached);
            return;
        }
        fresh.add(target);
        background.execute(()->{
            Bitmap artwork=null;
            try{artwork=getOrFetch(url);}
            catch(Exception ignored){}
            finally{
                java.util.concurrent.CopyOnWriteArrayList<WeakReference<ImageView>> receivers=
                    inFlight.remove(url);
                final Bitmap result=artwork;
                if(result!=null && receivers!=null)
                    activity.runOnUiThread(()->{
                        if(activity.isDestroyed())return;
                        for(WeakReference<ImageView> ref:receivers){
                            ImageView current=ref.get();
                            if(current!=null&&url.equals(current.getTag()))
                                current.setImageBitmap(result);
                        }
                    });
            }
        });
    }

    private Bitmap getOrFetch(String url) {
        Bitmap ready = memory.get(url);
        if (ready != null) return ready;
        File file = new File(cacheDir, LibraryCore.key(url) + ".poster");
        byte[] bytes = null;
        if (file.isFile() && file.length() > 0 && file.length() <= MAX_DOWNLOAD) {
            try (FileInputStream stream = new FileInputStream(file)) {
                bytes = readBounded(stream);
                file.setLastModified(System.currentTimeMillis());
            } catch (Exception ignored) {
                file.delete();
            }
        }
        if (bytes == null) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(6000);
                connection.setRequestProperty("User-Agent", "AuroraTV/0.2");
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK
                        || connection.getContentLength() > MAX_DOWNLOAD) return null;
                try (InputStream stream = connection.getInputStream()) {
                    bytes = readBounded(stream);
                }
                if (bytes == null || bytes.length == 0) return null;
                try (FileOutputStream stream = new FileOutputStream(file)) {
                    stream.write(bytes);
                } catch (IOException ignored) {
                    file.delete();
                }
            } catch (Exception ignored) {
                if (missed.size() < 1000) missed.add(url);
                return null;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        Bitmap decoded = decodePoster(bytes);
        if (decoded == null) {
            file.delete();
            if (missed.size() < 1000) missed.add(url);
        } else memory.put(url, decoded);
        return decoded;
    }

    private static byte[] readBounded(InputStream stream) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream(64 * 1024);
        byte[] buffer = new byte[8192];
        int n;
        while ((n = stream.read(buffer)) != -1) {
            if (result.size() + n > MAX_DOWNLOAD) throw new IOException("Poster too large");
            result.write(buffer, 0, n);
        }
        return result.toByteArray();
    }

    private static Bitmap decodePoster(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options scaled = new BitmapFactory.Options();
        scaled.inPreferredConfig = Bitmap.Config.RGB_565;
        int sample = 1;
        while (bounds.outWidth / sample > 360 || bounds.outHeight / sample > 540) sample *= 2;
        scaled.inSampleSize = sample;
        try {
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, scaled);
        } catch (OutOfMemoryError ignored) {
            return null;
        }
    }

    private void trimDiskCache() {
        File[] files = cacheDir.listFiles();
        if (files == null) return;
        long bytes = 0;
        for (File f : files) bytes += f.length();
        if (bytes <= MAX_DISK_CACHE) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File file : files) {
            if (bytes <= MAX_DISK_CACHE) break;
            long size = file.length();
            if (file.delete()) bytes -= size;
        }
    }

    /** Clear nonessential poster bitmaps before launching a hardware decoder. */
    public void clearMemory() { memory.evictAll(); }

    @Override public void close() {
        background.shutdownNow();
        inFlight.clear();
        memory.evictAll();
    }
}
