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

  // Google Cast handler
  private val googleCast: NouGoogleCast by lazy {
    NouGoogleCast(context).also { it.init() }
  }

  // Track device type per index in the combined list shown to user
  private val deviceTypeMap = mutableMapOf<Int, String>()  // "googlecast" or "dlna"
  private val castRouteIds  = mutableMapOf<Int, String>()  // routeId for googlecast entries

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
  // KORNDOG CAST — DLNA + Google Cast
  // ============================================

  @JavascriptInterface
  fun discoverDevices() {
    scope.launch {
      try {
        deviceTypeMap.clear()
        castRouteIds.clear()

        val jsonArray = JSONArray()
        var idx = 0

        // 1. Google Cast devices (Chromecast / Google TV)
        val castDevices = googleCast.discoverDevices()
        for (device in castDevices) {
          val obj = JSONObject()
          obj.put("name", "\uD83D\uDCFA ${device["name"]} (Cast)")
          obj.put("location", device["id"] ?: "")
          jsonArray.put(obj)
          deviceTypeMap[idx] = "googlecast"
          castRouteIds[idx] = device["id"] ?: ""
          idx++
        }

        // 2. DLNA devices (Onn, Fire Stick, etc.)
        val dlnaDevices = view.nouCast.discoverDevices()
        for (device in dlnaDevices) {
          val obj = JSONObject()
          obj.put("name", "\uD83D\uDCE1 ${device["name"]} (DLNA)")
          obj.put("location", device["location"] ?: "")
          jsonArray.put(obj)
          deviceTypeMap[idx] = "dlna"
          idx++
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
        val type = deviceTypeMap[deviceIndex] ?: "dlna"
        val deviceName: String

        if (type == "googlecast") {
          val routeId = castRouteIds[deviceIndex] ?: run {
            callCastResult(false, "")
            return@launch
          }
          deviceName = "Google TV"
          castCurrentVideoGoogle(routeId, deviceName)
        } else {
          val selected = view.nouCast.selectDevice(deviceIndex - (castRouteIds.size))
          if (!selected) { callCastResult(false, ""); return@launch }
          deviceName = view.nouCast.getSelectedDevice()?.get("name") ?: "TV"
          castCurrentVideo(deviceName)
        }
      } catch (e: Exception) {
        Log.e(TAG, "Cast failed: ${e.message}", e)
        callCastResult(false, "")
      }
    }
  }

  // Alias methods called by the JS castAdFree / castIpAdFree buttons
  @JavascriptInterface
  fun castAdFree(deviceIndex: Int) = selectAndCast(deviceIndex)

  @JavascriptInterface
  fun castIpAdFree(ip: String) = castToIp(ip)

  @JavascriptInterface
  fun castToIp(ip: String) {
    scope.launch {
      try {
        Log.d(TAG, "Attempting direct DLNA cast to IP: $ip")
        val connected = view.nouCast.connectToIp(ip)
        if (!connected) { callCastResult(false, ip); return@launch }
        castCurrentVideo(ip)
      } catch (e: Exception) {
        Log.e(TAG, "Direct IP cast failed: ${e.message}", e)
        callCastResult(false, ip)
      }
    }
  }

  @JavascriptInterface
  fun pauseCast() {
    scope.launch {
      try {
        if (googleCast.isConnected()) googleCast.pause()
        else view.nouCast.pause()
      } catch (e: Exception) {
        Log.e(TAG, "Pause failed: ${e.message}", e)
      }
    }
  }

  @JavascriptInterface
  fun stopCast() {
    scope.launch {
      try {
        if (googleCast.isConnected()) googleCast.stop()
        else view.nouCast.stop()
      } catch (e: Exception) {
        Log.e(TAG, "Stop failed: ${e.message}", e)
      }
    }
  }

  // ---- Private helpers ----

  private suspend fun castCurrentVideo(deviceName: String) {
    var pageUrl = ""
    withContext(Dispatchers.Main) { pageUrl = view.getPageUrl() }
    if (pageUrl.isEmpty()) { callCastResult(false, deviceName); return }

    val ytDlp = NouYtDlp(context)
    ytDlp.ensureYoutubeDLInitialized()
    val streamInfo = ytDlp.getStreamUrl(pageUrl)
    val streamUrl = streamInfo["url"] ?: ""
    val title = streamInfo["title"] ?: "NouTube"
    if (streamUrl.isEmpty()) { callCastResult(false, deviceName); return }

    val success = view.nouCast.castUrl(streamUrl, title)
    callCastResult(success, deviceName)
  }

  private suspend fun castCurrentVideoGoogle(routeId: String, deviceName: String) {
    var pageUrl = ""
    withContext(Dispatchers.Main) { pageUrl = view.getPageUrl() }
    if (pageUrl.isEmpty()) { callCastResult(false, deviceName); return }

    val ytDlp = NouYtDlp(context)
    ytDlp.ensureYoutubeDLInitialized()
    val streamInfo = ytDlp.getStreamUrl(pageUrl)
    val streamUrl = streamInfo["url"] ?: ""
    val title = streamInfo["title"] ?: "NouTube"
    if (streamUrl.isEmpty()) { callCastResult(false, deviceName); return }

    val success = googleCast.castUrl(routeId, streamUrl, title)
    callCastResult(success, deviceName)
  }

  private fun callCastResult(success: Boolean, deviceName: String) {
    val safeName = deviceName.replace("'", "\\'")
    view.currentActivity?.runOnUiThread {
      view.webView.evaluateJavascript("window.kdCastResult($success, '$safeName')", null)
    }
  }
}
