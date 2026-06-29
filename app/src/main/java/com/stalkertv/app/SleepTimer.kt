package com.stalkertv.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.appcompat.app.AlertDialog
import java.lang.ref.WeakReference

/**
 * Sleep timer. From a player it closes the player after the chosen delay; from Settings ([closeApp]
 * = true) it closes the whole app (whatever's in the foreground when it fires). The menu/dialog shows
 * how much time is left.
 */
object SleepTimer {
    private val ui = Handler(Looper.getMainLooper())
    private var runnable: Runnable? = null
    private var target: WeakReference<Activity>? = null
    private var fireAtUptime = 0L // when the timer will fire (SystemClock.uptimeMillis), 0 = off
    private var closeWholeApp = false

    // Tracks the current foreground activity so a "close the whole app" timer can finish it even if
    // the user has navigated away from where the timer was set.
    private var foreground: WeakReference<Activity>? = null
    private var tracking = false
    private fun ensureTracking(a: Activity) {
        if (tracking) return
        tracking = true
        (a.applicationContext as? Application)?.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) { foreground = WeakReference(activity) }
                override fun onActivityCreated(activity: Activity, b: Bundle?) {}
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, b: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            }
        )
    }

    fun isArmed(): Boolean = runnable != null && fireAtUptime > SystemClock.uptimeMillis()

    fun remainingMin(): Int =
        if (!isArmed()) 0 else (((fireAtUptime - SystemClock.uptimeMillis()) + 59_999L) / 60_000L).toInt()

    /** Label for the player menu — shows time left when a timer is running. */
    fun menuLabel(): String =
        if (isArmed()) "⏲   Sleep timer · ${remainingMin()} min left" else "⏲   Sleep timer"

    fun showDialog(a: Activity, closeApp: Boolean = false) {
        ensureTracking(a)
        val labels = if (isArmed())
            arrayOf("Turn off  (${remainingMin()} min left)", "15 minutes", "30 minutes", "45 minutes", "60 minutes", "90 minutes")
        else
            arrayOf("Off", "15 minutes", "30 minutes", "45 minutes", "60 minutes", "90 minutes")
        val mins = intArrayOf(0, 15, 30, 45, 60, 90)
        AlertDialog.Builder(a)
            .setTitle(if (isArmed()) "Sleep timer — ${remainingMin()} min left" else "Sleep timer")
            .setItems(labels) { _, w -> arm(a, mins[w], closeApp) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun arm(a: Activity, minutes: Int, closeApp: Boolean) {
        runnable?.let { ui.removeCallbacks(it) }
        runnable = null
        fireAtUptime = 0L
        if (minutes <= 0) { toast(a, "Sleep timer off"); return }
        target = WeakReference(a)
        closeWholeApp = closeApp
        val r = Runnable {
            val act = if (closeWholeApp) (foreground?.get() ?: target?.get()) else target?.get()
            act?.let { if (!it.isFinishing) { if (closeWholeApp) it.finishAffinity() else it.finish() } }
            runnable = null
            fireAtUptime = 0L
        }
        runnable = r
        fireAtUptime = SystemClock.uptimeMillis() + minutes * 60_000L
        ui.postDelayed(r, minutes * 60_000L)
        toast(a, if (closeApp) "Sleep timer set — turning off in $minutes min" else "Sleep timer set — closing in $minutes min")
    }

    private fun toast(a: Activity, m: String) =
        android.widget.Toast.makeText(a, m, android.widget.Toast.LENGTH_SHORT).show()
}
