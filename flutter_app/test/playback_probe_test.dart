import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:aurora_tv/playback_probe.dart';

void main(){
 test('provider video accepts partial-range transport',()async{
   final client=MockClient((request)async{
     expect(request.headers['Range'],'bytes=0-4095');
     expect(request.headers['User-Agent'],contains('VLC'));
     return http.Response('video bytes',206,headers:{
       'content-type':'video/x-matroska',
     });
   });
   addTearDown(client.close);
   final value=await PlaybackProbe.inspect(
      'http://example.invalid/movie/example.mkv',client:client);
   expect(value.plausibleVideo,isTrue);
   expect(value.status,206);
   expect(value.summary,contains('HTTP 206'));
   expect(value.summary,isNot(contains('example.invalid')));
 });
 test('provider JSON login error is not a playable video',()async{
   final client=MockClient((request)async=>http.Response(
     '{"user_info":{"auth":0}}',200,headers:{'content-type':'application/json'}));
   addTearDown(client.close);
   final value=await PlaybackProbe.inspect('https://example.invalid/a.ts',client:client);
   expect(value.plausibleVideo,isFalse);
   expect(value.summary,contains('nonvideo'));
 });
 test('does not disclose provider credential in failure report',()async{
   final client=MockClient((_)async=>http.Response('',403));
   addTearDown(client.close);
   final value=await PlaybackProbe.inspect('http://host.invalid/movie/PRIVATEUSER/SECRETPASS/4.mp4',client:client);
   expect(value.summary,contains('HTTP 403'));
   expect(value.summary,isNot(contains('PRIVATEUSER')));
   expect(value.summary,isNot(contains('SECRETPASS')));
 });
}
