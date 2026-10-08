package tv.aurora.player;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Non-sensitive local playback diagnostics. Never stores URLs, logcat dumps,
 * Xtream credentials, content identifiers, or authorization headers.
 * Logs the most recent 40 state/health entries across unexpected exits.
 */
public final class PlaybackDiagnostics {
    private static final int MAX_ENTRIES = 40;
    private final Context context;
    private final SharedPreferences prefs;

    public PlaybackDiagnostics(Context c) {
        context = c.getApplicationContext();
        prefs = context.getSharedPreferences("playback_diagnostics", Context.MODE_PRIVATE);
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date());
    }

    private static String safe(String value) {
        if (value == null) return "unknown";
        // Only allow human-readable event text; no URLs or account data.
        value = value.replaceAll("(?i)https?://\\S+", "[URL]")
                .replaceAll("(?i)(username|password|token)=\\S+", "$1=[redacted]");
        return value.length() > 200 ? value.substring(0, 200) : value;
    }

    public synchronized void event(String event) {
        String current = prefs.getString("events", "");
        String entry = now() + "  " + safe(event) + "\n";
        String combined = current + entry;
        String[] lines = combined.split("\n");
        if (lines.length > MAX_ENTRIES) {
            StringBuilder recent = new StringBuilder();
            for (int i = lines.length - MAX_ENTRIES; i < lines.length; i++)
                recent.append(lines[i]).append('\n');
            combined = recent.toString();
        }
        prefs.edit().putString("events", combined).apply();
    }

    public synchronized void start(String kind) {
        prefs.edit()
                .putString("last_kind", safe(kind))
                .putLong("started", System.currentTimeMillis())
                .putLong("heartbeat", System.currentTimeMillis())
                .putBoolean("active", true)
                .apply();
        event("Playback started: " + safe(kind));
    }

    public synchronized void heartbeat(long positionMs, long bufferedMs, int state,
                                       long javaHeapUsedMb, long javaHeapLimitMb) {
        prefs.edit().putLong("heartbeat", System.currentTimeMillis())
                .putLong("position", Math.max(0, positionMs))
                .putLong("buffered", Math.max(0, bufferedMs))
                .putInt("player_state", state)
                .putLong("heap_used", javaHeapUsedMb)
                .putLong("heap_limit", javaHeapLimitMb)
                .apply();
    }

    public synchronized void stop() {
        prefs.edit().putBoolean("active", false)
                .putLong("stopped", System.currentTimeMillis()).apply();
        event("Playback closed normally");
    }

    private static String exitReason(int code) {
        if (Build.VERSION.SDK_INT < 30) return "Unavailable on this Android version";
        switch (code) {
            case ApplicationExitInfo.REASON_CRASH: return "Java crash";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "Native/decoder crash";
            case ApplicationExitInfo.REASON_ANR: return "App not responding";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "Low-memory process termination";
            case ApplicationExitInfo.REASON_SIGNALED: return "Process killed by signal";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "User/system termination";
            case ApplicationExitInfo.REASON_EXIT_SELF: return "Normal application exit";
            case ApplicationExitInfo.REASON_OTHER: return "Other system reason";
            default: return "Reason code " + code;
        }
    }

    public String report() {
        StringBuilder result = new StringBuilder();
        result.append("AuroraTV playback diagnostics\n")
                .append("Android ").append(Build.VERSION.RELEASE)
                .append(" · SDK ").append(Build.VERSION.SDK_INT)
                .append("\nDevice ").append(Build.MANUFACTURER).append(" ")
                .append(Build.MODEL).append("\n\n");
        try {
            ActivityManager manager = (ActivityManager)
                    context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager != null) {
                result.append("Memory class: ").append(manager.getMemoryClass())
                        .append(" MB; low RAM: ").append(manager.isLowRamDevice())
                        .append("\n");
                if (Build.VERSION.SDK_INT >= 30) {
                    List<ApplicationExitInfo> exits =
                            manager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 6);
                    if (exits != null) {
                        result.append("Recent Android process exit reasons:\n");
                        for (ApplicationExitInfo exit : exits) {
                            result.append(" • ")
                                    .append(new SimpleDateFormat("MMM d HH:mm", Locale.US)
                                            .format(new Date(exit.getTimestamp())))
                                    .append("  ").append(exitReason(exit.getReason()))
                                    .append("\n");
                        }
                    }
                }
            }
        } catch (Exception e) {
            result.append("Exit-reason information unavailable\n");
        }
        result.append("\nPrevious session: ")
                .append(prefs.getBoolean("active", false) ?
                        "Not closed normally (could be a crash or device shutdown)" : "Closed normally")
                .append("\nPlayback type: ").append(prefs.getString("last_kind", "none"))
                .append("\nLast position: ").append(prefs.getLong("position", 0) / 1000)
                .append(" sec\nLast buffered position: ")
                .append(prefs.getLong("buffered", 0) / 1000)
                .append(" sec\nJava heap last sampled: ")
                .append(prefs.getLong("heap_used", 0)).append(" / ")
                .append(prefs.getLong("heap_limit", 0)).append(" MB")
                .append("\nLast heartbeat: ").append(prefs.getLong("heartbeat", 0) == 0 ?
                        "never" : new SimpleDateFormat("MMM d HH:mm:ss", Locale.US)
                                .format(new Date(prefs.getLong("heartbeat", 0))))
                .append("\n\nRecent player events:\n")
                .append(prefs.getString("events", ""));
        return result.toString();
    }
}
