package expo.modules.noutubeview

import android.app.Activity
import android.content.*
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
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

class NouWebView @JvmOverloads constructor(
  c: Context,
  a: AttributeSet? = null,
  d: Int = 0
) : WebView(c, a, d) {
  override fun onWindowVisibilityChanged(v: Int) {
    super.onWindowVisibilityChanged(VISIBLE)
  }

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
    setFocusable(true)
    setFocusableInTouchMode(true)
  }

  suspend fun eval(s: String): String? = suspendCancellableCoroutine { c ->
    evaluateJavascript(s) { r ->
      c.resume(if (r == "null") null else r.removeSurrounding("\""))
    }
  }
}

class NouOrientationListener(
  c: Context,
  private val v: NouTubeView
) : OrientationEventListener(c) {
  override fun onOrientationChanged(o: Int) {
    v.onOrientationChanged(o)
  }
}

class NouTubeView(
  context: Context,
  appContext: AppContext
) : ExpoView(context, appContext) {

  private val onLoad by EventDispatcher()
  internal val onMessage by EventDispatcher()

  private var scriptOnStart = ""
  private var pageUrl = ""
  private var customView: View? = null
  private lateinit var orientationListener: NouOrientationListener

  internal val nouCast = NouCast(context)

  private var service: NouService? = null
  private var powerManager: PowerManager? = null
  private var wakeLock: PowerManager.WakeLock? = null

  internal val currentActivity: Activity?
    get() = appContext.activityProvider?.currentActivity

  private val gestureDetector = GestureDetector(
    context,
    object : GestureDetector.SimpleOnGestureListener() {
      override fun onScroll(
        e1: MotionEvent?,
        e2: MotionEvent,
        dx: Float,
        dy0: Float
      ): Boolean {
        var dy = dy0
        if (e1 != null) {
          dy = (e2.y - e1.y) / context.resources.displayMetrics.density
        }
        emit("scroll", mapOf("dy" to dy, "y" to webView.scrollY))
        return false
      }
    }
  )

  internal val webView: NouWebView = NouWebView(context).apply {
    layoutParams = LayoutParams(
      LayoutParams.MATCH_PARENT,
      LayoutParams.MATCH_PARENT
    )

    setOnTouchListener { _, e ->
      gestureDetector.onTouchEvent(e)
      false
    }

    webViewClient = object : WebViewClient() {
      override fun doUpdateVisitedHistory(
        v: WebView,
        u: String,
        isReload: Boolean
      ) {
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

      override fun shouldInterceptRequest(
        v: WebView,
        r: WebResourceRequest
      ): WebResourceResponse? = null

      override fun shouldOverrideUrlLoading(v: WebView, u: String): Boolean {
        val uri = android.net.Uri.parse(u)

        return if (
          uri.host in VIEW_HOSTS ||
          uri.host?.startsWith("accounts.google.") == true ||
          uri.host?.startsWith("gds.google.") == true ||
          uri.host?.endsWith(".youtube.com") == true
        ) {
          false
        } else {
          v.context.startActivity(Intent(Intent.ACTION_VIEW, uri))
          true
        }
      }
    }

    webChromeClient = object : WebChromeClient() {
      override fun onPermissionRequest(r: PermissionRequest) {
        val a = currentActivity

        if (a == null) {
          r.deny()
          return
        }

        val ps = mutableListOf<String>()

        if (r.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
          ps.add(android.Manifest.permission.RECORD_AUDIO)
        }

        if (r.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
          ps.add(android.Manifest.permission.CAMERA)
        }

        if (ps.isEmpty()) {
          r.grant(r.resources)
          return
        }

        a.requestPermissions(ps.toTypedArray(), 101)
        r.grant(r.resources)
      }

      override fun onJsBeforeUnload(
        v: WebView,
        u: String,
        m: String,
        res: JsResult
      ): Boolean {
        res.confirm()
        return true
      }

      override fun onShowCustomView(v: View, cb: CustomViewCallback) {
        customView = v
        v.keepScreenOn = true

        val a = currentActivity ?: return
        val w = a.window

        (w.decorView as FrameLayout).addView(
          v,
          FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
          )
        )

        a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        val c = WindowCompat.getInsetsController(w, w.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior =
          WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        if (
          Settings.System.getInt(
            a.contentResolver,
            Settings.System.ACCELEROMETER_ROTATION,
            0
          ) == 1
        ) {
          orientationListener.enable()
        }
      }

      override fun onHideCustomView() {
        val a = currentActivity ?: return
        val w = a.window

        (w.decorView as FrameLayout).removeView(customView)
        customView?.keepScreenOn = false
        customView = null

        a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER

        val c = WindowCompat.getInsetsController(w, w.decorView)
        c.show(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT

        this@apply.requestFocus()
        orientationListener.disable()
      }
    }
  }

  init {
    addView(webView)
    initService()
    initWakeLock()

    currentActivity?.registerForContextMenu(webView)
    webView.addJavascriptInterface(NouJsInterface(context, this), "NouTubeI")

    ViewCompat.setOnApplyWindowInsetsListener(webView) { _, _ ->
      WindowInsetsCompat.CONSUMED
    }
  }

  override fun onCreateContextMenu(menu: ContextMenu) {
    super.onCreateContextMenu(menu)

    val r = webView.hitTestResult
    val a = currentActivity
    var u: String? = null

    if (r.type == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
      u = r.extra
    } else if (r.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
      val h = webView.handler.obtainMessage()
      webView.requestFocusNodeHref(h)
      u = h.data?.getString("url")
    }

    if (u != null && a != null) {
      menu.add("Copy link").setOnMenuItemClickListener {
        val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("link", u))
        true
      }
    }
  }

  private fun initWakeLock() {
    powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager?
    wakeLock = powerManager?.newWakeLock(
      PowerManager.PARTIAL_WAKE_LOCK,
      "NouTube::PlaybackWakeLock"
    )
  }

  fun initService() {
    val a = currentActivity ?: return

    val conn = object : ServiceConnection {
      override fun onServiceConnected(n: ComponentName, b: IBinder) {
        val nb = b as NouService.NouBinder
        service = nb.getService()
        service?.initialize(webView, a)
        nouController.service = service
        nouController.applyPendingSleepTimer()
      }

      override fun onServiceDisconnected(n: ComponentName) {}
    }

    val i = Intent(a, NouService::class.java)

    androidx.core.content.ContextCompat.startForegroundService(a, i)
    a.bindService(i, conn, Context.BIND_AUTO_CREATE)

    orientationListener = NouOrientationListener(a, this)
  }

  fun setScriptOnStart(s: String) {
    scriptOnStart = s
  }

  fun clearData() {
    val cm = CookieManager.getInstance()

    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
      cm.removeAllCookies(null)
    } else {
      @Suppress("DEPRECATION")
      cm.removeAllCookie()
    }

    cm.flush()
    webView.clearCache(true)
    webView.clearHistory()
    webView.clearFormData()
    webView.reload()
  }

  fun emit(t: String, d: Any) {
    onMessage(mapOf("payload" to mapOf("type" to t, "data" to d)))
  }

  fun notify(t: String, a: String, s: Long, th: String) {
    service?.notify(t, a, s, th)
  }

  fun notifyProgress(p: Boolean, pos: Long) {
    service?.notifyProgress(p, pos)

    currentActivity?.runOnUiThread {
      webView.keepScreenOn = p
      customView?.keepScreenOn = p

      if (p && wakeLock != null && !wakeLock!!.isHeld) {
        try {
          wakeLock!!.acquire(60 * 60 * 1000L)
        } catch (_: Exception) {}
      } else if (!p && wakeLock != null && wakeLock!!.isHeld) {
        try {
          wakeLock!!.release()
        } catch (_: Exception) {}
      }
    }
  }

  fun onOrientationChanged(o: Int) {
    val a = currentActivity

    if (
      a?.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE &&
      (o in 70..110 || o in 250..290)
    ) {
      a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER
    }
  }

  fun getPageUrl(): String = pageUrl

  fun exit() {
    service?.exit()

    if (wakeLock != null && wakeLock!!.isHeld) {
      wakeLock!!.release()
    }
  }
}
