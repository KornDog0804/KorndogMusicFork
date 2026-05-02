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
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import androidx.media.session.MediaSessionCompat
import androidx.media.session.PlaybackStateCompat
import androidx.media.MediaMetadataCompat
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

  private lateinit var mediaSession: MediaSessionCompat

  private var currentTitle = "NouTube"
  private var currentArtist = "Playing..."
  private var isPlaying = false
  private var currentPosition: Long = 0L

  private var notificationManager: NotificationManager? = null
  private var audioManager: AudioManager? = null
  private var audioFocusRequest: AudioFocusRequest? = null

  private var sleepTimerDeadlineMs: Long = 0L
  private var sleepTimerJob: Job? = null

  companion object {
    private const val TAG = "NouService"

    private const val NOTIFICATION_CHANNEL_ID = "noutube_playback"
    private const val NOTIFICATION_ID = 1

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

    Log.d(TAG, "NouService created")
  }

  fun initialize(webView: NouWebView, activity: Activity) {
    this.webView = webView
    this.activity = activity

    updateMetadata()
    updatePlaybackState()
    updateNotification()

    Log.d(TAG, "NouService initialized")
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_PLAY -> play()
      ACTION_PAUSE -> pause()
      ACTION_NEXT -> next()
      ACTION_PREVIOUS -> previous()
      ACTION_STOP -> exit()
    }

    return START_STICKY
  }

  private fun initMediaSession() {
    mediaSession = MediaSessionCompat(this, "NouTube Player").apply {
      setCallback(object : MediaSessionCompat.Callback() {
        override fun onPlay() {
          play()
        }

        override fun onPause() {
          pause()
        }

        override fun onSkipToNext() {
          next()
        }

        override fun onSkipToPrevious() {
          previous()
        }

        override fun onStop() {
          exit()
        }
      })

      isActive = true
    }

    updateMetadata()
    updatePlaybackState()
  }

  private fun play() {
    isPlaying = true
    runPlayerJs(playJs())
    updatePlaybackState()
    updateNotification()
  }

  private fun pause() {
    isPlaying = false
    runPlayerJs(pauseJs())
    updatePlaybackState()
    updateNotification()
  }

  private fun next() {
    runPlayerJs(nextJs())
    updatePlaybackState()
    updateNotification()
  }

  private fun previous() {
    runPlayerJs(previousJs())
    updatePlaybackState()
    updateNotification()
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
        var media = document.querySelector('video, audio');
        if (media && media.paused) {
          media.play();
          return true;
        }

        var btn =
          document.querySelector('ytmusic-player-bar [aria-label="Play"]') ||
          document.querySelector('ytmusic-player-bar [aria-label*="Play"]') ||
          document.querySelector('[aria-label="Play"]') ||
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
        var media = document.querySelector('video, audio');
        if (media && !media.paused) {
          media.pause();
          return true;
        }

        var btn =
          document.querySelector('ytmusic-player-bar [aria-label="Pause"]') ||
          document.querySelector('ytmusic-player-bar [aria-label*="Pause"]') ||
          document.querySelector('[aria-label="Pause"]') ||
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
        var btn =
          document.querySelector('ytmusic-player-bar [aria-label="Next"]') ||
          document.querySelector('ytmusic-player-bar [aria-label*="Next"]') ||
          document.querySelector('[aria-label="Next"]') ||
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
        var btn =
          document.querySelector('ytmusic-player-bar [aria-label="Previous"]') ||
          document.querySelector('ytmusic-player-bar [aria-label*="Previous"]') ||
          document.querySelector('[aria-label="Previous"]') ||
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
      .setOnlyAlertOnce(true)
      .setOngoing(isPlaying)
      .setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP))
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
      .setStyle(
        MediaStyle()
          .setMediaSession(mediaSession.sessionToken)
          .setShowActionsInCompactView(0, 1, 2)
      )

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

    val metadata = MediaMetadataCompat.Builder()
      .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
      .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
      .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, currentTitle)
      .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, currentArtist)
      .build()

    mediaSession.setMetadata(metadata)
  }

  private fun updatePlaybackState() {
    if (!::mediaSession.isInitialized) return

    val state = if (isPlaying) {
      PlaybackStateCompat.STATE_PLAYING
    } else {
      PlaybackStateCompat.STATE_PAUSED
    }

    val playbackState = PlaybackStateCompat.Builder()
      .setActions(
        PlaybackStateCompat.ACTION_PLAY or
          PlaybackStateCompat.ACTION_PAUSE or
          PlaybackStateCompat.ACTION_PLAY_PAUSE or
          PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
          PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
          PlaybackStateCompat.ACTION_STOP
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
              AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                isPlaying = false
                runPlayerJs(pauseJs())
                updatePlaybackState()
                updateNotification()
              }

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
    sleepTimerDeadlineMs = deadline
    sleepTimerJob?.cancel()

    if (deadline > 0L) {
      sleepTimerJob = scope.launch {
        val remaining = deadline - SystemClock.elapsedRealtime()

        if (remaining > 0L) {
          delay(remaining)
        }

        isPlaying = false
        runPlayerJs(pauseJs())
        updatePlaybackState()
        updateNotification()
        nouController.emitSleepTimerExpired()
      }
    }
  }

  fun clearSleepTimer() {
    sleepTimerDeadlineMs = 0L
    sleepTimerJob?.cancel()
    sleepTimerJob = null
  }

  fun getSleepTimerRemainingMs(): Long {
    if (sleepTimerDeadlineMs <= 0L) return 0L
    return maxOf(0L, sleepTimerDeadlineMs - SystemClock.elapsedRealtime())
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

    try {
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
    } catch (_: Exception) {}
  }

  override fun onDestroy() {
    try {
      if (::mediaSession.isInitialized) {
        mediaSession.release()
      }
    } catch (_: Exception) {}

    exit()
    scope.cancel()

    super.onDestroy()
  }
}
