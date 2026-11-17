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

/** TMDB: suggestions (trending / popular / top rated, kept only if the playlist has them), art, plots, episode stills. */
object Tmdb {
    private const val API = "https://api.themoviedb.org/3"
    private const val IMG = "https://image.tmdb.org/t/p"
    private const val TTL = 12 * 60 * 60 * 1000L

    data class Title(val name: String, val year: Int?, val overview: String?, val poster: String?, val backdrop: String?, val rating: Float? = null, val genres: String? = null, val alt: String? = null)
    data class EpMeta(val name: String?, val overview: String?, val still: String?, val runtime: Int?)

    private val genreNames = mapOf(
        28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime", 99 to "Documentary", 18 to "Drama",
        10751 to "Family", 14 to "Fantasy", 36 to "History", 27 to "Horror", 10402 to "Music", 9648 to "Mystery", 10749 to "Romance",
        878 to "Science Fiction", 10770 to "TV Movie", 53 to "Thriller", 10752 to "War", 37 to "Western", 10759 to "Action & Adventure",
        10762 to "Kids", 10763 to "News", 10764 to "Reality", 10765 to "Sci-Fi & Fantasy", 10766 to "Soap", 10767 to "Talk", 10768 to "War & Politics",
    )

    val enabled get() = Prefs.tmdbKey.isNotEmpty()
    private fun key() = URLEncoder.encode(Prefs.tmdbKey, "UTF-8")

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
                o.optDouble("vote_average", 0.0).takeIf { it > 0 }?.toFloat(),
                o.optJSONArray("genre_ids")?.let { g -> (0 until minOf(g.length(), 3)).mapNotNull { genreNames[g.optInt(it)] }.joinToString(", ").ifEmpty { null } },
                o.optString(if (tv) "original_name" else "original_title").takeIf { it.isNotBlank() && it != name },
            )
        }
    }

    /** A TMDB list (cached 12 h, several pages merged). Falls back to the cache if the network fails. */
    private fun listJson(path: String, pages: Int): JSONArray? {
        val ck = "tmdb_" + path.replace('/', '_')
        val cached = Prefs.rawGet(ck)
        if (cached.isNotEmpty() && System.currentTimeMillis() - Prefs.rawGetLong(ck + "_at") < TTL) return JSONArray(cached)
        return try {
            val merged = JSONArray()
            for (p in 1..pages) {
                val arr = JSONObject(Http.getString("$API/$path?api_key=${key()}&page=$p")).optJSONArray("results") ?: break
                for (i in 0 until arr.length()) merged.put(arr.get(i))
            }
            Prefs.rawPut(ck, merged.toString()); Prefs.rawPutLong(ck + "_at", System.currentTimeMillis())
            merged
        } catch (_: Exception) { if (cached.isNotEmpty()) JSONArray(cached) else null }
    }

    /** Titles of one TMDB list that exist in the user's playlist, in TMDB order; art/plot are stored on the matching rows. */
    fun listInPlaylist(path: String, kind: Kind, pages: Int): List<Item> {
        if (!enabled) return emptyList()
        val tv = kind == Kind.SERIES
        val titles = parse(listJson(path, pages), tv)
        val out = ArrayList<Item>(); val seen = HashSet<Long>()
        for (t in titles) {
            val hit = Db.matchTitle(normTitle(t.name), kind, t.year) ?: t.alt?.let { Db.matchTitle(normTitle(it), kind, t.year) } ?: continue
            if (!seen.add(hit.id)) continue
            Db.saveMeta(hit, t.year, t.overview, t.backdrop, t.poster, t.rating, t.genres)
            out += Db.byId(hit.id) ?: hit
        }
        return out
    }

    fun trendingInPlaylist(kind: Kind) = listInPlaylist(if (kind == Kind.SERIES) "trending/tv/week" else "trending/movie/week", kind, 1)
    fun popularInPlaylist(kind: Kind) = listInPlaylist(if (kind == Kind.SERIES) "tv/popular" else "movie/popular", kind, 3)
    fun topRatedInPlaylist(kind: Kind) = listInPlaylist(if (kind == Kind.SERIES) "tv/top_rated" else "movie/top_rated", kind, 3)

    fun lookup(item: Item): Title? {
        if (!enabled) return null
        val tv = item.kind == Kind.SERIES
        val q = URLEncoder.encode(cleanTitle(item.name), "UTF-8")
        val yearParam = item.year?.let { "&${if (tv) "first_air_date_year" else "year"}=$it" } ?: ""
        val res = JSONObject(Http.getString("$API/search/${if (tv) "tv" else "movie"}?api_key=${key()}&query=$q$yearParam")).optJSONArray("results")
        val titles = parse(res, tv)
        val want = normTitle(item.name)
        return titles.firstOrNull { normTitle(it.name) == want } ?: titles.firstOrNull()
    }

    /** For Settings: says plainly whether the key works. */
    fun test(): String {
        if (!enabled) return "No TMDB key set"
        return try { Http.getString("$API/configuration?api_key=${key()}"); "TMDB key works" }
        catch (e: Exception) { if (e.message?.contains("401") == true) "TMDB rejected the key (use the v3 API Key, not the Read Access Token)" else "Could not reach TMDB: ${e.message}" }
    }

    // ---- episodes (stills, plots) --------------------------------------------------------------
    private val tvIds = ConcurrentHashMap<String, Int>()

    private fun tvId(item: Item): Int? {
        tvIds[item.url]?.let { return it }
        val q = URLEncoder.encode(cleanTitle(item.name), "UTF-8")
        val yearParam = item.year?.let { "&first_air_date_year=$it" } ?: ""
        val arr = JSONObject(Http.getString("$API/search/tv?api_key=${key()}&query=$q$yearParam")).optJSONArray("results") ?: return null
        val want = normTitle(item.name)
        var id: Int? = null
        for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); if (normTitle(o.optString("name")) == want) { id = o.optInt("id"); break } }
        id = id ?: arr.optJSONObject(0)?.optInt("id")
        id?.let { tvIds[item.url] = it }
        return id
    }

    fun seasonEpisodes(item: Item, season: Int): Map<Int, EpMeta> {
        if (!enabled) return emptyMap()
        val id = tvId(item) ?: return emptyMap()
        val eps = JSONObject(Http.getString("$API/tv/$id/season/$season?api_key=${key()}")).optJSONArray("episodes") ?: return emptyMap()
        val out = HashMap<Int, EpMeta>()
        for (i in 0 until eps.length()) {
            val e = eps.getJSONObject(i)
            out[e.optInt("episode_number")] = EpMeta(
                e.optString("name").ifBlank { null }, e.optString("overview").ifBlank { null },
                e.optString("still_path").takeIf { it.startsWith("/") }?.let { "$IMG/w300$it" },
                e.optInt("runtime", 0).takeIf { it > 0 },
            )
        }
        return out
    }
}

/** Fills missing plot/art for one item in the background; never called on the UI path. */
object Enricher {
    private val main = Handler(Looper.getMainLooper())
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val missed = ConcurrentHashMap.newKeySet<String>()   // no TMDB match this session: do not ask again
    private val gate = Semaphore(2)

    fun ensure(item: Item, done: (Item) -> Unit) {
        if (!Tmdb.enabled || item.kind == Kind.LIVE || item.overview != null || item.url in missed) return
        if (!inFlight.add(item.url)) return
        Repo.scope.launch {
            try {
                gate.withPermit {
                    val t = Tmdb.lookup(item)
                    if (t != null) {
                        Db.saveMeta(item, t.year, t.overview, t.backdrop, t.poster, t.rating, t.genres)
                        Db.byId(item.id)?.let { fresh -> main.post { done(fresh) } }
                    } else missed += item.url
                }
            } catch (_: Exception) {
            } finally { inFlight.remove(item.url) }
        }
    }
}
