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

    fun ossKey(ctx: Context): String = prefs(ctx).getString("ossKey", "") ?: ""
    fun setOssKey(ctx: Context, key: String) { prefs(ctx).edit().putString("ossKey", key).apply() }

    /** Optional external XMLTV EPG URL. When set, the TV Guide prefers it (matched by channel name)
     *  and falls back to the portal's own EPG. Blank = portal EPG only. */
    fun epgXmltvUrl(ctx: Context): String = (prefs(ctx).getString("epgXmltvUrl", "") ?: "").trim()
    fun setEpgXmltvUrl(ctx: Context, url: String) { prefs(ctx).edit().putString("epgXmltvUrl", url.trim()).apply() }

    /** Home personalization: when on, the matching rail is omitted from the Home screen. */
    fun hideRecentlyAdded(ctx: Context): Boolean = prefs(ctx).getBoolean("hideRecentlyAdded", false)
    fun setHideRecentlyAdded(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("hideRecentlyAdded", v).apply() }
    fun hideForYou(ctx: Context): Boolean = prefs(ctx).getBoolean("hideForYou", false)
    fun setHideForYou(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("hideForYou", v).apply() }

    /** A handful of recent poster URLs, used to paint the loading splash montage (Strimix-style). */
    fun splashPosters(ctx: Context): List<String> =
        (prefs(ctx).getString("splashPosters", "") ?: "").split("\n").filter { it.isNotBlank() }
    fun setSplashPosters(ctx: Context, urls: List<String>) {
        prefs(ctx).edit().putString("splashPosters", urls.filter { it.isNotBlank() }.distinct().take(40).joinToString("\n")).apply()
    }

    /** Sort order for folder contents (live channels & movies), shared across all folders.
     *  0 = Default (provider order: channel number / newest movies), 1 = Name A–Z, 2 = Name Z–A. */
    const val SORT_DEFAULT = 0
    const val SORT_AZ = 1
    const val SORT_ZA = 2
    fun sortMode(ctx: Context): Int = prefs(ctx).getInt("sortMode", SORT_DEFAULT)
    fun setSortMode(ctx: Context, mode: Int) { prefs(ctx).edit().putInt("sortMode", mode).apply() }
    /** Advance to the next sort mode in the cycle and return it. */
    fun cycleSortMode(ctx: Context): Int {
        val next = (sortMode(ctx) + 1) % 3
        setSortMode(ctx, next)
        return next
    }

    /** Parental PIN guarding adult / restricted (censored) channels. Empty = not set yet. */
    fun parentalPin(ctx: Context): String = prefs(ctx).getString("parentalPin", "") ?: ""
    fun setParentalPin(ctx: Context, pin: String) { prefs(ctx).edit().putString("parentalPin", pin).apply() }

    // ---- Autoplay next episode (global toggle, default on) ----
    fun autoplay(ctx: Context): Boolean = prefs(ctx).getBoolean("autoplay_next", true)
    fun setAutoplay(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("autoplay_next", on).apply() }

    // ---- Playback: stream buffering + hardware decoding (global, both players) ----
    /** Buffering level. Higher = smoother on flaky connections, but slower to start / zap. */
    const val BUF_LOW = 0
    const val BUF_NORMAL = 1
    const val BUF_HIGH = 2
    fun bufferMode(ctx: Context): Int = prefs(ctx).getInt("bufferMode", BUF_NORMAL)
    fun setBufferMode(ctx: Context, m: Int) { prefs(ctx).edit().putInt("bufferMode", m.coerceIn(0, 2)).apply() }
    fun bufferLabel(ctx: Context): String = when (bufferMode(ctx)) {
        BUF_LOW -> "Low (fast zap)"; BUF_HIGH -> "High (smoothest)"; else -> "Normal"
    }
    /** libVLC network-caching (ms) for the chosen level. */
    fun netCachingMs(ctx: Context): Int = when (bufferMode(ctx)) {
        BUF_LOW -> 800; BUF_HIGH -> 4000; else -> 1500
    }
    /** ExoPlayer min/max buffer (ms) for the chosen level. */
    fun exoBufferMs(ctx: Context): Pair<Int, Int> = when (bufferMode(ctx)) {
        BUF_LOW -> 10_000 to 30_000; BUF_HIGH -> 40_000 to 120_000; else -> 20_000 to 60_000
    }

    /** Hardware video decoding. On = use the GPU decoder (smooth, battery-light); turn Off if a
     *  channel shows a green/garbled picture or audio-only (forces software decoding). */
    fun hwDecode(ctx: Context): Boolean = prefs(ctx).getBoolean("hwDecode", true)
    fun setHwDecode(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("hwDecode", on).apply() }

    // ---- Favourite channels (per active provider) ----
    private fun favKey(ctx: Context) = "fav:" + (active(ctx)?.sig() ?: "default") + ContentProfiles.scopeSuffix(ctx)

    fun favorites(ctx: Context): LinkedHashSet<String> {
        val set = LinkedHashSet<String>()
        try {
            val a = JSONArray(prefs(ctx).getString(favKey(ctx), "[]") ?: "[]")
            for (i in 0 until a.length()) set.add(a.getString(i))
        } catch (_: Exception) {}
        return set
    }

    fun isFavorite(ctx: Context, id: String): Boolean = favorites(ctx).contains(id)

    fun clearFavorites(ctx: Context) { prefs(ctx).edit().remove(favKey(ctx)).apply() }

    /** @return true if it's now a favourite, false if removed. */
    fun toggleFavorite(ctx: Context, id: String): Boolean {
        val set = favorites(ctx)
        val nowFav = if (set.contains(id)) { set.remove(id); false } else { set.add(id); true }
        val a = JSONArray(); for (s in set) a.put(s)
        prefs(ctx).edit().putString(favKey(ctx), a.toString()).apply()
        return nowFav
    }

    fun active(ctx: Context): Account? {
        val list = load(ctx)
        val i = activeIndex(ctx)
        return if (i in list.indices) list[i] else list.firstOrNull()
    }
}
