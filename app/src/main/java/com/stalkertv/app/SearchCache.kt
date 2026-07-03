package com.stalkertv.app

/**
 * Small in-memory cache of VOD search results so search feels instant:
 *  - exact repeats (or back-navigation) return with no network,
 *  - typing another character reuses the nearest cached prefix's results, filtered locally,
 *    so each extra keystroke is instant while the authoritative portal result refines in the background.
 * Keyed by (scope, lowercased query) — scope keeps global / all-movies / per-category results apart.
 * Access-ordered LRU, capped so it can't grow without bound. Cleared when the provider/session changes.
 */
object SearchCache {
    private const val CAP = 24

    // key = scope tag ("g" | "va" | "c:<catId>") to lowercased query
    private val cache = object : LinkedHashMap<Pair<String, String>, List<Portal.VodItem>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String>, List<Portal.VodItem>>): Boolean = size > CAP
    }

    /** Exact cached result for this query, or null. */
    @Synchronized fun get(scope: String, query: String): List<Portal.VodItem>? = cache[scope to query.trim().lowercase()]

    @Synchronized fun put(scope: String, query: String, list: List<Portal.VodItem>) {
        cache[scope to query.trim().lowercase()] = list
    }

    /**
     * The results of the LONGEST cached query that is a prefix of [query] (same scope), filtered down to
     * [query] in memory — an instant interim answer while the fresh portal search runs. Null if none.
     */
    @Synchronized fun prefixFilter(scope: String, query: String): List<Portal.VodItem>? {
        val ql = query.trim().lowercase()
        if (ql.length < 2) return null
        var best: Pair<String, String>? = null
        for (k in cache.keys) {
            if (k.first != scope) continue
            val cq = k.second
            if (cq.length >= 2 && ql.startsWith(cq) && (best == null || cq.length > best!!.second.length)) best = k
        }
        val src = best?.let { cache[it] } ?: return null
        return src.filter { it.name.contains(query, ignoreCase = true) }
    }

    @Synchronized fun clear() = cache.clear()
}
