package tv.aurora.player;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Pure-Java policies for live and on-demand IPTV playback.
 * Does not embed provider credentials in settings or diagnostic logs.
 */
public final class PlaybackTuning {
    public static final String STABLE = "stable";
    public static final String BALANCED = "balanced";
    public static final String FAST = "fast";
    public static final String FORMAT_ORIGINAL = "original";
    public static final String FORMAT_HLS = "hls";
    public static final String FORMAT_TS = "ts";
    private static final Pattern XTREAM_LIVE = Pattern.compile(
            "(?i)^https?://[^/?#]+(?:/[^?#]*)?/live/[^/?#]+/[^/?#]+/[^/?#]+\\.(?:ts|m3u8)(?:\\?[^#]*)?$");

    private PlaybackTuning() {}

    public static String profile(String stored) {
        if (BALANCED.equals(stored) || FAST.equals(stored)) return stored;
        return STABLE;
    }

    public static String format(String stored) {
        if (FORMAT_HLS.equals(stored) || FORMAT_TS.equals(stored)) return stored;
        return FORMAT_ORIGINAL;
    }

    public static final class Buffer {
        public final int minMs, maxMs, startMs, afterRebufferMs;
        public final int maxBytes;
        Buffer(int minMs,int maxMs,int startMs,int afterRebufferMs,int maxBytes){
            this.minMs=minMs;
            this.maxMs=maxMs;
            this.startMs=startMs;
            this.afterRebufferMs=afterRebufferMs;
            this.maxBytes=maxBytes;
        }
    }

    public static Buffer buffer(String profile,boolean isLive,boolean isHls,boolean isLowRam) {
        // Raw MPEG-TS has no HLS live-window limitation: allow longer prefetch.
        // HLS generally cannot buffer beyond its published live window.
        int cap = isLowRam ? 28 * 1024 * 1024 : 56 * 1024 * 1024;
        if (!isLive) {
            return isLowRam ? new Buffer(25000,65000,2000,5500,cap)
                    : new Buffer(35000,90000,2500,6500,cap);
        }
        switch (profile(profile)) {
            case FAST:
                return isHls ? new Buffer(7000,20000,1300,2200,cap)
                        : new Buffer(9000,26000,1400,2700,cap);
            case BALANCED:
                return isHls ? new Buffer(12000,38000,2100,4000,cap)
                        : new Buffer(18000,50000,2500,4500,cap);
            default:
                if (isLowRam) {
                    return isHls ? new Buffer(12000,38000,2700,5200,cap)
                            : new Buffer(21000,54000,3200,6500,cap);
                }
                return isHls ? new Buffer(15000,50000,3000,6000,cap)
                        : new Buffer(28000,75000,3500,7500,cap);
        }
    }

    /**
     * Only transform an ordinary Xtream live endpoint; never alter arbitrary
     * M3U URLs, movie files, or series episodes.
     */
    public static String resolveUrl(String original,String type,String format){
        if(original==null)return "";
        if(!"live".equals(type) || FORMAT_ORIGINAL.equals(format(format)) ||
                !XTREAM_LIVE.matcher(original).matches())return original;
        String extension=FORMAT_HLS.equals(format(format))?"m3u8":"ts";
        int q=original.indexOf('?');
        String base=q<0?original:original.substring(0,q);
        int dot=base.lastIndexOf('.');
        if(dot<0)return original;
        return base.substring(0,dot+1)+extension+(q<0?"":original.substring(q));
    }

    public static boolean isHls(String url){
        if(url==null)return false;
        String lower=url.toLowerCase(Locale.ROOT);
        int q=lower.indexOf('?');
        String path=q<0?lower:lower.substring(0,q);
        return path.endsWith(".m3u8") || path.endsWith(".m3u");
    }
}