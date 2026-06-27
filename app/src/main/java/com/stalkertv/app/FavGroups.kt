package com.stalkertv.app

import android.content.Context
import androidx.appcompat.app.AlertDialog
import org.json.JSONArray
import org.json.JSONObject

/**
 * User-defined favourite GROUPS (e.g. News, Sports, Kids), per active provider and per scope.
 * scope = "live" (channels) or "vod" (movies/series/episodes). Stores an ordered list of group
 * names plus a map of itemId → groupName. Items with no entry are "Ungrouped".
 */
object FavGroups {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    private fun key(ctx: Context, scope: String) = "favgroups_$scope:" + (Configs.active(ctx)?.sig() ?: "default") + ContentProfiles.scopeSuffix(ctx)

    private fun read(ctx: Context, scope: String): JSONObject =
        try { JSONObject(prefs(ctx).getString(key(ctx, scope), "{}") ?: "{}") } catch (_: Exception) { JSONObject() }

    private fun write(ctx: Context, scope: String, o: JSONObject) {
        prefs(ctx).edit().putString(key(ctx, scope), o.toString()).apply()
    }

    fun groups(ctx: Context, scope: String): MutableList<String> {
        val a = read(ctx, scope).optJSONArray("groups") ?: JSONArray()
        val out = ArrayList<String>()
        for (i in 0 until a.length()) out.add(a.getString(i))
        return out
    }

    fun addGroup(ctx: Context, scope: String, name: String) {
        val n = name.trim(); if (n.isEmpty()) return
        val o = read(ctx, scope)
        val arr = o.optJSONArray("groups") ?: JSONArray()
        for (i in 0 until arr.length()) if (arr.getString(i).equals(n, true)) return
        arr.put(n); o.put("groups", arr); write(ctx, scope, o)
    }

    fun deleteGroup(ctx: Context, scope: String, name: String) {
        val o = read(ctx, scope)
        val arr = JSONArray(groups(ctx, scope).filterNot { it.equals(name, true) })
        val map = o.optJSONObject("map") ?: JSONObject()
        for (k in map.keys().asSequence().toList()) if (map.optString(k).equals(name, true)) map.remove(k)
        o.put("groups", arr); o.put("map", map); write(ctx, scope, o)
    }

    fun renameGroup(ctx: Context, scope: String, old: String, new: String) {
        val n = new.trim(); if (n.isEmpty()) return
        val o = read(ctx, scope)
        val arr = JSONArray(groups(ctx, scope).map { if (it.equals(old, true)) n else it })
        val map = o.optJSONObject("map") ?: JSONObject()
        for (k in map.keys().asSequence().toList()) if (map.optString(k).equals(old, true)) map.put(k, n)
        o.put("groups", arr); o.put("map", map); write(ctx, scope, o)
    }

    /** The group an item belongs to, or null if Ungrouped. */
    fun groupOf(ctx: Context, scope: String, id: String): String? {
        val map = read(ctx, scope).optJSONObject("map") ?: return null
        val g = map.optString(id, "")
        return if (g.isEmpty()) null else g
    }

    /** Assign [id] to [group] (null/blank = Ungrouped). Creates the group if it's new. */
    fun setGroup(ctx: Context, scope: String, id: String, group: String?) {
        val o = read(ctx, scope)
        val arr = o.optJSONArray("groups") ?: JSONArray()
        val map = o.optJSONObject("map") ?: JSONObject()
        if (group.isNullOrBlank()) {
            map.remove(id)
        } else {
            map.put(id, group)
            var found = false
            for (i in 0 until arr.length()) if (arr.getString(i).equals(group, true)) { found = true; break }
            if (!found) arr.put(group)
        }
        o.put("groups", arr); o.put("map", map); write(ctx, scope, o)
    }
}

/** The "Add to group" dialog shown when a favourite is newly added. */
object FavGroupPicker {
    fun show(ctx: Context, scope: String, id: String, onDone: () -> Unit = {}) {
        val groups = FavGroups.groups(ctx, scope)
        val labels = (groups + listOf("➕  New group…", "Ungrouped")).toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle("Add to group")
            .setItems(labels) { _, w ->
                when {
                    w < groups.size -> { FavGroups.setGroup(ctx, scope, id, groups[w]); onDone() }
                    w == groups.size -> promptNewGroup(ctx, scope, id, onDone)
                    else -> { FavGroups.setGroup(ctx, scope, id, null); onDone() }
                }
            }
            .setOnCancelListener { onDone() }
            .show()
    }

    private fun promptNewGroup(ctx: Context, scope: String, id: String, onDone: () -> Unit) {
        val input = android.widget.EditText(ctx).apply { hint = "Group name (e.g. Sports)" }
        AlertDialog.Builder(ctx)
            .setTitle("New group")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) FavGroups.setGroup(ctx, scope, id, n)
                onDone()
            }
            .setNegativeButton("Cancel") { _, _ -> onDone() }
            .setOnCancelListener { onDone() }
            .show()
    }
}
