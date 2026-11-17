package com.pstv.tv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.size.Precision
import coil.size.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shell: tab bar + pages. Tabs: 0 Search (opens its own screen), 1 Home, 2 Live, 3 EPG, 4 Movies, 5 TV shows. */
class HomeActivity : BaseActivity(), CardHost {
    override val baseActivity get() = this

    private lateinit var tabs: LinearLayout
    private lateinit var status: TextView
    private lateinit var rowsPage: View
    private lateinit var livePageView: View
    private lateinit var epgPageView: View
    private lateinit var rowsRv: RecyclerView
    private lateinit var hero: Hero
    private lateinit var live: LivePage
    private lateinit var epg: EpgPage
    private val handler = Handler(Looper.getMainLooper())

    private var tab = 1
    private var adapter: RowsAdapter? = null
    private var rows = ArrayList<RowDesc>()
    private var shownVersion = -1
    private var buildToken = 0
    private var chromeShown = true

    private val repoListener: () -> Unit = {
        status.text = listOf(Repo.status, Epg.status).firstOrNull { it.isNotEmpty() } ?: ""
        if (Db.version != shownVersion) { shownVersion = Db.version; showTab(tab, focus = false) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_home)
        tabs = findViewById(R.id.tabs); status = findViewById(R.id.status)
        rowsPage = findViewById(R.id.page_rows); livePageView = findViewById(R.id.page_live); epgPageView = findViewById(R.id.page_epg)
        rowsRv = findViewById(R.id.rows)
        rowsRv.layoutManager = LinearLayoutManager(this)
        rowsRv.itemAnimator = null
        rowsRv.setItemViewCacheSize(2)
        hero = Hero(this)
        live = LivePage(this, livePageView)
        epg = EpgPage(this, epgPageView)

        for (i in 0 until 6) {
            val t = tabs.getChildAt(i)
            t.setOnClickListener { handler.removeCallbacksAndMessages(null); showTab(i, focus = true) }
            t.setOnFocusChangeListener { _, f -> if (f && i != 0 && i != tab && Prefs.tabFocusSwitch) { handler.removeCallbacksAndMessages(null); handler.postDelayed({ showTab(i, focus = false) }, 250) } }
            t.setOnKeyListener { _, k, e -> if (k == KeyEvent.KEYCODE_DPAD_DOWN && e.action == KeyEvent.ACTION_DOWN) { focusPage(); true } else false }
        }
        findViewById<View>(R.id.nav_settings).apply {
            setOnClickListener { startActivity(Intent(this@HomeActivity, SettingsActivity::class.java)) }
            setOnKeyListener { _, k, e -> if (k == KeyEvent.KEYCODE_DPAD_DOWN && e.action == KeyEvent.ACTION_DOWN) { focusPage(); true } else false }
        }
        val play = findViewById<View>(R.id.hero_play)
        play.nextFocusUpId = tabs.getChildAt(1).also { if (it.id == View.NO_ID) it.id = View.generateViewId() }.id
        findViewById<View>(R.id.hero_more).nextFocusUpId = play.nextFocusUpId
        val down = View.OnKeyListener { _, k, e -> if (k == KeyEvent.KEYCODE_DPAD_DOWN && e.action == KeyEvent.ACTION_DOWN) { focusRow(0); true } else false }
        play.setOnKeyListener(down); findViewById<View>(R.id.hero_more).setOnKeyListener(down)

        Repo.listeners.add(repoListener)
        shownVersion = Db.version
        showTab(1, focus = true)
    }

    override fun onStart() {
        super.onStart()
        repoListener()
        if (tab == 1) refreshPersonalRows()
    }

    override fun onDestroy() { Repo.listeners.remove(repoListener); handler.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun inTabs(v: View?): Boolean { var x: View? = v; while (x != null) { if (x === tabs) return true; x = x.parent as? View }; return false }

    /** Back: deep in a page -> top + tab bar focused; on a non-Home tab -> Home; on Home -> exit. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val onTabs = inTabs(currentFocus)
        when {
            !onTabs && (tab == 1 || tab == 4 || tab == 5) -> {
                (rowsRv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(0, 0)
                setChrome(true); focusTabs()
            }
            !onTabs -> focusTabs()
            tab != 1 -> { showTab(1, focus = false); tabs.getChildAt(1).requestFocus() }
            else -> super.onBackPressed()
        }
    }

    /** Tab bar and hero are shown on the first row only; below it they fade/collapse to give rows more room. */
    private fun setChrome(show: Boolean, force: Boolean = false) {
        if (chromeShown == show && !force) return
        chromeShown = show
        tabs.animate().cancel()
        if (Anim.dur == 0L) tabs.alpha = if (show) 1f else 0f else tabs.animate().alpha(if (show) 1f else 0f).setDuration(Anim.dur).start()
        findViewById<View>(R.id.hero_area).visibility = if (Prefs.hero && show) View.VISIBLE else View.GONE
        rowsPage.setPadding(0, if (show) (if (Prefs.hero) 0 else dp(this, 84)) else Tokens.pxi(this, R.dimen.safe_v), 0, 0)
    }

    // ---- tabs -----------------------------------------------------------------------------------

    fun focusTabs() { setChrome(true); tabs.getChildAt(tab).requestFocus() }

    private fun focusPage() {
        when (tab) {
            1, 4, 5 -> if (Prefs.hero) findViewById<View>(R.id.hero_play).requestFocus() else focusRow(0)
            2 -> live.focusFirst()
            3 -> epg.focusFirst()
        }
    }

    private fun showTab(i: Int, focus: Boolean) {
        if (i == 0) { startActivity(Intent(this, SearchActivity::class.java)); return }
        tab = i
        for (k in 1 until 6) tabs.getChildAt(k).isActivated = (k == i)
        val isRows = i == 1 || i == 4 || i == 5
        rowsPage.visibility = if (isRows) View.VISIBLE else View.GONE
        livePageView.visibility = if (i == 2) View.VISIBLE else View.GONE
        epgPageView.visibility = if (i == 3) View.VISIBLE else View.GONE
        setChrome(true, force = true)
        when (i) {
            1, 4, 5 -> buildRows(i, focus)
            2 -> live.show(focus)
            3 -> epg.show(focus)
        }
    }

    // ---- rows (Home / Movies / TV shows) --------------------------------------------------------

    private fun groupRows(kind: Kind) = Db.groups(kind).map { g -> RowDesc("g:${g.kind}:${g.name}", g.name) { o, l -> Db.page(g.kind, g.name, o, l) } }

    private fun trending(kind: Kind) = RowDesc(if (kind == Kind.MOVIE) "trendm" else "trends",
        if (kind == Kind.MOVIE) "Today's trending movies" else "Today's trending series", ranked = true) { o, _ -> if (o == 0) Tmdb.trendingInPlaylist(kind) else emptyList() }

    private fun homeRow(key: String): RowDesc? = when (key) {
        "recent" -> RowDesc("recent", "Continue Watching") { o, _ -> if (o == 0) Db.recents() else emptyList() }
        "trendm" -> if (Tmdb.enabled) trending(Kind.MOVIE) else null
        "trends" -> if (Tmdb.enabled) trending(Kind.SERIES) else null
        "favs" -> RowDesc("favs", "Favorites") { o, _ -> if (o == 0) Db.favorites() else emptyList() }
        "livehist" -> RowDesc("livehist", "Recently watched channels") { o, _ -> if (o == 0) Db.recentLive() else emptyList() }
        "newm" -> RowDesc("newm", "Recently added (movies)") { o, l -> Db.latest(Kind.MOVIE, o, l) }
        "news" -> RowDesc("news", "Recently added (series)") { o, l -> Db.latest(Kind.SERIES, o, l) }
        else -> null
    }

    private fun buildRows(which: Int, focus: Boolean) {
        val token = ++buildToken
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                val r = ArrayList<RowDesc>()
                when (which) {
                    1 -> Prefs.homeRows.split(',').filter { it.isNotBlank() && !it.startsWith("!") }.forEach { k -> homeRow(k)?.let(r::add) }
                    4 -> { if (Tmdb.enabled) r += trending(Kind.MOVIE); r += homeRow("newm")!!; r += groupRows(Kind.MOVIE) }
                    else -> { if (Tmdb.enabled) r += trending(Kind.SERIES); r += homeRow("news")!!; r += groupRows(Kind.SERIES) }
                }
                // Empty non-lazy rows are hidden.
                r.filter { it.ranked || it.load(0, 1).isNotEmpty() }.toMutableList()
            }
            if (token != buildToken) return@launch
            rows = ArrayList(list)
            adapter = RowsAdapter(rows, this@HomeActivity).also { rowsRv.adapter = it }
            if (rows.isEmpty()) hero.empty() else { if (focus) focusRow(0) else hero.show(null) }
        }
    }

    private fun refreshPersonalRows() {
        val a = adapter ?: return
        val keys = listOf("recent", "favs", "livehist")
        if (rows.none { it.key in keys }) return
        scope.launch {
            for (r in rows.filter { it.key in keys }) {
                val fresh = withContext(Dispatchers.IO) { r.load(0, 60) }
                if (fresh.isEmpty()) { onRowEmpty(r); continue }
                r.items.clear(); r.items.addAll(fresh); r.done = true; r.started = true
                a.notifyItemChanged(rows.indexOf(r))
            }
        }
    }

    private fun focusRow(index: Int, tries: Int = 0) {
        val a = adapter ?: return
        if (index !in rows.indices) return
        setChrome(index == 0)
        (rowsRv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index, 0)
        val vh = rowsRv.findViewHolderForAdapterPosition(index) as? RowsAdapter.VH
        if (vh == null) { if (tries < 8) rowsRv.post { focusRow(index, tries + 1) }; return }
        a.focusRow(vh, rows[index])
    }

    override fun onRowEmpty(row: RowDesc) {
        val i = rows.indexOf(row)
        if (i < 0) return
        rows.removeAt(i)
        adapter?.notifyItemRemoved(i)
        if (rows.isEmpty()) hero.empty()
    }

    // ---- CardHost (rows) ------------------------------------------------------------------------

    override fun onCardFocused(row: RowDesc, pos: Int, item: Item) {
        row.lastPos = pos
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
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (idx <= 0) { if (Prefs.hero) findViewById<View>(R.id.hero_play).requestFocus() else focusTabs() } else focusRow(idx - 1); true
            }
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
 * Hero banner. Text (title/chips/plot) comes from local data and is set immediately on focus;
 * the image is debounced ~150 ms and cross-fades between two ImageViews. Nothing here waits on the network.
 */
class Hero(private val act: HomeActivity) {
    private val a: ImageView = act.findViewById(R.id.hero_a)
    private val b: ImageView = act.findViewById(R.id.hero_b)
    private val title: TextView = act.findViewById(R.id.hero_title)
    private val chips = listOf<TextView>(act.findViewById(R.id.hero_c1), act.findViewById(R.id.hero_c2), act.findViewById(R.id.hero_c3))
    private val desc: TextView = act.findViewById(R.id.hero_desc)
    private val handler = Handler(Looper.getMainLooper())
    private var front = a
    private var back = b
    private var shownUrl: String? = null
    private var token = 0

    var current: Item? = null
        private set

    val w = act.resources.displayMetrics.widthPixels
    val h = dp(act, 280)

    init {
        act.findViewById<View>(R.id.hero_play).setOnClickListener { act.openCurrent(false) }
        act.findViewById<View>(R.id.hero_more).setOnClickListener { act.openCurrent(true) }
    }

    private fun setChips(vararg v: String?) { chips.forEachIndexed { i, c -> val t = v.getOrNull(i); c.text = t; c.visibility = if (t.isNullOrBlank()) View.GONE else View.VISIBLE } }

    fun empty() {
        current = null
        title.text = if (Prefs.hasSource) "Loading your library…" else "Welcome to PSTV"
        setChips()
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
        if (url == null) back.setImageDrawable(NameTile(item.name, act, when (item.kind) { Kind.LIVE -> R.drawable.ic_live; Kind.MOVIE -> R.drawable.ic_movies; Kind.SERIES -> R.drawable.ic_shows }))
        shownUrl = url
        back.animate().cancel(); front.animate().cancel()
        if (Anim.dur == 0L) { back.alpha = 1f; front.alpha = 0f }
        else {
            back.alpha = 0f
            back.animate().alpha(1f).setDuration(Anim.dur + 10).setInterpolator(Anim.interp).start()
            front.animate().alpha(0f).setDuration(Anim.dur + 10).setInterpolator(Anim.interp).start()
        }
        val t = front; front = back; back = t
    }

    /** [item] null = keep what is shown (tab switch). */
    fun show(item: Item?, instant: Boolean = false) {
        if (item == null) return
        current = item
        title.text = cleanTitle(item.name)
        setChips(
            item.year?.toString() ?: when (item.kind) { Kind.LIVE -> "Live"; Kind.MOVIE -> "Movie"; Kind.SERIES -> "Series" },
            item.rating?.let { "★ %.1f".format(it) },
            item.genres ?: item.group.takeIf { it.isNotBlank() && item.kind != Kind.LIVE },
        )
        desc.text = item.overview ?: if (item.kind == Kind.LIVE) item.group else ""
        handler.removeCallbacks(apply)
        if (instant || !Prefs.hero) apply.run() else handler.postDelayed(apply, 150)
    }
}
