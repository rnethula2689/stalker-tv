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
    private val epgAdapter = EpgPreviewAdapter()

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
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // preview is live video

        all = channels
        b.title.text = gridTitle

        val vlc = LibVLC(this, arrayListOf("--network-caching=1500", "--http-reconnect", "--no-drop-late-frames", "--no-skip-frames"))
        libVlc = vlc
        mp = MediaPlayer(vlc) // attached to the surface in onStart (and re-attached on return from fullscreen)

        adapter = ChannelGridAdapter(all, { ch -> activate(ch) }, { ch -> select(ch) }, { ch -> favToast(ch) }, { ch -> openCatchup(ch) })
        b.list.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        b.list.adapter = adapter

        b.upNextList.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        b.upNextList.adapter = epgAdapter
        b.upNextList.isFocusable = false // rows handle focus; the list itself shouldn't trap it

        b.previewFrame.setOnClickListener { openFullscreen() }
        b.searchBtn.setOnClickListener { toggleSearch() }
        b.menuBtn.setOnClickListener { showMenu() }
        if (gridTitle == "Favourites") {
            b.clearFavBtn.visibility = View.VISIBLE
            b.clearFavBtn.setOnClickListener { confirmClearFavorites() }
        }
        b.clearBtn.setOnClickListener { b.search.setText(""); b.search.requestFocus() }
        b.search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = filter(s?.toString() ?: "")
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
        })
        buildAzBar()

        if (all.isNotEmpty()) {
            showEpgStatus("Loading…")
            current = all[0] // onStart starts the preview (and restarts it when returning from fullscreen)
            b.list.post { b.list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
        } else {
            showEpgStatus("No channels.")
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
        showEpgStatus("Loading ${ch.name}…")
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
                renderEpg(ch, epg, url)
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

    /** Transient one-line message in the EPG panel (loading / opening / no channels). */
    private fun showEpgStatus(msg: String) {
        b.nowBadge.visibility = View.GONE
        b.epgProgress.visibility = View.GONE
        b.nowTime.text = ""
        b.nowTitle.text = msg
        b.nowDesc.text = ""
        b.upNextHeader.visibility = View.GONE
        epgAdapter.submit(emptyList())
    }

    /** Render the guide for [ch]: a NOW card (title, time, live progress, description) + an Up Next list. */
    private fun renderEpg(ch: Portal.Channel, epg: List<Portal.EpgItem>, url: String?) {
        if (epg.isEmpty()) {
            b.nowBadge.visibility = View.GONE
            b.epgProgress.visibility = View.GONE
            b.nowTime.text = ""
            b.nowTitle.text = ch.name
            b.nowDesc.text = if (url.isNullOrEmpty())
                "No stream — the provider may be down, or another device is using your connection."
            else "No program guide for this channel."
            b.upNextHeader.visibility = View.GONE
            epgAdapter.submit(emptyList())
            return
        }
        val nowSec = System.currentTimeMillis() / 1000
        // Pick the programme actually on now by absolute timestamps (not the portal's ordering/timezone).
        var nowIdx = epg.indexOfFirst { it.startTs in 1..nowSec && (it.stopTs == 0L || nowSec < it.stopTs) }
        if (nowIdx < 0) nowIdx = epg.indexOfLast { it.startTs in 1..nowSec }
        if (nowIdx < 0) nowIdx = 0
        val now = epg[nowIdx]
        b.nowBadge.visibility = View.VISIBLE
        b.nowTime.text = if (now.startTs > 0) "${Portal.localTime(now.startTs)} – ${Portal.localTime(now.stopTs)}" else "${now.start} – ${now.end}"
        b.nowTitle.text = now.name
        b.nowDesc.text = if (url.isNullOrEmpty())
            "(no stream — provider down, or connection limit reached)"
        else now.descr
        if (now.startTs > 0 && now.stopTs > now.startTs) {
            val pct = ((nowSec - now.startTs) * 100 / (now.stopTs - now.startTs)).coerceIn(0L, 100L)
            b.epgProgress.visibility = View.VISIBLE
            b.epgProgress.progress = pct.toInt()
        } else {
            b.epgProgress.visibility = View.GONE
        }
        val upcoming = if (nowIdx + 1 < epg.size) epg.subList(nowIdx + 1, epg.size) else emptyList()
        b.upNextHeader.visibility = if (upcoming.isEmpty()) View.GONE else View.VISIBLE
        epgAdapter.submit(upcoming)
        b.upNextList.scrollToPosition(0)
    }

    private fun durLabel(e: Portal.EpgItem): String {
        if (e.startTs > 0 && e.stopTs > e.startTs) {
            val mins = ((e.stopTs - e.startTs) / 60).toInt()
            return if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"
        }
        return ""
    }

    /** Tap an upcoming programme → show its full details (time + synopsis). */
    private fun showEpgDetail(e: Portal.EpgItem) {
        val time = if (e.startTs > 0) "${Portal.localTime(e.startTs)} – ${Portal.localTime(e.stopTs)}"
                   else "${e.start} – ${e.end}"
        val msg = if (e.descr.isNotBlank()) "$time\n\n${e.descr}" else time
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(e.name)
            .setMessage(msg)
            .setPositiveButton("Close", null)
            .show()
    }

    private inner class EpgPreviewAdapter :
        androidx.recyclerview.widget.RecyclerView.Adapter<EpgPreviewAdapter.VH>() {
        private var items = listOf<Portal.EpgItem>()
        fun submit(l: List<Portal.EpgItem>) { items = l; notifyDataSetChanged() }
        inner class VH(val v: com.stalkertv.app.databinding.ItemEpgPreviewBinding) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(v.root)
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) =
            VH(com.stalkertv.app.databinding.ItemEpgPreviewBinding.inflate(layoutInflater, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = items[position]
            holder.v.time.text = if (e.startTs > 0) Portal.localTime(e.startTs) else e.start
            holder.v.name.text = e.name
            holder.v.dur.text = durLabel(e)
            holder.v.root.setOnClickListener { showEpgDetail(e) }
        }
    }

    private fun openFullscreen() {
        val ch = current ?: return
        LiveVlcActivity.liveChannels = all
        val idx = all.indexOfFirst { it.id == ch.id }
        val url = if (currentUrlId == ch.id) currentUrl else null
        if (!url.isNullOrEmpty()) {
            startActivity(playerIntent(ch, url, idx))
        } else {
            showEpgStatus("Opening ${ch.name}…")
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

    private var progressAnim: android.animation.ObjectAnimator? = null

    private fun showLoading(msg: String) {
        progressAnim?.cancel()
        b.loadingBar.progress = 0
        b.loadingPct.text = "0%"
        b.loadingMsg.text = msg
        b.loadingOverlay.visibility = View.VISIBLE
    }

    private fun setProgress(pct: Int, msg: String, durationMs: Long = 600) {
        b.loadingMsg.text = msg
        progressAnim?.cancel()
        val anim = android.animation.ObjectAnimator.ofInt(b.loadingBar, "progress", b.loadingBar.progress, pct)
        anim.duration = durationMs
        anim.interpolator = android.view.animation.DecelerateInterpolator()
        anim.addUpdateListener { va -> b.loadingPct.text = "${va.animatedValue}%" }
        progressAnim = anim
        anim.start()
    }

    private fun hideLoading() {
        progressAnim?.cancel()
        b.loadingOverlay.visibility = View.GONE
    }

    /** Refresh: re-establish the portal session (recovers a stuck preview) and stay on this screen. */
    private fun refreshGrid() {
        showLoading("Reconnecting…")
        setProgress(55, "Reconnecting…", 1600)
        io.execute {
            val err = Portal.connect() // fresh session — recovers stuck / expired streams
            runOnUiThread {
                setProgress(100, if (err == null) "Reconnected ✓" else "Reconnected", 350)
                b.loadingOverlay.postDelayed({
                    hideLoading()
                    current?.let { loadPreview(it) } // restart the preview right where the user is
                }, 450)
            }
        }
    }

    private fun confirmClearFavorites() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Clear all favourites?")
            .setMessage("This removes all your favourite channels.")
            .setPositiveButton("Clear all") { _, _ ->
                Configs.clearFavorites(this)
                android.widget.Toast.makeText(this, "Favourites cleared", android.widget.Toast.LENGTH_SHORT).show()
                finish() // the Favourites folder is now empty
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openCatchup(ch: Portal.Channel) {
        startActivity(
            Intent(this, CatchupActivity::class.java)
                .putExtra("chId", ch.id).putExtra("chName", ch.name)
                .putExtra("chCmd", ch.cmd).putExtra("archiveDays", ch.archiveDays)
        )
    }

    private fun favToast(ch: Portal.Channel) {
        val fav = Configs.isFavorite(this, ch.id)
        android.widget.Toast.makeText(
            this,
            if (fav) "★  Added “${ch.name}” to Favourites" else "Removed “${ch.name}” from Favourites",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private var menuDialog: androidx.appcompat.app.AlertDialog? = null
    private fun showMenu() {
        if (menuDialog?.isShowing == true) { menuDialog?.dismiss(); return }
        val items = arrayOf("🔄   Refresh", "⚙   Settings", "📥   App updates", "ℹ️   About", "✖   Exit")
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> refreshGrid()
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
