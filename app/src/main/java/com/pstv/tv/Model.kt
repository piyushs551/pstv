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
) {
    val progress: Float get() = if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f
    /** Best image for a poster-style card. */
    val cardArt: String? get() = poster ?: logo
    /** Best image for the hero banner. */
    val heroArt: String? get() = backdrop ?: poster ?: logo
    val isXtreamSeries: Boolean get() = url.startsWith("xs:")
}

data class Group(val kind: Kind, val name: String, val count: Int)

private val yearParen = Regex("""[(\[]((?:19|20)\d{2})[)\]]""")
private val yearTail = Regex("""\s((?:19|20)\d{2})$""")
private val brackets = Regex("""\[[^\]]*]|\([^)]*\)""")
private val prefix = Regex("""^[a-z]{2,3}\s*[|:\-]\s+""")
private val nonAlnum = Regex("""[^a-z0-9]+""")

/** Lower-case title without tags, year or country prefix, for matching against TMDB. */
fun normTitle(raw: String): String {
    var s = raw.lowercase().trim()
    s = prefix.replace(s, "")
    s = brackets.replace(s, " ")
    s = nonAlnum.replace(s, " ").trim()
    return s
}

fun yearOf(raw: String): Int? =
    yearParen.find(raw)?.groupValues?.get(1)?.toIntOrNull()
        ?: yearTail.find(raw.trim())?.groupValues?.get(1)?.toIntOrNull()

fun cleanTitle(raw: String): String {
    var s = prefix.replace(raw.trim(), "")
    s = yearParen.replace(s, "")
    return s.replace(Regex("\\s+"), " ").trim().ifEmpty { raw }
}
