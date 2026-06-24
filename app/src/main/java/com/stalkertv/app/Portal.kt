package com.stalkertv.app

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Minimal Stalker (Ministra) portal client. */
object Portal {
    const val UA =
        "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG250 stbapp ver: 2 rev: 250 Safari/533.3"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    var portalUrl: String = ""
    var mac: String = ""
    var sn: String = ""
    private var base: String = ""
    private var token: String = ""
    private var host: String = ""
    private var logosBase: String = ""
    var lastError: String = ""

    data class Channel(
        val id: String, val name: String, val number: String,
        val cmd: String, val logoUrl: String, val genreId: String
    )
    data class Genre(val id: String, val title: String)
    data class VodCat(val id: String, val title: String)
    data class VodItem(val id: String, val name: String, val cmd: String, val posterUrl: String, val isSeries: Boolean)
    data class Season(val id: String, val name: String)
    data class Episode(val id: String, val name: String)
    data class EpgItem(
        val name: String, val start: String, val end: String, val descr: String,
        val hasArchive: Boolean, val startTs: Long = 0, val stopTs: Long = 0
    )

    private fun origin(u: String): String =
        Regex("https?://[^/]+").find(u.trim())?.value ?: u.trim().trimEnd('/')

    // Cloudflare / portal cookies captured from responses, resent on every request
    // so the whole session sticks to one backend node across requests.
    private val extraCookies = LinkedHashMap<String, String>()

    fun resetSession() {
        extraCookies.clear()
        token = ""
    }

    private fun cookie(): String {
        val tz = java.util.TimeZone.getDefault().id
        val sb = StringBuilder("mac=$mac; stb_lang=en; timezone=$tz")
        for ((k, v) in extraCookies) sb.append("; ").append(k).append("=").append(v)
        return sb.toString()
    }

    private fun get(url: String, auth: Boolean): String {
        val rb = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Cookie", cookie())
            .header("X-User-Agent", "Model: MAG250; Link: WiFi")
        if (auth && token.isNotEmpty()) rb.header("Authorization", "Bearer $token")
        client.newCall(rb.build()).execute().use { r ->
            for (h in r.headers("Set-Cookie")) {
                val pair = h.substringBefore(";")
                val k = pair.substringBefore("=").trim()
                val v = pair.substringAfter("=", "").trim()
                if (k.isNotEmpty() && k != "mac" && v.isNotEmpty()) extraCookies[k] = v
            }
            return r.body?.string() ?: ""
        }
    }

    /** js can be a plain array, or an object with a "data" array. */
    private fun jsArray(body: String): JSONArray? = try {
        when (val js = JSONObject(body).opt("js")) {
            is JSONArray -> js
            is JSONObject -> js.optJSONArray("data")
            else -> null
        }
    } catch (e: Exception) { null }

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
            resetSession()
            for (b in candidates) {
                val hs = get("$b?type=stb&action=handshake&JsHttpRequest=1-xml", false)
                val t = Regex("\"token\"\\s*:\\s*\"([^\"]+)\"").find(hs)?.groupValues?.get(1)
                if (!t.isNullOrEmpty()) { base = b; token = t; break }
            }
            if (token.isEmpty()) return "Handshake failed — check the portal URL & MAC."
            host = o
            val prefix = if (base.contains("server/load.php")) base.substringBefore("server/load.php")
                         else "$host/stalker_portal/"
            logosBase = prefix + "misc/logos/320/"
            val snEnc = URLEncoder.encode(sn, "UTF-8")
            val prof = get(
                "$base?type=stb&action=get_profile&sn=$snEnc&stb_type=MAG250" +
                    "&hw_version=1.7-BD-00&num_banks=2&image_version=218&hd=1&JsHttpRequest=1-xml",
                true
            )
            if (prof.contains("block_msg") || prof.contains("Serial Number mismatch")) {
                val msg = Regex("\"msg\"\\s*:\\s*\"([^\"]+)\"").find(prof)?.groupValues?.get(1)
                    ?: "device rejected"
                return "Portal rejected this device: $msg — check the Serial Number."
            }
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
        return out
    }

    fun liveGenres(): List<Genre> {
        val out = ArrayList<Genre>()
        val arr = jsArray(get("$base?type=itv&action=get_genres&JsHttpRequest=1-xml", true)) ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val title = o.optString("title")
            if (id == "*" || title.isEmpty()) continue
            out.add(Genre(id, title))
        }
        return out
    }

    private fun parseChannels(arr: JSONArray?, out: ArrayList<Channel>) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val logo = c.optString("logo")
            out.add(
                Channel(
                    id = c.optString("id"),
                    name = c.optString("name"),
                    number = c.optString("number"),
                    cmd = c.optString("cmd"),
                    logoUrl = if (logo.isBlank() || logo == "null") "" else logosBase + logo,
                    genreId = c.optString("tv_genre_id")
                )
            )
        }
    }

    /** Short EPG (now + upcoming programs) for a channel. */
    fun shortEpg(chId: String): List<EpgItem> {
        val out = ArrayList<EpgItem>()
        try {
            val body = get("$base?type=itv&action=get_short_epg&ch_id=$chId&size=6&JsHttpRequest=1-xml", true)
            val arr = JSONObject(body).optJSONArray("js") ?: return out
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    EpgItem(
                        name = o.optString("name"),
                        start = o.optString("t_time"),
                        end = o.optString("t_time_to"),
                        descr = o.optString("descr"),
                        hasArchive = o.optInt("mark_archive", 0) == 1,
                        startTs = o.optLong("start_timestamp", 0),
                        stopTs = o.optLong("stop_timestamp", 0)
                    )
                )
            }
        } catch (_: Exception) {}
        return out
    }

    fun vodCategories(): List<VodCat> {
        val out = ArrayList<VodCat>()
        val arr = jsArray(get("$base?type=vod&action=get_categories&JsHttpRequest=1-xml", true)) ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val title = o.optString("title")
            if (title.isEmpty()) continue
            out.add(VodCat(id, title))
        }
        return out
    }

    /** @return Pair(items, totalPages) */
    fun vodList(catId: String, page: Int): Pair<List<VodItem>, Int> {
        val out = ArrayList<VodItem>()
        var pages = 1
        try {
            val body = get(
                "$base?type=vod&action=get_ordered_list&category=$catId&p=$page&sortby=added&JsHttpRequest=1-xml",
                true
            )
            val js = JSONObject(body).optJSONObject("js") ?: return Pair(out, pages)
            val total = js.optInt("total_items", 0)
            val per = js.optInt("max_page_items", 14).coerceAtLeast(1)
            pages = Math.ceil(total.toDouble() / per).toInt().coerceAtLeast(1)
            parseVodItems(js.optJSONArray("data"), out)
        } catch (_: Exception) {}
        return Pair(out, pages)
    }

    private fun parseVodItems(arr: JSONArray?, out: ArrayList<VodItem>) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val ss = o.optString("screenshot_uri")
            val poster = when {
                ss.isBlank() || ss == "null" -> ""
                ss.startsWith("http") -> ss
                ss.startsWith("/") -> host + ss
                else -> "$host/$ss"
            }
            out.add(VodItem(o.optString("id"), o.optString("name"), o.optString("cmd"), poster, o.optString("is_series") == "1"))
        }
    }

    /** Global VOD search across all categories (returns both movies and series). */
    fun vodSearch(query: String): List<VodItem> {
        val out = ArrayList<VodItem>()
        try {
            val enc = URLEncoder.encode(query, "UTF-8")
            var page = 1
            while (page <= 5) {
                val body = get("$base?type=vod&action=get_ordered_list&search=$enc&p=$page&JsHttpRequest=1-xml", true)
                val js = JSONObject(body).optJSONObject("js") ?: break
                val arr = js.optJSONArray("data") ?: break
                if (arr.length() == 0) break
                val pageItems = ArrayList<VodItem>()
                parseVodItems(arr, pageItems)
                val matches = pageItems.filter { it.name.contains(query, ignoreCase = true) }
                out.addAll(matches)
                // Portal ranks title matches first — once a page yields none, later pages won't either.
                if (matches.isEmpty() && out.isNotEmpty()) break
                val total = js.optInt("total_items", out.size)
                val per = js.optInt("max_page_items", 14).coerceAtLeast(1)
                if (page >= Math.ceil(total.toDouble() / per).toInt()) break
                page++
            }
        } catch (_: Exception) {}
        return out
    }

    /** VOD search scoped to a single category (movies + series within that folder). */
    fun vodSearchInCategory(catId: String, query: String): List<VodItem> {
        val out = ArrayList<VodItem>()
        try {
            val enc = URLEncoder.encode(query, "UTF-8")
            var page = 1
            while (page <= 5) {
                val body = get("$base?type=vod&action=get_ordered_list&category=$catId&search=$enc&p=$page&JsHttpRequest=1-xml", true)
                val js = JSONObject(body).optJSONObject("js") ?: break
                val arr = js.optJSONArray("data") ?: break
                if (arr.length() == 0) break
                val pageItems = ArrayList<VodItem>()
                parseVodItems(arr, pageItems)
                val matches = pageItems.filter { it.name.contains(query, ignoreCase = true) }
                out.addAll(matches)
                if (matches.isEmpty() && out.isNotEmpty()) break
                val total = js.optInt("total_items", out.size)
                val per = js.optInt("max_page_items", 14).coerceAtLeast(1)
                if (page >= Math.ceil(total.toDouble() / per).toInt()) break
                page++
            }
        } catch (_: Exception) {}
        return out
    }

    /** All titles in a category whose name starts with [letter] (server-side `abc` filter, all pages). */
    fun vodByLetter(catId: String, letter: String): List<VodItem> {
        val out = ArrayList<VodItem>()
        try {
            val enc = URLEncoder.encode(letter, "UTF-8")
            var page = 1
            while (page <= 20) {
                val body = get("$base?type=vod&action=get_ordered_list&category=$catId&abc=$enc&p=$page&JsHttpRequest=1-xml", true)
                val js = JSONObject(body).optJSONObject("js") ?: break
                val arr = js.optJSONArray("data") ?: break
                if (arr.length() == 0) break
                parseVodItems(arr, out)
                val total = js.optInt("total_items", out.size)
                val per = js.optInt("max_page_items", 14).coerceAtLeast(1)
                if (page >= Math.ceil(total.toDouble() / per).toInt()) break
                page++
            }
        } catch (_: Exception) {}
        return out
    }

    fun createLink(cmd: String): String? = resolve("itv", cmd)

    /**
     * VOD play needs an extra step: fetch the movie's actual file(s) via movie_id,
     * then create_link with /media/file_<fileId>.mpg. Falls back to the list cmd.
     */
    fun playVodUrl(movieId: String, fallbackCmd: String): String? {
        try {
            val body = get(
                "$base?type=vod&action=get_ordered_list&movie_id=$movieId&JsHttpRequest=1-xml",
                true
            )
            val arr = JSONObject(body).optJSONObject("js")?.optJSONArray("data")
            if (arr != null && arr.length() > 0) {
                val fileId = arr.optJSONObject(0)?.optString("id") ?: ""
                if (fileId.isNotBlank()) {
                    val url = resolve("vod", "/media/file_$fileId.mpg")
                    if (!url.isNullOrEmpty()) return url
                }
            }
        } catch (_: Exception) {}
        return resolve("vod", fallbackCmd)
    }

    /** Series (is_series=1) → seasons (all pages). */
    fun seriesSeasons(seriesId: String): List<Season> {
        val out = ArrayList<Season>()
        pagedList("$base?type=vod&action=get_ordered_list&movie_id=$seriesId") { o ->
            val nm = o.optString("name").ifBlank { "Season ${o.optString("season_number")}" }
            out.add(Season(o.optString("id"), nm))
        }
        return out
    }

    /** A season → episodes (all pages). */
    fun seriesEpisodes(seriesId: String, seasonId: String): List<Episode> {
        val out = ArrayList<Episode>()
        pagedList("$base?type=vod&action=get_ordered_list&movie_id=$seriesId&season_id=$seasonId") { o ->
            val nm = o.optString("name").ifBlank { "Episode ${o.optString("series_number")}" }
            out.add(Episode(o.optString("id"), nm))
        }
        return out
    }

    /** Fetch every page of a get_ordered_list-style endpoint, calling [onItem] for each data row. */
    private fun pagedList(urlPrefix: String, onItem: (JSONObject) -> Unit) {
        try {
            var page = 1
            while (page <= 60) {
                val body = get("$urlPrefix&p=$page&JsHttpRequest=1-xml", true)
                val js = JSONObject(body).optJSONObject("js") ?: break
                val arr = js.optJSONArray("data") ?: break
                if (arr.length() == 0) break
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let(onItem)
                }
                val total = js.optInt("total_items", arr.length())
                val per = js.optInt("max_page_items", 14).coerceAtLeast(1)
                if (page >= Math.ceil(total.toDouble() / per).toInt()) break
                page++
            }
        } catch (_: Exception) {}
    }

    /** Resolve a single episode to a playable URL (episode → file id → create_link). */
    fun playEpisodeUrl(seriesId: String, seasonId: String, episodeId: String): String? {
        try {
            val body = get(
                "$base?type=vod&action=get_ordered_list&movie_id=$seriesId&season_id=$seasonId&episode_id=$episodeId&JsHttpRequest=1-xml",
                true
            )
            val fileId = JSONObject(body).optJSONObject("js")?.optJSONArray("data")
                ?.optJSONObject(0)?.optString("id") ?: ""
            if (fileId.isNotBlank()) {
                val url = resolve("vod", "/media/file_$fileId.mpg")
                if (!url.isNullOrEmpty()) return url
            }
        } catch (_: Exception) {}
        return null
    }

    private fun resolve(type: String, cmd: String): String? {
        val enc = URLEncoder.encode(cmd, "UTF-8")
        val u = "$base?type=$type&action=create_link&cmd=$enc" +
            "&series=0&forced_storage=&disable_ad=0&download=0&JsHttpRequest=1-xml"
        // Retry on transient storage timeouts (nothing_to_play); cookies keep us on one node.
        repeat(3) { attempt ->
            try {
                val js = JSONObject(get(u, true)).optJSONObject("js")
                var url = js?.optString("cmd") ?: ""
                if (url.isNotBlank()) {
                    val idx = url.indexOf("http")
                    if (idx > 0) url = url.substring(idx)
                    return url.trim()
                }
                val err = js?.optString("error") ?: ""
                lastError = if (err.isNotBlank()) err else "no stream returned"
                if (lastError != "nothing_to_play") return null
            } catch (e: Exception) {
                lastError = e.message ?: "connection error"
            }
            if (attempt < 2) Thread.sleep(800)
        }
        return null
    }
}
