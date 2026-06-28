package com.stalkertv.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * Programme reminders: the user taps "⏰ Remind" on a future programme in the TV Guide; we store it
 * and schedule an exact AlarmManager alarm that fires ~1 min before start. [ReminderReceiver] then
 * posts a notification. Reminders are re-scheduled on boot ([BootReceiver]). All persisted as JSON so
 * the list survives process death.
 */
object Reminders {
    private const val PREF = "reminders"
    private const val CHANNEL = "reminders"
    const val LEAD_MS = 60_000L // fire one minute before the programme starts

    data class R(val chId: String, val chName: String, val cmd: String, val progName: String, val startTs: Long) {
        fun key() = "$chId|$startTs"
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun all(ctx: Context): MutableList<R> {
        val out = ArrayList<R>()
        try {
            val arr = JSONArray(prefs(ctx).getString("list", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(R(o.optString("chId"), o.optString("chName"), o.optString("cmd"),
                    o.optString("progName"), o.optLong("startTs")))
            }
        } catch (_: Exception) {}
        return out
    }

    private fun save(ctx: Context, list: List<R>) {
        val arr = JSONArray()
        for (r in list) arr.put(JSONObject()
            .put("chId", r.chId).put("chName", r.chName).put("cmd", r.cmd)
            .put("progName", r.progName).put("startTs", r.startTs))
        prefs(ctx).edit().putString("list", arr.toString()).apply()
    }

    fun isSet(ctx: Context, chId: String, startTs: Long): Boolean =
        all(ctx).any { it.chId == chId && it.startTs == startTs }

    /** @return true if a reminder was added, false if it was removed (toggle). */
    fun toggle(ctx: Context, r: R): Boolean {
        val list = all(ctx)
        val existing = list.firstOrNull { it.key() == r.key() }
        return if (existing != null) {
            list.remove(existing); save(ctx, list); cancelAlarm(ctx, r); false
        } else {
            list.add(r); save(ctx, list); scheduleAlarm(ctx, r); true
        }
    }

    /** Drop a reminder after it has fired (or been handled), without touching its alarm. */
    fun forget(ctx: Context, key: String) {
        val list = all(ctx)
        if (list.removeAll { it.key() == key }) save(ctx, list)
    }

    /** Re-arm every stored reminder (called on boot). Drops ones already in the past. */
    fun rescheduleAll(ctx: Context) {
        val now = System.currentTimeMillis() / 1000
        val list = all(ctx).filter { it.startTs > now }
        save(ctx, list)
        for (r in list) scheduleAlarm(ctx, r)
    }

    private fun pendingIntent(ctx: Context, r: R): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).apply {
            putExtra("chId", r.chId); putExtra("chName", r.chName)
            putExtra("cmd", r.cmd); putExtra("progName", r.progName); putExtra("startTs", r.startTs)
        }
        val flags = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0) or
            PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(ctx, r.key().hashCode(), i, flags)
    }

    private fun scheduleAlarm(ctx: Context, r: R) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val fireAt = (r.startTs * 1000) - LEAD_MS
        val whenMs = if (fireAt < System.currentTimeMillis() + 3000) System.currentTimeMillis() + 3000 else fireAt
        val pi = pendingIntent(ctx, r)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi)
            else am.setExact(AlarmManager.RTC_WAKEUP, whenMs, pi)
        } catch (_: SecurityException) {
            // Exact alarms not permitted (API 31+ without permission) → inexact fallback.
            am.set(AlarmManager.RTC_WAKEUP, whenMs, pi)
        }
    }

    private fun cancelAlarm(ctx: Context, r: R) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(pendingIntent(ctx, r))
    }

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) == null) {
                val ch = NotificationChannel(CHANNEL, "Programme reminders", NotificationManager.IMPORTANCE_HIGH)
                ch.description = "Alerts when a programme you set a reminder for is about to start"
                nm.createNotificationChannel(ch)
            }
        }
    }

    fun channelId() = CHANNEL
}
