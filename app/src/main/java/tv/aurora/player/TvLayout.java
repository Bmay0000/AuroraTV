package tv.aurora.player;

/**
 * Pure responsive layout measurements, all dimensions in density-independent
 * pixels. The same composition works across 720p/1080p/4K Fire TV devices
 * without assuming a particular display density.
 */
public final class TvLayout {
    public final int widthDp, heightDp, marginX, marginY, sidebar,
            navRow, heroHeight, posterWidth, posterHeight, posterCardHeight,
            liveCardWidth, liveCardHeight, guideChannel, guideNow,
            guideNext, guideAction, columnGap;
    private TvLayout(int w,int h){
        widthDp=w; heightDp=h;
        marginX=clamp((int)Math.round(w*.027),16,58);
        marginY=clamp((int)Math.round(h*.026),10,28);
        sidebar=clamp((int)Math.round(w*.155),136,232);
        navRow=clamp((int)Math.round(h*.081),43,62);
        columnGap=clamp((int)Math.round(w*.011),9,20);
        int usable=Math.max(300,w-2*marginX-sidebar-columnGap-18);
        posterWidth=clamp((int)Math.round(usable*.215),138,290);
        posterHeight=(int)Math.round(posterWidth*1.36);
        posterCardHeight=posterHeight+66;
        liveCardWidth=clamp((int)Math.round(usable*.27),166,264);
        liveCardHeight=(int)Math.round(liveCardWidth*.68)+56;
        heroHeight=clamp((int)Math.round(h*.44),205,365);
        guideChannel=clamp((int)Math.round(usable*.235),145,900);
        guideNow=clamp((int)Math.round(usable*.34),190,1200);
        guideNext=clamp((int)Math.round(usable*.305),190,1100);
        guideAction=clamp((int)Math.round(usable*.12),98,380);
    }
    public static TvLayout of(int widthDp,int heightDp){
        return new TvLayout(Math.max(560,widthDp),Math.max(360,heightDp));
    }
    public int contentWidth(){return Math.max(280,widthDp-2*marginX-sidebar-columnGap-18);}
    public int guideWidth(){return guideChannel+guideNow+guideNext+guideAction+16;}
    public int headerHeight(){return clamp((int)Math.round(heightDp*.087),52,78);}
    public int headingSize(){return clamp((int)Math.round(widthDp*.031),25,39);}
    public int bodySize(){return clamp((int)Math.round(widthDp*.016),15,20);}
    public static int clamp(int value,int low,int high){
        return Math.max(low,Math.min(high,value));
    }
}
