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
    data class Page(val title: String, val rows: List<Row>)

    private val backStack = ArrayDeque<Page>()

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

        b.status.text = "Loading…"
        io.execute {
            val ch = Portal.liveChannels()
            val g = Portal.liveGenres()
            runOnUiThread {
                allChannels = ch
                genres = g
                byGenre = ch.groupBy { it.genreId }
                b.status.visibility = View.GONE
                if (ch.isEmpty()) {
                    b.status.visibility = View.VISIBLE
                    b.status.text = "No channels returned. (Portal auth or listing issue.)"
                } else {
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
        val src = backStack.lastOrNull()?.rows ?: return
        val query = q.trim().lowercase()
        adapter.submit(if (query.isEmpty()) src else src.filter { it.label.lowercase().contains(query) })
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
                )
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
        acc.forEach { v -> rows.add(Row(v.name, v.posterUrl) { play(v.name) { Portal.playVodUrl(v.id, v.cmd) } }) }
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
