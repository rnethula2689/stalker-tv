package com.stalkertv.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.stalkertv.app.databinding.ActivityLivegridBinding
import java.util.concurrent.Executors

@OptIn(UnstableApi::class)
class LiveGridActivity : AppCompatActivity() {
    companion object {
        var channels: List<Portal.Channel> = emptyList()
        var gridTitle: String = "Live TV"
    }

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var b: ActivityLivegridBinding
    private lateinit var adapter: ChannelGridAdapter

    private var player: ExoPlayer? = null
    private var all = listOf<Portal.Channel>()
    private var current: Portal.Channel? = null
    private var currentUrl: String? = null
    private var pendingPreview: Runnable? = null
    private var seq = 0
    private var retried = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLivegridBinding.inflate(layoutInflater)
        setContentView(b.root)

        all = channels
        b.title.text = gridTitle

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Portal.UA).setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20000).setReadTimeoutMs(20000)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 50000, 1200, 2500).build()
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setLoadControl(loadControl).build()
        player = p
        b.preview.player = p
        p.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val ch = current ?: return
                if (!retried) { retried = true; loadPreview(ch) }
            }
        })

        adapter = ChannelGridAdapter(all) { ch -> select(ch) }
        b.list.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        b.list.adapter = adapter

        b.previewFrame.setOnClickListener { openFullscreen() }
        b.searchBtn.setOnClickListener { toggleSearch() }
        b.menuBtn.setOnClickListener { showMenu(it) }
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
                if (d != null && e.x >= et.width - et.paddingEnd - d.intrinsicWidth) { et.setText(""); return@setOnTouchListener true }
            }
            false
        }

        if (all.isNotEmpty()) {
            b.epg.text = "Loading…"
            select(all[0])
            b.list.post { b.list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
        } else {
            b.epg.text = "No channels."
        }
    }

    /** Focus or click on a channel → update the preview (debounced). */
    private fun select(ch: Portal.Channel) {
        current = ch
        retried = false
        player?.stop() // release the previous stream immediately (portals often cap concurrent streams)
        pendingPreview?.let { ui.removeCallbacks(it) }
        val r = Runnable { loadPreview(ch) }
        pendingPreview = r
        ui.postDelayed(r, 550)
    }

    private fun loadPreview(ch: Portal.Channel) {
        val mine = ++seq
        b.epg.text = "▶  ${ch.name}\n\nLoading…"
        io.execute {
            if (mine != seq) return@execute // superseded by a newer selection — skip the work
            val url = Portal.createLink(ch.cmd)
            if (mine != seq) return@execute
            val epg = Portal.shortEpg(ch.id)
            runOnUiThread {
                if (mine != seq || current != ch) return@runOnUiThread
                currentUrl = url
                val pl = player ?: return@runOnUiThread
                pl.stop()
                pl.clearMediaItems()
                if (!url.isNullOrEmpty()) {
                    pl.setMediaItem(MediaItem.fromUri(url))
                    pl.prepare()
                    pl.playWhenReady = true
                }
                b.epg.text = formatEpg(ch, epg, url)
            }
        }
    }

    private fun formatEpg(ch: Portal.Channel, epg: List<Portal.EpgItem>, url: String?): String {
        val sb = StringBuilder("▶  ${ch.name}\n")
        if (url.isNullOrEmpty()) sb.append("\n(no stream — provider down, or connection limit reached: another device may be streaming)\n")
        if (epg.isEmpty()) {
            sb.append("\nNo program guide for this channel.")
        } else {
            epg.forEachIndexed { i, e ->
                val tag = if (i == 0) "NOW " else "NEXT"
                sb.append("\n$tag  ${e.start}–${e.end}\n${e.name}")
                if (i == 0 && e.descr.isNotBlank()) sb.append("\n${e.descr}")
                sb.append("\n")
            }
        }
        return sb.toString()
    }

    private fun openFullscreen() {
        val ch = current ?: return
        val url = currentUrl
        if (!url.isNullOrEmpty()) {
            startActivity(Intent(this, PlayerActivity::class.java).putExtra("url", url).putExtra("title", ch.name))
        } else {
            b.epg.text = "Opening ${ch.name}…"
            io.execute {
                val u = Portal.createLink(ch.cmd)
                runOnUiThread {
                    if (!u.isNullOrEmpty())
                        startActivity(Intent(this, PlayerActivity::class.java).putExtra("url", u).putExtra("title", ch.name))
                }
            }
        }
    }

    private fun toggleSearch() {
        if (b.search.visibility == View.VISIBLE) {
            b.search.setText("")
            b.search.visibility = View.GONE
        } else {
            b.search.visibility = View.VISIBLE
            b.search.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(b.search, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun filter(q: String) {
        val query = q.trim()
        adapter.submit(if (query.isEmpty()) all else all.filter { it.name.contains(query, ignoreCase = true) })
    }

    private fun showMenu(anchor: View) {
        val pm = androidx.appcompat.widget.PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "🔄   Refresh")
        pm.menu.add(0, 2, 0, "⚙   Settings")
        pm.menu.add(0, 3, 0, "✖   Exit")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> current?.let { loadPreview(it) }
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

    override fun onStop() {
        super.onStop()
        player?.stop() // free the stream while in fullscreen / background
    }

    override fun onRestart() {
        super.onRestart()
        current?.let { loadPreview(it) } // resume the preview when returning from fullscreen
    }

    override fun onDestroy() {
        super.onDestroy()
        pendingPreview?.let { ui.removeCallbacks(it) }
        player?.release()
        player = null
    }
}
