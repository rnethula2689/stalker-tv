package com.stalkertv.app

import android.content.Context
import androidx.appcompat.app.AlertDialog

object About {
    fun show(ctx: Context) {
        val msg = "Stalker TV\n\n" +
            "Version:   ${BuildConfig.VERSION_NAME}  (build ${BuildConfig.VERSION_CODE})\n" +
            "Built:   ${BuildConfig.BUILD_TIME}\n\n" +
            "Developer:   RN\n" +
            "Updates:   is.gd/stalkertvfiretv"
        AlertDialog.Builder(ctx)
            .setTitle("About")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }
}
