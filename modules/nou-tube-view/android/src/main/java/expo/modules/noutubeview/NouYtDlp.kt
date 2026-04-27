package expo.modules.noutubeview

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
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
    @Volatile
    private var youtubeDLInitialized = false

    @Volatile
    private var ffmpegInitialized = false
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

  /**
   * Extract direct stream URL for casting.
   *
   * YouTube Premium serves higher quality but often DASH (separate audio/video).
   * DLNA/Cast needs a single playable URL.
   *
   * Strategy:
   *   1. Try best combined mp4 (single URL, DLNA-safe) up to 720p
   *   2. Try best m4a audio-only (DLNA handles audio-only fine)
   *   3. Try any combined format
   *   4. Fall back to absolute best
   */
  fun getStreamUrl(videoUrl: String): Map<String, String> {
    ensureYoutubeDLInitialized()

    val request = YoutubeDLRequest(videoUrl)
    request.addOption("--dump-json")
    request.addOption("--no-playlist")
    request.addOption("-R", "1")
    request.addOption("--socket-timeout", "15")
    // Priority chain for DLNA/Cast compatibility:
    // 1. Combined mp4 up to 720p — single URL, universally supported
    // 2. Combined mp4 up to 1080p
    // 3. Best m4a audio — DLNA plays audio-only fine
    // 4. Any combined format
    // 5. Absolute fallback
    request.addOption(
      "-f",
      "best[ext=mp4][height<=720]/best[ext=mp4][height<=1080]/best[ext=mp4]/bestaudio[ext=m4a]/bestaudio/best"
    )

    val response = YoutubeDL.getInstance().execute(request)
    val json = JSONObject(response.out ?: throw Exception("yt-dlp returned empty output"))

    val title = json.optString("title", "NouTube Video")
    val directUrl = json.optString("url", "")

    if (directUrl.isNotEmpty()) {
      Log.d("NouYtDlp", "Got direct URL for: $title")
      return mapOf("url" to directUrl, "title" to title)
    }

    // Walk formats manually — pick best single-URL combined or audio-only
    val formats = json.optJSONArray("formats")
    if (formats != null) {
      // Pass 1: best combined mp4 with both video+audio in single URL
      var bestCombinedUrl = ""
      var bestHeight = 0
      for (i in 0 until formats.length()) {
        val fmt = formats.optJSONObject(i) ?: continue
        val vcodec = fmt.optString("vcodec", "none")
        val acodec = fmt.optString("acodec", "none")
        val ext = fmt.optString("ext", "")
        val height = fmt.optInt("height", 0)
        val url = fmt.optString("url", "")
        val protocol = fmt.optString("protocol", "")
        // Skip DASH segments — they won't work with DLNA
        if (protocol.contains("dash") || protocol.contains("m3u8")) continue
        if (vcodec != "none" && acodec != "none" && ext == "mp4"
          && height in 1..720 && url.isNotEmpty() && height > bestHeight) {
          bestCombinedUrl = url
          bestHeight = height
        }
      }
      if (bestCombinedUrl.isNotEmpty()) {
        Log.d("NouYtDlp", "Using combined mp4 ${bestHeight}p")
        return mapOf("url" to bestCombinedUrl, "title" to title)
      }

      // Pass 2: best m4a audio-only (Premium serves 256kbps here)
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
        if (acodec != "none" && vcodec == "none" && ext == "m4a"
          && url.isNotEmpty() && abr > bestAbr) {
          bestAudioUrl = url
          bestAbr = abr
        }
      }
      if (bestAudioUrl.isNotEmpty()) {
        Log.d("NouYtDlp", "Using m4a audio ${bestAbr}kbps")
        return mapOf("url" to bestAudioUrl, "title" to title)
      }

      // Pass 3: any non-DASH url
      for (i in formats.length() - 1 downTo 0) {
        val fmt = formats.optJSONObject(i) ?: continue
        val url = fmt.optString("url", "")
        val protocol = fmt.optString("protocol", "")
        if (url.isNotEmpty() && !protocol.contains("dash") && !protocol.contains("m3u8")) {
          Log.d("NouYtDlp", "Using fallback format")
          return mapOf("url" to url, "title" to title)
        }
      }
    }

    throw Exception("No DLNA-compatible stream URL found")
  }

  fun listFormats(url: String): Map<String, Any> {
    ensureYoutubeDLInitialized()

    val request = YoutubeDLRequest(url)
    request.addOption("--dump-json")
    request.addOption("--no-playlist")
    request.addOption("-R", "1")
    request.addOption("--socket-timeout", "5")
    val response = YoutubeDL.getInstance().execute(request)
    val json = JSONObject(response.out ?: throw Exception("yt-dlp returned empty format output"))
    val formats = (0 until json.optJSONArray("formats")?.length().orZero())
      .mapNotNull { index -> json.optJSONArray("formats")?.optJSONObject(index) }

    val options = mutableListOf<Map<String, String>>()
    val videoFormats = formats.filter {
      it.optString("vcodec") != "none" && it.optInt("height", 0) > 0
    }
    val maxHeight = videoFormats.maxOfOrNull { it.optInt("height", 0) } ?: 0

    if (maxHeight > 1080) {
      options.add(mapOf(
        "formatId" to "bestvideo+bestaudio/best",
        "label" to "Best quality",
        "description" to "Up to ${maxHeight}p video + audio",
      ))
    }

    if (videoFormats.any { it.optInt("height", 0) == 1080 }) {
      options.add(mapOf(
        "formatId" to "bestvideo[height<=1080]+bestaudio/best[height<=1080]",
        "label" to "1080p",
        "description" to "1080p video + audio",
      ))
    }

    if (videoFormats.any { it.optInt("height", 0) == 720 }) {
      options.add(mapOf(
        "formatId" to "bestvideo[height<=720]+bestaudio/best[height<=720]",
        "label" to "720p",
        "description" to "720p video + audio",
      ))
    }

    if (formats.any { it.optString("vcodec") == "none" && it.optString("acodec") != "none" }) {
      options.add(mapOf(
        "formatId" to "bestaudio[ext=m4a]/bestaudio",
        "label" to "Audio only (Best quality)",
        "description" to "Best available audio with album art",
      ))
    }

    return mapOf(
      "title" to json.optString("title"),
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

    val tempDir = File(context.cacheDir, "yt-dlp-download-${System.currentTimeMillis()}").apply { mkdirs() }
    val request = YoutubeDLRequest(url)
    request.addOption("-f", formatId)
    request.addOption("-o", "${tempDir.absolutePath}/%(title)s.%(ext)s")
    request.addOption("--no-playlist")
    request.addOption("--merge-output-format", "mp4")
    request.addOption("--embed-thumbnail")
    request.addOption("--add-metadata")
    request.addOption("--parse-metadata", "%(title)s:%(meta_title)s")
    request.addOption("--parse-metadata", "%(uploader)s:%(meta_artist)s")
    var lastLine = ""

    try {
      YoutubeDL.getInstance().execute(request) { progress, etaInSeconds, line ->
        lastLine = line ?: lastLine
        onProgress(progress, etaInSeconds, line)
      }

      val outputFile = tempDir
        .listFiles()
        ?.filter { it.isFile }
        ?.maxByOrNull { it.lastModified() }
        ?: throw Exception("Download completed but no output file was produced")
      val savedUri = publishToDownloads(outputFile)

      return DownloadResult(
        lastLine = lastLine,
        savedPath = savedUri.toString(),
      )
    } finally {
      tempDir.deleteRecursively()
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
          val stableEnum = clazz.enumConstants?.firstOrNull { (it as? Enum<*>)?.name == "STABLE" }
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

  private fun findUpdateYoutubeDLMethod(updateChannel: Any?, predicate: (Method) -> Boolean): Method? {
    val candidateNames = setOf("updateYoutubeDL", "updateYoutubeDl")
    return YoutubeDL::class.java.methods.firstOrNull { method ->
      method.name in candidateNames && predicate(method)
    } ?: if (updateChannel == null) null
    else YoutubeDL::class.java.methods.firstOrNull { method ->
      method.name in candidateNames && method.parameterTypes.any { isUpdateChannelParameter(it, updateChannel) }
    }
  }

  private fun publishToDownloads(sourceFile: File): Uri {
    val extension = sourceFile.extension.lowercase()
    val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension).orEmpty()
    val values = ContentValues().apply {
      put(MediaStore.Downloads.DISPLAY_NAME, sourceFile.name)
      if (mimeType.isNotBlank()) put(MediaStore.Downloads.MIME_TYPE, mimeType)
      put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
      put(MediaStore.Downloads.IS_PENDING, 1)
    }

    val resolver = context.contentResolver
    val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
    val uri = resolver.insert(collection, values) ?: throw Exception("Failed to create download entry")

    try {
      resolver.openOutputStream(uri)?.use { output ->
        sourceFile.inputStream().use { input -> input.copyTo(output) }
      } ?: throw Exception("Failed to open MediaStore output stream")

      values.clear()
      values.put(MediaStore.Downloads.IS_PENDING, 0)
      resolver.update(uri, values, null, null)
      return uri
    } catch (e: Exception) {
      resolver.delete(uri, null, null)
      throw e
    }
  }
}

private fun Int?.orZero(): Int = this ?: 0
