package com.stalkertv.app

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.stalkertv.app.databinding.ActivityPlayerBinding

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var b: ActivityPlayerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)

        val url = intent.getStringExtra("url") ?: run { finish(); return }

        // Title overlay — shows/hides together with the playback controls (tap to reveal).
        b.title.text = intent.getStringExtra("title") ?: ""
        b.playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility -> b.title.visibility = visibility }
        )

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Portal.UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20000)
            .setReadTimeoutMs(20000)

        // Bigger buffer + quick resume after seek = smoother scrubbing.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20000, 60000, 1500, 3000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setLoadControl(loadControl)
            .build()
        p.setSeekParameters(SeekParameters.CLOSEST_SYNC) // seek to nearest keyframe = faster
        player = p
        b.playerView.player = p
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.playWhenReady = true
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
