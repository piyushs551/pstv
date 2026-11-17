package com.pstv.tv

import java.io.BufferedReader

/** Streaming M3U/M3U8 parser: group-title, tvg-logo and the display name after the last comma. */
object M3uParser {
    private val attr = Regex("""([\w-]+)="([^"]*)"""")

    fun parse(r: BufferedReader, sink: (Item) -> Unit) {
        var name: String? = null
        var logo: String? = null
        var group = "Other"
        r.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val comma = line.lastIndexOf(',')
                    name = if (comma >= 0) line.substring(comma + 1).trim() else null
                    var tvgName: String? = null
                    logo = null; group = "Other"
                    for (m in attr.findAll(line)) {
                        val v = m.groupValues[2]
                        when (m.groupValues[1].lowercase()) {
                            "tvg-logo" -> logo = v.ifBlank { null }
                            "group-title" -> group = v.ifBlank { "Other" }
                            "tvg-name" -> tvgName = v
                        }
                    }
                    if (name.isNullOrBlank()) name = tvgName
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> group = line.substringAfter(':').trim().ifEmpty { group }
                line.isEmpty() || line.startsWith("#") -> {}
                else -> {
                    val n = name
                    if (!n.isNullOrBlank() && (line.startsWith("http") || line.startsWith("rtmp") || line.startsWith("rtsp") || line.startsWith("udp"))) {
                        sink(Item(0, classify(line, group), group, n, logo, line))
                    }
                    name = null; logo = null; group = "Other"
                }
            }
        }
    }

    private val vodExt = Regex("""\.(mkv|mp4|avi|mov|wmv|m4v)(\?.*)?$""", RegexOption.IGNORE_CASE)

    fun classify(url: String, group: String): Kind {
        val g = group.lowercase()
        val u = url.lowercase()
        return when {
            "/series/" in u || Regex("""\b(series|shows?|season|tv shows?)\b""").containsMatchIn(g) -> Kind.SERIES
            "/movie/" in u || Regex("""\b(movies?|films?|vod|cinema)\b""").containsMatchIn(g) || vodExt.containsMatchIn(u) -> Kind.MOVIE
            else -> Kind.LIVE
        }
    }
}
