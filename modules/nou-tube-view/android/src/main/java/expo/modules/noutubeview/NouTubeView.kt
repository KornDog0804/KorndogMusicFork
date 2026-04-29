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
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

val BLOCK_HOSTS = emptyArray<String>()

val VIEW_HOSTS = arrayOf(
  "youtube.com",
  "youtu.be"
)

val KORNDOG_LIGHT_THEME_CSS = """
html { background: #160026 !important; }
body { background: #160026 !important; }
ytmusic-app { background: #160026 !important; }
#content { background: #160026 !important; }
.music-container { background: #160026 !important; }
.content { background: #160026 !important; }
ytmusic-player-bar { background: #2d1450 !important; border-top: 3px solid #39ff14 !important; }
.progress { background: #39ff14 !important; }
.title { color: #39ff14 !important; }
.subtitle { color: #bba7d9 !important; }
yt-formatted-string { color: #f4edff !important; }
tp-yt-paper-slider #progressContainer #primaryProgress { background: #39ff14 !important; }
* { background-color: #160026 !important; }
""".trimIndent().replace("\n", " ").replace("'", "\\'")

val KORNDOG_LIGHT_THEME_SCRIPT = """
(function() {
  try {
    var existing = document.getElementById('korndog-light-theme');
    if (existing) existing.remove();
    
    var s = document.createElement('style');
    s.id = 'korndog-light-theme';
    s.textContent = '${KORNDOG_LIGHT_THEME_CSS}';
    document.head.appendChild(s);
    
    document.documentElement.style.background = '#160026';
    document.body.style.background = '#160026';
    
    console.log('[KornDog] Theme injected');
  } catch(e) {
    console.error('[KornDog] Theme error:', e);
  }
})();
""".trimIndent()

val KORNDOG_GENERATOR_SCRIPT = """
(function() {
  function initGeneratorButton() {
    if (!document.body) {
      setTimeout(initGeneratorButton, 500);
      return;
    }

    if (document.getElementById('korndog-generator-btn')) return;

    var container = document.createElement('div');
    container.id = 'korndog-gen-container';
    container.style.cssText =
      'position:fixed;' +
      'right:12px;' +
      'top:132px;' +
      'z-index:999999;' +
      'pointer-events:auto;';
    document.body.appendChild(container);

    var btn = document.createElement('button');
    btn.id = 'korndog-generator-btn';
    btn.textContent = '📺';
    btn.setAttribute('aria-label', 'Open KornDog Generator');
    btn.style.cssText = 'width:42px;height:42px;border-radius:14px;border:1px solid rgba(57,255,20,0.45);background:rgba(45,20,80,0.72);color:#39ff14;font-size:22px;line-height:1;display:flex;align-items:center;justify-content:center;box-shadow:0 0 14px rgba(57,255,20,0.22);backdrop-filter:blur(10px);opacity:0.92;padding:0;margin:0;cursor:pointer;transition:all 0.2s;touch-action:manipulation;pointer-events:auto;';

    btn.addEventListener('mouseenter', function() {
      this.style.opacity = '1';
      this.style.transform = 'scale(1.08)';
    });
    btn.addEventListener('mouseleave', function() {
      this.style.opacity = '0.92';
      this.style.transform = 'scale(1)';
    });

    btn.addEventListener('click', function(e) {
      e.preventDefault();
      e.stopPropagation();
      kdOpenGenerator();
    });

    container.appendChild(btn);
  }

  function kdOpenGenerator() {
    try {
      function cleanText(t) {
        return (t || '')
          .replace(/\s+/g, ' ')
          .replace('Explicit', '')
          .replace('Album', '')
          .trim();
      }

      function walkDOM(node, depth) {
        if (!node || depth > 8) return '';
        
        var text = '';
        if (node.nodeType === 3) {
          text = cleanText(node.textContent);
        } else if (node.nodeType === 1) {
          for (var i = 0; i < node.childNodes.length; i++) {
            text = walkDOM(node.childNodes[i], depth + 1);
            if (text && text.length > 1 && text.length < 140) return text;
          }
        }
        return text;
      }

      var title = '';
      var artist = '';
      var thumb = '';

      // Get title - walk page header area
      var titleEls = document.querySelectorAll('h1, h2, [role="heading"]');
      for (var ti = 0; ti < titleEls.length; ti++) {
        var t = cleanText(titleEls[ti].innerText || titleEls[ti].textContent);
        if (t && t.length > 2 && t.length < 140 && !title) {
          title = t;
          break;
        }
      }

      // Get artist - look for subtitle/byline
      var artistEls = document.querySelectorAll('h3, .subtitle, [class*="byline"], [class*="artist"]');
      for (var ai = 0; ai < artistEls.length; ai++) {
        var a = cleanText(artistEls[ai].innerText || artistEls[ai].textContent);
        if (a && a.indexOf(' • ') > -1) a = a.split(' • ')[0];
        if (a && a.length > 1 && a.length < 140 && !artist) {
          artist = a;
          break;
        }
      }

      // Get thumbnail - find largest image
      var imgs = Array.from(document.querySelectorAll('img'))
        .filter(function(img) {
          var src = img.currentSrc || img.src || '';
          var r = img.getBoundingClientRect();
          return (src.indexOf('ytimg') > -1 || src.indexOf('googleusercontent') > -1) && r.width >= 70 && r.height >= 70;
        })
        .sort(function(a, b) {
          var ar = a.getBoundingClientRect();
          var br = b.getBoundingClientRect();
          return (br.width * br.height) - (ar.width * ar.height);
        });

      if (imgs.length) thumb = imgs[0].currentSrc || imgs[0].src;

      if (!thumb) {
        var meta = document.querySelector('meta[property="og:image"], meta[name="twitter:image"]');
        if (meta && meta.content) thumb = meta.content;
      }

      var params = new URLSearchParams();
      params.set('from', 'ghostkernel');

      if (artist) params.set('artist', artist);
      if (title) params.set('album', title);
      if (thumb) params.set('thumb', thumb);

      params.set('sourceUrl', window.location.href);

      window.location.href =
        'https://korndogrecords.com/korndog-spinning-generator.html?' +
        params.toString();

    } catch(e) {
      console.error('[KornDog] Generator error:', e);
    }
  }

  setTimeout(initGeneratorButton, 1000);

  if (!window._kdAudioBoostInit) {
    window._kdAudioBoostInit = true;
    var AudioCtx = window.AudioContext || window.webkitAudioContext;
    if (AudioCtx) {
      var ctx = new AudioCtx();
      window._kdAudioCtx = ctx;

      var compressor = ctx.createDynamicsCompressor();
      compressor.threshold.value = -35;
      compressor.knee.value = 20;
      compressor.ratio.value = 8;
      compressor.attack.value = 0.005;
      compressor.release.value = 0.2;

      var gainNode = ctx.createGain();
      gainNode.gain.value = 2.0;

      compressor.connect(gainNode);
      gainNode.connect(ctx.destination);

      function hookAudio(el) {
        try {
          if (!el._kdHooked) {
            el._kdHooked = true;
            var src = ctx.createMediaElementSource(el);
            src.connect(compressor);
            if (ctx.state === 'suspended') ctx.resume();
          }
        } catch(e) {}
      }

      function hookAll() {
        document.querySelectorAll('audio, video').forEach(function(el) {
          hookAudio(el);
        });
      }

      var boostObserver = new MutationObserver(function() {
        hookAll();
      });

      boostObserver.observe(document.documentElement, { childList: true, subtree: true });
      hookAll();
      setTimeout(hookAll, 2000);
      setTimeout(hookAll, 5000);
    }
  }

  if (!window._kdAudioResumeInit) {
    window._kdAudioResumeInit = true;
    document.addEventListener('touchstart', function() {
      if (window._kdAudioCtx && window._kdAudioCtx.state === 'suspended') {
        window._kdAudioCtx.resume();
      }
    }, { passive: true });
  }

  if (!window._kdLightWatchdogInit) {
    window._kdLightWatchdogInit = true;

    var kdLastTime = 0;
    var kdStallTicks = 0;
    var kdUserPaused = false;

    document.addEventListener('pause', function(e) {
      if (e.target && (e.target.tagName === 'VIDEO' || e.target.tagName === 'AUDIO')) {
        kdUserPaused = true;
        setTimeout(function() {
          kdUserPaused = false;
        }, 4000);
      }
    }, true);

    document.addEventListener('play', function(e) {
      if (e.target && (e.target.tagName === 'VIDEO' || e.target.tagName === 'AUDIO')) {
        kdUserPaused = false;
        if (window._kdAudioCtx && window._kdAudioCtx.state === 'suspended') {
          window._kdAudioCtx.resume().catch(function(){});
        }
      }
    }, true);

    setInterval(function() {
      var media = document.querySelector('video, audio');
      if (!media) return;

      if (media.paused || media.ended || kdUserPaused) {
        kdLastTime = media.currentTime || 0;
        kdStallTicks = 0;
        return;
      }

      var nowTime = media.currentTime || 0;
      var stuck = Math.abs(nowTime - kdLastTime) < 0.05;

      if (stuck) {
        kdStallTicks++;
      } else {
        kdStallTicks = 0;
      }

      kdLastTime = nowTime;

      if (kdStallTicks >= 3) {
        try {
          media.play().catch(function(){});
          if (window._kdAudioCtx && window._kdAudioCtx.state === 'suspended') {
            window._kdAudioCtx.resume().catch(function(){});
          }
        } catch(e) {}
      }
    }, 2500);
  }

  if (window.NouTubeI && window.NouTubeI.keepScreenOn) {
    document.addEventListener('play', function() {
      window.NouTubeI.keepScreenOn(true);
    }, true);
    document.addEventListener('pause', function() {
      window.NouTubeI.keepScreenOn(false);
    }, true);
  }
})();
""".trimIndent()

class NouWebView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

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
        cont.resume(null)
      } else {
        cont.resume(result.removeSurrounding("\""))
      }
    }
  }
}

class NouOrientationListener(
  context: Context,
  private val view: NouTubeView
) : OrientationEventListener(context) {
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
    val result = webView.hitTestResult
    val activity = currentActivity
    var url: String? = null

    if (result.type == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
      url = result.extra
    } else if (result.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
      val href = webView.handler.obtainMessage()
      webView.requestFocusNodeHref(href)
      val data = href.data
      if (data != null) url = data.getString("url")
    }

    if (url != null && activity != null) {
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
            onLoad(mapOf("url" to pageUrl))
          }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
          evaluateJavascript(KORNDOG_LIGHT_THEME_SCRIPT, null)
          evaluateJavascript(scriptOnStart, null)
        }

        override fun onPageFinished(view: WebView, url: String) {
          evaluateJavascript(KORNDOG_GENERATOR_SCRIPT, null)
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
          return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
          val uri = Uri.parse(url)
          if (
            uri.host in VIEW_HOSTS ||
            (uri.host?.startsWith("accounts.google.") == true) ||
            (uri.host?.startsWith("gds.google.") == true) ||
            (uri.host?.endsWith(".youtube.com") == true)
          ) {
            return false
          } else {
            view.context.startActivity(Intent(Intent.ACTION_VIEW, uri))
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

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
          customView = view
          view.keepScreenOn = true

          val activity = currentActivity ?: return
          val window = activity.window

          (window.decorView as FrameLayout).addView(
            view,
            FrameLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.MATCH_PARENT
            )
          )

          activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

          val controller = WindowCompat.getInsetsController(window, window.decorView)
          controller.hide(WindowInsetsCompat.Type.systemBars())
          controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

          if (
            Settings.System.getInt(
              activity.contentResolver,
              Settings.System.ACCELEROMETER_ROTATION,
              0
            ) == 1
          ) {
            orientationListener.enable()
          }
        }

        override fun onHideCustomView() {
          val activity = currentActivity ?: return
          val window = activity.window

          (window.decorView as FrameLayout).removeView(customView)
          customView?.keepScreenOn = false
          customView = null

          activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER

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
    ViewCompat.setOnApplyWindowInsetsListener(webView) { _, _ -> WindowInsetsCompat.CONSUMED }
  }

  fun initService() {
    val activity = currentActivity ?: return

    val connection = object : ServiceConnection {
      override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        val nouBinder = binder as NouService.NouBinder
        service = nouBinder.getService()
        service?.initialize(webView, activity)
        nouController.service = service
        nouController.applyPendingSleepTimer()
      }

      override fun onServiceDisconnected(name: ComponentName) {}
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
    onMessage(mapOf("payload" to mapOf("type" to type, "data" to data)))
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
    if (
      activity?.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE &&
      (orientation in 70..110 || orientation in 250..290)
    ) {
      activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER
    }
  }

  fun getPageUrl(): String = pageUrl

  fun exit() {
    service?.exit()
  }
}
