package com.stalkertv.app

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Keyless fallback for trailers: scrape the first video id off a YouTube search results page.
 * Used when TMDb has no match, so the trailer can still play IN-APP (embedded) rather than
 * punting to the external YouTube app (which strands the user on TV).
 */
object YouTubeSearch {
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    fun firstVideoId(query: String): String? {
        return try {
            val q = URLEncoder.encode(query, "UTF-8")
            // sp=EgIQAQ%3D%3D restricts results to videos (skips channels/playlists).
            val html = httpGet("https://www.youtube.com/results?search_query=$q&sp=EgIQAQ%253D%253D")
            Regex("\"videoId\":\"([A-Za-z0-9_-]{11})\"").find(html)?.groupValues?.get(1)
        } catch (_: Exception) { null }
    }

    private fun httpGet(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000; requestMethod = "GET"
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        }
        return c.inputStream.bufferedReader().use { it.readText() }
    }
}
