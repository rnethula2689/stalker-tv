package com.stalkertv.app

import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.stalkertv.app.databinding.ActivityAppupdatesBinding

class AppUpdatesActivity : AppCompatActivity() {
    private lateinit var b: ActivityAppupdatesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAppupdatesBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.verInfo.text = "Installed:  ${BuildConfig.VERSION_NAME}  (build ${BuildConfig.VERSION_CODE})"

        // Show the bundled info page; route its download link to the in-app installer.
        b.web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, req: WebResourceRequest?): Boolean {
                val u = req?.url?.toString() ?: return false
                if (u.contains("is.gd") || u.endsWith(".apk")) {
                    AppUpdate.install(this@AppUpdatesActivity); return true
                }
                return false
            }
        }
        b.web.setDownloadListener { _, _, _, _, _ -> AppUpdate.install(this) }
        b.web.loadUrl("file:///android_asset/index.html")

        b.dlBtn.setOnClickListener { AppUpdate.install(this) }

        Thread {
            val v = Updater.latest()
            runOnUiThread {
                if (isFinishing || v == null) return@runOnUiThread
                val status = if (v.first > BuildConfig.VERSION_CODE) "update available" else "up to date"
                b.verInfo.text =
                    "Installed:  build ${BuildConfig.VERSION_CODE}   •   Latest:  build ${v.first}   ($status)"
            }
        }.start()
    }
}
