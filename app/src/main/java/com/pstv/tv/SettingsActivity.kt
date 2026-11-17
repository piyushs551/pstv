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
            text = t; setTextColor(getColor(R.color.accent)); textSize = 13f; setPadding(dp(this@SettingsActivity, 4), dp(this@SettingsActivity, 14), 0, dp(this@SettingsActivity, 4))
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
        val t = TextView(this).apply { text = title; setTextColor(getColor(R.color.text)); textSize = 15f }
        val s = TextView(this).apply { setTextColor(getColor(R.color.text_dim)); textSize = 12f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
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

        section("ARTWORK & PLOTS")
        row("TMDB API key", { mask(Prefs.tmdbKey) }) { edit("TMDB API key (v3)", Prefs.tmdbKey, password = true) { Prefs.tmdbKey = it; Prefs.trendingAt = 0 } }
        row("Image cache size", { "${Prefs.cacheMb} MB   (used ${Images.diskBytes() / 1_048_576} MB)" }) {
            choose("Image cache size", listOf("50 MB" to 50, "100 MB" to 100, "200 MB" to 200, "500 MB" to 500), Prefs.cacheMb) { Prefs.cacheMb = it; Images.rebuild() }
        }
        row("Clear image cache", { "Frees ${Images.diskBytes() / 1_048_576} MB" }) { Images.clear(); toast("Image cache cleared") }

        section("PLAYBACK")
        row("Seek step", { "${Prefs.seekStep} seconds" }) {
            choose("Seek step", listOf(5, 10, 30, 60).map { "$it seconds" to it }, Prefs.seekStep) { Prefs.seekStep = it }
        }

        section("SUBTITLES")
        row("OpenSubtitles API key", { mask(Prefs.osKey) }) { edit("OpenSubtitles API key", Prefs.osKey, password = true) { Prefs.osKey = it } }
        row("OpenSubtitles username", { orNotSet(Prefs.osUser) }) { edit("OpenSubtitles username", Prefs.osUser) { Prefs.osUser = it } }
        row("OpenSubtitles password", { mask(Prefs.osPass) }) { edit("OpenSubtitles password", Prefs.osPass, password = true) { Prefs.osPass = it } }
        row("Subtitle language", { Prefs.subLang }) { edit("Language code (e.g. en, es, fr)", Prefs.subLang) { Prefs.subLang = it.lowercase().ifEmpty { "en" } } }
        row("Subtitle size", { "${Prefs.subSize}%" }) { choose("Subtitle size", listOf(75, 100, 125, 150, 200).map { "$it%" to it }, Prefs.subSize) { Prefs.subSize = it } }
        row("Subtitle colour", { colorName(Prefs.subColor) }) { choose("Subtitle colour", colors, Prefs.subColor) { Prefs.subColor = it } }
        row("Subtitle delay", { delayLabel(Prefs.subDelayMs) }) {
            choose("Subtitle delay (applies to downloaded subtitles)", delays.map { delayLabel(it) to it }, Prefs.subDelayMs) { Prefs.subDelayMs = it }
        }

        section("ABOUT")
        row("PSTV", { "Version ${packageManager.getPackageInfo(packageName, 0).versionName}" }) {}
    }

    companion object {
        val colors = listOf("White" to 0xFFFFFFFF.toInt(), "Yellow" to 0xFFFFE94D.toInt(), "Cyan" to 0xFF5CE1FF.toInt(), "Green" to 0xFF7DFF8A.toInt())
        val delays = (-4000..4000 step 250).toList()
        fun colorName(c: Int) = colors.firstOrNull { it.second == c }?.first ?: "Custom"
        fun delayLabel(ms: Int) = if (ms == 0) "0 ms (in sync)" else "${if (ms > 0) "+" else ""}$ms ms"
    }
}
