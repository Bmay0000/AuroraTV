import 'package:http/http.dart' as http;

/// Diagnose HTTP transport without downloading the movie.
/// The result NEVER includes provider URLs or credentials.
class PlaybackProbeResult {
 final int? status;
 final String contentType,issue;
 const PlaybackProbeResult({this.status,this.contentType='',this.issue=''});
 bool get accessible=>status==200||status==206;
 bool get plausibleVideo=>accessible &&
     !contentType.contains('text/html') &&
     !contentType.contains('application/json') &&
     !contentType.contains('text/plain');
 String get summary {
   if(status==null)return 'Stream probe: $issue';
   final hint=plausibleVideo?'video response received':
     accessible?'server returned nonvideo content':'server rejected stream';
   return 'HTTP $status; type ${contentType.isEmpty?'unknown':contentType}; $hint';
 }
}

class PlaybackProbe {
 static const userAgent='VLC/3.0.20 LibVLC/3.0.20';
 static const headers=<String,String>{
   'User-Agent':userAgent,
   'Accept':'*/*',
   'Range':'bytes=0-4095',
 };
 /// Optional client enables deterministic tests without real network calls.
 static Future<PlaybackProbeResult> inspect(String url,{http.Client? client})async {
   final uri=Uri.tryParse(url);
   if(uri==null||!['http','https'].contains(uri.scheme)) {
     return const PlaybackProbeResult(issue:'invalid HTTP stream URI');
   }
   final owned=client==null;
   final connection=client??http.Client();
   try{
     final request=http.Request('GET',uri)
       ..headers.addAll(headers)
       ..followRedirects=true
       ..maxRedirects=5;
     final response=await connection.send(request)
       .timeout(const Duration(seconds:9));
     // Consume just one chunk; servers ignoring Range must not cause us
     // to buffer gigabytes of a movie.
     try{await response.stream.take(1).drain<void>()
       .timeout(const Duration(seconds:2));}catch(_){}
     return PlaybackProbeResult(
       status:response.statusCode,
       contentType:(response.headers['content-type']??'')
         .split(';').first.toLowerCase().trim());
   }catch(_){
     return const PlaybackProbeResult(issue:'connection timed out or failed');
   }finally{
     if(owned)connection.close();
   }
 }
}
