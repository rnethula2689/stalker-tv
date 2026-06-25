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
        b.removeAllBtn.setOnClickListener { confirmRemoveAll() }
        refresh()
    }

    private fun confirmRemoveAll() {
        AlertDialog.Builder(this)
            .setTitle("Remove all downloads?")
            .setMessage("This deletes every downloaded title and frees the disk space. This cannot be undone.")
            .setPositiveButton("Remove all") { _, _ ->
                Downloads.deleteAll(applicationContext)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private var netCb: android.net.ConnectivityManager.NetworkCallback? = null

    override fun onResume() {
        super.onResume()
        Downloads.addListener(this)
        registerNet()
        Downloads.resumeAllAuto(applicationContext) // resume outage-paused items if we're back online
        refresh()
    }

    override fun onPause() {
        super.onPause()
        Downloads.removeListener(this)
        unregisterNet()
    }

    private fun registerNet() {
        if (android.os.Build.VERSION.SDK_INT < 24) return
        try {
            val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    runOnUiThread { Downloads.resumeAllAuto(applicationContext) }
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            netCb = cb
        } catch (_: Exception) {}
    }

    private fun unregisterNet() {
        try {
            val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            netCb?.let { cm.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        netCb = null
    }

    override fun onDownloadsChanged() { runOnUiThread { refresh() } }

    private fun refresh() {
        val items = Downloads.list(this)
        b.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        b.removeAllBtn.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
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
                .setItems(arrayOf("⏸  Pause", "🗑  Stop & remove")) { _, w ->
                    if (w == 0) Downloads.pause(applicationContext, item.id) else confirmDelete(item)
                }.show()
            Downloads.PAUSED -> AlertDialog.Builder(this)
                .setTitle(item.title)
                .setItems(arrayOf("▶  Resume", "🗑  Delete")) { _, w ->
                    if (w == 0) Downloads.resume(applicationContext, item.id) else confirmDelete(item)
                }.show()
            else -> AlertDialog.Builder(this)
                .setTitle(item.title)
                .setItems(arrayOf("↻  Retry", "🗑  Remove")) { _, w ->
                    if (w == 0) Downloads.resume(applicationContext, item.id) else confirmDelete(item)
                }.show()
        }
    }

    private fun playOffline(item: Downloads.Item) {
        val f = Downloads.fileFor(this, item)
        if (!f.exists()) { Downloads.delete(this, item.id); refresh(); return }
        val r = Resume.get(applicationContext, item.id)
        if (Resume.resumable(r)) {
            AlertDialog.Builder(this)
                .setTitle(item.title)
                .setItems(arrayOf("▶  Resume", "↻  Start from beginning")) { _, w ->
                    launchOffline(item, f, if (w == 0) r!!.position else 0L)
                }.show()
        } else launchOffline(item, f, 0L)
    }

    private fun launchOffline(item: Downloads.Item, f: java.io.File, startPos: Long) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra("url", Uri.fromFile(f).toString())
                .putExtra("title", item.title)
                .putExtra("resumeId", item.id)
                .putExtra("resumeSource", item.source)
                .putExtra("resumePoster", item.poster)
                .putExtra("resumeStart", startPos)
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
        Downloads.DONE -> if (item.hls) "Downloaded • offline ready"
            else "Downloaded" + (if (item.total > 0) " • ${sizeStr(item.total)}" else "")
        Downloads.DOWNLOADING -> {
            val pct = if (item.total > 0) (item.done * 100 / item.total).toInt() else 0
            when {
                item.hls -> "Downloading $pct%   (${item.done} / ${item.total} segments)"
                item.total > 0 -> "Downloading $pct%   (${sizeStr(item.done)} / ${sizeStr(item.total)})"
                else -> "Downloading…   ${sizeStr(item.done)}"
            }
        }
        Downloads.PAUSED -> {
            val pct = if (item.total > 0) (item.done * 100 / item.total).toInt() else 0
            val why = if (item.userPaused) "Paused" else "Paused (no network)"
            why + (if (item.total > 0) " • $pct%" else "") + " — tap to resume"
        }
        else -> "Download failed — tap to retry"
    }

    private inner class DlAdapter : RecyclerView.Adapter<DlAdapter.VH>() {
        private var items = listOf<Downloads.Item>()
        fun submit(l: List<Downloads.Item>) { items = l; notifyDataSetChanged() }
        inner class VH(val v: ItemDownloadBinding) : RecyclerView.ViewHolder(v.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.v.name.text = item.title
            holder.v.sub.text = subtitle(item)
            if (item.poster.isBlank()) holder.v.thumb.setImageResource(R.drawable.thumb_placeholder)
            else holder.v.thumb.load(item.poster) { placeholder(R.drawable.thumb_placeholder); error(R.drawable.thumb_placeholder) }
            holder.v.root.setOnClickListener { onClick(item) }
        }
    }
}
