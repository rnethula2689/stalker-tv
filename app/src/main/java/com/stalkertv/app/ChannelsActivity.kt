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
    data class Page(val title: String, val rows: List<Row>, val global: Boolean = false)

    private val backStack = ArrayDeque<Page>()
    private var searchMode = false
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
            override fun afterTextChanged(s: android.text.Editable?) = filter(s?.toString() ?: "")
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
        })

        b.reloadBtn.setOnClickListener { connectAndLoad() }
        b.settingsBtn.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        connectAndLoad()
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
        searchMode = page.global
        b.title.text = page.title
        b.search.hint = if (page.global) "Search channels, movies & shows…" else "Filter this list…"
        adapter.submit(page.rows)
        if (b.search.text.isNotEmpty()) b.search.setText("")
        b.list.scrollToPosition(0)
        b.list.requestFocus()
    }

    override fun onBackPressed() {
        if (backStack.size > 1) {
            backStack.removeLast()
            display(backStack.last())
        } else {
            super.onBackPressed()
        }
    }

    private fun filter(q: String) {
        if (searchMode) { globalSearch(q); return }
        val src = backStack.lastOrNull()?.rows ?: return
        val query = q.trim().lowercase()
        adapter.submit(if (query.isEmpty()) src else src.filter { it.label.lowercase().contains(query) })
    }

    /** Home-screen global search: channels (instant, in-memory) + movies/series (portal VOD search). */
    private fun globalSearch(q: String) {
        val query = q.trim()
        pendingSearch?.let { searchHandler.removeCallbacks(it) }
        searchSeq++
        if (query.isEmpty()) {
            b.status.visibility = View.GONE
            adapter.submit(backStack.last().rows)
            return
        }
        val chRows = allChannels.asSequence()
            .filter { it.name.contains(query, ignoreCase = true) }
            .take(200)
            .map { ch ->
                val label = "📺  " + (if (ch.number.isNotEmpty()) "${ch.number}. " else "") + ch.name
                Row(label, ch.logoUrl) { play(ch.name) { Portal.createLink(ch.cmd) } }
            }.toList()
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
                    val vodRows = vod.map { v ->
                        val label = (if (v.isSeries) "📁  " else "🎬  ") + v.name
                        Row(label, v.posterUrl) {
                            if (v.isSeries) showSeasons(v) else play(v.name) { Portal.playVodUrl(v.id, v.cmd) }
                        }
                    }
                    b.status.visibility = View.GONE
                    adapter.submit(chRows + vodRows)
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
                global = true
            )
        )
    }

    private fun showLiveGenres() {
        val rows = ArrayList<Row>()
        rows.add(Row("All Channels  (${allChannels.size})", null) { showChannels(allChannels, "All Channels") })
        for (g in genres) {
            val list = byGenre[g.id] ?: emptyList()
            if (list.isNotEmpty()) rows.add(Row("${g.title}  (${list.size})", null) { showChannels(list, g.title) })
        }
        push(Page("Live TV", rows))
    }

    private fun showChannels(list: List<Portal.Channel>, title: String) {
        push(Page(title, list.map { ch ->
            val label = if (ch.number.isNotEmpty()) "${ch.number}.  ${ch.name}" else ch.name
            Row(label, ch.logoUrl) { play(ch.name) { Portal.createLink(ch.cmd) } }
        }))
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
                push(Page("Movies", cats.map { c -> Row(c.title, null) { showVodList(c) } }))
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
                push(Page(cat.title, vodRows(cat, ArrayList(items), 1, pages)))
            }
        }
    }

    private fun vodRows(cat: Portal.VodCat, acc: ArrayList<Portal.VodItem>, loaded: Int, total: Int): List<Row> {
        val rows = ArrayList<Row>()
        acc.forEach { v ->
            val label = if (v.isSeries) "📁  ${v.name}" else v.name
            rows.add(Row(label, v.posterUrl) {
                if (v.isSeries) showSeasons(v) else play(v.name) { Portal.playVodUrl(v.id, v.cmd) }
            })
        }
        if (loaded < total) {
            rows.add(Row("⬇  Load more  ($loaded/$total)", null) {
                b.status.visibility = View.VISIBLE
                b.status.text = "Loading…"
                io.execute {
                    val (more, _) = Portal.vodList(cat.id, loaded + 1)
                    runOnUiThread {
                        b.status.visibility = View.GONE
                        acc.addAll(more)
                        val page = Page(cat.title, vodRows(cat, acc, loaded + 1, total))
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
                        "“$title” — provider has no stream right now (storage unavailable)."
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
