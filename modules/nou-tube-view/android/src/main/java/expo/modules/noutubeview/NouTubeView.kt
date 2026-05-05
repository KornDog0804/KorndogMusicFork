package expo.modules.noutubeview

import android.app.Activity
import android.content.*
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.AttributeSet
import android.view.*
import android.webkit.*
import android.widget.FrameLayout
import androidx.core.view.*
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class NouWebView @JvmOverloads constructor(c: Context, a: AttributeSet? = null, d: Int = 0) : WebView(c, a, d) {
  override fun onWindowVisibilityChanged(v: Int) { super.onWindowVisibilityChanged(VISIBLE) }

  init {
    settings.run {
      javaScriptEnabled = true
      domStorageEnabled = true
      mediaPlaybackRequiresUserGesture = false
      supportZoom()
      builtInZoomControls = true
      displayZoomControls = false
      cacheMode = WebSettings.LOAD_DEFAULT
      loadsImagesAutomatically = true
      blockNetworkImage = false
      blockNetworkLoads = false
      allowFileAccess = true
      allowContentAccess = true
    }
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
    isFocusable = true
    isFocusableInTouchMode = true
  }

  suspend fun eval(s: String): String? = suspendCancellableCoroutine { c ->
    evaluateJavascript(s) { r ->
      c.resume(if (r == "null") null else r.removeSurrounding("\""))
    }
  }
}

class NouOrientationListener(c: Context, private val v: NouTubeView) : OrientationEventListener(c) {
  override fun onOrientationChanged(o: Int) { v.onOrientationChanged(o) }
}

class NouTubeView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {

  private val onLoad by EventDispatcher()
  internal val onMessage by EventDispatcher()

  private var scriptOnStart = ""
  private var pageUrl = ""

  private var customView: View? = null
  private lateinit var orientationListener: NouOrientationListener

  internal val nouCast = NouCast(context)

  private val gestureDetector = GestureDetector(context,
    object : GestureDetector.SimpleOnGestureListener() {
      override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy0: Float): Boolean {
        var dy = dy0
        if (e1 != null) dy = (e2.y - e1.y) / context.resources.displayMetrics.density
        emit("scroll", mapOf("dy" to dy, "y" to webView.scrollY))
        return false
      }
    })

  private var service: NouService? = null
  private var powerManager: PowerManager? = null
  private var wakeLock: PowerManager.WakeLock? = null

  internal val currentActivity: Activity?
    get() = appContext.activityProvider?.currentActivity

  internal val webView: NouWebView = NouWebView(context).apply {

    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

    setOnTouchListener { _, e ->
      gestureDetector.onTouchEvent(e)
      false
    }

    webViewClient = object : WebViewClient() {

      override fun doUpdateVisitedHistory(v: WebView, u: String, isReload: Boolean) {
        if (pageUrl != u) {
          pageUrl = u
          onLoad(mapOf("url" to pageUrl))
        }
      }

      override fun onPageStarted(v: WebView, u: String, f: Bitmap?) {
        evaluateJavascript(scriptOnStart, null)
      }

      override fun onPageFinished(v: WebView, u: String) {
        evaluateJavascript(KORNDOG_CAST_SCRIPT, null)
        evaluateJavascript(KORNDOG_QUEUE_TRACKER_SCRIPT, null)
        evaluateJavascript(KORNDOG_SYNCED_LYRICS_SCRIPT, null)
      }

      override fun shouldOverrideUrlLoading(v: WebView, u: String): Boolean {
        val uri = Uri.parse(u)
        return if (
          uri.host?.contains("youtube.com") == true ||
          uri.host?.contains("google.com") == true
        ) false
        else {
          v.context.startActivity(Intent(Intent.ACTION_VIEW, uri))
          true
        }
      }
    }

    webChromeClient = object : WebChromeClient() {}
  }

  init {
    addView(webView)
    initService()
    initWakeLock()

    webView.addJavascriptInterface(NouJsInterface(context, this), "NouTubeI")
  }

  private fun initWakeLock() {
    powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NouTube::Wake")
  }

  fun initService() {
    val a = currentActivity ?: return
    val conn = object : ServiceConnection {
      override fun onServiceConnected(n: ComponentName, b: IBinder) {
        val nb = b as NouService.NouBinder
        service = nb.getService()
        service?.initialize(webView, a)
      }
      override fun onServiceDisconnected(n: ComponentName) {}
    }

    val i = Intent(a, NouService::class.java)
    a.bindService(i, conn, Context.BIND_AUTO_CREATE)
    orientationListener = NouOrientationListener(a, this)
  }

  fun emit(t: String, d: Any) {
    onMessage(mapOf("payload" to mapOf("type" to t, "data" to d)))
  }

  fun onOrientationChanged(o: Int) {}

  fun getPageUrl(): String = pageUrl
}

/* ========================= */
/* 🔥 KORNDOG DUAL BUTTONS 🔥 */
/* ========================= */

private val KORNDOG_CAST_SCRIPT = """
(function () {
  if (window.__korndogDualInstalled) return;
  window.__korndogDualInstalled = true;

  function getTrack() {
    try {
      const q = JSON.parse(localStorage.getItem("korndog_queue") || "[]");
      if (q.length) return q[q.length - 1];
    } catch (e) {}

    return {
      title: document.querySelector(".title")?.innerText || "",
      artist: document.querySelector(".subtitle")?.innerText || "",
      thumb: document.querySelector("img")?.src || ""
    };
  }

  function go(type) {
    const t = getTrack();
    const base = type === "stream"
      ? "https://korndogrecords.com/korndog-streaming-generator.html"
      : "https://korndogrecords.com/korndog-spinning-generator.html";

    const url = base +
      "?title=" + encodeURIComponent(t.title) +
      "&artist=" + encodeURIComponent(t.artist) +
      "&thumb=" + encodeURIComponent(t.thumb);

    window.location.href = url;
  }

  function btn(emoji, bottom, color, type) {
    const b = document.createElement("button");
    b.innerText = emoji;
    b.style.cssText = `
      position:fixed;
      right:20px;
      bottom:${bottom}px;
      width:56px;
      height:56px;
      border-radius:50%;
      border:2px solid ${color};
      background:#12001c;
      color:white;
      font-size:24px;
      z-index:999999;
      box-shadow:0 0 15px ${color};
    `;
    b.onclick = () => go(type);
    document.body.appendChild(b);
  }

  btn("📺", 90, "#39ff14", "vinyl");
  btn("🎧", 160, "#b000ff", "stream");

})();
""".trimIndent()
