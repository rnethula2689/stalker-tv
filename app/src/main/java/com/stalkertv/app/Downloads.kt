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
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Offline downloads for movies / episodes. Handles both a single progressive file (mp4/mkv/mpg)
 * and HLS (.m3u8) — for HLS it downloads every segment + key into a folder and writes a local
 * playlist so it plays back with no network.
 */
object Downloads {
    const val DONE = "done"
    const val DOWNLOADING = "downloading"
    const val ERROR = "error"

    data class Item(
        val id: String, val title: String, val poster: String,
        var fileName: String, var status: String, var total: Long, var done: Long, var hls: Boolean = false
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
                        o.optLong("total"), o.optLong("done"), o.optBoolean("hls", false)
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
                        .put("total", it.total).put("done", it.done).put("hls", it.hls)
                )
            }
            indexFile(ctx).writeText(arr.toString())
        } catch (_: Exception) {}
    }

    fun list(ctx: Context): List<Item> {
        val items = readIndex(ctx)
        var changed = false
        for (i in items.indices) {
            val live = active[items[i].id]
            if (live != null) items[i] = live
            else if (items[i].status == DOWNLOADING) { items[i].status = ERROR; changed = true } // app killed mid-download
        }
        if (changed) writeIndex(ctx, items)
        return items.reversed()
    }

    fun fileFor(ctx: Context, item: Item): File = File(dir(ctx), item.fileName)

    fun delete(ctx: Context, id: String) {
        val items = readIndex(ctx)
        File(dir(ctx), "$id.part").delete()
        File(dir(ctx), id).deleteRecursively() // HLS folder, if any
        items.firstOrNull { it.id == id }?.let { File(dir(ctx), it.fileName).delete() }
        writeIndex(ctx, items.filterNot { it.id == id })
        active.remove(id)
        notifyChanged()
    }

    fun has(ctx: Context, id: String): Boolean =
        active.containsKey(id) || readIndex(ctx).any { it.id == id && it.status == DONE }

    private fun upsert(ctx: Context, item: Item) {
        val items = readIndex(ctx)
        items.removeAll { it.id == item.id }
        items.add(item)
        writeIndex(ctx, items)
    }

    private fun finish(ctx: Context, item: Item, status: String) {
        item.status = status
        active.remove(item.id)
        upsert(ctx, item)
        notifyChanged()
    }

    fun enqueue(ctx: Context, id: String, title: String, poster: String, resolve: () -> String?) {
        if (has(ctx, id)) return
        val item = Item(id, title, poster, "$id.mp4", DOWNLOADING, 0, 0)
        active[id] = item
        upsert(ctx, item)
        notifyChanged()
        exec.execute {
            try {
                val url = resolve() ?: throw IOException("no stream")
                if (isHlsUrl(url)) downloadHls(ctx, item, url)
                else downloadProgressive(ctx, item, url)
            } catch (e: Exception) {
                finish(ctx, item, ERROR)
            }
        }
    }

    private fun isHlsUrl(url: String) = url.substringBefore('?').endsWith(".m3u8", true)

    // ---- progressive (single file) ----
    private fun downloadProgressive(ctx: Context, item: Item, url: String) {
        val req = Request.Builder().url(url).header("User-Agent", Portal.UA).build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body ?: throw IOException("empty body")
            val ct = resp.header("Content-Type") ?: ""
            if (ct.contains("mpegurl", true)) { // server says it's actually HLS
                resp.close()
                downloadHls(ctx, item, url)
                return
            }
            item.total = body.contentLength()
            val part = File(dir(ctx), "${item.id}.part")
            body.byteStream().use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(128 * 1024)
                    var n: Int
                    var lastPersist = 0L
                    while (input.read(buf).also { n = it } > 0) {
                        if (!active.containsKey(item.id)) throw IOException("cancelled")
                        out.write(buf, 0, n)
                        item.done += n
                        if (item.done - lastPersist > 2_000_000) { lastPersist = item.done; notifyChanged() }
                    }
                }
            }
            val finalFile = File(dir(ctx), item.fileName)
            if (!part.renameTo(finalFile)) throw IOException("rename failed")
            finish(ctx, item, DONE)
        }
    }

    // ---- HLS (playlist + segments) ----
    private fun httpText(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", Portal.UA).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return r.body?.string() ?: ""
        }
    }

    private fun httpToFile(url: String, dest: File) {
        val req = Request.Builder().url(url).header("User-Agent", Portal.UA).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("empty segment")
            FileOutputStream(dest).use { o -> body.byteStream().copyTo(o, 128 * 1024) }
        }
    }

    private fun resolveRef(baseUrl: String, ref: String): String =
        if (ref.startsWith("http", true)) ref else URI(baseUrl).resolve(ref).toString()

    private fun uriIn(line: String): String? = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1)

    private fun downloadHls(ctx: Context, item: Item, firstUrl: String) {
        var playlistUrl = firstUrl
        var text = httpText(playlistUrl)
        // Master playlist → pick the highest-bandwidth variant.
        if (text.contains("#EXT-X-STREAM-INF")) {
            val lines = text.lines()
            var bestBw = -1
            var bestUri: String? = null
            for (i in lines.indices) {
                if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
                    val bw = Regex("BANDWIDTH=(\\d+)").find(lines[i])?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val uri = lines.getOrNull(i + 1)?.trim()
                    if (!uri.isNullOrEmpty() && !uri.startsWith("#") && bw >= bestBw) { bestBw = bw; bestUri = uri }
                }
            }
            bestUri ?: throw IOException("no HLS variant")
            playlistUrl = resolveRef(playlistUrl, bestUri)
            text = httpText(playlistUrl)
        }
        val folder = File(dir(ctx), item.id)
        folder.mkdirs()
        val lines = text.lines()
        item.total = lines.count { it.isNotBlank() && !it.startsWith("#") }.toLong()
        item.done = 0
        item.hls = true
        notifyChanged()
        val out = ArrayList<String>()
        var keyIdx = 0; var mapIdx = 0; var segIdx = 0
        for (raw in lines) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-KEY") -> {
                    val u = uriIn(line)
                    if (u != null && !u.startsWith("data:")) {
                        val name = "key$keyIdx.key"; keyIdx++
                        httpToFile(resolveRef(playlistUrl, u), File(folder, name))
                        out.add(line.replace(u, name))
                    } else out.add(line)
                }
                line.startsWith("#EXT-X-MAP") -> {
                    val u = uriIn(line)
                    if (u != null) {
                        val name = "map$mapIdx.bin"; mapIdx++
                        httpToFile(resolveRef(playlistUrl, u), File(folder, name))
                        out.add(line.replace(u, name))
                    } else out.add(line)
                }
                line.isEmpty() || line.startsWith("#") -> out.add(raw)
                else -> {
                    if (!active.containsKey(item.id)) throw IOException("cancelled")
                    val ext = if (line.substringBefore('?').endsWith(".m4s", true)) "m4s" else "ts"
                    val name = "seg$segIdx.$ext"; segIdx++
                    httpToFile(resolveRef(playlistUrl, line), File(folder, name))
                    out.add(name)
                    item.done = segIdx.toLong()
                    notifyChanged()
                }
            }
        }
        if (segIdx == 0) throw IOException("no segments")
        File(folder, "index.m3u8").writeText(out.joinToString("\n"))
        item.fileName = "${item.id}/index.m3u8"
        finish(ctx, item, DONE)
    }
}
