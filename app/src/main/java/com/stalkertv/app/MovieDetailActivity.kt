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
 * Rich movie details (Strimix-style): backdrop, poster, tagline, ratings, expandable overview,
 * a Trailers rail and a Cast rail — plus all the actions (Play / Favourite / Watch later / Download).
 * Replaces the old action-sheet popup. Portal data shows instantly; TMDb enriches in the background.
 */
class MovieDetailActivity : AppCompatActivity() {
    private lateinit var b: ActivityMovieDetailBinding
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var vodId: String
    private lateinit var title: String
    private var poster: String = ""
    private var year: String = ""
    private lateinit var source: String
    private lateinit var resumeId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        b = ActivityMovieDetailBinding.inflate(layoutInflater)
        setContentView(b.root)

        vodId = intent.getStringExtra("vodId") ?: ""
        title = intent.getStringExtra("title") ?: "Movie"
        poster = intent.getStringExtra("poster") ?: ""
        year = intent.getStringExtra("year") ?: ""
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
        b.dlBtn.setOnClickListener { download() }
        b.laterBtn.setOnClickListener {
            val added = WatchLater.add(applicationContext, "movie", resumeId, title, poster, source)
            toast(if (added) "Added to Watch Later" else "Already in Watch Later")
        }
        b.favBtn.setOnClickListener {
            val added = Favorites.toggle(this, Favorites.Entry("movie", resumeId, title, poster, source))
            refreshFav(added)
        }
        b.moreBtn.setOnClickListener {
            b.overview.maxLines = if (b.overview.maxLines > 100) 5 else 1000
            b.moreBtn.text = if (b.overview.maxLines > 100) "Less" else "More"
        }
        refreshFav(Favorites.all(this).any { it.id == resumeId })
        b.playBtn.requestFocus()

        loadTmdb(genre)
    }

    private fun toast(m: String) = android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()

    private fun refreshFav(isFav: Boolean) { b.favBtn.text = if (isFav) "★  Favourited" else "☆  Favourite" }

    // ---- actions ----
    private fun play() {
        Taste.record(applicationContext, intent.getStringExtra("genre") ?: "")
        val r = Resume.get(this, resumeId)
        if (Resume.resumable(r)) {
            AlertDialog.Builder(this).setTitle(title)
                .setItems(arrayOf("▶  Resume from ${fmtTime(r!!.position)}", "↻  Start from beginning")) { _, w ->
                    startPlayer(if (w == 0) r.position else 0L)
                }.show()
        } else startPlayer(0L)
    }

    private fun startPlayer(startPos: Long) {
        toast("Opening…")
        io.execute {
            val url = Downloads.resolveSource(source)
            runOnUiThread {
                if (url.isNullOrEmpty()) { toast("Couldn't open “$title” — ${Portal.lastErrorFriendly()}"); return@runOnUiThread }
                startActivity(Intent(this, PlayerActivity::class.java)
                    .putExtra("url", url).putExtra("title", title)
                    .putExtra("resumeId", resumeId).putExtra("resumeSource", source)
                    .putExtra("resumePoster", poster).putExtra("resumeStart", startPos))
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

    // ---- TMDb enrichment ----
    private fun loadTmdb(portalGenre: String) {
        val key = BuildConfig.TMDB_KEY
        if (key.isBlank()) return
        io.execute {
            val d = Tmdb.details(key, title, year) ?: return@execute
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                d.backdropUrl?.let { b.backdrop.load(it) }
                if (poster.isBlank()) d.posterUrl?.let { b.poster.load(it) }
                if (d.tagline.isNotBlank()) { b.tagline.text = d.tagline; b.tagline.visibility = View.VISIBLE }
                if (d.overview.isNotBlank()) {
                    b.overview.text = d.overview
                    b.overview.post { if (b.overview.lineCount > 5) b.moreBtn.visibility = View.VISIBLE }
                }
                val genres = if (d.genres.isNotEmpty()) d.genres.joinToString(", ") else portalGenre
                val rating = if (d.rating > 0) "★ %.1f".format(d.rating) else ""
                val runtime = if (d.runtimeMin > 0) "${d.runtimeMin / 60}h ${d.runtimeMin % 60}m" else ""
                val date = d.releaseDate.takeIf { it.length >= 4 }?.let { prettyDate(it) } ?: year
                b.meta.text = listOf(genres, rating, date, runtime).filter { it.isNotBlank() }.joinToString("   ·   ")
                buildTrailers(d.trailers)
                buildCast(d.cast)
            }
        }
    }

    private fun prettyDate(ymd: String): String = try {
        val out = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US)
        out.format(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(ymd)!!)
    } catch (_: Exception) { ymd.take(4) }

    private fun buildTrailers(trailers: List<Tmdb.Trailer>) {
        if (trailers.isEmpty()) return
        val dp = resources.displayMetrics.density
        b.trailersHeader.visibility = View.VISIBLE; b.trailersScroll.visibility = View.VISIBLE
        b.trailersRow.removeAllViews()
        for (t in trailers) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = true; isClickable = true
                setPadding((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
                background = androidx.core.content.ContextCompat.getDrawable(this@MovieDetailActivity, R.drawable.item_bg)
                val lp = LinearLayout.LayoutParams((200 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.marginEnd = (10 * dp).toInt(); layoutParams = lp
                setOnFocusChangeListener { v, f -> val s = if (f) 1.06f else 1f; v.animate().scaleX(s).scaleY(s).setDuration(120).start() }
            }
            val thumb = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams((192 * dp).toInt(), (108 * dp).toInt())
                scaleType = ImageView.ScaleType.CENTER_CROP
                load(t.thumbUrl)
            }
            val label = TextView(this).apply {
                text = "▶  ${t.name}"; setTextColor(0xFFE6EDF3.toInt()); textSize = 12f
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, (4 * dp).toInt(), 0, 0)
            }
            card.addView(thumb); card.addView(label)
            card.setOnClickListener {
                startActivity(Intent(this, TrailerActivity::class.java).putExtra("videoId", t.youtubeKey))
            }
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
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0x22FFFFFF)
                if (c.profileUrl != null) load(c.profileUrl) { transformations(CircleCropTransformation()) }
            }
            val name = TextView(this).apply {
                text = c.name; setTextColor(0xFFE6EDF3.toInt()); textSize = 12f
                gravity = android.view.Gravity.CENTER; maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, (4 * dp).toInt(), 0, 0)
            }
            val role = TextView(this).apply {
                text = c.character; setTextColor(0xFF8b97a5.toInt()); textSize = 10f
                gravity = android.view.Gravity.CENTER; maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            col.addView(pic); col.addView(name); if (c.character.isNotBlank()) col.addView(role)
            b.castRow.addView(col)
        }
    }

    override fun onResume() { super.onResume(); refreshFav(Favorites.all(this).any { it.id == resumeId }) }
    override fun onDestroy() { super.onDestroy(); io.shutdownNow() }
}
