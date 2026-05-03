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
  private var isPlaying = false
  private var currentArtwork: Bitmap? = null
  private var lastThumbUrl = ""
  private var notificationManager: NotificationManager? = null
  private var audioManager: AudioManager? = null
  private var audioFocusRequest: AudioFocusRequest? = null
  private var sleepTimerDeadline = 0L
  private var sleepTimerJob: Job? = null
  private var lastControlAt = 0L

  companion object {
    private const val TAG = "NouService"
    private const val CHANNEL_ID = "noutube_playback"
    private const val NOTIFICATION_ID = 1
    private const val ACTION_PLAY = "expo.modules.noutubeview.PLAY"
    private const val ACTION_PAUSE = "expo.modules.noutubeview.PAUSE"
    private const val ACTION_NEXT = "expo.modules.noutubeview.NEXT"
    private const val ACTION_PREVIOUS = "expo.modules.noutubeview.PREVIOUS"
  }

  inner class NouBinder : Binder() { fun getService(): NouService = this@NouService }
  override fun onBind(intent: Intent): IBinder = binder

  override fun onCreate() {
    super.onCreate()
    notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    createNotificationChannel()
    initMediaSession()
    startForeground(NOTIFICATION_ID, buildNotification())
    updateAll()
    Log.d(TAG, "NouService created")
  }

  fun initialize(webView: NouWebView, activity: Activity) {
    this.webView = webView
    this.activity = activity
    updateAll()
    Log.d(TAG, "NouService initialized")
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_PLAY -> playFromControl()
      ACTION_PAUSE -> pauseFromControl()
      ACTION_NEXT -> nextFromControl()
      ACTION_PREVIOUS -> previousFromControl()
    }
    return START_STICKY
  }

  private fun controlAllowed(): Boolean {
    val now = SystemClock.elapsedRealtime()
    if (now - lastControlAt < 750L) return false
    lastControlAt = now
    return true
  }

  private fun initMediaSession() {
    mediaSession = MediaSessionCompat(this, "NouTube Player")
    mediaSession.setCallback(object : MediaSessionCompat.Callback() {
      override fun onPlay() { playFromControl() }
      override fun onPause() { pauseFromControl() }
      override fun onSkipToNext() { nextFromControl() }
      override fun onSkipToPrevious() { previousFromControl() }
    })
    mediaSession.isActive = true
  }

  private fun playFromControl() {
    if (!controlAllowed()) return
    requestAudioFocus()
    runPlayerJs(playJs())
    isPlaying = true
    updateAll()
  }

  private fun pauseFromControl() {
    if (!controlAllowed()) return
    runPlayerJs(pauseJs())
    isPlaying = false
    updateAll()
  }

  private fun nextFromControl() {
    if (!controlAllowed()) return
    runPlayerJs(nextJs())
  }

  private fun previousFromControl() {
    if (!controlAllowed()) return
    runPlayerJs(previousJs())
  }

  private fun runPlayerJs(js: String) {
    val wv = webView ?: return
    val act = activity
    if (act != null) act.runOnUiThread { try { wv.evaluateJavascript(js, null) } catch (e: Exception) { Log.e(TAG, "JS failed", e) } }
    else try { wv.evaluateJavascript(js, null) } catch (e: Exception) { Log.e(TAG, "JS failed without activity", e) }
  }

  private fun playJs(): String = """
    (function(){
      try{
        window._kdUserPaused=false;
        window._kdShouldBePlaying=true;
        localStorage.setItem('kd_user_paused','false');
        var media=document.querySelector('video,audio');
        if(media){
          var p=media.play();
          if(p&&p.catch)p.catch(function(){});
          return true;
        }
        var bar=document.querySelector('ytmusic-player-bar');
        var btn=bar&&(bar.querySelector('tp-yt-paper-icon-button[title="Play"]')||bar.querySelector('button[aria-label="Play"]')||bar.querySelector('[aria-label="Play"]'));
        if(btn){btn.click();return true;}
      }catch(e){}
      return false;
    })();
  """.trimIndent()

  private fun pauseJs(): String = """
    (function(){
      try{
        window._kdUserPaused=true;
        window._kdShouldBePlaying=false;
        localStorage.setItem('kd_user_paused','true');
        var media=document.querySelector('video,audio');
        if(media){
          media.pause();
          return true;
        }
        var bar=document.querySelector('ytmusic-player-bar');
        var btn=bar&&(bar.querySelector('tp-yt-paper-icon-button[title="Pause"]')||bar.querySelector('button[aria-label="Pause"]')||bar.querySelector('[aria-label="Pause"]'));
        if(btn){btn.click();return true;}
      }catch(e){}
      return false;
    })();
  """.trimIndent()

  private fun nextJs(): String = """
    (function(){try{var bar=document.querySelector('ytmusic-player-bar');if(!bar)return false;var btn=bar.querySelector('tp-yt-paper-icon-button[title="Next"]')||bar.querySelector('button[aria-label="Next"]')||bar.querySelector('[aria-label="Next"]');if(btn){btn.click();return true;}}catch(e){}return false;})();
  """.trimIndent()

  private fun previousJs(): String = """
    (function(){try{var bar=document.querySelector('ytmusic-player-bar');if(!bar)return false;var btn=bar.querySelector('tp-yt-paper-icon-button[title="Previous"]')||bar.querySelector('button[aria-label="Previous"]')||bar.querySelector('[aria-label="Previous"]');if(btn){btn.click();return true;}}catch(e){}return false;})();
  """.trimIndent()

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(CHANNEL_ID, "NouTube Playback", NotificationManager.IMPORTANCE_LOW).apply {
        description = "NouTube music playback controls"
        setShowBadge(false)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
      }
      notificationManager?.createNotificationChannel(channel)
    }
  }

  private fun buildNotification(): Notification {
    val playPauseAction = if (isPlaying)
      NotificationCompat.Action(android.R.drawable.ic_media_pause, "Pause", getPendingIntent(ACTION_PAUSE))
    else
      NotificationCompat.Action(android.R.drawable.ic_media_play, "Play", getPendingIntent(ACTION_PLAY))

    val builder = NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_media_play)
      .setContentTitle(currentTitle)
      .setContentText(currentArtist)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setOngoing(isPlaying)
      .setOnlyAlertOnce(true)
      .addAction(android.R.drawable.ic_media_previous, "Previous", getPendingIntent(ACTION_PREVIOUS))
      .addAction(playPauseAction)
      .addAction(android.R.drawable.ic_media_next, "Next", getPendingIntent(ACTION_NEXT))
      .setStyle(MediaStyle().setMediaSession(mediaSession.sessionToken).setShowActionsInCompactView(0, 1, 2))

    currentArtwork?.let { builder.setLargeIcon(it) }
    return builder.build()
  }

  private fun getPendingIntent(action: String): PendingIntent =
    PendingIntent.getService(this, action.hashCode(), Intent(this, NouService::class.java).apply { this.action = action }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

  private fun updateAll() {
    updateMetadata()
    updatePlaybackState()
    try { notificationManager?.notify(NOTIFICATION_ID, buildNotification()) } catch (e: Exception) { Log.e(TAG, "Notification update failed", e) }
  }

  private fun updateMetadata() {
    if (!::mediaSession.isInitialized) return
    val builder = MediaMetadataCompat.Builder()
      .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
      .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
      .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, currentTitle)
      .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, currentArtist)
    currentArtwork?.let {
      builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
      builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it)
      builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
    }
    mediaSession.setMetadata(builder.build())
  }

  private fun updatePlaybackState() {
    if (!::mediaSession.isInitialized) return
    val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
    mediaSession.setPlaybackState(
      PlaybackStateCompat.Builder()
        .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
        .setState(state, currentPosition, 1f)
        .build()
    )
    mediaSession.isActive = true
  }

  fun notify(title: String, author: String, seconds: Long, thumbnail: String) {
    currentTitle = title.ifBlank { "Now Playing" }
    currentArtist = author.ifBlank { "NouTube" }
    if (thumbnail.isNotBlank() && thumbnail != lastThumbUrl) {
      lastThumbUrl = thumbnail
      loadArtworkAsync(thumbnail)
    } else updateAll()
  }

  fun notifyProgress(playing: Boolean, pos: Long) {
    isPlaying = playing
    currentPosition = pos
    updateAll()
  }

  private fun loadArtworkAsync(url: String) {
    scope.launch(Dispatchers.IO) {
      val bitmap = try { BitmapFactory.decodeStream(URL(url).openStream()) } catch (e: Exception) { Log.e(TAG, "Album art load failed", e); null }
      withContext(Dispatchers.Main) { currentArtwork = bitmap; updateAll() }
    }
  }

  private fun requestAudioFocus() {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs).build()
        audioManager?.requestAudioFocus(audioFocusRequest!!)
      } else {
        @Suppress("DEPRECATION")
        audioManager?.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
      }
    } catch (e: Exception) { Log.e(TAG, "Audio focus request failed", e) }
  }

  fun setSleepTimerDeadline(deadline: Long) {
    sleepTimerDeadline = deadline
    sleepTimerJob?.cancel()
    if (deadline > 0L) {
      sleepTimerJob = scope.launch {
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining > 0L) delay(remaining)
        isPlaying = false
        runPlayerJs(pauseJs())
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
    return if (remaining > 0L) remaining else 0L
  }

  fun exit() {
    clearSleepTimer()
    try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) audioManager?.abandonAudioFocusRequest(audioFocusRequest!!) } catch (_: Exception) {}
  }

  override fun onDestroy() {
    try { if (::mediaSession.isInitialized) mediaSession.release() } catch (_: Exception) {}
    try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
    exit()
    scope.cancel()
    super.onDestroy()
  }
}
