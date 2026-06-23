package com.stalkertv.app

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Checks the published version file so sideloaded users get notified of new builds. */
object Updater {
    private const val URL =
        "https://raw.githubusercontent.com/rnethula2689/stalker-tv/main/latest_version.json"
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
}
