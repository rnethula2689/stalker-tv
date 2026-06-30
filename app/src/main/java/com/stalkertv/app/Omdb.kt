package com.stalkertv.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * OMDb client for the ratings TMDb doesn't carry: IMDb, Rotten Tomatoes, Metacritic.
 * Free key from omdbapi.com, injected as BuildConfig.OMDB_KEY (CI secret OMDB_KEY); blank = disabled.
 */
object Omdb {
    data class Ratings(val imdb: String?, val rottenTomatoes: String?, val metacritic: String?)

    fun ratings(apiKey: String, title: String, year: String): Ratings? {
        if (apiKey.isBlank() || title.isBlank()) return null
        return try {
            val q = URLEncoder.encode(Tmdb.cleanTitle(title), "UTF-8")
            // Try the year-constrained lookup first; portal years often disagree with OMDb's, so
            // fall back to a title-only lookup rather than giving up (which would drop all ratings).
            var js = JSONObject(httpGet("https://www.omdbapi.com/?apikey=$apiKey&t=$q&y=$year".let {
                if (year.isBlank()) "https://www.omdbapi.com/?apikey=$apiKey&t=$q" else it
            }))
            if (js.optString("Response") != "True" && year.isNotBlank()) {
                js = JSONObject(httpGet("https://www.omdbapi.com/?apikey=$apiKey&t=$q"))
            }
            if (js.optString("Response") != "True") return null
            val imdb = js.optString("imdbRating").takeIf { it.isNotBlank() && it != "N/A" }
            var rt: String? = null; var mc: String? = null
            js.optJSONArray("Ratings")?.let { a ->
                for (i in 0 until a.length()) {
                    val r = a.optJSONObject(i) ?: continue
                    when (r.optString("Source")) {
                        "Rotten Tomatoes" -> rt = r.optString("Value").takeIf { it.isNotBlank() && it != "N/A" }
                        "Metacritic" -> mc = r.optString("Value").takeIf { it.isNotBlank() && it != "N/A" }
                    }
                }
            }
            if (imdb == null && rt == null && mc == null) null else Ratings(imdb, rt, mc)
        } catch (_: Exception) { null }
    }

    private fun httpGet(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000; requestMethod = "GET"
        }
        return c.inputStream.bufferedReader().use { it.readText() }
    }
}
