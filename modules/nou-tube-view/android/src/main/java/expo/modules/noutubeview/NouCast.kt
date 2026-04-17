package expo.modules.noutubeview

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import java.io.StringReader
import java.net.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import org.w3c.dom.Element

private const val TAG = "NouCast"
private const val SSDP_ADDRESS = "239.255.255.250"
private const val SSDP_PORT = 1900
private const val SEARCH_TARGET = "urn:schemas-upnp-org:service:AVTransport:1"
private const val SEARCH_TIMEOUT_MS = 5000L

data class CastDevice(
  val name: String,
  val location: String,
  val controlUrl: String,
  val baseUrl: String
)

class NouCast(private val context: Context) {

  private var currentDevice: CastDevice? = null
  private val devices = mutableListOf<CastDevice>()

  /**
   * Discover DLNA renderers on the local network via SSDP
   */
  suspend fun discoverDevices(): List<Map<String, String>> = withContext(Dispatchers.IO) {
    devices.clear()
    val foundLocations = mutableSetOf<String>()

    try {
      val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
      val multicastLock = wifiManager.createMulticastLock("NouCast")
      multicastLock.setReferenceCounted(true)
      multicastLock.acquire()

      try {
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        socket.broadcast = true
        socket.soTimeout = SEARCH_TIMEOUT_MS.toInt()

        val searchMessage = buildString {
          append("M-SEARCH * HTTP/1.1\r\n")
          append("HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n")
          append("MAN: \"ssdp:discover\"\r\n")
          append("MX: 3\r\n")
          append("ST: $SEARCH_TARGET\r\n")
          append("USER-AGENT: NouTube/1.0 KornDog\r\n")
          append("\r\n")
        }

        val sendData = searchMessage.toByteArray()
        val sendPacket = DatagramPacket(
          sendData, sendData.size,
          InetAddress.getByName(SSDP_ADDRESS), SSDP_PORT
        )

        // Send search request 3 times for reliability
        repeat(3) {
          socket.send(sendPacket)
          delay(100)
        }

        val receiveBuffer = ByteArray(4096)
        val deadline = System.currentTimeMillis() + SEARCH_TIMEOUT_MS

        while (System.currentTimeMillis() < deadline) {
          try {
            val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
            socket.receive(receivePacket)
            val response = String(receivePacket.data, 0, receivePacket.length)

            val location = extractHeader(response, "LOCATION")
            if (location != null && location !in foundLocations) {
              foundLocations.add(location)
              try {
                val device = fetchDeviceInfo(location)
                if (device != null) {
                  devices.add(device)
                  Log.d(TAG, "Found device: ${device.name} at ${device.controlUrl}")
                }
              } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch device info from $location: ${e.message}")
              }
            }
          } catch (e: SocketTimeoutException) {
            break
          }
        }

        socket.close()
      } finally {
        multicastLock.release()
      }
    } catch (e: Exception) {
      Log.e(TAG, "SSDP discovery failed: ${e.message}", e)
    }

    devices.map { device ->
      mapOf(
        "name" to device.name,
        "location" to device.location
      )
    }
  }

  /**
   * Connect to a discovered device by index
   */
  fun selectDevice(index: Int): Boolean {
    if (index < 0 || index >= devices.size) return false
    currentDevice = devices[index]
    Log.d(TAG, "Selected device: ${currentDevice?.name}")
    return true
  }

  /**
   * Cast a direct video URL to the selected device
   */
  suspend fun castUrl(streamUrl: String, title: String = "NouTube"): Boolean = withContext(Dispatchers.IO) {
    val device = currentDevice ?: run {
      Log.e(TAG, "No device selected")
      return@withContext false
    }

    try {
      // Stop any current playback
      sendAVTransportAction(device, "Stop", "")

      // Set the new stream URL
      val metadata = buildDIDLMetadata(title, streamUrl)
      val setUriBody = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
        append("<s:Body>")
        append("<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">")
        append("<InstanceID>0</InstanceID>")
        append("<CurrentURI>${escapeXml(streamUrl)}</CurrentURI>")
        append("<CurrentURIMetaData>${escapeXml(metadata)}</CurrentURIMetaData>")
        append("</u:SetAVTransportURI>")
        append("</s:Body>")
        append("</s:Envelope>")
      }

      val setResult = sendSOAPAction(
        device.controlUrl,
        "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI",
        setUriBody
      )

      if (!setResult) {
        Log.e(TAG, "Failed to set transport URI")
        return@withContext false
      }

      // Small delay to let the device process
      delay(500)

      // Start playback
      val playBody = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
        append("<s:Body>")
        append("<u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">")
        append("<InstanceID>0</InstanceID>")
        append("<Speed>1</Speed>")
        append("</u:Play>")
        append("</s:Body>")
        append("</s:Envelope>")
      }

      val playResult = sendSOAPAction(
        device.controlUrl,
        "urn:schemas-upnp-org:service:AVTransport:1#Play",
        playBody
      )

      Log.d(TAG, "Cast result: $playResult")
      playResult
    } catch (e: Exception) {
      Log.e(TAG, "Cast failed: ${e.message}", e)
      false
    }
  }

  /**
   * Pause playback on the selected device
   */
  suspend fun pause(): Boolean = withContext(Dispatchers.IO) {
    val device = currentDevice ?: return@withContext false
    sendAVTransportAction(device, "Pause", "")
  }

  /**
   * Resume playback on the selected device
   */
  suspend fun play(): Boolean = withContext(Dispatchers.IO) {
    val device = currentDevice ?: return@withContext false
    val body = buildString {
      append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
      append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
      append("<s:Body>")
      append("<u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">")
      append("<InstanceID>0</InstanceID>")
      append("<Speed>1</Speed>")
      append("</u:Play>")
      append("</s:Body>")
      append("</s:Envelope>")
    }
    sendSOAPAction(device.controlUrl, "urn:schemas-upnp-org:service:AVTransport:1#Play", body)
  }

  /**
   * Stop playback on the selected device
   */
  suspend fun stop(): Boolean = withContext(Dispatchers.IO) {
    val device = currentDevice ?: return@withContext false
    sendAVTransportAction(device, "Stop", "")
  }

  fun getSelectedDevice(): Map<String, String>? {
    return currentDevice?.let {
      mapOf("name" to it.name, "location" to it.location)
    }
  }

  // ---- Private helpers ----

  private fun extractHeader(response: String, header: String): String? {
    val lines = response.split("\r\n", "\n")
    for (line in lines) {
      if (line.startsWith("$header:", ignoreCase = true)) {
        return line.substringAfter(":").trim()
      }
    }
    return null
  }

  private fun fetchDeviceInfo(location: String): CastDevice? {
    val url = URL(location)
    val connection = url.openConnection() as HttpURLConnection
    connection.connectTimeout = 3000
    connection.readTimeout = 3000

    try {
      val xml = connection.inputStream.bufferedReader().readText()
      val factory = DocumentBuilderFactory.newInstance()
      val builder = factory.newDocumentBuilder()
      val doc = builder.parse(InputSource(StringReader(xml)))

      // Get device name
      val deviceElements = doc.getElementsByTagName("device")
      var friendlyName = "Unknown Device"
      if (deviceElements.length > 0) {
        val deviceElement = deviceElements.item(0) as Element
        val nameElements = deviceElement.getElementsByTagName("friendlyName")
        if (nameElements.length > 0) {
          friendlyName = nameElements.item(0).textContent ?: friendlyName
        }
      }

      // Find AVTransport control URL
      val serviceElements = doc.getElementsByTagName("service")
      var controlPath = ""
      for (i in 0 until serviceElements.length) {
        val service = serviceElements.item(i) as Element
        val serviceType = service.getElementsByTagName("serviceType").item(0)?.textContent ?: ""
        if (serviceType.contains("AVTransport")) {
          controlPath = service.getElementsByTagName("controlURL").item(0)?.textContent ?: ""
          break
        }
      }

      if (controlPath.isEmpty()) return null

      val baseUrl = "${url.protocol}://${url.host}:${url.port}"
      val controlUrl = if (controlPath.startsWith("http")) {
        controlPath
      } else {
        "$baseUrl$controlPath"
      }

      return CastDevice(
        name = friendlyName,
        location = location,
        controlUrl = controlUrl,
        baseUrl = baseUrl
      )
    } finally {
      connection.disconnect()
    }
  }

  private fun sendAVTransportAction(device: CastDevice, action: String, extraBody: String): Boolean {
    val body = buildString {
      append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
      append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
      append("<s:Body>")
      append("<u:$action xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">")
      append("<InstanceID>0</InstanceID>")
      append(extraBody)
      append("</u:$action>")
      append("</s:Body>")
      append("</s:Envelope>")
    }
    return sendSOAPAction(
      device.controlUrl,
      "urn:schemas-upnp-org:service:AVTransport:1#$action",
      body
    )
  }

  private fun sendSOAPAction(controlUrl: String, soapAction: String, body: String): Boolean {
    try {
      val url = URL(controlUrl)
      val connection = url.openConnection() as HttpURLConnection
      connection.requestMethod = "POST"
      connection.doOutput = true
      connection.connectTimeout = 5000
      connection.readTimeout = 5000
      connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
      connection.setRequestProperty("SOAPAction", "\"$soapAction\"")

      val writer = OutputStreamWriter(connection.outputStream)
      writer.write(body)
      writer.flush()
      writer.close()

      val responseCode = connection.responseCode
      connection.disconnect()

      return responseCode in 200..299
    } catch (e: Exception) {
      Log.e(TAG, "SOAP action failed: ${e.message}", e)
      return false
    }
  }

  private fun buildDIDLMetadata(title: String, url: String): String {
    return buildString {
      append("&lt;DIDL-Lite xmlns=&quot;urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/&quot; ")
      append("xmlns:dc=&quot;http://purl.org/dc/elements/1.1/&quot; ")
      append("xmlns:upnp=&quot;urn:schemas-upnp-org:metadata-1-0/upnp/&quot;&gt;")
      append("&lt;item id=&quot;0&quot; parentID=&quot;0&quot; restricted=&quot;1&quot;&gt;")
      append("&lt;dc:title&gt;${escapeXml(title)}&lt;/dc:title&gt;")
      append("&lt;upnp:class&gt;object.item.videoItem&lt;/upnp:class&gt;")
      append("&lt;res protocolInfo=&quot;http-get:*:video/mp4:*&quot;&gt;${escapeXml(url)}&lt;/res&gt;")
      append("&lt;/item&gt;")
      append("&lt;/DIDL-Lite&gt;")
    }
  }

  private fun escapeXml(text: String): String {
    return text
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&apos;")
  }
}
