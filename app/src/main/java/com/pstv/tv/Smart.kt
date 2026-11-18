package com.pstv.tv

/** What a themed search like "bollywood comedy" or "christmas movies" asks for. Parsed locally; no AI, no network. */
data class ThemeQuery(
    val kinds: Set<Kind>, val genres: List<String>, val lang: String?, val country: String?,
    val keywords: List<String>, val yearFrom: Int?, val yearTo: Int?, val sort: String, val label: String,
) {
    /** True if TMDB can filter on something other than free keywords (genre, language/region, year). */
    val hasFilters get() = genres.isNotEmpty() || lang != null || country != null || yearFrom != null
}

object Smart {
    private val langs = mapOf(
        "bollywood" to "hi", "hindi" to "hi", "tollywood" to "te", "telugu" to "te", "kollywood" to "ta", "tamil" to "ta",
        "malayalam" to "ml", "mollywood" to "ml", "kannada" to "kn", "sandalwood" to "kn", "punjabi" to "pa", "marathi" to "mr",
        "bengali" to "bn", "gujarati" to "gu", "korean" to "ko", "japanese" to "ja", "anime" to "ja", "spanish" to "es",
        "french" to "fr", "turkish" to "tr", "chinese" to "zh", "german" to "de", "italian" to "it",
    )
    private val countries = mapOf("hollywood" to "US", "american" to "US", "british" to "GB", "indian" to "IN")
    private val genreWords = mapOf(
        "comedy" to "comedy", "comedies" to "comedy", "funny" to "comedy", "humor" to "comedy", "action" to "action",
        "romance" to "romance", "romantic" to "romance", "love" to "romance", "horror" to "horror", "scary" to "horror",
        "thriller" to "thriller", "suspense" to "thriller", "drama" to "drama", "scifi" to "scifi", "sci-fi" to "scifi",
        "fantasy" to "fantasy", "animation" to "animation", "animated" to "animation", "cartoon" to "animation", "cartoons" to "animation",
        "crime" to "crime", "documentary" to "documentary", "documentaries" to "documentary", "family" to "family", "kids" to "family",
        "war" to "war", "western" to "western", "mystery" to "mystery", "history" to "history", "historical" to "history",
        "music" to "music", "musical" to "music", "adventure" to "adventure",
    )
    private val movieGenre = mapOf("action" to 28, "adventure" to 12, "animation" to 16, "comedy" to 35, "crime" to 80, "documentary" to 99, "drama" to 18,
        "family" to 10751, "fantasy" to 14, "history" to 36, "horror" to 27, "music" to 10402, "mystery" to 9648, "romance" to 10749, "scifi" to 878,
        "thriller" to 53, "war" to 10752, "western" to 37)
    private val tvGenre = mapOf("action" to 10759, "adventure" to 10759, "animation" to 16, "comedy" to 35, "crime" to 80, "documentary" to 99, "drama" to 18,
        "family" to 10751, "fantasy" to 10765, "mystery" to 9648, "scifi" to 10765, "thriller" to 9648, "war" to 10768, "western" to 37)
    private val movieWords = setOf("movie", "movies", "film", "films")
    private val seriesWords = setOf("series", "show", "shows", "tv", "serial", "serials")
    private val stop = setOf("the", "a", "an", "and", "of", "in", "for", "with", "some", "good", "me", "to", "on")
    private val year = Regex("(19|20)\\d{2}")
    private val decade = Regex("(\\d{2}|\\d{4})s")

    fun genreId(name: String, tv: Boolean): Int? = (if (tv) tvGenre else movieGenre)[name]

    fun words(q: String): List<String> = q.lowercase().replace(Regex("[^a-z0-9\\- ]"), " ").split(' ').filter { it.isNotBlank() }

    /** Null when the text is not recognisably a themed search (then only the normal text search runs). */
    fun parse(q: String): ThemeQuery? {
        val kinds = LinkedHashSet<Kind>(); val genres = LinkedHashSet<String>(); val keywords = ArrayList<String>()
        var lang: String? = null; var country: String? = null; var langWord: String? = null; var countryWord: String? = null
        var from: Int? = null; var to: Int? = null; var sort = "popular"
        for (w in words(q)) when {
            w in movieWords -> kinds += Kind.MOVIE
            w in seriesWords -> kinds += Kind.SERIES
            w in langs -> { if (lang == null) { lang = langs[w]; langWord = w } }
            w in countries -> { if (country == null) { country = countries[w]; countryWord = w } }
            w in genreWords -> genres += genreWords[w]!!
            year.matches(w) -> { from = w.toInt(); to = w.toInt() }
            decade.matches(w) -> {
                val n = w.dropLast(1).toInt()
                val start = if (w.length == 3) (if (n >= 30) 1900 + n else 2000 + n) else n
                from = start; to = start + 9
            }
            w in setOf("new", "latest", "recent") -> sort = "date"
            w in setOf("best", "top", "greatest") -> sort = "rating"
            w in stop -> {}
            w.length >= 3 -> keywords += w
        }
        if (genres.isEmpty() && lang == null && country == null && from == null && keywords.isEmpty()) return null
        val label = listOfNotNull(
            countryWord?.replaceFirstChar { it.uppercase() }, langWord?.replaceFirstChar { it.uppercase() },
            genres.joinToString(" · ") { it.replaceFirstChar { c -> c.uppercase() } }.ifEmpty { null },
            keywords.joinToString(" ").replaceFirstChar { it.uppercase() }.ifEmpty { null },
            from?.let { if (to != null && to != from) "$from–$to" else "$from" },
        ).joinToString(" · ")
        return ThemeQuery(kinds, genres.toList(), lang, country, keywords, from, to, sort, label)
    }
}
