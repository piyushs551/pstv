package com.pstv.tv

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One horizontal row. Items load lazily (only when the row is first bound) and page in as you scroll. */
class RowDesc(val key: String, val title: String, val load: (offset: Int, limit: Int) -> List<Item>) {
    val items = ArrayList<Item>()
    var loading = false
    var done = false
    var started = false
    var lastPos = 0
}

interface CardHost {
    val baseActivity: BaseActivity
    fun onCardFocused(row: RowDesc, pos: Int, item: Item)
    fun onCardKey(row: RowDesc, pos: Int, keyCode: Int, e: KeyEvent): Boolean
    fun onCardClick(item: Item)
    fun onCardLongClick(item: Item)
}

object Card {
    const val PAGE = 100
    fun width(c: android.content.Context, kind: Kind) = dp(c, if (kind == Kind.LIVE) 132 else 104)
    fun height(c: android.content.Context, kind: Kind) = dp(c, if (kind == Kind.LIVE) 74 else 156)
}

class CardAdapter(private val row: RowDesc, private val host: CardHost, private val rowKindHint: () -> Kind) :
    RecyclerView.Adapter<CardAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.card_img)
        val label: TextView = v.findViewById(R.id.card_label)
        val bar: View = v.findViewById(R.id.card_bar)
    }

    override fun getItemCount() = row.items.size
    override fun getItemId(position: Int) = row.items[position].id

    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_card, p, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = row.items[pos]
        val c = h.itemView.context
        val w = Card.width(c, item.kind)
        val ht = Card.height(c, item.kind)
        h.itemView.layoutParams = RecyclerView.LayoutParams(w, ht).apply { marginEnd = dp(c, 8) }
        h.img.scaleType = if (item.kind == Kind.LIVE && item.poster == null) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        h.img.setPadding(if (item.kind == Kind.LIVE && item.poster == null) dp(c, 14) else 0, 0, if (item.kind == Kind.LIVE && item.poster == null) dp(c, 14) else 0, 0)
        Images.card(h.img, item.cardArt, item.name, w, ht)
        h.label.text = cleanTitle(item.name)
        h.bar.visibility = if (item.progress > 0f) View.VISIBLE else View.GONE
        if (item.progress > 0f) h.bar.layoutParams = (h.bar.layoutParams as FrameLayout.LayoutParams).also { it.width = (w * item.progress).toInt() }

        h.itemView.setOnFocusChangeListener { v, focused ->
            v.animate().scaleX(if (focused) 1.08f else 1f).scaleY(if (focused) 1.08f else 1f).setDuration(100).start()
            v.translationZ = if (focused) 10f else 0f
            val p = h.bindingAdapterPosition
            if (focused && p >= 0 && p < row.items.size) host.onCardFocused(row, p, row.items[p])
        }
        h.itemView.setOnKeyListener { _, code, e ->
            val p = h.bindingAdapterPosition
            if (e.action == KeyEvent.ACTION_DOWN && p >= 0) host.onCardKey(row, p, code, e) else false
        }
        h.itemView.setOnClickListener { val p = h.bindingAdapterPosition; if (p >= 0) host.onCardClick(row.items[p]) }
        h.itemView.setOnLongClickListener { val p = h.bindingAdapterPosition; if (p >= 0) host.onCardLongClick(row.items[p]); true }

        if (pos >= row.items.size - 12 && !row.done) loadMore()
    }

    override fun onViewRecycled(h: VH) {
        h.itemView.animate().cancel()
        h.itemView.scaleX = 1f; h.itemView.scaleY = 1f; h.itemView.translationZ = 0f
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
        }
    }
}

class RowsAdapter(val rows: List<RowDesc>, private val host: CardHost) : RecyclerView.Adapter<RowsAdapter.VH>() {
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.row_title)
        val list: RecyclerView = v.findViewById(R.id.row_list)
        var adapter: CardAdapter? = null
    }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
        val v = LayoutInflater.from(p.context).inflate(R.layout.item_row, p, false)
        val vh = VH(v)
        vh.list.layoutManager = LinearLayoutManager(p.context, RecyclerView.HORIZONTAL, false)
        vh.list.itemAnimator = null
        vh.list.setHasFixedSize(false)
        vh.list.setItemViewCacheSize(4)
        return vh
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val row = rows[pos]
        h.title.text = row.title
        val a = CardAdapter(row, host) { Kind.MOVIE }
        h.adapter = a
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
