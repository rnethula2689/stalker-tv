package com.stalkertv.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Recorded live/catch-up streams saved to local storage (app external files → /recordings).
 * libVLC writes the actual broadcast (video + audio) to a file; this store keeps the title/channel
 * metadata alongside so the Recordings screen can show and group them.
 */
object Recordings {
    data class Item(val file: String, val title: String, val channel: String, val added: Long, val size: Long)

    fun dir(ctx: Context): File {
        val d = File(ctx.getExternalFilesDir(null), "recordings" + ContentProfiles.scopeDir(ctx))
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun indexFile(ctx: Context) = File(dir(ctx), "index.json")

    private fun readIndex(ctx: Context): JSONObject =
        try { JSONObject(indexFile(ctx).takeIf { it.exists() }?.readText() ?: "{}") } catch (_: Exception) { JSONObject() }

    private fun writeIndex(ctx: Context, o: JSONObject) {
        try { indexFile(ctx).writeText(o.toString()) } catch (_: Exception) {}
    }

    /** Actual recording files present on disk (excludes the index), newest first, joined with metadata. */
    fun list(ctx: Context): List<Item> {
        val meta = readIndex(ctx)
        val files = dir(ctx).listFiles { f -> f.isFile && f.name != "index.json" } ?: return emptyList()
        return files.map { f ->
            val m = meta.optJSONObject(f.name)
            Item(
                f.absolutePath,
                m?.optString("title")?.ifBlank { f.nameWithoutExtension } ?: f.nameWithoutExtension,
                m?.optString("channel") ?: "",
                m?.optLong("added") ?: f.lastModified(),
                f.length()
            )
        }.sortedByDescending { it.added }
    }

    /** Record the title/channel for a just-finished recording file. */
    fun add(ctx: Context, file: File, title: String, channel: String, added: Long) {
        val o = readIndex(ctx)
        o.put(file.name, JSONObject().put("title", title).put("channel", channel).put("added", added))
        writeIndex(ctx, o)
    }

    /** The newest recording file (used to identify the file libVLC just wrote on stop). */
    fun newestFile(ctx: Context): File? =
        dir(ctx).listFiles { f -> f.isFile && f.name != "index.json" }?.maxByOrNull { it.lastModified() }

    fun delete(ctx: Context, path: String) {
        try { File(path).delete() } catch (_: Exception) {}
        val o = readIndex(ctx); o.remove(File(path).name); writeIndex(ctx, o)
    }

    fun deleteIds(ctx: Context, paths: Set<String>) { paths.forEach { delete(ctx, it) } }

    fun deleteAll(ctx: Context) {
        dir(ctx).listFiles { f -> f.isFile && f.name != "index.json" }?.forEach { it.delete() }
        writeIndex(ctx, JSONObject())
    }
}
