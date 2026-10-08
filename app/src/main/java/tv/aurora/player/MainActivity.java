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
 final int BG=0xff080f1d,PANEL=0xff142238,ACCENT=0xff54e0c5;
 LinearLayout root,body,nav;TextView subtitle;SharedPreferences prefs;ExecutorService io=Executors.newSingleThreadExecutor();List<LibraryCore.Item> items=new ArrayList<>();List<Provider.Program> guide=new ArrayList<>();Map<String,List<Provider.Program>> guideIndex=new HashMap<>();Set<String> hidden,categories,favorites,allowed,shown,shownCategories;boolean hideUnknown;String section="live",query="",category="All";boolean editing=false,favOnly=false,hiddenOnly=false;LibraryCore.Item selected,playing;ExoPlayer player;PlayerView playerView;boolean loading=false;int generation=0;int browseToken=0;int page=0;static final int PAGE_SIZE=200;LibraryStore store;GuideEngine epg;int guidePage=0;ExecutorService epgRefreshIO=Executors.newSingleThreadExecutor(),shortEpgIO=Executors.newSingleThreadExecutor();Map<String,String> guideSummary=new HashMap<>();String screen="login",screenBeforePlayer="home";TextView loadingStatus;ExecutorService artworkIO=Executors.newFixedThreadPool(3);LruCache<String,Bitmap> artworkCache=new LruCache<String,Bitmap>(8192){@Override protected int sizeOf(String key,Bitmap bitmap){return bitmap.getByteCount()/1024;}};
 @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().getDecorView().setSystemUiVisibility(5894);prefs=getSharedPreferences("library",MODE_PRIVATE);store=new LibraryStore(this);epg=new GuideEngine(this);hidden=set("hidden");categories=set("categories");favorites=set("favorites");allowed=set("allowed");shown=set("shown");shownCategories=set("shownCategories");hideUnknown=prefs.getBoolean("unknown",false);
   if(!prefs.getBoolean("smartFilterV2",false)&&!allowed.isEmpty()){
    // An existing English-only choice previously retained unclassified groups.
    // Migrate once to strict filtering; user can turn this off in Smart Library.
    hideUnknown=true;
    prefs.edit().putBoolean("unknown",true).putBoolean("smartFilterV2",true).apply();
   }
   start();}
 Set<String> set(String k){return new HashSet<>(prefs.getStringSet(k,new HashSet<>()));}
 void save(){prefs.edit().putStringSet("hidden",hidden).putStringSet("categories",categories).putStringSet("favorites",favorites).putStringSet("allowed",allowed).putStringSet("shown",shown).putStringSet("shownCategories",shownCategories).putBoolean("unknown",hideUnknown).putBoolean("smartFilterV2",true).apply();}
 int dp(int v){return (int)(v*getResources().getDisplayMetrics().density);}
 LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
 TextView text(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextColor(Color.WHITE);t.setTextSize(size);t.setPadding(dp(8),dp(6),dp(8),dp(6));return t;}
 GradientDrawable shape(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(12));return d;}
 Button button(String s,Runnable action){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setTextSize(16);b.setBackground(shape(PANEL));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(52));lp.setMargins(dp(4),dp(4),dp(4),dp(4));b.setLayoutParams(lp);b.setOnFocusChangeListener((v,f)->{b.setBackground(shape(f?ACCENT:PANEL));b.setTextColor(f?BG:Color.WHITE);});b.setOnClickListener(v->action.run());return b;}

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
  root=column();root.setBackground(gradient(0xff07111f,0xff143a43,0));root.setGravity(Gravity.CENTER);
  root.setPadding(dp(50),dp(26),dp(50),dp(26));setContentView(root);
  TextView logo=headline("A U R O R A  /  T V",32,ACCENT);logo.setGravity(Gravity.CENTER);
  root.addView(logo);
  View stroke=new View(this);stroke.setBackgroundColor(ACCENT);
  LinearLayout.LayoutParams line=new LinearLayout.LayoutParams(dp(130),dp(3));line.topMargin=dp(22);line.bottomMargin=dp(25);root.addView(stroke,line);
  TextView t=headline(title,28,Color.WHITE);t.setGravity(Gravity.CENTER);root.addView(t);
  loadingStatus=text(message,18);loadingStatus.setGravity(Gravity.CENTER);
  loadingStatus.setTextColor(0xffb6c8d6);root.addView(loadingStatus);
  ProgressBar progress=new ProgressBar(this);progress.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
  LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(48),dp(48));p.topMargin=dp(32);root.addView(progress,p);
 }
 void status(String message){
  runOnUiThread(()->{if(!isDestroyed()&&screen.equals("loading")&&loadingStatus!=null)loadingStatus.setText(message);});
 }
 void loginScreen(boolean m3u){
  screen="login";
  root=column();root.setBackground(gradient(0xff070f1f,0xff142c3c,0));
  root.setPadding(dp(42),dp(18),dp(42),dp(18));root.setGravity(Gravity.CENTER);
  setContentView(root);
  TextView label=headline("A U R O R A  /  T V",30,ACCENT);root.addView(label);
  TextView title=headline("Your world of entertainment starts here.",26,Color.WHITE);root.addView(title);
  TextView caption=text("Connect your IPTV provider to unlock live channels, movies and series.",17);
  caption.setTextColor(0xffa5b6c7);root.addView(caption);
  LinearLayout panel=column();panel.setPadding(dp(24),dp(18),dp(24),dp(18));panel.setBackground(gradient(PANEL,0xff172f45,14));
  LinearLayout.LayoutParams pane=new LinearLayout.LayoutParams(Math.min(dp(570),getResources().getDisplayMetrics().widthPixels-dp(80)),-2);
  pane.topMargin=dp(16);root.addView(panel,pane);
  LinearLayout tabs=new LinearLayout(this);panel.addView(tabs);
  Button xt=button("Xtream Codes",()->loginScreen(false));
  Button ml=button("M3U Playlist",()->loginScreen(true));
  tabs.addView(xt,new LinearLayout.LayoutParams(0,dp(52),1));
  tabs.addView(ml,new LinearLayout.LayoutParams(0,dp(52),1));
  (m3u?ml:xt).setBackground(shape(0xff157c76));
  EditText url=field(panel,m3u?"Playlist URL (https://...)":"Server URL (http://... or https://...)",false);
  EditText user=m3u?null:field(panel,"Username",false);
  EditText pass=m3u?null:field(panel,"Password",true);
  String storedUrl="";
  try{storedUrl=Vault.open(prefs.getString("url",""));}catch(Exception ignored){}
  if(!storedUrl.isEmpty())url.setText(storedUrl);
  panel.addView(button("CONNECT & IMPORT  →",()->{
   String address=url.getText().toString().trim();
   String u=user==null?"":user.getText().toString().trim();
   String p=pass==null?"":pass.getText().toString();
   if(address.isEmpty()||(!m3u&&(u.isEmpty()||p.isEmpty()))){toast("Enter your provider login details");return;}
   importSource(m3u?"m3u":"xtream",address,u,p);
  }));
  TextView note=text("Credentials are stored privately on this device. Use only services you are authorized to access.",12);
  note.setTextColor(0xff98adbd);root.addView(note);
  url.requestFocus();
 }
 void shell(){
  screen="home";
  root=column();root.setBackgroundColor(BG);root.setPadding(dp(24),dp(15),dp(24),dp(16));setContentView(root);
  LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);root.addView(top);
  TextView logo=headline("A U R O R A  /  T V",24,ACCENT);top.addView(logo);
  subtitle=text("Discover more. Watch your way.",14);subtitle.setTextColor(0xff9eb2c6);
  LinearLayout.LayoutParams meta=new LinearLayout.LayoutParams(-2,-2);meta.leftMargin=dp(25);top.addView(subtitle,meta);
  LinearLayout row=new LinearLayout(this);LinearLayout.LayoutParams main=new LinearLayout.LayoutParams(-1,0,1);main.topMargin=dp(16);root.addView(row,main);
  nav=column();nav.setPadding(0,dp(4),dp(12),0);row.addView(nav,new LinearLayout.LayoutParams(dp(182),-1));
  nav.addView(button("⌂  Home",this::home));
  for(String[] entry:new String[][]{{"Live TV","live"},{"Movies","movie"},{"TV Shows","series"}}){
   nav.addView(button(entry[0],()->{section=entry[1];favOnly=false;hiddenOnly=false;editing=false;page=0;category="All";query="";browse();}));
  }
  nav.addView(button("▦  TV Guide",()->{section="live";category="All";guidePage=0;tvGuide();}));
  nav.addView(button("★  Favorites",()->{favOnly=true;hiddenOnly=false;page=0;browse();}));
  nav.addView(button("Search",this::search));
  nav.addView(button("Edit Library",this::manage));
  nav.addView(button("Connect / Refresh",this::connect));
  body=column();body.setPadding(dp(18),0,0,0);row.addView(body,new LinearLayout.LayoutParams(0,-1,1));
 }
 boolean visible(LibraryCore.Item i){return LibraryCore.visible(i,hidden,categories,favorites,allowed,hideUnknown,shown,shownCategories);}
 void home(){
  if(!store.hasLibrary()){loginScreen(false);return;}
  screen="home";
  final int token=++browseToken;
  body.removeAllViews();
  body.addView(text("Preparing your home screen…",20));
  final Set<String> h=new HashSet<>(hidden),c=new HashSet<>(categories),
      fav=new HashSet<>(favorites),langs=new HashSet<>(allowed),manual=new HashSet<>(shown),manualGroups=new HashSet<>(shownCategories);
  final boolean unknown=hideUnknown;
  io.execute(()->{
   try{
    LibraryStore.Page live=store.page("live","All","",false,false,h,c,fav,langs,unknown,manual,manualGroups,0,10);
    LibraryStore.Page movies=store.page("movie","All","",false,false,h,c,fav,langs,unknown,manual,manualGroups,0,10);
    LibraryStore.Page series=store.page("series","All","",false,false,h,c,fav,langs,unknown,manual,manualGroups,0,10);
    int l=store.count("live"),m=store.count("movie"),t=store.count("series");
    runOnUiThread(()->{
     if(isDestroyed()||token!=browseToken||!screen.equals("home"))return;
     body.removeAllViews();
     subtitle.setText(String.format(Locale.US,"%1$,d titles available · Your library, your rules",l+m+t));
     ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);body.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
     LinearLayout feed=column();feed.setPadding(0,0,dp(12),dp(30));scroll.addView(feed);
     LinearLayout hero=column();hero.setPadding(dp(24),dp(16),dp(24),dp(18));
     hero.setBackground(gradient(0xff185a64,0xff102239,20));feed.addView(hero);
     TextView eyebrow=headline("ALL YOUR ENTERTAINMENT. ONE PLACE.",12,ACCENT);hero.addView(eyebrow);
     hero.addView(headline("Welcome to Aurora",32,Color.WHITE));
     TextView description=text("Browse live television, discover a film or settle in for a series.\nCurated from your own IPTV library.",16);
     description.setTextColor(0xffc6d9e3);hero.addView(description);
     hero.addView(button("EXPLORE LIVE TV  →",()->{section="live";page=0;category="All";favOnly=false;hiddenOnly=false;browse();}));
     homeShelf(feed,"LIVE TELEVISION","live",l,live.rows);
     homeShelf(feed,"MOVIES FOR YOU","movie",m,movies.rows);
     homeShelf(feed,"SERIES TO EXPLORE","series",t,series.rows);
    });
   }catch(Exception e){
    runOnUiThread(()->{if(isDestroyed()||token!=browseToken)return;body.removeAllViews();body.addView(text("Unable to load your catalog: "+e.getClass().getSimpleName(),19));body.addView(button("Refresh your library",this::refresh));});
   }
  });
 }
 void homeShelf(LinearLayout feed,String title,String type,int count,List<LibraryCore.Item> rows){
  LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
  LinearLayout.LayoutParams margin=new LinearLayout.LayoutParams(-1,-2);margin.topMargin=dp(15);feed.addView(top,margin);
  TextView heading=headline(title,21,Color.WHITE);top.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
  TextView total=text(String.format(Locale.US,"%1$,d titles",count),14);total.setTextColor(0xffadc4d4);top.addView(total);
  HorizontalScrollView scroller=new HorizontalScrollView(this);scroller.setHorizontalScrollBarEnabled(false);feed.addView(scroller);
  LinearLayout cards=new LinearLayout(this);cards.setOrientation(LinearLayout.HORIZONTAL);scroller.addView(cards);
  if(rows.isEmpty()){cards.addView(text("No visible titles. Try changing your library filters.",15));return;}
  for(LibraryCore.Item item:rows)cards.addView(mediaCard(item,type));
  cards.addView(button("VIEW ALL  →",()->{section=type;page=0;category="All";query="";favOnly=false;hiddenOnly=false;browse();}),
    new LinearLayout.LayoutParams(dp(170),dp(174)));
 }
 View mediaCard(LibraryCore.Item item,String type){
  LinearLayout card=column();card.setPadding(dp(5),dp(5),dp(5),dp(5));
  LinearLayout.LayoutParams outer=new LinearLayout.LayoutParams(dp(178),dp(191));outer.rightMargin=dp(12);card.setLayoutParams(outer);
  int start=type.equals("live")?0xff117168:type.equals("movie")?0xff5b3c79:0xff255f83;
  FrameLayout artwork=new FrameLayout(this);artwork.setBackground(gradient(start,PANEL,14));
  card.addView(artwork,new LinearLayout.LayoutParams(-1,dp(123)));
  TextView badge=headline(type.equals("live")?"● LIVE":type.equals("movie")?"◆ MOVIE":"▣ SERIES",12,0xffd5fff6);
  FrameLayout.LayoutParams badgeParams=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.LEFT);
  badgeParams.setMargins(dp(10),dp(8),0,0);artwork.addView(badge,badgeParams);
  TextView letter=headline(item.name.isEmpty()?"A":item.name.substring(0,1).toUpperCase(Locale.ROOT),52,0x66ffffff);
  FrameLayout.LayoutParams initial=new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER);artwork.addView(letter,initial);
  displayArtwork(artwork,item.artwork);
  TextView title=text(item.name,15);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);
  card.addView(title);
  card.setOnClickListener(v->open(item));
  card.setFocusable(true);card.setBackground(shape(PANEL));
  card.setOnFocusChangeListener((v,focused)->card.setBackground(shape(focused?0xff1d746f:PANEL)));
  return card;
 }
 void displayArtwork(FrameLayout frame,String url){
  if(url==null||!url.startsWith("http")||url.length()>1000)return;
  if(url.matches("(?i).*(username=|password=|token=).*"))return;
  Bitmap existing=artworkCache.get(url);
  ImageView poster=new ImageView(this);poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
  FrameLayout.LayoutParams size=new FrameLayout.LayoutParams(-1,-1);
  frame.addView(poster,0,size);
  if(existing!=null){poster.setImageBitmap(existing);return;}
  artworkIO.execute(()->{
   Bitmap bitmap=null;
   HttpURLConnection conn=null;
   try{
    conn=(HttpURLConnection)new URL(url).openConnection();
    conn.setConnectTimeout(4500);conn.setReadTimeout(4500);
    if(conn.getResponseCode()!=200||conn.getContentLength()>2*1024*1024)return;
    try(InputStream in=conn.getInputStream()){
     BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=4;
     bitmap=BitmapFactory.decodeStream(in,null,options);
    }
   }catch(Exception ignored){}finally{if(conn!=null)conn.disconnect();}
   if(bitmap!=null){
    Bitmap safe=bitmap;
    artworkCache.put(url,safe);
    runOnUiThread(()->{if(!isDestroyed())poster.setImageBitmap(safe);});
   }
  });
 }


 void browse(){
  if(!store.hasLibrary()){loginScreen(false);return;}
  screen="browse";
  final int token=++browseToken;
  final String type=section,cat=category,search=query;
  final boolean showHidden=hiddenOnly,onlyFavorites=favOnly,isEditing=editing,hide=hideUnknown;
  final int requested=page;
  final Set<String> h=new HashSet<>(hidden),hc=new HashSet<>(categories),
     fav=new HashSet<>(favorites),lang=new HashSet<>(allowed),manual=new HashSet<>(shown),manualGroups=new HashSet<>(shownCategories);
  body.removeAllViews();
  body.addView(text("Finding your "+(type.equals("live")?"channels":type.equals("movie")?"movies":"series")+"…",18));
  io.execute(()->{
   try{
    LibraryStore.Page result=store.page(type,cat,search,showHidden,onlyFavorites,h,hc,fav,lang,hide,
         manual,manualGroups,requested*PAGE_SIZE,PAGE_SIZE);
    final Map<String,String> brief=new HashMap<>();
    if(type.equals("live")&&!showHidden){
     for(int x=0;x<Math.min(65,result.rows.size());x++){
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
     Button back=button("⌂ Home",this::home);heading.addView(back,new LinearLayout.LayoutParams(dp(135),dp(52)));
     body.addView(heading);
     body.addView(button("CATEGORY  /  "+cat+"    ▾",this::chooseCategory));
     if(isEditing)body.addView(text("Select a title to favorite or hide it.",14));
     if(result.rows.isEmpty()){
      if(requested>0){page=0;browse();return;}
      body.addView(text("No matching titles. Try a different category or search.",18));
      return;
     }
     subtitle.setText("Browse without waiting for the entire library");
     body.addView(text("Showing "+(requested*PAGE_SIZE+1)+"–"+
         (requested*PAGE_SIZE+result.rows.size())+(result.more?"+":"")+" matching titles",15));
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
       t.setText((favorites.contains(i.id)?"★  ":"")+i.name+"   ·   "+i.category+
          (i.type.equals("live")?nowNext(i):""));
       t.setMaxLines(3);
       t.setPadding(dp(14),dp(12),dp(14),dp(12));
       t.setBackground(shape(PANEL));
       return t;
      }
     });
     list.setSelector(shape(0xff27786c));
     list.setOnItemClickListener((parent,v,n,id)->{
      LibraryCore.Item picked=result.rows.get(n);
      if(editing)actions(picked);else open(picked);
     });
     list.setOnItemLongClickListener((parent,v,n,id)->{actions(result.rows.get(n));return true;});
     LinearLayout navigation=new LinearLayout(this);
     if(requested>0)navigation.addView(button("◀ Previous",()->{page--;browse();}),new LinearLayout.LayoutParams(0,dp(55),1));
     if(result.more)navigation.addView(button("Next ▶",()->{page++;browse();}),new LinearLayout.LayoutParams(0,dp(55),1));
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


 void search(){
  new AlertDialog.Builder(this).setTitle("SEARCH YOUR LIBRARY")
   .setItems(new String[]{"Live TV","Movies","TV Shows"},(d,index)->{
    section=index==0?"live":index==1?"movie":"series";
    EditText entry=new EditText(this);entry.setTextColor(Color.WHITE);entry.setHintTextColor(0xffbbbbbb);
    entry.setSingleLine();entry.setHint("Search "+(index==0?"channels":index==1?"movies":"series"));
    new AlertDialog.Builder(this).setTitle("Search "+(index==0?"Live TV":index==1?"Movies":"TV Shows"))
     .setView(entry).setPositiveButton("Search",(a,b)->{
       query=entry.getText().toString().trim();category="All";page=0;
       hiddenOnly=false;favOnly=false;editing=false;browse();
     }).setNegativeButton("Cancel",null).show();
   }).show();
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
     shown.add(i.id);
     categories.remove(i.type+"|"+i.category); // manual re-enable wins filter
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
  new AlertDialog.Builder(this).setTitle("EDIT LIBRARY")
   .setItems(new String[]{
     editing?"Finish editing":"Edit individual titles",
     "Manage categories · hide by group",
     "Smart language filter · Live TV",
     "Hidden channels · restore anything filtered",
     "Restore an entire filtered category",
     "Manage manually restored categories",
     "Undo previous bulk filter change",
     "Return to visible library"
   },(d,n)->{
    if(n==0)editing=!editing;
    if(n==1){manageCategories();return;}
    if(n==2){smart();return;}
    if(n==3){hiddenOnly=true;editing=true;favOnly=false;section="live";page=0;category="All";query="";}
    if(n==4){restoreCategory();return;}
    if(n==5){manageRestoredCategories();return;}
    if(n==6)undo();
    if(n==7){hiddenOnly=false;editing=false;category="All";page=0;}
    browse();
   }).show();
 }
 void restoreCategory(){
  final Set<String> h=new HashSet<>(hidden),c=new HashSet<>(categories),fav=new HashSet<>(favorites),
    lang=new HashSet<>(allowed),manual=new HashSet<>(shown),manualGroups=new HashSet<>(shownCategories);
  io.execute(()->{
   try{
    String[] groups=store.visibleCategoryNames("live",true,h,c,fav,lang,hideUnknown,manual,manualGroups);
    runOnUiThread(()->{
     if(isDestroyed())return;
     if(groups.length==0){toast("No hidden categories");return;}
     new AlertDialog.Builder(this).setTitle("Restore a hidden category")
      .setItems(groups,(d,n)->{
       snapshot();String key="live|"+groups[n];
       shownCategories.add(key);categories.remove(key);
       save();section="live";category=groups[n];hiddenOnly=false;editing=false;page=0;browse();
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
   "Strict filtering · hide unknown / ambiguous channels"
  };
  boolean[] checks=new boolean[labels.length];
  for(int i=0;i<codes.length;i++)checks[i]=allowed.contains(codes[i]);
  checks[codes.length]=hideUnknown||(!allowed.isEmpty()&&!prefs.getBoolean("smartFilterV2",false));
  new AlertDialog.Builder(this).setTitle("Smart Library · preferred languages")
   .setMultiChoiceItems(labels,checks,(d,n,c)->checks[n]=c)
   .setPositiveButton("Preview",(d,w)->{
    Set<String> selectedLanguages=new HashSet<>();
    for(int i=0;i<codes.length;i++)if(checks[i])selectedLanguages.add(codes[i]);
    boolean strict=checks[codes.length];
    final Set<String> h=new HashSet<>(hidden),c=new HashSet<>(categories),fav=new HashSet<>(favorites),
      manual=new HashSet<>(shown),manualGroups=new HashSet<>(shownCategories);
    io.execute(()->{
     int[] counts={0,0,0};
     Set<String> visibleGroups=new HashSet<>(),hiddenGroups=new HashSet<>();
     try{
      store.forEach(i->{
       if(i.type.equals("live")){
        if(LibraryCore.language(i).equals("unknown"))counts[1]++;
        boolean v=LibraryCore.visible(i,h,c,fav,selectedLanguages,strict,manual,manualGroups);
        if(!v){counts[0]++;hiddenGroups.add(i.category);}
        else visibleGroups.add(i.category);
       }
       return true;
      });
      for(String group:hiddenGroups)if(!visibleGroups.contains(group))counts[2]++;
     }catch(Exception e){runOnUiThread(()->toast("Unable to preview filters"));return;}
     runOnUiThread(()->{
      if(isDestroyed())return;
      new AlertDialog.Builder(this).setTitle("Filter preview")
       .setMessage(String.format(Locale.US,"%,d live channels will be hidden.\n%,d categories will disappear completely.\n%,d channels have uncertain language.\n\nManual restorations and favorites stay available. No languages selected disables the language filter.",counts[0],counts[2],counts[1]))
       .setPositiveButton("APPLY FILTER",(a,b)->{
        snapshot();allowed=selectedLanguages;hideUnknown=strict;
        prefs.edit().putBoolean("smartFilterV2",true).apply();
        save();category="All";page=0;
        if(screen.equals("guide"))tvGuide();else browse();
       })
       .setNegativeButton("Cancel",null).show();
     });
    });
   }).setNegativeButton("Cancel",null).show();
 }

 EditText field(LinearLayout form,String hint,boolean secret){EditText e=new EditText(this);e.setHint(hint);e.setTextColor(Color.WHITE);e.setHintTextColor(0xff9caebe);e.setBackgroundTintList(ColorStateList.valueOf(ACCENT));e.setSingleLine();if(secret)e.setInputType(129);form.addView(e);return e;}

 void connect(){
  new AlertDialog.Builder(this).setTitle("MANAGE YOUR CONNECTION")
   .setItems(new String[]{"Change or add IPTV source","Refresh library from provider","Smart EPG settings","Disconnect and clear this device"},(d,n)->{
    if(n==0){loginScreen(false);return;}
    if(n==1){refresh();return;}
    if(n==2){guideSettings();return;}
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
    try(LibraryStore.Writer writer=store.writer()){
     if(mode.equals("xtream")){
      count=Provider.xtreamStream(url,user,pass,writer,(stage,done)->{
       String phase=stage.equals("live")?"LIVE TELEVISION":stage.equals("vod")?"MOVIES":"TV SERIES";
       status(phase+"  ·  "+String.format(Locale.US,"%,d titles imported",done));
      });
     }else{
      status("Reading M3U playlist…");
      count=Provider.m3uStream(url,writer,(stage,done)->
        status(String.format(Locale.US,"%,d playlist entries imported",done)));
     }
     if(count==0)throw new IOException("No supported titles were returned by this provider.");
     status("Finishing your library index…");
     writer.commit();
    }
    String address=Vault.seal(url),account=Vault.seal(user),secret=Vault.seal(pass);
    runOnUiThread(()->{
     if(isDestroyed()||token!=generation)return;
     prefs.edit().putString("mode",mode).putString("url",address)
       .putString("user",account).putString("pass",secret).remove("items").apply();
     loading=false;items.clear();page=0;category="All";query="";
     hiddenOnly=false;favOnly=false;editing=false;
     shell();home();
     // EPG is opt-in and never downloaded during the import.
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

 void play(LibraryCore.Item i){screenBeforePlayer=screen;screen="player";release();playing=i;LinearLayout layout=column();layout.setBackgroundColor(BG);playerView=new PlayerView(this);layout.addView(playerView,new LinearLayout.LayoutParams(-1,0,1));LinearLayout controls=new LinearLayout(this);layout.addView(controls);Button pause=button("Play / Pause",()->{if(player.isPlaying())player.pause();else player.play();});controls.addView(pause,new LinearLayout.LayoutParams(0,dp(60),1));if(i.type.equals("live")){Button live=button("Go to live",()->{if(player.isCurrentMediaItemLive()){player.seekToDefaultPosition();player.play();}else toast("This stream does not expose a live timeline");});controls.addView(live,new LinearLayout.LayoutParams(0,dp(60),1));}else{Button rewind=button("−30 seconds",()->player.seekTo(Math.max(0,player.getCurrentPosition()-30000)));controls.addView(rewind,new LinearLayout.LayoutParams(0,dp(60),1));}controls.addView(button("Back to library",()->{release();shell();if(screenBeforePlayer.equals("home"))home();else browse();}),new LinearLayout.LayoutParams(0,dp(60),1));setContentView(layout);player=new ExoPlayer.Builder(this).build();playerView.setPlayer(player);player.setMediaItem(MediaItem.fromUri(i.url));player.addListener(new Player.Listener(){@Override public void onPlayerError(PlaybackException error){toast("Playback failed. Try another channel or check your source.");}});player.prepare();if(!i.type.equals("live"))player.seekTo(prefs.getLong("resume."+i.id,0));player.play();pause.requestFocus();}
 void release(){if(player!=null){if(playing!=null&&!playing.type.equals("live"))prefs.edit().putLong("resume."+playing.id,player.getCurrentPosition()).apply();player.release();player=null;playerView=null;playing=null;}}
 @Override public boolean onKeyDown(int key,KeyEvent e){if(key==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE&&player!=null){if(player.isPlaying())player.pause();else player.play();return true;}if(key==KeyEvent.KEYCODE_MENU&&player==null){manage();return true;}return super.onKeyDown(key,e);}

 @Override public void onBackPressed(){
  if(player!=null){
   release();shell();if(screenBeforePlayer.equals("home"))home();else browse();
  }else if(screen.equals("browse")||screen.equals("guide")){
   if(editing||hiddenOnly||favOnly||!query.isEmpty()){
    editing=false;hiddenOnly=false;favOnly=false;query="";page=0;
   }
   home();
  }else if(!loading)super.onBackPressed();
 }
 @Override protected void onStop(){
  super.onStop();
  if(player!=null){release();shell();home();}
 }
 @Override protected void onDestroy(){
  generation++;browseToken++;
  release();io.shutdownNow();artworkIO.shutdownNow();epgRefreshIO.shutdownNow();shortEpgIO.shutdownNow();store.close();epg.close();
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
