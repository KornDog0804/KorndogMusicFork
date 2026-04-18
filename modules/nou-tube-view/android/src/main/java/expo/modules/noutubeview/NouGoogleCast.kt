package expo.modules.noutubeview

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

private const val TAG = "NouGoogleCast"

class NouGoogleCast(private val context: Context) {

  private var castContext: CastContext? = null
  private var activeSession: CastSession? = null

  fun init() {
    try {
      castContext = CastContext.getSharedInstance(context)
      Log.d(TAG, "CastContext initialized")
    } catch (e: Exception) {
      Log.e(TAG, "CastContext init failed: ${e.message}", e)
    }
  }

  suspend fun discoverDevices(): List<Map<String, String>> = withContext(Dispatchers.Main) {
    val cc = castContext ?: return@withContext emptyList()

    val selector = MediaRouteSelector.Builder()
      .addControlCategory(
        CastMediaControlIntent.categoryForCast(
          CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
        )
      )
      .build()

    val router = MediaRouter.getInstance(context)
    router.routes
      .filter { route -> route.matchesSelector(selector) && !route.isDefault && !route.isBluetooth }
      .map { route ->
        mapOf(
          "name" to route.name,
          "id" to route.id,
          "type" to "googlecast"
        )
      }
  }

  suspend fun castUrl(routeId: String, streamUrl: String, title: String): Boolean =
    withContext(Dispatchers.Main) {
      val cc = castContext ?: run {
        Log.e(TAG, "CastContext not initialized")
        return@withContext false
      }

      try {
        val router = MediaRouter.getInstance(context)
        val route = router.routes.find { it.id == routeId } ?: run {
          Log.e(TAG, "Route not found: $routeId")
          return@withContext false
        }

        router.selectRoute(route)

        val session = awaitCastSession(cc, 10_000) ?: run {
          Log.e(TAG, "Cast session did not connect")
          return@withContext false
        }

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

        val remoteClient = session.remoteMediaClient ?: run {
          Log.e(TAG, "No remoteMediaClient on cast session")
          return@withContext false
        }

        val success = suspendCancellableCoroutine<Boolean> { cont ->
          remoteClient.load(loadRequest).setResultCallback { result ->
            if (cont.isActive) cont.resume(result.status.isSuccess)
          }
        }

        if (success) {
          activeSession = session
        }

        success
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

  private suspend fun awaitCastSession(cc: CastContext, timeoutMs: Long): CastSession? =
    withContext(Dispatchers.Main) {
      cc.sessionManager.currentCastSession?.let { return@withContext it }

      suspendCancellableCoroutine<CastSession?> { cont ->
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

        Handler(Looper.getMainLooper()).postDelayed({
          cc.sessionManager.removeSessionManagerListener(listener, CastSession::class.java)
          if (cont.isActive) cont.resume(null)
        }, timeoutMs)
      }
    }
}
