package tv.aurora.player;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
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
 * Separate small poster decodes from TV-resolution backdrops. One URL can be
 * decoded in both sizes without showing a blurry 360px thumbnail at 1080p.
 * Network, bitmap memory, and disk usage remain bounded on low-RAM Fire TV.
 */
public final class PosterLoader implements AutoCloseable {
    private static final int MAX_DOWNLOAD=8*1024*1024;
    private static final long MAX_DISK_CACHE=96L*1024*1024;
    private final Activity activity;
    private final ExecutorService background=Executors.newFixedThreadPool(3);
    private final LruCache<String,Bitmap> memory;
    private final boolean lowMemory;
    private final java.util.concurrent.ConcurrentHashMap<String,
        java.util.concurrent.CopyOnWriteArrayList<WeakReference<ImageView>>>
        inFlight=new java.util.concurrent.ConcurrentHashMap<>();
    private final File cacheDir;
    private final Set<String> missed=Collections.synchronizedSet(new HashSet<String>());

    public PosterLoader(Activity activity){
        this.activity=activity;
        ActivityManager manager=(ActivityManager)activity.getSystemService(Context.ACTIVITY_SERVICE);
        int memoryMb=manager==null?128:manager.getMemoryClass();
        lowMemory=manager!=null&&manager.isLowRamDevice();
        int maxKb=Math.min(48*1024,Math.max(14*1024,
            (memoryMb*1024)/(lowMemory?8:5)));
        memory=new LruCache<String,Bitmap>(maxKb){
            @Override protected int sizeOf(String key,Bitmap image){
                return Math.max(1,image.getByteCount()/1024);
            }
        };
        cacheDir=new File(activity.getCacheDir(),"posters");
        if(!cacheDir.isDirectory())cacheDir.mkdirs();
        background.execute(this::trimDiskCache);
    }
    public void bind(ImageView image,String raw){ bindInternal(image,raw,false); }
    public void bindBackdrop(ImageView image,String raw){ bindInternal(image,raw,true); }

    private void bindInternal(ImageView image,String raw,boolean backdrop){
        String url=raw==null?"":raw.trim();
        String key=url+(backdrop?"|wide":"|poster");
        if(key.equals(image.getTag())&&image.getDrawable()!=null)return;
        image.setTag(key);
        image.setImageDrawable(null);
        if(!(url.startsWith("https://")||url.startsWith("http://"))
                ||url.length()>1600||missed.contains(key))return;
        Bitmap ready=memory.get(key);
        if(ready!=null){image.setImageBitmap(ready);return;}
        WeakReference<ImageView> target=new WeakReference<>(image);
        java.util.concurrent.CopyOnWriteArrayList<WeakReference<ImageView>> fresh=
            new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.CopyOnWriteArrayList<WeakReference<ImageView>> existing=
            inFlight.putIfAbsent(key,fresh);
        if(existing!=null){
            existing.add(target);
            Bitmap cached=memory.get(key);
            if(cached!=null&&key.equals(image.getTag()))image.setImageBitmap(cached);
            return;
        }
        fresh.add(target);
        background.execute(()->{
            Bitmap artwork=null;
            try{artwork=getOrFetch(url,key,backdrop);}
            catch(Exception ignored){}
            finally{
                boolean stillCurrent=inFlight.remove(key,fresh);
                java.util.concurrent.CopyOnWriteArrayList<WeakReference<ImageView>> targets=
                    stillCurrent?fresh:null;
                final Bitmap result=artwork;
                if(result!=null&&targets!=null)activity.runOnUiThread(()->{
                    if(activity.isDestroyed())return;
                    for(WeakReference<ImageView> ref:targets){
                        ImageView current=ref.get();
                        if(current!=null&&key.equals(current.getTag()))
                            current.setImageBitmap(result);
                    }
                });
            }
        });
    }

    private Bitmap getOrFetch(String url,String key,boolean backdrop){
        Bitmap ready=memory.get(key);
        if(ready!=null)return ready;
        File file=new File(cacheDir,LibraryCore.key(url)+".poster");
        byte[] bytes=null;
        if(file.isFile()&&file.length()>0&&file.length()<=MAX_DOWNLOAD){
            try(FileInputStream stream=new FileInputStream(file)){
                bytes=readBounded(stream);
                file.setLastModified(System.currentTimeMillis());
            }catch(Exception ignored){file.delete();}
        }
        if(bytes==null){
            HttpURLConnection connection=null;
            try{
                connection=(HttpURLConnection)new URL(url).openConnection();
                connection.setConnectTimeout(5500);
                connection.setReadTimeout(7000);
                connection.setRequestProperty("User-Agent","AuroraTV/1.3");
                if(connection.getResponseCode()!=HttpURLConnection.HTTP_OK
                    ||connection.getContentLength()>MAX_DOWNLOAD)return null;
                try(InputStream stream=connection.getInputStream()){
                    bytes=readBounded(stream);
                }
                if(bytes==null||bytes.length==0)return null;
                try(FileOutputStream stream=new FileOutputStream(file)){
                    stream.write(bytes);
                }catch(IOException ignored){file.delete();}
            }catch(Exception ignored){
                if(missed.size()<1000)missed.add(key);
                return null;
            }finally{
                if(connection!=null)connection.disconnect();
            }
        }
        Bitmap decoded=decode(bytes,backdrop,lowMemory);
        if(decoded==null){
            file.delete();
            if(missed.size()<1000)missed.add(key);
        }else memory.put(key,decoded);
        return decoded;
    }

    private static byte[] readBounded(InputStream stream)throws IOException{
        ByteArrayOutputStream result=new ByteArrayOutputStream(64*1024);
        byte[] buf=new byte[8192];
        int n;
        while((n=stream.read(buf))!=-1){
            if(result.size()+n>MAX_DOWNLOAD)throw new IOException("Artwork exceeds safe size");
            result.write(buf,0,n);
        }
        return result.toByteArray();
    }
    private static Bitmap decode(byte[] bytes,boolean backdrop,boolean lowMemory){
        if(bytes==null||bytes.length==0)return null;
        BitmapFactory.Options bounds=new BitmapFactory.Options();
        bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
        if(bounds.outWidth<=0||bounds.outHeight<=0)return null;
        int maxWidth=backdrop?(lowMemory?1280:2048):420;
        int maxHeight=backdrop?(lowMemory?768:1152):630;
        int sample=1;
        while(bounds.outWidth/sample>maxWidth||bounds.outHeight/sample>maxHeight)sample*=2;
        BitmapFactory.Options opts=new BitmapFactory.Options();
        opts.inSampleSize=sample;
        opts.inPreferredConfig=backdrop&&!lowMemory?
            Bitmap.Config.ARGB_8888:Bitmap.Config.RGB_565;
        try{return BitmapFactory.decodeByteArray(bytes,0,bytes.length,opts);}
        catch(OutOfMemoryError ignored){return null;}
    }
    private void trimDiskCache(){
        File[] files=cacheDir.listFiles();
        if(files==null)return;
        long bytes=0;
        for(File f:files)bytes+=f.length();
        if(bytes<=MAX_DISK_CACHE)return;
        Arrays.sort(files,Comparator.comparingLong(File::lastModified));
        for(File file:files){
            if(bytes<=MAX_DISK_CACHE)break;
            long size=file.length();
            if(file.delete())bytes-=size;
        }
    }
    public void beginSection(){
        java.util.concurrent.ThreadPoolExecutor pool=
            (java.util.concurrent.ThreadPoolExecutor)background;
        pool.getQueue().clear();
        inFlight.clear();
    }
    public void clearMemory(){memory.evictAll();}
    @Override public void close(){
        background.shutdownNow();inFlight.clear();memory.evictAll();
    }
}
