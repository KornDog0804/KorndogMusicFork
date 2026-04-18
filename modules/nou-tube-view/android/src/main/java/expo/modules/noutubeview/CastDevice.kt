package expo.modules.noutubeview

data class CastDevice(
  val name: String,
  val type: String,   // "googlecast" | "dlna"
  val id: String,
  val priority: Int
)
