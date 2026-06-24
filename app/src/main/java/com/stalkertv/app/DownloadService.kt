package com.stalkertv.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service that keeps the process alive (with a progress notification) while downloads
 * run, so they continue even after the app is closed. The actual work runs on [Downloads]'s
 * executor; this service just holds the foreground notification and stops when nothing is active.
 */
class DownloadService : Service(), Downloads.Listener {
    companion object {
        private const val CHANNEL = "downloads"
        private const val NID = 1001
        private const val DONE_NID = 1002

        fun start(ctx: Context) {
            val i = Intent(ctx, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }

    private fun nm() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NID, build())
        Downloads.addListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NID, build())
        if (Downloads.activeCount() == 0) stopNow()
        return START_NOT_STICKY
    }

    override fun onDownloadsChanged() {
        if (Downloads.activeCount() == 0) { finishWithSummary(); return }
        try { nm().notify(NID, build()) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        Downloads.removeListener(this)
    }

    private fun piFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT

    private fun openDownloads(req: Int): PendingIntent =
        PendingIntent.getActivity(this, req, Intent(this, DownloadsActivity::class.java), piFlags())

    private fun build(): Notification {
        val info = Downloads.notifInfo()
        val text = if (info.count > 1) "${info.title}   (+${info.count - 1} more)" else info.title
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Downloading…")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, info.percent, info.percent <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openDownloads(0))
            .build()
    }

    private fun finishWithSummary() {
        @Suppress("DEPRECATION") stopForeground(true)
        try {
            val done = NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle("Downloads finished")
                .setContentText("Your offline titles are ready to watch.")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true)
                .setContentIntent(openDownloads(1))
                .build()
            nm().notify(DONE_NID, done)
        } catch (_: Exception) {}
        stopSelf()
    }

    private fun stopNow() {
        @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            nm().createNotificationChannel(ch)
        }
    }
}
