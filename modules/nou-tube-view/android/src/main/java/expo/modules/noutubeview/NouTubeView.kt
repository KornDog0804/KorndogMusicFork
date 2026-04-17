package expo.modules.noutubeview

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import android.util.AttributeSet
import android.view.ContextMenu
import android.view.MenuItem
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView
import java.io.ByteArrayInputStream
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

val BLOCK_HOSTS = arrayOf(
  "www.googletagmanager.com",
  "googleads.g.doubleclick.net"
)

val VIEW_HOSTS = arrayOf(
  "youtube.com",
  "youtu.be"
)

// ============================================
// KORNDOG RECORDS THEME CSS
// ============================================
val KORNDOG_THEME_CSS = """
:root {
  --yt-spec-base-background: #1a0a2e !important;
  --yt-spec-raised-background: #1e0e35 !important;
  --yt-spec-menu-background: #2d1450 !important;
  --yt-spec-brand-background-solid: #2d1450 !important;
  --yt-spec-general-background-a: #1a0a2e !important;
  --yt-spec-general-background-b: #110720 !important;
  --yt-spec-general-background-c: #1e0e35 !important;
  --yt-spec-static-brand-red: #39ff14 !important;
  --yt-spec-call-to-action: #39ff14 !important;
  --yt-spec-text-primary: #f0eaf8 !important;
  --yt-spec-text-secondary: #a090b8 !important;
  --yt-spec-text-disabled: #6b5a80 !important;
  --yt-spec-icon-active-other: #39ff14 !important;
  --yt-spec-icon-inactive: #8a7f99 !important;
  --yt-spec-badge-chip-background: #3f1d6b !important;
  --yt-spec-brand-icon-active: #39ff14 !important;
  --yt-spec-brand-button-background: #39ff14 !important;
  --yt-spec-10-percent-layer: rgba(57,255,20,0.1) !important;
  --yt-spec-outline: #3f1d6b !important;
  --yt-spec-shadow: rgba(0,0,0,0.5) !important;
}
body { background: #1a0a2e !important; color: #f0eaf8 !important; }
ytmusic-player-bar { background: #2d1450 !important; border-top: 2px solid #39ff14 !important; }
tp-yt-paper-slider #sliderKnob { background: #39ff14 !important; width: 12px !important; height: 12px !important; border-radius: 50% !important; }
tp-yt-paper-slider #sliderKnob #sliderKnobInner { background: #39ff14 !important; width: 12px !important; height: 12px !important; border-radius: 50% !important; border: none !important; box-shadow: none !important; }
tp-yt-paper-slider #progressContainer #primaryProgress { background: #39ff14 !important; }
ytmusic-pivot-bar-renderer { background: #1a0a2e !important; border-top: 1px solid #3f1d6b !important; }
ytmusic-chip-cloud-chip-renderer { background: #3f1d6b !important; }
ytmusic-chip-cloud-chip-renderer[selected] { background: #39ff14 !important; color: #1a0a2e !important; }
ytmusic-tabs-header { background: #2d1450 !important; }
#nav-bar-background { background: #2d1450 !important; }
ytmusic-search-box { background: #1e0e35 !important; }
.content-info-wrapper .title { color: #39ff14 !important; }
.content-info-wrapper .subtitle { color: #a090b8 !important; }
ytmusic-detail-header-renderer { background: #1a0a2e !important; }
ytmusic-section-list-renderer { background: #1a0a2e !important; }
ytmusic-responsive-list-item-renderer:hover { background: rgba(57,255,20,0.08) !important; }
ytmusic-menu-popup-renderer { background: #2d1450 !important; }
tp-yt-paper-item:hover { background: rgba(57,255,20,0.1) !important; }
tp-yt-paper-listbox { background: #2d1450 !important; }
ytmusic-dialog { background: #2d1450 !important; }
yt-button-renderer[button-next] a { color: #39ff14 !important; }
.toggle-button { color: #39ff14 !important; }
#korndog-cast-btn { position:fixed; bottom:80px; right:16px; z-index:99999; width:52px; height:52px; border-radius:50%; background:#39ff14; border:2px solid #2d1450; box-shadow:0 0 12px rgba(57,255,20,0.4); cursor:pointer; display:flex; align-items:center; justify-content:center; font-size:24px; line-height:1; transition:transform 0.15s,box-shadow 0.15s; }
#korndog-cast-btn:active { transform:scale(0.92); }
#korndog-cast-btn.connected { background:#2d1450; border-color:#39ff14; }
#korndog-cast-overlay { display:none; position:fixed; top:0; left:0; width:100%; height:100%; background:rgba(26,10,46,0.95); z-index:100000; flex-direction:column; align-items:center; justify-content:center; padding:20px; }
#korndog-cast-overlay.show { display:flex; }
#korndog-cast-overlay h2 { color:#39ff14; font-size:20px; margin-bottom:16px; font-family:sans-serif; }
#korndog-cast-overlay .kd-device { background:#2d1450; border:1px solid #3f1d6b; border-radius:8px; padding:14px 20px; margin:6px 0; width:100%; max-width:300px; color:#f0eaf8; font-size:16px; font-family:sans-serif; cursor:pointer; text-align:center; }
#korndog-cast-overlay .kd-device:active { background:#3f1d6b; border-color:#39ff14; }
#korndog-cast-overlay .kd-status { color:#a090b8; font-size:14px; margin:12px 0; font-family:sans-serif; }
#korndog-cast-overlay .kd-close { color:#ff2d2d; font-size:14px; margin-top:20px; cursor:pointer; font-family:sans-serif; padding:10px; }
#korndog-cast-controls { display:none; position:fixed; bottom:140px; right:10px; z-index:99999; background:#2d1450; border:1px solid #39ff14; border-radius:12px; padding:8px; flex-direction:column; gap:6px; box-shadow:0 0 12px rgba(57,255,20,0.3); }
#korndog-cast-controls.show { display:flex; }
#korndog-cast-controls button { width:40px; height:40px; border-radius:50%; border:none; background:#3f1d6b; color:#39ff14; font-size:18px; cursor:pointer; }
#korndog-cast-controls button:active { background:#5c2d91; }
""".trimIndent().replace("\n", " ").replace("'", "\\'")

// Theme injection - runs on page start (only needs head)
val KORNDOG_THEME_SCRIPT = """
(function() {
  var existing = document.getElementById('korndog-theme');
  if (existing) existing.remove();
  var s = document.createElement('style');
  s.id = 'korndog-theme';
  s.textContent = '$KORNDOG_THEME_CSS';
  document.head.appendChild(s);
})();
""".trimIndent()

// Cast button injection - deferred until body exists
val KORNDOG_CAST_SCRIPT = """
(function() {
  function initCastButton() {
    if (!document.body) { setTimeout(initCastButton, 500); return; }
    if (document.getElementById('korndog-cast-btn')) return;

    var btn = document.createElement('div');
    btn.id = 'korndog-cast-btn';
    btn.textContent = '\uD83D\uDCFA';
    document.body.appendChild(btn);

    var overlay = document.createElement('div');
    overlay.id = 'korndog-cast-overlay';
    document.body.appendChild(overlay);

    var h2 = document.createElement('h2');
    h2.textContent = 'Cast to TV';
    overlay.appendChild(h2);

    var status = document.createElement('div');
    status.className = 'kd-status';
    status.textContent = 'Searching for devices...';
    overlay.appendChild(status);

    var closeBtn = document.createElement('div');
    closeBtn.className = 'kd-close';
    closeBtn.textContent = 'Close';
    overlay.appendChild(closeBtn);

    var controls = document.createElement('div');
    controls.id = 'korndog-cast-controls';
    document.body.appendChild(controls);

    var pauseBtn = document.createElement('button');
    pauseBtn.id = 'kd-ctrl-pause';
    pauseBtn.textContent = '\u23F8';
    controls.appendChild(pauseBtn);

    var stopBtn = document.createElement('button');
    stopBtn.id = 'kd-ctrl-stop';
    stopBtn.textContent = '\u23F9';
    controls.appendChild(stopBtn);

    var isConnected = false;

    btn.addEventListener('click', function() {
      if (isConnected) {
        controls.classList.toggle('show');
      } else {
        overlay.classList.add('show');
        status.textContent = 'Searching for devices...';
        var old = overlay.querySelectorAll('.kd-device');
        for (var i = 0; i < old.length; i++) old[i].remove();
        if (window.NouTubeI && window.NouTubeI.discoverDevices) {
          window.NouTubeI.discoverDevices();
        } else {
          status.textContent = 'Cast not available';
        }
      }
    });

    closeBtn.addEventListener('click', function() {
      overlay.classList.remove('show');
    });

    pauseBtn.addEventListener('click', function() {
      if (window.NouTubeI) window.NouTubeI.pauseCast();
    });

    stopBtn.addEventListener('click', function() {
      if (window.NouTubeI) window.NouTubeI.stopCast();
      isConnected = false;
      btn.classList.remove('connected');
      btn.textContent = '\uD83D\uDCFA';
      controls.classList.remove('show');
    });

    window.kdShowDevices = function(devicesJson) {
      var devices = JSON.parse(devicesJson);
      var old = overlay.querySelectorAll('.kd-device');
      for (var i = 0; i < old.length; i++) old[i].remove();

      if (devices.length === 0) {
        status.textContent = 'No devices found. Make sure your TV is on and on the same WiFi.';
        return;
      }

      status.textContent = 'Found ' + devices.length + ' device(s):';
      for (var j = 0; j < devices.length; j++) {
        (function(index, name) {
          var el = document.createElement('div');
          el.className = 'kd-device';
          el.textContent = name;
          el.addEventListener('click', function() {
            status.textContent = 'Connecting to ' + name + '...';
            window.NouTubeI.selectAndCast(index);
          });
          overlay.insertBefore(el, closeBtn);
        })(j, devices[j].name);
      }
    };

    window.kdCastResult = function(success, deviceName) {
      if (success) {
        isConnected = true;
        btn.classList.add('connected');
        btn.textContent = '\uD83D\uDCFB';
        overlay.classList.remove('show');
        controls.classList.add('show');
      } else {
        status.textContent = 'Failed to cast. Try again.';
      }
    };
  }
  setTimeout(initCastButton, 1000);
})();
""".trimIndent()

class NouWebView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
  WebView(context, attrs, defStyleAttr) {

  override fun onWindowVisibilityChanged(visibility: Int) {
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
    }
    CookieManager.getInstance().setAcceptCookie(true)

    setFocusable(true)
    setFocusableInTouchMode(true)
  }

  suspend fun eval(script: String): String? = suspendCancellableCoroutine { cont ->
    evaluateJavascript(script) { result ->
      if (result == "null") {
        cont.resume(null, null)
      } else {
        cont.resume(result.removeSurrounding("\""), null)
      }
    }
  }
}

class NouOrientationListener(context: Context, private val view: NouTubeView) : OrientationEventListener(context) {
  override fun onOrientationChanged(orientation: Int) {
    view.onOrientationChanged(orientation)
  }
}

class NouTubeView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {
  private val onLoad by EventDispatcher()
  internal val onMessage by EventDispatcher()

  private var scriptOnStart = ""
  private var pageUrl = ""
  private var customView: View? = null
  private lateinit var orientationListener: NouOrientationListener

  internal val nouCast = NouCast(context)

  private val gestureListener =
    object : GestureDetector.SimpleOnGestureListener() {
      override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
        var dy = distanceY
        if (e1 != null) {
          dy = (e2.y - e1.y) / context.resources.displayMetrics.density
        }
        emit("scroll", mapOf("dy" to dy, "y" to webView.scrollY))
        return false
      }
    }

  private val gestureDetector = GestureDetector(context, gestureListener)

  private var service: NouService? = null

  internal val currentActivity: Activity?
    get() = appContext.activityProvider?.currentActivity

  override fun onCreateContextMenu(menu: ContextMenu) {
    super.onCreateContextMenu(menu)

    val result = webView.getHitTestResult()
    val activity = currentActivity
    var url: String? = null

    if (result.getType() == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
      url = result.getExtra()
    } else if (result.getType() == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
      val href = webView.getHandler().obtainMessage()
      webView.requestFocusNodeHref(href)
      val data = href.getData()
      if (data != null) {
        url = data.getString("url")
      }
    }
    if (
      url != null && activity != null
    ) {
      val onCopyLink = object : MenuItem.OnMenuItemClickListener {
        override fun onMenuItemClick(item: MenuItem): Boolean {
          val clipboardManager = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
          val clipData = ClipData.newPlainText("link", url)
          clipboardManager.setPrimaryClip(clipData)
          return true
        }
      }

      menu.add("Copy link").setOnMenuItemClickListener(onCopyLink)
    }
  }
  internal val webView: NouWebView =
    NouWebView(context).apply {
      layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
      setOnTouchListener { _, event ->
        gestureDetector.onTouchEvent(event)
        false
      }
      webViewClient = object : WebViewClient() {
          override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            if (pageUrl != url) {
              pageUrl = url
              onLoad(
                mapOf(
                  "url" to pageUrl
                )
              )
            }
          }

          override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            // Inject theme CSS immediately (only needs head)
            evaluateJavascript(KORNDOG_THEME_SCRIPT, null)
            evaluateJavascript(scriptOnStart, null)
          }

          override fun onPageFinished(view: WebView, url: String) {
            // Inject cast button after page is loaded (needs body)
            evaluateJavascript(KORNDOG_CAST_SCRIPT, null)
          }

          override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (request.url.host in BLOCK_HOSTS) {
              return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            return null
          }

          override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            val uri = Uri.parse(url)
            if (uri.host in VIEW_HOSTS ||
              (uri.host?.startsWith("accounts.google.") == true) ||
              (uri.host?.startsWith("gds.google.") == true) ||
              (uri.host?.endsWith(".youtube.com") == true)
            ) {
              return false
            } else {
              view.getContext().startActivity(
                Intent(Intent.ACTION_VIEW, uri)
              )
              return true
            }
          }
        }

      webChromeClient = object : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
          val activity = currentActivity
          if (activity == null) {
            request.deny()
            return
          }

          val resources = request.resources
          val permissionsToRequest = mutableListOf<String>()

          if (resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
            permissionsToRequest.add(android.Manifest.permission.RECORD_AUDIO)
          }
          if (resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
            permissionsToRequest.add(android.Manifest.permission.CAMERA)
          }

          if (permissionsToRequest.isEmpty()) {
            request.grant(resources)
            return
          }

          activity.requestPermissions(permissionsToRequest.toTypedArray(), 101)
          request.grant(resources)
        }

        override fun onJsBeforeUnload(view: WebView, url: String, message: String, result: JsResult): Boolean {
          result.confirm()
          return true
        }

        override fun onShowCustomView(view: View, cllback: CustomViewCallback) {
          customView = view
          view.setKeepScreenOn(true)
          val activity = currentActivity
          if (activity == null) {
            return
          }
          val window = activity.window
          (window.decorView as FrameLayout).addView(
            view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
          )
          activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)

          val controller = WindowCompat.getInsetsController(window, window.decorView)
          controller.hide(WindowInsetsCompat.Type.systemBars())
          controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

          if (Settings.System.getInt(activity.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1) {
            orientationListener.enable()
          }
        }

        override fun onHideCustomView() {
          val activity = currentActivity
          if (activity == null) {
            return
          }
          val window = activity.window
          (window.decorView as FrameLayout).removeView(customView)
          customView?.setKeepScreenOn(false)
          customView = null
          activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER)

          val controller = WindowCompat.getInsetsController(window, window.decorView)
          controller.show(WindowInsetsCompat.Type.systemBars())
          controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT

          this@apply.requestFocus()
          orientationListener.disable()
        }
      }
    }

  init {
    addView(webView)

    initService()

    val activity = currentActivity
    activity?.registerForContextMenu(webView)

    webView.addJavascriptInterface(NouJsInterface(context, this), "NouTubeI")

    ViewCompat.setOnApplyWindowInsetsListener(webView) { _, _ ->
      WindowInsetsCompat.CONSUMED
    }
  }

  fun initService() {
    val activity = currentActivity
    if (activity == null) {
      return
    }
    val connection = object : ServiceConnection {
      override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        val nouBinder = binder as NouService.NouBinder
        service = nouBinder.getService()
        service?.initialize(webView, activity)
        nouController.service = service
        nouController.applyPendingSleepTimer()
      }

      override fun onServiceDisconnected(name: ComponentName) {
      }
    }
    val intent = Intent(activity, NouService::class.java)
    activity.bindService(intent, connection, Context.BIND_AUTO_CREATE)

    orientationListener = NouOrientationListener(activity, this)
  }

  fun setScriptOnStart(script: String) {
    scriptOnStart = script
  }

  fun clearData() {
    val cookieManager = CookieManager.getInstance()
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
      cookieManager.removeAllCookies(null)
    } else {
      cookieManager.removeAllCookie()
    }
    cookieManager.flush()

    webView.clearCache(true)
    webView.clearHistory()
    webView.clearFormData()
    webView.reload()
  }

  fun emit(type: String, data: Any) {
    val payload = mapOf("type" to type, "data" to data)
    onMessage(mapOf("payload" to payload))
  }

  fun notify(title: String, author: String, seconds: Long, thumbnail: String) {
    service?.notify(title, author, seconds, thumbnail)
  }

  fun notifyProgress(playing: Boolean, pos: Long) {
    service?.notifyProgress(playing, pos)
    currentActivity?.runOnUiThread {
      webView.keepScreenOn = playing
      customView?.keepScreenOn = playing
    }
  }

  fun onOrientationChanged(orientation: Int) {
    val activity = currentActivity
    if (activity?.getRequestedOrientation() == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE &&
      (orientation in 70..110 || orientation in 250..290)
    ) {
      activity?.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER)
    }
  }

  fun getPageUrl(): String {
    return pageUrl
  }

  fun exit() {
    service?.exit()
  }
}
