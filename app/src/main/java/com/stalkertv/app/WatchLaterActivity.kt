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
        go { buildRoot() }
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
        val rows = ArrayList<WlRow>()
        for (m in all.filter { it.kind == "movie" })
            rows.add(WlRow(m.id, "🎬  ${m.title}", m.poster.ifBlank { null }, true) { play(m) })
        val eps = all.filter { it.kind == "episode" }
        for (seriesName in eps.map { e -> parts(e.title).getOrElse(0) { e.title } }.distinct())
            rows.add(WlRow(null, "📁  $seriesName", null, false) { go { buildSeries(seriesName) } })
        render(rows, all.isEmpty())
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
        b.empty.visibility = if (empty) View.VISIBLE else View.GONE
        b.list.visibility = if (empty) View.GONE else View.VISIBLE
        b.removeSelBtn.visibility = if (rows.any { it.checkable }) View.VISIBLE else View.GONE
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
                holder.v.check.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selected.add(id) else selected.remove(id)
                }
            } else {
                holder.v.check.visibility = View.GONE
                holder.v.check.isChecked = false
            }
            holder.v.root.setOnClickListener { row.onClick() }
        }
    }
}
