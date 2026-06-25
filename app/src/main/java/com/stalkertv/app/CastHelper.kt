package com.stalkertv.app

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.media.MediaRouteSelector
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import java.util.concurrent.Executors

/**
 * "Cast to TV" for the fullscreen players. Offers:
 *  - DLNA renderers (most smart TVs) — works everywhere, including Fire OS.
 *  - Google Cast / Chromecast — only where Google Play Services exists (not Fire OS); guarded so it
 *    simply doesn't appear otherwise.
 *
 * Best-effort: a given TV must support the stream type it's handed (HLS vs MP4/TS), so success
 * depends on the target device.
 */
object CastHelper {
    private val io = Executors.newSingleThreadExecutor()

    /** Google Cast context, or null when Play Services is unavailable (e.g. Fire OS). */
    private fun castContext(activity: AppCompatActivity): CastContext? = try {
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(activity) == ConnectionResult.SUCCESS)
            CastContext.getSharedInstance(activity)
        else null
    } catch (_: Throwable) {
        null
    }

    fun show(activity: AppCompatActivity, url: String, title: String, isLive: Boolean) {
        val searching = AlertDialog.Builder(activity)
            .setMessage("Searching for TVs on your network…")
            .setNegativeButton("Cancel", null)
            .create()
        searching.show()
        io.execute {
            val renderers = Dlna.discover(activity)
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                searching.dismiss()
                showPicker(activity, url, title, isLive, renderers)
            }
        }
    }

    private fun showPicker(
        activity: AppCompatActivity, url: String, title: String, isLive: Boolean,
        renderers: List<Dlna.Renderer>
    ) {
        val cc = castContext(activity)
        val labels = ArrayList<String>()
        renderers.forEach { labels.add("📺  ${it.name}") }
        if (cc != null) labels.add("🟢  Chromecast / Google Cast…")

        if (labels.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle("Cast to TV")
                .setMessage("No TVs found on your Wi-Fi. Make sure your TV is on the same network and DLNA / screen-share is enabled.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        AlertDialog.Builder(activity)
            .setTitle("Cast to TV")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < renderers.size) {
                    castDlna(activity, renderers[which], url, title)
                } else {
                    chooseChromecast(activity, cc!!, url, title, isLive)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun castDlna(activity: AppCompatActivity, r: Dlna.Renderer, url: String, title: String) {
        Toast.makeText(activity, "Casting to ${r.name}…", Toast.LENGTH_SHORT).show()
        io.execute {
            val ok = Dlna.cast(r, url, title)
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                Toast.makeText(
                    activity,
                    if (ok) "▶ Playing on ${r.name}" else "Couldn't cast to ${r.name} — it may not support this stream.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** Open the system Cast device chooser; when a session starts, load the media onto it. */
    private fun chooseChromecast(
        activity: AppCompatActivity, cc: CastContext, url: String, title: String, isLive: Boolean
    ) {
        // Load the media as soon as a Cast session connects.
        val mgr = cc.sessionManager
        val listener = object : SessionManagerListener<CastSession> {
            override fun onSessionStarted(session: CastSession, sessionId: String) { load(session); done() }
            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) { load(session); done() }
            override fun onSessionStartFailed(session: CastSession, error: Int) { done() }
            override fun onSessionEnded(session: CastSession, error: Int) {}
            override fun onSessionEnding(session: CastSession) {}
            override fun onSessionResuming(session: CastSession, sessionId: String) {}
            override fun onSessionResumeFailed(session: CastSession, error: Int) {}
            override fun onSessionSuspended(session: CastSession, reason: Int) {}
            override fun onSessionStarting(session: CastSession) {}
            private fun load(session: CastSession) {
                try {
                    val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_GENERIC)
                        .apply { putString(MediaMetadata.KEY_TITLE, title) }
                    val info = MediaInfo.Builder(url)
                        .setStreamType(if (isLive) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
                        .setContentType(contentType(url))
                        .setMetadata(meta)
                        .build()
                    session.remoteMediaClient?.load(
                        MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build()
                    )
                } catch (_: Throwable) {
                }
            }
            private fun done() = try { mgr.removeSessionManagerListener(this, CastSession::class.java) } catch (_: Throwable) {}
        }
        mgr.addSessionManagerListener(listener, CastSession::class.java)
        // If already connected, load immediately.
        mgr.currentCastSession?.let { if (it.isConnected) { listener.onSessionStarted(it, ""); return } }
        try {
            val selector = MediaRouteSelector.Builder()
                .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
                .build()
            MediaRouteChooserDialog(activity).apply { routeSelector = selector }.show()
        } catch (_: Throwable) {
            Toast.makeText(activity, "Chromecast isn't available on this device.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun contentType(url: String): String {
        val u = url.substringBefore("?").lowercase()
        return when {
            u.endsWith(".m3u8") -> "application/x-mpegURL"
            u.endsWith(".mp4") -> "video/mp4"
            u.endsWith(".mkv") -> "video/x-matroska"
            else -> "video/mp2t"
        }
    }
}
