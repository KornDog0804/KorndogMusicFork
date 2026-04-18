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

  private val devices = mutableListOf<CastDevice>()
  private var lastDeviceId: String? = null

  private var _googleCast: NouGoogleCast? = null
  private fun getGoogleCast(): NouGoogleCast? {
    if (_googleCast == null) {
      val activity = view.currentActivity ?: return null
      _googleCast = NouGoogleCast(activity).also { it.init() }
    }
    return _googleCast
  }

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
  // KORNDOG CAST — Unified Device System
  // ============================================

  @JavascriptInterface
  fun discoverDevices() {
    scope.launch {
      devices.clear()
      val jsonArray = JSONArray()

      // 1. Google Cast FIRST (best quality, ad-free)
      try {
        val gc = getGoogleCast()
        if (gc != null) {
          val castDevices = gc.discoverDevices()
          for (d in castDevices) {
            devices.add(
              CastDevice(
                name = "\uD83D\uDCFA ${d["name"]} (Best)",
                type = "googlecast",
                id = d["id"] ?: "",
                priority = 1
              )
            )
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Cast discovery failed: ${e.message}")
      }

      // 2. DLNA — filtered to real renderers only
      try {
        val dlnaDevices = view.nouCast.discoverDevices()
        for (d in dlnaDevices) {
          val loc = d["location"] ?: ""
          if (loc.contains("MediaRenderer") || loc.contains("dmr") || loc.contains("AVTransport")) {
            devices.add(
              CastDevice(
                name = "\uD83D\uDCE1 ${d["name"]} (Limited)",
                type = "dlna",
                id = loc,
                priority = 2
              )
            )
          }
        }
        // Fallback: if no filtered DLNA found, show all DLNA
        if (devices.none { it.type == "dlna" } && dlnaDevices.isNotEmpty()) {
          for (d in dlnaDevices) {
            devices.add(
              CastDevice(
                name = "\uD83D\uDCE1 ${d["name"]} (DLNA)",
                type = "dlna",
                id = d["location"] ?: "",
                priority = 2
              )
            )
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "DLNA discovery failed: ${e.message}")
      }

      val sorted = devices.sortedBy { it.priority }
      devices.clear()
      devices.addAll(sorted)

      for (dev in sorted) {
        val obj = JSONObject()
        obj.put("name", dev.name)
        obj.put("location", dev.id)
        jsonArray.put(obj)
      }

      val jsonStr = jsonArray.toString().replace("\\", "\\\\").replace("'", "\\'")
      view.currentActivity?.runOnUiThread {
        view.webView.evaluateJavascript("window.kdShowDevices('$jsonStr')", null)
      }
    }
  }

  @JavascriptInterface
  fun selectAndCast(index: Int) {
    scope.launch {
      val device = devices.getOrNull(index) ?: return@launch

      // Show extracting status
      postStatus("Extracting ad-free stream\u2026")

      var success = when (device.type) {
        "googlecast" -> castCurrentVideoGoogle(device.id, device.name)
        "dlna" -> {
          if (device.type == "dlna") {
            postStatus("Limited support. Install AirScreen on Fire Stick for best results.")
          }
          // Extract IP from location URL
          val ip = extractIp(device.id)
          if (ip != null) {
            val ok = view.nouCast.connectToIp(ip)
            if (ok) castCurrentVideo(device.name) else false
          } else false
        }
        else -> false
      }

      // Fallback to Google Cast if DLNA failed
      if (!success) {
        val fallback = devices.find { it.type == "googlecast" }
        if (fallback != null) {
          postStatus("DLNA failed, trying Cast\u2026")
          val retry = castCurrentVideoGoogle(fallback.id, fallback.name)
          if (retry) {
            lastDeviceId = fallback.id
            success = true
            callCastResult(true, fallback.name)
            return@launch
          }
        }
      }

      if (success) lastDeviceId = device.id
      callCastResult(success, device.name)
    }
  }

  @JavascriptInterface
  fun castAdFree(deviceIndex: Int) = selectAndCast(deviceIndex)

  @JavascriptInterface
  fun castIpAdFree(ip: String) = castToIp(ip)

  @JavascriptInterface
  fun castToIp(ip: String) {
    scope.launch {
      postStatus("Connecting to $ip\u2026")
      val connected = view.nouCast.connectToIp(ip)
      if (!connected) { callCastResult(false, ip); return@launch }
      val success = castCurrentVideo(ip)
      if (success) lastDeviceId = ip
      callCastResult(success, ip)
    }
  }

  @JavascriptInterface
  fun autoCast() {
    scope.launch {
      val last = lastDeviceId ?: return@launch
      val device = devices.find { it.id == last } ?: return@launch
      postStatus("Auto-casting to ${device.name.take(20)}\u2026")
      when (device.type) {
        "googlecast" -> castCurrentVideoGoogle(device.id, device.name)
        "dlna" -> {
          val ip = extractIp(device.id) ?: device.id
          val ok = view.nouCast.connectToIp(ip)
          if (ok) castCurrentVideo(device.name)
        }
      }
    }
  }

  @JavascriptInterface
  fun pauseCast() {
    scope.launch {
      try {
        val gc = getGoogleCast()
        if (gc?.isConnected() == true) gc.pause() else view.nouCast.pause()
      } catch (e: Exception) { Log.e(TAG, "Pause failed: ${e.message}") }
    }
  }

  @JavascriptInterface
  fun stopCast() {
    scope.launch {
      try {
        val gc = getGoogleCast()
        if (gc?.isConnected() == true) gc.stop() else view.nouCast.stop()
        _googleCast = null
        lastDeviceId = null
      } catch (e: Exception) { Log.e(TAG, "Stop failed: ${e.message}") }
    }
  }

  // ---- Private helpers ----

  private suspend fun castCurrentVideo(deviceName: String): Boolean {
    var pageUrl = ""
    withContext(Dispatchers.Main) { pageUrl = view.getPageUrl() }
    if (pageUrl.isEmpty()) return false
    return try {
      val ytDlp = NouYtDlp(context)
      ytDlp.ensureYoutubeDLInitialized()
      val info = ytDlp.getStreamUrl(pageUrl)
      val streamUrl = info["url"] ?: return false
      val title = info["title"] ?: "NouTube"
      if (streamUrl.isEmpty()) return false
      view.nouCast.castUrl(streamUrl, title)
    } catch (e: Exception) {
      Log.e(TAG, "DLNA cast failed: ${e.message}")
      false
    }
  }

  private suspend fun castCurrentVideoGoogle(routeId: String, deviceName: String): Boolean {
    var pageUrl = ""
    withContext(Dispatchers.Main) { pageUrl = view.getPageUrl() }
    if (pageUrl.isEmpty()) return false
    return try {
      val ytDlp = NouYtDlp(context)
      ytDlp.ensureYoutubeDLInitialized()
      val info = ytDlp.getStreamUrl(pageUrl)
      val streamUrl = info["url"] ?: return false
      val title = info["title"] ?: "NouTube"
      if (streamUrl.isEmpty()) return false
      val gc = getGoogleCast() ?: return false
      gc.castUrl(routeId, streamUrl, title)
    } catch (e: Exception) {
      Log.e(TAG, "Google Cast failed: ${e.message}")
      false
    }
  }

  private fun extractIp(locationUrl: String): String? {
    return try {
      val url = java.net.URL(locationUrl)
      url.host
    } catch (e: Exception) {
      // If it's already an IP just return it
      if (locationUrl.matches(Regex("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"))) locationUrl
      else null
    }
  }

  private fun postStatus(msg: String) {
    val escaped = msg.replace("'", "\\'")
    view.currentActivity?.runOnUiThread {
      view.webView.evaluateJavascript("window.kdSetStatus && window.kdSetStatus('$escaped')", null)
    }
  }

  private fun callCastResult(success: Boolean, deviceName: String) {
    val safeName = deviceName.replace("'", "\\'")
    view.currentActivity?.runOnUiThread {
      view.webView.evaluateJavascript("window.kdCastResult($success, '$safeName')", null)
    }
  }
}
