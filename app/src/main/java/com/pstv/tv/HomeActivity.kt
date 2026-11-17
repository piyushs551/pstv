package com.pstv.tv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeActivity : BaseActivity(), CardHost {
    override val baseActivity get() = this

    private lateinit var rowsRv: RecyclerView
    private lateinit var status: TextView
    private lateinit var hero: Hero
    private var adapter: RowsAdapter? = null
    private var rows: List<RowDesc> = emptyList()
    private var shownVersion = -1
    private var focusRowKey: String? = null
    private var focusPos = 0

    private val repoListener: () -> Unit = {
        status.text = Repo.status
        if (Db.version != shownVersion) buildRows()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_home)
        status = findViewById(R.id.status)
        rowsRv = findViewById(R.id.rows)
        rowsRv.layoutManager = LinearLayoutManager(this)
        rowsRv.itemAnimator = null
        rowsRv.setItemViewCacheSize(2)
        hero = Hero(this)

        val home = findViewById<View>(R.id.nav_home)
        val search = findViewById<View>(R.id.nav_search)
        val settings = findViewById<View>(R.id.nav_settings)
        home.setOnClickListener { focusRow(0) }
        search.setOnClickListener { startActivity(Intent(this, SearchActivity::class.java)) }
        settings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        val play = findViewById<View>(R.id.hero_play)
        listOf(home, search, settings).forEach { it.nextFocusDownId = R.id.hero_play }
        play.nextFocusUpId = R.id.nav_home
        findViewById<View>(R.id.hero_more).nextFocusUpId = R.id.nav_home
        val down = View.OnKeyListener { _, k, e ->
            if (k == KeyEvent.KEYCODE_DPAD_DOWN && e.action == KeyEvent.ACTION_DOWN) { focusRow(0); true } else false
        }
        play.setOnKeyListener(down); findViewById<View>(R.id.hero_more).setOnKeyListener(down)

        Repo.listeners.add(repoListener)
        buildRows()
    }

    override fun onStart() {
        super.onStart()
        status.text = Repo.status
        // Coming back from the player: refresh Continue Watching / Favorites without touching other rows.
        refreshPersonalRows()
    }

    override fun onDestroy() { Repo.listeners.remove(repoListener); super.onDestroy() }

    // ---- rows -----------------------------------------------------------------------------------

    private fun kindLabel(k: Kind) = when (k) { Kind.LIVE -> "Live"; Kind.MOVIE -> "Movies"; Kind.SERIES -> "Series" }

    private fun buildRows() {
        shownVersion = Db.version
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                val r = ArrayList<RowDesc>()
                r += RowDesc("recent", "Continue Watching") { o, _ -> if (o == 0) Db.recents() else emptyList() }
                r += RowDesc("favs", "Favorites") { o, _ -> if (o == 0) Db.favorites() else emptyList() }
                for (g in Db.groups()) r += RowDesc("g:${g.kind}:${g.name}", "${kindLabel(g.kind)} · ${g.name}") { o, l -> Db.page(g.kind, g.name, o, l) }
                r
            }
            // Empty personal rows are hidden.
            val visible = withContext(Dispatchers.IO) { list.filter { !(it.key == "recent" || it.key == "favs") || it.load(0, 1).isNotEmpty() } }
            rows = visible
            adapter = RowsAdapter(visible, this@HomeActivity).also { rowsRv.adapter = it }
            if (visible.isEmpty()) hero.empty() else { focusRow(0); loadTrending() }
        }
    }

    /** Trending (TMDB) titles that exist in the playlist; inserted after Favorites once fetched. */
    private fun loadTrending() {
        if (!Tmdb.enabled) return
        scope.launch {
            val items = withContext(Dispatchers.IO) { runCatching { Tmdb.trendingInPlaylist() }.getOrDefault(emptyList()) }
            if (items.isEmpty() || isFinishing) return@launch
            val row = RowDesc("trending", "Trending Now") { o, _ -> if (o == 0) items else emptyList() }
            val at = rows.indexOfFirst { it.key != "recent" && it.key != "favs" }.let { if (it < 0) rows.size else it }
            rows = rows.toMutableList().also { it.add(at, row) }
            val keep = adapter?.let { currentFocusKey() }
            adapter = RowsAdapter(rows, this@HomeActivity).also { rowsRv.adapter = it }
            keep?.let { k -> rows.indexOfFirst { it.key == k }.takeIf { it >= 0 }?.let { focusRow(it) } }
        }
    }

    private fun refreshPersonalRows() {
        val a = adapter ?: return
        val personal = rows.filter { it.key == "recent" || it.key == "favs" }
        if (personal.isEmpty() && Db.version == shownVersion) return
        scope.launch {
            var needRebuild = false
            for (r in listOf("recent", "favs")) {
                val row = rows.find { it.key == r }
                val fresh = withContext(Dispatchers.IO) { if (r == "recent") Db.recents() else Db.favorites() }
                if (row == null) { if (fresh.isNotEmpty()) needRebuild = true; continue }
                if (fresh.isEmpty()) { needRebuild = true; continue }
                row.items.clear(); row.items.addAll(fresh); row.done = true; row.started = true
                a.notifyItemChanged(rows.indexOf(row))
            }
            if (needRebuild) buildRows()
        }
    }

    private fun currentFocusKey(): String? = focusRowKey

    private fun focusRow(index: Int, tries: Int = 0) {
        val a = adapter ?: return
        if (index !in rows.indices) return
        (rowsRv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index, 0)
        val vh = rowsRv.findViewHolderForAdapterPosition(index) as? RowsAdapter.VH
        if (vh == null) { if (tries < 8) rowsRv.post { focusRow(index, tries + 1) }; return }
        a.focusRow(vh, rows[index])
    }

    // ---- CardHost -------------------------------------------------------------------------------

    override fun onCardFocused(row: RowDesc, pos: Int, item: Item) {
        row.lastPos = pos
        focusRowKey = row.key
        focusPos = pos
        hero.show(item)
        // Warm the hero image for the next few candidates (size-matched so the cache key hits).
        for (j in pos - 1..pos + 4) if (j != pos && j in row.items.indices) Images.preload(row.items[j].heroArt, hero.w, hero.h)
        Enricher.ensure(item) { fresh ->
            val i = row.items.indexOfFirst { it.id == fresh.id }
            if (i >= 0) row.items[i] = fresh
            if (hero.current?.id == fresh.id) hero.show(fresh, instant = true)
        }
    }

    override fun onCardKey(row: RowDesc, pos: Int, keyCode: Int, e: KeyEvent): Boolean {
        val idx = rows.indexOf(row)
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> { focusRow(idx + 1); true }
            KeyEvent.KEYCODE_DPAD_UP -> { if (idx <= 0) findViewById<View>(R.id.hero_play).requestFocus() else focusRow(idx - 1); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> pos == 0
            KeyEvent.KEYCODE_DPAD_RIGHT -> pos >= row.items.size - 1
            else -> false
        }
    }

    override fun onCardClick(item: Item) = Nav.open(this, item)

    override fun onCardLongClick(item: Item) {
        toast(if (Db.toggleFav(item.url)) "Added to Favorites" else "Removed from Favorites")
        refreshPersonalRows()
    }

    fun openCurrent(details: Boolean) {
        val it = hero.current ?: return
        if (details) Nav.details(this, it) else Nav.open(this, it)
    }
}

/**
 * Hero banner. Text (title/year/plot) comes from local data and is set immediately on focus;
 * the image is debounced ~150 ms and cross-fades between two ImageViews. Nothing here waits on the network.
 */
class Hero(private val act: HomeActivity) {
    private val a: ImageView = act.findViewById(R.id.hero_a)
    private val b: ImageView = act.findViewById(R.id.hero_b)
    private val title: TextView = act.findViewById(R.id.hero_title)
    private val meta: TextView = act.findViewById(R.id.hero_meta)
    private val desc: TextView = act.findViewById(R.id.hero_desc)
    private val handler = Handler(Looper.getMainLooper())
    private var front = a
    private var back = b
    private var shownUrl: String? = null
    private var token = 0

    var current: Item? = null
        private set

    val w = act.resources.displayMetrics.widthPixels
    val h = dp(act, 196)

    init {
        act.findViewById<View>(R.id.hero_play).setOnClickListener { act.openCurrent(false) }
        act.findViewById<View>(R.id.hero_more).setOnClickListener { act.openCurrent(true) }
    }

    fun empty() {
        current = null
        title.text = if (Prefs.hasSource) "Loading your library…" else "Welcome to PSTV"
        meta.text = ""
        desc.text = if (Prefs.hasSource) "Your playlist is being downloaded. This only happens once; it refreshes in the background after that."
        else "Open Settings and add your own M3U URL or Xtream login to get started."
    }

    private val apply = Runnable {
        val item = current ?: return@Runnable
        val url = item.heroArt
        if (url == shownUrl) return@Runnable
        val my = ++token
        if (url.isNullOrBlank()) { swap(null, item, my); return@Runnable }
        back.load(url, Images.loader) {
            size(Size(w, h)); precision(Precision.INEXACT)
            listener(onSuccess = { _, _ -> swap(url, item, my) }, onError = { _, _ -> swap(null, item, my) })
        }
    }

    private fun swap(url: String?, item: Item, my: Int) {
        if (my != token) return
        if (url == null) back.setImageDrawable(NameTile(item.name))
        shownUrl = url
        back.animate().cancel(); front.animate().cancel()
        back.alpha = 0f
        back.animate().alpha(1f).setDuration(150).start()
        front.animate().alpha(0f).setDuration(150).start()
        val t = front; front = back; back = t
    }

    fun show(item: Item, instant: Boolean = false) {
        current = item
        title.text = cleanTitle(item.name)
        meta.text = listOfNotNull(item.year?.toString(), when (item.kind) { Kind.LIVE -> "Live"; Kind.MOVIE -> "Movie"; Kind.SERIES -> "Series" }, item.group.takeIf { it.isNotBlank() }).joinToString("  •  ")
        desc.text = item.overview ?: if (item.kind == Kind.LIVE) "Live channel" else ""
        handler.removeCallbacks(apply)
        if (instant) apply.run() else handler.postDelayed(apply, 150)
    }
}
