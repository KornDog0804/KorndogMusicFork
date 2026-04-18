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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "NouGoogleCast"

class NouGoogleCast(private val context: Context) {

  private var castContext: CastContext? = null
  private var activeSession: CastSession? = null

  fun init() {
    try {
      castContext = CastContext.getSharedInstance(context)
      Log.d(TAG, "CastContext initialized")
    } catch (e: Exception) {
      Log.e(TAG, "CastContext init failed: ${e.message}")
    }
  }

  suspend fun discoverDevices(): List<Map<String, String>> = withContext(Dispatchers.Main) {
    castContext ?: return@withContext emptyList<Map<String, String>>()

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
      .map { route -> mapOf("name" to route.name, "id" to route.id, "type" to "googlecast") }
  }

  suspend fun castUrl(routeId: String, streamUrl: String, title: String): Boolean =
    withContext(Dispatchers.Main) {
      val cc = castContext ?: return@withContext false
      try {
        val router = MediaRouter.getInstance(context)
        val route = router.routes.find { it.id == routeId } ?: return@withContext false
        router.selectRoute(route)

        val session = awaitCastSession(cc, 10_000) ?: return@withContext false

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

        val remoteClient = session.remoteMediaClient ?: return@withContext false
        val deferred = CompletableDeferred<Boolean>()
        remoteClient.load(loadRequest).setResultCallback { r -> deferred.complete(r.status.isSuccess) }
        val result = deferred.await()

        if (result) activeSession = session
        result
      } catch (e: Exception) {
        Log.e(TAG, "Google Cast failed: ${e.message}", e)
        false
      }
    }

  fun pause() { activeSession?.remoteMediaClient?.pause() }
  fun stop() { activeSession?.remoteMediaClient?.stop(); activeSession = null }
  fun isConnected(): Boolean = activeSession?.isConnected == true

  private suspend fun awaitCastSession(cc: CastContext, timeoutMs: Long): CastSession? =
    withContext(Dispatchers.Main) {
      cc.sessionManager.currentCastSession?.let { return@withContext it }

      val deferred = CompletableDeferred<CastSession?>()

      val listener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(s: CastSession, id: String) {
          cc.sessionManager.removeSessionManagerListener(this, CastSession::class.java)
          deferred.complete(s)
        }
        override fun onSessionStartFailed(s: CastSession, e: Int) {
          cc.sessionManager.removeSessionManagerListener(this, CastSession::class.java)
          deferred.complete(null)
        }
        override fun onSessionEnded(s: CastSession, e: Int) {}
        override fun onSessionResumed(s: CastSession, w: Boolean) {}
        override fun onSessionResumeFailed(s: CastSession, e: Int) {}
        override fun onSessionSuspended(s: CastSession, r: Int) {}
        override fun onSessionEnding(s: CastSession) {}
        override fun onSessionResuming(s: CastSession, id: String) {}
        override fun onSessionStarting(s: CastSession) {}
      }

      cc.sessionManager.addSessionManagerListener(listener, CastSession::class.java)
      Handler(Looper.getMainLooper()).postDelayed({
        cc.sessionManager.removeSessionManagerListener(listener, CastSession::class.java)
        deferred.complete(null)
      }, timeoutMs)

      deferred.await()
    }
}
