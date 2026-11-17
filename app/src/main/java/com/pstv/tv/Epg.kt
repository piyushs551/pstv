package com.pstv.tv

import android.util.Xml
import kotlinx.coroutines.launch
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.util.GregorianCalendar
import java.util.TimeZone
import java.util.zip.GZIPInputStream

/**
 * XMLTV guide. Streamed with XmlPullParser (flat RAM even for huge files); only channels that exist in the
 * playlist and only a window from 3 h ago to 36 h ahead are stored.
 */
object Epg {
    @Volatile var refreshing = false
    @Volatile var status = ""

    fun url(): String = Prefs.epgUrl.ifBlank { if (Prefs.sourceType == "xtream" && Prefs.xHost.isNotEmpty()) Xtream.xmltvUrl() else Prefs.epgAuto }

    fun refreshIfStale() {
        if (url().isBlank() || Prefs.refreshHours == 0) return
        if (System.currentTimeMillis() - Prefs.epgLast > Prefs.refreshHours * 3_600_000L) refresh()
    }

    fun refresh() {
        val u = url()
        if (refreshing || u.isBlank()) return
        refreshing = true; status = "Updating guide…"
        Repo.scope.launch {
            try {
                val wanted = Db.liveTvgIds()
                if (wanted.isEmpty()) error("No channels with guide ids (tvg-id / epg_channel_id)")
                val now = System.currentTimeMillis()
                val from = now - 3 * 3_600_000L; val to = now + 36 * 3_600_000L
                Http.client.newCall(Http.req(u).build()).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    val bin = BufferedInputStream(r.body!!.byteStream(), 64 * 1024)
                    bin.mark(2); val b0 = bin.read(); val b1 = bin.read(); bin.reset()
                    val ins = if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(bin, 64 * 1024) else bin
                    Db.replaceEpg { sink -> parse(ins, wanted, from, to, sink) }
                }
                Prefs.epgLast = System.currentTimeMillis()
                status = ""
            } catch (e: Exception) {
                status = "Guide update failed: ${e.message ?: e.javaClass.simpleName}"
            }
            refreshing = false
            Repo.ping()
        }
    }

    private fun parse(ins: java.io.InputStream, wanted: Set<String>, from: Long, to: Long, sink: (String, Long, Long, String) -> Unit) {
        val p = Xml.newPullParser()
        p.setInput(ins, null)
        var ch: String? = null; var s = 0L; var e = 0L; var inTitle = false; var title: String? = null
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "programme" -> {
                        ch = p.getAttributeValue(null, "channel")?.lowercase()
                        s = if (ch != null && ch in wanted) time(p.getAttributeValue(null, "start")) else 0L
                        e = if (s != 0L) time(p.getAttributeValue(null, "stop")) else 0L
                        title = null
                    }
                    "title" -> inTitle = s != 0L && title == null
                }
                XmlPullParser.TEXT -> if (inTitle) title = p.text?.trim()
                XmlPullParser.END_TAG -> when (p.name) {
                    "title" -> inTitle = false
                    "programme" -> {
                        if (s != 0L && e > from && s < to && !title.isNullOrEmpty()) sink(ch!!, s, e, title!!)
                        ch = null; s = 0L
                    }
                }
            }
            ev = p.next()
        }
    }

    private val cal = GregorianCalendar(TimeZone.getTimeZone("UTC"))

    /** "20241117140000 +0100" -> epoch millis; 0 if unparseable. */
    private fun time(v: String?): Long {
        if (v == null || v.length < 14) return 0L
        return try {
            cal.clear()
            cal.set(v.substring(0, 4).toInt(), v.substring(4, 6).toInt() - 1, v.substring(6, 8).toInt(), v.substring(8, 10).toInt(), v.substring(10, 12).toInt(), v.substring(12, 14).toInt())
            var t = cal.timeInMillis
            val z = v.substring(14).trim()
            if (z.length >= 5 && (z[0] == '+' || z[0] == '-')) {
                val off = (z.substring(1, 3).toInt() * 60 + z.substring(3, 5).toInt()) * 60_000L
                t -= if (z[0] == '+') off else -off
            }
            t
        } catch (_: Exception) { 0L }
    }
}
