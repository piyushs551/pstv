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
        Prefs.init(this)
        Db.init(this)
        Images.init(this)
        Repo.refreshIfStale()
    }
}

open class BaseActivity : Activity() {
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

    fun details(c: Context, item: Item) =
        c.startActivity(Intent(c, DetailActivity::class.java).putExtra("url", item.url))

    fun play(c: Context, item: Item, url: String = item.url, title: String = item.name, season: Int = 0, episode: Int = 0, key: String = item.url) {
        c.startActivity(
            Intent(c, PlayerActivity::class.java)
                .putExtra("url", url).putExtra("title", title).putExtra("key", key)
                .putExtra("kind", item.kind.ordinal).putExtra("group", item.group)
                .putExtra("season", season).putExtra("episode", episode)
                .putExtra("baseName", item.name).putExtra("year", item.year ?: -1)
        )
    }
}

fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()
