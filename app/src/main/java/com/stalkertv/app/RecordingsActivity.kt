package com.stalkertv.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.stalkertv.app.databinding.ActivityRecordingsBinding
import com.stalkertv.app.databinding.ItemWatchlaterBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Recorded streams saved locally. Grouped into folders by channel; tap a recording to play it,
 * tick recordings for "Delete selected", or "Delete all". Mirrors the Watch Later folder UI.
 */
class RecordingsActivity : AppCompatActivity() {
    private lateinit var b: ActivityRecordingsBinding
    private val adapter = RecAdapter()
    private val selected = HashSet<String>()
    private val stack = ArrayDeque<() -> Unit>()
    private var lastEmpty = false

    data class RecRow(val path: String?, val label: String, val checkable: Boolean, val onClick: () -> Unit)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityRecordingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.removeSelBtn.setOnClickListener { removeSelected() }
        b.removeAllBtn.setOnClickListener { confirmRemoveAll() }
        go { buildRoot() }
    }

    private fun go(builder: () -> Unit) { stack.addLast(builder); builder() }

    override fun onBackPressed() {
        if (stack.size > 1) { stack.removeLast(); selected.clear(); stack.last().invoke() }
        else super.onBackPressed()
    }

    private fun chanOf(i: Recordings.Item) = i.channel.ifBlank { "Other" }

    private fun buildRoot() {
        b.title.text = "⏺  Recordings"
        selected.clear()
        val all = Recordings.list(this)
        val rows = ArrayList<RecRow>()
        for (ch in all.map { chanOf(it) }.distinct().sorted()) {
            val n = all.count { chanOf(it) == ch }
            rows.add(RecRow(null, "📁  $ch  ($n)", false) { go { buildChannel(ch) } })
        }
        render(rows, all.isEmpty())
    }

    private fun buildChannel(ch: String) {
        b.title.text = "⏺  $ch"
        selected.clear()
        val items = Recordings.list(this).filter { chanOf(it) == ch }
        val rows = items.map { it2 ->
            RecRow(it2.file, "🎬  ${it2.title}\n${fmtDate(it2.added)} · ${sizeStr(it2.size)}", true) { play(it2) }
        }
        render(ArrayList(rows), items.isEmpty())
    }

    private fun render(rows: List<RecRow>, empty: Boolean) {
        lastEmpty = empty
        b.empty.visibility = if (empty) View.VISIBLE else View.GONE
        b.list.visibility = if (empty) View.GONE else View.VISIBLE
        b.removeSelBtn.visibility = if (rows.any { it.checkable }) View.VISIBLE else View.GONE
        b.removeAllBtn.visibility = if (Recordings.list(this).isEmpty()) View.GONE else View.VISIBLE
        adapter.submit(rows)
    }

    private fun play(item: Recordings.Item) {
        val f = File(item.file)
        if (!f.exists()) { Recordings.delete(this, item.file); stack.last().invoke(); return }
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra("url", Uri.fromFile(f).toString())
                .putExtra("title", item.title)
                .putExtra("noPlaylist", true)
        )
    }

    private fun removeSelected() {
        if (selected.isEmpty()) {
            android.widget.Toast.makeText(this, "Tick recordings to delete first.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val n = selected.size
        Recordings.deleteIds(this, HashSet(selected))
        selected.clear()
        android.widget.Toast.makeText(this, "Deleted $n recording(s)", android.widget.Toast.LENGTH_SHORT).show()
        stack.last().invoke()
        while (stack.size > 1 && lastEmpty) { stack.removeLast(); stack.last().invoke() }
    }

    private fun confirmRemoveAll() {
        AlertDialog.Builder(this)
            .setTitle("Delete all recordings?")
            .setMessage("This permanently deletes every recording and frees the disk space.")
            .setPositiveButton("Delete all") { _, _ ->
                Recordings.deleteAll(this)
                android.widget.Toast.makeText(this, "All recordings deleted", android.widget.Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun fmtDate(ms: Long): String = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.US).format(Date(ms))

    private fun sizeStr(bytes: Long): String {
        if (bytes <= 0) return "—"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format("%.1f GB", mb / 1024) else String.format("%.0f MB", mb)
    }

    private inner class RecAdapter : RecyclerView.Adapter<RecAdapter.VH>() {
        private var items = listOf<RecRow>()
        fun submit(l: List<RecRow>) { items = l; notifyDataSetChanged() }
        inner class VH(val v: ItemWatchlaterBinding) : RecyclerView.ViewHolder(v.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemWatchlaterBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = items[position]
            holder.v.name.text = row.label
            holder.v.thumb.setImageResource(R.drawable.thumb_placeholder)
            holder.v.check.setOnCheckedChangeListener(null)
            val path = row.path
            if (row.checkable && path != null) {
                holder.v.check.visibility = View.VISIBLE
                holder.v.check.isChecked = selected.contains(path)
                holder.v.check.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selected.add(path) else selected.remove(path)
                }
            } else {
                holder.v.check.visibility = View.GONE
                holder.v.check.isChecked = false
            }
            holder.v.root.setOnClickListener { row.onClick() }
        }
    }
}
