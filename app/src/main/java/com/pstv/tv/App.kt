package com.pstv.tv

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Fonts.install(this)
        Prefs.init(this)
        Anim.init(this)
        Db.init(this)
        Images.init(this)
        Repo.refreshIfStale()
    }
}

open class BaseActivity : Activity() {
    /** Applies Settings > UI size by scaling the screen density; the whole layout (dp/sp) follows. */
    override fun attachBaseContext(base: Context) {
        val cfg = android.content.res.Configuration(base.resources.configuration)
        cfg.densityDpi = (cfg.densityDpi * Prefs.uiScale / 100f).toInt().coerceAtLeast(80)
        super.attachBaseContext(base.createConfigurationContext(cfg))
    }

    /** Cancelled with the screen; use for UI-bound coroutines. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

object Nav {
    /** Live and movies play straight away; series open their detail page. */
    fun open(c: Context, item: Item) {
        if (item.kind == Kind.SERIES) details(c, item) else play(c, item)
    }

    /** Long-press info for movies/series; live channels just toggle Favorites. */
    fun info(act: BaseActivity, item: Item, onClose: (() -> Unit)? = null) {
        if (item.kind == Kind.LIVE) { act.toast(if (Db.toggleFav(item.url)) "Added to Favorites" else "Removed from Favorites"); onClose?.invoke() }
        else InfoDialog(act, item, onClose).show()
    }

    fun details(c: Context, item: Item) =
        c.startActivity(Intent(c, DetailActivity::class.java).putExtra("url", item.url))

    fun play(c: Context, item: Item, url: String = item.url, title: String = item.name, season: Int = 0, episode: Int = 0, key: String = item.url) {
        c.startActivity(
            Intent(c, PlayerActivity::class.java)
                .putExtra("url", url).putExtra("title", title).putExtra("key", key)
                .putExtra("kind", item.kind.ordinal).putExtra("group", item.group)
                .putExtra("season", season).putExtra("episode", episode)
                .putExtra("baseName", item.name).putExtra("year", item.year ?: -1)
                .putExtra("parent", if (key != item.url) item.url else "").putExtra("label", if (season > 0) "S$season E$episode" else "")
        )
    }
}

fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()
