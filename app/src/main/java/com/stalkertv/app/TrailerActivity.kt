package com.stalkertv.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity

/**
 * Plays a YouTube trailer in-app via the YouTube IFrame Player API so Back returns straight to the
 * app (instead of stranding the user in the external YouTube app on TV). If the video can't be
 * embedded (owner-restricted), we fall back to the external YouTube app so it still plays.
 */
class TrailerActivity : AppCompatActivity() {
    private var web: WebView? = null
    private var videoId: String = ""
    private var fellBack = false

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        videoId = intent.getStringExtra("videoId") ?: ""
        videoId = "aqz-KE-bpKQ" // TEMP DIAGNOSTIC: known-embeddable video to test the player itself
        if (videoId.isBlank()) { finish(); return }

        val container = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        setContentView(container)
        goImmersive()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val wv = WebView(this)
        web = wv
        container.addView(wv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        wv.setBackgroundColor(0xFF000000.toInt())
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false // allow autoplay
            // Default WebView UA contains "wv"; YouTube's player rejects it.
            userAgentString = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }
        wv.webChromeClient = WebChromeClient()
        wv.webViewClient = WebViewClient()
        wv.addJavascriptInterface(Bridge(), "Android")

        // Full IFrame Player API (with an explicit origin) — far more reliable than a bare iframe,
        // which fails with "video unavailable (152)" in a WebView.
        val html = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
            <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{width:100%;height:100%}</style></head>
            <body><div id="p"></div>
            <script src="https://www.youtube.com/iframe_api"></script>
            <script>
            var player;
            function onYouTubeIframeAPIReady(){
              player = new YT.Player('p', {
                width:'100%', height:'100%', videoId:'$videoId',
                playerVars:{autoplay:1, playsinline:1, rel:0, fs:1, controls:1, modestbranding:1, origin:'https://www.youtube.com'},
                events:{
                  onReady:function(e){ try{ e.target.playVideo(); }catch(x){} },
                  onError:function(e){ if(window.Android) Android.onError(e.data); }
                }
              });
            }
            </script></body></html>
        """.trimIndent()
        wv.loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "utf-8", null)
    }

    private inner class Bridge {
        // The embed failed (owner disabled embedding etc.) → play in the external YouTube app instead.
        @JavascriptInterface
        fun onError(code: Int) {
            android.util.Log.d("TRAILERDBG", "IFrame onError code=$code videoId=$videoId")
            if (fellBack) return
            fellBack = true
            runOnUiThread {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId")))
                } catch (_: Exception) {}
                finish()
            }
        }
    }

    private fun goImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    override fun onPause() { super.onPause(); web?.onPause() }
    override fun onResume() { super.onResume(); web?.onResume() }
    override fun onDestroy() {
        web?.let { it.loadUrl("about:blank"); it.destroy() }
        web = null
        super.onDestroy()
    }
}
