package com.stalkertv.app

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/** Fires when a programme reminder is due → posts a heads-up notification, then forgets the reminder. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val chName = intent.getStringExtra("chName") ?: return
        val progName = intent.getStringExtra("progName") ?: ""
        val chId = intent.getStringExtra("chId") ?: ""
        val startTs = intent.getLongExtra("startTs", 0)

        Reminders.ensureChannel(context)

        val open = Intent(context, ChannelsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val piFlags = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0) or
            PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getActivity(context, "$chId|$startTs".hashCode(), open, piFlags)

        val n = NotificationCompat.Builder(context, Reminders.channelId())
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Starting soon on $chName")
            .setContentText(if (progName.isNotBlank()) progName else "Your reminder is due")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify("$chId|$startTs".hashCode(), n)
        } catch (_: Exception) {}

        Reminders.forget(context, "$chId|$startTs")
    }
}
