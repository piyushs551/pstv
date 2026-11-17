package com.pstv.tv

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class SettingsActivity : BaseActivity() {
    private lateinit var list: LinearLayout
    private val values = ArrayList<() -> Unit>()

    private val repoListener: () -> Unit = { refreshValues() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_settings)
        list = findViewById(R.id.list)
        build()
        Repo.listeners.add(repoListener)
        list.getChildAt(0)?.requestFocus()
    }

    override fun onDestroy() { Repo.listeners.remove(repoListener); super.onDestroy() }

    private fun refreshValues() = values.forEach { it() }

    private fun section(t: String) {
        list.addView(TextView(this).apply {
            text = t.uppercase(); letterSpacing = 0.08f; typeface = Fonts.medium(); setTextColor(getColor(R.color.text_dim)); type(R.dimen.type_caption); setPadding(dp(this@SettingsActivity, 4), dp(this@SettingsActivity, 14), 0, dp(this@SettingsActivity, 4))
        })
    }

    private fun row(title: String, value: () -> String, onClick: () -> Unit) {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            setBackgroundResource(R.drawable.row_bg)
            setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(context, 5) }
        }
        val t = TextView(this).apply { text = title; typeface = Fonts.medium(); setTextColor(getColor(R.color.text)); type(R.dimen.type_body) }
        val s = TextView(this).apply { setTextColor(getColor(R.color.text_dim)); type(R.dimen.type_caption); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        v.addView(t); v.addView(s)
        val update = { s.text = value() }
        update(); values += update
        v.setOnClickListener { onClick(); refreshValues() }
        list.addView(v)
    }

    private fun edit(title: String, cur: String, password: Boolean = false, hint: String = "", save: (String) -> Unit) {
        val et = EditText(this).apply {
            setText(cur); setSingleLine(); this.hint = hint
            inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(getColor(R.color.text))
        }
        AlertDialog.Builder(this, R.style.Theme_Pstv_Dialog).setTitle(title).setView(et)
            .setPositiveButton("Save") { _, _ -> save(et.text.toString()); refreshValues() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun <T> choose(title: String, options: List<Pair<String, T>>, cur: T, save: (T) -> Unit) {
        AlertDialog.Builder(this, R.style.Theme_Pstv_Dialog).setTitle(title)
            .setSingleChoiceItems(options.map { it.first }.toTypedArray(), options.indexOfFirst { it.second == cur }) { d, i -> save(options[i].second); d.dismiss(); refreshValues() }
            .show()
    }

    private fun mask(s: String) = if (s.isEmpty()) "Not set" else "••••••••"
    /** "Built in" when the key comes from the build (keys.properties) and nothing is typed here. */
    private fun keyLabel(user: String, built: String) = if (user.isNotEmpty()) "••••••••  (set here)" else if (built.isNotEmpty()) "Built in" else "Not set"
    private fun orNotSet(s: String) = s.ifEmpty { "Not set" }

    private fun build() {
        section("PLAYLIST  (your own provider; the app ships no content)")
        row("Source type", { if (Prefs.sourceType == "xtream") "Xtream login" else "M3U URL" }) {
            Prefs.sourceType = if (Prefs.sourceType == "xtream") "m3u" else "xtream"
        }
        row("M3U URL", { orNotSet(Prefs.m3uUrl) }) { edit("M3U URL", Prefs.m3uUrl, hint = "http://…/playlist.m3u") { Prefs.m3uUrl = it } }
        row("Xtream server", { orNotSet(Prefs.xHost) }) { edit("Xtream server", Prefs.xHost, hint = "http://host:port") { Prefs.xHost = it } }
        row("Xtream username", { orNotSet(Prefs.xUser) }) { edit("Xtream username", Prefs.xUser) { Prefs.xUser = it } }
        row("Xtream password", { mask(Prefs.xPass) }) { edit("Xtream password", Prefs.xPass, password = true) { Prefs.xPass = it } }
        row("Refresh playlist now", {
            when {
                Repo.refreshing -> "Updating…"
                Repo.status.isNotEmpty() -> Repo.status
                Prefs.lastRefresh == 0L -> "Never refreshed  •  ${Db.count()} items"
                else -> "Updated ${android.text.format.DateUtils.getRelativeTimeSpanString(Prefs.lastRefresh)}  •  ${Db.count()} items"
            }
        }) {
            if (!Prefs.hasSource) toast("Enter your M3U URL or Xtream login first") else Repo.refresh()
        }

        row("Auto refresh playlist", { if (Prefs.refreshHours == 0) "Manual only" else "Every ${Prefs.refreshHours} hours" }) {
            choose("Auto refresh", listOf("Manual only" to 0, "Every 1 hour" to 1, "Every 6 hours" to 6, "Every 12 hours" to 12, "Every 24 hours" to 24), Prefs.refreshHours) { Prefs.refreshHours = it }
        }
        row("User-Agent", { Prefs.ua }) { edit("User-Agent sent to your provider", Prefs.ua) { Prefs.ua = it } }

        section("GUIDE (EPG)")
        row("XMLTV URL", { Prefs.epgUrl.ifEmpty { "Automatic (Xtream login or the playlist's url-tvg)" } }) { edit("XMLTV URL (blank = automatic)", Prefs.epgUrl, hint = "http://…/xmltv.php") { Prefs.epgUrl = it } }
        row("Refresh guide now", {
            when {
                Epg.refreshing -> "Updating…"
                Epg.status.isNotEmpty() -> Epg.status
                Prefs.epgLast == 0L -> "Never refreshed"
                else -> "Updated ${android.text.format.DateUtils.getRelativeTimeSpanString(Prefs.epgLast)}  •  ${Db.epgCount()} programmes"
            }
        }) { if (Epg.url().isBlank()) toast("Set an XMLTV URL first") else Epg.refresh() }
        row("Guide shows", { if (Prefs.epgOnlyWithData) "Only channels with guide data" else "All live channels" }) { Prefs.epgOnlyWithData = !Prefs.epgOnlyWithData }

        section("APPEARANCE")
        row("UI size", { "${Prefs.uiScale}%  (smaller = more on screen)" }) {
            choose("UI size", listOf("Compact · 75%" to 75, "Compact · 85% (default)" to 85, "Medium · 100%" to 100, "Large · 115%" to 115), Prefs.uiScale) { Prefs.uiScale = it; recreate() }
        }
        row("Animations", { listOf("Off", "Fast", "Normal")[Prefs.anim] }) { choose("Animations (focus zoom and banner fade)", listOf("Off" to 0, "Fast" to 1, "Normal" to 2), Prefs.anim) { Prefs.anim = it } }
        row("Hero banner", { if (Prefs.hero) "On" else "Off (rows only, uses less memory)" }) { Prefs.hero = !Prefs.hero }
        row("Switch tabs when focused", { if (Prefs.tabFocusSwitch) "On" else "Off (press OK to switch)" }) { Prefs.tabFocusSwitch = !Prefs.tabFocusSwitch }
        row("Home rows", { "Choose and reorder" }) { homeRowsDialog() }

        section("ARTWORK & PLOTS")
        row("TMDB API key", { keyLabel(Prefs.tmdbKeyUser, BuildConfig.TMDB_KEY) }) { edit("TMDB API key (v3). Leave empty to use the built-in key", Prefs.tmdbKeyUser, password = true) { Prefs.tmdbKeyUser = it; Prefs.trendingAt = 0 } }
        row("Image cache size", { "${Prefs.cacheMb} MB   (used ${Images.diskBytes() / 1_048_576} MB)" }) {
            choose("Image cache size", listOf("50 MB" to 50, "100 MB" to 100, "200 MB" to 200, "500 MB" to 500), Prefs.cacheMb) { Prefs.cacheMb = it; Images.rebuild() }
        }
        row("Clear image cache", { "Frees ${Images.diskBytes() / 1_048_576} MB" }) { Images.clear(); toast("Image cache cleared") }

        section("PLAYER · LIVE")
        row("Player engine", { engName(Prefs.engineLive) }) { choose("Live player engine", engines, Prefs.engineLive) { Prefs.engineLive = it } }
        row("Low latency", { if (Prefs.lowLatency) "On (smaller buffer, faster start, less tolerance for a weak connection)" else "Off" }) { Prefs.lowLatency = !Prefs.lowLatency }

        section("PLAYER · VOD (movies and series)")
        row("Player engine", { engName(Prefs.engineVod) }) { choose("VOD player engine", engines, Prefs.engineVod) { Prefs.engineVod = it } }
        row("Fast-forward step", { "${Prefs.ffStep} seconds" }) { choose("Fast-forward step", listOf(5, 10, 30, 60).map { "$it seconds" to it }, Prefs.ffStep) { Prefs.ffStep = it } }
        row("Rewind step", { "${Prefs.rwStep} seconds" }) { choose("Rewind step", listOf(5, 10, 30, 60).map { "$it seconds" to it }, Prefs.rwStep) { Prefs.rwStep = it } }

        section("PLAYER · BOTH")
        row("MPV engine status", { if (Prefs.mpvBroken) "Switched off: the app crashed while MPV was running. Tap to re-enable and try again" else "Available" }) { Prefs.mpvBroken = false; Prefs.mpvPending = false }
        row("Auto fallback", { if (Prefs.autoFallback) "On: if a stream errors or shows nothing for 15 s, retry it with the other engine" else "Off" }) { Prefs.autoFallback = !Prefs.autoFallback }
        row("Match framerate", { if (Prefs.matchFps) "On (asks the TV for a matching refresh rate if it offers one)" else "Off" }) { Prefs.matchFps = !Prefs.matchFps }
        section("SUBTITLES")
        row("OpenSubtitles API key", { keyLabel(Prefs.osKeyUser, BuildConfig.OS_KEY) }) { edit("OpenSubtitles API key. Leave empty to use the built-in key", Prefs.osKeyUser, password = true) { Prefs.osKeyUser = it } }
        row("OpenSubtitles username", { if (Prefs.osUserUser.isNotEmpty()) Prefs.osUserUser else if (BuildConfig.OS_USER.isNotEmpty()) "Built in" else "Not set" }) { edit("OpenSubtitles username. Leave empty to use the built-in one", Prefs.osUserUser) { Prefs.osUserUser = it } }
        row("OpenSubtitles password", { keyLabel(Prefs.osPassUser, BuildConfig.OS_PASS) }) { edit("OpenSubtitles password. Leave empty to use the built-in one", Prefs.osPassUser, password = true) { Prefs.osPassUser = it } }
        row("Subtitle language", { Prefs.subLang }) { edit("Language code (e.g. en, es, fr)", Prefs.subLang) { Prefs.subLang = it.lowercase().ifEmpty { "en" } } }
        row("Subtitle size", { "${Prefs.subSize}%" }) { choose("Subtitle size", listOf(75, 100, 125, 150, 200).map { "$it%" to it }, Prefs.subSize) { Prefs.subSize = it } }
        row("Subtitle colour", { colorName(Prefs.subColor) }) { choose("Subtitle colour", colors, Prefs.subColor) { Prefs.subColor = it } }
        row("Subtitle delay", { delayLabel(Prefs.subDelayMs) }) {
            choose("Subtitle delay (applies to downloaded subtitles)", delays.map { delayLabel(it) to it }, Prefs.subDelayMs) { Prefs.subDelayMs = it }
        }

        section("ABOUT")
        row("PSTV", { "Version ${packageManager.getPackageInfo(packageName, 0).versionName}" }) {}
    }

    private val rowNames = linkedMapOf(
        "recent" to "Continue Watching", "trendm" to "Today's trending movies", "trends" to "Today's trending series", "favs" to "Favorites",
        "livehist" to "Recently watched channels", "newm" to "Recently added (movies)", "news" to "Recently added (series)",
    )

    /** Tap a row to move it or show/hide it. Takes effect next time Home is opened. */
    private fun homeRowsDialog() {
        val keys = Prefs.homeRows.split(',').filter { it.isNotBlank() }.toMutableList()
        for (k in rowNames.keys) if (keys.none { it.removePrefix("!") == k }) keys += "!$k"
        keys.retainAll { it.removePrefix("!") in rowNames }
        val labels = keys.map { (if (it.startsWith("!")) "✗   " else "✓   ") + rowNames[it.removePrefix("!")] }.toTypedArray()
        AlertDialog.Builder(this, R.style.Theme_Pstv_Dialog).setTitle("Home rows (✓ shown, ✗ hidden)").setItems(labels) { _, i ->
            AlertDialog.Builder(this, R.style.Theme_Pstv_Dialog).setTitle(rowNames[keys[i].removePrefix("!")])
                .setItems(arrayOf("Move up", "Move down", if (keys[i].startsWith("!")) "Show" else "Hide")) { _, a ->
                    when (a) {
                        0 -> if (i > 0) { val k = keys.removeAt(i); keys.add(i - 1, k) }
                        1 -> if (i < keys.size - 1) { val k = keys.removeAt(i); keys.add(i + 1, k) }
                        2 -> keys[i] = if (keys[i].startsWith("!")) keys[i].removePrefix("!") else "!" + keys[i]
                    }
                    Prefs.homeRows = keys.joinToString(",")
                    homeRowsDialog()
                }.show()
        }.show()
    }

    private val engines = listOf("ExoPlayer (lightweight, default)" to "exo", "MPV (plays more formats and audio tracks)" to "mpv")
    private fun engName(k: String) = engines.first { it.second == k }.first

    companion object {
        val colors = listOf("White" to 0xFFFFFFFF.toInt(), "Yellow" to 0xFFFFE94D.toInt(), "Cyan" to 0xFF5CE1FF.toInt(), "Green" to 0xFF7DFF8A.toInt())
        val delays = (-4000..4000 step 250).toList()
        fun colorName(c: Int) = colors.firstOrNull { it.second == c }?.first ?: "Custom"
        fun delayLabel(ms: Int) = if (ms == 0) "0 ms (in sync)" else "${if (ms > 0) "+" else ""}$ms ms"
    }
}
