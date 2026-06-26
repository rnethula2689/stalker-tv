package com.stalkertv.app

import android.content.Context
import android.media.AudioManager
import android.view.Window

/**
 * Small helpers for the full-screen players' volume / brightness controls. Volume drives the
 * device media stream; brightness is per-window (no WRITE_SETTINGS permission needed).
 */
object ScreenControls {
    fun audio(ctx: Context): AudioManager =
        ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun maxVolume(am: AudioManager): Int = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    fun volume(am: AudioManager): Int = am.getStreamVolume(AudioManager.STREAM_MUSIC)
    fun setVolume(am: AudioManager, v: Int) {
        am.setStreamVolume(AudioManager.STREAM_MUSIC, v.coerceIn(0, maxVolume(am)), 0)
    }

    /** [frac] 0f..1f. We clamp the low end to a hair above 0 so the screen never goes fully black. */
    fun setBrightness(window: Window, frac: Float) {
        val lp = window.attributes
        lp.screenBrightness = frac.coerceIn(0.02f, 1f)
        window.attributes = lp
    }

    /** Current window brightness as 0f..1f, falling back to ~0.6 when it's still on system default (-1). */
    fun brightness(window: Window): Float {
        val b = window.attributes.screenBrightness
        return if (b < 0f) 0.6f else b.coerceIn(0.02f, 1f)
    }
}
