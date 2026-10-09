import 'dart:io';
import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'catalog.dart';
import 'provider.dart';

/// Shared normalization for XMLTV display names and imported stream titles.
/// Only exact normalized matches are accepted: ESPN2 is never ESPN.
class GuideNames {
 static String canonical(String raw) => raw.toUpperCase()
   .replaceFirst(RegExp(r'^(?:US|USA|CA|CAN|CANADA|UK|NZ|AU|EN|ENG|NA)\s*[-|:]\s*'),'')
   .replaceAll(RegExp(r'\b(?:FHD|UHD|4K|HD|SD|HEVC|H265|1080P|720P|EAST|WEST)\b'),'')
   .replaceAll(RegExp(r'[^A-Z0-9]+'),'')
   .trim();
}

class XmltvService {
 final http.Client _client=http.Client();
 Future<int> refresh(IptvSource source, CatalogDatabase db,{String externalUrl=''}) async {
   final url=externalUrl.trim().isNotEmpty ? Uri.parse(externalUrl.trim()):
       source.kind=='xtream'?Uri.parse('${source.base}/xmltv.php').replace(queryParameters:{
         'username':source.username,'password':source.password}):null;
   if(url==null)throw Exception('Enter an XMLTV URL in settings');
   final response=await _client.get(url).timeout(const Duration(seconds:65));
   if(response.statusCode!=200)throw Exception('EPG HTTP ${response.statusCode}');
   if(response.bodyBytes.length>50000000)throw Exception('EPG file too large for this device');
   final bytes=response.bodyBytes;
   final uncompressed=bytes.length>2&&bytes[0]==0x1f&&bytes[1]==0x8b
       ?gzip.decode(bytes):bytes;
   if(uncompressed.length>90000000)throw Exception('EPG uncompressed file exceeds safety limit');
   final programs=await compute(_parseXmltv,utf8.decode(uncompressed,allowMalformed:true));
   if(programs.isEmpty)throw Exception('No valid current XMLTV programmes found; existing guide retained');
   await db.writePrograms(programs);
   return programs.length;
 }
 void dispose()=>_client.close();
}

List<TvProgramme> _parseXmltv(String raw){
 final result=<TvProgramme>[];
 final aliases=<String,String>{};
 final channelTags=RegExp(r'<channel\s+([^>]+)>([\s\S]*?)</channel>',caseSensitive:false);
 final idPattern=RegExp(r'id="([^"]+)"');
 final displayPattern=RegExp(r'<display-name[^>]*>([\s\S]*?)</display-name>',caseSensitive:false);
 for(final entry in channelTags.allMatches(raw)){
   final id=idPattern.firstMatch(entry.group(1)??'')?.group(1)??'';
   final display=displayPattern.firstMatch(entry.group(2)??'')?.group(1)??'';
   final key=GuideNames.canonical(_decodeEntities(display.replaceAll(RegExp(r'<[^>]+>'),'')));
   if(id.isNotEmpty&&key.length>=2)aliases[id]='name:$key';
 }
 final pattern=RegExp(r'<programme\s+([^>]+)>([\s\S]*?)</programme>',caseSensitive:false);
 final channel=RegExp(r'channel="([^"]+)"');
 final from=RegExp(r'start="([^"]+)"');
 final until=RegExp(r'stop="([^"]+)"');
 final title=RegExp(r'<title[^>]*>([\s\S]*?)</title>',caseSensitive:false);
 final desc=RegExp(r'<desc[^>]*>([\s\S]*?)</desc>',caseSensitive:false);
 final now=DateTime.now();
 for(final match in pattern.allMatches(raw)){
   if(result.length>=180000)break;
   final attrs=match.group(1)??'';
   final code=channel.firstMatch(attrs)?.group(1)??'';
   final a=_date(from.firstMatch(attrs)?.group(1)??'');
   final b=_date(until.firstMatch(attrs)?.group(1)??'');
   if(code.isEmpty||a==null||b==null||!b.isAfter(a)||
       b.isBefore(now.subtract(const Duration(hours:3)))||
       a.isAfter(now.add(const Duration(days:6))))continue;
   final body=match.group(2)??'';
   String clean(String v)=>_decodeEntities(v.replaceAll(RegExp(r'<[^>]+>'),''));
   final programmeTitle=clean(title.firstMatch(body)?.group(1)??'Programme');
   final description=clean(desc.firstMatch(body)?.group(1)??'');
   result.add(TvProgramme(channelId:code,start:a,end:b,title:programmeTitle,description:description));
   final alias=aliases[code];
   if(alias!=null)result.add(TvProgramme(
     channelId:alias,start:a,end:b,title:programmeTitle,description:description));
 }
 return result;
}
String _decodeEntities(String value)=>value
 .replaceAll('&amp;','&').replaceAll('&lt;','<').replaceAll('&gt;','>')
 .replaceAll('&quot;','"').replaceAll('&apos;',"'");
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
