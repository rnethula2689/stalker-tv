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

    /** A short, human-friendly version of [lastError] — hides raw parser/network jargon from the UI.
     *  Returns a lowercase clause meant to follow "Couldn't open “X” — …". */
    fun lastErrorFriendly(): String {
        val e = lastError
        return when {
            e.contains("JSON", true) || e.contains("cannot be converted", true) ||
                e.contains("Unterminated", true) || e.contains("End of input", true) ->
                "the provider returned an unexpected response. Please try again in a moment."
            e.contains("timeout", true) || e.contains("timed out", true) ->
                "the provider took too long to respond. Check your connection and try again."
            e.contains("Unable to resolve host", true) || e.contains("failed to connect", true) ||
                e.contains("No address associated", true) || e.contains("ECONNREFUSED", true) ||
                e.contains("Network is unreachable", true) ->
                "couldn't reach the provider. Check your internet connection."
            else -> "the stream couldn't be started right now. Please try again."
        }
    }

    /** True once a handshake + profile have succeeded (a session token exists). */
    fun isConnected(): Boolean = token.isNotEmpty() && base.isNotEmpty()
    /** The endpoint we handshaked against (for the diagnostics screen); blank until connected. */
    fun activeBase(): String = base

    data class Channel(
        val id: String, val name: String, val number: String,
        val cmd: String, val logoUrl: String, val genreId: String, val censored: Boolean = false,
        val archiveDays: Int = 0,
        // Extra fields for the Live filter/sort (defaulted so other call sites are unaffected).
        val hd: Boolean = false, val added: String = "", val locked: Boolean = false, val open: Boolean = true,
        // Alternate stream sources, if the portal provides them (for failover). Empty = single source.
        val cmds: List<String> = emptyList()
    )
    data class Genre(val id: String, val title: String, val censored: Boolean = false)
    data class VodCat(val id: String, val title: String)
    data class VodItem(
        val id: String, val name: String, val cmd: String, val posterUrl: String, val isSeries: Boolean,
        // Extra metadata the portal already returns (used by the movie info sheet); blank when absent.
        val description: String = "", val year: String = "", val imdb: String = "",
        val director: String = "", val actors: String = "", val genre: String = "",
        val runtimeMin: String = "", val country: String = "", val age: String = "", val origName: String = "",
        val hd: Boolean = false, val added: String = ""
    )
    data class Season(val id: String, val name: String)
    data class Episode(val id: String, val name: String, val screenshot: String = "")
    data class EpgItem(
        val name: String, val start: String, val end: String, val descr: String,
        val hasArchive: Boolean, val startTs: Long = 0, val stopTs: Long = 0
    )

    private fun origin(u: String): String =
        Regex("https?://[^/]+").find(u.trim())?.value ?: u.trim().trimEnd('/')

    // Short-timeout client used only to probe whether an http portal also answers over TLS.
    private val probeClient by lazy {
        OkHttpClient.Builder().connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS).build()
    }
    private var originPref: Pair<String, String>? = null // (configured origin) -> (preferred origin)

    /** Prefer HTTPS so the MAC / serial / session token aren't sent in cleartext. If the portal is
     *  configured as http://, probe the https:// variant once (short timeout, cached); use it only when
     *  the portal actually answers the handshake over TLS, otherwise keep the configured http origin. */
    private fun preferredOrigin(configured: String): String {
        if (configured.startsWith("https", ignoreCase = true)) return configured
        originPref?.let { if (it.first == configured) return it.second }
        val httpsO = configured.replaceFirst(Regex("(?i)^http://"), "https://")
        val ok = httpsO != configured && listOf("/server/load.php", "/stalker_portal/server/load.php").any { path ->
            try {
                val req = Request.Builder().url("$httpsO$path?type=stb&action=handshake&JsHttpRequest=1-xml")
                    .header("User-Agent", UA).header("X-User-Agent", "Model: MAG250; Link: WiFi").build()
                probeClient.newCall(req).execute().use { r -> (r.body?.string() ?: "").contains("\"token\"") }
            } catch (_: Exception) { false }
        }
        val chosen = if (ok) httpsO else configured
        originPref = configured to chosen
        return chosen
    }

    // Cloudflare / portal cookies captured from responses, resent on every request
    // so the whole session sticks to one backend node across requests.
    private val extraCookies = java.util.Collections.synchronizedMap(LinkedHashMap<String, String>())

    fun resetSession() {
        extraCookies.clear()
        token = ""
    }

    private fun cookie(): String {
        val tz = java.util.TimeZone.getDefault().id
        val sb = StringBuilder("mac=$mac; stb_lang=en; timezone=$tz")
        synchronized(extraCookies) { for ((k, v) in extraCookies) sb.append("; ").append(k).append("=").append(v) }
        return sb.toString()
    }

    /** Portal origin (e.g. http://host:port) so image requests can be matched & authed like Strimix does. */
    fun imgHost(): String = host
    /** Session cookie (mac + captured portal cookies) to send when fetching portal poster images. */
    fun imgCookie(): String = cookie()

    private fun get(url: String, auth: Boolean): String {
        // Portal URL not set yet (opened before connect, or blank config) → don't build an invalid
        // request (OkHttp would throw "no scheme" on a background thread and crash the app). Also
        // never let a network error propagate uncaught from a pool thread — return empty and let the
        // caller degrade gracefully (empty result / "couldn't connect").
        if (!url.startsWith("http", ignoreCase = true)) return ""
        return try {
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
                r.body?.string() ?: ""
            }
        } catch (_: Exception) { "" }
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
            val o = preferredOrigin(origin(portalUrl))
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
            out.add(Genre(id, title, censored = o.optInt("censored", 0) == 1))
        }
        return out
    }

    /**
     * Channels in one genre via the ordered list (paged). Unlike get_all_channels, this returns
     * **censored** (adult / restricted) channels too — so it's used to open locked folders.
     */
    fun itvByGenre(genreId: String): List<Channel> {
        val out = ArrayList<Channel>()
        try {
            var page = 1
            while (page <= 40) {
                val body = get("$base?type=itv&action=get_ordered_list&genre=$genreId&p=$page&JsHttpRequest=1-xml", true)
                val js = JSONObject(body).optJSONObject("js") ?: break
                val arr = js.optJSONArray("data") ?: break
                if (arr.length() == 0) break
                parseChannels(arr, out)
                val total = js.optInt("total_items", arr.length())
                val per = js.optInt("max_page_items", 14).coerceAtLeast(1)
                if (page >= Math.ceil(total.toDouble() / per).toInt()) break
                page++
            }
        } catch (_: Exception) {}
        return out
    }

    private fun parseChannels(arr: JSONArray?, out: ArrayList<Channel>) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val logo = c.optString("logo")
            // Alternate sources (some portals list several per channel) → used for failover.
            val cmds = ArrayList<String>()
            c.optJSONArray("cmds")?.let { a ->
                for (j in 0 until a.length()) {
                    val cc = a.optJSONObject(j)?.optString("cmd")
                    if (!cc.isNullOrBlank() && cc != "null") cmds.add(cc)
                }
            }
            out.add(
                Channel(
                    id = c.optString("id"),
                    name = c.optString("name"),
                    number = c.optString("number"),
                    cmd = c.optString("cmd"),
                    logoUrl = if (logo.isBlank() || logo == "null") "" else logosBase + logo,
                    genreId = c.optString("tv_genre_id"),
                    censored = c.optInt("censored", 0) == 1,
                    archiveDays = c.optInt("tv_archive_duration", 0),
                    hd = c.optInt("hd", 0) == 1,
                    added = c.optString("added"),
                    locked = (c.optInt("locked", 0) == 1 || c.optInt("lock", 0) == 1),
                    open = c.optInt("open", 1) == 1,
                    cmds = cmds
                )
            )
        }
    }

    /** Short EPG (now + upcoming programs) for a channel. */
    fun shortEpg(chId: String): List<EpgItem> {
        val out = ArrayList<EpgItem>()
        try {
            val body = get("$base?type=itv&action=get_short_epg&ch_id=$chId&size=24&JsHttpRequest=1-xml", true)
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

    /** Format an absolute (UTC) unix timestamp as local wall-clock time, e.g. "1:30 PM". */
    fun localTime(ts: Long): String =
        if (ts <= 0) "" else java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date(ts * 1000))

    /**
     * Full programme schedule for a channel on a given local date (yyyy-mm-dd), for catch-up.
     *
     * Uses the EPG grid table (`type=epg&action=get_data_table`). The portal keys the requested day
     * off the `from`/`to` **string** params (it ignores `from_ts`/`to_ts` and `p` for date paging).
     *
     * Response shape: `js.data` is an array of *channel* objects (a page of ~10 channels around
     * [chId]), each `{ch_id, name, …, epg:[…]}`. We find our channel and read its nested `epg` list —
     * the programmes (NOT the channel rows) carry start_timestamp / t_time / mark_archive.
     */
    fun epgForDate(chId: String, dateYmd: String): List<EpgItem> {
        val out = ArrayList<EpgItem>()
        try {
            val dayFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val startMs = dayFmt.parse(dateYmd)?.time ?: return out   // local midnight
            val endMs = startMs + 86_400_000L - 1000L
            val from = URLEncoder.encode("$dateYmd 00:00:00", "UTF-8")
            val to = URLEncoder.encode("$dateYmd 23:59:59", "UTF-8")
            val url = "$base?type=epg&action=get_data_table&ch_id=$chId&fav=0" +
                "&from_ts=$startMs&to_ts=$endMs&from=$from&to=$to&JsHttpRequest=1-xml"
            val data = JSONObject(get(url, true)).optJSONObject("js")?.optJSONArray("data") ?: return out
            for (i in 0 until data.length()) {
                val chObj = data.optJSONObject(i) ?: continue
                if (chObj.optString("ch_id") != chId && chObj.optString("id") != chId) continue
                val epg = chObj.optJSONArray("epg") ?: continue
                for (k in 0 until epg.length()) {
                    val o = epg.optJSONObject(k) ?: continue
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
                break   // found our channel — the rest of the page is neighbouring channels
            }
        } catch (_: Exception) {}
        return out
    }

    /**
     * Resolve a catch-up (archive) stream for a programme that started at [startTs] and ran
     * [durationSec] seconds. Flussonic DVR: take the live HLS link and swap its playlist filename for
     * `archive-<start>-<duration>.m3u8`, preserving the auth token query string.
     */
    fun archiveLink(channelCmd: String, startTs: Long, durationSec: Long): String? {
        val live = resolve("itv", channelCmd) ?: return null
        val dur = if (durationSec > 0) durationSec else 3600
        val path = live.substringBefore("?")
        val query = if (live.contains("?")) live.substringAfter("?") else ""
        val slash = path.lastIndexOf('/')
        if (slash < 0) return null
        val archive = path.substring(0, slash + 1) + "archive-$startTs-$dur.m3u8"
        return if (query.isNotEmpty()) "$archive?$query" else archive
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

    /** @return Pair(items, totalPages). [sortby] is the portal's server-side order ("added" or "name"). */
    fun vodList(catId: String, page: Int, sortby: String = "added"): Pair<List<VodItem>, Int> {
        val out = ArrayList<VodItem>()
        var pages = 1
        try {
            val body = get(
                "$base?type=vod&action=get_ordered_list&category=$catId&p=$page&sortby=$sortby&JsHttpRequest=1-xml",
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
        fun clean(s: String) = if (s == "null") "" else s
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val ss = o.optString("screenshot_uri")
            val poster = when {
                ss.isBlank() || ss == "null" -> ""
                ss.startsWith("http") -> ss
                ss.startsWith("/") -> host + ss
                else -> "$host/$ss"
            }
            // Real genre only (genres_str/genre). Do NOT fall back to category_name — that's the folder
            // the item is in, which would pollute the Genre filter with the current folder's own name.
            val genre = o.optString("genres_str").ifBlank { o.optString("genre") }
            // The portal returns 0 for ratings even on well-known titles, so treat 0 as "no rating".
            val imdb = clean(o.optString("rating_imdb")).let { if (it == "0" || it == "0.0") "" else it }
            out.add(VodItem(
                o.optString("id"), o.optString("name"), o.optString("cmd"), poster, o.optString("is_series") == "1",
                description = clean(o.optString("description").ifBlank { o.optString("descr") }),
                year = clean(o.optString("year")),
                imdb = imdb,
                director = clean(o.optString("director")),
                actors = clean(o.optString("actors")),
                genre = clean(genre),
                runtimeMin = clean(o.optString("time")),
                country = clean(o.optString("country")),
                age = clean(o.optString("age").ifBlank { o.optString("rating_mpaa") }),
                origName = clean(o.optString("o_name")),
                hd = o.optInt("hd", 0) == 1,
                added = clean(o.optString("added"))
            ))
        }
    }

    /** Global VOD search across all categories (returns both movies and series). */
    /** [onPage], if given, is called with the cumulative results after each page arrives, so the UI can
     *  render the first (best-ranked) hits immediately instead of blocking on all pages. */
    fun vodSearch(query: String, onPage: ((List<VodItem>) -> Unit)? = null): List<VodItem> {
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
                if (matches.isNotEmpty()) onPage?.invoke(ArrayList(out))
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
    fun vodSearchInCategory(catId: String, query: String, onPage: ((List<VodItem>) -> Unit)? = null): List<VodItem> {
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
                if (matches.isNotEmpty()) onPage?.invoke(ArrayList(out))
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
            val ss = o.optString("screenshot_uri")
            val shot = when {
                ss.isBlank() || ss == "null" -> ""
                ss.startsWith("http") -> ss
                ss.startsWith("/") -> host + ss
                else -> "$host/$ss"
            }
            out.add(Episode(o.optString("id"), nm, shot))
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
            // Back-off between retries. If the worker thread is interrupted mid-sleep (the activity
            // tore down / the user navigated away), abort cleanly instead of letting the
            // InterruptedException escape as an uncaught java.lang.Error that crashes the whole app.
            if (attempt < 2) {
                try { Thread.sleep(800) }
                catch (ie: InterruptedException) { Thread.currentThread().interrupt(); return null }
            }
        }
        return null
    }
}
