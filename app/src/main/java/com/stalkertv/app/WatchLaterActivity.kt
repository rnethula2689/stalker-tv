package com.stalkertv.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.stalkertv.app.databinding.ActivityWatchlaterBinding
import com.stalkertv.app.databinding.ItemWatchlaterBinding
import java.util.concurrent.Executors

/**
 * "Watch Later" folder: movies + episodes the user tagged from the action sheet. Movies sit at the
 * top level; episodes nest as Series → Season → Episode (same title-split rule as favourites). Each
 * playable row has a checkbox for "Remove selected"; "Remove all" clears the whole list.
 */
class WatchLaterActivity : AppCompatActivity() {
    private val playIo = Executors.newSingleThreadExecutor()
    private lateinit var b: ActivityWatchlaterBinding
    private val adapter = WlAdapter()
    private val selected = HashSet<String>()
    private val stack = ArrayDeque<() -> Unit>() // internal folder navigation
    private var lastEmpty = false
    private val tv by lazy { Tv.isTv(this) }
    private var hasCheckable = false
    // P4.2: root-level sort + search
    private var wlSort = 0 // 0 = Newest, 1 = A–Z, 2 = Z–A
    private val wlSortLabels = arrayOf("⇅  Newest", "⇅  A–Z", "⇅  Z–A")
    private var wlQuery = ""

    /** Show "Remove selected" when items are ticked (TV) / whenever the list has items (tablet). */
    private fun updateRemoveSelBtn() {
        b.removeSelBtn.visibility =
            if (hasCheckable && (!tv || selected.isNotEmpty())) View.VISIBLE else View.GONE
    }

    /** Toggle an item's selection (TV: long-press / press while selecting; tablet: checkbox). */
    private fun toggleSel(id: String) {
        if (selected.contains(id)) selected.remove(id) else selected.add(id)
        adapter.notifyDataSetChanged()
        updateRemoveSelBtn()
    }

    data class WlRow(
        val id: String?,        // entry id (checkable leaf) or null for a folder
        val label: String,
        val poster: String?,
        val checkable: Boolean,
        val onClick: () -> Unit
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityWatchlaterBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.removeSelBtn.setOnClickListener { removeSelected() }
        b.removeAllBtn.setOnClickListener { confirmRemoveAll() }
        b.sortBtn.text = wlSortLabels[wlSort]
        b.sortBtn.setOnClickListener {
            wlSort = (wlSort + 1) % wlSortLabels.size
            b.sortBtn.text = wlSortLabels[wlSort]
            resetToRoot()
        }
        b.exportBtn.setOnClickListener { exportList() }
        b.search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                wlQuery = s?.toString()?.trim() ?: ""
                resetToRoot()
            }
        })
        go { buildRoot() }
    }

    /** Collapse any nested folder navigation and re-show the (filtered/sorted) root. */
    private fun resetToRoot() {
        while (stack.size > 1) stack.removeLast()
        selected.clear()
        if (stack.isEmpty()) go { buildRoot() } else stack.last().invoke()
    }

    /** Share the whole Watch Later list as plain text. */
    private fun exportList() {
        val all = WatchLater.all(this)
        if (all.isEmpty()) {
            android.widget.Toast.makeText(this, "Nothing to export yet.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val text = buildString {
            append("My Watch Later (${all.size})\n\n")
            all.forEachIndexed { i, e -> append("${i + 1}. ${e.title}\n") }
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "My Watch Later")
            .putExtra(Intent.EXTRA_TEXT, text)
        try { startActivity(Intent.createChooser(send, "Export Watch Later")) }
        catch (_: Exception) { android.widget.Toast.makeText(this, "No app to share with.", android.widget.Toast.LENGTH_SHORT).show() }
    }

    private fun parts(title: String) = title.split("/").map { it.trim() }

    private fun go(builder: () -> Unit) { stack.addLast(builder); builder() }

    override fun onBackPressed() {
        if (stack.size > 1) { stack.removeLast(); selected.clear(); stack.last().invoke() }
        else super.onBackPressed()
    }

    private fun buildRoot() {
        b.title.text = "🕒  Watch Later"
        selected.clear()
        val all = WatchLater.all(this)
        val q = wlQuery.lowercase()
        fun sortNames(l: List<String>) = when (wlSort) {
            1 -> l.sortedBy { it.lowercase() }; 2 -> l.sortedByDescending { it.lowercase() }; else -> l
        }
        var movies = all.filter { it.kind == "movie" }
        if (q.isNotEmpty()) movies = movies.filter { it.title.lowercase().contains(q) }
        movies = when (wlSort) {
            1 -> movies.sortedBy { it.title.lowercase() }
            2 -> movies.sortedByDescending { it.title.lowercase() }
            else -> movies // Newest: all() is already newest-first
        }
        val rows = ArrayList<WlRow>()
        for (m in movies) rows.add(WlRow(m.id, "🎬  ${m.title}", m.poster.ifBlank { null }, true) { play(m) })
        var seriesNames = all.filter { it.kind == "episode" }
            .map { e -> parts(e.title).getOrElse(0) { e.title } }.distinct()
        if (q.isNotEmpty()) seriesNames = seriesNames.filter { it.lowercase().contains(q) }
        for (seriesName in sortNames(seriesNames))
            rows.add(WlRow(null, "📁  $seriesName", null, false) { go { buildSeries(seriesName) } })
        b.empty.text = if (all.isEmpty())
            "Nothing saved for later yet.\n\nOpen a movie or episode and choose “🕒 Watch later”."
        else "No matches for “$wlQuery”."
        render(rows, rows.isEmpty())
    }

    private fun buildSeries(seriesName: String) {
        b.title.text = "🕒  $seriesName"
        selected.clear()
        val eps = WatchLater.byKind(this, "episode").filter { parts(it.title).getOrElse(0) { "" } == seriesName }
        val rows = eps.map { parts(it.title).getOrElse(1) { "" } }.distinct().map { season ->
            WlRow(null, "📁  $season", null, false) { go { buildSeason(seriesName, season) } }
        }
        render(ArrayList(rows), eps.isEmpty())
    }

    private fun buildSeason(seriesName: String, season: String) {
        b.title.text = "🕒  $seriesName — $season"
        selected.clear()
        val eps = WatchLater.byKind(this, "episode").filter {
            val p = parts(it.title); p.getOrElse(0) { "" } == seriesName && p.getOrElse(1) { "" } == season
        }
        val rows = eps.map { e ->
            val epName = parts(e.title).getOrElse(2) { e.title }
            WlRow(e.id, "🎬  $epName", e.poster.ifBlank { null }, true) { play(e) }
        }
        render(ArrayList(rows), eps.isEmpty())
    }

    private fun render(rows: List<WlRow>, empty: Boolean) {
        lastEmpty = empty
        // Search + sort act on the root list only — hide them while inside a series/season folder.
        b.toolbar.visibility = if (stack.size > 1) View.GONE else View.VISIBLE
        b.empty.visibility = if (empty) View.VISIBLE else View.GONE
        b.list.visibility = if (empty) View.GONE else View.VISIBLE
        hasCheckable = rows.any { it.checkable }
        updateRemoveSelBtn()
        b.removeAllBtn.visibility = if (WatchLater.all(this).isEmpty()) View.GONE else View.VISIBLE
        adapter.submit(rows)
    }

    private fun removeSelected() {
        if (selected.isEmpty()) {
            android.widget.Toast.makeText(this, "Tick the items you want to remove first.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val n = selected.size
        WatchLater.removeIds(this, HashSet(selected))
        selected.clear()
        android.widget.Toast.makeText(this, "Removed $n from Watch Later", android.widget.Toast.LENGTH_SHORT).show()
        // Rebuild the current level; if it emptied out, pop back to a populated parent.
        stack.last().invoke()
        while (stack.size > 1 && lastEmpty) { stack.removeLast(); stack.last().invoke() }
    }

    private fun confirmRemoveAll() {
        AlertDialog.Builder(this)
            .setTitle("Remove all from Watch Later?")
            .setMessage("This clears your entire Watch Later list.")
            .setPositiveButton("Remove all") { _, _ ->
                WatchLater.clearAll(this)
                android.widget.Toast.makeText(this, "Watch Later cleared", android.widget.Toast.LENGTH_SHORT).show()
                finish() // back to home; the row is gone
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun play(e: WatchLater.Entry) {
        val r = Resume.get(this, e.id)
        if (Resume.resumable(r)) {
            AlertDialog.Builder(this)
                .setTitle(e.title)
                .setItems(arrayOf("▶  Resume from ${fmtTime(r!!.position)}", "↻  Start from beginning")) { _, w ->
                    startPlayer(e, if (w == 0) r.position else 0L)
                }.show()
        } else startPlayer(e, 0L)
    }

    private fun startPlayer(e: WatchLater.Entry, startPos: Long) {
        android.widget.Toast.makeText(this, "Opening ${e.title}…", android.widget.Toast.LENGTH_SHORT).show()
        playIo.execute {
            val url = Downloads.resolveSource(e.source)
            runOnUiThread {
                if (url.isNullOrEmpty()) {
                    android.widget.Toast.makeText(this, "Couldn't open “${e.title}”.", android.widget.Toast.LENGTH_LONG).show()
                } else {
                    startActivity(
                        Intent(this, PlayerActivity::class.java)
                            .putExtra("url", url)
                            .putExtra("title", e.title)
                            .putExtra("resumeId", e.id)
                            .putExtra("resumeSource", e.source)
                            .putExtra("resumePoster", e.poster)
                            .putExtra("resumeStart", startPos)
                    )
                }
            }
        }
    }

    private fun fmtTime(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
    }

    private inner class WlAdapter : RecyclerView.Adapter<WlAdapter.VH>() {
        private var items = listOf<WlRow>()
        fun submit(l: List<WlRow>) { items = l; notifyDataSetChanged() }
        inner class VH(val v: ItemWatchlaterBinding) : RecyclerView.ViewHolder(v.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemWatchlaterBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = items[position]
            holder.v.name.text = row.label
            if (row.poster.isNullOrBlank()) holder.v.thumb.setImageResource(R.drawable.thumb_placeholder)
            else holder.v.thumb.load(row.poster) { placeholder(R.drawable.thumb_placeholder); error(R.drawable.thumb_placeholder) }
            holder.v.check.setOnCheckedChangeListener(null)
            val id = row.id
            if (row.checkable && id != null) {
                holder.v.check.visibility = View.VISIBLE
                holder.v.check.isChecked = selected.contains(id)
                if (tv) {
                    holder.v.check.isClickable = false // not focus-reachable on TV; select via the row
                } else {
                    holder.v.check.setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) selected.add(id) else selected.remove(id)
                        updateRemoveSelBtn()
                    }
                }
            } else {
                holder.v.check.visibility = View.GONE
                holder.v.check.isChecked = false
            }
            // Center: play (or, on TV once you're selecting, toggle this item). Long-press: select.
            holder.v.root.setOnClickListener {
                if (row.checkable && id != null && tv && selected.isNotEmpty()) toggleSel(id) else row.onClick()
            }
            holder.v.root.setOnLongClickListener {
                if (row.checkable && id != null) { toggleSel(id); true } else false
            }
        }
    }
}
