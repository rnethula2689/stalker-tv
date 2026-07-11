package com.stalkertv.app

import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.stalkertv.app.databinding.ActivityPlayerBinding
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import java.io.File
import java.util.concurrent.Executors

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private var player: ExoPlayer? = null
    private lateinit var b: ActivityPlayerBinding
    private var videoUrl: String = ""
    private var titleText: String = ""
    private var isLive = false
    private var chIndex = -1
    private var resumeId = ""
    private var resumeSource = ""
    private var resumePoster = ""
    private var currentSubPath = ""   // applied subtitle file, carried across engine switches
    private var movieYear = ""        // release year (from portal), used to scope subtitle search + carried across switches
    private val resumeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val resumeSaver = object : Runnable {
        override fun run() { saveResume(); resumeHandler.postDelayed(this, 10_000) }
    }
    // Some streams use codecs the device can't hardware-decode (e.g. HEVC 10-bit, DTS).
    // We start hardware-first for efficiency, then on a playback error rebuild the player
    // forcing FFmpeg software decoders for both audio and video.
    private var forceSoftware = false
    private var linkRetried = false // P3.1: re-resolve a fresh link once if software-decode also fails

    private lateinit var am: AudioManager
    // Keep the on-screen volume slider synced with the hardware volume buttons (tablet drives device volume).
    private val volObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            if (!onTv && b.volumePanel.visibility == View.VISIBLE) refreshVol()
        }
    }
    private var preMuteVol = -1
    private val onTv by lazy { Tv.isTv(this) }
    private var tvDim = 0f          // TV "brightness": software dim-overlay alpha (0 = none)

    // Volume + brightness abstractions: on a TV the device stream volume is often fixed and the panel
    // backlight can't be touched, so we drive the PLAYER's own volume and a dim overlay instead.
    private fun volMax() = if (onTv) 100 else ScreenControls.maxVolume(am)
    private fun volGet() = if (onTv) ((player?.volume ?: 1f) * 100).toInt() else ScreenControls.volume(am)
    private fun volSet(v: Int) {
        val c = v.coerceIn(0, volMax())
        if (onTv) player?.volume = c / 100f else ScreenControls.setVolume(am, c)
        PlayPrefs.noteVolume(if (volMax() > 0) c * 100 / volMax() else 0)
    }
    private fun brightGetPct() = if (onTv) ((1f - tvDim) * 100).toInt() else (ScreenControls.brightness(window) * 100).toInt()
    private fun brightSetPct(pct: Int) {
        val f = pct.coerceIn(0, 100) / 100f
        if (onTv) { tvDim = (1f - f).coerceIn(0f, 0.92f); b.dimOverlay.alpha = tvDim }
        else ScreenControls.setBrightness(window, f)
        PlayPrefs.brightPct = pct.coerceIn(0, 100)
    }

    /** Apply the session mute to the player's own audio output (independent of device volume). */
    private fun applyPlayPrefsAudio() {
        player?.volume = if (PlayPrefs.muted) 0f else 1f
    }

    /** Apply the session brightness + night mode overlays. */
    private fun applyPlayPrefsScreen() {
        if (PlayPrefs.brightPct in 0..100) brightSetPct(PlayPrefs.brightPct)
        if (PlayPrefs.night) { nightOn = true; b.nightOverlay.visibility = View.VISIBLE; b.nightBtn.text = "🌙  Night mode: ON" }
    }
    private val aspectModes = listOf("Fit", "Zoom", "Stretch")
    private var aspectIdx = 0
    private var nightOn = false

    private var epList: List<PlaylistItem> = emptyList()
    private var epIndex = -1

    data class PlaylistItem(val title: String, val resumeId: String, val poster: String, val source: String)

    companion object {
        var liveChannels: List<Portal.Channel> = emptyList()
        // Set just before launching an episode so the player can auto-advance to the next one.
        var playlist: List<PlaylistItem> = emptyList()
        var playlistIndex = -1
        // Session engine preference: once the user switches a movie to VLC, new movies open in VLC
        // until they switch back to Default (or restart the app).
        var preferVlc = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Honour the session engine choice: route movies straight to VLC without building ExoPlayer.
        if (routeToPreferredEngine()) return
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        PipService.stop(this) // opening fullscreen playback closes any existing pop-up

        videoUrl = intent.getStringExtra("url") ?: run { finish(); return }
        titleText = intent.getStringExtra("title") ?: ""
        val savedKey = Configs.ossKey(this)
        if (savedKey.isNotBlank()) Subtitles.apiKey = savedKey

        // Top bar (title + Subtitles) shows/hides with the playback controls.
        b.title.text = titleText
        b.playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                b.topBar.visibility = visibility
                if (visibility == View.VISIBLE) {
                    hideDefaultGear()
                    // On TV, focus the SEEK BAR for movies so the D-pad rewinds/fast-forwards
                    // immediately (Play/Pause otherwise swallows LEFT/RIGHT). Live has no seek bar,
                    // so there we keep focus on the top option bar.
                    if (Tv.isTv(this)) {
                        if (isLive) b.topBar.post { b.topBar.requestFocus() }
                        else focusSeekBar()
                    }
                } else {
                    b.volumePanel.visibility = View.GONE
                    b.brightnessPanel.visibility = View.GONE
                }
            }
        )
        b.subBtn.setOnClickListener { showSubtitleMenu() }
        b.menuBtn.setOnClickListener { showMenu() }
        wireQuickControls()
        applyPlayPrefsScreen()   // restore session brightness + night mode

        // Episode playlist (for autoplay-next), handed over via the companion then consumed once.
        epList = playlist
        epIndex = playlistIndex
        playlist = emptyList(); playlistIndex = -1
        if (epList.isNotEmpty()) {
            b.nextBtn.visibility = View.VISIBLE
            b.nextBtn.setOnClickListener { advanceEpisode() }
            resumeHandler.postDelayed(endWatcher, 1000) // near-end fallback if STATE_ENDED never fires
        }

        isLive = intent.getBooleanExtra("live", false)
        chIndex = intent.getIntExtra("chIndex", -1)
        resumeId = intent.getStringExtra("resumeId") ?: ""
        resumeSource = intent.getStringExtra("resumeSource") ?: ""
        movieYear = intent.getStringExtra("year") ?: ""
        resumePoster = intent.getStringExtra("resumePoster") ?: ""
        val resumeStart = intent.getLongExtra("resumeStart", 0L)
        // We have our own ⏭ Next in the top bar — never show the default centre prev/next. On TV one of
        // them otherwise grabs a focus highlight in the middle of the screen the moment controls appear.
        b.playerView.setShowPreviousButton(false)
        b.playerView.setShowNextButton(false)
        if (isLive) {
            // Live TV can't seek back/forward — drop those too; Up/Down change channels.
            b.playerView.setShowFastForwardButton(false)
            b.playerView.setShowRewindButton(false)
        }

        val p = buildPlayer()
        player = p
        b.playerView.player = p
        // Subtitle to auto-attach: carried from the VLC engine (Switch player) or saved for this
        // title. Attached to the FIRST media item — the old "+800ms re-set" raced the initial
        // buffering and silently lost the subtitle when switching engines on slow/4K streams.
        val carrySub = intent.getStringExtra("subPath") ?: ""
        val autoSub = (if (carrySub.isNotEmpty()) File(carrySub) else SubStore.saved(this, subKey()))
            ?.takeIf { it.exists() && !isLive }
        val firstItem = if (autoSub != null) {
            currentSubPath = autoSub.absolutePath
            MediaItem.Builder().setUri(videoUrl).setSubtitleConfigurations(listOf(srtConfig(autoSub))).build()
        } else MediaItem.fromUri(videoUrl)
        // Start buffering DIRECTLY at the resume position — prepare()+seekTo() double-buffers
        // (loads at 0, discards it, re-buffers at the resume point) which stutters on open.
        if (resumeStart > 0 && !isLive) p.setMediaItem(firstItem, resumeStart)
        else p.setMediaItem(firstItem)
        p.prepare()
        p.playWhenReady = true
        // VOD: auto-show the stream's EMBEDDED subtitles/closed-captions by default (parity with VLC
        // and Strimix). setSelectUndeterminedTextLanguage is ESSENTIAL — CEA-608/708 closed captions
        // (common on US TV shows like "On the Case…") carry NO language, so an "en"-only preference
        // deselects them; this makes them auto-select like Strimix's "Closed captions 1".
        if (!isLive && autoSub == null) p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage("en")
            .setSelectUndeterminedTextLanguage(true)
            .build()
        applyPlayPrefsAudio()   // restore session mute/volume

        if (resumeId.isNotBlank() && !isLive) resumeHandler.postDelayed(resumeSaver, 10_000)

        b.playerView.controllerShowTimeoutMs = 6000
        b.playerView.requestFocus()
        goImmersive()
        b.playerView.post { hideDefaultGear() }

        // Carried-over playback state when arriving from the VLC engine (Switch player).
        val carrySpeed = intent.getFloatExtra("speed", 1f)
        if (carrySpeed != 1f) {
            speedIdx = speeds.indexOfFirst { it == carrySpeed }.let { if (it < 0) 2 else it }
            p.setPlaybackSpeed(speeds[speedIdx]); updateSpeedBtn()
        }
        // TV: land focus on the seek bar (not Play/Pause) on open so D-pad rewind/forward work at once.
        if (onTv && !isLive) focusSeekBar()
    }

    /** Force focus onto the seek bar (media3's exo_progress). media3 re-focuses the Play button each time
     *  it lays out the controller, so a single request loses the race — retry across a short window and
     *  make the bar explicitly focusable first. */
    private fun focusSeekBar() {
        if (isLive) return
        val bar = b.playerView.findViewById<View>(androidx.media3.ui.R.id.exo_progress) ?: return
        bar.isFocusable = true
        bar.isFocusableInTouchMode = true
        for (d in longArrayOf(150, 400, 800, 1400)) resumeHandler.postDelayed({
            if (!isLive && bar.isShown) bar.requestFocus()
        }, d)
    }

    /** The default ExoPlayer settings gear (playback speed / track menu) is redundant now that Speed
     *  and Audio live in the top control cluster — hide it whenever the controller appears. */
    private fun hideDefaultGear() {
        b.playerView.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_settings)?.visibility = View.GONE
    }

    /** True fullscreen — hide the status & navigation bars so the player (and its bottom time bar)
     *  use the whole screen instead of being crowded by the system nav bar. */
    private fun goImmersive() {
        val c = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    private fun buildPlayer(): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Portal.UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20000)
            .setReadTimeoutMs(20000)
        // Wrap so the factory can open both the http stream AND the local subtitle file://.
        val dataSource = androidx.media3.datasource.DefaultDataSource.Factory(this, http)
        val (minBuf, maxBuf) = Configs.exoBufferMs(this)
        // Give playback a healthier head-start before it begins / resumes after a seek, so a weak
        // box (Fire Stick) on high-bitrate VOD doesn't start early and stutter while the buffer fills.
        // (VOD only — live uses the separate libVLC player, so channel-zap speed is unaffected.)
        // HARD byte cap on the sample buffer. Buffered samples live on the JAVA heap (128 MB cap on
        // 32-bit Fire devices) — prioritizing time over size let a 4K movie buffer 60-120s ≈ 200-350 MB
        // → OutOfMemoryError loop when playing/resuming (verified via live heap sawtooth on a Fire HD).
        // With the cap, high-bitrate titles simply hold ~32 MB of buffer and refill continuously; the
        // time targets still give low-bitrate titles their full head-start.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBuf, maxBuf, 2500, 5000)
            .setTargetBufferBytes(32 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        // Honour the user's hardware-decoding pref, plus the auto software-fallback flag.
        val mode = if (forceSoftware || !Configs.hwDecode(this))
            androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
        else
            androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        val renderers = NextRenderersFactory(this)
            .setExtensionRendererMode(mode)
            .setEnableDecoderFallback(true)
        val p = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setLoadControl(loadControl)
            .build()
        // Respect audio focus (pause when another app takes over audio).
        p.setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true
        )
        p.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        p.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // First failure on a hardware decoder → retry once with FFmpeg software decoders.
                if (!forceSoftware) {
                    forceSoftware = true
                    rebuildSoftware()
                } else if (!linkRetried && resumeSource.isNotBlank()) {
                    // Software decode also failed → the stream link may have expired; re-resolve once.
                    linkRetried = true
                    reResolveAndReplay()
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Keep the screen awake while actually playing; let it sleep when paused.
                if (isPlaying) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_ENDED) {
                    if (epList.isNotEmpty()) maybeAutoplayNext() else promptRemoveFromContinue()
                }
            }
        })
        applyAudioBoost(p)
        return p
    }

    private var loudness: android.media.audiofx.LoudnessEnhancer? = null

    /** Boost quiet titles beyond 100% via a LoudnessEnhancer on the player's own audio session (gain
     *  from Configs). Safe no-op on devices that don't support the effect. */
    private fun applyAudioBoost(p: ExoPlayer) {
        try { loudness?.release() } catch (_: Exception) {}
        loudness = null
        try {
            val sid = ScreenControls.audio(this).generateAudioSessionId()
            p.setAudioSessionId(sid)
            val mb = Configs.audioBoostMb(this)
            loudness = android.media.audiofx.LoudnessEnhancer(sid).apply { setTargetGain(mb); enabled = mb > 0 }
        } catch (_: Exception) { loudness = null }
    }

    /** Cycle the boost level (Off → +4 → +8 → +12 dB) and apply it live. */
    private fun cycleAudioBoost() {
        Configs.cycleAudioBoost(this)
        val mb = Configs.audioBoostMb(this)
        val l = loudness
        if (l == null) { Toast.makeText(this, "Audio boost isn't supported on this device.", Toast.LENGTH_SHORT).show(); return }
        try { l.setTargetGain(mb); l.enabled = mb > 0 } catch (_: Exception) {}
        Toast.makeText(this, "Audio boost: ${Configs.audioBoostLabel(this)}", Toast.LENGTH_SHORT).show()
    }

    /** Re-resolve a fresh stream link (expired token / dead source) and replay from the same spot. */
    private fun reResolveAndReplay() {
        val pos = player?.currentPosition ?: 0L
        Toast.makeText(this, "Reconnecting…", Toast.LENGTH_SHORT).show()
        io.execute {
            val u = Downloads.resolveSource(resumeSource)
            runOnUiThread {
                if (isFinishing || u.isNullOrEmpty()) return@runOnUiThread
                videoUrl = u
                forceSoftware = false // fresh link → give hardware decode another try
                player?.release()
                val np = buildPlayer()
                player = np
                b.playerView.player = np
                if (!isLive && pos > 0) np.setMediaItem(MediaItem.fromUri(u), pos) else np.setMediaItem(MediaItem.fromUri(u))
                np.prepare()
                np.playWhenReady = true
            }
        }
    }

    /** Rebuild the player (now in software-decode mode) and resume the current stream. */
    private fun rebuildSoftware() {
        val old = player ?: return
        val pos = old.currentPosition
        old.release()
        val np = buildPlayer()
        player = np
        b.playerView.player = np
        if (!isLive && pos > 0) np.setMediaItem(MediaItem.fromUri(videoUrl), pos) else np.setMediaItem(MediaItem.fromUri(videoUrl))
        np.prepare()
        np.playWhenReady = true
    }

    /** Live TV: Up = previous channel, Down = next. Rebuilds the player to release the prior stream. */
    private fun switchChannel(delta: Int) {
        val list = liveChannels
        if (!isLive || list.isEmpty() || chIndex < 0) return
        var idx = chIndex + delta
        if (idx < 0) idx = 0
        if (idx > list.size - 1) idx = list.size - 1
        if (idx == chIndex) return
        chIndex = idx
        val ch = list[idx]
        titleText = ch.name
        b.title.text = ch.name
        forceSoftware = false // new channel: try hardware first again
        linkRetried = false
        b.playerView.showController()
        io.execute {
            val u = Portal.createLink(ch.cmd)
            runOnUiThread {
                if (u.isNullOrEmpty() || isFinishing) return@runOnUiThread
                videoUrl = u
                player?.release()
                val np = buildPlayer()
                player = np
                b.playerView.player = np
                np.setMediaItem(MediaItem.fromUri(u))
                np.prepare()
                np.playWhenReady = true
            }
        }
    }

    private var menuDialog: AlertDialog? = null
    private fun showMenu() {
        if (menuDialog?.isShowing == true) { menuDialog?.dismiss(); return }
        val sleepLabel = SleepTimer.menuLabel()
        val autoLabel = if (Configs.autoplay(this)) "🔁   Autoplay next: ON" else "🔁   Autoplay next: OFF"
        val boostLabel = "🔊   Audio boost: ${Configs.audioBoostLabel(this)}"
        val items = ArrayList<String>()
        items.add(sleepLabel)
        items.add("🎚   Playback settings")
        if (!isLive) items.add("🔀   Switch player (VLC)")   // hand this title to the libVLC engine
        items.add(boostLabel)
        items.add("⚠   Report not working")
        items.add("💬   Subtitles")
        items.add(autoLabel)
        items.add("⚙   Settings")
        items.add("📥   App updates")
        items.add("ℹ️   About")
        items.add("✖   Exit")
        val arr = items.toTypedArray()
        val dlg = AlertDialog.Builder(this)
            .setItems(arr) { _, which ->
                val a = arr[which]
                when {
                    a == sleepLabel -> SleepTimer.showDialog(this)
                    a.contains("Playback settings") -> PlaybackSettings.show(this)
                    a.contains("Switch player") -> switchToVlc()
                    a.contains("Audio boost") -> cycleAudioBoost()
                    a.contains("Report") -> {
                        Reports.add(this, titleText, resumeSource.ifBlank { "vod" })
                        Toast.makeText(this, "Reported — logged in Settings ▸ Diagnostics.", Toast.LENGTH_SHORT).show()
                    }
                    a.contains("Subtitles") -> searchSubtitles()
                    a.contains("Autoplay") -> {
                        Configs.setAutoplay(this, !Configs.autoplay(this))
                        Toast.makeText(this, if (Configs.autoplay(this)) "Autoplay next: ON" else "Autoplay next: OFF", Toast.LENGTH_SHORT).show()
                    }
                    a.contains("App updates") -> startActivity(android.content.Intent(this, AppUpdatesActivity::class.java))
                    a.contains("About") -> About.show(this)
                    a.contains("Exit") -> finishAffinity()
                    a.contains("Settings") -> startActivity(android.content.Intent(this, SettingsActivity::class.java))
                }
            }
            .setOnDismissListener { menuDialog = null }
            .create()
        dlg.setOnKeyListener { d, keyCode, ev ->
            if (keyCode == android.view.KeyEvent.KEYCODE_MENU && ev.action == android.view.KeyEvent.ACTION_UP) {
                d.dismiss(); true
            } else false
        }
        menuDialog = dlg
        dlg.show()
    }

    /** Hand the current title to the libVLC engine (some containers/codecs play cleaner there),
     *  carrying the position, Continue-Watching identity and the episode playlist so resume,
     *  Continue Watching and autoplay-next keep working in VLC exactly as they do here. */
    /** If the user's session engine choice is VLC, forward this movie to the VLC player and finish
     *  (before ExoPlayer/UI is built). Returns true if it routed. */
    private fun routeToPreferredEngine(): Boolean {
        val url = intent.getStringExtra("url") ?: ""
        if (!preferVlc || url.isEmpty() || intent.getBooleanExtra("live", false)) return false
        LiveVlcActivity.vodPlaylist = playlist
        LiveVlcActivity.vodPlaylistIndex = playlistIndex
        playlist = emptyList(); playlistIndex = -1
        startActivity(android.content.Intent(this, LiveVlcActivity::class.java)
            .putExtra("url", url)
            .putExtra("title", intent.getStringExtra("title") ?: "")
            .putExtra("vod", true)
            .putExtra("resumeId", intent.getStringExtra("resumeId") ?: "")
            .putExtra("resumeSource", intent.getStringExtra("resumeSource") ?: "")
            .putExtra("resumePoster", intent.getStringExtra("resumePoster") ?: "")
            .putExtra("resumeStart", intent.getLongExtra("resumeStart", 0L))
            .putExtra("year", intent.getStringExtra("year") ?: ""))
        finish()
        return true
    }

    private fun switchToVlc() {
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration
        saveResume()
        preferVlc = true   // remember VLC for the session → new titles open in VLC too
        LiveVlcActivity.vodPlaylist = epList
        LiveVlcActivity.vodPlaylistIndex = epIndex
        val i = android.content.Intent(this, LiveVlcActivity::class.java)
            .putExtra("url", videoUrl)
            .putExtra("title", titleText)
            .putExtra("vod", true)
            .putExtra("resumeId", resumeId)
            .putExtra("resumeSource", resumeSource)
            .putExtra("resumePoster", resumePoster)
            .putExtra("resumeStart", pos)
            .putExtra("durationSec", if (dur > 0) dur / 1000 else 0L)
            .putExtra("speed", speeds[speedIdx])
            .putExtra("subPath", currentSubPath)
            .putExtra("year", movieYear)
        startActivity(i)
        finish()
    }

    /** Menu key opens the overlay menu; any other key (except Back/volume) re-shows the controls. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val kc = event.keyCode
        if (kc == android.view.KeyEvent.KEYCODE_MENU) {
            if (event.action == android.view.KeyEvent.ACTION_UP) showMenu()
            return true
        }
        // Hardware mute key → app-level mute toggle (may be OS-locked on Fire TV and never arrive).
        if (kc == android.view.KeyEvent.KEYCODE_VOLUME_MUTE && event.action == android.view.KeyEvent.ACTION_DOWN) {
            toggleMute(); return true
        }
        // Panel open: the focused slider handles ◀▶, focus nav reaches the Mute/Night button.
        // Only intercept Back (close); everything else goes to native focus handling.
        if (event.action == android.view.KeyEvent.ACTION_DOWN &&
            (b.volumePanel.visibility == View.VISIBLE || b.brightnessPanel.visibility == View.VISIBLE)) {
            if (kc == android.view.KeyEvent.KEYCODE_BACK) { closePanels(); return true }
            return super.dispatchKeyEvent(event)
        }
        if (isLive && event.action == android.view.KeyEvent.ACTION_DOWN) {
            if (kc == android.view.KeyEvent.KEYCODE_DPAD_UP) { switchChannel(-1); return true }
            if (kc == android.view.KeyEvent.KEYCODE_DPAD_DOWN) { switchChannel(1); return true }
        }
        if (event.action == android.view.KeyEvent.ACTION_DOWN &&
            kc != android.view.KeyEvent.KEYCODE_BACK &&
            kc != android.view.KeyEvent.KEYCODE_VOLUME_UP &&
            kc != android.view.KeyEvent.KEYCODE_VOLUME_DOWN &&
            kc != android.view.KeyEvent.KEYCODE_VOLUME_MUTE
        ) {
            if (!b.playerView.isControllerFullyVisible) {
                b.playerView.showController()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Clean the display title into a search query (drops tags; adds S01E02 for episodes). */
    private fun searchQuery(): String = Subtitles.queryFor(titleText)

    /** 💬 picker: Off / the stream's embedded subtitle + closed-caption tracks / online search. */
    private fun showSubtitleMenu() {
        val p = player ?: run { searchSubtitles(); return }
        val groups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val textDisabled = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val anySelected = groups.any { g -> (0 until g.length).any { g.isTrackSelected(it) } }
        data class Row(val label: String, val apply: () -> Unit)
        val rows = ArrayList<Row>()
        rows.add(Row(if (textDisabled || !anySelected) "✔   Off" else "✖   Off") {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        })
        for (g in groups) for (i in 0 until g.length) {
            val f = g.getTrackFormat(i)
            val name = f.label ?: f.language?.uppercase() ?: "Closed captions"
            val sel = !textDisabled && g.isTrackSelected(i)
            rows.add(Row((if (sel) "✔   " else "💬   ") + name) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, i))
                    .build()
            })
        }
        rows.add(Row("🔍   Search online subtitles…") { searchSubtitles() })
        AlertDialog.Builder(this).setTitle("Subtitles")
            .setItems(rows.map { it.label }.toTypedArray()) { _, w -> rows[w].apply() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun searchSubtitles() {
        val q = searchQuery()
        if (q.isEmpty()) return
        SubtitleDialog.show(this, q, movieYear) { applySubtitle(it) }
    }

    private fun applySubtitle(sub: Subtitles.Sub) {
        Toast.makeText(this, "Downloading subtitle…", Toast.LENGTH_SHORT).show()
        io.execute {
            val file = File(cacheDir, "subtitle.srt")
            val ok = Subtitles.download(sub, file)
            runOnUiThread {
                if (!ok) {
                    Toast.makeText(this, "Couldn't download that subtitle.", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                applySubtitleFile(SubStore.remember(this, subKey(), file), toast = true)
            }
        }
    }

    /** Stable key for remembering a title's chosen subtitle across sessions. */
    private fun subKey() = resumeId.ifBlank { resumeSource }

    private fun srtConfig(file: File) = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
        .setMimeType(MimeTypes.APPLICATION_SUBRIP)
        .setLanguage("en")
        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
        .build()

    /** Attach a local subtitle .srt to the current stream at the current position (used when the
     *  user picks a new subtitle from the search dialog mid-playback). */
    private fun applySubtitleFile(file: File, toast: Boolean) {
        if (!file.exists()) return
        val pl = player ?: return
        val pos = pl.currentPosition
        val item = MediaItem.Builder()
            .setUri(videoUrl)
            .setSubtitleConfigurations(listOf(srtConfig(file)))
            .build()
        pl.setMediaItem(item, pos)
        pl.prepare()
        pl.playWhenReady = true
        pl.trackSelectionParameters = pl.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage("en").build()
        currentSubPath = file.absolutePath
        if (toast) Toast.makeText(this, "Subtitle applied ✓", Toast.LENGTH_SHORT).show()
    }

    private var advancing = false

    /** Periodic fallback: some HLS VOD streams never emit STATE_ENDED, so also advance when the
     *  position reaches the known duration. No-op for movies (empty playlist) and live. */
    private val endWatcher = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && epList.isNotEmpty() && !advancing && Configs.autoplay(this@PlayerActivity)) {
                val dur = p.duration
                if (dur > 0 && p.currentPosition >= dur - 800) advanceEpisode()
            }
            resumeHandler.postDelayed(this, 1000)
        }
    }

    /** Autoplay path: only when the setting is on. */
    private fun maybeAutoplayNext() {
        if (Configs.autoplay(this)) advanceEpisode()
    }

    /** Advance to the next episode in the season (also called by the ⏭ button, ignoring the setting).
     *  At the end of a season, roll over to the first episode of the next season if there is one. */
    private fun advanceEpisode() {
        if (isLive || advancing) return
        if (epList.isEmpty() || epIndex < 0) return
        if (epIndex + 1 >= epList.size) { loadNextSeason(); return }
        advancing = true
        saveResume()
        playEpisodeItem(epList[epIndex + 1], epIndex + 1)
    }

    /** Load the current title (resolve URL on IO) into the player and update resume fields. */
    private fun playEpisodeItem(item: PlaylistItem, newIndex: Int) {
        epIndex = newIndex
        titleText = item.title
        b.title.text = item.title
        resumeId = item.resumeId
        resumeSource = item.source
        resumePoster = item.poster
        forceSoftware = false
        linkRetried = false
        Toast.makeText(this, "▶  Next: ${item.title}", Toast.LENGTH_SHORT).show()
        io.execute {
            val url = Downloads.resolveSource(item.source)
            runOnUiThread {
                advancing = false
                if (isFinishing) return@runOnUiThread
                if (url.isNullOrEmpty()) {
                    Toast.makeText(this, "Couldn't load the next episode.", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                videoUrl = url
                val p = player ?: return@runOnUiThread
                p.setMediaItem(MediaItem.fromUri(url))
                p.prepare()
                p.playWhenReady = true
            }
        }
    }

    /** End of season → find the next season in this series and start its first episode. Stops if none.
     *  Series/season context is derived from the current playlist (source = "ep|series|season|ep"). */
    private fun loadNextSeason() {
        if (epList.isEmpty() || epIndex < 0) return
        val cur = epList[epIndex]
        val parts = cur.source.split("|")            // ep | seriesId | seasonId | episodeId
        if (parts.size < 4 || parts[0] != "ep") return
        val seriesId = parts[1]
        val curSeasonId = parts[2]
        val seriesName = cur.title.split("/").getOrElse(0) { "" }.trim()
        val poster = cur.poster
        advancing = true
        io.execute {
            val seasons = Portal.seriesSeasons(seriesId).reversed() // reversed() = ascending (S1, S2…)
            val curIdx = seasons.indexOfFirst { it.id == curSeasonId }
            val nextSeason = if (curIdx >= 0) seasons.getOrNull(curIdx + 1) else null
            if (nextSeason == null) {
                runOnUiThread { advancing = false; Toast.makeText(this, "That was the last episode — no more episodes or seasons after this.", Toast.LENGTH_LONG).show() }
                return@execute
            }
            val ordered = Portal.seriesEpisodes(seriesId, nextSeason.id).reversed()
            if (ordered.isEmpty()) {
                runOnUiThread { advancing = false; Toast.makeText(this, "That was the last episode — no more episodes or seasons after this.", Toast.LENGTH_LONG).show() }
                return@execute
            }
            val newList = ordered.map { ep ->
                PlaylistItem(
                    "$seriesName  /  ${nextSeason.name}  /  ${ep.name}",
                    "ep_${seriesId}_${nextSeason.id}_${ep.id}", poster,
                    "ep|$seriesId|${nextSeason.id}|${ep.id}"
                )
            }
            runOnUiThread {
                if (isFinishing) { advancing = false; return@runOnUiThread }
                epList = newList
                Toast.makeText(this, "▶  Next season: ${nextSeason.name}", Toast.LENGTH_SHORT).show()
                playEpisodeItem(newList[0], 0)
            }
        }
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
                player?.setPlaybackSpeed(speeds[w])
                updateSpeedBtn()
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Wire the top-left quick controls: aspect ratio, volume (+ mute), brightness (+ night mode). */
    private fun wireQuickControls() {
        am = ScreenControls.audio(this)
        contentResolver.registerContentObserver(android.provider.Settings.System.CONTENT_URI, true, volObserver)
        b.aspectBtn.text = "⤢  ${aspectModes[aspectIdx]}"
        b.aspectBtn.setOnClickListener { cycleAspect() }

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
        b.pipBtn.setOnClickListener { enterPipFlow() }
        // Overlay PiP isn't supported on TV; volume/brightness stay (D-pad up/down drives the panel).
        if (Tv.isTv(this)) b.pipBtn.visibility = View.GONE
    }

    /** Shrink to the floating pop-up player (needs the "display over other apps" permission once). */
    private fun enterPipFlow() {
        if (!PipLauncher.hasPermission(this)) { PipLauncher.requestPermission(this); return }
        val pos = player?.currentPosition ?: 0L
        saveResume()
        player?.playWhenReady = false // hand audio over to the pop-up cleanly
        PipService.start(this, videoUrl, titleText, resumeSource, resumeId, resumePoster, pos, isLive)
        finish()
    }

    // Swipe down on the video → shrink to the pop-up (PiP), YouTube-style.
    private val pipGesture by lazy {
        android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 != null && vy > 0 && (e2.y - e1.y) > 180f && Math.abs(e2.y - e1.y) > Math.abs(e2.x - e1.x)) {
                    enterPipFlow(); return true
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        pipGesture.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    /** Open one panel (and close the other). While a panel is open, keep the controller up. */
    private fun openPanel(panel: View) {
        val show = panel.visibility != View.VISIBLE
        b.volumePanel.visibility = View.GONE
        b.brightnessPanel.visibility = View.GONE
        panel.visibility = if (show) View.VISIBLE else View.GONE
        b.playerView.controllerShowTimeoutMs = if (show) 0 else 6000
        b.playerView.showController()
        if (show) { val sb = if (panel === b.volumePanel) b.volSeek else b.brightSeek; sb.post { sb.requestFocus() } }
    }

    private fun cycleAspect() {
        aspectIdx = (aspectIdx + 1) % aspectModes.size
        b.playerView.resizeMode = when (aspectModes[aspectIdx]) {
            "Zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            "Stretch" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        b.aspectBtn.text = "⤢  ${aspectModes[aspectIdx]}"
    }

    /** App-level mute toggle: mutes only the player's own audio (device volume keys stay independent). */
    private fun toggleMute() {
        PlayPrefs.muted = !PlayPrefs.muted
        applyPlayPrefsAudio()   // push the new mute state to the player output
        updateMuteLabel()
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
        b.playerView.controllerShowTimeoutMs = 6000
        b.playerView.showController()
    }

    private fun toggleNight() {
        nightOn = !nightOn
        b.nightOverlay.visibility = if (nightOn) View.VISIBLE else View.GONE
        b.nightBtn.text = if (nightOn) "🌙  Night mode: ON" else "🌙  Night mode"
        PlayPrefs.night = nightOn
    }

    /** A finished movie: ask whether to drop it from Continue Watching (TV + tablet). */
    private fun promptRemoveFromContinue() {
        if (isLive) return
        val id = resumeId
        if (id.isBlank()) { finish(); return }
        player?.pause()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Finished watching")
            .setMessage("Remove “$titleText” from Continue Watching?")
            .setCancelable(false)
            .setPositiveButton("Yes, remove") { _, _ ->
                resumeId = "" // stop onStop/saveResume from re-adding it
                Resume.remove(applicationContext, id)
                finish()
            }
            .setNegativeButton("Keep") { _, _ ->
                // Keep it in Continue Watching: a finished (~end) position would be auto-dropped, so
                // re-save it (reset to start = a clean re-watch entry) and stop onStop overwriting it.
                resumeId = ""
                Resume.save(applicationContext, id, "vod", titleText, resumePoster, resumeSource, 0L, player?.duration ?: 0L, movieYear)
                finish()
            }
            .show()
    }

    private fun saveResume() {
        if (resumeId.isBlank() || isLive) return
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration
        if (pos > 0) Resume.save(applicationContext, resumeId, "vod", titleText, resumePoster, resumeSource, pos, if (dur > 0) dur else 0, movieYear)
    }

    override fun onStop() {
        super.onStop()
        saveResume()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { contentResolver.unregisterContentObserver(volObserver) } catch (_: Exception) {}
        resumeHandler.removeCallbacksAndMessages(null) // drop resumeSaver/endWatcher + any pending focus retries so nothing holds this activity past teardown
        saveResume()
        player?.release()
        player = null
        try { loudness?.release() } catch (_: Exception) {}
        loudness = null
    }
}
