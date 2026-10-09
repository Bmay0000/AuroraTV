package tv.aurora.player;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One silent video preview for the entire guide; never one decoder per row.
 * Focus changes are debounced, network/URL decryption is off the UI thread,
 * and stale asynchronous completions are discarded.
 */
public final class GuidePreviewPane implements AutoCloseable {
    private static final long FOCUS_DELAY_MS=950;
    private static final long CONNECT_TIMEOUT_MS=9000;
    private final Activity activity;
    private final LibraryStore catalog;
    private final PosterLoader posters;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final FrameLayout videoSurface;
    private final ImageView channelArtwork;
    private final TextView title, category, now, next, status;
    private final LinearLayout content;
    private ExoPlayer preview;
    private PlayerView videoView;
    private Runnable pendingStart, pendingTimeout;
    private int token;
    private boolean closed;
    private final boolean autoVideo;

    private int dp(int v){return (int)(v*activity.getResources().getDisplayMetrics().density);}
    private GradientDrawable panel(int color){
        GradientDrawable d=new GradientDrawable();d.setColor(color);
        d.setCornerRadius(dp(14));return d;
    }
    private TextView text(String value,int size,int color){
        TextView v=new TextView(activity);v.setText(value);v.setTextSize(size);
        v.setTextColor(color);v.setMaxLines(2);v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return v;
    }
    public GuidePreviewPane(Activity activity,LibraryStore catalog,PosterLoader posters,
                            LinearLayout container,TvLayout metrics,boolean autoVideo){
        this(activity,catalog,posters,container,metrics,autoVideo,false);
    }
    public GuidePreviewPane(Activity activity,LibraryStore catalog,PosterLoader posters,
                            LinearLayout container,TvLayout metrics,boolean autoVideo,
                            boolean sidePane){
        this.activity=activity;this.catalog=catalog;this.posters=posters;
        this.autoVideo=autoVideo;
        container.setOrientation(sidePane?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.CENTER_VERTICAL);
        container.setPadding(dp(12),dp(9),dp(16),dp(9));
        container.setBackground(panel(0xff11273a));
        videoSurface=new FrameLayout(activity);
        videoSurface.setBackground(panel(0xff192d42));
        videoSurface.setClipToOutline(true);
        int paneHeight=TvLayout.clamp((int)(metrics.heightDp*.15),65,138);
        int width=sidePane?TvLayout.clamp((int)(metrics.contentWidth()*.245),155,410):
            (int)Math.round(Math.max(40,paneHeight-18)*16.0/9.0);
        int height=sidePane?(int)Math.round(width*9.0/16.0):
            Math.max(40,paneHeight-18);
        LinearLayout.LayoutParams videoBounds=new LinearLayout.LayoutParams(
            sidePane?-1:dp(width),dp(height));
        if(sidePane)videoBounds.bottomMargin=dp(9);
        container.addView(videoSurface,videoBounds);
        channelArtwork=new ImageView(activity);
        channelArtwork.setScaleType(ImageView.ScaleType.FIT_CENTER);
        channelArtwork.setPadding(dp(12),dp(12),dp(12),dp(12));
        videoSurface.addView(channelArtwork,new FrameLayout.LayoutParams(-1,-1));

        content=new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(sidePane?dp(5):dp(19),0,0,0);
        container.addView(content,sidePane?
            new LinearLayout.LayoutParams(-1,-2):
            new LinearLayout.LayoutParams(0,-1,1));
        boolean compact=!sidePane&&metrics.heightDp<700;
        TextView eyebrow=text("AUTO PREVIEW  ·  MUTED",11,0xff5debd0);
        eyebrow.setLetterSpacing(.1f);
        if(!compact)content.addView(eyebrow);
        title=text("Highlight a channel",compact?16:TvLayout.clamp(metrics.bodySize()+5,20,29),Color.WHITE);
        if(compact)title.setMaxLines(1);
        title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);content.addView(title);
        category=text("Browse to see what's playing",13,0xff9eafc2);
        if(!compact)content.addView(category);
        now=text("NOW  ·  No channel selected",compact?12:14,Color.WHITE);
        if(compact)now.setMaxLines(1);
        LinearLayout.LayoutParams n=new LinearLayout.LayoutParams(-1,-2);n.topMargin=dp(compact?2:7);
        content.addView(now,n);
        next=text("NEXT  ·  —",13,0xffb3c9d4);
        if(!compact)content.addView(next);
        status=text("Preview starts when highlighted.",compact?10:12,0xff6fd6c9);
        if(compact)status.setMaxLines(1);
        LinearLayout.LayoutParams st=new LinearLayout.LayoutParams(-1,-2);st.topMargin=dp(compact?2:8);
        content.addView(status,st);
    }
    public void highlight(LibraryCore.Item item,GuideEngine.Slot slot) {
        if(closed)return;
        final int selected=++token;
        cancelPending();
        stopPlayer();
        title.setText(item.name);
        category.setText(item.category);
        now.setText(slot!=null&&slot.now!=null?"NOW  ·  "+slot.now.title:"NOW  ·  No guide listing");
        next.setText(slot!=null&&slot.next!=null?"NEXT  ·  "+slot.next.title:"NEXT  ·  Not available");
        status.setText(autoVideo?"Previewing shortly…":"Automatic video disabled in Guide settings");
        posters.bind(channelArtwork,item.artwork);
        if(!autoVideo)return;
        pendingStart=()->{
            if(closed||selected!=token)return;
            status.setText("Connecting to muted live preview…");
            worker.execute(()->{
                try{
                    LibraryCore.Item resolved=catalog.resolve(item);
                    final String url=resolved.url;
                    ui.post(()->{if(!closed&&selected==token)start(url,selected);});
                }catch(Exception ignored){
                    ui.post(()->{if(!closed&&selected==token)
                        status.setText("Preview unavailable  ·  Select to open channel");});
                }
            });
        };
        ui.postDelayed(pendingStart,FOCUS_DELAY_MS);
    }
    /** Refresh programme labels without tearing down the stream on each EPG update. */
    public void updateSchedule(GuideEngine.Slot slot){
        if(closed)return;
        now.setText(slot!=null&&slot.now!=null?"NOW  ·  "+slot.now.title:"NOW  ·  No listing");
        next.setText(slot!=null&&slot.next!=null?"NEXT  ·  "+slot.next.title:"NEXT  ·  Unavailable");
    }
    private void start(String url,int selected){
        if(url==null||url.isEmpty()||closed||token!=selected)return;
        try{
            ExoPlayer p=new ExoPlayer.Builder(activity)
                .setLoadControl(new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(3000,11000,900,1800)
                    .setTargetBufferBytes(8*1024*1024).build()).build();
            preview=p;
            videoView=new PlayerView(activity);
            videoView.setUseController(false);
            videoView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
            videoView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER);
            videoSurface.addView(videoView,new FrameLayout.LayoutParams(-1,-1));
            videoView.setPlayer(p);
            p.setVolume(0f);
            MediaItem.Builder builder=new MediaItem.Builder().setUri(url);
            if(PlaybackTuning.isHls(url))builder.setMimeType(MimeTypes.APPLICATION_M3U8);
            p.addListener(new Player.Listener(){
                @Override public void onPlaybackStateChanged(int state){
                    if(closed||token!=selected||preview!=p)return;
                    if(state==Player.STATE_READY){status.setText("●  LIVE PREVIEW  ·  MUTED");cancelTimeout();}
                }
                @Override public void onPlayerError(androidx.media3.common.PlaybackException e){
                    if(!closed&&token==selected&&preview==p){
                        stopPlayer();status.setText("Preview unavailable  ·  Select to watch");
                    }
                }
            });
            p.setMediaItem(builder.build());
            p.prepare();p.play();
            pendingTimeout=()->{
                if(!closed&&token==selected&&preview==p&&p.getPlaybackState()!=Player.STATE_READY){
                    stopPlayer();
                    status.setText("Preview timed out  ·  Select to watch");
                }
            };
            ui.postDelayed(pendingTimeout,CONNECT_TIMEOUT_MS);
        }catch(Exception ex){
            stopPlayer();status.setText("Preview unavailable  ·  Select to watch");
        }
    }
    private void cancelTimeout(){
        if(pendingTimeout!=null){ui.removeCallbacks(pendingTimeout);pendingTimeout=null;}
    }
    private void cancelPending(){
        if(pendingStart!=null){ui.removeCallbacks(pendingStart);pendingStart=null;}
        cancelTimeout();
    }
    private void stopPlayer(){
        if(preview!=null){
            ExoPlayer old=preview;preview=null;
            if(videoView!=null){videoView.setPlayer(null);videoSurface.removeView(videoView);videoView=null;}
            try{old.release();}catch(Exception ignored){}
        }
    }
    @Override public void close(){
        if(closed)return;
        closed=true;token++;
        cancelPending();stopPlayer();worker.shutdownNow();
    }
}
