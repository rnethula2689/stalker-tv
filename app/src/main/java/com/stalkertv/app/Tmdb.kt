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
        // Drop an appended category suffix the portal adds to search results, e.g.
        // " - ENGLISH | LATEST MOVIES 4K UHD" or " | LATEST MOVIES".
        t = t.replace(Regex("\\s-\\s[^-]*\\|.*$"), " ")
        t = t.replace(Regex("\\s\\|.*$"), " ")
        // Drop bracketed decorations: (English-Amazon Prime), (4K), [tags]
        t = t.replace(Regex("\\([^)]*\\)"), " ").replace(Regex("\\[[^\\]]*\\]"), " ")
        // Drop loose quality/format tags
        t = t.replace(Regex("(?i)\\b(4k|uhd|fhd|hd|sd|hq|1080p?|720p?|480p|web-?dl|blu-?ray|x26[45]|hevc|hdr|dolby|atmos|imax|remastered|extended|uncut|multi|dual|dubbed|sub(bed)?)\\b"), " ")
        t = t.replace(Regex("[/_.:]+"), " ").replace(Regex("\\s{2,}"), " ").trim()
        // Strip a dangling trailing " -"
        t = t.replace(Regex("\\s-\\s*$"), "").trim()
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

    /** Full details for a movie OR TV series, in one call. Tries the likely type first, then the other. */
    fun details(apiKey: String, title: String, year: String, isSeries: Boolean = false): Details? {
        if (apiKey.isBlank() || title.isBlank()) return null
        return if (isSeries) (tvDetails(apiKey, title, year) ?: movieDetails(apiKey, title, year))
        else (movieDetails(apiKey, title, year) ?: tvDetails(apiKey, title, year))
    }

    private fun searchId(apiKey: String, kind: String, title: String, year: String): Int? {
        val q = URLEncoder.encode(cleanTitle(title), "UTF-8")
        val yp = if (year.isNotBlank()) (if (kind == "tv") "&first_air_date_year=$year" else "&year=$year") else ""
        val js = JSONObject(httpGet("https://api.themoviedb.org/3/search/$kind?api_key=$apiKey&query=$q$yp"))
        val results = js.optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        return results.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }
    }

    private fun movieDetails(apiKey: String, title: String, year: String): Details? = try {
        val id = searchId(apiKey, "movie", title, year) ?: (if (year.isNotBlank()) searchId(apiKey, "movie", title, "") else null)
        if (id == null) null else parseDetails(JSONObject(httpGet("https://api.themoviedb.org/3/movie/$id?api_key=$apiKey&append_to_response=videos,credits")), title, false)
    } catch (_: Exception) { null }

    private fun tvDetails(apiKey: String, title: String, year: String): Details? = try {
        val id = searchId(apiKey, "tv", title, year) ?: (if (year.isNotBlank()) searchId(apiKey, "tv", title, "") else null)
        if (id == null) null else parseDetails(JSONObject(httpGet("https://api.themoviedb.org/3/tv/$id?api_key=$apiKey&append_to_response=videos,aggregate_credits")), title, true)
    } catch (_: Exception) { null }

    private fun parseDetails(js: JSONObject, fallbackTitle: String, tv: Boolean): Details {
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
        val castArr = js.optJSONObject("credits")?.optJSONArray("cast")
            ?: js.optJSONObject("aggregate_credits")?.optJSONArray("cast")
        if (castArr != null) for (i in 0 until minOf(castArr.length(), 20)) {
            val c = castArr.optJSONObject(i) ?: continue
            val name = c.optString("name"); if (name.isBlank()) continue
            val character = c.optString("character").ifBlank { c.optJSONArray("roles")?.optJSONObject(0)?.optString("character") ?: "" }
            cast.add(CastMember(name, character, img(c.optString("profile_path"), "w185")))
        }
        val runtime = if (tv) (js.optJSONArray("episode_run_time")?.let { if (it.length() > 0) it.optInt(0) else 0 } ?: 0) else js.optInt("runtime", 0)
        return Details(
            (if (tv) js.optString("name") else js.optString("title")).ifBlank { fallbackTitle },
            js.optString("tagline"), js.optString("overview"),
            img(js.optString("poster_path"), "w500"), img(js.optString("backdrop_path"), "w300"),
            js.optDouble("vote_average", 0.0),
            if (tv) js.optString("first_air_date") else js.optString("release_date"),
            runtime, genres, trailers, cast
        )
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
