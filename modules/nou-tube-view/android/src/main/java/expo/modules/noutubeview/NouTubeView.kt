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
import android.os.PowerManager
import android.provider.Settings
import android.util.AttributeSet
import android.util.Log
import android.view.ContextMenu
import android.view.GestureDetector
import android.view.MenuItem
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
import android.webkit.WebSettings
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

const val TAG = "NouTube"

val VIEW_HOSTS = arrayOf("youtube.com", "youtu.be")

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
  var lastSong = '';
  var lastProgressSend = 0;

  function cleanText(t) {
    return (t || '')
      .replace(/\s+/g, ' ')
      .replace(/Explicit/g, '')
      .replace(/Album/g, '')
      .replace(/Song/g, '')
      .replace(/Video/g, '')
      .trim();
  }

  function upgradeThumb(url) {
    if (!url) return '';
    return url
      .replace(/=w[0-9]+-h[0-9]+.*$/i, '=w800-h800-l90-rj')
      .replace(//s[0-9]+//i, '/s800/');
  }

  function getCurrentSongInfo() {
    var title = '';
    var artist = '';
    var thumb = '';
    var seconds = 0;

    var media = document.querySelector('video, audio');
    if (media) seconds = Math.floor(media.duration || 0);

    var titleEl = document.querySelector('ytmusic-player-bar .title') || 
                  document.querySelector('.content-info-wrapper .title') ||
                  document.querySelector('ytmusic-player-page .title');
    if (titleEl) title = cleanText(titleEl.innerText || titleEl.textContent);

    var artistEl = document.querySelector('ytmusic-player-bar .subtitle') ||
                   document.querySelector('.content-info-wrapper .subtitle') ||
                   document.querySelector('ytmusic-player-page .subtitle');
    if (artistEl) artist = cleanText(artistEl.innerText || artistEl.textContent);

    if (artist.indexOf(' • ') > -1) artist = artist.split(' • ')[0].trim();
    if (artist.indexOf(' - ') > -1) artist = artist.split(' - ')[0].trim();

    var imgs = Array.from(document.querySelectorAll('img')).filter(function(img) {
      var src = img.currentSrc || img.src || '';
      return src && (src.indexOf('ytimg') > -1 || src.indexOf('googleusercontent') > -1);
    }).sort(function(a, b) {
      var ar = a.getBoundingClientRect();
      var br = b.getBoundingClientRect();
      return (br.width * br.height) - (ar.width * ar.height);
    });

    if (imgs.length) thumb = imgs[0].currentSrc || imgs[0].src;

    var ogImg = document.querySelector('meta[property="og:image"], meta[name="twitter:image"]');
    if (!thumb && ogImg && ogImg.content) thumb = ogImg.content;

    var ogTitle = document.querySelector('meta[property="og:title"]');
    if (!title && ogTitle && ogTitle.content) title = cleanText(ogTitle.content);

    var ogDesc = document.querySelector('meta[property="og:description"]');
    if (!artist && ogDesc && ogDesc.content) artist = cleanText(ogDesc.content);

    return {
      title: title,
      artist: artist,
      thumb: upgradeThumb(thumb),
      seconds: seconds
    };
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

  function sendToAndroid(info) {
    try {
      if (!info.title || !info.artist) return;
      if (window.NouTubeI && window.NouTubeI.notify) {
        window.NouTubeI.notify(info.title, info.artist, String(info.seconds || 0), info.thumb || '');
      }
    } catch(e) {}
  }

  function sendProgress() {
    try {
      var media = document.querySelector('video, audio');
      if (!media) return;
      var now = Date.now();
      if (now - lastProgressSend < 1000) return;
      lastProgressSend = now;
      if (window.NouTubeI && window.NouTubeI.notifyProgress) {
        window.NouTubeI.notifyProgress(String(!media.paused), String(Math.floor(media.currentTime || 0)));
      }
    } catch(e) {}
  }

  function addToQueue(info) {
    if (!info.title || !info.artist) return;
    var queue = getQueue();
    if (queue.length > 0 && queue[0].title === info.title && queue[0].artist === info.artist) {
      if (info.thumb && !queue[0].thumb) {
        queue[0].thumb = info.thumb;
        saveQueue(queue);
      }
      sendToAndroid(info);
      return;
    }
    queue.unshift({ title: info.title, artist: info.artist, thumb: info.thumb || '', played: Date.now() });
    if (queue.length > MAX_QUEUE_SIZE) queue = queue.slice(0, MAX_QUEUE_SIZE);
    saveQueue(queue);
    sendToAndroid(info);
  }

  function checkForNewSong() {
    try {
      var info = getCurrentSongInfo();
      var songKey = info.title + '|' + info.artist;
      if (songKey && info.title && info.artist) {
        if (songKey !== lastSong) {
          lastSong = songKey;
          addToQueue(info);
        } else {
          sendToAndroid(info);
        }
      }
      sendProgress();
    } catch(e) {}
  }

  setInterval(checkForNewSong, 1500);
  document.addEventListener('play', function() { setTimeout(checkForNewSong, 250); }, true);
  document.addEventListener('pause', function() { setTimeout(checkForNewSong, 250); }, true);
  document.addEventListener('timeupdate', function() { checkForNewSong(); }, true);
  setTimeout(checkForNewSong, 500);
  setTimeout(checkForNewSong, 2000);
  setTimeout(checkForNewSong, 5000);
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
    btn.style.cssText = 'position:fixed;right:14px;bottom:116px;z-index:999999;width:38px;height:38px;border-radius:13px;border:1px solid rgba(57,255,20,.55);background:rgba(45,20,80,.82);color:#39ff14;font-size:20px;display:flex;align-items:center;justify-content:center;box-shadow:0 0 16px rgba(57,255,20,.35);backdrop-filter:blur(10px);padding:0;margin:0;cursor:pointer;touch-action:manipulation;';
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
      window.location.href = 'https://korndogrecords.com/korndog-spinning-generator.html?' + params.toString();
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

  if (!window._kdSmartKeepAliveInit) {
    window._kdSmartKeepAliveInit = true;
    window._kdUserPaused = false;
    window._kdShouldBePlaying = false;
    window._kdLastMediaTime = 0;
    window._kdLastMediaCheck = Date.now();
    window._kdRecovering = false;

    function getMedia() {
      return document.querySelector('video, audio');
    }

    function safePlay(media) {
      try {
        if (!media || window._kdUserPaused) return;
        var p = media.play();
        if (p && p.catch) p.catch(function(){});
      } catch(e) {}
    }

    function markPlaying() {
      window._kdUserPaused = false;
      window._kdShouldBePlaying = true;
    }

    function markPaused() {
      var media = getMedia();
      if (media && media.currentTime > 0 && !media.ended) {
        window._kdUserPaused = true;
        window._kdShouldBePlaying = false;
      }
    }

    document.addEventListener('play', function(e) {
      if (e.target && (e.target.tagName === 'VIDEO' || e.target.tagName === 'AUDIO')) {
        markPlaying();
      }
    }, true);

    document.addEventListener('pause', function(e) {
      if (e.target && (e.target.tagName === 'VIDEO' || e.target.tagName === 'AUDIO')) {
        markPaused();
      }
    }, true);

    document.addEventListener('canplay', function(e) {
      var media = e.target;
      if (media && (media.tagName === 'VIDEO' || media.tagName === 'AUDIO') && window._kdShouldBePlaying && !window._kdUserPaused) {
        safePlay(media);
      }
    }, true);

    setInterval(function() {
      try {
        var media = getMedia();
        if (!media) return;
        var now = Date.now();
        var current = media.currentTime || 0;
        var paused = media.paused;
        var ended = media.ended;
        var ready = media.readyState || 0;
        if (!paused && !ended) {
          window._kdShouldBePlaying = true;
          window._kdUserPaused = false;
        }
        var timeMoved = Math.abs(current - window._kdLastMediaTime) > 0.15;
        var stuck = window._kdShouldBePlaying && !window._kdUserPaused && !ended && !timeMoved && now - window._kdLastMediaCheck > 3500;
        var buffering = window._kdShouldBePlaying && !window._kdUserPaused && !ended && ready < 3;
        if ((stuck || buffering || paused) && window._kdShouldBePlaying && !window._kdUserPaused) {
          if (!window._kdRecovering) {
            window._kdRecovering = true;
            try {
              if (media.networkState === 3 || ready === 0) {
                media.load();
              }
            } catch(e) {}
            safePlay(media);
            setTimeout(function() {
              window._kdRecovering = false;
            }, 2500);
          }
        }
        window._kdLastMediaTime = current;
        window._kdLastMediaCheck = now;
      } catch(e) {}
    }, 2500);
  }
})();
""".trimIndent()

val KORNDOG_SYNCED_LYRICS_SCRIPT = """
(function() {
  if (window._kdLyricsInit) return;
  window._kdLyricsInit = true;

  function injectStyle() {
    var old = document.getElementById('korndog-lyrics-highlight-style');
    if (old) old.remove();
    var style = document.createElement('style');
    style.id = 'korndog-lyrics-highlight-style';
    style.textContent = `
      .korndog-lyric-line {
        display: inline-block !important;
        color: #d8ffd0 !important;
        transition: color .2s ease, text-shadow .2s ease, transform .2s ease !important;
      }
      .korndog-lyric-active {
        color: #39ff14 !important;
        font-weight: 900 !important;
        text-shadow: 0 0 12px rgba(57,255,20,.95), 0 0 24px rgba(57,255,20,.55) !important;
        transform: scale(1.025) !important;
      }
    `;
    document.head.appendChild(style);
  }

  injectStyle();
  setInterval(injectStyle, 3000);

  function visible(el) {
    if (!el) return false;
    var r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  }

  function findLyricsContainer() {
    var selectors = [
      'ytmusic-description-shelf-renderer',
      'ytmusic-player-section-list-renderer',
      'ytmusic-player-page [role="tabpanel"]',
      '.lyrics-wrapper',
      '.lyrics'
    ];
    for (var i = 0; i < selectors.length; i++) {
      var nodes = document.querySelectorAll(selectors[i]);
      for (var j = 0; j < nodes.length; j++) {
        var el = nodes[j];
        var text = (el.innerText || '').trim();
        if (visible(el) && text.length > 60) return el;
      }
    }
    return null;
  }

  function wrapLines(container) {
    if (!container || container._kdLyricsWrapped) return;
    container._kdLyricsWrapped = true;
    var walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT, null);
    var nodes = [];
    var node;
    while ((node = walker.nextNode())) {
      var text = (node.textContent || '').trim();
      if (text.length > 2 && text.length < 220 && node.parentElement && visible(node.parentElement)) {
        nodes.push(node);
      }
    }
    nodes.forEach(function(textNode, index) {
      try {
        if (textNode.parentElement && textNode.parentElement.classList.contains('korndog-lyric-line')) return;
        var span = document.createElement('span');
        span.className = 'korndog-lyric-line';
        span.setAttribute('data-korndog-lyric-index', String(index));
        textNode.parentNode.insertBefore(span, textNode);
        span.appendChild(textNode);
      } catch(e) {}
    });
  }

  function highlightClosestLine() {
    var lines = Array.from(document.querySelectorAll('.korndog-lyric-line'));
    if (!lines.length) return;
    var targetY = window.innerHeight * 0.42;
    var best = null;
    var bestDistance = Infinity;
    lines.forEach(function(line) {
      if (!visible(line)) return;
      var r = line.getBoundingClientRect();
      var d = Math.abs(r.top - targetY);
      if (d < bestDistance) {
        bestDistance = d;
        best = line;
      }
    });
    lines.forEach(function(line) {
      line.classList.remove('korndog-lyric-active');
    });
    if (best) best.classList.add('korndog-lyric-active');
  }

  setInterval(function() {
    try {
      var container = findLyricsContainer();
      if (container) {
        wrapLines(container);
        highlightClosestLine();
      }
    } catch(e) {}
  }, 500);
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
      cacheMode = WebSettings.LOAD_DEFAULT
      loadsImagesAutomatically = true
      blockNetworkImage = false
      blockNetworkLoads = false
      databaseEnabled = true
      allowFileAccess = true
      allowContentAccess = true
    }
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
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
  private var powerManager: PowerManager? = null
  private var wakeLock: PowerManager.WakeLock? = null

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
          Log.d(TAG, "Page finished - injecting scripts")
          evaluateJavascript(KORNDOG_CAST_SCRIPT, null)
          evaluateJavascript(KORNDOG_QUEUE_TRACKER_SCRIPT, null)
          evaluateJavascript(KORNDOG_SYNCED_LYRICS_SCRIPT, null)
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
    initWakeLock()
    val activity = currentActivity
    activity?.registerForContextMenu(webView)
    webView.addJavascriptInterface(NouJsInterface(context, this), "NouTubeI")
    ViewCompat.setOnApplyWindowInsetsListener(webView) { _, _ -> WindowInsetsCompat.CONSUMED }
  }

  private fun initWakeLock() {
    powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager?
    wakeLock = powerManager?.newWakeLock(
      PowerManager.PARTIAL_WAKE_LOCK,
      "NouTube::PlaybackWakeLock"
    )
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
        Log.d(TAG, "Service connected and initialized")
      }

      override fun onServiceDisconnected(name: ComponentName) {}
    }

    val intent = Intent(activity, NouService::class.java)
    androidx.core.content.ContextCompat.startForegroundService(activity, intent)
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
      @Suppress("DEPRECATION")
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
    if (wakeLock != null && wakeLock!!.isHeld) {
      wakeLock!!.release()
    }
  }
}
