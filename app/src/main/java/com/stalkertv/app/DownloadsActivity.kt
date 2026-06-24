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
import coil.load
import com.stalkertv.app.databinding.ActivityDownloadsBinding
import com.stalkertv.app.databinding.ItemDownloadBinding

class DownloadsActivity : AppCompatActivity(), Downloads.Listener {
    private lateinit var b: ActivityDownloadsBinding
    private val adapter = DlAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        refresh()
    }

    override fun onResume() {
        super.onResume()
        Downloads.setListener(this)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        Downloads.setListener(null)
    }

    override fun onDownloadsChanged() { runOnUiThread { refresh() } }

    private fun refresh() {
        val items = Downloads.list(this)
        b.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        adapter.submit(items)
    }

    private fun onClick(item: Downloads.Item) {
        when (item.status) {
            Downloads.DONE -> AlertDialog.Builder(this)
                .setTitle(item.title)
                .setItems(arrayOf("▶  Play offline", "🗑  Delete")) { _, w ->
                    if (w == 0) playOffline(item) else confirmDelete(item)
                }.show()
            Downloads.DOWNLOADING -> AlertDialog.Builder(this)
                .setTitle(item.title)
                .setMessage("Downloading…")
                .setPositiveButton("Stop & remove") { _, _ -> Downloads.delete(this, item.id) }
                .setNegativeButton("Close", null)
                .show()
            else -> AlertDialog.Builder(this)
                .setTitle(item.title)
                .setMessage(if (item.status == Downloads.UNSUPPORTED) "This title is streamed in a format that can't be saved as a single file." else "Download failed.")
                .setPositiveButton("Remove") { _, _ -> Downloads.delete(this, item.id) }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private fun playOffline(item: Downloads.Item) {
        val f = Downloads.fileFor(this, item)
        if (!f.exists()) { Downloads.delete(this, item.id); refresh(); return }
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra("url", Uri.fromFile(f).toString())
                .putExtra("title", item.title)
        )
    }

    private fun confirmDelete(item: Downloads.Item) {
        AlertDialog.Builder(this)
            .setTitle("Delete download?")
            .setMessage(item.title)
            .setPositiveButton("Delete") { _, _ -> Downloads.delete(this, item.id) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sizeStr(bytes: Long): String {
        if (bytes <= 0) return ""
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format("%.1f GB", mb / 1024) else String.format("%.0f MB", mb)
    }

    private fun subtitle(item: Downloads.Item): String = when (item.status) {
        Downloads.DONE -> "Downloaded" + (if (item.total > 0) " • ${sizeStr(item.total)}" else "")
        Downloads.DOWNLOADING ->
            if (item.total > 0) {
                val pct = (item.done * 100 / item.total).toInt()
                "Downloading $pct%   (${sizeStr(item.done)} / ${sizeStr(item.total)})"
            } else "Downloading…   ${sizeStr(item.done)}"
        Downloads.UNSUPPORTED -> "Can't save this format"
        else -> "Download failed — tap to remove"
    }

    private inner class DlAdapter : RecyclerView.Adapter<DlAdapter.VH>() {
        private var items = listOf<Downloads.Item>()
        fun submit(l: List<Downloads.Item>) { items = l; notifyDataSetChanged() }
        inner class VH(val v: ItemDownloadBinding) : RecyclerView.ViewHolder(v.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val it = items[position]
            holder.v.name.text = it.title
            holder.v.sub.text = subtitle(it)
            if (it.poster.isBlank()) holder.v.thumb.setImageResource(R.drawable.thumb_placeholder)
            else holder.v.thumb.load(it.poster) { placeholder(R.drawable.thumb_placeholder); error(R.drawable.thumb_placeholder) }
            holder.v.root.setOnClickListener { onClick(it) }
        }
    }
}
