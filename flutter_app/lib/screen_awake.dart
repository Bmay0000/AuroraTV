import 'package:flutter/services.dart';

/// Prevents Android TV display idle while a video is actively in use.
/// Requests from the guide preview and fullscreen playback are tracked
/// separately by the native Activity.
class ScreenAwake {
  static const MethodChannel _channel=MethodChannel('aurora.tv/screen_awake');

  static Future<void> set(String owner,bool enabled) async {
    try{
      await _channel.invokeMethod<bool>('set',{
        'owner':owner,
        'enabled':enabled,
      });
    }on MissingPluginException{
      // Non-Android hosts and Flutter widget tests need no TV wake lock.
    }on PlatformException{
      // A wake policy error should never interrupt video playback.
    }
  }
}
