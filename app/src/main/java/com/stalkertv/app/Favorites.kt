package com.stalkertv.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rich favourites store (movies / series / episodes) per active provider. Channels keep their own
 * lightweight id-set in [Configs]; this holds enough (title/poster/source) to show and replay
 * favourited VOD later. Keyed by account so providers don't share favourites.
 */
object Favorites {
    data class Entry(val kind: String, val id: String, val title: String, val poster: String, val source: String)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    private fun key(ctx: Context) = "fav2:" + (Configs.active(ctx)?.sig() ?: "default") + ContentProfiles.scopeSuffix(ctx)

    fun all(ctx: Context): MutableList<Entry> {
        val out = ArrayList<Entry>()
        try {
            val a = JSONArray(prefs(ctx).getString(key(ctx), "[]") ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Entry(o.optString("kind"), o.optString("id"), o.optString("title"), o.optString("poster"), o.optString("source")))
            }
        } catch (_: Exception) {}
        return out
    }

    private fun save(ctx: Context, list: List<Entry>) {
        val a = JSONArray()
        for (e in list) a.put(
            JSONObject().put("kind", e.kind).put("id", e.id).put("title", e.title).put("poster", e.poster).put("source", e.source)
        )
        prefs(ctx).edit().putString(key(ctx), a.toString()).apply()
    }

    fun isFav(ctx: Context, kind: String, id: String): Boolean = all(ctx).any { it.kind == kind && it.id == id }

    /** @return true if now a favourite, false if removed. */
    fun toggle(ctx: Context, e: Entry): Boolean {
        val list = all(ctx)
        val existing = list.firstOrNull { it.kind == e.kind && it.id == e.id }
        val nowFav = if (existing != null) { list.remove(existing); false } else { list.add(e); true }
        save(ctx, list)
        return nowFav
    }

    fun byKind(ctx: Context, kind: String): List<Entry> = all(ctx).filter { it.kind == kind }

    fun clearAll(ctx: Context) { prefs(ctx).edit().remove(key(ctx)).apply() }
}
