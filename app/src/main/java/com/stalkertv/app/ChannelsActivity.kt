package com.stalkertv.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import coil.load
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
    private val liveCatAdapter = RowAdapter() // Live TV category panel (Strimix-style left list)

    /** Optional favourite toggle for a row (channels / movies). Null = not favouritable (e.g. folders). */
    class FavInfo(val isFav: () -> Boolean, val toggle: () -> Boolean, val onAdded: (() -> Unit)? = null)
    data class Row(val label: String, val iconUrl: String?, val sortKey: String = "", val fav: FavInfo? = null, val isHeader: Boolean = false, val catchup: (() -> Unit)? = null, val rail: List<Card>? = null, val chip: Boolean = false, val poster: Boolean = false, val action: () -> Unit)
    /** A poster/landscape card inside a home rail. */
    data class Card(val title: String, val poster: String?, val progress: Int = -1, val landscape: Boolean = false, val onLongClick: (() -> Unit)? = null, val onClick: () -> Unit)
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

    // --- Movie list filter/sort state (the current VOD_CATEGORY view) ---
    private var vodBase = listOf<Portal.VodItem>()   // loaded items backing the current movie list
    private var vodCat: Portal.VodCat? = null         // set when paginated (null for search / A–Z views)
    private var vodCatRef: Portal.VodCat? = null      // the category being browsed (for "ALL" / clear search)
    private var vodLoaded = 0
    private var vodTotal = 0
    private var vodFilterAttr: String? = null         // Genre | Year | Decade | Age | Country | HD | Type
    private var vodFilterVal: String? = null
    private var vodLetter: String? = null             // A–Z sub-search applied on top of the filter
    private var vodSortKey = "default"                // default | za | az | year_desc | year_asc | run_asc | run_desc
    private var vodLoadSeq = 0                         // cancels a stale all-pages load when the list changes
    private val pageIo = Executors.newFixedThreadPool(8) // parallel page fetches (fast "load all")
    private val vodSortLabels = linkedMapOf(
        "default" to "Newest", "az" to "A–Z", "za" to "Z–A",
        "year_desc" to "Year ↓", "year_asc" to "Year ↑", "run_asc" to "Shortest", "run_desc" to "Longest"
    )
    private var welcomeShown = false
    private var updateChecked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PosterLoader.install(this) // auth poster requests to the portal so thumbnails load reliably
        b = ActivityChannelsBinding.inflate(layoutInflater)
        setContentView(b.root)
        // One grid for everything: category chips and movie posters tile in columns, while normal
        // rows/rails span the full width. Base 60 is divisible by 2/3/4/5/6 so any column count fits.
        val wdp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val chipCols = (wdp / 200f).toInt().coerceIn(2, 4)
        val posterCols = if (wdp >= 900) 6 else if (wdp >= 600) 5 else 3
        val total = 60
        val chipSpan = total / chipCols
        val posterSpan = total / posterCols
        val glm = androidx.recyclerview.widget.GridLayoutManager(this, total)
        glm.spanSizeLookup = object : androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = when {
                adapter.isChip(position) -> chipSpan
                adapter.isPoster(position) -> posterSpan
                else -> total
            }
        }
        b.list.layoutManager = glm
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
        buildAzBar() // shown only inside a movie folder (see display())

        b.searchBtn.setOnClickListener { toggleSearch() }
        b.reloadBtn.setOnClickListener { connectAndLoad(true) } // true = real portal reconnect, not a cache rebuild
        b.sortBtn.setOnClickListener { showSortDialog() }
        b.filterBtn.setOnClickListener { showFilterDialog() }
        b.settingsBtn.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.profileBtn.setOnClickListener { showProfilePicker() }
        b.profileBtn.setOnFocusChangeListener { v, f -> val s = if (f) 1.18f else 1f; v.animate().scaleX(s).scaleY(s).setDuration(120).start() }

        b.liveCatList.layoutManager = LinearLayoutManager(this)
        b.liveCatList.adapter = liveCatAdapter

        // Floating bottom tab bar (home only).
        b.navLive.setOnClickListener { showLiveCategories() }
        b.navMovies.setOnClickListener { showVodCategories() }
        b.navFav.setOnClickListener { showFavouritesHome() }
        b.navWatch.setOnClickListener { startActivity(Intent(this, WatchLaterActivity::class.java)) }
        b.navRec.setOnClickListener { startActivity(Intent(this, RecordingsActivity::class.java)) }
        b.navDl.setOnClickListener { startActivity(Intent(this, DownloadsActivity::class.java)) }
        b.list.clipToPadding = false

        // The self-update APK lives in cache and is only needed until the install finishes; once we're
        // running again it's stale, so delete it to reclaim the ~60 MB.
        try { java.io.File(cacheDir, "update.apk").delete() } catch (_: Exception) {}

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
                        .setMessage("A newer build is available: build ${v.first} (you have build ${BuildConfig.VERSION_CODE}).\nDownload and install it now?")
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
            .setTitle("Welcome to Vibe TV")
            .setMessage(
                "To start watching, add your IPTV provider:\n\n" +
                    "1. Open Settings (⚙ top-right, or the MENU button on your remote).\n" +
                    "2. Enter your Portal URL, MAC address, and Serial Number.\n" +
                    "3. Tap Submit — channels and movies load automatically.\n\n" +
                    "You get these details from your IPTV provider."
            )
            .setPositiveButton("Add provider") { _, _ -> startActivity(Intent(this, ProvidersActivity::class.java)) }
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
        // Movie folder: A–Z is a client-side sub-search on the already-loaded + filtered set.
        if (page.kind == SearchKind.VOD_CATEGORY) {
            vodLetter = letter // null = ALL (no sub-search)
            renderVodItems(0)
            return
        }
        if (letter == null) {
            adapter.submit(page.rows)
            return
        }
        run {
            // Channels / genres / categories are all in memory — filter locally.
            adapter.submit(page.rows.filter { it.sortKey.trimStart().startsWith(letter, ignoreCase = true) })
        }
    }

    private fun confirmExit() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Exit Vibe TV?")
            .setPositiveButton("Yes") { _, _ -> finishAffinity() }
            .setNegativeButton("No", null)
            .show()
    }

    private fun isInList(v: android.view.View?): Boolean {
        var p = v?.parent
        while (p != null) { if (p === b.list) return true; p = (p as? android.view.View)?.parent }
        return false
    }

    private fun isNavKey(kc: Int) = kc == android.view.KeyEvent.KEYCODE_DPAD_UP ||
        kc == android.view.KeyEvent.KEYCODE_DPAD_DOWN || kc == android.view.KeyEvent.KEYCODE_DPAD_LEFT ||
        kc == android.view.KeyEvent.KEYCODE_DPAD_RIGHT || kc == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
        kc == android.view.KeyEvent.KEYCODE_ENTER

    /** Put focus somewhere sensible for the current screen — used so a remote always has a target. */
    private fun grabInitialFocus() {
        when {
            b.searchRow.visibility == View.VISIBLE -> b.search.requestFocus()
            b.profileOverlay.visibility == View.VISIBLE -> (b.profileRow.getChildAt(0))?.requestFocus()
            b.liveCatOverlay.visibility == View.VISIBLE ->
                (b.liveCatList.findViewHolderForAdapterPosition(0)?.itemView ?: b.liveCatList).requestFocus()
            (b.list.adapter?.itemCount ?: 0) > 0 -> { if (!b.list.requestFocus()) b.searchBtn.requestFocus() }
            else -> b.searchBtn.requestFocus()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Some Android-TV launchers hand the app focus with nothing selected → grab a default.
        if (hasFocus && currentFocus == null) b.list.post { if (currentFocus == null) grabInitialFocus() }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // Universal remote safety net: if a navigation key arrives but nothing is focused (common on
        // non-Fire Android-TV boxes — the "cursor keeps dropping" symptom), grab a sensible focus first
        // so the remote always controls the UI.
        if (event.action == android.view.KeyEvent.ACTION_DOWN && currentFocus == null && isNavKey(event.keyCode)) {
            grabInitialFocus()
            return true
        }
        // TV: pressing UP from the top of the content list jumps to the top-bar icons (the nested
        // horizontal rails otherwise trap focus and never reach search/refresh/etc.).
        if (event.action == android.view.KeyEvent.ACTION_DOWN &&
            event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP &&
            b.profileOverlay.visibility != View.VISIBLE && b.liveCatOverlay.visibility != View.VISIBLE &&
            b.searchRow.visibility != View.VISIBLE &&
            isInList(currentFocus) && !b.list.canScrollVertically(-1)
        ) {
            b.searchBtn.requestFocus()
            return true
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
        if (keyCode == android.view.KeyEvent.KEYCODE_MENU) { startActivity(Intent(this, SettingsActivity::class.java)); return true }
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
        // Returning from the profile editor while the picker is open → refresh the tiles.
        if (b.profileOverlay.visibility == View.VISIBLE) { buildProfileTiles(); return }
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

    override fun onStop() {
        super.onStop()
        // If the Live category overlay was left up while launching the player, drop it now (while we're
        // hidden behind the player) so home doesn't flash through, and the panel isn't stuck on return.
        if (b.liveCatOverlay.visibility == View.VISIBLE) hideLiveCategories()
    }

    private var progressAnim: android.animation.ObjectAnimator? = null

    private fun showLoading(msg: String) {
        progressAnim?.cancel()
        b.loadingBar.progress = 0
        b.loadingPct.text = "0%"
        b.loadingMsg.text = msg
        buildSplashMontage()
        b.loadingOverlay.visibility = View.VISIBLE
    }

    private var splashBuilt = false
    /** Paint the splash backdrop with a grid of recent posters (Strimix-style). Empty on first launch. */
    private fun buildSplashMontage() {
        if (splashBuilt) return
        val posters = Configs.splashPosters(this)
        if (posters.isEmpty()) return
        splashBuilt = true
        val grid = b.splashGrid
        val dm = resources.displayMetrics
        val cell = (108 * dm.density).toInt().coerceAtLeast(1)
        val cols = (dm.widthPixels / cell).coerceIn(3, 8)
        val rows = (dm.heightPixels / cell + 1).coerceIn(3, 9)
        val shuffled = posters.shuffled()
        var idx = 0
        val mp = android.view.ViewGroup.LayoutParams.MATCH_PARENT
        for (r in 0 until rows) {
            val row = android.widget.LinearLayout(this)
            row.orientation = android.widget.LinearLayout.HORIZONTAL
            row.layoutParams = android.widget.LinearLayout.LayoutParams(mp, 0, 1f)
            for (c in 0 until cols) {
                val iv = android.widget.ImageView(this)
                iv.layoutParams = android.widget.LinearLayout.LayoutParams(0, mp, 1f)
                iv.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                iv.load(shuffled[idx % shuffled.size]) {
                    placeholder(R.drawable.thumb_placeholder); error(R.drawable.thumb_placeholder)
                }
                idx++
                row.addView(iv)
            }
            grid.addView(row)
        }
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
        b.title.text = "Vibe TV"
        b.status.visibility = View.VISIBLE
        b.status.text = "📡  $netMsg"
        push(
            Page(
                "Vibe TV",
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
        private var cachedRecent: List<Portal.VodItem> = emptyList() // newest movies, for the home rail
        private var cachedRecentScope: String? = " " // profile id the rail was loaded for (  = never)

        // Catalog access for the profile editor (so it can list categories without a screen of its own).
        fun catGenres(): List<Portal.Genre> = cachedGenres
        fun catVodCats(): List<Portal.VodCat> = cachedVodCats
        fun cacheCatalog(g: List<Portal.Genre>, v: List<Portal.VodCat>) { cachedGenres = g; cachedVodCats = v }
        /** Full channel list across all categories (for Multi-view's cross-category picker). */
        fun allChannelsCatalog(): List<Portal.Channel> = cachedChannels
    }

    /** Read the active provider, connect in the background, then show the home menu. */
    private fun connectAndLoad(force: Boolean = false) {
        parentalUnlocked = false // a fresh portal load re-locks restricted folders
        val acct = Configs.active(this)
        b.title.text = "Vibe TV"
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
            maybeShowProfilePicker()
            loadRecentlyAdded()
            return
        }
        vodCats = emptyList()
        cachedVodCats = emptyList()
        cachedRecent = emptyList()
        cachedRecentScope = " "
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
                    b.loadingOverlay.postDelayed({ hideLoading(); showHome(); maybeShowProfilePicker() }, 450)
                    loadRecentlyAdded()
                }
            }
        }
    }

    /** Fetch the newest movies (all categories, sorted by date added) in the background and, if the
     *  user is still on home, refresh it to surface the "Recently Added" rail. Portal-dependent: if
     *  the all-category query isn't supported it returns nothing and the rail simply stays hidden. */
    private fun loadRecentlyAdded() {
        val scope = ContentProfiles.activeId(this)
        if (cachedRecentScope == scope) return // already loaded/attempted for this profile
        cachedRecentScope = scope
        cachedRecent = emptyList() // drop the previous profile's items until the new ones arrive
        pageIo.submit {
            val p = ContentProfiles.active(this)
            val items: List<Portal.VodItem> = if (p == null || p.allVod) {
                try { Portal.vodList("*", 1, "added").first } catch (_: Exception) { emptyList() }
            } else {
                // Profile limits the movie categories → pull newest from each allowed category and merge.
                val merged = ArrayList<Portal.VodItem>()
                for (catId in p.vodCats) {
                    try { merged.addAll(Portal.vodList(catId, 1, "added").first) } catch (_: Exception) {}
                }
                merged.sortedByDescending { it.added }
            }
            val recent = items.filter { it.posterUrl.isNotBlank() }.take(20)
            runOnUiThread {
                if (ContentProfiles.activeId(this) != scope) return@runOnUiThread // profile changed mid-load
                cachedRecent = recent
                if (backStack.size <= 1) showHome()
            }
        }
    }

    // ---- navigation ----
    private fun push(page: Page) {
        backStack.addLast(page)
        display(page)
    }

    private fun display(page: Page, focusPos: Int = 0) {
        b.title.text = page.title
        b.search.hint = when (page.kind) {
            SearchKind.GLOBAL -> "Search channels, movies & shows…"
            SearchKind.CHANNELS -> "Search channels…"
            SearchKind.VOD_ALL -> "Search movies & shows…"
            SearchKind.VOD_CATEGORY -> "Search this folder…"
            SearchKind.LOCAL -> "Filter…"
        }
        adapter.submit(page.rows)
        // Floating bottom nav only on the home (top level); pad the list so rails clear the bar.
        val atHome = backStack.size <= 1
        b.bottomNav.visibility = if (atHome) View.VISIBLE else View.GONE
        b.list.setPadding(0, 0, 0, if (atHome) (96 * resources.displayMetrics.density).toInt() else 0)
        // Filter + Sort apply inside a movie category (also covers its A–Z and in-folder search).
        val vodList = page.kind == SearchKind.VOD_CATEGORY
        b.sortBtn.visibility = if (vodList) View.VISIBLE else View.GONE
        b.filterBtn.visibility = if (vodList) View.VISIBLE else View.GONE
        // A–Z bar inside a movie folder (jump titles) AND on the category grid (jump folders).
        b.azScroll.visibility = if (vodList || page.kind == SearchKind.VOD_ALL) View.VISIBLE else View.GONE
        if (vodList) updateVodButtons()
        if (b.search.text.isNotEmpty()) b.search.setText("")
        b.searchRow.visibility = View.GONE
        val pos = focusPos.coerceIn(0, (page.rows.size - 1).coerceAtLeast(0))
        b.list.scrollToPosition(pos)
        // Defer until the rebuilt rows are laid out; requesting focus on a not-yet-populated
        // list silently fails and focus falls back to the first toolbar icon (the 🔍 button).
        // On "Load more" (focusPos>0) land the cursor on the first newly-loaded item rather
        // than snapping back to the top of the folder.
        b.list.post {
            val vh = b.list.findViewHolderForAdapterPosition(pos)
            if (vh != null) vh.itemView.requestFocus()
            else if (!b.list.hasFocus()) b.list.requestFocus()
        }
    }

    override fun onBackPressed() {
        if (b.profileOverlay.visibility == View.VISIBLE) { hideProfilePicker(); showHome(); return }
        if (b.liveCatOverlay.visibility == View.VISIBLE) { hideLiveCategories(); return }
        // In a movie folder, Back first clears an active filter / A–Z / search → back to the full folder,
        // only then (next Back) exits to the Movies grid.
        val cur = backStack.lastOrNull()
        if (cur?.kind == SearchKind.VOD_CATEGORY) {
            val hadSearch = b.search.text.isNotEmpty()
            if (hadSearch || vodFilterAttr != null || vodLetter != null) {
                vodFilterAttr = null; vodFilterVal = null; vodLetter = null
                updateVodButtons()
                if (hadSearch) { b.search.setText(""); b.searchRow.visibility = View.GONE } // → vodSearchUi("") reloads full folder
                else renderVodItems(0)
                return
            }
        }
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
                    else "Couldn't open “${ch.name}” — ${Portal.lastErrorFriendly()}"
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

    private fun vodItemRow(v: Portal.VodItem, poster: Boolean = false): Row {
        val label = (if (v.isSeries) "📁  " else "🎬  ") + v.name
        val fav = if (v.isSeries)
            vodFav("series", Favorites.Entry("series", v.id, v.name, v.posterUrl, "series|${v.id}"))
        else
            vodFav("movie", Favorites.Entry("movie", v.id, v.name, v.posterUrl, "vod|${v.id}|${v.cmd}"))
        return Row(label, v.posterUrl, sortKey = v.name, fav = fav, poster = poster) {
            if (v.isSeries) showSeasons(v)
            else mediaActions(v.name, v.posterUrl, "movie_${v.id}", "vod|${v.id}|${v.cmd}", info = MovieInfo.from(v))
        }
    }

    /** Lightweight bundle of the metadata the portal already returns for a movie (for the info sheet). */
    data class MovieInfo(
        val description: String, val year: String, val imdb: String,
        val director: String, val actors: String, val genre: String,
        val runtimeMin: String, val country: String, val age: String
    ) {
        fun hasAny() = description.isNotBlank() || imdb.isNotBlank() || year.isNotBlank() ||
            director.isNotBlank() || actors.isNotBlank() || genre.isNotBlank() ||
            runtimeMin.isNotBlank() || country.isNotBlank() || age.isNotBlank()
        companion object {
            fun from(v: Portal.VodItem) =
                MovieInfo(v.description, v.year, v.imdb, v.director, v.actors, v.genre, v.runtimeMin, v.country, v.age)
        }
    }

    /** Movie / episode action sheet: play, watch trailer, info & ratings, watch later, download.
     *  [source] lets a download resume later; [info] (movies only) drives the info sheet. */
    private fun mediaActions(
        title: String, poster: String?, id: String, source: String,
        playlist: List<PlayerActivity.PlaylistItem> = emptyList(), plIndex: Int = -1,
        info: MovieInfo? = null
    ) {
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        labels.add("▶  Play"); acts.add { Taste.record(applicationContext, info?.genre ?: ""); play(title, id, poster, source, playlist, plIndex) }
        labels.add("🎬  Watch trailer"); acts.add { watchTrailer(title, info?.year ?: "") }
        if (info != null) { // TMDb can fill rating/overview even when the portal metadata is sparse
            labels.add("ℹ  Movie info & rating"); acts.add { showMovieInfo(title, info) }
        }
        labels.add("🕒  Watch later"); acts.add {
            val kind = if (source.startsWith("ep|")) "episode" else "movie"
            val added = WatchLater.add(applicationContext, kind, id, title, poster ?: "", source)
            android.widget.Toast.makeText(this, if (added) "Added to Watch Later" else "Already in Watch Later", android.widget.Toast.LENGTH_SHORT).show()
        }
        labels.add("⬇  Download for offline"); acts.add {
            if (Downloads.has(applicationContext, id)) {
                android.widget.Toast.makeText(this, "Already saved (or downloading). See Downloads.", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                Downloads.enqueue(applicationContext, id, title, poster ?: "", source)
                android.widget.Toast.makeText(this, "Download started — see ⬇ Downloads.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, w -> acts[w]() }
            .show()
    }

    /** Resolve the trailer's YouTube video id (TMDb first, then a keyless YouTube search) and play it
     *  IN-APP. Only if BOTH fail do we punt to the external YouTube app. */
    private fun watchTrailer(title: String, year: String) {
        val clean = cleanTitleForSearch(title)
        val key = BuildConfig.TMDB_KEY
        android.widget.Toast.makeText(this, "Finding trailer…", android.widget.Toast.LENGTH_SHORT).show()
        io.execute {
            var vid = if (key.isNotBlank()) Tmdb.trailerYoutubeId(key, clean, year) else null
            if (vid == null) vid = YouTubeSearch.firstVideoId("$clean ${year.trim()} trailer".trim())
            runOnUiThread {
                if (vid != null) openYouTubeVideo(vid) else openYouTubeSearch(clean, year)
            }
        }
    }

    /** Play the trailer IN-APP (embedded) so Back returns straight to the app — no stranding in the
     *  external YouTube app (a Fire TV pain). Falls back to the YouTube app only if the embed can't start. */
    private fun openYouTubeVideo(videoId: String) {
        try { startActivity(Intent(this, TrailerActivity::class.java).putExtra("videoId", videoId)); return }
        catch (_: Exception) {}
        try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=$videoId"))); return }
        catch (_: Exception) {}
        android.widget.Toast.makeText(this, "Couldn't open the trailer.", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Fallback: a YouTube search for the trailer (reliable on tablet/browser; best-effort on TV). */
    private fun openYouTubeSearch(title: String, year: String) {
        val q = (title.trim() + (if (year.isNotBlank()) " $year" else "") + " trailer").trim()
        val enc = android.net.Uri.encode(q)
        val resultsUrl = "https://www.youtube.com/results?search_query=$enc"
        val ytPkgs = listOf("com.amazon.firetv.youtube", "com.google.android.youtube.tv", "com.google.android.youtube")
        val attempts = ArrayList<Intent>()
        if (Tv.isTv(this)) {
            for (p in ytPkgs) attempts.add(
                Intent(Intent.ACTION_SEARCH).setPackage(p)
                    .putExtra(android.app.SearchManager.QUERY, q).putExtra("query", q)
            )
            for (p in ytPkgs) attempts.add(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(resultsUrl)).setPackage(p))
        }
        attempts.add(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(resultsUrl)))
        for (i in attempts) try { startActivity(i); return } catch (_: Exception) {}
        android.widget.Toast.makeText(this, "No app available to play the trailer.", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Strip portal cruft ("- TELUGU | DUBB MOVIES", "(English)", "[HD]") to a clean movie title for lookup. */
    private fun cleanTitleForSearch(raw: String): String {
        var s = raw
        val dash = s.indexOf(" - ")
        if (dash > 0) s = s.substring(0, dash)
        s = s.replace(Regex("\\([^)]*\\)"), " ").replace(Regex("\\[[^\\]]*\\]"), " ")
        s = s.substringBefore("|")
        s = s.replace(Regex("\\s+"), " ").trim()
        return s.ifBlank { raw.trim() }
    }

    /** Description + cast/runtime/etc., all from the portal's own metadata.
     *  (Ratings are omitted — this portal returns 0 for IMDb/Kinopoisk on every title.) */
    private fun showMovieInfo(title: String, info: MovieInfo) {
        val key = BuildConfig.TMDB_KEY
        if (key.isBlank()) { showMovieInfoDialog(title, info, null); return }
        android.widget.Toast.makeText(this, "Loading details…", android.widget.Toast.LENGTH_SHORT).show()
        io.execute {
            val meta = Tmdb.movieMeta(key, cleanTitleForSearch(title), info.year)
            runOnUiThread { showMovieInfoDialog(title, info, meta) }
        }
    }

    private fun showMovieInfoDialog(title: String, info: MovieInfo, meta: Tmdb.Meta?) {
        val sb = StringBuilder()
        val line1 = listOf(info.year, runtimeStr(info.runtimeMin), info.age).filter { it.isNotBlank() }.joinToString("  ·  ")
        if (line1.isNotBlank())         sb.append("$line1\n")
        // Rating: portal IMDb if present, otherwise TMDb (portal usually returns 0).
        val ratingLine = when {
            info.imdb.isNotBlank() -> "IMDb:  ⭐ ${info.imdb}"
            meta != null && meta.rating > 0 -> "Rating:  ⭐ ${String.format("%.1f", meta.rating)}/10  (TMDb)"
            else -> null
        }
        if (ratingLine != null)         sb.append("$ratingLine\n")
        if (info.genre.isNotBlank())    sb.append("Genre:  ${info.genre}\n")
        if (info.country.isNotBlank())  sb.append("Country:  ${info.country}\n")
        if (info.director.isNotBlank()) sb.append("Director:  ${info.director}\n")
        if (info.actors.isNotBlank())   sb.append("Cast:  ${info.actors}\n")
        if (sb.isNotEmpty()) sb.append("\n")
        val desc = when {
            info.description.isNotBlank() -> info.description
            meta?.overview != null -> meta.overview
            else -> "No description available for this title."
        }
        sb.append(desc)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(sb.toString())
            .setPositiveButton("Close", null)
            .setNeutralButton("🎬  Trailer") { _, _ -> watchTrailer(title, info.year) }
            .show()
    }

    /** "148" minutes → "2h 28m". */
    private fun runtimeStr(min: String): String {
        val m = min.toIntOrNull() ?: return ""
        if (m <= 0) return ""
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
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
        val inCategory = page.kind == SearchKind.VOD_CATEGORY
        pendingSearch?.let { searchHandler.removeCallbacks(it) }
        searchSeq++
        if (query.isEmpty()) {
            b.status.visibility = View.GONE
            // Clearing the search in a category restores its full (filter/sort-aware) list.
            if (inCategory && vodCatRef != null) loadVodAll(vodCatRef!!) else adapter.submit(page.rows)
            return
        }
        if (query.length < 2) return
        b.status.visibility = View.VISIBLE
        b.status.text = "Searching…"
        val seq = searchSeq
        val task = Runnable {
            io.execute {
                val vod = if (catId == null) Portal.vodSearch(query) else Portal.vodSearchInCategory(catId, query)
                runOnUiThread {
                    if (seq != searchSeq) return@runOnUiThread
                    if (vod.isEmpty()) { b.status.visibility = View.VISIBLE; b.status.text = "No results for “$query”." }
                    else b.status.visibility = View.GONE
                    if (inCategory) {
                        // Search results become the working set so Filter/Sort apply on top of them.
                        vodLoadSeq++ // cancel any in-flight "load all" so it can't overwrite results
                        vodBase = vod; vodCat = null; vodLoaded = 0; vodTotal = 0
                        renderVodItems(0)
                    } else {
                        adapter.submit(vod.map { vodItemRow(it) })
                    }
                }
            }
        }
        pendingSearch = task
        searchHandler.postDelayed(task, 450)
    }

    // ---- screens ----
    private fun showHome() {
        backStack.clear()
        updateProfileBadge()
        loadRecentlyAdded() // refreshes the Recently Added / For You rails for the active profile
        val rows = ArrayList<Row>()

        // Content rails (thumbnails) from local data — no portal calls, so the home is instant.
        val cw = Resume.all(this)
        if (cw.isNotEmpty()) rows.add(Row("Continue Watching", null, rail = cw.map { e ->
            val pct = if (e.duration > 0) (e.position * 100 / e.duration).toInt() else -1
            Card(e.title, e.poster.ifBlank { null }, pct, landscape = true, onLongClick = { cwCardMenu(e) }) { continueClick(e) }
        }) {})
        val favs = Favorites.all(this)
        if (favs.isNotEmpty()) rows.add(Row("Favourites", null, rail = favs.map { e ->
            Card(e.title, e.poster.ifBlank { null }) { openFavEntry(e) }
        }) {})
        val wl = WatchLater.all(this)
        if (wl.isNotEmpty()) rows.add(Row("Watch Later", null, rail = wl.map { e ->
            Card(e.title, e.poster.ifBlank { null }) { play(e.title, e.id, e.poster, e.source) }
        }) {})
        // "For You": the newest movies re-ranked by the user's taste (genres they've played).
        val recent = cachedRecent
        if (recent.isNotEmpty() && Taste.hasData(this) && !Configs.hideForYou(this)) {
            val ranked = recent.map { it to Taste.score(this, it.genre) }
                .filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
            if (ranked.isNotEmpty()) rows.add(Row("For You", null, rail = ranked.take(15).map { v ->
                Card(v.name, v.posterUrl.ifBlank { null }) {
                    if (v.isSeries) showSeasons(v)
                    else mediaActions(v.name, v.posterUrl, "movie_${v.id}", "vod|${v.id}|${v.cmd}", info = MovieInfo.from(v))
                }
            }) {})
        }
        // Newest movies from the portal (fetched in the background after connect; absent until ready).
        if (recent.isNotEmpty() && !Configs.hideRecentlyAdded(this)) rows.add(Row("Recently Added", null, rail = recent.map { v ->
            Card(v.name, v.posterUrl.ifBlank { null }) {
                if (v.isSeries) showSeasons(v)
                else mediaActions(v.name, v.posterUrl, "movie_${v.id}", "vod|${v.id}|${v.cmd}", info = MovieInfo.from(v))
            }
        }) {})

        // Destinations now live in the floating bottom tab bar (see onCreate / display).
        push(Page("Vibe TV", rows, kind = SearchKind.GLOBAL, rebuild = { showHome() }))
    }

    /** Long-press a Continue Watching card → remove just it, or clear the whole row. */
    private fun cwCardMenu(e: Resume.Entry) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(e.title)
            .setItems(arrayOf("🗑  Remove from Continue Watching", "🧹  Clear all Continue Watching")) { _, w ->
                when (w) {
                    0 -> { Resume.remove(this, e.id); showHome() }
                    1 -> androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Clear all Continue Watching?")
                        .setPositiveButton("Clear all") { _, _ -> Resume.clearAll(this); showHome() }
                        .setNegativeButton("Cancel", null).show()
                }
            }.show()
    }

    /** Open a favourite card from the home rail (movie → play, series → seasons). */
    private fun openFavEntry(e: Favorites.Entry) {
        if (e.kind == "series") showSeasons(Portal.VodItem(e.id, e.title, "", e.poster, true))
        else play(e.title, e.id, e.poster, e.source)
    }

    private var liveCatRows = listOf<Row>()
    private var liveAzBuilt = false

    /** Strimix-style Live TV entry: a left category panel over a blank dark screen. */
    private fun showLiveCategories() {
        if (allChannels.isEmpty()) { showLiveGenres(); return } // data not ready → old flow
        val rows = ArrayList<Row>()
        val favs = Configs.favorites(this)
        val favChannels = allChannels.filter { favs.contains(it.id) }
        // Only the categories/channels this profile is allowed to see.
        val visChannels = allChannels.filter { ContentProfiles.liveCatVisible(this, it.genreId) }
        // Keep the overlay up while the player launches (hidden in onStop) so home doesn't flash through.
        rows.add(Row("📺  TV Guide — what's on now", null, sortKey = "TV Guide") {
            EpgGuideActivity.channels = visChannels
            startActivity(Intent(this, EpgGuideActivity::class.java))
        })
        if (favChannels.isNotEmpty())
            rows.add(Row("⭐  Favourites  (${favChannels.size})", null, sortKey = "Favourites") { openLiveGrid(favChannels, "Favourites") })
        rows.add(Row("All Channels  (${visChannels.size})", null, sortKey = "All Channels") { openLiveGrid(visChannels, "All Channels") })
        for (g in genres) {
            if (!ContentProfiles.liveCatVisible(this, g.id)) continue
            val list = byGenre[g.id] ?: emptyList()
            if (list.isEmpty() && !g.censored) continue
            val label = (if (g.censored) "🔒  " else "") + g.title + (if (list.isNotEmpty()) "  (${list.size})" else "")
            rows.add(Row(label, null, sortKey = g.title) { openGenre(g) })
        }
        liveCatRows = rows
        liveCatAdapter.submit(rows)
        if (!liveAzBuilt) { buildLiveAzBar(); liveAzBuilt = true }
        b.liveCatOverlay.visibility = View.VISIBLE
        // Stop the remote from focusing the home content behind the overlay (keeps A–Z + list reachable).
        b.contentRoot.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        b.bottomNav.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        b.liveCatList.post { b.liveCatList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: b.liveCatList.requestFocus() }
    }

    private fun buildLiveAzBar() {
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
            tv.setOnClickListener { liveAzFilter(if (lbl == "ALL") null else lbl) }
            b.liveAzBar.addView(tv)
        }
    }

    private fun liveAzFilter(letter: String?) {
        liveCatAdapter.submit(
            if (letter == null) liveCatRows
            else liveCatRows.filter { it.sortKey.trimStart().startsWith(letter, ignoreCase = true) }
        )
        b.liveCatList.post { b.liveCatList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
    }

    private fun hideLiveCategories() {
        b.liveCatOverlay.visibility = View.GONE
        b.contentRoot.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        b.bottomNav.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
    }

    // ---- Content profiles ("Who's watching?") ----

    /** Top-bar avatar = active profile's initial on its colour (tap → switch profile). */
    private fun updateProfileBadge() {
        val p = ContentProfiles.active(this)
        b.profileBtn.text = p?.name?.firstOrNull()?.uppercaseChar()?.toString() ?: "👤"
        val d = android.graphics.drawable.GradientDrawable()
        d.shape = android.graphics.drawable.GradientDrawable.OVAL
        d.setColor(p?.color ?: 0x55FFFFFF.toInt())
        b.profileBtn.background = d
    }

    /** Show the picker once on first run (no profile yet). Afterwards we auto-enter (remember-last). */
    private fun maybeShowProfilePicker() {
        if (!ContentProfiles.setupSeen(this) && ContentProfiles.active(this) == null) {
            ContentProfiles.setSetupSeen(this)
            showProfilePicker()
        }
    }

    private fun showProfilePicker() {
        buildProfileTiles()
        b.profileOverlay.visibility = View.VISIBLE
        b.contentRoot.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        b.bottomNav.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        b.profileRow.post { (b.profileRow.getChildAt(0))?.requestFocus() }
    }

    private fun hideProfilePicker() {
        // Don't strand the user with no active profile when ones exist.
        if (ContentProfiles.activeId(this) == null) ContentProfiles.list(this).firstOrNull()?.let { ContentProfiles.setActive(this, it.id) }
        b.profileOverlay.visibility = View.GONE
        b.contentRoot.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        b.bottomNav.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
    }

    private fun buildProfileTiles() {
        b.profileRow.removeAllViews()
        val dp = resources.displayMetrics.density
        val activeId = ContentProfiles.activeId(this)
        for (p in ContentProfiles.list(this)) {
            b.profileRow.addView(profileTile(p.name, p.color, p.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                p.id == activeId,
                onClick = { ContentProfiles.setActive(this, p.id); hideProfilePicker(); showHome() },
                onLong = { editProfileMenu(p) }))
        }
        // "＋ Add" tile
        b.profileRow.addView(profileTile("Add", 0x33FFFFFF, "＋", false,
            onClick = { startActivity(Intent(this, ProfileEditActivity::class.java)) }, onLong = null))
        val pad = (8 * dp).toInt()
        for (i in 0 until b.profileRow.childCount) {
            (b.profileRow.getChildAt(i).layoutParams as android.widget.LinearLayout.LayoutParams).setMargins(pad, 0, pad, 0)
        }
    }

    private fun profileTile(name: String, color: Int, initial: String, active: Boolean,
                            onClick: () -> Unit, onLong: (() -> Unit)?): View {
        val dp = resources.displayMetrics.density
        val col = android.widget.LinearLayout(this)
        col.orientation = android.widget.LinearLayout.VERTICAL
        col.gravity = android.view.Gravity.CENTER_HORIZONTAL
        col.isFocusable = true
        col.isClickable = true
        col.setPadding((6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt())
        val circle = android.widget.TextView(this)
        val size = (96 * dp).toInt()
        circle.layoutParams = android.widget.LinearLayout.LayoutParams(size, size)
        circle.gravity = android.view.Gravity.CENTER
        circle.text = initial
        circle.textSize = 34f
        circle.setTextColor(0xFFFFFFFF.toInt())
        val d = android.graphics.drawable.GradientDrawable()
        d.shape = android.graphics.drawable.GradientDrawable.OVAL
        d.setColor(color)
        if (active) d.setStroke((3 * dp).toInt(), 0xFF19C37D.toInt())
        circle.background = d
        col.addView(circle)
        val label = android.widget.TextView(this)
        label.text = name
        label.setTextColor(0xFFE6EDF3.toInt())
        label.textSize = 14f
        label.gravity = android.view.Gravity.CENTER
        val llp = android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        llp.topMargin = (8 * dp).toInt()
        label.layoutParams = llp
        col.addView(label)
        col.setOnClickListener { onClick() }
        if (onLong != null) col.setOnLongClickListener { onLong(); true }
        col.setOnFocusChangeListener { v, f -> val s = if (f) 1.10f else 1f; v.animate().scaleX(s).scaleY(s).setDuration(120).start() }
        return col
    }

    private fun editProfileMenu(p: ContentProfiles.Profile) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(p.name)
            .setItems(arrayOf("✎  Edit", "🗑  Delete")) { _, w ->
                when (w) {
                    0 -> startActivity(Intent(this, ProfileEditActivity::class.java).putExtra("profileId", p.id))
                    1 -> androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Delete “${p.name}”?")
                        .setPositiveButton("Delete") { _, _ -> ContentProfiles.delete(this, p.id); buildProfileTiles() }
                        .setNegativeButton("Cancel", null).show()
                }
            }.show()
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
        val visChannels = allChannels.filter { ContentProfiles.liveCatVisible(this, it.genreId) }
        if (favChannels.isNotEmpty())
            rows.add(Row("⭐  Favourites  (${favChannels.size})", null, sortKey = "Favourites") { showLiveFavRoot() })
        rows.add(Row("All Channels  (${visChannels.size})", null, sortKey = "All Channels") { openLiveGrid(visChannels, "All Channels") })
        for (g in genres) {
            if (!ContentProfiles.liveCatVisible(this, g.id)) continue
            val list = byGenre[g.id] ?: emptyList()
            // Censored (adult/restricted) genres aren't returned by get_all_channels, so they look
            // empty here — show them anyway (locked) and load their channels on demand.
            if (list.isEmpty() && !g.censored) continue
            val label = (if (g.censored) "🔒  " else "") + g.title + (if (list.isNotEmpty()) "  (${list.size})" else "")
            rows.add(Row(label, null, sortKey = g.title) { openGenre(g) })
        }
        push(Page("Live TV", rows, kind = SearchKind.CHANNELS, scopeChannels = visChannels, rebuild = { showLiveGenres() }))
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
        // A grid of category chips (Favourites lives in the bottom tab bar, not here).
        // sortKey lets the A–Z bar jump to a category folder by first letter.
        val rows = vodCats.filter { ContentProfiles.vodCatVisible(this, it.id) }
            .map { c -> Row(c.title, null, sortKey = c.title, chip = true) { showVodList(c) } }
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
            val fav = vodFav("episode", e)
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

    /** Enter a movie category: one page on the stack; the whole folder is loaded up front (all pages),
     *  and A–Z / search / filter / sort swap the working set in place. */
    private fun showVodList(cat: Portal.VodCat) {
        vodCatRef = cat
        vodFilterAttr = null; vodFilterVal = null; vodLetter = null // fresh category: no filter / sub-search
        push(Page(cat.title, emptyList(), kind = SearchKind.VOD_CATEGORY, scopeId = cat.id, rebuild = { showVodList(cat) }))
        loadVodAll(cat)
    }

    /** Load the WHOLE category (every page) — no "Load more". Page 1 shows instantly, then the rest
     *  are fetched in parallel and merged so filter/sort always see all titles. */
    private fun loadVodAll(cat: Portal.VodCat) {
        val seq = ++vodLoadSeq
        b.status.visibility = View.VISIBLE
        b.status.text = "Loading ${cat.title}…"
        io.execute {
            val (first, pages) = Portal.vodList(cat.id, 1, "added")
            runOnUiThread {
                if (seq != vodLoadSeq) return@runOnUiThread
                vodBase = first; vodCat = cat; vodLoaded = 1; vodTotal = pages
                b.status.visibility = if (pages <= 1) View.GONE else View.VISIBLE
                if (pages > 1) b.status.text = "Loading all $pages pages…"
                renderVodItems(0)
                val posters = first.mapNotNull { it.posterUrl.ifBlank { null } }
                if (posters.size >= 8) Configs.setSplashPosters(applicationContext, posters)
            }
            if (pages <= 1) return@execute
            // Fetch pages 2..N concurrently; cap as a runaway guard.
            val last = pages.coerceAtMost(1000)
            val futures = (2..last).map { p ->
                pageIo.submit(java.util.concurrent.Callable { Portal.vodList(cat.id, p, "added").first })
            }
            val rest = ArrayList<Portal.VodItem>()
            for (f in futures) { try { rest.addAll(f.get()) } catch (_: Exception) {} }
            runOnUiThread {
                if (seq != vodLoadSeq) return@runOnUiThread
                vodBase = ArrayList<Portal.VodItem>(first).also { it.addAll(rest) }
                vodLoaded = pages
                b.status.visibility = View.GONE
                renderVodItems(0)
            }
        }
    }

    /** Apply the active filter + sort to the (fully loaded) set and render. No pagination rows. */
    private fun renderVodItems(focusPos: Int) {
        // Pipeline: filter → A–Z sub-search → sort (default sort = newest, i.e. the loaded order).
        var filtered = vodBase.filter { vodMatches(it) }
        vodLetter?.let { L -> filtered = filtered.filter { it.name.trimStart().startsWith(L, ignoreCase = true) } }
        val sorted = vodSortApply(filtered)
        val rows = ArrayList<Row>()
        sorted.forEach { rows.add(vodItemRow(it, poster = true)) }
        adapter.submit(rows)
        val pos = focusPos.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
        b.list.scrollToPosition(pos)
        b.list.post {
            // While the user is typing in the search box, don't yank focus to the list (it collapses
            // the search field / keyboard mid-search).
            if (b.searchRow.visibility == View.VISIBLE && b.search.hasFocus()) return@post
            val vh = b.list.findViewHolderForAdapterPosition(pos)
            if (vh != null) vh.itemView.requestFocus() else if (!b.list.hasFocus()) b.list.requestFocus()
        }
        updateVodButtons()
    }

    private fun vodDecade(year: String): String {
        val y = year.toIntOrNull() ?: return ""
        return "${(y / 10) * 10}s"
    }

    private fun vodMatches(v: Portal.VodItem): Boolean {
        val attr = vodFilterAttr ?: return true
        val value = vodFilterVal ?: return true
        return when (attr) {
            "Genre" -> v.genre == value
            "Year" -> v.year == value
            "Decade" -> vodDecade(v.year) == value
            "Age" -> v.age == value
            "Country" -> v.country.split(",").map { it.trim() }.any { it == value }
            "HD" -> (if (v.hd) "HD" else "SD") == value
            "Type" -> (if (v.isSeries) "Series" else "Movies") == value
            else -> true
        }
    }

    private fun vodSortApply(list: List<Portal.VodItem>): List<Portal.VodItem> = when (vodSortKey) {
        "az" -> list.sortedBy { it.name.trim().lowercase() }
        "za" -> list.sortedByDescending { it.name.trim().lowercase() }
        "year_desc" -> list.sortedByDescending { it.year.toIntOrNull() ?: 0 }
        "year_asc" -> list.sortedBy { it.year.toIntOrNull() ?: Int.MAX_VALUE }
        "run_asc" -> list.sortedBy { it.runtimeMin.toIntOrNull() ?: Int.MAX_VALUE }
        "run_desc" -> list.sortedByDescending { it.runtimeMin.toIntOrNull() ?: 0 }
        else -> list // "default" = the portal's newest-first order, as loaded
    }

    private fun updateVodButtons() {
        b.sortBtn.text = "⇅ ${vodSortLabels[vodSortKey] ?: "Sort"}"
        b.filterBtn.text = if (vodFilterAttr != null) "⛃ ${vodFilterVal}" else "⛃ Filter"
    }

    /** Sort: one dialog with every option (client-side over the loaded set). */
    private fun showSortDialog() {
        val keys = vodSortLabels.keys.toList()
        val labels = vodSortLabels.values.toTypedArray()
        val cur = keys.indexOf(vodSortKey).coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Sort movies")
            .setSingleChoiceItems(labels, cur) { d, w ->
                vodSortKey = keys[w]; d.dismiss(); renderVodItems(0)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Filter: pick an attribute, then one of its values present in the loaded set (single-select). */
    private fun showFilterDialog() {
        val attrs = listOf("Genre", "Year", "Decade", "Age", "Country", "HD", "Type")
        val items = (if (vodFilterAttr != null) listOf("✖  Clear filter") else emptyList()) + attrs
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Filter by")
            .setItems(items.toTypedArray()) { _, w ->
                val pick = items[w]
                if (pick.startsWith("✖")) { vodFilterAttr = null; vodFilterVal = null; renderVodItems(0) }
                else showFilterValues(pick)
            }
            .show()
    }

    private fun showFilterValues(attr: String) {
        val values: List<String> = when (attr) {
            "Genre" -> vodBase.map { it.genre }.filter { it.isNotBlank() }.distinct().sorted()
            "Year" -> vodBase.map { it.year }.filter { it.isNotBlank() }.distinct().sortedByDescending { it.toIntOrNull() ?: 0 }
            "Decade" -> vodBase.map { vodDecade(it.year) }.filter { it.isNotBlank() }.distinct().sortedDescending()
            "Age" -> vodBase.map { it.age }.filter { it.isNotBlank() }.distinct().sorted()
            "Country" -> vodBase.flatMap { it.country.split(",") }.map { it.trim() }.filter { it.isNotBlank() }.distinct().sorted()
            "HD" -> listOf("HD", "SD")
            "Type" -> listOf("Movies", "Series")
            else -> emptyList()
        }
        if (values.isEmpty()) {
            android.widget.Toast.makeText(this, "No “$attr” info available for these titles.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val arr = values.toTypedArray()
        val cur = if (vodFilterAttr == attr) values.indexOf(vodFilterVal) else -1
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(attr)
            .setSingleChoiceItems(arr, cur) { d, w ->
                vodFilterAttr = attr; vodFilterVal = values[w]; d.dismiss(); renderVodItems(0)
            }
            .setNegativeButton("Cancel", null)
            .show()
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
                    else "Couldn't open “$title” — ${Portal.lastErrorFriendly()}"
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
