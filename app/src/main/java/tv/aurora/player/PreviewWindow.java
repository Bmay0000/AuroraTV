package tv.aurora.player;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import java.util.Locale;

/** Lightweight on-demand channel preview. Never autoplays while guide scrolls. */
public final class PreviewWindow {
    private final Activity activity;
    private final LibraryCore.Item channel;
    private final GuideEngine.Slot schedule;
    private final PosterLoader images;
    private final Runnable watch;
    private final Dialog dialog;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private FrameLayout videoBox;
    private LinearLayout posterLayer;
    private TextView status;
    private ExoPlayer previewPlayer;
    private boolean disposed;
    private Runnable timeout;

    private int dp(int v){return (int)(v*activity.getResources().getDisplayMetrics().density);}
    private GradientDrawable shape(int fill,int radius,int stroke){
        GradientDrawable d=new GradientDrawable();
        d.setColor(fill);d.setCornerRadius(dp(radius));
        if(stroke!=0)d.setStroke(dp(1),stroke);
        return d;
    }
    private TextView text(String value,int size,int color){
        TextView t=new TextView(activity);t.setText(value);t.setTextSize(size);
        t.setTextColor(color);t.setPadding(dp(4),dp(4),dp(4),dp(4));
        t.setMaxLines(3);return t;
    }
    private Button button(String name,Runnable callback){
        Button b=new Button(activity);
        b.setAllCaps(false);b.setText(name);b.setTextSize(15);
        b.setTextColor(Color.WHITE);b.setBackground(shape(0xff193549,11,0xff306072));
        b.setMinHeight(0);b.setMinimumHeight(0);b.setPadding(dp(12),0,dp(12),0);
        b.setOnClickListener(v->callback.run());
        b.setOnFocusChangeListener((v,focus)->{
            b.setBackground(shape(focus?0xff5debd0:0xff193549,11,
                focus?0xff5debd0:0xff306072));
            b.setTextColor(focus?0xff07111b:Color.WHITE);
        });
        return b;
    }
    private String program(GuideEngine.Program p){
        if(p==null)return "Schedule not available";
        return p.title+"\n"+android.text.format.DateFormat.format("h:mm a",p.start)+" – "+
            android.text.format.DateFormat.format("h:mm a",p.end);
    }

    public PreviewWindow(Activity activity,LibraryCore.Item channel,
                         GuideEngine.Slot schedule,PosterLoader images,Runnable watch){
        this.activity=activity;this.channel=channel;this.schedule=schedule;
        this.images=images;this.watch=watch;
        dialog=new Dialog(activity);
        TvLayout dims=TvLayout.of(
            (int)(activity.getResources().getDisplayMetrics().widthPixels/
                activity.getResources().getDisplayMetrics().density),
            (int)(activity.getResources().getDisplayMetrics().heightPixels/
                activity.getResources().getDisplayMetrics().density));
        LinearLayout root=new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(shape(0xff101d30,19,0xff355a68));
        root.setPadding(dp(23),dp(16),dp(23),dp(18));
        TextView label=text("AURORATV     •     CHANNEL PREVIEW",12,0xff5debd0);
        label.setLetterSpacing(.12f);root.addView(label);

        LinearLayout content=new LinearLayout(activity);
        content.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams contentSpace=new LinearLayout.LayoutParams(-1,-2);
        contentSpace.topMargin=dp(8);root.addView(content,contentSpace);
        videoBox=new FrameLayout(activity);
        videoBox.setClipToOutline(true);
        videoBox.setBackground(shape(0xff1a384d,12,0));
        int w=TvLayout.clamp((int)(dims.contentWidth()*.48),285,550);
        int height=(int)(w*9f/16f);
        content.addView(videoBox,new LinearLayout.LayoutParams(dp(w),dp(height)));
        posterLayer=new LinearLayout(activity);
        posterLayer.setGravity(Gravity.CENTER);
        posterLayer.setOrientation(LinearLayout.VERTICAL);
        videoBox.addView(posterLayer,new FrameLayout.LayoutParams(-1,-1));
        ImageView art=new ImageView(activity);
        art.setScaleType(ImageView.ScaleType.FIT_CENTER);
        posterLayer.addView(art,new LinearLayout.LayoutParams(-1,0,1));
        images.bind(art,channel.artwork);
        status=text("PREVIEW IS OFF  •  PRESS PREVIEW TO CONNECT",12,0xffc0d8dd);
        status.setGravity(Gravity.CENTER);posterLayer.addView(status);

        LinearLayout info=new LinearLayout(activity);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(18),0,0,0);
        content.addView(info,new LinearLayout.LayoutParams(0,-2,1));
        TextView title=text(channel.name,TvLayout.clamp(dims.headingSize(),24,35),Color.WHITE);
        title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        title.setMaxLines(2);info.addView(title);
        TextView group=text(channel.category,14,0xffa1b7c6);
        info.addView(group);
        TextView now=text("ON NOW",12,0xff5debd0);info.addView(now);
        info.addView(text(schedule==null? "No programme data available":program(schedule.now),
            16,Color.WHITE));
        TextView next=text("UP NEXT",12,0xff5debd0);info.addView(next);
        info.addView(text(schedule==null?"No upcoming listing":program(schedule.next),
            14,0xffb9c8d3));

        LinearLayout controls=new LinearLayout(activity);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams controlsSize=new LinearLayout.LayoutParams(-1,dp(58));
        controlsSize.topMargin=dp(16);root.addView(controls,controlsSize);
        Button preview=button("▶  PLAY MUTED PREVIEW",this::togglePreview);
        controls.addView(preview,new LinearLayout.LayoutParams(0,-1,1));
        Button fullscreen=button("↗  WATCH FULLSCREEN",()->{
            dialog.dismiss();watch.run();
        });
        LinearLayout.LayoutParams playSize=new LinearLayout.LayoutParams(0,-1,1);
        playSize.leftMargin=dp(8);controls.addView(fullscreen,playSize);
        Button close=button("✕ CLOSE",dialog::dismiss);
        LinearLayout.LayoutParams closeSize=new LinearLayout.LayoutParams(dp(105),-1);
        closeSize.leftMargin=dp(8);controls.addView(close,closeSize);

        dialog.setContentView(root);
        Window win=dialog.getWindow();
        if(win!=null){
            win.setBackgroundDrawableResource(android.R.color.transparent);
            win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp=win.getAttributes();
            lp.dimAmount=.75f;win.setAttributes(lp);
        }
        dialog.setOnDismissListener(d->closePlayer());
        dialog.show();
        if(win!=null)win.setLayout(dp(Math.min(dims.widthDp-2*dims.marginX,1120)),-2);
        fullscreen.requestFocus();
    }

    private void togglePreview(){
        if(disposed)return;
        if(previewPlayer!=null){stopPreview();return;}
        status.setText("Connecting to preview…");
        String original=channel.url;
        if(original==null || original.isEmpty()){
            status.setText("Preview unavailable for this channel");return;
        }
        SharedPreferences settings=activity.getSharedPreferences("playback_options",
            Context.MODE_PRIVATE);
        String url=PlaybackTuning.resolveUrl(original,"live",
            PlaybackTuning.format(settings.getString("live_format","original")));
        try{
            boolean hls=PlaybackTuning.isHls(url);
            DefaultLoadControl load=new DefaultLoadControl.Builder()
                .setBufferDurationsMs(3500,12000,800,1500)
                .setTargetBufferBytes(10*1024*1024).build();
            previewPlayer=new ExoPlayer.Builder(activity).setLoadControl(load).build();
            ExoPlayer active=previewPlayer;
            PlayerView surface=new PlayerView(activity);
            surface.setUseController(false);
            surface.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
            videoBox.addView(surface,new FrameLayout.LayoutParams(-1,-1));
            surface.setPlayer(active);
            active.setVolume(0f);
            MediaItem.Builder builder=new MediaItem.Builder().setUri(url);
            if(hls)builder.setMimeType(MimeTypes.APPLICATION_M3U8);
            active.addListener(new Player.Listener(){
                @Override public void onPlaybackStateChanged(int state){
                    if(disposed || previewPlayer!=active)return;
                    if(state==Player.STATE_READY)status.setText("MUTED LIVE PREVIEW");
                }
                @Override public void onPlayerError(androidx.media3.common.PlaybackException error){
                    if(previewPlayer!=active || disposed)return;
                    stopPreview();status.setText("Preview unavailable • try Watch Fullscreen");
                }
            });
            active.setMediaItem(builder.build());
            active.prepare();active.play();
            timeout=()->{
                if(disposed || previewPlayer!=active)return;
                if(active.getPlaybackState()!=Player.STATE_READY){
                    stopPreview();
                    status.setText("Preview timed out • Watch Fullscreen may still work");
                }
            };
            handler.postDelayed(timeout,11000L);
        }catch(Exception error){
            stopPreview();
            status.setText("Preview unavailable • try Watch Fullscreen");
        }
    }
    private void stopPreview(){
        if(timeout!=null){handler.removeCallbacks(timeout);timeout=null;}
        if(previewPlayer!=null){
            ExoPlayer old=previewPlayer;previewPlayer=null;
            try{old.stop();old.release();}catch(Exception ignored){}
        }
        // Stop all preview media surfaces; the poster remains underneath.
        if(videoBox.getChildCount()>1)videoBox.removeViews(1,videoBox.getChildCount()-1);
    }
    private void closePlayer(){
        disposed=true;
        stopPreview();
    }
}
