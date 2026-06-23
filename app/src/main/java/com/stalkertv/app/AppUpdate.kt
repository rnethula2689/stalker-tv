package com.stalkertv.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import java.io.File

/** Downloads the latest APK and launches the system installer (in-app self-update). */
object AppUpdate {
    fun install(act: Activity) {
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(act)
                .setTitle("Allow updates")
                .setMessage("To install updates in-app, allow this app to install apps. You'll be taken to the setting — turn it on, then press Download again.")
                .setPositiveButton("Open setting") { _, _ ->
                    act.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}"))
                    )
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        Toast.makeText(act, "Downloading update…", Toast.LENGTH_LONG).show()
        Thread {
            val apk = File(act.cacheDir, "update.apk")
            val ok = Updater.downloadApk(apk)
            act.runOnUiThread {
                if (act.isFinishing) return@runOnUiThread
                if (!ok) {
                    Toast.makeText(act, "Download failed. Please try again.", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val uri = FileProvider.getUriForFile(act, "${act.packageName}.fileprovider", apk)
                val i = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                act.startActivity(i)
            }
        }.start()
    }
}
