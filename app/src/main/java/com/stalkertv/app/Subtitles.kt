package com.stalkertv.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** English subtitle search/download. Uses the official OpenSubtitles API when an API key is set. */
object Subtitles {
    private const val APP_UA = "VibeTV v1.0"
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** OpenSubtitles API key. Bundled default; can be overridden in Settings. */
    var apiKey: String = "zVrOLC9GryTV5tAGMblRZbBiTQlmarLH"

    // legacyUrl used for the keyless endpoint; fileId used for the official API.
    data class Sub(val name: String, val legacyUrl: String, val fileId: String, val downloads: Int = -1) {
        /** Picker label: subtitle name + how often it's been downloaded (a quality signal, à la Strimix). */
        val label: String get() = if (downloads >= 0) "$name  •  $downloads downloads" else name
    }

    /** A selectable subtitle language: display label + ISO codes for the API (2-letter) and the
     *  legacy endpoint (3-letter). */
    data class Lang(val label: String, val api2: String, val legacy3: String)
    val LANGS = listOf(
        Lang("English", "en", "eng"), Lang("Spanish", "es", "spa"), Lang("French", "fr", "fre"),
        Lang("German", "de", "ger"), Lang("Italian", "it", "ita"), Lang("Portuguese", "pt", "por"),
        Lang("Hindi", "hi", "hin"), Lang("Tamil", "ta", "tam"), Lang("Telugu", "te", "tel"),
        Lang("Malayalam", "ml", "mal"), Lang("Kannada", "kn", "kan"), Lang("Bengali", "bn", "ben"),
        Lang("Marathi", "mr", "mar"), Lang("Urdu", "ur", "urd"), Lang("Arabic", "ar", "ara"),
        Lang("Russian", "ru", "rus"), Lang("Chinese", "zh", "chi"), Lang("Japanese", "ja", "jpn"),
        Lang("Korean", "ko", "kor"), Lang("Turkish", "tr", "tur"), Lang("Dutch", "nl", "dut"),
        Lang("Polish", "pl", "pol")
    )

    /** Plain subtitle search by title in [lang]. No year/relevance filtering — those were removing
     *  legitimate results; show whatever OpenSubtitles returns. [year] is accepted but ignored. */
    fun search(query: String, year: String = "", lang: Lang = LANGS[0]): List<Sub> =
        if (apiKey.isNotBlank()) searchApi(query, lang.api2) else searchLegacy(query, lang.legacy3)

    /** Build the search query from a player title. For series episodes the S/E context is ESSENTIAL:
     *  "Super Subbu" alone fuzzy-matches junk (Super Troopers…), while "Super Subbu S01E02" hits the
     *  exact episode releases — this is what Strimix searches too. Season defaults to 1 when the
     *  portal title only carries an episode number. */
    fun queryFor(rawTitle: String): String {
        val base = rawTitle.substringBefore(" / ").substringBefore(" - ").substringBefore(" (").trim()
        val se = Regex("S(\\d{1,2})\\s*E(\\d{1,3})", RegexOption.IGNORE_CASE).find(rawTitle)
        val ep = Regex("Episode\\s*(\\d{1,3})", RegexOption.IGNORE_CASE).find(rawTitle)
        val season = Regex("Season\\s*(\\d{1,2})", RegexOption.IGNORE_CASE).find(rawTitle)
            ?.groupValues?.get(1)?.toIntOrNull()
        return when {
            se != null -> "%s S%02dE%02d".format(base, se.groupValues[1].toInt(), se.groupValues[2].toInt())
            ep != null -> "%s S%02dE%02d".format(base, season ?: 1, ep.groupValues[1].toInt())
            else -> base
        }
    }

    fun download(sub: Sub, dest: File): Boolean =
        if (sub.fileId.isNotBlank()) downloadApi(sub.fileId, dest) else downloadLegacy(sub.legacyUrl, dest)

    /** Exact-feature search: subtitles for THIS movie (by TMDb id), not for whatever fuzzy-matches
     *  the title text — a plain "David" query returns David & Lisa (1962) junk; the id returns only
     *  David (2025) releases. This is how Strimix gets clean results. */
    fun searchByTmdb(tmdbId: Int, lang: Lang = LANGS[0]): List<Sub> =
        if (apiKey.isBlank() || tmdbId <= 0) emptyList()
        else apiFetch("https://api.opensubtitles.com/api/v1/subtitles?languages=${lang.api2}&order_by=download_count&tmdb_id=$tmdbId")

    // ---- Official api.opensubtitles.com ----
    private fun searchApi(query: String, lang2: String = "en"): List<Sub> {
        val q = URLEncoder.encode(query, "UTF-8")
        return apiFetch("https://api.opensubtitles.com/api/v1/subtitles?languages=$lang2&order_by=download_count&query=$q")
    }

    private fun apiFetch(url: String): List<Sub> {
        val out = ArrayList<Sub>()
        try {
            val req = Request.Builder()
                .url(url)
                .header("Api-Key", apiKey)
                .header("User-Agent", APP_UA)
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { r ->
                val data = JSONObject(r.body?.string() ?: return out).optJSONArray("data") ?: return out
                for (i in 0 until data.length()) {
                    val attr = data.optJSONObject(i)?.optJSONObject("attributes") ?: continue
                    val files = attr.optJSONArray("files") ?: continue
                    val fileId = files.optJSONObject(0)?.optString("file_id") ?: continue
                    if (fileId.isBlank()) continue
                    val name = attr.optString("release").ifBlank {
                        attr.optJSONObject("feature_details")?.optString("title") ?: "Subtitle"
                    }
                    out.add(Sub(name, "", fileId, attr.optInt("download_count", -1)))
                    if (out.size >= 25) break
                }
            }
        } catch (_: Exception) {}
        return out
    }

    private fun downloadApi(fileId: String, dest: File): Boolean {
        return try {
            val body = "{\"file_id\":$fileId}".toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("https://api.opensubtitles.com/api/v1/download")
                .header("Api-Key", apiKey)
                .header("User-Agent", APP_UA)
                .header("Accept", "application/json")
                .post(body)
                .build()
            val link = client.newCall(req).execute().use { r ->
                JSONObject(r.body?.string() ?: return false).optString("link")
            }
            if (link.isBlank()) return false
            val get = Request.Builder().url(link).header("User-Agent", APP_UA).build()
            client.newCall(get).execute().use { r ->
                dest.writeBytes(r.body?.bytes() ?: return false)
            }
            true
        } catch (e: Exception) { false }
    }

    // ---- Keyless legacy fallback (rest.opensubtitles.org) ----
    private fun searchLegacy(query: String, lang3: String = "eng"): List<Sub> {
        val out = ArrayList<Sub>()
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val req = Request.Builder()
                .url("https://rest.opensubtitles.org/search/query-$q/sublanguageid-$lang3")
                .header("User-Agent", "TemporaryUserAgent").build()
            client.newCall(req).execute().use { r ->
                val arr = org.json.JSONArray(r.body?.string() ?: return out)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (!o.optString("SubFormat").equals("srt", true)) continue
                    val name = o.optString("SubFileName")
                    val dl = o.optString("SubDownloadLink")
                    val cnt = o.optString("SubDownloadsCnt").toIntOrNull() ?: -1
                    if (name.isNotBlank() && dl.isNotBlank()) out.add(Sub(name, dl, "", cnt))
                    if (out.size >= 25) break
                }
            }
        } catch (_: Exception) {}
        return out
    }

    private fun downloadLegacy(url: String, dest: File): Boolean {
        return try {
            val req = Request.Builder().url(url).header("User-Agent", "TemporaryUserAgent").build()
            client.newCall(req).execute().use { r ->
                val bytes = r.body?.bytes() ?: return false
                val srt = try { GZIPInputStream(ByteArrayInputStream(bytes)).readBytes() } catch (e: Exception) { bytes }
                dest.writeBytes(srt)
            }
            true
        } catch (e: Exception) { false }
    }
}
