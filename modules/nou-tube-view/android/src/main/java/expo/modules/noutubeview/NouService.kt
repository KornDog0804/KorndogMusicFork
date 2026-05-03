package expo.modules.noutubeview

import android.app.*
import android.content.*
import android.graphics.*
import android.media.*
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
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
  private var currentArtist = "Ready"
  private var currentPosition = 0L
  private var currentDuration = 1L

  private var isPlaying = false
  private var isLiked = false

  private var currentArtwork: Bitmap? = null
  private var lastThumbUrl = ""

  private var notificationManager: NotificationManager? = null
  private var audioManager: AudioManager? = null
  private var audioFocusRequest: AudioFocusRequest? = null

  private var lastControlAt = 0L

  companion object {
    private const val TAG = "NouService"
    private const val CHANNEL_ID = "noutube_playback"
    private const val NOTIFICATION_ID = 1

    private const val ACTION_PLAY = "PLAY"
    private const val ACTION_PAUSE = "PAUSE"
    private const val ACTION_NEXT = "NEXT"
    private const val ACTION_PREVIOUS = "PREVIOUS"
    private const val ACTION_LIKE = "LIKE"
    private const val ACTION_SHUFFLE = "SHUFFLE"
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
    updateAll()
  }

  fun initialize(webView: NouWebView, activity: Activity) {
    this.webView = webView
    this.activity = activity
    updateAll()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_PLAY -> playFromControl()
      ACTION_PAUSE -> pauseFromControl()
      ACTION_NEXT -> nextFromControl()
      ACTION_PREVIOUS -> previousFromControl()
      ACTION_LIKE -> likeFromControl()
      ACTION_SHUFFLE -> shuffleFromControl()
    }
    return START_STICKY
  }

  private fun controlAllowed(): Boolean {
    val now = SystemClock.elapsedRealtime()
    if (now - lastControlAt < 500) return false
    lastControlAt = now
    return true
  }

  private fun initMediaSession() {
    mediaSession = MediaSessionCompat(this, "NouTube Player")

    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
    if (launchIntent != null) {
      val pendingIntent = PendingIntent.getActivity(
        this,
        100,
        launchIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )
      mediaSession.setSessionActivity(pendingIntent)
    }

    mediaSession.setCallback(object : MediaSessionCompat.Callback() {
      override fun onPlay() = playFromControl()
      override fun onPause() = pauseFromControl()
      override fun onSkipToNext() = nextFromControl()
      override fun onSkipToPrevious() = previousFromControl()
    })

    mediaSession.isActive = true
  }

  private fun runJs(js: String) {
    val wv = webView ?: return
    activity?.runOnUiThread { wv.evaluateJavascript(js, null) }
  }

  private fun playFromControl() {
    if (!controlAllowed()) return
    requestAudioFocus()
    runJs("document.querySelector('video,audio')?.play()")
    isPlaying = true
    updateAll()
  }

  private fun pauseFromControl() {
    if (!controlAllowed()) return
    runJs("document.querySelector('video,audio')?.pause()")
    isPlaying = false
    updateAll()
  }

  private fun nextFromControl() {
    runJs("document.querySelector('[aria-label=\"Next\"]')?.click()")
  }

  private fun previousFromControl() {
    runJs("document.querySelector('[aria-label=\"Previous\"]')?.click()")
  }

  private fun likeFromControl() {
    isLiked = !isLiked
    runJs("document.querySelector('[aria-label*=\"Like\"]')?.click()")
    updateAll()
  }

  private fun shuffleFromControl() {
    runJs("document.querySelector('[aria-label*=\"Shuffle\"]')?.click()")
  }

  private fun buildNotification(): Notification {

    val playPauseAction =
      if (isPlaying)
        NotificationCompat.Action(android.R.drawable.ic_media_pause, "Pause", getPI(ACTION_PAUSE))
      else
        NotificationCompat.Action(android.R.drawable.ic_media_play, "Play", getPI(ACTION_PLAY))

    val likeIcon =
      if (isLiked) android.R.drawable.btn_star_big_on
      else android.R.drawable.btn_star_big_off

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(applicationInfo.icon)
      .setContentTitle(currentTitle)
      .setContentText(currentArtist)
      .setLargeIcon(currentArtwork)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setOnlyAlertOnce(true)
      .setOngoing(isPlaying)

      .addAction(android.R.drawable.ic_media_previous, "Prev", getPI(ACTION_PREVIOUS))
      .addAction(playPauseAction)
      .addAction(android.R.drawable.ic_media_next, "Next", getPI(ACTION_NEXT))
      .addAction(likeIcon, "Like", getPI(ACTION_LIKE))
      .addAction(android.R.drawable.ic_menu_rotate, "Shuffle", getPI(ACTION_SHUFFLE))

      .setStyle(
        MediaStyle()
          .setMediaSession(mediaSession.sessionToken)
          .setShowActionsInCompactView(0,1,2)
      )
      .build()
  }

  private fun getPI(action: String): PendingIntent =
    PendingIntent.getService(
      this,
      action.hashCode(),
      Intent(this, NouService::class.java).apply { this.action = action },
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

  private fun updateAll() {
    updateMetadata()
    updatePlaybackState()
    notificationManager?.notify(NOTIFICATION_ID, buildNotification())
  }

  private fun updateMetadata() {
    val meta = MediaMetadataCompat.Builder()
      .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
      .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
      .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentDuration)
      .apply {
        currentArtwork?.let {
          putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
        }
      }
      .build()

    mediaSession.setMetadata(meta)
  }

  private fun updatePlaybackState() {
    val state =
      if (isPlaying) PlaybackStateCompat.STATE_PLAYING
      else PlaybackStateCompat.STATE_PAUSED

    mediaSession.setPlaybackState(
      PlaybackStateCompat.Builder()
        .setActions(
          PlaybackStateCompat.ACTION_PLAY or
          PlaybackStateCompat.ACTION_PAUSE or
          PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
          PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
          PlaybackStateCompat.ACTION_SEEK_TO
        )
        .setState(state, currentPosition, 1f)
        .build()
    )
  }

  fun notify(title: String, artist: String, duration: Long, thumb: String) {
    currentTitle = title
    currentArtist = artist
    currentDuration = if (duration > 0) duration * 1000 else 1

    if (thumb.isNotBlank() && thumb != lastThumbUrl) {
      lastThumbUrl = thumb
      loadArt(thumb)
    } else {
      updateAll()
    }
  }

  fun notifyProgress(playing: Boolean, pos: Long) {
    isPlaying = playing
    currentPosition = pos * 1000
    updateAll()
  }

  private fun loadArt(url: String) {
    scope.launch(Dispatchers.IO) {
      val bmp = try { BitmapFactory.decodeStream(URL(url).openStream()) } catch (_: Exception) { null }
      withContext(Dispatchers.Main) {
        currentArtwork = bmp
        updateAll()
      }
    }
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
          .build()

        audioManager?.requestAudioFocus(audioFocusRequest!!)
      }
    } catch (_: Exception) {}
  }

  override fun onDestroy() {
    mediaSession.release()
    scope.cancel()
    super.onDestroy()
  }
}
