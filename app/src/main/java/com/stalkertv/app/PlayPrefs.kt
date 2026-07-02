package com.stalkertv.app

/**
 * Playback preferences kept for the whole app session (in memory), applied by EVERY player —
 * live TV, movies (ExoPlayer + VLC), recordings, downloads, PiP. So if the user mutes, sets a
 * volume/brightness level, or turns on night mode anywhere, it sticks everywhere until they
 * change it or the app is killed.
 *
 *  - volPct: last chosen (non-zero) volume level, 0..100. -1 = unset → player default.
 *  - muted:  whether audio is muted.
 *  - brightPct: brightness level 0..100. -1 = unset → leave the player/system default.
 *  - night:  night-mode dimming overlay on/off.
 */
object PlayPrefs {
    var volPct = -1
    var muted = false
    var brightPct = -1
    var night = false

    /** Record a volume change: a real level clears mute + remembers the level; 0 means muted. */
    fun noteVolume(pct: Int) {
        if (pct > 0) { volPct = pct.coerceIn(0, 100); muted = false } else muted = true
    }
}
