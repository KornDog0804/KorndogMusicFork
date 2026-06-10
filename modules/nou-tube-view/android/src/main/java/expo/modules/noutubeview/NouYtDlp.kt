package expo.modules.noutubeview

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.lang.reflect.Method
import org.json.JSONObject

internal class NouYtDlp(private val context: Context) {
  companion object {
    @Volatile private var youtubeDLInitialized = false
    @Volatile private var ffmpegInitialized = false

    private const val DOWNLOAD_RELATIVE_PATH = "Music/NouTube"
    private const val SINGLE_AUDIO_FORMAT_ID = "bestaudio[ext=m4a]/bestaudio/best"
    private const val FALLBACK_AUDIO_FORMAT_ID = "bestaudio/best"
  }

  data class DownloadResult(
    val lastLine: String,
    val savedPath: String,
  )

  private data class PlaylistEntry(
    val id: String,
    val url: String,
    val title: String,
    val index: Int,
  )

  fun ensureInitialized() {
    ensureYoutubeDLInitialized()
    ensureFFmpegInitialized()
  }

  fun ensureYoutubeDLInitialized() {
    if (youtubeDLInitialized) return
    try {
      YoutubeDL.getInstance().init(context)
      youtubeDLInitialized = true
    } catch (e: Exception) {
      Log.e("NouTubeView", "Failed to initialize YoutubeDL", e)
      throw Exception("Failed to initialize YoutubeDL: ${e.message}")
    }
  }

  fun ensureFFmpegInitialized() {
    if (ffmpegInitialized) return
    try {
      FFmpeg.getInstance().init(context)
      ffmpegInitialized = true
    } catch (e: Exception) {
      Log.e("NouTubeView", "Failed to initialize FFmpeg", e)
      throw Exception("Failed to initialize FFmpeg: ${e.message}")
    }
  }

  fun getStreamUrl(videoUrl: String): Map<String, String> {
    ensureYoutubeDLInitialized()

    val request = YoutubeDLRequest(videoUrl)
    request.addOption("--dump-json")
    request.addOption("--no-playlist")
    request.addOption("-R", "1")
    request.addOption("--socket-timeout", "15")
    request.addOption("--extractor-args", "youtube:player_client=android,web,tv")
    request.addOption("-f", SINGLE_AUDIO_FORMAT_ID)

    val response = YoutubeDL.getInstance().execute(request)
    val json = JSONObject(response.out ?: throw Exception("yt-dlp returned empty output"))

    val title = json.optString("title", "NouTube Audio")
    val directUrl = json.optString("url", "")

    if (directUrl.isNotEmpty()) {
      Log.d("NouYtDlp", "Got direct audio URL for: $title")
      return mapOf("url" to directUrl, "title" to title)
    }

    val formats = json.optJSONArray("formats")
    if (formats != null) {
      var bestAudioUrl = ""
      var bestAbr = -1

      for (i in 0 until formats.length()) {
        val fmt = formats.optJSONObject(i) ?: continue
        val acodec = fmt.optString("acodec", "none")
        val vcodec = fmt.optString("vcodec", "none")
        val ext = fmt.optString("ext", "")
        val abr = fmt.optInt("abr", 0)
        val url = fmt.optString("url", "")
        val protocol = fmt.optString("protocol", "")

        if (protocol.contains("dash") || protocol.contains("m3u8")) continue

        if (
          acodec != "none" &&
          vcodec == "none" &&
          ext == "m4a" &&
          url.isNotEmpty() &&
          abr > bestAbr
        ) {
          bestAudioUrl = url
          bestAbr = abr
        }
      }

      if (bestAudioUrl.isNotEmpty()) {
        Log.d("NouYtDlp", "Using m4a audio ${bestAbr}kbps")
        return mapOf("url" to bestAudioUrl, "title" to title)
      }

      for (i in formats.length() - 1 downTo 0) {
        val fmt = formats.optJSONObject(i) ?: continue
        val acodec = fmt.optString("acodec", "none")
        val vcodec = fmt.optString("vcodec", "none")
        val url = fmt.optString("url", "")
        val protocol = fmt.optString("protocol", "")

        if (
          acodec != "none" &&
          vcodec == "none" &&
          url.isNotEmpty() &&
          !protocol.contains("dash") &&
          !protocol.contains("m3u8")
        ) {
          Log.d("NouYtDlp", "Using fallback audio format")
          return mapOf("url" to url, "title" to title)
        }
      }
    }

    throw Exception("No audio stream URL found")
  }

  fun listFormats(url: String): Map<String, Any> {
    ensureYoutubeDLInitialized()

    val isCollection = isAlbumOrPlaylistUrl(url)
    val title = if (isCollection) getCollectionTitleFast(url) else getSingleTitleFast(url)

    val options = mutableListOf<Map<String, String>>()

    options.add(
      mapOf(
        "formatId" to if (isCollection) "playlist" else SINGLE_AUDIO_FORMAT_ID,
        "label" to if (isCollection) "Download Full Playlist / Album" else "Audio only (Best quality)",
        "description" to if (isCollection) {
          "Deep scans playlist tracks, skips songs already saved, and downloads missing tracks one at a time"
        } else {
          "Best available YouTube Music audio with album art"
        },
      )
    )

    return mapOf(
      "title" to title,
      "formats" to options,
    )
  }

  fun downloadVideo(
    url: String,
    formatId: String,
    outputDir: String,
    onProgress: (progress: Float, etaInSeconds: Long, line: String?) -> Unit,
  ): DownloadResult {
    ensureInitialized()

    return if (isAlbumOrPlaylistUrl(url)) {
      downloadCollection(url, onProgress)
    } else {
      downloadSingle(url, formatId, null, onProgress)
    }
  }

  private fun downloadCollection(
    url: String,
    onProgress: (progress: Float, etaInSeconds: Long, line: String?) -> Unit,
  ): DownloadResult {
    val normalizedPlaylistUrl = normalizePlaylistUrl(url)
    val collectionTitle = cleanFolderName(getCollectionTitleFast(normalizedPlaylistUrl))
    val entries = extractPlaylistEntriesWithFallback(url)

    if (entries.isEmpty()) {
      throw Exception("YouTube blocked playlist scan. Songs and albums still work. Try opening the playlist in NouTube and downloading smaller sections.")
    }

    var savedUri: Uri? = null
    var savedCount = 0
    var alreadyHadCount = 0
    var failedCount = 0
    var lastLine = "Starting playlist download..."

    onProgress(0f, 0L, "Playlist found: ${entries.size} tracks • $collectionTitle")

    for ((zeroIndex, entry) in entries.withIndex()) {
      val trackNumber = zeroIndex + 1
      val total = entries.size
      val safeTitle = cleanFileName(entry.title.ifBlank { "Track $trackNumber" })

      if (alreadyDownloaded(safeTitle, collectionTitle)) {
        alreadyHadCount++

        val overallDone = (trackNumber.toFloat() / total.toFloat()) * 100f
        lastLine =
          "Track $trackNumber / $total already saved • Saved $savedCount • Already $alreadyHadCount • Failed $failedCount • $safeTitle"

        onProgress(overallDone.coerceIn(0f, 100f), 0L, lastLine)
        continue
      }

      try {
        val result = downloadSingle(
          url = entry.url,
          formatId = FALLBACK_AUDIO_FORMAT_ID,
          collectionFolder = collectionTitle,
        ) { songProgress, eta, line ->
          val safeSongProgress = songProgress.coerceIn(0f, 100f)
          val overall =
            (((trackNumber - 1).toFloat() + (safeSongProgress / 100f)) / total.toFloat()) * 100f

          val prettyLine =
            "Track $trackNumber / $total • Saved $savedCount • Already $alreadyHadCount • Failed $failedCount • $safeTitle • ${line ?: "Downloading..."}"

          lastLine = prettyLine
          onProgress(overall.coerceIn(0f, 100f), eta, prettyLine)
        }

        savedCount++
        savedUri = Uri.parse(result.savedPath)

        val overallDone = (trackNumber.toFloat() / total.toFloat()) * 100f
        lastLine =
          "Track $trackNumber / $total saved • Saved $savedCount • Already $alreadyHadCount • Failed $failedCount • $safeTitle"

        onProgress(overallDone.coerceIn(0f, 100f), 0L, lastLine)
      } catch (e: Exception) {
        failedCount++

        val overallDone = (trackNumber.toFloat() / total.toFloat()) * 100f
        lastLine =
          "Track $trackNumber / $total failed • Saved $savedCount • Already $alreadyHadCount • Failed $failedCount • $safeTitle • ${cleanError(e.message)}"

        Log.e("NouYtDlp", lastLine, e)
        onProgress(overallDone.coerceIn(0f, 100f), 0L, lastLine)
      }
    }

    if (savedCount <= 0 && alreadyHadCount <= 0) {
      throw Exception("Playlist failed. No tracks were saved. Last error: ${cleanError(lastLine)}")
    }

    val doneLine =
      "Playlist complete • Saved $savedCount • Already $alreadyHadCount • Failed $failedCount • Total ${entries.size}"

    onProgress(100f, 0L, doneLine)

    return DownloadResult(
      lastLine = doneLine,
      savedPath = savedUri?.toString() ?: "",
    )
  }

  private fun downloadSingle(
    url: String,
    formatId: String,
    collectionFolder: String?,
    onProgress: (progress: Float, etaInSeconds: Long, line: String?) -> Unit,
  ): DownloadResult {
    val tempDir = File(context.cacheDir, "yt-dlp-download-${System.currentTimeMillis()}").apply {
      mkdirs()
    }

    val request = YoutubeDLRequest(url)

    val safeFormatId = if (
      formatId.isBlank() ||
      formatId == "playlist" ||
      formatId == "album"
    ) {
      SINGLE_AUDIO_FORMAT_ID
    } else {
      formatId
    }

    request.addOption("-f", safeFormatId)
    request.addOption("-o", "${tempDir.absolutePath}/%(title)s.%(ext)s")
    request.addOption("--no-playlist")

    request.addOption("--extract-audio")
    request.addOption("--audio-format", "m4a")
    request.addOption("--audio-quality", "0")

    request.addOption("--embed-thumbnail")
    request.addOption("--convert-thumbnails", "jpg")
    request.addOption("--write-thumbnail")
    request.addOption("--add-metadata")
    request.addOption("--embed-metadata")

    request.addOption("--parse-metadata", "%(title)s:%(meta_title)s")
    request.addOption("--parse-metadata", "%(uploader)s:%(meta_artist)s")
    request.addOption("--parse-metadata", "%(channel)s:%(meta_artist)s")

    request.addOption("--extractor-args", "youtube:player_client=android,web,tv")
    request.addOption("--ignore-errors")
    request.addOption("--no-abort-on-error")
    request.addOption("-R", "2")
    request.addOption("--socket-timeout", "25")

    var lastLine = ""

    try {
      YoutubeDL.getInstance().execute(request) { progress, etaInSeconds, line ->
        lastLine = line ?: lastLine
        onProgress(progress, etaInSeconds, cleanProgressLine(line))
      }

      val outputFiles = tempDir
        .walkTopDown()
        .filter { it.isFile && isPlayableAudioOutput(it) }
        .sortedBy { it.name.lowercase() }
        .toList()

      if (outputFiles.isEmpty()) {
        throw Exception("Download completed but no playable audio file was produced")
      }

      var savedUri: Uri? = null

      for (file in outputFiles) {
        savedUri = publishToMusic(file, collectionFolder)
      }

      return DownloadResult(
        lastLine = lastLine,
        savedPath = savedUri?.toString() ?: "",
      )
    } finally {
      tempDir.deleteRecursively()
    }
  }

  // FIX: Stop hammering YouTube with 24 sequential requests before downloading anything.
  // Now: try flat scan first, break as soon as we have entries, only deep scan if flat got nothing.
  private fun extractPlaylistEntriesWithFallback(url: String): List<PlaylistEntry> {
    val normalizedUrl = normalizePlaylistUrl(url)
    val originalUrl = url.trim()

    val urlsToTry = listOf(normalizedUrl, originalUrl)
      .filter { it.isNotBlank() }
      .distinct()

    val clientAttempts = listOf(
      "android,web,tv",
      "web,android,tv",
      "tv,web,android",
      "android",
      "web",
      "tv"
    )

    val allEntries = linkedMapOf<String, PlaylistEntry>()
    var lastError: Exception? = null

    outer@ for (tryUrl in urlsToTry) {
      for (clients in clientAttempts) {
        // Try flat scan first — fast, low cost
        try {
          val flatEntries = extractPlaylistEntries(tryUrl, clients, flat = true)
          for (entry in flatEntries) allEntries[entry.url] = entry
          Log.d("NouYtDlp", "Flat playlist scan found ${flatEntries.size} using $clients")
        } catch (e: Exception) {
          lastError = e
          Log.e("NouYtDlp", "Flat playlist extract failed with clients=$clients", e)
        }

        // Got enough from flat — no need to deep scan or try more clients
        if (allEntries.isNotEmpty()) break@outer

        // Flat got nothing — try deep scan with same client combo before moving on
        try {
          val deepEntries = extractPlaylistEntries(tryUrl, clients, flat = false)
          for (entry in deepEntries) allEntries[entry.url] = entry
          Log.d("NouYtDlp", "Deep playlist scan found ${deepEntries.size} using $clients")
        } catch (e: Exception) {
          lastError = e
          Log.e("NouYtDlp", "Deep playlist extract failed with clients=$clients", e)
        }

        // Got results from deep scan — done
        if (allEntries.isNotEmpty()) break@outer

        // Hit the cap mid-loop
        if (allEntries.size >= 250) break@outer
      }
    }

    if (allEntries.isNotEmpty()) {
      return allEntries.values.sortedBy { it.index }.toList()
    }

    if (lastError != null) {
      throw Exception(cleanError(lastError.message))
    }

    return emptyList()
  }

  private fun extractPlaylistEntries(url: String, clients: String, flat: Boolean): List<PlaylistEntry> {
    ensureYoutubeDLInitialized()

    val request = YoutubeDLRequest(normalizePlaylistUrl(url))
    request.addOption("--dump-single-json")
    request.addOption("--yes-playlist")
    request.addOption("--ignore-errors")
    request.addOption("--no-abort-on-error")
    request.addOption("--extractor-args", "youtube:player_client=$clients")
    request.addOption("--playlist-end", "9999")
    request.addOption("-R", "2")
    request.addOption("--socket-timeout", "35")

    if (flat) {
      request.addOption("--flat-playlist")
    }

    val response = YoutubeDL.getInstance().execute(request)
    val out = response.out ?: ""

    if (out.isBlank()) return emptyList()

    return parsePlaylistEntries(out)
  }

  private fun parsePlaylistEntries(out: String): List<PlaylistEntry> {
    val json = JSONObject(out)
    val entries = json.optJSONArray("entries") ?: return emptyList()
    val results = mutableListOf<PlaylistEntry>()

    for (i in 0 until entries.length()) {
      val item = entries.optJSONObject(i) ?: continue

      val id = item.optString("id", "").trim()
      val rawUrl = item.optString("url", "").trim()
      val webpageUrl = item.optString("webpage_url", "").trim()
      val title = item.optString("title", "Track ${i + 1}").trim()

      val resolvedUrl = when {
        webpageUrl.startsWith("http") -> webpageUrl
        id.isNotBlank() -> "https://www.youtube.com/watch?v=$id"
        rawUrl.startsWith("http") -> rawUrl
        rawUrl.isNotBlank() -> "https://www.youtube.com/watch?v=$rawUrl"
        else -> ""
      }

      if (resolvedUrl.isNotBlank()) {
        results.add(
          PlaylistEntry(
            id = id.ifBlank { rawUrl },
            url = resolvedUrl,
            title = title,
            index = i + 1,
          )
        )
      }
    }

    return results.distinctBy { it.url }
  }

  // FIX: --playlist-end 1 instead of 9999 — we only need the title, not the full scan
  private fun getCollectionTitleFast(url: String): String {
    return try {
      val request = YoutubeDLRequest(normalizePlaylistUrl(url))
      request.addOption("--dump-single-json")
      request.addOption("--flat-playlist")
      request.addOption("--yes-playlist")
      request.addOption("--ignore-errors")
      request.addOption("--extractor-args", "youtube:player_client=android,web,tv")
      request.addOption("--playlist-end", "1")
      request.addOption("-R", "1")
      request.addOption("--socket-timeout", "12")

      val response = YoutubeDL.getInstance().execute(request)
      val json = JSONObject(response.out ?: "")

      json.optString(
        "playlist_title",
        json.optString("title", "NouTube Collection")
      ).ifBlank { "NouTube Collection" }
    } catch (_: Exception) {
      "NouTube Collection"
    }
  }

  private fun getSingleTitleFast(url: String): String {
    return try {
      val request = YoutubeDLRequest(url)
      request.addOption("--dump-json")
      request.addOption("--no-playlist")
      request.addOption("-R", "1")
      request.addOption("--socket-timeout", "10")
      request.addOption("--extractor-args", "youtube:player_client=android,web,tv")
      request.addOption("-f", SINGLE_AUDIO_FORMAT_ID)

      val response = YoutubeDL.getInstance().execute(request)
      val json = JSONObject(response.out ?: "")
      json.optString("title", "NouTube Audio").ifBlank { "NouTube Audio" }
    } catch (_: Exception) {
      "NouTube Audio"
    }
  }

  private fun alreadyDownloaded(title: String, collectionFolder: String?): Boolean {
    val cleanNeedle = normalizeForMatch(title)
    if (cleanNeedle.isBlank()) return false

    val projection = arrayOf(
      MediaStore.Audio.Media.DISPLAY_NAME,
      MediaStore.Audio.Media.TITLE,
      MediaStore.Audio.Media.RELATIVE_PATH
    )

    val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
    val args = arrayOf("%$DOWNLOAD_RELATIVE_PATH%")

    var cursor: Cursor? = null

    return try {
      cursor = context.contentResolver.query(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        projection,
        selection,
        args,
        null
      )

      if (cursor == null) return false

      val displayIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
      val titleIdx = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
      val pathIdx = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)

      while (cursor.moveToNext()) {
        val displayName = if (displayIdx >= 0) cursor.getString(displayIdx).orEmpty() else ""
        val mediaTitle = if (titleIdx >= 0) cursor.getString(titleIdx).orEmpty() else ""
        val relativePath = if (pathIdx >= 0) cursor.getString(pathIdx).orEmpty() else ""

        if (!collectionFolder.isNullOrBlank() && relativePath.isNotBlank()) {
          val cleanPath = normalizeForMatch(relativePath)
          val cleanFolder = normalizeForMatch(collectionFolder)

          if (!cleanPath.contains(cleanFolder)) {
            // Do not hard reject. Some older files may live directly in Music/NouTube.
          }
        }

        val cleanDisplay = normalizeForMatch(displayName)
        val cleanTitle = normalizeForMatch(mediaTitle)

        if (cleanDisplay.isNotBlank() && (cleanDisplay.contains(cleanNeedle) || cleanNeedle.contains(cleanDisplay))) return true
        if (cleanTitle.isNotBlank() && (cleanTitle.contains(cleanNeedle) || cleanNeedle.contains(cleanTitle))) return true
      }

      false
    } catch (e: Exception) {
      Log.e("NouYtDlp", "alreadyDownloaded check failed", e)
      false
    } finally {
      cursor?.close()
    }
  }

  private fun normalizePlaylistUrl(url: String): String {
    val trimmed = url.trim()

    val listId = Regex("""[?&]list=([^&]+)""")
      .find(trimmed)
      ?.groupValues
      ?.getOrNull(1)
      ?.trim()

    if (!listId.isNullOrBlank()) {
      return "https://www.youtube.com/playlist?list=$listId"
    }

    if (trimmed.startsWith("PL") || trimmed.startsWith("OLAK")) {
      return "https://www.youtube.com/playlist?list=$trimmed"
    }

    return trimmed
      .replace("https://music.youtube.com/playlist", "https://www.youtube.com/playlist")
      .replace("http://music.youtube.com/playlist", "https://www.youtube.com/playlist")
  }

  private fun isAlbumOrPlaylistUrl(url: String): Boolean {
    val lower = url.lowercase()
    return lower.contains("list=") ||
      lower.contains("/playlist") ||
      lower.contains("/browse/") ||
      lower.contains("olak") ||
      lower.contains("olāk") ||
      lower.startsWith("pl") ||
      lower.startsWith("olak")
  }

  private fun isPlayableAudioOutput(file: File): Boolean {
    return when (file.extension.lowercase()) {
      "m4a", "mp3", "aac", "opus", "ogg", "flac" -> true
      else -> false
    }
  }

  private fun cleanFolderName(name: String): String {
    return cleanFileName(name).ifBlank { "NouTube Collection" }
  }

  private fun cleanFileName(name: String): String {
    return name
      .replace("/", "-")
      .replace("\\", "-")
      .replace(":", " -")
      .replace("*", "")
      .replace("?", "")
      .replace("\"", "")
      .replace("<", "")
      .replace(">", "")
      .replace("|", "")
      .replace(Regex("\\s+"), " ")
      .trim()
  }

  private fun normalizeForMatch(value: String): String {
    return value
      .lowercase()
      .replace(Regex("\\.(m4a|mp3|aac|opus|ogg|flac)$"), "")
      .replace(Regex("^\\d+\\s*-\\s*"), "")
      .replace(Regex("\\[[^\\]]*\\]"), "")
      .replace(Regex("\\([^)]*official[^)]*\\)"), "")
      .replace(Regex("\\([^)]*audio[^)]*\\)"), "")
      .replace(Regex("\\([^)]*video[^)]*\\)"), "")
      .replace(Regex("[^a-z0-9]+"), " ")
      .replace(Regex("\\s+"), " ")
      .trim()
  }

  private fun cleanProgressLine(line: String?): String? {
    if (line.isNullOrBlank()) return line

    return line
      .replace("WARNING:", "Warning:")
      .replace(Regex("\\s+"), " ")
      .trim()
      .take(180)
  }

  private fun cleanError(message: String?): String {
    val raw = message.orEmpty()

    return when {
      raw.contains("403", ignoreCase = true) || raw.contains("Forbidden", ignoreCase = true) ->
        "YouTube blocked this playlist scan. Retry later or use albums/smaller playlists."

      raw.contains("caller does not have permission", ignoreCase = true) ->
        "YouTube blocked access to this playlist."

      raw.contains("Incomplete yt initial data", ignoreCase = true) ->
        "YouTube returned incomplete playlist data."

      raw.contains("Requested format is not available", ignoreCase = true) ->
        "That track format was unavailable. NouTube skipped it."

      raw.isBlank() ->
        "Unknown download error."

      else -> raw.replace(Regex("\\s+"), " ").trim().take(220)
    }
  }

  fun update() {
    val youtubeDL = YoutubeDL.getInstance()
    val updateChannel = runCatching { resolveStableUpdateChannel() }.getOrNull()

    val contextAndChannelMethod = updateChannel?.let { channel ->
      findUpdateYoutubeDLMethod(channel) { method ->
        method.parameterTypes.size == 2 &&
          isContextParameter(method.parameterTypes[0]) &&
          isUpdateChannelParameter(method.parameterTypes[1], channel)
      }
    }

    if (contextAndChannelMethod != null) {
      contextAndChannelMethod.invoke(youtubeDL, context, updateChannel)
      return
    }

    val channelOnlyMethod = updateChannel?.let { channel ->
      findUpdateYoutubeDLMethod(channel) { method ->
        method.parameterTypes.size == 1 &&
          isUpdateChannelParameter(method.parameterTypes[0], channel)
      }
    }

    if (channelOnlyMethod != null) {
      channelOnlyMethod.invoke(youtubeDL, updateChannel)
      return
    }

    val contextOnlyMethod = findUpdateYoutubeDLMethod(updateChannel) { method ->
      method.parameterTypes.size == 1 && isContextParameter(method.parameterTypes[0])
    }

    if (contextOnlyMethod != null) {
      contextOnlyMethod.invoke(youtubeDL, context)
      return
    }

    val noArgMethod = findUpdateYoutubeDLMethod(updateChannel) { method ->
      method.parameterTypes.isEmpty()
    }

    if (noArgMethod != null) {
      noArgMethod.invoke(youtubeDL)
      return
    }

    throw Exception("updateYoutubeDL method not found")
  }

  private fun resolveStableUpdateChannel(): Any {
    val candidates = listOf(
      "com.yausername.youtubedl_android.YoutubeDL\$UpdateChannel",
      "com.yausername.youtubedl_android.UpdateChannel",
    )

    for (className in candidates) {
      try {
        val clazz = Class.forName(className)

        try {
          val stableField = clazz.getField("_STABLE")
          return stableField.get(null) ?: throw Exception("_STABLE update channel field is null")
        } catch (_: NoSuchFieldException) {}

        try {
          val stableField = clazz.getField("STABLE")
          return stableField.get(null) ?: throw Exception("STABLE update channel field is null")
        } catch (_: NoSuchFieldException) {}

        if (clazz.isEnum) {
          val stableEnum = clazz.enumConstants?.firstOrNull {
            (it as? Enum<*>)?.name == "STABLE"
          }

          if (stableEnum != null) return stableEnum
        }
      } catch (_: Exception) {}
    }

    throw Exception("Unable to resolve yt-dlp update channel")
  }

  private fun isContextParameter(parameterType: Class<*>): Boolean =
    parameterType.isAssignableFrom(Context::class.java)

  private fun isUpdateChannelParameter(parameterType: Class<*>, updateChannel: Any): Boolean =
    parameterType.isAssignableFrom(updateChannel.javaClass)

  private fun findUpdateYoutubeDLMethod(
    updateChannel: Any?,
    predicate: (Method) -> Boolean
  ): Method? {
    val candidateNames = setOf("updateYoutubeDL", "updateYoutubeDl")

    return YoutubeDL::class.java.methods.firstOrNull { method ->
      method.name in candidateNames && predicate(method)
    } ?: if (updateChannel == null) {
      null
    } else {
      YoutubeDL::class.java.methods.firstOrNull { method ->
        method.name in candidateNames &&
          method.parameterTypes.any { isUpdateChannelParameter(it, updateChannel) }
      }
    }
  }

  // FIX: removed double-nested inputStream() — outer stream was opened and leaked,
  // inner stream did the actual copy, outer was never closed properly
  private fun publishToMusic(sourceFile: File, collectionFolder: String? = null): Uri {
    val extension = sourceFile.extension.lowercase()
    val mimeType = MimeTypeMap.getSingleton()
      .getMimeTypeFromExtension(extension)
      .orEmpty()

    val relativePath = if (collectionFolder.isNullOrBlank()) {
      DOWNLOAD_RELATIVE_PATH
    } else {
      "$DOWNLOAD_RELATIVE_PATH/${cleanFolderName(collectionFolder)}"
    }

    val values = ContentValues().apply {
      put(MediaStore.Audio.Media.DISPLAY_NAME, sourceFile.name)
      put(MediaStore.Audio.Media.TITLE, sourceFile.nameWithoutExtension)

      if (mimeType.isNotBlank()) {
        put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
      }

      put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
      put(MediaStore.Audio.Media.IS_PENDING, 1)
    }

    val resolver = context.contentResolver
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val uri = resolver.insert(collection, values)
      ?: throw Exception("Failed to create audio entry")

    try {
      resolver.openOutputStream(uri)?.use { output ->
        sourceFile.inputStream().use { input ->
          input.copyTo(output)
        }
      } ?: throw Exception("Failed to open MediaStore output stream")

      values.clear()
      values.put(MediaStore.Audio.Media.IS_PENDING, 0)
      resolver.update(uri, values, null, null)

      return uri
    } catch (e: Exception) {
      resolver.delete(uri, null, null)
      throw e
    }
  }
}

private fun Int?.orZero(): Int = this ?: 0
