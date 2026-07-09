package com.stalkertv.app

import android.content.Context
import coil.Coil
import coil.ImageLoader
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Coil image loader that authenticates poster requests to the portal exactly the way a portal
 * client (e.g. Strimix) does: the portal's screenshot server rejects/empties requests that lack the
 * MAG user-agent + session cookie + referer, which is why some movie thumbnails came back blank.
 * We attach those headers for any request to the portal host so posters load reliably.
 */
object PosterLoader {
    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        Coil.setImageLoader {
            val client = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(40, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                // Cap concurrent image loads so a fast scroll on a weak Fire TV doesn't flood the CPU/network
                // (and leaves connections for the portal's own requests).
                .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 8; maxRequestsPerHost = 4 })
                .addInterceptor { chain ->
                    var req = chain.request()
                    val host = Portal.imgHost()
                    if (host.isNotEmpty() && req.url.toString().startsWith(host)) {
                        req = req.newBuilder()
                            .header("User-Agent", Portal.UA)
                            .header("Cookie", Portal.imgCookie())
                            .header("X-User-Agent", "Model: MAG250; Link: WiFi")
                            .header("Referer", "$host/")
                            .build()
                    }
                    chain.proceed(req)
                }
                .build()
            ImageLoader.Builder(app)
                .okHttpClient(client)
                .crossfade(true)
                // Low-RAM resilience: opaque posters/logos as RGB_565 (half the memory) + a smaller memory
                // cache, so heavy grids don't add to memory pressure on low-end boxes (Fire TV Lite).
                .allowRgb565(true)
                .memoryCache { coil.memory.MemoryCache.Builder(app).maxSizePercent(0.15).build() }
                .build()
        }
    }
}
