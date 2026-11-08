package tv.aurora.rust;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.graphics.drawable.GradientDrawable;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Native Android playback Activity for AuroraTV Rust. All catalog, guide and
 * provider decisions originate in Rust; this class owns only the media surface,
 * decoder lifecycle and Fire TV remote controls.
 */
public final class PlayerActivity extends Activity implements SurfaceHolder.Callback {
    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private SurfaceView surface;
    private LinearLayout controls;
    private TextView heading, status;
    private SeekBar progress;
    private MediaPlayer player;
    private final ArrayList<String> urls = new ArrayList<>();
    private int sourceIndex=0, aspectMode=0;
    private int videoWidth=1920, videoHeight=1080;
    private boolean live=false, prepared=false, changing=false;
    private long activeToken=0;
    private Button pauseButton;
    private int audioTrack=-1, subtitleTrack=-1;
    private final Runnable tick=new Runnable(){
        @Override public void run(){
            if(player!=null&&prepared){
                try{
                    int duration=player.getDuration();
                    if(duration>0&&!live){
                        progress.setMax(duration);
                        progress.setProgress(player.getCurrentPosition());
                    }
                }catch(Exception ignored){}
            }
            main.postDelayed(this,1000);
        }
    };
    private final Runnable hideControls=()->{
        if(prepared && player!=null && player.isPlaying()) controls.setVisibility(View.INVISIBLE);
    };
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_FULLSCREEN);
        live=getIntent().getBooleanExtra("live",false);
        String url=getIntent().getStringExtra("stream_url");
        String options=getIntent().getStringExtra("stream_options");
        if(options!=null){
            try{
                JSONArray arr=new JSONArray(options);
                for(int i=0;i<arr.length()&&i<6;i++){
                    String item=arr.optString(i);
                    if((item.startsWith("http://")||item.startsWith("https://"))&&!urls.contains(item)){
                        urls.add(item);
                    }
                }
            }catch(Exception ignored){}
        }
        if(url!=null && (url.startsWith("http://")||url.startsWith("https://")) && !urls.contains(url)){
            urls.add(0,url);
        }
        buildUI(getIntent().getStringExtra("stream_title"));
        if(urls.isEmpty()){
            message("No valid provider playback URL was supplied.");
            return;
        }
        main.post(tick);
    }
    private GradientDrawable background(int color){
        GradientDrawable drawable=new GradientDrawable();
        drawable.setColor(color);drawable.setCornerRadius(14);
        drawable.setStroke(1,0xff41515f);return drawable;
    }
    private Button button(String label,Runnable onClick){
        Button b=new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setFocusable(true);
        b.setBackground(background(0xff22333f));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(
            0,55,1);
        lp.setMargins(4,0,4,0);
        controls.addView(b,lp);
        b.setOnClickListener(v->{showControls();onClick.run();});
        return b;
    }
    private void buildUI(String title){
        root=new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surface=new SurfaceView(this);
        surface.getHolder().addCallback(this);
        root.addView(surface,new FrameLayout.LayoutParams(-1,-1));

        LinearLayout banner=new LinearLayout(this);
        banner.setOrientation(LinearLayout.VERTICAL);
        banner.setPadding(24,15,24,15);
        banner.setBackgroundColor(0xcc101923);
        heading=new TextView(this);
        heading.setText((title==null||title.isEmpty())?"AuroraTV":title);
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(20);
        heading.setTypeface(null,Typeface.BOLD);
        heading.setSingleLine(true);
        banner.addView(heading);
        status=new TextView(this);
        status.setTextColor(0xff5de6d0);
        status.setTextSize(13);
        status.setText("Connecting to IPTV provider…");
        banner.addView(status);
        FrameLayout.LayoutParams bannerParams=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);
        root.addView(banner,bannerParams);

        LinearLayout bottom=new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(18,10,18,20);
        bottom.setBackgroundColor(0xdd0c1721);
        progress=new SeekBar(this);
        progress.setEnabled(!live);
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int p,boolean fromUser){}
            public void onStartTrackingTouch(SeekBar bar){showControls();}
            public void onStopTrackingTouch(SeekBar bar){seekTo(bar.getProgress());}
        });
        bottom.addView(progress,new LinearLayout.LayoutParams(-1,39));
        controls=new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        bottom.addView(controls,new LinearLayout.LayoutParams(-1,60));

        Button back=button("◀ Back",this::finish);
        pauseButton=button("Pause / Play",this::togglePlayback);
        button("-10s",()->seekRelative(-10_000));
        button("+10s",()->seekRelative(10_000));
        button("Aspect",this::nextAspect);
        button("Audio",()->selectNextTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO));
        button("Subtitles",()->selectNextTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE));
        button("Retry",()->openSource(sourceIndex));
        button("External",this::openExternal);
        if(Build.VERSION.SDK_INT>=26&&live){
            button("PiP",()->{
                try{enterPictureInPictureMode(new PictureInPictureParams.Builder().build());}
                catch(Exception ignored){message("PiP not supported by this Fire TV.");}
            });
        }
        FrameLayout.LayoutParams bottomParams=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);
        root.addView(bottom,bottomParams);
        setContentView(root);
        controls.setVisibility(View.VISIBLE);
        back.requestFocus();
        scheduleHide();
    }
    private void showControls(){
        if(controls==null)return;
        controls.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideControls);
        scheduleHide();
    }
    private void scheduleHide(){main.postDelayed(hideControls,12_000);}
    private void message(String text) { if(status!=null)status.setText(text); }
    @Override public void surfaceCreated(SurfaceHolder holder){
        if(!urls.isEmpty())openSource(sourceIndex);
    }
    @Override public void surfaceChanged(SurfaceHolder holder,int format,int w,int h){}
    @Override public void surfaceDestroyed(SurfaceHolder holder){releasePlayer();}
    private void releasePlayer(){
        activeToken++;
        if(player==null)return;
        MediaPlayer old=player;player=null;prepared=false;
        try{old.setOnErrorListener(null);old.setOnPreparedListener(null);old.reset();}
        catch(Exception ignored){}
        try{old.release();}catch(Exception ignored){}
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void openSource(int index){
        if(index<0||index>=urls.size()){message("No more provider stream formats.");return;}
        sourceIndex=index;
        releasePlayer();
        final long token=activeToken;
        changing=true;
        audioTrack=-1;subtitleTrack=-1;
        message("Opening provider format "+(index+1)+"/"+urls.size()+"…");
        MediaPlayer mp=new MediaPlayer();
        player=mp;
        try{
            mp.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);
            mp.setDisplay(surface.getHolder());
            Map<String,String> headers=new HashMap<>();
            headers.put("User-Agent","VLC/3.0.20 LibVLC/3.0.20");
            headers.put("Accept","*/*");
            mp.setDataSource(this,Uri.parse(urls.get(index)),headers);
            mp.setOnVideoSizeChangedListener((p,w,h)->{
                videoWidth=w;videoHeight=h;applyAspect();
            });
            mp.setOnPreparedListener(p->{
                if(token!=activeToken)return;
                prepared=true;changing=false;
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                try{p.start();}catch(Exception e){fail("Playback start failed");return;}
                message(live?"LIVE • Playing via Android MediaPlayer":
                    "On demand • Playing via Android MediaPlayer");
                scheduleHide();
            });
            mp.setOnCompletionListener(p->{
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                showControls();message("Playback finished");
            });
            mp.setOnErrorListener((p,what,extra)->{
                if(token!=activeToken)return true;
                fail("Decoder/source error "+what+" / "+extra);
                return true;
            });
            mp.setOnInfoListener((p,what,extra)->{
                if(what==MediaPlayer.MEDIA_INFO_BUFFERING_START)message("Buffering…");
                if(what==MediaPlayer.MEDIA_INFO_BUFFERING_END)message("Playing");
                return false;
            });
            mp.prepareAsync();
            main.postDelayed(()->{
                if(token==activeToken&&!prepared)fail("Stream connection timed out.");
            },18_000);
        }catch(Exception e){fail("Cannot open provider stream ("+e.getClass().getSimpleName()+")");}
    }
    private void fail(String reason){
        if(changing)return;
        changing=true;
        if(sourceIndex+1<urls.size()){
            message(reason+" · Trying another provider format…");
            int next=sourceIndex+1;
            main.postDelayed(()->openSource(next),900);
        }else{
            releasePlayer();
            message(reason+" · Hardware codec may not support this video. Try External.");
            showControls();
        }
    }
    private void togglePlayback(){
        if(player==null||!prepared)return;
        try{
            if(player.isPlaying()){
                player.pause();
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                message("Paused");
            }else{
                player.start();
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                message("Playing");
            }
        }catch(Exception ignored){}
    }
    private void seekTo(int millis){
        if(!prepared||player==null||live)return;
        try{
            int max=player.getDuration();
            if(max<=0)return;
            player.seekTo(Math.max(0,Math.min(max,millis)));
        }catch(Exception ignored){}
    }
    private void seekRelative(int millis){
        if(!prepared||player==null)return;
        try{seekTo(player.getCurrentPosition()+millis);}catch(Exception ignored){}
    }
    private void nextAspect(){aspectMode=(aspectMode+1)%3;applyAspect();}
    private void applyAspect(){
        if(surface==null||root==null||videoHeight<=0||videoWidth<=0)return;
        Point screen=new Point();getWindowManager().getDefaultDisplay().getSize(screen);
        int w=screen.x,h=screen.y;
        if(aspectMode!=2){
            float videoRatio=(float)videoWidth/videoHeight;
            float screenRatio=(float)w/h;
            if((aspectMode==0&&videoRatio>screenRatio) ||
               (aspectMode==1&&videoRatio<screenRatio))h=Math.round(w/videoRatio);
            else w=Math.round(h*videoRatio);
        }
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(w,h,Gravity.CENTER);
        surface.setLayoutParams(lp);
        message(aspectMode==0?"Fit":aspectMode==1?"Fill / crop":"Stretch");
    }
    private void selectNextTrack(int kind){
        if(!prepared||player==null)return;
        try{
            MediaPlayer.TrackInfo[] tracks=player.getTrackInfo();
            ArrayList<Integer> choices=new ArrayList<>();
            for(int i=0;i<tracks.length;i++){
                if(tracks[i].getTrackType()==kind)choices.add(i);
            }
            if(choices.isEmpty()){message("No alternate track available");return;}
            int old=kind==MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO?audioTrack:subtitleTrack;
            int next=choices.get((choices.indexOf(old)+1)%choices.size());
            player.selectTrack(next);
            if(kind==MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO)audioTrack=next;
            else subtitleTrack=next;
            message((kind==MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO?"Audio":"Subtitle")+
                " track "+((choices.indexOf(next))+1)+"/"+choices.size());
        }catch(Exception e){message("Track switching unavailable for this stream");}
    }
    private void openExternal(){
        try{
            Intent intent=new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.parse(urls.get(sourceIndex)),"video/*");
            startActivity(intent);
        }catch(ActivityNotFoundException e){
            message("Install VLC for Android TV to use an external software decoder.");
        }catch(Exception e){message("External player launch failed");}
    }
    @Override public boolean onKeyDown(int code,KeyEvent event){
        switch(code){
            case KeyEvent.KEYCODE_BACK: finish();return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: togglePlayback();return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if(controls.getVisibility()!=View.VISIBLE) {
                    seekRelative(-10_000);showControls();return true;
                }break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if(controls.getVisibility()!=View.VISIBLE) {
                    seekRelative(10_000);showControls();return true;
                }break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if(controls.getVisibility()!=View.VISIBLE){
                    showControls();togglePlayback();return true;
                }break;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if(controls.getVisibility()!=View.VISIBLE){
                    showControls();pauseButton.requestFocus();return true;
                }break;
        }
        showControls();
        return super.onKeyDown(code,event);
    }
    @Override protected void onPause(){
        super.onPause();
        if(player!=null&&prepared){
            try{if(player.isPlaying())player.pause();}catch(Exception ignored){}
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    @Override protected void onDestroy(){
        main.removeCallbacksAndMessages(null);
        releasePlayer();
        super.onDestroy();
    }
}
