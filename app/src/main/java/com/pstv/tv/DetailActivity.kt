package com.pstv.tv

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.size.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "More Info" page: plot, favorite toggle and, for Xtream series, the episode list. */
class DetailActivity : BaseActivity() {
    private lateinit var item: Item

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
            play.visibility = View.GONE
            findViewById<View>(R.id.episodes).visibility = View.VISIBLE
            loadEpisodes()
        } else {
            play.setOnClickListener { Nav.play(this, item) }
            play.requestFocus()
        }
        Enricher.ensure(item) { fresh -> item = fresh; bind() }
    }

    private fun bind() {
        findViewById<TextView>(R.id.d_title).text = cleanTitle(item.name)
        findViewById<TextView>(R.id.d_meta).text = listOfNotNull(item.year?.toString(), item.group.takeIf { it.isNotBlank() }).joinToString("  •  ")
        findViewById<TextView>(R.id.d_desc).text = item.overview ?: ""
        val bd = findViewById<ImageView>(R.id.backdrop)
        val m = resources.displayMetrics
        item.heroArt?.let { bd.load(it, Images.loader) { size(Size(m.widthPixels, m.heightPixels)) } } ?: bd.setImageDrawable(NameTile(item.name))
    }

    private fun loadEpisodes() {
        val list = findViewById<RecyclerView>(R.id.episodes)
        list.layoutManager = LinearLayoutManager(this)
        scope.launch {
            val eps = withContext(Dispatchers.IO) { runCatching { Xtream.episodes(item.url) } }
            eps.onFailure { toast("Could not load episodes: ${it.message}") }
            val data = eps.getOrDefault(emptyList())
            list.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = data.size
                override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
                    val tv = TextView(p.context).apply {
                        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(context, 6) }
                        setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
                        setBackgroundResource(R.drawable.row_bg)
                        isFocusable = true
                        setTextColor(resources.getColor(R.color.text, null))
                        textSize = 15f
                        gravity = Gravity.CENTER_VERTICAL
                        maxLines = 1
                    }
                    return object : RecyclerView.ViewHolder(tv) {}
                }
                override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
                    val e = data[i]
                    val tv = h.itemView as TextView
                    tv.text = "S${e.season}  E${e.number}   ${e.title}"
                    tv.setOnClickListener { Nav.play(this@DetailActivity, item, e.url, "${cleanTitle(item.name)}  S${e.season}E${e.number}", e.season, e.number, key = e.url) }
                }
            }
            list.post { list.getChildAt(0)?.requestFocus() }
        }
    }
}
