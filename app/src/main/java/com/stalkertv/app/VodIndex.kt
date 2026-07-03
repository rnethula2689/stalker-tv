package com.stalkertv.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Local index of VOD titles for instant, offline search. Populated as the user browses movie categories
 * (each category already loads every page for display, so indexing it costs ZERO extra network), persisted
 * to disk, and reused across launches until it goes stale or the provider changes.
 *
 * Search filters it in memory — no network, no debounce. Coverage grows with use: a folder you've opened
 * once is fully searchable forever (even offline). Callers use it as an instant layer and let the portal
 * fill anything not yet indexed, so results are always complete.
 *
 * Safe by design: until [ready] it returns nothing and callers fall straight back to the portal search.
 */
object VodIndex {
    private const val MAX_ITEMS = 60_000
    private const val STALE_MS = 14L * 24 * 60 * 60 * 1000 // drop the cache after 14 days
    private const val FILE = "vodindex.json"
    private val saveIo = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

    /** Compact entry — only what a result row + detail-open needs, plus the category for folder-scoped search. */
    private class E(
        val id: String, val name: String, val cmd: String, val poster: String,
        val series: Boolean, val year: String, val genre: String, val imdb: String, val cat: String
    )

    private var items: List<E> = emptyList()
    private val ids = HashSet<String>()
    private var sig: String = ""
    @Volatile var ready: Boolean = false; private set
    /** True only if the whole catalogue is known to be indexed. Browse-feeding never sets this, so search
     *  always also consults the portal to stay complete; kept for the search-integration contract. */
    @Volatile val complete: Boolean = false

    fun count(): Int = items.size

    private fun E.toVod() = Portal.VodItem(id, name, cmd, poster, series, year = year, imdb = imdb, genre = genre)

    /** Instant name search over the in-memory index. [catId] scopes to one folder. Empty if not ready. */
    @Synchronized fun search(query: String, catId: String? = null): List<Portal.VodItem> {
        if (!ready) return emptyList()
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return items.asSequence()
            .filter { catId == null || it.cat == catId }
            .filter { it.name.contains(q, ignoreCase = true) }
            .take(300)
            .map { it.toVod() }
            .toList()
    }

    /** Load the disk cache for [providerSig] (if fresh). Cheap; call once after the provider loads. */
    @Synchronized fun ensure(ctx: Context, providerSig: String) {
        if (providerSig.isBlank() || (ready && sig == providerSig)) return
        val loaded = load(ctx, providerSig)
        sig = providerSig
        if (loaded != null) {
            items = loaded
            ids.clear(); items.forEach { ids.add(it.id) }
            ready = items.isNotEmpty()
        } else {
            items = emptyList(); ids.clear(); ready = false
        }
    }

    /** Feed the titles of a browsed category into the index (no extra network). Persists in the background. */
    @Synchronized fun add(ctx: Context, providerSig: String, newItems: List<Portal.VodItem>, catId: String) {
        if (providerSig.isBlank() || newItems.isEmpty()) return
        if (sig != providerSig) { items = emptyList(); ids.clear(); sig = providerSig } // provider changed → reset
        if (items.size >= MAX_ITEMS) return
        val mut = ArrayList(items)
        var changed = false
        for (v in newItems) if (v.id.isNotBlank() && ids.add(v.id)) {
            mut.add(E(v.id, v.name, v.cmd, v.posterUrl, v.isSeries, v.year, v.genre, v.imdb, catId)); changed = true
        }
        if (changed) {
            items = mut; ready = true
            val snap = items; val s = sig
            saveIo.execute { save(ctx, s, snap) }
        }
    }

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    private fun load(ctx: Context, providerSig: String): List<E>? = try {
        val f = file(ctx)
        if (!f.exists()) null else {
            val o = JSONObject(f.readText())
            when {
                o.optString("sig") != providerSig -> null
                System.currentTimeMillis() - o.optLong("built") > STALE_MS -> null
                else -> {
                    val arr = o.optJSONArray("items") ?: JSONArray()
                    val list = ArrayList<E>(arr.length())
                    for (i in 0 until arr.length()) {
                        val a = arr.optJSONArray(i) ?: continue
                        list.add(E(a.optString(0), a.optString(1), a.optString(2), a.optString(3),
                            a.optInt(4) == 1, a.optString(5), a.optString(6), a.optString(7), a.optString(8)))
                    }
                    list
                }
            }
        }
    } catch (_: Exception) { null }

    private fun save(ctx: Context, providerSig: String, list: List<E>) {
        try {
            val arr = JSONArray()
            for (e in list) arr.put(JSONArray().put(e.id).put(e.name).put(e.cmd).put(e.poster)
                .put(if (e.series) 1 else 0).put(e.year).put(e.genre).put(e.imdb).put(e.cat))
            val o = JSONObject().put("sig", providerSig).put("built", System.currentTimeMillis()).put("items", arr)
            file(ctx).writeText(o.toString())
        } catch (_: Exception) {}
    }
}
