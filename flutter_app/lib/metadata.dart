import 'dart:convert';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';
import 'catalog.dart';

class MovieMeta {
 final String backdrop,overview,genres,trailer;
 final double rating;
 const MovieMeta({this.backdrop='',this.overview='',this.genres='',this.trailer='',this.rating=0});
 factory MovieMeta.fromJson(Map<String,dynamic> j)=>MovieMeta(
   backdrop:j['backdrop'] as String? ?? '',
   overview:j['overview'] as String? ?? '',
   genres:j['genres'] as String? ?? '',
   trailer:j['trailer'] as String? ?? '',
   rating:(j['rating'] as num?)?.toDouble() ?? 0);
 Map<String,dynamic> toJson()=>{'backdrop':backdrop,'overview':overview,'genres':genres,'trailer':trailer,'rating':rating};
}

class TmdbClient {
 final http.Client _client=http.Client();
 String apiKey='';
 final Map<String,MovieMeta> _mem={};
 final Map<String,List<String>> _trending={};
 final Map<String,Map<String,List<String>>> _weekly={};
 static const genres={
  28:'Action',12:'Adventure',16:'Animation',35:'Comedy',80:'Crime',
  99:'Documentary',18:'Drama',10751:'Family',14:'Fantasy',36:'History',
  27:'Horror',10402:'Music',9648:'Mystery',10749:'Romance',
  878:'Sci-Fi',10770:'TV Movie',53:'Thriller',10752:'War',37:'Western',
  10759:'Action & Adventure',10765:'Sci-Fi & Fantasy',10762:'Kids',
 };
 Future<void> restore() async {
   apiKey=(await SharedPreferences.getInstance()).getString('tmdb.key')??'';
 }
 Future<void> saveKey(String key) async {
   apiKey=key.trim();_mem.clear();_trending.clear();
   _weekly.clear();
   (await SharedPreferences.getInstance()).setString('tmdb.key',apiKey);
 }
 Future<List<String>> trending(MediaKind kind) async {
   if(apiKey.isEmpty)return [];
   final media=kind==MediaKind.movie?'movie':'tv';
   final date=DateTime.now().toUtc().toIso8601String().substring(0,10);
   final cacheKey='tmdb.daily.$media.$date';
   if(_trending.containsKey(cacheKey))return _trending[cacheKey]!;
   final prefs=await SharedPreferences.getInstance();
   final saved=prefs.getString(cacheKey);
   if(saved!=null){
    try{final result=(jsonDecode(saved) as List).cast<String>();_trending[cacheKey]=result;return result;}catch(_){}
   }
   try{
     final response=await _client.get(Uri.https('api.themoviedb.org','/3/trending/$media/day',
       {'api_key':apiKey,'language':'en-US'})).timeout(const Duration(seconds:7));
     if(response.statusCode!=200)return [];
     final data=jsonDecode(response.body) as Map<String,dynamic>;
     final titles=<String>[];
     for(final entry in (data['results'] as List? ?? [])){
       if(entry is! Map || entry['original_language']!='en')continue;
       final title=entry[media=='movie'?'title':'name']?.toString()??'';
       if(title.isNotEmpty)titles.add(title);
       if(titles.length>=20)break;
     }
     await prefs.setString(cacheKey,jsonEncode(titles));
     _trending[cacheKey]=titles;
     return titles;
   }catch(_){return [];}
 }
 /// English-language TMDB trending titles grouped by actual TMDB genres.
 /// Fetch three weekly pages once per week and store only compact title lists.
 /// Lists are matched against the subscribed IPTV library before being displayed.
 Future<Map<String,List<String>>> weeklyGenres(MediaKind kind) async {
   if(apiKey.isEmpty)return {};
   final media=kind==MediaKind.movie?'movie':'tv';
   final monday=DateTime.now().toUtc().subtract(Duration(days:DateTime.now().toUtc().weekday-1));
   final week='${monday.year}-${monday.month}-${monday.day}';
   final cacheKey='tmdb.weekly.v2.$media.$week';
   if(_weekly.containsKey(cacheKey))return _weekly[cacheKey]!;
   final prefs=await SharedPreferences.getInstance();
   final saved=prefs.getString(cacheKey);
   if(saved!=null){
     try{
       final raw=jsonDecode(saved) as Map<String,dynamic>;
       final parsed=raw.map((k,v)=>MapEntry(k,(v as List).cast<String>()));
       _weekly[cacheKey]=parsed;return parsed;
     }catch(_){}
   }
   final result=<String,List<String>>{};
   try {
     for(var page=1;page<=3;page++){
       final response=await _client.get(Uri.https('api.themoviedb.org',
         '/3/trending/$media/week',{'api_key':apiKey,'language':'en-US','page':'$page'}))
         .timeout(const Duration(seconds:8));
       if(response.statusCode!=200)break;
       final data=jsonDecode(response.body) as Map<String,dynamic>;
       for(final entry in (data['results'] as List? ?? [])){
         if(entry is! Map||entry['original_language']!='en')continue;
         final title=entry[media=='movie'?'title':'name']?.toString()??'';
         if(title.isEmpty)continue;
         for(final id in entry['genre_ids'] as List? ?? []){
           final name=genres[id];
           if(name==null||name=='TV Movie')continue;
           final titles=result.putIfAbsent(name,()=>[]);
           if(!titles.contains(title)&&titles.length<30)titles.add(title);
         }
       }
     }
     if(result.isNotEmpty){
       await prefs.setString(cacheKey,jsonEncode(result));
       _weekly[cacheKey]=result;
     }
   }catch(_){}
   return result;
 }
 /// Discovery cards are TMDB titles, not falsely advertised IPTV streams.
 /// Results can be displayed even when the user's provider does not carry them.
 Future<List<MediaEntry>> discovery(MediaKind kind,{String window='day',int? genreId}) async {
   if(apiKey.isEmpty)return [];
   final media=kind==MediaKind.movie?'movie':'tv';
   final today=DateTime.now().toUtc();
   final period=genreId==null?'${today.year}-${today.month}-${today.day}':
     '${today.year}-W${(today.difference(DateTime.utc(today.year,1,1)).inDays~/7)}';
   final cacheKey='tmdb.discovery.v4.$media.$window.${genreId??0}.$period';
   final prefs=await SharedPreferences.getInstance();
   final stored=prefs.getString(cacheKey);
   if(stored!=null){
     try{return (jsonDecode(stored) as List).map((v)=>_discoveryEntry(v as Map<String,dynamic>,kind)).toList();}
     catch(_){}
   }
   final all=<Map<String,dynamic>>[];
   try{
     for(var page=1;page<=(genreId==null?2:1);page++){
       final uri=genreId==null
         ?Uri.https('api.themoviedb.org','/3/trending/$media/$window',
             {'api_key':apiKey,'language':'en-US','page':'$page'})
         :Uri.https('api.themoviedb.org','/3/discover/$media',{
             'api_key':apiKey,'language':'en-US','page':'$page',
             'sort_by':'popularity.desc','with_genres':'$genreId',
             'watch_region':'US','with_original_language':'en'});
       final response=await _client.get(uri).timeout(const Duration(seconds:9));
       if(response.statusCode!=200)break;
       final results=(jsonDecode(response.body) as Map)['results'] as List? ?? [];
       for(final value in results){
         if(value is! Map)continue;
         final entry=Map<String,dynamic>.from(value);
         if(entry['original_language']!='en')continue;
         if((entry[media=='movie'?'title':'name']?.toString()??'').isEmpty)continue;
         all.add(entry);
         if(all.length>=40)break;
       }
       if(all.length>=40)break;
     }
     if(all.isNotEmpty)await prefs.setString(cacheKey,jsonEncode(all));
   }catch(_){}
   return all.map((v)=>_discoveryEntry(v,kind)).toList();
 }
 static MediaEntry _discoveryEntry(Map<String,dynamic> v,MediaKind kind){
   final title=(v[kind==MediaKind.movie?'title':'name']??'').toString();
   final poster=(v['poster_path']??'').toString();
   final backdrop=(v['backdrop_path']??'').toString();
   final date=(v[kind==MediaKind.movie?'release_date':'first_air_date']??'').toString();
   return MediaEntry(
     id:'tmdb:${kind.name}:${v['id']}',kind:kind,title:title,
     category:'TMDB discovery',year:date.length>=4?int.tryParse(date.substring(0,4))??0:0,
     artwork:poster.startsWith('/')?'https://image.tmdb.org/t/p/w342$poster':
       backdrop.startsWith('/')?'https://image.tmdb.org/t/p/w780$backdrop':'',
     rating:(v['vote_average'] as num?)?.toDouble()??0);
 }
 Future<MovieMeta> details(MediaEntry item) async {
   if(apiKey.isEmpty||item.kind==MediaKind.live)return const MovieMeta();
   final media=item.kind==MediaKind.movie?'movie':'tv';
   final id='$media:${MediaEntry.normalize(item.cleanTitle)}:${item.year}';
   if(_mem.containsKey(id))return _mem[id]!;
   final prefs=await SharedPreferences.getInstance();
   final key='tmdb.meta.${apiKey.hashCode}.$id';
   final cached=prefs.getString(key);
   if(cached!=null){try{
     final m=MovieMeta.fromJson(jsonDecode(cached) as Map<String,dynamic>);
     _mem[id]=m;return m;
   }catch(_){}}
   try{
     final params={'api_key':apiKey,'query':item.cleanTitle,'language':'en-US'};
     if(item.year>0)params[media=='movie'?'year':'first_air_date_year']=item.year.toString();
     final response=await _client.get(Uri.https('api.themoviedb.org','/3/search/$media',params))
       .timeout(const Duration(seconds:7));
     if(response.statusCode!=200)return const MovieMeta();
     final results=(jsonDecode(response.body) as Map)['results'] as List? ?? [];
     Map? chosen;
     for(final r in results.take(8)){
       if(r is! Map)continue;
       if(MediaEntry.normalize(r[media=='movie'?'title':'name']?.toString()??'')==
           MediaEntry.normalize(item.cleanTitle)){
         chosen=r;break;
       }
     }
     if(chosen==null)return const MovieMeta();
     final path=chosen['backdrop_path']?.toString()??'';
     final tagList=(chosen['genre_ids'] as List? ?? []).map((v)=>genres[v]??'').where((v)=>v.isNotEmpty).take(3);
     final movie=MovieMeta(
       backdrop:path.startsWith('/')?'https://image.tmdb.org/t/p/w1280$path':'',
       overview:chosen['overview']?.toString()??'',
       genres:tagList.join('  •  '),
       rating:(chosen['vote_average'] as num?)?.toDouble()??0,
     );
     _mem[id]=movie;
     await prefs.setString(key,jsonEncode(movie.toJson()));
     return movie;
   }catch(_){return const MovieMeta();}
 }
 void dispose()=>_client.close();
}
