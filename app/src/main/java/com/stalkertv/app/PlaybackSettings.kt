package com.stalkertv.app

import android.app.Activity
import androidx.appcompat.app.AlertDialog

/**
 * Playback settings shown from the player menu: stream buffering level and hardware decoding.
 * Both are global prefs (Configs) consumed by the live (libVLC) and VOD (ExoPlayer) players the
 * next time a stream starts. Self-contained dialog — no layout, identical in both apps.
 */
object PlaybackSettings {
    fun show(a: Activity) {
        val items = arrayOf(
            "🎚   Buffering — ${Configs.bufferLabel(a)}",
            "🧩   Hardware decoding — ${if (Configs.hwDecode(a)) "On" else "Off"}"
        )
        AlertDialog.Builder(a)
            .setTitle("Playback settings")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> chooseBuffering(a)
                    1 -> {
                        val on = !Configs.hwDecode(a)
                        Configs.setHwDecode(a, on)
                        toast(a, "Hardware decoding ${if (on) "on" else "off"} — restart playback to apply")
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun chooseBuffering(a: Activity) {
        val labels = arrayOf("Low (fast zap)", "Normal", "High (smoothest)")
        AlertDialog.Builder(a)
            .setTitle("Buffering")
            .setSingleChoiceItems(labels, Configs.bufferMode(a)) { d, w ->
                Configs.setBufferMode(a, w)
                d.dismiss()
                toast(a, "Buffering: ${labels[w]} — restart playback to apply")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(a: Activity, m: String) =
        android.widget.Toast.makeText(a, m, android.widget.Toast.LENGTH_SHORT).show()
}
