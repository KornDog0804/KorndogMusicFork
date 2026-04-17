package expo.modules.noutubeview

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

class NouJsInterface(private val context: Context, private val view: NouTubeView) {
  private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
  private val TAG = "NouJsInterface"

  @JavascriptInterface
  fun onMessage(payload: String) {
    view.onMessage(mapOf("payload" to payload))
  }

  @JavascriptInterface
  fun notify(title: String, author: String, seconds: Long, thumbnail: String) {
    view.notify(title, author, seconds, thumbnail)
  }

  @JavascriptInterface
  fun notifyProgress(playing: Boolean, pos: Long) {
    view.notifyProgress(playing, pos)
  }

  // ============================================
  // KORNDOG CAST - DLNA Methods
  // ============================================

  @JavascriptInterface
  fun discoverDevices() {
    scope.launch {
      try {
        val devices = view.nouCast.discoverDevices()
        val jsonArray = JSONArray()
        for (device in devices) {
          val obj = JSONObject()
          obj.put("name", device["name"])
          obj.put("location", device["location"])
          jsonArray.put(obj)
        }
        val jsonStr = jsonArray.toString().replace("\\", "\\\\").replace("'", "\\'")
        view.currentActivity?.runOnUiThread {
          view.webView.evaluateJavascript("window.kdShowDevices('$jsonStr')", null)
        }
      } catch (e: Exception) {
        Log.e(TAG, "Device discovery failed: ${e.message}", e)
        view.currentActivity?.runOnUiThread {
          view.webView.evaluateJavascript("window.kdShowDevices('[]')", null)
        }
      }
    }
  }

  @JavascriptInterface
  fun selectAndCast(deviceIndex: Int) {
    scope.launch {
      try {
        val selected = view.nouCast.selectDevice(deviceIndex)
        if (!selected) {
          callCastResult(false, "")
          return@launch
        }

        val deviceInfo = view.nouCast.getSelectedDevice()
        val deviceName = deviceInfo?.get("name") ?: "TV"

        // Get current page URL
        var pageUrl = ""
        withContext(Dispatchers.Main) {
          pageUrl = view.getPageUrl()
        }

        if (pageUrl.isEmpty()) {
          Log.e(TAG, "No page URL available")
          callCastResult(false, deviceName)
          return@launch
        }

        // Extract direct stream URL using yt-dlp
        Log.d(TAG, "Extracting stream URL for: $pageUrl")
        val ytDlp = NouYtDlp(context)
        ytDlp.ensureYoutubeDLInitialized()

        val streamInfo = ytDlp.getStreamUrl(pageUrl)
        val streamUrl = streamInfo["url"] ?: ""
        val title = streamInfo["title"] ?: "NouTube"

        if (streamUrl.isEmpty()) {
          Log.e(TAG, "Failed to extract stream URL")
          callCastResult(false, deviceName)
          return@launch
        }

        Log.d(TAG, "Got stream URL, casting to $deviceName")

        // Cast the direct stream URL to the TV
        val success = view.nouCast.castUrl(streamUrl, title)
        callCastResult(success, deviceName)

      } catch (e: Exception) {
        Log.e(TAG, "Cast failed: ${e.message}", e)
        callCastResult(false, "")
      }
    }
  }

  @JavascriptInterface
  fun pauseCast() {
    scope.launch {
      try {
        view.nouCast.pause()
      } catch (e: Exception) {
        Log.e(TAG, "Pause failed: ${e.message}", e)
      }
    }
  }

  @JavascriptInterface
  fun stopCast() {
    scope.launch {
      try {
        view.nouCast.stop()
      } catch (e: Exception) {
        Log.e(TAG, "Stop failed: ${e.message}", e)
      }
    }
  }

  private fun callCastResult(success: Boolean, deviceName: String) {
    val safeName = deviceName.replace("'", "\\'")
    view.currentActivity?.runOnUiThread {
      view.webView.evaluateJavascript(
        "window.kdCastResult($success, '$safeName')",
        null
      )
    }
  }
}
