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

    /** Strip portal decorations (quality/lang/provider tags, brackets) so TMDb search can match.
     *  e.g. "The Magnificent Mendez (English-Amazon Prime) (4K)" -> "The Magnificent Mendez". */
    fun cleanTitle(raw: String): String {
        var t = raw
        t = t.replace(Regex("\\([^)]*\\)"), " ").replace(Regex("\\[[^\\]]*\\]"), " ")
        t = t.replace(Regex("(?i)\\b(4k|uhd|fhd|hd|sd|hq|1080p?|720p?|480p|web-?dl|blu-?ray|x26[45]|hevc|hdr|dolby|atmos|imax|remastered|extended|uncut|multi|dual|dubbed|sub(bed)?)\\b"), " ")
        t = t.replace(Regex("[|/_.:]+"), " ").replace(Regex("\\s{2,}"), " ").trim()
        return t.ifBlank { raw.trim() }
    }

    /** One search call → poster + rating + overview for a movie (portal returns ratings=0/no art). */
    fun movieMeta(apiKey: String, title: String, year: String): Meta? {
        if (apiKey.isBlank() || title.isBlank()) return null
        return try {
            val q = URLEncoder.encode(cleanTitle(title), "UTF-8")
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

    data class CastMember(val name: String, val character: String, val profileUrl: String?)
    data class Trailer(val name: String, val youtubeKey: String) {
        val thumbUrl get() = "https://img.youtube.com/vi/$youtubeKey/hqdefault.jpg"
    }
    data class Details(
        val title: String, val tagline: String, val overview: String,
        val posterUrl: String?, val backdropUrl: String?,
        val rating: Double, val releaseDate: String, val runtimeMin: Int,
        val genres: List<String>, val trailers: List<Trailer>, val cast: List<CastMember>
    )

    /** Full movie details (poster, backdrop, tagline, runtime, genres, trailers, cast) in ONE call. */
    fun details(apiKey: String, title: String, year: String): Details? {
        if (apiKey.isBlank() || title.isBlank()) return null
        return try {
            val id = searchMovieId(apiKey, title, year)
                ?: (if (year.isNotBlank()) searchMovieId(apiKey, title, "") else null) ?: return null
            val js = JSONObject(httpGet("https://api.themoviedb.org/3/movie/$id?api_key=$apiKey&append_to_response=videos,credits"))
            fun img(path: String, size: String) = path.takeIf { it.isNotBlank() && it != "null" }?.let { "https://image.tmdb.org/t/p/$size$it" }
            val genres = js.optJSONArray("genres")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
            } ?: emptyList()
            val trailers = ArrayList<Trailer>()
            js.optJSONObject("videos")?.optJSONArray("results")?.let { a ->
                for (i in 0 until a.length()) {
                    val v = a.optJSONObject(i) ?: continue
                    if (v.optString("site") != "YouTube") continue
                    val type = v.optString("type"); if (type != "Trailer" && type != "Teaser") continue
                    val key = v.optString("key"); if (key.isBlank()) continue
                    trailers.add(Trailer(v.optString("name").ifBlank { type }, key))
                }
            }
            val cast = ArrayList<CastMember>()
            js.optJSONObject("credits")?.optJSONArray("cast")?.let { a ->
                for (i in 0 until minOf(a.length(), 20)) {
                    val c = a.optJSONObject(i) ?: continue
                    val name = c.optString("name"); if (name.isBlank()) continue
                    cast.add(CastMember(name, c.optString("character"), img(c.optString("profile_path"), "w185")))
                }
            }
            Details(
                js.optString("title").ifBlank { title }, js.optString("tagline"), js.optString("overview"),
                img(js.optString("poster_path"), "w500"), img(js.optString("backdrop_path"), "w300"),
                js.optDouble("vote_average", 0.0), js.optString("release_date"), js.optInt("runtime", 0),
                genres, trailers, cast
            )
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
        val q = URLEncoder.encode(cleanTitle(title), "UTF-8")
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
