package com.stalkertv.app

import java.io.File

/**
 * Minimal SRT parser for the app's OWN subtitle overlay. We render external subtitles ourselves
 * (a TextView synced to the player clock) because libVLC's native SPU pipeline silently fails to
 * paint on Fire hardware decode (track selects fine, text never appears — plus "no reference
 * clock" timestamp errors break SPU scheduling). Parsing SRT is trivial; owning the render makes
 * subtitles deterministic on every device and lets a new subtitle apply WITHOUT reloading the stream.
 */
object SrtSubs {
    data class Cue(val startMs: Long, val endMs: Long, val text: String)

    private val TIME = Regex("(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})")
    private val TAG = Regex("<[^>]*>")            // strip <i>, <font …>, etc.
    private val BRACE = Regex("\\{[^}]*\\}")      // strip {\an8}-style ASS leftovers

    fun parse(file: File): List<Cue> = try {
        val out = ArrayList<Cue>()
        // UTF-8 first; if it looks mangled (lots of replacement chars), fall back to Latin-1.
        var text = file.readText(Charsets.UTF_8)
        if (text.count { it == '�' } > 20) text = file.readText(Charsets.ISO_8859_1)
        text = text.removePrefix("﻿")
        for (block in text.split(Regex("\\r?\\n\\r?\\n"))) {
            val lines = block.trim().split(Regex("\\r?\\n"))
            if (lines.isEmpty()) continue
            val timeIdx = lines.indexOfFirst { TIME.containsMatchIn(it) }
            if (timeIdx < 0) continue
            val m = TIME.find(lines[timeIdx]) ?: continue
            fun ms(h: String, mi: String, s: String, f: String) =
                h.toLong() * 3_600_000 + mi.toLong() * 60_000 + s.toLong() * 1000 + f.padEnd(3, '0').toLong()
            val start = ms(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])
            val end = ms(m.groupValues[5], m.groupValues[6], m.groupValues[7], m.groupValues[8])
            val body = lines.drop(timeIdx + 1).joinToString("\n")
                .replace(TAG, "").replace(BRACE, "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
                .trim()
            if (body.isNotEmpty() && end > start) out.add(Cue(start, end, body))
        }
        out.sortedBy { it.startMs }
    } catch (_: Exception) { emptyList() }

    /** The cue visible at [posMs], or null. Binary search — called a few times a second. */
    fun cueAt(cues: List<Cue>, posMs: Long): Cue? {
        var lo = 0; var hi = cues.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val c = cues[mid]
            when {
                posMs < c.startMs -> hi = mid - 1
                posMs > c.endMs -> lo = mid + 1
                else -> return c
            }
        }
        return null
    }
}
