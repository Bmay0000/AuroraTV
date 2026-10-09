import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'catalog.dart';
import 'provider.dart';

class XmltvService {
 final http.Client _client=http.Client();
 Future<int> refresh(IptvSource source, CatalogDatabase db,{String externalUrl=''}) async {
   final url=externalUrl.trim().isNotEmpty ? Uri.parse(externalUrl.trim()):
       source.kind=='xtream'?Uri.parse('${source.base}/xmltv.php').replace(queryParameters:{
         'username':source.username,'password':source.password}):null;
   if(url==null)throw Exception('Enter an XMLTV URL in settings');
   final response=await _client.get(url).timeout(const Duration(seconds:50));
   if(response.statusCode!=200)throw Exception('EPG HTTP ${response.statusCode}');
   if(response.bodyBytes.length>25000000)throw Exception('EPG too large for this device');
   final programs=await compute(_parseXmltv,utf8.decode(response.bodyBytes));
   await db.writePrograms(programs);
   return programs.length;
 }
 void dispose()=>_client.close();
}

// This parser limits processing to a bounded number of schedule entries.
// XMLTV channel identifiers are matched against provider IDs where possible;
// unmatched programmes remain harmless. No schedule times are fabricated.
List<TvProgramme> _parseXmltv(String raw){
 final result=<TvProgramme>[];
 final pattern=RegExp(r'<programme\s+([^>]+)>([\s\S]*?)</programme>',caseSensitive:false);
 final channel=RegExp(r'channel="([^"]+)"');
 final from=RegExp(r'start="([^"]+)"');
 final until=RegExp(r'stop="([^"]+)"');
 final title=RegExp(r'<title[^>]*>([\s\S]*?)</title>',caseSensitive:false);
 final desc=RegExp(r'<desc[^>]*>([\s\S]*?)</desc>',caseSensitive:false);
 final now=DateTime.now();
 for(final match in pattern.allMatches(raw)){
   if(result.length>=120000)break;
   final attrs=match.group(1)??'';
   final code=channel.firstMatch(attrs)?.group(1)??'';
   final a=_date(from.firstMatch(attrs)?.group(1)??'');
   final b=_date(until.firstMatch(attrs)?.group(1)??'');
   if(code.isEmpty||a==null||b==null||b.isBefore(now.subtract(const Duration(hours:2)))||
       a.isAfter(now.add(const Duration(days:6))))continue;
   String clean(String v)=>v.replaceAll(RegExp(r'<[^>]+>'),'')
     .replaceAll('&amp;','&').replaceAll('&lt;','<').replaceAll('&gt;','>');
   final body=match.group(2)??'';
   result.add(TvProgramme(channelId:code,start:a,end:b,
     title:clean(title.firstMatch(body)?.group(1)??'Programme'),
     description:clean(desc.firstMatch(body)?.group(1)??'')));
 }
 return result;
}
DateTime? _date(String s){
 if(s.length<14)return null;
 try{
   final y=int.parse(s.substring(0,4)),m=int.parse(s.substring(4,6)),
     d=int.parse(s.substring(6,8)),h=int.parse(s.substring(8,10)),min=int.parse(s.substring(10,12));
   final offset=RegExp(r'([+-])(\d{2})(\d{2})').firstMatch(s);
   if(offset==null)return DateTime(y,m,d,h,min);
   final minutes=int.parse(offset.group(2)!)*60+int.parse(offset.group(3)!);
   final signed=offset.group(1)=='+'?minutes:-minutes;
   return DateTime.utc(y,m,d,h,min).subtract(Duration(minutes:signed)).toLocal();
 }catch(_){return null;}
}
