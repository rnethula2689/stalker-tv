package com.stalkertv.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Processes all pending downloads as a foreground job. Scheduled with a CONNECTED network
 * constraint, so WorkManager runs it automatically when the network is available — including
 * resuming outage-paused downloads after the app has been closed.
 */
class DownloadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    companion object {
        private const val CHANNEL = "downloads"
        private const val NID = 1001
        private const val DONE_NID = 1002
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Preparing downloads…", -1)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        createChannel()
        try { setForeground(foregroundInfo("Downloading…", -1)) } catch (_: Exception) {}
        var anyDone = false
        var networkLost = false
        while (true) {
            val item = Downloads.nextPendingItem(applicationContext) ?: break
            try { setForeground(foregroundInfo(item.title, percent(item))) } catch (_: Exception) {}
            when (Downloads.runItem(applicationContext, item) { p -> notify(buildNotification(p.title, percent(p))) }) {
                Downloads.Outcome.DONE -> anyDone = true
                Downloads.Outcome.NETWORK -> { networkLost = true }
                else -> {}
            }
            if (networkLost) break
        }
        if (networkLost) Result.retry()
        else { if (anyDone) showDone(); Result.success() }
    }

    private fun percent(item: Downloads.Item): Int =
        if (item.total > 0) (item.done * 100 / item.total).toInt().coerceIn(0, 100) else -1

    private fun nm() = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun notify(n: Notification) { try { nm().notify(NID, n) } catch (_: Exception) {} }

    private fun piFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT

    private fun openDownloads(req: Int): PendingIntent =
        PendingIntent.getActivity(applicationContext, req, Intent(applicationContext, DownloadsActivity::class.java), piFlags())

    private fun buildNotification(title: String, pct: Int): Notification =
        NotificationCompat.Builder(applicationContext, CHANNEL)
            .setContentTitle("Downloading…")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, if (pct < 0) 0 else pct, pct < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openDownloads(0))
            .build()

    private fun foregroundInfo(title: String, pct: Int): ForegroundInfo {
        val n = buildNotification(title, pct)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ForegroundInfo(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NID, n)
    }

    private fun showDone() {
        try {
            val done = NotificationCompat.Builder(applicationContext, CHANNEL)
                .setContentTitle("Downloads finished")
                .setContentText("Your offline titles are ready to watch.")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true)
                .setContentIntent(openDownloads(1))
                .build()
            nm().notify(DONE_NID, done)
        } catch (_: Exception) {}
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            nm().createNotificationChannel(ch)
        }
    }
}
