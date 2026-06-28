package com.stalkertv.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** AlarmManager alarms are cleared on reboot — re-arm any pending programme reminders on boot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            try { Reminders.rescheduleAll(context) } catch (_: Exception) {}
        }
    }
}
