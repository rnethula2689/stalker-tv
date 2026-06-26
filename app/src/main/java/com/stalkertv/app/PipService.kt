package com.stalkertv.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.stalkertv.app.databinding.PipOverlayBinding
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory

/**
 * Floating "pop-up" (PiP-style) video player implemented as a system overlay window, so playback
 * continues while the user navigates the rest of the app — and other apps / the home screen.
 * (Fire OS has no real system PiP, hence the overlay approach.)
 *
 * Tap the pop-up to expand back to fullscreen; drag to move; ✕ to close.
 */
@OptIn(UnstableApi::class)
class PipService : Service() {

    companion object {
        var running = false
        var playing = false                       // true while the pop-up is actively playing
        var onStateChanged: (() -> Unit)? = null  // notifies e.g. the live grid to mute/unmute its preview
        private const val CH = "pip"
        private const val NOTIF = 42
        private const val E_URL = "url"; private const val E_TITLE = "title"
        private const val E_SOURCE = "source"; private const val E_RESUMEID = "resumeId"
        private const val E_POSTER = "poster"; private const val E_POS = "pos"; private const val E_LIVE = "live"

        fun start(ctx: Context, url: String, title: String, source: String, resumeId: String, poster: String, pos: Long, isLive: Boolean) {
            val i = Intent(ctx, PipService::class.java)
                .putExtra(E_URL, url).putExtra(E_TITLE, title).putExtra(E_SOURCE, source)
                .putExtra(E_RESUMEID, resumeId).putExtra(E_POSTER, poster).putExtra(E_POS, pos).putExtra(E_LIVE, isLive)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            if (running) ctx.stopService(Intent(ctx, PipService::class.java))
        }
    }

    private var wm: WindowManager? = null
    private var b: PipOverlayBinding? = null
    private lateinit var lp: WindowManager.LayoutParams
    private var player: ExoPlayer? = null

    private var url = ""; private var title = ""; private var source = ""
    private var resumeId = ""; private var poster = ""; private var startPos = 0L; private var isLive = false
    private var forceSoftware = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        url = intent.getStringExtra(E_URL) ?: ""
        title = intent.getStringExtra(E_TITLE) ?: ""
        source = intent.getStringExtra(E_SOURCE) ?: ""
        resumeId = intent.getStringExtra(E_RESUMEID) ?: ""
        poster = intent.getStringExtra(E_POSTER) ?: ""
        startPos = intent.getLongExtra(E_POS, 0L)
        isLive = intent.getBooleanExtra(E_LIVE, false)
        if (url.isEmpty()) { stopSelf(); return START_NOT_STICKY }

        startForegroundCompat()
        if (b == null) showOverlay()
        forceSoftware = false
        startPlayer()
        running = true
        return START_NOT_STICKY
    }

    /** On rotation the screen dimensions swap, so the saved x/y can fall off-screen (the pop-up
     *  "disappears"). Recompute the size for the new orientation and clamp it back on-screen. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val binding = b ?: return
        val wmgr = wm ?: return
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") wmgr.defaultDisplay.getRealMetrics(dm)
        val sw = dm.widthPixels; val sh = dm.heightPixels
        val w = (sw * 0.39f).toInt().coerceAtLeast(280); val h = w * 9 / 16
        lp.width = w; lp.height = h
        lp.x = lp.x.coerceIn(0, (sw - w).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (sh - h).coerceAtLeast(0))
        try { wmgr.updateViewLayout(binding.root, lp) } catch (_: Exception) {}
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CH) == null)
                nm.createNotificationChannel(NotificationChannel(CH, "Pop-up player", NotificationManager.IMPORTANCE_LOW))
        }
        val tap = PendingIntent.getActivity(
            this, 0, expandIntent(startPos),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val n = Notification.Builder(this).apply {
            if (Build.VERSION.SDK_INT >= 26) setChannelId(CH)
            setSmallIcon(android.R.drawable.ic_media_play)
            setContentTitle("Playing in pop-up")
            setContentText(title)
            setContentIntent(tap)
            setOngoing(true)
        }.build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIF, n)
    }

    private fun showOverlay() {
        val wmgr = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = wmgr
        val binding = PipOverlayBinding.inflate(LayoutInflater.from(this))
        b = binding
        val dm = resources.displayMetrics
        val w = (dm.widthPixels * 0.39f).toInt().coerceAtLeast(280)
        val h = w * 9 / 16
        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        lp = WindowManager.LayoutParams(
            w, h, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dm.widthPixels - w - 24
        lp.y = dm.heightPixels - h - 120
        wmgr.addView(binding.root, lp)

        binding.pipClose.setOnClickListener { closeAndSave() }
        binding.pipPlay.setOnClickListener {
            val p = player ?: return@setOnClickListener
            if (p.isPlaying) {
                p.pause(); binding.pipPlay.text = "▶"
            } else {
                if (isLive) p.seekToDefaultPosition() // catch up to the live edge on resume
                p.play(); binding.pipPlay.text = "⏸"
            }
        }
        binding.pipMute.setOnClickListener {
            val p = player ?: return@setOnClickListener
            if (p.volume > 0f) { p.volume = 0f; binding.pipMute.text = "🔇" } else { p.volume = 1f; binding.pipMute.text = "🔊" }
        }
        wireDrag(binding, wmgr, dm)
    }

    private fun wireDrag(binding: PipOverlayBinding, wmgr: WindowManager, dm: android.util.DisplayMetrics) {
        var downX = 0f; var downY = 0f; var startXp = 0; var startYp = 0; var moved = false
        binding.dragArea.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startXp = lp.x; startYp = lp.y; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX); val dy = (e.rawY - downY)
                    if (kotlin.math.abs(dx) > 12 || kotlin.math.abs(dy) > 12) moved = true
                    lp.x = (startXp + dx).toInt().coerceIn(0, dm.widthPixels - lp.width)
                    lp.y = (startYp + dy).toInt().coerceIn(0, dm.heightPixels - lp.height)
                    try { wmgr.updateViewLayout(binding.root, lp) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) expand(); true }
                else -> false
            }
        }
    }

    private fun startPlayer() {
        player?.release()
        val p = buildPlayer()
        player = p
        b?.pipPlayer?.player = p
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        if (!isLive && startPos > 0) p.seekTo(startPos)
        p.playWhenReady = true
    }

    private fun buildPlayer(): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Portal.UA).setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20000).setReadTimeoutMs(20000)
        val ds = DefaultDataSource.Factory(this, http)
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20000, 60000, 1500, 3000).setPrioritizeTimeOverSizeThresholds(true).build()
        val mode = if (forceSoftware) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
        else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        val renderers = NextRenderersFactory(this).setExtensionRendererMode(mode).setEnableDecoderFallback(true)
        val p = ExoPlayer.Builder(this, renderers).setMediaSourceFactory(DefaultMediaSourceFactory(ds)).setLoadControl(load).build()
        // Respect audio focus: pause the pop-up when another app (YouTube, a call, etc.) starts audio.
        p.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus = */ true
        )
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (!forceSoftware) { forceSoftware = true; rebuildSoftware() }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                b?.pipPlay?.text = if (isPlaying) "⏸" else "▶"
                playing = isPlaying
                onStateChanged?.invoke()
            }
        })
        return p
    }

    private fun rebuildSoftware() {
        val old = player ?: return
        val pos = old.currentPosition
        old.release()
        val np = buildPlayer()
        player = np
        b?.pipPlayer?.player = np
        np.setMediaItem(MediaItem.fromUri(url))
        np.prepare()
        if (!isLive && pos > 0) np.seekTo(pos)
        np.playWhenReady = true
    }

    private fun currentPos(): Long = player?.currentPosition ?: startPos

    private fun expandIntent(pos: Long): Intent =
        Intent(this, PlayerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("url", url).putExtra("title", title)
            .putExtra("resumeId", resumeId).putExtra("resumeSource", source)
            .putExtra("resumePoster", poster).putExtra("resumeStart", pos)
            .putExtra("live", isLive).putExtra("noPlaylist", true)

    /** Tap the pop-up → reopen fullscreen at the live position. */
    private fun expand() {
        startActivity(expandIntent(currentPos()))
        stopSelf() // PlayerActivity also calls PipService.stop(); removing the overlay here is immediate
    }

    private fun closeAndSave() {
        saveResume()
        stopSelf()
    }

    private fun saveResume() {
        val p = player ?: return
        if (isLive || resumeId.isBlank()) return
        val pos = p.currentPosition; val dur = p.duration
        if (pos > 0) Resume.save(applicationContext, resumeId, "vod", title, poster, source, pos, if (dur > 0) dur else 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        playing = false
        onStateChanged?.invoke() // let the live grid restore its preview audio
        saveResume()
        try { b?.let { wm?.removeView(it.root) } } catch (_: Exception) {}
        b = null
        player?.release()
        player = null
    }
}
