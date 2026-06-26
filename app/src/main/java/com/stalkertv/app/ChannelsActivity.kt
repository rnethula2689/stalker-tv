package com.stalkertv.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.stalkertv.app.databinding.ActivityChannelsBinding
import java.util.concurrent.Executors

class ChannelsActivity : AppCompatActivity() {
    private val io = Executors.newSingleThreadExecutor()
    // Separate executor for opening streams, so playback never queues behind a slow search/list load.
    private val playIo = Executors.newSingleThreadExecutor()
    private lateinit var b: ActivityChannelsBinding
    private val adapter = RowAdapter()

    /** Optional favourite toggle for a row (channels / movies). Null = not favouritable (e.g. folders). */
    class FavInfo(val isFav: () -> Boolean, val toggle: () -> Boolean, val onAdded: (() -> Unit)? = null)
    data class Row(val label: String, val iconUrl: String?, val sortKey: String = "", val fav: FavInfo? = null, val isHeader: Boolean = false, val catchup: (() -> Unit)? = null, val action: () -> Unit)
    enum class SearchKind { LOCAL, GLOBAL, CHANNELS, VOD_ALL, VOD_CATEGORY }
    data class Page(
        val title: String,
        val rows: List<Row>,
        val kind: SearchKind = SearchKind.LOCAL,
        val scopeId: String? = null,
        val scopeChannels: List<Portal.Channel>? = null,
        val rebuild: (() -> Unit)? = null // pull-to-refresh / return rebuilds this screen in place
    )

    private val backStack = ArrayDeque<Page>()
    private val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingSearch: Runnable? = null
    private var searchSeq = 0

    private var allChannels = listOf<Portal.Channel>()
    private var genres = listOf<Portal.Genre>()
    private var byGenre = mapOf<String, List<Portal.Channel>>()
    private var vodCats = listOf<Portal.VodCat>() // cached so Movies rebuilds in-memory
    private var welcomeShown = false
    private var updateChecked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityChannelsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.swipe.setOnRefreshListener {
            val r = backStack.lastOrNull()?.rebuild
            if (r != null) { backStack.removeLast(); r() }
            b.swipe.isRefreshing = false
        }
        // In a Favourites screen, un-favouriting should drop the row + update the count right away.
        adapter.onFavToggled = {
            val top = backStack.lastOrNull()
            if (top != null && top.title.startsWith("Favourites") && top.rebuild != null) {
                backStack.removeLast(); top.rebuild!!.invoke()
            }
        }

        b.search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = filter(s?.toString() ?: "")
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
        })
        b.clearBtn.setOnClickListener { b.search.setText(""); b.search.requestFocus() }
        buildAzBar()

        b.searchBtn.setOnClickListener { toggleSearch() }
        b.reloadBtn.setOnClickListener { connectAndLoad(true) } // true = real portal reconnect, not a cache rebuild
        b.sortBtn.setOnClickListener { cycleVodSort() }
        b.menuBtn.setOnClickListener { showMenu() }

        registerForegroundWatch()
        maybeRequestNotifications()
        connectAndLoad()
        checkForUpdate()
    }

    /** Ask for notification permission (Android 13+) so background download progress is visible. */
    private fun maybeRequestNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            try { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 77) } catch (_: Exception) {}
        }
    }

    private var lifecycleCb: android.app.Application.ActivityLifecycleCallbacks? = null

    /** Re-lock restricted folders whenever the whole app leaves the foreground (exit / Home / background). */
    private fun registerForegroundWatch() {
        val cb = object : android.app.Application.ActivityLifecycleCallbacks {
            var started = 0
            override fun onActivityStarted(activity: android.app.Activity) { started++ }
            override fun onActivityStopped(activity: android.app.Activity) {
                started--
                if (started <= 0) parentalUnlocked = false // app moved to background → re-lock
            }
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        }
        lifecycleCb = cb
        application.registerActivityLifecycleCallbacks(cb)
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleCb?.let { application.unregisterActivityLifecycleCallbacks(it) }
    }

    private fun checkForUpdate() {
        io.execute {
            val v = Updater.latest() ?: return@execute
            if (v.first > BuildConfig.VERSION_CODE) {
                runOnUiThread {
                    if (updateChecked) return@runOnUiThread
                    updateChecked = true
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Update available")
                        .setMessage("Version ${v.second} is available.\nDownload and install it now?")
                        .setPositiveButton("Download now") { _, _ -> startUpdate() }
                        .setNegativeButton("Close", null)
                        .show()
                }
            }
        }
    }

    private fun startUpdate() = AppUpdate.install(this)

    private fun showWelcome() {
        if (welcomeShown) return
        welcomeShown = true
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Welcome to Stalker TV")
            .setMessage(
                "To start watching, add your IPTV provider:\n\n" +
                    "1. Open Settings (⚙ top-right, or ⋮ menu → Settings).\n" +
                    "2. Enter your Portal URL, MAC address, and Serial Number.\n" +
                    "3. Tap Submit — channels and movies load automatically.\n\n" +
                    "You get these details from your IPTV provider."
            )
            .setPositiveButton("Open Settings") { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)) }
            .setNegativeButton("Later", null)
            .show()
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
        val page = backStack.lastOrNull() ?: return
        if (letter == null) {
            adapter.submit(page.rows)
            return
        }
        // Movie folders: ask the portal for every title starting with this letter (complete, not just loaded).
        if (page.kind == SearchKind.VOD_CATEGORY && page.scopeId != null) {
            val cat = page.scopeId
            b.status.visibility = View.VISIBLE
            b.status.text = "Loading “$letter”…"
            io.execute {
                val items = Portal.vodByLetter(cat, letter)
                runOnUiThread {
                    if (items.isEmpty()) {
                        b.status.visibility = View.VISIBLE
                        b.status.text = "No titles starting with “$letter”."
                    } else {
                        b.status.visibility = View.GONE
                    }
                    adapter.submit(items.map { vodItemRow(it) })
                }
            }
        } else {
            // Channels / genres / categories are all in memory — filter locally.
            adapter.submit(page.rows.filter { it.sortKey.trimStart().startsWith(letter, ignoreCase = true) })
        }
    }

    private var menuDialog: androidx.appcompat.app.AlertDialog? = null
    private fun showMenu() {
        if (menuDialog?.isShowing == true) { menuDialog?.dismiss(); return }
        val items = arrayOf("🔄   Refresh portal", "⚙   Settings", "🔒   Parental PIN", "📥   App updates", "ℹ️   About", "✖   Exit")
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> connectAndLoad(true)
                    1 -> startActivity(Intent(this, SettingsActivity::class.java))
                    2 -> changePin()
                    3 -> startActivity(Intent(this, AppUpdatesActivity::class.java))
                    4 -> About.show(this)
                    5 -> finishAffinity()
                }
            }
            .setOnDismissListener { menuDialog = null }
            .create()
        // Pressing the menu/hamburger key again closes it (Back also closes by default).
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

    private fun toggleSearch() {
        if (b.searchRow.visibility == View.VISIBLE) {
            b.search.setText("")
            b.searchRow.visibility = View.GONE
        } else {
            b.searchRow.visibility = View.VISIBLE
            b.search.requestFocus()
            (getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showSoftInput(b.search, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun openLiveGrid(list: List<Portal.Channel>, title: String) {
        LiveGridActivity.channels = list
        LiveGridActivity.gridTitle = title
        startActivity(Intent(this, LiveGridActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        if (Configs.dirty) {
            Configs.dirty = false
            connectAndLoad(true)
            return
        }
        // Returning from a player/child → rebuild the current screen so counts and
        // Continue-Watching progress are current (all in-memory; no portal reconnect).
        val top = backStack.lastOrNull()
        if (top?.rebuild != null) { backStack.removeLast(); top.rebuild!!.invoke() }
    }

    private var progressAnim: android.animation.ObjectAnimator? = null

    private fun showLoading(msg: String) {
        progressAnim?.cancel()
        b.loadingBar.progress = 0
        b.loadingPct.text = "0%"
        b.loadingMsg.text = msg
        b.loadingOverlay.visibility = View.VISIBLE
    }

    /** Animate the bar and the counting % toward [pct] while showing [msg]. */
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

    private val netMsg =
        "No network connection — you can only watch offline Downloads. Please check your Wi-Fi."

    private fun isOnline(): Boolean = try {
        val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION") (cm.activeNetworkInfo?.isConnected == true)
        }
    } catch (_: Exception) { false }

    /** Show the network message briefly on the splash, then land on an offline home. */
    private fun goOffline() {
        showLoading(netMsg)
        setProgress(100, netMsg, 400)
        b.loadingMsg.text = netMsg
        b.loadingOverlay.postDelayed({ showOfflineHome() }, 1500)
    }

    /** Home shown when there's no portal connection: Downloads (works offline) + Retry. */
    private fun showOfflineHome() {
        hideLoading()
        backStack.clear()
        b.title.text = "Stalker TV"
        b.status.visibility = View.VISIBLE
        b.status.text = "📡  $netMsg"
        push(
            Page(
                "Stalker TV",
                listOf(
                    Row("⬇   Downloads (offline)", null) { startActivity(Intent(this, DownloadsActivity::class.java)) },
                    Row("🔄   Retry connection", null) { connectAndLoad(true) }
                ),
                kind = SearchKind.LOCAL
            )
        )
    }

    // Cached portal data, kept across activity recreation so returning to the app (e.g. after a
    // movie) reuses it instead of reconnecting and showing the loading splash again.
    companion object {
        private var cachedSig: String? = null
        private var cachedChannels: List<Portal.Channel> = emptyList()
        private var cachedGenres: List<Portal.Genre> = emptyList()
        private var cachedVodCats: List<Portal.VodCat> = emptyList()
    }

    /** Read the active provider, connect in the background, then show the home menu. */
    private fun connectAndLoad(force: Boolean = false) {
        parentalUnlocked = false // a fresh portal load re-locks restricted folders
        val acct = Configs.active(this)
        b.title.text = "Stalker TV"
        b.search.setText("")
        backStack.clear()
        adapter.submit(emptyList())
        b.status.visibility = View.GONE
        if (acct == null) {
            hideLoading()
            b.status.visibility = View.VISIBLE
            b.status.text = "Welcome! Open Settings (⚙ top-right) to add your IPTV provider."
            showWelcome()
            return
        }
        Portal.portalUrl = acct.portal
        Portal.mac = acct.mac
        Portal.sn = acct.sn
        // Reuse cached data (same provider, app still in memory) → no reconnect, no splash.
        if (!force && cachedSig == acct.sig() && cachedChannels.isNotEmpty()) {
            allChannels = cachedChannels
            genres = cachedGenres
            byGenre = cachedChannels.groupBy { it.genreId }
            vodCats = cachedVodCats
            hideLoading()
            showHome()
            return
        }
        vodCats = emptyList()
        cachedVodCats = emptyList()
        if (!isOnline()) { goOffline(); return } // no network → straight to offline home
        showLoading("Connecting to portal…")
        setProgress(40, "Connecting to portal…", 2200) // creep up while the handshake runs
        io.execute {
            val err = Portal.connect() // resets the session and re-handshakes → a true fresh load
            if (err != null) {
                runOnUiThread { goOffline() } // can't reach the portal → offline home with Downloads
                return@execute
            }
            runOnUiThread { setProgress(65, "Authenticated ✓   Loading channels…", 700) }
            val ch = Portal.liveChannels()
            runOnUiThread { setProgress(88, "Loading categories…", 700) }
            val g = Portal.liveGenres()
            runOnUiThread {
                allChannels = ch
                genres = g
                byGenre = ch.groupBy { it.genreId }
                cachedSig = acct.sig(); cachedChannels = ch; cachedGenres = g // cache for next launch
                if (ch.isEmpty()) {
                    hideLoading()
                    b.status.visibility = View.VISIBLE
                    b.status.text = "No channels returned. Check the configuration (⚙)."
                } else {
                    setProgress(100, "Ready", 350)
                    b.loadingOverlay.postDelayed({ hideLoading(); showHome() }, 450)
                }
            }
        }
    }

    // ---- navigation ----
    private fun push(page: Page) {
        backStack.addLast(page)
        display(page)
    }

    private fun display(page: Page) {
        b.title.text = page.title
        b.search.hint = when (page.kind) {
            SearchKind.GLOBAL -> "Search channels, movies & shows…"
            SearchKind.CHANNELS -> "Search channels…"
            SearchKind.VOD_ALL -> "Search movies & shows…"
            SearchKind.VOD_CATEGORY -> "Search this folder…"
            SearchKind.LOCAL -> "Filter…"
        }
        adapter.submit(page.rows)
        // The ⇅ sort button only applies inside a movie category.
        b.sortBtn.visibility = if (page.kind == SearchKind.VOD_CATEGORY) View.VISIBLE else View.GONE
        updateSortBtn()
        if (b.search.text.isNotEmpty()) b.search.setText("")
        b.searchRow.visibility = View.GONE
        b.list.scrollToPosition(0)
        // Defer until the rebuilt rows are laid out; requesting focus on a not-yet-populated
        // list silently fails and focus falls back to the first toolbar icon (the 🔍 button).
        b.list.post { if (!b.list.hasFocus()) b.list.requestFocus() }
    }

    override fun onBackPressed() {
        if (b.searchRow.visibility == View.VISIBLE) {
            b.search.setText("")
            b.searchRow.visibility = View.GONE
            return
        }
        if (backStack.size > 1) {
            backStack.removeLast()
            val prev = backStack.last()
            // Rebuild the screen we're returning to (refreshes favourite counts on TV, no pull needed).
            if (prev.rebuild != null) { backStack.removeLast(); prev.rebuild!!.invoke() }
            else display(prev)
        } else {
            super.onBackPressed()
        }
    }

    private fun filter(q: String) {
        val page = backStack.lastOrNull() ?: return
        when (page.kind) {
            SearchKind.LOCAL -> {
                val query = q.trim().lowercase()
                adapter.submit(if (query.isEmpty()) page.rows else page.rows.filter { it.label.lowercase().contains(query) })
            }
            SearchKind.CHANNELS -> channelSearch(q, page.scopeChannels ?: allChannels, page)
            SearchKind.GLOBAL -> globalSearch(q, page)
            SearchKind.VOD_ALL -> vodSearchUi(q, null, page)
            SearchKind.VOD_CATEGORY -> vodSearchUi(q, page.scopeId, page)
        }
    }

    /** Favourite handle for a live channel: toggles the id-set, clears its group on removal,
     *  and prompts for a group when newly added. */
    private fun liveFav(ch: Portal.Channel) = FavInfo(
        isFav = { Configs.isFavorite(this, ch.id) },
        toggle = {
            val now = Configs.toggleFavorite(this, ch.id)
            if (!now) FavGroups.setGroup(this, "live", ch.id, null)
            now
        },
        onAdded = { FavGroupPicker.show(this, "live", ch.id) { afterFavChange() } }
    )

    /** Favourite handle for a VOD entry (movie/series/season/episode). */
    private fun vodFav(kind: String, e: Favorites.Entry) = FavInfo(
        isFav = { Favorites.isFav(this, kind, e.id) },
        toggle = {
            val now = Favorites.toggle(this, e)
            if (!now) FavGroups.setGroup(this, "vod", e.id, null)
            now
        },
        onAdded = { FavGroupPicker.show(this, "vod", e.id) { afterFavChange() } }
    )

    /** Rebuild the current screen after a favourite/group change so counts/rows refresh. */
    private fun afterFavChange() {
        val top = backStack.lastOrNull()
        if (top?.rebuild != null) { backStack.removeLast(); top.rebuild!!.invoke() }
    }

    private fun channelRow(ch: Portal.Channel): Row {
        val label = "📺  " + (if (ch.number.isNotEmpty()) "${ch.number}. " else "") + ch.name
        val fav = liveFav(ch)
        // Clock only on channels with an actual archive (tv_archive_duration > 0).
        val catchup: (() -> Unit)? = if (ch.archiveDays > 0) ({ openCatchup(ch) }) else null
        return Row(label, ch.logoUrl, sortKey = ch.name, fav = fav, catchup = catchup) { playChannel(ch) }
    }

    private fun openCatchup(ch: Portal.Channel) {
        startActivity(
            Intent(this, CatchupActivity::class.java)
                .putExtra("chId", ch.id).putExtra("chName", ch.name)
                .putExtra("chCmd", ch.cmd).putExtra("archiveDays", ch.archiveDays)
        )
    }

    /** Channels always open in the live (VLC) player — same as the Live TV grid, no seek controls. */
    private fun playChannel(ch: Portal.Channel) {
        b.status.visibility = View.VISIBLE
        b.status.text = "Opening ${ch.name}…"
        playIo.execute {
            val url = Portal.createLink(ch.cmd)
            runOnUiThread {
                if (url.isNullOrEmpty()) {
                    b.status.visibility = View.VISIBLE
                    val why = Portal.lastError
                    b.status.text = if (why == "nothing_to_play")
                        "“${ch.name}” — no stream. The provider may be down, or your connection limit is reached (another device is already streaming)."
                    else "Couldn't open “${ch.name}” — $why"
                } else {
                    b.status.visibility = View.GONE
                    LiveVlcActivity.liveChannels = allChannels
                    val idx = allChannels.indexOfFirst { it.id == ch.id }
                    startActivity(
                        Intent(this, LiveVlcActivity::class.java)
                            .putExtra("url", url)
                            .putExtra("title", ch.name)
                            .putExtra("chIndex", idx)
                    )
                }
            }
        }
    }

    private fun vodItemRow(v: Portal.VodItem): Row {
        val label = (if (v.isSeries) "📁  " else "🎬  ") + v.name
        val fav = if (v.isSeries)
            vodFav("series", Favorites.Entry("series", v.id, v.name, v.posterUrl, "series|${v.id}"))
        else
            vodFav("movie", Favorites.Entry("movie", v.id, v.name, v.posterUrl, "vod|${v.id}|${v.cmd}"))
        return Row(label, v.posterUrl, sortKey = v.name, fav = fav) {
            if (v.isSeries) showSeasons(v)
            else mediaActions(v.name, v.posterUrl, "movie_${v.id}", "vod|${v.id}|${v.cmd}")
        }
    }

    /** Movie / episode action sheet: play now, or download for offline. [source] lets a download resume later. */
    private fun mediaActions(
        title: String, poster: String?, id: String, source: String,
        playlist: List<PlayerActivity.PlaylistItem> = emptyList(), plIndex: Int = -1
    ) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(arrayOf("▶  Play", "🕒  Watch later", "⬇  Download for offline")) { _, w ->
                when (w) {
                    0 -> play(title, id, poster, source, playlist, plIndex)
                    1 -> {
                        val kind = if (source.startsWith("ep|")) "episode" else "movie"
                        val added = WatchLater.add(applicationContext, kind, id, title, poster ?: "", source)
                        android.widget.Toast.makeText(this, if (added) "Added to Watch Later" else "Already in Watch Later", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        if (Downloads.has(applicationContext, id)) {
                            android.widget.Toast.makeText(this, "Already saved (or downloading). See Downloads.", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            Downloads.enqueue(applicationContext, id, title, poster ?: "", source)
                            android.widget.Toast.makeText(this, "Download started — see ⬇ Downloads.", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .show()
    }

    /** In-memory channel search (Live TV scope). */
    private fun channelSearch(q: String, channels: List<Portal.Channel>, page: Page) {
        val query = q.trim()
        if (query.isEmpty()) { b.status.visibility = View.GONE; adapter.submit(page.rows); return }
        val rows = channels.asSequence()
            .filter { it.name.contains(query, ignoreCase = true) }
            .take(500).map { channelRow(it) }.toList()
        b.status.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        if (rows.isEmpty()) b.status.text = "No channels match “$query”."
        adapter.submit(rows)
    }

    /** Global search: channels (instant) + all movies/series (portal). */
    private fun globalSearch(q: String, page: Page) {
        val query = q.trim()
        pendingSearch?.let { searchHandler.removeCallbacks(it) }
        searchSeq++
        if (query.isEmpty()) { b.status.visibility = View.GONE; adapter.submit(page.rows); return }
        val chRows = allChannels.asSequence().filter { it.name.contains(query, ignoreCase = true) }
            .take(150).map { channelRow(it) }.toList()
        adapter.submit(chRows)
        if (query.length < 2) { b.status.visibility = View.GONE; return }
        b.status.visibility = View.VISIBLE
        b.status.text = "Searching movies & shows…"
        val seq = searchSeq
        val task = Runnable {
            io.execute {
                val vod = Portal.vodSearch(query)
                runOnUiThread {
                    if (seq != searchSeq) return@runOnUiThread
                    b.status.visibility = View.GONE
                    adapter.submit(chRows + vod.map { vodItemRow(it) })
                }
            }
        }
        pendingSearch = task
        searchHandler.postDelayed(task, 450)
    }

    /** VOD search — all categories if catId is null, else scoped to that folder. */
    private fun vodSearchUi(q: String, catId: String?, page: Page) {
        val query = q.trim()
        pendingSearch?.let { searchHandler.removeCallbacks(it) }
        searchSeq++
        if (query.isEmpty()) { b.status.visibility = View.GONE; adapter.submit(page.rows); return }
        if (query.length < 2) return
        b.status.visibility = View.VISIBLE
        b.status.text = "Searching…"
        val seq = searchSeq
        val task = Runnable {
            io.execute {
                val vod = if (catId == null) Portal.vodSearch(query) else Portal.vodSearchInCategory(catId, query)
                runOnUiThread {
                    if (seq != searchSeq) return@runOnUiThread
                    val rows = vod.map { vodItemRow(it) }
                    b.status.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                    if (rows.isEmpty()) b.status.text = "No results for “$query”."
                    adapter.submit(rows)
                }
            }
        }
        pendingSearch = task
        searchHandler.postDelayed(task, 450)
    }

    // ---- screens ----
    private fun showHome() {
        backStack.clear()
        val rows = ArrayList<Row>()
        val cw = Resume.all(this)
        if (cw.isNotEmpty())
            rows.add(Row("▶   Continue Watching  (${cw.size})", null) { showContinueWatching() })
        val wl = WatchLater.all(this)
        if (wl.isNotEmpty())
            rows.add(Row("🕒   Watch Later  (${wl.size})", null) { startActivity(Intent(this, WatchLaterActivity::class.java)) })
        rows.add(Row("⭐   Favourites", null) { showFavouritesHome() })
        rows.add(Row("📺   Live TV", null) { showLiveGenres() })
        rows.add(Row("🎬   Movies (VOD)", null) { showVodCategories() })
        rows.add(Row("⬇   Downloads", null) { startActivity(Intent(this, DownloadsActivity::class.java)) })
        push(Page("Stalker TV", rows, kind = SearchKind.GLOBAL, rebuild = { showHome() }))
    }

    private fun showContinueWatching() {
        val all = Resume.all(this)
        val live = all.filter { it.kind == "live" }
        val vod = all.filter { it.kind != "live" }
        val rows = ArrayList<Row>()
        if (live.isNotEmpty()) {
            rows.add(Row("📺   LIVE TV", null, isHeader = true) {})
            for (e in live) rows.add(Row("📺  ${e.title}", e.poster.ifBlank { null }, sortKey = e.title) { continueClick(e) })
        }
        if (vod.isNotEmpty()) {
            rows.add(Row("🎬   MOVIES & SHOWS", null, isHeader = true) {})
            for (e in vod) {
                val pct = if (e.duration > 0) (e.position * 100 / e.duration).toInt() else 0
                val label = "🎬  ${e.title}" + (if (pct in 1..99) "   •   $pct%" else "")
                rows.add(Row(label, e.poster.ifBlank { null }, sortKey = e.title) { continueClick(e) })
            }
        }
        if (rows.isNotEmpty())
            rows.add(Row("🗑   Clear Continue Watching", null) { confirmClearContinue() })
        push(Page("Continue Watching", rows, kind = SearchKind.LOCAL, rebuild = { showContinueWatching() }))
    }

    private fun confirmClearContinue() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Clear Continue Watching?")
            .setMessage("This removes everything from your Continue Watching list.")
            .setPositiveButton("Clear all") { _, _ ->
                Resume.clearAll(this)
                android.widget.Toast.makeText(this, "Continue Watching cleared", android.widget.Toast.LENGTH_SHORT).show()
                onBackPressed() // back to home; the row is gone
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun continueClick(e: Resume.Entry) {
        if (e.kind == "live") {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(e.title)
                .setItems(arrayOf("▶  Play", "🗑  Remove from Continue Watching")) { _, w ->
                    if (w == 0) playLiveResume(e) else { Resume.remove(this, e.id); refreshContinue() }
                }
                .show()
        } else {
            play(e.title, e.id, e.poster, e.source) // shows Resume / Start over / Remove
        }
    }

    private fun playLiveResume(e: Resume.Entry) {
        val p = e.source.split("|")
        val chId = p.getOrElse(1) { "" }
        val cmd = p.getOrElse(2) { "" }
        b.status.visibility = View.VISIBLE
        b.status.text = "Opening ${e.title}…"
        playIo.execute {
            val url = Portal.createLink(cmd)
            runOnUiThread {
                if (url.isNullOrEmpty()) {
                    b.status.visibility = View.VISIBLE
                    b.status.text = "Couldn't open “${e.title}”."
                } else {
                    b.status.visibility = View.GONE
                    LiveVlcActivity.liveChannels = allChannels
                    startActivity(
                        Intent(this, LiveVlcActivity::class.java)
                            .putExtra("url", url).putExtra("title", e.title)
                            .putExtra("chIndex", allChannels.indexOfFirst { it.id == chId })
                    )
                }
            }
        }
    }

    private fun refreshContinue() {
        val top = backStack.lastOrNull()
        if (top?.title == "Continue Watching" && top.rebuild != null) { backStack.removeLast(); top.rebuild!!.invoke() }
    }

    private fun showLiveGenres() {
        val rows = ArrayList<Row>()
        val favs = Configs.favorites(this)
        val favChannels = allChannels.filter { favs.contains(it.id) }
        if (favChannels.isNotEmpty())
            rows.add(Row("⭐  Favourites  (${favChannels.size})", null, sortKey = "Favourites") { showLiveFavRoot() })
        rows.add(Row("All Channels  (${allChannels.size})", null, sortKey = "All Channels") { openLiveGrid(allChannels, "All Channels") })
        for (g in genres) {
            val list = byGenre[g.id] ?: emptyList()
            // Censored (adult/restricted) genres aren't returned by get_all_channels, so they look
            // empty here — show them anyway (locked) and load their channels on demand.
            if (list.isEmpty() && !g.censored) continue
            val label = (if (g.censored) "🔒  " else "") + g.title + (if (list.isNotEmpty()) "  (${list.size})" else "")
            rows.add(Row(label, null, sortKey = g.title) { openGenre(g) })
        }
        push(Page("Live TV", rows, kind = SearchKind.CHANNELS, scopeChannels = allChannels, rebuild = { showLiveGenres() }))
    }

    private var parentalUnlocked = false

    private fun openGenre(g: Portal.Genre) {
        val proceed = {
            val cached = byGenre[g.id] ?: emptyList()
            if (cached.isNotEmpty()) openLiveGrid(cached, g.title) else loadGenreAndOpen(g)
        }
        if (g.censored && !parentalUnlocked) requirePin { parentalUnlocked = true; proceed() }
        else proceed()
    }

    /** Censored genres come from the ordered list (get_all_channels omits them). */
    private fun loadGenreAndOpen(g: Portal.Genre) {
        b.status.visibility = View.VISIBLE
        b.status.text = "Loading ${g.title}…"
        io.execute {
            val list = Portal.itvByGenre(g.id)
            runOnUiThread {
                if (list.isEmpty()) {
                    b.status.visibility = View.VISIBLE
                    b.status.text = "No channels in ${g.title}."
                } else {
                    b.status.visibility = View.GONE
                    openLiveGrid(list, g.title)
                }
            }
        }
    }

    /** Prompt for the parental PIN (sets it on first use), then run [onOk] if it matches. */
    private fun requirePin(onOk: () -> Unit) {
        val saved = Configs.parentalPin(this)
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
        }
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (saved.isBlank()) "Set a parental PIN" else "Enter parental PIN")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("OK") { _, _ ->
                val entered = input.text.toString().trim()
                if (saved.isBlank()) {
                    if (entered.length >= 3) { Configs.setParentalPin(this, entered); onOk() }
                    else android.widget.Toast.makeText(this, "PIN must be at least 3 digits.", android.widget.Toast.LENGTH_SHORT).show()
                } else if (entered == saved) onOk()
                else android.widget.Toast.makeText(this, "Incorrect PIN.", android.widget.Toast.LENGTH_SHORT).show()
            }
        if (saved.isBlank()) builder.setMessage("This locks adult / restricted channels. Enter the passcode from your provider, or choose your own (min 3 digits).")
        builder.show()
    }

    /** Set or change the parental PIN from the menu (asks the current PIN first if one exists). */
    private fun changePin() {
        val saved = Configs.parentalPin(this)
        if (saved.isBlank()) { requirePin {}; return }
        val cur = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Current PIN"
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Change parental PIN")
            .setView(cur)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Next") { _, _ ->
                if (cur.text.toString().trim() != saved) {
                    android.widget.Toast.makeText(this, "Incorrect PIN.", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    val next = android.widget.EditText(this).apply {
                        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
                        hint = "New PIN"
                    }
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("New parental PIN")
                        .setView(next)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Save") { _, _ ->
                            val n = next.text.toString().trim()
                            if (n.length >= 3) {
                                Configs.setParentalPin(this, n)
                                android.widget.Toast.makeText(this, "Parental PIN updated.", android.widget.Toast.LENGTH_SHORT).show()
                            } else android.widget.Toast.makeText(this, "PIN must be at least 3 digits.", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        .show()
                }
            }
            .show()
    }

    private fun showChannels(list: List<Portal.Channel>, title: String) {
        push(Page(title, list.map { channelRow(it) }, kind = SearchKind.CHANNELS, scopeChannels = list))
    }

    /** ⇅ button (movie category): cycle Default → A–Z → Z–A and reload this category from page 1. */
    private fun cycleVodSort() {
        val page = backStack.lastOrNull() ?: return
        if (page.kind != SearchKind.VOD_CATEGORY || page.scopeId == null) return
        Configs.cycleSortMode(this)
        updateSortBtn()
        backStack.removeLast() // replace the current category page with a freshly-sorted one
        showVodList(Portal.VodCat(page.scopeId!!, page.title))
    }

    private fun updateSortBtn() {
        b.sortBtn.text = when (Configs.sortMode(this)) {
            Configs.SORT_AZ -> "⇅ A–Z"
            Configs.SORT_ZA -> "⇅ Z–A"
            else -> "⇅ New"
        }
    }

    private fun showVodCategories() {
        if (vodCats.isNotEmpty()) { displayVodCategories(); return } // cached → in-memory rebuild
        b.status.visibility = View.VISIBLE
        b.status.text = "Loading movies…"
        io.execute {
            val cats = Portal.vodCategories()
            runOnUiThread {
                b.status.visibility = View.GONE
                if (cats.isEmpty()) {
                    b.status.visibility = View.VISIBLE
                    b.status.text = "No VOD categories found."
                    return@runOnUiThread
                }
                vodCats = cats
                cachedVodCats = cats
                displayVodCategories()
            }
        }
    }

    private fun displayVodCategories() {
        val rows = ArrayList<Row>()
        val favCount = Favorites.all(this).size
        if (favCount > 0)
            rows.add(Row("⭐  Favourites  ($favCount)", null, sortKey = "Favourites") { showVodFavRoot() })
        rows.addAll(vodCats.map { c -> Row(c.title, null, sortKey = c.title) { showVodList(c) } })
        push(Page("Movies", rows, kind = SearchKind.VOD_ALL, rebuild = { showVodCategories() }))
    }

    /** Title for episodes/seasons is "Series  /  Season  /  Episode" → split for nesting. */
    private fun favParts(title: String) = title.split("/").map { it.trim() }

    // ---- Favourites (grouped) ----

    /** Top-level Favourites: two folders — Live TV and Movies & VOD. */
    private fun showFavouritesHome() {
        val liveN = allChannels.count { Configs.isFavorite(this, it.id) }
        val vodN = Favorites.all(this).size
        val rows = ArrayList<Row>()
        rows.add(Row("📺   Live TV — Favourites  ($liveN)", null) { showLiveFavRoot() })
        rows.add(Row("🎬   Movies & VOD — Favourites  ($vodN)", null) { showVodFavRoot() })
        push(Page("Favourites", rows, kind = SearchKind.LOCAL, rebuild = { showFavouritesHome() }))
    }

    /** Live favourites: if no groups exist, open the grid directly; otherwise show group folders. */
    private fun showLiveFavRoot() {
        val favChannels = allChannels.filter { Configs.isFavorite(this, it.id) }
        val groups = FavGroups.groups(this, "live")
        if (groups.isEmpty() && favChannels.isNotEmpty()) {
            openLiveGrid(favChannels, "Favourites"); return
        }
        val rows = ArrayList<Row>()
        rows.add(Row("➕   New group", null) { promptCreateGroup("live") })
        for (g in groups) {
            val list = favChannels.filter { FavGroups.groupOf(this, "live", it.id) == g }
            rows.add(Row("📁  $g  (${list.size})", null, sortKey = g) { openLiveGrid(list, g) })
        }
        val ungrouped = favChannels.filter { FavGroups.groupOf(this, "live", it.id) == null }
        if (ungrouped.isNotEmpty())
            rows.add(Row("📁  Ungrouped  (${ungrouped.size})", null) { openLiveGrid(ungrouped, "Ungrouped") })
        if (groups.isNotEmpty()) rows.add(Row("⚙   Manage groups", null) { manageGroups("live") })
        push(Page("Live TV — Favourites", rows, kind = SearchKind.LOCAL, rebuild = { showLiveFavRoot() }))
    }

    /** Movies & VOD favourites: if no groups exist, show the flat/nested list; else group folders. */
    private fun showVodFavRoot() {
        val groups = FavGroups.groups(this, "vod")
        if (groups.isEmpty()) { showFavVod(); return }
        val all = Favorites.all(this)
        val rows = ArrayList<Row>()
        rows.add(Row("➕   New group", null) { promptCreateGroup("vod") })
        for (g in groups) {
            val n = all.count { FavGroups.groupOf(this, "vod", it.id) == g }
            rows.add(Row("📁  $g  ($n)", null, sortKey = g) { showVodFavGroup(g) })
        }
        val ungroupedN = all.count { FavGroups.groupOf(this, "vod", it.id) == null }
        if (ungroupedN > 0) rows.add(Row("📁  Ungrouped  ($ungroupedN)", null) { showVodFavGroup(null) })
        rows.add(Row("⚙   Manage groups", null) { manageGroups("vod") })
        if (all.isNotEmpty()) rows.add(Row("🗑   Clear all favourites", null) { confirmClearVodFavorites() })
        push(Page("Movies & VOD — Favourites", rows, kind = SearchKind.LOCAL, rebuild = { showVodFavRoot() }))
    }

    /** Flat list of a single VOD favourite group (movies play; series/season open; episodes play). */
    private fun showVodFavGroup(group: String?) {
        val all = Favorites.all(this).filter { FavGroups.groupOf(this, "vod", it.id) == group }
        val rows = ArrayList<Row>()
        for (m in all.filter { it.kind == "movie" })
            rows.add(Row("🎬  ${m.title}", m.poster.ifBlank { null }, sortKey = m.title, fav = vodFav("movie", m)) {
                mediaActions(m.title, m.poster, "movie_${m.id}", m.source)
            })
        for (s in all.filter { it.kind == "series" })
            rows.add(Row("📁  ${s.title}", s.poster.ifBlank { null }, sortKey = s.title, fav = vodFav("series", s)) { openFavSeries(s) })
        for (se in all.filter { it.kind == "season" })
            rows.add(Row("📁  ${se.title}", se.poster.ifBlank { null }, sortKey = se.title, fav = vodFav("season", se)) { openFavSeason(se) })
        for (e in all.filter { it.kind == "episode" })
            rows.add(Row("🎬  ${e.title}", e.poster.ifBlank { null }, sortKey = e.title, fav = vodFav("episode", e)) {
                mediaActions(e.title, e.poster, "ep_${e.id}", e.source)
            })
        val title = "Favourites — " + (group ?: "Ungrouped")
        push(Page(title, rows, kind = SearchKind.LOCAL, rebuild = { showVodFavGroup(group) }))
    }

    private fun promptCreateGroup(scope: String) {
        val input = android.widget.EditText(this).apply { hint = "Group name (e.g. Sports)" }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("New group")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) FavGroups.addGroup(this, scope, n)
                afterFavChange()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun manageGroups(scope: String) {
        val groups = FavGroups.groups(this, scope)
        if (groups.isEmpty()) { android.widget.Toast.makeText(this, "No groups yet.", android.widget.Toast.LENGTH_SHORT).show(); return }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Manage groups")
            .setItems(groups.toTypedArray()) { _, w -> groupActions(scope, groups[w]) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun groupActions(scope: String, name: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(arrayOf("✏  Rename", "🗑  Delete group")) { _, w ->
                if (w == 0) {
                    val input = android.widget.EditText(this).apply { setText(name) }
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Rename group")
                        .setView(input)
                        .setPositiveButton("Save") { _, _ ->
                            val n = input.text.toString().trim()
                            if (n.isNotEmpty()) FavGroups.renameGroup(this, scope, name, n)
                            afterFavChange()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    FavGroups.deleteGroup(this, scope, name)
                    android.widget.Toast.makeText(this, "Deleted “$name” — items moved to Ungrouped", android.widget.Toast.LENGTH_SHORT).show()
                    afterFavChange()
                }
            }
            .show()
    }

    private fun showFavVod() {
        val all = Favorites.all(this)
        val rows = ArrayList<Row>()
        // Movies (play directly)
        for (m in all.filter { it.kind == "movie" }) {
            val fav = vodFav("movie", m)
            rows.add(Row("🎬  ${m.title}", m.poster.ifBlank { null }, sortKey = m.title, fav = fav) {
                mediaActions(m.title, m.poster, "movie_${m.id}", m.source)
            })
        }
        // Whole-series favourites (open all seasons; long-press to un-favourite)
        for (s in all.filter { it.kind == "series" }) {
            val fav = vodFav("series", s)
            rows.add(Row("📁  ${s.title}", s.poster.ifBlank { null }, sortKey = s.title, fav = fav) { openFavSeries(s) })
        }
        // Favourited seasons (open episodes; long-press to un-favourite)
        for (se in all.filter { it.kind == "season" }) {
            val fav = vodFav("season", se)
            rows.add(Row("📁  ${se.title}", se.poster.ifBlank { null }, sortKey = se.title, fav = fav) { openFavSeason(se) })
        }
        // Series → Season → Episode nesting for favourited episodes
        val episodes = all.filter { it.kind == "episode" }
        for (seriesName in episodes.map { e -> favParts(e.title).getOrElse(0) { e.title } }.distinct())
            rows.add(Row("📁  $seriesName", null, sortKey = seriesName) { showFavEpSeries(seriesName) })
        if (rows.isNotEmpty())
            rows.add(Row("🗑   Clear all favourites", null) { confirmClearVodFavorites() })
        push(Page("Favourites", rows, kind = SearchKind.LOCAL, rebuild = { showFavVod() }))
    }

    private fun confirmClearVodFavorites() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Clear all favourites?")
            .setMessage("This removes all your favourite movies and shows.")
            .setPositiveButton("Clear all") { _, _ ->
                Favorites.clearAll(this)
                android.widget.Toast.makeText(this, "Favourites cleared", android.widget.Toast.LENGTH_SHORT).show()
                onBackPressed() // back to Movies; the Favourites folder is now empty
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFavEpSeries(seriesName: String) {
        val eps = Favorites.byKind(this, "episode").filter { favParts(it.title).getOrElse(0) { "" } == seriesName }
        val rows = eps.map { favParts(it.title).getOrElse(1) { "" } }.distinct().map { season ->
            Row("📁  $season", null, sortKey = season) { showFavEpSeason(seriesName, season) }
        }
        push(Page("Favourites — $seriesName", rows, kind = SearchKind.LOCAL, rebuild = { showFavEpSeries(seriesName) }))
    }

    private fun showFavEpSeason(seriesName: String, season: String) {
        val eps = Favorites.byKind(this, "episode").filter {
            val p = favParts(it.title); p.getOrElse(0) { "" } == seriesName && p.getOrElse(1) { "" } == season
        }
        val rows = eps.map { e ->
            val epName = favParts(e.title).getOrElse(2) { e.title }
            val fav = FavInfo({ Favorites.isFav(this, "episode", e.id) }, { Favorites.toggle(this, e) })
            Row("🎬  $epName", e.poster.ifBlank { null }, sortKey = epName, fav = fav) {
                mediaActions(e.title, e.poster, "ep_${e.id}", e.source)
            }
        }
        push(Page("Favourites — $seriesName — $season", rows, kind = SearchKind.LOCAL, rebuild = { showFavEpSeason(seriesName, season) }))
    }

    private fun openFavSeries(e: Favorites.Entry) {
        val id = e.source.split("|").getOrElse(1) { e.id }
        showSeasons(Portal.VodItem(id, e.title, "", e.poster, true))
    }

    private fun openFavSeason(e: Favorites.Entry) {
        val p = e.source.split("|")
        val parts = favParts(e.title)
        showEpisodes(
            Portal.VodItem(p.getOrElse(1) { "" }, parts.getOrElse(0) { e.title }, "", e.poster, true),
            Portal.Season(p.getOrElse(2) { "" }, parts.getOrElse(1) { "Season" })
        )
    }

    /** Server-side order for the current global sort. Default = newest ("added"); A–Z/Z–A both fetch
     *  "name" (ascending) and Z–A is reversed client-side (the portal has no descending option). */
    private fun vodSortby(): String = if (Configs.sortMode(this) == Configs.SORT_DEFAULT) "added" else "name"
    private fun orderedVod(acc: List<Portal.VodItem>): List<Portal.VodItem> =
        if (Configs.sortMode(this) == Configs.SORT_ZA) acc.reversed() else acc

    private fun showVodList(cat: Portal.VodCat) {
        b.status.visibility = View.VISIBLE
        b.status.text = "Loading ${cat.title}…"
        io.execute {
            val (items, pages) = Portal.vodList(cat.id, 1, vodSortby())
            runOnUiThread {
                b.status.visibility = View.GONE
                push(Page(cat.title, vodRows(cat, ArrayList(items), 1, pages), kind = SearchKind.VOD_CATEGORY, scopeId = cat.id))
            }
        }
    }

    private fun vodRows(cat: Portal.VodCat, acc: ArrayList<Portal.VodItem>, loaded: Int, total: Int): List<Row> {
        val rows = ArrayList<Row>()
        orderedVod(acc).forEach { v -> rows.add(vodItemRow(v)) }
        if (loaded < total) {
            rows.add(Row("⬇  Load more  ($loaded/$total)", null) {
                b.status.visibility = View.VISIBLE
                b.status.text = "Loading…"
                io.execute {
                    val (more, _) = Portal.vodList(cat.id, loaded + 1, vodSortby())
                    runOnUiThread {
                        b.status.visibility = View.GONE
                        acc.addAll(more)
                        val page = Page(cat.title, vodRows(cat, acc, loaded + 1, total), kind = SearchKind.VOD_CATEGORY, scopeId = cat.id)
                        backStack.removeLast()
                        backStack.addLast(page)
                        display(page)
                    }
                }
            })
        }
        return rows
    }

    private fun showSeasons(series: Portal.VodItem) {
        b.status.visibility = View.VISIBLE
        b.status.text = "Loading ${series.name}…"
        io.execute {
            val seasons = Portal.seriesSeasons(series.id)
            runOnUiThread {
                b.status.visibility = View.GONE
                if (seasons.isEmpty()) {
                    b.status.visibility = View.VISIBLE
                    b.status.text = "No seasons found for ${series.name}."
                    return@runOnUiThread
                }
                push(Page(series.name, seasons.reversed().map { s ->
                    val favE = Favorites.Entry("season", "${series.id}_${s.id}", "${series.name}  /  ${s.name}", series.posterUrl, "season|${series.id}|${s.id}")
                    val fav = vodFav("season", favE)
                    Row(s.name, null, fav = fav) { showEpisodes(series, s) }
                }))
            }
        }
    }

    private fun showEpisodes(series: Portal.VodItem, season: Portal.Season) {
        b.status.visibility = View.VISIBLE
        b.status.text = "Loading ${season.name}…"
        io.execute {
            val eps = Portal.seriesEpisodes(series.id, season.id)
            runOnUiThread {
                b.status.visibility = View.GONE
                if (eps.isEmpty()) {
                    b.status.visibility = View.VISIBLE
                    b.status.text = "No episodes found."
                    return@runOnUiThread
                }
                // The portal returns episodes newest-first; reversed() gives true episode order
                // (E1, E2, E3…). Use that SAME order for the display rows and the autoplay playlist
                // so autoplay / Next advance forward and indices line up.
                val ordered = eps.reversed()
                val playlist = ordered.map { ep ->
                    PlayerActivity.PlaylistItem(
                        "${series.name}  /  ${season.name}  /  ${ep.name}",
                        "ep_${series.id}_${season.id}_${ep.id}",
                        series.posterUrl,
                        "ep|${series.id}|${season.id}|${ep.id}"
                    )
                }
                push(Page("${series.name} — ${season.name}", ordered.mapIndexed { idx, e ->
                    val title = "${series.name}  /  ${season.name}  /  ${e.name}"
                    val favE = Favorites.Entry("episode", "${series.id}_${season.id}_${e.id}", title, series.posterUrl, "ep|${series.id}|${season.id}|${e.id}")
                    val fav = vodFav("episode", favE)
                    Row(e.name, null, fav = fav) {
                        mediaActions(title, series.posterUrl, "ep_${series.id}_${season.id}_${e.id}", "ep|${series.id}|${season.id}|${e.id}", playlist, idx)
                    }
                }))
            }
        }
    }

    /** Play a movie/episode; if there's a saved position, ask Resume / Start over first. */
    private fun play(
        title: String, resumeId: String, poster: String?, source: String,
        playlist: List<PlayerActivity.PlaylistItem> = emptyList(), plIndex: Int = -1
    ) {
        val r = Resume.get(this, resumeId)
        if (Resume.resumable(r)) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(arrayOf("▶  Resume from ${fmtTime(r!!.position)}", "↻  Start from beginning", "🗑  Remove from Continue Watching")) { _, w ->
                    when (w) {
                        0 -> startPlayer(title, resumeId, poster, source, r.position, playlist, plIndex)
                        1 -> startPlayer(title, resumeId, poster, source, 0L, playlist, plIndex)
                        2 -> { Resume.remove(this, resumeId); refreshContinue() }
                    }
                }
                .show()
        } else startPlayer(title, resumeId, poster, source, 0L, playlist, plIndex)
    }

    private fun startPlayer(
        title: String, resumeId: String, poster: String?, source: String, startPos: Long,
        playlist: List<PlayerActivity.PlaylistItem> = emptyList(), plIndex: Int = -1
    ) {
        b.status.visibility = View.VISIBLE
        b.status.text = "Opening $title…"
        playIo.execute {
            val url = Downloads.resolveSource(source)
            runOnUiThread {
                if (url.isNullOrEmpty()) {
                    b.status.visibility = View.VISIBLE
                    val why = Portal.lastError
                    b.status.text = if (why == "nothing_to_play")
                        "“$title” — no stream returned. Either the provider's storage is down, or your account's connection limit is reached (another device is already streaming)."
                    else "Couldn't open “$title” — $why"
                } else {
                    b.status.visibility = View.GONE
                    PlayerActivity.playlist = playlist
                    PlayerActivity.playlistIndex = plIndex
                    startActivity(
                        Intent(this, PlayerActivity::class.java)
                            .putExtra("url", url)
                            .putExtra("title", title)
                            .putExtra("resumeId", resumeId)
                            .putExtra("resumeSource", source)
                            .putExtra("resumePoster", poster ?: "")
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
}
