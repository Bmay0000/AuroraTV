package tv.aurora.player;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.MimeTypes;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

/**
 * Fire TV / Android TV fullscreen playback. The surface ALWAYS fills the
 * display. Remote-select opens an overlay that hides after five seconds.
 * Playback does not depend on the overlay visibility.
 */
public final class PlaybackScreen {
    public interface Exit { void goBack(); }
    private static final long CONTROLS_TIMEOUT_MS = 5000;
    private static final long HEALTH_INTERVAL_MS = 20000;
    private final Activity activity;
    private final PlaybackDiagnostics diagnostics;
    private final String type;
    private final LibraryCore.Item media;
    private final SharedPreferences settings;
    private final boolean lowRam;
    private String sourceUrl;
    private boolean isHls;
    private String bufferProfile;
    private int priorState=Player.STATE_IDLE;
    private long bufferStartMs;
    private int rebufferCount;
    private final Exit exit;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final FrameLayout root;
    private final LinearLayout controlsOverlay;
    private final PlayerView view;
    private ExoPlayer player;
    private TextView state;
    private Button playPause;
    private Button optionsButton;
    private View primaryButton;
    private boolean closed;
    private boolean overlayVisible;
    private long resumeAt;

    private final Runnable hideControls = () -> setControlsVisible(false);
    private final Runnable sampleHealth = new Runnable() {
        @Override public void run() {
            if (closed || player == null) return;
            try {
                Runtime runtime = Runtime.getRuntime();
                diagnostics.heartbeat(player.getCurrentPosition(), player.getBufferedPosition(),
                        player.getPlaybackState(),
                        (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024),
                        runtime.maxMemory() / (1024 * 1024));
            } catch (Exception ignored) {}
            handler.postDelayed(this, HEALTH_INTERVAL_MS);
        }
    };

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density);
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private TextView label(String text, int size, int color) {
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    private Button control(String text, View.OnClickListener onClick) {
        Button b = new Button(activity);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextSize(17);
        b.setTextColor(Color.WHITE);
        b.setBackground(shape(0xd91b344a, 12));
        b.setPadding(dp(10),0,dp(10),0);
        b.setOnClickListener(v -> {
            handler.removeCallbacks(hideControls);
            onClick.onClick(v);
            if (!closed && overlayVisible) scheduleHide();
        });
        b.setOnFocusChangeListener((v, focused) -> {
            b.setBackground(shape(focused ? 0xff4ddbc4 : 0xd91b344a,12));
            b.setTextColor(focused ? 0xff071421 : Color.WHITE);
            if (focused) scheduleHide();
        });
        return b;
    }

    public PlaybackScreen(Activity activity, LibraryCore.Item media,
                          long resumeAt, PlaybackDiagnostics diagnostics, Exit exit) {
        this.activity = activity;
        this.diagnostics = diagnostics;
        this.type = media.type;
        this.media = media;
        this.exit = exit;
        this.resumeAt = Math.max(0, resumeAt);
        this.settings = activity.getSharedPreferences("playback_options",Context.MODE_PRIVATE);
        ActivityManager manager=(ActivityManager)activity.getSystemService(Context.ACTIVITY_SERVICE);
        this.lowRam=manager!=null&&(manager.isLowRamDevice()||manager.getMemoryClass()<=160);

        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        activity.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        root = new FrameLayout(activity);
        root.setBackgroundColor(Color.BLACK);
        root.setFocusableInTouchMode(true);
        view = new PlayerView(activity);
        view.setUseController(false); // No permanent Media3 controller occupying the display
        view.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        view.setKeepContentOnPlayerReset(true);
        root.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        controlsOverlay = new LinearLayout(activity);
        controlsOverlay.setOrientation(LinearLayout.VERTICAL);
        controlsOverlay.setPadding(dp(20),dp(14),dp(20),dp(20));
        controlsOverlay.setBackground(shape(0xee071624, 12));
        FrameLayout.LayoutParams overlayBounds = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        overlayBounds.setMargins(dp(12),0,dp(12),dp(12));
        root.addView(controlsOverlay, overlayBounds);

        TextView title = label(media.name, 21, Color.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        title.setMaxLines(1);
        controlsOverlay.addView(title);
        state = label("Connecting to stream…", 14, 0xffa7c7d3);
        LinearLayout.LayoutParams messageBounds =
                new LinearLayout.LayoutParams(-1, dp(26));
        controlsOverlay.addView(state,messageBounds);
        LinearLayout buttons = new LinearLayout(activity);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        controlsOverlay.addView(buttons);

        playPause = control("Pause",v -> {
            if (player == null) return;
            if (player.isPlaying()) player.pause(); else player.play();
            updatePlaybackState();
        });
        primaryButton = playPause;
        buttons.addView(playPause, new LinearLayout.LayoutParams(0,dp(56),1));

        if ("live".equals(type)) {
            Button live = control("Go Live",v -> {
                if (player == null) return;
                if (player.isCurrentMediaItemLive()) {
                    player.seekToDefaultPosition();
                    player.play();
                } else Toast.makeText(activity,
                        "This stream has no rewindable live timeline",Toast.LENGTH_SHORT).show();
            });
            buttons.addView(live,new LinearLayout.LayoutParams(0,dp(56),1));
        } else {
            Button seek = control("−30s",v -> {
                if (player != null)
                    player.seekTo(Math.max(0,player.getCurrentPosition()-30000));
            });
            buttons.addView(seek,new LinearLayout.LayoutParams(0,dp(56),1));
        }

        optionsButton=control("Playback Settings",v -> showSettings());
        buttons.addView(optionsButton,new LinearLayout.LayoutParams(0,dp(56),1));
        Button back = control("Back to Library",v -> exit.goBack());
        buttons.addView(back,new LinearLayout.LayoutParams(0,dp(56),1));
        setControlsVisible(false);
        activity.setContentView(root);
        root.requestFocus();

        startPlayer(this.resumeAt);
    }

    private void startPlayer(long resumePosition) {
        if (closed) return;
        bufferProfile=PlaybackTuning.profile(settings.getString("buffer","stable"));
        String preferred=PlaybackTuning.format(settings.getString("live_format","original"));
        sourceUrl=PlaybackTuning.resolveUrl(media.url,media.type,preferred);
        isHls=PlaybackTuning.isHls(sourceUrl);
        PlaybackTuning.Buffer config=PlaybackTuning.buffer(bufferProfile,
                "live".equals(type),isHls,lowRam);
        try {
            // Keep memory use proportional to Fire TV's RAM class while
            // allowing substantially more buffering than the old 12s/3s.
            DefaultLoadControl loadControl=new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(config.minMs,config.maxMs,
                            config.startMs,config.afterRebufferMs)
                    .setTargetBufferBytes(config.maxBytes)
                    .setBackBuffer(0,false)
                    .build();
            DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(15000)
                    .setReadTimeoutMs(20000)
                    .setAllowCrossProtocolRedirects(true);
            DefaultDataSource.Factory data=new DefaultDataSource.Factory(activity,http);
            DefaultMediaSourceFactory factory=new DefaultMediaSourceFactory(data)
                    .setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(6));
            DefaultRenderersFactory renderers=new DefaultRenderersFactory(activity)
                    .setEnableDecoderFallback(true);
            player=new ExoPlayer.Builder(activity,renderers)
                    .setLoadControl(loadControl)
                    .setMediaSourceFactory(factory)
                    .build();
            player.setWakeMode(C.WAKE_MODE_NETWORK);
            player.setHandleAudioBecomingNoisy(true);
            view.setPlayer(player);
            priorState=Player.STATE_IDLE;
            bufferStartMs=0;
            rebufferCount=0;

            player.addListener(new Player.Listener(){
                @Override public void onPlaybackStateChanged(int playbackState){
                    if(closed || player==null)return;
                    if(playbackState==Player.STATE_BUFFERING &&
                        priorState==Player.STATE_READY && player.getPlayWhenReady()){
                        rebufferCount++;
                        bufferStartMs=SystemClock.elapsedRealtime();
                        diagnostics.event("Rebuffer #"+rebufferCount+"; buffered ahead "+
                            Math.max(0,player.getBufferedPosition()-player.getCurrentPosition())+" ms");
                    }else if(playbackState==Player.STATE_READY && bufferStartMs>0){
                        long duration=SystemClock.elapsedRealtime()-bufferStartMs;
                        diagnostics.event("Rebuffer ended after "+duration+" ms");
                        bufferStartMs=0;
                    }
                    priorState=playbackState;
                    diagnostics.event("Player state="+playbackState);
                    updatePlaybackState();
                }
                @Override public void onIsPlayingChanged(boolean playing){
                    if(!closed)updatePlaybackState();
                }
                @Override public void onPositionDiscontinuity(
                        Player.PositionInfo oldPosition,Player.PositionInfo newPosition,int reason){
                    if(player==null || closed)return;
                    long delta=newPosition.positionMs-oldPosition.positionMs;
                    if(Math.abs(delta)>=500 &&
                       reason!=Player.DISCONTINUITY_REASON_SEEK){
                        diagnostics.event("Position discontinuity reason="+reason+
                                " deltaMs="+delta+"; bufferAheadMs="+
                                Math.max(0,player.getBufferedPosition()-player.getCurrentPosition()));
                    }
                }
                @Override public void onPlaybackParametersChanged(
                        androidx.media3.common.PlaybackParameters parameters){
                    if(Math.abs(parameters.speed-1f)>0.005f)
                        diagnostics.event("Playback speed adjustment="+parameters.speed);
                }
                @Override public void onPlayerError(PlaybackException error){
                    if(closed)return;
                    String cause=error.getCause()==null?"unknown":
                            error.getCause().getClass().getSimpleName();
                    diagnostics.event("Playback error: "+error.getErrorCodeName()+
                            " cause="+cause+" rebuffers="+rebufferCount);
                    state.setText("Playback interrupted ("+error.getErrorCodeName()+
                            "). Select Retry.");
                    Toast.makeText(activity,"Playback stopped. Select Retry.",
                            Toast.LENGTH_LONG).show();
                    showControls();
                }
            });
            diagnostics.start(type);
            diagnostics.event("Stream="+(isHls?"HLS":"progressive")+
                    " profile="+bufferProfile+(lowRam?" lowRAM":"")+
                    " targetMinMs="+config.minMs+" maxMs="+config.maxMs+
                    " afterRebufferMs="+config.afterRebufferMs);
            MediaItem.Builder mediaItem=new MediaItem.Builder().setUri(sourceUrl);
            if(isHls) mediaItem.setMimeType(MimeTypes.APPLICATION_M3U8);
            player.setMediaItem(mediaItem.build());
            player.prepare();
            if(!"live".equals(type) && resumePosition>0)player.seekTo(resumePosition);
            player.play();
            handler.removeCallbacks(sampleHealth);
            handler.postDelayed(sampleHealth,HEALTH_INTERVAL_MS);
        }catch(Exception error){
            diagnostics.event("Player setup error: "+error.getClass().getSimpleName());
            Toast.makeText(activity,"Could not initialize playback; open Settings.",
                    Toast.LENGTH_LONG).show();
            showControls();
        }
    }

    private void restartPlayer(){
        if(closed)return;
        long position=resumeAt;
        if(player!=null){
            position="live".equals(type)?0:Math.max(0,player.getCurrentPosition());
            view.setPlayer(null);
            try{player.stop();player.release();}
            catch(Exception e){diagnostics.event("Restart release="+e.getClass().getSimpleName());}
            player=null;
        }
        diagnostics.event("Player restarting after settings change");
        state.setText("Applying playback settings…");
        startPlayer(position);
        scheduleHide();
    }

    private void showSettings(){
        String[] options={
                "Buffering: "+labelBuffer(),
                "Live stream format: "+labelFormat(),
                "Restart this stream",
                "Why does Live TV buffer?"
        };
        new AlertDialog.Builder(activity).setTitle("AuroraTV · Playback Settings")
          .setItems(options,(dialog,index)->{
              if(index==0){chooseBuffering();return;}
              if(index==1){chooseFormat();return;}
              if(index==2){restartPlayer();return;}
              new AlertDialog.Builder(activity).setTitle("Playback buffering")
                 .setMessage("Stable mode builds a bigger buffer to help smooth out uneven IPTV streams. "
                   +"Original keeps your provider's stream type. Some Xtream providers offer both MPEG-TS and HLS. "
                   +"Try HLS if live TS frequently stalls or repeats. Stream formats only switch when the provider "
                   +"uses the standard Xtream /live/ endpoint. Playback errors and buffering counts are available "
                   +"in Connect / Refresh → Playback diagnostics.")
                 .setPositiveButton("OK",null).show();
          }).show();
    }

    private String labelBuffer(){
        switch(PlaybackTuning.profile(settings.getString("buffer","stable"))){
            case "fast": return "Low latency";
            case "balanced": return "Balanced";
            default: return "Stable (recommended)";
        }
    }
    private String labelFormat(){
        switch(PlaybackTuning.format(settings.getString("live_format","original"))){
            case "hls": return "HLS (.m3u8)";
            case "ts": return "MPEG-TS (.ts)";
            default: return "Provider original";
        }
    }
    private void chooseBuffering(){
        final String[] labels={
                "Stable – fewer stalls; slightly slower start",
                "Balanced – moderate buffer",
                "Low latency – starts sooner, may buffer more"
        };
        final String[] values={"stable","balanced","fast"};
        int selection=0;
        String current=PlaybackTuning.profile(settings.getString("buffer","stable"));
        for(int i=0;i<values.length;i++)if(values[i].equals(current))selection=i;
        new AlertDialog.Builder(activity).setTitle("Choose buffering profile")
             .setSingleChoiceItems(labels,selection,(dialog,index)->{
                 settings.edit().putString("buffer",values[index]).apply();
                 dialog.dismiss();
                 restartPlayer();
             }).setNegativeButton("CANCEL",null).show();
    }
    private void chooseFormat(){
        if(!"live".equals(type)){
            Toast.makeText(activity,"Stream format switching is for Live TV.",
                    Toast.LENGTH_SHORT).show();return;
        }
        String[] labels={"Provider original (recommended)",
                "HLS (.m3u8) – try for choppy MPEG-TS",
                "MPEG-TS (.ts) – continuous transport stream"};
        String[] values={"original","hls","ts"};
        int selection=0;
        String current=PlaybackTuning.format(settings.getString("live_format","original"));
        for(int i=0;i<values.length;i++)if(values[i].equals(current))selection=i;
        new AlertDialog.Builder(activity).setTitle("Live streaming format")
          .setMessage("Not every provider supports both formats. If HLS fails, switch back to Original.")
          .setSingleChoiceItems(labels,selection,(dialog,index)->{
              String selected=values[index];
              String testUrl=PlaybackTuning.resolveUrl(media.url,type,selected);
              boolean canSwitch=!PlaybackTuning.resolveUrl(media.url,type,"hls").equals(media.url)
                      || !PlaybackTuning.resolveUrl(media.url,type,"ts").equals(media.url);
              if(!selected.equals("original") && !canSwitch){
                  Toast.makeText(activity,
                    "This playlist uses a custom URL. Format cannot be switched safely.",
                    Toast.LENGTH_LONG).show();
              }else{
                  settings.edit().putString("live_format",selected).apply();
                  restartPlayer();
              }
              dialog.dismiss();
          }).setNegativeButton("CANCEL",null).show();
    }

    private void updatePlaybackState() {
        if (player == null || closed) return;
        if (player.getPlayerError() != null) {
            playPause.setText("Retry Playback");
            playPause.setOnClickListener(v -> retry());
        } else {
            playPause.setText(player.isPlaying()?"Pause":"Play");
            playPause.setOnClickListener(v -> {
                if (player == null) return;
                if (player.isPlaying()) player.pause(); else player.play();
                scheduleHide();
            });
        }
        if (player.getPlayerError() != null) return;
        switch (player.getPlaybackState()) {
            case Player.STATE_BUFFERING: state.setText("Buffering…"); break;
            case Player.STATE_READY: state.setText(player.isPlaying()?"Playing":"Paused"); break;
            case Player.STATE_ENDED: state.setText("Playback ended"); break;
            default: state.setText("Connecting…");
        }
    }

    private void retry() {
        if (closed || player == null) return;
        diagnostics.event("Manual playback retry");
        player.prepare();
        if ("live".equals(type)) player.seekToDefaultPosition();
        player.play();
        updatePlaybackState();
        scheduleHide();
    }

    private void scheduleHide() {
        handler.removeCallbacks(hideControls);
        if (!closed && overlayVisible) handler.postDelayed(hideControls,CONTROLS_TIMEOUT_MS);
    }

    private void setControlsVisible(boolean shown) {
        if (closed) return;
        overlayVisible = shown;
        controlsOverlay.setVisibility(shown ? View.VISIBLE : View.GONE);
        if (shown) {
            primaryButton.requestFocus();
            scheduleHide();
        } else {
            handler.removeCallbacks(hideControls);
            root.requestFocus();
        }
    }

    public void showControls() { setControlsVisible(true); }
    public void hideControls() { setControlsVisible(false); }
    public boolean controlsVisible() { return overlayVisible; }

    public boolean handleKey(KeyEvent event) {
        if (closed || player == null) return false;
        int key = event.getKeyCode();
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return key == KeyEvent.KEYCODE_MENU ||
                   key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                   (!overlayVisible && isNavigationKey(key));
        }
        if (key == KeyEvent.KEYCODE_MENU) {
            setControlsVisible(!overlayVisible);
            return true;
        }
        if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
            key == KeyEvent.KEYCODE_MEDIA_PLAY ||
            key == KeyEvent.KEYCODE_MEDIA_PAUSE) {
            if (key == KeyEvent.KEYCODE_MEDIA_PLAY) player.play();
            else if (key == KeyEvent.KEYCODE_MEDIA_PAUSE) player.pause();
            else if (player.isPlaying()) player.pause(); else player.play();
            return true;
        }
        if (isNavigationKey(key)) {
            if (!overlayVisible) {
                showControls();
                return true;
            }
            scheduleHide();
            return false; // Let buttons respond to DPAD navigation/Select.
        }
        return false;
    }

    private boolean isNavigationKey(int key) {
        return key == KeyEvent.KEYCODE_DPAD_CENTER ||
               key == KeyEvent.KEYCODE_ENTER ||
               key == KeyEvent.KEYCODE_NUMPAD_ENTER ||
               key == KeyEvent.KEYCODE_DPAD_LEFT ||
               key == KeyEvent.KEYCODE_DPAD_RIGHT ||
               key == KeyEvent.KEYCODE_DPAD_UP ||
               key == KeyEvent.KEYCODE_DPAD_DOWN;
    }

    public long close() {
        if (closed) return 0;
        closed=true;
        handler.removeCallbacks(hideControls);
        handler.removeCallbacks(sampleHealth);
        long position = 0;
        try {
            if (player != null) {
                position = Math.max(0,player.getCurrentPosition());
                view.setPlayer(null);
                player.stop();
                player.release();
            }
        } catch (Exception ex) {
            diagnostics.event("Player release error: " + ex.getClass().getSimpleName());
        } finally {
            player = null;
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            diagnostics.stop();
        }
        return position;
    }
}
