package expo.modules.noutubeview

import android.content.ContentValues
import android.content.Context
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
    request.addOption("--extractor-args", "youtube:player_client=android,web")
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
    val title = if (isCollection) {
      getCollectionTitleFast(url)
    } else {
      getSingleTitleFast(url)
    }

    val options = mutableListOf<Map<String, String>>()

    options.add(
      mapOf(
        "formatId" to if (isCollection) "playlist" else SINGLE_AUDIO_FORMAT_ID,
        "label" to if (isCollection) "Download Full Playlist / Album" else "Audio only (Best quality)",
        "description" to if (isCollection) {
          "Downloads one track at a time with track count progress"
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
    val entries = extractPlaylistEntries(normalizedPlaylistUrl)

    if (entries.isEmpty()) {
      throw Exception("Could not read playlist tracks")
    }

    var savedUri: Uri? = null
    var savedCount = 0
    var failedCount = 0
    var lastLine = "Starting playlist download..."

    onProgress(0f, 0L, "Playlist found: ${entries.size} tracks • $collectionTitle")

    for ((zeroIndex, entry) in entries.withIndex()) {
      val trackNumber = zeroIndex + 1
      val total = entries.size
      val safeTitle = cleanFileName(entry.title.ifBlank { "Track $trackNumber" })

      try {
        val result = downloadSingle(
          url = entry.url,
          formatId = FALLBACK_AUDIO_FORMAT_ID,
          collectionFolder = collectionTitle,
        ) { songProgress, eta, line ->
          val safeSongProgress = songProgress.coerceIn(0f, 100f)
          val overall = (((trackNumber - 1).toFloat() + (safeSongProgress / 100f)) / total.toFloat()) * 100f

          val prettyLine =
            "Track $trackNumber / $total • Saved $savedCount • Skipped $failedCount • $safeTitle • ${line ?: "Downloading..."}"

          lastLine = prettyLine
          onProgress(overall.coerceIn(0f, 100f), eta, prettyLine)
        }

        savedCount++
        savedUri = Uri.parse(result.savedPath)

        val overallDone = (trackNumber.toFloat() / total.toFloat()) * 100f
        lastLine = "Track $trackNumber / $total saved • Saved $savedCount • Skipped $failedCount • $safeTitle"

        onProgress(overallDone.coerceIn(0f, 100f), 0L, lastLine)
      } catch (e: Exception) {
        failedCount++

        val overallDone = (trackNumber.toFloat() / total.toFloat()) * 100f
        lastLine = "Track $trackNumber / $total skipped • Saved $savedCount • Skipped $failedCount • $safeTitle • ${e.message}"

        Log.e("NouYtDlp", lastLine, e)
        onProgress(overallDone.coerceIn(0f, 100f), 0L, lastLine)
      }
    }

    if (savedCount <= 0) {
      throw Exception("Playlist failed. No tracks were saved. Last error: $lastLine")
    }

    val doneLine = if (failedCount > 0) {
      "Playlist complete • Saved $savedCount • Skipped $failedCount • Total ${entries.size}"
    } else {
      "Playlist complete • Saved $savedCount / ${entries.size}"
    }

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

    request.addOption("--extractor-args", "youtube:player_client=android,web")
    request.addOption("--ignore-errors")
    request.addOption("--no-abort-on-error")
    request.addOption("-R", "2")
    request.addOption("--socket-timeout", "25")

    var lastLine = ""

    try {
      YoutubeDL.getInstance().execute(request) { progress, etaInSeconds, line ->
        lastLine = line ?: lastLine
        onProgress(progress, etaInSeconds, line)
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

  private fun extractPlaylistEntries(url: String): List<PlaylistEntry> {
    ensureYoutubeDLInitialized()

    val normalizedUrl = normalizePlaylistUrl(url)

    val request = YoutubeDLRequest(normalizedUrl)
    request.addOption("--dump-single-json")
    request.addOption("--flat-playlist")
    request.addOption("--yes-playlist")
    request.addOption("--ignore-errors")
    request.addOption("--no-abort-on-error")
    request.addOption("--extractor-args", "youtube:player_client=android,web")
    request.addOption("-R", "2")
    request.addOption("--socket-timeout", "25")

    val response = YoutubeDL.getInstance().execute(request)
    val out = response.out ?: ""

    if (out.isBlank()) return emptyList()

    val json = JSONObject(out)
    val entries = json.optJSONArray("entries") ?: return emptyList()
    val results = mutableListOf<PlaylistEntry>()

    for (i in 0 until entries.length()) {
      val item = entries.optJSONObject(i) ?: continue

      val id = item.optString("id", "").trim()
      val rawUrl = item.optString("url", "").trim()
      val title = item.optString("title", "Track ${i + 1}").trim()

      val resolvedUrl = when {
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

  private fun getCollectionTitleFast(url: String): String {
    return try {
      val request = YoutubeDLRequest(normalizePlaylistUrl(url))
      request.addOption("--dump-single-json")
      request.addOption("--flat-playlist")
      request.addOption("--yes-playlist")
      request.addOption("--ignore-errors")
      request.addOption("--extractor-args", "youtube:player_client=android,web")
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
      request.addOption("--extractor-args", "youtube:player_client=android,web")
      request.addOption("-f", SINGLE_AUDIO_FORMAT_ID)

      val response = YoutubeDL.getInstance().execute(request)
      val json = JSONObject(response.out ?: "")
      json.optString("title", "NouTube Audio").ifBlank { "NouTube Audio" }
    } catch (_: Exception) {
      "NouTube Audio"
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
