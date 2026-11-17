package com.pstv.tv

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One horizontal row. Items load lazily (only when the row is first bound) and page in as you scroll. */
class RowDesc(val key: String, val title: String, val ranked: Boolean = false, val load: (offset: Int, limit: Int) -> List<Item>) {
    val items = ArrayList<Item>()
    var loading = false
    var done = false
    var started = false
    var lastPos = 0
    var focusedPos = -1
}

interface CardHost {
    val baseActivity: BaseActivity
    fun onCardFocused(row: RowDesc, pos: Int, item: Item) {}
    fun onCardKey(row: RowDesc, pos: Int, keyCode: Int, e: KeyEvent): Boolean = false
    fun onCardClick(item: Item)
    fun onCardLongClick(item: Item) {}
    fun onRowEmpty(row: RowDesc) {}
}

/** Animation: token values (tokens.xml) scaled by the Settings > Animations choice. Property animations only. */
object Anim {
    private var tokenScale = 1.09f
    private var tokenMs = 150L
    var dimAlpha = 0.55f
        private set
    fun init(c: Context) {
        tokenScale = Tokens.float(c, R.dimen.focus_scale)
        tokenMs = Tokens.int(c, R.integer.focus_ms).toLong()
        dimAlpha = Tokens.int(c, R.integer.dim_alpha_pct) / 100f
    }
    val scale get() = when (Prefs.anim) { 0 -> 1f; 1 -> 1f + (tokenScale - 1f) * 0.6f; else -> tokenScale }
    val dur get() = when (Prefs.anim) { 0 -> 0L; 1 -> tokenMs * 3 / 5; else -> tokenMs }
    val interp = DecelerateInterpolator()
}

object Card {
    const val PAGE = 100

    fun w(c: Context, item: Item) = Tokens.pxi(c, if (item.isWide) R.dimen.wide_w else R.dimen.poster_w)
    fun h(c: Context, item: Item) = Tokens.pxi(c, if (item.isWide) R.dimen.wide_h else R.dimen.poster_h)

    fun sub(item: Item): String = when {
        item.progress > 0f -> "${(item.progress * 100).toInt()}% watched"
        item.kind == Kind.LIVE -> item.group
        else -> listOfNotNull(item.year?.toString(), item.genres?.substringBefore(',')).joinToString("  •  ")
    }
}

class CardAdapter(private val row: RowDesc, private val host: CardHost) : RecyclerView.Adapter<CardAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val frame: View = v.findViewById(R.id.card_frame)
        val img: ImageView = v.findViewById(R.id.card_img)
        val rank: TextView = v.findViewById(R.id.card_rank)
        val caption: View = v.findViewById(R.id.card_caption)
        val label: TextView = v.findViewById(R.id.card_label)
        val sub: TextView = v.findViewById(R.id.card_sub)
        val bar: View = v.findViewById(R.id.card_bar)
    }

    override fun getItemCount() = row.items.size

    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_card, p, false))

    private fun fade(v: View, to: Float) {
        if (Anim.dur == 0L) v.alpha = to else v.animate().alpha(to).setDuration(Anim.dur).setInterpolator(Anim.interp).start()
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = row.items[pos]
        val c: Context = h.itemView.context
        val w = Card.w(c, item); val ht = Card.h(c, item)
        h.frame.layoutParams = LinearLayout.LayoutParams(w, ht)
        h.itemView.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = Tokens.pxi(c, R.dimen.card_gap) }
        val logoOnly = item.kind == Kind.LIVE && item.logo != null
        h.img.scaleType = if (logoOnly) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        val pad = if (logoOnly) dp(c, 24) else 0
        h.img.setPadding(pad, pad / 2, pad, pad / 2)
        Images.card(h.img, item.cardArt, cleanTitle(item.name), w, ht)
        h.label.text = cleanTitle(item.name)
        h.sub.text = Card.sub(item)
        h.caption.animate().cancel(); h.caption.alpha = 0f
        h.itemView.animate().cancel(); h.itemView.alpha = if (row.focusedPos >= 0 && pos != row.focusedPos) Anim.dimAlpha else 1f
        h.rank.visibility = if (row.ranked) View.VISIBLE else View.GONE
        if (row.ranked) h.rank.text = "#${pos + 1}"
        h.bar.visibility = if (item.progress > 0f) View.VISIBLE else View.GONE
        if (item.progress > 0f) h.bar.layoutParams = (h.bar.layoutParams as FrameLayout.LayoutParams).also { it.width = (w * item.progress).toInt() }

        h.itemView.setOnFocusChangeListener { v, focused ->
            val s = if (focused) Anim.scale else 1f
            if (Anim.dur == 0L) { v.scaleX = s; v.scaleY = s } else v.animate().scaleX(s).scaleY(s).setDuration(Anim.dur).setInterpolator(Anim.interp).start()
            v.translationZ = if (focused) 10f else 0f
            h.frame.translationZ = if (focused) 12f else 0f          // soft shadow under the focused card
            fade(h.caption, if (focused) 1f else 0f)
            val rv = v.parent as? RecyclerView
            val p = h.bindingAdapterPosition
            if (focused && p >= 0 && p < row.items.size) {
                row.focusedPos = p
                rv?.let { list -> for (i in 0 until list.childCount) { val ch = list.getChildAt(i); fade(ch, if (ch === v) 1f else Anim.dimAlpha) } }
                host.onCardFocused(row, p, row.items[p])
            } else if (!focused) {
                row.focusedPos = -1
                rv?.post { if (row.focusedPos == -1) for (i in 0 until rv.childCount) fade(rv.getChildAt(i), 1f) }
            }
        }
        h.itemView.setOnKeyListener { _, code, e ->
            val p = h.bindingAdapterPosition
            if (e.action == KeyEvent.ACTION_DOWN && p >= 0) host.onCardKey(row, p, code, e) else false
        }
        h.itemView.setOnClickListener { val p = h.bindingAdapterPosition; if (p >= 0) host.onCardClick(row.items[p]) }
        h.itemView.setOnLongClickListener { val p = h.bindingAdapterPosition; if (p >= 0) host.onCardLongClick(row.items[p]); true }

        if (pos >= row.items.size - 12 && !row.done) loadMore()
        // Fill in TMDB art/plot for visible cards in the background (cached in SQLite; never blocks the UI).
        if (item.overview == null && item.kind != Kind.LIVE) Enricher.ensure(item) { fresh ->
            val i = row.items.indexOfFirst { it.id == fresh.id }
            if (i >= 0) { row.items[i] = fresh; notifyItemChanged(i) }
        }
    }

    override fun onViewRecycled(h: VH) {
        h.itemView.animate().cancel(); h.caption.animate().cancel()
        h.itemView.scaleX = 1f; h.itemView.scaleY = 1f; h.itemView.translationZ = 0f; h.itemView.alpha = 1f
        h.frame.translationZ = 0f; h.caption.alpha = 0f
    }

    fun loadMore() {
        if (row.loading || row.done) return
        row.loading = true
        host.baseActivity.scope.launch {
            val more = withContext(Dispatchers.IO) { runCatching { row.load(row.items.size, Card.PAGE) }.getOrDefault(emptyList()) }
            val start = row.items.size
            row.items.addAll(more)
            row.done = more.size < Card.PAGE
            row.loading = false
            row.started = true
            if (start == 0) notifyDataSetChanged() else notifyItemRangeInserted(start, more.size)
            if (row.items.isEmpty() && row.done) host.onRowEmpty(row)
        }
    }
}

class RowsAdapter(val rows: MutableList<RowDesc>, private val host: CardHost) : RecyclerView.Adapter<RowsAdapter.VH>() {
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.row_title)
        val list: RecyclerView = v.findViewById(R.id.row_list)
    }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
        val v = LayoutInflater.from(p.context).inflate(R.layout.item_row, p, false)
        val vh = VH(v)
        vh.list.layoutManager = LinearLayoutManager(p.context, RecyclerView.HORIZONTAL, false)
        vh.list.itemAnimator = null
        vh.list.setItemViewCacheSize(4)
        return vh
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val row = rows[pos]
        h.title.text = row.title
        val a = CardAdapter(row, host)
        h.list.adapter = a
        if (!row.started) a.loadMore() // lazy: only rows that get bound ever hit the database
    }

    /** Give focus to the remembered card of the row; retries while the row is still laid out / loading. */
    fun focusRow(vh: VH, row: RowDesc, tries: Int = 0) {
        val lm = vh.list.layoutManager as LinearLayoutManager
        if (row.items.isEmpty()) {
            if (tries < 20 && !row.done) vh.itemView.postDelayed({ focusRow(vh, row, tries + 1) }, 50)
            return
        }
        val pos = row.lastPos.coerceIn(0, row.items.size - 1)
        val target = lm.findViewByPosition(pos)
        if (target != null) { target.requestFocus(); return }
        vh.list.scrollToPosition(pos)
        if (tries < 10) vh.itemView.post { focusRow(vh, row, tries + 1) }
    }
}
