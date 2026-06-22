package com.stalkertv.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** On-device store for one or more IPTV provider configurations. */
object Configs {
    private const val PREF = "cfg"

    /** Set true whenever the active config changes, so the browse screen reloads on resume. */
    var dirty = false

    data class Account(val name: String, val portal: String, val mac: String, val sn: String) {
        fun sig() = "$portal|$mac|$sn"
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(ctx: Context): MutableList<Account> {
        val list = ArrayList<Account>()
        try {
            val arr = JSONArray(prefs(ctx).getString("accounts", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Account(o.optString("name"), o.optString("portal"), o.optString("mac"), o.optString("sn")))
            }
        } catch (_: Exception) {}
        // One-time migration of the old single-login creds into a named config.
        if (list.isEmpty()) {
            val p = prefs(ctx).getString("portal", "") ?: ""
            val m = prefs(ctx).getString("mac", "") ?: ""
            val s = prefs(ctx).getString("sn", "") ?: ""
            if (p.isNotBlank() && m.isNotBlank()) {
                list.add(Account("My Provider", p, m, s))
                save(ctx, list)
                setActive(ctx, 0)
            }
        }
        return list
    }

    fun save(ctx: Context, list: List<Account>) {
        val arr = JSONArray()
        for (a in list) {
            arr.put(JSONObject().put("name", a.name).put("portal", a.portal).put("mac", a.mac).put("sn", a.sn))
        }
        prefs(ctx).edit().putString("accounts", arr.toString()).apply()
    }

    fun activeIndex(ctx: Context): Int = prefs(ctx).getInt("active", 0)

    fun setActive(ctx: Context, index: Int) {
        prefs(ctx).edit().putInt("active", index).apply()
    }

    fun active(ctx: Context): Account? {
        val list = load(ctx)
        val i = activeIndex(ctx)
        return if (i in list.indices) list[i] else list.firstOrNull()
    }
}
