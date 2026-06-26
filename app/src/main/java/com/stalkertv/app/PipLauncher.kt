package com.stalkertv.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast

/** Shared helper for entering the pop-up (PiP) player: the overlay-permission gate + clear wording. */
object PipLauncher {
    fun hasPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

    /** Send the user to grant "display over other apps" with a clear, app-named instruction. */
    fun requestPermission(act: Activity) {
        val name = act.getString(R.string.app_name)
        Toast.makeText(
            act,
            "To use the pop-up player: find “$name” in the next list and switch it ON, then come back and tap ⧉.",
            Toast.LENGTH_LONG
        ).show()
        try {
            act.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${act.packageName}"))
            )
        } catch (_: Exception) {
            try { act.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) } catch (_: Exception) {}
        }
    }
}
