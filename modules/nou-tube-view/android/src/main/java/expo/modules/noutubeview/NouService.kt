package expo.modules.noutubeview

import android.app.*
import android.content.*
import android.graphics.*
import android.graphics.drawable.Drawable
import android.media.*
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.RatingCompat
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
  private var currentDuration = 5 * 60 * 1000L
  private var isPlaying = false
  private var isLiked = false

  private var currentArtwork: Bitmap? = null
  private var defaultArtwork: Bitmap? = null
  private var lastThumbUrl = ""

  private var pendingTitle = ""
  private var pendingArtist = ""
  private var pendingThumb = ""
  private var pendingSince = 0L
  private var lastAcceptedTrackKey = ""
  private var lastNotifyAt = 0L

  private var notificationManager: NotificationManager? = null
  private var audioManager: AudioManager? = null
  private var audioFocusRequest: AudioFocusRequest? = null

  private var sleepTimerDeadline = 0L
  private var sleepTimerJob: Job? = null
  private var lastControlAt = 0L
  private var userPausedFromControls = false

  companion object {
    private const val TAG = "NouService"
    private const val CHANNEL_ID = "noutube_playback"
    private const val NOTIFICATION_ID = 1

    private const val ACTION_PLAY = "expo.modules.noutubeview.PLAY"
    private const val ACTION_PAUSE = "expo.modules.noutubeview.PAUSE"
    private const val ACTION_NEXT = "expo.modules.noutubeview.NEXT"
    private const val ACTION_PREVIOUS = "expo.modules.noutubeview.PREVIOUS"
    private const val ACTION_LIKE = "expo.modules.noutubeview.LIKE"
    private const val ACTION_SHUFFLE = "expo.modules.noutubeview.SHUFFLE"
  }

  inner class NouBinder : Binder() {
    fun getService(): NouService = this@NouService
  }

  override fun onBind(intent: Intent): IBinder = binder

  override fun onCreate() {
    super.onCreate()
    notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    defaultArtwork = loadAppIconBitmap()
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
      ACTION_LIKE -> likeFromControl()
      ACTION_SHUFFLE -> shuffleFromControl()
    }
    return START_STICKY
  }

  private fun controlAllowed(): Boolean {
    val now = SystemClock.elapsedRealtime()
    if (now - lastControlAt < 650L) return false
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
      override fun onPlay() { playFromControl() }
      override fun onPause() { pauseFromControl() }
      override fun onSkipToNext() { nextFromControl() }
      override fun onSkipToPrevious() { previousFromControl() }
      override fun onSeekTo(pos: Long) { seekFromControl(pos) }

      override fun onSetRating(rating: RatingCompat?) {
        likeFromControl()
      }

      override fun onCustomAction(action: String?, extras: Bundle?) {
        when (action) {
          ACTION_LIKE -> likeFromControl()
          ACTION_SHUFFLE -> shuffleFromControl()
        }
      }
    })

    mediaSession.isActive = true
  }

  private fun playFromControl() {
    if (!controlAllowed()) return
    userPausedFromControls = false
    requestAudioFocus()
    runPlayerJs(playJs())
    isPlaying = true
    updateAll()
  }

  private fun pauseFromControl() {
    if (!controlAllowed()) return
    userPausedFromControls = true
    runPlayerJs(pauseJs())
    isPlaying = false
    updateAll()
  }

  private fun nextFromControl() {
    if (!controlAllowed()) return
    userPausedFromControls = false
    resetPendingTrack()
    runPlayerJs(nextJs())
    isPlaying = true
    updateAll()
  }

  private fun previousFromControl() {
    if (!controlAllowed()) return
    userPausedFromControls = false
    resetPendingTrack()
    runPlayerJs(previousJs())
    isPlaying = true
    updateAll()
  }

  private fun likeFromControl() {
    if (!controlAllowed()) return

    val optimisticLiked = !isLiked
    isLiked = optimisticLiked
    updateAll()

    runPlayerJs(likeJs())

    scope.launch {
      delay(900L)
      runPlayerJs(checkLikeStateJs())
    }
  }

  private fun shuffleFromControl() {
    if (!controlAllowed()) return
    runPlayerJs(shuffleJs())
    updateAll()
  }

  private fun seekFromControl(posMs: Long) {
    val safeMs = if (posMs < 0L) 0L else posMs
    currentPosition = safeMs
    runPlayerJs(seekJs(safeMs / 1000L))
    updateAll()
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
        var btn=document.querySelector('ytmusic-player-bar button[aria-label="Play"], ytmusic-player-bar [title="Play"], button[aria-label="Play"], [aria-label="Play"]');
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
        var btn=document.querySelector('ytmusic-player-bar button[aria-label="Pause"], ytmusic-player-bar [title="Pause"], button[aria-label="Pause"], [aria-label="Pause"]');
        if(btn){btn.click();return true;}
      }catch(e){}
      return false;
    })();
  """.trimIndent()

  private fun nextJs(): String = """
    (function(){
      try{
        window._kdUserPaused=false;
        window._kdShouldBePlaying=true;
        localStorage.setItem('kd_user_paused','false');
        var btn=document.querySelector('ytmusic-player-bar button[aria-label="Next"], ytmusic-player-bar [title="Next"], button[aria-label="Next"], [aria-label="Next"]');
        if(btn){btn.click();return true;}
      }catch(e){}
      return false;
    })();
  """.trimIndent()

  private fun previousJs(): String = """
    (function(){
      try{
        window._kdUserPaused=false;
        window._kdShouldBePlaying=true;
        localStorage.setItem('kd_user_paused','false');
        var btn=document.querySelector('ytmusic-player-bar button[aria-label="Previous"], ytmusic-player-bar [title="Previous"], button[aria-label="Previous"], [aria-label="Previous"]');
        if(btn){btn.click();return true;}
      }catch(e){}
      return false;
    })();
  """.trimIndent()

  private fun likeJs(): String = """
    (function(){
      try{
        var selectors=[
          'ytmusic-player-bar button[aria-label*="Add to liked songs"]',
          'ytmusic-player-bar button[aria-label*="Remove from liked songs"]',
          'ytmusic-player-bar [aria-label*="Add to liked songs"]',
          'ytmusic-player-bar [aria-label*="Remove from liked songs"]',
          'ytmusic-player-bar button[aria-label*="Like"]',
          'ytmusic-player-bar button[aria-label*="like"]',
          'ytmusic-player-bar [aria-label*="Like"]',
          'ytmusic-player-bar [aria-label*="like"]',
          'button[aria-label*="Add to liked songs"]',
          'button[aria-label*="Remove from liked songs"]',
          '[aria-label*="Add to liked songs"]',
          '[aria-label*="Remove from liked songs"]',
          'button[aria-label*="Like"]',
          'button[aria-label*="like"]',
          '[aria-label*="Like"]',
          '[aria-label*="like"]'
        ];

        for(var i=0;i<selectors.length;i++){
          var btn=document.querySelector(selectors[i]);
          if(btn){
            btn.click();
            setTimeout(function(){
              try{
                if(window.NouTubeI && window.NouTubeI.notifyLikeState){
                  window.NouTubeI.notifyLikeState(window.__kdDetectLiked ? window.__kdDetectLiked() : false);
                }
              }catch(e){}
            },650);
            return true;
          }
        }
      }catch(e){}
      return false;
    })();
  """.trimIndent()

  private fun checkLikeStateJs(): String = """
    (function(){
      try{
        if(!window.__kdDetectLiked){
          window.__kdDetectLiked=function(){
            try{
              var selectors=[
                'ytmusic-player-bar button[aria-label*="Remove from liked songs"]',
                'ytmusic-player-bar [aria-label*="Remove from liked songs"]',
                'button[aria-label*="Remove from liked songs"]',
                '[aria-label*="Remove from liked songs"]'
              ];
              for(var i=0;i<selectors.length;i++){
                if(document.querySelector(selectors[i])) return true;
              }

              var pressed=document.querySelector('ytmusic-player-bar [aria-pressed="true"][aria-label*="Like"], ytmusic-player-bar [aria-pressed="true"][aria-label*="like"]');
              if(pressed) return true;

              return false;
            }catch(e){
              return false;
            }
          };
        }

        if(window.NouTubeI && window.NouTubeI.notifyLikeState){
          window.NouTubeI.notifyLikeState(window.__kdDetectLiked());
        }
      }catch(e){}
    })();
  """.trimIndent()

  private fun shuffleJs(): String = """
    (function(){
      try{
        var selectors=[
          'ytmusic-player-bar button[aria-label*="Shuffle"]',
          'ytmusic-player-bar [aria-label*="Shuffle"]',
          'button[aria-label*="Shuffle"]',
          'button[aria-label*="shuffle"]',
          '[aria-label*="Shuffle"]',
          '[aria-label*="shuffle"]'
        ];
        for(var i=0;i<selectors.length;i++){
          var btn=document.querySelector(selectors[i]);
          if(btn){btn.click();return true;}
        }
      }catch(e){}
      return false;
    })();
  """.trimIndent()

  private fun seekJs(seconds: Long): String = """
    (function(){
      try{
        var media=document.querySelector('video,audio');
        if(media){
          media.currentTime=$seconds;
          return true;
        }
      }catch(e){}
      return false;
    })();
  """.trimIndent()

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID,
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
      NotificationCompat.Action(android.R.drawable.ic_media_pause, "Pause", getPendingIntent(ACTION_PAUSE))
    } else {
      NotificationCompat.Action(android.R.drawable.ic_media_play, "Play", getPendingIntent(ACTION_PLAY))
    }

    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
    val contentIntent = if (launchIntent != null) {
      PendingIntent.getActivity(
        this,
        99,
        launchIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )
    } else null

    val builder = NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(applicationInfo.icon)
      .setContentTitle(currentTitle)
      .setContentText(currentArtist)
      .setSubText("NouTube")
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
      .setOngoing(isPlaying)
      .setOnlyAlertOnce(true)
      .setShowWhen(false)
      .addAction(android.R.drawable.ic_media_previous, "Previous", getPendingIntent(ACTION_PREVIOUS))
      .addAction(playPauseAction)
      .addAction(android.R.drawable.ic_media_next, "Next", getPendingIntent(ACTION_NEXT))
      .setStyle(
        MediaStyle()
          .setMediaSession(mediaSession.sessionToken)
          .setShowActionsInCompactView(0, 1, 2)
      )

    if (contentIntent != null) builder.setContentIntent(contentIntent)

    val artwork = makeBrandedArtwork(currentArtwork, defaultArtwork)
    artwork?.let { builder.setLargeIcon(it) }

    return builder.build()
  }

  private fun getPendingIntent(action: String): PendingIntent =
    PendingIntent.getService(
      this,
      action.hashCode(),
      Intent(this, NouService::class.java).apply { this.action = action },
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

  private fun updateAll() {
    updateMetadata()
    updatePlaybackState()

    try {
      notificationManager?.notify(NOTIFICATION_ID, buildNotification())
    } catch (e: Exception) {
      Log.e(TAG, "Notification update failed", e)
    }
  }

  private fun updateMetadata() {
    if (!::mediaSession.isInitialized) return

    val artwork = makeBrandedArtwork(currentArtwork, defaultArtwork)

    val builder = MediaMetadataCompat.Builder()
      .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
      .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
      .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, currentTitle)
      .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, currentArtist)
      .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentDuration)
      .putRating(MediaMetadataCompat.METADATA_KEY_USER_RATING, RatingCompat.newHeartRating(isLiked))

    artwork?.let {
      builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
      builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it)
      builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
    }

    mediaSession.setMetadata(builder.build())
  }

  private fun updatePlaybackState() {
    if (!::mediaSession.isInitialized) return

    val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
    val likeIcon = if (isLiked) {
    android.R.drawable.btn_star_big_on
} else {
    android.R.drawable.ic_menu_add
}

val likeTitle = if (isLiked) "Liked" else "Add to liked songs"

    val playbackState = PlaybackStateCompat.Builder()
      .setActions(
        PlaybackStateCompat.ACTION_PLAY or
          PlaybackStateCompat.ACTION_PAUSE or
          PlaybackStateCompat.ACTION_PLAY_PAUSE or
          PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
          PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
          PlaybackStateCompat.ACTION_SEEK_TO or
          PlaybackStateCompat.ACTION_SET_RATING
      )
      .addCustomAction(
        PlaybackStateCompat.CustomAction.Builder(
          ACTION_LIKE,
          likeTitle,
          likeIcon
        ).build()
      )
      .setState(state, currentPosition, 1f, SystemClock.elapsedRealtime())
      .build()

    mediaSession.setPlaybackState(playbackState)
    mediaSession.isActive = true
  }

  fun notify(title: String, author: String, seconds: Long, thumbnail: String) {
    val cleanTitle = cleanTitle(title)
    val cleanArtist = cleanArtist(author)

    if (cleanTitle.isBlank() || cleanArtist.isBlank()) {
      return
    }

    val incomingKey = "$cleanTitle|$cleanArtist|$thumbnail"
    val now = SystemClock.elapsedRealtime()

    if (incomingKey != lastAcceptedTrackKey) {
      val pendingKey = "$pendingTitle|$pendingArtist|$pendingThumb"

      if (incomingKey != pendingKey) {
        pendingTitle = cleanTitle
        pendingArtist = cleanArtist
        pendingThumb = thumbnail
        pendingSince = now
        return
      }

      if (now - pendingSince < 900L) {
        return
      }
    }

    if (now - lastNotifyAt < 350L && incomingKey == lastAcceptedTrackKey) {
      return
    }

    lastNotifyAt = now
    lastAcceptedTrackKey = incomingKey

    currentTitle = cleanTitle
    currentArtist = cleanArtist
    resetPendingTrack()

    currentDuration = if (seconds > 0L) {
      seconds * 1000L
    } else if (currentDuration > 0L) {
      currentDuration
    } else {
      5 * 60 * 1000L
    }

    if (!userPausedFromControls) {
      isPlaying = true
    }

    runPlayerJs(checkLikeStateJs())

    if (thumbnail.isNotBlank() && thumbnail != lastThumbUrl) {
      lastThumbUrl = thumbnail
      loadArtworkAsync(thumbnail)
    } else {
      updateAll()
    }
  }

  fun notifyLikeState(liked: Boolean) {
    if (isLiked == liked) return
    isLiked = liked
    updateAll()
  }

  fun notifyProgress(playing: Boolean, pos: Long) {
    if (playing) {
      isPlaying = true
      userPausedFromControls = false
    } else if (userPausedFromControls) {
      isPlaying = false
    }

    if (pos >= 0L) {
      currentPosition = pos * 1000L
    }

    if (currentDuration <= 0L) {
      currentDuration = 5 * 60 * 1000L
    }

    updateAll()
  }

  private fun resetPendingTrack() {
    pendingTitle = ""
    pendingArtist = ""
    pendingThumb = ""
    pendingSince = 0L
  }

  private fun cleanTitle(value: String): String {
    return value
      .replace(Regex("\\s+"), " ")
      .replace("Official Video", "", ignoreCase = true)
      .replace("Official Audio", "", ignoreCase = true)
      .replace("Music Video", "", ignoreCase = true)
      .trim()
  }

  private fun cleanArtist(value: String): String {
    var out = value
      .replace(Regex("\\s+"), " ")
      .replace(" - Topic", "", ignoreCase = true)
      .trim()

    if (out.contains(" • ")) out = out.split(" • ")[0].trim()
    if (out.contains(" · ")) out = out.split(" · ")[0].trim()

    return out
  }

  private fun loadArtworkAsync(url: String) {
    scope.launch(Dispatchers.IO) {
      val bitmap = try {
        BitmapFactory.decodeStream(URL(url).openStream())
      } catch (e: Exception) {
        Log.e(TAG, "Album art load failed", e)
        null
      }

      withContext(Dispatchers.Main) {
        currentArtwork = bitmap
        updateAll()
      }
    }
  }

  private fun loadAppIconBitmap(): Bitmap? {
    return try {
      val possibleNames = listOf(
        "ic_launcher_foreground",
        "ic_launcher_round",
        "ic_launcher",
        "adaptive_icon_foreground",
        "adaptive_icon",
        "icon",
        "notification_icon"
      )

      var drawable: Drawable? = null

      for (resType in listOf("mipmap", "drawable")) {
        for (name in possibleNames) {
          val id = resources.getIdentifier(name, resType, packageName)
          if (id != 0) {
            drawable = resources.getDrawable(id, theme)
            Log.d(TAG, "Loaded default artwork resource: $resType/$name")
            break
          }
        }
        if (drawable != null) break
      }

      if (drawable == null) {
        drawable = applicationInfo.loadIcon(packageManager)
        Log.d(TAG, "Loaded default artwork from applicationInfo icon")
      }

      drawableToBitmap(drawable)
    } catch (e: Exception) {
      Log.e(TAG, "Default artwork load failed", e)
      generateFallbackLogo()
    }
  }

  private fun drawableToBitmap(drawable: Drawable): Bitmap {
    val size = 512
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, size, size)
    drawable.draw(canvas)
    return bitmap
  }

  private fun generateFallbackLogo(): Bitmap {
    val size = 512
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(18, 0, 32)
    }

    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(57, 255, 20)
      style = Paint.Style.STROKE
      strokeWidth = 18f
    }

    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(57, 255, 20)
      textSize = 138f
      textAlign = Paint.Align.CENTER
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    canvas.drawCircle(size / 2f, size / 2f, 226f, bg)
    canvas.drawCircle(size / 2f, size / 2f, 220f, ring)
    canvas.drawText("KD", size / 2f, size / 2f + 48f, text)

    return bitmap
  }

  private fun makeBrandedArtwork(albumArt: Bitmap?, logo: Bitmap?): Bitmap? {
    val base = albumArt ?: logo ?: return null
    val size = 512
    val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)

    canvas.drawBitmap(base, null, Rect(0, 0, size, size), null)

    if (albumArt != null && logo != null) {
      val logoSize = 150
      val padding = 16
      val left = size - logoSize - padding
      val top = padding
      val logoRect = Rect(left, top, left + logoSize, top + logoSize)

      val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(165, 0, 0, 0)
      }

      val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(232, 18, 0, 32)
      }

      val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 57, 255, 20)
        style = Paint.Style.STROKE
        strokeWidth = 7f
      }

      val cx = left + logoSize / 2f
      val cy = top + logoSize / 2f
      val radius = logoSize / 2f + 10f

      canvas.drawCircle(cx + 3f, cy + 5f, radius + 2f, shadowPaint)
      canvas.drawCircle(cx, cy, radius, bgPaint)
      canvas.drawCircle(cx, cy, radius, ringPaint)
      canvas.drawBitmap(logo, null, logoRect, null)
    }

    return output
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
      } else {
        @Suppress("DEPRECATION")
        audioManager?.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Audio focus request failed", e)
    }
  }

  fun setSleepTimerDeadline(deadline: Long) {
    sleepTimerDeadline = deadline
    sleepTimerJob?.cancel()

    if (deadline > 0L) {
      sleepTimerJob = scope.launch {
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining > 0L) delay(remaining)

        userPausedFromControls = true
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

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
        audioManager?.abandonAudioFocusRequest(audioFocusRequest!!)
      }
    } catch (_: Exception) {}
  }

  override fun onDestroy() {
    try {
      if (::mediaSession.isInitialized) mediaSession.release()
    } catch (_: Exception) {}

    try {
      stopForeground(STOP_FOREGROUND_REMOVE)
    } catch (_: Exception) {}

    exit()
    scope.cancel()
    super.onDestroy()
  }
}
