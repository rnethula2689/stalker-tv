package com.stalkertv.app

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Minimal Stalker (Ministra) portal client. */
object Portal {
    private const val UA =
        "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG250 stbapp ver: 2 rev: 250 Safari/533.3"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    var portalUrl: String = ""
    var mac: String = ""
    private var base: String = ""
    private var token: String = ""

    data class Channel(val id: String, val name: String, val number: String, val cmd: String, val logo: String)

    private fun origin(u: String): String =
        Regex("https?://[^/]+").find(u.trim())?.value ?: u.trim().trimEnd('/')

    private fun cookie() = "mac=$mac; stb_lang=en; timezone=America/New_York"

    private fun get(url: String, auth: Boolean): String {
        val rb = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Cookie", cookie())
            .header("X-User-Agent", "Model: MAG250; Link: WiFi")
        if (auth && token.isNotEmpty()) rb.header("Authorization", "Bearer $token")
        client.newCall(rb.build()).execute().use { r -> return r.body?.string() ?: "" }
    }

    /** @return null on success, else an error message. */
    fun connect(): String? {
        return try {
            val o = origin(portalUrl)
            val candidates = listOf(
                "$o/stalker_portal/server/load.php",
                "$o/server/load.php",
                "$o/portal.php",
                "$o/c/server/load.php"
            )
            token = ""
            for (b in candidates) {
                val hs = get("$b?type=stb&action=handshake&JsHttpRequest=1-xml", false)
                val t = Regex("\"token\"\\s*:\\s*\"([^\"]+)\"").find(hs)?.groupValues?.get(1)
                if (!t.isNullOrEmpty()) { base = b; token = t; break }
            }
            if (token.isEmpty()) return "Handshake failed — check the portal URL & MAC."
            get("$base?type=stb&action=get_profile&JsHttpRequest=1-xml", true) // register session (best-effort)
            null
        } catch (e: Exception) {
            "Connection error: ${e.message}"
        }
    }

    fun liveChannels(): List<Channel> {
        val out = ArrayList<Channel>()
        try {
            val body = get("$base?type=itv&action=get_all_channels&JsHttpRequest=1-xml", true)
            parseChannels(JSONObject(body).optJSONObject("js")?.optJSONArray("data"), out)
        } catch (_: Exception) {}
        if (out.isNotEmpty()) return out
        // fallback: paged ordered list (capped to keep request count modest)
        try {
            val first = get("$base?type=itv&action=get_ordered_list&genre=*&p=1&JsHttpRequest=1-xml", true)
            val js = JSONObject(first).optJSONObject("js") ?: return out
            parseChannels(js.optJSONArray("data"), out)
            val total = js.optInt("total_items", 0)
            val per = js.optInt("max_page_items", 14).coerceAtLeast(1)
            val pages = Math.min(Math.ceil(total.toDouble() / per).toInt(), 40)
            for (p in 2..pages) {
                val b = get("$base?type=itv&action=get_ordered_list&genre=*&p=$p&JsHttpRequest=1-xml", true)
                parseChannels(JSONObject(b).optJSONObject("js")?.optJSONArray("data"), out)
            }
        } catch (_: Exception) {}
        return out
    }

    private fun parseChannels(arr: JSONArray?, out: ArrayList<Channel>) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            out.add(
                Channel(
                    id = c.optString("id"),
                    name = c.optString("name"),
                    number = c.optString("number"),
                    cmd = c.optString("cmd"),
                    logo = c.optString("logo")
                )
            )
        }
    }

    /** Resolve a channel's cmd into a playable URL. */
    fun createLink(cmd: String): String? {
        return try {
            val enc = URLEncoder.encode(cmd, "UTF-8")
            val body = get("$base?type=itv&action=create_link&cmd=$enc&JsHttpRequest=1-xml", true)
            var url = JSONObject(body).optJSONObject("js")?.optString("cmd") ?: return null
            val idx = url.indexOf("http")
            if (idx > 0) url = url.substring(idx)
            url.trim()
        } catch (e: Exception) {
            null
        }
    }
}
