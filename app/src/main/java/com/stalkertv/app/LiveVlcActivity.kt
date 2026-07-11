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
        // Episode playlist handed over from PlayerActivity when switching engines mid-title, so VLC
        // can autoplay-next just like ExoPlayer. Consumed once in onCreate.
        var vodPlaylist: List<PlayerActivity.PlaylistItem> = emptyList()
        var vodPlaylistIndex = -1
        // True while a fullscreen LIVE stream is open (or still releasing). The channel-grid preview
        // waits for this to clear before opening its own stream, so the portal's single concurrent-stream
        // slot is free (otherwise the preview gets HTTP 403 → ~10s black retry storm on return).
        @Volatile var liveStreamHeld = false
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
    private var vlcAttached = false   // video surface attached? (detach on stop, re-attach on resume)

    private var isArchive = false
    // --- VOD mode: movie/episode played through VLC (a sub-type of the scrubbable archive UI) with
    // full resume-save, Continue Watching and autoplay-next, so VLC is a true equal to ExoPlayer. ---
    private var isVod = false
    private var resumeId = ""
    private var resumeSource = ""
    private var resumePoster = ""
    private var vodEpList: List<PlayerActivity.PlaylistItem> = emptyList()
    private var vodEpIndex = -1
    private var vodAdvancing = false
    private var vodLastPos = 0L      // position captured in onStop so onResume can seek back to it
    private var vodSpeed = 1f        // playback speed, carried across engine switches
    private var vodSubPath = ""      // applied subtitle file (cacheDir/subtitle.srt), carried across switches
    private var vodSubAttached = false // guard so we attach the subtitle once per media
    private var movieYear = ""        // release year, scopes subtitle search + carried across engine switches
    private val resumeSaver = object : Runnable {
        override fun run() { saveVodResume(); ui.postDelayed(this, 10_000) }
    }
    private var seeking = false      // user is dragging the slider
    private var durationMs = 0L
    private var knownDurationMs = 0L // authoritative timeline length (program / timeshift window); 0 = fall back to VLC length
    private var seekTarget = -1L     // ms we just seeked to; poller holds the UI here until VLC catches up
    private var seekDeadline = 0L    // uptime cutoff so a clamped/failed seek can't freeze the UI
    private var currentUrl = ""      // stream currently playing
    private var timeshifting = false // live rewind (DVR) mode
    private var tsWindowSec = 0L     // scrubbable timeshift buffer length
    private var startSeekTo = 0L     // pending seek (ms) to apply once the stream length is known
    private var liveUrl = ""         // current live stream URL — its token is reused for timeshift

    private lateinit var am: AudioManager
    private val onTv by lazy { Tv.isTv(this) }
    private var tvDim = 0f          // TV "brightness": software dim-overlay alpha (0 = none)

    /** Apply the session mute to the player's own audio output (independent of device volume). */
    private fun applyPlayPrefsAudio() {
        mp?.volume = if (PlayPrefs.muted) 0 else 100
    }

    /** Apply the session brightness + night mode (screen overlays; safe to call any time). */
    private fun applyPlayPrefsScreen() {
        if (PlayPrefs.brightPct in 0..100) brightSetPct(PlayPrefs.brightPct)
        if (PlayPrefs.night) { nightOn = true; b.nightOverlay.visibility = View.VISIBLE; b.nightBtn.text = "🌙  Night mode: ON" }
    }
    private fun brightGetPct() = if (onTv) ((1f - tvDim) * 100).toInt() else (ScreenControls.brightness(window) * 100).toInt()
    private fun brightSetPct(pct: Int) {
        val f = pct.coerceIn(0, 100) / 100f
        if (onTv) { tvDim = (1f - f).coerceIn(0f, 0.92f); b.dimOverlay.alpha = tvDim }
        else ScreenControls.setBrightness(window, f)
        PlayPrefs.brightPct = pct.coerceIn(0, 100)
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
        CrashLog.install(this)
        b = ActivityLivevlcBinding.inflate(layoutInflater)
        setContentView(b.root)
        PipService.stop(this) // opening fullscreen playback closes any existing pop-up
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val url = intent.getStringExtra("url") ?: run { finish(); return }
        titleText = intent.getStringExtra("title") ?: ""
        chIndex = intent.getIntExtra("chIndex", -1)
        isArchive = intent.getBooleanExtra("archive", false)
        isVod = intent.getBooleanExtra("vod", false)
        if (isVod) {
            isArchive = true   // reuse the scrubbable finite-timeline UI (slider + skip controls)
            resumeId = intent.getStringExtra("resumeId") ?: ""
            resumeSource = intent.getStringExtra("resumeSource") ?: ""
            resumePoster = intent.getStringExtra("resumePoster") ?: ""
            vodSpeed = intent.getFloatExtra("speed", 1f)
            movieYear = intent.getStringExtra("year") ?: ""
            vodSubPath = intent.getStringExtra("subPath") ?: ""
            // No carried subtitle? Auto-load the one saved for this title (resume without a new search).
            if (vodSubPath.isBlank()) SubStore.saved(this, resumeId.ifBlank { resumeSource })?.let { vodSubPath = it.absolutePath }
            vodEpList = vodPlaylist; vodEpIndex = vodPlaylistIndex
            vodPlaylist = emptyList(); vodPlaylistIndex = -1
        }
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
        buildVlcPlayer(vlc)

        b.menuBtn.setOnClickListener { showMenu() }
        b.multiBtn.setOnClickListener { openMultiView() }
        b.multiBtn.visibility = if (!isArchive) View.VISIBLE else View.GONE
        b.root.setOnTouchListener { _, ev -> gestureDetector.onTouchEvent(ev) }
        wireQuickControls()
        applyPlayPrefsScreen()   // restore session brightness + night mode
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
            // Subtitle icon (ExoPlayer's CC-button parity): pick embedded tracks, turn off, or search online.
            b.subBtn.visibility = View.VISIBLE
            b.subBtn.setOnClickListener { showSubtitleMenu() }
            if (isVod) {
                b.recBtn.visibility = View.GONE   // recording a movie/episode makes no sense
                val rs = intent.getLongExtra("resumeStart", 0L)
                if (rs > 0) startSeekTo = rs   // poller applies it once the stream length is known
                if (resumeId.isNotBlank()) ui.postDelayed(resumeSaver, 10_000)
            }
        } else {
            saveLastChannel()
            // Live channels with an archive get timeshift (DVR rewind).
            if (currentArchiveSec() > 0) {
                wireTransportControls()
                b.tsBtn.visibility = View.VISIBLE
                b.tsBtn.setOnClickListener { enterTimeshift() }
            }
        }
        if (!isArchive) { liveUrl = url; liveStreamHeld = true } // grid preview waits for this to clear
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
                freshPlay(u)
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
                if (!u.isNullOrEmpty()) { liveUrl = u; freshPlay(u) } else b.status.text = "Couldn't return to live"
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
        // Tag adult/censored/PIN-locked channels so they never surface on the home Continue Watching rail (PIN bypass).
        Resume.save(applicationContext, Resume.LIVE_ID, "live", ch.name, ch.logoUrl, "live|${ch.id}|${ch.cmd}", 0, 0,
            restricted = ch.censored || ch.locked)
    }

    /** Create a fresh MediaPlayer bound to the video surface + event listener. */
    private fun buildVlcPlayer(vlc: LibVLC) {
        val player = MediaPlayer(vlc)
        mp = player
        // 3rd arg = enableSubtitles: MUST be true or libVLC selects the SPU track but never PAINTS it
        // (the movie plays with no visible subtitle text, which is exactly what we hit for weeks).
        player.attachViews(b.vlc, null, true, false)
        vlcAttached = true
        player.setEventListener { ev -> onVlcEvent(ev) }
    }

    private fun onVlcEvent(ev: MediaPlayer.Event) {
        when (ev.type) {
            MediaPlayer.Event.Playing -> {
                b.status.visibility = View.GONE; retryCount = 0; playFailed = false; applyAspect()
                applyPlayPrefsAudio() // restore session mute/volume (survives channel changes & re-entry)
                applyVlcBoost() // amplify quiet VOD titles per the shared Audio-boost setting
                refreshVol() // reflect the true volume/mute state now that audio output exists
                if (isVod) applyVodPlaybackState() // re-apply carried speed + subtitle
                if (isArchive || timeshifting) b.playBtn.text = "⏸"
            }
            MediaPlayer.Event.Paused -> { if (isArchive || timeshifting) b.playBtn.text = "▶" }
            // Feed the interpolated clock used by the subtitle overlay (mp.time alone lags 1-2s on
            // Fire's MediaTek decode — "no reference clock" — making subs appear late).
            MediaPlayer.Event.TimeChanged -> {
                vlcTimeBase = ev.timeChanged
                vlcTimeStamp = android.os.SystemClock.uptimeMillis()
            }
            MediaPlayer.Event.EndReached -> {
                if (isVod) ui.post { onVodEnded() }
                else if (isArchive) b.playBtn.text = "▶"
                else if (timeshifting) ui.post { returnToLive() }
            }
            MediaPlayer.Event.EncounteredError ->
                if (!isArchive && !timeshifting) autoRetry()
                else b.status.apply { visibility = View.VISIBLE; text = "Couldn't play this." }
            MediaPlayer.Event.RecordChanged -> { if (!ev.recording) ui.post { onRecordingStopped() } }
            else -> {}
        }
    }

    /** Returning to the foreground: the surface was torn down and (for live) the stream token is often
     *  dead, so rebuild the player from scratch and re-resolve a fresh link — replaying the stale URL
     *  just hangs on "Loading" with no video/audio. */
    private fun rebuildAndResume() {
        val vlc = libVlc ?: return
        try { mp?.let { it.stop(); if (vlcAttached) it.detachViews(); it.release() } } catch (_: Exception) {}
        vlcAttached = false
        buildVlcPlayer(vlc)
        b.status.visibility = View.VISIBLE; b.status.text = "Loading…"
        if (isVod || timeshifting) {
            if (isVod && vodLastPos > 0) startSeekTo = vodLastPos
            try { play(currentUrl) } catch (_: Exception) { showPlayFailed() }
            return
        }
        val ch = channels.getOrNull(chIndex)
        if (ch == null) { try { play(currentUrl) } catch (_: Exception) { showPlayFailed() }; return }
        io.execute {
            // Coming back from 4-pane multi-view the portal is still freeing those stream slots, so the
            // first fresh link often comes back empty — retry a few times before giving up.
            var u: String? = null
            for (attempt in 0 until 4) {
                u = Portal.createLink(ch.cmd)
                if (!u.isNullOrEmpty()) break
                try { Thread.sleep(700) } catch (_: Exception) {}
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val url = if (!u.isNullOrEmpty()) u else currentUrl
                if (!u.isNullOrEmpty()) liveUrl = u
                try { play(url) } catch (_: Exception) { showPlayFailed() }
            }
        }
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
        // Subtitle is attached at runtime via addSlave once Playing (see applyVodPlaybackState) — a single
        // path, so we don't end up with duplicate/competing SPU tracks. Surface subtitles are enabled in
        // buildVlcPlayer (attachViews enableSubtitles=true), which is what actually makes them render.
        vodSubAttached = false
        player.media = media
        media.release()
        player.play()
    }

    /**
     * Switch to a new stream with a *fresh* decoder. Reusing the same MediaPlayer across a stream
     * change (play()'s stop + new media) can segfault the hardware video decoder on low-end MediaTek
     * boxes (MtkOmxVdecDecod SIGSEGV → whole-app crash-loop), which is what killed the app during
     * live↔timeshift transitions and channel surfing. Tearing the player fully down and rebuilding it
     * guarantees the old OMX decode session is released before the new one starts.
     * MUST be called on the UI thread and never from inside a VLC event callback.
     */
    private fun freshPlay(url: String) {
        val vlc = libVlc ?: return
        try { mp?.let { it.stop(); if (vlcAttached) it.detachViews(); it.release() } } catch (_: Exception) {}
        vlcAttached = false
        buildVlcPlayer(vlc)
        play(url)
    }

    // ---------------------------------------------------------------------------------------------
    // VOD-in-VLC: resume, Continue Watching and autoplay-next (parity with the ExoPlayer VOD screen)
    // ---------------------------------------------------------------------------------------------

    /** Persist the current position to Continue Watching (same store/keys as the ExoPlayer screen). */
    private fun saveVodResume() {
        if (!isVod || resumeId.isBlank()) return
        val p = mp ?: return
        val pos = p.time
        val dur = if (knownDurationMs > 0) knownDurationMs else p.length
        if (pos > 0) Resume.save(applicationContext, resumeId, "vod", titleText, resumePoster, resumeSource, pos, if (dur > 0) dur else 0, movieYear)
    }

    /** End of a VOD item: autoplay the next episode, or (movie / autoplay off) offer to drop it. */
    private fun onVodEnded() {
        if (vodEpList.isNotEmpty() && Configs.autoplay(this)) advanceVodEpisode() else promptRemoveFromContinue()
    }

    /** A finished movie/episode with nothing to autoplay: ask whether to drop it from Continue Watching. */
    private fun promptRemoveFromContinue() {
        val id = resumeId
        if (id.isBlank()) { finish(); return }
        mp?.pause()
        AlertDialog.Builder(this)
            .setTitle("Finished watching")
            .setMessage("Remove “$titleText” from Continue Watching?")
            .setCancelable(false)
            .setPositiveButton("Yes, remove") { _, _ ->
                resumeId = ""  // stop onStop/onDestroy re-adding it
                Resume.remove(applicationContext, id); finish()
            }
            .setNegativeButton("Keep") { _, _ ->
                resumeId = ""
                val dur = if (knownDurationMs > 0) knownDurationMs else (mp?.length ?: 0L)
                Resume.save(applicationContext, id, "vod", titleText, resumePoster, resumeSource, 0L, dur)
                finish()
            }
            .show()
    }

    /** Advance to the next episode in the season (rolling into the next season at the end). */
    private fun advanceVodEpisode() {
        if (vodAdvancing || vodEpList.isEmpty() || vodEpIndex < 0) return
        if (vodEpIndex + 1 >= vodEpList.size) { loadNextVodSeason(); return }
        vodAdvancing = true
        saveVodResume()
        playVodEpisode(vodEpList[vodEpIndex + 1], vodEpIndex + 1)
    }

    /** Resolve the item's fresh URL on IO and start it, updating title + resume identity. */
    private fun playVodEpisode(item: PlayerActivity.PlaylistItem, newIndex: Int) {
        vodEpIndex = newIndex
        titleText = item.title; b.title.text = item.title
        resumeId = item.resumeId; resumeSource = item.source; resumePoster = item.poster
        knownDurationMs = 0            // new episode: unknown length → fall back to VLC's own timeline
        seekTarget = -1L; startSeekTo = 0
        vodSubPath = ""               // a new episode has its own (or no) subtitle; speed persists
        stopSrtOverlay()
        android.widget.Toast.makeText(this, "▶  Next: ${item.title}", android.widget.Toast.LENGTH_SHORT).show()
        io.execute {
            val url = Downloads.resolveSource(item.source)
            runOnUiThread {
                vodAdvancing = false
                if (isFinishing) return@runOnUiThread
                if (url.isNullOrEmpty()) {
                    android.widget.Toast.makeText(this, "Couldn't load the next episode.", android.widget.Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                play(url)
            }
        }
    }

    /** End of season → first episode of the next season, if any (mirrors the ExoPlayer screen). */
    private fun loadNextVodSeason() {
        if (vodEpList.isEmpty() || vodEpIndex < 0) return
        val cur = vodEpList[vodEpIndex]
        val parts = cur.source.split("|")            // ep | seriesId | seasonId | episodeId
        if (parts.size < 4 || parts[0] != "ep") return
        val seriesId = parts[1]; val curSeasonId = parts[2]
        val seriesName = cur.title.split("/").getOrElse(0) { "" }.trim()
        val poster = cur.poster
        vodAdvancing = true
        io.execute {
            val seasons = Portal.seriesSeasons(seriesId).reversed()
            val curIdx = seasons.indexOfFirst { it.id == curSeasonId }
            val nextSeason = if (curIdx >= 0) seasons.getOrNull(curIdx + 1) else null
            val ordered = if (nextSeason != null) Portal.seriesEpisodes(seriesId, nextSeason.id).reversed() else emptyList()
            runOnUiThread {
                if (isFinishing) { vodAdvancing = false; return@runOnUiThread }
                if (nextSeason == null || ordered.isEmpty()) {
                    vodAdvancing = false
                    android.widget.Toast.makeText(this, "That was the last episode — no more episodes or seasons after this.", android.widget.Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                vodEpList = ordered.map { ep ->
                    PlayerActivity.PlaylistItem(
                        "$seriesName  /  ${nextSeason.name}  /  ${ep.name}",
                        "ep_${seriesId}_${nextSeason.id}_${ep.id}", poster,
                        "ep|$seriesId|${nextSeason.id}|${ep.id}"
                    )
                }
                android.widget.Toast.makeText(this, "▶  Next season: ${nextSeason.name}", android.widget.Toast.LENGTH_SHORT).show()
                playVodEpisode(vodEpList[0], 0)
            }
        }
    }

    /** Hand this title back to the ExoPlayer engine, carrying position + resume identity + playlist
     *  + the playback state (speed, applied subtitle) so nothing resets across the switch. */
    private fun switchToExo() {
        val p = mp ?: return
        val pos = p.time
        saveVodResume()
        PlayerActivity.preferVlc = false   // user chose the default engine → new titles open in ExoPlayer
        PlayerActivity.playlist = vodEpList
        PlayerActivity.playlistIndex = vodEpIndex
        val i = Intent(this, PlayerActivity::class.java)
            .putExtra("url", currentUrl)
            .putExtra("title", titleText)
            .putExtra("resumeId", resumeId)
            .putExtra("resumeSource", resumeSource)
            .putExtra("resumePoster", resumePoster)
            .putExtra("resumeStart", pos)
            .putExtra("speed", vodSpeed)
            .putExtra("subPath", vodSubPath)
            .putExtra("year", movieYear)
        startActivity(i)
        finish()
    }

    /** Re-apply the carried playback speed once the media is playing, keeping the top-bar speed button
     *  in sync (subtitle rides on the media slave set in play(), so it's already loaded here). */
    private fun applyVodPlaybackState() {
        speedIdx = speeds.indexOfFirst { it == vodSpeed }.let { if (it < 0) 2 else it }
        try { mp?.rate = speeds[speedIdx] } catch (_: Exception) {}
        updateSpeedBtn()
        // libVLC resets SPU delay on new media — re-apply the user's timing shift for embedded tracks.
        if (subSyncMs != 0L) try { mp?.setSpuDelay(-subSyncMs * 1000L) } catch (_: Exception) {}
        // External subtitle → OUR overlay renderer (once per media). libVLC's addSlave path selects
        // the track but never PAINTS it on Fire hardware decode ("no reference clock" breaks SPU
        // scheduling), so we parse the SRT and draw it ourselves, synced to the player clock.
        if (vodSubPath.isNotEmpty() && !vodSubAttached) {
            vodSubAttached = true
            startSrtOverlay(java.io.File(vodSubPath))
        } else if (vodSubPath.isEmpty()) {
            // No external sub carried/saved — the subtitles the user saw in ExoPlayer were the stream's
            // EMBEDDED track (media3 auto-selects English). VLC doesn't auto-select on Fire, so do it here.
            for (d in longArrayOf(800, 2000, 4000)) ui.postDelayed({ selectEmbeddedEnglishTrack() }, d)
        }
    }

    // ---- The app's own SRT overlay (external subtitles) ----
    private var srtCues: List<SrtSubs.Cue> = emptyList()
    private var subSyncMs = 0L    // user timing adjust: positive = show subtitles EARLIER
    private var vlcTimeBase = 0L  // last TimeChanged position…
    private var vlcTimeStamp = 0L // …and when it arrived (uptime) — interpolate between sparse events

    /** Playback position for subtitle timing: the last TimeChanged value advanced by wall-clock,
     *  because polling mp.time lags 1-2s behind the picture on Fire hardware decode. */
    private fun playerTimeNow(): Long {
        val p = mp ?: return 0L
        return try {
            if (p.isPlaying && vlcTimeStamp > 0)
                vlcTimeBase + ((android.os.SystemClock.uptimeMillis() - vlcTimeStamp) * p.rate).toLong()
            else p.time
        } catch (_: Exception) { 0L }
    }

    private val srtTick = object : Runnable {
        override fun run() {
            if (srtCues.isEmpty()) return
            val t = playerTimeNow() + subSyncMs
            val txt = SrtSubs.cueAt(srtCues, t)?.text ?: ""
            if (b.subtitleText.text.toString() != txt) {
                b.subtitleText.text = txt
                b.subtitleText.visibility = if (txt.isEmpty()) View.GONE else View.VISIBLE
            }
            ui.postDelayed(this, 200)
        }
    }

    /** ⏱ fine-tune when overlay subtitles appear (compensates stream/decoder clock drift). */
    private fun showSubSyncDialog() {
        val opts = (-8..8).map { it * 500L } // −4s … +4s in ½s steps
        val labels = opts.map { o ->
            val base = when {
                o == 0L -> "On time (default)"
                o > 0 -> "Show %.1fs earlier".format(o / 1000f)
                else -> "Show %.1fs later".format(-o / 1000f)
            }
            if (o == subSyncMs) "✔   $base" else base
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("⏱  Subtitle timing")
            .setItems(labels) { _, w -> setSubSync(opts[w]) }
            .setNegativeButton("Cancel", null).show()
    }

    /** Apply a timing shift to BOTH subtitle paths: our SRT overlay (via subSyncMs, read each tick)
     *  and libVLC's own SPU track (embedded/selected) via setSpuDelay. Positive = show EARLIER, so
     *  VLC's delay is negated (its positive delay means later). */
    private fun setSubSync(ms: Long) {
        subSyncMs = ms
        try { mp?.setSpuDelay(-ms * 1000L) } catch (_: Exception) {} // libVLC delay is in microseconds
        android.widget.Toast.makeText(this,
            if (ms == 0L) "Subtitle timing: on time"
            else "Subtitle timing: %.1fs %s".format(Math.abs(ms) / 1000f, if (ms > 0) "earlier" else "later"),
            android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun startSrtOverlay(f: java.io.File) {
        io.execute {
            val cues = SrtSubs.parse(f)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                srtCues = cues
                ui.removeCallbacks(srtTick)
                if (cues.isNotEmpty()) ui.post(srtTick)
                else android.widget.Toast.makeText(this, "Couldn't read that subtitle file.", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopSrtOverlay() {
        srtCues = emptyList()
        ui.removeCallbacks(srtTick)
        b.subtitleText.visibility = View.GONE
    }

    private var subUserOff = false // the user chose "Off" — never auto re-enable behind their back

    /** No external subtitle for this title? Match ExoPlayer's default of auto-showing an embedded
     *  ENGLISH text track, so switching engines doesn't silently lose the subtitles the user was
     *  watching. Retried, because embedded tracks register asynchronously after Playing. */
    private fun selectEmbeddedEnglishTrack() {
        if (subUserOff) return
        val p = mp ?: return
        try {
            if (p.spuTrack >= 0) return // something is already selected
            val tracks = p.spuTracks?.filter { it.id >= 0 } ?: return
            // Prefer an English track; else fall back to a closed-captions track (US TV shows carry
            // CEA-608 CCs with no language name), else the first available — so embedded subs show
            // automatically like Strimix's "Closed captions 1".
            val t = tracks.firstOrNull { it.name.contains("eng", ignoreCase = true) }
                ?: tracks.firstOrNull { it.name.contains("caption", ignoreCase = true) || it.name.contains("CC", ignoreCase = true) }
                ?: tracks.firstOrNull()
            if (t != null) p.setSpuTrack(t.id)
        } catch (_: Exception) {}
    }

    /** 💬 icon: the subtitle picker — Off / downloaded SRT (own overlay) / embedded tracks / search online. */
    private fun showSubtitleMenu() {
        val p = mp
        val tracks = try { p?.spuTracks?.filter { it.id >= 0 } ?: emptyList() } catch (_: Exception) { emptyList() }
        val cur = try { p?.spuTrack ?: -1 } catch (_: Exception) { -1 }
        val overlayOn = srtCues.isNotEmpty()
        val hasDl = vodSubPath.isNotEmpty()
        val names = ArrayList<String>()
        names.add(if (!overlayOn && cur < 0) "✔   Off" else "✖   Off")
        if (hasDl) names.add((if (overlayOn) "✔   " else "💬   ") + "Downloaded subtitle")
        val trackBase = names.size
        for (t in tracks) names.add((if (t.id == cur) "✔   " else "💬   ") + t.name)
        val timingIdx = if (overlayOn || cur >= 0) { names.add("⏱   Adjust timing"); names.size - 1 } else -1
        names.add("🔍   Search online subtitles…")
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Subtitles")
            .setItems(names.toTypedArray()) { _, w ->
                when {
                    w == 0 -> { subUserOff = true; stopSrtOverlay(); try { p?.setSpuTrack(-1) } catch (_: Exception) {} }
                    hasDl && w == 1 -> { subUserOff = false; startSrtOverlay(java.io.File(vodSubPath)) }
                    w == timingIdx -> showSubSyncDialog()
                    w == names.size - 1 -> searchSubtitles()
                    else -> { subUserOff = false; stopSrtOverlay(); try { p?.setSpuTrack(tracks[w - trackBase].id) } catch (_: Exception) {} }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ---- VOD menu parity: audio boost, subtitles, speed, report ----

    /** libVLC amplifies up to 200%, so map the shared Audio-boost setting (Off/+4/+8/+12 dB) to a
     *  volume % (capped at 200) — the VLC-native equivalent of the ExoPlayer LoudnessEnhancer. */
    private fun mbToVlcVol(mb: Int): Int =
        if (mb <= 0) 100 else (100.0 * Math.pow(10.0, mb / 2000.0)).toInt().coerceIn(100, 200)

    private fun applyVlcBoost() {
        if (!isVod) return
        val mb = Configs.audioBoostMb(this)
        if (mb > 0 && !PlayPrefs.muted) mp?.volume = mbToVlcVol(mb) // never un-mute a muted player
    }

    private fun cycleVlcAudioBoost() {
        Configs.cycleAudioBoost(this)
        val mb = Configs.audioBoostMb(this)
        if (!PlayPrefs.muted) mp?.volume = if (mb > 0) mbToVlcVol(mb) else 100 // never un-mute a muted player
        refreshVol()
        val extra = if (mb >= 800) "  (VLC max ~200%)" else ""
        android.widget.Toast.makeText(this, "Audio boost: ${Configs.audioBoostLabel(this)}$extra", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun reportVod() {
        Reports.add(this, titleText, resumeSource.ifBlank { "vod" })
        android.widget.Toast.makeText(this, "Reported — logged in Settings ▸ Diagnostics.", android.widget.Toast.LENGTH_SHORT).show()
    }

    private val vodSpeeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    private fun showVodSpeedDialog() {
        val labels = vodSpeeds.map { if (it == 1f) "Normal (1×)" else "${it}×" }.toTypedArray()
        val cur = vodSpeeds.indexOfFirst { it == vodSpeed }.let { if (it < 0) 2 else it }
        AlertDialog.Builder(this)
            .setTitle("Playback speed")
            .setSingleChoiceItems(labels, cur) { d, w ->
                vodSpeed = vodSpeeds[w]
                try { mp?.rate = vodSpeed } catch (_: Exception) {}
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun searchQuery(): String = Subtitles.queryFor(titleText)

    /** Stable key for remembering a title's chosen subtitle across sessions. */
    private fun subKey() = resumeId.ifBlank { resumeSource }

    private fun searchSubtitles() {
        Configs.ossKey(this).let { if (it.isNotBlank()) Subtitles.apiKey = it }
        val q = searchQuery()
        if (q.isEmpty()) return
        SubtitleDialog.show(this, q, movieYear) { applySubtitle(it) }
    }

    private fun applySubtitle(sub: Subtitles.Sub) {
        android.widget.Toast.makeText(this, "Downloading subtitle…", android.widget.Toast.LENGTH_SHORT).show()
        io.execute {
            val file = java.io.File(cacheDir, "subtitle.srt")
            val ok = Subtitles.download(sub, file)
            runOnUiThread {
                if (!ok) {
                    android.widget.Toast.makeText(this, "Couldn't download that subtitle.", android.widget.Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                // Save it per-title (so resume / engine-switch re-loads it without another API call).
                // Our own overlay renders it instantly — no stream reload needed.
                vodSubPath = SubStore.remember(this, subKey(), file).absolutePath
                vodSubAttached = true
                subUserOff = false
                startSrtOverlay(java.io.File(vodSubPath))
                android.widget.Toast.makeText(this, "Subtitle applied ✓", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
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
                    if (!u.isNullOrEmpty()) { liveUrl = u; freshPlay(u) }
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
    private var pendingSwitch: Runnable? = null

    /** Channel up/down. Rapid presses (BLE remote repeats / surfing) only update the selection + title
     *  immediately; the actual stream load is debounced ~350ms so we don't churn libVLC (a native-crash
     *  trigger on some TVs) by stopping/starting a media for every intermediate channel. */
    private fun switchChannel(delta: Int) {
        if (isArchive || channels.isEmpty() || chIndex < 0) return
        var idx = chIndex + delta
        if (idx < 0) idx = 0
        if (idx > channels.size - 1) idx = channels.size - 1
        if (idx == chIndex && pendingSwitch == null) return
        chIndex = idx
        val ch = channels[idx]
        titleText = ch.name
        b.title.text = ch.name
        showBar()
        b.status.visibility = View.VISIBLE
        b.status.text = "▶  ${ch.name}…"
        pendingSwitch?.let { ui.removeCallbacks(it) }
        val r = Runnable { commitSwitch(ch) }
        pendingSwitch = r
        ui.postDelayed(r, 350)
    }

    /** Actually load the (settled) channel's stream — runs once the surfing stops. */
    private fun commitSwitch(ch: Portal.Channel) {
        pendingSwitch = null
        stopRecordingIfActive() // switching channel ends the current recording
        retryCount = 0; playFailed = false
        saveLastChannel()
        io.execute {
            val u = Portal.createLink(ch.cmd)
            runOnUiThread {
                if (isFinishing || channels.getOrNull(chIndex)?.id != ch.id) return@runOnUiThread
                if (u.isNullOrEmpty()) { b.status.text = "No stream for ${ch.name}"; return@runOnUiThread }
                liveUrl = u
                try { freshPlay(u) } catch (e: Exception) { showPlayFailed() }
                loadNowNext(ch)
            }
        }
    }

    private fun showBar() {
        b.topBar.visibility = View.VISIBLE
        if (isVod) b.controlBar.visibility = View.VISIBLE  // VOD transport auto-hides with the top bar
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
        if (isVod) b.controlBar.visibility = View.GONE  // movie transport hides too (was pinned forever)
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

        updateMuteLabel()
        b.volBtn.setOnClickListener { toggleMute() }   // simple mute/unmute toggle (no slider panel)

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
                vodSpeed = speeds[w]   // keep the carry value in sync so switching engines preserves it
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

    /** App-level mute toggle: mutes only the player's own audio (device volume keys stay independent). */
    private fun toggleMute() {
        PlayPrefs.muted = !PlayPrefs.muted
        applyPlayPrefsAudio()   // push the new mute state to the player output
        updateMuteLabel()
        scheduleHide()
    }

    /** Kept so existing callers compile — just refreshes the mute icon now (no slider/device volume). */
    private fun refreshVol() { updateMuteLabel() }

    private fun updateBrightLabel() { b.brightLabel.text = "☀  Brightness  ${brightGetPct()}%" }

    private fun updateMuteLabel() {
        b.volBtn.text = if (PlayPrefs.muted) "🔇" else "🔊"
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
        PlayPrefs.night = nightOn
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
        if (isVod) { showVodMenu(); return }
        val items = arrayOf("🔄   Retry stream", "⚠   Report not working", SleepTimer.menuLabel(), "🎚   Playback settings", "⚙   Settings", "📥   App updates", "ℹ️   About", "✖   Exit")
        val dlg = AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> retryNow()
                    1 -> reportNotWorking()
                    2 -> SleepTimer.showDialog(this)
                    3 -> PlaybackSettings.show(this)
                    4 -> startActivity(Intent(this, SettingsActivity::class.java))
                    5 -> startActivity(Intent(this, AppUpdatesActivity::class.java))
                    6 -> About.show(this)
                    7 -> finishAffinity()
                }
            }
            .setOnDismissListener { menuDialog = null }
            .create()
        menuDialog = dlg
        dlg.show()
    }

    /** Movie/episode-in-VLC menu — full parity with the ExoPlayer VOD menu (plus a Speed item, since
     *  VLC has no on-screen speed button) so switching engines loses no option. */
    private fun showVodMenu() {
        val autoLabel = if (Configs.autoplay(this)) "🔁   Autoplay next: ON" else "🔁   Autoplay next: OFF"
        val boostLabel = "🔊   Audio boost: ${Configs.audioBoostLabel(this)}"
        val items = arrayOf(
            SleepTimer.menuLabel(),
            "🎚   Playback settings",
            "🔀   Switch player (Default)",
            boostLabel,
            "⏩   Playback speed",
            "💬   Subtitles",
            "⚠   Report not working",
            autoLabel,
            "⚙   Settings",
            "📥   App updates",
            "ℹ️   About",
            "✖   Exit"
        )
        val dlg = AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> SleepTimer.showDialog(this)
                    1 -> PlaybackSettings.show(this)
                    2 -> switchToExo()
                    3 -> cycleVlcAudioBoost()
                    4 -> showSpeedDialog()
                    5 -> searchSubtitles()
                    6 -> reportVod()
                    7 -> {
                        Configs.setAutoplay(this, !Configs.autoplay(this))
                        android.widget.Toast.makeText(this, if (Configs.autoplay(this)) "Autoplay next: ON" else "Autoplay next: OFF", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    8 -> startActivity(Intent(this, SettingsActivity::class.java))
                    9 -> startActivity(Intent(this, AppUpdatesActivity::class.java))
                    10 -> About.show(this)
                    11 -> finishAffinity()
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

    /** Run a user-mapped remote action (Settings ▸ Remote control). */
    private fun performMapped(action: String) {
        when (action) {
            "channel_up" -> switchChannel(-1)
            "channel_down" -> switchChannel(1)
            "play_pause" -> togglePlay()
            "rewind" -> { if (timeshifting) seekBy(-60_000) else if (!isArchive && currentArchiveSec() > 0) enterTimeshift() else seekBy(-15_000); showBar() }
            "forward" -> { seekBy(if (timeshifting) 60_000 else 15_000); showBar() }
            "aspect" -> { cycleAspect(); showBar() }
            "menu" -> showMenu()
            "info" -> showBar()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            // Hardware mute key → app-level mute toggle (may be OS-locked on Fire TV and never arrive).
            if (event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) { toggleMute(); return true }
            // Panel open: the focused slider handles ◀▶, focus nav moves to the Mute/Night button.
            // We only intercept Back (close) — everything else goes to native focus handling.
            if (panelOpen()) {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) { closePanels(); return true }
                return super.dispatchKeyEvent(event)
            }
            // User-defined remote key mapping takes priority (Settings ▸ Remote control). Back/Home never hijacked.
            RemoteMap.actionFor(this, event.keyCode)?.let { performMapped(it); return true }
            if (isArchive) {
                if (event.keyCode == KeyEvent.KEYCODE_MENU) { showMenu(); return true }
                // VOD, ExoPlayer-style: when controls are hidden, the first press just reveals them
                // (and the top ⋮ menu) instead of acting. Catch-up keeps its immediate seek.
                // VOD with controls hidden: the first press just reveals them (ExoPlayer-style).
                if (isVod && b.topBar.visibility != View.VISIBLE) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> { showBar(); b.topBar.post { b.topBar.requestFocus() }; return true }
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN,
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { showBar(); return true }
                    }
                }
                // When the top icon bar has focus, ◀ ▶ / OK navigate & activate those icons instead of
                // seeking; ▲ reaches the bar, ▼ leaves it to seek again. Media keys always seek.
                val onTopBar = b.topBar.hasFocus()
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { if (onTopBar && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return super.dispatchKeyEvent(event); seekBy(-15_000); showBar(); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (onTopBar && event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return super.dispatchKeyEvent(event); seekBy(15_000); showBar(); return true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (onTopBar) return super.dispatchKeyEvent(event); togglePlay(); showBar(); return true }
                    KeyEvent.KEYCODE_DPAD_UP -> { showBar(); b.topBar.post { b.topBar.requestFocus() }; return true }   // reach the ⋮ menu
                    KeyEvent.KEYCODE_DPAD_DOWN -> { showBar(); if (onTopBar) b.playBtn.requestFocus(); return true }   // leave the icon bar → seek again
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
                    // Up/Down ALWAYS change channel (the options bar is horizontal — navigate it with
                    // ◀ ▶ and open/activate with OK), so fast surfing works even while the bar is showing.
                    KeyEvent.KEYCODE_DPAD_UP -> { switchChannel(-1); return true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { switchChannel(1); return true }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        if (!barShown && currentArchiveSec() > 0) { enterTimeshift(); return true }
                        // bar shown: let super move focus left among the options
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (playFailed) { retryNow(); return true }
                        if (!barShown) { showBar(); b.topBar.requestFocus(); return true } // show + land on options
                        if (b.topBar.findFocus() == null) { b.topBar.requestFocus(); return true } // bar up but not in it → enter options
                        // an option is focused → let super activate it
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
        if (resumedOnce && !playFailed && currentUrl.isNotEmpty()) rebuildAndResume()
        resumedOnce = true
    }

    override fun onStop() {
        super.onStop()
        if (isVod) { vodLastPos = mp?.time ?: 0L; saveVodResume() }
        stopRecordingIfActive()
        mp?.stop()
        // Detach the surface so it re-binds cleanly next time (fixes blank-video-with-audio on return).
        if (vlcAttached) { try { mp?.detachViews() } catch (_: Exception) {}; vlcAttached = false }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isVod) saveVodResume()
        ui.removeCallbacksAndMessages(null) // drops every posted runnable incl. the async subtitle-select retries, so nothing holds this activity past teardown
        // Detach the surface on the MAIN thread (must match the attach thread), then release the player
        // + LibVLC on a BACKGROUND thread: on low-end MediaTek boxes the native decoder teardown takes
        // ~10s, which was blocking onDestroy (→ "Activity destroy timeout"/ANR) AND holding the live
        // stream so the grid preview got HTTP 403 until it finished. Backgrounding returns onDestroy
        // instantly and frees the stream sooner.
        if (vlcAttached) { try { mp?.detachViews() } catch (_: Exception) {}; vlcAttached = false }
        val p = mp; val v = libVlc
        mp = null; libVlc = null
        Thread {
            try { p?.stop() } catch (_: Exception) {}
            try { p?.release() } catch (_: Exception) {}
            try { v?.release() } catch (_: Exception) {}
            liveStreamHeld = false // portal slot is now free → the grid preview may open its stream
        }.start()
    }
}
