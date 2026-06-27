package com.stalkertv.app

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration

/** Detects a TV/leanback device (Fire TV / Fire Stick) vs a touchscreen tablet, so touch-only
 *  controls (PiP overlay, volume/brightness sliders, multi-select checkboxes) can be adapted. */
object Tv {
    fun isTv(ctx: Context): Boolean {
        val pm = ctx.packageManager
        if (pm.hasSystemFeature("android.software.leanback")) return true
        if (pm.hasSystemFeature("android.hardware.type.television")) return true
        val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }
}
