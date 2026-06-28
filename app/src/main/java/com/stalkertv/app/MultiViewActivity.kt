package com.stalkertv.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.stalkertv.app.databinding.ActivityMultiviewBinding
import com.stalkertv.app.databinding.ItemMvPaneBinding
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.util.concurrent.Executors

/**
 * Watch 2 or 4 live channels at once. Each pane is its own libVLC player; only one pane carries
 * audio at a time (tap a pane to move the sound). Long-press a pane for change / fullscreen / remove.
 * NOTE: many portals cap simultaneous streams (often 1–2), so 4 panes may fail "connection limit".
 */
class MultiViewActivity : AppCompatActivity() {
    companion object {
        var channels: List<Portal.Channel> = emptyList()   // pool to pick from
        var startChannels: List<Portal.Channel> = emptyList() // pre-loaded panes
    }

    private lateinit var b: ActivityMultiviewBinding
    private lateinit var panes: List<ItemMvPaneBinding>
    private val io = Executors.newFixedThreadPool(4)
    private val ui = Handler(Looper.getMainLooper())

    private var libVlc: LibVLC? = null
    private val players = arrayOfNulls<MediaPlayer>(4)
    private val paneCh = arrayOfNulls<Portal.Channel>(4)
    private val attached = BooleanArray(4)
    private val seqs = IntArray(4)
    private var audioPane = 0
    private var mode = 2  // 2 or 4 panes

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMultiviewBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goImmersive()

        panes = listOf(b.pane0, b.pane1, b.pane2, b.pane3)
        libVlc = LibVLC(this, arrayListOf("--network-caching=1500", "--http-reconnect", "--no-drop-late-frames", "--no-skip-frames"))

        for (i in panes.indices) {
            panes[i].root.setOnClickListener { if (paneCh[i] == null) pickChannel(i) else setAudio(i) }
            panes[i].root.setOnLongClickListener { if (paneCh[i] != null) paneMenu(i); true }
        }
        b.modeBtn.setOnClickListener { toggleMode() }

        // Pre-load the panes the launcher chose (start in 2-pane mode if ≤2 were given).
        val pre = startChannels.take(4)
        mode = if (pre.size > 2) 4 else 2
        applyMode()
        for (i in pre.indices) { paneCh[i] = pre[i]; markFilled(i, pre[i].name) }
        audioPane = 0
        panes[0].root.post { panes[0].root.requestFocus() }
    }

    private fun markFilled(i: Int, name: String) {
        panes[i].paneEmpty.visibility = View.GONE
        panes[i].paneName.text = name
        panes[i].paneName.visibility = View.VISIBLE
        panes[i].paneAudio.visibility = View.VISIBLE
    }

    private fun markEmpty(i: Int) {
        panes[i].paneEmpty.visibility = View.VISIBLE
        panes[i].paneName.visibility = View.GONE
        panes[i].paneAudio.visibility = View.GONE
    }

    /** (Re)attach + play whatever channel a pane holds. */
    private fun startPane(i: Int) {
        val ch = paneCh[i] ?: return
        val vlc = libVlc ?: return
        val mp = players[i] ?: MediaPlayer(vlc).also { players[i] = it }
        if (!attached[i]) { mp.attachViews(panes[i].video, null, false, false); attached[i] = true }
        val mine = ++seqs[i]
        io.execute {
            val url = Portal.createLink(ch.cmd)
            ui.post {
                if (mine != seqs[i] || paneCh[i] !== ch) return@post
                if (url.isNullOrEmpty()) { panes[i].paneName.text = "${ch.name}  (no stream)"; return@post }
                try {
                    val media = Media(vlc, Uri.parse(url))
                    media.setHWDecoderEnabled(true, false)
                    media.addOption(":network-caching=1500")
                    media.addOption(":http-user-agent=" + Portal.UA)
                    media.addOption(":http-reconnect")
                    mp.media = media
                    media.release()
                    mp.play()
                    applyAudio()
                } catch (e: Exception) {
                    panes[i].paneName.text = "${ch.name}  (error)"
                }
            }
        }
    }

    private fun stopPane(i: Int) {
        seqs[i]++
        players[i]?.let { it.stop(); if (attached[i]) { it.detachViews(); attached[i] = false } }
    }

    private fun loadPane(i: Int, ch: Portal.Channel) {
        paneCh[i] = ch
        markFilled(i, ch.name)
        if (paneCh.count { it != null } == 1) audioPane = i // first channel added gets the sound
        startPane(i)
    }

    private fun removePane(i: Int) {
        stopPane(i)
        players[i]?.release()
        players[i] = null
        paneCh[i] = null
        markEmpty(i)
        if (audioPane == i) paneCh.indexOfFirst { it != null }.let { if (it >= 0) setAudio(it) }
    }

    /** Route audio to one pane; mute the rest (only one sound source at a time). */
    private fun setAudio(i: Int) {
        audioPane = i
        applyAudio()
    }

    private fun applyAudio() {
        for (j in players.indices) {
            try { players[j]?.setVolume(if (j == audioPane) 100 else 0) } catch (_: Exception) {}
            panes[j].paneAudio.text = if (j == audioPane && paneCh[j] != null) "🔊" else "🔇"
        }
    }

    private fun pickChannel(i: Int) {
        if (channels.isEmpty()) return
        val names = channels.map { it.name }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Choose a channel")
            .setItems(names) { _, w -> loadPane(i, channels[w]) }
            .show()
    }

    private fun paneMenu(i: Int) {
        val ch = paneCh[i] ?: return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(ch.name)
            .setItems(arrayOf("🔊  Sound from here", "🔁  Change channel", "⛶  Fullscreen", "✖  Remove")) { _, w ->
                when (w) {
                    0 -> setAudio(i)
                    1 -> pickChannel(i)
                    2 -> openFullscreen(ch)
                    3 -> removePane(i)
                }
            }.show()
    }

    private fun openFullscreen(ch: Portal.Channel) {
        LiveVlcActivity.liveChannels = channels
        val idx = channels.indexOfFirst { it.id == ch.id }
        io.execute {
            val url = Portal.createLink(ch.cmd)
            ui.post {
                if (!url.isNullOrEmpty()) startActivity(
                    Intent(this, LiveVlcActivity::class.java)
                        .putExtra("url", url).putExtra("title", ch.name).putExtra("chIndex", idx)
                )
            }
        }
    }

    private fun toggleMode() {
        mode = if (mode == 4) 2 else 4
        if (mode == 2) { removePane(2); removePane(3) } // free those streams
        applyMode()
    }

    private fun applyMode() {
        b.bottomRow.visibility = if (mode == 4) View.VISIBLE else View.GONE
        b.modeBtn.text = if (mode == 4) "4 panes" else "2 panes"
    }

    private fun goImmersive() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = androidx.core.view.WindowInsetsControllerCompat(window, b.root)
        c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    override fun onStart() {
        super.onStart()
        for (i in panes.indices) if (paneCh[i] != null && (mode == 4 || i < 2)) startPane(i)
    }

    override fun onStop() {
        super.onStop()
        for (i in panes.indices) stopPane(i)
    }

    override fun onDestroy() {
        super.onDestroy()
        for (i in players.indices) { players[i]?.let { it.stop(); it.release() }; players[i] = null }
        libVlc?.release(); libVlc = null
    }
}
