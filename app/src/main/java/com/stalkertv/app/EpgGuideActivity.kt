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
        list = channels
        b.title.text = "📺  TV Guide  (${list.size})"
        b.guideList.layoutManager = LinearLayoutManager(this)
        b.guideList.adapter = GuideAdapter()
        b.empty.visibility = if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
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

    inner class VH(val v: ItemEpgGuideBinding) : RecyclerView.ViewHolder(v.root)

    inner class GuideAdapter : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemEpgGuideBinding.inflate(layoutInflater, parent, false))
        override fun getItemCount() = list.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val ch = list[position]
            holder.v.chName.text = (if (ch.number.isNotBlank()) "${ch.number}.  " else "") + ch.name
            holder.v.nextLine.text = ""
            holder.v.root.setOnClickListener { play(ch) }
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
