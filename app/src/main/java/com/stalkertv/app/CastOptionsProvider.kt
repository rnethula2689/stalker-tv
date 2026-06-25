package com.stalkertv.app

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Required by the Google Cast framework (referenced from the manifest). Uses the default media
 * receiver so we can cast plain HLS/MP4 URLs without hosting a custom receiver app.
 *
 * This is only ever instantiated when CastContext initialises, which we do exclusively on devices
 * that have Google Play Services — on Fire OS it never runs.
 */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .build()

    override fun getAdditionalSessionProviders(context: Context): MutableList<SessionProvider>? = null
}
