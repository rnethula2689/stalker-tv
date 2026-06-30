package com.stalkertv.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.widget.TextView

/**
 * Profile avatars. A profile's `avatar` string is one of:
 *   ""              -> default (coloured circle + name initial)
 *   "emoji:🦁"      -> a built-in fun icon
 *   "file:/path"    -> a photo the user took or picked (stored square ~256px)
 */
object Avatars {
    /** Built-in picks: animals, then toys / fun stuff. */
    val EMOJI = listOf(
        "🦁","🐶","🐱","🦊","🐻","🐼","🐨","🐯","🦄","🐸","🐵","🐰",
        "🐧","🐢","🦋","🐙","🐳","🦖","🦕","🐝","🐞","🦉","🐬","🐠",
        "🚗","🚂","✈️","🚀","🚁","⚽","🏀","🎈","🧸","🎮","🎨","🎸",
        "🍦","🍓","🍩","🍕","⭐","🌈","🌟","🎀","👑","🤖","🦸","🎵"
    )

    private const val RING = 0xFF19C37D.toInt()

    /** Render an avatar into a circular TextView (its layoutParams width = the circle size). */
    fun render(tv: TextView, avatar: String, color: Int, initial: String, active: Boolean) {
        val dp = tv.resources.displayMetrics.density
        val ringW = (3 * dp).toInt()
        val size = tv.layoutParams?.width?.takeIf { it > 0 } ?: (96 * dp).toInt()

        if (avatar.startsWith("file:")) {
            val bm = circularBitmap(avatar.removePrefix("file:"), size)
            if (bm != null) {
                tv.text = ""
                val img = BitmapDrawable(tv.resources, bm)
                tv.background = if (active) {
                    val ring = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x00000000); setStroke(ringW, RING) }
                    LayerDrawable(arrayOf(img, ring))
                } else img
                return
            }
        }
        val oval = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(color); if (active) setStroke(ringW, RING)
        }
        tv.background = oval
        tv.text = if (avatar.startsWith("emoji:")) avatar.removePrefix("emoji:") else initial
    }

    /** Decode a photo file, centre-crop to a circle of sizePx. Returns null on any error. */
    fun circularBitmap(path: String, sizePx: Int): Bitmap? = try {
        val size = if (sizePx > 0) sizePx else 96
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > size * 2 || bounds.outHeight / sample > size * 2) sample *= 2
        val src = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        if (src == null) null else {
            val min = minOf(src.width, src.height)
            val sq = Bitmap.createBitmap(src, (src.width - min) / 2, (src.height - min) / 2, min, min)
            val scaled = Bitmap.createScaledBitmap(sq, size, size, true)
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
            out
        }
    } catch (_: Exception) { null }

    /** Import a picked/captured image into the app's avatars folder as a square ~256px JPEG. */
    fun importFrom(ctx: android.content.Context, src: android.net.Uri): String? = try {
        val input = ctx.contentResolver.openInputStream(src)
        val bm = BitmapFactory.decodeStream(input); input?.close()
        if (bm == null) null else saveSquare(ctx, bm)
    } catch (_: Exception) { null }

    /** Shrink an already-saved photo file (e.g. a full-res camera shot) to a square ~256px JPEG. */
    fun shrinkFile(ctx: android.content.Context, path: String): String? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
        val bm = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        if (bm == null) null else saveSquare(ctx, bm)
    } catch (_: Exception) { null }

    private fun saveSquare(ctx: android.content.Context, bm: Bitmap): String {
        val min = minOf(bm.width, bm.height)
        val sq = Bitmap.createBitmap(bm, (bm.width - min) / 2, (bm.height - min) / 2, min, min)
        val scaled = Bitmap.createScaledBitmap(sq, 256, 256, true)
        val dir = java.io.File(ctx.filesDir, "avatars").apply { mkdirs() }
        val out = java.io.File(dir, "av_${System.currentTimeMillis()}.jpg")
        java.io.FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        return "file:${out.absolutePath}"
    }
}
