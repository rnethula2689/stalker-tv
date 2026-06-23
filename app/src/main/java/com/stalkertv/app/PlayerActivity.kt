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
import java.io.File
import java.util.concurrent.Executors

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private var player: ExoPlayer? = null
    private lateinit var b: ActivityPlayerBinding
    private var videoUrl: String = ""
    private var titleText: String = ""

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

        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setLoadControl(loadControl)
            .build()
        p.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        player = p
        b.playerView.player = p
        p.setMediaItem(MediaItem.fromUri(videoUrl))
        p.prepare()
        p.playWhenReady = true

        b.playerView.controllerShowTimeoutMs = 6000
        b.playerView.requestFocus()
    }

    private var menuDialog: AlertDialog? = null
    private fun showMenu() {
        if (menuDialog?.isShowing == true) { menuDialog?.dismiss(); return }
        val items = arrayOf("💬   Subtitles", "⚙   Settings", "📥   App updates", "ℹ️   About", "✖   Exit")
        val dlg = AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> searchSubtitles()
                    1 -> startActivity(android.content.Intent(this, SettingsActivity::class.java))
                    2 -> startActivity(android.content.Intent(this, AppUpdatesActivity::class.java))
                    3 -> About.show(this)
                    4 -> finishAffinity()
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

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
