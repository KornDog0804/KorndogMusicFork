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
    private const val AUDIO_FORMAT_ID = "bestaudio[ext=m4a]/bestaudio/best"
  }

  data class DownloadResult(
    val lastLine: String,
    val savedPath: String,
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
    request.addOption("-f", AUDIO_FORMAT_ID)

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

    val isAlbumOrPlaylist = isAlbumOrPlaylistUrl(url)

    val request = YoutubeDLRequest(url)
    request.addOption("--dump-json")
    if (!isAlbumOrPlaylist) {
      request.addOption("--no-playlist")
    }
    request.addOption("-R", "1")
    request.addOption("--socket-timeout", "10")
    request.addOption("-f", AUDIO_FORMAT_ID)

    val response = YoutubeDL.getInstance().execute(request)
    val json = JSONObject(response.out ?: throw Exception("yt-dlp returned empty format output"))

    val title = json.optString(
      "playlist_title",
      json.optString("album", json.optString("title", "NouTube Audio"))
    )

    val options = mutableListOf<Map<String, String>>()

    options.add(
      mapOf(
        "formatId" to AUDIO_FORMAT_ID,
        "label" to if (isAlbumOrPlaylist) "Album / Playlist (Best quality)" else "Audio only (Best quality)",
        "description" to if (isAlbumOrPlaylist) {
          "Download all tracks as best available YouTube Music audio"
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

    val isAlbumOrPlaylist = isAlbumOrPlaylistUrl(url)

    val tempDir = File(context.cacheDir, "yt-dlp-download-${System.currentTimeMillis()}").apply {
      mkdirs()
    }

    val request = YoutubeDLRequest(url)

    val safeFormatId = if (
      formatId.isBlank() ||
      formatId == "playlist" ||
      formatId == "album"
    ) {
      AUDIO_FORMAT_ID
    } else {
      formatId
    }

    request.addOption("-f", safeFormatId)

    if (isAlbumOrPlaylist) {
      request.addOption("--yes-playlist")
      request.addOption("--ignore-errors")
      request.addOption(
        "-o",
        "${tempDir.absolutePath}/%(playlist_title,album,title|NouTube Collection)s/%(playlist_index,track_number|000)03d - %(title)s.%(ext)s"
      )
    } else {
      request.addOption("-o", "${tempDir.absolutePath}/%(title)s.%(ext)s")
      request.addOption("--no-playlist")
    }

    request.addOption("--extract-audio")
    request.addOption("--audio-format", "m4a")
    request.addOption("--audio-quality", "0")
    request.addOption("--embed-thumbnail")
    request.addOption("--convert-thumbnails", "jpg")
    request.addOption("--add-metadata")
    request.addOption("--parse-metadata", "%(title)s:%(meta_title)s")
    request.addOption("--parse-metadata", "%(uploader)s:%(meta_artist)s")
    request.addOption("-R", "2")
    request.addOption("--socket-timeout", "20")

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
        val collectionFolder = if (isAlbumOrPlaylist) {
          val parent = file.parentFile
          if (parent != null && parent.absolutePath != tempDir.absolutePath) {
            cleanFolderName(parent.name)
          } else {
            "Collection"
          }
        } else {
          null
        }

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

  private fun isAlbumOrPlaylistUrl(url: String): Boolean {
    val lower = url.lowercase()
    return lower.contains("list=") ||
      lower.contains("/playlist") ||
      lower.contains("/browse/") ||
      lower.contains("olak") ||
      lower.contains("olāk")
  }

  private fun isPlayableAudioOutput(file: File): Boolean {
    return when (file.extension.lowercase()) {
      "m4a", "mp3", "aac", "opus", "ogg", "flac" -> true
      else -> false
    }
  }

  private fun cleanFolderName(name: String): String {
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
      .trim()
      .ifBlank { "NouTube Collection" }
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
