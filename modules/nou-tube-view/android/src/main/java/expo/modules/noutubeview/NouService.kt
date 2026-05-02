package expo.modules.noutubeview

import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaSessionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import kotlinx.coroutines.*
import java.net.URL

class NouService : Service() {

  private var webView: NouWebView? = null
  private var activity: Activity? = null
  private val binder = NouBinder()
  private val scope = CoroutineScope(Dispatchers.Main + Job())

  private lateinit var mediaSession: MediaSessionCompat

  private var currentTitle = "NouTube"
  private var currentArtist = "Playing..."
  private var isPlaying = false
  private var currentPosition: Long = 0L
  private var currentArtwork: Bitmap? = null

  private var notificationManager: NotificationManager? = null

  private var sleepTimerDeadline: Long = 0L
  private var sleepTimerJob: Job? = null

  companion object {
    private const val TAG = "NouService"
    private const val CHANNEL_ID = "noutube_playback"
    private const val NOTIFICATION_ID = 1

    private const val ACTION_PLAY = "PLAY"
    private const val ACTION_PAUSE = "PAUSE"
    private const val ACTION_NEXT = "NEXT"
    private const val ACTION_PREV = "PREV"
  }

  inner class NouBinder : Binder() {
    fun getService(): NouService = this@NouService
  }

  override fun onBind(intent: Intent): IBinder = binder

  override fun onCreate() {
    super.onCreate()
    notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    createChannel()
    initMediaSession()
    startForeground(NOTIFICATION_ID, buildNotification())
  }

  fun initialize(webView: NouWebView, activity: Activity) {
    this.webView = webView
    this.activity = activity
    updateAll()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_PLAY -> play()
      ACTION_PAUSE -> pause()
      ACTION_NEXT -> next()
      ACTION_PREV -> prev()
    }
    return START_STICKY
  }

  private fun initMediaSession() {
    mediaSession = MediaSessionCompat(this, "NouTube")

    mediaSession.setCallback(object : MediaSessionCompat.Callback() {
      override fun onPlay() = play()
      override fun onPause() = pause()
      override fun onSkipToNext() = next()
      override fun onSkipToPrevious() = prev()
    })

    mediaSession.isActive = true
  }

  private fun play() {
    isPlaying = true
    runJS("document.querySelector('video,audio')?.play();")
    updateAll()
  }

  private fun pause() {
    isPlaying = false
    runJS("document.querySelector('video,audio')?.pause();")
    updateAll()
  }

  private fun next() {
    runJS("document.querySelector('[aria-label*=Next]')?.click();")
    updateAll()
  }

  private fun prev() {
    runJS("document.querySelector('[aria-label*=Previous]')?.click();")
    updateAll()
  }

  private fun runJS(js: String) {
    val wv = webView ?: return
    activity?.runOnUiThread {
      try { wv.evaluateJavascript(js, null) } catch (e: Exception) {}
    }
  }

  private fun createChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        "NouTube Playback",
        NotificationManager.IMPORTANCE_LOW
      )
      notificationManager?.createNotificationChannel(channel)
    }
  }

  private fun buildNotification(): Notification {
    val playPause = if (isPlaying) {
      NotificationCompat.Action(
        android.R.drawable.ic_media_pause,
        "Pause",
        pending(ACTION_PAUSE)
      )
    } else {
      NotificationCompat.Action(
        android.R.drawable.ic_media_play,
        "Play",
        pending(ACTION_PLAY)
      )
    }

    val builder = NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_media_play)
      .setContentTitle(currentTitle)
      .setContentText(currentArtist)
      .setOnlyAlertOnce(true)
      .setOngoing(isPlaying)
      .addAction(android.R.drawable.ic_media_previous, "Prev", pending(ACTION_PREV))
      .addAction(playPause)
      .addAction(android.R.drawable.ic_media_next, "Next", pending(ACTION_NEXT))
      .setStyle(
        MediaStyle()
          .setMediaSession(mediaSession.sessionToken)
          .setShowActionsInCompactView(0,1,2)
      )

    currentArtwork?.let { builder.setLargeIcon(it) }

    return builder.build()
  }

  private fun pending(action: String): PendingIntent {
    val intent = Intent(this, NouService::class.java).apply { this.action = action }
    return PendingIntent.getService(
      this,
      action.hashCode(),
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
  }

  private fun updateAll() {
    updateMetadata()
    updatePlayback()
    notificationManager?.notify(NOTIFICATION_ID, buildNotification())
  }

  private fun updateMetadata() {
    val metadata = MediaMetadataCompat.Builder()
      .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
      .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
      .apply {
        currentArtwork?.let {
          putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
        }
      }
      .build()

    mediaSession.setMetadata(metadata)
  }

  private fun updatePlayback() {
    val state = if (isPlaying)
      PlaybackStateCompat.STATE_PLAYING
    else
      PlaybackStateCompat.STATE_PAUSED

    val playbackState = PlaybackStateCompat.Builder()
      .setActions(
        PlaybackStateCompat.ACTION_PLAY or
        PlaybackStateCompat.ACTION_PAUSE or
        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
      )
      .setState(state, currentPosition, 1f)
      .build()

    mediaSession.setPlaybackState(playbackState)
  }

  fun notify(title: String, artist: String, seconds: Long, thumb: String) {
    currentTitle = title
    currentArtist = artist
    isPlaying = true

    scope.launch(Dispatchers.IO) {
      currentArtwork = try {
        BitmapFactory.decodeStream(URL(thumb).openStream())
      } catch (e: Exception) { null }

      withContext(Dispatchers.Main) {
        updateAll()
      }
    }
  }

  fun notifyProgress(playing: Boolean, pos: Long) {
    isPlaying = playing
    currentPosition = pos
    updateAll()
  }

  // ✅ REQUIRED FOR NouController
  fun setSleepTimerDeadline(deadline: Long) {
    sleepTimerDeadline = deadline
    sleepTimerJob?.cancel()

    if (deadline > 0) {
      sleepTimerJob = scope.launch {
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining > 0) delay(remaining)

        isPlaying = false
        runJS("document.querySelector('video,audio')?.pause();")
        updateAll()
        nouController.emitSleepTimerExpired()
      }
    }
  }

  fun clearSleepTimer() {
    sleepTimerDeadline = 0L
    sleepTimerJob?.cancel()
    sleepTimerJob = null
  }

  fun getSleepTimerRemainingMs(): Long {
    if (sleepTimerDeadline <= 0L) return 0L
    val remaining = sleepTimerDeadline - SystemClock.elapsedRealtime()
    return if (remaining > 0) remaining else 0L
  }

  fun exit() {
    clearSleepTimer()
  }

  override fun onDestroy() {
    mediaSession.release()
    scope.cancel()
    super.onDestroy()
  }
}
