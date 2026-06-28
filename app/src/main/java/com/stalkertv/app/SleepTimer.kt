package com.stalkertv.app

import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import java.lang.ref.WeakReference

/** Simple sleep timer: after the chosen delay, close the player (stops playback). */
object SleepTimer {
    private val ui = Handler(Looper.getMainLooper())
    private var runnable: Runnable? = null
    private var target: WeakReference<Activity>? = null

    fun showDialog(a: Activity) {
        val labels = arrayOf("Off", "15 minutes", "30 minutes", "45 minutes", "60 minutes", "90 minutes")
        val mins = intArrayOf(0, 15, 30, 45, 60, 90)
        AlertDialog.Builder(a)
            .setTitle("Sleep timer")
            .setItems(labels) { _, w -> arm(a, mins[w]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun arm(a: Activity, minutes: Int) {
        runnable?.let { ui.removeCallbacks(it) }
        runnable = null
        if (minutes <= 0) { toast(a, "Sleep timer off"); return }
        target = WeakReference(a)
        val r = Runnable {
            target?.get()?.let { if (!it.isFinishing) it.finish() }
            runnable = null
        }
        runnable = r
        ui.postDelayed(r, minutes * 60_000L)
        toast(a, "Sleep timer set — closing in $minutes min")
    }

    private fun toast(a: Activity, m: String) =
        android.widget.Toast.makeText(a, m, android.widget.Toast.LENGTH_SHORT).show()
}
