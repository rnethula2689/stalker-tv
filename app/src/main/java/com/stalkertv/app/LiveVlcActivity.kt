package com.stalkertv.app

import android.content.Intent
import android.media.AudioManager
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
    private var knownDurationMs = 0L // authoritative timeline length (program / timeshift window); 0 = fall back to VLC length
    private var seekTarget = -1L     // ms we just seeked to; poller holds the UI here until VLC catches up
    private var seekDeadline = 0L    // uptime cutoff so a clamped/failed seek can't freeze the UI
    private var currentUrl = ""      // stream currently playing (for casting)
    private var timeshifting = false // live rewind (DVR) mode
    private var tsWindowSec = 0L     // scrubbable timeshift buffer length
    private var startSeekTo = 0L     // pending seek (ms) to apply once the stream length is known
    private var liveUrl = ""         // current live stream URL — its token is reused for timeshift

    private lateinit var am: AudioManager
    private var preMuteVol = -1
    private val onTv by lazy { Tv.isTv(this) }
    private var tvDim = 0f          // TV "brightness": software dim-overlay alpha (0 = none)

    // On a TV the device stream volume is often fixed and the panel backlight can't be touched, so
    // drive libVLC's own volume and a dim overlay instead of the device volume / window brightness.
    private fun volMax() = if (onTv) 100 else ScreenControls.maxVolume(am)
    private fun volGet() = if (onTv) (mp?.volume ?: 100) else ScreenControls.volume(am)
    private fun volSet(v: Int) {
        val c = v.coerceIn(0, volMax())
        if (onTv) mp?.volume = c else ScreenControls.setVolume(am, c)
    }
    private fun brightGetPct() = if (onTv) ((1f - tvDim) * 100).toInt() else (ScreenControls.brightness(window) * 100).toInt()
    private fun brightSetPct(pct: Int) {
        val f = pct.coerceIn(0, 100) / 100f
        if (onTv) { tvDim = (1f - f).coerceIn(0f, 0.92f); b.dimOverlay.alpha = tvDim }
        else ScreenControls.setBrightness(window, f)
    }
    private val aspectModes = listOf("Fit", "16:9", "4:3", "Stretch")
    private var aspectIdx = 0
    private var nightOn = false
    private var isRecording = false
    private var recChannel = ""
    // --- P3.1 reliability: auto-retry a failed live stream (fresh link + alternate sources) ---
    private var retryCount = 0
    private val maxRetry = 3
    private var autoRetrying = false
    private var playFailed = false
    private val OPTION_NAV_KEYS = intArrayOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER
    )

    private val poller = object : Runnable {
        override fun run() {
            val p = mp
            if (p != null && (isArchive || timeshifting)) {
                val len = p.length
                if (len > 0) {
                    if (knownDurationMs <= 0) durationMs = len
                    b.durText.text = fmt(scaleMs())
                    if (startSeekTo > 0) { p.time = minOf(startSeekTo, len - 2000); startSeekTo = 0 }
                }
                if (!seeking) {
                    val t = p.time
                    // Hold the UI on the requested position until the (async) seek lands, so the
                    // thumb/time don't snap back to the pre-seek spot for a frame.
                    if (seekTarget >= 0 && (Math.abs(t - seekTarget) < 3000 || android.os.SystemClock.uptimeMillis() > seekDeadline))
                        seekTarget = -1L
                    if (seekTarget < 0) {
                        val sc = scaleMs()
                        b.posText.text = fmt(t)
                        b.seek.progress = if (sc > 0) ((t * 1000) / sc).toInt().coerceIn(0, 1000) else 0
                    }
                }
            }
            ui.postDelayed(this, 500)
        }
    }

    // Tablet swipe (live only): up/right = previous, down/left = next (like flicking photos).
    private val gestureDetector by lazy {
        android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent): Boolean = true
            override fun onSingleTapUp(e: android.view.MotionEvent): Boolean { if (playFailed) retryNow() else toggleBar(); return true }
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || isArchive || timeshifting) return false
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
        PipService.stop(this) // opening fullscreen playback closes any existing pop-up
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val url = intent.getStringExtra("url") ?: run { finish(); return }
        titleText = intent.getStringExtra("title") ?: ""
        chIndex = intent.getIntExtra("chIndex", -1)
        isArchive = intent.getBooleanExtra("archive", false)
        channels = liveChannels
        b.title.text = titleText

        val options = arrayListOf(
            "--network-caching=${Configs.netCachingMs(this)}",
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
                MediaPlayer.Event.Playing -> {
                    b.status.visibility = View.GONE; retryCount = 0; playFailed = false; applyAspect()
                    if (onTv && (mp?.volume ?: 0) <= 0) mp?.volume = 100 // TV: seed a real volume so the icon isn't stuck on 🔇
                    refreshVol() // reflect the true volume/mute state now that audio output exists
                    if (isArchive || timeshifting) b.playBtn.text = "⏸"
                }
                MediaPlayer.Event.Paused -> { if (isArchive || timeshifting) b.playBtn.text = "▶" }
                MediaPlayer.Event.EndReached -> { if (isArchive) b.playBtn.text = "▶" else if (timeshifting) ui.post { returnToLive() } }
                MediaPlayer.Event.EncounteredError ->
                    if (!isArchive && !timeshifting) autoRetry()
                    else b.status.apply { visibility = View.VISIBLE; text = "Couldn't play this." }
                MediaPlayer.Event.RecordChanged -> { if (!ev.recording) ui.post { onRecordingStopped() } }
                else -> {}
            }
        }

        b.menuBtn.setOnClickListener { showMenu() }
        b.multiBtn.setOnClickListener { openMultiView() }
        b.multiBtn.visibility = if (!isArchive) View.VISIBLE else View.GONE
        b.root.setOnTouchListener { _, ev -> gestureDetector.onTouchEvent(ev) }
        wireQuickControls()
        val tv = Tv.isTv(this)
        b.pipBtn.setOnClickListener { enterPipFlow() }
        b.pipBtn.visibility = if (!isArchive && !tv) View.VISIBLE else View.GONE
        b.recBtn.visibility = View.VISIBLE
        b.recBtn.setOnClickListener { toggleRecord() }
        // Volume/brightness stay on TV — D-pad up/down drives the panel (see dispatchKeyEvent).

        if (isArchive) {
            knownDurationMs = intent.getLongExtra("durationSec", 0) * 1000 // program length → stable scrub timeline
            b.controlBar.visibility = View.VISIBLE   // catch-up: bar stays visible
            wireTransportControls()
        } else {
            saveLastChannel()
            // Live channels with an archive get timeshift (DVR rewind).
            if (currentArchiveSec() > 0) {
                wireTransportControls()
                b.tsBtn.visibility = View.VISIBLE
                b.tsBtn.setOnClickListener { enterTimeshift() }
            }
        }
        if (!isArchive) liveUrl = url
        play(url)
        if (!isArchive) channels.getOrNull(chIndex)?.let { loadNowNext(it) }
        showBar()
    }

    // ---- Live "now / next" bar (current programme + progress slider, STB-style) ----
    private var nowItem: Portal.EpgItem? = null
    private var nextItem: Portal.EpgItem? = null
    private var nowBarHasData = false
    private val nowProgressTick = object : Runnable {
        override fun run() {
            if (b.nowBar.visibility == View.VISIBLE) { refreshNowProgress(); ui.postDelayed(this, 30_000) }
        }
    }

    /** Resolve just the current and next programme for the channel and fill the slim bottom bar. */
    private fun loadNowNext(ch: Portal.Channel) {
        if (isArchive) return
        val mine = ch.id
        nowItem = null; nextItem = null; nowBarHasData = false
        io.execute {
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            val epg = Portal.epgForDate(ch.id, today).ifEmpty { Portal.shortEpg(ch.id) }
            val nowSec = System.currentTimeMillis() / 1000
            var cur = epg.firstOrNull { it.startTs in 1..nowSec && (it.stopTs == 0L || nowSec < it.stopTs) }
            if (cur == null) cur = epg.lastOrNull { it.startTs in 1..nowSec }
            val idx = if (cur != null) epg.indexOf(cur) else -1
            val nxt = if (idx >= 0 && idx + 1 < epg.size) epg[idx + 1] else null
            runOnUiThread {
                if (isFinishing || channels.getOrNull(chIndex)?.id != mine) return@runOnUiThread
                nowItem = cur; nextItem = nxt; nowBarHasData = cur != null
                bindNowBar()
                updateNowBar()
            }
        }
    }

    private fun bindNowBar() {
        val cur = nowItem
        if (cur == null) { b.nowTitle.text = ""; b.nowNext.text = ""; return }
        b.nowTitle.text = "🔴 NOW   ${cur.name}"
        b.nowStart.text = if (cur.startTs > 0) Portal.localTime(cur.startTs) else cur.start
        b.nowEnd.text = if (cur.stopTs > 0) Portal.localTime(cur.stopTs) else cur.end
        val nxt = nextItem
        if (nxt != null) {
            val t = if (nxt.startTs > 0) Portal.localTime(nxt.startTs) else nxt.start
            b.nowNext.text = "NEXT   $t   ${nxt.name}"
            b.nowNext.visibility = View.VISIBLE
        } else b.nowNext.visibility = View.GONE
        refreshNowProgress()
    }

    /** Slide the progress bar to how far we are through the current programme. */
    private fun refreshNowProgress() {
        val cur = nowItem ?: return
        val nowSec = System.currentTimeMillis() / 1000
        b.nowProgress.progress = if (cur.startTs in 1 until cur.stopTs)
            (((nowSec - cur.startTs).toDouble() / (cur.stopTs - cur.startTs)) * 1000).toInt().coerceIn(0, 1000)
        else 0
    }

    /** Show the now-bar only while the controls are up on a live channel that has guide data. */
    private fun updateNowBar() {
        val show = !isArchive && !timeshifting && b.topBar.visibility == View.VISIBLE && nowBarHasData
        b.nowBar.visibility = if (show) View.VISIBLE else View.GONE
        ui.removeCallbacks(nowProgressTick)
        if (show) ui.postDelayed(nowProgressTick, 30_000)
    }

    /** Wire the transport bar (play/skip/seek). Step sizes and live-edge behaviour adapt to mode. */
    private fun wireTransportControls() {
        b.playBtn.setOnClickListener { togglePlay() }
        b.rewindBtn.setOnClickListener { seekBy(if (timeshifting) -60_000 else -15_000) }
        b.forwardBtn.setOnClickListener {
            if (timeshifting && nearLiveEdge()) returnToLive() else seekBy(if (timeshifting) 60_000 else 15_000)
        }
        b.liveBtn.setOnClickListener { returnToLive() }
        b.seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && scaleMs() > 0) b.posText.text = fmt(scaleMs() * progress / 1000)
            }
            override fun onStartTrackingTouch(sb: SeekBar) { seeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                val p = mp
                val sc = scaleMs()
                if (p != null && sc > 0) seekTo(p, sc * sb.progress / 1000)
                seeking = false
            }
        })
        ui.postDelayed(poller, 500)
    }

    /** Timeline length used to map slider ⇄ time. Prefer the known, fixed duration (program length
     *  for catch-up, the timeshift window for DVR) over VLC's HLS length estimate, which drifts by
     *  minutes and makes scrubbing land far off the requested spot. */
    private fun scaleMs(): Long = if (knownDurationMs > 0) knownDurationMs else durationMs

    /** Touch slider drop: seek to [target] ms now and hold the UI there until VLC reports it arrived. */
    private fun seekTo(p: MediaPlayer, target: Long) {
        ui.removeCallbacks(applySeek)   // a touch drop overrides any in-flight D-pad scrub
        val t = target.coerceAtLeast(0)
        p.time = t
        b.posText.text = fmt(t)
        val sc = scaleMs()
        if (sc > 0) b.seek.progress = ((t * 1000) / sc).toInt().coerceIn(0, 1000)
        seekTarget = t
        seekDeadline = android.os.SystemClock.uptimeMillis() + 2500
    }

    /** Apply the accumulated D-pad scrub target once the user stops pressing. */
    private val applySeek = Runnable {
        val p = mp
        if (p != null && seekTarget >= 0) {
            p.time = seekTarget
            seekDeadline = android.os.SystemClock.uptimeMillis() + 2500
        }
    }

    private fun currentArchiveSec(): Long = (channels.getOrNull(chIndex)?.archiveDays ?: 0).toLong() * 3600

    private fun nearLiveEdge(): Boolean {
        val p = mp ?: return false
        val len = p.length
        return len > 0 && p.time >= len - 15_000
    }

    /**
     * Enter live timeshift: play the recent archive buffer as a seekable VOD, starting ~30s back.
     * The portal's stream token is session-bound (reusing the live one returns 403), so we stop the
     * live stream first to free the slot, then resolve a fresh archive link via create_link.
     */
    private fun enterTimeshift() {
        if (timeshifting || isArchive) return
        val ch = channels.getOrNull(chIndex) ?: return
        val archiveSec = ch.archiveDays.toLong() * 3600
        if (archiveSec <= 0) return
        stopRecordingIfActive() // recording is tied to the live media; rewinding changes it
        timeshifting = true
        tsWindowSec = minOf(archiveSec, 7_200L)     // up to 2h scrubbable buffer
        knownDurationMs = tsWindowSec * 1000        // fixed buffer length → stable scrub timeline
        b.tsBtn.visibility = View.GONE
        b.nowBar.visibility = View.GONE // the now-bar is for live only; the transport bar owns the bottom in timeshift
        b.controlBar.visibility = View.VISIBLE
        b.liveBtn.visibility = View.VISIBLE
        b.hint.visibility = View.GONE
        b.playBtn.text = "⏸"
        b.status.visibility = View.VISIBLE
        b.status.text = "Rewinding live…"
        mp?.stop()                                   // free the live stream so the new link isn't blocked
        startSeekTo = (tsWindowSec - 30) * 1000      // start ~30s behind the live edge
        val now = System.currentTimeMillis() / 1000
        io.execute {
            val u = Portal.archiveLink(ch.cmd, now - tsWindowSec, tsWindowSec)
            runOnUiThread {
                if (isFinishing || !timeshifting) return@runOnUiThread
                if (u.isNullOrEmpty()) {
                    android.widget.Toast.makeText(this, "Timeshift unavailable right now", android.widget.Toast.LENGTH_SHORT).show()
                    returnToLive(); return@runOnUiThread
                }
                play(u)
            }
        }
    }

    /** Leave timeshift and resume the live stream (fresh link, since timeshift freed the live one). */
    private fun returnToLive() {
        if (!timeshifting) return
        timeshifting = false
        startSeekTo = 0
        seeking = false
        knownDurationMs = 0
        seekTarget = -1L
        ui.removeCallbacks(applySeek)
        b.controlBar.visibility = View.GONE
        b.liveBtn.visibility = View.GONE
        if (currentArchiveSec() > 0) b.tsBtn.visibility = View.VISIBLE
        b.status.visibility = View.VISIBLE
        b.status.text = "▶  LIVE…"
        mp?.stop()
        val ch = channels.getOrNull(chIndex) ?: run { finish(); return }
        io.execute {
            val u = Portal.createLink(ch.cmd)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (!u.isNullOrEmpty()) { liveUrl = u; play(u) } else b.status.text = "Couldn't return to live"
            }
        }
    }

    private fun togglePlay() {
        val p = mp ?: return
        if (p.isPlaying) { p.pause(); b.playBtn.text = "▶" } else { p.play(); b.playBtn.text = "⏸" }
    }

    /**
     * D-pad scrubbing (TV remote). VLC's seek is async, so p.time still reports the *old* position on
     * the next keypress — reading it as the base makes rapid presses measure from the same stale spot
     * and land short. Instead accumulate from the pending target, show it immediately, and apply once
     * the user stops pressing (debounced), so the jumps add up and it lands where intended.
     */
    private fun seekBy(deltaMs: Long) {
        val p = mp ?: return
        val cap = if (scaleMs() > 0) scaleMs() else p.length
        val base = if (seekTarget >= 0) seekTarget else p.time
        var t = base + deltaMs
        if (t < 0) t = 0
        if (cap > 0 && t > cap - 1000) t = cap - 1000
        seekTarget = t
        seekDeadline = android.os.SystemClock.uptimeMillis() + 3000
        b.posText.text = fmt(t)
        val sc = scaleMs()
        if (sc > 0) b.seek.progress = ((t * 1000) / sc).toInt().coerceIn(0, 1000)
        ui.removeCallbacks(applySeek)
        ui.postDelayed(applySeek, 280)   // wait for the burst of presses to settle, then seek once
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
        media.setHWDecoderEnabled(Configs.hwDecode(this), false) // HW first (pref), auto software fallback
        media.addOption(":network-caching=${Configs.netCachingMs(this)}")
        media.addOption(":http-user-agent=" + Portal.UA)
        media.addOption(":http-reconnect")
        player.media = media
        media.release()
        player.play()
    }

    /** Candidate stream sources for a channel: primary cmd first, then any alternates (for failover). */
    private fun candidateCmds(ch: Portal.Channel): List<String> {
        val all = ArrayList<String>()
        if (ch.cmd.isNotBlank()) all.add(ch.cmd)
        for (c in ch.cmds) if (c.isNotBlank() && c !in all) all.add(c)
        return if (all.isEmpty()) listOf(ch.cmd) else all
    }

    /** On a live error, re-resolve a fresh link (token expiry is the usual cause), cycling through any
     *  alternate sources, with backoff — before giving up. */
    private fun autoRetry() {
        if (autoRetrying) return
        val ch = channels.getOrNull(chIndex) ?: run { showPlayFailed(); return }
        if (retryCount >= maxRetry) { showPlayFailed(); return }
        autoRetrying = true
        retryCount++
        playFailed = false
        val cmds = candidateCmds(ch)
        val cmd = cmds[(retryCount - 1) % cmds.size]
        b.status.visibility = View.VISIBLE
        b.status.text = "Reconnecting…  ($retryCount/$maxRetry)"
        ui.postDelayed({
            if (isFinishing) { autoRetrying = false; return@postDelayed }
            io.execute {
                val u = Portal.createLink(cmd)
                runOnUiThread {
                    autoRetrying = false
                    if (isFinishing) return@runOnUiThread
                    if (!u.isNullOrEmpty()) { liveUrl = u; play(u) }
                    else if (retryCount < maxRetry) autoRetry()
                    else showPlayFailed()
                }
            }
        }, 1000L * retryCount)
    }

    private fun showPlayFailed() {
        autoRetrying = false
        playFailed = true
        b.status.visibility = View.VISIBLE
        b.status.text = "Couldn't play this channel.\nTap / press OK to retry  ·  Menu ⋮ to report"
    }

    private fun retryNow() {
        retryCount = 0
        playFailed = false
        autoRetry()
    }

    /** Live only: Up = previous channel, Down = next. */
    private fun switchChannel(delta: Int) {
        if (isArchive || channels.isEmpty() || chIndex < 0) return
        var idx = chIndex + delta
        if (idx < 0) idx = 0
        if (idx > channels.size - 1) idx = channels.size - 1
        if (idx == chIndex) return
        stopRecordingIfActive() // switching channel ends the current recording
        retryCount = 0; playFailed = false
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
                liveUrl = u
                play(u)
                loadNowNext(ch)
            }
        }
    }

    private fun showBar() {
        b.topBar.visibility = View.VISIBLE
        updateNowBar()
        // The now-bar carries its own "▲ ▼ channel" hint; only show the standalone hint when it isn't on screen.
        b.hint.visibility = if (!isArchive && !timeshifting && b.nowBar.visibility != View.VISIBLE) View.VISIBLE else View.GONE
        scheduleHide()
    }

    private fun scheduleHide() {
        hideBarRunnable?.let { ui.removeCallbacks(it) }
        val r = Runnable { hideBars() }
        hideBarRunnable = r
        ui.postDelayed(r, 4000)
    }

    private fun hideBars() {
        hideBarRunnable?.let { ui.removeCallbacks(it) }
        b.topBar.visibility = View.GONE
        b.hint.visibility = View.GONE
        b.volumePanel.visibility = View.GONE
        b.brightnessPanel.visibility = View.GONE
        b.nowBar.visibility = View.GONE
        ui.removeCallbacks(nowProgressTick)
    }

    /** Tap toggles the whole control layer (top bar + left quick controls + any open panel). */
    private fun toggleBar() {
        if (b.topBar.visibility == View.VISIBLE) hideBars() else showBar()
    }

    /** Wire the top-left quick controls: aspect ratio, volume (+ mute), brightness (+ night mode). */
    private fun wireQuickControls() {
        am = ScreenControls.audio(this)
        b.aspectBtn.text = "⤢  ${aspectModes[aspectIdx]}"
        b.aspectBtn.setOnClickListener { cycleAspect(); scheduleHide() }

        b.volSeek.max = volMax()
        refreshVol()
        b.volSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    volSet(progress); updateMuteLabel()
                    b.volLabel.text = "🔊  Volume  ${if (volMax() > 0) progress * 100 / volMax() else 0}%"
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        b.volBtn.setOnClickListener { refreshVol(); openPanel(b.volumePanel) }
        b.muteBtn.setOnClickListener { toggleMute() }

        b.brightSeek.max = 100
        b.brightSeek.progress = brightGetPct()
        b.brightSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) { brightSetPct(progress); updateBrightLabel() }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        b.brightBtn.setOnClickListener {
            b.brightSeek.progress = brightGetPct()
            updateBrightLabel()
            openPanel(b.brightnessPanel)
        }
        b.nightBtn.setOnClickListener { toggleNight() }

        updateSpeedBtn()
        b.speedBtn.setOnClickListener { showSpeedDialog() }
    }

    private val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    private var speedIdx = 2

    private fun updateSpeedBtn() {
        val s = speeds[speedIdx]
        b.speedBtn.text = if (s == 1f) "1×" else (if (s % 1f == 0f) "${s.toInt()}×" else "${s}×")
    }

    private fun showSpeedDialog() {
        val labels = speeds.map { if (it == 1f) "Normal (1×)" else "${it}×" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Playback speed")
            .setSingleChoiceItems(labels, speedIdx) { d, w ->
                speedIdx = w
                mp?.rate = speeds[w]
                updateSpeedBtn()
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Open one panel (and close the other) and focus its slider so the remote ◀▶ adjusts it natively. */
    private fun openPanel(panel: View) {
        val show = panel.visibility != View.VISIBLE
        b.volumePanel.visibility = View.GONE
        b.brightnessPanel.visibility = View.GONE
        panel.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            hideBarRunnable?.let { ui.removeCallbacks(it) }
            val sb = if (panel === b.volumePanel) b.volSeek else b.brightSeek
            sb.post { sb.requestFocus() }
        } else scheduleHide()
    }

    private fun cycleAspect() {
        aspectIdx = (aspectIdx + 1) % aspectModes.size
        applyAspect()
        b.aspectBtn.text = "⤢  ${aspectModes[aspectIdx]}"
    }

    private fun applyAspect() {
        val p = mp ?: return
        when (aspectModes[aspectIdx]) {
            "Fit" -> { p.setAspectRatio(null); p.setScale(0f) }
            "16:9" -> { p.setAspectRatio("16:9"); p.setScale(0f) }
            "4:3" -> { p.setAspectRatio("4:3"); p.setScale(0f) }
            "Stretch" -> {
                val dm = resources.displayMetrics
                p.setAspectRatio("${dm.widthPixels}:${dm.heightPixels}"); p.setScale(0f)
            }
        }
    }

    private fun toggleMute() {
        if (volGet() > 0) { preMuteVol = volGet(); volSet(0) }
        else volSet(if (preMuteVol > 0) preMuteVol else volMax() / 2)
        refreshVol()
    }

    private fun refreshVol() {
        b.volSeek.progress = volGet()
        updateMuteLabel()
        val pct = if (volMax() > 0) volGet() * 100 / volMax() else 0
        b.volLabel.text = "🔊  Volume  $pct%"
    }

    private fun updateBrightLabel() { b.brightLabel.text = "☀  Brightness  ${brightGetPct()}%" }

    private fun updateMuteLabel() {
        val muted = volGet() == 0
        b.muteBtn.text = if (muted) "🔈  Unmute" else "🔇  Mute"
        b.volBtn.text = if (muted) "🔇" else "🔊"
    }

    private fun closePanels() {
        b.volumePanel.visibility = View.GONE
        b.brightnessPanel.visibility = View.GONE
        if (b.topBar.visibility == View.VISIBLE) b.topBar.requestFocus()
        scheduleHide()
    }
    private fun panelOpen() = b.volumePanel.visibility == View.VISIBLE || b.brightnessPanel.visibility == View.VISIBLE

    private fun toggleNight() {
        nightOn = !nightOn
        b.nightOverlay.visibility = if (nightOn) View.VISIBLE else View.GONE
        b.nightBtn.text = if (nightOn) "🌙  Night mode: ON" else "🌙  Night mode"
    }

    /** Start/stop recording the current stream (video + audio) to local storage → Recordings. */
    private fun toggleRecord() {
        val p = mp ?: return
        if (!isRecording) {
            val ok = try { p.record(Recordings.dir(this).absolutePath) } catch (_: Exception) { false }
            if (ok) {
                isRecording = true
                recChannel = titleText
                b.recDot.visibility = View.VISIBLE
                b.recBtn.text = "⏹"
                android.widget.Toast.makeText(this, "● Recording…", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                android.widget.Toast.makeText(this, "Couldn't start recording.", android.widget.Toast.LENGTH_SHORT).show()
            }
        } else {
            try { p.record(null) } catch (_: Exception) {} // RecordChanged(false) finalizes + saves
        }
    }

    /** Called when libVLC has finished writing the recording (or playback ended/changed). */
    private fun onRecordingStopped() {
        if (!isRecording) return
        isRecording = false
        b.recDot.visibility = View.GONE
        b.recBtn.text = "⏺"
        Recordings.newestFile(applicationContext)?.let {
            Recordings.add(applicationContext, it, recChannel.ifBlank { "Recording" }, recChannel, System.currentTimeMillis())
            android.widget.Toast.makeText(this, "Saved to Recordings ✓", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Stop + save any in-progress recording before the media changes or we leave (PiP, timeshift,
     *  channel switch, exit). libVLC recording is tied to the current media, so it must end first. */
    private fun stopRecordingIfActive() {
        if (!isRecording) return
        try { mp?.record(null) } catch (_: Exception) {}
        onRecordingStopped()
    }

    /** Shrink the live stream to the floating pop-up player (plays via ExoPlayer in the overlay). */
    private fun enterPipFlow() {
        if (!PipLauncher.hasPermission(this)) { PipLauncher.requestPermission(this); return }
        if (currentUrl.isEmpty()) return
        stopRecordingIfActive()
        PipService.start(this, currentUrl, titleText, "", "", "", 0L, true)
        finish()
    }

    private fun reportNotWorking() {
        val ch = channels.getOrNull(chIndex)
        val src = if (ch != null) "live|${ch.id}|${ch.cmd}" else "live"
        Reports.add(this, titleText.ifBlank { ch?.name ?: "Live channel" }, src)
        android.widget.Toast.makeText(this, "Reported — logged in Settings ▸ Diagnostics.", android.widget.Toast.LENGTH_SHORT).show()
    }

    private var menuDialog: AlertDialog? = null
    private fun showMenu() {
        if (menuDialog?.isShowing == true) { menuDialog?.dismiss(); return }
        val items = arrayOf("🔄   Retry stream", "⚠   Report not working", SleepTimer.menuLabel(), "🎚   Playback settings", "📡   Cast to TV", "⚙   Settings", "📥   App updates", "ℹ️   About", "✖   Exit")
        val dlg = AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> retryNow()
                    1 -> reportNotWorking()
                    2 -> SleepTimer.showDialog(this)
                    3 -> PlaybackSettings.show(this)
                    4 -> if (currentUrl.isNotEmpty()) CastHelper.show(this, currentUrl, titleText, isLive = !isArchive)
                    5 -> startActivity(Intent(this, SettingsActivity::class.java))
                    6 -> startActivity(Intent(this, AppUpdatesActivity::class.java))
                    7 -> About.show(this)
                    8 -> finishAffinity()
                }
            }
            .setOnDismissListener { menuDialog = null }
            .create()
        menuDialog = dlg
        dlg.show()
    }

    /** Open Multi-view: pane 1 = the channel playing now; other panes empty. Only the active profile's
     *  categories/channels are offered. */
    private fun openMultiView() {
        MultiViewActivity.channels = ChannelsActivity.allChannelsCatalog()
            .filter { ContentProfiles.liveCatVisible(this, it.genreId) }.ifEmpty { channels }
        MultiViewActivity.genres = ChannelsActivity.catGenres().filter { ContentProfiles.liveCatVisible(this, it.id) }
        val cur = channels.getOrNull(chIndex)
        MultiViewActivity.startChannels = if (cur != null) listOf(cur) else emptyList()
        startActivity(Intent(this, MultiViewActivity::class.java))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            // Panel open: the focused slider handles ◀▶, focus nav moves to the Mute/Night button.
            // We only intercept Back (close) — everything else goes to native focus handling.
            if (panelOpen()) {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) { closePanels(); return true }
                return super.dispatchKeyEvent(event)
            }
            if (isArchive) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_MENU -> { showMenu(); return true }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-15_000); showBar(); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(15_000); showBar(); return true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlay(); return true }
                }
            } else if (timeshifting) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_MENU -> { showMenu(); return true }
                    KeyEvent.KEYCODE_BACK -> { returnToLive(); return true }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-60_000); showBar(); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        if (nearLiveEdge()) returnToLive() else seekBy(60_000); showBar(); return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlay(); return true }
                }
            } else {
                // When the options bar is showing, the D-pad navigates the on-screen buttons (focus);
                // channel switching only happens when the bar is hidden.
                val barShown = b.topBar.visibility == View.VISIBLE
                if (barShown && event.keyCode in OPTION_NAV_KEYS) scheduleHide() // keep bar up while navigating
                when (event.keyCode) {
                    KeyEvent.KEYCODE_MENU -> { showMenu(); return true }
                    KeyEvent.KEYCODE_CHANNEL_UP -> { switchChannel(-1); return true }   // dedicated keys always switch
                    KeyEvent.KEYCODE_CHANNEL_DOWN -> { switchChannel(1); return true }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (!barShown) { switchChannel(-1); return true }
                        if (b.topBar.findFocus() == null) { b.topBar.requestFocus(); return true } // jump into options
                        // else let focus move (super)
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (!barShown) { switchChannel(1); return true }
                        // bar shown: stay on the options row (let super)
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        if (!barShown && currentArchiveSec() > 0) { enterTimeshift(); return true }
                        // bar shown: let super move focus left among the options
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (playFailed) { retryNow(); return true }
                        if (!barShown) { showBar(); b.topBar.requestFocus(); return true } // show + land on options
                        // bar shown: let super activate the focused option
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private var resumedOnce = false
    override fun onResume() {
        super.onResume()
        // onStop() stops playback to free the stream; when we come back (e.g. from Settings/Diagnostics)
        // re-start the current stream so the player isn't left on a blank screen.
        if (resumedOnce && !playFailed && currentUrl.isNotEmpty()) play(currentUrl)
        resumedOnce = true
    }

    override fun onStop() {
        super.onStop()
        stopRecordingIfActive()
        mp?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        hideBarRunnable?.let { ui.removeCallbacks(it) }
        ui.removeCallbacks(poller)
        ui.removeCallbacks(applySeek)
        mp?.let { it.stop(); it.detachViews(); it.release() }
        mp = null
        libVlc?.release()
        libVlc = null
    }
}
