import 'package:shared_preferences/shared_preferences.dart';

/// Private on-device crash breadcrumbs. Never sends data over the network.
class CrashDiagnostics {
  static const _stageKey='aurora.diag.stage.v1';
  static const _errorKey='aurora.diag.error.v1';
  static String _lastStage='starting';
  static Future<void> _pending=Future<void>.value();

  static String _redact(String input) {
    var result=input.replaceAll(
      RegExp(r'https?://[^\s\]\)\}<>"]+',caseSensitive:false),
      '[network address hidden]');
    result=result.replaceAll(
      RegExp(r'(username|password|token|api_key)=[^\s&,;]+',caseSensitive:false),
      '[credential hidden]');
    return result.length>5000?result.substring(0,5000):result;
  }

  static void mark(String stage) {
    if(stage==_lastStage)return;
    _lastStage=stage;
    _pending=_pending.then((_)async{
      try{
        final prefs=await SharedPreferences.getInstance();
        await prefs.setString(_stageKey,
          '${DateTime.now().toUtc().toIso8601String()} — ${_redact(stage)}');
      }catch(_){}
    });
  }

  static void record(String kind,Object error,StackTrace? stack) {
    // Persist the first lines of the error and stack, never a full streaming URL.
    final message=_redact('$kind: $error\n${stack??''}');
    _pending=_pending.then((_)async{
      try{
        final prefs=await SharedPreferences.getInstance();
        await prefs.setString(_errorKey,
          '${DateTime.now().toUtc().toIso8601String()}\n$message');
      }catch(_){}
    });
  }

  static Future<String> report() async {
    await _pending;
    try{
      final prefs=await SharedPreferences.getInstance();
      return 'AuroraTV Flutter diagnostics\n'
        'Last action: ${prefs.getString(_stageKey)??_lastStage}\n\n'
        'Last captured Flutter/Dart error:\n'
        '${prefs.getString(_errorKey)??'No Flutter/Dart exception recorded.'}\n\n'
        'Note: a native Android codec crash or an out-of-memory system kill '
        'can terminate the app without creating a Flutter error. '
        'An Android logcat capture is needed for those cases.';
    }catch(e){
      return 'Could not open local diagnostics: ${_redact(e.toString())}';
    }
  }
}
