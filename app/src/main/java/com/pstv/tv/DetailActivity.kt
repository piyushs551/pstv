package com.pstv.tv

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.size.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Info page. Series (Xtream): title/plot on top, a Season dropdown and episodes as landscape cards with their details. */
class DetailActivity : BaseActivity() {
    private lateinit var item: Item
    private var episodes: List<Xtream.Episode> = emptyList()
    private var season = 0
    private val shown = ArrayList<Xtream.Episode>()
    private val tmdbDone = HashSet<Int>()
    private var epAdapter: RecyclerView.Adapter<RecyclerView.ViewHolder>? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_detail)
        item = Db.byUrl(intent.getStringExtra("url") ?: "") ?: run { finish(); return }
        bind()

        val play = findViewById<TextView>(R.id.d_play)
        val fav = findViewById<TextView>(R.id.d_fav)
        fun favText() { fav.text = if (Db.isFav(item.url)) "Remove from Favorites" else "Add to Favorites" }
        favText()
        fav.setOnClickListener { Db.toggleFav(item.url); favText() }

        if (item.isXtreamSeries) {
            findViewById<View>(R.id.series_area).visibility = View.VISIBLE
            play.setOnClickListener { playResume() }
            loadEpisodes()
        } else {
            play.setOnClickListener { Nav.play(this, item) }
        }
        play.requestFocus()
        Enricher.ensure(item) { fresh -> item = fresh; bind() }
    }

    private fun bind() {
        findViewById<TextView>(R.id.d_title).text = cleanTitle(item.name)
        val chips = listOf(R.id.d_c1, R.id.d_c2, R.id.d_c3).map { findViewById<TextView>(it) }
        val vals = listOf(item.year?.toString(), item.rating?.let { "★ %.1f".format(it) }, item.genres ?: item.group.takeIf { it.isNotBlank() })
        chips.forEachIndexed { i, c -> c.text = vals[i]; c.visibility = if (vals[i].isNullOrBlank()) View.GONE else View.VISIBLE }
        findViewById<TextView>(R.id.d_desc).text = item.overview ?: ""
        val bd = findViewById<ImageView>(R.id.backdrop)
        val m = resources.displayMetrics
        bd.scaleType = if (item.backdrop != null) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_END
        item.heroArt?.let { bd.load(it, Images.loader) { size(Size(m.widthPixels, m.heightPixels)) } } ?: bd.setImageDrawable(NameTile(item.name, this, R.drawable.ic_shows))
    }

    private fun playResume() {
        val last = Db.lastEpisode(item.url)
        val ep = last?.let { l -> episodes.firstOrNull { it.url == l.first } } ?: episodes.firstOrNull() ?: return
        playEpisode(ep)
    }

    private fun playEpisode(e: Xtream.Episode) =
        Nav.play(this, item, e.url, "${cleanTitle(item.name)}  S${e.season}E${e.number}", e.season, e.number, key = e.url)

    private fun loadEpisodes() {
        val list = findViewById<RecyclerView>(R.id.ep_list)
        list.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        list.itemAnimator = null
        findViewById<View>(R.id.season_btn).setOnClickListener { pickSeason() }
        scope.launch {
            val res = withContext(Dispatchers.IO) { runCatching { Xtream.episodes(item.url) } }
            res.onFailure { toast("Could not load episodes: ${it.message}") }
            episodes = res.getOrDefault(emptyList())
            val seasons = episodes.map { it.season }.distinct().sorted()
            val last = Db.lastEpisode(item.url)?.first
            season = episodes.firstOrNull { it.url == last }?.season ?: seasons.firstOrNull() ?: 0
            showSeason()
        }
    }

    /** Dropdown right under the Season button (not a centred dialog). */
    private fun pickSeason() {
        val seasons = episodes.map { it.season }.distinct().sorted()
        if (seasons.isEmpty()) return
        val popup = android.widget.ListPopupWindow(this)
        popup.anchorView = findViewById(R.id.season_btn)
        popup.width = dp(this, 210); popup.isModal = true
        popup.setBackgroundDrawable(getDrawable(R.drawable.frost_panel))
        popup.setAdapter(object : android.widget.BaseAdapter() {
            override fun getCount() = seasons.size
            override fun getItem(i: Int) = seasons[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, v: View?, p: ViewGroup): View = (v as? TextView ?: TextView(p.context)).apply {
                text = (if (seasons[i] == season) "✓   " else "     ") + "Season ${seasons[i]}"
                setTextColor(getColor(R.color.text)); type(R.dimen.type_body); setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
            }
        })
        popup.setOnItemClickListener { _, _, i, _ -> season = seasons[i]; popup.dismiss(); showSeason(focusList = true) }
        popup.show()
        popup.listView?.apply { selector = getDrawable(R.drawable.row_bg); divider = null }
        popup.setSelection(seasons.indexOf(season).coerceAtLeast(0))
    }

    private fun showSeason(focusList: Boolean = false) {
        findViewById<TextView>(R.id.season_btn).text = "Season $season"
        shown.clear(); shown.addAll(episodes.filter { it.season == season })
        val list = findViewById<RecyclerView>(R.id.ep_list)
        val title = findViewById<TextView>(R.id.ep_title); val desc = findViewById<TextView>(R.id.ep_desc)
        fun info(e: Xtream.Episode) {
            title.text = "${e.number}. ${e.title.substringAfterLast(" - ").ifBlank { e.title }}"
            desc.text = listOfNotNull(e.plot, e.duration?.let { "Duration $it" }).joinToString("  •  ")
        }
        shown.firstOrNull()?.let { info(it) }
        list.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = shown.size
            override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder =
                object : RecyclerView.ViewHolder(LayoutInflater.from(p.context).inflate(R.layout.item_card, p, false)) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
                val e = shown[i]; val v = h.itemView; val c = v.context
                val w = Card.w(c, item); val ht = Card.h(c, item)
                v.findViewById<View>(R.id.card_frame).layoutParams = LinearLayout.LayoutParams(w, ht)
                v.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = Tokens.pxi(c, R.dimen.card_gap) }
                val img = v.findViewById<CardImageView>(R.id.card_img)
                Images.card(img, e.image ?: item.cardArt, cleanTitle(item.name), w, ht, R.drawable.ic_shows)
                v.findViewById<TextView>(R.id.card_rank).apply { visibility = View.VISIBLE; text = "S${e.season} E${e.number}"; type(R.dimen.type_title) }
                v.findViewById<TextView>(R.id.card_label).text = e.title.substringAfterLast(" - ").ifBlank { e.title }
                v.findViewById<TextView>(R.id.card_sub).text = e.duration ?: ""
                val cap = v.findViewById<View>(R.id.card_caption)
                v.setOnFocusChangeListener { x, f ->
                    val s = if (f) Anim.scale else 1f
                    x.animate().scaleX(s).scaleY(s).setDuration(Anim.dur).setInterpolator(Anim.interp).start()
                    cap.animate().alpha(if (f) 1f else 0f).setDuration(Anim.dur).start()
                    if (f) info(e)
                }
                v.setOnClickListener { playEpisode(e) }
            }
        }
        @Suppress("UNCHECKED_CAST") run { epAdapter = list.adapter as RecyclerView.Adapter<RecyclerView.ViewHolder> }
        if (focusList) list.post { list.getChildAt(0)?.requestFocus() }
        // Episode stills, titles and plots from TMDB (the provider often has none): fetched once per season.
        if (Tmdb.enabled && tmdbDone.add(season)) {
            val sNow = season
            scope.launch {
                val m = withContext(Dispatchers.IO) { runCatching { Tmdb.seasonEpisodes(item, sNow) }.getOrDefault(emptyMap()) }
                if (m.isEmpty()) return@launch
                episodes = episodes.map { e -> if (e.season != sNow) e else m[e.number]?.let { md ->
                    e.copy(title = md.name ?: e.title, image = md.still ?: e.image, plot = md.overview ?: e.plot, duration = e.duration ?: md.runtime?.let { "$it min" }) } ?: e }
                if (season == sNow) { shown.clear(); shown.addAll(episodes.filter { it.season == sNow }); epAdapter?.notifyDataSetChanged(); shown.firstOrNull()?.let { info(it) } }
            }
        }
    }
}
