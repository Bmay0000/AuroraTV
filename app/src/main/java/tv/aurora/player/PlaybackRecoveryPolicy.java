package tv.aurora.player;

/**
 * Pure, deterministic watchdog decisions. It only decides WHEN to reconnect;
 * the Android player owns the sockets, lifecycle, and media source.
 *
 * Distinguishes an expected pause from an actual frozen stream. Consecutive
 * retries are bounded so a dead IPTV source cannot spin forever.
 */
public final class PlaybackRecoveryPolicy {
    public enum Action { WAIT, RECONNECT, GIVE_UP }
    public static final int MAX_ATTEMPTS = 3;
    public static final long INITIAL_STALL_MS = 17000L;
    public static final long REBUFFER_STALL_MS = 14000L;
    public static final long NO_PROGRESS_MS = 14000L;
    public static final long STABLE_RESET_MS = 60000L;
    private static final long RECONNECT_COOLDOWN_MS = 3000L;
    private long bufferingSince = -1;
    private long noProgressSince = -1;
    private long stableSince = -1;
    private long lastReconnectAt = -1;
    private long lastPosition = -1;
    private int attempts;
    private boolean hasPlayed;

    public int attempts() { return attempts; }
    public boolean hasPlayed() { return hasPlayed; }

    /** Call whenever the stream is replaced/reinitialized. */
    public void afterReconnect(long now) {
        bufferingSince = now;
        noProgressSince = now;
        lastPosition = -1;
        stableSince = -1;
        lastReconnectAt = now;
    }

    /** Explicit user action is allowed after automatic retries are exhausted. */
    public void resetManually() {
        attempts = 0;
        hasPlayed = false;
        lastReconnectAt = -1;
        bufferingSince = -1;
        noProgressSince = -1;
        lastPosition = -1;
        stableSince = -1;
    }

    /**
     * @param now monotonic elapsed real time, not wall clock
     * @param state Player.STATE_* (BUFFERING=2, READY=3, IDLE=1, ENDED=4)
     * @param shouldPlay false if the user paused deliberately
     * @param isActuallyPlaying active output status from the player
     * @param positionMs player position; on live, may jump due to timestamps
     */
    public Action sample(long now, int state, boolean shouldPlay,
                         boolean isActuallyPlaying, long positionMs) {
        if (!shouldPlay || state == 4) {
            bufferingSince = -1;
            noProgressSince = -1;
            stableSince = -1;
            lastPosition = positionMs;
            return Action.WAIT;
        }
        if (state == 3 && isActuallyPlaying) {
            if (lastPosition < 0 || Math.abs(positionMs - lastPosition) > 400) {
                if (stableSince < 0) stableSince = now;
                if (now - stableSince >= STABLE_RESET_MS) attempts = 0;
                lastPosition = positionMs;
                noProgressSince = now;
                bufferingSince = -1;
                hasPlayed = true;
                return Action.WAIT;
            }
            if (noProgressSince < 0) noProgressSince = now;
            if (now - noProgressSince >= NO_PROGRESS_MS) {
                return recover(now);
            }
            return Action.WAIT;
        }
        stableSince = -1;
        if (state == 2 || state == 1) {
            if (bufferingSince < 0) bufferingSince = now;
            long allowed = hasPlayed ? REBUFFER_STALL_MS : INITIAL_STALL_MS;
            if (now - bufferingSince >= allowed) return recover(now);
        }
        return Action.WAIT;
    }

    /** A fatal player error should not wait for the buffering watchdog. */
    public Action onError(long now) { return recover(now); }

    private Action recover(long now) {
        if (lastReconnectAt >= 0 && now - lastReconnectAt < RECONNECT_COOLDOWN_MS)
            return Action.WAIT;
        if (attempts >= MAX_ATTEMPTS) return Action.GIVE_UP;
        attempts++;
        afterReconnect(now);
        return Action.RECONNECT;
    }
}
