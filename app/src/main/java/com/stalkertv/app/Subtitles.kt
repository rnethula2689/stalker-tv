package com.stalkertv.app

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** Keyless English subtitle search/download via the legacy OpenSubtitles REST endpoint. */
object Subtitles {
    private const val UA = "TemporaryUserAgent"
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class Sub(val name: String, val url: String)

    fun search(query: String): List<Sub> {
        val out = ArrayList<Sub>()
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val url = "https://rest.opensubtitles.org/search/query-$q/sublanguageid-eng"
            val req = Request.Builder().url(url).header("User-Agent", UA).build()
            client.newCall(req).execute().use { r ->
                val body = r.body?.string() ?: return out
                val arr = JSONArray(body)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (!o.optString("SubFormat").equals("srt", true)) continue
                    val name = o.optString("SubFileName")
                    val dl = o.optString("SubDownloadLink")
                    if (name.isNotBlank() && dl.isNotBlank()) out.add(Sub(name, dl))
                    if (out.size >= 25) break
                }
            }
        } catch (_: Exception) {}
        return out
    }

    /** Download a (gzipped) subtitle and write the decompressed SRT to [dest]. */
    fun download(url: String, dest: File): Boolean {
        return try {
            val req = Request.Builder().url(url).header("User-Agent", UA).build()
            client.newCall(req).execute().use { r ->
                val bytes = r.body?.bytes() ?: return false
                val srt = try {
                    GZIPInputStream(ByteArrayInputStream(bytes)).readBytes()
                } catch (e: Exception) {
                    bytes // not gzipped — use as-is
                }
                dest.writeBytes(srt)
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
