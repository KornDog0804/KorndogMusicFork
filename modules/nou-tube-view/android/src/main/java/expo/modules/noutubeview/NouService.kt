package expo.modules.noutubeview

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URL

class NouService : Service() {

  private var webView: NouWebView? = null
  private var activity: Activity? = null
  private val binder = NouBinder()

  // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  // BACKGROUND PLAYBACK + MEDIA CONTROLS
  // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  private var mediaSession: MediaSession? = null
  private var currentTitle = "NouTube"
  private var currentArtist = "Playing..."
  private var isPlaying = false
  private var notificationManager: NotificationManager? = null
  private var audioManager: AudioManager? = null
  private var audioFocusRequest: AudioFocusRequest? = null

  companion object {
    private const val NOTIFICATION_CHANNEL_ID = "noutube_playback"
    private const val NOTIFICATION_ID = 1
    private const val ACTION_PLAY = "com.noutube.PLAY"
    private const val ACTION_PAUSE = "com.noutube.PAUSE"
    private const val ACTION_NEXT = "com.noutube.NEXT"
    private const val ACTION_PREVIOUS = "com.noutube.PREVIOUS"
  }

  inner class NouBinder : Binder() {
    fun getService(): NouService = this@NouService
  }

  override fun onBind(intent: Intent): IBinder = binder

  override fun onCreate() {
    super.onCreate()
    notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    createNotificationChannel()
  }

  fun initialize(webView: NouWebView, activity: Activity, mediaSession: MediaSession) {
    this.webView = webView
    this.activity = activity
    this.mediaSession = mediaSession
    
    startForeground(NOTIFICATION_ID, buildNotification())
    requestAudioFocus()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent != null) {
      when (intent.action) {
        ACTION_PLAY -> {
          isPlaying = true
          webView?.evaluateJavascript("document.querySelector('video, audio')?.play?.();", null)
          updateNotification()
        }
        ACTION_PAUSE -> {
          isPlaying = false
          webView?.evaluateJavascript("document.querySelector('video, audio')?.pause?.();", null)
          updateNotification()
        }
        ACTION_NEXT -> {
          webView?.evaluateJavascript("document.querySelector('[aria-label=\"Next\"]')?.click?.();", null)
        }
        ACTION_PREVIOUS -> {
          webView?.evaluateJavascript("document.querySelector('[aria-label=\"Previous\"]')?.click?.();", null)
        }
      }
    }
    return START_STICKY
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        NOTIFICATION_CHANNEL_ID,
        "NouTube Playback",
        NotificationManager.IMPORTANCE_LOW
      ).apply {
        description = "Controls for NouTube music playback"
        setShowBadge(false)
      }
      notificationManager?.createNotificationChannel(channel)
    }
  }

  private fun buildNotification(): Notification {
    val playPauseAction = if (isPlaying) {
      NotificationCompat.Action(
        android.R.drawable.ic_media_pause,
        "Pause",
        getPendingIntent(ACTION_PAUSE)
      )
    } else {
      NotificationCompat.Action(
        android.R.drawable.ic_media_play,
        "Play",
        getPendingIntent(ACTION_PLAY)
      )
    }

    val notificationBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_media_play)
      .setContentTitle(currentTitle)
      .setContentText(currentArtist)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setOngoing(isPlaying)
      .addAction(
        android.R.drawable.ic_media_previous,
        "Previous",
        getPendingIntent(ACTION_PREVIOUS)
      )
      .addAction(playPauseAction)
      .addAction(
        android.R.drawable.ic_media_next,
        "Next",
        getPendingIntent(ACTION_NEXT)
      )

    return notificationBuilder.build()
  }

  private fun getPendingIntent(action: String): PendingIntent {
    val intent = Intent(this, NouService::class.java).apply {
      this.action = action
    }
    return PendingIntent.getService(
      this,
      action.hashCode(),
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
  }

  private fun updateNotification() {
    val notification = buildNotification()
    notificationManager?.notify(NOTIFICATION_ID, notification)
  }

  fun notify(title: String, author: String, seconds: Long, thumbnail: String) {
    currentTitle = if (title.isNotEmpty()) title else "Now Playing"
    currentArtist = if (author.isNotEmpty()) author else "NouTube"
    isPlaying = true
    updateNotification()
  }

  fun notifyProgress(playing: Boolean, pos: Long) {
    isPlaying = playing
    updateNotification()
  }

  private fun requestAudioFocus() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

      audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(audioAttributes)
        .build()

      audioManager?.requestAudioFocus(audioFocusRequest!!)
    }
  }

  fun exit() {
    if (audioFocusRequest != null) {
      audioManager?.abandonAudioFocusRequest(audioFocusRequest!!)
    }
  }

  override fun onDestroy() {
    mediaSession?.release()
    stopForeground(STOP_FOREGROUND_REMOVE)
    exit()
    super.onDestroy()
  }
}
