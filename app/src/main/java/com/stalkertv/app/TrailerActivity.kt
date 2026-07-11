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
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Plays a YouTube trailer in-app so Back returns straight to the app (instead of stranding the user
 * in the external YouTube app on TV). The player page is served from a tiny local web server
 * (http://127.0.0.1) so it has a REAL origin — loadDataWithBaseURL gives a null origin and YouTube
 * then refuses playback with "video unavailable (152)". If a video genuinely can't be embedded we
 * fall back to the external YouTube app so it still plays.
 */
class TrailerActivity : AppCompatActivity() {
    private var web: WebView? = null
    private var server: ServerSocket? = null
    private var videoId: String = ""
    private var fellBack = false

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Validate the id (from portal/TMDb data) against the YouTube id charset before it's ever injected
        // into the player page's JS — blocks quote-breakout / script injection into the WebView + JS bridge.
        videoId = (intent.getStringExtra("videoId") ?: "").takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,20}")) } ?: ""
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
            userAgentString = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }
        wv.webChromeClient = WebChromeClient()
        wv.webViewClient = WebViewClient()
        wv.addJavascriptInterface(Bridge(), "Android")

        try {
            val ss = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            server = ss
            val port = ss.localPort
            serve(ss, playerHtml(videoId, "http://127.0.0.1:$port"))
            wv.loadUrl("http://127.0.0.1:$port/")
        } catch (_: Exception) {
            // Couldn't start the local server → just play it externally.
            playExternal()
        }
    }

    private fun playerHtml(id: String, origin: String) = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
        <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{width:100%;height:100%}</style></head>
        <body><div id="p"></div>
        <script src="https://www.youtube.com/iframe_api"></script>
        <script>
        var player;
        function onYouTubeIframeAPIReady(){
          player = new YT.Player('p', {
            width:'100%', height:'100%', videoId:'$id',
            playerVars:{autoplay:1, playsinline:1, rel:0, fs:1, controls:1, modestbranding:1, origin:'$origin'},
            events:{
              onReady:function(e){ try{ e.target.playVideo(); }catch(x){} },
              onError:function(e){ if(window.Android) Android.onError(e.data); }
            }
          });
        }
        </script></body></html>
    """.trimIndent()

    /** Minimal HTTP server: respond to every request with the player page. */
    private fun serve(ss: ServerSocket, html: String) {
        Thread {
            val body = html.toByteArray(Charsets.UTF_8)
            val resp = ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8)
            while (!ss.isClosed) {
                try {
                    val sock = ss.accept()
                    Thread {
                        try {
                            sock.getInputStream().read(ByteArray(2048)) // drain request line/headers
                            sock.getOutputStream().apply { write(resp); write(body); flush() }
                        } catch (_: Exception) {} finally { try { sock.close() } catch (_: Exception) {} }
                    }.start()
                } catch (_: Exception) { break }
            }
        }.start()
    }

    private fun playExternal() {
        if (fellBack) return
        fellBack = true
        runOnUiThread {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId"))) } catch (_: Exception) {}
            finish()
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onError(code: Int) {
            playExternal()
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
        try { server?.close() } catch (_: Exception) {}
        web?.let { it.loadUrl("about:blank"); it.destroy() }
        web = null
        super.onDestroy()
    }
}
