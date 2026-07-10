package com.stalkertv.app

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import java.util.concurrent.Executors

/**
 * Strimix-style subtitle picker used by BOTH players:
 *   💬 Subtitles                                   ✕
 *   [ 🔍  <editable query> ]  [ 🌐 English ⌄ ]  [ → Search ]
 *   💬 name • N downloads                          ⬇   (tap a row to apply)
 * The query is editable, the language dropdown persists the last choice, and results show each
 * subtitle's download count (quality signal). Auto-searches on open.
 */
object SubtitleDialog {

    fun show(a: Activity, initialQuery: String, year: String = "", onPick: (Subtitles.Sub) -> Unit) {
        val dp = a.resources.displayMetrics.density
        fun pad(v: View, l: Int, t: Int, r: Int, b: Int) =
            v.setPadding((l * dp).toInt(), (t * dp).toInt(), (r * dp).toInt(), (b * dp).toInt())
        fun pill(color: Int, radiusDp: Float = 12f) = GradientDrawable().apply {
            setColor(color); cornerRadius = radiusDp * dp
        }

        val io = Executors.newSingleThreadExecutor()
        var lang = savedLang(a)

        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; pad(this, 20, 16, 20, 16) }

        // ---- header: 💬 Subtitles ................ ✕ ----
        val header = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(a).apply {
            text = "💬  Subtitles"; textSize = 18f; setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = TextView(a).apply {
            text = "✕"; textSize = 18f; setTextColor(0xFFB9C4CF.toInt())
            isFocusable = true; isClickable = true
            pad(this, 10, 4, 10, 4)
            background = pill(0xFF232A33.toInt(), 18f)
        }
        header.addView(title); header.addView(closeBtn)
        root.addView(header)

        // ---- search row: [🔍 query] [🌐 English ⌄] [→ Search] ----
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (14 * dp).toInt() }
        }
        val queryBox = EditText(a).apply {
            setText(initialQuery); setSelection(text.length)
            hint = "Search subtitles…"; setHintTextColor(0xFF6B7885.toInt())
            setTextColor(Color.WHITE); textSize = 14f; maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            background = pill(0xFF10151B.toInt())
            pad(this, 14, 10, 14, 10)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val langBtn = TextView(a).apply {
            textSize = 13f; setTextColor(Color.WHITE)
            isFocusable = true; isClickable = true; maxLines = 1
            background = pill(0xFF232A33.toInt())
            pad(this, 12, 10, 12, 10)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (8 * dp).toInt() }
        }
        fun renderLang() { langBtn.text = "🌐 ${lang.label}  ⌄" }
        renderLang()
        val searchBtn = TextView(a).apply {
            text = "→  Search"; textSize = 13f; setTextColor(0xFF04120C.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            isFocusable = true; isClickable = true; maxLines = 1
            background = pill(0xFFF2F4F6.toInt())
            pad(this, 14, 10, 14, 10)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (8 * dp).toInt() }
        }
        row.addView(queryBox); row.addView(langBtn); row.addView(searchBtn)
        root.addView(row)

        // ---- status line (✓ token / searching / no results) ----
        val status = TextView(a).apply {
            textSize = 12f; setTextColor(0xFF19C37D.toInt())
            text = if (Subtitles.apiKey.isNotBlank()) "✓  Using OpenSubtitles API" else "Using keyless search"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * dp).toInt() }
        }
        root.addView(status)

        // ---- results (scrollable rows) ----
        val listWrap = ScrollView(a).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (330 * dp).toInt()
            ).apply { topMargin = (10 * dp).toInt() }
            isFillViewport = true
        }
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        listWrap.addView(list)
        root.addView(listWrap)

        val dlg = AlertDialog.Builder(a).setView(root).create()
        dlg.window?.setBackgroundDrawable(pill(0xFF161B22.toInt(), 20f))
        dlg.setOnDismissListener { io.shutdownNow() }
        closeBtn.setOnClickListener { dlg.dismiss() }

        var searchSeq = 0
        /** [strict] = an automatic search (dialog open / language change): junk-free — identity
         *  match or clean "not found", like Strimix. Pressing Search runs the broad text match. */
        fun runSearch(strict: Boolean) {
            val q = queryBox.text.toString().trim()
            if (q.isEmpty()) return
            val mine = ++searchSeq
            status.setTextColor(0xFF9AA6B2.toInt())
            status.text = "Searching ${lang.label} subtitles…"
            list.removeAllViews()
            io.execute {
                // Movies: resolve the EXACT feature via TMDb and ask for its subtitles by id — a text
                // query fuzzy-matches junk ("David" → David & Lisa 1962), the id returns only the real
                // movie's releases (how Strimix gets clean results). Episodes keep the S01E02 text query.
                // Strict (auto) searches filter text results to ones that actually NAME the title, so a
                // regional movie unknown to OpenSubtitles shows "not found" instead of unrelated junk.
                val results = try {
                    val isEpisode = Regex("S\\d{1,2}E\\d{1,3}", RegexOption.IGNORE_CASE).containsMatchIn(q)
                    val tmdbId = if (!isEpisode && Subtitles.apiKey.isNotBlank() && BuildConfig.TMDB_KEY.isNotBlank())
                        Tmdb.movieIdFor(BuildConfig.TMDB_KEY, q, if (q == initialQuery) year else "") ?: 0 else 0
                    val byId = if (tmdbId > 0) Subtitles.searchByTmdb(tmdbId, lang) else emptyList()
                    when {
                        byId.isNotEmpty() -> byId
                        strict -> relevantOnly(Subtitles.search(q, lang = lang), q)
                        else -> Subtitles.search(q, lang = lang)
                    }
                } catch (_: Exception) { emptyList() }
                a.runOnUiThread {
                    if (mine != searchSeq || !dlg.isShowing) return@runOnUiThread
                    if (results.isEmpty()) {
                        status.setTextColor(0xFFFF8A8A.toInt())
                        status.text = if (strict)
                            "⚠  No ${lang.label} subtitles found for this title.  Edit the title or press Search for a broader match."
                        else "No ${lang.label} subtitles found for “$q”."
                        return@runOnUiThread
                    }
                    status.setTextColor(0xFF19C37D.toInt())
                    status.text = "${results.size} result${if (results.size == 1) "" else "s"}"
                    for (sub in results) {
                        val r = LinearLayout(a).apply {
                            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                            isFocusable = true; isClickable = true
                            background = pill(0xFF10151B.toInt())
                            pad(this, 12, 10, 12, 10)
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { topMargin = (8 * dp).toInt() }
                            setOnFocusChangeListener { v, f -> v.alpha = if (f) 1f else 0.92f; v.scaleX = if (f) 1.01f else 1f; v.scaleY = if (f) 1.01f else 1f }
                        }
                        r.addView(TextView(a).apply { text = "💬"; textSize = 14f; pad(this, 0, 0, 10, 0) })
                        r.addView(TextView(a).apply {
                            text = sub.label; textSize = 13f; setTextColor(0xFFE6EDF3.toInt())
                            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        })
                        r.addView(TextView(a).apply { text = "⬇"; textSize = 15f; setTextColor(0xFF9AA6B2.toInt()); pad(this, 10, 0, 0, 0) })
                        r.setOnClickListener { dlg.dismiss(); onPick(sub) }
                        list.addView(r)
                    }
                }
            }
        }

        langBtn.setOnClickListener {
            val labels = Subtitles.LANGS.map { it.label }.toTypedArray()
            val cur = Subtitles.LANGS.indexOfFirst { it.api2 == lang.api2 }.coerceAtLeast(0)
            AlertDialog.Builder(a).setTitle("Subtitle language")
                .setSingleChoiceItems(labels, cur) { d, w ->
                    lang = Subtitles.LANGS[w]
                    saveLang(a, lang); renderLang(); d.dismiss(); runSearch(strict = true)
                }
                .setNegativeButton("Cancel", null).show()
        }
        // The user explicitly pressing Search opts into the broad text match (junk possible).
        searchBtn.setOnClickListener { runSearch(strict = false) }
        queryBox.setOnEditorActionListener { _, _, _ -> runSearch(strict = false); true }

        dlg.show()
        dlg.window?.setLayout((a.resources.displayMetrics.widthPixels * 0.92f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        runSearch(strict = true) // auto-search the pre-filled title on open — junk-free
    }

    /** Keep only results whose release name actually contains the title's words — an auto-search
     *  for a regional movie OpenSubtitles doesn't know should say "not found", not list junk. */
    private fun relevantOnly(subs: List<Subtitles.Sub>, title: String): List<Subtitles.Sub> {
        val words = title.lowercase()
            .replace(Regex("s\\d{1,2}e\\d{1,3}"), " ") // drop the S01E02 token
            .split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }
        if (words.isEmpty()) return subs
        return subs.filter { s ->
            val n = s.name.lowercase().replace('.', ' ')
            words.all { n.contains(it) }
        }
    }

    private fun savedLang(a: Activity): Subtitles.Lang {
        val code = a.getSharedPreferences("subs", 0).getString("lang2", "en")
        return Subtitles.LANGS.firstOrNull { it.api2 == code } ?: Subtitles.LANGS[0]
    }

    private fun saveLang(a: Activity, l: Subtitles.Lang) =
        a.getSharedPreferences("subs", 0).edit().putString("lang2", l.api2).apply()
}
