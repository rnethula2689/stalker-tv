package com.stalkertv.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Offline downloads for movies / episodes. Streams the resolved progressive URL to the app's
 * external files dir and tracks progress. HLS (.m3u8) can't be saved as one file → marked unsupported.
 */
object Downloads {
    const val DONE = "done"
    const val DOWNLOADING = "downloading"
    const val ERROR = "error"
    const val UNSUPPORTED = "unsupported"

    data class Item(
        val id: String, val title: String, val poster: String,
        val fileName: String, var status: String, var total: Long, var done: Long
    )

    interface Listener { fun onDownloadsChanged() }
    @Volatile private var listener: Listener? = null
    fun setListener(l: Listener?) { listener = l }
    private val ui = Handler(Looper.getMainLooper())
    private fun notifyChanged() { ui.post { listener?.onDownloadsChanged() } }

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    private val active = ConcurrentHashMap<String, Item>()
    private val exec = Executors.newSingleThreadExecutor() // one download at a time

    fun dir(ctx: Context): File {
        val d = File(ctx.getExternalFilesDir(null), "downloads")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun indexFile(ctx: Context) = File(dir(ctx), "index.json")

    private fun readIndex(ctx: Context): MutableList<Item> {
        val out = ArrayList<Item>()
        try {
            val f = indexFile(ctx)
            if (!f.exists()) return out
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Item(
                        o.optString("id"), o.optString("title"), o.optString("poster"),
                        o.optString("fileName"), o.optString("status"),
                        o.optLong("total"), o.optLong("done")
                    )
                )
            }
        } catch (_: Exception) {}
        return out
    }

    private fun writeIndex(ctx: Context, items: List<Item>) {
        try {
            val arr = JSONArray()
            for (it in items) {
                arr.put(
                    JSONObject().put("id", it.id).put("title", it.title).put("poster", it.poster)
                        .put("fileName", it.fileName).put("status", it.status)
                        .put("total", it.total).put("done", it.done)
                )
            }
            indexFile(ctx).writeText(arr.toString())
        } catch (_: Exception) {}
    }

    /** Index merged with live progress from in-flight downloads (newest first). */
    fun list(ctx: Context): List<Item> {
        val items = readIndex(ctx)
        var changed = false
        for (i in items.indices) {
            val live = active[items[i].id]
            if (live != null) items[i] = live
            else if (items[i].status == DOWNLOADING) { items[i].status = ERROR; changed = true } // app was killed mid-download
        }
        if (changed) writeIndex(ctx, items)
        return items.reversed()
    }

    fun isSaved(ctx: Context, id: String): Boolean =
        readIndex(ctx).any { it.id == id && it.status == DONE }

    fun fileFor(ctx: Context, item: Item): File = File(dir(ctx), item.fileName)

    fun delete(ctx: Context, id: String) {
        val items = readIndex(ctx)
        items.firstOrNull { it.id == id }?.let { File(dir(ctx), it.fileName).delete() }
        File(dir(ctx), "$id.part").delete()
        writeIndex(ctx, items.filterNot { it.id == id })
        active.remove(id)
        notifyChanged()
    }

    private fun upsert(ctx: Context, item: Item) {
        val items = readIndex(ctx)
        items.removeAll { it.id == item.id }
        items.add(item)
        writeIndex(ctx, items)
    }

    /** Already downloaded or currently downloading? */
    fun has(ctx: Context, id: String): Boolean =
        active.containsKey(id) || readIndex(ctx).any { it.id == id && it.status == DONE }

    /**
     * Queue a download. [resolve] returns the playable URL (run off the UI thread — it does network).
     */
    fun enqueue(ctx: Context, id: String, title: String, poster: String, resolve: () -> String?) {
        if (has(ctx, id)) return
        val item = Item(id, title, poster, "$id.mp4", DOWNLOADING, 0, 0)
        active[id] = item
        upsert(ctx, item)
        notifyChanged()
        exec.execute {
            try {
                val url = resolve() ?: throw IOException("no stream")
                if (url.contains(".m3u8", true)) { finish(ctx, item, UNSUPPORTED); return@execute }
                val req = Request.Builder().url(url).header("User-Agent", Portal.UA).build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body ?: throw IOException("empty body")
                    val ct = resp.header("Content-Type") ?: ""
                    if (ct.contains("mpegurl", true) || ct.contains("m3u8", true)) {
                        finish(ctx, item, UNSUPPORTED); return@execute
                    }
                    item.total = body.contentLength()
                    val part = File(dir(ctx), "$id.part")
                    body.byteStream().use { input ->
                        FileOutputStream(part).use { out ->
                            val buf = ByteArray(128 * 1024)
                            var n: Int
                            var lastPersist = 0L
                            while (input.read(buf).also { n = it } > 0) {
                                if (!active.containsKey(id)) throw IOException("cancelled")
                                out.write(buf, 0, n)
                                item.done += n
                                if (item.done - lastPersist > 2_000_000) {
                                    lastPersist = item.done
                                    notifyChanged()
                                }
                            }
                        }
                    }
                    val finalFile = File(dir(ctx), item.fileName)
                    if (part.renameTo(finalFile)) finish(ctx, item, DONE)
                    else throw IOException("rename failed")
                }
            } catch (e: Exception) {
                finish(ctx, item, ERROR)
            }
        }
    }

    private fun finish(ctx: Context, item: Item, status: String) {
        item.status = status
        active.remove(item.id)
        upsert(ctx, item)
        notifyChanged()
    }
}
