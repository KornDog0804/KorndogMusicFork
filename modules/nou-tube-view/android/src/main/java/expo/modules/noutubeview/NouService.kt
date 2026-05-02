package expo.modules.noutubeview

import android.app.*
import android.content.*
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle

import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat

import kotlinx.coroutines.*

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

  private var notificationManager: NotificationManager? = null
  private var audioManager: AudioManager? = null
  private var audioFocusRequest: AudioFocusRequest? = null

  companion object {
    private const val CHANNEL_ID = "noutube_playback"
    private const val NOTIF_ID = 1

    private const val ACTION_PLAY = "PLAY"
    private const val ACTION_PAUSE = "PAUSE"
    private const val ACTION_NEXT = "NEXT"
    private const val ACTION_PREV = "PREV"

    private const val TAG = "NouService"
  }

  inner class NouBinder : Binder() {
    fun getService(): NouService = this@NouService
  }

  override fun onBind(intent: Intent): IBinder = binder

  override fun onCreate() {
    super.onCreate()

    notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

    createChannel()
    initMediaSession()

    startForeground(NOTIF_ID, buildNotification())
    requestAudioFocus()

    Log.d(TAG, "Service created")
  }

  // ✅ REQUIRED FOR NouTubeView
  fun initialize(webView: NouWebView, activity: Activity) {
    this.webView = webView
    this.activity = activity
  }

  fun notify(title: String, author: String, seconds: Long, thumbnail: String) {
    currentTitle = if (title.isNotBlank()) title else "Now Playing"
    currentArtist = if (author.isNotBlank()) author else "NouTube"
    isPlaying = true
    updateAll()
  }

  fun notifyProgress(playing: Boolean, pos: Long) {
    isPlaying = playing
    currentPosition = pos
    updateAll()
  }

  fun exit() {
    try {
      mediaSession.release()
    } catch (_: Exception) {}
  }

  private fun initMediaSession() {
    mediaSession = MediaSessionCompat(this, "NouTube")

    mediaSession.setCallback(object : MediaSessionCompat.Callback() {
      override fun onPlay() {
        isPlaying = true
        runJs("document.querySelector('video,audio')?.play();")
        updateAll()
      }

      override fun onPause() {
        isPlaying = false
        runJs("document.querySelector('video,audio')?.pause();")
        updateAll()
      }

      override fun onSkipToNext() {
        runJs("document.querySelector('[aria-label*=Next]')?.click();")
      }

      override fun onSkipToPrevious() {
        runJs("document.querySelector('[aria-label*=Previous]')?.click();")
      }
    })

    mediaSession.isActive = true
    updateAll()
  }

  private fun runJs(js: String) {
    activity?.runOnUiThread {
      webView?.evaluateJavascript(js, null)
    }
  }

  private fun updateAll() {
    updateMetadata()
    updatePlayback()
    updateNotification()
  }

  private fun updateMetadata() {
    val meta = MediaMetadataCompat.Builder()
      .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
      .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
      .build()

    mediaSession.setMetadata(meta)
  }

  private fun updatePlayback() {
    val state = if (isPlaying)
      PlaybackStateCompat.STATE_PLAYING
    else
      PlaybackStateCompat.STATE_PAUSED

    val pb = PlaybackStateCompat.Builder()
      .setActions(
        PlaybackStateCompat.ACTION_PLAY or
        PlaybackStateCompat.ACTION_PAUSE or
        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
      )
      .setState(state, currentPosition, 1f)
      .build()

    mediaSession.setPlaybackState(pb)
  }

  private fun buildNotification(): Notification {
    val playPause = if (isPlaying)
      NotificationCompat.Action(
        android.R.drawable.ic_media_pause,
        "Pause",
        intent(ACTION_PAUSE)
      )
    else
      NotificationCompat.Action(
        android.R.drawable.ic_media_play,
        "Play",
        intent(ACTION_PLAY)
      )

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_media_play)
      .setContentTitle(currentTitle)
      .setContentText(currentArtist)
      .setOngoing(isPlaying)
      .addAction(android.R.drawable.ic_media_previous, "Prev", intent(ACTION_PREV))
      .addAction(playPause)
      .addAction(android.R.drawable.ic_media_next, "Next", intent(ACTION_NEXT))
      .setStyle(
        MediaStyle()
          .setMediaSession(mediaSession.sessionToken)
          .setShowActionsInCompactView(0, 1, 2)
      )
      .build()
  }

  private fun intent(action: String): PendingIntent {
    val i = Intent(this, NouService::class.java).apply { this.action = action }
    return PendingIntent.getService(
      this,
      action.hashCode(),
      i,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
  }

  private fun updateNotification() {
    notificationManager?.notify(NOTIF_ID, buildNotification())
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_PLAY -> { isPlaying = true; runJs("document.querySelector('video,audio')?.play();"); updateAll() }
      ACTION_PAUSE -> { isPlaying = false; runJs("document.querySelector('video,audio')?.pause();"); updateAll() }
      ACTION_NEXT -> runJs("document.querySelector('[aria-label*=Next]')?.click();")
      ACTION_PREV -> runJs("document.querySelector('[aria-label*=Previous]')?.click();")
    }
    return START_STICKY
  }

  private fun createChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val ch = NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW)
      notificationManager?.createNotificationChannel(ch)
    }
  }

  private fun requestAudioFocus() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()
        )
        .build()

      audioManager?.requestAudioFocus(req)
    }
  }

  override fun onDestroy() {
    try {
      mediaSession.release()
    } catch (_: Exception) {}

    stopForeground(STOP_FOREGROUND_REMOVE)
    scope.cancel()
    super.onDestroy()
  }
}
