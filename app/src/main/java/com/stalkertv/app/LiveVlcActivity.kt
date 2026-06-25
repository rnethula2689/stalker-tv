package com.stalkertv.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.stalkertv.app.databinding.ActivityLivevlcBinding
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.util.concurrent.Executors

/**
 * Fullscreen player backed by libVLC (handles any codec/protocol).
 *  - Live TV: Up/Down (or swipe) change channels, no transport controls.
 *  - Catch-up archive ("archive" extra): a finite, seekable VOD — shows a slider + skip controls
 *    so the user can scrub, like a movie. VLC seeks HLS-VOD reliably where ExoPlayer stalls.
 */
class LiveVlcActivity : AppCompatActivity() {
    companion object {
        var liveChannels: List<Portal.Channel> = emptyList()
    }

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var b: ActivityLivevlcBinding
    private var libVlc: LibVLC? = null
    private var mp: MediaPlayer? = null
    private var channels: List<Portal.Channel> = emptyList()
    private var chIndex = -1
    private var titleText = ""
    private var hideBarRunnable: Runnable? = null

    private var isArchive = false
    private var seeking = false      // user is dragging the slider
    private var durationMs = 0L
    private var currentUrl = ""      // stream currently playing (for casting)

    private val poller = object : Runnable {
        override fun run() {
            val p = mp
            if (p != null && isArchive) {
                val len = p.length
                if (len > 0) { durationMs = len; b.durText.text = fmt(len) }
                if (!seeking) {
                    val t = p.time
                    b.posText.text = fmt(t)
                    b.seek.progress = if (len > 0) ((t * 1000) / len).toInt() else 0
                }
            }
            ui.postDelayed(this, 500)
        }
    }

    // Tablet swipe (live only): up/right = previous, down/left = next (like flicking photos).
    private val gestureDetector by lazy {
        android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent): Boolean = true
            override fun onSingleTapUp(e: android.view.MotionEvent): Boolean { showBar(); return true }
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || isArchive) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                val min = 80f
                if (Math.abs(dx) > Math.abs(dy)) {
                    if (Math.abs(dx) > min) { switchChannel(if (dx > 0) -1 else 1); return true }
                } else {
                    if (Math.abs(dy) > min) { switchChannel(if (dy > 0) -1 else 1); return true }
                }
                return false
            }
        })
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLivevlcBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val url = intent.getStringExtra("url") ?: run { finish(); return }
        titleText = intent.getStringExtra("title") ?: ""
        chIndex = intent.getIntExtra("chIndex", -1)
        isArchive = intent.getBooleanExtra("archive", false)
        channels = liveChannels
        b.title.text = titleText

        val options = arrayListOf(
            "--network-caching=1500",
            "--http-reconnect",
            "--no-drop-late-frames",
            "--no-skip-frames"
        )
        val vlc = LibVLC(this, options)
        libVlc = vlc
        val player = MediaPlayer(vlc)
        mp = player
        player.attachViews(b.vlc, null, false, false)
        player.setEventListener { ev ->
            when (ev.type) {
                MediaPlayer.Event.Playing -> { b.status.visibility = View.GONE; if (isArchive) b.playBtn.text = "⏸" }
                MediaPlayer.Event.Paused -> { if (isArchive) b.playBtn.text = "▶" }
                MediaPlayer.Event.EndReached -> { if (isArchive) b.playBtn.text = "▶" }
                MediaPlayer.Event.EncounteredError ->
                    b.status.apply { visibility = View.VISIBLE; text = "Couldn't play this." }
                else -> {}
            }
        }

        b.menuBtn.setOnClickListener { showMenu() }
        b.root.setOnTouchListener { _, ev -> gestureDetector.onTouchEvent(ev) }

        if (isArchive) setupArchiveControls() else saveLastChannel()
        play(url)
        showBar()
    }

    private fun setupArchiveControls() {
        b.controlBar.visibility = View.VISIBLE   // stays visible the whole time in catch-up
        b.playBtn.setOnClickListener { togglePlay() }
        b.rewindBtn.setOnClickListener { seekBy(-15_000) }
        b.forwardBtn.setOnClickListener { seekBy(15_000) }
        b.seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && durationMs > 0) b.posText.text = fmt(durationMs * progress / 1000)
            }
            override fun onStartTrackingTouch(sb: SeekBar) { seeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                val p = mp
                if (p != null && durationMs > 0) p.time = durationMs * sb.progress / 1000
                seeking = false
            }
        })
        ui.postDelayed(poller, 500)
    }

    private fun togglePlay() {
        val p = mp ?: return
        if (p.isPlaying) { p.pause(); b.playBtn.text = "▶" } else { p.play(); b.playBtn.text = "⏸" }
    }

    private fun seekBy(deltaMs: Long) {
        val p = mp ?: return
        val len = p.length
        var t = p.time + deltaMs
        if (t < 0) t = 0
        if (len > 0 && t > len - 1000) t = len - 1000
        p.time = t
        b.posText.text = fmt(t)
        if (len > 0) b.seek.progress = ((t * 1000) / len).toInt()
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).toInt()
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
    }

    /** Remember the current channel for Continue Watching (single rolling "last live" entry). */
    private fun saveLastChannel() {
        val ch = channels.getOrNull(chIndex) ?: return
        Resume.save(applicationContext, Resume.LIVE_ID, "live", ch.name, ch.logoUrl, "live|${ch.id}|${ch.cmd}", 0, 0)
    }

    private fun play(url: String) {
        val vlc = libVlc ?: return
        val player = mp ?: return
        currentUrl = url
        b.status.visibility = View.VISIBLE
        b.status.text = "Loading…"
        player.stop()
        val media = Media(vlc, Uri.parse(url))
        media.setHWDecoderEnabled(true, false) // HW first, auto software fallback
        media.addOption(":network-caching=1500")
        media.addOption(":http-user-agent=" + Portal.UA)
        media.addOption(":http-reconnect")
        player.media = media
        media.release()
        player.play()
    }

    /** Live only: Up = previous channel, Down = next. */
    private fun switchChannel(delta: Int) {
        if (isArchive || channels.isEmpty() || chIndex < 0) return
        var idx = chIndex + delta
        if (idx < 0) idx = 0
        if (idx > channels.size - 1) idx = channels.size - 1
        if (idx == chIndex) return
        chIndex = idx
        val ch = channels[idx]
        titleText = ch.name
        b.title.text = ch.name
        saveLastChannel()
        showBar()
        b.status.visibility = View.VISIBLE
        b.status.text = "▶  ${ch.name}…"
        io.execute {
            val u = Portal.createLink(ch.cmd)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (u.isNullOrEmpty()) { b.status.text = "No stream for ${ch.name}"; return@runOnUiThread }
                play(u)
            }
        }
    }

    private fun showBar() {
        b.topBar.visibility = View.VISIBLE
        if (!isArchive) b.hint.visibility = View.VISIBLE
        hideBarRunnable?.let { ui.removeCallbacks(it) }
        val r = Runnable { b.topBar.visibility = View.GONE; b.hint.visibility = View.GONE }
        hideBarRunnable = r
        ui.postDelayed(r, 4000)
    }

    private var menuDialog: AlertDialog? = null
    private fun showMenu() {
        if (menuDialog?.isShowing == true) { menuDialog?.dismiss(); return }
        val items = arrayOf("📡   Cast to TV", "⚙   Settings", "📥   App updates", "ℹ️   About", "✖   Exit")
        val dlg = AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> if (currentUrl.isNotEmpty()) CastHelper.show(this, currentUrl, titleText, isLive = !isArchive)
                    1 -> startActivity(Intent(this, SettingsActivity::class.java))
                    2 -> startActivity(Intent(this, AppUpdatesActivity::class.java))
                    3 -> About.show(this)
                    4 -> finishAffinity()
                }
            }
            .setOnDismissListener { menuDialog = null }
            .create()
        menuDialog = dlg
        dlg.show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (isArchive) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_MENU -> { showMenu(); return true }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-15_000); showBar(); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(15_000); showBar(); return true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlay(); return true }
                }
            } else {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_MENU -> { showMenu(); return true }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { switchChannel(-1); return true }
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { switchChannel(1); return true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { showBar(); return true }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        mp?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        hideBarRunnable?.let { ui.removeCallbacks(it) }
        ui.removeCallbacks(poller)
        mp?.let { it.stop(); it.detachViews(); it.release() }
        mp = null
        libVlc?.release()
        libVlc = null
    }
}
