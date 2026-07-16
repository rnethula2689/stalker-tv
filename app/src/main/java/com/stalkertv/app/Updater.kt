package com.stalkertv.app

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Checks the published version file so sideloaded users get notified of new builds. */
object Updater {
    private const val REPO = "rnethula2689/stalker-tv"
    private val releaseBase =
        "https://github.com/$REPO/releases/download/${BuildConfig.UPDATE_TAG}"
    private val versionUrl = "$releaseBase/latest_version.json"
    private val apkUrl = "$releaseBase/${BuildConfig.UPDATE_APK_NAME}"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** @return Pair(versionCode, versionName) of the latest release, or null on failure. */
    fun latest(): Pair<Int, String>? {
        return try {
            val req = Request.Builder().url(versionUrl).header("User-Agent", "VibeTV").build()
            client.newCall(req).execute().use { r ->
                val o = JSONObject(r.body?.string() ?: return null)
                Pair(o.optInt("versionCode", 0), o.optString("versionName"))
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Downloads the latest APK to [dest]. @return true on success. */
    fun downloadApk(dest: File): Boolean {
        return try {
            val req = Request.Builder().url(apkUrl).header("User-Agent", "VibeTV").build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return false
                val body = r.body ?: return false
                dest.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
