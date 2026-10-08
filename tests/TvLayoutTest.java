import tv.aurora.player.TvLayout;

public final class TvLayoutTest {
 private static int cases;
 private static void verify(boolean condition,String why) {
  cases++;
  if(!condition)throw new AssertionError(why);
 }
 public static void main(String[] args) {
  for(int[] device:new int[][]{
    {560,360},{640,360},{800,450},{960,540},{1280,720},
    {1920,1080},{2560,1440},{3840,2160}
  }){
   TvLayout layout=TvLayout.of(device[0],device[1]);
   verify(layout.marginX>=16 && layout.marginX<=58,"Safe left margin "+device[0]);
   verify(layout.marginY>=10 && layout.marginY<=28,"Safe vertical margin "+device[1]);
   verify(layout.sidebar>120 && layout.sidebar<device[0]/2,"Sidebar size "+device[0]);
   verify(layout.contentWidth()>=400,"Browse area is too narrow "+device[0]);
   verify(layout.posterWidth>=138 && layout.posterWidth<=224,"Poster tiles bounded");
   verify(layout.posterCardHeight<layout.heightDp,"Poster tiles taller than screen");
   verify(layout.heroHeight<layout.heightDp*.6,"Hero prevents scrolling");
   verify(layout.navRow<=62,"Remote nav row bounded");
   verify(layout.guideWidth()>=layout.contentWidth()*.7,"Guide table missing columns");
  }
  TvLayout hd=TvLayout.of(960,540),uhd=TvLayout.of(1920,1080);
  verify(uhd.sidebar>hd.sidebar,"Large displays deserve wider navigation");
  verify(uhd.posterWidth>=hd.posterWidth,"Poster scale should not shrink");
  verify(uhd.headerHeight()>=hd.headerHeight(),"Header should scale responsibly");
  System.out.println(cases+" responsive Fire TV layout tests passed");
 }
}
