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
   verify(layout.marginX>=10 && layout.marginX<=34,"Safe left margin "+device[0]);
   verify(layout.marginY>=6 && layout.marginY<=17,"Safe vertical margin "+device[1]);
   verify(layout.sidebar==0,"Full-width top navigation "+device[0]);
   verify(layout.contentWidth()>=320,"Browse area is too narrow "+device[0]);
   verify(layout.posterWidth>=90 && layout.posterWidth<=165,"Compact poster tiles bounded");
   verify(layout.posterColumns>=4 && layout.posterColumns<=12,"Reasonable number of visible posters");
   verify(layout.posterCardHeight<layout.heightDp,"Poster tiles taller than screen");
   verify(layout.heroHeight<=170 && layout.heroHeight<=layout.heightDp*.3,"Compact hero reveals first shelf");
   verify(layout.navRow<=62,"Remote nav row bounded");
   verify(layout.guideWidth()>=Math.min(layout.contentWidth()*.7,1300),"Guide table missing columns");
  }
  TvLayout hd=TvLayout.of(960,540),uhd=TvLayout.of(1920,1080);
  verify(uhd.posterColumns>hd.posterColumns,"Wide displays show more films");
  verify(uhd.posterWidth>=hd.posterWidth,"Poster clarity should not shrink");
  verify(hd.posterColumns>=7,"Typical Fire TV width should show at least seven posters");
  verify(uhd.headerHeight()>=hd.headerHeight(),"Header should scale responsibly");
  System.out.println(cases+" responsive Fire TV layout tests passed");
 }
}
