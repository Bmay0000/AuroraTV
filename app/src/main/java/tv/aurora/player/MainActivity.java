package tv.aurora.player;
import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.res.ColorStateList;
import android.widget.FrameLayout;
import android.util.LruCache;
import java.net.*;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import android.text.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
public class MainActivity extends Activity {
 final int BG=0xff070c17,PANEL=0xff142033,ACCENT=0xff5debd0,MUTED=0xff9badc1,SURFACE=0xff101b2d;
 LinearLayout root,body,nav;TextView subtitle;SharedPreferences prefs;ExecutorService io=Executors.newSingleThreadExecutor();List<LibraryCore.Item> items=new ArrayList<>();List<Provider.Program> guide=new ArrayList<>();Map<String,List<Provider.Program>> guideIndex=new HashMap<>();Set<String> hidden,categories,favorites,allowed,shown,shownCategories;boolean hideUnknown;String section="live",query="",category="All";boolean editing=false,favOnly=false,hiddenOnly=false;LibraryCore.Item selected,playing;PlaybackScreen playbackScreen;PlaybackDiagnostics playbackDiagnostics;boolean restoreLibraryOnResume=false;boolean loading=false;int generation=0;int browseToken=0;int page=0;static final int PAGE_SIZE=200;LibraryStore store;GuideEngine epg;int guidePage=0;ExecutorService epgRefreshIO=Executors.newSingleThreadExecutor(),shortEpgIO=Executors.newSingleThreadExecutor();Map<String,String> guideSummary=new HashMap<>();String screen="login",screenBeforePlayer="home";TextView loadingStatus;PosterLoader posters;boolean focusSearchNext=false;Map<String,Button> navButtons=new LinkedHashMap<>();
 @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().getDecorView().setSystemUiVisibility(5894);prefs=getSharedPreferences("library",MODE_PRIVATE);store=new LibraryStore(this);posters=new PosterLoader(this);playbackDiagnostics=new PlaybackDiagnostics(this);epg=new GuideEngine(this);hidden=set("hidden");categories=set("categories");favorites=set("favorites");allowed=set("allowed");shown=set("shown");shownCategories=set("shownCategories");hideUnknown=prefs.getBoolean("unknown",false);
   if(!prefs.getBoolean("smartFilterV3",false)){
    // Prior versions auto-enabled strict mode for English-only libraries,
    // unintentionally hiding unclassified English stations. Reset once.
    if(!allowed.isEmpty())hideUnknown=false;
    prefs.edit().putBoolean("unknown",hideUnknown)
     .putBoolean("smartFilterV2",true).putBoolean("smartFilterV3",true).apply();
   }
   if(!prefs.getBoolean("guideNamesV2",false)){
    // Existing XMLTV name indexes used to retain HDR/4K provider labels.
    // Trigger one normal asynchronous refresh to rebuild matching metadata.
    SharedPreferences.Editor migrate=prefs.edit().putBoolean("guideNamesV2",true);
    for(String source:new String[]{"provider","external1","external2","external3","external4"})
     migrate.remove("guide.attempt."+source);
    migrate.apply();
   }
   start();}
 Set<String> set(String k){return new HashSet<>(prefs.getStringSet(k,new HashSet<>()));}
 void save(){prefs.edit().putStringSet("hidden",hidden).putStringSet("categories",categories).putStringSet("favorites",favorites).putStringSet("allowed",allowed).putStringSet("shown",shown).putStringSet("shownCategories",shownCategories).putBoolean("unknown",hideUnknown).putBoolean("smartFilterV2",true).apply();}
 int dp(int v){return (int)(v*getResources().getDisplayMetrics().density);}
 TvLayout tv(){
   android.util.DisplayMetrics dm=getResources().getDisplayMetrics();
   float density=Math.max(.5f,dm.density);
   return TvLayout.of((int)(dm.widthPixels/density),(int)(dm.heightPixels/density));
 }
 LinearLayout column(){
   LinearLayout l=new LinearLayout(this);
   l.setOrientation(LinearLayout.VERTICAL);
   l.setClipChildren(false);l.setClipToPadding(false);
   return l;
 }
 TextView text(String value,int size){
   TextView t=new TextView(this);
   t.setText(value);t.setTextColor(Color.WHITE);t.setTextSize(size);
   t.setLineSpacing(dp(2),1.04f);t.setIncludeFontPadding(false);
   t.setPadding(dp(5),dp(5),dp(5),dp(5));
   return t;
 }
 GradientDrawable shape(int color){
   GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(13));return d;
 }
 GradientDrawable rounded(int color,int radius,int stroke){
   GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));
   if(stroke!=0)d.setStroke(Math.max(1,dp(1)),stroke);
   return d;
 }
 TextView brand(int size){
   android.text.SpannableString name=new android.text.SpannableString("AuroraTV");
   name.setSpan(new android.text.style.ForegroundColorSpan(ACCENT),
     6,8,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
   TextView v=text("",size);v.setText(name);
   v.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));
   v.setLetterSpacing(.04f);v.setSingleLine(true);v.setPadding(0,0,0,0);
   return v;
 }
 Button button(String label,Runnable action){
   Button b=new Button(this);b.setText(label);b.setAllCaps(false);
   b.setTextColor(Color.WHITE);b.setTextSize(tv().bodySize()-1);b.setLetterSpacing(.015f);
   b.setMinHeight(0);b.setMinimumHeight(0);b.setMinWidth(0);b.setMinimumWidth(0);
   b.setPadding(dp(12),0,dp(12),0);
   b.setBackground(rounded(PANEL,12,0xff26374b));
   LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(tv().navRow));
   lp.setMargins(dp(3),dp(3),dp(3),dp(3));b.setLayoutParams(lp);
   b.setFocusable(true);
   b.setOnFocusChangeListener((view,focused)->{
     b.animate().scaleX(focused?1.02f:1f).scaleY(focused?1.02f:1f).setDuration(110).start();
     b.setBackground(rounded(focused?ACCENT:PANEL,12,focused?ACCENT:0xff26374b));
     b.setTextColor(focused?BG:Color.WHITE);
   });
   b.setOnClickListener(v->action.run());
   return b;
 }
 TextView kicker(String value){
   TextView t=text(value.toUpperCase(Locale.ROOT),12);
   t.setTextColor(ACCENT);t.setLetterSpacing(.13f);
   t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;
 }

 void start(){
  if(store.hasLibrary()){
   loadingScreen("WELCOME BACK","Preparing your entertainment library");
   io.execute(()->{
    try{
     int total=store.count("live")+store.count("movie")+store.count("series");
     runOnUiThread(()->{if(!isDestroyed()){shell();home();}});
    }catch(Exception e){runOnUiThread(()->{if(!isDestroyed()){toast("Catalog needs refreshing");restoreAccountOrLogin();}});}
   });
  }else restoreAccountOrLogin();
 }
 void restoreAccountOrLogin(){
  String mode=prefs.getString("mode","");
  if(mode.isEmpty()){loginScreen(false);return;}
  loadingScreen("UPDATING YOUR LIBRARY","Building a faster catalog for this device");
  refresh();
 }
 GradientDrawable gradient(int a,int b,int radius){
  GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{a,b});
  d.setCornerRadius(dp(radius));return d;
 }
 TextView headline(String value,int size,int color){
  TextView t=text(value,size);t.setTextColor(color);t.setTypeface(null,Typeface.BOLD);return t;
 }
 void loadingScreen(String title,String message){
  screen="loading";
  TvLayout metrics=tv();
  root=column();
  root.setGravity(Gravity.CENTER);
  root.setPadding(dp(metrics.marginX),dp(metrics.marginY),
                  dp(metrics.marginX),dp(metrics.marginY));
  root.setBackground(gradient(0xff050a16,0xff123b42,0));
  setContentView(root);
  LinearLayout card=column();
  card.setGravity(Gravity.CENTER);
  card.setPadding(dp(28),dp(25),dp(28),dp(25));
  card.setBackground(rounded(0xcc0f1c2d,24,0xff2d5360));
  LinearLayout.LayoutParams c=new LinearLayout.LayoutParams(
      Math.min(dp(620),metrics.widthDp>0?dp(metrics.widthDp-2*metrics.marginX):-1),-2);
  root.addView(card,c);
  TextView logo=brand(TvLayout.clamp(metrics.widthDp/26,33,49));
  logo.setGravity(Gravity.CENTER);card.addView(logo);
  TextView eyebrow=kicker("CURATED FOR YOUR SCREEN");
  eyebrow.setGravity(Gravity.CENTER);
  LinearLayout.LayoutParams e=new LinearLayout.LayoutParams(-1,-2);
  e.topMargin=dp(18);card.addView(eyebrow,e);
  TextView heading=headline(title,30,Color.WHITE);
  heading.setGravity(Gravity.CENTER);card.addView(heading);
  loadingStatus=text(message,16);
  loadingStatus.setGravity(Gravity.CENTER);
  loadingStatus.setTextColor(MUTED);
  loadingStatus.setMaxLines(3);card.addView(loadingStatus);
  ProgressBar spinner=new ProgressBar(this);
  spinner.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
  LinearLayout.LayoutParams progress=new LinearLayout.LayoutParams(dp(38),dp(38));
  progress.gravity=Gravity.CENTER_HORIZONTAL;progress.topMargin=dp(24);
  card.addView(spinner,progress);
  TextView note=text("ONE LIBRARY  •  ENDLESS POSSIBILITIES",12);
  note.setGravity(Gravity.CENTER);note.setTextColor(0xff849aab);
  LinearLayout.LayoutParams foot=new LinearLayout.LayoutParams(-1,-2);foot.topMargin=dp(20);
  card.addView(note,foot);
 }
 void status(String message){
  runOnUiThread(()->{
   if(!isDestroyed()&&screen.equals("loading")&&loadingStatus!=null)
    loadingStatus.setText(message);
  });
 }
 void loginScreen(boolean m3u){
  screen="login";
  TvLayout metrics=tv();
  root=column();root.setBackground(gradient(0xff050a14,0xff102a36,0));
  root.setPadding(dp(metrics.marginX),dp(metrics.marginY),
    dp(metrics.marginX),dp(metrics.marginY));
  setContentView(root);
  ScrollView scrolling=new ScrollView(this);
  scrolling.setFillViewport(true);scrolling.setVerticalScrollBarEnabled(false);
  root.addView(scrolling,new LinearLayout.LayoutParams(-1,-1));
  LinearLayout content=new LinearLayout(this);
  boolean wide=metrics.widthDp>=950;
  content.setOrientation(wide?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);
  content.setGravity(Gravity.CENTER);
  scrolling.addView(content,new ScrollView.LayoutParams(-1,-1));

  LinearLayout intro=column();
  intro.setPadding(dp(wide?24:8),dp(10),dp(wide?35:8),dp(15));
  if(wide)content.addView(intro,new LinearLayout.LayoutParams(0,-2,1));
  else content.addView(intro,new LinearLayout.LayoutParams(-1,-2));
  intro.addView(brand(TvLayout.clamp(metrics.widthDp/30,32,48)));
  LinearLayout.LayoutParams introSpace=new LinearLayout.LayoutParams(-1,-2);
  introSpace.topMargin=dp(wide?31:15);
  intro.addView(kicker("YOUR ENTERTAINMENT, REIMAGINED"),introSpace);
  TextView title=headline("Everything you love.\nOne beautiful place.",
    TvLayout.clamp(metrics.headingSize()+5,29,45),Color.WHITE);
  title.setMaxLines(3);intro.addView(title);
  TextView description=text(
    "Watch live channels, explore thousands of movies, and enjoy your favorite shows — all from your own IPTV provider.",17);
  description.setTextColor(MUTED);description.setMaxLines(4);
  intro.addView(description);
  if(wide){
   TextView marks=kicker("LIVE CHANNELS      ◆      MOVIES      ◆      SERIES");
   LinearLayout.LayoutParams marksSize=new LinearLayout.LayoutParams(-1,-2);
   marksSize.topMargin=dp(30);intro.addView(marks,marksSize);
  }

  LinearLayout panel=column();
  panel.setPadding(dp(26),dp(20),dp(26),dp(20));
  panel.setBackground(gradient(0xff17263b,0xff0c182b,22));
  int panelWidth=TvLayout.clamp((int)(metrics.widthDp*.44),340,565);
  LinearLayout.LayoutParams pp=wide?
     new LinearLayout.LayoutParams(dp(panelWidth),-2):
     new LinearLayout.LayoutParams(-1,-2);
  if(!wide)pp.topMargin=dp(16);
  content.addView(panel,pp);
  panel.addView(kicker("CONNECT A SOURCE"));
  TextView caption=headline(m3u?"Add your M3U playlist":"Xtream Codes login",24,Color.WHITE);
  panel.addView(caption);
  TextView hint=text("Sign in with the details supplied by your IPTV service.",14);
  hint.setTextColor(MUTED);panel.addView(hint);
  LinearLayout tabs=new LinearLayout(this);tabs.setGravity(Gravity.CENTER_VERTICAL);
  LinearLayout.LayoutParams tabSpace=new LinearLayout.LayoutParams(-1,dp(50));
  tabSpace.topMargin=dp(12);panel.addView(tabs,tabSpace);
  Button xt=button("Xtream Codes",()->loginScreen(false));
  Button ml=button("M3U playlist",()->loginScreen(true));
  tabs.addView(xt,new LinearLayout.LayoutParams(0,-1,1));
  tabs.addView(ml,new LinearLayout.LayoutParams(0,-1,1));
  (m3u?ml:xt).setBackground(rounded(0xff1c746e,12,ACCENT));
  EditText address=field(panel,m3u?"Playlist URL":"Server URL (http:// or https://)",false);
  EditText account=m3u?null:field(panel,"Username",false);
  EditText secret=m3u?null:field(panel,"Password",true);
  try{
   String cached=Vault.open(prefs.getString("url",""));
   if(!cached.isEmpty())address.setText(cached);
  }catch(Exception ignored){}
  Button connect=button("CONNECT TO YOUR LIBRARY    →",()->{
   String url=address.getText().toString().trim();
   String username=account==null?"":account.getText().toString().trim();
   String password=secret==null?"":secret.getText().toString();
   if(url.isEmpty()||(!m3u&&(username.isEmpty()||password.isEmpty()))){
    toast("Enter your connection details");return;
   }
   importSource(m3u?"m3u":"xtream",url,username,password);
  });
  connect.setBackground(rounded(0xff188475,12,0xff4de2c7));
  LinearLayout.LayoutParams connectGap=new LinearLayout.LayoutParams(-1,dp(54));
  connectGap.topMargin=dp(12);panel.addView(connect,connectGap);
  TextView security=text("PRIVATE BY DESIGN  ·  Your login stays on this device.",12);
  security.setTextColor(0xff91abbc);
  panel.addView(security);
  address.requestFocus();
 }
 void addNav(String key,String label,Runnable action){
  Button b=button(label,()->{action.run();refreshSidebar();});
  b.setTag(key);
  b.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);
  b.setTextSize(TvLayout.clamp(tv().bodySize(),14,18));
  b.setPadding(dp(17),0,dp(5),0);
  b.setLetterSpacing(.01f);
  b.setOnFocusChangeListener((view,focused)->{
   styleNav(b,focused);
  });
  navButtons.put(key,b);nav.addView(b);
 }
 void styleNav(Button b,boolean focused){
  boolean active=isNavActive(String.valueOf(b.getTag()));
  b.setBackground(rounded(
   focused?ACCENT:active?0xff173c45:0x00000000,12,
   focused?ACCENT:active?0xff2c8b80:0x00000000));
  b.setTextColor(focused?BG:active?0xff84f8dd:0xffc0cede);
  b.setTypeface(null,active?Typeface.BOLD:Typeface.NORMAL);
  b.animate().scaleX(focused?1.02f:1f).scaleY(focused?1.02f:1f).setDuration(110).start();
 }
 boolean isNavActive(String key){
  if(key.equals("home"))return screen.equals("home");
  if(key.equals("guide"))return screen.equals("guide");
  if(key.equals("favorites"))return screen.equals("browse")&&favOnly;
  if(key.equals("library"))return screen.equals("browse")&&editing;
  return screen.equals("browse")&&!favOnly&&!editing&&key.equals(section);
 }
 void refreshSidebar(){
  for(Button b:navButtons.values())styleNav(b,b.isFocused());
 }
 void shell(){
  screen="home";
  TvLayout metrics=tv();
  root=column();
  root.setBackground(gradient(0xff060b16,0xff0a1322,0));
  root.setPadding(dp(metrics.marginX),dp(metrics.marginY),
                  dp(metrics.marginX),dp(metrics.marginY));
  setContentView(root);
  LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
  root.addView(header,new LinearLayout.LayoutParams(-1,dp(metrics.headerHeight())));
  TextView mark=brand(TvLayout.clamp(metrics.widthDp/36,26,36));header.addView(mark);
  TextView dot=text("  •  YOUR PERSONAL STREAMING SPACE",12);
  dot.setTextColor(0xff8497ad);
  if(metrics.widthDp>940)header.addView(dot);
  LinearLayout.LayoutParams space=new LinearLayout.LayoutParams(0,1,1);
  View spacer=new View(this);header.addView(spacer,space);
  subtitle=text("Ready to watch",TvLayout.clamp(metrics.bodySize()-2,12,16));
  subtitle.setTextColor(MUTED);
  subtitle.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
  header.addView(subtitle,new LinearLayout.LayoutParams(-2,-1));

  LinearLayout layout=new LinearLayout(this);
  layout.setClipChildren(false);layout.setClipToPadding(false);
  LinearLayout.LayoutParams area=new LinearLayout.LayoutParams(-1,0,1);
  area.topMargin=dp(7);root.addView(layout,area);
  LinearLayout sidebar=column();
  sidebar.setBackground(rounded(0xff0c1726,18,0xff1c3042));
  sidebar.setPadding(dp(7),dp(12),dp(7),dp(12));
  layout.addView(sidebar,new LinearLayout.LayoutParams(dp(metrics.sidebar),-1));
  TextView explore=kicker("DISCOVER");
  explore.setPadding(dp(17),dp(8),0,dp(10));
  sidebar.addView(explore);
  ScrollView menuScroll=new ScrollView(this);
  menuScroll.setFillViewport(false);
  menuScroll.setVerticalScrollBarEnabled(false);
  sidebar.addView(menuScroll,new LinearLayout.LayoutParams(-1,0,1));
  nav=column();menuScroll.addView(nav);
  navButtons.clear();
  addNav("home","⌂   Home",this::home);
  addNav("live","◉   Live TV",()->{section="live";favOnly=false;hiddenOnly=false;editing=false;page=0;category="All";query="";browse();});
  addNav("guide","▦   TV Guide",()->{section="live";category="All";guidePage=0;tvGuide();});
  addNav("movie","◆   Movies",()->{section="movie";favOnly=false;hiddenOnly=false;editing=false;page=0;category="All";query="";browse();});
  addNav("series","▥   TV Shows",()->{section="series";favOnly=false;hiddenOnly=false;editing=false;page=0;category="All";query="";browse();});
  addNav("favorites","★   Favorites",()->{favOnly=true;hiddenOnly=false;editing=false;page=0;browse();});
  addNav("search","⌕   Search",this::search);
  TextView manageLabel=kicker("YOUR LIBRARY");
  manageLabel.setPadding(dp(17),dp(18),0,dp(7));nav.addView(manageLabel);
  addNav("library","☷   Edit Library",this::manage);
  addNav("sources","⚙   Sources & Settings",this::connect);
  TextView foot=text("BUILT FOR THE BIG SCREEN",10);
  foot.setTextColor(0xff647e8f);foot.setGravity(Gravity.CENTER);
  sidebar.addView(foot);
  body=column();body.setPadding(dp(metrics.columnGap),0,0,0);
  layout.addView(body,new LinearLayout.LayoutParams(0,-1,1));
  refreshSidebar();
 }

 boolean visible(LibraryCore.Item i){return LibraryCore.visible(i,hidden,categories,favorites,allowed,hideUnknown,shown,shownCategories);}
 void home(){
  if(!store.hasLibrary()){loginScreen(false);return;}
  screen="home";refreshSidebar();
  scheduleGuideSync(false,true);
  final int token=++browseToken;
  body.removeAllViews();
  body.addView(kicker("PREPARING YOUR DISCOVER PAGE"));
  final Set<String> h=new HashSet<>(hidden),c=new HashSet<>(categories),
      fav=new HashSet<>(favorites),langs=new HashSet<>(allowed),
      manual=new HashSet<>(shown),manualGroups=new HashSet<>(shownCategories);
  final boolean strict=hideUnknown;
  io.execute(()->{
   try{
    LibraryStore.Page live=store.page("live","All","",false,false,h,c,fav,langs,strict,
       manual,manualGroups,0,12);
    LibraryStore.Page movies=store.page("movie","All","",false,false,h,c,fav,langs,strict,
       manual,manualGroups,0,12);
    LibraryStore.Page series=store.page("series","All","",false,false,h,c,fav,langs,strict,
       manual,manualGroups,0,12);
    runOnUiThread(()->{
     if(isDestroyed()||token!=browseToken||!screen.equals("home"))return;
     body.removeAllViews();
     subtitle.setText("●  YOUR LIBRARY IS READY");
     ScrollView scroller=new ScrollView(this);
     scroller.setVerticalScrollBarEnabled(false);
     scroller.setFillViewport(false);
     scroller.setClipToPadding(false);scroller.setClipChildren(false);
     body.addView(scroller,new LinearLayout.LayoutParams(-1,-1));
     LinearLayout feed=column();feed.setPadding(dp(6),0,dp(9),dp(24));
     scroller.addView(feed,new ScrollView.LayoutParams(-1,-2));
     LibraryCore.Item featured=null;
     for(LibraryCore.Item i:movies.rows){
      if(i.artwork!=null&&i.artwork.startsWith("http")){featured=i;break;}
     }
     if(featured==null&&!movies.rows.isEmpty())featured=movies.rows.get(0);
     feed.addView(homeHero(featured));
     quickCategories(feed);
     homeShelf(feed,"LIVE RIGHT NOW","live",live.rows);
     homeShelf(feed,"MOVIES TO EXPLORE","movie",movies.rows);
     homeShelf(feed,"YOUR NEXT TV OBSESSION","series",series.rows);
    });
   }catch(Exception e){
    runOnUiThread(()->{
     if(isDestroyed()||token!=browseToken)return;
     body.removeAllViews();
     body.addView(headline("Your library could not load.",24,Color.WHITE));
     body.addView(text("Please try refreshing the connection.",16));
     body.addView(button("RETRY",this::home));
    });
   }
  });
 }
 View homeHero(LibraryCore.Item selectedFeature){
  final LibraryCore.Item feature=selectedFeature;
  TvLayout dim=tv();
  FrameLayout hero=new FrameLayout(this);
  hero.setBackground(rounded(0xff132d3e,23,0xff365467));
  hero.setClipToOutline(true);
  LinearLayout.LayoutParams frameBounds=new LinearLayout.LayoutParams(-1,dp(dim.heroHeight));
  hero.setLayoutParams(frameBounds);
  if(feature!=null&&feature.artwork!=null&&feature.artwork.startsWith("http")){
   ImageView backdrop=new ImageView(this);
   backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
   hero.addView(backdrop,new FrameLayout.LayoutParams(-1,-1));
   posters.bind(backdrop,feature.artwork);
  }
  View veil=new View(this);
  GradientDrawable shade=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
    new int[]{0xff0e2634,0xf70e2634,0xbc122b3c,0x44102236});
  shade.setCornerRadius(dp(22));veil.setBackground(shade);
  hero.addView(veil,new FrameLayout.LayoutParams(-1,-1));
  LinearLayout front=column();
  front.setGravity(Gravity.CENTER_VERTICAL);
  int textWidth=TvLayout.clamp((int)(dim.contentWidth()*.66),315,730);
  front.setPadding(dp(20),dp(11),dp(12),dp(12));
  FrameLayout.LayoutParams frontParams=new FrameLayout.LayoutParams(
    dp(textWidth),-1,Gravity.TOP|Gravity.LEFT);
  hero.addView(front,frontParams);
  front.addView(kicker(feature==null?"WELCOME TO AURORATV":"FEATURED FROM YOUR LIBRARY"));
  TextView title=headline(feature==null?"Your screen.\nYour world.":feature.name,
     TvLayout.clamp(dim.headingSize()+3,29,44),Color.WHITE);
  title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);
  front.addView(title);
  TextView sub=text(feature==null?
    "Every channel, movie and series. One seamless home.":
    "Discover something worth watching  •  "+feature.category,
    TvLayout.clamp(dim.bodySize(),14,19));
  sub.setTextColor(0xffcee0e8);sub.setMaxLines(2);
  sub.setEllipsize(TextUtils.TruncateAt.END);front.addView(sub);
  LinearLayout buttons=new LinearLayout(this);buttons.setGravity(Gravity.CENTER_VERTICAL);
  LinearLayout.LayoutParams gap=new LinearLayout.LayoutParams(-1,dp(48));
  gap.topMargin=dp(12);front.addView(buttons,gap);
  Button primary=button(feature==null?"▶  WATCH LIVE TV":"▶  FEATURED MOVIE",()->{
   if(feature!=null)showMediaDetails(feature);
   else{section="live";category="All";page=0;favOnly=false;editing=false;hiddenOnly=false;browse();}
  });
  primary.setBackground(rounded(0xff2fd5bc,11,ACCENT));
  primary.setTextColor(0xff06191b);
  buttons.addView(primary,new LinearLayout.LayoutParams(0,-1,1));
  Button secondary=button("EXPLORE MOVIES  →",()->{
   section="movie";category="All";query="";page=0;favOnly=false;hiddenOnly=false;editing=false;browse();
  });
  LinearLayout.LayoutParams second=new LinearLayout.LayoutParams(0,-1,1);
  second.leftMargin=dp(10);buttons.addView(secondary,second);
  return hero;
 }
 void quickCategories(LinearLayout feed){
  TvLayout metrics=tv();
  LinearLayout row=new LinearLayout(this);
  row.setGravity(Gravity.CENTER_VERTICAL);
  LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(TvLayout.clamp(metrics.heightDp/11,48,64)));
  p.topMargin=dp(12);feed.addView(row,p);
  String[][] categories={
   {"◉   LIVE CHANNELS","live"},
   {"◆   ALL MOVIES","movie"},
   {"▥   TV SERIES","series"}
  };
  for(String[] shortcut:categories){
   Button tile=button(shortcut[0],()->{
    section=shortcut[1];category="All";page=0;query="";
    favOnly=false;hiddenOnly=false;editing=false;browse();
   });
   tile.setGravity(Gravity.CENTER);
   tile.setTextSize(TvLayout.clamp(tv().bodySize(),14,18));
   tile.setBackground(rounded(0xff102636,12,0xff285163));
   LinearLayout.LayoutParams width=new LinearLayout.LayoutParams(0,-1,1);
   width.setMargins(dp(3),0,dp(3),0);row.addView(tile,width);
  }
 }
 void homeShelf(LinearLayout feed,String title,String type,List<LibraryCore.Item> rows){
  TvLayout dim=tv();
  LinearLayout header=new LinearLayout(this);
  header.setGravity(Gravity.CENTER_VERTICAL);
  LinearLayout.LayoutParams spacing=new LinearLayout.LayoutParams(-1,dp(51));
  spacing.topMargin=dp(17);feed.addView(header,spacing);
  LinearLayout lhs=column();
  header.addView(lhs,new LinearLayout.LayoutParams(0,-1,1));
  lhs.addView(headline(title,TvLayout.clamp(dim.headingSize()-8,18,27),Color.WHITE));
  TextView detail=text(type.equals("live")?"Your channels, at your fingertips":
    type.equals("movie")?"Discover cinema from your collection":
    "Your next binge starts here",12);
  detail.setTextColor(MUTED);lhs.addView(detail);
  Button seeAll=button("VIEW ALL  →",()->{
   section=type;page=0;category="All";query="";
   favOnly=false;hiddenOnly=false;editing=false;browse();
  });
  seeAll.setTextSize(13);
  header.addView(seeAll,new LinearLayout.LayoutParams(dp(128),dp(41)));
  HorizontalScrollView gallery=new HorizontalScrollView(this);
  gallery.setHorizontalScrollBarEnabled(false);
  gallery.setClipToPadding(false);gallery.setClipChildren(false);
  feed.addView(gallery,new LinearLayout.LayoutParams(-1,dp(
     type.equals("live")?dim.liveCardHeight+18:dim.posterCardHeight+18)));
  LinearLayout cards=new LinearLayout(this);
  cards.setClipChildren(false);cards.setGravity(Gravity.TOP);
  gallery.addView(cards,new ViewGroup.LayoutParams(-2,-2));
  if(rows.isEmpty()){
   TextView empty=text("No visible titles in this section. Try editing your filters.",16);
   empty.setTextColor(MUTED);cards.addView(empty);
   return;
  }
  for(LibraryCore.Item item:rows)cards.addView(mediaCard(item,type));
 }
 View mediaCard(LibraryCore.Item item,String type){
  TvLayout dim=tv();
  boolean live="live".equals(type);
  int cardWidth=live?dim.liveCardWidth:dim.posterWidth;
  int artHeight=live?dim.liveCardHeight-53:dim.posterHeight;
  int totalHeight=live?dim.liveCardHeight:dim.posterCardHeight;
  LinearLayout card=column();card.setPadding(dp(5),dp(5),dp(5),dp(5));
  LinearLayout.LayoutParams outer=new LinearLayout.LayoutParams(dp(cardWidth),dp(totalHeight));
  outer.rightMargin=dp(dim.columnGap);card.setLayoutParams(outer);
  card.setBackground(rounded(PANEL,14,0xff20384a));
  FrameLayout art=new FrameLayout(this);
  art.setBackground(gradient(live?0xff105c69:type.equals("movie")?0xff3b385f:0xff26466b,
     0xff111e34,13));
  art.setClipToOutline(true);
  card.addView(art,new LinearLayout.LayoutParams(-1,dp(artHeight)));
  TextView initial=headline(item.name.isEmpty()?"A":item.name.substring(0,1).toUpperCase(Locale.ROOT),
    46,0xff567287);
  art.addView(initial,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
  displayArtwork(art,item.artwork);
  TextView badge=kicker(live?"●  LIVE":type.equals("movie")?"◆  MOVIE":"▥  SERIES");
  badge.setBackground(rounded(0xee0b2732,8,0xff315761));
  badge.setTextSize(10);badge.setPadding(dp(8),dp(5),dp(8),dp(5));
  FrameLayout.LayoutParams badgeLp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.LEFT);
  badgeLp.setMargins(dp(8),dp(8),0,0);art.addView(badge,badgeLp);
  TextView text=text(item.name,TvLayout.clamp(dim.bodySize()-1,14,18));
  text.setMaxLines(2);text.setEllipsize(TextUtils.TruncateAt.END);
  text.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));
  LinearLayout.LayoutParams titleBounds=new LinearLayout.LayoutParams(-1,-2);
  titleBounds.topMargin=dp(5);card.addView(text,titleBounds);
  card.setFocusable(true);card.setClickable(true);
  card.setOnClickListener(v->{
   if(live)open(item);else showMediaDetails(item);
  });
  card.setOnFocusChangeListener((v,focus)->{
   card.setBackground(rounded(focus?0xff1b484f:PANEL,14,focus?ACCENT:0xff20384a));
   card.animate().scaleX(focus?1.035f:1f).scaleY(focus?1.035f:1f)
     .setDuration(140).start();
  });
  return card;
 }

 void displayArtwork(FrameLayout frame,String url){
  ImageView image=new ImageView(this);
  image.setScaleType(ImageView.ScaleType.CENTER_CROP);
  frame.addView(image,new FrameLayout.LayoutParams(-1,-1));
  posters.bind(image,url);
 }


 static final class PosterTile {
  ImageView image;
  TextView title,category,badge,initial;
 }
 View posterGridCard(LibraryCore.Item item,View recycled){
  LinearLayout card;
  PosterTile holder;
  if(recycled instanceof LinearLayout && recycled.getTag() instanceof PosterTile){
   card=(LinearLayout)recycled;
   holder=(PosterTile)card.getTag();
  }else{
   card=column();
   card.setPadding(dp(5),dp(5),dp(5),dp(5));
   card.setLayoutParams(new AbsListView.LayoutParams(-1,dp(292)));
   holder=new PosterTile();
   FrameLayout cover=new FrameLayout(this);
   cover.setBackground(gradient(0xff234554,0xff121f36,12));
   card.addView(cover,new LinearLayout.LayoutParams(-1,dp(222)));
   holder.initial=headline("A",48,0xff557f97);
   cover.addView(holder.initial,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
   holder.image=new ImageView(this);
   holder.image.setScaleType(ImageView.ScaleType.CENTER_CROP);
   cover.addView(holder.image,new FrameLayout.LayoutParams(-1,-1));
   holder.badge=headline("MOVIE",10,0xffbcfff1);
   holder.badge.setPadding(dp(8),dp(5),dp(8),dp(5));
   holder.badge.setBackground(shape(0xdd113b42));
   FrameLayout.LayoutParams badgeLocation=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.LEFT);
   badgeLocation.leftMargin=dp(7);badgeLocation.topMargin=dp(7);
   cover.addView(holder.badge,badgeLocation);
   holder.title=text("",15);
   holder.title.setTypeface(null,Typeface.BOLD);
   holder.title.setMaxLines(2);
   holder.title.setEllipsize(TextUtils.TruncateAt.END);
   holder.title.setPadding(dp(6),dp(4),dp(6),0);
   card.addView(holder.title,new LinearLayout.LayoutParams(-1,dp(43)));
   holder.category=text("",12);
   holder.category.setSingleLine(true);
   holder.category.setTextColor(0xffa7bbc9);
   holder.category.setEllipsize(TextUtils.TruncateAt.END);
   holder.category.setPadding(dp(6),0,dp(6),0);
   card.addView(holder.category);
   card.setTag(holder);
   card.setFocusable(false);
   card.setClickable(false);
   card.setBackground(shape(PANEL));
   card.setOnFocusChangeListener((v,focused)->{
    card.setBackground(shape(focused?0xff227d78:PANEL));
    card.setScaleX(focused?1.025f:1f);card.setScaleY(focused?1.025f:1f);
   });
  }
  holder.title.setText((favorites.contains(item.id)?"★  ":"")+item.name);
  holder.category.setText(item.category);
  holder.badge.setText(item.type.equals("movie")?"MOVIE":"TV SERIES");
  holder.initial.setText(item.name.isEmpty()?"A":item.name.substring(0,1).toUpperCase(Locale.ROOT));
  posters.bind(holder.image,item.artwork);
  return card;
 }
 void showMediaDetails(LibraryCore.Item item){
  LinearLayout panel=column();
  panel.setPadding(dp(18),dp(8),dp(18),dp(8));
  LinearLayout horizontal=new LinearLayout(this);
  panel.addView(horizontal);
  FrameLayout poster=new FrameLayout(this);
  poster.setBackground(gradient(0xff22667b,0xff142238,12));
  horizontal.addView(poster,new LinearLayout.LayoutParams(dp(155),dp(230)));
  TextView initial=headline(item.name.isEmpty()?"A":item.name.substring(0,1),52,0xff7b98b1);
  poster.addView(initial,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
  displayArtwork(poster,item.artwork);
  LinearLayout detail=column();
  detail.setPadding(dp(17),dp(4),0,0);
  horizontal.addView(detail,new LinearLayout.LayoutParams(0,-2,1));
  detail.addView(headline(item.name,24,Color.WHITE));
  detail.addView(text(item.type.equals("movie")?"MOVIE":"TV SERIES",13));
  TextView group=text(item.category,15);group.setTextColor(0xffa9c4d4);
  detail.addView(group);
  detail.addView(text(favorites.contains(item.id)?"★ In your favorites":"Add this title to favorites from Options.",13));
  new AlertDialog.Builder(this)
   .setTitle("AURORA  /  "+(item.type.equals("movie")?"MOVIES":"SERIES"))
   .setView(panel)
   .setPositiveButton(item.type.equals("movie")?"PLAY MOVIE":"VIEW EPISODES",(d,n)->open(item))
   .setNeutralButton("OPTIONS",(d,n)->actions(item))
   .setNegativeButton("CLOSE",null)
   .show();
 }
 void browse(){
  if(!store.hasLibrary()){loginScreen(false);return;}
  screen="browse";
  final int token=++browseToken;
  final String type=section,cat=category,search=query;
  final boolean showHidden=hiddenOnly,onlyFavorites=favOnly,isEditing=editing,hide=hideUnknown;
  final int requested=page;
  final Set<String> h=new HashSet<>(hidden),hc=new HashSet<>(categories),
      fav=new HashSet<>(favorites),lang=new HashSet<>(allowed),
      manual=new HashSet<>(shown),manualGroups=new HashSet<>(shownCategories);
  body.removeAllViews();
  body.addView(text("Finding your "+(type.equals("live")?"channels":type.equals("movie")?"movies":"TV shows")+"…",18));
  io.execute(()->{
   try{
    LibraryStore.Page result=store.page(type,cat,search,showHidden,onlyFavorites,h,hc,fav,lang,hide,
         manual,manualGroups,requested*PAGE_SIZE,PAGE_SIZE);
    final Map<String,String> brief=new HashMap<>();
    if(type.equals("live")&&!showHidden){
     for(int x=0;x<Math.min(18,result.rows.size());x++){
      LibraryCore.Item channel=result.rows.get(x);
      GuideEngine.Slot entry=epg.nowNext(channel);
      if(entry.hasData()){
       String now=entry.now==null?"Coming up: "+entry.next.title:"Now: "+entry.now.title;
       brief.put(channel.id,"\n"+now);
      }
     }
    }
    runOnUiThread(()->{
     if(isDestroyed()||token!=browseToken||!screen.equals("browse"))return;
     items=result.rows;guideSummary=brief;
     body.removeAllViews();
     LinearLayout heading=new LinearLayout(this);
     heading.setGravity(Gravity.CENTER_VERTICAL);
     String title=(isEditing?"EDIT  /  ":"")+(showHidden?"HIDDEN":onlyFavorites?"FAVORITES":
       type.equals("live")?"LIVE TV":type.equals("movie")?"MOVIES":"TV SHOWS");
     heading.addView(headline(title,25,Color.WHITE),new LinearLayout.LayoutParams(0,-2,1));
     Button filters=button("LANGUAGE",this::smart);heading.addView(filters,new LinearLayout.LayoutParams(dp(135),dp(52)));
     Button back=button("⌂ Home",this::home);heading.addView(back,new LinearLayout.LayoutParams(dp(125),dp(52)));
     body.addView(heading);

     // Users can search within the selected media type without going back to
     // the navigation menu. Search is performed by SQLite, not in-memory scans.
     LinearLayout controls=new LinearLayout(this);
     controls.setGravity(Gravity.CENTER_VERTICAL);
     body.addView(controls);
     Button selectCategory=button("CATEGORY  /  "+cat+"   ▾",this::chooseCategory);
     controls.addView(selectCategory,new LinearLayout.LayoutParams(0,dp(52),2));
     EditText searchField=new EditText(this);
     searchField.setTextColor(Color.WHITE);
     searchField.setHintTextColor(0xffa6bbc9);
     searchField.setSingleLine(true);
     searchField.setText(search);
     searchField.setHint(type.equals("live")?"Search channel":type.equals("movie")?"Search movie titles":"Search TV series");
     searchField.setTextSize(15);
     searchField.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
     LinearLayout.LayoutParams searchSize=new LinearLayout.LayoutParams(0,dp(52),2);
     searchSize.leftMargin=dp(10);controls.addView(searchField,searchSize);
     if(focusSearchNext){focusSearchNext=false;searchField.requestFocus();}
     Runnable applySearch=()->{query=searchField.getText().toString().trim();page=0;browse();};
     searchField.setOnEditorActionListener((v,action,event)->{
      if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH||
          (event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER&&event.getAction()==KeyEvent.ACTION_DOWN)){
       applySearch.run();return true;
      }
      return false;
     });
     Button go=button("SEARCH",applySearch);
     controls.addView(go,new LinearLayout.LayoutParams(dp(108),dp(52)));
     if(!search.isEmpty()){
      controls.addView(button("✕",()->{query="";page=0;browse();}),
          new LinearLayout.LayoutParams(dp(55),dp(52)));
     }
     if(isEditing)body.addView(text("Select a title to favorite, restore or hide it.",14));
     if(result.rows.isEmpty()){
      if(requested>0){page=0;browse();return;}
      body.addView(text("No titles match these filters. Change the category, search or language rules.",18));
      if(!search.isEmpty())body.addView(button("CLEAR SEARCH",()->{query="";page=0;browse();}));
      return;
     }
     subtitle.setText("Your entertainment, your selection");
     body.addView(text("Showing "+(requested*PAGE_SIZE+1)+"–"+
          (requested*PAGE_SIZE+result.rows.size())+(result.more?"+":"")+" matching titles",14));
     if(type.equals("movie")||type.equals("series")){
      GridView grid=new GridView(this);
      grid.setNumColumns(GridView.AUTO_FIT);
      grid.setColumnWidth(dp(180));
      grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
      grid.setHorizontalSpacing(dp(12));
      grid.setVerticalSpacing(dp(12));
      grid.setVerticalScrollBarEnabled(false);
      grid.setClipToPadding(false);
      grid.setPadding(dp(5),dp(10),dp(5),dp(16));
      grid.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
      grid.setSelector(shape(0xff257871));
      body.addView(grid,new LinearLayout.LayoutParams(-1,0,1));
      grid.setAdapter(new BaseAdapter(){
       public int getCount(){return result.rows.size();}
       public Object getItem(int n){return result.rows.get(n);}
       public long getItemId(int n){return n;}
       public View getView(int n,View reuse,ViewGroup parent){
        return posterGridCard(result.rows.get(n),reuse);
       }
      });
      grid.setOnItemClickListener((parent,v,n,id)->{
       LibraryCore.Item media=result.rows.get(n);
       if(editing)actions(media);else showMediaDetails(media);
      });
      grid.setOnItemLongClickListener((parent,v,n,id)->{actions(result.rows.get(n));return true;});
     }else{
      ListView list=new ListView(this);
      list.setDividerHeight(dp(5));
      body.addView(list,new LinearLayout.LayoutParams(-1,0,1));
      list.setAdapter(new BaseAdapter(){
       public int getCount(){return result.rows.size();}
       public Object getItem(int n){return result.rows.get(n);}
       public long getItemId(int n){return n;}
       public View getView(int n,View reuse,ViewGroup parent){
        LibraryCore.Item i=result.rows.get(n);
        TextView t=reuse instanceof TextView?(TextView)reuse:text("",17);
        t.setText((favorites.contains(i.id)?"★  ":"")+i.name+"   ·   "+i.category+nowNext(i));
        t.setMaxLines(3);t.setPadding(dp(14),dp(12),dp(14),dp(12));t.setBackground(shape(PANEL));
        return t;
       }
      });
      list.setSelector(shape(0xff27786c));
      list.setOnItemClickListener((parent,v,n,id)->{
       LibraryCore.Item picked=result.rows.get(n);if(editing)actions(picked);else open(picked);
      });
      list.setOnItemLongClickListener((parent,v,n,id)->{actions(result.rows.get(n));return true;});
     }
     LinearLayout navigation=new LinearLayout(this);
     if(requested>0)navigation.addView(button("◀ Previous",()->{page--;browse();}),
         new LinearLayout.LayoutParams(0,dp(55),1));
     if(result.more)navigation.addView(button("Next ▶",()->{page++;browse();}),
         new LinearLayout.LayoutParams(0,dp(55),1));
     body.addView(navigation);
    });
   }catch(Exception error){
    runOnUiThread(()->{
     if(isDestroyed()||token!=browseToken)return;
     body.removeAllViews();
     body.addView(text("Could not open this page: "+error.getClass().getSimpleName(),19));
     body.addView(button("Retry",this::browse));
    });
   }
  });
 }

 String nowNext(LibraryCore.Item i){return guideSummary.getOrDefault(i.id,"");}

  void chooseCategory(){
  final String requestedType=section;
  final boolean listingHidden=hiddenOnly;
  final Set<String> h=new HashSet<>(hidden), c=new HashSet<>(categories),
     fav=new HashSet<>(favorites), langs=new HashSet<>(allowed),
     manual=new HashSet<>(shown), visibleGroups=new HashSet<>(shownCategories);
  final boolean strict=hideUnknown;
  io.execute(()->{
   try{
    String[] groups=store.visibleCategoryNames(requestedType,listingHidden,h,c,fav,langs,strict,manual,visibleGroups);
    runOnUiThread(()->{
     if(isDestroyed()||!requestedType.equals(section)||listingHidden!=hiddenOnly)return;
     String[] names=new String[groups.length+1];names[0]="All";
     System.arraycopy(groups,0,names,1,groups.length);
     new AlertDialog.Builder(this)
       .setTitle(listingHidden?"Hidden categories · restore from here":"Only visible categories")
       .setItems(names,(d,n)->{category=names[n];page=0;browse();}).show();
    });
   }catch(Exception error){runOnUiThread(()->toast("Could not list visible categories"));}
  });
 }



 // -------------------------------------------------------------
 // HYBRID SMART EPG
 // -------------------------------------------------------------
 String displayTime(long timestamp){
  return android.text.format.DateFormat.format("h:mm a",timestamp).toString();
 }
 String programLabel(GuideEngine.Program p,String fallback){
  if(p==null)return fallback;
  String time=displayTime(p.start)+" – "+displayTime(p.end);
  return p.title+"\n"+time;
 }
 void tvGuide(){
  if(!store.hasLibrary()){loginScreen(false);return;}
  screen="guide";section="live";
  final int token=++browseToken;
  final String selectedCategory=category;
  final int selectedPage=guidePage,limit=18;
  final Set<String> h=new HashSet<>(hidden),c=new HashSet<>(categories),
    fav=new HashSet<>(favorites),lang=new HashSet<>(allowed),
    manual=new HashSet<>(shown),manualGroups=new HashSet<>(shownCategories);
  final boolean strict=hideUnknown;
  body.removeAllViews();
  body.addView(text("Preparing your TV guide…",19));
  io.execute(()->{
   try{
    LibraryStore.Page result=store.page("live",selectedCategory,"",false,false,
      h,c,fav,lang,strict,manual,manualGroups,selectedPage*limit,limit);
    final List<GuideEngine.Slot> listings=new ArrayList<>();
    for(LibraryCore.Item channel:result.rows)listings.add(epg.nowNext(channel));
    runOnUiThread(()->{
     if(isDestroyed()||token!=browseToken||!screen.equals("guide"))return;
     body.removeAllViews();
     LinearLayout heading=new LinearLayout(this);
     heading.setGravity(Gravity.CENTER_VERTICAL);
     heading.addView(headline("LIVE TV  /  GUIDE",25,Color.WHITE),new LinearLayout.LayoutParams(0,-2,1));
     heading.addView(button("Guide settings",this::guideSettings),new LinearLayout.LayoutParams(dp(156),dp(52)));
     heading.addView(button("Refresh",()->{scheduleGuideSync(true,true);toast("Refreshing guide sources in background");}),
       new LinearLayout.LayoutParams(dp(110),dp(52)));
     body.addView(heading);
     body.addView(button("CATEGORIES   /   "+selectedCategory+"  ▾",this::chooseGuideCategory));
     TextView helper=text("Live programmes shown in your device's local time · only visible channels appear",14);
     helper.setTextColor(0xffa8c4ce);body.addView(helper);
     // TV hardware reports different dp widths; keep the timeline horizontally
     // navigable instead of clipping programme and mapping controls.
     HorizontalScrollView horizontal=new HorizontalScrollView(this);
     horizontal.setHorizontalScrollBarEnabled(false);
     horizontal.setFillViewport(true);
     body.addView(horizontal,new LinearLayout.LayoutParams(-1,0,1));
     ScrollView scroll=new ScrollView(this);
     horizontal.addView(scroll,new FrameLayout.LayoutParams(dp(940),-1));
     LinearLayout feed=column();scroll.addView(feed);
     LinearLayout timeline=new LinearLayout(this);
     timeline.setPadding(0,dp(6),0,dp(6));timeline.setBackground(shape(0xff18374a));
     timeline.addView(guideColumn("CHANNEL",dp(178),ACCENT));
     timeline.addView(guideColumn("ON NOW  ·  "+displayTime(System.currentTimeMillis()),dp(305),ACCENT));
     timeline.addView(guideColumn("UP NEXT",dp(305),ACCENT));
     timeline.addView(guideColumn("OPTIONS",dp(100),ACCENT));
     feed.addView(timeline);
     if(result.rows.isEmpty()){
      feed.addView(text("No channels in this category. Check your Smart Library filter or restore a hidden category.",18));
     }
     final List<TextView[]> cells=new ArrayList<>();
     for(int n=0;n<result.rows.size();n++){
      LibraryCore.Item channel=result.rows.get(n);
      GuideEngine.Slot slot=listings.get(n);
      LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
      row.setPadding(0,dp(4),0,dp(4));
      row.setBackground(shape(n%2==0?0xff101e30:0xff142638));
      Button watch=button("▶  "+channel.name,()->open(channel));
      watch.setTextSize(15);watch.setGravity(Gravity.CENTER_VERTICAL|Gravity.LEFT);
      watch.setPadding(dp(12),0,dp(7),0);
      row.addView(watch,new LinearLayout.LayoutParams(dp(178),dp(87)));
      LinearLayout current=column();
      TextView now=text("",15);now.setTextColor(Color.WHITE);now.setMaxLines(3);
      current.addView(now,new LinearLayout.LayoutParams(-1,0,1));
      ProgressBar progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
      progress.setMax(1000);progress.setProgressTintList(ColorStateList.valueOf(ACCENT));
      current.addView(progress,new LinearLayout.LayoutParams(-1,dp(4)));
      LinearLayout.LayoutParams nowLp=new LinearLayout.LayoutParams(dp(305),dp(87));
      nowLp.setMargins(dp(8),0,dp(8),0);row.addView(current,nowLp);
      TextView next=text("",15);next.setMaxLines(3);next.setTextColor(0xffc2d3df);
      LinearLayout.LayoutParams nextLp=new LinearLayout.LayoutParams(dp(305),dp(87));
      row.addView(next,nextLp);
      row.addView(button("⋯  More",()->moreGuide(channel)),
        new LinearLayout.LayoutParams(dp(104),dp(60)));
      feed.addView(row);
      updateGuideCells(slot,now,next,progress);
      cells.add(new TextView[]{now,next});
     }
     LinearLayout controls=new LinearLayout(this);
     if(selectedPage>0)controls.addView(button("◀ Previous",()->{guidePage--;tvGuide();}),
       new LinearLayout.LayoutParams(0,dp(55),1));
     if(result.more)controls.addView(button("Next channels ▶",()->{guidePage++;tvGuide();}),
       new LinearLayout.LayoutParams(0,dp(55),1));
     body.addView(controls);
     int scheduled=0;
     for(GuideEngine.Slot item:listings)if(item.hasData())scheduled++;
     subtitle.setText(scheduled+" of "+result.rows.size()+" channels have cached schedule data · page "+(selectedPage+1));
     if(!result.rows.isEmpty()&&scheduled*4<result.rows.size())
      body.addView(button("MISSING PROGRAMMES?  GUIDE HEALTH & FREE EPG SOURCES",this::guideSettings));
     queueVisibleShortEpg(result.rows,listings,cells,token);
     scheduleGuideSync(false,true);
    });
   }catch(Exception e){
    runOnUiThread(()->{
     if(isDestroyed()||token!=browseToken||!screen.equals("guide"))return;
     body.removeAllViews();
     body.addView(text("Unable to open guide: "+e.getClass().getSimpleName(),18));
     body.addView(button("Retry guide",this::tvGuide));
    });
   }
  });
 }
 TextView guideColumn(String text,int width,int color){
  TextView title=headline(text,14,color);title.setGravity(Gravity.CENTER_VERTICAL);
  title.setPadding(dp(8),dp(10),dp(5),dp(10));
  title.setWidth(width);return title;
 }
 void updateGuideCells(GuideEngine.Slot slot,TextView now,TextView next,ProgressBar progress){
  if(slot.now==null){
   now.setText(slot.next==null?"No programme data available":"Schedule pending");
   progress.setProgress(0);
  }else{
   now.setText(programLabel(slot.now,""));
   long span=slot.now.end-slot.now.start;
   int percentage=span<=0?0:(int)(1000*Math.min(1d,Math.max(0d,
     (System.currentTimeMillis()-slot.now.start)/(double)span)));
   progress.setProgress(percentage);
  }
  next.setText(slot.next==null?"No upcoming listing":programLabel(slot.next,""));
 }
 void chooseGuideCategory(){
  final Set<String> h=new HashSet<>(hidden),c=new HashSet<>(categories),
    fav=new HashSet<>(favorites),lang=new HashSet<>(allowed),
    manual=new HashSet<>(shown),manualGroups=new HashSet<>(shownCategories);
  io.execute(()->{
   try{
    String[] names=store.visibleCategoryNames("live",false,h,c,fav,lang,hideUnknown,manual,manualGroups);
    runOnUiThread(()->{
     if(isDestroyed()||!screen.equals("guide"))return;
     String[] list=new String[names.length+1];list[0]="All";
     System.arraycopy(names,0,list,1,names.length);
     new AlertDialog.Builder(this).setTitle("Visible TV categories")
      .setItems(list,(d,n)->{category=list[n];guidePage=0;tvGuide();}).show();
    });
   }catch(Exception error){runOnUiThread(()->toast("Could not load guide categories"));}
  });
 }
 void queueVisibleShortEpg(List<LibraryCore.Item> channels,List<GuideEngine.Slot> snapshots,
         List<TextView[]> cells,int token){
  if(!prefs.getString("mode","").equals("xtream"))return;
  // Request only the first handful of visible channels; never the full catalog.
  List<LibraryCore.Item> needs=new ArrayList<>();
  List<TextView[]> targets=new ArrayList<>();
  for(int n=0;n<Math.min(24,channels.size());n++){
   if(!snapshots.get(n).hasData()){
    needs.add(channels.get(n));targets.add(cells.get(n));
   }
  }
  if(needs.isEmpty())return;
  shortEpgIO.execute(()->{
   try{
    String url=Vault.open(prefs.getString("url",""));
    String user=Vault.open(prefs.getString("user",""));
    String password=Vault.open(prefs.getString("pass",""));
    for(int n=0;n<needs.size();n++){
     if(Thread.currentThread().isInterrupted()||token!=browseToken||!screen.equals("guide"))break;
     LibraryCore.Item item=store.resolve(needs.get(n));
     if(epg.fetchShort(item,url,user,password)){
      GuideEngine.Slot updated=epg.nowNext(item);
      TextView[] targetsForRow=targets.get(n);
      runOnUiThread(()->{
       if(isDestroyed()||token!=browseToken||!screen.equals("guide"))return;
       // The progress indicator is owned by the row; update textual guide cells.
       targetsForRow[0].setText(programLabel(updated.now,"No programme data available"));
       targetsForRow[1].setText(programLabel(updated.next,"No upcoming listing"));
      });
     }
    }
   }catch(Exception ignored){}
  });
 }
 void guideSettings(){
  String[] options={
   "Guide health · diagnose missing programmes",
   "Enable public US + UK guide feeds",
   "Enable public Free TV + Sports feeds",
   "Disable public EPG presets",
   "Set independent XMLTV source A",
   "Set independent XMLTV source B",
   "Refresh all configured guide sources",
   "About Aurora Smart EPG"
  };
  new AlertDialog.Builder(this).setTitle("AURORA / SMART EPG")
   .setItems(options,(dialog,index)->{
    if(index==0){epgDiagnostics();return;}
    if(index==1){confirmGuidePreset("US + UK guide feeds",
      "https://raw.githubusercontent.com/acidjesuz/EPGTalk/master/US_guide.xml.gz",
      "https://raw.githubusercontent.com/acidjesuz/EPGTalk/master/UK_guide.xml.gz");return;}
    if(index==2){confirmGuidePreset("Free streaming + sports guide feeds",
      "https://raw.githubusercontent.com/acidjesuz/EPGTalk/master/FreeTV_guide.xml.gz",
      "https://raw.githubusercontent.com/acidjesuz/EPGTalk/master/Sports_guide.xml.gz");return;}
    if(index==3){confirmGuidePreset("Disable public guide feeds","","");return;}
    if(index==4||index==5){promptGuideUrl(index==4?"external1":"external2");return;}
    if(index==6){scheduleGuideSync(true,true);toast("Updating configured EPG feeds in the background");return;}
    new AlertDialog.Builder(this).setTitle("About Smart EPG")
     .setMessage("Aurora combines your provider's XMLTV and per-channel data with two custom XMLTV and two optional public feeds. A schedule can only be displayed when a real source supplies it. Unmatched stations can be mapped through their guide row.")
     .setPositiveButton("OK",null).show();
   }).show();
 }
 void confirmGuidePreset(String title,String url1,String url2){
  new AlertDialog.Builder(this).setTitle(title)
   .setMessage(url1.isEmpty()?
      "Disable the optional public feeds? Your own provider and custom XMLTV sources stay unchanged.":
      "Import public, independently maintained XMLTV data. Coverage varies by provider and channel name. These feeds are optional and update in the background.")
   .setPositiveButton("APPLY",(dialog,button)->{
    try{
     SharedPreferences.Editor edit=prefs.edit();
     for(String slot:new String[]{"external3","external4"})
      edit.remove("guide.updated."+slot).remove("guide.attempt."+slot).remove("guide.error."+slot);
     if(url1.isEmpty())edit.remove("guide.external3");else edit.putString("guide.external3",Vault.seal(url1));
     if(url2.isEmpty())edit.remove("guide.external4");else edit.putString("guide.external4",Vault.seal(url2));
     edit.apply();
     epgRefreshIO.execute(()->{
      epg.clearSource("external3");epg.clearSource("external4");
      if(!url1.isEmpty())scheduleGuideSync(true,false);
      else runOnUiThread(()->{if(!isDestroyed()&&screen.equals("guide"))tvGuide();});
     });
     toast(url1.isEmpty()?"Public guide feeds disabled":"Importing public guide feeds in background");
    }catch(Exception e){toast("Could not save the public guide settings");}
   }).setNegativeButton("CANCEL",null).show();
 }
 void epgDiagnostics(){
  AlertDialog waiting=new AlertDialog.Builder(this).setTitle("Guide health")
   .setMessage("Checking current programme coverage…").setCancelable(false).create();
  waiting.show();
  io.execute(()->{
   StringBuilder report=new StringBuilder();
   for(String source:new String[]{"provider","external1","external2","external3","external4","short"}){
    try{
     GuideEngine.SourceStats stats=epg.sourceStats(source);
     String label=source.equals("provider")?"Provider XMLTV":
       source.equals("short")?"Xtream fallback":
       source.equals("external1")?"Custom source A":
       source.equals("external2")?"Custom source B":
       source.equals("external3")?"Public source 1":"Public source 2";
     report.append(label).append(": ").append(stats.channelsWithPrograms)
       .append(" channel IDs / ").append(stats.futurePrograms).append(" available programmes");
     String error=prefs.getString("guide.error."+source,"");
     if(!error.isEmpty())report.append("\n").append(error);
     long last=prefs.getLong("guide.updated."+source,0);
     if(last>0)report.append("\nUpdated ").append(
       android.text.format.DateFormat.format("MMM d, h:mm a",last));
     report.append("\n\n");
    }catch(Exception e){report.append(source).append(": no diagnostics available\n\n");}
   }
   report.append("Feeds can contain programmes for channels not in your playlist. " +
     "If rows are blank, select More → Match this channel. " +
     "If a source shows zero programmes, refresh it or add an independent XMLTV feed.");
   runOnUiThread(()->{
    waiting.dismiss();if(isDestroyed())return;
    new AlertDialog.Builder(this).setTitle("GUIDE HEALTH").setMessage(report.toString())
     .setPositiveButton("REFRESH ALL",(d,n)->{scheduleGuideSync(true,true);toast("Guide refresh started");})
     .setNegativeButton("CLOSE",null).show();
   });
  });
 }
 void promptGuideUrl(String source){
  LinearLayout form=column();
  EditText field=field(form,"XMLTV URL (also supports .xml.gz)",false);
  try{
   String stored=prefs.getString("guide."+source,"");
   if(!stored.isEmpty())field.setText(Vault.open(stored));
  }catch(Exception ignored){}
  new AlertDialog.Builder(this).setTitle(source.equals("external1")?"Independent XMLTV A":"Independent XMLTV B")
   .setMessage("Enter a direct XMLTV URL. Leave empty to remove this source.")
   .setView(form).setPositiveButton("Save and refresh",(d,n)->
    setGuideUrl(source,field.getText().toString().trim()))
   .setNegativeButton("Cancel",null).show();
 }

 void setGuideUrl(String source,String url){
  if(!source.equals("external1")&&!source.equals("external2"))return;
  if(!url.isEmpty()&&!(url.startsWith("https://")||url.startsWith("http://"))){
   toast("Enter a direct HTTP or HTTPS XMLTV URL");return;
  }
  try{
   String old=prefs.getString("guide."+source,"");
   String previous=old.isEmpty()?"":Vault.open(old);
   boolean changed=!previous.equals(url);
   SharedPreferences.Editor edit=prefs.edit();
   if(changed)edit.remove("guide.updated."+source).remove("guide.attempt."+source);
   if(url.isEmpty())edit.remove("guide."+source);
   else edit.putString("guide."+source,Vault.seal(url));
   edit.apply();
   if(changed){
    // Deleting a large cached guide must NEVER block Fire TV navigation.
    epgRefreshIO.execute(()->{
     epg.clearSource(source);
     if(!url.isEmpty())scheduleGuideSync(true,false);
    });
   }else if(!url.isEmpty())scheduleGuideSync(true,false);
   if(url.isEmpty()){
    toast("Removed independent guide source");
    if(screen.equals("guide"))tvGuide();
   }else toast("Refreshing XMLTV in the background; TV remains usable");
  }catch(Exception e){toast("Unable to save guide source");}
 }
 void scheduleGuideSync(boolean force,boolean includeProvider){
  // Video playback takes priority on Fire TV's limited memory/CPU budget.
  if("player".equals(screen)||playbackScreen!=null)return;
  // One long-running source refresh at a time, separate from fast catalog queries.
  epgRefreshIO.execute(()->{
   boolean newData=false;
   String[] options=includeProvider?new String[]{"provider","external1","external2","external3","external4"}:
     new String[]{"external1","external2","external3","external4"};
   for(String source:options){
    if(Thread.currentThread().isInterrupted())break;
    long now=System.currentTimeMillis();
    if(!force&&now-prefs.getLong("guide.attempt."+source,0)<GuideEngine.REFRESH_INTERVAL)continue;
    String address="";
    try{
     if(source.equals("provider")){
      if(!prefs.getString("mode","").equals("xtream"))continue;
      String host=Vault.open(prefs.getString("url",""));
      String username=Vault.open(prefs.getString("user",""));
      String password=Vault.open(prefs.getString("pass",""));
      address=Provider.base(host)+"/xmltv.php?username="+Provider.enc(username)+
        "&password="+Provider.enc(password);
     }else{
      String stored=prefs.getString("guide."+source,"");
      if(stored.isEmpty())continue;
      address=Vault.open(stored);
     }
     prefs.edit().putLong("guide.attempt."+source,now).apply();
     int records=epg.importXmltv(address,source);
     prefs.edit().putLong("guide.updated."+source,System.currentTimeMillis())
       .putInt("guide.count."+source,records).remove("guide.error."+source).apply();
     newData=true;
    }catch(Exception e){
     if(Thread.currentThread().isInterrupted()){
      // Playback interrupted an EPG import intentionally: allow a later retry.
      prefs.edit().remove("guide.attempt."+source).apply();
      break;
     }
     // Deliberately never display exception text: provider URLs can include credentials.
     String reason="Guide source is unavailable or returned invalid XMLTV";
     String message=e.getMessage()==null?"":e.getMessage();
     if(message.startsWith("EPG returned HTTP "))reason=message;
     else if(message.contains("no current or upcoming programmes"))
      reason="No current or upcoming programmes in this feed";
     else if(message.contains("safe download limit")||message.contains("safe programme limit"))
      reason="Guide exceeded the device-safe size limit";
     else if(e instanceof java.net.SocketTimeoutException)
      reason="Guide source timed out";
     prefs.edit().putString("guide.error."+source,reason).apply();
    }
   }
   if(newData){
    runOnUiThread(()->{if(!isDestroyed()&&screen.equals("guide"))tvGuide();});
   }
  });
 }

 void moreGuide(LibraryCore.Item channel){
  new AlertDialog.Builder(this).setTitle(channel.name)
   .setItems(new String[]{"Full programme schedule","Match this channel to an EPG source",
     favorites.contains(channel.id)?"Remove favorite":"Add favorite","Hide channel"},
    (d,n)->{
     if(n==0){showChannelSchedule(channel);return;}
     if(n==1){chooseGuideMatch(channel);return;}
     if(n==2){
      if(!favorites.add(channel.id))favorites.remove(channel.id);
      save();tvGuide();return;
     }
     hidden.add(channel.id);shown.remove(channel.id);save();tvGuide();
    }).show();
 }
 void showChannelSchedule(LibraryCore.Item channel){
  io.execute(()->{
   List<GuideEngine.Program> initial=epg.schedule(channel,22);
   if(initial.isEmpty()&&prefs.getString("mode","").equals("xtream")){
    try{
     LibraryCore.Item resolved=store.resolve(channel);
     epg.fetchShort(resolved,Vault.open(prefs.getString("url","")),
       Vault.open(prefs.getString("user","")),Vault.open(prefs.getString("pass","")));
     initial=epg.schedule(channel,22);
    }catch(Exception ignored){}
   }
   final List<GuideEngine.Program> programs=initial;
   runOnUiThread(()->{
    if(isDestroyed())return;
    if(programs.isEmpty()){
     new AlertDialog.Builder(this).setTitle(channel.name)
      .setMessage("No reliable schedule available yet. Use Guide settings to add independent XMLTV, or manually match this channel.")
      .setPositiveButton("Match EPG",(d,n)->chooseGuideMatch(channel))
      .setNegativeButton("Close",null).show();
     return;
    }
    String[] titles=new String[programs.size()];
    for(int i=0;i<programs.size();i++){
     GuideEngine.Program p=programs.get(i);
     titles[i]=displayTime(p.start)+"–"+displayTime(p.end)+"  "+p.title;
    }
    new AlertDialog.Builder(this).setTitle(channel.name+"  /  PROGRAMMES")
     .setItems(titles,(d,n)->{
      GuideEngine.Program p=programs.get(n);
      new AlertDialog.Builder(this).setTitle(p.title)
       .setMessage(displayTime(p.start)+" – "+displayTime(p.end)+
        (p.description==null||p.description.isEmpty()?"":"\n\n"+p.description))
       .setPositiveButton("Watch channel",(a,b)->open(channel))
       .setNegativeButton("Back",null).show();
     }).setNegativeButton("Close",null).show();
   });
  });
 }

 void chooseGuideMatch(LibraryCore.Item item){
  io.execute(()->{
   List<GuideEngine.Match> candidates=epg.findCandidates(item.name,20);
   runOnUiThread(()->{
    if(isDestroyed())return;
    String[] options=new String[candidates.size()+2];
    for(int n=0;n<candidates.size();n++){
     GuideEngine.Match c=candidates.get(n);
     options[n]=c.name+"  ·  "+c.source;
    }
    options[candidates.size()]="Enter XMLTV channel ID manually";
    options[candidates.size()+1]="Remove manual guide match";
    new AlertDialog.Builder(this).setTitle("Match guide: "+item.name)
     .setItems(options,(d,index)->{
      if(index<candidates.size()){
       GuideEngine.Match choice=candidates.get(index);
       io.execute(()->{epg.setManual(item.id,choice.source,choice.id);
        runOnUiThread(()->{toast("Guide mapping saved");if(screen.equals("guide"))tvGuide();else browse();});});
      }else if(index==candidates.size())promptManualGuideMatch(item);
      else{
       io.execute(()->{epg.clearManual(item.id);
        runOnUiThread(()->{toast("Automatic guide matching restored");if(screen.equals("guide"))tvGuide();else browse();});});
      }
     }).show();
   });
  });
 }
 void promptManualGuideMatch(LibraryCore.Item item){
  String[] sources={"Provider XMLTV","Independent XMLTV A","Independent XMLTV B"};
  String[] keys={"provider","external1","external2"};
  new AlertDialog.Builder(this).setTitle("Choose guide source")
   .setItems(sources,(d,n)->{
    EditText id=new EditText(this);
    id.setTextColor(Color.WHITE);id.setHintTextColor(0xffaaaaaa);
    id.setHint("Exact XMLTV channel id");
    new AlertDialog.Builder(this).setTitle("XMLTV channel ID").setView(id)
     .setPositiveButton("Save",(a,b)->{
      String value=id.getText().toString().trim();
      if(value.isEmpty()){toast("Enter a channel ID");return;}
      io.execute(()->{
       epg.setManual(item.id,keys[n],value);
       runOnUiThread(()->{toast("Manual guide match saved");
        if(screen.equals("guide"))tvGuide();else browse();});
      });
     }).setNegativeButton("Cancel",null).show();
   }).show();
 }


 interface SectionChoice { void choose(String type); }
 void chooseLibrarySection(String title,SectionChoice action){
  new AlertDialog.Builder(this).setTitle(title)
   .setItems(new String[]{"Live TV","Movies","TV Shows"},(d,index)->
     action.choose(index==0?"live":index==1?"movie":"series"))
   .show();
 }
 void search(){
  chooseLibrarySection("SEARCH YOUR LIBRARY",type->{
   section=type;query="";category="All";page=0;
   hiddenOnly=false;favOnly=false;editing=false;
   focusSearchNext=true;browse();
  });
 }
 void actions(LibraryCore.Item i){
  boolean isHidden=!visible(i);
  String[] options={"★  "+(favorites.contains(i.id)?"Remove favorite":"Add favorite"),
     isHidden?"RESTORE  ·  Show even if filtered":"HIDE  ·  Remove from browsing",
     i.type.equals("live")?"Match programme guide":"Play / Open",
     i.type.equals("live")?"Play channel":"Show title details"};
  new AlertDialog.Builder(this).setTitle(i.name).setItems(options,(d,n)->{
   if(n==0){
    if(!favorites.add(i.id))favorites.remove(i.id);
    save();browse();return;
   }
   if(n==1){
    if(isHidden){
     hidden.remove(i.id);
     shown.add(i.id); // Show just this title; do not restore the entire category
    }else{
     hidden.add(i.id);shown.remove(i.id);
    }
    save();browse();return;
   }
   if(n==2&&i.type.equals("live")){chooseGuideMatch(i);return;}
   if(n==2){open(i);return;}
   if(n==3&&i.type.equals("live")){open(i);return;}
   new AlertDialog.Builder(this).setTitle(i.name).setMessage(i.category).setPositiveButton("OK",null).show();
  }).show();
 }

 void manage(){
  new AlertDialog.Builder(this).setTitle("EDIT YOUR LIBRARY")
   .setItems(new String[]{
     editing?"Finish editing":"Edit individual titles",
     "Hide / manage a category",
     "Smart language filter · All media",
     "Hidden titles · Browse and restore",
     "Restore an entire hidden category",
     "Manage manually restored categories",
     "Undo previous bulk filter change",
     "Return to your visible library"
   },(d,n)->{
    if(n==1){
     chooseLibrarySection("MANAGE CATEGORIES",type->{section=type;manageCategories();});
     return;
    }
    if(n==2){smart();return;}
    if(n==3){
     chooseLibrarySection("SHOW HIDDEN ITEMS FROM",type->{
      section=type;hiddenOnly=true;editing=true;favOnly=false;page=0;category="All";query="";browse();
     });
     return;
    }
    if(n==4){restoreCategory();return;}
    if(n==5){manageRestoredCategories();return;}
    if(n==6)undo();
    if(n==0)editing=!editing;
    if(n==7){hiddenOnly=false;editing=false;category="All";page=0;}
    browse();
   }).show();
 }
 void restoreCategory(){
  chooseLibrarySection("RESTORE A CATEGORY FROM",this::restoreCategoryFor);
 }
 void restoreCategoryFor(String type){
  final Set<String> h=new HashSet<>(hidden),c=new HashSet<>(categories),fav=new HashSet<>(favorites),
    lang=new HashSet<>(allowed),manual=new HashSet<>(shown),manualGroups=new HashSet<>(shownCategories);
  io.execute(()->{
   try{
    String[] groups=store.visibleCategoryNames(type,true,h,c,fav,lang,hideUnknown,manual,manualGroups);
    runOnUiThread(()->{
     if(isDestroyed())return;
     if(groups.length==0){toast("No hidden categories in this section");return;}
     new AlertDialog.Builder(this).setTitle("Restore an entire "+(type.equals("movie")?"movie":type.equals("series")?"TV show":"channel")+" category")
      .setItems(groups,(d,n)->{
       snapshot();
       String key=type+"|"+groups[n];
       shownCategories.add(key);categories.remove(key);
       save();section=type;category=groups[n];hiddenOnly=false;editing=false;
       favOnly=false;page=0;query="";browse();
      }).show();
    });
   }catch(Exception error){runOnUiThread(()->toast("Could not list hidden categories"));}
  });
 }
 void manageRestoredCategories(){
  String[] groups=shownCategories.toArray(new String[0]);
  Arrays.sort(groups);
  if(groups.length==0){toast("No categories manually restored");return;}
  new AlertDialog.Builder(this).setTitle("Restore automatic filtering")
   .setItems(groups,(d,n)->{snapshot();shownCategories.remove(groups[n]);save();category="All";page=0;browse();})
   .show();
 }
 void snapshot(){
  prefs.edit().putStringSet("undoHidden",new HashSet<>(hidden))
   .putStringSet("undoCategories",new HashSet<>(categories))
   .putStringSet("undoAllowed",new HashSet<>(allowed))
   .putStringSet("undoShown",new HashSet<>(shown))
   .putStringSet("undoShownCategories",new HashSet<>(shownCategories))
   .putBoolean("undoUnknown",hideUnknown).putBoolean("undo",true).apply();
 }
 void undo(){
  if(!prefs.getBoolean("undo",false)){toast("No bulk change to undo");return;}
  hidden=set("undoHidden");categories=set("undoCategories");allowed=set("undoAllowed");
  shown=set("undoShown");shownCategories=set("undoShownCategories");
  hideUnknown=prefs.getBoolean("undoUnknown",false);
  prefs.edit().putBoolean("undo",false).apply();save();category="All";page=0;
 }

 void manageCategories(){
  final String requestedType=section;
  io.execute(()->{
   try{
    String[] names=store.categories(requestedType);
    runOnUiThread(()->{
     if(isDestroyed())return;
     boolean[] checks=new boolean[names.length];
     for(int i=0;i<names.length;i++)checks[i]=categories.contains(requestedType+"|"+names[i]);
     new AlertDialog.Builder(this).setTitle("Hide categories · "+requestedType)
      .setMultiChoiceItems(names,checks,(d,n,checked)->checks[n]=checked)
      .setPositiveButton("Apply",(d,w)->{
       snapshot();
       for(int i=0;i<names.length;i++){
        String k=requestedType+"|"+names[i];
        if(checks[i])categories.add(k);else categories.remove(k);
       }
       save();category="All";page=0;browse();
      }).setNegativeButton("Cancel",null).show();
    });
   }catch(Exception error){runOnUiThread(()->toast("Could not load categories"));}
  });
 }


 void smart(){
  String[] codes={"en","fr","de","es","ar","pt","it","ru","hi","other"};
  String[] labels={
   "English · UK, US, Canada, NZ, Australia",
   "French","German","Spanish","Arabic","Portuguese","Italian","Russian","Hindi",
   "Other recognized languages",
   "Strict mode · also hide channels, movies and shows without language metadata"
  };
  boolean[] checks=new boolean[labels.length];
  for(int i=0;i<codes.length;i++)checks[i]=allowed.contains(codes[i]);
  checks[codes.length]=hideUnknown;
  new AlertDialog.Builder(this).setTitle("Smart Library · Live TV, Movies & Series")
   .setMultiChoiceItems(labels,checks,(d,n,checked)->checks[n]=checked)
   .setPositiveButton("PREVIEW CHANGES",(d,w)->{
    Set<String> selectedLanguages=new HashSet<>();
    for(int i=0;i<codes.length;i++)if(checks[i])selectedLanguages.add(codes[i]);
    boolean strict=checks[codes.length];
    final Set<String> h=new HashSet<>(hidden),c=new HashSet<>(categories),fav=new HashSet<>(favorites),
      manual=new HashSet<>(shown),manualGroups=new HashSet<>(shownCategories);
    final String previousScreen=screen;
    AlertDialog waiting=new AlertDialog.Builder(this).setTitle("Checking all media")
     .setMessage("Previewing the effect on Live TV, Movies and TV Shows…")
     .setCancelable(false).create();
    waiting.show();
    io.execute(()->{
     int[] counts={0,0,0,0,0};
     Set<String> visibleGroups=new HashSet<>(),hiddenGroups=new HashSet<>();
     try{
      store.forEach(i->{
       int kind=i.type.equals("live")?0:i.type.equals("movie")?1:i.type.equals("series")?2:-1;
       if(kind<0)return true;
       if(LibraryCore.language(i).equals("unknown"))counts[3]++;
       boolean visible=LibraryCore.visible(i,h,c,fav,selectedLanguages,strict,manual,manualGroups);
       String key=i.type+"|"+i.category;
       if(!visible){counts[kind]++;hiddenGroups.add(key);}
       else visibleGroups.add(key);
       return true;
      });
      for(String group:hiddenGroups)if(!visibleGroups.contains(group))counts[4]++;
     }catch(Exception error){
      runOnUiThread(()->{
       waiting.dismiss();
       if(!isDestroyed())toast("Unable to preview the language filter");
      });
      return;
     }
     runOnUiThread(()->{
      waiting.dismiss();
      if(isDestroyed())return;
      new AlertDialog.Builder(this).setTitle("LANGUAGE FILTER PREVIEW")
       .setMessage(String.format(Locale.US,
        "Hidden after applying:\n\n" +
        "Live TV: %,d channels\nMovies: %,d films\nTV Shows: %,d series\n\n" +
        "%,d categories will disappear from browsing.\n" +
        "%,d items have unknown language.\n\n" +
        "Favorites and manually restored items stay visible. " +
        "Strict mode hides unclassified titles across all three sections; turn it off to keep them. " +
        "No languages selected disables language filtering.",
        counts[0],counts[1],counts[2],counts[4],counts[3]))
       .setPositiveButton("APPLY TO ALL MEDIA",(confirm,button)->{
        snapshot();
        allowed=selectedLanguages;hideUnknown=strict;
        save();category="All";page=0;
        if(previousScreen.equals("guide"))tvGuide();
        else if(previousScreen.equals("home"))home();
        else browse();
       })
       .setNegativeButton("CANCEL",null).show();
     });
    });
   }).setNegativeButton("CANCEL",null).show();
 }

 EditText field(LinearLayout form,String hint,boolean secret){EditText e=new EditText(this);e.setHint(hint);e.setTextColor(Color.WHITE);e.setHintTextColor(0xff9caebe);e.setBackgroundTintList(ColorStateList.valueOf(ACCENT));e.setSingleLine();if(secret)e.setInputType(129);form.addView(e);return e;}

 void connect(){
  new AlertDialog.Builder(this).setTitle("MANAGE YOUR CONNECTION")
   .setItems(new String[]{"Change or add IPTV source","Refresh library from provider","Smart EPG settings","Playback diagnostics","Disconnect and clear this device"},(d,n)->{
    if(n==0){loginScreen(false);return;}
    if(n==1){refresh();return;}
    if(n==2){guideSettings();return;}
    if(n==3){showPlaybackDiagnostics();return;}
    new AlertDialog.Builder(this).setTitle("Remove connected provider?")
     .setMessage("This deletes the imported library, saved login and filters from this device.")
     .setPositiveButton("Disconnect",(a,b)->{
      generation++;browseToken++;loading=false;
      prefs.edit().clear().apply();
      store.clear();epg.clearAll();items.clear();guide.clear();guideIndex.clear();
      hidden.clear();categories.clear();favorites.clear();allowed.clear();shown.clear();shownCategories.clear();hideUnknown=false;
      page=0;category="All";query="";loginScreen(false);
     }).setNegativeButton("Cancel",null).show();
   }).show();
 }

 void showPlaybackDiagnostics(){
  String report=playbackDiagnostics.report();
  LinearLayout column=column();
  column.setPadding(dp(20),dp(6),dp(20),dp(6));
  TextView note=text("Safe to share for debugging. No IPTV credentials or streaming URLs are included.",13);
  note.setTextColor(0xff8fb4be);
  column.addView(note);
  ScrollView scrolling=new ScrollView(this);
  TextView details=text(report,15);
  details.setTextIsSelectable(true);
  details.setTypeface(Typeface.MONOSPACE);
  scrolling.addView(details);
  column.addView(scrolling,new LinearLayout.LayoutParams(-1,dp(340)));
  new AlertDialog.Builder(this).setTitle("AURORA / PLAYBACK HEALTH")
   .setView(column)
   .setPositiveButton("COPY REPORT",(d,n)->{
    android.content.ClipboardManager clipboard=
      (android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
    if(clipboard!=null)clipboard.setPrimaryClip(
      ClipData.newPlainText("AuroraTV playback diagnostics",report));
    toast("Playback report copied to clipboard");
   })
   .setNegativeButton("CLOSE",null).show();
 }
 void refresh(){
  String mode=prefs.getString("mode","");
  if(mode.isEmpty()){loginScreen(false);return;}
  try{
   importSource(mode,Vault.open(prefs.getString("url","")),
     Vault.open(prefs.getString("user","")),Vault.open(prefs.getString("pass","")));
  }catch(Exception e){
   toast("Please sign into your provider again");
   loginScreen(false);
  }
 }


 void importSource(String mode,String url,String user,String pass){
  if(loading){toast("An import is already running");return;}
  loading=true;
  final int token=++generation;
  loadingScreen("SETTING UP YOUR LIBRARY","Connecting to your IPTV provider…");
  io.execute(()->{
   try{
    int count;
    final String[] discoveredGuide={null};
    try(LibraryStore.Writer writer=store.writer()){
     if(mode.equals("xtream")){
      count=Provider.xtreamStream(url,user,pass,writer,(stage,done)->{
       String phase=stage.equals("live")?"LIVE TELEVISION":stage.equals("vod")?"MOVIES":"TV SERIES";
       status(phase+"  ·  "+String.format(Locale.US,"%,d titles imported",done));
      });
     }else{
      status("Reading M3U playlist…");
      count=Provider.m3uStream(url,writer,(stage,done)->
        status(String.format(Locale.US,"%,d playlist entries imported",done)),
        xmltv->discoveredGuide[0]=xmltv);
     }
     if(count==0)throw new IOException("No supported titles were returned by this provider.");
     status("Finishing your library index…");
     writer.commit();
    }
    String address=Vault.seal(url),account=Vault.seal(user),secret=Vault.seal(pass);
    boolean changedSource=true;
    try{
     changedSource=!mode.equals(prefs.getString("mode",""))||
       !url.equals(Vault.open(prefs.getString("url","")))||
       !user.equals(Vault.open(prefs.getString("user","")))||
       !pass.equals(Vault.open(prefs.getString("pass","")));
    }catch(Exception ignored){}
    if(changedSource){
     epg.clearProvider(); // Previous account's programme IDs must not leak into this account.
     prefs.edit().remove("guide.attempt.provider").remove("guide.updated.provider").apply();
    }
    if(discoveredGuide[0]!=null&&!prefs.contains("guide.external1")){
     prefs.edit().putString("guide.external1",Vault.seal(discoveredGuide[0])).apply();
    }
    runOnUiThread(()->{
     if(isDestroyed()||token!=generation)return;
     prefs.edit().putString("mode",mode).putString("url",address)
       .putString("user",account).putString("pass",secret).remove("items").apply();
     loading=false;items.clear();page=0;category="All";query="";
     hiddenOnly=false;favOnly=false;editing=false;
     shell();home();
     // EPG loads separately in the background after the home screen appears.
    });
   }catch(Exception error){
    runOnUiThread(()->{
     if(isDestroyed()||token!=generation)return;
     loading=false;
     String message=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();
     message=message.replaceAll("(?i)(username|password)=[^&\\s]+","$1=***");
     toast("Import failed: "+message);
     if(store.hasLibrary()){shell();home();}
     else loginScreen(mode.equals("m3u"));
    });
   }
  });
 }

 void loadGuide(String url){setGuideUrl("external1",url);}

  void open(LibraryCore.Item chosen){
  io.execute(()->{
   try{
    LibraryCore.Item source=store.resolve(chosen);
    if(source.type.equals("series")){
     runOnUiThread(()->{if(!isDestroyed()&&subtitle!=null)subtitle.setText("Loading episodes…");});
     List<LibraryCore.Item> eps=Provider.episodes(source);
     runOnUiThread(()->{
      if(isDestroyed())return;
      String[] names=new String[eps.size()];
      for(int n=0;n<eps.size();n++)names[n]=eps.get(n).name;
      new AlertDialog.Builder(this).setTitle(source.name)
       .setItems(names,(d,n)->play(eps.get(n))).show();
     });
    }else runOnUiThread(()->{if(!isDestroyed())play(source);});
   }catch(Exception error){
    runOnUiThread(()->toast("Could not open this title. Check your provider connection."));
   }
  });
 }


 /**
  * Stop memory-heavy EPG/short-guide downloads while decoding video.
  * Interrupted XMLTV imports roll back their DB transaction and retry later.
  */
 void pauseBackgroundGuidesForPlayback(){
  epgRefreshIO.shutdownNow();
  shortEpgIO.shutdownNow();
  epgRefreshIO=Executors.newSingleThreadExecutor();
  shortEpgIO=Executors.newSingleThreadExecutor();
  posters.clearMemory();
 }
 void play(LibraryCore.Item media){
  if(media==null||media.url==null||media.url.isEmpty()){
   toast("No playable stream URL is available");return;
  }
  if(playbackScreen!=null)release();
  screenBeforePlayer=screen;
  screen="player";
  playing=media;
  pauseBackgroundGuidesForPlayback();
  try{
   long resume=media.type.equals("live")?0:prefs.getLong("resume."+media.id,0);
   playbackScreen=new PlaybackScreen(this,media,resume,
     playbackDiagnostics,this::backToLibraryFromPlayer);
  }catch(Exception ex){
   playbackDiagnostics.event("Player creation failed: "+ex.getClass().getSimpleName());
   toast("Could not start video. See playback diagnostics.");
   release();backToLibraryFromPlayer();
  }
 }
 void backToLibraryFromPlayer(){
  String previous=screenBeforePlayer;
  release();
  shell();
  if(previous.equals("guide"))tvGuide();
  else if(previous.equals("home"))home();
  else browse();
 }
 void release(){
  if(playbackScreen!=null){
   PlaybackScreen active=playbackScreen;
   playbackScreen=null;
   long position=active.close();
   if(playing!=null&&!playing.type.equals("live"))
    prefs.edit().putLong("resume."+playing.id,position).apply();
  }
  playing=null;
 }
 @Override public boolean dispatchKeyEvent(KeyEvent event){
  if(playbackScreen!=null&&playbackScreen.handleKey(event))return true;
  return super.dispatchKeyEvent(event);
 }
 @Override public boolean onKeyDown(int key,KeyEvent event){
  if(key==KeyEvent.KEYCODE_MENU&&playbackScreen==null){manage();return true;}
  return super.onKeyDown(key,event);
 }

 @Override public void onBackPressed(){
  if(playbackScreen!=null){
   if(playbackScreen.controlsVisible())playbackScreen.hideControls();
   else backToLibraryFromPlayer();
  }else if(screen.equals("browse")||screen.equals("guide")){
   if(editing||hiddenOnly||favOnly||!query.isEmpty()){
    editing=false;hiddenOnly=false;favOnly=false;query="";page=0;
   }
   home();
  }else if(!loading)super.onBackPressed();
 }
 @Override protected void onStop(){
  // Never keep a hardware video decoder or wake lock running in background.
  if(playbackScreen!=null){
   release();
   restoreLibraryOnResume=true;
  }
  super.onStop();
 }
 @Override protected void onResume(){
  super.onResume();
  if(restoreLibraryOnResume){
   restoreLibraryOnResume=false;
   shell();home();
  }
 }
 @Override public void onTrimMemory(int level){
  super.onTrimMemory(level);
  if(level>=TRIM_MEMORY_RUNNING_LOW){
   posters.clearMemory();
   if(playbackDiagnostics!=null)playbackDiagnostics.event("Memory pressure level="+level);
  }
 }
 @Override public void onLowMemory(){
  super.onLowMemory();
  posters.clearMemory();
  if(playbackDiagnostics!=null)playbackDiagnostics.event("Android low-memory callback");
 }
 @Override protected void onDestroy(){
  generation++;browseToken++;
  release();io.shutdownNow();posters.close();epgRefreshIO.shutdownNow();shortEpgIO.shutdownNow();store.close();epg.close();
  super.onDestroy();
 }
 void toast(String s){if(!isDestroyed())Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
 // Keep large encrypted libraries out of SharedPreferences (which loads values into memory).
 void writeLibrary(List<LibraryCore.Item> result)throws Exception{
  File target=getFileStreamPath("library.enc"), temp=getFileStreamPath("library.enc.tmp");
  try(FileOutputStream stream=new FileOutputStream(temp)){
   stream.write(Vault.seal(encode(result)).getBytes("UTF-8"));stream.getFD().sync();
  }
  if(!temp.renameTo(target))throw new IOException("Could not save library");
 }
 List<LibraryCore.Item> readLibrary()throws Exception{
  File file=getFileStreamPath("library.enc");
  if(!file.exists())return decode(Vault.open(prefs.getString("items","")));
  try(FileInputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){
   byte[] buffer=new byte[16384];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
   return decode(Vault.open(out.toString("UTF-8")));
  }
 }
 static String encode(List<LibraryCore.Item> list)throws Exception{JSONArray a=new JSONArray();for(LibraryCore.Item i:list){JSONObject o=new JSONObject();o.put("id",i.id).put("name",i.name).put("category",i.category).put("url",i.url).put("type",i.type).put("epgId",i.epgId).put("language",i.language);a.put(o);}return a.toString();}
 static List<LibraryCore.Item> decode(String json)throws Exception{JSONArray a=new JSONArray(json);List<LibraryCore.Item> out=new ArrayList<>();for(int n=0;n<a.length();n++){JSONObject o=a.getJSONObject(n);out.add(new LibraryCore.Item(o.getString("id"),o.getString("name"),o.getString("category"),o.getString("url"),o.getString("type"),o.optString("epgId"),o.optString("language")));}return out;}
}
