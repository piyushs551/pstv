package com.pstv.tv

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** TMDB (user's own free API key): trending, posters, backdrops and plots. */
object Tmdb {
    private const val API = "https://api.themoviedb.org/3"
    private const val IMG = "https://image.tmdb.org/t/p"
    private const val TTL = 12 * 60 * 60 * 1000L

    data class Title(val name: String, val year: Int?, val overview: String?, val poster: String?, val backdrop: String?)

    val enabled get() = Prefs.tmdbKey.isNotEmpty()

    private fun parse(arr: JSONArray?, tv: Boolean): List<Title> {
        arr ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString(if (tv) "name" else "title").ifBlank { return@mapNotNull null }
            val date = o.optString(if (tv) "first_air_date" else "release_date")
            Title(
                name, date.take(4).toIntOrNull(), o.optString("overview").ifBlank { null },
                o.optString("poster_path").takeIf { it.startsWith("/") }?.let { "$IMG/w342$it" },
                o.optString("backdrop_path").takeIf { it.startsWith("/") }?.let { "$IMG/w1280$it" },
            )
        }
    }

    /** Trending titles that exist in the user's playlist, with TMDB art/plot stored on the matching rows. */
    fun trendingInPlaylist(): List<Item> {
        if (!enabled) return emptyList()
        val now = System.currentTimeMillis()
        var json = Prefs.trendingJson
        if (json.isEmpty() || now - Prefs.trendingAt > TTL) {
            try {
                val k = URLEncoder.encode(Prefs.tmdbKey, "UTF-8")
                val m = JSONObject(Http.getString("$API/trending/movie/week?api_key=$k")).optJSONArray("results")
                val t = JSONObject(Http.getString("$API/trending/tv/week?api_key=$k")).optJSONArray("results")
                json = JSONObject().put("m", m).put("t", t).toString()
                Prefs.trendingJson = json; Prefs.trendingAt = now
            } catch (_: Exception) { if (json.isEmpty()) return emptyList() }
        }
        val o = JSONObject(json)
        val out = ArrayList<Item>()
        val seen = HashSet<Long>()
        fun match(titles: List<Title>, kind: Kind) {
            for (t in titles) {
                val hit = Db.matchTitle(normTitle(t.name), kind, t.year) ?: continue
                if (!seen.add(hit.id)) continue
                Db.saveMeta(hit, t.year, t.overview, t.backdrop, t.poster)
                out += Db.byId(hit.id) ?: hit
            }
        }
        val movies = parse(o.optJSONArray("m"), false)
        val shows = parse(o.optJSONArray("t"), true)
        // Interleave so both kinds show up near the front.
        val a = ArrayList<Item>(); val b = ArrayList<Item>()
        val keep = out
        match(movies, Kind.MOVIE); a.addAll(keep); keep.clear()
        match(shows, Kind.SERIES); b.addAll(keep)
        val res = ArrayList<Item>()
        for (i in 0 until maxOf(a.size, b.size)) { a.getOrNull(i)?.let(res::add); b.getOrNull(i)?.let(res::add) }
        return res
    }

    fun lookup(item: Item): Title? {
        if (!enabled) return null
        val tv = item.kind == Kind.SERIES
        val q = URLEncoder.encode(cleanTitle(item.name), "UTF-8")
        val yearParam = item.year?.let { "&${if (tv) "first_air_date_year" else "year"}=$it" } ?: ""
        val k = URLEncoder.encode(Prefs.tmdbKey, "UTF-8")
        val res = JSONObject(Http.getString("$API/search/${if (tv) "tv" else "movie"}?api_key=$k&query=$q$yearParam")).optJSONArray("results")
        val titles = parse(res, tv)
        val want = normTitle(item.name)
        return titles.firstOrNull { normTitle(it.name) == want } ?: titles.firstOrNull()
    }
}

/** Fills missing plot/art for one item in the background; never called on the UI path. */
object Enricher {
    private val main = Handler(Looper.getMainLooper())
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val gate = Semaphore(2)

    fun ensure(item: Item, done: (Item) -> Unit) {
        if (!Tmdb.enabled || item.kind == Kind.LIVE || item.overview != null) return
        if (!inFlight.add(item.url)) return
        Repo.scope.launch {
            try {
                gate.withPermit {
                    val t = Tmdb.lookup(item)
                    if (t != null) {
                        Db.saveMeta(item, t.year, t.overview, t.backdrop, t.poster)
                        Db.byId(item.id)?.let { fresh -> main.post { done(fresh) } }
                    }
                }
            } catch (_: Exception) {
            } finally { inFlight.remove(item.url) }
        }
    }
}
