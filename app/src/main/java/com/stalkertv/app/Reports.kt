package com.stalkertv.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local "report not working" log. The user flags a channel/title that won't play from the player menu;
 * we keep a small rolling list (newest first) shown on the Diagnostics screen. Purely local — no
 * network — so it's a self-help record (which sources have been flaky) rather than a server report.
 */
object Reports {
    private const val PREF = "reports"
    private const val MAX = 100

    data class R(val title: String, val source: String, val ts: Long)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<R> {
        val out = ArrayList<R>()
        try {
            val arr = JSONArray(prefs(ctx).getString("list", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(R(o.optString("title"), o.optString("source"), o.optLong("ts")))
            }
        } catch (_: Exception) {}
        return out
    }

    fun count(ctx: Context): Int = all(ctx).size

    fun add(ctx: Context, title: String, source: String) {
        val list = ArrayList(all(ctx))
        list.add(0, R(title.ifBlank { "(unknown)" }, source, System.currentTimeMillis()))
        save(ctx, list.take(MAX))
    }

    fun clear(ctx: Context) { prefs(ctx).edit().remove("list").apply() }

    private fun save(ctx: Context, list: List<R>) {
        val arr = JSONArray()
        for (r in list) arr.put(JSONObject().put("title", r.title).put("source", r.source).put("ts", r.ts))
        prefs(ctx).edit().putString("list", arr.toString()).apply()
    }
}
