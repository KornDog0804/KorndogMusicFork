package expo.modules.noutubeview

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.JavascriptInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class NouJsInterface(private val context: Context, private val view: NouTubeView) {
  private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
  private val TAG = "NouJsInterface"

  private val googleCast: NouGoogleCast by lazy {
    NouGoogleCast(context).also { it.init() }
  }

  // UI list only. Do not use NouCast's CastDevice class here.
  private val devices = mutableListOf<Map<String, String>>()
  private var lastDeviceId: String? = null

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

  @JavascriptInterface
  fun discoverDevices() {
    scope.launch {
      try {
        devices.clear()
        val jsonArray = JSONArray()

        // Google Cast first
        val castDevices = googleCast.discoverDevices()
        for (d in castDevices) {
          val device = mapOf(
            "name" to "📺 ${d["name"] ?: "Google Cast"} (Best)",
            "type" to "googlecast",
            "id" to (d["id"] ?: "")
          )
          devices.add(device)
        }

        // DLNA second
        val dlnaDevices = view.nouCast.discoverDevices()
        for (d in dlnaDevices) {
          val device = mapOf(
            "name" to "📡 ${d["name"] ?: "DLNA Device"} (Limited)",
            "type" to "dlna",
            "id" to (d["location"] ?: "")
          )
          devices.add(device)
        }

        for (dev in devices) {
          val obj = JSONObject()
          obj.put("name", dev["name"] ?: "Unknown Device")
          obj.put("location", dev["id"] ?: "")
          jsonArray.put(obj)
        }

        val jsonStr = jsonArray.toString()
          .replace("\\", "\\\\")
          .replace("'", "\\'")
          .replace("\n", "")

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
  fun selectAndCast(index: Int) {
    scope.launch {
      try {
        val device = devices.getOrNull(index)
        if (device == null) {
          callCastResult(false, "")
          return@launch
        }

        val type = device["type"] ?: ""
        val id = device["id"] ?: ""
        val name = device["name"] ?: "TV"

        val success = when (type) {
          "googlecast" -> castCurrentVideoGoogle(id, name)
          "dlna" -> {
            val ip = extractIpFromLocation(id)
            val connected = view.nouCast.connectToIp(ip)
            if (!connected) false else castCurrentVideo(name)
          }
          else -> false
        }

        if (success) {
          lastDeviceId = id
        }

        callCastResult(success, name)
      } catch (e: Exception) {
        Log.e(TAG, "Cast failed: ${e.message}", e)
        callCastResult(false, "")
      }
    }
  }

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
        if (!connected) {
          callCastResult(false, ip)
          return@launch
        }
        val success = castCurrentVideo(ip)
        callCastResult(success, ip)
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

  private suspend fun castCurrentVideo(deviceName: String): Boolean {
    var pageUrl = ""
    withContext(Dispatchers.Main) { pageUrl = view.getPageUrl() }
    if (pageUrl.isEmpty()) {
      return false
    }

    val ytDlp = NouYtDlp(context)
    ytDlp.ensureYoutubeDLInitialized()

    val streamInfo = ytDlp.getStreamUrl(pageUrl)
    val streamUrl = streamInfo["url"] ?: ""
    val title = streamInfo["title"] ?: "NouTube"

    if (streamUrl.isEmpty()) {
      return false
    }

    val success = view.nouCast.castUrl(streamUrl, title)
    Log.d(TAG, "DLNA cast to $deviceName result: $success")
    return success
  }

  private suspend fun castCurrentVideoGoogle(routeId: String, deviceName: String): Boolean {
    var pageUrl = ""
    withContext(Dispatchers.Main) { pageUrl = view.getPageUrl() }
    if (pageUrl.isEmpty()) {
      return false
    }

    val ytDlp = NouYtDlp(context)
    ytDlp.ensureYoutubeDLInitialized()

    val streamInfo = ytDlp.getStreamUrl(pageUrl)
    val streamUrl = streamInfo["url"] ?: ""
    val title = streamInfo["title"] ?: "NouTube"

    if (streamUrl.isEmpty()) {
      return false
    }

    val success = googleCast.castUrl(routeId, streamUrl, title)
    Log.d(TAG, "Google Cast to $deviceName result: $success")
    return success
  }

  private fun callCastResult(success: Boolean, deviceName: String) {
    val safeName = deviceName.replace("\\", "\\\\").replace("'", "\\'")
    view.currentActivity?.runOnUiThread {
      view.webView.evaluateJavascript("window.kdCastResult($success, '$safeName')", null)
    }
  }

  private fun extractIpFromLocation(location: String): String {
    return try {
      Uri.parse(location).host ?: location
    } catch (e: Exception) {
      location
    }
  }
}
