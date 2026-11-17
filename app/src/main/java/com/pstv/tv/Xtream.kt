package com.pstv.tv

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONObject
import java.io.InputStreamReader

/** Xtream Codes API client. Big lists are read with a streaming JsonReader to keep RAM flat. */
object Xtream {
    private fun base() = Prefs.xHost.let { if (it.startsWith("http")) it else "http://$it" }
    private fun api(action: String, extra: String = "") =
        "${base()}/player_api.php?username=${enc(Prefs.xUser)}&password=${enc(Prefs.xPass)}&action=$action$extra"

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    fun xmltvUrl() = "${base()}/xmltv.php?username=${enc(Prefs.xUser)}&password=${enc(Prefs.xPass)}"
    fun liveUrl(id: String) = "${base()}/live/${Prefs.xUser}/${Prefs.xPass}/$id.m3u8"
    fun movieUrl(id: String, ext: String) = "${base()}/movie/${Prefs.xUser}/${Prefs.xPass}/$id.$ext"
    fun episodeUrl(id: String, ext: String) = "${base()}/series/${Prefs.xUser}/${Prefs.xPass}/$id.$ext"

    private fun stream(url: String, block: (JsonReader) -> Unit) {
        Http.client.newCall(Http.req(url).build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            JsonReader(InputStreamReader(r.body!!.byteStream(), Charsets.UTF_8)).use(block)
        }
    }

    /** Reads an array of flat objects, calling [each] with string-valued fields. */
    private fun arrayOfObjects(r: JsonReader, each: (Map<String, String>) -> Unit) {
        if (r.peek() != JsonToken.BEGIN_ARRAY) { r.skipValue(); return }
        r.beginArray()
        while (r.hasNext()) {
            if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
            val m = HashMap<String, String>(16)
            r.beginObject()
            while (r.hasNext()) {
                val k = r.nextName()
                when (r.peek()) {
                    JsonToken.STRING, JsonToken.NUMBER -> m[k] = r.nextString()
                    JsonToken.BEGIN_ARRAY -> { // e.g. backdrop_path: ["url"]
                        r.beginArray()
                        if (r.hasNext() && r.peek() == JsonToken.STRING) m[k] = r.nextString()
                        while (r.hasNext()) r.skipValue()
                        r.endArray()
                    }
                    else -> r.skipValue()
                }
            }
            r.endObject()
            each(m)
        }
        r.endArray()
    }

    private fun categories(action: String): Map<String, String> {
        val out = HashMap<String, String>()
        stream(api(action)) { r -> arrayOfObjects(r) { m -> out[m["category_id"] ?: return@arrayOfObjects] = m["category_name"] ?: "Other" } }
        return out
    }

    fun verify(): String {
        val o = JSONObject(Http.getString(api("")))
        val info = o.optJSONObject("user_info") ?: error("Login failed")
        if (info.optInt("auth", 0) != 1) error("Login rejected")
        return info.optString("status", "ok")
    }

    fun load(sink: (Item) -> Unit) {
        val liveCats = categories("get_live_categories")
        stream(api("get_live_streams")) { r ->
            arrayOfObjects(r) { m ->
                val id = m["stream_id"] ?: return@arrayOfObjects
                sink(Item(0, Kind.LIVE, liveCats[m["category_id"]] ?: "Live", m["name"] ?: id, m["stream_icon"]?.ifBlank { null }, liveUrl(id), tvg = m["epg_channel_id"]?.trim()?.lowercase()?.ifBlank { null }))
            }
        }
        val vodCats = categories("get_vod_categories")
        stream(api("get_vod_streams")) { r ->
            arrayOfObjects(r) { m ->
                val id = m["stream_id"] ?: return@arrayOfObjects
                val ext = m["container_extension"]?.ifBlank { null } ?: "mp4"
                sink(Item(0, Kind.MOVIE, vodCats[m["category_id"]] ?: "Movies", m["name"] ?: id, m["stream_icon"]?.ifBlank { null }, movieUrl(id, ext), year = m["year"]?.take(4)?.toIntOrNull()))
            }
        }
        val serCats = categories("get_series_categories")
        stream(api("get_series")) { r ->
            arrayOfObjects(r) { m ->
                val id = m["series_id"] ?: return@arrayOfObjects
                sink(Item(0, Kind.SERIES, serCats[m["category_id"]] ?: "Series", m["name"] ?: id, m["cover"]?.ifBlank { null }, "xs:$id",
                    year = (m["year"] ?: m["releaseDate"])?.take(4)?.toIntOrNull(), overview = null))
            }
        }
    }

    data class Episode(val season: Int, val number: Int, val title: String, val url: String)

    /** Episodes of one series, fetched on demand when the user opens it. */
    fun episodes(seriesUrl: String): List<Episode> {
        val id = seriesUrl.removePrefix("xs:")
        val o = JSONObject(Http.getString(api("get_series_info", "&series_id=$id")))
        val eps = o.optJSONObject("episodes") ?: return emptyList()
        val out = ArrayList<Episode>()
        for (season in eps.keys()) {
            val arr = eps.optJSONArray(season) ?: continue
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val ext = e.optString("container_extension", "mp4").ifBlank { "mp4" }
                out += Episode(season.toIntOrNull() ?: 0, e.optInt("episode_num", i + 1), e.optString("title", "Episode ${i + 1}"), episodeUrl(e.optString("id"), ext))
            }
        }
        return out.sortedWith(compareBy({ it.season }, { it.number }))
    }
}
