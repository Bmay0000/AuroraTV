package tv.aurora.player;

/**
 * Fire TV geometry in density-independent pixels. Posters are sized by the
 * number of visible cards, not by 20%+ of screen width. This keeps a 1080p
 * television from showing only three or four oversized posters.
 */
public final class TvLayout {
    public final int widthDp, heightDp, marginX, marginY, sidebar,
            navRow, heroHeight, posterWidth, posterHeight, posterCardHeight,
            posterColumns, liveCardWidth, liveCardHeight, guideChannel, guideNow,
            guideNext, guideAction, columnGap;

    private TvLayout(int w,int h){
        widthDp=w;heightDp=h;
        marginX=clamp((int)Math.round(w*.019),10,34);
        marginY=clamp((int)Math.round(h*.013),6,17);
        navRow=clamp((int)Math.round(h*.065),36,47);
        columnGap=clamp((int)Math.round(w*.006),5,11);
        sidebar=0; // Top navigation uses the full usable screen width.
        int usable=Math.max(300,w-2*marginX-columnGap-12);
        posterColumns=w<650?4:w<850?5:w<1050?7:w<1450?8:w<1900?10:12;
        posterWidth=clamp((usable-(posterColumns+1)*columnGap)/posterColumns,90,165);
        posterHeight=(int)Math.round(posterWidth*1.34);
        posterCardHeight=posterHeight+44;
        liveCardWidth=clamp((usable-6*columnGap)/6,115,190);
        liveCardHeight=(int)Math.round(liveCardWidth*.57)+40;
        heroHeight=clamp((int)Math.round(h*.215),104,170);
        guideChannel=clamp((int)Math.round(usable*.235),135,460);
        guideNow=clamp((int)Math.round(usable*.34),175,650);
        guideNext=clamp((int)Math.round(usable*.305),170,570);
        guideAction=clamp((int)Math.round(usable*.12),94,190);
    }
    public static TvLayout of(int widthDp,int heightDp){
        return new TvLayout(Math.max(560,widthDp),Math.max(360,heightDp));
    }
    public int contentWidth(){return Math.max(300,widthDp-2*marginX-columnGap-12);}
    public int guideWidth(){return guideChannel+guideNow+guideNext+guideAction+16;}
    public int headerHeight(){return clamp((int)Math.round(heightDp*.075),42,61);}
    public int headingSize(){return clamp((int)Math.round(widthDp*.023),22,35);}
    public int bodySize(){return clamp((int)Math.round(widthDp*.014),13,18);}
    public static int clamp(int value,int low,int high){
        return Math.max(low,Math.min(high,value));
    }
}
