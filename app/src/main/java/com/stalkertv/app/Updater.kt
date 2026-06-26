package com.stalkertv.app

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Checks the published version file so sideloaded users get notified of new builds. */
object Updater {
    private const val URL =
        "https://github.com/rnethula2689/stalker-tv/releases/download/apk-latest/latest_version.json"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** @return Pair(versionCode, versionName) of the latest release, or null on failure. */
    fun latest(): Pair<Int, String>? {
        return try {
            val req = Request.Builder().url(URL).header("User-Agent", "StalkerTV").build()
            client.newCall(req).execute().use { r ->
                val o = JSONObject(r.body?.string() ?: return null)
                Pair(o.optInt("versionCode", 0), o.optString("versionName"))
            }
        } catch (e: Exception) {
            null
        }
    }

    private const val APK_URL =
        "https://github.com/rnethula2689/stalker-tv/releases/download/apk-latest/app-debug.apk"

    /** Downloads the latest APK to [dest]. @return true on success. */
    fun downloadApk(dest: File): Boolean {
        return try {
            val req = Request.Builder().url(APK_URL).header("User-Agent", "StalkerTV").build()
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
