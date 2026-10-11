import 'dart:async';
import 'dart:ui' show PlatformDispatcher;
import 'crash_diagnostics.dart';
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
  FlutterError.onError=(details){
    CrashDiagnostics.record('Flutter framework',details.exception,details.stack);
    FlutterError.presentError(details);
  };
  PlatformDispatcher.instance.onError=(error,stack){
    CrashDiagnostics.record('Platform',error,stack);
    return true;
  };
  CrashDiagnostics.mark('Launch');
  PaintingBinding.instance.imageCache.maximumSizeBytes=48*1024*1024;
  PaintingBinding.instance.imageCache.maximumSize=110;
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
  bool epgSyncing=false;
  String status='',epgUrl='';
  MediaEntry? featured;
  MediaEntry? detailItem;
  Future<TmdbTitleDetails?>? detailFuture;
  Future<List<MediaEntry>>? detailRelated;
  final FocusNode detailPlayFocus=FocusNode(debugLabel:'Details play');
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
  // Keep a reliable remote shortcut into the header, even on heavily
  // nested scrolling pages (Home shelves, guide, search, edit library).
  void _focusNavigation([int? index]){
    if(!mounted)return;
    final target=navFocus[(index??page).clamp(0,navFocus.length-1)];
    target.requestFocus();
    WidgetsBinding.instance.addPostFrameCallback((_){
      if(!mounted||!target.hasFocus)return;
      final ctx=target.context;
      if(ctx!=null){
        Scrollable.ensureVisible(ctx,alignment:0.4,
          duration:const Duration(milliseconds:160));
      }
    });
  }
  void _backToNavigation(){
    if(detailItem!=null){
      setState(()=>detailItem=null);
      WidgetsBinding.instance.addPostFrameCallback((_){
        if(mounted)_focusNavigation(page);
      });
      return;
    }
    if(navFocus.any((n)=>n.hasFocus) && page!=0){
      _choose(0);
    }else{
      _focusNavigation();
    }
  }
  KeyEventResult _onShellKey(FocusNode node,KeyEvent event){
    if(event is! KeyDownEvent)return KeyEventResult.ignored;
    final key=event.logicalKey;
    if(key==LogicalKeyboardKey.contextMenu||
       key==LogicalKeyboardKey.escape||
       key==LogicalKeyboardKey.goBack||
       key==LogicalKeyboardKey.browserBack){
      _backToNavigation();
      return KeyEventResult.handled;
    }
    final active=navFocus.indexWhere((n)=>n.hasFocus);
    if(active>=0){
      if(key==LogicalKeyboardKey.arrowLeft && active>0){
        _focusNavigation(active-1);
        return KeyEventResult.handled;
      }
      if(key==LogicalKeyboardKey.arrowRight && active<navFocus.length-1){
        _focusNavigation(active+1);
        return KeyEventResult.handled;
      }
    }else if(key==LogicalKeyboardKey.arrowUp){
      // At the beginning of the currently scrolled page, Up returns to the
      // header. Away from the top it remains regular directional traversal.
      final ctx=FocusManager.instance.primaryFocus?.context;
      if(ctx!=null){
        final scroll=Scrollable.maybeOf(ctx,axis:Axis.vertical);
        final atTop=scroll==null||
          scroll.position.pixels<=scroll.position.minScrollExtent+2;
        final render=ctx.findRenderObject();
        if(render is RenderBox&&render.hasSize&&atTop){
          final y=render.localToGlobal(Offset.zero).dy;
          if(y<MediaQuery.sizeOf(context).height*.55){
            _focusNavigation();
            return KeyEventResult.handled;
          }
        }
      }
    }
    return KeyEventResult.ignored;
  }
  Future<void> _restore() async{
    CrashDiagnostics.mark('Restoring library');
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
      if(source!=null)WidgetsBinding.instance.addPostFrameCallback((_){
        if(mounted&&!showLogin)_focusNavigation(0);
      });
      _getTrends();
      // The EPG can be tens of MB. Do not re-download, decompress and
      // reinsert it on every app launch; keep the persisted guide for 8 hours.
      final lastEpg=prefs.getInt('epg.last_success')??0;
      if(source!=null&&DateTime.now().millisecondsSinceEpoch-lastEpg>
          const Duration(hours:8).inMilliseconds)_refreshEpg(silent:true);
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
      WidgetsBinding.instance.addPostFrameCallback((_){
        if(mounted&&!showLogin)_focusNavigation(0);
      });
      _getTrends();
      (await SharedPreferences.getInstance()).remove('epg.last_success');
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
    if(source==null||epgSyncing)return;
    epgSyncing=true;
    if(!silent&&mounted)setState((){loading=true;status='Importing programme information…';});
    try{
      final count=await epg.refresh(source!,db,externalUrl:epgUrl);
      await (await SharedPreferences.getInstance()).setInt(
        'epg.last_success',DateTime.now().millisecondsSinceEpoch);
      if(mounted){
        if(!silent)setState((){loading=false;status='Updated $count real EPG programmes';});
        guideRevision.value++;
      }
    }catch(e){
      if(mounted&&!silent)setState((){loading=false;status='EPG error: $e';});
    }finally{epgSyncing=false;}
  }
  void _openCatalog(MediaKind kind){
    Navigator.of(context).push(MaterialPageRoute<void>(builder:(_)=>CatalogBrowseScreen(
      db:db,kind:kind,onPlay:_open,onFocus:_feature)));
  }
  void _choose(int index){
    if(page==index&&detailItem==null)return;
    if(detailItem!=null)setState(()=>detailItem=null);
    if(page==index)return;
    CrashDiagnostics.mark('Navigation tab $index');
    FocusManager.instance.primaryFocus?.unfocus();
    setState(()=>page=index);
    WidgetsBinding.instance.addPostFrameCallback((_){
      if(mounted && page==index)_focusNavigation(index);
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
    CrashDiagnostics.mark('Opening ${item.kind.name}');
    await _recordHistory(item);
    final src=source;
    if(src==null)return;
    if(item.kind==MediaKind.series&&!item.id.startsWith('xtream:episode:')){
      try{
        // A title can have several provider series IDs. The highest-ranked
        // rendition sometimes has an empty episode listing, so try alternates.
        final candidates=await db.seriesCandidates(item.cleanTitle,max:8);
        final attempts=<MediaEntry>[item,...candidates.where((e)=>e.id!=item.id)];
        String? issue;
        for(final candidate in attempts){
          List<MediaEntry> episodes;
          try{episodes=await provider.episodes(src,candidate);}
          catch(e){issue=e.toString().replaceFirst('Exception: ','');continue;}
          if(!mounted)return;
          if(episodes.isNotEmpty){_episodes(candidate,episodes);return;}
        }
        // Some services list S01E01 / 1x01 episodes as VOD files and leave
        // get_series_info empty. Only accept strict title+episode matches.
        final standalone=await db.standaloneEpisodes(item.cleanTitle);
        if(!mounted)return;
        if(standalone.isNotEmpty){
          _episodes(item,standalone);
          ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
            content:Text('Episodes located in your IPTV on-demand catalog.')));
          return;
        }
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          duration:const Duration(seconds:10),
          content:Text(issue==null
           ?'Series ${item.streamId}: provider returned no episodes and no separately listed VOD episodes were found.'
           :'Series ${item.streamId}: $issue. No separately listed episodes found.')));
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
      builder:(_)=>PlayerScreen(title:item.cleanTitle,url:stream,live:item.kind==MediaKind.live,
        alternatives:item.kind==MediaKind.live?null:()=>provider.playbackOptions(src,item))));
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
    if(item.kind==MediaKind.live){_open(item);return;}
    detailFuture=tmdb.fullDetails(item);
    detailRelated=detailFuture!.then((details)async{
      if(details==null||details.related.isEmpty)return <MediaEntry>[];
      final matches=await db.matchTitleMap(item.kind,
        details.related.map((e)=>e.title).toList());
      return [
        for(final related in details.related)
          if(matches[related.title]!=null)
            _withTmdbArt(matches[related.title]!,related),
      ].take(18).toList();
    });
    setState(()=>detailItem=item);
    final route=ModalRoute.of(context);
    if(route!=null&&!route.isCurrent&&Navigator.of(context).canPop()){
      Navigator.of(context).pop();
    }
    WidgetsBinding.instance.addPostFrameCallback((_){
      if(mounted&&detailItem?.id==item.id)detailPlayFocus.requestFocus();
    });
  }
  Widget _titlePage(MediaEntry item){
    return FutureBuilder<TmdbTitleDetails?>(
      future:detailFuture,builder:(ctx,snapshot){
      final info=snapshot.data;
      final backdrop=info?.backdrop.isNotEmpty==true?info!.backdrop:item.artwork;
      return ListView(padding:const EdgeInsets.only(bottom:55),children:[
        SizedBox(height:245,child:Stack(children:[
          Positioned.fill(child:artwork(backdrop,memoryWidth:1280)),
          const Positioned.fill(child:DecoratedBox(decoration:BoxDecoration(
            gradient:LinearGradient(
              colors:[Color(0xff070e16),Color(0xe6070e16),Color(0x22070e16)],
              stops:[0,.56,1])))),
          Positioned(left:28,top:12,child:TextButton.icon(
            onPressed:()=>_backToNavigation(),
            icon:const Icon(Icons.arrow_back),label:const Text('BACK TO BROWSE'))),
          Positioned(left:32,right:260,bottom:24,child:Column(
            crossAxisAlignment:CrossAxisAlignment.start,mainAxisSize:MainAxisSize.min,
            children:[
              Text(item.cleanTitle,maxLines:2,overflow:TextOverflow.ellipsis,
                style:const TextStyle(color:Colors.white,fontSize:32,
                  fontWeight:FontWeight.w800,height:1.06)),
              const SizedBox(height:9),
              Text([
                if(item.year>0)'${item.year}',
                if(info?.runtime!=null&&info!.runtime>0)'${info.runtime} min',
                if(info!=null&&info.rating>0)'★ ${info.rating.toStringAsFixed(1)}',
                if(info?.genres.isNotEmpty==true)info!.genres,
              ].join('  ·  '),maxLines:1,overflow:TextOverflow.ellipsis,
                style:const TextStyle(color:C.gold,fontSize:13)),
              if(info?.tagline.isNotEmpty==true)Padding(
                padding:const EdgeInsets.only(top:6),
                child:Text(info!.tagline,maxLines:1,overflow:TextOverflow.ellipsis,
                  style:const TextStyle(color:C.secondary,fontSize:13))),
            ])),
        ])),
        Padding(padding:const EdgeInsets.fromLTRB(32,8,32,12),
          child:Row(children:[
            OutlinedButton.icon(focusNode:detailPlayFocus,
              onPressed:()=>_open(item),icon:const Icon(Icons.play_arrow),
              label:Text(item.kind==MediaKind.series?'View episodes':'Play movie'),
              style:const ButtonStyle(
                backgroundColor:WidgetStatePropertyAll(C.aqua),
                foregroundColor:WidgetStatePropertyAll(Colors.black),
                padding:WidgetStatePropertyAll(EdgeInsets.symmetric(
                  horizontal:20,vertical:12)))),
            const SizedBox(width:12),
            OutlinedButton.icon(onPressed:()async{
              await _toggle(item);
              if(mounted&&detailItem?.id==item.id){
                _details(MediaEntry(id:item.id,title:item.title,kind:item.kind,
                  category:item.category,artwork:item.artwork,extension:item.extension,
                  streamId:item.streamId,directUrl:item.directUrl,epgId:item.epgId,
                  year:item.year,rating:item.rating,favorite:!item.favorite));
              }
            },icon:Icon(item.favorite?Icons.check:Icons.add),
              label:Text(item.favorite?'In My List':'My List')),
            const Spacer(),
            const Text('SOURCE: IPTV PROVIDER',
              style:TextStyle(fontSize:11,color:C.secondary,letterSpacing:1.3)),
          ])),
        if(snapshot.connectionState==ConnectionState.waiting)
          const Padding(padding:EdgeInsets.symmetric(horizontal:32,vertical:6),
            child:LinearProgressIndicator(minHeight:2,color:C.aqua)),
        Padding(padding:const EdgeInsets.fromLTRB(32,8,32,18),child:Column(
          crossAxisAlignment:CrossAxisAlignment.start,children:[
            const Text('OVERVIEW',style:TextStyle(fontSize:14,
              color:C.aqua,fontWeight:FontWeight.w800,letterSpacing:1.3)),
            const SizedBox(height:7),
            Text(info?.overview.isNotEmpty==true?info!.overview:
              'No verified TMDB description for this provider title. '
              'Playback uses the actual IPTV catalog entry.',
              style:const TextStyle(fontSize:14,height:1.5,color:C.ink)),
          ])),
        if(info?.cast.isNotEmpty==true)...[
          const Padding(padding:EdgeInsets.fromLTRB(32,0,32,9),child:Text('CAST',
            style:TextStyle(fontSize:17,fontWeight:FontWeight.w700))),
          SizedBox(height:118,child:ListView.separated(
            scrollDirection:Axis.horizontal,padding:const EdgeInsets.symmetric(horizontal:32),
            itemCount:info!.cast.length,
            separatorBuilder:(_,__)=>const SizedBox(width:12),
            itemBuilder:(ctx,i){
              final actor=info.cast[i];
              return SizedBox(width:160,child:Row(children:[
                SizedBox(width:45,height:66,child:ClipRRect(
                  borderRadius:BorderRadius.circular(7),
                  child:artwork(actor.photo,fit:BoxFit.cover,memoryWidth:185))),
                const SizedBox(width:7),
                Expanded(child:Column(mainAxisAlignment:MainAxisAlignment.center,
                  crossAxisAlignment:CrossAxisAlignment.start,children:[
                  Text(actor.name,maxLines:2,overflow:TextOverflow.ellipsis,
                    style:const TextStyle(fontSize:12,fontWeight:FontWeight.w700)),
                  Text(actor.character,maxLines:2,overflow:TextOverflow.ellipsis,
                    style:const TextStyle(fontSize:11,color:C.secondary)),
                ])),
              ]));
            })),
        ],
        FutureBuilder<List<MediaEntry>>(future:detailRelated,
          builder:(ctx,results){
            final related=results.data??[];
            return related.isEmpty?const SizedBox.shrink():
              _shelf('MORE LIKE THIS · IN YOUR LIBRARY',related,item.kind);
          }),
      ]);
    });
  }
  Future<void> _showCrashReport() async{
    final report=await CrashDiagnostics.report();
    if(!mounted)return;
    await showDialog<void>(context:context,builder:(ctx)=>AlertDialog(
      backgroundColor:C.surface,
      title:const Text('Crash diagnostics'),
      content:SizedBox(width:650,height:370,
        child:SingleChildScrollView(child:SelectableText(report))),
      actions:[
        TextButton(onPressed:(){
          Clipboard.setData(ClipboardData(text:report));
          Navigator.pop(ctx);
        },child:const Text('Copy report')),
        TextButton(onPressed:()=>Navigator.pop(ctx),child:const Text('Close')),
      ],
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
          TextButton(onPressed:(){
            Navigator.pop(ctx);
            _showCrashReport();
          },child:const Text('Crash diagnostics')),
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
    heroVersion.dispose();guideRevision.dispose();detailPlayFocus.dispose();
    for(final node in navFocus){node.dispose();}
    navScroll.dispose();
    super.dispose();
  }

  @override Widget build(BuildContext context){
    if(!ready)return const Scaffold(body:Center(child:CircularProgressIndicator(color:C.aqua)));
    if(showLogin)return LoginScreen(connect:_connect,loading:loading,status:status);

    final media=MediaQuery.sizeOf(context);
    final isCinema=page==0||page==2||page==3;
    return PopScope(
      canPop:false,
      onPopInvokedWithResult:(didPop,_){if(!didPop)_backToNavigation();},
      child:Scaffold(body:Focus(
        skipTraversal:true,
        onKeyEvent:_onShellKey,
        child:Stack(children:[
      Positioned.fill(child:ValueListenableBuilder<int>(valueListenable:heroVersion,
        builder:(_,__,___)=>AnimatedSwitcher(duration:const Duration(milliseconds:350),
        child:Container(key:ValueKey(isCinema?(meta.backdrop.isNotEmpty?meta.backdrop:featured?.artwork??''):'empty'),
          color:C.canvas,
          child:isCinema&&featured!=null
          ?meta.backdrop.isNotEmpty
            ?SizedBox.expand(child:artwork(meta.backdrop,fit:BoxFit.cover,memoryWidth:1280))
            :SizedBox.expand(child:artwork(featured!.artwork,fit:BoxFit.cover,memoryWidth:1280))
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
        // Render only the active section. IndexedStack kept all pages alive,
        // including Live TV's decoder and several poster-heavy genre pages
        // while the viewer was elsewhere, consuming Fire TV graphics memory.
        Expanded(child:_activePage()),
      ])),
      if(loading)Positioned(left:0,right:0,bottom:0,child:Container(
        color:Colors.black87,padding:const EdgeInsets.all(12),
        child:Text(status,textAlign:TextAlign.center))),
    ])))); 
  }
  Widget _activePage(){
    if(detailItem!=null)return _titlePage(detailItem!);
    switch(page){
      case 0: return _discovery(MediaKind.movie,home:true);
      case 1: return GuideScreen(
        db:db,channels:live,groups:groups[MediaKind.live]??[],
        group:channelGroup,onGroup:_loadLive,onPlay:_open,previewOn:previewOn,
        source:source,provider:provider,refreshEpg:()=>_refreshEpg(),revision:guideRevision);
      case 2: return _discovery(MediaKind.movie);
      case 3: return _discovery(MediaKind.series);
      case 4: return _myList();
      case 5: return _searchPage();
      case 6: return _libraryPage();
      default: return _discovery(MediaKind.movie,home:true);
    }
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
      // Compact featured area leaves room for multiple shelves on TV screens.
      final heroHeight=(available*.38).clamp(190.0,295.0);
      return ListView(padding:EdgeInsets.zero,children:[
        SizedBox(height:heroHeight,child:ValueListenableBuilder<int>(valueListenable:heroVersion,
          builder:(_,__,___)=>Stack(children:[
          Align(alignment:Alignment.centerLeft,
            child:Padding(padding:const EdgeInsets.fromLTRB(26,8,0,8),
              child:ConstrainedBox(
                constraints:BoxConstraints(maxWidth:math.min(c.maxWidth*.48,570)),
                child:_heroText(heroHeight)))),
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
  Widget _heroText(double heroHeight){
    final current=featured;
    if(current==null)return const Text('Connect your IPTV library to discover entertainment',
      style:TextStyle(fontSize:24,color:C.ink));
    final hasMeta=meta.rating>0||current.rating>0;
    final rating=meta.rating>0?meta.rating:current.rating;
    return Column(mainAxisSize:MainAxisSize.min,crossAxisAlignment:CrossAxisAlignment.start,children:[
      Text('AURORATV  /  FEATURED',style:TextStyle(fontSize:10,letterSpacing:2.1,
        fontWeight:FontWeight.w700,color:C.aqua.withValues(alpha:.95))),
      const SizedBox(height:6),
      Text(current.cleanTitle.toUpperCase(),maxLines:2,overflow:TextOverflow.ellipsis,
        style:TextStyle(fontSize:math.min(32,MediaQuery.sizeOf(context).height*.055),
          fontWeight:FontWeight.w800,height:1.04,letterSpacing:.7,color:Colors.white,
          shadows:const [Shadow(blurRadius:12,color:Colors.black)])),
      const SizedBox(height:7),
      Text('${hasMeta?'★ ${rating.toStringAsFixed(1)}     ':''}${current.year>0?'${current.year}    ':''}${meta.genres.isNotEmpty?meta.genres:current.kind==MediaKind.series?'TV SERIES':'MOVIE'}',
        style:const TextStyle(color:C.gold,fontSize:12.5,fontWeight:FontWeight.w600)),
      if(meta.overview.isNotEmpty&&heroHeight>=250)...[
        const SizedBox(height:7),
        Text(meta.overview,maxLines:2,overflow:TextOverflow.ellipsis,
          style:const TextStyle(fontSize:12.5,height:1.26,color:C.ink)),
      ],
      const SizedBox(height:12),
      Row(mainAxisSize:MainAxisSize.min,children:[
        SizedBox(height:38,child:AuroraButton(text:'▶  Watch Now',primary:true,onPressed:()=>_open(current))),
        const SizedBox(width:8),
        SizedBox(height:38,child:AuroraButton(text:'ⓘ  More Info',onPressed:()=>_details(current))),
      ]),
    ]);
  }
  Widget _shelf(String heading,List<MediaEntry> items,MediaKind kind,{bool ranked=false}){
    if(items.isEmpty)return const SizedBox.shrink();
    return Padding(padding:const EdgeInsets.only(top:4,bottom:7),child:Column(
      crossAxisAlignment:CrossAxisAlignment.start,children:[
      Padding(padding:const EdgeInsets.fromLTRB(26,0,22,3),child:Row(children:[
        Expanded(child:Text(heading,style:const TextStyle(fontSize:17,fontWeight:FontWeight.w700))),
        TextButton(onPressed:()=>_choose(kind==MediaKind.series?3:2),
          child:const Text('See All  →',style:TextStyle(color:C.secondary,fontSize:12))),
      ])),
      SizedBox(height:118,child:ListView.builder(
        scrollDirection:Axis.horizontal,padding:const EdgeInsets.symmetric(horizontal:22),
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
      const SizedBox(height:12),
      Expanded(child:GridView.builder(gridDelegate:const SliverGridDelegateWithMaxCrossAxisExtent(
        maxCrossAxisExtent:208,mainAxisSpacing:10,crossAxisSpacing:8,childAspectRatio:1.56),
        itemCount:items.length,itemBuilder:(ctx,i)=>MediaCard(item:items[i],index:0,
          onFocused:()=>_feature(items[i]),onOpen:()=>_details(items[i])))),
    ]));
  Widget _searchPage()=>_SearchContent(db:db,onOpen:_details,onFocused:_feature);
  Widget _libraryPage()=>_LibraryContent(db:db,onRefresh:_refresh,onHide:(item)=>_toggle(item,hide:true),
    onFavorite:_toggle);
}

Widget artwork(String url,{BoxFit fit=BoxFit.cover,int memoryWidth=640}){
  if(url.isEmpty||!url.startsWith('http'))return const DecoratedBox(
    decoration:BoxDecoration(gradient:LinearGradient(colors:[Color(0xff172a38),Color(0xff07121d)])),
    child:Center(child:Icon(Icons.movie_creation_outlined,size:39,color:Color(0xff35505d))));
  return CachedNetworkImage(imageUrl:url,fit:fit,
    memCacheWidth:memoryWidth,maxWidthDiskCache:memoryWidth>720?1280:720,
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
        backgroundColor:WidgetStateProperty.resolveWith((states)=>
          states.contains(WidgetState.focused)?C.aqua.withValues(alpha:.35):
          primary?Colors.white:
          selected?C.aqua.withValues(alpha:.16):Colors.black.withValues(alpha:nav ? 0.02 : 0.45)),
        foregroundColor:WidgetStatePropertyAll(primary?Colors.black:Colors.white),
        side:WidgetStateProperty.resolveWith((states)=>BorderSide(
          width:states.contains(WidgetState.focused)?2.5:1,
          color:states.contains(WidgetState.focused)?C.aqua:
            selected?C.aqua:primary?Colors.white:C.secondary.withValues(alpha:nav?0:.23))),
        shape:WidgetStatePropertyAll(RoundedRectangleBorder(borderRadius:BorderRadius.circular(nav?12:9))),
      ),child:Text(text,maxLines:1,style:TextStyle(fontSize:nav?14:15,fontWeight:FontWeight.w700))),
  );
}

class MediaCard extends StatefulWidget{
  final MediaEntry item;final int index;final VoidCallback onFocused,onOpen;
  final FocusNode? focusNode;
  const MediaCard({super.key,required this.item,required this.index,
    required this.onFocused,required this.onOpen,this.focusNode});
  @override State<MediaCard> createState()=>_MediaCardState();
}
class _MediaCardState extends State<MediaCard>{
 bool focused=false;
 @override Widget build(BuildContext context)=>Padding(
  padding:const EdgeInsets.symmetric(horizontal:5,vertical:4),
  child:InkWell(
    canRequestFocus:true,focusNode:widget.focusNode,
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
      width:184,decoration:BoxDecoration(
        borderRadius:BorderRadius.circular(12),
        border:Border.all(width:focused?3:1,color:focused?C.aqua:Colors.white12),
        boxShadow:focused?[BoxShadow(color:C.aqua.withValues(alpha:.22),blurRadius:10)]:[],
      ),clipBehavior:Clip.antiAlias,
      child:Stack(fit:StackFit.expand,children:[
        artwork(widget.item.artwork),
        DecoratedBox(decoration:BoxDecoration(gradient:LinearGradient(
          begin:Alignment.topCenter,end:Alignment.bottomCenter,
          colors:[Colors.transparent,Colors.black.withValues(alpha:.84)]))),
        if(widget.index>0)Positioned(top:5,left:8,
          child:Text('#${widget.index}',style:const TextStyle(
            fontSize:12,color:C.aqua,fontWeight:FontWeight.w800))),
        Positioned(left:9,right:9,bottom:7,child:Text(widget.item.cleanTitle,
          maxLines:2,overflow:TextOverflow.ellipsis,
          style:const TextStyle(fontSize:13,fontWeight:FontWeight.w700,color:Colors.white,
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

class PlayerScreen extends StatefulWidget {
 final String title,url;
 final bool live;
 final List<MediaEntry> episodes;
 final MediaEntry? episode;
 final IptvSource? source;
 final Future<List<String>> Function()? alternatives;
 const PlayerScreen({super.key,required this.title,required this.url,this.live=false,
   this.episodes=const [],this.episode,this.source,this.alternatives});
 @override State<PlayerScreen> createState()=>_PlayerScreenState();
}
class _PlayerScreenState extends State<PlayerScreen>{
 VideoPlayerController? video;
 final FocusNode surfaceFocus=FocusNode(debugLabel:'Player video');
 final FocusNode playFocus=FocusNode(debugLabel:'Player controls');
 final FocusNode backFocus=FocusNode(debugLabel:'Back from player');
 static const pipChannel=MethodChannel('aurora.tv/picture_in_picture');
 static const externalChannel=MethodChannel('aurora.tv/external_playback');
 bool controls=true,buffering=true,muted=false;
 int displayMode=0,attempt=0;
 double playbackSpeed=1;
 String error='',status='Opening video…',lastUrl='';
 String? episodeTitle,episodeUrl;
 Timer? hideTimer;
 @override void initState(){
   super.initState();
   CrashDiagnostics.mark(widget.live?'Playing live TV':'Playing VOD');
   _init();
 }
 Future<void> _init()async{
   final revision=++attempt;
   hideTimer?.cancel();
   final previous=video;
   video=null;
   previous?.removeListener(_monitor);
   try{await previous?.dispose();}catch(_){}
   if(!mounted||revision!=attempt)return;
   setState((){error='';buffering=true;controls=true;status='Opening video…';});
   final candidates=<String>[episodeUrl??widget.url];
   var expanded=false;
   Object? lastFailure;
   for(var i=0;i<candidates.length && i<5;i++){
     if(!mounted||revision!=attempt)return;
     final url=candidates[i];
     lastUrl=url;
     VideoPlayerController? controller;
     try{
       setState(()=>status=i==0?'Connecting to provider…':
         'Trying provider format ${i+1} of ${candidates.length}…');
       controller=VideoPlayerController.networkUrl(Uri.parse(url));
       video=controller;
       controller.addListener(_monitor);
       await controller.initialize().timeout(const Duration(seconds:14));
       if(!mounted||revision!=attempt){
         controller.removeListener(_monitor);
         await controller.dispose();
         return;
       }
       await controller.setVolume(muted?0:1);
       try{await controller.setPlaybackSpeed(playbackSpeed);}catch(_){}
       await controller.play();
       if(!mounted||revision!=attempt)return;
       setState((){buffering=false;error='';status='';controls=true;});
       _showControls();
       return;
     }catch(e){
       lastFailure=e;
       controller?.removeListener(_monitor);
       if(identical(video,controller))video=null;
       try{await controller?.dispose();}catch(_){}
       if(!mounted||revision!=attempt)return;
       if(!expanded && widget.alternatives!=null && episodeUrl==null){
         expanded=true;
         try{
           final options=await widget.alternatives!()
             .timeout(const Duration(seconds:10));
           for(final next in options){
             if(next!=url&&!candidates.contains(next)&&
                (next.startsWith('http://')||next.startsWith('https://'))){
               candidates.add(next);
             }
           }
         }catch(_){}
       }
     }
   }
   if(!mounted||revision!=attempt)return;
   final safe=(lastFailure??'Unknown stream error').toString()
     .replaceAll(RegExp(r'https?://[^\s,)\]]+'),'[provider URL hidden]')
     .replaceAll(RegExp(r'(username|password|token)=[^\s&,]+',caseSensitive:false),
       '[credential hidden]');
   setState((){
     buffering=false;controls=true;
     status='';
     error='Unable to decode or load this IPTV stream. '
       'The provider may require a different container or codec.'
       '\n${safe.length>210?safe.substring(0,210):safe}';
   });
   CrashDiagnostics.record('Playback error',error,null);
 }
 void _monitor(){
   final v=video;
   if(!mounted||v==null)return;
   if(v.value.hasError&&error.isEmpty){
     final message=(v.value.errorDescription??'Decoder error').replaceAll(
       RegExp(r'https?://[^\s,)\]]+'),'[provider URL hidden]');
     setState(()=>error=message);
   }else if(v.value.isInitialized){
     setState(()=>buffering=v.value.isBuffering);
   }
 }
 void _showControls(){
   hideTimer?.cancel();
   if(mounted)setState(()=>controls=true);
   hideTimer=Timer(const Duration(seconds:12),(){
     if(mounted&&surfaceFocus.hasFocus&&video?.value.isPlaying==true){
       setState(()=>controls=false);
     }
   });
 }
 void _playPause(){
   final v=video;
   if(v==null||!v.value.isInitialized)return;
   v.value.isPlaying?v.pause():v.play();
   _showControls();
 }
 void _seek(Duration delta){
   final v=video;
   if(v==null||!v.value.isInitialized||v.value.duration.inSeconds<=0)return;
   final target=v.value.position+delta;
   final clamped=target<Duration.zero?Duration.zero:
     target>v.value.duration?v.value.duration:target;
   v.seekTo(clamped);
   _showControls();
 }
 KeyEventResult _surfaceKey(FocusNode node,KeyEvent event){
   if(event is! KeyDownEvent)return KeyEventResult.ignored;
   final key=event.logicalKey;
   if(key==LogicalKeyboardKey.arrowDown){
     _showControls();
     playFocus.requestFocus();
     return KeyEventResult.handled;
   }
   if(key==LogicalKeyboardKey.arrowUp){
     _showControls();
     backFocus.requestFocus();
     return KeyEventResult.handled;
   }
   if(key==LogicalKeyboardKey.select||key==LogicalKeyboardKey.enter||
      key==LogicalKeyboardKey.space){
     _playPause();
     return KeyEventResult.handled;
   }
   if(key==LogicalKeyboardKey.arrowLeft){
     _seek(const Duration(seconds:-10));return KeyEventResult.handled;
   }
   if(key==LogicalKeyboardKey.arrowRight){
     _seek(const Duration(seconds:10));return KeyEventResult.handled;
   }
   return KeyEventResult.ignored;
 }
 Future<void> _pip()async{
   if(!widget.live)return;
   try{await pipChannel.invokeMethod<void>('enter');}
   catch(_){if(mounted)ScaffoldMessenger.of(context).showSnackBar(
     const SnackBar(content:Text('Picture-in-picture unavailable.')));}
 }
 Future<void> _external()async{
   final url=lastUrl.isNotEmpty?lastUrl:widget.url;
   try{
     await externalChannel.invokeMethod<bool>('open',{'url':url});
   }catch(_){
     if(mounted)ScaffoldMessenger.of(context).showSnackBar(
       const SnackBar(content:Text('Install a compatible player such as VLC '
         'to open this provider stream externally.')));
   }
 }
 Future<void> _aspectMenu()async{
   await showModalBottomSheet<void>(context:context,
     backgroundColor:const Color(0xff101c29),
     builder:(ctx)=>SafeArea(child:Column(mainAxisSize:MainAxisSize.min,children:[
       const Padding(padding:EdgeInsets.all(16),child:Text('DISPLAY MODE',
         style:TextStyle(fontSize:18,fontWeight:FontWeight.w800))),
       for(var i=0;i<3;i++)
         ListTile(
           leading:Icon(displayMode==i?Icons.check_circle:Icons.radio_button_unchecked,
             color:displayMode==i?C.aqua:C.secondary),
           title:Text(['Fit · Original ratio','Fill · Crop edges','Stretch · Entire screen'][i]),
           onTap:(){setState(()=>displayMode=i);Navigator.pop(ctx);_showControls();}),
     ])));
 }
 Future<void> _speedMenu()async{
   await showModalBottomSheet<void>(context:context,
     backgroundColor:const Color(0xff101c29),
     builder:(ctx)=>SafeArea(child:Column(mainAxisSize:MainAxisSize.min,children:[
       const Padding(padding:EdgeInsets.all(12),child:Text('PLAYBACK SPEED',
         style:TextStyle(fontSize:18,fontWeight:FontWeight.w800))),
       for(final value in <double>[.5,.75,1,1.25,1.5,2])
         ListTile(
           leading:Icon(playbackSpeed==value?Icons.check_circle:Icons.radio_button_unchecked,
             color:playbackSpeed==value?C.aqua:C.secondary),
           title:Text('${value}×'),
           onTap:()async{
             playbackSpeed=value;
             try{await video?.setPlaybackSpeed(value);}catch(_){}
             if(ctx.mounted)Navigator.pop(ctx);
             _showControls();
           }),
     ])));
 }
 void _selectEpisode(MediaEntry episode){
   final src=widget.source;
   if(src==null)return;
   episodeTitle=episode.title;
   episodeUrl=src.playback(episode);
   _init();
 }
 Future<void> _episodePicker()async{
  if(widget.episodes.isEmpty)return;
  final grouped=<int,List<MediaEntry>>{};
  for(final e in widget.episodes){
   final match=RegExp(r'Season\s*(\d+)',caseSensitive:false).firstMatch(e.category);
   final season=int.tryParse(match?.group(1)??'')??0;
   grouped.putIfAbsent(season,()=>[]).add(e);
  }
  final sorted=grouped.keys.toList()..sort();
  final picked=await showModalBottomSheet<MediaEntry>(context:context,
   backgroundColor:const Color(0xff101e29),isScrollControlled:true,
   builder:(ctx){
    var season=sorted.first;
    return StatefulBuilder(builder:(ctx,rebuild)=>SafeArea(child:SizedBox(
     height:math.min(MediaQuery.sizeOf(ctx).height*.78,600),
     child:Column(children:[
      Padding(padding:const EdgeInsets.symmetric(horizontal:20,vertical:10),
       child:Row(children:[
        const Expanded(child:Text('SEASONS & EPISODES',style:TextStyle(
          fontSize:19,fontWeight:FontWeight.bold))),
        DropdownButton<int>(value:season,dropdownColor:const Color(0xff1a2e3c),
         items:[for(final key in sorted)DropdownMenuItem(value:key,
           child:Text(key==0?'Specials':'Season $key'))],
         onChanged:(value){if(value!=null)rebuild(()=>season=value);}),
       ])),
      Expanded(child:ListView.builder(itemCount:grouped[season]!.length,
       itemBuilder:(ctx,i){
        final ep=grouped[season]![i];
        return ListTile(title:Text(ep.title,maxLines:2,overflow:TextOverflow.ellipsis),
         subtitle:Text(ep.category),trailing:const Icon(Icons.play_arrow),
         onTap:()=>Navigator.pop(ctx,ep));
       })),
     ]))));
   });
  if(picked!=null&&mounted)_selectEpisode(picked);
 }

 Widget _action(IconData icon,String label,VoidCallback action,{FocusNode? node}){
   return Padding(padding:const EdgeInsets.only(right:8),
     child:OutlinedButton.icon(
       focusNode:node,onPressed:action,icon:Icon(icon,size:20),
       label:Text(label,style:const TextStyle(fontSize:12,fontWeight:FontWeight.w600)),
       style:ButtonStyle(
         padding:const WidgetStatePropertyAll(
           EdgeInsets.symmetric(horizontal:13,vertical:12)),
         backgroundColor:WidgetStateProperty.resolveWith((states)=>
           states.contains(WidgetState.focused)?
             C.aqua.withValues(alpha:.35):Colors.white.withValues(alpha:.09)),
         side:WidgetStateProperty.resolveWith((states)=>BorderSide(
           width:states.contains(WidgetState.focused)?2:1,
           color:states.contains(WidgetState.focused)?C.aqua:Colors.white24)),
         foregroundColor:const WidgetStatePropertyAll(Colors.white),
         shape:WidgetStatePropertyAll(RoundedRectangleBorder(
           borderRadius:BorderRadius.circular(10))),
       )));
 }
 String _time(Duration v){
   final h=v.inHours,m=v.inMinutes.remainder(60),s=v.inSeconds.remainder(60);
   final mm=m.toString().padLeft(2,'0'),ss=s.toString().padLeft(2,'0');
   return h>0?'$h:$mm:$ss':'$m:$ss';
 }
 @override void dispose(){
   ++attempt;
   hideTimer?.cancel();
   surfaceFocus.dispose();playFocus.dispose();backFocus.dispose();
   video?.removeListener(_monitor);
   video?.dispose();
   super.dispose();
 }
 @override Widget build(BuildContext context){
   final v=video;
   final initialized=v?.value.isInitialized==true;
   final seekable=initialized&&!widget.live&&v!.value.duration.inSeconds>0&&
     v.value.duration< const Duration(days:1);
   final duration=initialized?v!.value.duration:Duration.zero;
   final position=initialized?v!.value.position:Duration.zero;
   final progress=seekable&&duration.inMilliseconds>0?
     (position.inMilliseconds/duration.inMilliseconds).clamp(0.0,1.0):0.0;
   return Scaffold(backgroundColor:Colors.black,body:FocusTraversalGroup(
     child:Stack(children:[
       Positioned.fill(child:Focus(
         focusNode:surfaceFocus,autofocus:true,onKeyEvent:_surfaceKey,
         child:GestureDetector(behavior:HitTestBehavior.opaque,onTap:_showControls,
           child:initialized?FittedBox(
             fit:[BoxFit.contain,BoxFit.cover,BoxFit.fill][displayMode],
             child:SizedBox(width:v!.value.size.width>0?v.value.size.width:640,
               height:v.value.size.height>0?v.value.size.height:360,
               child:VideoPlayer(v))):
             const ColoredBox(color:Colors.black)))),
       if(buffering)Center(child:Column(mainAxisSize:MainAxisSize.min,children:[
         const CircularProgressIndicator(color:C.aqua),
         const SizedBox(height:15),
         Text(status,style:const TextStyle(color:C.secondary,fontSize:14)),
       ])),
       if(controls||error.isNotEmpty)Positioned(top:0,left:0,right:0,
         child:Container(padding:const EdgeInsets.fromLTRB(20,14,20,18),
           decoration:const BoxDecoration(gradient:LinearGradient(
             begin:Alignment.topCenter,end:Alignment.bottomCenter,
             colors:[Color(0xdd070d15),Color(0x00070d15)])),
           child:Row(children:[
             _action(Icons.arrow_back,'Back',()=>Navigator.pop(context),node:backFocus),
             const SizedBox(width:12),
             Expanded(child:Column(crossAxisAlignment:CrossAxisAlignment.start,
               mainAxisSize:MainAxisSize.min,children:[
               Text(episodeTitle??widget.title,maxLines:1,
                 overflow:TextOverflow.ellipsis,
                 style:const TextStyle(fontSize:20,fontWeight:FontWeight.w800)),
               Text(widget.live?'LIVE · IPTV PROVIDER':'ON DEMAND · IPTV PROVIDER',
                 style:const TextStyle(fontSize:11,color:C.secondary,letterSpacing:1.4)),
             ])),
             _action(Icons.refresh,'Retry',_init),
             if(widget.live)_action(Icons.picture_in_picture_alt_outlined,'PiP',_pip),
           ]))),
       if(controls&&initialized&&error.isEmpty)Positioned(
         bottom:0,left:0,right:0,
         child:Container(padding:const EdgeInsets.fromLTRB(25,25,25,18),
           decoration:const BoxDecoration(gradient:LinearGradient(
             begin:Alignment.topCenter,end:Alignment.bottomCenter,
             colors:[Color(0x00070d15),Color(0xf2070d15)])),
           child:Column(crossAxisAlignment:CrossAxisAlignment.stretch,children:[
             Row(children:[
               Text(widget.live?'LIVE':_time(position),
                 style:const TextStyle(fontSize:12,color:C.secondary)),
               const SizedBox(width:12),
               Expanded(child:ClipRRect(borderRadius:BorderRadius.circular(3),
                 child:LinearProgressIndicator(value:seekable?progress:null,
                   minHeight:4,color:C.aqua,
                   backgroundColor:Colors.white24))),
               const SizedBox(width:12),
               Text(widget.live?'ON AIR':_time(duration),
                 style:const TextStyle(fontSize:12,color:C.secondary)),
             ]),
             const SizedBox(height:12),
             SingleChildScrollView(scrollDirection:Axis.horizontal,child:Row(children:[
               _action(v!.value.isPlaying?Icons.pause:Icons.play_arrow,
                 v.value.isPlaying?'Pause':'Play',_playPause,node:playFocus),
               if(seekable)_action(Icons.replay_10,'Back 10s',
                 ()=>_seek(const Duration(seconds:-10))),
               if(seekable)_action(Icons.forward_10,'Forward 10s',
                 ()=>_seek(const Duration(seconds:10))),
               _action(muted?Icons.volume_off:Icons.volume_up,
                 muted?'Unmute':'Mute',(){
                   setState(()=>muted=!muted);
                   v.setVolume(muted?0:1);
                   _showControls();
                 }),
               _action(Icons.aspect_ratio,'Aspect ratio',_aspectMenu),
               if(!widget.live)_action(Icons.speed,'Speed',_speedMenu),
               if(widget.episodes.isNotEmpty)_action(
                 Icons.video_library_outlined,'Seasons & episodes',_episodePicker),
               if(!widget.live)_action(Icons.open_in_new,'External player',_external),
             ])),
           ]))),
       if(error.isNotEmpty)Center(child:Container(
         width:math.min(MediaQuery.sizeOf(context).width*.68,650),
         padding:const EdgeInsets.all(23),
         decoration:BoxDecoration(color:const Color(0xf5111b27),
           borderRadius:BorderRadius.circular(18),border:Border.all(color:Colors.white24)),
         child:Column(mainAxisSize:MainAxisSize.min,children:[
           const Icon(Icons.warning_amber_rounded,size:36,color:C.gold),
           const SizedBox(height:8),
           const Text('STREAM COULD NOT PLAY',
             style:TextStyle(fontSize:18,fontWeight:FontWeight.w800)),
           const SizedBox(height:8),
           Text(error,textAlign:TextAlign.center,
             style:const TextStyle(fontSize:12.5,color:C.secondary)),
           const SizedBox(height:14),
           Row(mainAxisAlignment:MainAxisAlignment.center,children:[
             _action(Icons.refresh,'Retry formats',_init),
             _action(Icons.open_in_new,'External player',_external),
             _action(Icons.arrow_back,'Back',()=>Navigator.pop(context)),
           ]),
         ]))),
     ])));
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
 Timer? guideScrollTimer;
 final Set<String> coveredStations=<String>{};
 int epgInFlight=0;
 bool guideDbLoading=false;
 int guideGeneration=0;
 int guideLoadRequest=0;
 int epgLoaded=0;
 int epgWork=0;
 int guideLastWindow=-1;
 DateTime? guideLastAnchor;
 // Small, paced queue for weak Fire TV sticks and rate-limited providers.
 // No more than one HTTP EPG lookup at a time, with a quiet period between.
 static const int epgConcurrency=1;
 static const Duration epgPause=Duration(milliseconds:750);
 static const Duration guideScrollPause=Duration(milliseconds:380);
 bool get epgLoading=>guideDbLoading||epgInFlight>0||epgQueue.isNotEmpty;
 void _updateEpgIndicator(){
   // Focus changes can arrive while Flutter is laying out guide rows.
   // Do not call setState synchronously during focus traversal.
   WidgetsBinding.instance.addPostFrameCallback((_){
     if(mounted)setState((){});
   });
 }
 void _queueVisibleGuides(int first,{MediaEntry? priority}){
   if(!mounted||widget.source?.kind!='xtream')return;
   final channels=widget.channels;
   if(channels.isEmpty)return;
   final start=first<0?0:first>=channels.length?channels.length-1:first;
   // Fill only the visible rows and a small look-ahead, not 25+ requests.
   final end=start+16>channels.length?channels.length:start+16;
   final candidates=<MediaEntry>[
     if(priority!=null)priority,
     ...channels.sublist(start,end),
   ];
   for(final dropped in epgQueue){epgRequested.remove(dropped.id);}
   epgQueue.clear();
   for(final item in candidates){
     if(item.streamId.isEmpty||item.id.startsWith('lineup:')||
        (programs[key(item)]?.isNotEmpty??false)||
        !epgRequested.add(item.id))continue;
     epgQueue.add(item);
   }
   // The progress count is for the current visible batch only.
   epgLoaded=0;
   epgWork=epgQueue.length+epgInFlight;
   _updateEpgIndicator();
   _drainEpg();
 }
 void _drainEpg(){
   if(!mounted||epgInFlight>=epgConcurrency||epgQueue.isEmpty)return;
   final item=epgQueue.removeAt(0);
   final generation=guideGeneration;
   final source=widget.source;
   if(source==null)return;
   epgInFlight=1;
   _updateEpgIndicator();
   () async{
     try{
       final data=await widget.provider.shortEpg(source,item);
       if(mounted&&generation==guideGeneration&&data.isNotEmpty){
         setState((){
           programs['stream:${item.streamId}']=data;
           coveredStations.add(item.id);
         });
       }
     }catch(_){
       // A missing provider guide cannot block navigating channels.
     }finally{
       // Slow down even successful small responses; never burst through
       // dozens of channels during focus/scroll navigation.
       await Future<void>.delayed(epgPause);
       epgInFlight=0;
       if(mounted){
         if(generation==guideGeneration)epgLoaded++;
         setState((){});
         _drainEpg();
       }
     }
   }();
 }
 void _onGuideScroll(){
   if(!guideScroll.hasClients)return;
   guideScrollTimer?.cancel();
   guideScrollTimer=Timer(guideScrollPause,(){
     if(!mounted||!guideScroll.hasClients)return;
     final first=(guideScroll.offset/44).floor();
     _loadVisibleEpg(first);
   });
 }
 int _firstVisible()=>guideScroll.hasClients?(guideScroll.offset/44).floor():0;
 /// Query SQLite for only the visible guide rows. The previous version
 /// loaded the entire 6,000+ channel EPG, repeatedly, on the UI isolate.
 /// All existing XMLTV and per-channel provider fallback sources remain.
 Future<void> _loadVisibleEpg(int first,{bool reset=false})async{
   if(!mounted||widget.channels.isEmpty)return;
   final generation=guideGeneration;
   final request=++guideLoadRequest;
   final clamped=first<0?0:first>=widget.channels.length?widget.channels.length-1:first;
   final window=clamped~/6;
   if(!reset&&guideLastWindow==window&&guideLastAnchor==anchor)return;
   guideLastWindow=window;
   guideLastAnchor=anchor;
   final start=clamped>4?clamped-4:0;
   final end=clamped+20>widget.channels.length?widget.channels.length:clamped+20;
   final stations=widget.channels.sublist(start,end);
   final ids=stations.expand((e)=>[
     if(e.epgId.isNotEmpty)e.epgId,
     'name:${GuideNames.canonical(e.cleanTitle)}',
   ]).toSet().toList();
   final begin=anchor;
   guideDbLoading=true;
   _updateEpgIndicator();
   try{
     final data=await widget.db.schedules(ids,begin,begin.add(const Duration(hours:3)));
     if(!mounted||generation!=guideGeneration||request!=guideLoadRequest)return;
     setState((){
       if(reset)programs.removeWhere((key,_)=>!key.startsWith('stream:'));
       // Limit data retained from previously visited guide windows.
       if(programs.length>520){
         programs.removeWhere((key,_)=>!key.startsWith('stream:'));
         if(programs.length>520)programs.clear();
         coveredStations.clear();
       }
       programs.addAll(data);
       for(final item in stations){
         if((programs[item.epgId]?.isNotEmpty??false)||
             (programs['name:${GuideNames.canonical(item.cleanTitle)}']?.isNotEmpty??false)||
             (programs['stream:${item.streamId}']?.isNotEmpty??false)){
           coveredStations.add(item.id);
         }
       }
     });
     _queueVisibleGuides(clamped);
   }catch(_){
     // Keep channel navigation and provider short EPG available on DB failure.
   }finally{
     if(mounted&&generation==guideGeneration&&request==guideLoadRequest){
       guideDbLoading=false;
       _updateEpgIndicator();
     }
   }
 }
 String key(MediaEntry item){
   if(item.epgId.isNotEmpty&&(programs[item.epgId]?.isNotEmpty??false))return item.epgId;
   final byName='name:${GuideNames.canonical(item.cleanTitle)}';
   if(programs[byName]?.isNotEmpty??false)return byName;
   return 'stream:${item.streamId}';
 }
 void _shortGuide(MediaEntry item){
   if(widget.source?.kind!='xtream'||item.streamId.isEmpty||
     (programs[key(item)]?.isNotEmpty??false))return;
   // A focused station has priority, but must use the same single-request
   // queue rather than creating a concurrent independent network call.
   _queueVisibleGuides(_firstVisible(),priority:item);
 }
 MediaEntry? focused;Timer? debounce;VideoPlayerController? preview;
 bool previewEnabled=true;
 // Decoder initialization and release must be serialized on Android TV.
 // Rapid guide navigation must not create multiple simultaneous decoders.
 Future<void> previewSerial=Future<void>.value();
 void _serialPreview(Future<void> Function() operation){
   previewSerial=previewSerial.then((_)=>operation()).catchError((Object _){});
 }
 void _cancelPreview(){
   ++previewRequest;
   debounce?.cancel();
   final old=preview;
   preview=null;
   if(old!=null)_serialPreview(() async{
     // Wait until VideoPlayer is removed from the rebuilt widget tree.
     // Avoid tearing down an Android surface still referenced by this frame.
     await WidgetsBinding.instance.endOfFrame;
     try{await old.pause();}catch(_){}
     try{await old.dispose();}catch(_){}
   });
 }
 @override void initState(){super.initState();previewEnabled=widget.previewOn;widget.revision.addListener(_loadForAnchor);guideScroll.addListener(_onGuideScroll);_load();}
 @override void didUpdateWidget(covariant GuideScreen old){
   super.didUpdateWidget(old);
   if(old.channels!=widget.channels||old.group!=widget.group){
     guideGeneration++;
     guideLoadRequest++;
     guideLastWindow=-1;
     guideLastAnchor=null;
     guideDbLoading=false;
     epgLoaded=0;
     epgWork=0;
     guideScrollTimer?.cancel();
     _cancelPreview();
     focused=null;
     programs.clear();
     coveredStations.clear();
     epgQueue.clear();
     epgRequested.clear();
     if(guideScroll.hasClients)guideScroll.jumpTo(0);
     _load();
   }
 }
 Future<void> _load()async{
   final now=DateTime.now();
   anchor=DateTime(now.year,now.month,now.day,now.hour,now.minute<30?0:30);
   guideLastWindow=-1;
   await _loadVisibleEpg(0,reset:true);
   if(!mounted)return;
   if(widget.channels.isNotEmpty&&focused==null){
     _queueVisibleGuides(0);
     WidgetsBinding.instance.addPostFrameCallback((_){
       if(mounted&&widget.channels.isNotEmpty&&focused==null)_focus(widget.channels.first);
     });
   }
 }
 void _focus(MediaEntry item){
   if(focused?.id==item.id&&preview!=null)return;
   CrashDiagnostics.mark('Guide preview');
   _cancelPreview();
   final request=previewRequest;
   setState(()=>focused=item);
   _shortGuide(item);
   if(!previewEnabled||widget.source==null||item.id.startsWith('lineup:'))return;
   // Give the D-pad time to settle, then wait for the previous decoder to
   // finish releasing before creating a new native VideoPlayerController.
   debounce=Timer(const Duration(milliseconds:850),(){
     _serialPreview(() async{
       if(!mounted||request!=previewRequest||!previewEnabled||
          focused?.id!=item.id||widget.source==null)return;
       VideoPlayerController? controller;
       try{
         final uri=Uri.parse(widget.source!.playback(item));
         controller=VideoPlayerController.networkUrl(uri);
         await controller.initialize().timeout(const Duration(seconds:12));
         if(!mounted||request!=previewRequest||focused?.id!=item.id){
           await controller.dispose();return;
         }
         await controller.setVolume(0);
         // Live transport streams are already continuous; looping may
         // reinitialize the native codec on several Fire TV builds.
         await controller.play();
         if(!mounted||request!=previewRequest||focused?.id!=item.id){
           await controller.dispose();return;
         }
         setState(()=>preview=controller);
       }catch(_){
         await controller?.dispose();
       }
     });
   });
 }
 @override void dispose(){
   _cancelPreview();
   guideScrollTimer?.cancel();
   widget.revision.removeListener(_loadForAnchor);
   guideScroll.removeListener(_onGuideScroll);
   guideScroll.dispose();
   super.dispose();
 }
 @override Widget build(BuildContext context){
   final channels=widget.channels;
   final selected=focused??(channels.isNotEmpty?channels.first:null);
   final now=DateTime.now();
   final channelsWithEpg=coveredStations.length;
   final pending=epgLoading;
   final loadingDone=epgLoaded>epgWork?epgWork:epgLoaded;
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
         onPressed:(){
           if(previewEnabled){
             _cancelPreview();
             setState(()=>previewEnabled=false);
           }else{
             setState(()=>previewEnabled=true);
             if(selected!=null){focused=null;_focus(selected);}
           }
         },
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
       Text('${channels.length} CHANNELS · EPG ${channelsWithEpg}/${channels.length}',
         style:const TextStyle(color:C.secondary,fontSize:12)),
       const SizedBox(width:12),
       if(pending)Row(mainAxisSize:MainAxisSize.min,children:[
         const SizedBox(width:12,height:12,child:CircularProgressIndicator(
           strokeWidth:2,color:C.aqua)),
         const SizedBox(width:7),
         Text(guideDbLoading&&epgWork==0
           ?'EPG loading…'
           :'EPG loading ${loadingDone}/${epgWork}',
           style:const TextStyle(color:C.aqua,fontSize:12)),
       ])else const Text('EPG idle',
         style:TextStyle(color:C.secondary,fontSize:12)),
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
   guideLastWindow=-1;
   guideLastAnchor=null;
   await _loadVisibleEpg(_firstVisible(),reset:true);
   if(!mounted)return;
   _queueVisibleGuides(_firstVisible());
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

/// Search is a selectable control on TV. D-pad focus does not summon a
/// virtual keyboard until the user actually presses Select.
class TvSearchField extends StatelessWidget {
 final TextEditingController controller;
 final String hint;
 final Future<void> Function(String query) onSubmitted;
 const TvSearchField({super.key,required this.controller,required this.hint,
   required this.onSubmitted});
 Future<void> _edit(BuildContext context) async {
   final query=await showDialog<String>(context:context,
     builder:(_)=>_TvSearchDialog(initial:controller.text,hint:hint));
   if(query==null||!context.mounted)return;
   controller.value=TextEditingValue(text:query,
     selection:TextSelection.collapsed(offset:query.length));
   await onSubmitted(query);
 }
 @override Widget build(BuildContext context)=>OutlinedButton.icon(
   onPressed:()=>_edit(context),icon:const Icon(Icons.search,size:22),
   label:Align(alignment:Alignment.centerLeft,child:Text(
     controller.text.isEmpty?hint:controller.text,
     maxLines:1,overflow:TextOverflow.ellipsis,
     style:TextStyle(color:controller.text.isEmpty?C.secondary:C.ink))),
   style:ButtonStyle(
     minimumSize:const WidgetStatePropertyAll(Size(130,51)),
     padding:const WidgetStatePropertyAll(EdgeInsets.symmetric(horizontal:14)),
     backgroundColor:WidgetStatePropertyAll(C.surface.withValues(alpha:.85)),
     alignment:Alignment.centerLeft,
     shape:WidgetStatePropertyAll(RoundedRectangleBorder(
       borderRadius:BorderRadius.circular(9))),
     side:WidgetStateProperty.resolveWith((states)=>BorderSide(
       width:states.contains(WidgetState.focused)?2.5:1,
       color:states.contains(WidgetState.focused)?C.aqua:Colors.white24)),
   ),
 );
}

/// Owns its text controller for the entire modal route lifetime.
/// Disposing a controller immediately after Navigator.pop can crash on TV,
/// because the closing keyboard still references EditableText briefly.
class _TvSearchDialog extends StatefulWidget {
 final String initial,hint;
 const _TvSearchDialog({required this.initial,required this.hint});
 @override State<_TvSearchDialog> createState()=>_TvSearchDialogState();
}
class _TvSearchDialogState extends State<_TvSearchDialog>{
 late final TextEditingController draft;
 @override void initState(){super.initState();draft=TextEditingController(text:widget.initial);}
 @override void dispose(){draft.dispose();super.dispose();}
 @override Widget build(BuildContext context)=>AlertDialog(
   backgroundColor:C.surface,title:const Text('Search'),
   content:SizedBox(width:490,child:TextField(
     controller:draft,autofocus:true,textInputAction:TextInputAction.search,
     decoration:InputDecoration(prefixIcon:const Icon(Icons.search),hintText:widget.hint),
     onSubmitted:(value)=>Navigator.pop(context,value))),
   actions:[
     TextButton(onPressed:()=>Navigator.pop(context),child:const Text('Cancel')),
     FilledButton(onPressed:()=>Navigator.pop(context,draft.text),
       child:const Text('Search')),
   ],
 );
}

class _SearchContent extends StatefulWidget{
 final CatalogDatabase db;
 final void Function(MediaEntry) onOpen,onFocused;
 const _SearchContent({required this.db,required this.onOpen,required this.onFocused});
 @override State<_SearchContent> createState()=>_SearchContentState();
}
class _SearchContentState extends State<_SearchContent>{
 final input=TextEditingController();
 final firstResult=FocusNode(debugLabel:'Search first result');
 List<MediaEntry> results=[];
 int request=0;
 @override void dispose(){++request;input.dispose();firstResult.dispose();super.dispose();}
 Future<void> _search(String text) async {
   final revision=++request;
   final term=text.trim();
   if(term.isEmpty){if(mounted)setState(()=>results=[]);return;}
   final movies=await widget.db.list(MediaKind.movie,search:term,limit:55);
   final series=await widget.db.list(MediaKind.series,search:term,limit:40);
   final live=await widget.db.list(MediaKind.live,search:term,limit:20);
   if(!mounted||revision!=request)return;
   setState(()=>results=[...movies,...series,...live]);
   if(results.isNotEmpty)WidgetsBinding.instance.addPostFrameCallback((_){
     if(mounted&&revision==request)firstResult.requestFocus();
   });
 }
 @override Widget build(BuildContext context)=>Padding(padding:const EdgeInsets.all(30),
   child:Column(children:[
     TvSearchField(controller:input,hint:'Search movies, shows and channels',
       onSubmitted:_search),
     const SizedBox(height:16),
     Expanded(child:GridView.builder(
       gridDelegate:const SliverGridDelegateWithMaxCrossAxisExtent(maxCrossAxisExtent:208,
         mainAxisSpacing:9,crossAxisSpacing:8,childAspectRatio:1.56),
       itemCount:results.length,
       itemBuilder:(ctx,i)=>MediaCard(item:results[i],index:0,
         focusNode:i==0?firstResult:null,
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
       Expanded(child:TvSearchField(controller:search,hint:'Filter library titles',
         onSubmitted:(_)=>_load(reset:true))),
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
       SizedBox(width:260,child:TvSearchField(controller:search,
         hint:'Find a title',onSubmitted:(_)=>_next(reset:true))),
       const SizedBox(width:16),
       DropdownButton<String>(value:category,dropdownColor:C.surface,
         items:categories.map((g)=>DropdownMenuItem(value:g,child:Text(g,
           overflow:TextOverflow.ellipsis))).toList(),
         onChanged:(g){if(g!=null){setState(()=>category=g);_next(reset:true);}}),
     ]),
     const SizedBox(height:14),
     Expanded(child:GridView.builder(controller:scroll,
       gridDelegate:const SliverGridDelegateWithMaxCrossAxisExtent(
         maxCrossAxisExtent:208,mainAxisSpacing:9,crossAxisSpacing:8,childAspectRatio:1.56),
       itemCount:results.length,
       itemBuilder:(ctx,i)=>MediaCard(item:results[i],index:0,
         onFocused:()=>widget.onFocus(results[i]),onOpen:()=>widget.onPlay(results[i])))),
     if(busy)const LinearProgressIndicator(color:C.aqua,minHeight:2),
   ]))));
}
