package com.stalkertv.app

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.appcompat.app.AlertDialog
import java.lang.ref.WeakReference

/** Simple sleep timer: after the chosen delay, close the player (stops playback). */
object SleepTimer {
    private val ui = Handler(Looper.getMainLooper())
    private var runnable: Runnable? = null
    private var target: WeakReference<Activity>? = null
    private var fireAtUptime = 0L // when the timer will fire (SystemClock.uptimeMillis), 0 = off

    fun isArmed(): Boolean = runnable != null && fireAtUptime > SystemClock.uptimeMillis()

    fun remainingMin(): Int =
        if (!isArmed()) 0 else (((fireAtUptime - SystemClock.uptimeMillis()) + 59_999L) / 60_000L).toInt()

    /** Label for the player menu — shows time left when a timer is running. */
    fun menuLabel(): String =
        if (isArmed()) "⏲   Sleep timer · ${remainingMin()} min left" else "⏲   Sleep timer"

    fun showDialog(a: Activity) {
        val labels = if (isArmed())
            arrayOf("Turn off  (${remainingMin()} min left)", "15 minutes", "30 minutes", "45 minutes", "60 minutes", "90 minutes")
        else
            arrayOf("Off", "15 minutes", "30 minutes", "45 minutes", "60 minutes", "90 minutes")
        val mins = intArrayOf(0, 15, 30, 45, 60, 90)
        AlertDialog.Builder(a)
            .setTitle(if (isArmed()) "Sleep timer — ${remainingMin()} min left" else "Sleep timer")
            .setItems(labels) { _, w -> arm(a, mins[w]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun arm(a: Activity, minutes: Int) {
        runnable?.let { ui.removeCallbacks(it) }
        runnable = null
        fireAtUptime = 0L
        if (minutes <= 0) { toast(a, "Sleep timer off"); return }
        target = WeakReference(a)
        val r = Runnable {
            target?.get()?.let { if (!it.isFinishing) it.finish() }
            runnable = null
            fireAtUptime = 0L
        }
        runnable = r
        fireAtUptime = SystemClock.uptimeMillis() + minutes * 60_000L
        ui.postDelayed(r, minutes * 60_000L)
        toast(a, "Sleep timer set — closing in $minutes min")
    }

    private fun toast(a: Activity, m: String) =
        android.widget.Toast.makeText(a, m, android.widget.Toast.LENGTH_SHORT).show()
}
