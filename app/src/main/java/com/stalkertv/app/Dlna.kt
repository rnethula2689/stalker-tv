package com.stalkertv.app

import android.content.Context
import android.net.wifi.WifiManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Minimal DLNA / UPnP control point — discovers MediaRenderer devices (most smart TVs) via SSDP and
 * pushes a stream URL to them with AVTransport SetAVTransportURI + Play. No external library, and no
 * Google Play Services needed, so it works on Fire OS.
 */
object Dlna {
    data class Renderer(val name: String, val controlUrl: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"

    /** Blocking SSDP discovery — call off the main thread. */
    fun discover(context: Context, timeoutMs: Int = 2500): List<Renderer> {
        val found = LinkedHashMap<String, Renderer>()
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("stalkertv-dlna")?.apply { setReferenceCounted(true); acquire() }
        var sock: DatagramSocket? = null
        try {
            val search = "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
            sock = DatagramSocket().apply { soTimeout = timeoutMs; broadcast = true }
            val group = InetAddress.getByName("239.255.255.250")
            val bytes = search.toByteArray()
            // send a couple of times — UDP is lossy
            repeat(2) { sock.send(DatagramPacket(bytes, bytes.size, group, 1900)) }
            val buf = ByteArray(4096)
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    val resp = String(pkt.data, 0, pkt.length)
                    val loc = Regex("(?im)^LOCATION:\\s*(\\S+)").find(resp)?.groupValues?.get(1)?.trim()
                    if (!loc.isNullOrEmpty() && !found.containsKey(loc)) {
                        describe(loc)?.let { found[loc] = it }
                    }
                } catch (e: SocketTimeoutException) {
                    break
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        } finally {
            try { sock?.close() } catch (_: Exception) {}
            try { lock?.release() } catch (_: Exception) {}
        }
        return found.values.toList()
    }

    /** Fetch a device description and pull out its AVTransport control URL + friendly name. */
    private fun describe(location: String): Renderer? {
        return try {
            val xml = http.newCall(Request.Builder().url(location).build()).execute()
                .use { it.body?.string() } ?: return null
            if (!xml.contains("AVTransport")) return null
            val name = Regex("<friendlyName>(.*?)</friendlyName>", RegexOption.DOT_MATCHES_ALL)
                .find(xml)?.groupValues?.get(1)?.trim()?.ifBlank { null } ?: "Smart TV"
            val avService = Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL)
                .findAll(xml).map { it.groupValues[1] }
                .firstOrNull { it.contains("AVTransport") } ?: return null
            val control = Regex("<controlURL>(.*?)</controlURL>")
                .find(avService)?.groupValues?.get(1)?.trim() ?: return null
            val abs = if (control.startsWith("http")) control else URL(URL(location), control).toString()
            Renderer(name, abs)
        } catch (_: Exception) {
            null
        }
    }

    /** Push [url] to the renderer and start playback. Blocking — call off the main thread. */
    fun cast(renderer: Renderer, url: String, title: String): Boolean {
        return try {
            val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
                "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
                "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
                "<dc:title>${esc(title)}</dc:title>" +
                "<upnp:class>object.item.videoItem</upnp:class>" +
                "<res protocolInfo=\"http-get:*:video/mp2t:*\">${esc(url)}</res>" +
                "</item></DIDL-Lite>"
            soap(renderer.controlUrl, "SetAVTransportURI",
                "<InstanceID>0</InstanceID><CurrentURI>${esc(url)}</CurrentURI>" +
                    "<CurrentURIMetaData>${esc(didl)}</CurrentURIMetaData>")
            soap(renderer.controlUrl, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun soap(controlUrl: String, action: String, args: String) {
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:$action xmlns:u=\"$AV_TRANSPORT\">$args</u:$action>" +
            "</s:Body></s:Envelope>"
        val req = Request.Builder().url(controlUrl)
            .addHeader("SOAPACTION", "\"$AV_TRANSPORT#$action\"")
            .post(body.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
            .build()
        http.newCall(req).execute().use { /* ignore body */ }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")
}
