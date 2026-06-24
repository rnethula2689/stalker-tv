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

    data class Row(val label: String, val iconUrl: String?, val sortKey: String = "", val action: () -> Unit)
    enum class SearchKind { LOCAL, GLOBAL, CHANNELS, VOD_ALL, VOD_CATEGORY }
    data class Page(
        val title: String,
        val rows: List<Row>,
        val kind: SearchKind = SearchKind.LOCAL,
        val scopeId: String? = null,
        val scopeChannels: List<Portal.Channel>? = null
    )

    private val backStack = ArrayDeque<Page>()
    private val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingSearch: Runnable? = null
    private var searchSeq = 0

    private var allChannels = listOf<Portal.Channel>()
    private var genres = listOf<Portal.Genre>()
    private var byGenre = mapOf<String, List<Portal.Channel>>()
    private var welcomeShown = false
    private var updateChecked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityChannelsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter

        b.search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = filter(s?.toString() ?: "")
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
        })
        b.clearBtn.setOnClickListener { b.search.setText(""); b.search.requestFocus() }
        buildAzBar()

        b.searchBtn.setOnClickListener { toggleSearch() }
        b.reloadBtn.setOnClickListener { connectAndLoad() }
        b.menuBtn.setOnClickListener { showMenu() }

        registerForegroundWatch()
        connectAndLoad()
        checkForUpdate()
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
                    0 -> connectAndLoad()
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
            connectAndLoad()
        }
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

    /** Read the active provider, connect in the background, then show the home menu. */
    private fun connectAndLoad() {
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
        showLoading("Connecting to portal…")
        setProgress(40, "Connecting to portal…", 2200) // creep up while the handshake runs
        io.execute {
            val err = Portal.connect() // resets the session and re-handshakes → a true fresh load
            if (err != null) {
                runOnUiThread {
                    hideLoading()
                    b.status.visibility = View.VISIBLE
                    b.status.text = err
                }
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
        if (b.search.text.isNotEmpty()) b.search.setText("")
        b.searchRow.visibility = View.GONE
        b.list.scrollToPosition(0)
        b.list.requestFocus()
    }

    override fun onBackPressed() {
        if (b.searchRow.visibility == View.VISIBLE) {
            b.search.setText("")
            b.searchRow.visibility = View.GONE
            return
        }
        if (backStack.size > 1) {
            backStack.removeLast()
            display(backStack.last())
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

    private fun channelRow(ch: Portal.Channel): Row {
        val label = "📺  " + (if (ch.number.isNotEmpty()) "${ch.number}. " else "") + ch.name
        return Row(label, ch.logoUrl, sortKey = ch.name) { playChannel(ch) }
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
        return Row(label, v.posterUrl, sortKey = v.name) {
            if (v.isSeries) showSeasons(v) else play(v.name) { Portal.playVodUrl(v.id, v.cmd) }
        }
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
        push(
            Page(
                "Stalker TV",
                listOf(
                    Row("📺   Live TV", null) { showLiveGenres() },
                    Row("🎬   Movies (VOD)", null) { showVodCategories() }
                ),
                kind = SearchKind.GLOBAL
            )
        )
    }

    private fun showLiveGenres() {
        val rows = ArrayList<Row>()
        rows.add(Row("All Channels  (${allChannels.size})", null, sortKey = "All Channels") { openLiveGrid(allChannels, "All Channels") })
        for (g in genres) {
            val list = byGenre[g.id] ?: emptyList()
            // Censored (adult/restricted) genres aren't returned by get_all_channels, so they look
            // empty here — show them anyway (locked) and load their channels on demand.
            if (list.isEmpty() && !g.censored) continue
            val label = (if (g.censored) "🔒  " else "") + g.title + (if (list.isNotEmpty()) "  (${list.size})" else "")
            rows.add(Row(label, null, sortKey = g.title) { openGenre(g) })
        }
        push(Page("Live TV", rows, kind = SearchKind.CHANNELS, scopeChannels = allChannels))
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
                push(Page("Movies", cats.map { c -> Row(c.title, null, sortKey = c.title) { showVodList(c) } }, kind = SearchKind.VOD_ALL))
            }
        }
    }

    private fun showVodList(cat: Portal.VodCat) {
        b.status.visibility = View.VISIBLE
        b.status.text = "Loading ${cat.title}…"
        io.execute {
            val (items, pages) = Portal.vodList(cat.id, 1)
            runOnUiThread {
                b.status.visibility = View.GONE
                push(Page(cat.title, vodRows(cat, ArrayList(items), 1, pages), kind = SearchKind.VOD_CATEGORY, scopeId = cat.id))
            }
        }
    }

    private fun vodRows(cat: Portal.VodCat, acc: ArrayList<Portal.VodItem>, loaded: Int, total: Int): List<Row> {
        val rows = ArrayList<Row>()
        acc.forEach { v -> rows.add(vodItemRow(v)) }
        if (loaded < total) {
            rows.add(Row("⬇  Load more  ($loaded/$total)", null) {
                b.status.visibility = View.VISIBLE
                b.status.text = "Loading…"
                io.execute {
                    val (more, _) = Portal.vodList(cat.id, loaded + 1)
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
                    Row(s.name, null) { showEpisodes(series, s) }
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
                push(Page("${series.name} — ${season.name}", eps.reversed().map { e ->
                    Row(e.name, null) {
                        play("${series.name}  /  ${season.name}  /  ${e.name}") {
                            Portal.playEpisodeUrl(series.id, season.id, e.id)
                        }
                    }
                }))
            }
        }
    }

    private fun play(title: String, resolve: () -> String?) {
        b.status.visibility = View.VISIBLE
        b.status.text = "Opening $title…"
        playIo.execute {
            val url = resolve()
            runOnUiThread {
                if (url.isNullOrEmpty()) {
                    b.status.visibility = View.VISIBLE
                    val why = Portal.lastError
                    b.status.text = if (why == "nothing_to_play")
                        "“$title” — no stream returned. Either the provider's storage is down, or your account's connection limit is reached (another device is already streaming)."
                    else "Couldn't open “$title” — $why"
                } else {
                    b.status.visibility = View.GONE
                    startActivity(
                        Intent(this, PlayerActivity::class.java)
                            .putExtra("url", url)
                            .putExtra("title", title)
                    )
                }
            }
        }
    }
}
