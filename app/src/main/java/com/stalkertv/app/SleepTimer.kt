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

    fun remainingMs(): Long = if (!isArmed()) 0L else (fireAtUptime - SystemClock.uptimeMillis()).coerceAtLeast(0L)

    /** Live m:ss for the running-clock popup / top-bar chip. */
    fun remainingClock(): String {
        val s = (remainingMs() / 1000L).toInt()
        return String.format("%d:%02d", s / 60, s % 60)
    }

    /** Label for the player menu — shows time left when a timer is running. */
    fun menuLabel(): String =
        if (isArmed()) "⏲   Sleep timer · ${remainingMin()} min left" else "⏲   Sleep timer"

    /**
     * Tapped from the global top-bar chip: a small popup with a LIVE m:ss countdown and quick options
     * to change/extend or turn the timer off (like a volume/brightness pop-up, but for the timer).
     */
    fun showCountdown(a: Activity) {
        if (!isArmed()) { showDialog(a, closeWholeApp); return }
        val keepCloseApp = closeWholeApp
        val msg = android.widget.TextView(a).apply {
            textSize = 30f; setTextColor(0xFFE6EDF3.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(48, 48, 48, 24)
        }
        val dlg = AlertDialog.Builder(a)
            .setTitle("⏲  Sleep timer")
            .setView(msg)
            .setPositiveButton("Change / extend") { _, _ -> showDialog(a, keepCloseApp) }
            .setNegativeButton("Turn off") { _, _ -> arm(a, 0, keepCloseApp) }
            .setNeutralButton("Close", null)
            .create()
        val tick = object : Runnable {
            override fun run() {
                if (!isArmed()) { msg.text = "Off"; return }
                msg.text = "${remainingClock()}  left"
                ui.postDelayed(this, 1000)
            }
        }
        dlg.setOnShowListener { tick.run() }
        dlg.setOnDismissListener { ui.removeCallbacks(tick) }
        dlg.show()
    }

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
