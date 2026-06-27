package com.stalkertv.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Offline downloads with pause / resume, driven by WorkManager so they continue in the background
 * and resume automatically when the network returns (even if the app was closed). Progressive files
 * resume via HTTP Range; HLS resumes by skipping segments already on disk. Partial data on disk is
 * the source of truth for progress, and the [Item.source] descriptor lets a download be re-resolved.
 */
object Downloads {
    const val DONE = "done"
    const val DOWNLOADING = "downloading"
    const val PAUSED = "paused"
    const val ERROR = "error"
    const val QUEUED = "queued"

    enum class Outcome { DONE, NETWORK, USER_PAUSED, ERROR, SKIP }

    data class Item(
        val id: String, val title: String, val poster: String, val source: String,
        var fileName: String, var status: String, var total: Long, var done: Long,
        var hls: Boolean = false, var userPaused: Boolean = false
    )

    interface Listener { fun onDownloadsChanged() }
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<Listener>()
    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }
    private val ui = Handler(Looper.getMainLooper())
    private fun notifyChanged() { ui.post { for (l in listeners) l.onDownloadsChanged() } }

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    private val active = ConcurrentHashMap<String, Item>()
    private val pauseFlags = ConcurrentHashMap<String, Boolean>()
    private val cancelled = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    @Volatile private var currentId: String? = null
    @Volatile private var progressReporter: ((Item) -> Unit)? = null

    private class PausedException : Exception()
    private class CancelledException : Exception()

    fun activeCount(): Int = active.size

    data class NotifInfo(val title: String, val percent: Int, val count: Int)
    fun notifInfo(): NotifInfo {
        val cur = currentId?.let { active[it] } ?: active.values.firstOrNull()
        val pct = if (cur != null && cur.total > 0) (cur.done * 100 / cur.total).toInt() else 0
        return NotifInfo(cur?.title ?: "Downloading", pct.coerceIn(0, 100), active.size)
    }

    fun dir(ctx: Context): File {
        val d = File(ctx.getExternalFilesDir(null), "downloads" + ContentProfiles.scopeDir(ctx))
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
                        o.optString("id"), o.optString("title"), o.optString("poster"), o.optString("source"),
                        o.optString("fileName"), o.optString("status"),
                        o.optLong("total"), o.optLong("done"), o.optBoolean("hls", false), o.optBoolean("userPaused", false)
                    )
                )
            }
        } catch (_: Exception) {}
        return out
    }

    @Synchronized
    private fun writeIndex(ctx: Context, items: List<Item>) {
        try {
            val arr = JSONArray()
            for (it in items) {
                arr.put(
                    JSONObject().put("id", it.id).put("title", it.title).put("poster", it.poster).put("source", it.source)
                        .put("fileName", it.fileName).put("status", it.status)
                        .put("total", it.total).put("done", it.done).put("hls", it.hls).put("userPaused", it.userPaused)
                )
            }
            indexFile(ctx).writeText(arr.toString())
        } catch (_: Exception) {}
    }

    fun list(ctx: Context): List<Item> {
        val items = readIndex(ctx)
        for (i in items.indices) active[items[i].id]?.let { items[i] = it }
        return items.reversed()
    }

    fun fileFor(ctx: Context, item: Item): File = File(dir(ctx), item.fileName)

    fun has(ctx: Context, id: String): Boolean =
        active.containsKey(id) || readIndex(ctx).any { it.id == id && it.status == DONE }

    /** Delete every download (files + queue) and free the disk space. */
    fun deleteAll(ctx: Context) {
        try { WorkManager.getInstance(ctx.applicationContext).cancelUniqueWork("downloads") } catch (_: Exception) {}
        for (it in readIndex(ctx)) cancelled.add(it.id)
        active.clear()
        dir(ctx).listFiles()?.forEach { it.deleteRecursively() } // removes .part, .mp4, HLS folders, index.json
        notifyChanged()
    }

    @Synchronized
    private fun upsert(ctx: Context, item: Item) {
        val items = readIndex(ctx)
        items.removeAll { it.id == item.id }
        items.add(item)
        writeIndex(ctx, items)
    }

    fun resolveSource(src: String): String? {
        val p = src.split("|")
        return when (p.getOrNull(0)) {
            "vod" -> Portal.playVodUrl(p.getOrElse(1) { "" }, p.getOrElse(2) { "" })
            "ep" -> Portal.playEpisodeUrl(p.getOrElse(1) { "" }, p.getOrElse(2) { "" }, p.getOrElse(3) { "" })
            "url" -> p.getOrElse(1) { "" }
            else -> null
        }
    }

    fun delete(ctx: Context, id: String) {
        cancelled.add(id)
        active.remove(id)
        val items = readIndex(ctx)
        File(dir(ctx), "$id.part").delete()
        File(dir(ctx), id).deleteRecursively()
        items.firstOrNull { it.id == id }?.let { File(dir(ctx), it.fileName).delete() }
        writeIndex(ctx, items.filterNot { it.id == id })
        notifyChanged()
    }

    fun pause(ctx: Context, id: String) {
        if (currentId == id) {
            pauseFlags[id] = true // running → worker stops at the next checkpoint
        } else {
            val items = readIndex(ctx)
            items.firstOrNull { it.id == id }?.let { it.status = PAUSED; it.userPaused = true }
            writeIndex(ctx, items)
            notifyChanged()
        }
    }

    fun resume(ctx: Context, id: String) {
        val items = readIndex(ctx)
        val it = items.firstOrNull { it.id == id } ?: return
        if (it.status == DONE || it.status == DOWNLOADING) return
        it.status = QUEUED
        it.userPaused = false
        writeIndex(ctx, items)
        notifyChanged()
        scheduleWork(ctx)
    }

    /** Kick the worker so it picks up anything pending (used as auto-resume trigger too). */
    fun resumeAllAuto(ctx: Context) = scheduleWork(ctx)

    fun enqueue(ctx: Context, id: String, title: String, poster: String, source: String) {
        if (has(ctx, id)) return
        upsert(ctx, Item(id, title, poster, source, "$id.mp4", QUEUED, 0, 0))
        notifyChanged()
        scheduleWork(ctx)
    }

    fun scheduleWork(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx.applicationContext)
            .enqueueUniqueWork("downloads", ExistingWorkPolicy.KEEP, req)
    }

    // ---- worker engine ----
    /** Next item the worker should process: freshly queued, or paused by an outage (not by the user). */
    fun nextPendingItem(ctx: Context): Item? {
        // Treat a download left "downloading" by an app-kill as resumable.
        val items = readIndex(ctx)
        var changed = false
        for (it in items) if (it.status == DOWNLOADING && !active.containsKey(it.id)) {
            it.status = PAUSED; it.userPaused = false; changed = true
        }
        if (changed) writeIndex(ctx, items)
        return items.firstOrNull { it.status == QUEUED || (it.status == PAUSED && !it.userPaused) }
    }

    private fun report(item: Item) { progressReporter?.invoke(item); notifyChanged() }

    /** Run one item to completion / pause / error. Called by [DownloadWorker]. */
    fun runItem(ctx: Context, item: Item, onProgress: (Item) -> Unit): Outcome {
        cancelled.remove(item.id)
        pauseFlags.remove(item.id)
        item.status = DOWNLOADING
        item.userPaused = false
        active[item.id] = item
        currentId = item.id
        progressReporter = onProgress
        upsert(ctx, item)
        report(item)
        try {
            if (cancelled.contains(item.id)) throw CancelledException()
            val url = resolveSource(item.source) ?: throw IOException("no stream")
            if (isHlsUrl(url) || item.hls) downloadHls(ctx, item, url) else downloadProgressive(ctx, item, url)
            item.status = DONE
            upsert(ctx, item)
            return Outcome.DONE
        } catch (e: CancelledException) {
            return Outcome.SKIP // already removed by delete()
        } catch (e: PausedException) {
            item.status = PAUSED; item.userPaused = true
            upsert(ctx, item)
            return Outcome.USER_PAUSED
        } catch (e: Exception) {
            if (hasPartial(ctx, item)) {
                item.status = PAUSED; item.userPaused = false; upsert(ctx, item); return Outcome.NETWORK
            }
            item.status = ERROR; upsert(ctx, item); return Outcome.ERROR
        } finally {
            active.remove(item.id)
            currentId = null
            pauseFlags.remove(item.id)
            progressReporter = null
            notifyChanged()
        }
    }

    private fun checkpoint(id: String) {
        if (cancelled.contains(id)) throw CancelledException()
        if (pauseFlags[id] == true) throw PausedException()
    }

    private fun hasPartial(ctx: Context, item: Item): Boolean {
        if (File(dir(ctx), "${item.id}.part").length() > 0) return true
        val folder = File(dir(ctx), item.id)
        return folder.isDirectory && (folder.listFiles()?.any { it.name.startsWith("seg") } == true)
    }

    private fun isHlsUrl(url: String) = url.substringBefore('?').endsWith(".m3u8", true)

    // ---- progressive (resumable via Range) ----
    private fun downloadProgressive(ctx: Context, item: Item, url: String) {
        val part = File(dir(ctx), "${item.id}.part")
        val have = if (part.exists()) part.length() else 0L
        val reqB = Request.Builder().url(url).header("User-Agent", Portal.UA)
        if (have > 0) reqB.header("Range", "bytes=$have-")
        client.newCall(reqB.build()).execute().use { resp ->
            val body = resp.body ?: throw IOException("empty body")
            val ct = resp.header("Content-Type") ?: ""
            if (ct.contains("mpegurl", true)) { resp.close(); item.hls = true; downloadHls(ctx, item, url); return }
            val append = have > 0 && resp.code == 206
            if (have > 0 && resp.code != 206) part.delete()
            val base = if (append) have else 0L
            item.total = base + body.contentLength()
            item.done = base
            FileOutputStream(part, append).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(128 * 1024)
                    var n: Int
                    var lastNotify = base
                    while (input.read(buf).also { n = it } > 0) {
                        checkpoint(item.id)
                        out.write(buf, 0, n)
                        item.done += n
                        if (item.done - lastNotify > 2_000_000) { lastNotify = item.done; report(item) }
                    }
                }
            }
            val finalFile = File(dir(ctx), item.fileName)
            if (!part.renameTo(finalFile)) throw IOException("rename failed")
        }
    }

    // ---- HLS (resumes by skipping segments already on disk) ----
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
        item.hls = true
        val out = ArrayList<String>()
        var keyIdx = 0; var mapIdx = 0; var segIdx = 0
        for (raw in lines) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-KEY") -> {
                    val u = uriIn(line)
                    if (u != null && !u.startsWith("data:")) {
                        val name = "key$keyIdx.key"; keyIdx++
                        val kf = File(folder, name)
                        if (!(kf.exists() && kf.length() > 0)) httpToFile(resolveRef(playlistUrl, u), kf)
                        out.add(line.replace(u, name))
                    } else out.add(line)
                }
                line.startsWith("#EXT-X-MAP") -> {
                    val u = uriIn(line)
                    if (u != null) {
                        val name = "map$mapIdx.bin"; mapIdx++
                        val mf = File(folder, name)
                        if (!(mf.exists() && mf.length() > 0)) httpToFile(resolveRef(playlistUrl, u), mf)
                        out.add(line.replace(u, name))
                    } else out.add(line)
                }
                line.isEmpty() || line.startsWith("#") -> out.add(raw)
                else -> {
                    checkpoint(item.id)
                    val ext = if (line.substringBefore('?').endsWith(".m4s", true)) "m4s" else "ts"
                    val name = "seg$segIdx.$ext"
                    val sf = File(folder, name)
                    if (!(sf.exists() && sf.length() > 0)) httpToFile(resolveRef(playlistUrl, line), sf)
                    out.add(name)
                    segIdx++
                    item.done = segIdx.toLong()
                    report(item)
                }
            }
        }
        if (segIdx == 0) throw IOException("no segments")
        File(folder, "index.m3u8").writeText(out.joinToString("\n"))
        item.fileName = "${item.id}/index.m3u8"
    }
}
