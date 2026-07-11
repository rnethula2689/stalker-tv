package com.stalkertv.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Continue Watching" store (per provider). Remembers playback position for movies/episodes
 * (kind="vod") and the last-watched live channel (kind="live", a single rolling entry). Finished
 * items (~95%+) are dropped. Newest first; capped so it doesn't grow without bound.
 */
object Resume {
    const val LIVE_ID = "live_last"
    private const val CAP = 40
    private const val MIN_RESUME_MS = 30_000L

    data class Entry(
        val id: String, val kind: String, val title: String, val poster: String,
        val source: String, val position: Long, val duration: Long, val updated: Long,
        val year: String = "",
        val restricted: Boolean = false   // came from an adult/censored (PIN-locked) channel → keep off home rails
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    private fun key(ctx: Context) = "resume:" + (Configs.active(ctx)?.sig() ?: "default") + ContentProfiles.scopeSuffix(ctx)

    fun all(ctx: Context): List<Entry> {
        val out = ArrayList<Entry>()
        try {
            val a = JSONArray(prefs(ctx).getString(key(ctx), "[]") ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(
                    Entry(
                        o.optString("id"), o.optString("kind"), o.optString("title"), o.optString("poster"),
                        o.optString("source"), o.optLong("position"), o.optLong("duration"), o.optLong("updated"),
                        o.optString("year"), o.optBoolean("restricted", false)
                    )
                )
            }
        } catch (_: Exception) {}
        return out.sortedByDescending { it.updated }
    }

    private fun saveList(ctx: Context, list: List<Entry>) {
        val a = JSONArray()
        for (e in list.sortedByDescending { it.updated }.take(CAP)) a.put(
            JSONObject().put("id", e.id).put("kind", e.kind).put("title", e.title).put("poster", e.poster)
                .put("source", e.source).put("position", e.position).put("duration", e.duration).put("updated", e.updated)
                .put("year", e.year).put("restricted", e.restricted)
        )
        prefs(ctx).edit().putString(key(ctx), a.toString()).apply()
    }

    fun get(ctx: Context, id: String): Entry? = all(ctx).firstOrNull { it.id == id }

    /** True if [e] has a meaningful position to resume from. */
    fun resumable(e: Entry?): Boolean =
        e != null && e.kind == "vod" && e.position > MIN_RESUME_MS &&
            (e.duration <= 0 || e.position < e.duration * 95 / 100)

    fun save(ctx: Context, id: String, kind: String, title: String, poster: String, source: String, position: Long, duration: Long, year: String = "", restricted: Boolean = false) {
        if (id.isBlank()) return
        // Preserve a previously-saved year if this call doesn't carry one (e.g. resumed from Continue Watching).
        val prevYear = all(ctx).firstOrNull { it.id == id }?.year ?: ""
        val list = all(ctx).filterNot { it.id == id }.toMutableList()
        // Drop finished VOD instead of storing it.
        if (kind == "vod" && duration > 0 && position >= duration * 95 / 100) { saveList(ctx, list); return }
        list.add(Entry(id, kind, title, poster, source, position, duration, System.currentTimeMillis(), year.ifBlank { prevYear }, restricted))
        saveList(ctx, list)
    }

    fun remove(ctx: Context, id: String) = saveList(ctx, all(ctx).filterNot { it.id == id })

    fun clearAll(ctx: Context) { prefs(ctx).edit().remove(key(ctx)).apply() }
}
