import tv.aurora.player.PlaybackTuning;
public final class PlaybackTuningTest {
    static int checks;
    static void require(boolean condition,String msg) {
        checks++;
        if (!condition) throw new AssertionError(msg);
    }
    public static void main(String[] args) {
        PlaybackTuning.Buffer stable=PlaybackTuning.buffer("stable",true,false,false);
        require(stable.minMs>=28000,"Stable live TS should maintain larger forward buffer");
        require(stable.maxMs>=stable.minMs,"Max buffer must exceed min");
        require(stable.afterRebufferMs>=6000,"Avoid restarting after tiny buffer");
        require(stable.startMs<=stable.afterRebufferMs,"Start should be no slower than rebuffer");
        require(PlaybackTuning.buffer("stable",true,false,true).maxBytes<stable.maxBytes,
                "Low-memory Fire TV should use smaller network buffer allocation");
        require(PlaybackTuning.buffer("stable",true,true,false).minMs<stable.minMs,
                "HLS live window requires a shorter buffer target");
        require(PlaybackTuning.buffer("stable",false,false,false).maxMs>=stable.maxMs,
                "Movies should have a larger max buffer");
        require(PlaybackTuning.buffer("fast",true,false,false).minMs<stable.minMs,
                "Low latency mode should have smaller buffers");
        require(PlaybackTuning.buffer("balanced",true,false,false).minMs<stable.minMs,
                "Balanced should have smaller buffer than stable");
        require(PlaybackTuning.profile("bogus").equals(PlaybackTuning.STABLE),
                "Unexpected profile should revert to stable");
        String source="https://example.org:8080/live/alice/secret/123.ts?token=hello";
        String hls=PlaybackTuning.resolveUrl(source,"live","hls");
        require(hls.equals("https://example.org:8080/live/alice/secret/123.m3u8?token=hello"),
                "Switch extension without corrupting credentials or query");
        require(PlaybackTuning.isHls(hls),"HLS mime should be recognized");
        require(!PlaybackTuning.isHls(source),"TS should remain progressive");
        require(PlaybackTuning.resolveUrl(hls,"live","ts").equals(source),
                "Switch from HLS back to transport stream");
        require(PlaybackTuning.resolveUrl("https://example.org/stream/12.ts","live","hls")
                .equals("https://example.org/stream/12.ts"),
                "Never rewrite generic M3U stream URLs");
        require(PlaybackTuning.resolveUrl(source,"movie","hls").equals(source),
                "Never transform movie stream URLs");
        require(PlaybackTuning.resolveUrl(source,"live","original").equals(source),
                "Original mode leaves provider URL alone");
        require(PlaybackTuning.buffer("stable",true,false,true).maxBytes<=32*1024*1024,
                "Low RAM profile must bound loading memory");
        System.out.println(checks+" playback tuning tests passed");
    }
}
