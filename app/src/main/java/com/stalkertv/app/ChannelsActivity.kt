package com.stalkertv.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.stalkertv.app.databinding.ActivityChannelsBinding
import java.util.concurrent.Executors

class ChannelsActivity : AppCompatActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var b: ActivityChannelsBinding
    private val adapter = RowAdapter()

    data class Row(val label: String, val iconUrl: String?, val action: () -> Unit)
    enum class SearchKind { LOCAL, GLOBAL, CHANNELS, VOD_ALL, VOD_CATEGORY }
    data class Page(
        val title: String,
        val rows: List<Row>,
        val kind: SearchKind = SearchKind.LOCAL,
        val scopeId: String? = null,
        val scopeChannels: List<Portal.Channel>? = null
    )

    private val backStack = ArrayDeque<Page>()
    private val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingSearch: Runnable? = null
    private var searchSeq = 0

    private var allChannels = listOf<Portal.Channel>()
    private var genres = listOf<Portal.Genre>()
    private var byGenre = mapOf<String, List<Portal.Channel>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityChannelsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter

        b.search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                b.search.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, if (s.isNullOrEmpty()) 0 else R.drawable.ic_clear, 0)
                filter(s?.toString() ?: "")
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
        })
        b.search.setOnTouchListener { v, e ->
            if (e.action == android.view.MotionEvent.ACTION_UP) {
                val et = v as android.widget.EditText
                val d = et.compoundDrawablesRelative[2]
                if (d != null && e.x >= et.width - et.paddingEnd - d.intrinsicWidth) {
                    et.setText(""); return@setOnTouchListener true
                }
            }
            false
        }

        b.searchBtn.setOnClickListener { toggleSearch() }
        b.reloadBtn.setOnClickListener { connectAndLoad() }
        b.menuBtn.setOnClickListener { showMenu(it) }

        connectAndLoad()
    }

    private fun showMenu(anchor: View) {
        val pm = androidx.appcompat.widget.PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "🔄   Refresh portal")
        pm.menu.add(0, 2, 0, "⚙   Settings")
        pm.menu.add(0, 3, 0, "✖   Exit")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> connectAndLoad()
                2 -> startActivity(Intent(this, SettingsActivity::class.java))
                3 -> finishAffinity()
            }
            true
        }
        pm.show()
    }

    private fun confirmExit() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Exit Stalker TV?")
            .setPositiveButton("Yes") { _, _ -> finishAffinity() }
            .setNegativeButton("No", null)
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_MENU -> { showMenu(b.menuBtn); return true }
            android.view.KeyEvent.KEYCODE_BACK -> { event.startTracking(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) { confirmExit(); return true }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK && !event.isCanceled) {
            @Suppress("DEPRECATION") onBackPressed(); return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun toggleSearch() {
        if (b.search.visibility == View.VISIBLE) {
            b.search.setText("")
            b.search.visibility = View.GONE
        } else {
            b.search.visibility = View.VISIBLE
            b.search.requestFocus()
            (getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showSoftInput(b.search, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun openLiveGrid(list: List<Portal.Channel>, title: String) {
        LiveGridActivity.channels = list
        LiveGridActivity.gridTitle = title
        startActivity(Intent(this, LiveGridActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        if (Configs.dirty) {
            Configs.dirty = false
            connectAndLoad()
        }
    }

    /** Read the active provider, connect in the background, then show the home menu. */
    private fun connectAndLoad() {
        val acct = Configs.active(this)
        b.title.text = "Stalker TV"
        b.search.setText("")
        backStack.clear()
        adapter.submit(emptyList())
        if (acct == null) {
            b.status.visibility = View.VISIBLE
            b.status.text = "No IPTV configuration.\nTap ⚙ Settings to add a provider."
            return
        }
        Portal.portalUrl = acct.portal
        Portal.mac = acct.mac
        Portal.sn = acct.sn
        b.status.visibility = View.VISIBLE
        b.status.text = "Loading ${acct.name}…"
        io.execute {
            val err = Portal.connect()
            if (err != null) {
                runOnUiThread {
                    b.status.visibility = View.VISIBLE
                    b.status.text = err
                }
                return@execute
            }
            val ch = Portal.liveChannels()
            val g = Portal.liveGenres()
            runOnUiThread {
                allChannels = ch
                genres = g
                byGenre = ch.groupBy { it.genreId }
                if (ch.isEmpty()) {
                    b.status.visibility = View.VISIBLE
                    b.status.text = "No channels returned. Check the configuration (⚙)."
                } else {
                    b.status.visibility = View.GONE
                    showHome()
                }
            }
        }
    }

    // ---- navigation ----
    private fun push(page: Page) {
        backStack.addLast(page)
        display(page)
    }

    private fun display(page: Page) {
        b.title.text = page.title
        b.search.hint = when (page.kind) {
            SearchKind.GLOBAL -> "Search channels, movies & shows…"
            SearchKind.CHANNELS -> "Search channels…"
            SearchKind.VOD_ALL -> "Search movies & shows…"
            SearchKind.VOD_CATEGORY -> "Search this folder…"
            SearchKind.LOCAL -> "Filter…"
        }
        adapter.submit(page.rows)
        if (b.search.text.isNotEmpty()) b.search.setText("")
        b.search.visibility = View.GONE
        b.list.scrollToPosition(0)
        b.list.requestFocus()
    }

    override fun onBackPressed() {
        if (b.search.visibility == View.VISIBLE) {
            b.search.setText("")
            b.search.visibility = View.GONE
            return
        }
        if (backStack.size > 1) {
            backStack.removeLast()
            display(backStack.last())
        } else {
            super.onBackPressed()
        }
    }

    private fun filter(q: String) {
        val page = backStack.lastOrNull() ?: return
        when (page.kind) {
            SearchKind.LOCAL -> {
                val query = q.trim().lowercase()
                adapter.submit(if (query.isEmpty()) page.rows else page.rows.filter { it.label.lowercase().contains(query) })
            }
            SearchKind.CHANNELS -> channelSearch(q, page.scopeChannels ?: allChannels, page)
            SearchKind.GLOBAL -> globalSearch(q, page)
            SearchKind.VOD_ALL -> vodSearchUi(q, null, page)
            SearchKind.VOD_CATEGORY -> vodSearchUi(q, page.scopeId, page)
        }
    }

    private fun channelRow(ch: Portal.Channel): Row {
        val label = "📺  " + (if (ch.number.isNotEmpty()) "${ch.number}. " else "") + ch.name
        return Row(label, ch.logoUrl) { play(ch.name) { Portal.createLink(ch.cmd) } }
    }

    private fun vodItemRow(v: Portal.VodItem): Row {
        val label = (if (v.isSeries) "📁  " else "🎬  ") + v.name
        return Row(label, v.posterUrl) {
            if (v.isSeries) showSeasons(v) else play(v.name) { Portal.playVodUrl(v.id, v.cmd) }
        }
    }

    /** In-memory channel search (Live TV scope). */
    private fun channelSearch(q: String, channels: List<Portal.Channel>, page: Page) {
        val query = q.trim()
        if (query.isEmpty()) { b.status.visibility = View.GONE; adapter.submit(page.rows); return }
        val rows = channels.asSequence()
            .filter { it.name.contains(query, ignoreCase = true) }
            .take(500).map { channelRow(it) }.toList()
        b.status.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        if (rows.isEmpty()) b.status.text = "No channels match “$query”."
        adapter.submit(rows)
    }

    /** Global search: channels (instant) + all movies/series (portal). */
    private fun globalSearch(q: String, page: Page) {
        val query = q.trim()
        pendingSearch?.let { searchHandler.removeCallbacks(it) }
        searchSeq++
        if (query.isEmpty()) { b.status.visibility = View.GONE; adapter.submit(page.rows); return }
        val chRows = allChannels.asSequence().filter { it.name.contains(query, ignoreCase = true) }
            .take(150).map { channelRow(it) }.toList()
        adapter.submit(chRows)
        if (query.length < 2) { b.status.visibility = View.GONE; return }
        b.status.visibility = View.VISIBLE
        b.status.text = "Searching movies & shows…"
        val seq = searchSeq
        val task = Runnable {
            io.execute {
                val vod = Portal.vodSearch(query)
                runOnUiThread {
                    if (seq != searchSeq) return@runOnUiThread
                    b.status.visibility = View.GONE
                    adapter.submit(chRows + vod.map { vodItemRow(it) })
                }
            }
        }
        pendingSearch = task
        searchHandler.postDelayed(task, 450)
    }

    /** VOD search — all categories if catId is null, else scoped to that folder. */
    private fun vodSearchUi(q: String, catId: String?, page: Page) {
        val query = q.trim()
        pendingSearch?.let { searchHandler.removeCallbacks(it) }
        searchSeq++
        if (query.isEmpty()) { b.status.visibility = View.GONE; adapter.submit(page.rows); return }
        if (query.length < 2) return
        b.status.visibility = View.VISIBLE
        b.status.text = "Searching…"
        val seq = searchSeq
        val task = Runnable {
            io.execute {
                val vod = if (catId == null) Portal.vodSearch(query) else Portal.vodSearchInCategory(catId, query)
                runOnUiThread {
                    if (seq != searchSeq) return@runOnUiThread
                    val rows = vod.map { vodItemRow(it) }
                    b.status.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                    if (rows.isEmpty()) b.status.text = "No results for “$query”."
                    adapter.submit(rows)
                }
            }
        }
        pendingSearch = task
        searchHandler.postDelayed(task, 450)
    }

    // ---- screens ----
    private fun showHome() {
        backStack.clear()
        push(
            Page(
                "Stalker TV",
                listOf(
                    Row("📺   Live TV", null) { showLiveGenres() },
                    Row("🎬   Movies (VOD)", null) { showVodCategories() }
                ),
                kind = SearchKind.GLOBAL
            )
        )
    }

    private fun showLiveGenres() {
        val rows = ArrayList<Row>()
        rows.add(Row("All Channels  (${allChannels.size})", null) { openLiveGrid(allChannels, "All Channels") })
        for (g in genres) {
            val list = byGenre[g.id] ?: emptyList()
            if (list.isNotEmpty()) rows.add(Row("${g.title}  (${list.size})", null) { openLiveGrid(list, g.title) })
        }
        push(Page("Live TV", rows, kind = SearchKind.CHANNELS, scopeChannels = allChannels))
    }

    private fun showChannels(list: List<Portal.Channel>, title: String) {
        push(Page(title, list.map { channelRow(it) }, kind = SearchKind.CHANNELS, scopeChannels = list))
    }

    private fun showVodCategories() {
        b.status.visibility = View.VISIBLE
        b.status.text = "Loading movies…"
        io.execute {
            val cats = Portal.vodCategories()
            runOnUiThread {
                b.status.visibility = View.GONE
                if (cats.isEmpty()) {
                    b.status.visibility = View.VISIBLE
                    b.status.text = "No VOD categories found."
                    return@runOnUiThread
                }
                push(Page("Movies", cats.map { c -> Row(c.title, null) { showVodList(c) } }, kind = SearchKind.VOD_ALL))
            }
        }
    }

    private fun showVodList(cat: Portal.VodCat) {
        b.status.visibility = View.VISIBLE
        b.status.text = "Loading ${cat.title}…"
        io.execute {
            val (items, pages) = Portal.vodList(cat.id, 1)
            runOnUiThread {
                b.status.visibility = View.GONE
                push(Page(cat.title, vodRows(cat, ArrayList(items), 1, pages), kind = SearchKind.VOD_CATEGORY, scopeId = cat.id))
            }
        }
    }

    private fun vodRows(cat: Portal.VodCat, acc: ArrayList<Portal.VodItem>, loaded: Int, total: Int): List<Row> {
        val rows = ArrayList<Row>()
        acc.forEach { v -> rows.add(vodItemRow(v)) }
        if (loaded < total) {
            rows.add(Row("⬇  Load more  ($loaded/$total)", null) {
                b.status.visibility = View.VISIBLE
                b.status.text = "Loading…"
                io.execute {
                    val (more, _) = Portal.vodList(cat.id, loaded + 1)
                    runOnUiThread {
                        b.status.visibility = View.GONE
                        acc.addAll(more)
                        val page = Page(cat.title, vodRows(cat, acc, loaded + 1, total), kind = SearchKind.VOD_CATEGORY, scopeId = cat.id)
                        backStack.removeLast()
                        backStack.addLast(page)
                        display(page)
                    }
                }
            })
        }
        return rows
    }

    private fun showSeasons(series: Portal.VodItem) {
        b.status.visibility = View.VISIBLE
        b.status.text = "Loading ${series.name}…"
        io.execute {
            val seasons = Portal.seriesSeasons(series.id)
            runOnUiThread {
                b.status.visibility = View.GONE
                if (seasons.isEmpty()) {
                    b.status.visibility = View.VISIBLE
                    b.status.text = "No seasons found for ${series.name}."
                    return@runOnUiThread
                }
                push(Page(series.name, seasons.reversed().map { s ->
                    Row(s.name, null) { showEpisodes(series, s) }
                }))
            }
        }
    }

    private fun showEpisodes(series: Portal.VodItem, season: Portal.Season) {
        b.status.visibility = View.VISIBLE
        b.status.text = "Loading ${season.name}…"
        io.execute {
            val eps = Portal.seriesEpisodes(series.id, season.id)
            runOnUiThread {
                b.status.visibility = View.GONE
                if (eps.isEmpty()) {
                    b.status.visibility = View.VISIBLE
                    b.status.text = "No episodes found."
                    return@runOnUiThread
                }
                push(Page("${series.name} — ${season.name}", eps.reversed().map { e ->
                    Row(e.name, null) {
                        play("${series.name}  /  ${season.name}  /  ${e.name}") {
                            Portal.playEpisodeUrl(series.id, season.id, e.id)
                        }
                    }
                }))
            }
        }
    }

    private fun play(title: String, resolve: () -> String?) {
        b.status.visibility = View.VISIBLE
        b.status.text = "Opening $title…"
        io.execute {
            val url = resolve()
            runOnUiThread {
                if (url.isNullOrEmpty()) {
                    b.status.visibility = View.VISIBLE
                    val why = Portal.lastError
                    b.status.text = if (why == "nothing_to_play")
                        "“$title” — no stream returned. Either the provider's storage is down, or your account's connection limit is reached (another device is already streaming)."
                    else "Couldn't open “$title” — $why"
                } else {
                    b.status.visibility = View.GONE
                    startActivity(
                        Intent(this, PlayerActivity::class.java)
                            .putExtra("url", url)
                            .putExtra("title", title)
                    )
                }
            }
        }
    }
}
