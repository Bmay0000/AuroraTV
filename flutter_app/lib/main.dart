import 'dart:async';
import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:video_player/video_player.dart';
import 'catalog.dart';
import 'provider.dart';
import 'metadata.dart';
import 'epg.dart';
import 'channel_lineup.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setPreferredOrientations([DeviceOrientation.landscapeLeft,DeviceOrientation.landscapeRight]);
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
  runApp(const AuroraApp());
}

class C {
  static const canvas=Color(0xff060a10);
  static const surface=Color(0xff13212b);
  static const ink=Color(0xffeaf0f3);
  static const secondary=Color(0xffa3b7c0);
  static const aqua=Color(0xff5debd0);
  static const gold=Color(0xfff0bc57);
}

class AuroraApp extends StatelessWidget {
  const AuroraApp({super.key});
  @override Widget build(BuildContext context)=>MaterialApp(
    debugShowCheckedModeBanner:false,title:'AuroraTV',
    theme:ThemeData.dark(useMaterial3:true).copyWith(
      scaffoldBackgroundColor:C.canvas,
      colorScheme:const ColorScheme.dark(primary:C.aqua,surface:C.surface),
      textTheme:const TextTheme(
        headlineLarge:TextStyle(fontSize:40,fontWeight:FontWeight.w800,color:Colors.white,height:1.06),
        titleLarge:TextStyle(fontSize:22,fontWeight:FontWeight.w700,color:Colors.white),
        titleMedium:TextStyle(fontSize:17,fontWeight:FontWeight.w600,color:Colors.white),
        bodyMedium:TextStyle(fontSize:15,color:C.ink,height:1.4),
      ),
    ),
    home:const AuroraShell(),
  );
}

class AuroraShell extends StatefulWidget {
  const AuroraShell({super.key});
  @override State<AuroraShell> createState()=>_AuroraShellState();
}
class _AuroraShellState extends State<AuroraShell>{
  final db=CatalogDatabase(),provider=ProviderClient(),tmdb=TmdbClient(),epg=XmltvService();
  IptvSource? source;
  int page=0,focusRevision=0;
  final ValueNotifier<int> heroVersion=ValueNotifier<int>(0);
  final ValueNotifier<int> guideRevision=ValueNotifier<int>(0);
  final List<FocusNode> navFocus=List.generate(7,(i)=>FocusNode(debugLabel:'Nav $i'));
  final ScrollController navScroll=ScrollController();
  bool ready=false,loading=false,showLogin=false,trailers=false,previewOn=true;
  String status='',epgUrl='';
  MediaEntry? featured;
  MovieMeta meta=const MovieMeta();
  final Map<MediaKind,List<MediaEntry>> shelves={};
  final Map<MediaKind,List<MediaEntry>> trends={};
  final Map<MediaKind,Map<String,List<MediaEntry>>> popularGenres={};
  final Map<MediaKind,List<MediaEntry>> discoverToday={};
  final Map<MediaKind,List<String>> groups={};
  final Map<MediaKind,List<MediaEntry>> favorites={};
  List<MediaEntry> live=[];
  List<MediaEntry> recentHistory=[];
  String channelGroup='North America';
  int selectedCategory=0;
  bool englishFirst=true;

  @override void initState(){super.initState();_restore();}
  Future<void> _restore() async{
    try{
      await db.open();
      await tmdb.restore();
      source=await IptvCredentials.load();
      final prefs=await SharedPreferences.getInstance();
      epgUrl=prefs.getString('epg.url')??'';
      trailers=prefs.getBool('trailers')??false;
      previewOn=prefs.getBool('preview')??true;
      englishFirst=prefs.getBool('english.first')??true;
      await _reload();
      if(!mounted)return;
      setState((){ready=true;showLogin=source==null;});
      _getTrends();
      if(source!=null) _refreshEpg(silent:true);
    }catch(e){if(mounted)setState((){status='Startup failed: $e';ready=true;showLogin=true;});}
  }
  Future<void> _reload() async{
    for(final kind in MediaKind.values){
      shelves[kind]=await db.list(kind,limit:32);
      groups[kind]=await db.categories(kind);
      favorites[kind]=await db.list(kind,favorites:true,limit:60);
    }
    live=shelves[MediaKind.live]??[];
    final prefs=await SharedPreferences.getInstance();
    recentHistory=await db.byIds(prefs.getStringList('recent.ids')??[]);
    final picks=trends[MediaKind.movie]??[];
    final movies=shelves[MediaKind.movie]??[];
    final ranked=movies.where((e)=>e.likelyEnglish&&e.rating>=6.0&&e.artwork.isNotEmpty).toList()
      ..sort((a,b)=>b.rating.compareTo(a.rating));
    final next=picks.isNotEmpty?picks.first:ranked.isNotEmpty?ranked.first:
      movies.firstWhere((e)=>e.likelyEnglish&&e.artwork.isNotEmpty,
        orElse:()=>movies.isNotEmpty?movies.first:const MediaEntry(id:'empty',title:'Browse your library',kind:MediaKind.movie));
    if(next.id!='empty')_feature(next);
  }
  MediaEntry _withTmdbArt(MediaEntry providerItem,MediaEntry tmdbItem) => MediaEntry(
    id:providerItem.id,title:providerItem.title,kind:providerItem.kind,
    category:providerItem.category,
    artwork:tmdbItem.artwork.isNotEmpty?tmdbItem.artwork:providerItem.artwork,
    extension:providerItem.extension,streamId:providerItem.streamId,
    directUrl:providerItem.directUrl,epgId:providerItem.epgId,
    year:providerItem.year>0?providerItem.year:tmdbItem.year,
    rating:tmdbItem.rating>0?tmdbItem.rating:providerItem.rating,
    favorite:providerItem.favorite,hidden:providerItem.hidden);
  Future<void> _getTrends() async{
    if(tmdb.apiKey.isEmpty)return;
    for(final kind in [MediaKind.series,MediaKind.movie]){
      final daily=await tmdb.discovery(kind);
      final groupsByGenre=<String,List<MediaEntry>>{};
      for(final genre in TmdbClient.genres.entries){
        if(kind==MediaKind.movie&&genre.key>=10759)continue;
        if(kind==MediaKind.series&&(genre.key==10749||genre.key==878||genre.key==10770))continue;
        final titles=await tmdb.discovery(kind,window:'week',genreId:genre.key);
        if(!mounted)return;
        if(titles.isNotEmpty)groupsByGenre[genre.value]=titles;
      }
      final names=<String>{
        ...daily.map((e)=>e.title),
        ...groupsByGenre.values.expand((rows)=>rows.map((e)=>e.title)),
      };
      // One scan of the entire local IPTV catalog, not a search limited to
      // 60 titles for every genre. This is indexed by canonical title.
      final matching=await db.matchTitleMap(kind,names.toList());
      if(!mounted)return;
      List<MediaEntry> available(List<MediaEntry> tmdbItems)=>[
        for(final item in tmdbItems)
          if(matching[item.title]!=null)_withTmdbArt(matching[item.title]!,item)
      ];
      final matchedGenres=<String,List<MediaEntry>>{};
      for(final row in groupsByGenre.entries){
        final titles=available(row.value);
        if(titles.isNotEmpty)matchedGenres[row.key]=titles;
      }
      setState((){
        discoverToday[kind]=available(daily);
        popularGenres[kind]=matchedGenres;
      });
      if(kind==MediaKind.series && page==0 && (discoverToday[kind]?.isNotEmpty??false)){
        _feature(discoverToday[kind]!.first);
      }
    }
  }
  void _feature(MediaEntry item){
    final id=++focusRevision;
    if(!mounted)return;
    featured=item;meta=const MovieMeta();
    heroVersion.value++;
    tmdb.details(item).then((result){
      if(mounted&&id==focusRevision){
        meta=result;
        heroVersion.value++;
      }
    });
  }
  Future<void> _connect(IptvSource next)async{
    setState((){loading=true;status='Checking provider…';});
    try{
      await provider.verify(next);
      await IptvCredentials.save(next);
      source=next;
      final count=await provider.import(next,db,(message){
        if(mounted)setState(()=>status=message);
      });
      await _reload();
      if(!mounted)return;
      setState((){loading=false;showLogin=false;status='Imported $count titles and channels';page=0;});
      _getTrends();
      _refreshEpg(silent:true);
    }catch(e){if(mounted)setState((){loading=false;status=e.toString();});}
  }
  Future<void> _refresh()async{
    if(source==null)return;
    setState((){loading=true;status='Refreshing provider…';});
    try{
      final count=await provider.import(source!,db,(message){
        if(mounted)setState(()=>status=message);
      });
      await _reload();
      if(mounted)setState((){loading=false;status='Refreshed $count entries';});
      _getTrends();
    }catch(e){if(mounted)setState((){loading=false;status='Refresh failed: $e';});}
  }
  Future<void> _refreshEpg({bool silent=false}) async{
    if(source==null)return;
    if(!silent)setState((){loading=true;status='Importing programme information…';});
    try{
      final count=await epg.refresh(source!,db,externalUrl:epgUrl);
      if(mounted){if(!silent)setState((){loading=false;status='Updated $count real EPG programmes';});
        guideRevision.value++;}
    }catch(e){if(mounted&&!silent)setState((){loading=false;status='EPG error: $e';});}
  }
  void _openCatalog(MediaKind kind){
    Navigator.of(context).push(MaterialPageRoute<void>(builder:(_)=>CatalogBrowseScreen(
      db:db,kind:kind,onPlay:_open,onFocus:_feature)));
  }
  void _choose(int index){
    if(page==index)return;
    FocusManager.instance.primaryFocus?.unfocus();
    setState(()=>page=index);
    WidgetsBinding.instance.addPostFrameCallback((_){
      if(mounted && page==index) navFocus[index].requestFocus();
    });
    if(index==1)_loadLive();
    if(index==2||index==3){
      final kind=index==2?MediaKind.movie:MediaKind.series;
      final items=trends[kind]?.isNotEmpty==true?trends[kind]!:shelves[kind]??[];
      if(items.isNotEmpty)_feature(items.first);
    }
  }
  Future<void> _loadLive({String group='North America'}) async{
    List<MediaEntry> loaded;
    if(group=='North America'){
      final all=await db.list(MediaKind.live,limit:12000);
      final curated=ChannelLineup.curated(all);
      // Keep the published channel-number ordering, then include other
      // English-language provider stations rather than cutting at 32 matches.
      final used=curated.map((e)=>e.id).toSet();
      final extras=all.where((e)=>!used.contains(e.id)&&e.likelyEnglish).toList()
        ..sort((a,b)=>a.cleanTitle.compareTo(b.cleanTitle));
      // The main Guide displays only real, playable provider stations.
      // DIRECTV numbers are reference positions assigned after a safe match.
      // Do not invent a stream when the provider doesn't have the station.
      loaded=[...curated,...extras];
      if(loaded.isEmpty)loaded=all.take(350).toList();
    }else{
      loaded=await db.list(MediaKind.live,category:group=='Favorites'?'All':group,
        favorites:group=='Favorites',limit:250);
    }
    if(mounted)setState((){live=loaded;channelGroup=group;});
  }
  Future<void> _toggle(MediaEntry item,{bool hide=false})async{
    await db.mark(item.id,favorite:hide?null:!item.favorite,hidden:hide?true:null);
    await _reload();if(mounted)setState((){});
  }
  Future<void> _recordHistory(MediaEntry item) async {
    if(item.kind==MediaKind.live||item.id.startsWith('xtream:episode:'))return;
    final prefs=await SharedPreferences.getInstance();
    final ids=prefs.getStringList('recent.ids')??[];
    ids.remove(item.id);ids.insert(0,item.id);
    await prefs.setStringList('recent.ids',ids.take(60).toList());
    recentHistory=await db.byIds(ids.take(60).toList());
    if(mounted)setState((){});
  }
  void _open(MediaEntry item) async{
    await _recordHistory(item);
    final src=source;
    if(src==null)return;
    if(item.kind==MediaKind.series&&!item.id.startsWith('xtream:episode:')){
      try{
        final episodes=await provider.episodes(src,item);
        if(!mounted)return;
        if(episodes.isNotEmpty){_episodes(item,episodes);return;}
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
          content:Text('No episodes returned by your IPTV provider for this series.')));
      }catch(_){
        if(mounted)ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
          content:Text('Unable to load episodes from your IPTV provider.')));
      }
      return; // A series ID is not an episode and must never be played directly.
    }
    final stream=src.playback(item);
    if(stream.isEmpty)return;
    if(!mounted)return;
    await Navigator.of(context).push(MaterialPageRoute<void>(
      builder:(_)=>PlayerScreen(title:item.cleanTitle,url:stream,live:item.kind==MediaKind.live)));
  }
  void _episodes(MediaEntry item,List<MediaEntry> episodes){
    Navigator.of(context).push(MaterialPageRoute<void>(builder:(_)=>
      EpisodeBrowser(series:item,episodes:episodes,source:source!)));
  }
  Future<void> _openDiscovery(MediaEntry item)async{
    if(!item.id.startsWith('tmdb:')){_details(item);return;}
    final matches=await db.matchTitles(item.kind,[item.title]);
    if(!mounted)return;
    if(matches.isNotEmpty){_details(matches.first);return;}
    await showDialog<void>(context:context,builder:(ctx)=>AlertDialog(
      title:Text(item.title),content:const Text('Trending on TMDB. This title was not found in your IPTV library.'),
      actions:[TextButton(onPressed:()=>Navigator.pop(ctx),child:const Text('Close'))]));
  }
  void _details(MediaEntry item){
    showDialog<void>(context:context,builder:(ctx)=>Dialog(
      backgroundColor:const Color(0xff0e1821),
      shape:RoundedRectangleBorder(borderRadius:BorderRadius.circular(18)),
      child:ConstrainedBox(constraints:const BoxConstraints(maxWidth:770,maxHeight:500),
        child:Padding(padding:const EdgeInsets.all(24),child:Row(children:[
          SizedBox(width:200,child:artwork(item.artwork,fit:BoxFit.contain)),
          const SizedBox(width:28),
          Expanded(child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
            Text(item.cleanTitle,style:Theme.of(context).textTheme.headlineLarge),
            const SizedBox(height:14),
            Text('${item.year>0?item.year:''}  ${meta.genres}',style:const TextStyle(color:C.gold)),
            const SizedBox(height:16),
            Expanded(child:SingleChildScrollView(child:Text(meta.overview.isNotEmpty?meta.overview:item.category,
              style:const TextStyle(color:C.secondary,fontSize:16)))),
            Row(children:[
              AuroraButton(text:'▶  Play',onPressed:(){Navigator.pop(ctx);_open(item);},primary:true),
              const SizedBox(width:12),
              AuroraButton(text:item.favorite?'♥ In My List':'+ My List',
                onPressed:(){Navigator.pop(ctx);_toggle(item);}),
            ]),
          ])),
        ])),
      ),
    ));
  }
  void _settings(){
    final key=TextEditingController(text:tmdb.apiKey);
    final guide=TextEditingController(text:epgUrl);
    showDialog<void>(context:context,builder:(ctx)=>StatefulBuilder(
      builder:(ctx,rebuild)=>AlertDialog(
        backgroundColor:const Color(0xff121e29),
        title:const Text('AuroraTV Settings'),
        content:SizedBox(width:520,child:SingleChildScrollView(child:Column(
          mainAxisSize:MainAxisSize.min,children:[
          TextField(controller:key,decoration:const InputDecoration(labelText:'TMDB API key (optional)')),
          const SizedBox(height:12),
          TextField(controller:guide,decoration:const InputDecoration(labelText:'External XMLTV URL (optional)')),
          const SizedBox(height:12),
          SwitchListTile(title:const Text('English-first discovery'),value:englishFirst,
            onChanged:(v){rebuild(()=>englishFirst=v);}),
          SwitchListTile(title:const Text('Automatic preview in Live Guide'),value:previewOn,
            onChanged:(v){rebuild(()=>previewOn=v);}),
          SwitchListTile(title:const Text('Trailer previews (future native integration)'),value:trailers,
            onChanged:null),
        ]))),
        actions:[
          TextButton(onPressed:(){Navigator.pop(ctx);setState(()=>showLogin=true);},child:const Text('Change provider')),
          TextButton(onPressed:(){Navigator.pop(ctx);_refresh();},child:const Text('Refresh library')),
          TextButton(onPressed:(){Navigator.pop(ctx);_refreshEpg();},child:const Text('Refresh EPG')),
          TextButton(onPressed:()async{
            await tmdb.saveKey(key.text);epgUrl=guide.text.trim();
            final prefs=await SharedPreferences.getInstance();
            await prefs.setString('epg.url',epgUrl);
            await prefs.setBool('english.first',englishFirst);
            await prefs.setBool('preview',previewOn);
            if(ctx.mounted)Navigator.pop(ctx);
            if(mounted)setState((){});
            _getTrends();
          },child:const Text('Save')),
        ],
      )
    ));
  }
  @override void dispose(){
    db.close();provider.dispose();tmdb.dispose();epg.dispose();
    heroVersion.dispose();guideRevision.dispose();
    for(final node in navFocus){node.dispose();}
    navScroll.dispose();
    super.dispose();
  }

  @override Widget build(BuildContext context){
    if(!ready)return const Scaffold(body:Center(child:CircularProgressIndicator(color:C.aqua)));
    if(showLogin)return LoginScreen(connect:_connect,loading:loading,status:status);

    final media=MediaQuery.sizeOf(context);
    final isCinema=page==0||page==2||page==3;
    return Scaffold(body:Stack(children:[
      Positioned.fill(child:ValueListenableBuilder<int>(valueListenable:heroVersion,
        builder:(_,__,___)=>AnimatedSwitcher(duration:const Duration(milliseconds:350),
        child:Container(key:ValueKey(isCinema?(meta.backdrop.isNotEmpty?meta.backdrop:featured?.artwork??''):'empty'),
          color:C.canvas,
          child:isCinema&&featured!=null
          ?meta.backdrop.isNotEmpty
            ?SizedBox.expand(child:artwork(meta.backdrop,fit:BoxFit.cover))
            :SizedBox.expand(child:artwork(featured!.artwork,fit:BoxFit.cover))
          :const SizedBox.shrink())))),
      if(isCinema)Positioned.fill(child:DecoratedBox(decoration:BoxDecoration(
        gradient:LinearGradient(begin:Alignment.centerLeft,end:Alignment.centerRight,
          colors:[Colors.black.withValues(alpha:.95),Colors.black.withValues(alpha:.70),
            Colors.black.withValues(alpha:.24),Colors.black.withValues(alpha:.06)])))),
      if(isCinema)Positioned.fill(child:DecoratedBox(decoration:BoxDecoration(
        gradient:LinearGradient(begin:Alignment.topCenter,end:Alignment.bottomCenter,
          colors:[Colors.black.withValues(alpha:.67),Colors.transparent,
            Colors.black.withValues(alpha:.12),Colors.black.withValues(alpha:.90)])))),
      SafeArea(child:Column(children:[
        _navBar(media.width),
        Expanded(child:FocusScope(child:IndexedStack(index:page,children:[
          _discovery(MediaKind.movie,home:true),
          GuideScreen(db:db,channels:live,groups:groups[MediaKind.live]??[],
            group:channelGroup,onGroup:_loadLive,onPlay:_open,previewOn:previewOn,
            source:source,provider:provider,refreshEpg:()=>_refreshEpg(),revision:guideRevision),
          _discovery(MediaKind.movie),
          _discovery(MediaKind.series),
          _myList(),
          _searchPage(),
          _libraryPage(),
        ]))),
      ])),
      if(loading)Positioned(left:0,right:0,bottom:0,child:Container(
        color:Colors.black87,padding:const EdgeInsets.all(12),
        child:Text(status,textAlign:TextAlign.center))),
    ]));
  }
  Widget _navBar(double width){
    const labels=['Home','Live TV & Guide','Movies','TV Shows','My List','Search','Edit Library'];
    return Container(
      height:58,padding:EdgeInsets.symmetric(horizontal:width<1050?18:30),
      decoration:BoxDecoration(color:Colors.black.withValues(alpha:.30)),
      child:Row(children:[
        RichText(text:const TextSpan(style:TextStyle(fontSize:25,fontWeight:FontWeight.w800),children:[
          TextSpan(text:'Aurora',style:TextStyle(color:Colors.white)),
          TextSpan(text:'TV',style:TextStyle(color:C.aqua)),
        ])),
        const SizedBox(width:28),
        Expanded(child:SingleChildScrollView(controller:navScroll,
          scrollDirection:Axis.horizontal,child:Row(children:[
          for(var i=0;i<labels.length;i++)
            FocusTraversalOrder(order:NumericFocusOrder(i.toDouble()),
              child:AuroraButton(
                focusNode:navFocus[i],text:labels[i],selected:page==i,
                onPressed:()=>_choose(i),nav:true)),
        ]))),
        const SizedBox(width:8),
        IconButton(onPressed:_settings,tooltip:'Settings',icon:const Icon(Icons.settings_outlined,size:25)),
      ]));
  }

  Widget _discovery(MediaKind kind,{bool home=false}){
    final mainKind=home?MediaKind.series:kind;
    final items=shelves[mainKind]??[];
    final ranked=discoverToday[mainKind]??[];
    final english=items.where((v)=>v.likelyEnglish).toList();
    final recent=english.isNotEmpty?english:items;
    final title=home?'TRENDING MOVIES TODAY':mainKind==MediaKind.movie?'TOP MOVIES TODAY':'TOP TV SHOWS TODAY';
    final genresForPage=popularGenres[mainKind]??{};
    return LayoutBuilder(builder:(ctx,c){
      final available=c.maxHeight;
      final heroHeight=(available*.64).clamp(245.0,540.0);
      return ListView(padding:EdgeInsets.zero,children:[
        SizedBox(height:heroHeight,child:ValueListenableBuilder<int>(valueListenable:heroVersion,
          builder:(_,__,___)=>Stack(children:[
          Align(alignment:Alignment.centerLeft,
            child:Padding(padding:const EdgeInsets.fromLTRB(34,12,0,6),
              child:ConstrainedBox(
                constraints:BoxConstraints(maxWidth:math.min(c.maxWidth*.49,680)),
                child:_heroText()))),
        ]))),
        if(!home)Padding(padding:const EdgeInsets.fromLTRB(30,0,30,12),
          child:Row(children:[
            Text('EXPLORE ${kind==MediaKind.movie?'MOVIES':'TV SHOWS'}',
              style:const TextStyle(fontSize:19,fontWeight:FontWeight.w700)),
            const Spacer(),
            AuroraButton(text:'All Titles  →',onPressed:()=>_openCatalog(kind)),
          ])),
        if(home&&ranked.isNotEmpty)
          _shelf('POPULAR TODAY · TV SHOWS',ranked,MediaKind.series,ranked:true),
        if(!home&&ranked.isNotEmpty)
          _shelf(title,ranked,mainKind,ranked:true),
        if(recentHistory.isNotEmpty)
          _shelf('RECENTLY WATCHED',recentHistory.where((e)=>home||e.kind==mainKind).toList(),mainKind),
        if(home&&tmdb.apiKey.isEmpty)
          Padding(padding:const EdgeInsets.symmetric(horizontal:34,vertical:12),child:TextButton(
            onPressed:_settings,child:const Text('Enable Popular Today & genre rankings — add your TMDB API key in Settings'))),
        if(home&&ranked.isEmpty&&tmdb.apiKey.isNotEmpty)
          _shelf('POPULAR TV SHOWS',shelves[MediaKind.series]??[],MediaKind.series),
        if(!home&&ranked.isEmpty)
          _shelf('EXPLORE ${mainKind==MediaKind.movie?'MOVIES':'TV SHOWS'}',recent,mainKind),
        if(home)for(final entry in (popularGenres[MediaKind.series]??{}).entries)
          _shelf('${entry.key.toUpperCase()} · POPULAR THIS WEEK',entry.value,MediaKind.series),
        if(home)for(final entry in (popularGenres[MediaKind.movie]??{}).entries)
          _shelf('${entry.key.toUpperCase()} MOVIES · POPULAR THIS WEEK',entry.value,MediaKind.movie),
        if(!home)for(final entry in genresForPage.entries)
          _shelf('${entry.key.toUpperCase()} · POPULAR THIS WEEK',entry.value,mainKind),
        if(home&&popularGenres[MediaKind.series]?.isNotEmpty!=true&&popularGenres[MediaKind.movie]?.isNotEmpty!=true)
          _shelf('NEW & RECENT · ENGLISH MOVIES',recent,MediaKind.movie),
        if(!home&&genresForPage.isEmpty)
          for(final genre in (groups[mainKind]??[]).take(8))_genreShelf(mainKind,genre),
        if(home)_shelf('MY LIST',
          [...?favorites[MediaKind.movie],...?favorites[MediaKind.series]],MediaKind.movie),
        const SizedBox(height:70),
      ]);
    });
  }
  Widget _heroText(){
    final current=featured;
    if(current==null)return const Text('Connect your IPTV library to discover entertainment',
      style:TextStyle(fontSize:24,color:C.ink));
    final hasMeta=meta.rating>0||current.rating>0;
    final rating=meta.rating>0?meta.rating:current.rating;
    return Column(mainAxisSize:MainAxisSize.min,crossAxisAlignment:CrossAxisAlignment.start,children:[
      Text('AURORATV  /  FEATURED',style:TextStyle(fontSize:11,letterSpacing:3,
        fontWeight:FontWeight.w700,color:C.aqua.withValues(alpha:.95))),
      const SizedBox(height:13),
      Text(current.cleanTitle.toUpperCase(),maxLines:2,overflow:TextOverflow.ellipsis,
        style:TextStyle(fontSize:math.min(48,MediaQuery.sizeOf(context).height*.074),
          fontWeight:FontWeight.w800,height:1.05,letterSpacing:1.2,color:Colors.white,
          shadows:const [Shadow(blurRadius:12,color:Colors.black)])),
      const SizedBox(height:13),
      Text('${hasMeta?'★ ${rating.toStringAsFixed(1)}     ':''}${current.year>0?'${current.year}    ':''}${meta.genres.isNotEmpty?meta.genres:current.kind==MediaKind.series?'TV SERIES':'MOVIE'}',
        style:const TextStyle(color:C.gold,fontSize:15,fontWeight:FontWeight.w600)),
      if(meta.overview.isNotEmpty)...[
        const SizedBox(height:15),
        Text(meta.overview,maxLines:3,overflow:TextOverflow.ellipsis,
          style:const TextStyle(fontSize:16,height:1.48,color:C.ink)),
      ],
      const SizedBox(height:23),
      Row(mainAxisSize:MainAxisSize.min,children:[
        AuroraButton(text:'▶  Watch Now',primary:true,onPressed:()=>_open(current)),
        const SizedBox(width:10),
        AuroraButton(text:'ⓘ  More Info',onPressed:()=>_details(current)),
      ]),
    ]);
  }
  Widget _shelf(String heading,List<MediaEntry> items,MediaKind kind,{bool ranked=false}){
    if(items.isEmpty)return const SizedBox.shrink();
    return Padding(padding:const EdgeInsets.only(top:8,bottom:12),child:Column(
      crossAxisAlignment:CrossAxisAlignment.start,children:[
      Padding(padding:const EdgeInsets.fromLTRB(34,0,28,10),child:Row(children:[
        Expanded(child:Text(heading,style:const TextStyle(fontSize:21,fontWeight:FontWeight.w700))),
        TextButton(onPressed:()=>_choose(kind==MediaKind.series?3:2),
          child:const Text('See All  →',style:TextStyle(color:C.secondary,fontSize:13))),
      ])),
      SizedBox(height:155,child:ListView.builder(
        scrollDirection:Axis.horizontal,padding:const EdgeInsets.symmetric(horizontal:30),
        itemCount:math.min(items.length,20),itemBuilder:(ctx,i){
          final item=items[i];
          return MediaCard(item:item,index:ranked?i+1:0,
            onFocused:()=>_feature(item),onOpen:()=>_openDiscovery(item));
        })),
    ]));
  }
  Widget _genreShelf(MediaKind kind,String group){
    return FutureBuilder<List<MediaEntry>>(future:db.list(kind,category:group,limit:14),
      builder:(ctx,snapshot)=>snapshot.hasData?
        _shelf(group.toUpperCase(),snapshot.data!,kind):const SizedBox.shrink());
  }
  Widget _myList()=>_catalogPage('MY LIST',[
    ...?favorites[MediaKind.movie],...?favorites[MediaKind.series],...?favorites[MediaKind.live],
  ]);
  Widget _catalogPage(String heading,List<MediaEntry> items)=>Padding(
    padding:const EdgeInsets.fromLTRB(32,26,32,12),
    child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
      Text(heading,style:Theme.of(context).textTheme.headlineLarge),
      const SizedBox(height:20),
      Expanded(child:GridView.builder(gridDelegate:const SliverGridDelegateWithMaxCrossAxisExtent(
        maxCrossAxisExtent:285,mainAxisSpacing:16,crossAxisSpacing:12,childAspectRatio:1.6),
        itemCount:items.length,itemBuilder:(ctx,i)=>MediaCard(item:items[i],index:0,
          onFocused:()=>_feature(items[i]),onOpen:()=>_details(items[i])))),
    ]));
  Widget _searchPage()=>_SearchContent(db:db,onOpen:_details,onFocused:_feature);
  Widget _libraryPage()=>_LibraryContent(db:db,onRefresh:_refresh,onHide:(item)=>_toggle(item,hide:true),
    onFavorite:_toggle);
}

Widget artwork(String url,{BoxFit fit=BoxFit.cover}){
  if(url.isEmpty||!url.startsWith('http'))return const DecoratedBox(
    decoration:BoxDecoration(gradient:LinearGradient(colors:[Color(0xff172a38),Color(0xff07121d)])),
    child:Center(child:Icon(Icons.movie_creation_outlined,size:39,color:Color(0xff35505d))));
  return CachedNetworkImage(imageUrl:url,fit:fit,memCacheWidth:1280,maxWidthDiskCache:1280,
    fadeInDuration:const Duration(milliseconds:120),errorWidget:(_,__,___)=>const ColoredBox(
    color:Color(0xff14232d),child:Center(child:Icon(Icons.movie_outlined,color:C.secondary))),
    placeholder:(_,__)=>const ColoredBox(color:Color(0xff13202b)));
}

class AuroraButton extends StatelessWidget{
  final String text;final VoidCallback onPressed;
  final bool primary,selected,nav;
  final FocusNode? focusNode;
  const AuroraButton({super.key,required this.text,required this.onPressed,
    this.primary=false,this.selected=false,this.nav=false,this.focusNode});
  @override Widget build(BuildContext context)=>Padding(
    padding:EdgeInsets.symmetric(horizontal:nav?3:0,vertical:nav?7:0),
    child:OutlinedButton(focusNode:focusNode,onPressed:onPressed,style:ButtonStyle(
        minimumSize:WidgetStatePropertyAll(Size(nav?54:156,nav?37:44)),
        padding:WidgetStatePropertyAll(EdgeInsets.symmetric(horizontal:nav?14:20)),
        backgroundColor:WidgetStatePropertyAll(primary?Colors.white:
          selected?C.aqua.withValues(alpha:.16):Colors.black.withValues(alpha:nav ? .02 : .45)),
        foregroundColor:WidgetStatePropertyAll(primary?Colors.black:Colors.white),
        side:WidgetStatePropertyAll(BorderSide(
          color:selected?C.aqua:primary?Colors.white:C.secondary.withValues(alpha:nav?0:.23))),
        shape:WidgetStatePropertyAll(RoundedRectangleBorder(borderRadius:BorderRadius.circular(nav?12:9))),
      ),child:Text(text,maxLines:1,style:TextStyle(fontSize:nav?14:15,fontWeight:FontWeight.w700))),
  );
}

class MediaCard extends StatefulWidget{
  final MediaEntry item;final int index;final VoidCallback onFocused,onOpen;
  const MediaCard({super.key,required this.item,required this.index,
    required this.onFocused,required this.onOpen});
  @override State<MediaCard> createState()=>_MediaCardState();
}
class _MediaCardState extends State<MediaCard>{
 bool focused=false;
 @override Widget build(BuildContext context)=>Padding(
  padding:const EdgeInsets.symmetric(horizontal:5,vertical:4),
  child:InkWell(
    canRequestFocus:true,
    onTap:widget.onOpen,
    onFocusChange:(hasFocus){
      if(!mounted)return;
      if(focused!=hasFocus)setState(()=>focused=hasFocus);
      if(hasFocus){
        widget.onFocused();
        // Only the nearest scrollable moves. The previous ensureVisible call
        // also jumped the vertical Home scroll while browsing a horizontal row.
      }
    },
    borderRadius:BorderRadius.circular(12),
    child:AnimatedContainer(duration:const Duration(milliseconds:130),
      width:242,decoration:BoxDecoration(
        borderRadius:BorderRadius.circular(12),
        border:Border.all(width:focused?3:1,color:focused?C.aqua:Colors.white12),
        boxShadow:focused?[BoxShadow(color:C.aqua.withValues(alpha:.22),blurRadius:10)]:[],
      ),clipBehavior:Clip.antiAlias,
      child:Stack(fit:StackFit.expand,children:[
        artwork(widget.item.artwork),
        DecoratedBox(decoration:BoxDecoration(gradient:LinearGradient(
          begin:Alignment.topCenter,end:Alignment.bottomCenter,
          colors:[Colors.transparent,Colors.black.withValues(alpha:.84)]))),
        if(widget.index>0)Positioned(top:8,left:10,
          child:Text('#${widget.index}',style:const TextStyle(
            color:C.aqua,fontWeight:FontWeight.w800))),
        Positioned(left:12,right:12,bottom:10,child:Text(widget.item.cleanTitle,
          maxLines:2,overflow:TextOverflow.ellipsis,
          style:const TextStyle(fontSize:16,fontWeight:FontWeight.w700,color:Colors.white,
            shadows:[Shadow(color:Colors.black,blurRadius:8)]))),
      ]),
    ),
  ),
);
}

class LoginScreen extends StatefulWidget{
  final Future<void> Function(IptvSource) connect;
  final bool loading;final String status;
  const LoginScreen({super.key,required this.connect,required this.loading,required this.status});
  @override State<LoginScreen> createState()=>_LoginScreenState();
}
class _LoginScreenState extends State<LoginScreen>{
  final server=TextEditingController(),user=TextEditingController(),
    pass=TextEditingController(),playlist=TextEditingController();
  final serverFocus=FocusNode(debugLabel:'Server URL'),
    userFocus=FocusNode(debugLabel:'Username'),
    passFocus=FocusNode(debugLabel:'Password'),
    playlistFocus=FocusNode(debugLabel:'M3U Playlist'),
    connectFocus=FocusNode(debugLabel:'Connect');
  final formScroll=ScrollController();
  bool m3u=false;

  void _next(FocusNode focus){
    // Fire TV software keyboards typically send Done rather than Next.
    // Explicitly transfer focus, rather than returning to the first form field.
    focus.requestFocus();
  }
  void _submit(){
    if(widget.loading)return;
    widget.connect(m3u?
      IptvSource(kind:'m3u',playlist:playlist.text.trim()):
      IptvSource(kind:'xtream',server:server.text.trim(),
        username:user.text.trim(),password:pass.text));
  }
  @override void dispose(){
    server.dispose();user.dispose();pass.dispose();playlist.dispose();
    serverFocus.dispose();userFocus.dispose();passFocus.dispose();
    playlistFocus.dispose();connectFocus.dispose();formScroll.dispose();
    super.dispose();
  }
  @override Widget build(BuildContext context)=>Scaffold(body:Center(
    child:ConstrainedBox(constraints:const BoxConstraints(maxWidth:610),
      child:FocusTraversalGroup(policy:OrderedTraversalPolicy(),
        child:SingleChildScrollView(controller:formScroll,
          keyboardDismissBehavior:ScrollViewKeyboardDismissBehavior.manual,
          padding:const EdgeInsets.all(30),child:Column(
        crossAxisAlignment:CrossAxisAlignment.start,children:[
        const Text('AuroraTV',style:TextStyle(fontSize:40,fontWeight:FontWeight.w800,color:C.aqua)),
        const SizedBox(height:8),
        const Text('Your channels. Your cinema. Your screen.',style:TextStyle(color:C.secondary,fontSize:17)),
        const SizedBox(height:22),
        SegmentedButton<bool>(segments:const [
          ButtonSegment(value:false,label:Text('Xtream Codes')),
          ButtonSegment(value:true,label:Text('M3U Playlist')),
        ],selected:{m3u},onSelectionChanged:(s)=>setState(()=>m3u=s.first)),
        const SizedBox(height:18),
        if(!m3u)...[
          FocusTraversalOrder(order:const NumericFocusOrder(1),
            child:TextField(
              key:const ValueKey('server-url'),
              controller:server,focusNode:serverFocus,
              keyboardType:TextInputType.url,textInputAction:TextInputAction.next,
              onEditingComplete:()=>_next(userFocus),
              onSubmitted:(_)=>_next(userFocus),
              decoration:const InputDecoration(labelText:'Server URL, including port',
                hintText:'http://provider.example:8080'))),
          const SizedBox(height:10),
          FocusTraversalOrder(order:const NumericFocusOrder(2),
            child:TextField(
              key:const ValueKey('xtream-username'),
              controller:user,focusNode:userFocus,
              textInputAction:TextInputAction.next,
              onEditingComplete:()=>_next(passFocus),
              onSubmitted:(_)=>_next(passFocus),
              decoration:const InputDecoration(labelText:'Username'))),
          const SizedBox(height:10),
          FocusTraversalOrder(order:const NumericFocusOrder(3),
            child:TextField(
              key:const ValueKey('xtream-password'),
              controller:pass,focusNode:passFocus,obscureText:true,
              textInputAction:TextInputAction.done,
              onEditingComplete:()=>_next(connectFocus),
              onSubmitted:(_)=>_next(connectFocus),
              decoration:const InputDecoration(labelText:'Password'))),
        ]else FocusTraversalOrder(order:const NumericFocusOrder(1),
          child:TextField(
            key:const ValueKey('playlist-url'),controller:playlist,
            focusNode:playlistFocus,maxLines:3,
            textInputAction:TextInputAction.done,
            onEditingComplete:()=>_next(connectFocus),
            onSubmitted:(_)=>_next(connectFocus),
            decoration:const InputDecoration(
              labelText:'Playlist URL or pasted #EXTM3U data'))),
        const SizedBox(height:22),
        FocusTraversalOrder(order:const NumericFocusOrder(4),
          child:FilledButton(
            focusNode:connectFocus,
            onPressed:widget.loading?null:_submit,
            child:Text(widget.loading?'Importing…':'Connect and Import Library'))),
        if(widget.loading)const Padding(padding:EdgeInsets.all(12),child:LinearProgressIndicator()),
        if(widget.status.isNotEmpty)Padding(padding:const EdgeInsets.only(top:13),
          child:Text(widget.status,style:const TextStyle(color:C.secondary))),
      ])))),
  ));
}

class EpisodeBrowser extends StatefulWidget {
 final MediaEntry series;final List<MediaEntry> episodes;final IptvSource source;
 const EpisodeBrowser({super.key,required this.series,required this.episodes,required this.source});
 @override State<EpisodeBrowser> createState()=>_EpisodeBrowserState();
}
class _EpisodeBrowserState extends State<EpisodeBrowser>{
 late int season;
 late final Map<int,List<MediaEntry>> seasons;
 @override void initState(){
  super.initState();
  seasons={};
  for(final ep in widget.episodes){
   final match=RegExp(r'Season\s*(\d+)',caseSensitive:false).firstMatch(ep.category);
   final number=int.tryParse(match?.group(1)??'')??0;
   seasons.putIfAbsent(number,()=>[]).add(ep);
  }
  for(final entries in seasons.values){
   entries.sort((a,b){
    int number(MediaEntry e)=>int.tryParse(RegExp(r'(?:E|Episode\s*)(\d+)',caseSensitive:false)
      .firstMatch(e.title)?.group(1)??'')??0;
    return number(a).compareTo(number(b));
   });
  }
  season=seasons.keys.where((k)=>k>0).fold<int>(0,(a,b)=>a==0||b<a?b:a);
  if(!seasons.containsKey(season)&&seasons.isNotEmpty)season=seasons.keys.first;
 }
 @override Widget build(BuildContext context){
  final entries=seasons[season]??[];
  return Scaffold(backgroundColor:const Color(0xff09111b),appBar:AppBar(
    backgroundColor:const Color(0xff101e29),title:Text(widget.series.cleanTitle)),
   body:Padding(padding:const EdgeInsets.all(24),child:Row(children:[
    SizedBox(width:210,child:Column(crossAxisAlignment:CrossAxisAlignment.stretch,children:[
      const Text('SEASONS',style:TextStyle(fontSize:16,color:C.aqua,fontWeight:FontWeight.bold)),
      const SizedBox(height:12),
      Expanded(child:ListView(children:[
        for(final key in seasons.keys.toList()..sort())
          Padding(padding:const EdgeInsets.only(bottom:6),child:AuroraButton(
            text:key==0?'Specials / Other':'Season $key',
            selected:season==key,onPressed:()=>setState(()=>season=key))),
      ])),
    ])),
    const SizedBox(width:22),
    Expanded(child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
      Text('SEASON $season · ${entries.length} EPISODES',
        style:const TextStyle(fontSize:20,fontWeight:FontWeight.bold)),
      const SizedBox(height:12),
      Expanded(child:ListView.builder(itemCount:entries.length,itemBuilder:(ctx,index){
       final ep=entries[index];
       return Card(color:const Color(0xff192b38),margin:const EdgeInsets.only(bottom:8),
        child:ListTile(
         leading:CircleAvatar(backgroundColor:C.aqua,foregroundColor:Colors.black,
           child:Text('${index+1}')),
         title:Text(ep.title,maxLines:2,overflow:TextOverflow.ellipsis),
         subtitle:Text(ep.category),trailing:const Icon(Icons.play_circle_outline,size:32),
         onTap:()=>Navigator.push(context,MaterialPageRoute<void>(
           builder:(_)=>PlayerScreen(title:ep.title,url:widget.source.playback(ep),
             episodes:widget.episodes,episode:ep,source:widget.source))),
        ));
      })),
    ]))
   ])));
 }
}

class PlayerScreen extends StatefulWidget{
 final String title,url;final bool live;
 final List<MediaEntry> episodes;final MediaEntry? episode;final IptvSource? source;
 const PlayerScreen({super.key,required this.title,required this.url,this.live=false,
   this.episodes=const [],this.episode,this.source});
 @override State<PlayerScreen> createState()=>_PlayerScreenState();
}
class _PlayerScreenState extends State<PlayerScreen>{
 VideoPlayerController? video;
 String error='';
 String? episodeTitle,episodeUrl;
 bool buffering=true,controls=true,muted=false,fillScreen=false;
 double playbackSpeed=1;
 static const pipChannel=MethodChannel('aurora.tv/picture_in_picture');
 Timer? hideTimer;
 int attempt=0;
 @override void initState(){super.initState();_init();}
 Future<void> _init() async{
  final revision=++attempt;
  final previous=video;
  video=null;
  previous?.removeListener(_monitor);
  await previous?.dispose();
  if(!mounted||revision!=attempt)return;
  setState((){buffering=true;error='';});
  final controller=VideoPlayerController.networkUrl(Uri.parse(episodeUrl??widget.url));
  video=controller;
  controller.addListener(_monitor);
  try{
    await controller.initialize().timeout(const Duration(seconds:22));
    if(!mounted||revision!=attempt){await controller.dispose();return;}
    await controller.play();
    _showControls();
    setState(()=>buffering=false);
  }catch(e){
    if(mounted&&revision==attempt)setState((){
      error='Unable to play stream. Check your provider or retry.';
      buffering=false;
    });
  }
 }
 void _monitor(){
  final v=video;
  if(!mounted||v==null)return;
  if(v.value.hasError&&error.isEmpty){
    setState(()=>error=v.value.errorDescription??'Playback error');
  }
  if(buffering!=v.value.isBuffering && v.value.isInitialized){
    setState(()=>buffering=v.value.isBuffering);
  }
 }
 void _selectEpisode(MediaEntry episode){
  final src=widget.source;
  if(src==null)return;
  setState((){episodeTitle=episode.title;episodeUrl=src.playback(episode);});
  _init();
 }
 Future<void> _episodePicker()async{
  if(widget.episodes.isEmpty)return;
  final selected=await showModalBottomSheet<MediaEntry>(context:context,
   backgroundColor:const Color(0xff101e29),isScrollControlled:true,
   builder:(ctx)=>SafeArea(child:SizedBox(height:math.min(MediaQuery.sizeOf(ctx).height*.75,600),
    child:Column(children:[const Padding(padding:EdgeInsets.all(16),child:Text('EPISODES',
      style:TextStyle(fontSize:20,fontWeight:FontWeight.bold))),
      Expanded(child:ListView.builder(itemCount:widget.episodes.length,itemBuilder:(ctx,i){
       final ep=widget.episodes[i];
       return ListTile(title:Text(ep.title),subtitle:Text(ep.category),
         trailing:const Icon(Icons.play_arrow),onTap:()=>Navigator.pop(ctx,ep));
      }))])));
  if(selected!=null&&mounted)_selectEpisode(selected);
 }
 void _showControls(){
  hideTimer?.cancel();
  if(mounted)setState(()=>controls=true);
  hideTimer=Timer(const Duration(seconds:7),(){
    if(mounted&&video?.value.isPlaying==true)setState(()=>controls=false);
  });
 }
 Future<void> _pip()async{
  if(!widget.live)return;
  try{
    await pipChannel.invokeMethod<void>('enter');
  }catch(_){
    if(mounted)ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content:Text('System picture-in-picture is unavailable on this device.')));
  }
 }
 void _seek(Duration delta){
  final v=video;
  if(v==null||!v.value.isInitialized||v.value.duration.inSeconds<=0)return;
  final position=v.value.position+delta;
  final bounded=position<Duration.zero?Duration.zero:
    position>v.value.duration?v.value.duration:position;
  v.seekTo(bounded);_showControls();
 }
 @override void dispose(){
  ++attempt;hideTimer?.cancel();
  video?.removeListener(_monitor);video?.dispose();
  super.dispose();
 }
 @override Widget build(BuildContext context){
  final v=video;
  final initialized=v?.value.isInitialized==true;
  final duration=initialized?v!.value.duration:Duration.zero;
  final position=initialized?v!.value.position:Duration.zero;
  final seekable=duration.inSeconds>0&&duration< const Duration(days:1);
  return Scaffold(body:Focus(
   autofocus:true,onKeyEvent:(_,event){
     if(event is! KeyDownEvent)return KeyEventResult.ignored;
     if(event.logicalKey==LogicalKeyboardKey.arrowLeft&&seekable){
       _seek(const Duration(seconds:-10));return KeyEventResult.handled;
     }
     if(event.logicalKey==LogicalKeyboardKey.arrowRight&&seekable){
       _seek(const Duration(seconds:10));return KeyEventResult.handled;
     }
     if(event.logicalKey==LogicalKeyboardKey.select ||
       event.logicalKey==LogicalKeyboardKey.enter ||
       event.logicalKey==LogicalKeyboardKey.space){
       if(v!=null&&initialized){v.value.isPlaying?v.pause():v.play();_showControls();}
       return KeyEventResult.handled;
     }
     _showControls();return KeyEventResult.ignored;
   },
   child:GestureDetector(
    behavior:HitTestBehavior.opaque,
    onTap:_showControls,
    child:Stack(children:[
      Positioned.fill(child:initialized?
       FittedBox(fit:fillScreen?BoxFit.cover:BoxFit.contain,child:SizedBox(
         width:v!.value.size.width,height:v.value.size.height,child:VideoPlayer(v))):
       const ColoredBox(color:Colors.black)),
      if(controls||error.isNotEmpty)Positioned(top:0,left:0,right:0,
        child:Container(padding:const EdgeInsets.symmetric(vertical:12,horizontal:20),
          color:Colors.black54,child:Row(children:[
          IconButton(onPressed:()=>Navigator.pop(context),icon:const Icon(Icons.arrow_back)),
          Expanded(child:Text(episodeTitle??widget.title,maxLines:1,overflow:TextOverflow.ellipsis,
            style:const TextStyle(fontSize:19,fontWeight:FontWeight.w700))),
          if(widget.episodes.isNotEmpty)IconButton(tooltip:'Seasons and episodes',
            onPressed:_episodePicker,icon:const Icon(Icons.video_library_outlined)),
          IconButton(tooltip:'Retry playback',onPressed:_init,icon:const Icon(Icons.refresh)),
          if(widget.live)IconButton(tooltip:'Picture-in-picture',onPressed:_pip,
            icon:const Icon(Icons.picture_in_picture_alt_outlined)),
        ]))),
      if(buffering)const Center(child:CircularProgressIndicator(color:C.aqua)),
      if(error.isNotEmpty)Center(child:Container(
        padding:const EdgeInsets.all(26),color:Colors.black87,
        child:Column(mainAxisSize:MainAxisSize.min,children:[
          Text(error,textAlign:TextAlign.center),
          const SizedBox(height:12),
          AuroraButton(text:'Retry',onPressed:_init),
        ]))),
      if(controls&&initialized&&error.isEmpty)Positioned(
        left:28,right:28,bottom:22,child:Container(
          padding:const EdgeInsets.all(10),color:Colors.black54,
          child:Row(children:[
            IconButton(onPressed:(){
              v.value.isPlaying?v.pause():v.play();_showControls();
            },icon:Icon(v!.value.isPlaying?Icons.pause:Icons.play_arrow,size:30)),
            IconButton(tooltip:muted?'Unmute':'Mute',onPressed:(){
              muted=!muted;v.setVolume(muted?0:1);_showControls();
            },icon:Icon(muted?Icons.volume_off:Icons.volume_up)),
            if(widget.episodes.isNotEmpty)IconButton(tooltip:'Episodes',
              onPressed:_episodePicker,icon:const Icon(Icons.playlist_play)),
            IconButton(tooltip:'Aspect ratio',onPressed:(){
              setState(()=>fillScreen=!fillScreen);_showControls();
            },icon:Icon(fillScreen?Icons.fit_screen:Icons.aspect_ratio)),
            if(seekable)...[
              IconButton(onPressed:()=>_seek(const Duration(seconds:-10)),
                icon:const Icon(Icons.replay_10)),
              Expanded(child:Slider(
                value:position.inMilliseconds.clamp(0,duration.inMilliseconds).toDouble(),
                max:math.max(1,duration.inMilliseconds).toDouble(),
                onChanged:(n){v.seekTo(Duration(milliseconds:n.round()));_showControls();})),
              IconButton(onPressed:()=>_seek(const Duration(seconds:10)),
                icon:const Icon(Icons.forward_10)),
              PopupMenuButton<double>(tooltip:'Playback speed',initialValue:playbackSpeed,
                onSelected:(speed){playbackSpeed=speed;v.setPlaybackSpeed(speed);_showControls();},
                itemBuilder:(_)=><double>[.5,1,1.25,1.5,2].map((speed)=>PopupMenuItem<double>(
                  value:speed,child:Text('${speed}x'))).toList(),
                child:Padding(padding:const EdgeInsets.all(9),child:Text('${playbackSpeed}x'))),
            ]else const Expanded(child:Text('LIVE',style:TextStyle(color:C.aqua,
              fontWeight:FontWeight.w800))),
          ]),
        )),
    ]),
   ),
  ));
 }
}

class GuideScreen extends StatefulWidget{
 final CatalogDatabase db;final List<MediaEntry> channels;final List<String> groups;
 final String group;final Future<void> Function({String group}) onGroup;
 final void Function(MediaEntry) onPlay;final bool previewOn;
 final IptvSource? source;final VoidCallback refreshEpg;
 final ProviderClient provider;
 final ValueNotifier<int> revision;
 const GuideScreen({super.key,required this.db,required this.channels,required this.groups,
   required this.group,required this.onGroup,required this.onPlay,required this.previewOn,
   required this.source,required this.provider,required this.refreshEpg,required this.revision});
 @override State<GuideScreen> createState()=>_GuideScreenState();
}
class _GuideScreenState extends State<GuideScreen>{
 DateTime anchor=DateTime.now();
 Map<String,List<TvProgramme>> programs={};
 int previewRequest=0;
 final Set<String> epgRequested=<String>{};
 final List<MediaEntry> epgQueue=<MediaEntry>[];
 final ScrollController guideScroll=ScrollController();
 int epgInFlight=0;
 int guideGeneration=0;
 // Xtream servers often rate-limit bursts. Fetch only rows close to the viewport.
 static const int epgConcurrency=3;
 void _queueVisibleGuides(int first){
   if(!mounted||widget.source?.kind!='xtream')return;
   final channels=widget.channels;
   if(channels.isEmpty)return;
   final start=first<0?0:(first>=channels.length?channels.length-1:first);
   final end=start+25>channels.length?channels.length:start+25;
   for(var i=start;i<end;i++){
     final item=channels[i];
     if(item.streamId.isEmpty||item.id.startsWith('lineup:')||
         (programs[key(item)]?.isNotEmpty??false)||!epgRequested.add(item.id))continue;
     epgQueue.add(item);
   }
   _drainEpg();
 }
 void _drainEpg(){
   while(mounted&&epgInFlight<epgConcurrency&&epgQueue.isNotEmpty){
     final item=epgQueue.removeAt(0);
     final generation=guideGeneration;
     epgInFlight++;
     () async{
       try{
         final data=await widget.provider.shortEpg(widget.source!,item);
         if(mounted&&generation==guideGeneration&&data.isNotEmpty){
           setState(()=>programs['stream:${item.streamId}']=data);
         }
       }catch(_){
         // A missing provider guide should not block any other channel.
       }finally{
         epgInFlight--;
         if(mounted)_drainEpg();
       }
     }();
   }
 }
 void _onGuideScroll(){
   if(!guideScroll.hasClients)return;
   _queueVisibleGuides((guideScroll.offset/44).floor());
 }
 String key(MediaEntry item){
   if(item.epgId.isNotEmpty&&(programs[item.epgId]?.isNotEmpty??false))return item.epgId;
   final byName='name:${GuideNames.canonical(item.cleanTitle)}';
   if(programs[byName]?.isNotEmpty??false)return byName;
   return 'stream:${item.streamId}';
 }
 Future<void> _shortGuide(MediaEntry item)async{
   if(widget.source?.kind!='xtream'||item.streamId.isEmpty||
       !epgRequested.add(item.id))return;
   final data=await widget.provider.shortEpg(widget.source!,item);
   if(!mounted||data.isEmpty)return;
   final entry='stream:${item.streamId}';
   setState(()=>programs[entry]=data);
 }
 MediaEntry? focused;Timer? debounce;VideoPlayerController? preview;
 bool previewEnabled=true;
 @override void initState(){super.initState();previewEnabled=widget.previewOn;widget.revision.addListener(_loadForAnchor);guideScroll.addListener(_onGuideScroll);_load();}
 @override void didUpdateWidget(covariant GuideScreen old){
   super.didUpdateWidget(old);
   if(old.channels!=widget.channels||old.group!=widget.group){
     guideGeneration++;
     epgQueue.clear();
     epgRequested.clear();
     if(guideScroll.hasClients)guideScroll.jumpTo(0);
     _load();
   }
 }
 Future<void> _load()async{
   final now=DateTime.now();
   anchor=DateTime(now.year,now.month,now.day,now.hour,now.minute<30?0:30);
   final result=await widget.db.schedules(widget.channels.expand((e)=>[if(e.epgId.isNotEmpty)e.epgId,'name:${GuideNames.canonical(e.cleanTitle)}']).toSet().toList(),
     anchor,anchor.add(const Duration(hours:3)));
   if(mounted){
     setState(()=>programs=result);
     // Start the embedded Live TV preview immediately on guide entry.
     // No separate picture-in-picture action is required.
     if(widget.channels.isNotEmpty&&focused==null){
       _queueVisibleGuides(0);
       WidgetsBinding.instance.addPostFrameCallback((_){
         if(mounted)_focus(widget.channels.first);
       });
     }
     // Prefetch the active viewport regardless of whether a channel is focused.
     WidgetsBinding.instance.addPostFrameCallback((_){
       if(mounted)_queueVisibleGuides(guideScroll.hasClients?(guideScroll.offset/44).floor():0);
     });
   }
 }
 void _focus(MediaEntry item){
   if(focused?.id==item.id&&preview!=null)return;
   final request=++previewRequest;
   setState(()=>focused=item);
   _shortGuide(item);
   debounce?.cancel();
   final old=preview;
   preview=null;
   old?.dispose();
   if(!previewEnabled||widget.source==null||item.id.startsWith('lineup:'))return;
   debounce=Timer(const Duration(milliseconds:450),()async{
     VideoPlayerController? controller;
     try{
       final uri=Uri.parse(widget.source!.playback(item));
       controller=VideoPlayerController.networkUrl(uri);
       await controller.initialize().timeout(const Duration(seconds:12));
       if(!mounted||request!=previewRequest||focused?.id!=item.id){
         await controller.dispose();return;
       }
       await controller.setVolume(0);
       await controller.setLooping(true);
       await controller.play();
       if(mounted&&request==previewRequest)setState(()=>preview=controller);
     }catch(_){
       await controller?.dispose();
       // Keep navigation available if the provider stream cannot preview.
     }
   });
 }
 @override void dispose(){
   ++previewRequest;
   widget.revision.removeListener(_loadForAnchor);
   debounce?.cancel();preview?.dispose();guideScroll.dispose();super.dispose();
 }
 @override Widget build(BuildContext context){
   final channels=widget.channels;
   final selected=focused??(channels.isNotEmpty?channels.first:null);
   final now=DateTime.now();
   final channelsWithEpg=widget.channels.where((e)=>programs[key(e)]?.isNotEmpty??false).length;
   final categoryGroups=['North America','All','Favorites',...widget.groups.take(12)].toSet().toList();
   return Padding(padding:const EdgeInsets.symmetric(horizontal:18,vertical:8),child:Column(children:[
     SizedBox(height:143,child:Row(children:[
       Expanded(child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
         Text(selected?.cleanTitle??'Live TV Guide',
           style:const TextStyle(fontSize:22,fontWeight:FontWeight.w800)),
         const SizedBox(height:6),
         Text(_currentProgram(selected,now)?.title??'Live channels · programme information as available',
           maxLines:1,overflow:TextOverflow.ellipsis,
           style:const TextStyle(fontSize:16,color:C.ink)),
         Text(_currentProgram(selected,now)?.description??'Navigate with your remote. Select a channel to watch.',
           maxLines:1,overflow:TextOverflow.ellipsis,style:const TextStyle(fontSize:12,color:C.secondary)),
       ])),
       const SizedBox(width:16),
       IconButton(tooltip:previewEnabled?'Turn off automatic preview':'Turn on automatic preview',
         onPressed:(){setState(()=>previewEnabled=!previewEnabled);
           if(!previewEnabled){++previewRequest;debounce?.cancel();preview?.dispose();preview=null;}
           else if(selected!=null){focused=null;_focus(selected);}},
         icon:Icon(previewEnabled?Icons.picture_in_picture:Icons.picture_in_picture_alt)),
       SizedBox(width:265,height:136,child:ClipRRect(borderRadius:BorderRadius.circular(9),
         child:ColoredBox(color:Colors.black,child:preview?.value.isInitialized==true?
           Center(child:AspectRatio(
             aspectRatio:preview!.value.aspectRatio>0?preview!.value.aspectRatio:16/9,
             child:VideoPlayer(preview!))):
           Stack(fit:StackFit.expand,children:[
             if(selected!=null)artwork(selected.artwork),
             const Center(child:Icon(Icons.live_tv,color:Colors.white70,size:28)),
           ])))),
     ])),
     Row(children:[
       TextButton(onPressed:widget.refreshEpg,child:const Text('↻ Refresh EPG')),
       const Spacer(),
       Text('${channels.length} CHANNELS · EPG ${channelsWithEpg}/${channels.length}',style:const TextStyle(color:C.secondary,fontSize:12)),
       const SizedBox(width:10),
       IconButton(onPressed:(){setState(()=>anchor=anchor.subtract(const Duration(hours:1)));_loadForAnchor();},
         icon:const Icon(Icons.chevron_left)),
       Text('${_clock(anchor)} — ${_clock(anchor.add(const Duration(hours:2)))}',
         style:const TextStyle(color:C.secondary,fontSize:12)),
       IconButton(onPressed:(){setState(()=>anchor=anchor.add(const Duration(hours:1)));_loadForAnchor();},
         icon:const Icon(Icons.chevron_right)),
     ]),
     Expanded(child:Row(children:[
       SizedBox(width:152,child:ListView.builder(itemCount:categoryGroups.length,
         itemBuilder:(ctx,i)=>AuroraButton(
           text:categoryGroups[i],selected:categoryGroups[i]==widget.group,
           onPressed:()=>widget.onGroup(group:categoryGroups[i])))),
       const SizedBox(width:8),
       Expanded(child:Column(children:[
         Container(height:35,color:const Color(0xff20313f),
           child:Row(children:[
             const SizedBox(width:190,child:Padding(padding:EdgeInsets.only(left:12),
               child:Text('CHANNEL',style:TextStyle(color:C.aqua,fontWeight:FontWeight.w700)))),
             for(var i=0;i<4;i++)Expanded(child:Text(_clock(anchor.add(Duration(minutes:30*i))),
               style:const TextStyle(color:C.ink,fontSize:13))),
           ])),
         Expanded(child:ListView.builder(controller:guideScroll,itemExtent:44,itemCount:channels.length,itemBuilder:(ctx,i){
           final item=channels[i];
           final blocks=programs[key(item)]??[];
           final current=focused?.id==item.id;
           final unavailable=item.id.startsWith('lineup:');
           return InkWell(
             onFocusChange:(value){if(value)_focus(item);},
             onTap:()=>item.id.startsWith('lineup:')?null:widget.onPlay(item),
             child:Container(height:42,margin:const EdgeInsets.only(bottom:2),
               decoration:BoxDecoration(color:current?const Color(0xff1b5051):
                 i.isEven?const Color(0xff142331):const Color(0xff192939)),
               child:Row(children:[
                 SizedBox(width:190,child:Padding(padding:const EdgeInsets.symmetric(horizontal:8),
                   child:Row(children:[
                     if(item.artwork.isNotEmpty)SizedBox(width:38,height:30,child:artwork(item.artwork,fit:BoxFit.contain)),
                     const SizedBox(width:5),
                     Expanded(child:Text('${ChannelLineup.referenceNumber(item)??''}  ${item.cleanTitle}${unavailable?' · Unavailable':''}',maxLines:1,overflow:TextOverflow.ellipsis,
                       style:const TextStyle(fontSize:13,fontWeight:FontWeight.w600))),
                   ]))),
                 for(var slot=0;slot<4;slot++)Expanded(child:Container(
                   margin:const EdgeInsets.only(right:2),
                   padding:const EdgeInsets.symmetric(horizontal:6,vertical:11),
                   color:slot.isEven?Colors.white.withValues(alpha:.045):Colors.white.withValues(alpha:.02),
                   child:Text(_blockTitle(blocks,anchor.add(Duration(minutes:slot*30))),
                     maxLines:1,overflow:TextOverflow.ellipsis,style:const TextStyle(fontSize:12)))),
               ]),
             ),
           );
         })),
       ])),
     ])),
   ]));
 }
 Future<void> _loadForAnchor()async{
   final data=await widget.db.schedules(widget.channels.expand((e)=>[if(e.epgId.isNotEmpty)e.epgId,'name:${GuideNames.canonical(e.cleanTitle)}']).toSet().toList(),
     anchor,anchor.add(const Duration(hours:3)));
   if(mounted){
     setState((){
       // Preserve per-channel Xtream fallbacks when changing the time window or
       // refreshing the main XMLTV cache.
       final perStream=Map<String,List<TvProgramme>>.fromEntries(programs.entries.where((e)=>e.key.startsWith('stream:')));
       programs={...data,...perStream};
     });
     _queueVisibleGuides(guideScroll.hasClients?(guideScroll.offset/44).floor():0);
   }
 }
 TvProgramme? _currentProgram(MediaEntry? item,DateTime clock){
   if(item==null)return null;
   for(final p in programs[key(item)]??[]){if(p.start.isBefore(clock)&&p.end.isAfter(clock))return p;}
   return null;
 }
 String _blockTitle(List<TvProgramme> entries,DateTime time){
   for(final p in entries){if(p.start.isBefore(time.add(const Duration(minutes:1)))&&p.end.isAfter(time))return p.title;}
   return 'Programme unavailable';
 }
 String _clock(DateTime time)=>TimeOfDay.fromDateTime(time).format(context);
}

class _SearchContent extends StatefulWidget{
 final CatalogDatabase db;
 final void Function(MediaEntry) onOpen,onFocused;
 const _SearchContent({required this.db,required this.onOpen,required this.onFocused});
 @override State<_SearchContent> createState()=>_SearchContentState();
}
class _SearchContentState extends State<_SearchContent>{
 final input=TextEditingController();List<MediaEntry> results=[];Timer? timer;
 @override void dispose(){timer?.cancel();input.dispose();super.dispose();}
 Future<void> _search() async {
   if(input.text.trim().isEmpty){setState(()=>results=[]);return;}
   final movies=await widget.db.list(MediaKind.movie,search:input.text,limit:55);
   final series=await widget.db.list(MediaKind.series,search:input.text,limit:40);
   final live=await widget.db.list(MediaKind.live,search:input.text,limit:20);
   if(mounted)setState(()=>results=[...movies,...series,...live]);
 }
 @override Widget build(BuildContext context)=>Padding(padding:const EdgeInsets.all(30),
   child:Column(children:[
     TextField(controller:input,onChanged:(_){
       timer?.cancel();timer=Timer(const Duration(milliseconds:260),_search);
     },decoration:const InputDecoration(prefixIcon:Icon(Icons.search),labelText:'Search your entire IPTV library')),
     const SizedBox(height:16),
     Expanded(child:GridView.builder(
       gridDelegate:const SliverGridDelegateWithMaxCrossAxisExtent(maxCrossAxisExtent:265,
         mainAxisSpacing:12,crossAxisSpacing:10,childAspectRatio:1.6),
       itemCount:results.length,
       itemBuilder:(ctx,i)=>MediaCard(item:results[i],index:0,
         onFocused:()=>widget.onFocused(results[i]),onOpen:()=>widget.onOpen(results[i])))),
   ]));
}

class _LibraryContent extends StatefulWidget{
 final CatalogDatabase db;final VoidCallback onRefresh;
 final void Function(MediaEntry) onHide,onFavorite;
 const _LibraryContent({required this.db,required this.onRefresh,required this.onHide,required this.onFavorite});
 @override State<_LibraryContent> createState()=>_LibraryContentState();
}
class _LibraryContentState extends State<_LibraryContent>{
 MediaKind kind=MediaKind.live;
 List<MediaEntry> rows=[];
 final search=TextEditingController();
 Timer? debounce;
 bool hiddenOnly=false,loading=false,hasMore=true;
 int offset=0,revision=0;
 final scroll=ScrollController();
 @override void initState(){
   super.initState();_load(reset:true);
   scroll.addListener((){
     if(scroll.hasClients&&scroll.position.extentAfter<450&&!loading&&hasMore)_load();
   });
 }
 Future<void> _load({bool reset=false})async{
   if(loading&&!reset)return;
   final id=reset?++revision:revision;
   if(reset){offset=0;rows=[];hasMore=true;}
   if(!hasMore)return;
   setState(()=>loading=true);
   final found=await widget.db.manage(kind,hiddenOnly:hiddenOnly,
     search:search.text,limit:75,offset:offset);
   if(!mounted||id!=revision)return;
   setState((){
     rows=[...rows,...found];
     offset+=found.length;
     hasMore=found.length==75;
     loading=false;
   });
 }
 Future<void> _mark(MediaEntry item,{bool hide=false}) async{
   // Library management is local and should never re-import the provider.
   await widget.db.mark(item.id,
     favorite:hide?null:!item.favorite,
     hidden:hide?!item.hidden:null);
   // Provider refresh is deliberately NOT called here.
   await _load(reset:true);
 }
 @override void dispose(){search.dispose();scroll.dispose();debounce?.cancel();super.dispose();}
 @override Widget build(BuildContext context)=>Padding(padding:const EdgeInsets.all(22),
   child:Column(children:[
     Row(children:[
       Text('EDIT LIBRARY',style:Theme.of(context).textTheme.titleLarge),
       const SizedBox(width:12),
       for(final t in MediaKind.values)AuroraButton(text:t.name.toUpperCase(),
         selected:kind==t,onPressed:(){kind=t;_load(reset:true);}),
       const Spacer(),
       AuroraButton(text:'Refresh provider',onPressed:widget.onRefresh),
     ]),
     const SizedBox(height:12),
     Row(children:[
       Expanded(child:TextField(controller:search,onChanged:(_){
         debounce?.cancel();
         debounce=Timer(const Duration(milliseconds:250),()=>_load(reset:true));
       },decoration:const InputDecoration(
         prefixIcon:Icon(Icons.search),hintText:'Filter library titles'))),
       const SizedBox(width:14),
       FilterChip(label:const Text('Show hidden'),selected:hiddenOnly,
         onSelected:(v){setState(()=>hiddenOnly=v);_load(reset:true);}),
     ]),
     const SizedBox(height:10),
     Expanded(child:ListView.builder(controller:scroll,
       itemCount:rows.length+(loading?1:0),
       itemBuilder:(ctx,i){
         if(i==rows.length)return const Center(child:Padding(
           padding:EdgeInsets.all(12),child:CircularProgressIndicator()));
         final item=rows[i];
         return ListTile(dense:true,title:Text(item.cleanTitle,
           maxLines:1,overflow:TextOverflow.ellipsis),
           subtitle:Text(item.category,maxLines:1),
           trailing:Row(mainAxisSize:MainAxisSize.min,children:[
             IconButton(tooltip:item.favorite?'Remove from My List':'Add to My List',
               onPressed:()=>_mark(item),
               icon:Icon(item.favorite?Icons.favorite:Icons.favorite_outline,
                 color:item.favorite?C.aqua:null)),
             IconButton(tooltip:item.hidden?'Restore channel':'Hide channel',
               onPressed:()=>_mark(item,hide:true),
               icon:Icon(item.hidden?Icons.visibility:Icons.visibility_off_outlined)),
           ]));
       })),
   ]));
}

class CatalogBrowseScreen extends StatefulWidget {
 final CatalogDatabase db;
 final MediaKind kind;
 final void Function(MediaEntry) onPlay, onFocus;
 const CatalogBrowseScreen({super.key,required this.db,required this.kind,
   required this.onPlay,required this.onFocus});
 @override State<CatalogBrowseScreen> createState()=>_CatalogBrowseState();
}
class _CatalogBrowseState extends State<CatalogBrowseScreen>{
 final search=TextEditingController(),scroll=ScrollController();
 List<String> categories=['All'];
 List<MediaEntry> results=[];
 String category='All'; bool busy=false,hasMore=true;
 int offset=0; int generation=0; Timer? debounce;
 @override void initState(){
   super.initState();_reload();
   scroll.addListener((){
     if(scroll.hasClients&&scroll.position.extentAfter<600&&!busy&&hasMore)_next();
   });
 }
 Future<void> _reload()async{
   final names=await widget.db.categories(widget.kind);
   if(!mounted)return;
   setState(()=>categories=['All',...names]);
   await _next(reset:true);
 }
 Future<void> _next({bool reset=false})async{
   if(busy&&!reset)return;
   final seq=reset?++generation:generation;
   if(reset){offset=0;hasMore=true;results=[];}
   if(!hasMore)return;
   setState(()=>busy=true);
   final rows=await widget.db.list(widget.kind,search:search.text,category:category,
       limit:48,offset:offset);
   if(!mounted||seq!=generation)return;
   setState((){
     results=[...results,...rows];offset+=48;
     hasMore=rows.length>=48;busy=false;
   });
 }
 @override void dispose(){debounce?.cancel();search.dispose();scroll.dispose();super.dispose();}
 @override Widget build(BuildContext context)=>Scaffold(
   backgroundColor:C.canvas,
   body:SafeArea(child:Padding(padding:const EdgeInsets.all(20),child:Column(children:[
     Row(children:[
       IconButton(onPressed:()=>Navigator.pop(context),
         icon:const Icon(Icons.arrow_back,color:C.aqua)),
       const SizedBox(width:10),
       Expanded(child:Text(widget.kind==MediaKind.movie?'MOVIE LIBRARY':'TV SERIES LIBRARY',
         style:const TextStyle(fontSize:24,fontWeight:FontWeight.w800))),
       SizedBox(width:260,child:TextField(controller:search,
         decoration:const InputDecoration(prefixIcon:Icon(Icons.search),hintText:'Find a title'),
         onChanged:(_){debounce?.cancel();debounce=Timer(const Duration(milliseconds:250),
           ()=>_next(reset:true));})),
       const SizedBox(width:16),
       DropdownButton<String>(value:category,dropdownColor:C.surface,
         items:categories.map((g)=>DropdownMenuItem(value:g,child:Text(g,
           overflow:TextOverflow.ellipsis))).toList(),
         onChanged:(g){if(g!=null){setState(()=>category=g);_next(reset:true);}}),
     ]),
     const SizedBox(height:14),
     Expanded(child:GridView.builder(controller:scroll,
       gridDelegate:const SliverGridDelegateWithMaxCrossAxisExtent(
         maxCrossAxisExtent:285,mainAxisSpacing:10,crossAxisSpacing:8,childAspectRatio:1.62),
       itemCount:results.length,
       itemBuilder:(ctx,i)=>MediaCard(item:results[i],index:0,
         onFocused:()=>widget.onFocus(results[i]),onOpen:()=>widget.onPlay(results[i])))),
     if(busy)const LinearProgressIndicator(color:C.aqua,minHeight:2),
   ]))));
}
