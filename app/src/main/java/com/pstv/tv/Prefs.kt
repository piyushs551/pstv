package com.pstv.tv

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private lateinit var sp: SharedPreferences
    fun init(c: Context) { sp = c.getSharedPreferences("pstv", Context.MODE_PRIVATE) }

    private fun s(k: String, d: String = "") = sp.getString(k, d) ?: d
    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()
    private fun i(k: String, d: Int) = sp.getInt(k, d)
    private fun put(k: String, v: Int) = sp.edit().putInt(k, v).apply()

    /** "m3u" or "xtream" */
    var sourceType: String get() = s("source", "m3u"); set(v) = put("source", v)
    var m3uUrl: String get() = s("m3u"); set(v) = put("m3u", v.trim())
    var xHost: String get() = s("xhost"); set(v) = put("xhost", v.trim().trimEnd('/'))
    var xUser: String get() = s("xuser"); set(v) = put("xuser", v.trim())
    var xPass: String get() = s("xpass"); set(v) = put("xpass", v.trim())

    var tmdbKey: String get() = s("tmdb"); set(v) = put("tmdb", v.trim())
    var osKey: String get() = s("oskey"); set(v) = put("oskey", v.trim())
    var osUser: String get() = s("osuser"); set(v) = put("osuser", v.trim())
    var osPass: String get() = s("ospass"); set(v) = put("ospass", v)
    var subLang: String get() = s("sublang", "en"); set(v) = put("sublang", v.trim())

    /** Seek step in seconds: 5, 10, 30 or 60. */
    var seekStep: Int get() = i("seek", 10); set(v) = put("seek", v)
    var cacheMb: Int get() = i("cachemb", 100); set(v) = put("cachemb", v)
    /** 0 Fit, 1 Fill, 2 Zoom, 3 16:9, 4 4:3 */
    var aspect: Int get() = i("aspect", 0); set(v) = put("aspect", v)
    /** Subtitle text size in percent of default. */
    var subSize: Int get() = i("subsize", 100); set(v) = put("subsize", v)
    var subColor: Int get() = i("subcolor", 0xFFFFFFFF.toInt()); set(v) = put("subcolor", v)
    var subDelayMs: Int get() = i("subdelay", 0); set(v) = put("subdelay", v)

    var lastRefresh: Long get() = sp.getLong("lastrefresh", 0); set(v) = sp.edit().putLong("lastrefresh", v).apply()
    var trendingJson: String get() = s("trending"); set(v) = put("trending", v)
    var trendingAt: Long get() = sp.getLong("trendingat", 0); set(v) = sp.edit().putLong("trendingat", v).apply()

    val hasSource: Boolean
        get() = if (sourceType == "xtream") xHost.isNotEmpty() && xUser.isNotEmpty() else m3uUrl.isNotEmpty()
}
