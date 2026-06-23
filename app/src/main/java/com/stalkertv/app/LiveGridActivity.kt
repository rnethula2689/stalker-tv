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
    private var currentUrlId: String? = null
    private var pendingPreview: Runnable? = null
    private var seq = 0
    private var retried = false
    private var lastActivateId = ""
    private var lastActivateTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLivegridBinding.inflate(layoutInflater)
        setContentView(b.root)

        all = channels
        b.title.text = gridTitle

        val p = buildPlayer()
        player = p
        b.preview.player = p

        adapter = ChannelGridAdapter(all, { ch -> select(ch) }, { ch -> activate(ch) })
        b.list.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        b.list.adapter = adapter

        b.previewFrame.setOnClickListener { openFullscreen() }
        b.searchBtn.setOnClickListener { toggleSearch() }
        b.menuBtn.setOnClickListener { showMenu() }
        b.clearBtn.setOnClickListener { b.search.setText(""); b.search.requestFocus() }
        b.search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = filter(s?.toString() ?: "")
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
        })
        buildAzBar()

        if (all.isNotEmpty()) {
            b.epg.text = "Loading…"
            select(all[0])
            b.list.post { b.list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
        } else {
            b.epg.text = "No channels."
        }
    }

    private fun buildPlayer(): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Portal.UA).setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20000).setReadTimeoutMs(20000)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 50000, 1200, 2500).build()
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setLoadControl(loadControl).build()
        p.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val ch = current ?: return
                if (!retried) { retried = true; loadPreview(ch) }
            }
        })
        return p
    }

    /** Double center-press / double-tap a channel → fullscreen; single = preview. */
    private fun activate(ch: Portal.Channel) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (ch.id == lastActivateId && now - lastActivateTime < 600) {
            lastActivateId = ""
            openFullscreen()
        } else {
            if (current?.id != ch.id) select(ch)
            lastActivateId = ch.id
            lastActivateTime = now
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
                currentUrlId = ch.id
                // Fully rebuild the player so the previous stream's connection is released
                // (this server caps concurrent connections per token).
                player?.release()
                val pl = buildPlayer()
                player = pl
                b.preview.player = pl
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
        val url = if (currentUrlId == ch.id) currentUrl else null
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
        if (b.searchRow.visibility == View.VISIBLE) {
            b.search.setText("")
            b.searchRow.visibility = View.GONE
        } else {
            b.searchRow.visibility = View.VISIBLE
            b.search.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(b.search, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun filter(q: String) {
        val query = q.trim()
        adapter.submit(if (query.isEmpty()) all else all.filter { it.name.contains(query, ignoreCase = true) })
    }

    private fun buildAzBar() {
        val labels = listOf("ALL") + ('A'..'Z').map { it.toString() } + ('0'..'9').map { it.toString() }
        for (lbl in labels) {
            val tv = android.widget.TextView(this)
            tv.text = lbl
            tv.setTextColor(0xFFE6EDF3.toInt())
            tv.textSize = 15f
            tv.setPadding(20, 12, 20, 12)
            tv.isFocusable = true
            tv.isClickable = true
            tv.setBackgroundResource(R.drawable.item_bg)
            tv.setOnClickListener { azFilter(if (lbl == "ALL") null else lbl) }
            b.azBar.addView(tv)
        }
    }

    private fun azFilter(letter: String?) {
        if (b.search.text.isNotEmpty()) b.search.setText("")
        adapter.submit(if (letter == null) all else all.filter { it.name.trimStart().startsWith(letter, ignoreCase = true) })
    }

    private var menuDialog: androidx.appcompat.app.AlertDialog? = null
    private fun showMenu() {
        if (menuDialog?.isShowing == true) { menuDialog?.dismiss(); return }
        val items = arrayOf("🔄   Refresh", "⚙   Settings", "✖   Exit")
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> current?.let { loadPreview(it) }
                    1 -> startActivity(Intent(this, SettingsActivity::class.java))
                    2 -> finishAffinity()
                }
            }
            .setOnDismissListener { menuDialog = null }
            .create()
        dlg.setOnKeyListener { d, keyCode, ev ->
            if (keyCode == android.view.KeyEvent.KEYCODE_MENU && ev.action == android.view.KeyEvent.ACTION_UP) {
                d.dismiss(); true
            } else false
        }
        menuDialog = dlg
        dlg.show()
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
            android.view.KeyEvent.KEYCODE_MENU -> return true // handled on key-up (avoids flash)
            android.view.KeyEvent.KEYCODE_BACK -> { event.startTracking(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) { confirmExit(); return true }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_MENU) { showMenu(); return true }
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
