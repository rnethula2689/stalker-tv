package com.stalkertv.app

import android.content.Context
import org.json.JSONObject

/**
 * Lightweight on-device taste profile for the "For You" rail. When a movie is played we tokenise its
 * genre/category string and bump per-token counts (scoped per account + content profile). "For You"
 * then ranks candidate titles by how well their genre overlaps the user's accumulated taste. Purely
 * local; no network, no tracking off-device.
 */
object Taste {
    private const val PREF = "taste"
    private val STOP = setOf("the", "and", "for", "with", "tv", "hd", "fhd", "uhd", "4k", "sd", "24x7", "vod")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun key(ctx: Context) = "taste:" + (Configs.active(ctx)?.sig() ?: "default") + ContentProfiles.scopeSuffix(ctx)

    fun tokens(s: String): List<String> =
        s.lowercase(java.util.Locale.US)
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in STOP }

    private fun load(ctx: Context): JSONObject =
        try { JSONObject(prefs(ctx).getString(key(ctx), "{}") ?: "{}") } catch (_: Exception) { JSONObject() }

    /** Record a play: bump the count for each token of the title's genre/category. */
    fun record(ctx: Context, genre: String) {
        val toks = tokens(genre)
        if (toks.isEmpty()) return
        val o = load(ctx)
        for (t in toks) o.put(t, o.optInt(t, 0) + 1)
        prefs(ctx).edit().putString(key(ctx), o.toString()).apply()
    }

    fun hasData(ctx: Context): Boolean = load(ctx).length() > 0

    /** Score a candidate's genre against the taste profile (sum of matching token counts). */
    fun score(ctx: Context, genre: String): Int {
        val o = load(ctx)
        if (o.length() == 0) return 0
        return tokens(genre).sumOf { o.optInt(it, 0) }
    }

    fun clear(ctx: Context) { prefs(ctx).edit().remove(key(ctx)).apply() }
}
