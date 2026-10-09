import tv.aurora.player.PlaybackRecoveryPolicy;
public final class PlaybackRecoveryTest {
    private static int checks;
    static void ok(boolean v,String message){checks++;if(!v)throw new AssertionError(message);}
    public static void main(String[] args) {
        PlaybackRecoveryPolicy p=new PlaybackRecoveryPolicy();
        ok(p.sample(0,2,true,false,0)==PlaybackRecoveryPolicy.Action.WAIT,"Start buffers");
        ok(p.sample(16000,2,true,false,0)==PlaybackRecoveryPolicy.Action.WAIT,"Don't reconnect too soon");
        ok(p.sample(18000,2,true,false,0)==PlaybackRecoveryPolicy.Action.RECONNECT,"Initial stall auto retry");
        ok(p.attempts()==1,"Attempt counted");
        ok(p.sample(19000,2,true,false,0)==PlaybackRecoveryPolicy.Action.WAIT,"Wait for cooldown");
        ok(p.sample(33000,2,true,false,0)==PlaybackRecoveryPolicy.Action.RECONNECT,"Second stall");
        ok(p.sample(48000,2,true,false,0)==PlaybackRecoveryPolicy.Action.RECONNECT,"Third stall");
        ok(p.sample(63000,2,true,false,0)==PlaybackRecoveryPolicy.Action.GIVE_UP,"Bounded recovery");
        p.resetManually();
        ok(p.attempts()==0,"Manual retry resets budget");

        p=new PlaybackRecoveryPolicy();
        p.sample(0,2,true,false,0);
        ok(p.sample(12000,2,false,false,0)==PlaybackRecoveryPolicy.Action.WAIT,"User paused");
        ok(p.sample(30000,2,false,false,0)==PlaybackRecoveryPolicy.Action.WAIT,"Paused no retry");
        ok(p.sample(32000,2,true,false,0)==PlaybackRecoveryPolicy.Action.WAIT,"Resume restarts timer");
        ok(p.sample(50000,2,true,false,0)==PlaybackRecoveryPolicy.Action.RECONNECT,"Resumed buffers too long");

        p=new PlaybackRecoveryPolicy();
        p.sample(0,2,true,false,0);
        ok(p.sample(3000,3,true,true,2000)==PlaybackRecoveryPolicy.Action.WAIT,"Live running");
        ok(p.sample(19000,2,true,false,2000)==PlaybackRecoveryPolicy.Action.WAIT,"First rebuffer");
        ok(p.sample(34000,2,true,false,2000)==PlaybackRecoveryPolicy.Action.RECONNECT,"Rebuffer reconnect");
        p.resetManually();
        ok(p.onError(100000)==PlaybackRecoveryPolicy.Action.RECONNECT,"Error reconnect");
        ok(p.onError(100100)==PlaybackRecoveryPolicy.Action.WAIT,"Immediate duplicate error throttled");
        ok(p.sample(105000,3,true,true,100)==PlaybackRecoveryPolicy.Action.WAIT,"Playing after recovery");
        ok(p.sample(120000,3,true,true,100)==PlaybackRecoveryPolicy.Action.RECONNECT,"Frozen READY output");
        p.resetManually();
        p.sample(0,2,true,false,0);
        p.sample(1000,3,true,true,1000);
        for(int t=3000;t<=63000;t+=2000)p.sample(t,3,true,true,t);
        ok(p.attempts()==0,"Stable playback restores retry allowance");
        ok(p.sample(65000,4,true,false,64000)==PlaybackRecoveryPolicy.Action.WAIT,"End of movie is not a stall");
        System.out.println(checks+" playback recovery policy tests passed");
    }
}