package com.stalkertv.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Content-customization profiles (Prime/YouTube style). Each profile chooses which Live TV
 * categories (genres) and which Movie/VOD categories are visible, so e.g. a "Telugu" profile only
 * sees Telugu live + movie folders. Profiles are device-local; the active one is remembered.
 */
object ContentProfiles {
    private const val PREF = "content_profiles"
    private const val KEY_LIST = "list"
    private const val KEY_ACTIVE = "active"
    private const val KEY_SEEN = "setupSeen"

    data class Profile(
        val id: String,
        var name: String,
        var color: Int,
        var allLive: Boolean,                 // true = every Live category visible
        var liveCats: MutableSet<String>,     // genre ids when not allLive
        var allVod: Boolean,                  // true = every Movie category visible
        var vodCats: MutableSet<String>       // vod category ids when not allVod
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun list(ctx: Context): List<Profile> {
        val raw = prefs(ctx).getString(KEY_LIST, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Profile>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    Profile(
                        o.optString("id"),
                        o.optString("name"),
                        o.optInt("color", COLORS[0]),
                        o.optBoolean("allLive", true),
                        jsonToSet(o.optJSONArray("liveCats")),
                        o.optBoolean("allVod", true),
                        jsonToSet(o.optJSONArray("vodCats"))
                    )
                )
            }
            out
        } catch (_: Exception) { emptyList() }
    }

    private fun jsonToSet(a: JSONArray?): MutableSet<String> {
        val s = LinkedHashSet<String>()
        if (a != null) for (i in 0 until a.length()) s.add(a.optString(i))
        return s
    }

    private fun saveList(ctx: Context, list: List<Profile>) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(
                JSONObject()
                    .put("id", p.id).put("name", p.name).put("color", p.color)
                    .put("allLive", p.allLive).put("liveCats", JSONArray(p.liveCats.toList()))
                    .put("allVod", p.allVod).put("vodCats", JSONArray(p.vodCats.toList()))
            )
        }
        prefs(ctx).edit().putString(KEY_LIST, arr.toString()).apply()
    }

    fun save(ctx: Context, p: Profile) {
        val list = list(ctx).toMutableList()
        val idx = list.indexOfFirst { it.id == p.id }
        if (idx >= 0) list[idx] = p else list.add(p)
        saveList(ctx, list)
    }

    fun delete(ctx: Context, id: String) {
        saveList(ctx, list(ctx).filter { it.id != id })
        if (activeId(ctx) == id) setActive(ctx, null)
    }

    fun get(ctx: Context, id: String?): Profile? = if (id == null) null else list(ctx).firstOrNull { it.id == id }

    fun activeId(ctx: Context): String? = prefs(ctx).getString(KEY_ACTIVE, null)

    fun setActive(ctx: Context, id: String?) {
        prefs(ctx).edit().apply { if (id == null) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, id) }.apply()
    }

    /** The active profile, falling back to the first saved one (so filtering is consistent). */
    fun active(ctx: Context): Profile? = get(ctx, activeId(ctx)) ?: list(ctx).firstOrNull()

    fun setupSeen(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SEEN, false)
    fun setSetupSeen(ctx: Context) { prefs(ctx).edit().putBoolean(KEY_SEEN, true).apply() }

    fun newId(): String = "p" + System.currentTimeMillis().toString(36)

    // The active id without parsing the whole list when one is explicitly set (hot path).
    private fun scopeId(ctx: Context): String? = activeId(ctx) ?: list(ctx).firstOrNull()?.id

    /** Per-profile namespace for prefs keys (empty = no profile → shared/global, backward compatible). */
    fun scopeSuffix(ctx: Context): String = scopeId(ctx)?.let { "@$it" } ?: ""
    /** Per-profile sub-folder suffix for file-based stores (downloads / recordings). */
    fun scopeDir(ctx: Context): String = scopeId(ctx)?.let { "-$it" } ?: ""

    val COLORS = intArrayOf(
        0xFF19C37D.toInt(), 0xFF4F8CFF.toInt(), 0xFFFF6B6B.toInt(),
        0xFFFFB020.toInt(), 0xFFB36BFF.toInt(), 0xFF20C9C9.toInt(), 0xFFE05BD6.toInt()
    )

    // Visibility — default to visible when there's no profile or it's an all-access profile.
    fun liveCatVisible(ctx: Context, genreId: String): Boolean {
        val p = active(ctx) ?: return true
        return p.allLive || p.liveCats.contains(genreId)
    }

    fun vodCatVisible(ctx: Context, catId: String): Boolean {
        val p = active(ctx) ?: return true
        return p.allVod || p.vodCats.contains(catId)
    }
}
