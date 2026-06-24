package com.stalkertv.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import com.stalkertv.app.databinding.ActivityLivegridBinding
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.util.concurrent.Executors

/** Split-screen live TV: channel list on the left, libVLC preview + EPG on the right. */
class LiveGridActivity : AppCompatActivity() {
    companion object {
        var channels: List<Portal.Channel> = emptyList()
        var gridTitle: String = "Live TV"
    }

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var b: ActivityLivegridBinding
    private lateinit var adapter: ChannelGridAdapter

    private var libVlc: LibVLC? = null
    private var mp: MediaPlayer? = null
    private var all = listOf<Portal.Channel>()
    private var current: Portal.Channel? = null
    private var currentUrl: String? = null
    private var currentUrlId: String? = null
    private var pendingPreview: Runnable? = null
    private var seq = 0
    private var attached = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLivegridBinding.inflate(layoutInflater)
        setContentView(b.root)

        all = channels
        b.title.text = gridTitle

        val vlc = LibVLC(this, arrayListOf("--network-caching=1500", "--http-reconnect", "--no-drop-late-frames", "--no-skip-frames"))
        libVlc = vlc
        mp = MediaPlayer(vlc) // attached to the surface in onStart (and re-attached on return from fullscreen)

        adapter = ChannelGridAdapter(all, { ch -> activate(ch) }, { ch -> select(ch) })
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
            current = all[0] // onStart starts the preview (and restarts it when returning from fullscreen)
            b.list.post { b.list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
        } else {
            b.epg.text = "No channels."
        }
    }

    /** OK/tap a channel → preview it; OK/tap again on the same (already-previewing) channel → fullscreen. */
    private fun activate(ch: Portal.Channel) {
        if (current?.id == ch.id) openFullscreen() else select(ch)
    }

    /** Focus or click on a channel → update the preview (debounced). */
    private fun select(ch: Portal.Channel) {
        if (current?.id == ch.id) return // already previewing this channel (e.g. redundant focus event)
        current = ch
        mp?.stop() // release the previous stream immediately (portals often cap concurrent streams)
        pendingPreview?.let { ui.removeCallbacks(it) }
        val r = Runnable { loadPreview(ch) }
        pendingPreview = r
        ui.postDelayed(r, 450)
    }

    private fun loadPreview(ch: Portal.Channel) {
        val mine = ++seq
        b.epg.text = "▶  ${ch.name}\n\nLoading…"
        io.execute {
            if (mine != seq) return@execute // superseded by a newer selection
            val url = Portal.createLink(ch.cmd)
            if (mine != seq) return@execute
            val epg = Portal.shortEpg(ch.id)
            runOnUiThread {
                if (mine != seq || current != ch) return@runOnUiThread
                currentUrl = url
                currentUrlId = ch.id
                if (!url.isNullOrEmpty()) playPreview(url)
                b.epg.text = formatEpg(ch, epg, url)
            }
        }
    }

    private fun playPreview(url: String) {
        val vlc = libVlc ?: return
        val player = mp ?: return
        player.stop()
        val media = Media(vlc, Uri.parse(url))
        media.setHWDecoderEnabled(true, false)
        media.addOption(":network-caching=1500")
        media.addOption(":http-user-agent=" + Portal.UA)
        media.addOption(":http-reconnect")
        player.media = media
        media.release()
        player.play()
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
        LiveVlcActivity.liveChannels = all
        val idx = all.indexOfFirst { it.id == ch.id }
        val url = if (currentUrlId == ch.id) currentUrl else null
        if (!url.isNullOrEmpty()) {
            startActivity(playerIntent(ch, url, idx))
        } else {
            b.epg.text = "Opening ${ch.name}…"
            io.execute {
                val u = Portal.createLink(ch.cmd)
                runOnUiThread { if (!u.isNullOrEmpty()) startActivity(playerIntent(ch, u, idx)) }
            }
        }
    }

    private fun playerIntent(ch: Portal.Channel, url: String, idx: Int) =
        Intent(this, LiveVlcActivity::class.java)
            .putExtra("url", url)
            .putExtra("title", ch.name)
            .putExtra("chIndex", idx)

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
        val items = arrayOf("🔄   Refresh", "⚙   Settings", "📥   App updates", "ℹ️   About", "✖   Exit")
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> current?.let { loadPreview(it) }
                    1 -> startActivity(Intent(this, SettingsActivity::class.java))
                    2 -> startActivity(Intent(this, AppUpdatesActivity::class.java))
                    3 -> About.show(this)
                    4 -> finishAffinity()
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

    override fun onStart() {
        super.onStart()
        // (Re)attach the VLC surface and (re)start the preview. Without re-attaching, the
        // preview shows a blank surface after returning from the fullscreen player.
        if (!attached) { mp?.attachViews(b.preview, null, false, false); attached = true }
        current?.let { loadPreview(it) }
    }

    override fun onStop() {
        super.onStop()
        mp?.stop() // free the stream while in fullscreen / background
        if (attached) { mp?.detachViews(); attached = false }
    }

    override fun onDestroy() {
        super.onDestroy()
        pendingPreview?.let { ui.removeCallbacks(it) }
        mp?.let { it.stop(); if (attached) { it.detachViews(); attached = false }; it.release() }
        mp = null
        libVlc?.release()
        libVlc = null
    }
}
