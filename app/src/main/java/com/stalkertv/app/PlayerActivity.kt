package com.stalkertv.app

import android.net.Uri
import android.os.Bundle
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

    companion object {
        var liveChannels: List<Portal.Channel> = emptyList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)

        videoUrl = intent.getStringExtra("url") ?: run { finish(); return }
        titleText = intent.getStringExtra("title") ?: ""
        val savedKey = Configs.ossKey(this)
        if (savedKey.isNotBlank()) Subtitles.apiKey = savedKey

        // Top bar (title + Subtitles) shows/hides with the playback controls.
        b.title.text = titleText
        b.playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility -> b.topBar.visibility = visibility }
        )
        b.subBtn.setOnClickListener { searchSubtitles() }
        b.menuBtn.setOnClickListener { showMenu() }

        isLive = intent.getBooleanExtra("live", false)
        chIndex = intent.getIntExtra("chIndex", -1)
        resumeId = intent.getStringExtra("resumeId") ?: ""
        resumeSource = intent.getStringExtra("resumeSource") ?: ""
        resumePoster = intent.getStringExtra("resumePoster") ?: ""
        val resumeStart = intent.getLongExtra("resumeStart", 0L)
        if (isLive) {
            // Live TV can't seek back/forward — drop those controls; Up/Down change channels.
            b.playerView.setShowPreviousButton(false)
            b.playerView.setShowNextButton(false)
            b.playerView.setShowFastForwardButton(false)
            b.playerView.setShowRewindButton(false)
        } else if (intent.getBooleanExtra("noPlaylist", false)) {
            // Single item (e.g. catch-up archive): keep the seek bar + skip, hide episode prev/next.
            b.playerView.setShowPreviousButton(false)
            b.playerView.setShowNextButton(false)
        }

        val p = buildPlayer()
        player = p
        b.playerView.player = p
        p.setMediaItem(MediaItem.fromUri(videoUrl))
        p.prepare()
        if (resumeStart > 0 && !isLive) p.seekTo(resumeStart)
        p.playWhenReady = true

        if (resumeId.isNotBlank() && !isLive) resumeHandler.postDelayed(resumeSaver, 10_000)

        b.playerView.controllerShowTimeoutMs = 6000
        b.playerView.requestFocus()
    }

    private fun buildPlayer(): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Portal.UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20000)
            .setReadTimeoutMs(20000)
        // Wrap so the factory can open both the http stream AND the local subtitle file://.
        val dataSource = androidx.media3.datasource.DefaultDataSource.Factory(this, http)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20000, 60000, 1500, 3000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val mode = if (forceSoftware)
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
        p.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        p.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // First failure on a hardware decoder → retry once with FFmpeg software decoders.
                if (!forceSoftware) {
                    forceSoftware = true
                    rebuildSoftware()
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Keep the screen awake while actually playing; let it sleep when paused.
                if (isPlaying) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        })
        return p
    }

    /** Rebuild the player (now in software-decode mode) and resume the current stream. */
    private fun rebuildSoftware() {
        val old = player ?: return
        val pos = old.currentPosition
        old.release()
        val np = buildPlayer()
        player = np
        b.playerView.player = np
        np.setMediaItem(MediaItem.fromUri(videoUrl))
        np.prepare()
        if (!isLive && pos > 0) np.seekTo(pos)
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
        val items = arrayOf("📡   Cast to TV", "💬   Subtitles", "⚙   Settings", "📥   App updates", "ℹ️   About", "✖   Exit")
        val dlg = AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> if (videoUrl.isNotEmpty()) CastHelper.show(this, videoUrl, titleText, isLive)
                    1 -> searchSubtitles()
                    2 -> startActivity(android.content.Intent(this, SettingsActivity::class.java))
                    3 -> startActivity(android.content.Intent(this, AppUpdatesActivity::class.java))
                    4 -> About.show(this)
                    5 -> finishAffinity()
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
        saveResume()
        player?.release()
        player = null
    }
}
