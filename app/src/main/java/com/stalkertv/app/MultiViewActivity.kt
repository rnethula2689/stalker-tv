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
        var channels: List<Portal.Channel> = emptyList()      // pool to pick from
        var genres: List<Portal.Genre> = emptyList()          // live categories for the picker
        var startChannels: List<Portal.Channel> = emptyList() // pre-loaded panes (pane 0 = current channel)
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
            panes[i].paneAudio.isClickable = true
            panes[i].paneAudio.isFocusable = true
            panes[i].paneAudio.setOnClickListener { if (paneCh[i] != null) toggleAudio(i) }
        }
        b.modeBtn.setOnClickListener { toggleMode() }

        // Pane 0 = the channel the user was watching; the rest start empty.
        val pre = startChannels.take(4)
        mode = if (pre.size > 2) 4 else 2
        applyMode()
        for (i in pre.indices) { paneCh[i] = pre[i]; markFilled(i, pre[i].name) }
        audioPane = if (pre.isNotEmpty()) 0 else -1
        panes[0].root.post { panes[0].root.requestFocus() }
    }

    private fun markFilled(i: Int, name: String) {
        panes[i].video.visibility = View.VISIBLE
        panes[i].paneEmpty.visibility = View.GONE
        panes[i].paneName.text = name
        panes[i].paneName.visibility = View.VISIBLE
        panes[i].paneAudio.visibility = View.VISIBLE
    }

    private fun markEmpty(i: Int) {
        panes[i].video.visibility = View.INVISIBLE // destroy the surface so the last frame doesn't linger
        panes[i].paneEmpty.visibility = View.VISIBLE
        panes[i].paneName.visibility = View.GONE
        panes[i].paneAudio.visibility = View.GONE
    }

    /** (Re)attach + play whatever channel a pane holds. */
    private fun startPane(i: Int) {
        val ch = paneCh[i] ?: return
        val vlc = libVlc ?: return
        // libVLC ignores setVolume() until the audio output exists, so re-apply mute on Playing.
        val mp = players[i] ?: MediaPlayer(vlc).also { p ->
            players[i] = p
            p.setEventListener { ev -> if (ev.type == MediaPlayer.Event.Playing) ui.post { applyAudio() } }
        }
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
        if (audioPane == -1 && paneCh.count { it != null } == 1) audioPane = i // only the very first gets sound
        startPane(i)
    }

    private fun removePane(i: Int) {
        stopPane(i)
        players[i]?.release()
        players[i] = null
        paneCh[i] = null
        markEmpty(i)
        if (audioPane == i) audioPane = -1 // removing the sounding pane → all muted until user picks
        applyAudio()
    }

    /** Route audio to exactly one pane; mute the rest. */
    private fun setAudio(i: Int) { audioPane = i; applyAudio() }

    /** Mute icon tap: if this pane is the audio source, mute everything; otherwise make it the source. */
    private fun toggleAudio(i: Int) { audioPane = if (audioPane == i) -1 else i; applyAudio() }

    private fun applyAudio() {
        for (j in players.indices) {
            val on = j == audioPane && paneCh[j] != null
            val p = players[j]
            if (p != null) try {
                if (on) {
                    // Re-enable the audio track if we'd disabled it, then full volume.
                    if (p.audioTrack == -1) p.audioTracks?.firstOrNull { it.id >= 0 }?.let { p.setAudioTrack(it.id) }
                    p.setVolume(100)
                } else {
                    // setVolume(0) alone is unreliable on Fire libVLC → also disable the audio track.
                    p.setVolume(0)
                    try { p.setAudioTrack(-1) } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
            panes[j].paneAudio.text = if (on) "🔊" else "🔇"
        }
    }

    /** Step 1: pick a Live TV category. Step 2: pick a channel within it. */
    private fun pickChannel(i: Int) {
        if (channels.isEmpty()) return
        val byG = channels.groupBy { it.genreId }
        val cats = genres.filter { (byG[it.id]?.size ?: 0) > 0 }
        if (cats.isEmpty()) { pickFromList(i, channels); return } // no genre info → flat list
        val labels = cats.map { "${it.title}  (${byG[it.id]?.size ?: 0})" }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Choose a category")
            .setItems(labels) { _, w -> pickFromList(i, byG[cats[w].id] ?: emptyList()) }
            .show()
    }

    private fun pickFromList(i: Int, list: List<Portal.Channel>) {
        if (list.isEmpty()) return
        val names = list.map { it.name }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Choose a channel")
            .setItems(names) { _, w -> loadPane(i, list[w]) }
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
