package com.pstv.tv

enum class Kind { LIVE, MOVIE, SERIES }

data class Item(
    val id: Long,
    val kind: Kind,
    val group: String,
    val name: String,
    val logo: String?,
    val url: String,
    val year: Int? = null,
    val overview: String? = null,
    val backdrop: String? = null,
    val poster: String? = null,
    val posMs: Long = 0,
    val durMs: Long = 0,
    val tvg: String? = null,
    val rating: Float? = null,
    val genres: String? = null,
    val note: String? = null,
) {
    val progress: Float get() = if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f
    /** Best image for a poster-style card. */
    val cardArt: String? get() = if (kind == Kind.LIVE) logo else (backdrop?.replace("/w1280/", "/w300/") ?: poster ?: logo)
    val isWide: Boolean get() = true
    /** Best image for the hero banner. */
    val heroArt: String? get() = backdrop ?: poster ?: logo
    val isXtreamSeries: Boolean get() = url.startsWith("xs:")
}

data class Group(val kind: Kind, val name: String, val count: Int)

private val yearParen = Regex("""[(\[]((?:19|20)\d{2})[)\]]""")
private val yearTail = Regex("""\s((?:19|20)\d{2})$""")
private val brackets = Regex("""\[[^\]]*]|\([^)]*\)""")
private val prefix = Regex("""^[a-z]{2,3}\s*[|:\-]\s+""")
private val noiseTokens = setOf("hd", "uhd", "fhd", "4k", "8k", "1080p", "720p", "480p", "2160p", "cam", "hdcam", "hdrip", "webrip", "webdl", "dvdrip", "bluray", "brrip", "hdr", "hevc", "x264", "x265")
private val nonAlnum = Regex("""[^a-z0-9]+""")

/** Lower-case title without tags, year or country prefix, for matching against TMDB. */
fun normTitle(raw: String): String {
    var s = raw.lowercase().trim()
    s = prefix.replace(s, "")
    s = brackets.replace(s, " ")
    s = nonAlnum.replace(s, " ").trim()
    return s.split(' ').filter { it !in noiseTokens }.joinToString(" ")
}

private val qualRe = Regex("(?i)\\b(4k|uhd|2160p|fhd|1080p|720p|480p|hd|sd|hdcam|cam|hdrip|webrip|web-dl|bluray|brrip|dvdrip|hevc|x265|hdr|dolby|atmos)\\b")

/** Quality tags found in a title ("4K", "UHD HEVC", "CAM"...), or null. */
fun qualityTag(name: String): String? =
    qualRe.findAll(name).map { it.value.uppercase() }.distinct().take(2).toList().takeIf { it.isNotEmpty() }?.joinToString(" ")

fun yearOf(raw: String): Int? =
    yearParen.find(raw)?.groupValues?.get(1)?.toIntOrNull()
        ?: yearTail.find(raw.trim())?.groupValues?.get(1)?.toIntOrNull()

fun cleanTitle(raw: String): String {
    var s = prefix.replace(raw.trim(), "")
    s = yearParen.replace(s, "")
    return s.replace(Regex("\\s+"), " ").trim().ifEmpty { raw }
}

// ---- looser title matching (TMDB title vs playlist title) -------------------------------------
private val softNoise = setOf("hindi", "english", "dubbed", "dual", "audio", "multi", "sub", "subs", "esub", "web", "dl", "dts", "ac3", "aac", "org", "uncut", "extended", "remastered", "bd")
private val yearToken = Regex("(19|20)\\d{2}")
private val leadStop = setOf("the", "a", "an")

/** Words that identify a title: years, language/quality tags and a leading "the" are dropped. */
fun fuzzyTokens(norm: String): List<String> {
    val all = norm.split(' ').filter { it.isNotEmpty() }
    var t = all.filter { it !in softNoise && !yearToken.matches(it) }
    if (t.isEmpty()) t = all                                   // titles that are only a year, e.g. "1917"
    return if (t.size > 1 && t.first() in leadStop) t.drop(1) else t
}

/**
 * Same title despite different decoration? Spacing/hyphen differences ("spider man" = "spiderman") and one stray word
 * (Jaccard >= 0.8) are fine. A title that merely contains the other ("The Dark Knight" / "...Rises") only counts when both
 * years are known and agree.
 */
fun fuzzyAccept(a: List<String>, b: List<String>, yearA: Int?, yearB: Int?): Boolean {
    if (a.isEmpty() || b.isEmpty()) return false
    if (yearA != null && yearB != null && kotlin.math.abs(yearA - yearB) > 1) return false
    if (a.joinToString("") == b.joinToString("")) return true
    val sa = a.toSet(); val sb = b.toSet()
    val inter = sa.intersect(sb).size
    if (inter.toDouble() / sa.union(sb).size >= 0.8 && minOf(sa.size, sb.size) >= 2) return true
    val subset = inter == minOf(sa.size, sb.size)
    return subset && inter >= 2 && sa.size.coerceAtLeast(sb.size) - inter <= 2 && yearA != null && yearB != null
}
