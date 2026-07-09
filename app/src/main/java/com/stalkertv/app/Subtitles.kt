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
    data class Sub(val name: String, val legacyUrl: String, val fileId: String)

    /** Plain English subtitle search by title. No year/relevance filtering — those were removing
     *  legitimate results; show whatever OpenSubtitles returns. [year] is accepted but ignored. */
    fun search(query: String, year: String = ""): List<Sub> =
        if (apiKey.isNotBlank()) searchApi(query) else searchLegacy(query)

    fun download(sub: Sub, dest: File): Boolean =
        if (sub.fileId.isNotBlank()) downloadApi(sub.fileId, dest) else downloadLegacy(sub.legacyUrl, dest)

    // ---- Official api.opensubtitles.com ----
    private fun searchApi(query: String): List<Sub> {
        val out = ArrayList<Sub>()
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val req = Request.Builder()
                .url("https://api.opensubtitles.com/api/v1/subtitles?languages=en&order_by=download_count&query=$q")
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
                        attr.optJSONObject("feature_details")?.optString("title") ?: "English subtitle"
                    }
                    out.add(Sub(name, "", fileId))
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
    private fun searchLegacy(query: String): List<Sub> {
        val out = ArrayList<Sub>()
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val req = Request.Builder()
                .url("https://rest.opensubtitles.org/search/query-$q/sublanguageid-eng")
                .header("User-Agent", "TemporaryUserAgent").build()
            client.newCall(req).execute().use { r ->
                val arr = org.json.JSONArray(r.body?.string() ?: return out)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (!o.optString("SubFormat").equals("srt", true)) continue
                    val name = o.optString("SubFileName")
                    val dl = o.optString("SubDownloadLink")
                    if (name.isNotBlank() && dl.isNotBlank()) out.add(Sub(name, dl, ""))
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
