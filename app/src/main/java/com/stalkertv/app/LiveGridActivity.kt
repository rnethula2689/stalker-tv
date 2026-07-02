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
    private var nowItem: Portal.EpgItem? = null   // current programme, for the NOW-card synopsis tap

    private var libVlc: LibVLC? = null
    private var mp: MediaPlayer? = null
    private var all = listOf<Portal.Channel>()          // current (sorted/filtered) view
    private var originalOrder = listOf<Portal.Channel>() // provider order, for the "Default" sort
    private var current: Portal.Channel? = null
    private var currentUrl: String? = null
    private var currentUrlId: String? = null
    private var pendingPreview: Runnable? = null
    private var seq = 0
    private var attached = false

    // Live filter + sort (client-side over the loaded channel list).
    private var liveFilter: String? = null   // one of liveFilters, or null
    private var liveLetter: String? = null   // active A–Z letter, or null
    private var liveSortKey = "num"
    private val liveFilters = listOf("Catch-up available", "HD", "Favourites", "Free", "Hide locked")
    private val liveSortLabels = linkedMapOf(
        "num" to "Channel number", "az" to "A–Z", "za" to "Z–A", "added" to "Recently added"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLivegridBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // preview is live video

        originalOrder = channels
        b.title.text = gridTitle
        all = baseChannels()

        val vlc = LibVLC(this, arrayListOf("--network-caching=${Configs.netCachingMs(this)}", "--http-reconnect", "--no-drop-late-frames", "--no-skip-frames"))
        libVlc = vlc
        mp = MediaPlayer(vlc).apply { // attached to the surface in onStart (re-attached on return from fullscreen)
            // Re-apply mute/volume once audio output exists (libVLC ignores setVolume before then).
            setEventListener { ev -> if (ev.type == MediaPlayer.Event.Playing) runOnUiThread { applyPreviewMute() } }
        }

        adapter = ChannelGridAdapter(
            all, { ch -> activate(ch) }, { ch -> select(ch) }, { ch -> favToast(ch) }, { ch -> openCatchup(ch) },
            { ch -> FavGroupPicker.show(this, "live", ch.id) { adapter.notifyDataSetChanged() } }
        )
        b.list.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        b.list.adapter = adapter

        b.upNextList.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        b.upNextList.adapter = epgAdapter
        b.upNextList.isFocusable = false // rows handle focus; the list itself shouldn't trap it

        b.previewFrame.setOnClickListener { openFullscreen() }
        b.nowCard.setOnClickListener { nowItem?.let { showEpgDetail(it) } } // NOW programme → full synopsis
        b.searchBtn.setOnClickListener { toggleSearch() }
        b.filterBtn.setOnClickListener { showFilterDialog() }
        b.sortBtn.setOnClickListener { showSortDialog() }
        updateLiveButtons()
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
            // The real guide lives in the day table (get_data_table); get_short_epg returns
            // "no guide" placeholders for many channels on this portal. Fall back to it only if empty.
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            val epg = Portal.epgForDate(ch.id, today).ifEmpty { Portal.shortEpg(ch.id) }
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
        media.setHWDecoderEnabled(Configs.hwDecode(this), false)
        media.addOption(":network-caching=${Configs.netCachingMs(this)}")
        media.addOption(":http-user-agent=" + Portal.UA)
        media.addOption(":http-reconnect")
        player.media = media
        media.release()
        player.play()
        applyPreviewMute()
    }

    /** Only one audio source: mute the preview while the pop-up (PiP) is running AND playing. Also
     *  honour the session mute/volume (PlayPrefs) so a mute set in the fullscreen player carries here. */
    private fun applyPreviewMute() {
        val muted = PlayPrefs.muted || (PipService.running && PipService.playing)
        val lvl = if (PlayPrefs.volPct in 0..100) PlayPrefs.volPct else 100
        try { mp?.setVolume(if (muted) 0 else lvl) } catch (_: Exception) {}
    }

    /** Transient one-line message in the EPG panel (loading / opening / no channels). */
    private fun showEpgStatus(msg: String) {
        b.nowBadge.visibility = View.GONE
        b.epgProgress.visibility = View.GONE
        b.nowTime.text = ""
        b.nowTitle.text = msg
        b.nowDesc.text = ""
        b.nowDesc.visibility = View.GONE
        nowItem = null
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
            b.nowTime.text = ""
            b.nowDesc.visibility = View.VISIBLE
            nowItem = null
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
        nowItem = now
        b.nowBadge.visibility = View.VISIBLE
        val range = if (now.startTs > 0) "${Portal.localTime(now.startTs)} – ${Portal.localTime(now.stopTs)}" else "${now.start} – ${now.end}"
        b.nowTitle.text = now.name
        // Single-line card: name + time + ⓘ (tap the card for the full synopsis).
        if (url.isNullOrEmpty()) {
            b.nowTime.text = "ⓘ"
            b.nowDesc.visibility = View.VISIBLE
            b.nowDesc.text = "(no stream — provider down, or connection limit reached)"
        } else {
            b.nowTime.text = "$range   ⓘ"
            b.nowDesc.visibility = View.GONE
        }
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
        if (q.trim().isNotEmpty()) liveLetter = null // typing a search clears the A–Z letter
        applyView(resetFocus = false)
    }

    /** The filtered + sorted channel set (before any search / A–Z narrowing). */
    private fun baseChannels(): List<Portal.Channel> = sortedChannels(originalOrder.filter { liveMatches(it) })

    private fun sortedChannels(list: List<Portal.Channel>): List<Portal.Channel> = when (liveSortKey) {
        "az" -> list.sortedBy { it.name.trim().lowercase() }
        "za" -> list.sortedByDescending { it.name.trim().lowercase() }
        "added" -> list.sortedByDescending { it.added }
        else -> list.sortedBy { it.number.toIntOrNull() ?: Int.MAX_VALUE } // "num" = channel number
    }

    private fun liveMatches(ch: Portal.Channel): Boolean = when (liveFilter) {
        "Catch-up available" -> ch.archiveDays > 0
        "HD" -> ch.hd
        "Favourites" -> Configs.isFavorite(this, ch.id)
        "Free" -> ch.open
        "Hide locked" -> !ch.locked && !ch.censored
        else -> true
    }

    /** Recompute the view: filter → sort → (active search OR A–Z letter), then show it. */
    private fun applyView(resetFocus: Boolean = true) {
        all = baseChannels()
        val q = b.search.text?.toString()?.trim().orEmpty()
        val shown = when {
            q.isNotEmpty() -> all.filter { it.name.contains(q, ignoreCase = true) }
            liveLetter != null -> all.filter { it.name.trimStart().startsWith(liveLetter!!, ignoreCase = true) }
            else -> all
        }
        adapter.submit(shown)
        if (resetFocus) {
            b.list.scrollToPosition(0)
            b.list.post { b.list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
        }
    }

    /** Filter: pick ONE attribute (boolean). Works alongside search + A–Z + sort. */
    private fun showFilterDialog() {
        val items = (if (liveFilter != null) listOf("✖  Clear filter") else emptyList()) + liveFilters
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Filter channels")
            .setItems(items.toTypedArray()) { _, w ->
                val pick = items[w]
                liveFilter = if (pick.startsWith("✖")) null else pick
                updateLiveButtons(); applyView()
            }
            .show()
    }

    /** Sort: one dialog with every option. */
    private fun showSortDialog() {
        val keys = liveSortLabels.keys.toList()
        val labels = liveSortLabels.values.toTypedArray()
        val cur = keys.indexOf(liveSortKey).coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Sort channels")
            .setSingleChoiceItems(labels, cur) { d, w ->
                liveSortKey = keys[w]; d.dismiss(); updateLiveButtons(); applyView()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateLiveButtons() {
        b.sortBtn.text = when (liveSortKey) {
            "az" -> "⇅ A–Z"
            "za" -> "⇅ Z–A"
            "added" -> "⇅ New"
            else -> "⇅ #"
        }
        b.filterBtn.text = if (liveFilter != null) "⛃ •" else "⛃"
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
        liveLetter = letter
        if (b.search.text.isNotEmpty()) b.search.setText("") // triggers filter() → applyView()
        applyView()
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

    /** Open Multi-view: pane 1 = the previewed channel; only the active profile's categories are offered. */
    private fun openMultiView() {
        MultiViewActivity.channels = ChannelsActivity.allChannelsCatalog()
            .filter { ContentProfiles.liveCatVisible(this, it.genreId) }.ifEmpty { all }
        MultiViewActivity.genres = ChannelsActivity.catGenres().filter { ContentProfiles.liveCatVisible(this, it.id) }
        MultiViewActivity.startChannels = listOfNotNull(current)
        startActivity(Intent(this, MultiViewActivity::class.java))
    }

    private var menuDialog: androidx.appcompat.app.AlertDialog? = null
    private fun showMenu() {
        if (menuDialog?.isShowing == true) { menuDialog?.dismiss(); return }
        val items = arrayOf("⊞   Multi-view", "🔄   Refresh", "⚙   Settings", "📥   App updates", "ℹ️   About", "✖   Exit")
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openMultiView()
                    1 -> refreshGrid()
                    2 -> startActivity(Intent(this, SettingsActivity::class.java))
                    3 -> startActivity(Intent(this, AppUpdatesActivity::class.java))
                    4 -> About.show(this)
                    5 -> finishAffinity()
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
            .setTitle("Exit Vibe TV?")
            .setPositiveButton("Yes") { _, _ -> finishAffinity() }
            .setNegativeButton("No", null)
            .show()
    }

    private var fastScrolled = false
    private fun focusInList(v: android.view.View?): Boolean {
        var p = v?.parent
        while (p != null) { if (p === b.list) return true; p = (p as? android.view.View)?.parent }
        return false
    }

    /** Hold Up ~4s → jump to the top of the channel list; hold Down ~4s → jump to the bottom. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val kc = event.keyCode
        if (kc == android.view.KeyEvent.KEYCODE_DPAD_UP || kc == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
            if (event.action == android.view.KeyEvent.ACTION_UP) fastScrolled = false
            else if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount > 0 && !fastScrolled &&
                (event.eventTime - event.downTime) >= 4000L && focusInList(currentFocus)
            ) {
                if (kc == android.view.KeyEvent.KEYCODE_DPAD_UP && b.list.canScrollVertically(-1)) {
                    fastScrolled = true
                    b.list.scrollToPosition(0)
                    b.list.post { b.list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
                    return true
                }
                if (kc == android.view.KeyEvent.KEYCODE_DPAD_DOWN && b.list.canScrollVertically(1)) {
                    fastScrolled = true
                    val last = (b.list.adapter?.itemCount ?: 1) - 1
                    b.list.scrollToPosition(last)
                    b.list.post { b.list.findViewHolderForAdapterPosition(last)?.itemView?.requestFocus() }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
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

    override fun onBackPressed() {
        // Back first clears an open search / active filter / A–Z letter, only then leaves the grid.
        if (b.searchRow.visibility == View.VISIBLE) { b.search.setText(""); b.searchRow.visibility = View.GONE; return }
        if (liveFilter != null || liveLetter != null) {
            liveFilter = null; liveLetter = null; updateLiveButtons(); applyView(); return
        }
        super.onBackPressed()
    }

    override fun onStart() {
        super.onStart()
        // (Re)attach the VLC surface and (re)start the preview. Without re-attaching, the
        // preview shows a blank surface after returning from the fullscreen player.
        if (!attached) { mp?.attachViews(b.preview, null, false, false); attached = true }
        // React to pop-up play/pause so we mute/unmute this preview accordingly (single audio source).
        PipService.onStateChanged = { runOnUiThread { applyPreviewMute() } }
        current?.let { loadPreview(it) }
    }

    override fun onStop() {
        super.onStop()
        PipService.onStateChanged = null
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
