package com.stalkertv.app

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Minimal field-crash capture. Records the last uncaught JVM exception (with stack trace) to a file
 * so it can be shown in Settings ▸ Troubleshooting — invaluable for crashes on devices we can't
 * attach to (e.g. a tester's TV). It chains to the previous handler so the app still crashes normally.
 *
 * NOTE: native crashes (e.g. a libVLC SIGSEGV) bypass the JVM handler — if the app "restarts" but
 * leaves NO record here, that strongly implies a native crash rather than a Kotlin exception.
 */
object CrashLog {
    private var installed = false
    private fun file(ctx: Context) = File(ctx.filesDir, "last_crash.txt")

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date())
                file(app).writeText("[$stamp]  thread=${t.name}\n$sw")
            } catch (_: Throwable) { /* never let logging mask the real crash */ }
            prev?.uncaughtException(t, e)
        }
    }

    fun last(ctx: Context): String? = try {
        val f = file(ctx); if (f.exists()) f.readText().trim().ifBlank { null } else null
    } catch (_: Exception) { null }

    /** A short, plain-language summary of the last crash for non-technical display. */
    fun lastFriendly(ctx: Context): String? {
        val raw = last(ctx) ?: return null
        return try {
            val lines = raw.lines()
            val whenStamp = lines.firstOrNull { it.startsWith("[") }?.substringAfter('[')?.substringBefore(']')?.trim()
            val exLine = lines.firstOrNull { it.contains("Exception") || it.contains("Error:") || it.contains("Error\n") }
                ?: lines.getOrNull(1).orEmpty()
            val type = exLine.substringBefore(":").substringAfterLast('.').trim()
            val msg = exLine.substringAfter(":", "").trim()
            val where = lines.firstOrNull { it.contains("at ${ctx.packageName}") }?.trim()?.removePrefix("at ")
            buildString {
                append("What happened:  ").append(if (type.isNotBlank()) type else "Unexpected error")
                if (msg.isNotBlank()) append("\nDetail:  ").append(msg)
                if (where != null) append("\nWhere:  ").append(where)
                if (!whenStamp.isNullOrBlank()) append("\nWhen:  ").append(whenStamp)
            }
        } catch (_: Exception) { raw.take(300) }
    }

    fun clear(ctx: Context) { try { file(ctx).delete() } catch (_: Exception) {} }
}
