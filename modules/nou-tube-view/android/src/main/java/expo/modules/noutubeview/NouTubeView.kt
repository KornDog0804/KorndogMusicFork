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

val KORNDOG_THEME_CSS = """
:root {
  --yt-spec-base-background: #120020 !important;
  --yt-spec-raised-background: #1a0a2e !important;
  --yt-spec-menu-background: #2d1450 !important;
  --yt-spec-brand-background-solid: #2d1450 !important;
  --yt-spec-general-background-a: #120020 !important;
  --yt-spec-general-background-b: #1a0a2e !important;
  --yt-spec-general-background-c: #2d1450 !important;
  --yt-spec-call-to-action: #39ff14 !important;
  --yt-spec-static-brand-red: #39ff14 !important;
  --yt-spec-text-primary: #f0eaf8 !important;
  --yt-spec-text-secondary: #bba7d9 !important;
  --yt-spec-icon-active-other: #39ff14 !important;
  --yt-spec-brand-icon-active: #39ff14 !important;
}

ytmusic-nav-bar,
#nav-bar-background,
ytmusic-player-bar {
  background: #2d1450 !important;
}

ytmusic-player-bar {
  border-top: 3px solid #39ff14 !important;
  box-shadow: 0 -2px 18px rgba(57,255,20,.18) !important;
}

ytmusic-player-bar .title,
.content-info-wrapper .title,
ytmusic-player-page .title {
  color: #39ff14 !important;
}

ytmusic-player-bar .subtitle,
.content-info-wrapper .subtitle,
ytmusic-player-page .subtitle {
  color: #bba7d9 !important;
}

tp-yt-paper-slider #progressContainer #primaryProgress {
  background: #39ff14 !important;
}

ytmusic-chip-cloud-chip-renderer,
ytmusic-responsive-list-item-renderer:hover,
tp-yt-paper-listbox,
ytmusic-menu-popup-renderer {
  background: #2d1450 !important;
}

ytmusic-player-page,
ytmusic-browse-response,
ytmusic-section-list-renderer {
  background: #120020 !important;
  color: #f0eaf8 !important;
}
""".trimIndent().replace("\n", " ").replace("'", "\\'")

val KORNDOG_QUEUE_TRACKER_SCRIPT = """
(function() {
  if (window._kdQueueTrackerInit) return;
  window._kdQueueTrackerInit = true;

  var QUEUE_STORAGE_KEY = 'korndog_queue';
  var MAX_QUEUE_SIZE = 5;

  function cleanText(t) {
    return (t || '')
      .replace(/\s+/g, ' ')
      .replace(/Explicit/g, '')
      .replace(/Album/g, '')
      .replace(/Song/g, '')
      .replace(/Video/g, '')
      .trim();
  }

  function getCurrentSongInfo() {
    var title = '';
    var artist = '';
    var thumb = '';

    var titleEl = document.querySelector('ytmusic-player-bar .title, .content-info-wrapper .title, ytmusic-player-page .title');
    if (titleEl) title = cleanText(titleEl.innerText || titleEl.textContent);

    var artistEl = document.querySelector('ytmusic-player-bar .subtitle, .content-info-wrapper .subtitle, ytmusic-player-page .subtitle');
    if (artistEl) artist = cleanText(artistEl.innerText || artistEl.textContent);

    if (artist.indexOf(' • ') > -1) artist = artist.split(' • ')[0].trim();
    if (artist.indexOf(' - ') > -1) artist = artist.split(' - ')[0].trim();

    var imgEls = Array.from(document.querySelectorAll('img')).filter(function(img) {
      var src = img.currentSrc || img.src || '';
      return src && (src.indexOf('ytimg') > -1 || src.indexOf('googleusercontent') > -1);
    }).sort(function(a, b) {
      var ar = a.getBoundingClientRect();
      var br = b.getBoundingClientRect();
      return (br.width * br.height) - (ar.width * ar.height);
    });

    if (imgEls.length) thumb = imgEls[0].currentSrc || imgEls[0].src;

    if (!title) {
      var ogTitle = document.querySelector('meta[property="og:title"]');
      if (ogTitle && ogTitle.content) title = cleanText(ogTitle.content);
    }

    if (!artist) {
      var ogDesc = document.querySelector('meta[property="og:description"]');
      if (ogDesc && ogDesc.content) artist = cleanText(ogDesc.content);
    }

    if (!thumb) {
      var ogImg = document.querySelector('meta[property="og:image"], meta[name="twitter:image"]');
      if (ogImg && ogImg.content) thumb = ogImg.content;
    }

    return { title: title, artist: artist, thumb: thumb };
  }

  function getQueue() {
    try {
      var stored = localStorage.getItem(QUEUE_STORAGE_KEY);
      return stored ? JSON.parse(stored) : [];
    } catch(e) {
      return [];
    }
  }

  function saveQueue(queue) {
    try {
      localStorage.setItem(QUEUE_STORAGE_KEY, JSON.stringify(queue));
    } catch(e) {}
  }

  function addToQueue(title, artist, thumb) {
    if (!title || !artist) return;

    var queue = getQueue();
    var now = Date.now();

    if (queue.length > 0 && queue[0].title === title && queue[0].artist === artist) {
      return;
    }

    queue.unshift({
      title: title,
      artist: artist,
      thumb: thumb || '',
      played: now
    });

    if (queue.length > MAX_QUEUE_SIZE) {
      queue = queue.slice(0, MAX_QUEUE_SIZE);
    }

    saveQueue(queue);
    console.log('[KornDog Queue] Added:', title, '-', artist);
  }

  var lastSong = '';

  function checkForNewSong() {
    try {
      var info = getCurrentSongInfo();
      var songKey = info.title + '|' + info.artist;

      if (songKey && songKey !== lastSong && info.title && info.artist) {
        lastSong = songKey;
        addToQueue(info.title, info.artist, info.thumb);
      }
    } catch(e) {}
  }

  setInterval(checkForNewSong, 2000);

  document.addEventListener('play', function() {
    setTimeout(checkForNewSong, 500);
  }, true);

  document.addEventListener('timeupdate', function() {
    checkForNewSong();
  }, true);

  checkForNewSong();
})();
""".trimIndent()

val KORNDOG_CAST_SCRIPT = """
(function() {
  function applyKornDogThemeAgain() {
    try {
      var s = document.getElementById('korndog-theme');
      if (s) s.remove();

      var style = document.createElement('style');
      style.id = 'korndog-theme';
      style.textContent = '${KORNDOG_THEME_CSS}';
      document.head.appendChild(style);
    } catch(e) {}
  }

  applyKornDogThemeAgain();
  setInterval(applyKornDogThemeAgain, 2500);

  function initTVButton() {
    if (!document.body) {
      setTimeout(initTVButton, 500);
      return;
    }

    if (document.getElementById('korndog-tv-btn')) return;

    var btn = document.createElement('button');
    btn.id = 'korndog-tv-btn';
    btn.textContent = '📺';
    btn.setAttribute('aria-label', 'Open KornDog Generator');
    btn.style.cssText =
      'position:fixed;' +
      'right:14px;' +
      'bottom:116px;' +
      'z-index:999999;' +
      'width:38px;' +
      'height:38px;' +
      'border-radius:13px;' +
      'border:1px solid rgba(57,255,20,.55);' +
      'background:rgba(45,20,80,.82);' +
      'color:#39ff14;' +
      'font-size:20px;' +
      'display:flex;' +
      'align-items:center;' +
      'justify-content:center;' +
      'box-shadow:0 0 16px rgba(57,255,20,.35);' +
      'backdrop-filter:blur(10px);' +
      'padding:0;' +
      'margin:0;' +
      'cursor:pointer;' +
      'touch-action:manipulation;';

    btn.addEventListener('click', function(e) {
      e.preventDefault();
      e.stopPropagation();
      openKornDogGenerator();
      return false;
    });

    document.body.appendChild(btn);
  }

  function openKornDogGenerator() {
    try {
      var queue = [];
      try {
        queue = JSON.parse(localStorage.getItem('korndog_queue') || '[]');
      } catch(e) {}

      var song = queue && queue.length ? queue[0] : null;
      var params = new URLSearchParams();
      params.set('from', 'ghostkernel');

      if (song) {
        if (song.artist) params.set('artist', song.artist);
        if (song.title) params.set('album', song.title);
        if (song.thumb) params.set('thumb', song.thumb);
      }

      window.location.href =
        'https://korndogrecords.com/korndog-spinning-generator.html?' +
        params.toString();
    } catch(e) {
      window.location.href = 'https://korndogrecords.com/korndog-spinning-generator.html?from=ghostkernel';
    }
  }

  setTimeout(initTVButton, 1000);

  if (!window._kdAudioNormInit) {
    window._kdAudioNormInit = true;
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

      var obs = new MutationObserver(hookAll);
      obs.observe(document.documentElement, { childList: true, subtree: true });
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
          evaluateJavascript(scriptOnStart, null)
        }

        override fun onPageFinished(view: WebView, url: String) {
          evaluateJavascript(KORNDOG_CAST_SCRIPT, null)
          evaluateJavascript(KORNDOG_QUEUE_TRACKER_SCRIPT, null)
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
