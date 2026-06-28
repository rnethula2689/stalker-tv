package com.stalkertv.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.stalkertv.app.databinding.ActivityEpgGuideBinding
import com.stalkertv.app.databinding.ItemEpgGuideBinding
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * TV Guide: a vertical list of live channels, each showing what's on Now and Next (from the portal's
 * short EPG). Per-channel EPG is fetched lazily as rows scroll into view and cached, so opening the
 * guide over a few hundred channels stays cheap. Tapping a row plays that channel fullscreen.
 */
class EpgGuideActivity : AppCompatActivity() {
    companion object {
        /** Channels to show — set by the caller before starting the activity. */
        var channels: List<Portal.Channel> = emptyList()
    }

    private lateinit var b: ActivityEpgGuideBinding
    private val pool = Executors.newFixedThreadPool(4)
    private val ui = Handler(Looper.getMainLooper())
    private val cache = ConcurrentHashMap<String, List<Portal.EpgItem>>()
    private var list: List<Portal.Channel> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityEpgGuideBinding.inflate(layoutInflater)
        setContentView(b.root)
        // Reminders post notifications — on API 33+ that needs a runtime grant (no-op on older Fire OS).
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001) } catch (_: Exception) {}
        }
        list = channels
        b.title.text = "📺  TV Guide  (${list.size})"
        b.guideList.layoutManager = LinearLayoutManager(this)
        b.guideList.adapter = GuideAdapter()
        b.empty.visibility = if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        // If an external XMLTV guide is configured, load it in the background then rebind rows to use it.
        if (XmltvEpg.isActive(this)) {
            b.subtitle.text = "Loading external EPG…"
            pool.execute {
                XmltvEpg.ensureLoaded(this)
                ui.post {
                    if (isFinishing) return@post
                    b.subtitle.text = "Tap to watch  ·  📅 for full schedule & reminders"
                    b.guideList.adapter?.notifyDataSetChanged()
                }
            }
        }
    }

    private fun timeRange(e: Portal.EpgItem): String {
        val s = if (e.startTs > 0) Portal.localTime(e.startTs) else e.start
        val en = if (e.stopTs > 0) Portal.localTime(e.stopTs) else e.end
        return if (en.isNotBlank()) "$s – $en" else s
    }

    private fun bindEpg(h: VH, epg: List<Portal.EpgItem>) {
        if (epg.isEmpty()) { h.v.nowLine.text = "No guide for this channel"; h.v.nextLine.text = ""; return }
        val now = System.currentTimeMillis() / 1000
        var nowIdx = epg.indexOfFirst { it.startTs in 1..now && (it.stopTs == 0L || now < it.stopTs) }
        if (nowIdx < 0) nowIdx = epg.indexOfLast { it.startTs in 1..now }
        val cur = epg.getOrNull(nowIdx)
        val nxt = epg.getOrNull(nowIdx + 1) ?: epg.firstOrNull { it.startTs > now }
        h.v.nowLine.text = if (cur != null) "🔴 Now  ${timeRange(cur)}  ·  ${cur.name}" else "Now playing"
        h.v.nextLine.text = if (nxt != null) "Next  ${Portal.localTime(nxt.startTs)}  ·  ${nxt.name}" else ""
    }

    private fun play(ch: Portal.Channel) {
        LiveVlcActivity.liveChannels = list
        val idx = list.indexOfFirst { it.id == ch.id }
        pool.execute {
            val u = Portal.createLink(ch.cmd)
            ui.post {
                if (isFinishing) return@post
                if (u.isNullOrEmpty()) {
                    android.widget.Toast.makeText(this, "No stream for ${ch.name}", android.widget.Toast.LENGTH_SHORT).show()
                    return@post
                }
                startActivity(Intent(this, LiveVlcActivity::class.java)
                    .putExtra("url", u).putExtra("title", ch.name).putExtra("chIndex", idx))
            }
        }
    }

    /** Load and show the full day's schedule for a channel, with catch-up (past) and reminders (future). */
    private fun openSchedule(ch: Portal.Channel) {
        val xm = if (XmltvEpg.isActive(this)) XmltvEpg.forChannel(ch.name) else emptyList()
        if (xm.isNotEmpty()) { showScheduleDialog(ch, xm); return }
        android.widget.Toast.makeText(this, "Loading ${ch.name} schedule…", android.widget.Toast.LENGTH_SHORT).show()
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        pool.execute {
            val epg = try {
                Portal.epgForDate(ch.id, today).ifEmpty { Portal.shortEpg(ch.id) }
            } catch (_: Exception) { emptyList() }
            ui.post { if (!isFinishing) showScheduleDialog(ch, epg) }
        }
    }

    private fun showScheduleDialog(ch: Portal.Channel, epg: List<Portal.EpgItem>) {
        if (epg.isEmpty()) {
            android.widget.Toast.makeText(this, "No schedule for ${ch.name}", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val now = System.currentTimeMillis() / 1000
        fun state(e: Portal.EpgItem): Int = when {
            e.startTs in 1..now && (e.stopTs == 0L || now < e.stopTs) -> 0 // live now
            e.stopTs in 1..now -> 1                                        // past
            e.startTs > now -> 2                                           // future
            else -> 3
        }
        val labels = epg.map { e ->
            val t = if (e.startTs > 0) Portal.localTime(e.startTs) else e.start
            val mark = when (state(e)) {
                0 -> "🔴"
                1 -> if (ch.archiveDays > 0) "▶" else "  "
                2 -> if (Reminders.isSet(this, ch.id, e.startTs)) "⏰" else "＋"
                else -> "  "
            }
            "$mark   $t   ${e.name}"
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("${ch.name} — Today")
            .setItems(labels) { _, w ->
                val e = epg[w]
                when (state(e)) {
                    0 -> play(ch)
                    1 -> if (ch.archiveDays > 0) startActivity(Intent(this, CatchupActivity::class.java)
                        .putExtra("chId", ch.id).putExtra("chName", ch.name)
                        .putExtra("chCmd", ch.cmd).putExtra("archiveDays", ch.archiveDays))
                    else android.widget.Toast.makeText(this, "No catch-up for this channel", android.widget.Toast.LENGTH_SHORT).show()
                    2 -> {
                        val added = Reminders.toggle(this, Reminders.R(ch.id, ch.name, ch.cmd, e.name, e.startTs))
                        android.widget.Toast.makeText(this,
                            if (added) "Reminder set ⏰  ${e.name}" else "Reminder removed",
                            android.widget.Toast.LENGTH_SHORT).show()
                        showScheduleDialog(ch, epg) // refresh marks
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    inner class VH(val v: ItemEpgGuideBinding) : RecyclerView.ViewHolder(v.root)

    inner class GuideAdapter : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemEpgGuideBinding.inflate(layoutInflater, parent, false))
        override fun getItemCount() = list.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val ch = list[position]
            holder.v.chName.text = (if (ch.number.isNotBlank()) "${ch.number}.  " else "") + ch.name
            holder.v.nextLine.text = ""
            holder.v.infoBlock.setOnClickListener { play(ch) }
            holder.v.scheduleBtn.setOnClickListener { openSchedule(ch) }
            // External XMLTV (if configured + matched by name) takes precedence — instant, in-memory.
            val xm = if (XmltvEpg.isActive(this@EpgGuideActivity)) XmltvEpg.forChannel(ch.name) else emptyList()
            if (xm.isNotEmpty()) { bindEpg(holder, xm); return }
            val cached = cache[ch.id]
            if (cached != null) { bindEpg(holder, cached); return }
            holder.v.nowLine.text = "Loading…"
            pool.execute {
                val epg = try { Portal.shortEpg(ch.id) } catch (_: Exception) { emptyList() }
                cache[ch.id] = epg
                ui.post { if (holder.bindingAdapterPosition == position) bindEpg(holder, epg) }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pool.shutdownNow()
        ui.removeCallbacksAndMessages(null)
    }
}
