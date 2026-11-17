package com.pstv.tv

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder

/** OpenSubtitles REST API v1. The user supplies their own API key (and account, needed to download). */
object OpenSubs {
    private const val BASE = "https://api.opensubtitles.com/api/v1"
    private var token: String? = null
    private var tokenHost: String = "api.opensubtitles.com"

    data class Hit(val fileId: Int, val release: String, val lang: String, val downloads: Int)

    private fun headers() = mapOf("Api-Key" to Prefs.osKey, "Accept" to "application/json")

    fun search(title: String, year: Int?, season: Int?, episode: Int?): List<Hit> {
        require(Prefs.osKey.isNotEmpty()) { "Add your OpenSubtitles API key in Settings" }
        val q = URLEncoder.encode(cleanTitle(title).lowercase(), "UTF-8")
        var url = "$BASE/subtitles?query=$q&languages=${Prefs.subLang}"
        if (year != null && season == null) url += "&year=$year"
        if (season != null) url += "&season_number=$season"
        if (episode != null) url += "&episode_number=$episode"
        val data = JSONObject(Http.getString(url, headers())).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val a = data.getJSONObject(i).optJSONObject("attributes") ?: return@mapNotNull null
            val file = a.optJSONArray("files")?.optJSONObject(0) ?: return@mapNotNull null
            Hit(file.optInt("file_id"), a.optString("release", file.optString("file_name", "subtitle")), a.optString("language"), a.optInt("download_count"))
        }.sortedByDescending { it.downloads }.take(25)
    }

    private fun login() {
        require(Prefs.osUser.isNotEmpty() && Prefs.osPass.isNotEmpty()) { "Add your OpenSubtitles username and password in Settings" }
        val body = JSONObject().put("username", Prefs.osUser).put("password", Prefs.osPass).toString()
            .toRequestBody("application/json".toMediaType())
        val req = Http.req("$BASE/login").apply { headers().forEach { (k, v) -> header(k, v) } }.post(body).build()
        Http.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("Login failed (HTTP ${r.code})")
            val o = JSONObject(r.body!!.string())
            token = o.optString("token").ifBlank { error("Login failed") }
            tokenHost = o.optString("base_url").ifBlank { "api.opensubtitles.com" }
        }
    }

    /** Returns the subtitle as SRT text. */
    fun download(fileId: Int): String {
        if (token == null) login()
        fun call(): String {
            val body = JSONObject().put("file_id", fileId).toString().toRequestBody("application/json".toMediaType())
            val req = Http.req("https://$tokenHost/api/v1/download").apply {
                headers().forEach { (k, v) -> header(k, v) }
                header("Authorization", "Bearer $token")
            }.post(body).build()
            Http.client.newCall(req).execute().use { r ->
                if (r.code == 401) { token = null; error("401") }
                if (!r.isSuccessful) error("Download failed (HTTP ${r.code})")
                return JSONObject(r.body!!.string()).optString("link").ifBlank { error("No download link (quota reached?)") }
            }
        }
        val link = try { call() } catch (e: IllegalStateException) { if (e.message == "401") { login(); call() } else throw e }
        return Http.getString(link)
    }
}

/** Shifts every SRT timestamp by [delayMs] (positive = later). */
object Srt {
    private val ts = Regex("""(\d{2}):(\d{2}):(\d{2})[,.](\d{3})""")

    fun shift(text: String, delayMs: Int): String {
        if (delayMs == 0) return text
        return ts.replace(text) { m ->
            val (h, mi, s, ms) = m.destructured
            var t = ((h.toLong() * 60 + mi.toLong()) * 60 + s.toLong()) * 1000 + ms.toLong() + delayMs
            if (t < 0) t = 0
            "%02d:%02d:%02d,%03d".format(t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, t % 1000)
        }
    }
}
