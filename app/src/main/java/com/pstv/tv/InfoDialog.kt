package com.pstv.tv

import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.view.KeyEvent
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

/**
 * Long-press info for a movie or show: banner, plot, season/episode counts, "More like this" (TMDB, only titles in the
 * playlist) and other versions of the same title in the playlist.
 */
class InfoDialog(private val act: BaseActivity, private var item: Item, private val onClose: (() -> Unit)? = null) : CardHost {
    override val baseActivity get() = act
    private val dialog = Dialog(act, R.style.Theme_Pstv_Info)
    private var navBusyUntil = 0L

    fun show() {
        dialog.setContentView(R.layout.dialog_info)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0xB3000000.toInt()))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        dialog.setOnDismissListener { onClose?.invoke() }
        bind()
        val v = dialog.findViewById<View>(R.id.info_play)
        v.setOnClickListener { dialog.dismiss(); Nav.open(act, item) }
        dialog.findViewById<View>(R.id.info_details).setOnClickListener { dialog.dismiss(); Nav.details(act, item) }
        val fav = dialog.findViewById<TextView>(R.id.info_fav)
        fun favText() { fav.text = if (Db.isFav(item.url)) "Remove from Favorites" else "Add to Favorites" }
        favText()
        fav.setOnClickListener { Db.toggleFav(item.url); favText() }
        dialog.show()
        v.requestFocus()
        load()
    }

    private fun bind() {
        dialog.findViewById<TextView>(R.id.info_title).text = cleanTitle(item.name)
        val chips = listOf(R.id.info_c1, R.id.info_c2, R.id.info_c3).map { dialog.findViewById<TextView>(it) }
        val vals = listOf(item.year?.toString(), item.rating?.let { "★ %.1f".format(it) }, item.genres ?: item.group.takeIf { it.isNotBlank() })
        chips.forEachIndexed { i, c -> c.text = vals[i]; c.visibility = if (vals[i].isNullOrBlank()) View.GONE else View.VISIBLE }
        dialog.findViewById<TextView>(R.id.info_plot).text = item.overview ?: "No description yet."
        val banner = dialog.findViewById<CardImageView>(R.id.info_banner)
        val url = item.heroArt
        if (url != null) banner.load(url, Images.loader) { size(Size(1200, 300)) } else banner.setImageDrawable(NameTile(item.name, act, R.drawable.ic_movies))
    }

    private fun load() {
        // plot/art if TMDB knows it and the playlist did not
        Enricher.ensure(item) { fresh -> item = fresh; bind() }

        val seasons = dialog.findViewById<TextView>(R.id.info_seasons)
        if (item.isXtreamSeries) act.scope.launch {
            val eps = withContext(Dispatchers.IO) { runCatching { Xtream.episodes(item.url) }.getOrDefault(emptyList()) }
            if (eps.isNotEmpty() && dialog.isShowing) {
                val by = eps.groupBy { it.season }.toSortedMap()
                seasons.text = "${by.size} season${if (by.size == 1) "" else "s"}  •  ${eps.size} episodes     " + by.entries.take(6).joinToString("   ") { "S${it.key}: ${it.value.size}" } + if (by.size > 6) "   …" else ""
                seasons.visibility = View.VISIBLE
            }
        }

        val same = Db.sameName(item)
        if (same.isNotEmpty()) {
            dialog.findViewById<View>(R.id.info_same_label).visibility = View.VISIBLE
            fill(R.id.info_same, same)
        }

        val msg = dialog.findViewById<TextView>(R.id.info_recs_msg)
        val label = dialog.findViewById<View>(R.id.info_recs_label)
        if (item.kind == Kind.LIVE) { label.visibility = View.GONE; msg.visibility = View.GONE; return }
        if (!Tmdb.enabled) { msg.text = "Recommendations need a TMDB key."; return }
        msg.text = "Looking for similar titles you can play…"
        act.scope.launch {
            val recs = withContext(Dispatchers.IO) { runCatching { Tmdb.recommendationsInPlaylist(item) }.getOrDefault(emptyList()) }
            if (!dialog.isShowing) return@launch
            if (recs.isEmpty()) msg.text = "No similar titles from your playlist were found."
            else { msg.visibility = View.GONE; fill(R.id.info_recs, recs) }
        }
    }

    private fun fill(id: Int, items: List<Item>) {
        val rv = dialog.findViewById<RecyclerView>(id)
        val row = RowDesc("info$id", "") { _, _ -> emptyList() }.also { it.items.addAll(items); it.done = true; it.started = true }
        rv.layoutManager = LinearLayoutManager(act, RecyclerView.HORIZONTAL, false)
        rv.itemAnimator = null
        rv.adapter = CardAdapter(row, this)
        rv.visibility = View.VISIBLE
    }

    // ---- cards inside the dialog ----------------------------------------------------------------
    override fun onCardClick(item: Item) { dialog.dismiss(); Nav.open(act, item) }
    override fun onCardLongClick(item: Item) { dialog.dismiss(); InfoDialog(act, item, onClose).show() }
    override fun onCardKey(row: RowDesc, pos: Int, keyCode: Int, e: KeyEvent): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (e.repeatCount > 0 && now < navBusyUntil && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) return true
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (pos > 0) { navBusyUntil = now + 70; (dialog.currentFocus?.parent as? RecyclerView)?.let { moveInRow(it, pos - 1) } }; true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (pos < row.items.size - 1) { navBusyUntil = now + 70; (dialog.currentFocus?.parent as? RecyclerView)?.let { moveInRow(it, pos + 1) } }; true }
            else -> false
        }
    }
}
