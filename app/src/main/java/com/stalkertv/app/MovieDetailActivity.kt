package com.stalkertv.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import coil.load
import coil.transform.CircleCropTransformation
import com.stalkertv.app.databinding.ActivityMovieDetailBinding
import java.util.concurrent.Executors

/**
 * Rich details for a movie OR series (Strimix-style): blurred backdrop, poster, tagline, ratings,
 * expandable overview, Trailers + Cast rails, and — for series — a season selector + episode rail.
 * All actions (Play / Favourite / Watch later / Download) live here; replaces the old popup.
 * Adapts portrait/landscape automatically (res/layout + res/layout-land).
 */
class MovieDetailActivity : AppCompatActivity() {
    private lateinit var b: ActivityMovieDetailBinding
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var vodId: String
    private lateinit var title: String
    private var poster: String = ""
    private var year: String = ""
    private var isSeries: Boolean = false
    private lateinit var source: String       // movie source ("vod|id|cmd"); unused for series
    private lateinit var resumeId: String     // movie resumeId; episodes have their own

    private var seasons: List<Portal.Season> = emptyList()
    private var curSeason: Portal.Season? = null
    private var firstEp: Portal.Episode? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        b = ActivityMovieDetailBinding.inflate(layoutInflater)
        setContentView(b.root)

        vodId = intent.getStringExtra("vodId") ?: ""
        title = intent.getStringExtra("title") ?: "Title"
        poster = intent.getStringExtra("poster") ?: ""
        year = intent.getStringExtra("year") ?: ""
        isSeries = intent.getBooleanExtra("isSeries", false)
        val cmd = intent.getStringExtra("cmd") ?: ""
        val genre = intent.getStringExtra("genre") ?: ""
        val imdb = intent.getStringExtra("imdb") ?: ""
        resumeId = "movie_$vodId"
        source = "vod|$vodId|$cmd"

        b.title.text = title
        if (poster.isNotBlank()) b.poster.load(poster)
        b.meta.text = listOf(genre, (if (imdb.isNotBlank()) "★ $imdb" else ""), year)
            .filter { it.isNotBlank() }.joinToString("   ·   ")

        b.playBtn.setOnClickListener { play() }
        b.laterBtn.setOnClickListener {
            val kind = if (isSeries) "series" else "movie"
            val added = WatchLater.add(applicationContext, kind, favId(), title, poster, favSource())
            toast(if (added) "Added to Watch Later" else "Already in Watch Later")
        }
        b.favBtn.setOnClickListener {
            val added = Favorites.toggle(this, Favorites.Entry(if (isSeries) "series" else "movie", favId(), title, poster, favSource()))
            refreshFav(added)
        }
        b.moreBtn.setOnClickListener {
            b.overview.maxLines = if (b.overview.maxLines > 100) 5 else 1000
            b.moreBtn.text = if (b.overview.maxLines > 100) "Less" else "More"
        }
        refreshFav(Favorites.all(this).any { it.id == favId() })

        if (isSeries) {
            b.dlBtn.visibility = View.GONE // downloads are per-episode
            setupSeries()
        } else {
            b.dlBtn.setOnClickListener { download() }
        }
        b.playBtn.requestFocus()
        loadTmdb(genre)
    }

    private fun favId() = if (isSeries) "series_$vodId" else resumeId
    private fun favSource() = if (isSeries) "series|$vodId" else source
    private fun toast(m: String) = android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()
    private fun refreshFav(isFav: Boolean) {
        b.favBtn.text = if (isFav) "★" else "☆"
        b.favBtn.setTextColor(if (isFav) 0xFFFFC107.toInt() else 0xFFFFFFFF.toInt()) // gold when favourited
    }

    // ---- play ----
    private fun play() {
        if (isSeries) { firstEp?.let { e -> curSeason?.let { s -> playEpisode(s, e) } } ?: toast("Loading episodes…"); return }
        Taste.record(applicationContext, intent.getStringExtra("genre") ?: "")
        val r = Resume.get(this, resumeId)
        if (Resume.resumable(r)) {
            AlertDialog.Builder(this).setTitle(title)
                .setItems(arrayOf("▶  Resume from ${fmtTime(r!!.position)}", "↻  Start from beginning")) { _, w ->
                    startPlayerWith(title, resumeId, source, if (w == 0) r.position else 0L)
                }.show()
        } else startPlayerWith(title, resumeId, source, 0L)
    }

    private fun playEpisode(s: Portal.Season, e: Portal.Episode) {
        // Include the SEASON in the title so the subtitle search can build the right SxxExx query —
        // "… — Episode 2" alone made it default to Season 1 (wrong for a season-29 show).
        val label = if (s.name.contains("season", ignoreCase = true)) "$title — ${s.name}, ${e.name}"
                    else "$title — Season ${s.name}, ${e.name}"
        startPlayerWith(label, "ep_${vodId}_${s.id}_${e.id}", "ep|$vodId|${s.id}|${e.id}", 0L)
    }

    private fun startPlayerWith(title: String, resumeId: String, source: String, startPos: Long) {
        toast("Opening…")
        io.execute {
            val url = Downloads.resolveSource(source)
            runOnUiThread {
                if (url.isNullOrEmpty()) { toast("Couldn't open “$title” — ${Portal.lastErrorFriendly()}"); return@runOnUiThread }
                startActivity(Intent(this, PlayerActivity::class.java)
                    .putExtra("url", url).putExtra("title", title)
                    .putExtra("resumeId", resumeId).putExtra("resumeSource", source)
                    .putExtra("resumePoster", poster).putExtra("resumeStart", startPos)
                    .putExtra("year", year))  // narrows OpenSubtitles to this exact release
            }
        }
    }

    private fun download() {
        if (Downloads.has(applicationContext, resumeId)) { toast("Already saved (or downloading). See Downloads."); return }
        Downloads.enqueue(applicationContext, resumeId, title, poster, source)
        toast("Download started — see Downloads.")
    }

    private fun fmtTime(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    // ---- series: seasons + episodes ----
    private fun setupSeries() {
        b.seasonBtn.setOnClickListener { pickSeason() }
        io.execute {
            val ss = Portal.seriesSeasons(vodId)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                seasons = ss
                if (ss.isEmpty()) return@runOnUiThread
                b.seasonBtn.visibility = View.VISIBLE
                b.episodesScroll.visibility = View.VISIBLE
                selectSeason(ss.first())
            }
        }
    }

    private fun pickSeason() {
        if (seasons.isEmpty()) return
        AlertDialog.Builder(this).setTitle("Select season")
            .setItems(seasons.map { it.name }.toTypedArray()) { _, w -> selectSeason(seasons[w]) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun selectSeason(s: Portal.Season) {
        curSeason = s
        b.seasonBtn.text = "${s.name}  ▾"
        b.episodesRow.removeAllViews()
        io.execute {
            val eps = Portal.seriesEpisodes(vodId, s.id).reversed() // portal is newest-first → E1..En
            runOnUiThread {
                if (isFinishing || curSeason?.id != s.id) return@runOnUiThread
                firstEp = eps.firstOrNull()
                buildEpisodes(eps, s)
            }
        }
    }

    private fun buildEpisodes(eps: List<Portal.Episode>, s: Portal.Season) {
        val dp = resources.displayMetrics.density
        val thumbs = ArrayList<ImageView>() // aligned with eps; TMDb stills may replace these async
        for (e in eps) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = true; isClickable = true
                setPadding((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
                background = androidx.core.content.ContextCompat.getDrawable(this@MovieDetailActivity, R.drawable.item_bg)
                val lp = LinearLayout.LayoutParams((200 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.marginEnd = (10 * dp).toInt(); layoutParams = lp
                setOnFocusChangeListener { v, f -> val sc = if (f) 1.06f else 1f; v.animate().scaleX(sc).scaleY(sc).setDuration(120).start() }
            }
            val frame = android.widget.FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams((192 * dp).toInt(), (108 * dp).toInt())
            }
            val thumb = ImageView(this).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0x22FFFFFF)
                val epShot = e.screenshot.ifBlank { poster } // real per-episode still when the portal has one
                if (epShot.isNotBlank()) load(epShot)
            }
            thumbs.add(thumb)
            val badge = TextView(this).apply {
                text = "📥"; textSize = 13f
                setPadding((6 * dp).toInt(), (2 * dp).toInt(), (6 * dp).toInt(), (2 * dp).toInt())
                setBackgroundColor(0xAA000000.toInt())
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.START }
            }
            frame.addView(thumb); frame.addView(badge)
            val label = TextView(this).apply {
                text = "▶  ${e.name}"; setTextColor(0xFFE6EDF3.toInt()); textSize = 12f
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, (4 * dp).toInt(), 0, 0)
            }
            card.addView(frame); card.addView(label)
            card.setOnClickListener { playEpisode(s, e) }
            card.setOnLongClickListener { episodeMenu(s, e); true } // OK-hold on TV → Play / Download
            b.episodesRow.addView(card)
        }
        maybeLoadTmdbStills(eps, s, thumbs)
    }

    private var tmdbTvId = 0 // cached TMDb series id (resolved once, reused across seasons)

    /** Some providers repeat ONE series-level image in screenshot_uri for every episode (all cards
     *  look identical). When that happens, swap in the real per-episode stills from TMDb — the same
     *  source Strimix shows. Portal stills that genuinely differ per episode are left untouched. */
    private fun maybeLoadTmdbStills(eps: List<Portal.Episode>, s: Portal.Season, thumbs: List<ImageView>) {
        if (eps.isEmpty() || eps.map { it.screenshot }.distinct().size > 1) return // portal stills are real
        val key = BuildConfig.TMDB_KEY
        if (key.isBlank()) return
        val seasonNum = Regex("\\d+").find(s.name)?.value?.toIntOrNull() ?: return
        io.execute {
            val id = tmdbTvId.takeIf { it > 0 }
                ?: Tmdb.tvIdFor(key, title, year)?.also { tmdbTvId = it }
                ?: return@execute
            val stills = Tmdb.seasonStills(key, id, seasonNum)
            if (stills.isEmpty()) return@execute
            runOnUiThread {
                if (isFinishing || curSeason?.id != s.id) return@runOnUiThread
                thumbs.forEachIndexed { i, iv ->
                    // Episode number from the name ("Episode 3. …" → 3), falling back to list order.
                    val epNum = Regex("\\d+").find(eps[i].name)?.value?.toIntOrNull() ?: (i + 1)
                    stills[epNum]?.let { iv.load(it) }
                }
            }
        }
    }

    private fun episodeMenu(s: Portal.Season, e: Portal.Episode) {
        AlertDialog.Builder(this).setTitle(e.name)
            .setItems(arrayOf("▶  Play", "📥  Download")) { _, w -> if (w == 0) playEpisode(s, e) else downloadEpisode(s, e) }
            .show()
    }

    private fun downloadEpisode(s: Portal.Season, e: Portal.Episode) {
        val id = "ep_${vodId}_${s.id}_${e.id}"
        if (Downloads.has(applicationContext, id)) { toast("Already saved (or downloading). See Downloads."); return }
        Downloads.enqueue(applicationContext, id, "$title — ${e.name}", poster, "ep|$vodId|${s.id}|${e.id}")
        toast("Download started — see Downloads.")
    }

    // ---- TMDb enrichment ----
    private fun loadTmdb(portalGenre: String) {
        val key = BuildConfig.TMDB_KEY
        if (key.isBlank()) return
        io.execute {
            val d = Tmdb.details(key, title, year, isSeries) ?: return@execute
            // Query OMDb with the canonical title/year TMDb resolved (the raw portal title often has
            // provider/quality junk that OMDb's stricter title match rejects → missing RT/Metacritic).
            val omTitle = d.title.ifBlank { title }
            val omYear = d.releaseDate.take(4).ifBlank { year }
            val om = if (BuildConfig.OMDB_KEY.isNotBlank()) Omdb.ratings(BuildConfig.OMDB_KEY, omTitle, omYear) else null
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (d.title.isNotBlank()) b.title.text = d.title
                (d.backdropUrl ?: d.posterUrl ?: poster.ifBlank { null })?.let { b.backdrop.load(it) }
                if (poster.isBlank()) d.posterUrl?.let { b.poster.load(it) }
                if (d.tagline.isNotBlank()) { b.tagline.text = d.tagline; b.tagline.visibility = View.VISIBLE }
                if (d.overview.isNotBlank()) {
                    b.overview.text = d.overview
                    b.overview.post { if (b.overview.lineCount > b.overview.maxLines) b.moreBtn.visibility = View.VISIBLE }
                }
                val genres = if (d.genres.isNotEmpty()) d.genres.joinToString(", ") else portalGenre
                val runtime = if (d.runtimeMin > 0) "${d.runtimeMin / 60}h ${d.runtimeMin % 60}m" else ""
                val date = d.releaseDate.takeIf { it.length >= 4 }?.let { prettyDate(it) } ?: year
                val rateParts = ArrayList<String>()
                if (om?.imdb != null) rateParts.add("IMDb ${om.imdb}") else if (d.rating > 0) rateParts.add("★ %.1f".format(d.rating))
                om?.rottenTomatoes?.let { rateParts.add("🍅 $it") }
                om?.metacritic?.let { rateParts.add("Ⓜ ${it.substringBefore("/")}") }
                b.meta.text = (listOf(genres) + rateParts + listOf(date, runtime)).filter { it.isNotBlank() }.joinToString("   ·   ")
                buildTrailers(d.trailers)
                buildCast(d.cast)
            }
        }
    }

    private fun prettyDate(ymd: String): String = try {
        java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US)
            .format(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(ymd)!!)
    } catch (_: Exception) { ymd.take(4) }

    private fun buildTrailers(trailers: List<Tmdb.Trailer>) {
        if (trailers.isEmpty()) return
        val dp = resources.displayMetrics.density
        b.trailersHeader.visibility = View.VISIBLE; b.trailersScroll.visibility = View.VISIBLE
        b.trailersRow.removeAllViews()
        for (t in trailers) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; isFocusable = true; isClickable = true
                setPadding((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
                background = androidx.core.content.ContextCompat.getDrawable(this@MovieDetailActivity, R.drawable.item_bg)
                val lp = LinearLayout.LayoutParams((200 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.marginEnd = (10 * dp).toInt(); layoutParams = lp
                setOnFocusChangeListener { v, f -> val s = if (f) 1.06f else 1f; v.animate().scaleX(s).scaleY(s).setDuration(120).start() }
            }
            val thumb = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams((192 * dp).toInt(), (108 * dp).toInt())
                scaleType = ImageView.ScaleType.CENTER_CROP; load(t.thumbUrl)
            }
            val label = TextView(this).apply {
                text = "▶  ${t.name}"; setTextColor(0xFFE6EDF3.toInt()); textSize = 12f
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, (4 * dp).toInt(), 0, 0)
            }
            card.addView(thumb); card.addView(label)
            card.setOnClickListener { startActivity(Intent(this, TrailerActivity::class.java).putExtra("videoId", t.youtubeKey)) }
            b.trailersRow.addView(card)
        }
    }

    private fun buildCast(cast: List<Tmdb.CastMember>) {
        if (cast.isEmpty()) return
        val dp = resources.displayMetrics.density
        b.castHeader.visibility = View.VISIBLE; b.castScroll.visibility = View.VISIBLE
        b.castRow.removeAllViews()
        for (c in cast) {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_HORIZONTAL
                val lp = LinearLayout.LayoutParams((92 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.marginEnd = (10 * dp).toInt(); layoutParams = lp
            }
            val pic = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams((76 * dp).toInt(), (76 * dp).toInt())
                scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0x22FFFFFF)
                if (c.profileUrl != null) load(c.profileUrl) { transformations(CircleCropTransformation()) }
            }
            val name = TextView(this).apply {
                text = c.name; setTextColor(0xFFE6EDF3.toInt()); textSize = 12f
                gravity = android.view.Gravity.CENTER; maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, (4 * dp).toInt(), 0, 0)
            }
            val role = TextView(this).apply {
                text = c.character; setTextColor(0xFF8b97a5.toInt()); textSize = 10f
                gravity = android.view.Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }
            col.addView(pic); col.addView(name); if (c.character.isNotBlank()) col.addView(role)
            b.castRow.addView(col)
        }
    }

    override fun onResume() { super.onResume(); refreshFav(Favorites.all(this).any { it.id == favId() }) }
    override fun onDestroy() { super.onDestroy(); io.shutdownNow() }
}
