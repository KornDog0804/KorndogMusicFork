package expo.modules.noutubeview

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class NouService : Service() {

  private var webView: NouWebView? = null
  private var activity: Activity? = null
  private val binder = NouBinder()
  private val scope = CoroutineScope(Dispatchers.Main + Job())

  private lateinit var mediaSession: MediaSession
  private var notificationManager: NotificationManager? = null
  private var audioManager: AudioManager? = null
  private var audioFocusRequest: AudioFocusRequest? = null

  private var currentTitle = "NouTube"
  private var currentArtist = "Playing..."
  private var isPlaying = false
  private var currentPosition: Long = 0L

  private var sleepTimerDeadline: Long = 0L
  private var sleepTimerJob: Job? = null

  companion object {
    private const val TAG = "NouService"
    private const val CHANNEL_ID = "noutube_playback"
    private const val CHANNEL_NAME = "NouTube Playback"
    private const val NOTIFICATION_ID = 1001

    private const val ACTION_PLAY = "expo.modules.noutubeview.PLAY"
    private const val ACTION_PAUSE = "expo.modules.noutubeview.PAUSE"
    private const val ACTION_NEXT = "expo.modules.noutubeview.NEXT"
    private const val ACTION_PREVIOUS = "expo.modules.noutubeview.PREVIOUS"
    private const val ACTION_STOP = "expo.modules.noutubeview.STOP"
  }

  inner class NouBinder : Binder() {
    fun getService(): NouService = this@NouService
  }

  override fun onBind(intent: Intent): IBinder = binder

  override fun onCreate() {
    super.onCreate()

    notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

    createNotificationChannel()
    initMediaSession()
    requestAudioFocus()

    startForeground(NOTIFICATION_ID, buildNotification())

    Log.d(TAG, "Service created")
  }

  fun initialize(webView: NouWebView, activity: Activity) {
    this.webView = webView
    this.activity = activity

    updateMetadata()
    updatePlaybackState()
    updateNotification()

    Log.d(TAG, "Service initialized with WebView")
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_PLAY -> handlePlay()
      ACTION_PAUSE -> handlePause()
      ACTION_NEXT -> handleNext()
      ACTION_PREVIOUS -> handlePrevious()
      ACTION_STOP -> exit()
    }

    return START_STICKY
  }

  private fun initMediaSession() {
    mediaSession = MediaSession(this, "NouTube Player")

    mediaSession.setCallback(object : MediaSession.Callback() {
      override fun onPlay() {
        handlePlay()
      }

      override fun onPause() {
        handlePause()
      }

      override fun onSkipToNext() {
        handleNext()
      }

      override fun onSkipToPrevious() {
        handlePrevious()
      }

      override fun onStop() {
        exit()
      }
    })

    mediaSession.isActive = true
    updateMetadata()
    updatePlaybackState()
  }

  private fun handlePlay() {
    isPlaying = true
    runPlayerJs(playJs())
    updatePlaybackState()
    updateNotification()
  }

  private fun handlePause() {
    isPlaying = false
    runPlayerJs(pauseJs())
    updatePlaybackState()
    updateNotification()
  }

  private fun handleNext() {
    runPlayerJs(nextJs())
    updateNotification()
  }

  private fun handlePrevious() {
    runPlayerJs(previousJs())
    updateNotification()
  }

  private fun runPlayerJs(js: String) {
    val wv = webView ?: return
    val act = activity

    try {
      if (act != null) {
        act.runOnUiThread {
          wv.evaluateJavascript(js, null)
        }
      } else {
        wv.evaluateJavascript(js, null)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed running player JS", e)
    }
  }

  private fun playJs(): String = """
    (function(){
      try {
        const media = document.querySelector('video, audio');
        if (media && media.paused) {
          media.play();
          return true;
        }

        const btn =
          document.querySelector('ytmusic-player-bar [aria-label*="Play"]') ||
          document.querySelector('[aria-label*="Play"]');

        if (btn) {
          btn.click();
          return true;
        }
      } catch(e) {}
      return false;
    })();
  """.trimIndent()

  private fun pauseJs(): String = """
    (function(){
      try {
        const media = document.querySelector('video, audio');
        if (media && !media.paused) {
          media.pause();
          return true;
        }

        const btn =
          document.querySelector('ytmusic-player-bar [aria-label*="Pause"]') ||
          document.querySelector('[aria-label*="Pause"]');

        if (btn) {
          btn.click();
          return true;
        }
      } catch(e) {}
      return false;
    })();
  """.trimIndent()

  private fun nextJs(): String = """
    (function(){
      try {
        const btn =
          document.querySelector('ytmusic-player-bar [aria-label*="Next"]') ||
          document.querySelector('[aria-label*="Next"]');

        if (btn) {
          btn.click();
          return true;
        }
      } catch(e) {}
      return false;
    })();
  """.trimIndent()

  private fun previousJs(): String = """
    (function(){
      try {
        const btn =
          document.querySelector('ytmusic-player-bar [aria-label*="Previous"]') ||
          document.querySelector('[aria-label*="Previous"]');

        if (btn) {
          btn.click();
          return true;
        }
      } catch(e) {}
      return false;
    })();
  """.trimIndent()

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        CHANNEL_NAME,
        NotificationManager.IMPORTANCE_LOW
      ).apply {
        description = "NouTube lock screen and notification controls"
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        setShowBadge(false)
      }

      notificationManager?.createNotificationChannel(channel)
    }
  }

  private fun buildNotification(): Notification {
    val builder =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Notification.Builder(this, CHANNEL_ID)
      } else {
        @Suppress("DEPRECATION")
        Notification.Builder(this)
      }

    val previousAction = Notification.Action.Builder(
      android.R.drawable.ic_media_previous,
      "Previous",
      pendingIntent(ACTION_PREVIOUS)
    ).build()

    val playPauseAction = Notification.Action.Builder(
      if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
      if (isPlaying) "Pause" else "Play",
      pendingIntent(if (isPlaying) ACTION_PAUSE else ACTION_PLAY)
    ).build()

    val nextAction = Notification.Action.Builder(
      android.R.drawable.ic_media_next,
      "Next",
      pendingIntent(ACTION_NEXT)
    ).build()

    builder
      .setSmallIcon(android.R.drawable.ic_media_play)
      .setContentTitle(currentTitle)
      .setContentText(currentArtist)
      .setVisibility(Notification.VISIBILITY_PUBLIC)
      .setOngoing(isPlaying)
      .setOnlyAlertOnce(true)
      .addAction(previousAction)
      .addAction(playPauseAction)
      .addAction(nextAction)
      .setStyle(
        Notification.MediaStyle()
          .setMediaSession(mediaSession.sessionToken)
          .setShowActionsInCompactView(0, 1, 2)
      )

    return builder.build()
  }

  private fun pendingIntent(action: String): PendingIntent {
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
    try {
      notificationManager?.notify(NOTIFICATION_ID, buildNotification())
    } catch (e: Exception) {
      Log.e(TAG, "Notification update failed", e)
    }
  }

  private fun updateMetadata() {
    if (!::mediaSession.isInitialized) return

    val metadata = MediaMetadata.Builder()
      .putString(MediaMetadata.METADATA_KEY_TITLE, currentTitle)
      .putString(MediaMetadata.METADATA_KEY_ARTIST, currentArtist)
      .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, currentTitle)
      .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, currentArtist)
      .build()

    mediaSession.setMetadata(metadata)
  }

  private fun updatePlaybackState() {
    if (!::mediaSession.isInitialized) return

    val state = if (isPlaying) {
      PlaybackState.STATE_PLAYING
    } else {
      PlaybackState.STATE_PAUSED
    }

    val playbackState = PlaybackState.Builder()
      .setActions(
        PlaybackState.ACTION_PLAY or
          PlaybackState.ACTION_PAUSE or
          PlaybackState.ACTION_PLAY_PAUSE or
          PlaybackState.ACTION_SKIP_TO_NEXT or
          PlaybackState.ACTION_SKIP_TO_PREVIOUS or
          PlaybackState.ACTION_STOP
      )
      .setState(state, currentPosition, 1f)
      .build()

    mediaSession.setPlaybackState(playbackState)
    mediaSession.isActive = true
  }

  fun notify(title: String, author: String, seconds: Long, thumbnail: String) {
    currentTitle = title.ifBlank { "Now Playing" }
    currentArtist = author.ifBlank { "NouTube" }
    isPlaying = true

    updateMetadata()
    updatePlaybackState()
    updateNotification()
  }

  fun notifyProgress(playing: Boolean, pos: Long) {
    isPlaying = playing
    currentPosition = pos

    updateMetadata()
    updatePlaybackState()
    updateNotification()
  }

  private fun requestAudioFocus() {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val attrs = AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_MEDIA)
          .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
          .build()

        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
          .setAudioAttributes(attrs)
          .setOnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
              AudioManager.AUDIOFOCUS_LOSS,
              AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> handlePause()
              AudioManager.AUDIOFOCUS_GAIN -> {
                updatePlaybackState()
                updateNotification()
              }
            }
          }
          .build()

        audioManager?.requestAudioFocus(audioFocusRequest!!)
      } else {
        @Suppress("DEPRECATION")
        audioManager?.requestAudioFocus(
          null,
          AudioManager.STREAM_MUSIC,
          AudioManager.AUDIOFOCUS_GAIN
        )
      }
    } catch (e: Exception) {
      Log.e(TAG, "Audio focus request failed", e)
    }
  }

  fun setSleepTimerDeadline(deadline: Long) {
    sleepTimerDeadline = deadline
    sleepTimerJob?.cancel()

    if (deadline > 0) {
      sleepTimerJob = scope.launch {
        val remaining = deadline - SystemClock.elapsedRealtime()

        if (remaining > 0) {
          delay(remaining)
        }

        handlePause()
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
    return maxOf(0L, sleepTimerDeadline - SystemClock.elapsedRealtime())
  }

  fun exit() {
    clearSleepTimer()

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
        audioManager?.abandonAudioFocusRequest(audioFocusRequest!!)
      }
    } catch (_: Exception) {}

    try {
      if (::mediaSession.isInitialized) {
        mediaSession.isActive = false
      }
    } catch (_: Exception) {}

    stopSelf()
  }

  override fun onDestroy() {
    try {
      if (::mediaSession.isInitialized) {
        mediaSession.release()
      }
    } catch (_: Exception) {}

    try {
      stopForeground(STOP_FOREGROUND_REMOVE)
    } catch (_: Exception) {}

    scope.cancel()

    super.onDestroy()
  }
}
