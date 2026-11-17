package com.pstv.tv

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/** Loads the user's own playlist (M3U URL or Xtream login) into SQLite, in the background. */
object Repo {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile var status: String = ""
    @Volatile var refreshing = false

    fun ping() = notifyChanged()

    private fun notifyChanged() = main.post { listeners.forEach { it() } }
    private fun updateStatus(s: String) { status = s; notifyChanged() }

    fun refreshIfStale() {
        if (!Prefs.hasSource) return
        if (Db.count() == 0 || (Prefs.refreshHours > 0 && System.currentTimeMillis() - Prefs.lastRefresh > Prefs.refreshHours * 3_600_000L)) refresh()
        Epg.refreshIfStale()
    }

    fun refresh() {
        if (refreshing || !Prefs.hasSource) return
        refreshing = true
        updateStatus("Updating playlist…")
        scope.launch {
            try {
                if (Prefs.sourceType == "xtream") {
                    Xtream.verify()
                    Db.replaceAll { sink -> Xtream.load(sink) }
                } else {
                    Http.client.newCall(Http.req(Prefs.m3uUrl).build()).execute().use { r ->
                        if (!r.isSuccessful) error("HTTP ${r.code}")
                        r.body!!.charStream().buffered(64 * 1024).let { reader ->
                            Db.replaceAll { sink -> M3uParser.parse(reader, sink) }
                        }
                    }
                }
                Prefs.lastRefresh = System.currentTimeMillis()
                refreshing = false
                updateStatus("")
                Epg.refreshIfStale()
            } catch (e: Exception) {
                refreshing = false
                updateStatus("Playlist update failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
}
