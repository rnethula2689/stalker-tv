package com.stalkertv.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Local index of VOD titles for instant, offline search. Crawled once per provider in a low-priority
 * background thread (throttled + bounded), cached to disk, and reused across launches until it goes stale
 * or the provider changes. Search filters it in memory — no network, no debounce.
 *
 * Safe by design: until [ready] is true it returns nothing, so callers fall straight back to the portal
 * search. If [complete] (the whole catalogue fit within the caps) the index is authoritative and search
 * can skip the network entirely; otherwise callers use it as an instant layer and let the portal fill gaps.
 */
object VodIndex {
    private const val MAX_ITEMS = 40_000
    private const val MAX_PAGES_PER_CAT = 500
    private const val STALE_MS = 5L * 24 * 60 * 60 * 1000 // rebuild after 5 days
    private const val FILE = "vodindex.json"

    /** Compact entry — only what a result row + detail-open needs, plus the category for folder-scoped search. */
    private class E(
        val id: String, val name: String, val cmd: String, val poster: String,
        val series: Boolean, val year: String, val genre: String, val imdb: String, val cat: String
    )

    @Volatile private var items: List<E> = emptyList()
    @Volatile private var sig: String = ""
    @Volatile var ready: Boolean = false; private set
    @Volatile var complete: Boolean = false; private set
    @Volatile private var building = false

    fun count(): Int = items.size

    private fun E.toVod() = Portal.VodItem(id, name, cmd, poster, series, year = year, imdb = imdb, genre = genre)

    /** Instant name search over the in-memory index. [catId] scopes to one folder. Empty if not ready. */
    fun search(query: String, catId: String? = null): List<Portal.VodItem> {
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

    /** Load a fresh cached index for [providerSig], else crawl it in the background. Cheap + idempotent. */
    fun ensure(ctx: Context, providerSig: String) {
        if (providerSig.isBlank() || building) return
        if (ready && sig == providerSig && complete) return
        Thread {
            try {
                if (!(ready && sig == providerSig)) {
                    val cached = load(ctx, providerSig)
                    if (cached != null) {
                        items = cached.first; sig = providerSig; ready = true; complete = cached.second
                        if (complete) return@Thread
                    }
                }
                crawl(ctx, providerSig)
            } catch (_: Exception) {}
        }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun crawl(ctx: Context, providerSig: String) {
        if (building) return
        building = true
        try {
            val cats = Portal.vodCategories()
            if (cats.isEmpty()) return
            val acc = ArrayList<E>(items)          // resume from any partial already in memory
            val seen = HashSet<String>(acc.size * 2).apply { acc.forEach { add(it.id) } }
            var capped = false
            outer@ for (cat in cats) {
                var page = 1; var pages = 1
                while (page <= pages && page <= MAX_PAGES_PER_CAT) {
                    val (list, tp) = Portal.vodList(cat.id, page)
                    pages = tp
                    for (v in list) if (seen.add(v.id))
                        acc.add(E(v.id, v.name, v.cmd, v.posterUrl, v.isSeries, v.year, v.genre, v.imdb, cat.id))
                    if (acc.size >= MAX_ITEMS) { capped = true; break@outer }
                    page++
                    try { Thread.sleep(35) } catch (_: InterruptedException) {} // gentle on the portal
                }
                items = acc.toList(); sig = providerSig; ready = true // publish progress so search improves mid-build
            }
            items = acc.toList(); sig = providerSig; ready = true; complete = !capped
            save(ctx, providerSig, acc, complete)
        } catch (_: Exception) {
        } finally { building = false }
    }

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    private fun load(ctx: Context, providerSig: String): Pair<List<E>, Boolean>? = try {
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
                    Pair(list, o.optBoolean("complete"))
                }
            }
        }
    } catch (_: Exception) { null }

    private fun save(ctx: Context, providerSig: String, list: List<E>, complete: Boolean) {
        try {
            val arr = JSONArray()
            for (e in list) arr.put(JSONArray().put(e.id).put(e.name).put(e.cmd).put(e.poster)
                .put(if (e.series) 1 else 0).put(e.year).put(e.genre).put(e.imdb).put(e.cat))
            val o = JSONObject().put("sig", providerSig).put("built", System.currentTimeMillis())
                .put("complete", complete).put("items", arr)
            file(ctx).writeText(o.toString())
        } catch (_: Exception) {}
    }

    fun invalidate(ctx: Context) {
        items = emptyList(); ready = false; complete = false; sig = ""
        try { file(ctx).delete() } catch (_: Exception) {}
    }
}
