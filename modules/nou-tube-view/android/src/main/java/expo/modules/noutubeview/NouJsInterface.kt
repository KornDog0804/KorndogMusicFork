package expo.modules.noutubeview

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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

  private val prefs = context.getSharedPreferences("noutube_cast_prefs", Context.MODE_PRIVATE)

  private val googleCast: NouGoogleCast by lazy {
    NouGoogleCast(context).also { it.init() }
  }

  private val devices = mutableListOf<Map<String, String>>()

  private var lastDeviceId: String? = prefs.getString("lastDeviceId", null)
  private var lastDeviceType: String? = prefs.getString("lastDeviceType", null)
  private var lastDeviceName: String? = prefs.getString("lastDeviceName", null)

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
      callStatus("Searching for TVs on your network...")

      try {
        devices.clear()
        val jsonArray = JSONArray()

        val castDevices = googleCast.discoverDevices()
        for (d in castDevices) {
          val device = mapOf(
            "name" to "📺 ${d["name"] ?: "Google Cast"}",
            "type" to "googlecast",
            "id" to (d["id"] ?: "")
          )
          devices.add(device)
        }

        val dlnaDevices = view.nouCast.discoverDevices()
        for (d in dlnaDevices) {
          val rawName = d["name"] ?: "DLNA Device"
          val lowerName = rawName.lowercase()

          val prettyName =
            if (
              lowerName.contains("fire") ||
              lowerName.contains("aft") ||
              lowerName.contains("airscreen")
            ) {
              "📡 $rawName (Fire Stick / AirScreen)"
            } else {
              "📡 $rawName (DLNA)"
            }

          val device = mapOf(
            "name" to prettyName,
            "type" to "dlna",
            "id" to (d["location"] ?: "")
          )
          devices.add(device)
        }

        for (dev in devices) {
          val obj = JSONObject()
          obj.put("name", dev["name"] ?: "Unknown Device")
          obj.put("location", dev["id"] ?: "")
          obj.put("type", dev["type"] ?: "")
          jsonArray.put(obj)
        }

        val jsonStr = escapeJs(jsonArray.toString())

        view.currentActivity?.runOnUiThread {
          view.webView.evaluateJavascript("window.kdShowDevices('$jsonStr')", null)

          if (devices.isEmpty()) {
            callStatus("No TVs found. Fire Stick users: open AirScreen, then scan again or enter the TV IP.")
          } else {
            callStatus("Found ${devices.size} device(s). Pick your TV.")
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Device discovery failed: ${e.message}", e)
        view.currentActivity?.runOnUiThread {
          view.webView.evaluateJavascript("window.kdShowDevices('[]')", null)
          callStatus("Device discovery failed. Make sure your phone and TV are on the same Wi-Fi.")
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
          callStatus("That TV disappeared. Try scanning again.")
          callCastResult(false, "")
          return@launch
        }

        val type = device["type"] ?: ""
        val id = device["id"] ?: ""
        val name = device["name"] ?: "TV"

        callStatus("Connecting to $name...")

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
          saveLastDevice(id, type, name)
          callStatus("Casting to $name")
        } else {
          callStatus("Could not cast to $name. Try again or use manual IP.")
        }

        callCastResult(success, name)
      } catch (e: Exception) {
        Log.e(TAG, "Cast failed: ${e.message}", e)
        callStatus("Cast failed: ${e.message?.take(80) ?: "unknown error"}")
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
        val cleanIp = ip.trim()
        callStatus("Connecting to $cleanIp...")

        val connected = view.nouCast.connectToIp(cleanIp)
        if (!connected) {
          callStatus("No DLNA device found at $cleanIp. Check the TV IP and Wi-Fi.")
          callCastResult(false, cleanIp)
          return@launch
        }

        val success = castCurrentVideo(cleanIp)

        if (success) {
          saveLastDevice(cleanIp, "dlna", cleanIp)
          callStatus("Casting to $cleanIp")
        } else {
          callStatus("Connected, but the video could not be sent to $cleanIp.")
        }

        callCastResult(success, cleanIp)
      } catch (e: Exception) {
        Log.e(TAG, "Direct IP cast failed: ${e.message}", e)
        callStatus("Direct IP cast failed: ${e.message?.take(80) ?: "unknown error"}")
        callCastResult(false, ip)
      }
    }
  }

  @JavascriptInterface
  fun autoCast() {
    scope.launch {
      try {
        val id = lastDeviceId ?: return@launch
        val type = lastDeviceType ?: return@launch
        val name = lastDeviceName ?: "TV"

        callStatus("Auto-casting to $name...")

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
          callStatus("Casting to $name")
          callCastResult(true, name)
        }
      } catch (e: Exception) {
        Log.e(TAG, "Auto-cast failed: ${e.message}", e)
      }
    }
  }

  @JavascriptInterface
  fun reconnectLastDevice() {
    autoCast()
  }

  @JavascriptInterface
  fun getLastDeviceName(): String {
    return lastDeviceName ?: ""
  }

  @JavascriptInterface
  fun pauseCast() {
    scope.launch {
      try {
        if (googleCast.isConnected()) {
          googleCast.pause()
        } else {
          view.nouCast.pause()
        }
        callStatus("Paused cast")
      } catch (e: Exception) {
        Log.e(TAG, "Pause failed: ${e.message}", e)
        callStatus("Pause failed")
      }
    }
  }

  @JavascriptInterface
  fun stopCast() {
    scope.launch {
      try {
        if (googleCast.isConnected()) {
          googleCast.stop()
        } else {
          view.nouCast.stop()
        }
        callStatus("Stopped casting")
      } catch (e: Exception) {
        Log.e(TAG, "Stop failed: ${e.message}", e)
        callStatus("Stop failed")
      }
    }
  }

  @JavascriptInterface
  fun copyCurrentUrl() {
    scope.launch {
      var pageUrl = ""
      withContext(Dispatchers.Main) {
        pageUrl = view.getPageUrl()
      }

      if (pageUrl.isBlank()) {
        callStatus("No video link to copy")
        return@launch
      }

      val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
      clipboard.setPrimaryClip(ClipData.newPlainText("NouTube Link", pageUrl))
      callStatus("Copied current video link")
    }
  }

  @JavascriptInterface
  fun openInYouTubeApp() {
    scope.launch {
      var pageUrl = ""
      withContext(Dispatchers.Main) {
        pageUrl = view.getPageUrl()
      }

      if (pageUrl.isBlank()) {
        callStatus("No YouTube video open")
        return@launch
      }

      try {
        val uri = Uri.parse(pageUrl)
        val intent = Intent(Intent.ACTION_VIEW, uri)
        intent.setPackage("com.google.android.youtube")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
      } catch (e: Exception) {
        try {
          val intent = Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl))
          intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          context.startActivity(intent)
        } catch (_: Exception) {
          callStatus("Could not open YouTube app")
        }
      }
    }
  }

  private suspend fun castCurrentVideo(deviceName: String): Boolean {
    var pageUrl = ""
    withContext(Dispatchers.Main) {
      pageUrl = view.getPageUrl()
    }

    if (pageUrl.isEmpty()) {
      callStatus("Open a YouTube video first")
      return false
    }

    callStatus("Preparing stream for $deviceName...")

    val ytDlp = NouYtDlp(context)
    ytDlp.ensureYoutubeDLInitialized()

    val streamInfo = ytDlp.getStreamUrl(pageUrl)
    val streamUrl = streamInfo["url"] ?: ""
    val title = streamInfo["title"] ?: "NouTube"

    if (streamUrl.isEmpty()) {
      callStatus("Could not prepare this video. Try another one.")
      return false
    }

    val success = view.nouCast.castUrl(streamUrl, title)
    Log.d(TAG, "DLNA cast to $deviceName result: $success")
    return success
  }

  private suspend fun castCurrentVideoGoogle(routeId: String, deviceName: String): Boolean {
    var pageUrl = ""
    withContext(Dispatchers.Main) {
      pageUrl = view.getPageUrl()
    }

    if (pageUrl.isEmpty()) {
      callStatus("Open a YouTube video first")
      return false
    }

    callStatus("Preparing stream for $deviceName...")

    val ytDlp = NouYtDlp(context)
    ytDlp.ensureYoutubeDLInitialized()

    val streamInfo = ytDlp.getStreamUrl(pageUrl)
    val streamUrl = streamInfo["url"] ?: ""
    val title = streamInfo["title"] ?: "NouTube"

    if (streamUrl.isEmpty()) {
      callStatus("Could not prepare this video. Try another one.")
      return false
    }

    val success = googleCast.castUrl(routeId, streamUrl, title)
    Log.d(TAG, "Google Cast to $deviceName result: $success")
    return success
  }

  private fun saveLastDevice(id: String, type: String, name: String) {
    lastDeviceId = id
    lastDeviceType = type
    lastDeviceName = name

    prefs.edit()
      .putString("lastDeviceId", id)
      .putString("lastDeviceType", type)
      .putString("lastDeviceName", name)
      .apply()
  }

  private fun callCastResult(success: Boolean, deviceName: String) {
    val safeName = escapeJs(deviceName)
    view.currentActivity?.runOnUiThread {
      view.webView.evaluateJavascript("window.kdCastResult($success, '$safeName')", null)
    }
  }

  private fun callStatus(message: String) {
    val safeMessage = escapeJs(message)
    view.currentActivity?.runOnUiThread {
      view.webView.evaluateJavascript("window.kdSetStatus && window.kdSetStatus('$safeMessage')", null)
    }
  }

  private fun escapeJs(value: String): String {
    return value
      .replace("\\", "\\\\")
      .replace("'", "\\'")
      .replace("\n", " ")
      .replace("\r", " ")
  }

  private fun extractIpFromLocation(location: String): String {
    return try {
      Uri.parse(location).host ?: location
    } catch (e: Exception) {
      location
    }
  }
}
