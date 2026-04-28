package expo.modules.noutubeview

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NouJsInterface(private val context: Context, private val view: NouTubeView) {
  private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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

  /**
   * Premium-wrapper mode:
   * We do NOT extract streams anymore.
   * We let YouTube / YouTube Music handle playback and casting.
   */
  @JavascriptInterface
  fun discoverDevices() {
    openNativeYouTubeCastOrApp()
  }

  @JavascriptInterface
  fun selectAndCast(index: Int) {
    openNativeYouTubeCastOrApp()
  }

  @JavascriptInterface
  fun castAdFree(deviceIndex: Int) {
    openNativeYouTubeCastOrApp()
  }

  @JavascriptInterface
  fun castIpAdFree(ip: String) {
    callStatus("Manual IP/DLNA casting is disabled in Premium mode.")
  }

  @JavascriptInterface
  fun castToIp(ip: String) {
    callStatus("Manual IP/DLNA casting is disabled in Premium mode.")
  }

  @JavascriptInterface
  fun autoCast() {
    // Disabled on purpose. Auto-cast polling can pause or interrupt YouTube Music.
  }

  @JavascriptInterface
  fun reconnectLastDevice() {
    openNativeYouTubeCastOrApp()
  }

  @JavascriptInterface
  fun getLastDeviceName(): String {
    return ""
  }

  @JavascriptInterface
  fun pauseCast() {
    callStatus("Use YouTube Music playback controls.")
  }

  @JavascriptInterface
  fun stopCast() {
    callStatus("Use YouTube Music playback controls.")
  }

  @JavascriptInterface
  fun copyCurrentUrl() {
    scope.launch {
      val pageUrl = getPageUrlOnMain()

      if (pageUrl.isBlank()) {
        callStatus("No link to copy.")
        return@launch
      }

      val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
      clipboard.setPrimaryClip(ClipData.newPlainText("NouTube Link", pageUrl))
      callStatus("Copied current link.")
    }
  }

  @JavascriptInterface
  fun openInYouTubeApp() {
    openNativeYouTubeCastOrApp()
  }

  private fun openNativeYouTubeCastOrApp() {
    scope.launch {
      val pageUrl = getPageUrlOnMain()

      if (pageUrl.isBlank()) {
        callStatus("Open a YouTube Music song first.")
        return@launch
      }

      try {
        val uri = Uri.parse(pageUrl)

        val youtubeMusicIntent = Intent(Intent.ACTION_VIEW, uri).apply {
          setPackage("com.google.android.apps.youtube.music")
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(youtubeMusicIntent)
        callStatus("Opened in YouTube Music. Use its native cast button.")
        return@launch
      } catch (_: Exception) {
        // Fall through to YouTube app/browser.
      }

      try {
        val uri = Uri.parse(pageUrl)

        val youtubeIntent = Intent(Intent.ACTION_VIEW, uri).apply {
          setPackage("com.google.android.youtube")
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(youtubeIntent)
        callStatus("Opened in YouTube. Use its native cast button.")
        return@launch
      } catch (_: Exception) {
        // Fall through to generic browser.
      }

      try {
        val uri = Uri.parse(pageUrl)

        val fallbackIntent = Intent(Intent.ACTION_VIEW, uri).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(fallbackIntent)
        callStatus("Opened outside NouTube.")
      } catch (_: Exception) {
        callStatus("Could not open YouTube Music.")
      }
    }
  }

  private suspend fun getPageUrlOnMain(): String {
    var pageUrl = ""
    withContext(Dispatchers.Main) {
      pageUrl = view.getPageUrl()
    }
    return pageUrl
  }

  private fun callStatus(message: String) {
    val safeMessage = escapeJs(message)
    view.currentActivity?.runOnUiThread {
      view.webView.evaluateJavascript(
        "window.kdSetStatus && window.kdSetStatus('$safeMessage')",
        null
      )
    }
  }

  private fun escapeJs(value: String): String {
    return value
      .replace("\\", "\\\\")
      .replace("'", "\\'")
      .replace("\n", " ")
      .replace("\r", " ")
  }
}
