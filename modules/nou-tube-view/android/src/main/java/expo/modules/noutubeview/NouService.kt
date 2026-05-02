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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
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
  private var currentTitle = "NouTube"
  private var currentArtist = "Playing..."
  private var isPlaying = false
  private var currentPosition: Long = 0L

  private var notificationManager: NotificationManager? = null
  private var audioManager: AudioManager? = null
  private var audioFocusRequest: AudioFocusRequest? = null

  private var sleepTimerDeadline: Long = 0
  private var sleepTimerJob: Job? = null

  companion object {
    private const val NOTIFICATION_CHANNEL_ID = "noutube_playback"
    private const val NOTIFICATION_ID = 1

    private const val ACTION_PLAY = "com.noutube.PLAY"
    private const val ACTION_PAUSE = "com.noutube.PAUSE"
    private const val ACTION_NEXT = "com.noutube.NEXT"
    private const val ACTION_PREVIOUS = "com.noutube.PREVIOUS"

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

    createNotificationChannel()
    initMediaSession()

    startForeground(NOTIFICATION_ID, buildNotification())
    requestAudioFocus()

    Log.d(TAG, "NouService created and foreground started")
  }

  fun initialize(webView: NouWebView, activity: Activity) {
    this.webView = webView
    this.activity = activity

    updateMetadata()
    updatePlaybackState()
    forceMediaSessionPriority()
    updateNotification()

    Log.d(TAG, "NouService initialized with WebView")
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_PLAY -> {
        isPlaying = true
        runPlayerJs(playJs())
        updatePlaybackState()
        forceMediaSessionPriority()
        updateNotification()
      }

      ACTION_PAUSE -> {
        isPlaying = false
        runPlayerJs(pauseJs())
        updatePlaybackState()
        forceMediaSessionPriority()
        updateNotification()
      }

      ACTION_NEXT -> {
        runPlayerJs(nextJs())
        forceMediaSessionPriority()
      }

      ACTION_PREVIOUS -> {
        runPlayerJs(previousJs())
        forceMediaSessionPriority()
      }
    }

    return START_STICKY
  }

  private fun initMediaSession() {
    mediaSession = MediaSession(this, "NouTube Player")

    mediaSession.setCallback(object : MediaSession.Callback() {
      override fun onPlay() {
        Log.d(TAG, "MediaSession PLAY")
        isPlaying = true
        runPlayerJs(playJs())
        updatePlaybackState()
        forceMediaSessionPriority()
        updateNotification()
      }

      override fun onPause() {
        Log.d(TAG, "MediaSession PAUSE")
        isPlaying = false
        runPlayerJs(pauseJs())
        updatePlaybackState()
        forceMediaSessionPriority()
        updateNotification()
      }

      override fun onSkipToNext() {
        Log.d(TAG, "MediaSession NEXT")
        runPlayerJs(nextJs())
        forceMediaSessionPriority()
      }

      override fun onSkipToPrevious() {
        Log.d(TAG, "MediaSession PREVIOUS")
        runPlayerJs(previousJs())
        forceMediaSessionPriority()
      }
    })

    updateMetadata()
    updatePlaybackState()
    mediaSession.isActive = true
  }

  private fun runPlayerJs(js: String) {
    val wv = webView ?: return
    val act = activity

    if (act != null) {
      act.runOnUiThread {
        try {
          wv.evaluateJavascript(js, null)
        } catch (e: Exception) {
          Log.e(TAG, "JS failed", e)
        }
      }
    } else {
      try {
        wv.evaluateJavascript(js, null)
      } catch (e: Exception) {
        Log.e(TAG, "JS failed without activity", e)
      }
    }
  }

  private fun playJs(): String = """
    (function(){
      try {
        const btn =
          document.querySelector('ytmusic-player-bar .play-pause-button') ||
          document.querySelector('ytmusic-player-bar [aria-label*="Play"]') ||
          document.querySelector('[aria-label*="Play"]');

        if (btn) {
          btn.click();
          console.log('[NouTube] Play clicked');
          return true;
        }

        const media = document.querySelector('video, audio');
        if (media) {
          media.play();
          console.log('[NouTube] Media play fallback');
          return true;
        }
      } catch(e) {
        console.log('[NouTube] Play error', e);
      }
      return false;
    })();
  """.trimIndent()

  private fun pauseJs(): String = """
    (function(){
      try {
        const btn =
          document.querySelector('ytmusic-player-bar .play-pause-button') ||
          document.querySelector('ytmusic-player-bar [aria-label*="Pause"]') ||
          document.querySelector('[aria-label*="Pause"]');

        if (btn) {
          btn.click();
          console.log('[NouTube] Pause clicked');
          return true;
        }

        const media = document.querySelector('video, audio');
        if (media) {
          media.pause();
          console.log('[NouTube] Media pause fallback');
          return true;
        }
      } catch(e) {
        console.log('[NouTube] Pause error', e);
      }
      return false;
    })();
  """.trimIndent()

  private fun nextJs(): String = """
    (function(){
      try {
        const btn =
          document.querySelector('ytmusic-player-bar .next-button') ||
          document.querySelector('ytmusic-player-bar [aria-label*="Next"]') ||
          document.querySelector('[aria-label*="Next"]');

        if (btn) {
          btn.click();
          console.log('[NouTube] Next clicked');
          return true;
        }
      } catch(e) {
        console.log('[NouTube] Next error', e);
      }
      return false;
    })();
  """.trimIndent()

  private fun previousJs(): String = """
    (function(){
      try {
        const btn =
          document.querySelector('ytmusic-player-bar .previous-button') ||
          document.querySelector('ytmusic-player-bar [aria-label*="Previous"]') ||
          document.querySelector('[aria-label*="Previous"]');

        if (btn) {
          btn.click();
          console.log('[NouTube] Previous clicked');
          return true;
        }
      } catch(e) {
        console.log('[NouTube] Previous error', e);
      }
      return false;
    })();
  """.trimIndent()

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        NOTIFICATION_CHANNEL_ID,
        "NouTube Playback",
        NotificationManager.IMPORTANCE_LOW
      ).apply {
        description = "NouTube music playback controls"
        setShowBadge(false)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
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

    val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_media_play)
      .setContentTitle(currentTitle)
      .setContentText(currentArtist)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setOngoing(isPlaying)
      .setOnlyAlertOnce(true)
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

    if (::mediaSession.isInitialized) {
      builder.setStyle(
        MediaStyle()
          .setMediaSession(mediaSession.sessionToken)
          .setShowActionsInCompactView(0, 1, 2)
      )
    }

    return builder.build()
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
    try {
      notificationManager?.notify(NOTIFICATION_ID, buildNotification())
    } catch (e: Exception) {
      Log.e(TAG, "Notification update failed", e)
    }
  }

  private fun updateMetadata() {
    if (!::mediaSession.isInitialized) return

    try {
      val metadata = MediaMetadata.Builder()
        .putString(MediaMetadata.METADATA_KEY_TITLE, currentTitle)
        .putString(MediaMetadata.METADATA_KEY_ARTIST, currentArtist)
        .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, currentTitle)
        .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, currentArtist)
        .build()

      mediaSession.setMetadata(metadata)
    } catch (e: Exception) {
      Log.e(TAG, "Metadata update failed", e)
    }
  }

  private fun updatePlaybackState() {
    if (!::mediaSession.isInitialized) return

    try {
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
            PlaybackState.ACTION_SKIP_TO_PREVIOUS
        )
        .setState(state, currentPosition, 1f)
        .build()

      mediaSession.setPlaybackState(playbackState)
    } catch (e: Exception) {
      Log.e(TAG, "PlaybackState update failed", e)
    }
  }

  private fun forceMediaSessionPriority() {
    if (!::mediaSession.isInitialized) return

    try {
      mediaSession.isActive = false
      mediaSession.isActive = true
    } catch (e: Exception) {
      Log.e(TAG, "MediaSession priority bump failed", e)
    }
  }

  fun notify(title: String, author: String, seconds: Long, thumbnail: String) {
    currentTitle = title.ifBlank { "Now Playing" }
    currentArtist = author.ifBlank { "NouTube" }
    isPlaying = true

    updateMetadata()
    updatePlaybackState()
    forceMediaSessionPriority()
    updateNotification()
  }

  fun notifyProgress(playing: Boolean, pos: Long) {
    isPlaying = playing
    currentPosition = pos

    updateMetadata()
    updatePlaybackState()
    forceMediaSessionPriority()
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
              AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                isPlaying = false
                runPlayerJs(pauseJs())
                updatePlaybackState()
                updateNotification()
              }

              AudioManager.AUDIOFOCUS_GAIN -> {
                updatePlaybackState()
                forceMediaSessionPriority()
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
        val remaining = deadline - System.currentTimeMillis()

        if (remaining > 0) {
          delay(remaining)
          isPlaying = false
          runPlayerJs(pauseJs())
          updatePlaybackState()
          forceMediaSessionPriority()
          updateNotification()
        }
      }
    }
  }

  fun clearSleepTimer() {
    sleepTimerDeadline = 0
    sleepTimerJob?.cancel()
    sleepTimerJob = null
  }

  fun getSleepTimerRemainingMs(): Long {
    if (sleepTimerDeadline <= 0) return 0
    val remaining = sleepTimerDeadline - System.currentTimeMillis()
    return if (remaining > 0) remaining else 0
  }

  fun exit() {
    clearSleepTimer()

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
        audioManager?.abandonAudioFocusRequest(audioFocusRequest!!)
      }
    } catch (_: Exception) {}
  }

  override fun onDestroy() {
    try {
      if (::mediaSession.isInitialized) {
        mediaSession.release()
      }
    } catch (_: Exception) {}

    stopForeground(STOP_FOREGROUND_REMOVE)
    exit()
    scope.cancel()

    super.onDestroy()
  }
}
