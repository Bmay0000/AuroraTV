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
 ImageView cinematicBackdrop,cinematicIncoming; TextView cinematicTitle,cinematicSubtitle; FrameLayout cinematicArtwork; Runnable pendingCinematic; int cinematicRevision=0;
 final int BG=0xff070c17,PANEL=0xff142033,ACCENT=0xff5debd0,MUTED=0xff9badc1,SURFACE=0xff101b2d;
 LinearLayout root,body,nav;TextView subtitle;SharedPreferences prefs;ExecutorService io=Executors.newSingleThreadExecutor(),catalogReadIO=Executors.newFixedThreadPool(2),importIO=Executors.newSingleThreadExecutor();List<LibraryCore.Item> items=new ArrayList<>();List<Provider.Program> guide=new ArrayList<>();Map<String,List<Provider.Program>> guideIndex=new HashMap<>();Set<String> hidden,categories,favorites,allowed,shown,shownCategories;boolean hideUnknown;String section="live",query="",category="All";boolean editing=false,favOnly=false,hiddenOnly=false,browseAll=false;LibraryCore.Item selected,playing;PlaybackScreen playbackScreen;PlaybackDiagnostics playbackDiagnostics;boolean restoreLibraryOnResume=false;boolean loading=false;int generation=0;volatile int browseToken=0;int page=0;static final int PAGE_SIZE=200;LibraryStore store;GuideEngine epg;int guidePage=0;String guideFilter="North America",guideQuery="";String guideSelectedId="";ExecutorService epgRefreshIO=Executors.newSingleThreadExecutor(),shortEpgIO=Executors.newSingleThreadExecutor();Map<String,String> guideSummary=new HashMap<>();final Map<String,GuideEngine.Slot> guideSlotCache=new java.util.concurrent.ConcurrentHashMap<>();final ExecutorService guideDirectoryIO=Executors.newSingleThreadExecutor();int guideCategorySequence=0;long guideSlotCacheAt=0;String screen="login",screenBeforePlayer="home";TextView loadingStatus;PosterLoader posters;PreviewWindow livePreview;GuidePreviewPane guidePreview;boolean focusSearchNext=false;Map<String,Button> navButtons=new LinkedHashMap<>();Handler uiHandler=new Handler(Looper.getMainLooper());Runnable pendingGuideUpdate;boolean guideSyncBusy=false;long navigationStartedAt;int navigationMarkedToken=-1;
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
 void save(){
  prefs.edit().putStringSet("hidden",hidden).putStringSet("categories",categories)
   .putStringSet("favorites",favorites).putStringSet("allowed",allowed)
   .putStringSet("shown",shown).putStringSet("shownCategories",shownCategories)
   .putBoolean("unknown",hideUnknown).putBoolean("smartFilterV2",true).apply();
  // Favorites, hides and language filters immediately retire old browse results.
  store.invalidateBrowseCache();
 }
 /** A fresh section must not sit behind minutes of stale catalog scans.
  * Interrupt abandoned jobs; the SQLite readers check interruption and close
  * their cursors. Playback, provider import and EPG have separate executors. */
 int beginNavigationRead(){
  int current=++browseToken;
  navigationStartedAt=SystemClock.elapsedRealtime();
  if(posters!=null)posters.beginSection();
  ExecutorService obsolete=catalogReadIO;
  catalogReadIO=Executors.newFixedThreadPool(2);
  obsolete.shutdownNow();
  return current;
 }
 void markLoad(String sectionName,int token){
  if(token!=browseToken||navigationMarkedToken==token)return;
  navigationMarkedToken=token;
  long elapsed=Math.max(0,SystemClock.elapsedRealtime()-navigationStartedAt);
  prefs.edit().putLong("nav.last."+sectionName+".ms",elapsed).apply();
 }
 int dp(int v){return (int)(v*getResources().getDisplayMetrics().density);}
 TvLayout tv(){
   android.util.DisplayMetrics dm=getResources().getDisplayMetrics();
   float density=Math.max(.5f,dm.density);
   return TvLayout.of((int)(dm.widthPixels/density),(int)(dm.heightPixels/density),
      prefs==null?"compact":prefs.getString("display.density","compact"));
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
 Button textAction(String title,Runnable action){
  Button b=button(title,action);
  b.setBackground(rounded(Color.TRANSPARENT,8,0));
  b.setTextColor(0xffa8e4da);
  b.setOnFocusChangeListener((v,focused)->{
   b.setBackground(rounded(focused?0xff1a4143:Color.TRANSPARENT,8,
     focused?ACCENT:0));
   b.setTextColor(focused?ACCENT:0xffa8e4da);
  });
  return b;
 }
 TextView kicker(String value){
   TextView t=text(value.toUpperCase(Locale.ROOT),12);
   t.setTextColor(ACCENT);t.setLetterSpacing(.13f);
   t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;
 }

 void start(){
  // Repair v0.4 staged imports. Do not claim Movies/TV Shows are empty
  // merely because an interrupted import left only Live TV in the database.
  if(store.hasLibrary()&&!prefs.getString("import.pending","").isEmpty()){
   loadingScreen("RESTORING YOUR COMPLETE LIBRARY",
     "Updating Live TV, Movies and TV Shows together. Your existing catalog stays safe.");
   refresh();
   return;
  }
  if(store.hasLibrary()){shell();home();}
  else restoreAccountOrLogin();
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
   if(isDestroyed())return;
   if(screen.equals("loading")&&loadingStatus!=null)
    loadingStatus.setText(message);
   else if(loading&&subtitle!=null)subtitle.setText(message);
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
 void addNav(String key,String caption,Runnable action){
  Button navItem=button(caption,()->{action.run();refreshSidebar();});
  navItem.setTag(key);
  navItem.setGravity(Gravity.CENTER);
  navItem.setTextSize(TvLayout.clamp(tv().bodySize()-2,12,16));
  navItem.setAllCaps(false);
  navItem.setPadding(dp(11),0,dp(11),0);
  navItem.setLetterSpacing(.04f);
  LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-2,dp(TvLayout.clamp(tv().navRow-5,29,41)));
  params.setMargins(dp(3),dp(2),dp(3),dp(2));
  navItem.setLayoutParams(params);
  navItem.setOnFocusChangeListener((v,focused)->styleNav(navItem,focused));
  navButtons.put(key,navItem);
  nav.addView(navItem,params);
 }
 void styleNav(Button item,boolean focused){
  boolean selected=isNavActive(String.valueOf(item.getTag()));
  // AuroraTV's own high-contrast pill/underline language; not a recreation
  // of any proprietary streaming-service navigation.
  int fill=focused?ACCENT:selected?0xff284b4b:Color.TRANSPARENT;
  int outline=focused?ACCENT:selected?0xff438d85:Color.TRANSPARENT;
  item.setBackground(rounded(fill,12,outline));
  item.setTextColor(focused?BG:selected?0xffb7ffed:0xffb3c4d5);
  item.setTypeface(Typeface.create("sans-serif-medium",
      selected||focused?Typeface.BOLD:Typeface.NORMAL));
  item.animate().scaleX(focused?1.045f:1f).scaleY(focused?1.045f:1f)
      .setDuration(100).start();
 }
 boolean isNavActive(String key){
  if(key.equals("home"))return screen.equals("home");
  if(key.equals("guide"))return screen.equals("guide");
  if(key.equals("favorites"))return screen.equals("browse")&&favOnly;
  if(key.equals("library"))return screen.equals("browse")&&editing;
  return (screen.equals("browse")||screen.equals("discover"))&&!favOnly&&!editing&&key.equals(section);
 }
 void refreshSidebar(){
  for(Button item:navButtons.values())styleNav(item,item.isFocused());
 }
 void shell(){
  screen="home";
  TvLayout metrics=tv();
  root=column();
  root.setClipChildren(true);root.setClipToPadding(true);
  root.setBackground(gradient(0xff070b15,0xff0b1723,0));
  root.setPadding(dp(Math.max(6,metrics.marginX-4)),dp(2),
    dp(Math.max(6,metrics.marginX-4)),dp(2));
  setContentView(root);

  // One persistent navigation row. Previous versions had a separate
  // branding/header plus a second tab bar and consumed ~20% of the TV.
  LinearLayout top=new LinearLayout(this);
  top.setGravity(Gravity.CENTER_VERTICAL);
  top.setClipChildren(true);top.setClipToPadding(true);
  root.addView(top,new LinearLayout.LayoutParams(-1,dp(
      TvLayout.clamp(metrics.heightDp/12,40,53))));
  TextView mark=brand(TvLayout.clamp(metrics.widthDp/48,19,29));
  LinearLayout.LayoutParams brandSize=new LinearLayout.LayoutParams(-2,-2);
  brandSize.setMargins(dp(2),0,dp(12),0);
  top.addView(mark,brandSize);

  HorizontalScrollView navScroll=new HorizontalScrollView(this);
  navScroll.setHorizontalScrollBarEnabled(false);
  navScroll.setClipChildren(true);
  navScroll.setFillViewport(false);
  top.addView(navScroll,new LinearLayout.LayoutParams(0,-1,1));
  nav=new LinearLayout(this);
  nav.setOrientation(LinearLayout.HORIZONTAL);
  nav.setGravity(Gravity.CENTER_VERTICAL);
  nav.setClipChildren(false);
  navScroll.addView(nav,new ViewGroup.LayoutParams(-2,-1));
  navButtons.clear();
  addNav("home","Home",this::home);
  addNav("guide","Live TV & Guide",()->{
   section="live";category="All";guidePage=0;guideFilter="North America";
   guideQuery="";tvGuide();
  });
  addNav("movie","Movies",()->{
   section="movie";favOnly=false;hiddenOnly=false;editing=false;
   page=0;category="All";query="";browseAll=false;browse();
  });
  addNav("series","TV Shows",()->{
   section="series";favOnly=false;hiddenOnly=false;editing=false;
   page=0;category="All";query="";browseAll=false;browse();
  });
  addNav("favorites","My List",()->{
   favOnly=true;hiddenOnly=false;editing=false;page=0;browse();
  });
  addNav("search","Search",this::search);
  addNav("library","Edit Library",this::manage);
  Button settings=button("⚙",this::connect);
  settings.setContentDescription("AuroraTV settings");
  settings.setTextSize(19);
  LinearLayout.LayoutParams gear=new LinearLayout.LayoutParams(dp(44),dp(37));
  gear.leftMargin=dp(5);
  top.addView(settings,gear);
  subtitle=text("",12);
  subtitle.setVisibility(View.GONE);
  View edge=new View(this);edge.setBackgroundColor(0xff1e3944);
  root.addView(edge,new LinearLayout.LayoutParams(-1,dp(1)));
  body=column();
  body.setClipChildren(true);body.setClipToPadding(true);
  body.setPadding(dp(2),dp(2),dp(2),dp(2));
  root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
  refreshSidebar();
 }

 boolean visible(LibraryCore.Item i){return LibraryCore.visible(i,hidden,categories,favorites,allowed,hideUnknown,shown,shownCategories);}
 void home(){
  clearCinematic();
  stopGuidePreview();
  if(!store.hasLibrary()){loginScreen(false);return;}
  screen="home";refreshSidebar();
  final int token=beginNavigationRead();
  final Set<String> h=new HashSet<>(hidden),hc=new HashSet<>(categories),
      fav=new HashSet<>(favorites),lang=new HashSet<>(allowed),
      manual=new HashSet<>(shown),groups=new HashSet<>(shownCategories);
  final boolean strict=hideUnknown;
  body.removeAllViews();
  ScrollView scroll=new ScrollView(this);
  scroll.setVerticalScrollBarEnabled(false);
  scroll.setClipChildren(true);scroll.setClipToPadding(true);
  body.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
  LinearLayout feed=column();
  feed.setClipChildren(true);feed.setClipToPadding(true);
  feed.setPadding(dp(3),dp(1),dp(4),dp(12));
  scroll.addView(feed,new ScrollView.LayoutParams(-1,-2));

  // Everything above the international divider has positive English or
  // North American evidence; unknown-language titles never become the hero.
  LinearLayout latest=column(),continueRow=column(),englishMovies=column(),
    americanTV=column(),englishSeries=column(),genres=column(),
    myList=column(),unknown=column(),foreign=column();
  LinearLayout movieTrending=column(),seriesTrending=column();
   feed.addView(latest);feed.addView(movieTrending);feed.addView(seriesTrending);
   fetchTrendingShelf(token,"movie",movieTrending,h,hc,fav,lang,strict,manual,groups);
   fetchTrendingShelf(token,"series",seriesTrending,h,hc,fav,lang,strict,manual,groups);
  feed.addView(continueRow);
  feed.addView(englishMovies);
  feed.addView(americanTV);
  feed.addView(englishSeries);
  feed.addView(genres);
  feed.addView(myList);
  feed.addView(unknown);
  feed.addView(foreign);

  showShelfPlaceholder(latest,"NEW & RECENT • ENGLISH MOVIES");
  fetchRecentMovies(token,latest,unknown,foreign,h,hc,fav,lang,strict,manual,groups);
  fetchLanguageShelf(token,"movie",englishMovies,"ENGLISH MOVIES",false,
    h,hc,fav,lang,strict,manual,groups);
  fetchNorthAmericanShelf(token,americanTV,h,hc,fav,lang,strict,manual,groups);
  fetchLanguageShelf(token,"series",englishSeries,"ENGLISH TV SHOWS",false,
    h,hc,fav,lang,strict,manual,groups);
  fetchGenreShelves(token,genres,h,hc,fav,lang,strict,manual,groups);
  List<String> history=Arrays.asList(prefs.getString("recent.items","").split(","));
  if(!history.isEmpty()&&!history.get(0).isEmpty())
   fetchPersonalShelf(token,"CONTINUE WATCHING",continueRow,history,
     h,hc,fav,lang,strict,manual,groups);
  if(!fav.isEmpty())
   fetchPersonalShelf(token,"MY LIST",myList,new ArrayList<>(fav),
     h,hc,fav,lang,strict,manual,groups);
  fetchLanguageShelf(token,"movie",foreign,"INTERNATIONAL MOVIES",true,
     h,hc,fav,lang,strict,manual,groups);

  if(pendingGuideUpdate!=null)uiHandler.removeCallbacks(pendingGuideUpdate);
  pendingGuideUpdate=()->{
   if(!isDestroyed()&&"home".equals(screen)&&!loading&&playbackScreen==null)
    scheduleGuideSync(false,true);
  };
  uiHandler.postDelayed(pendingGuideUpdate,90000L);
 }

 void fetchRecentMovies(int token,LinearLayout recentArea,LinearLayout unknownArea,
      LinearLayout internationalArea,Set<String> h,Set<String> hc,Set<String> fav,
      Set<String> langs,boolean strict,Set<String> manual,Set<String> groups){
  catalogReadIO.execute(()->{
   try{
    LibraryStore.RecentMovies result=store.recentMovies(h,hc,fav,langs,strict,manual,groups);
    runOnUiThread(()->{
     if(isDestroyed()||token!=browseToken||(!"home".equals(screen)&&!"discover".equals(screen)))return;
     markLoad("home".equals(screen)?"home":"movie",token);
     recentArea.removeAllViews();
     if(!result.english.isEmpty())
      homeShelf(recentArea,"NEW & RECENT • ENGLISH MOVIES","movie",result.english);
     // No speculative "New" label on films lacking an actual release year.
     // Unknown and foreign films always appear AFTER English shelves.
     if(!result.unverified.isEmpty())
      homeShelf(unknownArea,"MORE RECENT • LANGUAGE UNVERIFIED","movie",result.unverified);
     if(!result.international.isEmpty()){
      homeShelf(internationalArea,"RECENT INTERNATIONAL MOVIES","movie",result.international);
     }
    });
   }catch(Exception err){
    runOnUiThread(()->{
     if(!isDestroyed()&&token==browseToken&&"home".equals(screen))
      recentArea.removeAllViews();
    });
   }
  });
 }

 void fetchLanguageShelf(int token,String type,LinearLayout target,String label,
       boolean international,Set<String> h,Set<String> hc,Set<String> fav,
       Set<String> lang,boolean strict,Set<String> manual,Set<String> groups){
  catalogReadIO.execute(()->{
   try{
    List<LibraryCore.Item> matches=international?
      store.featuredInternational(type,24,h,hc,fav,lang,strict,manual,groups):
      store.featuredEnglish(type,24,h,hc,fav,lang,strict,manual,groups);
    runOnUiThread(()->{
     if(isDestroyed()||token!=browseToken||(!"home".equals(screen)&&!"discover".equals(screen)))return;
     if(matches.isEmpty())return;
     if("discover".equals(screen))markLoad(type,token);
     homeShelf(target,label,type,matches);
    });
   }catch(Exception ignored){}
  });
 }

 void fetchNorthAmericanShelf(int token,LinearLayout target,Set<String> h,
      Set<String> hc,Set<String> fav,Set<String> lang,boolean strict,
      Set<String> manual,Set<String> groups){
  catalogReadIO.execute(()->{
   try{
    List<LibraryCore.Item> channels=store.channelDirectory("North America","",
       h,hc,fav,lang,strict,manual,groups);
    if(channels.size()>28)channels=new ArrayList<>(channels.subList(0,28));
    final List<LibraryCore.Item> result=channels;
    runOnUiThread(()->{
     if(isDestroyed()||token!=browseToken||!"home".equals(screen))return;
     if(!result.isEmpty())
      homeShelf(target,"NORTH AMERICAN LIVE TV","live",result);
    });
   }catch(Exception ignored){}
  });
 }

 void fetchGenreShelves(int token,LinearLayout target,
      Set<String> h,Set<String> hc,Set<String> fav,Set<String> langs,boolean strict,
      Set<String> manual,Set<String> groups){
  catalogReadIO.execute(()->{
   try{
    class Genre{
     final LibraryStore.GenreCategory category;
     final List<LibraryCore.Item> movies;
     Genre(LibraryStore.GenreCategory c,List<LibraryCore.Item> list){
      category=c;movies=list;
     }
    }
    List<Genre> verified=new ArrayList<>();
    Set<String> unique=new HashSet<>();
    for(LibraryStore.GenreCategory candidate:store.movieGenres()){
     if(Thread.currentThread().isInterrupted()||token!=browseToken)break;
     if(verified.size()>=4)break;
     if(unique.contains(candidate.genre))continue;
     LibraryStore.Page page=store.page("movie",candidate.name,"",false,false,
       h,hc,fav,langs,strict,manual,groups,0,120);
     List<LibraryCore.Item> english=new ArrayList<>();
     for(LibraryCore.Item film:page.rows){
      if(MediaDiscovery.confirmedEnglish(film))english.add(film);
      if(english.size()>=24)break;
     }
     if(english.size()<2)continue;
     verified.add(new Genre(candidate,english));
     unique.add(candidate.genre);
    }
    runOnUiThread(()->{
     if(isDestroyed()||token!=browseToken||(!"home".equals(screen)&&!"discover".equals(screen)))return;
     target.removeAllViews();
     for(Genre row:verified)
      homeShelf(target,row.category.genre+" • ENGLISH MOVIES",
         "movie",row.movies,row.category.name);
    });
   }catch(Exception ignored){}
  });
 }

 void openMovieCategory(String targetCategory){
  section="movie";category=targetCategory;
  query="";page=0;favOnly=false;hiddenOnly=false;editing=false;
  browse();
 }

 void fetchPersonalShelf(int token,String title,LinearLayout target,
      List<String> ids,Set<String> h,Set<String> c,Set<String> fav,
      Set<String> languages,boolean strict,Set<String> restore,Set<String> groups){
  catalogReadIO.execute(()->{
   try{
    List<LibraryCore.Item> all=store.lookupByIds(ids);
    List<LibraryCore.Item> shownRows=new ArrayList<>();
    for(LibraryCore.Item item:all){
     if(LibraryCore.visible(item,h,c,fav,languages,strict,restore,groups))
      shownRows.add(item);
    }
    runOnUiThread(()->{
     if(isDestroyed()||token!=browseToken||!"home".equals(screen))return;
     target.removeAllViews();
     if(!shownRows.isEmpty())homeShelf(target,title,"personal",shownRows);
    });
   }catch(Exception ignored){}
  });
 }
 void showShelfPlaceholder(LinearLayout area,String title){
  area.addView(headline(title,20,Color.WHITE));
  TextView status=text("Opening your collection…",14);
  status.setTextColor(MUTED);area.addView(status);
 }
 void fetchHomeShelf(int token,String type,String title,LinearLayout target,
                     Set<String> h,Set<String> categoriesSnapshot,
                     Set<String> favoritesSnapshot,Set<String> languagesSnapshot,
                     boolean strict,Set<String> restored,Set<String> restoredGroups,
                     FrameLayout hero){
  catalogReadIO.execute(()->{
   if(token!=browseToken || isDestroyed())return;
   try{
    LibraryStore.Page page=store.page(type,"All","",false,false,h,categoriesSnapshot,
      favoritesSnapshot,languagesSnapshot,strict,restored,restoredGroups,0,12);
    runOnUiThread(()->{
     if(isDestroyed() || token!=browseToken || !"home".equals(screen))return;
     target.removeAllViews();
     homeShelf(target,title,type,page.rows);
     if(hero!=null && !page.rows.isEmpty()){
      LibraryCore.Item feature=page.rows.get(0);
      for(LibraryCore.Item item:page.rows){
       if(item.artwork!=null && item.artwork.startsWith("http")){
        feature=item;break;
       }
      }
      hero.removeAllViews();
      hero.addView(homeHero(feature),new FrameLayout.LayoutParams(-1,-1));
     }
    });
   }catch(Exception error){
    runOnUiThread(()->{
     if(isDestroyed() || token!=browseToken || !"home".equals(screen))return;
     target.removeAllViews();
     target.addView(text(title+" is temporarily unavailable.",16));
     target.addView(button("RETRY",this::home));
    });
   }
  });
 }

 View homeHero(LibraryCore.Item chosen){
  final LibraryCore.Item feature=chosen;
  TvLayout dim=tv();
  FrameLayout hero=new FrameLayout(this);
  hero.setBackground(rounded(0xff11313c,16,0xff254653));
  hero.setClipToOutline(true);
  hero.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(dim.heroHeight)));
  if(feature!=null && feature.artwork!=null && feature.artwork.startsWith("http")){
   ImageView backdrop=new ImageView(this);
   backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
   hero.addView(backdrop,new FrameLayout.LayoutParams(-1,-1));
   posters.bind(backdrop,feature.artwork);
  }
  View veil=new View(this);
  GradientDrawable shade=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
      new int[]{0xff0a1827,0xf40a1b2b,0xa40d2431,0x260d2631});
  veil.setBackground(shade);
  hero.addView(veil,new FrameLayout.LayoutParams(-1,-1));
  LinearLayout content=column();
  content.setGravity(Gravity.CENTER_VERTICAL);
  int width=TvLayout.clamp((int)(dim.contentWidth()*.60),280,720);
  content.setPadding(dp(14),dp(4),dp(8),dp(4));
  hero.addView(content,new FrameLayout.LayoutParams(dp(width),-1,
    Gravity.TOP|Gravity.LEFT));
  if(dim.heroHeight>=109){
   TextView eyebrow=kicker(feature==null?"EXPLORE AURORATV":
     MediaDiscovery.recent(feature.releaseYear,MediaDiscovery.currentYear())?
     (MediaDiscovery.confirmedEnglish(feature)?"RECENT ENGLISH RELEASE":"RECENT MOVIE"):
     "FROM YOUR LIBRARY");
   eyebrow.setTextSize(10);
   content.addView(eyebrow);
  }
  TextView heading=headline(feature==null?"Your entertainment, beautifully organized":
    feature.name,TvLayout.clamp(dim.headingSize()+2,21,30),Color.WHITE);
  heading.setMaxLines(1);heading.setEllipsize(TextUtils.TruncateAt.END);
  content.addView(heading);
  if(dim.heroHeight>=145){
   TextView detail=text(feature==null?"Movies, series and live channels":
     (feature.releaseYear>0?feature.releaseYear+"  ·  ":"")+feature.category,13);
   detail.setMaxLines(1);detail.setTextColor(0xffd4e5e8);
   detail.setEllipsize(TextUtils.TruncateAt.END);
   content.addView(detail);
  }
  LinearLayout actions=new LinearLayout(this);
  actions.setGravity(Gravity.CENTER_VERTICAL);
  LinearLayout.LayoutParams buttons=new LinearLayout.LayoutParams(-1,dp(34));
  buttons.topMargin=dp(2);content.addView(actions,buttons);
  Button primary=button(feature==null?"▶  LIVE TV":"▶  DETAILS",()->{
   if(feature!=null)showMediaDetails(feature);
   else{section="live";category="All";page=0;favOnly=false;editing=false;
     hiddenOnly=false;browse();}
  });
  primary.setTextSize(12);
  actions.addView(primary,new LinearLayout.LayoutParams(0,-1,1));
  Button secondary=button("MOVIES  →",()->{
   section="movie";category="All";query="";page=0;favOnly=false;
   hiddenOnly=false;editing=false;browse();
  });
  secondary.setTextSize(12);
  LinearLayout.LayoutParams adjacent=new LinearLayout.LayoutParams(0,-1,1);
  adjacent.leftMargin=dp(6);actions.addView(secondary,adjacent);
  return hero;
 }

 void quickCategories(LinearLayout feed){
  TvLayout dim=tv();
  LinearLayout row=new LinearLayout(this);
  row.setGravity(Gravity.CENTER_VERTICAL);
  LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(-1,dp(
    TvLayout.clamp(dim.heightDp/13,36,47)));
  size.topMargin=dp(4);feed.addView(row,size);
  String[][] targets={
    {"◉  LIVE TV","live"},
    {"◆  MOVIES","movie"},
    {"▥  TV SERIES","series"}
  };
  for(String[] shortcut:targets){
   Button tile=button(shortcut[0],()->{
    section=shortcut[1];category="All";page=0;query="";
    favOnly=false;hiddenOnly=false;editing=false;browse();
   });
   tile.setGravity(Gravity.CENTER);
   tile.setTextSize(TvLayout.clamp(dim.bodySize(),12,16));
   LinearLayout.LayoutParams slot=new LinearLayout.LayoutParams(0,-1,1);
   slot.setMargins(dp(2),0,dp(2),0);row.addView(tile,slot);
  }
 }

 void homeShelf(LinearLayout feed,String heading,String type,
                List<LibraryCore.Item> rows){
  homeShelf(feed,heading,type,rows,null);
 }

 void homeShelf(LinearLayout feed,String heading,String type,
                List<LibraryCore.Item> rows,String exactCategory){
  TvLayout dim=tv();
  LinearLayout line=new LinearLayout(this);
  line.setGravity(Gravity.CENTER_VERTICAL);
  LinearLayout.LayoutParams spacing=new LinearLayout.LayoutParams(-1,dp(38));
  spacing.topMargin=dp(5);feed.addView(line,spacing);
  TextView title=headline(heading,TvLayout.clamp(dim.headingSize()-5,16,23),Color.WHITE);
  title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);
  line.addView(title,new LinearLayout.LayoutParams(0,-2,1));
  Button more=textAction(type.equals("personal")?"MY LIST  →":"SEE ALL  →",()->{
   if(type.equals("personal")){
    favOnly=true;hiddenOnly=false;editing=false;page=0;browse();return;
   }
   if(type.equals("live")){
    guideFilter="North America";guideQuery="";guidePage=0;tvGuide();return;
   }
   section=type;category=exactCategory==null?"All":exactCategory;
   browseAll=true;query="";page=0;favOnly=false;hiddenOnly=false;editing=false;browse();
  });
  more.setTextSize(11);
  line.addView(more,new LinearLayout.LayoutParams(dp(97),dp(32)));
  HorizontalScrollView carousel=new HorizontalScrollView(this);
  carousel.setHorizontalScrollBarEnabled(false);
  carousel.setClipChildren(true);carousel.setClipToPadding(true);
  carousel.setPadding(dp(2),dp(2),dp(2),dp(2));
  feed.addView(carousel,new LinearLayout.LayoutParams(-1,dp(
    ("live".equals(type)?dim.liveCardHeight:dim.posterCardHeight)+8)));
  LinearLayout cards=new LinearLayout(this);
  cards.setClipChildren(true);cards.setGravity(Gravity.TOP);
  carousel.addView(cards,new ViewGroup.LayoutParams(-2,-2));
  if(rows.isEmpty()){
   TextView empty=text("No visible titles in this category.",13);
   empty.setTextColor(MUTED);cards.addView(empty);
   return;
  }
  for(LibraryCore.Item item:rows)
   cards.addView(mediaCard(item,type.equals("personal")?item.type:type));
 }

 View mediaCard(LibraryCore.Item item,String type){
  TvLayout dim=tv();
  boolean live="live".equals(type);
  int width=live?dim.liveCardWidth:dim.posterWidth;
  int artHeight=live?dim.liveCardHeight-40:dim.posterHeight;
  int height=live?dim.liveCardHeight:dim.posterCardHeight;
  LinearLayout card=column();
  card.setPadding(dp(3),dp(3),dp(3),dp(3));
  LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(dp(width),dp(height));
  size.rightMargin=dp(dim.columnGap);card.setLayoutParams(size);
  card.setBackground(rounded(PANEL,10,0xff253b4c));
  FrameLayout art=new FrameLayout(this);
  art.setClipToOutline(true);
  art.setBackground(gradient(live?0xff164854:0xff303653,0xff121f34,9));
  card.addView(art,new LinearLayout.LayoutParams(-1,dp(artHeight)));
  TextView initial=headline(item.name.isEmpty()?"A":
    item.name.substring(0,1).toUpperCase(Locale.ROOT),
    TvLayout.clamp(width/4,22,35),0xff617f96);
  art.addView(initial,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
  displayArtwork(art,item.artwork);
  boolean recent=MediaDiscovery.recent(item.releaseYear,MediaDiscovery.currentYear());
  if(live||recent){
   TextView marker=kicker(live?"●  LIVE":Integer.toString(item.releaseYear));
   marker.setTextSize(9);marker.setPadding(dp(5),dp(2),dp(5),dp(2));
   marker.setBackground(rounded(0xea0b2732,6,0xff315761));
   FrameLayout.LayoutParams label=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.LEFT);
   label.setMargins(dp(5),dp(5),0,0);
   art.addView(marker,label);
  }
  TextView name=text(item.name,TvLayout.clamp(dim.bodySize()-1,12,15));
  name.setMaxLines(1);name.setEllipsize(TextUtils.TruncateAt.END);
  name.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));
  name.setPadding(dp(4),dp(3),dp(4),0);
  card.addView(name,new LinearLayout.LayoutParams(-1,dp(23)));
  TextView details=text(item.releaseYear>0&&!live?
    item.releaseYear+"   ·   "+item.category:item.category,11);
  details.setTextColor(MUTED);details.setSingleLine(true);
  details.setEllipsize(TextUtils.TruncateAt.END);
  details.setPadding(dp(4),0,dp(4),0);
  card.addView(details,new LinearLayout.LayoutParams(-1,dp(15)));
  card.setFocusable(true);card.setClickable(true);
  card.setOnClickListener(v->{
   if(live)open(item);else showMediaDetails(item);
  });
  card.setOnFocusChangeListener((v,focus)->{
   if(focus&&!live)updateCinematicPanel(item);
   card.setBackground(rounded(focus?0xff214a4c:PANEL,10,focus?ACCENT:0xff253b4c));
   card.animate().scaleX(focus?1.025f:1f).scaleY(focus?1.025f:1f)
    .setDuration(100).start();
  });
  return card;
 }

 void displayArtwork(FrameLayout frame,String url){
  ImageView image=new ImageView(this);
  image.setScaleType(ImageView.ScaleType.CENTER_CROP);
  frame.addView(image,new FrameLayout.LayoutParams(-1,-1));
  posters.bind(image,url);
 }


 GradientDrawable selectionOutline(){
  GradientDrawable d=new GradientDrawable();
  d.setColor(0x264cebd0);
  d.setStroke(dp(4),ACCENT);
  d.setCornerRadius(dp(16));
  return d;
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
   card.setPadding(dp(3),dp(3),dp(3),dp(3));
   card.setLayoutParams(new AbsListView.LayoutParams(-1,dp(tv().posterCardHeight+4)));
   holder=new PosterTile();
   FrameLayout cover=new FrameLayout(this);
   cover.setBackground(gradient(0xff234554,0xff121f36,12));
   card.addView(cover,new LinearLayout.LayoutParams(-1,dp(tv().posterHeight)));
   holder.initial=headline("A",28,0xff557f97);
   cover.addView(holder.initial,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
   holder.image=new ImageView(this);
   holder.image.setScaleType(ImageView.ScaleType.CENTER_CROP);
   cover.addView(holder.image,new FrameLayout.LayoutParams(-1,-1));
   holder.badge=headline("MOVIE",9,0xffbcfff1);
   holder.badge.setPadding(dp(5),dp(2),dp(5),dp(2));
   holder.badge.setBackground(shape(0xdd113b42));
   FrameLayout.LayoutParams badgeLocation=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.LEFT);
   badgeLocation.leftMargin=dp(7);badgeLocation.topMargin=dp(7);
   cover.addView(holder.badge,badgeLocation);
   holder.title=text("",TvLayout.clamp(tv().bodySize()-1,12,15));
   holder.title.setTypeface(null,Typeface.BOLD);
   holder.title.setMaxLines(1);
   holder.title.setEllipsize(TextUtils.TruncateAt.END);
   holder.title.setPadding(dp(4),dp(3),dp(4),0);
   card.addView(holder.title,new LinearLayout.LayoutParams(-1,dp(24)));
   holder.category=text("",11);
   holder.category.setSingleLine(true);
   holder.category.setTextColor(0xffa7bbc9);
   holder.category.setEllipsize(TextUtils.TruncateAt.END);
   holder.category.setPadding(dp(4),0,dp(4),0);
   card.addView(holder.category,new LinearLayout.LayoutParams(-1,dp(15)));
   card.setTag(holder);
   card.setFocusable(false);
   card.setClickable(false);
   card.setBackground(rounded(PANEL,14,0xff24384c));
   card.setOnFocusChangeListener((v,focused)->{
    card.setBackground(rounded(focused?0xff21514c:PANEL,14,focused?ACCENT:0xff24384c));
    card.setScaleX(focused?1.025f:1f);card.setScaleY(focused?1.025f:1f);
   });
  }
  holder.title.setText((favorites.contains(item.id)?"★  ":"")+item.name);
  holder.category.setText(item.releaseYear>0?item.releaseYear+"  ·  "+item.category:item.category);
  holder.badge.setText(item.type.equals("movie")?"MOVIE":"TV SERIES");
  holder.initial.setText(item.name.isEmpty()?"A":item.name.substring(0,1).toUpperCase(Locale.ROOT));
  posters.bind(holder.image,item.artwork);
  return card;
 }
 void showLivePreview(LibraryCore.Item selectedChannel){
  final int token=browseToken;
  // Resolve the encrypted URL and guide entry off the UI thread. Preview
  // networking only begins if the user explicitly selects muted playback.
  io.execute(()->{
   try{
    LibraryCore.Item resolved=store.resolve(selectedChannel);
    GuideEngine.Slot entry=epg.nowNext(selectedChannel);
    runOnUiThread(()->{
     if(isDestroyed() || isFinishing() || screen.equals("player") || token!=browseToken)return;
     if(livePreview!=null)livePreview.dismiss();
     livePreview=new PreviewWindow(this,resolved,entry,posters,()->play(resolved));
    });
   }catch(Exception error){
    runOnUiThread(()->{
     if(!isDestroyed())toast("Preview unavailable; try Watch Fullscreen");
    });
   }
  });
 }
 void showMediaDetails(LibraryCore.Item item){
  TvLayout dim=tv();
  android.app.Dialog dialog=new android.app.Dialog(this);
  LinearLayout panel=column();
  panel.setBackground(gradient(0xff18283d,0xff071321,20));
  panel.setPadding(dp(23),dp(19),dp(23),dp(23));
  panel.addView(brand(24));
  LinearLayout layout=new LinearLayout(this);
  layout.setGravity(Gravity.CENTER_VERTICAL);
  LinearLayout.LayoutParams gap=new LinearLayout.LayoutParams(-1,-2);gap.topMargin=dp(14);
  panel.addView(layout,gap);
  int posterWidth=TvLayout.clamp(dim.widthDp/5,145,228);
  int posterHeight=(int)(posterWidth*1.45);
  FrameLayout cover=new FrameLayout(this);
  cover.setBackground(gradient(0xff215c6c,0xff102439,15));
  cover.setClipToOutline(true);
  layout.addView(cover,new LinearLayout.LayoutParams(dp(posterWidth),dp(posterHeight)));
  TextView letter=headline(item.name.isEmpty()?"A":item.name.substring(0,1),55,0xff77acc0);
  cover.addView(letter,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
  displayArtwork(cover,item.artwork);
  LinearLayout info=column();info.setGravity(Gravity.CENTER_VERTICAL);
  info.setPadding(dp(22),0,0,0);layout.addView(info,new LinearLayout.LayoutParams(0,-2,1));
  info.addView(kicker(item.type.equals("movie")?"MOVIE FROM YOUR LIBRARY":"TV SERIES FROM YOUR LIBRARY"));
  TextView heading=headline(item.name,TvLayout.clamp(dim.headingSize(),26,37),Color.WHITE);
  heading.setMaxLines(3);heading.setEllipsize(TextUtils.TruncateAt.END);
  info.addView(heading);
  TextView group=text(item.category,16);group.setTextColor(MUTED);info.addView(group);
  TextView description=text(prefs.getString("mode","").equals("xtream")?"Loading the provider synopsis…":"Artwork and title details from your IPTV playlist.",15);
  description.setTextColor(0xffb4c7d3);
  LinearLayout.LayoutParams ds=new LinearLayout.LayoutParams(-1,-2);ds.topMargin=dp(13);
  info.addView(description,ds);
  LinearLayout controls=new LinearLayout(this);
  controls.setGravity(Gravity.CENTER_VERTICAL);
  LinearLayout.LayoutParams actions=new LinearLayout.LayoutParams(-1,dp(56));
  actions.topMargin=dp(17);info.addView(controls,actions);
  Button play=button(item.type.equals("movie")?"▶  PLAY MOVIE":"▶  VIEW EPISODES",()->{
   dialog.dismiss();open(item);
  });
  play.setBackground(rounded(0xff1a8d7b,12,ACCENT));
  controls.addView(play,new LinearLayout.LayoutParams(0,-1,1));
  Button manageButton=button("★  OPTIONS",()->{
   dialog.dismiss();actions(item);
  });
  LinearLayout.LayoutParams manageSpace=new LinearLayout.LayoutParams(0,-1,1);
  manageSpace.leftMargin=dp(12);controls.addView(manageButton,manageSpace);
  Button close=button("✕  CLOSE",dialog::dismiss);
  LinearLayout.LayoutParams closeBounds=new LinearLayout.LayoutParams(-1,dp(43));
  closeBounds.topMargin=dp(10);panel.addView(close,closeBounds);
  dialog.setContentView(panel);
  android.view.Window window=dialog.getWindow();
  if(window!=null){
   window.setBackgroundDrawableResource(android.R.color.transparent);
   window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
   WindowManager.LayoutParams attrs=window.getAttributes();
   attrs.dimAmount=.74f;window.setAttributes(attrs);
   window.setLayout(dp(Math.min(dim.widthDp-2*dim.marginX,1080)),-2);
  }
  dialog.show();
  if(window!=null)window.setLayout(dp(Math.min(dim.widthDp-2*dim.marginX,1080)),-2);
  play.requestFocus();
  // Rich media previews are fetched only on selection, off the UI thread.
  if("xtream".equals(prefs.getString("mode",""))){
   io.execute(()->{
    String synopsis="";
    try{
     LibraryCore.Item full=store.resolve(item);
     synopsis=Provider.mediaSummary(full,
       Vault.open(prefs.getString("url","")),
       Vault.open(prefs.getString("user","")),
       Vault.open(prefs.getString("pass","")));
    }catch(Exception ignored){}
    final String shownSynopsis=synopsis;
    runOnUiThread(()->{
     if(isDestroyed() || !dialog.isShowing())return;
     description.setText(shownSynopsis.isEmpty()?
       "No synopsis was supplied by this IPTV provider. You can still play or browse this title.":
       shownSynopsis);
    });
   });
  }
 }

 static final class ChannelTile{
  ImageView logo;TextView name,subtitle,now,icon;
 }
 View liveChannelRow(LibraryCore.Item channel,View recycle){
  LinearLayout row;ChannelTile holder;
  if(recycle instanceof LinearLayout && recycle.getTag() instanceof ChannelTile){
   row=(LinearLayout)recycle;holder=(ChannelTile)row.getTag();
  }else{
   row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
   row.setPadding(dp(11),dp(7),dp(11),dp(7));
   row.setBackground(rounded(0xff111e30,13,0xff1f3247));
   row.setLayoutParams(new AbsListView.LayoutParams(-1,dp(66)));
   holder=new ChannelTile();
   FrameLayout thumbnail=new FrameLayout(this);
   thumbnail.setBackground(rounded(0xff21384b,10,0xff294d57));
   thumbnail.setClipToOutline(true);
   row.addView(thumbnail,new LinearLayout.LayoutParams(dp(70),dp(47)));
   holder.icon=headline("TV",19,0xff91ebdc);
   FrameLayout.LayoutParams iconLoc=new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER);
   thumbnail.addView(holder.icon,iconLoc);
   holder.logo=new ImageView(this);holder.logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
   holder.logo.setPadding(dp(5),dp(5),dp(5),dp(5));
   thumbnail.addView(holder.logo,new FrameLayout.LayoutParams(-1,-1));
   LinearLayout description=column();
   description.setPadding(dp(16),0,dp(7),0);
   row.addView(description,new LinearLayout.LayoutParams(0,-2,1));
   holder.name=text("",TvLayout.clamp(tv().bodySize()+1,16,21));
   holder.name.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));
   holder.name.setMaxLines(1);holder.name.setEllipsize(TextUtils.TruncateAt.END);
   description.addView(holder.name);
   holder.subtitle=text("",12);holder.subtitle.setTextColor(MUTED);
   holder.subtitle.setSingleLine(true);holder.subtitle.setEllipsize(TextUtils.TruncateAt.END);
   description.addView(holder.subtitle);
   holder.now=text("",12);holder.now.setTextColor(0xff84e8d8);
   holder.now.setSingleLine(true);holder.now.setEllipsize(TextUtils.TruncateAt.END);
   description.addView(holder.now);
   TextView play=headline("▶",22,ACCENT);
   play.setGravity(Gravity.CENTER);row.addView(play,new LinearLayout.LayoutParams(dp(42),dp(50)));
   row.setTag(holder);
  }
  holder.name.setText((favorites.contains(channel.id)?"★  ":"")+channel.name);
  holder.subtitle.setText(channel.category);
  String now=nowNext(channel);
  holder.now.setText(now.isEmpty()?"READY TO WATCH":now.replace('\n',' ').trim());
  holder.icon.setText(channel.name.isEmpty()?"TV":channel.name.substring(0,1).toUpperCase(Locale.ROOT));
  posters.bind(holder.logo,channel.artwork);
  return row;
 }

 /** A streaming-service browse page: curated English categories first,
  *  then unverified titles, then international. The complete grid is one
  *  action away, rather than immediately dumping alphabetically sorted VOD. */
 void catalogLanding(String type){
  clearCinematic();
  stopGuidePreview();
  screen="discover";section=type;refreshSidebar();
  final int token=beginNavigationRead();
  final Set<String> h=new HashSet<>(hidden),hc=new HashSet<>(categories),
      fav=new HashSet<>(favorites),langs=new HashSet<>(allowed),
      manual=new HashSet<>(shown),groups=new HashSet<>(shownCategories);
  final boolean strict=hideUnknown;
  body.removeAllViews();
  LinearLayout toolbar=new LinearLayout(this);
  toolbar.setGravity(Gravity.CENTER_VERTICAL);
  body.addView(toolbar,new LinearLayout.LayoutParams(-1,dp(38)));
  TextView heading=headline(type.equals("movie")?"MOVIES":"TV SHOWS",
      TvLayout.clamp(tv().bodySize()+4,17,23),Color.WHITE);
  toolbar.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
  Button search=textAction("⌕ FIND",()->{
   section=type;focusSearchNext=true;browseAll=true;browse();
  });
  toolbar.addView(search,new LinearLayout.LayoutParams(dp(79),dp(32)));
  Button groupsButton=textAction("GENRES ▾",()->{
   section=type;browseAll=true;chooseCategory();
  });
  toolbar.addView(groupsButton,new LinearLayout.LayoutParams(dp(104),dp(32)));
  Button all=textAction("ALL TITLES →",()->{
   section=type;category="All";browseAll=true;page=0;browse();
  });
  toolbar.addView(all,new LinearLayout.LayoutParams(dp(119),dp(32)));

  ScrollView scroller=new ScrollView(this);
  scroller.setVerticalScrollBarEnabled(false);scroller.setFillViewport(false);
  scroller.setClipChildren(true);scroller.setClipToPadding(true);
  body.addView(scroller,new LinearLayout.LayoutParams(-1,0,1));
  LinearLayout feed=column();
  feed.setPadding(dp(3),dp(1),dp(3),dp(10));
  feed.setClipChildren(true);feed.setClipToPadding(true);
  scroller.addView(feed,new ScrollView.LayoutParams(-1,-2));
  addCinematicPanel(feed);
  LinearLayout recent=column(),english=column(),genres=column(),unknown=column(),
      international=column();
  LinearLayout trending=column();feed.addView(trending);
   fetchTrendingShelf(token,type,trending,h,hc,fav,langs,strict,manual,groups);
   feed.addView(recent);feed.addView(english);feed.addView(genres);
  feed.addView(unknown);feed.addView(international);
  if("movie".equals(type)){
   fetchRecentMovies(token,recent,unknown,international,h,hc,fav,langs,strict,manual,groups);
   fetchGenreShelves(token,genres,h,hc,fav,langs,strict,manual,groups);
  }else{
   fetchSeriesCategories(token,genres,h,hc,fav,langs,strict,manual,groups);
  }
  fetchLanguageShelf(token,type,english,
      type.equals("movie")?"POPULAR ENGLISH MOVIES":"ENGLISH TV SERIES",
      false,h,hc,fav,langs,strict,manual,groups);
  fetchLanguageShelf(token,type,international,
      type.equals("movie")?"MORE INTERNATIONAL MOVIES":"INTERNATIONAL TV SERIES",
      true,h,hc,fav,langs,strict,manual,groups);
 }


 /** Only verified catalogue matches appear in the daily Top 20 rails. */
 void fetchTrendingShelf(int token,String type,LinearLayout target,
       Set<String> h,Set<String> hc,Set<String> fav,Set<String> langs,
       boolean strict,Set<String> manual,Set<String> groups){
   String apiKey=prefs.getString("tmdb.apiKey","");
   if(apiKey.isEmpty())return;
   catalogReadIO.execute(()->{
    try{
     java.util.List<TrendingCatalog.Entry> trending=TrendingCatalog.load(this,type,apiKey);
     java.util.List<LibraryCore.Item> matches=new java.util.ArrayList<>();
     java.util.Set<String> ids=new java.util.HashSet<>();
     for(TrendingCatalog.Entry rank:trending){
      if(Thread.currentThread().isInterrupted()||token!=browseToken)return;
      String search=rank.title.length()>4?rank.title:rank.originalTitle;
      LibraryStore.Page found=store.page(type,"All",search,false,false,
          h,hc,fav,langs,strict,manual,groups,0,60);
      LibraryCore.Item best=null;
      for(LibraryCore.Item item:found.rows){
       if(!TrendingCatalog.matches(rank,item))continue;
       if(MediaDiscovery.knownForeign(item))continue;
       String id=TrendingCatalog.normalize(item.name)+":"+item.releaseYear;
       if(ids.contains(id))continue;
       if(best==null || (item.artwork!=null&&item.artwork.startsWith("http")
            &&(best.artwork==null||!best.artwork.startsWith("http"))))best=item;
      }
      if(best!=null){
       matches.add(best);
       ids.add(TrendingCatalog.normalize(best.name)+":"+best.releaseYear);
      }
     }
     runOnUiThread(()->{
      if(isDestroyed()||token!=browseToken
          ||!("home".equals(screen)||"discover".equals(screen)))return;
      target.removeAllViews();
      if(!matches.isEmpty())homeShelf(target,
          "TOP 20 "+("movie".equals(type)?"MOVIES":"TV SHOWS")+" TODAY · IN YOUR LIBRARY",
          type,matches);
     });
    }catch(Exception ignored){
     // Offline or invalid API key: existing library shelves remain available.
    }
   });
 }
 void trendingSettings(){
   EditText input=new EditText(this);
   input.setSingleLine(true);
   input.setText(prefs.getString("tmdb.apiKey",""));
   input.setHint("TMDB API key");
   input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|
       android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
   new AlertDialog.Builder(this).setTitle("DAILY TOP 20")
     .setMessage("Enter your own TMDB v3 API key to display daily trending movies and series available in your IPTV library. The key stays on this device. Movie rankings update daily.")
     .setView(input).setPositiveButton("SAVE",(dialog,which)->{
       prefs.edit().putString("tmdb.apiKey",input.getText().toString().trim()).apply();
       if("home".equals(screen))home();
       else if("discover".equals(screen))catalogLanding(section);
     }).setNegativeButton("CANCEL",null).show();
 }


 void clearCinematic(){
  cinematicRevision++;
  if(pendingCinematic!=null)uiHandler.removeCallbacks(pendingCinematic);
  pendingCinematic=null;cinematicBackdrop=null;cinematicIncoming=null;
  cinematicArtwork=null;cinematicTitle=null;cinematicSubtitle=null;
 }
 void addCinematicPanel(LinearLayout feed){
  TvLayout m=tv();
  FrameLayout hero=new FrameLayout(this);
  hero.setBackground(rounded(0xff0c1b2b,12,0xff203746));
  hero.setClipToOutline(true);
  LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(-1,
    dp(TvLayout.clamp(m.heightDp/3,155,245)));
  size.bottomMargin=dp(9);
  feed.addView(hero,size);
  cinematicArtwork=hero;
  cinematicBackdrop=new ImageView(this);
  cinematicBackdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
  cinematicBackdrop.setAlpha(.52f);
  hero.addView(cinematicBackdrop,new FrameLayout.LayoutParams(-1,-1));
  View shade=new View(this);
  shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
    new int[]{0xff081321,0xf0081423,0x87081524,0x240b1724}));
  hero.addView(shade,new FrameLayout.LayoutParams(-1,-1));
  LinearLayout info=column();info.setGravity(Gravity.CENTER_VERTICAL);
  info.setPadding(dp(22),dp(6),dp(8),dp(6));
  hero.addView(info,new FrameLayout.LayoutParams(
      dp(TvLayout.clamp(m.contentWidth()*3/5,265,780)),-1,Gravity.LEFT));
  TextView eyebrow=kicker("AURORATV   /   CINEMA");eyebrow.setTextSize(10);
  info.addView(eyebrow);
  cinematicTitle=headline("Discover something great",
    TvLayout.clamp(m.headingSize()+7,26,41),Color.WHITE);
  cinematicTitle.setMaxLines(2);
  cinematicTitle.setEllipsize(TextUtils.TruncateAt.END);
  info.addView(cinematicTitle);
  cinematicSubtitle=text("Move between titles to explore your collection",13);
  cinematicSubtitle.setTextColor(0xffd1dce7);
  cinematicSubtitle.setMaxLines(2);
  cinematicSubtitle.setEllipsize(TextUtils.TruncateAt.END);
  info.addView(cinematicSubtitle);
 }
 void updateCinematicPanel(LibraryCore.Item item){
  if(cinematicArtwork==null||cinematicTitle==null||item==null)return;
  if(pendingCinematic!=null)uiHandler.removeCallbacks(pendingCinematic);
  final int revision=++cinematicRevision;
  // Debounce D-pad movement so quick browsing never launches dozens of image requests.
  pendingCinematic=()->{
   if(revision!=cinematicRevision||cinematicArtwork==null||
      cinematicTitle==null||isDestroyed())return;
   cinematicTitle.animate().cancel();
   cinematicTitle.animate().alpha(0.15f).setDuration(75)
     .withEndAction(()->{
      if(revision!=cinematicRevision||cinematicTitle==null)return;
      cinematicTitle.setText(item.name);
      cinematicTitle.animate().alpha(1f).setDuration(210).start();
     }).start();
   String descriptor=(item.releaseYear>0?item.releaseYear+"  •  ":"")
      +("series".equals(item.type)?"TV SERIES":"MOVIE")
      +(item.category.isEmpty()?"":"  •  "+item.category);
   cinematicSubtitle.setText(descriptor);
   if(item.artwork==null||!item.artwork.startsWith("http"))return;
   final FrameLayout parent=cinematicArtwork;
   final ImageView old=cinematicBackdrop;
   ImageView next=new ImageView(this);
   next.setScaleType(ImageView.ScaleType.CENTER_CROP);
   next.setAlpha(0f);
   // Keep transitions behind the metadata gradient and foreground labels.
   parent.addView(next,0,new FrameLayout.LayoutParams(-1,-1));
   posters.bind(next,item.artwork);
   next.animate().alpha(.54f).setDuration(350).withEndAction(()->{
    if(revision!=cinematicRevision||cinematicArtwork!=parent){
     parent.removeView(next);return;
    }
    if(old!=null&&old.getParent()==parent)parent.removeView(old);
    cinematicBackdrop=next;
   }).start();
  };
  uiHandler.postDelayed(pendingCinematic,155);
 }
 void fetchSeriesCategories(int token,LinearLayout target,
       Set<String> h,Set<String> hc,Set<String> fav,Set<String> langs,boolean strict,
       Set<String> manual,Set<String> groups){
  catalogReadIO.execute(()->{
   try{
    List<String> categoryNames=new ArrayList<>();
    for(String categoryName:store.categories("series")){
     if(categoryNames.size()>=4)break;
     if(!"en".equals(LibraryCore.infer(categoryName)))continue;
     categoryNames.add(categoryName);
    }
    final Map<String,List<LibraryCore.Item>> entries=new LinkedHashMap<>();
    for(String cat:categoryNames){
     LibraryStore.Page result=store.page("series",cat,"",false,false,
       h,hc,fav,langs,strict,manual,groups,0,80);
     List<LibraryCore.Item> rows=new ArrayList<>();
     for(LibraryCore.Item item:result.rows){
      if(MediaDiscovery.confirmedEnglish(item))rows.add(item);
      if(rows.size()>=24)break;
     }
     if(rows.size()>1)entries.put(cat,rows);
    }
    runOnUiThread(()->{
     if(isDestroyed()||token!=browseToken||!screen.equals("discover"))return;
     target.removeAllViews();
     for(Map.Entry<String,List<LibraryCore.Item>> entry:entries.entrySet())
      homeShelf(target,entry.getKey(),"series",entry.getValue(),entry.getKey());
    });
   }catch(Exception ignored){}
  });
 }

 void browse(){
  stopGuidePreview();
  if(!store.hasLibrary()){loginScreen(false);return;}
  if(!browseAll&&!editing&&!hiddenOnly&&!favOnly&&
      ("movie".equals(section)||"series".equals(section))&&
      "All".equals(category)&&(query==null||query.isEmpty())){
   catalogLanding(section);return;
  }
  screen="browse";refreshSidebar();
  final int token=beginNavigationRead();
  final String type=section,cat=category,search=query;
  final boolean showHidden=hiddenOnly,onlyFavorites=favOnly,isEditing=editing,hide=hideUnknown;
  final int requested=page;
  final Set<String> h=new HashSet<>(hidden),hc=new HashSet<>(categories),
      fav=new HashSet<>(favorites),lang=new HashSet<>(allowed),
      manual=new HashSet<>(shown),manualGroups=new HashSet<>(shownCategories);
  body.removeAllViews();
  body.addView(text("Finding your "+(type.equals("live")?"channels":type.equals("movie")?"movies":"TV shows")+"…",18));
  catalogReadIO.execute(()->{
   try{
    LibraryStore.Page result=store.page(type,cat,search,showHidden,onlyFavorites,h,hc,fav,lang,hide,
         manual,manualGroups,requested*PAGE_SIZE,PAGE_SIZE);
    final boolean mediaTypeMissing=result.rows.isEmpty()&&store.count(type)==0;
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
     markLoad(type,token);
     items=result.rows;guideSummary=brief;
     body.removeAllViews();
     LinearLayout heading=new LinearLayout(this);
     heading.setGravity(Gravity.CENTER_VERTICAL);
     boolean compact=tv().heightDp<470;
     int actionHeight=compact?31:36;
     String title=(isEditing?"EDIT  /  ":"")+(showHidden?"HIDDEN":onlyFavorites?"FAVORITES":
       type.equals("live")?"LIVE TV":type.equals("movie")?"MOVIES":"TV SHOWS");
     heading.addView(headline(title,TvLayout.clamp(tv().headingSize(),compact?20:23,31),Color.WHITE),new LinearLayout.LayoutParams(0,-2,1));
     int actionsWidth=tv().contentWidth()<650?98:118;
     Button filters=textAction("☷  FILTERS",this::smart);
     heading.addView(filters,new LinearLayout.LayoutParams(dp(actionsWidth),dp(actionHeight)));

     body.addView(heading,new LinearLayout.LayoutParams(-1,dp(compact?33:41)));

     // Users can search within the selected media type without going back to
     // the navigation menu. Search is performed by SQLite, not in-memory scans.
     LinearLayout controls=new LinearLayout(this);
     controls.setGravity(Gravity.CENTER_VERTICAL);
     body.addView(controls);
     Button selectCategory=button("▦  "+cat+"   ▾",this::chooseCategory);
     selectCategory.setSingleLine(true);selectCategory.setEllipsize(TextUtils.TruncateAt.END);
     controls.addView(selectCategory,new LinearLayout.LayoutParams(0,dp(actionHeight),2));
     EditText searchField=new EditText(this);
     searchField.setTextColor(Color.WHITE);
     searchField.setHintTextColor(0xffa6bbc9);
     searchField.setSingleLine(true);
     searchField.setText(search);
     searchField.setHint(type.equals("live")?"Search channel":type.equals("movie")?"Search movie titles":"Search TV series");
     searchField.setTextSize(TvLayout.clamp(tv().bodySize(),14,18));
     searchField.setPadding(dp(15),0,dp(15),0);
     searchField.setBackground(rounded(0xff102139,12,0xff2a4a5b));
     searchField.setOnFocusChangeListener((view,focused)->
       searchField.setBackground(rounded(focused?0xff193849:0xff102139,12,
         focused?ACCENT:0xff2a4a5b)));
     searchField.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
     LinearLayout.LayoutParams searchSize=new LinearLayout.LayoutParams(0,dp(actionHeight),2);
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
     Button go=textAction("SEARCH",applySearch);
     controls.addView(go,new LinearLayout.LayoutParams(dp(compact?76:104),dp(actionHeight)));
     if(!search.isEmpty()){
      controls.addView(button("✕",()->{query="";page=0;browse();}),
          new LinearLayout.LayoutParams(dp(42),dp(actionHeight)));
     }
     if(isEditing)body.addView(text("Select a title to favorite, restore or hide it.",14));
     if(result.rows.isEmpty()){
      if(requested>0){page=0;browse();return;}
      body.addView(text(mediaTypeMissing?
       "There are currently no imported "+(type.equals("movie")?"movies":type.equals("series")?"TV shows":"live channels")+
         " in this catalog. Refresh all media to check the provider.":
       "No titles match these filters. Change the category, search or language rules.",18));
      if(mediaTypeMissing)body.addView(button("REFRESH COMPLETE LIBRARY",this::refresh));
      if(!search.isEmpty())body.addView(button("CLEAR SEARCH",()->{query="";page=0;browse();}));
      return;
     }
     subtitle.setText("Your entertainment, your selection");

     final List<LibraryCore.Item> shownRows=new ArrayList<>(result.rows);
     final boolean[] more={result.more},loadingMore={false};
     final int[] loadedPages={requested};
     final BaseAdapter[] viewAdapter={null};
     Runnable fetchNext=()->{
      if(loadingMore[0]||!more[0]||token!=browseToken)return;
      loadingMore[0]=true;
      final int next=loadedPages[0]+1;
      catalogReadIO.execute(()->{
       try{
        LibraryStore.Page batch=store.page(type,cat,search,showHidden,onlyFavorites,
          h,hc,fav,lang,hide,manual,manualGroups,next*PAGE_SIZE,PAGE_SIZE);
        runOnUiThread(()->{
         if(isDestroyed()||token!=browseToken||!"browse".equals(screen))return;
         shownRows.addAll(batch.rows);
         loadedPages[0]=next;
         more[0]=batch.more;
         loadingMore[0]=false;
         if(viewAdapter[0]!=null)viewAdapter[0].notifyDataSetChanged();
        });
       }catch(Exception e){
        runOnUiThread(()->{
         if(token==browseToken){loadingMore[0]=false;more[0]=false;}
        });
       }
      });
     };
     if(type.equals("movie")||type.equals("series")){
      GridView grid=new GridView(this);
      grid.setNumColumns(GridView.AUTO_FIT);
      grid.setColumnWidth(dp(tv().posterWidth));
      grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
      grid.setHorizontalSpacing(dp(tv().columnGap));
      grid.setVerticalSpacing(dp(tv().columnGap));
      grid.setVerticalScrollBarEnabled(false);
      grid.setClipToPadding(true);
      grid.setPadding(dp(3),dp(4),dp(3),dp(5));
      grid.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
      grid.setSelector(selectionOutline());
      grid.setDrawSelectorOnTop(true);
      grid.setFocusable(true);
      body.addView(grid,new LinearLayout.LayoutParams(-1,0,1));
      BaseAdapter gridAdapter=new BaseAdapter(){
       public int getCount(){return shownRows.size();}
       public Object getItem(int n){return shownRows.get(n);}
       public long getItemId(int n){return n;}
       public View getView(int n,View reuse,ViewGroup parent){
        return posterGridCard(shownRows.get(n),reuse);
       }
      };
      viewAdapter[0]=gridAdapter;
      grid.setAdapter(gridAdapter);
      grid.setOnScrollListener(new AbsListView.OnScrollListener(){
       public void onScrollStateChanged(AbsListView view,int state){}
       public void onScroll(AbsListView view,int first,int visible,int total){
        if(visible>0&&first+visible>=shownRows.size()-18)fetchNext.run();
       }
      });

      grid.setOnItemClickListener((parent,v,n,id)->{
       LibraryCore.Item media=shownRows.get(n);
       if(editing)actions(media);else showMediaDetails(media);
      });
      grid.setOnItemLongClickListener((parent,v,n,id)->{actions(shownRows.get(n));return true;});
     }else{
      LinearLayout selection=new LinearLayout(this);
      selection.setGravity(Gravity.CENTER_VERTICAL);
      selection.setPadding(dp(17),dp(8),dp(17),dp(8));
      selection.setBackground(rounded(0xff152c3d,14,0xff2d5363));
      LinearLayout.LayoutParams selectedBounds=new LinearLayout.LayoutParams(
          -1,dp(tv().heightDp<650?61:78));
      selectedBounds.bottomMargin=dp(8);
      body.addView(selection,selectedBounds);
      ImageView stationLogo=new ImageView(this);
      stationLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
      selection.addView(stationLogo,new LinearLayout.LayoutParams(dp(62),-1));
      LinearLayout info=column();
      info.setGravity(Gravity.CENTER_VERTICAL);
      info.setPadding(dp(13),0,0,0);
      selection.addView(info,new LinearLayout.LayoutParams(0,-1,1));
      info.addView(kicker("HIGHLIGHTED CHANNEL"));
      TextView channelTitle=headline("Choose a channel",TvLayout.clamp(tv().bodySize()+4,17,24),Color.WHITE);
      channelTitle.setMaxLines(1);channelTitle.setEllipsize(TextUtils.TruncateAt.END);
      info.addView(channelTitle);
      TextView channelNow=text("Press Select to preview, or open the TV Guide",13);
      channelNow.setTextColor(MUTED);
      channelNow.setSingleLine(true);channelNow.setEllipsize(TextUtils.TruncateAt.END);
      info.addView(channelNow);
      ListView list=new ListView(this);
      list.setDividerHeight(dp(9));
      body.addView(list,new LinearLayout.LayoutParams(-1,0,1));
      BaseAdapter listAdapter=new BaseAdapter(){
       public int getCount(){return shownRows.size();}
       public Object getItem(int n){return shownRows.get(n);}
       public long getItemId(int n){return n;}
       public View getView(int n,View reuse,ViewGroup parent){
        return liveChannelRow(shownRows.get(n),reuse);
       }
      };
      viewAdapter[0]=listAdapter;
      list.setAdapter(listAdapter);
      list.setOnScrollListener(new AbsListView.OnScrollListener(){
       public void onScrollStateChanged(AbsListView view,int state){}
       public void onScroll(AbsListView view,int first,int visible,int total){
        if(visible>0&&first+visible>=shownRows.size()-18)fetchNext.run();
       }
      });
      list.setSelector(selectionOutline());
      list.setDrawSelectorOnTop(true);
      list.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
       public void onItemSelected(AdapterView<?> parent,View v,int position,long id){
        LibraryCore.Item channel=shownRows.get(position);
        channelTitle.setText(channel.name);
        channelNow.setText(channel.category+nowNext(channel).replace('\n',' '));
        posters.bind(stationLogo,channel.artwork);
       }
       public void onNothingSelected(AdapterView<?> parent){}
      });
      if(!result.rows.isEmpty()){
       LibraryCore.Item first=result.rows.get(0);
       channelTitle.setText(first.name);
       channelNow.setText(first.category+nowNext(first).replace('\n',' '));
       posters.bind(stationLogo,first.artwork);
      }
      list.setOnItemClickListener((parent,v,n,id)->{
       LibraryCore.Item picked=shownRows.get(n);if(editing)actions(picked);else showLivePreview(picked);
      });
      list.setOnItemLongClickListener((parent,v,n,id)->{actions(shownRows.get(n));return true;});
     }

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
 void stopGuidePreview(){
  if(guidePreview!=null){guidePreview.close();guidePreview=null;}
 }
 static final class CompactGuideRow {
  TextView channel,programme,next;
 }

 void tvGuide(){
  stopGuidePreview();
  if(!store.hasLibrary()){loginScreen(false);return;}
  screen="guide";section="live";refreshSidebar();
  if(SystemClock.elapsedRealtime()-guideSlotCacheAt>5*60*1000L){
   guideSlotCache.clear();guideSlotCacheAt=SystemClock.elapsedRealtime();
  }
  final int token=beginNavigationRead();
  final String filter=guideFilter,search=guideQuery;
  final Set<String> h=new HashSet<>(hidden),hc=new HashSet<>(categories),
      fav=new HashSet<>(favorites),langs=new HashSet<>(allowed),
      manual=new HashSet<>(shown),groups=new HashSet<>(shownCategories);
  final boolean strict=hideUnknown;
  // Retain last screen until the requested directory is ready.
  catalogReadIO.execute(()->{
   try{
    final List<LibraryCore.Item> channels=new ArrayList<>(store.channelDirectory(
        filter,search,h,hc,fav,langs,strict,manual,groups));
    runOnUiThread(()->{
     if(isDestroyed()||token!=browseToken||!screen.equals("guide"))return;
     markLoad("guide",token);
     TvLayout metrics=tv();
     body.removeAllViews();
     // One compact toolbar, no separate hero/selection area or pagination.
     LinearLayout toolbar=new LinearLayout(this);
     toolbar.setGravity(Gravity.CENTER_VERTICAL);
     body.addView(toolbar,new LinearLayout.LayoutParams(-1,dp(37)));
     TextView heading=headline("LIVE TV · GUIDE",TvLayout.clamp(metrics.bodySize()+3,16,23),Color.WHITE);
     toolbar.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
     TextView total=text(channels.size()+("North America".equals(filter)?" NETWORKS":" CHANNELS"),11);
     total.setTextColor(MUTED);
     toolbar.addView(total);
     Button searchButton=textAction(search.isEmpty()?"⌕ SEARCH":"⌕ "+search,()->guideSearch());
     searchButton.setSingleLine(true);searchButton.setEllipsize(TextUtils.TruncateAt.END);
     toolbar.addView(searchButton,new LinearLayout.LayoutParams(dp(
       TvLayout.clamp(metrics.widthDp/7,78,140)),dp(32)));
     Button settings=textAction("⚙",this::guideSettings);
     settings.setContentDescription("TV Guide data and preview settings");
     toolbar.addView(settings,new LinearLayout.LayoutParams(dp(43),dp(32)));
     Button refresh=textAction("⟳",()->{
      scheduleGuideSync(true,true);toast("Updating your guide in the background");
     });
     refresh.setContentDescription("Refresh TV programmes");
     toolbar.addView(refresh,new LinearLayout.LayoutParams(dp(43),dp(32)));

     HorizontalScrollView chipsScroll=new HorizontalScrollView(this);
     chipsScroll.setHorizontalScrollBarEnabled(false);
     chipsScroll.setClipChildren(true);chipsScroll.setClipToPadding(true);
     body.addView(chipsScroll,new LinearLayout.LayoutParams(-1,dp(38)));
     LinearLayout chips=new LinearLayout(this);
     chips.setOrientation(LinearLayout.HORIZONTAL);
     chips.setGravity(Gravity.CENTER_VERTICAL);
     chipsScroll.addView(chips,new ViewGroup.LayoutParams(-2,-1));
     final String[] filters={"North America","News","Sports","Entertainment",
       "Movies","Kids","English","More North America","All","My Channels",
       "International","Other"};
     final Runnable[] guideSwitcher={null};
     final java.util.Map<String,Button> guideChips=new java.util.HashMap<>();
     for(String option:filters){
      Button chip=textAction(
        "North America".equals(option)?"US / CANADA":
        "More North America".equals(option)?"MORE NORTH AMERICA":
        "All".equals(option)?"ALL STREAMS":option,()->{
       if(option.equals(guideFilter))return;
       guideFilter=option;guidePage=0;
       if(guideSwitcher[0]!=null)guideSwitcher[0].run();
      });
      chip.setTextSize(TvLayout.clamp(metrics.bodySize()-2,11,14));
      chip.setPadding(dp(9),0,dp(9),0);
      if(option.equals(filter))chip.setBackground(rounded(0xff20594e,9,ACCENT));
      LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(-2,dp(31));
      size.rightMargin=dp(4);chips.addView(chip,size);
      guideChips.put(option,chip);
     }
     Button moreCategories=textAction("CATEGORIES ▾",this::chooseGuideCategory);
     moreCategories.setTextSize(12);
     chips.addView(moreCategories,new LinearLayout.LayoutParams(dp(119),dp(31)));

     LinearLayout main=new LinearLayout(this);
     main.setGravity(Gravity.TOP);
     main.setClipChildren(true);main.setClipToPadding(true);
     LinearLayout.LayoutParams viewport=new LinearLayout.LayoutParams(-1,0,1);
     viewport.topMargin=dp(3);
     body.addView(main,viewport);
     LinearLayout directory=column();
     directory.setClipToPadding(true);directory.setClipChildren(true);
     final int paneWidth=TvLayout.clamp((int)(metrics.contentWidth()*.265),180,350);
     LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,-1,1);
     left.rightMargin=dp(7);main.addView(directory,left);
     LinearLayout columns=new LinearLayout(this);
     columns.setGravity(Gravity.CENTER_VERTICAL);
     columns.setBackground(rounded(0xff12303b,8,0));
     directory.addView(columns,new LinearLayout.LayoutParams(-1,dp(28)));
     int leftWidth=Math.max(380,metrics.contentWidth()-paneWidth-20);
     int channelW=(int)(leftWidth*.40),nowW=(int)(leftWidth*.36),nextW=leftWidth-channelW-nowW;
     TextView channelHeader=text(metrics.widthDp>950?"CHANNEL · US REFERENCE ORDER":"CHANNEL",11);channelHeader.setTextColor(ACCENT);
     columns.addView(channelHeader,new LinearLayout.LayoutParams(0,-2,.40f));
     TextView nowHeader=text("ON NOW",11);nowHeader.setTextColor(ACCENT);
     columns.addView(nowHeader,new LinearLayout.LayoutParams(0,-2,.36f));
     TextView nextHeader=text("UP NEXT",11);nextHeader.setTextColor(ACCENT);
     columns.addView(nextHeader,new LinearLayout.LayoutParams(0,-2,.24f));

     final java.util.Map<String,GuideEngine.Slot> slots=guideSlotCache;
     final Set<String> pending=java.util.concurrent.ConcurrentHashMap.newKeySet();
     final ListView listing=new ListView(this);
     listing.setVerticalScrollBarEnabled(false);
     listing.setDividerHeight(dp(1));
     listing.setCacheColorHint(Color.TRANSPARENT);
     listing.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
     listing.setSelector(selectionOutline());
     listing.setDrawSelectorOnTop(true);
     listing.setClipToPadding(true);
     listing.setPadding(dp(1),dp(2),dp(1),dp(2));
     directory.addView(listing,new LinearLayout.LayoutParams(-1,0,1));
     final int lineHeight=TvLayout.clamp((int)(metrics.heightDp*.073),33,49);
     final BaseAdapter adapter=new BaseAdapter(){
      @Override public int getCount(){return channels.size();}
      @Override public Object getItem(int position){return channels.get(position);}
      @Override public long getItemId(int position){return position;}
      @Override public View getView(int position,View recycled,ViewGroup parent){
       LinearLayout row;
       CompactGuideRow holder;
       if(recycled instanceof LinearLayout && recycled.getTag() instanceof CompactGuideRow){
        row=(LinearLayout)recycled;holder=(CompactGuideRow)row.getTag();
       }else{
        row=new LinearLayout(MainActivity.this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(5),dp(2),dp(4),dp(2));
        holder=new CompactGuideRow();
        holder.channel=text("",TvLayout.clamp(metrics.bodySize()-1,12,16));
        holder.programme=text("",TvLayout.clamp(metrics.bodySize()-2,11,15));
        holder.next=text("",TvLayout.clamp(metrics.bodySize()-3,10,14));
        holder.channel.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));
        for(TextView t:new TextView[]{holder.channel,holder.programme,holder.next}){
         t.setMaxLines(1);t.setSingleLine(true);
         t.setEllipsize(TextUtils.TruncateAt.END);
         t.setPadding(dp(5),0,dp(5),0);
        }
        holder.programme.setTextColor(0xffd5e3ed);
        holder.next.setTextColor(0xffa2b6c6);
        row.addView(holder.channel,new LinearLayout.LayoutParams(0,-2,.40f));
        row.addView(holder.programme,new LinearLayout.LayoutParams(0,-2,.36f));
        row.addView(holder.next,new LinearLayout.LayoutParams(0,-2,.24f));
        row.setTag(holder);
        row.setLayoutParams(new AbsListView.LayoutParams(-1,dp(lineHeight)));
       }
       LibraryCore.Item item=channels.get(position);
       int satelliteRef=ChannelDiscovery.satelliteNumber(item);
       holder.channel.setText(satelliteRef>0?satelliteRef+"  "+item.name:item.name);
       GuideEngine.Slot available=slots.get(item.id);
       holder.programme.setText(available!=null&&available.now!=null?
           available.now.title:"Programme information unavailable");
       holder.next.setText(available!=null&&available.next!=null?
           available.next.title:"—");
       row.setBackground(rounded(position%2==0?0xff111e2d:0xff142334,7,0));
       return row;
      }
     };
     listing.setAdapter(adapter);
     if(channels.isEmpty()){
      TextView empty=text("No channels in this section. Try All, English, or a provider category.",15);
      directory.addView(empty);
     }

     LinearLayout previewPanel=column();
     previewPanel.setClipChildren(true);previewPanel.setClipToPadding(true);
     main.addView(previewPanel,new LinearLayout.LayoutParams(dp(paneWidth),-1));
     LinearLayout window=new LinearLayout(this);
     // Actual video is in a right-side preview, not a huge block above the guide.
     previewPanel.addView(window,new LinearLayout.LayoutParams(-1,-2));
     guidePreview=new GuidePreviewPane(this,store,posters,window,metrics,
       prefs.getBoolean("guide.preview.auto",true),true);
     final GuidePreviewPane preview=guidePreview;
     final LibraryCore.Item[] focused={null};
     Button watch=button("▶ WATCH",()->{
      if(focused[0]!=null)open(focused[0]);
     });
     LinearLayout.LayoutParams wb=new LinearLayout.LayoutParams(-1,dp(37));
     wb.topMargin=dp(7);previewPanel.addView(watch,wb);
     Button info=button("⋯ CHANNEL OPTIONS",()->{
      if(focused[0]!=null)moreGuide(focused[0]);
     });
     if(metrics.heightDp>=480)
      previewPanel.addView(info,new LinearLayout.LayoutParams(-1,dp(34)));
          guideSwitcher[0]=()->{
      final String wanted=guideFilter;
      final int sequence=++guideCategorySequence;
      guideDirectoryIO.execute(()->{
       List<LibraryCore.Item> updated=store.channelDirectory(
          wanted,guideQuery,h,hc,fav,langs,strict,manual,groups);
       runOnUiThread(()->{
        if(isDestroyed()||!"guide".equals(screen)||token!=browseToken
            ||sequence!=guideCategorySequence)return;
        channels.clear();channels.addAll(updated);
        adapter.notifyDataSetChanged();
        total.setText(channels.size()+("North America".equals(wanted)?" NETWORKS":" CHANNELS"));
        for(java.util.Map.Entry<String,Button> entry:guideChips.entrySet()){
         boolean active=entry.getKey().equals(wanted);
         entry.getValue().setBackground(rounded(active?0xff20594e:Color.TRANSPARENT,9,active?ACCENT:0));
        }
        if(!channels.isEmpty()){
         listing.setSelection(0);
         focused[0]=channels.get(0);guideSelectedId=focused[0].id;
         preview.highlight(focused[0],slots.get(focused[0].id));
         fetchGuideSchedules(token,channels,0,14,slots,pending,adapter,guideSelectedId,preview);
        }else{
         focused[0]=null;guideSelectedId="";
        }
       });
      });
     };
     listing.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
      @Override public void onItemSelected(AdapterView<?> parent,View v,int position,long id){
       if(position<0||position>=channels.size())return;
       LibraryCore.Item chosen=channels.get(position);
       focused[0]=chosen;
       guideSelectedId=chosen.id;
       preview.highlight(chosen,slots.get(chosen.id));
       fetchGuideSchedules(token,channels,Math.max(0,position-3),12,slots,pending,adapter,
         chosen.id,preview);
      }
      @Override public void onNothingSelected(AdapterView<?> parent){}
     });
     listing.setOnItemClickListener((parent,v,position,id)->{
      if(position>=0&&position<channels.size())open(channels.get(position));
     });
     listing.setOnItemLongClickListener((parent,v,position,id)->{
      if(position>=0&&position<channels.size())moreGuide(channels.get(position));
      return true;
     });
     listing.setOnScrollListener(new AbsListView.OnScrollListener(){
      @Override public void onScrollStateChanged(AbsListView view,int state){}
      @Override public void onScroll(AbsListView view,int first,int visible,int total){
       if(visible>0)fetchGuideSchedules(token,channels,first,visible+5,
         slots,pending,adapter,focused[0]==null?"":focused[0].id,preview);
      }
     });
     if(!channels.isEmpty()){
      listing.setSelection(0);
      listing.requestFocus();
      focused[0]=channels.get(0);
      guideSelectedId=channels.get(0).id;
      preview.highlight(channels.get(0),slots.get(channels.get(0).id));
      fetchGuideSchedules(token,channels,0,14,slots,pending,adapter,
        channels.get(0).id,preview);
     }else searchButton.requestFocus();
     scheduleGuideSync(false,true);
    });
   }catch(Exception ex){
    runOnUiThread(()->{
     if(isDestroyed()||token!=browseToken||!screen.equals("guide"))return;
     body.removeAllViews();
     body.addView(text("Could not load channels. Please try again.",16));
     body.addView(button("RETRY",this::tvGuide));
    });
   }
  });
 }

 void fetchGuideSchedules(int token,List<LibraryCore.Item> channels,int first,int count,
     Map<String,GuideEngine.Slot> cache,Set<String> pending,
     BaseAdapter adapter,String selectedId,GuidePreviewPane preview){
  if(token!=browseToken||!screen.equals("guide"))return;
  List<LibraryCore.Item> needed=new ArrayList<>();
  for(int i=Math.max(0,first);i<Math.min(channels.size(),first+count);i++){
   LibraryCore.Item item=channels.get(i);
   if(cache.containsKey(item.id)||!pending.add(item.id))continue;
   needed.add(item);
  }
  if(needed.isEmpty())return;
  catalogReadIO.execute(()->{
   final Map<String,GuideEngine.Slot> filled=new HashMap<>();
   for(LibraryCore.Item item:needed){
    if(token!=browseToken||Thread.currentThread().isInterrupted())break;
    try{filled.put(item.id,epg.nowNext(item));}catch(Exception ignored){}
   }
   runOnUiThread(()->{
    for(LibraryCore.Item item:needed)pending.remove(item.id);
    if(isDestroyed()||token!=browseToken||!screen.equals("guide"))return;
    cache.putAll(filled);
    adapter.notifyDataSetChanged();
    if(selectedId!=null&&selectedId.equals(guideSelectedId)&&
       filled.containsKey(selectedId)&&preview==guidePreview)
     preview.updateSchedule(filled.get(selectedId));
   });
  });
 }

 void guideSearch(){
  EditText field=new EditText(this);
  field.setText(guideQuery);field.setSingleLine(true);
  field.setHint("Channel name or category");
  field.setTextColor(Color.WHITE);
  field.setPadding(dp(15),dp(12),dp(15),dp(12));
  new AlertDialog.Builder(this).setTitle("Find a channel")
   .setView(field)
   .setPositiveButton("SEARCH",(dialog,which)->{
    guideQuery=field.getText().toString().trim();tvGuide();
   })
   .setNeutralButton("CLEAR",(dialog,which)->{
    guideQuery="";tvGuide();
   })
   .setNegativeButton("CANCEL",null).show();
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
     new AlertDialog.Builder(this).setTitle("Provider TV categories")
      .setItems(list,(d,n)->{guideFilter=list[n];guidePage=0;guideQuery="";tvGuide();}).show();
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
   "Automatic muted preview: "+(prefs.getBoolean("guide.preview.auto",true)?"ON":"OFF"),
   "About Aurora Smart EPG"
  };
  new AlertDialog.Builder(this).setTitle("AuroraTV · Smart EPG")
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
    if(index==7){
     boolean enabled=!prefs.getBoolean("guide.preview.auto",true);
     prefs.edit().putBoolean("guide.preview.auto",enabled).apply();
     if("guide".equals(screen))tvGuide();
     toast(enabled?"Automatic muted previews enabled":"Automatic video previews disabled");
     return;
    }
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
   .setItems(new String[]{
     "▶  Watch channel",
     "Change stream quality / alternate source",
     "Preview channel",
     "Full programme schedule",
     "Match this channel to an EPG source",
     favorites.contains(channel.id)?"Remove favorite":"Add favorite",
     "Hide channel"
    },(d,n)->{
     if(n==0){open(channel);return;}
     if(n==1){chooseAlternateSource(channel);return;}
     if(n==2){showLivePreview(channel);return;}
     if(n==3){showChannelSchedule(channel);return;}
     if(n==4){chooseGuideMatch(channel);return;}
     if(n==5){
      if(!favorites.add(channel.id))favorites.remove(channel.id);
      save();tvGuide();return;
     }
     hidden.add(channel.id);shown.remove(channel.id);save();tvGuide();
    }).show();
 }

 void chooseAlternateSource(LibraryCore.Item channel){
  final int token=browseToken;
  final Set<String> h=new HashSet<>(hidden),hc=new HashSet<>(categories),
      fav=new HashSet<>(favorites),lang=new HashSet<>(allowed),
      manual=new HashSet<>(shown),groups=new HashSet<>(shownCategories);
  final boolean strict=hideUnknown;
  catalogReadIO.execute(()->{
   try{
    List<LibraryCore.Item> streams=store.alternateStreams(
      channel,h,hc,fav,lang,strict,manual,groups);
    runOnUiThread(()->{
     if(isDestroyed()||token!=browseToken||!"guide".equals(screen))return;
     if(streams.size()<=1){
      toast("This network has no other visible provider streams");return;
     }
     String[] options=new String[streams.size()];
     for(int i=0;i<streams.size();i++){
      LibraryCore.Item alternate=streams.get(i);
      String label=(alternate.id.equals(channel.id)?"✓  ":"")+
         alternate.name+" · "+alternate.category;
      options[i]=label.length()>120?label.substring(0,117)+"…":label;
     }
     new AlertDialog.Builder(this).setTitle("Choose stream · "+channel.name)
       .setItems(options,(d,n)->open(streams.get(n)))
       .setNegativeButton("CANCEL",null).show();
    });
   }catch(Exception error){
    runOnUiThread(()->{
     if(!isDestroyed()&&token==browseToken)
      toast("Unable to inspect alternate channel streams");
    });
   }
  });
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
   .setItems(new String[]{"Change or add IPTV source","Refresh library from provider","Smart EPG settings","Playback diagnostics","Library status & import speed","Display density / poster size","Daily Top 20 / TMDB key","Disconnect and clear this device"},(d,n)->{
    if(n==0){loginScreen(false);return;}
    if(n==1){refresh();return;}
    if(n==2){guideSettings();return;}
    if(n==3){showPlaybackDiagnostics();return;}
    if(n==4){showLibraryStatus();return;}
    if(n==5){chooseDisplayDensity();return;}
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

 void chooseDisplayDensity(){
  final String[] values={"comfortable","compact","dense"};
  final String[] labels={
   "Comfortable · larger posters",
   "Compact · recommended (more titles)",
   "Extra compact · maximum titles on screen"
  };
  String current=prefs.getString("display.density","compact");
  int active=1;
  for(int i=0;i<values.length;i++)if(values[i].equals(current))active=i;
  new AlertDialog.Builder(this).setTitle("AuroraTV · Display Density")
   .setSingleChoiceItems(labels,active,(dialog,index)->{
    prefs.edit().putString("display.density",values[index]).apply();
    dialog.dismiss();
    String previous=screen;
    shell();
    if("guide".equals(previous))tvGuide();
    else if("browse".equals(previous))browse();
    else home();
   }).setNegativeButton("CANCEL",null).show();
 }

 void showLibraryStatus(){
  io.execute(()->{
   StringBuilder report=new StringBuilder();
   try{
    report.append("Local library ready: ").append(store.hasLibrary()?"yes":"no");
    report.append("\nLive channels: ").append(store.count("live"));
    report.append("\nMovies: ").append(store.count("movie"));
    report.append("\nSeries: ").append(store.count("series"));
    report.append("\nUpdating catalog: ").append(loading?"yes":"no");
    String pending=prefs.getString("import.pending","");
    report.append("\nPrevious incomplete import: ").append(pending.isEmpty()?"none":pending+" (repair on next startup)");
    long liveMs=prefs.getLong("import.stage.live.ms",0);
    if(liveMs>0)report.append("\nLive TV processing: ").append(liveMs/1000d).append(" seconds");
    for(String kind:new String[]{"vod","series"}){
     long elapsed=prefs.getLong("import.stage."+kind+".ms",0);
     if(elapsed>0)report.append("\n").append(kind.equals("vod")?"Movies":"TV Shows")
       .append(" processing: ").append(elapsed/1000d).append(" seconds");
    }
    long totalMs=prefs.getLong("import.total_ms",0);
    if(totalMs>0)report.append("\nFull catalog import: ").append(totalMs/1000d).append(" seconds");
    report.append("\n\nSection opening (most recent successful load):");
    for(String destination:new String[]{"home","guide","movie","series","live"}){
     long elapsed=prefs.getLong("nav.last."+destination+".ms",-1);
     if(elapsed>=0)
      report.append("\n").append(destination.toUpperCase(Locale.ROOT))
        .append(": ").append(elapsed).append(" ms");
    }
    report.append("\n").append(store.cacheDiagnostics());
    report.append("\n\nScreen timings measure catalog data availability; posters and the optional live preview load separately. Importing is only necessary when changing or refreshing the provider.");
   }catch(Exception e){report.append("Catalog diagnostics unavailable: "+e.getClass().getSimpleName());}
   runOnUiThread(()->{
    if(isDestroyed())return;
    new AlertDialog.Builder(this).setTitle("AuroraTV · Library Status")
      .setMessage(report.toString())
      .setPositiveButton("REFRESH ALL MEDIA",(d,n)->refresh())
      .setNegativeButton("CLOSE",null).show();
   });
  });
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
  new AlertDialog.Builder(this).setTitle("AuroraTV · Playback Health")
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
  if(loading){toast("Your catalog is already updating");return;}
  loading=true;
  final long started=SystemClock.elapsedRealtime();
  final boolean existing=store.hasLibrary();
  final boolean repairing=!prefs.getString("import.pending","").isEmpty();
  final int token=++generation;
  if(!existing||repairing||"loading".equals(screen))
   loadingScreen("PREPARING YOUR COMPLETE LIBRARY",
       "Importing Live TV, Movies and TV Shows. This will run just once.");
  else toast("Refreshing the complete catalog. Your current library remains available.");
  importIO.execute(()->{
   try{
    // All three Xtream media sections use ONE authenticated streaming import,
    // ONE indexed staging transaction and ONE atomic catalog replacement.
    // We never show an incomplete provider library as complete.
    long stageStart=SystemClock.elapsedRealtime();
    final String[] lastStage={""};
    final long[] stageOpenedAt={stageStart};
    final String[] discoveredGuide={null};
    final int count;
    try(LibraryStore.Writer writer=store.writer()){
     if("xtream".equals(mode)){
      count=Provider.xtreamStream(url,user,pass,writer,(stage,done)->{
       if(!stage.equals(lastStage[0])){
        if(!lastStage[0].isEmpty()){
         prefs.edit().putLong("import.stage."+lastStage[0]+".ms",
           SystemClock.elapsedRealtime()-stageOpenedAt[0]).apply();
        }
        lastStage[0]=stage;
        stageOpenedAt[0]=SystemClock.elapsedRealtime();
       }
       String label=stage.equals("live")?"Live TV":stage.equals("vod")?"Movies":"TV Shows";
       status(label+"  •  "+String.format(Locale.US,"%,d",done)+
         " titles processed · Preparing all 3 sections");
      });
     }else{
      count=Provider.m3uStream(url,writer,(stage,done)->
        status("Reading playlist  •  "+String.format(Locale.US,"%,d",done)+
           " titles processed"),xmltv->discoveredGuide[0]=xmltv);
     }
     if(count<=0)throw new IOException("No media titles were supplied by this provider");
     status("Finalizing your complete catalog…");
     writer.commit();
    }
    if(!lastStage[0].isEmpty())
     prefs.edit().putLong("import.stage."+lastStage[0]+".ms",
       SystemClock.elapsedRealtime()-stageOpenedAt[0]).apply();
    boolean changed=true;
    try{
     changed=!mode.equals(prefs.getString("mode",""))||
       !url.equals(Vault.open(prefs.getString("url","")))||
       !user.equals(Vault.open(prefs.getString("user","")))||
       !pass.equals(Vault.open(prefs.getString("pass","")));
    }catch(Exception ignored){}
    // Keep provider credentials sealed once. Xtream entries store only compact
    // references and reconstruct the full URL on selection.
    SharedPreferences.Editor edit=prefs.edit()
      .putString("mode",mode)
      .putString("url",Vault.seal(url))
      .putString("user",Vault.seal(user))
      .putString("pass",Vault.seal(pass))
      .putLong("import.total_ms",SystemClock.elapsedRealtime()-started)
      .putInt("import.total_items",count)
      .remove("import.pending").remove("items");
    if(discoveredGuide[0]!=null&&!prefs.contains("guide.external1"))
     edit.putString("guide.external1",Vault.seal(discoveredGuide[0]));
    if(changed)edit.remove("guide.attempt.provider").remove("guide.updated.provider");
    if(!edit.commit())throw new IOException("Unable to save account details");
    if(changed)epg.clearProvider();
    runOnUiThread(()->{
     if(isDestroyed()||token!=generation)return;
     loading=false;page=0;category="All";query="";
     hiddenOnly=false;favOnly=false;editing=false;items.clear();
     if(!existing||repairing||screen.equals("loading")){shell();home();}
     else if(screen.equals("home"))home();
     else if(screen.equals("browse"))browse();
     else if(screen.equals("guide"))tvGuide();
     toast("All "+String.format(Locale.US,"%,d",count)+" titles are now available.");
    });
   }catch(Exception error){
    runOnUiThread(()->{
     if(isDestroyed()||token!=generation)return;
     loading=false;
     // Errors are displayed without stream URLs, usernames, or passwords.
     String kind=error.getClass().getSimpleName();
     if(store.hasLibrary()&&!repairing){
      toast("Catalog refresh failed ("+kind+"). Your previous library is unchanged.");
      if(screen.equals("loading")){shell();home();}
     }else{
      loadingScreen("LIBRARY IMPORT INTERRUPTED",
        "The provider or device interrupted the import. Your saved titles have not been deleted.");
      root.addView(button("RETRY COMPLETE IMPORT",()->refresh()));
      if(existing)root.addView(button("OPEN PREVIOUS LIBRARY",()->{shell();home();}));
     }
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
  stopGuidePreview();
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
   if(playing!=null&&!playing.type.equals("live")){
    String recentId=playing.id;
    if("episode".equals(playing.type)&&recentId.contains("|"))
     recentId=recentId.substring(0,recentId.indexOf('|'));
    if(recentId.matches("[0-9a-f]{64}")){
     LinkedHashSet<String> recent=new LinkedHashSet<>();
     recent.add(recentId);
     for(String id:prefs.getString("recent.items","").split(",")){
      if(id.matches("[0-9a-f]{64}")&&!id.equals(recentId)&&recent.size()<14)
       recent.add(id);
     }
     prefs.edit().putLong("resume."+playing.id,position)
       .putString("recent.items",android.text.TextUtils.join(",",recent)).apply();
    }else prefs.edit().putLong("resume."+playing.id,position).apply();
   }
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
  stopGuidePreview();
  if(livePreview!=null){livePreview.dismiss();livePreview=null;}
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
  stopGuidePreview();
  if(pendingGuideUpdate!=null)uiHandler.removeCallbacks(pendingGuideUpdate);
  if(livePreview!=null){livePreview.dismiss();livePreview=null;}
  release();io.shutdownNow();catalogReadIO.shutdownNow();importIO.shutdownNow();posters.close();epgRefreshIO.shutdownNow();shortEpgIO.shutdownNow();guideDirectoryIO.shutdownNow();store.close();epg.close();
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
