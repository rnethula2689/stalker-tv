package com.stalkertv.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Local backup/restore of the user's personal lists — Favourites, Watch Later and Continue Watching —
 * to a single JSON file (no portal credentials included, so it's safe to share). This is the concrete,
 * testable form of "sync": export on one device, import on another. Import MERGES (adds what's missing)
 * rather than overwriting, so restoring never wipes existing data.
 */
object Backup {
    const val APP_TAG = "vibetv"

    data class Result(val favorites: Int, val watchLater: Int, val resume: Int)

    fun exportJson(ctx: Context): String {
        val o = JSONObject()
        o.put("_app", APP_TAG)
        o.put("_v", 1)
        val favs = JSONArray()
        for (e in Favorites.all(ctx)) favs.put(JSONObject()
            .put("kind", e.kind).put("id", e.id).put("title", e.title).put("poster", e.poster).put("source", e.source))
        o.put("favorites", favs)
        val wl = JSONArray()
        for (e in WatchLater.all(ctx)) wl.put(JSONObject()
            .put("kind", e.kind).put("id", e.id).put("title", e.title).put("poster", e.poster)
            .put("source", e.source).put("added", e.added))
        o.put("watchLater", wl)
        val rs = JSONArray()
        for (e in Resume.all(ctx)) rs.put(JSONObject()
            .put("id", e.id).put("kind", e.kind).put("title", e.title).put("poster", e.poster)
            .put("source", e.source).put("position", e.position).put("duration", e.duration))
        o.put("resume", rs)
        return o.toString(2)
    }

    /** Write the backup to the app's external files dir (shareable via FileProvider). */
    fun writeToFile(ctx: Context): File {
        val dir = File(ctx.getExternalFilesDir(null), "backup")
        dir.mkdirs()
        val f = File(dir, "$APP_TAG-backup.json")
        f.writeText(exportJson(ctx))
        return f
    }

    /** Merge a backup JSON into the current account/profile. @return counts added. */
    fun importJson(ctx: Context, text: String): Result {
        val o = JSONObject(text)
        var fav = 0
        o.optJSONArray("favorites")?.let { a ->
            for (i in 0 until a.length()) {
                val e = a.optJSONObject(i) ?: continue
                val kind = e.optString("kind"); val id = e.optString("id")
                if (id.isBlank() || Favorites.isFav(ctx, kind, id)) continue
                Favorites.toggle(ctx, Favorites.Entry(kind, id, e.optString("title"), e.optString("poster"), e.optString("source")))
                fav++
            }
        }
        var wl = 0
        o.optJSONArray("watchLater")?.let { a ->
            for (i in 0 until a.length()) {
                val e = a.optJSONObject(i) ?: continue
                if (WatchLater.add(ctx, e.optString("kind"), e.optString("id"), e.optString("title"),
                        e.optString("poster"), e.optString("source"))) wl++
            }
        }
        var rs = 0
        o.optJSONArray("resume")?.let { a ->
            for (i in 0 until a.length()) {
                val e = a.optJSONObject(i) ?: continue
                val id = e.optString("id")
                if (id.isBlank() || Resume.get(ctx, id) != null) continue
                Resume.save(ctx, id, e.optString("kind"), e.optString("title"), e.optString("poster"),
                    e.optString("source"), e.optLong("position"), e.optLong("duration"))
                rs++
            }
        }
        return Result(fav, wl, rs)
    }
}
