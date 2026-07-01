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
    }
    private fun brightGetPct() = if (onTv) ((1f - tvDim) * 100).toInt() else (ScreenControls.brightness(window) * 100).toInt()
    private fun brightSetPct(pct: Int) {
        val f = pct.coerceIn(0, 100) / 100f
        if (onTv) { tvDim = (1f - f).coerceIn(0f, 0.92f); b.dimOverlay.alpha = tvDim }
        else ScreenControls.setBrightness(window, f)
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                        else b.playerView.post {
                            b.playerView.findViewById<View>(androidx.media3.ui.R.id.exo_progress)?.requestFocus()
                        }
                    }
                } else {
                    b.volumePanel.visibility = View.GONE
                    b.brightnessPanel.visibility = View.GONE
                }
            }
        )
        b.subBtn.setOnClickListener { searchSubtitles() }
        b.menuBtn.setOnClickListener { showMenu() }
        wireQuickControls()

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
        // Start buffering DIRECTLY at the resume position — prepare()+seekTo() double-buffers
        // (loads at 0, discards it, re-buffers at the resume point) which stutters on open.
        if (resumeStart > 0 && !isLive) p.setMediaItem(MediaItem.fromUri(videoUrl), resumeStart)
        else p.setMediaItem(MediaItem.fromUri(videoUrl))
        p.prepare()
        p.playWhenReady = true

        if (resumeId.isNotBlank() && !isLive) resumeHandler.postDelayed(resumeSaver, 10_000)

        b.playerView.controllerShowTimeoutMs = 6000
        b.playerView.requestFocus()
        goImmersive()
        b.playerView.post { hideDefaultGear() }
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
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBuf, maxBuf, 2500, 5000)
            .setPrioritizeTimeOverSizeThresholds(true)
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
        val autoLabel = if (Configs.autoplay(this)) "🔁   Autoplay next: ON" else "🔁   Autoplay next: OFF"
        val boostLabel = "🔊   Audio boost: ${Configs.audioBoostLabel(this)}"
        val items = arrayOf(SleepTimer.menuLabel(), "🎚   Playback settings", boostLabel, "⚠   Report not working", "📡   Cast to TV", "💬   Subtitles", autoLabel, "⚙   Settings", "📥   App updates", "ℹ️   About", "✖   Exit")
        val dlg = AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> SleepTimer.showDialog(this)
                    1 -> PlaybackSettings.show(this)
                    2 -> cycleAudioBoost()
                    3 -> {
                        Reports.add(this, titleText, resumeSource.ifBlank { "vod" })
                        Toast.makeText(this, "Reported — logged in Settings ▸ Diagnostics.", Toast.LENGTH_SHORT).show()
                    }
                    4 -> if (videoUrl.isNotEmpty()) CastHelper.show(this, videoUrl, titleText, isLive)
                    5 -> searchSubtitles()
                    6 -> {
                        Configs.setAutoplay(this, !Configs.autoplay(this))
                        Toast.makeText(this, if (Configs.autoplay(this)) "Autoplay next: ON" else "Autoplay next: OFF", Toast.LENGTH_SHORT).show()
                    }
                    7 -> startActivity(android.content.Intent(this, SettingsActivity::class.java))
                    8 -> startActivity(android.content.Intent(this, AppUpdatesActivity::class.java))
                    9 -> About.show(this)
                    10 -> finishAffinity()
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

    /** Menu key opens the overlay menu; any other key (except Back/volume) re-shows the controls. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val kc = event.keyCode
        if (kc == android.view.KeyEvent.KEYCODE_MENU) {
            if (event.action == android.view.KeyEvent.ACTION_UP) showMenu()
            return true
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

    /** Clean the display title into a search query (drop language/category/quality tags). */
    private fun searchQuery(): String =
        titleText.substringBefore(" / ").substringBefore(" - ").substringBefore(" (").trim()

    private fun searchSubtitles() {
        val q = searchQuery()
        if (q.isEmpty()) return
        Toast.makeText(this, "Searching English subtitles for “$q”…", Toast.LENGTH_SHORT).show()
        io.execute {
            val results = Subtitles.search(q)
            runOnUiThread {
                if (results.isEmpty()) {
                    Toast.makeText(this, "No subtitles found for “$q”.", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val names = results.map { it.name }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("English subtitles (${results.size})")
                    .setItems(names) { _, which -> applySubtitle(results[which]) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
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
                val pl = player ?: return@runOnUiThread
                val pos = pl.currentPosition
                val subConfig = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
                    .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                    .setLanguage("en")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
                val item = MediaItem.Builder()
                    .setUri(videoUrl)
                    .setSubtitleConfigurations(listOf(subConfig))
                    .build()
                pl.setMediaItem(item, pos)
                pl.prepare()
                pl.playWhenReady = true
                pl.trackSelectionParameters = pl.trackSelectionParameters.buildUpon()
                    .setPreferredTextLanguage("en").build()
                Toast.makeText(this, "Subtitle applied ✓", Toast.LENGTH_SHORT).show()
            }
        }
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
        b.aspectBtn.text = "⤢  ${aspectModes[aspectIdx]}"
        b.aspectBtn.setOnClickListener { cycleAspect() }

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

    private fun toggleMute() {
        if (volGet() > 0) { preMuteVol = volGet(); volSet(0) }
        else volSet(if (preMuteVol > 0) preMuteVol else volMax() / 2)
        refreshVol()
    }

    private fun refreshVol() {
        b.volSeek.progress = volGet()
        updateMuteLabel()
        b.volLabel.text = "🔊  Volume  ${if (volMax() > 0) volGet() * 100 / volMax() else 0}%"
    }

    private fun updateBrightLabel() { b.brightLabel.text = "☀  Brightness  ${brightGetPct()}%" }

    private fun updateMuteLabel() {
        val muted = volGet() == 0
        b.muteBtn.text = if (muted) "🔈  Unmute" else "🔇  Mute"
        b.volBtn.text = if (muted) "🔇" else "🔊"   // the cluster button shows the mute state on TV
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
                Resume.save(applicationContext, id, "vod", titleText, resumePoster, resumeSource, 0L, player?.duration ?: 0L)
                finish()
            }
            .show()
    }

    private fun saveResume() {
        if (resumeId.isBlank() || isLive) return
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration
        if (pos > 0) Resume.save(applicationContext, resumeId, "vod", titleText, resumePoster, resumeSource, pos, if (dur > 0) dur else 0)
    }

    override fun onStop() {
        super.onStop()
        saveResume()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        resumeHandler.removeCallbacks(resumeSaver)
        resumeHandler.removeCallbacks(endWatcher)
        saveResume()
        player?.release()
        player = null
        try { loudness?.release() } catch (_: Exception) {}
        loudness = null
    }
}
