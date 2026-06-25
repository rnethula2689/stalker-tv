package com.stalkertv.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.stalkertv.app.databinding.ActivityCatchupBinding
import com.stalkertv.app.databinding.ItemEpgBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

/** Catch-up: pick a past date for a channel, see its EPG, tap a programme to play the archive. */
class CatchupActivity : AppCompatActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var b: ActivityCatchupBinding
    private val adapter = EpgAdapter()

    private var chId = ""
    private var chName = ""
    private var chCmd = ""
    private data class DateOpt(val display: String, val ymd: String, val dayStartSec: Long)
    private var dates = listOf<DateOpt>()
    private var loadSeq = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCatchupBinding.inflate(layoutInflater)
        setContentView(b.root)

        chId = intent.getStringExtra("chId") ?: ""
        chName = intent.getStringExtra("chName") ?: "Catch-up"
        chCmd = intent.getStringExtra("chCmd") ?: ""
        // The intent carries tv_archive_duration, which the portal reports in HOURS (e.g. 120 = 5 days).
        val archiveHours = intent.getIntExtra("archiveDays", 0)
        val days = if (archiveHours > 0) Math.ceil(archiveHours / 24.0).toInt().coerceAtLeast(1) else 7
        b.title.text = "🕐  $chName"
        dates = buildDates(days)
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.dateBtn.setOnClickListener { pickDate() }
        select(0)
    }

    private fun buildDates(days: Int): List<DateOpt> {
        val out = ArrayList<DateOpt>()
        val ymdFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val monthFmt = SimpleDateFormat("MMMM", Locale.US)
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        for (i in 0..days) {
            val day = cal.get(Calendar.DAY_OF_MONTH)
            val display = "$day${ordinal(day)} ${monthFmt.format(cal.time)}, ${cal.get(Calendar.YEAR)}"
            out.add(DateOpt(display, ymdFmt.format(cal.time), cal.timeInMillis / 1000))
            cal.add(Calendar.DAY_OF_MONTH, -1)
        }
        return out
    }

    private fun ordinal(d: Int): String =
        if (d in 11..13) "th" else when (d % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }

    private fun pickDate() {
        val labels = dates.map { it.display }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select date")
            .setItems(labels) { _, which -> select(which) }
            .show()
    }

    private fun select(index: Int) {
        val d = dates[index]
        b.dateBtn.text = "▾  ${d.display}"
        val mine = ++loadSeq
        b.status.visibility = View.VISIBLE
        b.status.text = "Loading…"
        adapter.submit(emptyList())
        val dayStart = d.dayStartSec
        val dayEnd = dayStart + 86400
        io.execute {
            val raw = Portal.epgForDate(chId, d.ymd)
            val filtered = raw.filter { it.startTs in dayStart until dayEnd }.sortedBy { it.startTs }
            // If items have timestamps but none fall on this day → genuinely empty; if none have
            // timestamps, the portal already filtered by date server-side, so show what we got.
            val epg = if (filtered.isNotEmpty()) filtered
                else if (raw.any { it.startTs > 0 }) emptyList()
                else raw
            runOnUiThread {
                if (mine != loadSeq) return@runOnUiThread
                b.status.visibility = if (epg.isEmpty()) View.VISIBLE else View.GONE
                if (epg.isEmpty()) b.status.text = "No catch-up programmes for this date."
                adapter.submit(epg)
            }
        }
    }

    private fun playArchive(e: Portal.EpgItem) {
        val now = System.currentTimeMillis() / 1000
        if (e.startTs > now) {
            android.widget.Toast.makeText(this, "“${e.name}” hasn't aired yet.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        b.status.visibility = View.VISIBLE
        b.status.text = "Opening “${e.name}”…"
        io.execute {
            val durationSec = if (e.stopTs > e.startTs) e.stopTs - e.startTs else 3600
            val url = Portal.archiveLink(chCmd, e.startTs, durationSec)
            runOnUiThread {
                if (url.isNullOrEmpty()) {
                    b.status.visibility = View.VISIBLE
                    b.status.text = "Couldn't open “${e.name}” — catch-up may not be available."
                } else {
                    b.status.visibility = View.GONE
                    // Archive is a finite, seekable VOD. Play it in the VLC player's archive mode,
                    // which shows a slider + skip controls. VLC seeks HLS-VOD reliably (ExoPlayer stalls).
                    LiveVlcActivity.liveChannels = emptyList()
                    startActivity(
                        Intent(this, LiveVlcActivity::class.java)
                            .putExtra("url", url)
                            .putExtra("title", "${e.start}  ${e.name}")
                            .putExtra("chIndex", -1)
                            .putExtra("archive", true)
                    )
                }
            }
        }
    }

    private inner class EpgAdapter : RecyclerView.Adapter<EpgAdapter.VH>() {
        private var items = listOf<Portal.EpgItem>()
        fun submit(l: List<Portal.EpgItem>) { items = l; notifyDataSetChanged() }
        inner class VH(val v: ItemEpgBinding) : RecyclerView.ViewHolder(v.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemEpgBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = items[position]
            holder.v.time.text =
                if (e.startTs > 0) "${Portal.localTime(e.startTs)} – ${Portal.localTime(e.stopTs)}"
                else if (e.end.isNotBlank()) "${e.start} – ${e.end}" else e.start
            holder.v.name.text = e.name
            val future = e.startTs > System.currentTimeMillis() / 1000
            holder.v.name.setTextColor(if (future) 0xFF5A6675.toInt() else 0xFFE6EDF3.toInt())
            holder.v.root.setOnClickListener { playArchive(e) }
        }
    }
}
