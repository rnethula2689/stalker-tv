package com.stalkertv.app

import android.content.Context
import android.util.Xml
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Optional external XMLTV EPG. When the user sets an XMLTV URL in Settings, we download it (cached to
 * a file, refreshed every few hours), parse <channel>/<programme>, and key programmes by the channel's
 * normalised display-name. The TV Guide then matches a portal channel by its (normalised) name and
 * prefers these programmes, falling back to the portal's own EPG when there's no match.
 *
 * Kept deliberately bounded: only programmes within a window around "now" are retained, so even large
 * national guides stay light in memory.
 */
object XmltvEpg {
    private const val TTL_MS = 6 * 60 * 60 * 1000L      // refresh the cached file every 6 hours
    private const val WINDOW_BACK = 12 * 3600L          // keep programmes from 12h ago…
    private const val WINDOW_FWD = 36 * 3600L           // …to 36h ahead

    private var byName: Map<String, List<Portal.EpgItem>> = emptyMap()
    private var loadedUrl = ""
    @Volatile var lastStatus = ""                       // human-readable result, for the Settings screen
        private set

    fun isActive(ctx: Context): Boolean = Configs.epgXmltvUrl(ctx).isNotBlank()

    fun normalize(s: String): String = s.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")

    /** Programmes for a channel name (normalised match), empty if none / not loaded. */
    fun forChannel(name: String): List<Portal.EpgItem> = byName[normalize(name)] ?: emptyList()

    fun matchedChannelCount(): Int = byName.size

    /** Download+parse if needed. Call OFF the main thread. @return true if usable data is loaded. */
    @Synchronized
    fun ensureLoaded(ctx: Context, force: Boolean = false): Boolean {
        val url = Configs.epgXmltvUrl(ctx)
        if (url.isBlank()) { byName = emptyMap(); loadedUrl = ""; lastStatus = "No XMLTV URL set"; return false }
        if (!force && url == loadedUrl && byName.isNotEmpty()) return true
        return try {
            val file = cacheFile(ctx)
            val fresh = file.exists() && (System.currentTimeMillis() - file.lastModified() < TTL_MS) && url == loadedUrl
            if (force || !fresh) download(url, file)
            parse(file)
            loadedUrl = url
            if (byName.isEmpty()) { lastStatus = "Loaded, but no programmes matched the window"; false }
            else { lastStatus = "Loaded ✓  ${byName.size} channels with a guide"; true }
        } catch (e: Exception) {
            lastStatus = "Failed: ${e.message ?: e.javaClass.simpleName}"
            false
        }
    }

    private fun cacheFile(ctx: Context) = File(ctx.cacheDir, "epg_xmltv.xml")

    private fun download(url: String, dest: File) {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 30000; requestMethod = "GET"
            setRequestProperty("Accept-Encoding", "gzip")
            setRequestProperty("User-Agent", Portal.UA)
        }
        c.inputStream.use { raw ->
            val gz = url.endsWith(".gz", true) || c.contentEncoding?.contains("gzip", true) == true
            val ins = if (gz) GZIPInputStream(raw) else raw
            dest.outputStream().use { out -> ins.copyTo(out, 64 * 1024) }
        }
    }

    private val fmtZ = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
    private val fmtPlain = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)

    /** XMLTV time: "20240628140000 +0530" (offset optional). @return epoch seconds, 0 on failure. */
    private fun parseTs(s: String): Long {
        if (s.isBlank()) return 0
        val t = s.trim()
        return try {
            (if (t.length > 14 && (t.contains('+') || t.contains('-')))
                fmtZ.parse(t)!! else fmtPlain.parse(t.take(14))!!).time / 1000
        } catch (_: Exception) { 0 }
    }

    private fun parse(file: File) {
        val idToName = HashMap<String, String>()
        val byId = HashMap<String, ArrayList<Portal.EpgItem>>()
        val now = System.currentTimeMillis() / 1000
        val lo = now - WINDOW_BACK; val hi = now + WINDOW_FWD

        file.inputStream().buffered().use { ins ->
            val p = Xml.newPullParser()
            p.setInput(ins, null)
            var ev = p.eventType
            var curChanId: String? = null            // inside <channel>
            var firstNameForChan = false
            // programme accumulation
            var pgChan: String? = null
            var pgStart = 0L; var pgStop = 0L
            var pgTitle: String? = null; var pgDesc: String? = null
            var inTitle = false; var inDesc = false; var inDisplayName = false
            while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    org.xmlpull.v1.XmlPullParser.START_TAG -> when (p.name) {
                        "channel" -> { curChanId = p.getAttributeValue(null, "id"); firstNameForChan = true }
                        "display-name" -> inDisplayName = curChanId != null
                        "programme" -> {
                            pgChan = p.getAttributeValue(null, "channel")
                            pgStart = parseTs(p.getAttributeValue(null, "start") ?: "")
                            pgStop = parseTs(p.getAttributeValue(null, "stop") ?: "")
                            pgTitle = null; pgDesc = null
                        }
                        "title" -> inTitle = pgChan != null
                        "desc" -> inDesc = pgChan != null
                    }
                    org.xmlpull.v1.XmlPullParser.TEXT -> {
                        val txt = p.text
                        when {
                            inDisplayName && firstNameForChan && curChanId != null && !txt.isNullOrBlank() -> {
                                idToName[curChanId!!] = txt.trim(); firstNameForChan = false
                            }
                            inTitle && pgTitle == null && !txt.isNullOrBlank() -> pgTitle = txt.trim()
                            inDesc && pgDesc == null && !txt.isNullOrBlank() -> pgDesc = txt.trim()
                        }
                    }
                    org.xmlpull.v1.XmlPullParser.END_TAG -> when (p.name) {
                        "display-name" -> inDisplayName = false
                        "title" -> inTitle = false
                        "desc" -> inDesc = false
                        "channel" -> curChanId = null
                        "programme" -> {
                            val ch = pgChan
                            if (ch != null && pgStart > 0 && (pgStop == 0L || pgStop > lo) && pgStart < hi) {
                                byId.getOrPut(ch) { ArrayList() }.add(
                                    Portal.EpgItem(
                                        name = pgTitle ?: "(no title)", start = "", end = "",
                                        descr = pgDesc ?: "", hasArchive = false,
                                        startTs = pgStart, stopTs = pgStop
                                    )
                                )
                            }
                            pgChan = null
                        }
                    }
                }
                ev = p.next()
            }
        }

        // Re-key by normalised display-name (fall back to the raw id when no <display-name>).
        val out = HashMap<String, List<Portal.EpgItem>>()
        for ((id, items) in byId) {
            if (items.isEmpty()) continue
            val sorted = items.sortedBy { it.startTs }
            val nm = idToName[id]
            if (nm != null) out[normalize(nm)] = sorted
            val idKey = normalize(id)
            if (!out.containsKey(idKey)) out[idKey] = sorted
        }
        byName = out
    }
}
