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
    private data class DateOpt(val display: String, val ymd: String)
    private var dates = listOf<DateOpt>()
    private var loadSeq = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCatchupBinding.inflate(layoutInflater)
        setContentView(b.root)

        chId = intent.getStringExtra("chId") ?: ""
        chName = intent.getStringExtra("chName") ?: "Catch-up"
        chCmd = intent.getStringExtra("chCmd") ?: ""
        val days = intent.getIntExtra("archiveDays", 0).let { if (it > 0) it else 7 }
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
        for (i in 0..days) {
            val day = cal.get(Calendar.DAY_OF_MONTH)
            val display = "$day${ordinal(day)} ${monthFmt.format(cal.time)}, ${cal.get(Calendar.YEAR)}"
            out.add(DateOpt(display, ymdFmt.format(cal.time)))
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
        io.execute {
            val epg = Portal.epgForDate(chId, d.ymd).sortedBy { it.startTs }
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
            val url = Portal.archiveLink(chCmd, e.startTs)
            runOnUiThread {
                if (url.isNullOrEmpty()) {
                    b.status.visibility = View.VISIBLE
                    b.status.text = "Couldn't open “${e.name}” — catch-up may not be available."
                } else {
                    b.status.visibility = View.GONE
                    LiveVlcActivity.liveChannels = emptyList()
                    startActivity(
                        Intent(this, LiveVlcActivity::class.java)
                            .putExtra("url", url)
                            .putExtra("title", "${e.start}  ${e.name}")
                            .putExtra("chIndex", -1)
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
            holder.v.time.text = if (e.end.isNotBlank()) "${e.start} – ${e.end}" else e.start
            holder.v.name.text = e.name
            val future = e.startTs > System.currentTimeMillis() / 1000
            holder.v.name.setTextColor(if (future) 0xFF5A6675.toInt() else 0xFFE6EDF3.toInt())
            holder.v.root.setOnClickListener { playArchive(e) }
        }
    }
}
