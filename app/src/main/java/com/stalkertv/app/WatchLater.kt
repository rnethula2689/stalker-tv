package com.stalkertv.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Watch Later" store (per active provider). A simple tag list of movies and episodes the user
 * wants to keep for later — mirrors [Favorites]'s shape. Episodes nest by their
 * "Series / Season / Episode" title the same way favourites do. Newest first.
 */
object WatchLater {
    data class Entry(
        val kind: String, val id: String, val title: String,
        val poster: String, val source: String, val added: Long
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    private fun key(ctx: Context) = "watchlater:" + (Configs.active(ctx)?.sig() ?: "default") + ContentProfiles.scopeSuffix(ctx)

    fun all(ctx: Context): MutableList<Entry> {
        val out = ArrayList<Entry>()
        try {
            val a = JSONArray(prefs(ctx).getString(key(ctx), "[]") ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(
                    Entry(
                        o.optString("kind"), o.optString("id"), o.optString("title"),
                        o.optString("poster"), o.optString("source"), o.optLong("added")
                    )
                )
            }
        } catch (_: Exception) {}
        return out.sortedByDescending { it.added }.toMutableList()
    }

    private fun save(ctx: Context, list: List<Entry>) {
        val a = JSONArray()
        for (e in list) a.put(
            JSONObject().put("kind", e.kind).put("id", e.id).put("title", e.title)
                .put("poster", e.poster).put("source", e.source).put("added", e.added)
        )
        prefs(ctx).edit().putString(key(ctx), a.toString()).apply()
    }

    fun has(ctx: Context, id: String): Boolean = all(ctx).any { it.id == id }

    /** Add if absent. @return true if newly added, false if it was already saved. */
    fun add(ctx: Context, kind: String, id: String, title: String, poster: String, source: String): Boolean {
        if (id.isBlank()) return false
        val list = all(ctx)
        if (list.any { it.id == id }) return false
        list.add(Entry(kind, id, title, poster, source, System.currentTimeMillis()))
        save(ctx, list)
        return true
    }

    fun remove(ctx: Context, id: String) = save(ctx, all(ctx).filterNot { it.id == id })
    fun removeIds(ctx: Context, ids: Set<String>) = save(ctx, all(ctx).filterNot { ids.contains(it.id) })
    fun byKind(ctx: Context, kind: String): List<Entry> = all(ctx).filter { it.kind == kind }
    fun clearAll(ctx: Context) { prefs(ctx).edit().remove(key(ctx)).apply() }
}
