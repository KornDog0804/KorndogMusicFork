package expo.modules.noutubeview

import android.content.Context
import android.util.Log
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

private const val TAG = "NouGoogleCast"

class NouGoogleCast(private val context: Context) {

  private var castContext: CastContext? = null
  private var activeSession: CastSession? = null

  /**
   * Initialize Cast context — must be called on main thread
   */
  fun init() {
    try {
      castContext = CastContext.getSharedInstance(context)
      Log.d(TAG, "CastContext initialized")
    } catch (e: Exception) {
      Log.e(TAG, "CastContext init failed (no Google Play Services?): ${e.message}")
    }
  }

  /**
   * Discover nearby Chromecast / Google TV devices via MediaRouter
   * Returns list of device maps with "name" and "type" = "googlecast"
   */
  suspend fun discoverDevices(): List<Map<String, String>> = withContext(Dispatchers.Main) {
    val cc = castContext ?: return@withContext emptyList()
    val router = androidx.mediarouter.media.MediaRouter.getInstance(context)
    val selector = androidx.mediarouter.media.MediaRouteSelector.Builder()
      .addControlCategory(com.google.android.gms.cast.framework.media.RemoteMediaClient.CATEGORY_CAST_REMOTE_PLAYBACK)
      .build()

    val routes = router.routes.filter { route ->
      route.matchesSelector(selector) && !route.isDefault && !route.isBluetooth
    }

    routes.map { route ->
      mapOf(
        "name" to route.name,
        "id"   to route.id,
        "type" to "googlecast"
      )
    }
  }

  /**
   * Cast a direct stream URL to a discovered Google Cast device by route ID
   */
  suspend fun castUrl(routeId: String, streamUrl: String, title: String): Boolean =
    withContext(Dispatchers.Main) {
      val cc = castContext ?: run {
        Log.e(TAG, "CastContext not initialized")
        return@withContext false
      }

      try {
        val router = androidx.mediarouter.media.MediaRouter.getInstance(context)
        val route = router.routes.find { it.id == routeId } ?: run {
          Log.e(TAG, "Route $routeId not found")
          return@withContext false
        }

        // Select the route (connects to the device)
        router.selectRoute(route)

        // Wait for session to connect (up to 10 seconds)
        val session = awaitCastSession(cc, timeoutMs = 10_000) ?: run {
          Log.e(TAG, "Session never connected")
          return@withContext false
        }

        // Build media info from the direct stream URL
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
          putString(MediaMetadata.KEY_TITLE, title)
        }
        val mediaInfo = MediaInfo.Builder(streamUrl)
          .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
          .setContentType("video/mp4")
          .setMetadata(metadata)
          .build()

        val loadRequest = MediaLoadRequestData.Builder()
          .setMediaInfo(mediaInfo)
          .setAutoplay(true)
          .build()

        // Load and play
        val remoteClient = session.remoteMediaClient ?: run {
          Log.e(TAG, "No RemoteMediaClient on session")
          return@withContext false
        }

        val result = suspendCancellableCoroutine<Boolean> { cont ->
          remoteClient.load(loadRequest).setResultCallback { result ->
            cont.resume(!result.status.isInterrupted && result.status.isSuccess)
          }
        }

        activeSession = session
        Log.d(TAG, "Cast load result: $result")
        result

      } catch (e: Exception) {
        Log.e(TAG, "Google Cast failed: ${e.message}", e)
        false
      }
    }

  fun pause() {
    activeSession?.remoteMediaClient?.pause()
  }

  fun stop() {
    activeSession?.remoteMediaClient?.stop()
    activeSession = null
  }

  fun isConnected(): Boolean = activeSession?.isConnected == true

  // ---- Private helpers ----

  private suspend fun awaitCastSession(cc: CastContext, timeoutMs: Long): CastSession? =
    withContext(Dispatchers.Main) {
      // Already have one?
      cc.sessionManager.currentCastSession?.let { return@withContext it }

      suspendCancellableCoroutine { cont ->
        val listener = object : SessionManagerListener<CastSession> {
          override fun onSessionStarted(session: CastSession, sessionId: String) {
            cc.sessionManager.removeSessionManagerListener(this, CastSession::class.java)
            if (cont.isActive) cont.resume(session)
          }
          override fun onSessionStartFailed(session: CastSession, error: Int) {
            cc.sessionManager.removeSessionManagerListener(this, CastSession::class.java)
            if (cont.isActive) cont.resume(null)
          }
          override fun onSessionEnded(session: CastSession, error: Int) {}
          override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {}
          override fun onSessionResumeFailed(session: CastSession, error: Int) {}
          override fun onSessionSuspended(session: CastSession, reason: Int) {}
          override fun onSessionEnding(session: CastSession) {}
          override fun onSessionResuming(session: CastSession, sessionId: String) {}
          override fun onSessionStarting(session: CastSession) {}
        }
        cc.sessionManager.addSessionManagerListener(listener, CastSession::class.java)

        // Timeout fallback
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
          cc.sessionManager.removeSessionManagerListener(listener, CastSession::class.java)
          if (cont.isActive) cont.resume(null)
        }, timeoutMs)
      }
    }
