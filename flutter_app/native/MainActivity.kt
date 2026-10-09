package tv.aurora.aurora_tv

import android.app.PictureInPictureParams
import android.os.Build
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "aurora.tv/picture_in_picture")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "enter" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                            packageManager.hasSystemFeature("android.software.picture_in_picture")) {
                            try {
                                val parameters = PictureInPictureParams.Builder().build()
                                result.success(enterPictureInPictureMode(parameters))
                            } catch (e: Exception) {
                                result.error("pip", "Picture-in-picture cannot start", null)
                            }
                        } else result.error("unsupported", "Picture-in-picture unavailable", null)
                    }
                    else -> result.notImplemented()
                }
            }
    }
}
