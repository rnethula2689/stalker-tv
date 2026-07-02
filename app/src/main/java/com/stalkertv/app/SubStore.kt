package com.stalkertv.app

import android.content.Context
import java.io.File

/**
 * Remembers the subtitle a user picked for a title, so resuming the movie (or switching player)
 * re-loads it automatically — no repeat OpenSubtitles search / download / API call. The chosen .srt
 * is copied into permanent per-title storage (filesDir/subs) and mapped by the title's resume id.
 */
object SubStore {
    private const val PREF = "substore"

    private fun dir(ctx: Context) = File(ctx.filesDir, "subs").apply { mkdirs() }
    private fun key(id: String) = "sub:$id"

    /** The saved subtitle file for this title, or null if none / the file is gone. */
    fun saved(ctx: Context, id: String): File? {
        if (id.isBlank()) return null
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(key(id), null) ?: return null
        val f = File(p)
        return if (f.exists()) f else null
    }

    /** (count, total bytes) of saved subtitles — for the Settings label. */
    fun stats(ctx: Context): Pair<Int, Long> {
        val files = dir(ctx).listFiles()?.filter { it.isFile } ?: emptyList()
        return files.size to files.sumOf { it.length() }
    }

    /** Delete every saved subtitle + its mapping. @return number of files removed. */
    fun clearAll(ctx: Context): Int {
        var n = 0
        dir(ctx).listFiles()?.forEach { if (it.isFile && it.delete()) n++ }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
        return n
    }

    /** Copy a just-downloaded subtitle into permanent per-title storage and remember it. Returns the
     *  stored file (falls back to the source if the copy fails). */
    fun remember(ctx: Context, id: String, src: File): File {
        if (id.isBlank() || !src.exists()) return src
        val safe = id.replace(Regex("[^A-Za-z0-9_]"), "_").take(80)
        val dest = File(dir(ctx), "$safe.srt")
        return try {
            src.copyTo(dest, overwrite = true)
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(key(id), dest.absolutePath).apply()
            dest
        } catch (_: Exception) { src }
    }
}
