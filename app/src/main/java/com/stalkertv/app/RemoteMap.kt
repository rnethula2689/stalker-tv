package com.stalkertv.app

import android.content.Context
import android.view.KeyEvent

/**
 * User-defined remote key → app-action mapping (like STB Emulator's key settings). Lets a viewer on
 * any TV/remote assign their physical buttons to player actions. Empty by default, so stock remotes
 * behave exactly as before until the user customises. Back/Home are never remappable (so a user can
 * always exit). Applied in the live player's dispatchKeyEvent.
 */
object RemoteMap {
    /** Ordered action id → human label. */
    val ACTIONS = linkedMapOf(
        "channel_up" to "Channel up",
        "channel_down" to "Channel down",
        "play_pause" to "Play / Pause",
        "rewind" to "Rewind",
        "forward" to "Fast-forward",
        "aspect" to "Change aspect ratio",
        "menu" to "Open menu (options)",
        "info" to "Show info / controls"
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("remotemap", Context.MODE_PRIVATE)

    fun keyFor(ctx: Context, action: String): Int = prefs(ctx).getInt(action, 0)
    fun setKey(ctx: Context, action: String, keyCode: Int) { prefs(ctx).edit().putInt(action, keyCode).apply() }
    fun clear(ctx: Context, action: String) { prefs(ctx).edit().remove(action).apply() }
    fun clearAll(ctx: Context) { prefs(ctx).edit().clear().apply() }

    /** Which action (if any) the user mapped this key to. Back/Home are never hijacked. */
    fun actionFor(ctx: Context, keyCode: Int): String? {
        if (keyCode == 0 || keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_HOME) return null
        val p = prefs(ctx)
        for (a in ACTIONS.keys) if (p.getInt(a, 0) == keyCode) return a
        return null
    }

    fun keyName(keyCode: Int): String =
        if (keyCode == 0) "Not set" else KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
}
