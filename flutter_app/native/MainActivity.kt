package tv.aurora.aurora_tv

import android.app.PictureInPictureParams
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    // Activity window flags prevent the Fire TV screensaver / display dimming.
    // Keep owners independent: a guide preview must not clear a fullscreen
    // player's keep-awake request when the route changes.
    private val screenAwakeOwners = mutableSetOf<String>()
    private var activityResumed = false

    private fun refreshScreenAwake() {
        if (activityResumed && screenAwakeOwners.isNotEmpty()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        refreshScreenAwake()
    }

    override fun onPause() {
        activityResumed = false
        refreshScreenAwake()
        super.onPause()
    }

    override fun onDestroy() {
        screenAwakeOwners.clear()
        refreshScreenAwake()
        super.onDestroy()
    }
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "aurora.tv/screen_awake")
            .setMethodCallHandler { call, result ->
                if (call.method != "set") {
                    result.notImplemented()
                } else {
                    val owner = call.argument<String>("owner")
                    val enabled = call.argument<Boolean>("enabled")
                    if (owner !in setOf("player", "guide") || enabled == null) {
                        result.error("invalid", "Invalid screen awake request", null)
                    } else {
                        runOnUiThread {
                            if (enabled) screenAwakeOwners.add(owner!!)
                            else screenAwakeOwners.remove(owner!!)
                            refreshScreenAwake()
                            result.success(true)
                        }
                    }
                }
            }
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
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "aurora.tv/external_playback")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "open" -> {
                        val url = call.argument<String>("url") ?: ""
                        if (!url.startsWith("http://") && !url.startsWith("https://")) {
                            result.error("url", "Invalid video URL", null)
                        } else try {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(Uri.parse(url), "video/*")
                                addCategory(Intent.CATEGORY_DEFAULT)
                            }
                            startActivity(intent)
                            result.success(true)
                        } catch (e: ActivityNotFoundException) {
                            result.error("not_installed", "No compatible external player installed", null)
                        } catch (e: Exception) {
                            result.error("failed", "Unable to open external video player", null)
                        }
                    }
                    else -> result.notImplemented()
                }
            }
    }
}
