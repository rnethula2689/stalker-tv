package com.stalkertv.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal TMDb client used only to resolve a movie's official YouTube trailer id.
 * Needed because (a) this portal returns no trailer/tmdb_id, and (b) Fire TV's YouTube app
 * opens specific videos reliably but ignores search. Key comes from BuildConfig.TMDB_KEY
 * (injected from the CI secret); when blank, callers fall back to a YouTube search.
 */
object Tmdb {
    data class Meta(val posterUrl: String?, val rating: Double, val voteCount: Int, val overview: String?)

    /** One search call → poster + rating + overview for a movie (portal returns ratings=0/no art). */
    fun movieMeta(apiKey: String, title: String, year: String): Meta? {
        if (apiKey.isBlank() || title.isBlank()) return null
        return try {
            val q = URLEncoder.encode(title, "UTF-8")
            fun query(yr: String): JSONObject? {
                val yp = if (yr.isNotBlank()) "&year=$yr" else ""
                val js = JSONObject(httpGet("https://api.themoviedb.org/3/search/movie?api_key=$apiKey&query=$q$yp"))
                return js.optJSONArray("results")?.takeIf { it.length() > 0 }?.optJSONObject(0)
            }
            val r = query(year) ?: (if (year.isNotBlank()) query("") else null) ?: return null
            val poster = r.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
                ?.let { "https://image.tmdb.org/t/p/w500$it" }
            Meta(poster, r.optDouble("vote_average", 0.0), r.optInt("vote_count", 0), r.optString("overview").takeIf { it.isNotBlank() })
        } catch (_: Exception) { null }
    }

    /** Returns a YouTube video id for the best trailer, or null (no key / no match / network error). */
    fun trailerYoutubeId(apiKey: String, title: String, year: String): String? {
        if (apiKey.isBlank() || title.isBlank()) return null
        return try {
            val movieId = searchMovieId(apiKey, title, year)
                ?: (if (year.isNotBlank()) searchMovieId(apiKey, title, "") else null)
                ?: return null
            bestTrailerKey(apiKey, movieId)
        } catch (_: Exception) { null }
    }

    private fun searchMovieId(apiKey: String, title: String, year: String): Int? {
        val q = URLEncoder.encode(title, "UTF-8")
        val yp = if (year.isNotBlank()) "&year=$year" else ""
        val js = JSONObject(httpGet("https://api.themoviedb.org/3/search/movie?api_key=$apiKey&query=$q$yp"))
        val results = js.optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        return results.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }
    }

    private fun bestTrailerKey(apiKey: String, movieId: Int): String? {
        val js = JSONObject(httpGet("https://api.themoviedb.org/3/movie/$movieId/videos?api_key=$apiKey"))
        val vids = js.optJSONArray("results") ?: return null
        var best: String? = null
        var bestScore = -1
        for (i in 0 until vids.length()) {
            val v = vids.optJSONObject(i) ?: continue
            if (v.optString("site") != "YouTube") continue
            val key = v.optString("key").ifBlank { continue }
            var score = when (v.optString("type")) {
                "Trailer" -> 4
                "Teaser" -> 2
                else -> 0
            }
            if (v.optBoolean("official")) score += 1
            if (score > bestScore) { bestScore = score; best = key }
        }
        return best
    }

    private fun httpGet(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000; requestMethod = "GET"
        }
        return c.inputStream.bufferedReader().use { it.readText() }
    }
}
