package tv.aurora.player;

import android.app.Activity;
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
    private final Exit exit;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final FrameLayout root;
    private final LinearLayout controlsOverlay;
    private final PlayerView view;
    private ExoPlayer player;
    private TextView state;
    private Button playPause;
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
        this.exit = exit;
        this.resumeAt = Math.max(0, resumeAt);

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

        Button back = control("Back to Library",v -> exit.goBack());
        buttons.addView(back,new LinearLayout.LayoutParams(0,dp(56),1));
        setControlsVisible(false);
        activity.setContentView(root);
        root.requestFocus();

        try {
            // Fire TV decoders vary; try a compatible decoder before failing.
            DefaultRenderersFactory renderers =
                    new DefaultRenderersFactory(activity).setEnableDecoderFallback(true);
            DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(12000,40000,1500,3000).build();
            player = new ExoPlayer.Builder(activity,renderers)
                    .setLoadControl(loadControl).build();
            player.setWakeMode(C.WAKE_MODE_NETWORK);
            player.setHandleAudioBecomingNoisy(true);
            view.setPlayer(player);

            player.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int playbackState) {
                    if (closed) return;
                    diagnostics.event("Player state=" + playbackState);
                    updatePlaybackState();
                }
                @Override public void onIsPlayingChanged(boolean isPlaying) {
                    if (!closed) updatePlaybackState();
                }
                @Override public void onPlayerError(PlaybackException error) {
                    if (closed) return;
                    String cause = error.getCause()==null?"unknown":
                            error.getCause().getClass().getSimpleName();
                    diagnostics.event("Playback error: " + error.getErrorCodeName() +
                            " cause=" + cause);
                    state.setText("Playback interrupted (" + error.getErrorCodeName() +
                            "). Select Retry.");
                    Toast.makeText(activity,
                            "Playback stopped; open controls to retry.",Toast.LENGTH_LONG).show();
                    showControls();
                }
            });

            // Never record the stream URL. It can contain account credentials.
            diagnostics.start(type);
            player.setMediaItem(MediaItem.fromUri(media.url));
            player.prepare();
            if (!"live".equals(type) && this.resumeAt > 0)
                player.seekTo(this.resumeAt);
            player.play();
            handler.postDelayed(sampleHealth, HEALTH_INTERVAL_MS);
        } catch (Exception ex) {
            diagnostics.event("Player setup error: " + ex.getClass().getSimpleName());
            Toast.makeText(activity,"Could not initialize video decoder.",Toast.LENGTH_LONG).show();
            showControls();
        }
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
