import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:http/http.dart' as http;
import 'catalog.dart';

class IptvSource {
  final String kind, server, username, password, playlist;
  const IptvSource({required this.kind,this.server='',this.username='',this.password='',this.playlist=''});
  String get base => server.trim().replaceAll(RegExp(r'/+$'), '');
  Uri get api => Uri.parse('$base/player_api.php').replace(queryParameters:{
    'username':username,'password':password,
  });
  Uri action(String name, [Map<String,String> extra=const {}]) =>
      api.replace(queryParameters:{...api.queryParameters,'action':name,...extra});
  String playback(MediaEntry item) {
    if (kind=='m3u') return item.directUrl;
    final ext=item.extension.isEmpty ? (item.kind==MediaKind.live?'ts':'mp4'):item.extension;
    final folder=item.kind==MediaKind.live?'live':item.kind==MediaKind.movie?'movie':'series';
    // Preserve provider-supplied server base, including nonstandard ports / context paths.
    return '$base/$folder/${Uri.encodeComponent(username)}/${Uri.encodeComponent(password)}/${item.streamId}.$ext';
  }
}

class IptvCredentials {
  static const _storage=FlutterSecureStorage(aOptions:AndroidOptions(encryptedSharedPreferences:true));
  static Future<void> save(IptvSource src) async {
    await _storage.write(key:'source',value:jsonEncode({
      'kind':src.kind,'server':src.server,'username':src.username,
      'password':src.password,'playlist':src.playlist,
    }));
  }
  static Future<IptvSource?> load() async {
    final saved=await _storage.read(key:'source');
    if(saved==null)return null;
    try {
      final j=jsonDecode(saved) as Map<String,dynamic>;
      return IptvSource(kind:j['kind'] as String? ?? '',server:j['server'] as String? ?? '',
        username:j['username'] as String? ?? '',password:j['password'] as String? ?? '',
        playlist:j['playlist'] as String? ?? '');
    }catch(_){return null;}
  }
  static Future<void> clear() => _storage.delete(key:'source');
}

String _string(dynamic value)=>value==null?'':value.toString();
int _year(String value) {
  final m=RegExp(r'(?:19|20)\d{2}').firstMatch(value);
  return int.tryParse(m?.group(0)??'')??0;
}

List<MediaEntry> _xtreamParse(Map<String,dynamic> args) {
  final kind=MediaKind.values.byName(args['kind'] as String);
  final input=args['data'] as List<dynamic>;
  final groups=Map<String,String>.from(args['groups'] as Map);
  final entries=<MediaEntry>[];
  for(final value in input) {
    if(value is! Map)continue;
    final j=Map<String,dynamic>.from(value);
    final id=_string(j[kind==MediaKind.series?'series_id':'stream_id']);
    if(id.isEmpty)continue;
    final title=_string(j['name']);
    if(title.isEmpty)continue;
    final category=groups[_string(j['category_id'])]??'Other';
    final artwork=_string(j[kind==MediaKind.series?'cover':'stream_icon']);
    final explicitExt=_string(j['container_extension']);
    final extension=explicitExt.isNotEmpty?explicitExt:(kind==MediaKind.live?'ts':'mp4');
    final year=_year(_string(j['releasedate']).isEmpty ? title:_string(j['releasedate']));
    final rating=double.tryParse(_string(j['rating']))??0;
    entries.add(MediaEntry(id:'xtream:${kind.name}:$id',title:title,kind:kind,
      category:category,artwork:artwork,streamId:id,extension:extension,year:year,rating:rating,
      epgId:_string(j['epg_channel_id'])));
  }
  return entries;
}

List<MediaEntry> _m3uParse(String source) {
  final output=<MediaEntry>[];
  String? extinf;
  final attributes=RegExp(r'([\w-]+)="([^"]*)"');
  final lines=const LineSplitter().convert(source);
  for(final raw in lines){
    final line=raw.trim();
    if(line.startsWith('#EXTINF:')){extinf=line;continue;}
    if(line.isEmpty||line.startsWith('#')||extinf==null)continue;
    final attrs=<String,String>{};
    for(final m in attributes.allMatches(extinf)){attrs[m.group(1)!]=m.group(2)!;}
    final comma=extinf.indexOf(',');
    final name=comma>=0?extinf.substring(comma+1).trim():'Untitled';
    final group=attrs['group-title']??'Other';
    final kind=line.contains('/series/')?MediaKind.series:line.contains('/movie/')?MediaKind.movie:MediaKind.live;
    // URL is used for playback; do not put source credentials in logs or UI.
    output.add(MediaEntry(
      id:'m3u:${kind.name}:${output.length}',title:name,kind:kind,
      category:group,artwork:attrs['tvg-logo']??'',directUrl:line,
      epgId:attrs['tvg-id']??'',
      year:_year(name),
    ));
    extinf=null;
  }
  return output;
}

class ProviderClient {
  final http.Client _http=http.Client();
  Future<List<dynamic>> _request(Uri url) async {
    final response=await _http.get(url).timeout(const Duration(seconds:35));
    if(response.statusCode!=200)throw Exception('Provider returned HTTP ${response.statusCode}');
    final data=await compute(_decodeJson,response.body);
    if(data is List)return data;
    if(data is Map && data['user_info'] is Map){
      final auth=data['user_info'] as Map;
      if(auth['auth']==0||auth['auth']=='0')throw Exception('Invalid Xtream login details');
      return [];
    }
    throw Exception('Provider returned an unexpected response');
  }
  static dynamic _decodeJson(String data) => jsonDecode(data);

  Future<void> verify(IptvSource source) async {
    if(source.kind=='m3u') {
      if(source.playlist.startsWith('#EXTM3U'))return;
      final u=Uri.tryParse(source.playlist);
      if(u==null||!u.hasScheme)throw Exception('Enter an M3U URL or paste an M3U playlist');
      return;
    }
    if(source.username.isEmpty||source.password.isEmpty||source.base.isEmpty){
      throw Exception('Server, username and password are required');
    }
    final response=await _http.get(source.api).timeout(const Duration(seconds:20));
    if(response.statusCode!=200)throw Exception('Could not connect: HTTP ${response.statusCode}');
    final payload=await compute(_decodeJson,response.body);
    if(payload is! Map||payload['user_info'] is! Map)throw Exception('Invalid Xtream server response');
    final user=payload['user_info'] as Map;
    if(user['auth']==0||user['auth']=='0')throw Exception('Xtream credentials were rejected');
  }

  Future<int> import(IptvSource source,CatalogDatabase db,
      void Function(String text) progress) async {
    if(source.kind=='m3u'){
      progress('Reading playlist…');
      String text=source.playlist;
      if(!text.startsWith('#EXTM3U')){
        final response=await _http.get(Uri.parse(text)).timeout(const Duration(seconds:60));
        if(response.statusCode!=200)throw Exception('Playlist HTTP ${response.statusCode}');
        text=response.body;
      }
      final items=await compute(_m3uParse,text);
      for(final kind in MediaKind.values){
        progress('Saving ${kind.name}…');
        await db.replaceKind(kind,items.where((e)=>e.kind==kind).toList());
      }
      return items.length;
    }
    var total=0;
    for(final pair in const [
      ('get_live_categories','get_live_streams',MediaKind.live),
      ('get_vod_categories','get_vod_streams',MediaKind.movie),
      ('get_series_categories','get_series',MediaKind.series),
    ]){
      final categoryAction=pair.$1;
      final listingAction=pair.$2;
      final kind=pair.$3;
      progress('Importing ${kind.name} categories…');
      final groups=<String,String>{};
      try {
        for(final v in await _request(source.action(categoryAction))){
          if(v is Map)groups[_string(v['category_id'])]=_string(v['category_name']);
        }
      }catch(_){/* A provider may omit optional categories. */}
      progress('Importing ${kind.name} entries…');
      final rows=await _request(source.action(listingAction));
      progress('Indexing ${kind.name}…');
      final items=await compute(_xtreamParse,{
        'kind':kind.name,'data':rows,'groups':groups,
      });
      await db.replaceKind(kind,items);
      total+=items.length;
    }
    return total;
  }

  // Xtream short EPG is a useful fallback when xmltv.php is disabled.
  // Data is fetched only for the stations being viewed, not thousands at once.
  Future<List<TvProgramme>> shortEpg(IptvSource source,MediaEntry station)async{
    if(source.kind!='xtream'||station.streamId.isEmpty)return [];
    try{
      final uri=source.action('get_short_epg',{
        'stream_id':station.streamId,'limit':'8'});
      final response=await _http.get(uri).timeout(const Duration(seconds:9));
      if(response.statusCode!=200)return [];
      final body=await compute(_decodeJson,response.body);
      if(body is! Map||body['epg_listings'] is! List)return [];
      final result=<TvProgramme>[];
      for(final row in body['epg_listings'] as List){
        if(row is! Map)continue;
        final start=int.tryParse(_string(row['start_timestamp']));
        final stop=int.tryParse(_string(row['stop_timestamp']));
        if(start==null||stop==null||stop<=start)continue;
        String title=_string(row['title']),description=_string(row['description']);
        String decodeText(String value){
          if(value.isEmpty)return '';
          try{return utf8.decode(base64.decode(base64.normalize(value)),allowMalformed:true);}
          catch(_){return value;}
        }
        title=decodeText(title);description=decodeText(description);
        if(title.isEmpty)continue;
        result.add(TvProgramme(
          channelId:station.epgId.isNotEmpty?station.epgId:'stream:${station.streamId}',
          title:title,description:description,
          start:DateTime.fromMillisecondsSinceEpoch(start*1000),
          end:DateTime.fromMillisecondsSinceEpoch(stop*1000)));
      }
      return result;
    }catch(_){return [];}
  }

  Future<List<MediaEntry>> episodes(IptvSource source,MediaEntry series) async {
    if(source.kind!='xtream')return [];
    final rows=await _http.get(source.action('get_series_info',{'series_id':series.streamId}))
        .timeout(const Duration(seconds:25));
    if(rows.statusCode!=200)return [];
    final info=await compute(_decodeJson,rows.body);
    if(info is! Map||info['episodes'] is! Map)return [];
    final episodes=<MediaEntry>[];
    (info['episodes'] as Map).forEach((season,list){
      if(list is! List)return;
      for(final episode in list){
        if(episode is! Map)continue;
        final id=_string(episode['id']);
        if(id.isEmpty)continue;
        episodes.add(MediaEntry(
          id:'xtream:episode:$id',title:_string(episode['title']).isEmpty?'Episode $id':_string(episode['title']),
          kind:MediaKind.series,category:'Season $season',streamId:id,
          extension:_string(episode['container_extension']).isEmpty?'mp4':_string(episode['container_extension']),
          artwork:series.artwork,
        ));
      }
    });
    return episodes;
  }

  void dispose()=>_http.close();
}
