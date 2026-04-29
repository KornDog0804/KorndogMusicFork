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
tp-yt-paper-slider #sliderKnob { display: none !important; }
tp-yt-paper-slider #sliderKnob #sliderKnobInner { display: none !important; }
tp-yt-paper-slider #progressContainer #primaryProgress { background: #39ff14 !important; }
tp-yt-paper-slider #progressContainer { height: 3px !important; }
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
""".trimIndent().replace("\n", " ").replace("'", "\\'")

val KORNDOG_THEME_SCRIPT = """
(function() {
  var existing = document.getElementById('korndog-theme');
  if (existing) existing.remove();
  var s = document.createElement('style');
  s.id = 'korndog-theme';
  s.textContent = '${KORNDOG_THEME_CSS}';
  document.head.appendChild(s);
})();
""".trimIndent()

val KORNDOG_CAST_SCRIPT = """
(function() {
  // Floating Cast + TV Generator Button UI
  function initCastAndTVButtons() {
    if (!document.body) {
      setTimeout(initCastAndTVButtons, 500);
      return;
    }

    if (document.getElementById('korndog-cast-tv-buttons')) return;

    // Container for both buttons
    var container = document.createElement('div');
    container.id = 'korndog-cast-tv-buttons';
    container.style.cssText =
      'position:fixed;' +
      'right:12px;' +
      'top:86px;' +
      'z-index:999999;' +
      'display:flex;' +
      'flex-direction:column;' +
      'gap:8px;';
    document.body.appendChild(container);

    // Cast Button
    var castBtn = document.createElement('button');
    castBtn.id = 'korndog-cast-btn';
    castBtn.textContent = '📡';
    castBtn.setAttribute('aria-label', 'Cast to device');
    castBtn.style.cssText =
      'width:42px;' +
      'height:42px;' +
      'border-radius:14px;' +
      'border:1px solid rgba(57,255,20,0.45);' +
      'background:rgba(45,20,80,0.72);' +
      'color:#39ff14;' +
      'font-size:22px;' +
      'line-height:1;' +
      'display:flex;' +
      'align-items:center;' +
      'justify-content:center;' +
      'box-shadow:0 0 14px rgba(57,255,20,0.22);' +
      'backdrop-filter:blur(10px);' +
      'opacity:0.92;' +
      'padding:0;' +
      'margin:0;' +
      'cursor:pointer;' +
      'transition:all 0.2s;';

    castBtn.addEventListener('mouseenter', function() {
      this.style.opacity = '1';
      this.style.transform = 'scale(1.08)';
    });
    castBtn.addEventListener('mouseleave', function() {
      this.style.opacity = '0.92';
      this.style.transform = 'scale(1)';
    });

    container.appendChild(castBtn);

    // TV/Generator Button
    var tvBtn = document.createElement('button');
    tvBtn.id = 'korndog-tv-generator-btn';
    tvBtn.textContent = '📺';
    tvBtn.setAttribute('aria-label', 'Open KornDog Generator');
    tvBtn.style.cssText =
      'width:42px;' +
      'height:42px;' +
      'border-radius:14px;' +
      'border:1px solid rgba(57,255,20,0.45);' +
      'background:rgba(45,20,80,0.72);' +
      'color:#39ff14;' +
      'font-size:22px;' +
      'line-height:1;' +
      'display:flex;' +
      'align-items:center;' +
      'justify-content:center;' +
      'box-shadow:0 0 14px rgba(57,255,20,0.22);' +
      'backdrop-filter:blur(10px);' +
      'opacity:0.92;' +
      'padding:0;' +
      'margin:0;' +
      'cursor:pointer;' +
      'transition:all 0.2s;';

    tvBtn.addEventListener('mouseenter', function() {
      this.style.opacity = '1';
      this.style.transform = 'scale(1.08)';
    });
    tvBtn.addEventListener('mouseleave', function() {
      this.style.opacity = '0.92';
      this.style.transform = 'scale(1)';
    });

    container.appendChild(tvBtn);

    // Overlay panel for cast/TV options
    var overlay = document.createElement('div');
    overlay.id = 'korndog-cast-tv-overlay';
    overlay.style.cssText =
      'display:none;' +
      'position:fixed;' +
      'inset:0;' +
      'z-index:1000000;' +
      'background:rgba(18,0,35,0.92);' +
      'backdrop-filter:blur(6px);' +
      'align-items:center;' +
      'justify-content:center;' +
      'padding:22px;' +
      'box-sizing:border-box;' +
      'font-family:sans-serif;';
    document.body.appendChild(overlay);

    var panel = document.createElement('div');
    panel.style.cssText =
      'width:100%;' +
      'max-width:360px;' +
      'border-radius:22px;' +
      'background:rgba(45,20,80,0.96);' +
      'border:1px solid rgba(57,255,20,0.35);' +
      'box-shadow:0 0 28px rgba(57,255,20,0.18);' +
      'padding:22px;' +
      'box-sizing:border-box;' +
      'text-align:center;';
    overlay.appendChild(panel);

    var title = document.createElement('div');
    title.id = 'korndog-cast-tv-title';
    title.textContent = 'Cast / Share to KornDog';
    title.style.cssText =
      'color:#39ff14;' +
      'font-size:22px;' +
      'font-weight:800;' +
      'margin-bottom:12px;';
    panel.appendChild(title);

    var status = document.createElement('div');
    status.id = 'korndog-cast-tv-status';
    status.textContent = 'Select an option below';
    status.style.cssText =
      'color:#c8b8dd;' +
      'font-size:15px;' +
      'line-height:1.35;' +
      'margin-bottom:18px;';
    panel.appendChild(status);

    function makeAction(text) {
      var el = document.createElement('button');
      el.textContent = text;
      el.style.cssText =
        'width:100%;' +
        'border:none;' +
        'border-radius:14px;' +
        'background:#35165f;' +
        'color:#fff;' +
        'font-size:17px;' +
        'font-weight:700;' +
        'padding:15px 14px;' +
        'margin:7px 0;' +
        'box-shadow:inset 0 0 0 1px rgba(57,255,20,0.16);' +
        'cursor:pointer;' +
        'transition:all 0.2s;';
      el.addEventListener('mouseenter', function() {
        this.style.background = '#4a2575';
      });
      el.addEventListener('mouseleave', function() {
        this.style.background = '#35165f';
      });
      panel.appendChild(el);
      return el;
    }

    var nativeBtn = makeAction('📡 Native Cast / YouTube Music');
    var generatorBtn = makeAction('📺 Open KornDog Generator');
    var copyBtn = makeAction('📋 Copy Current URL');

    var closeBtn = document.createElement('button');
    closeBtn.textContent = 'Close';
    closeBtn.style.cssText =
      'margin-top:16px;' +
      'background:transparent;' +
      'border:1px solid #ff4b6a;' +
      'color:#ff4b6a;' +
      'border-radius:14px;' +
      'font-size:16px;' +
      'font-weight:700;' +
      'padding:12px 24px;' +
      'cursor:pointer;' +
      'transition:all 0.2s;';
    closeBtn.addEventListener('mouseenter', function() {
      this.style.background = 'rgba(255,75,106,0.1)';
    });
    closeBtn.addEventListener('mouseleave', function() {
      this.style.background = 'transparent';
    });
    panel.appendChild(closeBtn);

    // Button handlers
    castBtn.addEventListener('click', function(e) {
      e.preventDefault();
      e.stopPropagation();
      overlay.style.display = 'flex';
      title.textContent = '📡 Cast Device';
      status.textContent = 'Send audio to YouTube Music or native cast device.';
    });

    tvBtn.addEventListener('click', function(e) {
      e.preventDefault();
      e.stopPropagation();
      kdOpenGeneratorDirect();
    });

    nativeBtn.addEventListener('click', function() {
      status.textContent = 'Opening YouTube Music...';
      if (window.NouTubeI && window.NouTubeI.openInYouTubeApp) {
        window.NouTubeI.openInYouTubeApp();
      }
      setTimeout(function() {
        overlay.style.display = 'none';
      }, 800);
    });

    generatorBtn.addEventListener('click', function() {
      status.textContent = 'Opening KornDog Generator...';
      setTimeout(kdOpenGeneratorDirect, 400);
      setTimeout(function() {
        overlay.style.display = 'none';
      }, 800);
    });

    copyBtn.addEventListener('click', function() {
      if (window.NouTubeI && window.NouTubeI.copyCurrentUrl) {
        window.NouTubeI.copyCurrentUrl();
        status.textContent = '✓ URL copied';
      }
    });

    closeBtn.addEventListener('click', function() {
      overlay.style.display = 'none';
    });

    overlay.addEventListener('click', function(e) {
      if (e.target === overlay) overlay.style.display = 'none';
    });

    window.kdOpenOverlay = function() {
      overlay.style.display = 'flex';
    };

    window.kdSetStatus = function(msg) {
      status.textContent = msg;
      overlay.style.display = 'flex';
    };
  }

  // KornDog Generator — captures everything
  function kdOpenGeneratorDirect() {
    try {
      function kdText(selectors) {
        for (var i = 0; i < selectors.length; i++) {
          var el = document.querySelector(selectors[i]);
          if (el && el.textContent && el.textContent.trim()) return el.textContent.trim();
        }
        return '';
      }

      function kdFindThumb() {
        var imgs = Array.from(document.querySelectorAll('img[src*="ytimg"], img[src*="googleusercontent"]'))
          .filter(function(img) {
            var r = img.getBoundingClientRect();
            return r.width >= 80 && r.height >= 80 && img.src;
          })
          .sort(function(a, b) {
            var ar = a.getBoundingClientRect();
            var br = b.getBoundingClientRect();
            return (br.width * br.height) - (ar.width * ar.height);
          });

        if (imgs.length) return imgs[0].src;

        var og = document.querySelector('meta[property="og:image"], meta[name="twitter:image"]');
        if (og && og.content) return og.content;

        return '';
      }

      var title = kdText([
        'ytmusic-player-page .title',
        '.content-info-wrapper .title',
        'ytmusic-player-bar .title',
        '.title.ytmusic-player-bar'
      ]);

      var artist = kdText([
        'ytmusic-player-page .subtitle',
        '.content-info-wrapper .subtitle',
        'ytmusic-player-bar .subtitle',
        '.byline.ytmusic-player-bar'
      ]);

      var thumb = kdFindThumb();

      var params = new URLSearchParams();
      if (artist) params.set('artist', artist);
      if (title) params.set('album', title);
      if (thumb) params.set('thumb', thumb);
      params.set('from', 'ghostkernel');

      window.location.href = 'https://korndogrecords.com/korndog-spinning-generator.html?' + params.toString();
    } catch(e) {
      console.error('[KornDog] Generator error:', e);
    }
  }

  setTimeout(initCastAndTVButtons, 1000);

  // Audio normalization — 2.0x gain for KornDog signature sound
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

      var normObserver = new MutationObserver(function() {
        hookAll();
      });

      normObserver.observe(document.documentElement, { childList: true, subtree: true });
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

  // ── PREMIUM-LIKE BUFFER STRATEGY WITH AUTO-PLAY FIX ─────────────────────
  if (!window._kdPremiumBufferInit) {
    window._kdPremiumBufferInit = true;

    var currentTime = 0;
    var duration = 0;
    var bufferedEnd = 0;
    var bufferThreshold = 10;
    var isOnline = navigator.onLine;
    var playbackAttempted = false;
    var forcePauseAllowed = false;
    var currentTrackId = '';

    function updateBufferState() {
      try {
        var video = document.querySelector('video');
        if (!video) return;
        currentTime = video.currentTime || 0;
        duration = video.duration || 0;
        if (video.buffered && video.buffered.length > 0) {
          bufferedEnd = video.buffered.end(video.buffered.length - 1);
        }
      } catch (e) {}
    }

    function getBufferMargin() {
      return bufferedEnd - currentTime;
    }

    function hasEnoughBuffer() {
      return getBufferMargin() >= bufferThreshold;
    }

    function getCurrentTrackId() {
      var el = document.querySelector('ytmusic-player-bar .title, .content-info-wrapper .title');
      if (el) return el.textContent;
      return '';
    }

    // AUTO-PLAY on canplay (CRITICAL FIX FOR FIRST SONG)
    document.addEventListener('canplay', function(e) {
      if (e.target && (e.target.tagName === 'VIDEO' || e.target.tagName === 'AUDIO')) {
        if (!playbackAttempted) {
          console.log('[KornDog] canplay → auto-playing');
          e.target.play().then(function() {
            playbackAttempted = true;
            console.log('[KornDog] ▶️ Auto-play succeeded');
            if (window._kdAudioCtx && window._kdAudioCtx.state === 'suspended') {
              window._kdAudioCtx.resume();
            }
          }).catch(function(err) {
            console.log('[KornDog] Auto-play blocked:', err);
          });
        }
      }
    }, true);

    // RESET on loadstart (track change)
    document.addEventListener('loadstart', function(e) {
      if (e.target && (e.target.tagName === 'VIDEO' || e.target.tagName === 'AUDIO')) {
        var newTrackId = getCurrentTrackId();
        if (newTrackId && newTrackId !== currentTrackId) {
          console.log('[KornDog] 🔄 Track transition: ' + newTrackId);
          currentTrackId = newTrackId;
          playbackAttempted = false;
          forcePauseAllowed = false;
        }
      }
    }, true);

    // RESET on emptied (between tracks)
    document.addEventListener('emptied', function(e) {
      if (e.target && (e.target.tagName === 'VIDEO' || e.target.tagName === 'AUDIO')) {
        console.log('[KornDog] 🔄 Media emptied, resetting for next track');
        playbackAttempted = false;
        forcePauseAllowed = false;
      }
    }, true);

    // Allow manual PLAY
    document.addEventListener('play', function(e) {
      playbackAttempted = true;
      console.log('[KornDog] ▶️ Playback started');
      if (window._kdAudioCtx && window._kdAudioCtx.state === 'suspended') {
        window._kdAudioCtx.resume();
      }
    }, true);

    // Smart PAUSE blocking
    document.addEventListener('pause', function(e) {
      if (!playbackAttempted) return;
      updateBufferState();
      
      var hasBuffer = hasEnoughBuffer();
      var shouldPreventPause = !isOnline && hasBuffer && !forcePauseAllowed;

      if (shouldPreventPause) {
        console.log('[KornDog] 🔒 Buffer safe, blocking pause');
        e.preventDefault();
        if (e.stopImmediatePropagation) e.stopImmediatePropagation();
        var video = e.target;
        if (video && video.paused) {
          video.play().catch(function() {});
        }
        return false;
      }
    }, true);

    // Online/offline handlers
    window.addEventListener('online', function() {
      isOnline = true;
      var video = document.querySelector('video');
      if (video && video.paused && playbackAttempted) {
        video.play().catch(function() {});
      }
    });

    window.addEventListener('offline', function() {
      isOnline = false;
      updateBufferState();
      if (hasEnoughBuffer() && playbackAttempted) {
        var video = document.querySelector('video');
        if (video && video.paused) {
          video.play().catch(function() {});
        }
      }
    });

    // Monitor critical buffer
    setInterval(function() {
      if (!playbackAttempted) return;
      updateBufferState();
      if (!isOnline && getBufferMargin() < 3) {
        forcePauseAllowed = true;
        setTimeout(function() { forcePauseAllowed = false; }, 1000);
      }
    }, 500);

    // Keep audio context alive
    var AudioCtx = window.AudioContext || window.webkitAudioContext;
    if (AudioCtx && !window._kdAudioCtxResilience) {
      window._kdAudioCtxResilience = true;
      var ctx = new AudioCtx();
      document.addEventListener('play', function() {
        if (ctx.state === 'suspended') ctx.resume().catch(function() {});
      }, true);
    }

    // Prevent screen lock
    if (window.NouTubeI && window.NouTubeI.keepScreenOn) {
      document.addEventListener('play', function() {
        window.NouTubeI.keepScreenOn(true);
      }, true);
      document.addEventListener('pause', function() {
        window.NouTubeI.keepScreenOn(false);
      }, true);
    }
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
          evaluateJavascript(KORNDOG_THEME_SCRIPT, null)
          evaluateJavascript(scriptOnStart, null)
        }

        override fun onPageFinished(view: WebView, url: String) {
          evaluateJavascript(KORNDOG_CAST_SCRIPT, null)
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
