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

  private var lastAcceptedTrackIdentity = ""
  private var lastAcceptedArtworkKey = ""
  private var lastNotifyAt = 0L
  private var lastTrackChangedAt = 0L

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
    schedulePlayerRefresh()
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
    if (now - lastControlAt < 500L) return false
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
      override fun onSetRating(rating: RatingCompat?) { likeFromControl() }

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
    schedulePlayerRefresh()
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
    forceTrackReset()
    requestAudioFocus()
    runPlayerJs(nextJs())
    isPlaying = true
    updateAll()
    schedulePlayerRefresh()
  }

  private fun previousFromControl() {
    if (!controlAllowed()) return
    userPausedFromControls = false
    resetPendingTrack()
    forceTrackReset()
    requestAudioFocus()
    runPlayerJs(previousJs())
    isPlaying = true
    updateAll()
    schedulePlayerRefresh()
  }

  private fun likeFromControl() {
    if (!controlAllowed()) return
    isLiked = !isLiked
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

  // FIX: simple and clean — zero out position and record when track changed.
  // No complex filtering in notifyProgress anymore.
  private fun forceTrackReset() {
    currentPosition = 0L
    lastTrackChangedAt = SystemClock.elapsedRealtime()
  }

  private fun schedulePlayerRefresh() {
    scope.launch {
      delay(200L)
      runPlayerJs(refreshNowPlayingJs())
      delay(450L)
      runPlayerJs(refreshNowPlayingJs())
      delay(750L)
      runPlayerJs(refreshNowPlayingJs())
      delay(1200L)
      runPlayerJs(refreshNowPlayingJs())
      delay(2200L)
      runPlayerJs(refreshNowPlayingJs())
      delay(3500L)
      runPlayerJs(refreshNowPlayingJs())
    }
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

        var selectors=[
          'ytmusic-player-bar button[aria-label="Play"]',
          'ytmusic-player-bar [title="Play"]',
          'ytmusic-player-bar tp-yt-paper-icon-button[title="Play"]',
          'button[aria-label="Play"]',
          '[aria-label="Play"]',
          '[title="Play"]'
        ];

        for(var i=0;i<selectors.length;i++){
          var btn=document.querySelector(selectors[i]);
          if(btn){btn.click();return true;}
        }
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

        var selectors=[
          'ytmusic-player-bar button[aria-label="Pause"]',
          'ytmusic-player-bar [title="Pause"]',
          'ytmusic-player-bar tp-yt-paper-icon-button[title="Pause"]',
          'button[aria-label="Pause"]',
          '[aria-label="Pause"]',
          '[title="Pause"]'
        ];

        for(var i=0;i<selectors.length;i++){
          var btn=document.querySelector(selectors[i]);
          if(btn){btn.click();return true;}
        }
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

        var selectors=[
          'ytmusic-player-bar button[aria-label="Next"]',
          'ytmusic-player-bar button[aria-label^="Next"]',
          'ytmusic-player-bar [aria-label="Next"]',
          'ytmusic-player-bar [aria-label^="Next"]',
          'ytmusic-player-bar tp-yt-paper-icon-button[title="Next"]',
          'ytmusic-player-bar [title="Next"]',
          'button[aria-label="Next"]',
          'button[aria-label^="Next"]',
          '[aria-label="Next"]',
          '[aria-label^="Next"]',
          '[title="Next"]',
          '.next-button',
          '#next-button'
        ];

        for(var i=0;i<selectors.length;i++){
          var btn=document.querySelector(selectors[i]);
          if(btn){
            btn.click();
            setTimeout(function(){
              try{
                var media=document.querySelector('video,audio');
                if(media){
                  var p=media.play();
                  if(p&&p.catch)p.catch(function(){});
                }
              }catch(e){}
            },250);
            return true;
          }
        }

        try{
          var media=document.querySelector('video,audio');
          if(media && media.fastSeek && isFinite(media.duration)){
            media.fastSeek(Math.max(0, media.duration - 1));
            return true;
          }
        }catch(e){}
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

        var selectors=[
          'ytmusic-player-bar button[aria-label="Previous"]',
          'ytmusic-player-bar button[aria-label^="Previous"]',
          'ytmusic-player-bar [aria-label="Previous"]',
          'ytmusic-player-bar [aria-label^="Previous"]',
          'ytmusic-player-bar tp-yt-paper-icon-button[title="Previous"]',
          'ytmusic-player-bar [title="Previous"]',
          'button[aria-label="Previous"]',
          'button[aria-label^="Previous"]',
          '[aria-label="Previous"]',
          '[aria-label^="Previous"]',
          '[title="Previous"]',
          '.previous-button',
          '#previous-button'
        ];

        for(var i=0;i<selectors.length;i++){
          var btn=document.querySelector(selectors[i]);
          if(btn){
            btn.click();
            setTimeout(function(){
              try{
                var media=document.querySelector('video,audio');
                if(media){
                  var p=media.play();
                  if(p&&p.catch)p.catch(function(){});
                }
              }catch(e){}
            },250);
            return true;
          }
        }

        try{
          var media=document.querySelector('video,audio');
          if(media){
            media.currentTime=0;
            var p=media.play();
            if(p&&p.catch)p.catch(function(){});
            return true;
          }
        }catch(e){}
      }catch(e){}
      return false;
    })();
  """.trimIndent()

  private fun refreshNowPlayingJs(): String = """
    (function(){
      try{
        function clean(t){
          return (t||'')
            .replace(/\s+/g,' ')
            .replace(/Explicit|Video/g,'')
            .trim();
        }

        function up(u){
          if(!u)return'';
          return u
            .replace(/=w[0-9]+-h[0-9]+.*$/i,'=w800-h800-l90-rj')
            .replace(/\/s[0-9]+\//i,'/s800/');
        }

        function thumb(){
          var spots=[
            'ytmusic-player-bar img',
            '.player-bar img',
            '.miniplayer img',
            'ytmusic-player-page img'
          ];

          for(var x=0;x<spots.length;x++){
            var im=document.querySelector(spots[x]);
            if(im){
              var src=im.currentSrc||im.src||'';
              if(src&&(src.indexOf('ytimg')>-1||src.indexOf('googleusercontent')>-1))return up(src);
            }
          }

          return '';
        }

        var media=document.querySelector('video,audio');
        var seconds=0;
        var pos=0;

        if(media){
          if(isFinite(media.duration))seconds=Math.floor(media.duration||0);
          if(isFinite(media.currentTime))pos=Math.floor(media.currentTime||0);
        }

        var te=
          document.querySelector('ytmusic-player-bar .title')||
          document.querySelector('.title.ytmusic-player-bar')||
          document.querySelector('.content-info-wrapper .title')||
          document.querySelector('ytmusic-player-page .title');

        var ae=
          document.querySelector('ytmusic-player-bar .subtitle')||
          document.querySelector('.subtitle.ytmusic-player-bar')||
          document.querySelector('.content-info-wrapper .subtitle')||
          document.querySelector('ytmusic-player-page .subtitle');

        var title=te?clean(te.innerText||te.textContent):'';
        var artist=ae?clean(ae.innerText||ae.textContent):'';

        if(artist.indexOf(' • ')>-1)artist=artist.split(' • ')[0].trim();
        if(artist.indexOf(' - ')>-1)artist=artist.split(' - ')[0].trim();
        if(artist.indexOf(' · ')>-1)artist=artist.split(' · ')[0].trim();

        if(title&&artist&&window.NouTubeI&&window.NouTubeI.notify){
          window.NouTubeI.notify(title,artist,seconds,thumb());
        }

        if(media&&window.NouTubeI&&window.NouTubeI.notifyProgress){
          window.NouTubeI.notifyProgress(!media.paused,pos);
        }

        if(window.__kdDetectLiked&&window.NouTubeI&&window.NouTubeI.notifyLikeState){
          window.NouTubeI.notifyLikeState(window.__kdDetectLiked());
        }
      }catch(e){}
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
      .setSmallIcon(getNotificationIconResId())
      .setColor(Color.rgb(57, 255, 20))
      .setColorized(true)
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

  private fun getNotificationIconResId(): Int {
    val id = resources.getIdentifier("notification_icon", "drawable", packageName)
    return if (id != 0) id else applicationInfo.icon
  }

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

    if (cleanTitle.isBlank() || cleanArtist.isBlank()) return

    val incomingIdentity = "$cleanTitle|$cleanArtist"
    val incomingArtworkKey = "$incomingIdentity|$thumbnail"
    val now = SystemClock.elapsedRealtime()
    val trackChanged = incomingIdentity != lastAcceptedTrackIdentity

    if (trackChanged) {
      val pendingIdentity = "$pendingTitle|$pendingArtist"

      if (incomingIdentity != pendingIdentity) {
        pendingTitle = cleanTitle
        pendingArtist = cleanArtist
        pendingThumb = thumbnail
        pendingSince = now
        return
      }

      if (now - pendingSince < 500L) return
    }

    if (now - lastNotifyAt < 250L && !trackChanged) return

    lastNotifyAt = now

    if (trackChanged) {
      // FIX: reset position and duration together on track change so lock
      // screen never shows stale progress from the previous song
      forceTrackReset()
      currentDuration = if (seconds > 0L) seconds * 1000L else 5 * 60 * 1000L
    } else if (seconds > 0L) {
      currentDuration = seconds * 1000L
    }

    lastAcceptedTrackIdentity = incomingIdentity
    lastAcceptedArtworkKey = incomingArtworkKey
    currentTitle = cleanTitle
    currentArtist = cleanArtist
    resetPendingTrack()

    if (!userPausedFromControls) isPlaying = true

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

  // FIX: removed the over-aggressive looksLikeOldSongPosition and giantJumpForward
  // guards that were blocking valid position updates after a song skip.
  // Now: trust the position from the WebView directly. The only guard kept is
  // a backwards-jump detection for genuine seeks backward (which is fine to allow).
  // forceTrackReset() already zeros position on skip/next/prev, so stale positions
  // from the old song are naturally replaced by the first real update from the new one.
  fun notifyProgress(playing: Boolean, pos: Long) {
    if (playing) {
      isPlaying = true
      userPausedFromControls = false
    } else if (userPausedFromControls) {
      isPlaying = false
    }

    if (pos >= 0L) {
      val incomingMs = pos * 1000L
      // If position resets to near zero but track identity hasn't
      // been confirmed yet, reset our position early so the bar
      // doesn't show stale progress during the debounce window
      if (incomingMs <= 3000L && currentPosition > 10_000L) {
        currentPosition = 0L
      } else {
        currentPosition = incomingMs
      }
    }

    if (currentDuration <= 0L) currentDuration = 5 * 60 * 1000L

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
