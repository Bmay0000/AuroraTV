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
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.content.res.ColorStateList;
import android.text.TextUtils;

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
    private final PlaybackRecoveryPolicy recovery = new PlaybackRecoveryPolicy();
    private boolean sessionStarted;
    private boolean recoveryExhausted;
    private String temporaryFormat = PlaybackTuning.FORMAT_ORIGINAL;
    private boolean manualPause;
    private long lastPlaybackPosition;
    private final Exit exit;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final FrameLayout root;
    private final LinearLayout controlsOverlay;
    private final PlayerView view;
    private ExoPlayer player;
    private TextView state;
    private Button playPause;
    private Button optionsButton;
    private TextView centerStatus;
    private LinearLayout centerBanner;
    private ProgressBar loadingIndicator;
    private SeekBar playbackSeekbar;
    private TextView clockText;
    private boolean scrubbing;
    private View primaryButton;
    private boolean closed;
    private boolean overlayVisible;
    private long resumeAt;

    private final Runnable updateClock=new Runnable(){
        @Override public void run(){
            if(closed)return;
            if(player!=null && clockText!=null && !"live".equals(type)){
                long duration=player.getDuration();
                long position=Math.max(0,player.getCurrentPosition());
                if(duration>0 && duration!=C.TIME_UNSET){
                    clockText.setText(time(position)+"  /  "+time(duration));
                    if(playbackSeekbar!=null && !scrubbing){
                        int progress=(int)(1000d*Math.min(1d,position/(double)duration));
                        playbackSeekbar.setProgress(progress);
                    }
                }else{
                    clockText.setText(time(position)+"  /  —");
                }
            }
            handler.postDelayed(this,1000L);
        }
    };
    private static String time(long ms){
        long seconds=Math.max(0,ms/1000),hours=seconds/3600;
        if(hours>0)return String.format(java.util.Locale.US,"%d:%02d:%02d",
            hours,(seconds%3600)/60,seconds%60);
        return String.format(java.util.Locale.US,"%02d:%02d",seconds/60,seconds%60);
    }
    private final Runnable hideControls = () -> setControlsVisible(false);
    private static final long WATCHDOG_INTERVAL_MS=2500L;
    private final Runnable watchdog = new Runnable(){
        @Override public void run(){
            if(closed || player==null || recoveryExhausted)return;
            try{
                PlaybackRecoveryPolicy.Action action=recovery.sample(
                    SystemClock.elapsedRealtime(),player.getPlaybackState(),
                    player.getPlayWhenReady() && !manualPause,player.isPlaying(),
                    player.getCurrentPosition());
                respondToRecovery(action,"watchdog");
            }catch(Exception ex){diagnostics.event("Watchdog: "+ex.getClass().getSimpleName());}
            if(!closed && !recoveryExhausted)handler.postDelayed(this,WATCHDOG_INTERVAL_MS);
        }
    };
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
        view.setUseController(false);
        view.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        // AuroraTV owns the buffering UI so a failed source never leaves
        // Media3's spinner displayed indefinitely.
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER);
        view.setKeepContentOnPlayerReset(true);
        root.addView(view,new FrameLayout.LayoutParams(-1,-1));

        centerBanner=new LinearLayout(activity);
        centerBanner.setOrientation(LinearLayout.VERTICAL);
        centerBanner.setGravity(Gravity.CENTER);
        centerBanner.setPadding(dp(28),dp(17),dp(28),dp(17));
        centerBanner.setBackground(shape(0xe9152535,15));
        FrameLayout.LayoutParams centerBounds=new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER);
        centerBounds.setMargins(dp(14),0,dp(14),0);
        root.addView(centerBanner,centerBounds);
        loadingIndicator=new ProgressBar(activity);
        loadingIndicator.setIndeterminateTintList(ColorStateList.valueOf(0xff5debd0));
        LinearLayout.LayoutParams spinnerLoc=new LinearLayout.LayoutParams(dp(34),dp(34));
        spinnerLoc.gravity=Gravity.CENTER_HORIZONTAL;centerBanner.addView(loadingIndicator,spinnerLoc);
        centerStatus=label("Connecting to your stream…",17,Color.WHITE);
        centerStatus.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusSpace=new LinearLayout.LayoutParams(-2,-2);
        statusSpace.topMargin=dp(9);
        centerBanner.addView(centerStatus,statusSpace);

        controlsOverlay = new LinearLayout(activity);
        controlsOverlay.setOrientation(LinearLayout.VERTICAL);
        controlsOverlay.setPadding(dp(35),dp(45),dp(35),dp(22));
        GradientDrawable glass=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0x05070d18,0xc00a1525,0xf7061020});
        controlsOverlay.setBackground(glass);
        FrameLayout.LayoutParams overlayBounds = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        root.addView(controlsOverlay, overlayBounds);

        TextView label=label("AuroraTV   •   "+("live".equals(type)?"LIVE TELEVISION":
            "movie".equals(type)?"MOVIE":"TV SERIES"),13,0xff5debd0);
        label.setLetterSpacing(.13f);label.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        controlsOverlay.addView(label);
        TextView title = label(media.name, TvLayout.clamp(
            (int)(activity.getResources().getDisplayMetrics().widthPixels/
                activity.getResources().getDisplayMetrics().density*.023),20,29),
            Color.WHITE);
        title.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));
        title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);
        controlsOverlay.addView(title);
        state = label("Connecting to stream…",14,0xffb5d0dc);
        LinearLayout.LayoutParams stateBounds =
            new LinearLayout.LayoutParams(-1,dp(27));
        controlsOverlay.addView(state,stateBounds);

        if(!"live".equals(type)){
            LinearLayout timing=new LinearLayout(activity);
            timing.setGravity(Gravity.CENTER_VERTICAL);
            playbackSeekbar=new SeekBar(activity);
            playbackSeekbar.setMax(1000);
            playbackSeekbar.setProgressTintList(ColorStateList.valueOf(0xff5debd0));
            playbackSeekbar.setThumbTintList(ColorStateList.valueOf(0xff5debd0));
            timing.addView(playbackSeekbar,new LinearLayout.LayoutParams(0,dp(40),1));
            clockText=label("00:00  /  —",13,0xffe2f1f6);
            clockText.setGravity(Gravity.CENTER_VERTICAL|Gravity.RIGHT);
            timing.addView(clockText,new LinearLayout.LayoutParams(dp(134),dp(40)));
            controlsOverlay.addView(timing);
            playbackSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
                @Override public void onStartTrackingTouch(SeekBar bar){scrubbing=true;handler.removeCallbacks(hideControls);}
                @Override public void onProgressChanged(SeekBar bar,int progress,boolean user){
                    if(user && player!=null && clockText!=null &&
                       player.getDuration()>0 && player.getDuration()!=C.TIME_UNSET)
                        clockText.setText(time((long)(player.getDuration()*progress/1000d))+
                            "  /  "+time(player.getDuration()));
                }
                @Override public void onStopTrackingTouch(SeekBar bar){
                    scrubbing=false;
                    if(player!=null&&player.isCurrentMediaItemSeekable() &&
                            player.getDuration()>0 && player.getDuration()!=C.TIME_UNSET)
                        player.seekTo((long)(player.getDuration()*bar.getProgress()/1000d));
                    scheduleHide();
                }
            });
        }

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actions=new LinearLayout.LayoutParams(-1,dp(52));
        actions.topMargin=dp(8);controlsOverlay.addView(buttons,actions);
        playPause = control("Ⅱ  PAUSE",v -> togglePlay());
        primaryButton = playPause;
        buttons.addView(playPause,new LinearLayout.LayoutParams(0,-1,1));

        if("live".equals(type)){
            Button live=control("◉  GO LIVE",v ->{
                if(player==null)return;
                manualPause=false;
                if(player.isCurrentMediaItemLive() && player.isCurrentMediaItemSeekable()){
                    player.seekToDefaultPosition();player.play();
                }else retry();
                scheduleHide();
            });
            buttons.addView(live,new LinearLayout.LayoutParams(0,-1,1));
        } else {
            Button rewind=control("↶  −30 SEC",v ->{
                if(player!=null && player.isCurrentMediaItemSeekable())
                    player.seekTo(Math.max(0,player.getCurrentPosition()-30000));
            });
            buttons.addView(rewind,new LinearLayout.LayoutParams(0,-1,1));
        }
        Button reconnect=control("⟳  RECONNECT",v ->retry());
        buttons.addView(reconnect,new LinearLayout.LayoutParams(0,-1,1));
        optionsButton=control("⚙  OPTIONS",v->showSettings());
        buttons.addView(optionsButton,new LinearLayout.LayoutParams(0,-1,1));
        Button back=control("←  EXIT",v->exit.goBack());
        buttons.addView(back,new LinearLayout.LayoutParams(0,-1,1));
        setControlsVisible(false);
        activity.setContentView(root);
        root.requestFocus();
        handler.postDelayed(updateClock,1000L);
        startPlayer(this.resumeAt);
    }

    private void startPlayer(long resumePosition) {
        if (closed) return;
        bufferProfile=PlaybackTuning.profile(settings.getString("buffer","stable"));
        String preferred=PlaybackTuning.format(settings.getString("live_format","original"));
        sourceUrl=PlaybackTuning.resolveUrl(media.url,media.type,
                temporaryFormat.equals(PlaybackTuning.FORMAT_ORIGINAL)?preferred:temporaryFormat);
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
                    .setReadTimeoutMs(12000)
                    .setAllowCrossProtocolRedirects(true);
            DefaultDataSource.Factory data=new DefaultDataSource.Factory(activity,http);
            DefaultMediaSourceFactory factory=new DefaultMediaSourceFactory(data)
                    .setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(2));
            DefaultRenderersFactory renderers=new DefaultRenderersFactory(activity)
                    .setEnableDecoderFallback(true);
            player=new ExoPlayer.Builder(activity,renderers)
                    .setLoadControl(loadControl)
                    .setMediaSourceFactory(factory)
                    .build();
            player.setWakeMode(C.WAKE_MODE_NETWORK);
            player.setHandleAudioBecomingNoisy(true);
            view.setPlayer(player);
            final ExoPlayer currentPlayer=player;
            priorState=Player.STATE_IDLE;
            bufferStartMs=0;
            // Session rebuffer counter remains meaningful across retries.

            player.addListener(new Player.Listener(){
                @Override public void onPlaybackStateChanged(int playbackState){
                    if(closed || player!=currentPlayer)return;
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
                    if(!closed && player==currentPlayer)updatePlaybackState();
                }
                @Override public void onPositionDiscontinuity(
                        Player.PositionInfo oldPosition,Player.PositionInfo newPosition,int reason){
                    if(player!=currentPlayer || closed)return;
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
                    if(player!=currentPlayer || closed)return;
                    if(Math.abs(parameters.speed-1f)>0.005f)
                        diagnostics.event("Playback speed adjustment="+parameters.speed);
                }
                @Override public void onPlayerError(PlaybackException error){
                    if(closed || player!=currentPlayer)return;
                    String cause=error.getCause()==null?"unknown":
                            error.getCause().getClass().getSimpleName();
                    diagnostics.event("Playback error: "+error.getErrorCodeName()+
                            " cause="+cause+" rebuffers="+rebufferCount);
                    state.setText("Connection interrupted · recovering…");
                    handler.post(()->{
                        if(closed || player!=currentPlayer)return;
                        respondToRecovery(recovery.onError(SystemClock.elapsedRealtime()),
                            "player error");
                    });
                }
            });
            if(!sessionStarted){diagnostics.start(type);sessionStarted=true;}
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
            handler.removeCallbacks(watchdog);
            handler.postDelayed(watchdog,WATCHDOG_INTERVAL_MS);
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
        recovery.resetManually();recoveryExhausted=false;manualPause=false;
        temporaryFormat=PlaybackTuning.FORMAT_ORIGINAL;
        state.setText("Applying playback settings…");
        startPlayer(position);
        scheduleHide();
    }

    private void showStreamInfo(){
        long ahead=player==null?0:Math.max(0,
            player.getBufferedPosition()-player.getCurrentPosition());
        String report="Channel: "+media.name+"\n"+
            "Source format: "+(isHls?"HLS":"MPEG-TS / progressive")+"\n"+
            "Buffer profile: "+bufferProfile+"\n"+
            "Buffered ahead: "+(ahead/1000)+" seconds\n"+
            "Rebuffer events: "+rebufferCount+"\n"+
            "Automatic reconnects: "+recovery.attempts()+" / "+
                PlaybackRecoveryPolicy.MAX_ATTEMPTS+"\n\n"+
            "If this stream freezes, choose Reconnect. AuroraTV also retries "+
            "automatically before suggesting another source or format.";
        new AlertDialog.Builder(activity).setTitle("AuroraTV · Stream Health")
          .setMessage(report).setPositiveButton("RECONNECT",(d,n)->retry())
          .setNegativeButton("CLOSE",null).show();
    }

    private void showSettings(){
        String[] options={
                "Buffering: "+labelBuffer(),
                "Live stream format: "+labelFormat(),
                "Restart this stream",
                "Stream Health · current connection",
                "Why does Live TV buffer?"
        };
        new AlertDialog.Builder(activity).setTitle("AuroraTV · Playback Settings")
          .setItems(options,(dialog,index)->{
              if(index==0){chooseBuffering();return;}
              if(index==1){chooseFormat();return;}
              if(index==2){restartPlayer();return;}
              if(index==3){showStreamInfo();return;}
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


    private boolean canTryHls() {
        if(!"live".equals(type) || PlaybackTuning.isHls(media.url))return false;
        String candidate=PlaybackTuning.resolveUrl(media.url,"live","hls");
        return !candidate.equals(media.url);
    }

    /** Never leaves an infinite loading spinner: reconnect, then show actions. */
    private void respondToRecovery(PlaybackRecoveryPolicy.Action action,String reason) {
        if(closed || action==PlaybackRecoveryPolicy.Action.WAIT)return;
        if(action==PlaybackRecoveryPolicy.Action.GIVE_UP) {
            recoveryExhausted=true;
            handler.removeCallbacks(watchdog);
            diagnostics.event("Source unavailable after "+recovery.attempts()+" recoveries");
            if(player!=null){try{player.pause();}catch(Exception ignored){}}
            state.setText("Stream unavailable · choose Retry or another channel");
            playPause.setText("⟳  RETRY");
            playPause.setOnClickListener(v->retry());
            showStatus("Stream stopped responding.\nUse Retry, Options or Exit.",false);
            showControls();
            Toast.makeText(activity,"This channel stopped responding. Use Retry or change format.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        int attempt=recovery.attempts();
        diagnostics.event("Auto-reconnect #"+attempt+" cause="+reason);
        state.setText("Reconnecting  "+attempt+"/"+PlaybackRecoveryPolicy.MAX_ATTEMPTS+"…");
        showStatus("Reconnecting  "+attempt+"/"+PlaybackRecoveryPolicy.MAX_ATTEMPTS+"…",true);
        // On the second connection retry, attempt HLS only for the standard
        // Xtream /live/ path. Never change arbitrary M3U or VOD URLs.
        if(attempt==2 && canTryHls() &&
                PlaybackTuning.format(settings.getString("live_format","original"))
                        .equals(PlaybackTuning.FORMAT_ORIGINAL)){
            temporaryFormat=PlaybackTuning.FORMAT_HLS;
            diagnostics.event("Temporary HLS failover attempted");
        }else if(attempt==3){
            temporaryFormat=PlaybackTuning.FORMAT_ORIGINAL;
        }
        restartConnectionWithoutReset();
    }

    private void restartConnectionWithoutReset() {
        if(closed)return;
        long position=0;
        if(player!=null){
            position="live".equals(type)?0:Math.max(0,player.getCurrentPosition());
            ExoPlayer old=player;
            player=null;
            view.setPlayer(null);
            try{old.stop();old.release();}
            catch(Exception ex){diagnostics.event("Reconnect release "+ex.getClass().getSimpleName());}
        }
        handler.removeCallbacks(watchdog);
        startPlayer(position);
    }
    private void togglePlay(){
        if(player==null)return;
        if(recoveryExhausted || player.getPlayerError()!=null){
            retry();return;
        }
        if(player.getPlayWhenReady()){
            manualPause=true;player.pause();showControls();
        }else{
            manualPause=false;player.play();scheduleHide();
        }
        updatePlaybackState();
    }
    private void showStatus(String message,boolean spinner){
        if(centerBanner==null)return;
        centerStatus.setText(message);
        loadingIndicator.setVisibility(spinner?View.VISIBLE:View.GONE);
        centerBanner.setVisibility(View.VISIBLE);
    }
    private void updatePlaybackState(){
        if(player==null || closed)return;
        if(recoveryExhausted || player.getPlayerError()!=null){
            playPause.setText("⟳  RETRY");
            playPause.setOnClickListener(v->retry());
            showStatus("This stream is unavailable.\nUse Retry, Options or Exit.",false);
            return;
        }
        playPause.setText(player.getPlayWhenReady()?"Ⅱ  PAUSE":"▶  PLAY");
        playPause.setOnClickListener(v->togglePlay());
        switch(player.getPlaybackState()){
            case Player.STATE_BUFFERING:
                state.setText("Buffering · AuroraTV will reconnect if needed");
                if(player.getPlayWhenReady())
                    showStatus(recovery.attempts()>0?
                        "Reconnecting stream  "+recovery.attempts()+"/"+
                            PlaybackRecoveryPolicy.MAX_ATTEMPTS+"…":
                        "Loading stream…",true);
                else centerBanner.setVisibility(View.GONE);
                break;
            case Player.STATE_READY:
                state.setText(player.isPlaying()?"●  PLAYING":"PAUSED");
                centerBanner.setVisibility(View.GONE);
                break;
            case Player.STATE_ENDED:
                state.setText("Playback complete");
                centerBanner.setVisibility(View.GONE);
                showControls();
                break;
            default:
                state.setText("Connecting to provider…");
                if(player.getPlayWhenReady())
                    showStatus("Connecting to stream…",true);
                break;
        }
    }

    private void retry() {
        if (closed) return;
        diagnostics.event("Manual playback retry");
        recovery.resetManually();recoveryExhausted=false;manualPause=false;
        temporaryFormat=PlaybackTuning.FORMAT_ORIGINAL;
        restartPlayer();
    }

    private void scheduleHide() {
        handler.removeCallbacks(hideControls);
        if (!closed && overlayVisible && !recoveryExhausted) handler.postDelayed(hideControls,CONTROLS_TIMEOUT_MS);
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

    public boolean handleKey(KeyEvent event){
        if(closed || player==null)return false;
        int key=event.getKeyCode();
        if(event.getAction()!=KeyEvent.ACTION_DOWN){
            return key==KeyEvent.KEYCODE_MENU||
                   key==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE||
                   key==KeyEvent.KEYCODE_MEDIA_PLAY||
                   key==KeyEvent.KEYCODE_MEDIA_PAUSE||
                   (!overlayVisible && isNavigationKey(key));
        }
        if(key==KeyEvent.KEYCODE_MENU){
            setControlsVisible(!overlayVisible);return true;
        }
        if(key==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE){
            togglePlay();return true;
        }
        if(key==KeyEvent.KEYCODE_MEDIA_PLAY){
            manualPause=false;player.play();updatePlaybackState();return true;
        }
        if(key==KeyEvent.KEYCODE_MEDIA_PAUSE){
            manualPause=true;player.pause();showControls();return true;
        }
        if(!overlayVisible && !"live".equals(type) && player.isCurrentMediaItemSeekable() &&
           (key==KeyEvent.KEYCODE_DPAD_LEFT || key==KeyEvent.KEYCODE_DPAD_RIGHT)){
            long offset=key==KeyEvent.KEYCODE_DPAD_RIGHT?10000L:-10000L;
            long target=Math.max(0,player.getCurrentPosition()+offset);
            long duration=player.getDuration();
            if(duration>0 && duration!=C.TIME_UNSET)target=Math.min(duration,target);
            player.seekTo(target);showControls();return true;
        }
        if(isNavigationKey(key)){
            if(!overlayVisible){showControls();return true;}
            scheduleHide();return false;
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
        handler.removeCallbacks(watchdog);
        handler.removeCallbacks(updateClock);
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
            if(sessionStarted)diagnostics.stop();
        }
        return position;
    }
}
