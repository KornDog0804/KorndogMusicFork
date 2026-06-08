// In downloadVideo(), replace this line:
val safeFormatId = if (formatId.isBlank()) AUDIO_FORMAT_ID else formatId

// With this:
val safeFormatId = if (formatId.isBlank() || formatId == "playlist") AUDIO_FORMAT_ID else formatId
