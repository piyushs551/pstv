package com.pstv.tv

import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Live tab: category sidebar on the left (with a check on the selected one), channel tiles on the right. */
class LivePage(private val act: HomeActivity, root: View) : CardHost {
    override val baseActivity get() = act

    private class Cat(val title: String, val load: (Int, Int) -> List<Item>)

    private val catsRv: RecyclerView = root.findViewById(R.id.live_cats)
    private val grid: RecyclerView = root.findViewById(R.id.live_grid)
    private val handler = Handler(Looper.getMainLooper())
    private var cats: List<Cat> = emptyList()
    private var selected = 0
    private var row: RowDesc? = null
    private val cols = 3

    init {
        catsRv.layoutManager = LinearLayoutManager(act)
        catsRv.itemAnimator = null
        grid.layoutManager = GridLayoutManager(act, cols)
        grid.itemAnimator = null
        catsRv.adapter = CatAdapter()
    }

    fun show(focus: Boolean) {
        act.scope.launch {
            val list = withContext(Dispatchers.IO) {
                val c = ArrayList<Cat>()
                if (Db.recentLive(1).isNotEmpty()) c += Cat("Recently watched channels") { o, _ -> if (o == 0) Db.recentLive() else emptyList() }
                c += Cat("Latest channels added") { o, l -> Db.latest(Kind.LIVE, o, l) }
                for (g in Db.groups(Kind.LIVE)) c += Cat(g.name) { o, l -> Db.page(Kind.LIVE, g.name, o, l) }
                c
            }
            cats = list
            selected = selected.coerceIn(0, maxOf(0, cats.size - 1))
            catsRv.adapter?.notifyDataSetChanged()
            loadSelected()
            if (focus) focusCat()
        }
    }

    private fun loadSelected() {
        val c = cats.getOrNull(selected) ?: return
        val r = RowDesc("live:${c.title}", c.title, load = c.load)
        row = r
        grid.adapter = CardAdapter(r, this).also { it.loadMore() }
        catsRv.adapter?.notifyDataSetChanged()
    }

    private fun focusCat() = catsRv.post { (catsRv.layoutManager as LinearLayoutManager).findViewByPosition(selected)?.requestFocus() }

    fun focusFirst() = focusCat()

    private fun focusGrid() { grid.post { grid.getChildAt(0)?.requestFocus() } }

    private inner class CatAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = cats.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
            val tv = TextView(p.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(context, 4) }
                setPadding(dp(context, 14), dp(context, 11), dp(context, 14), dp(context, 11))
                setBackgroundResource(R.drawable.cat_bg)
                isFocusable = true; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; type(R.dimen.type_body); gravity = Gravity.CENTER_VERTICAL
                setTextColor(resources.getColorStateList(R.drawable.tab_text, null))
            }
            return object : RecyclerView.ViewHolder(tv) {}
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            val tv = h.itemView as TextView
            tv.isActivated = i == selected
            tv.text = cats[i].title + if (i == selected) "   ✓" else ""
            tv.setOnFocusChangeListener { _, f ->
                if (f && i != selected) {
                    handler.removeCallbacksAndMessages(null)
                    handler.postDelayed({ selected = i; loadSelected() }, 220)
                }
            }
            tv.setOnClickListener { if (i != selected) { selected = i; loadSelected() }; focusGrid() }
            tv.setOnKeyListener { _, k, e ->
                if (e.action != KeyEvent.ACTION_DOWN) false
                else when (k) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { if (i != selected) { handler.removeCallbacksAndMessages(null); selected = i; loadSelected() }; focusGrid(); true }
                    KeyEvent.KEYCODE_DPAD_UP -> if (i == 0) { act.focusTabs(); true } else false
                    KeyEvent.KEYCODE_DPAD_LEFT -> true
                    else -> false
                }
            }
        }
    }

    // ---- tiles ----------------------------------------------------------------------------------

    override fun onCardClick(item: Item) = Nav.open(act, item)
    override fun onCardLongClick(item: Item) { act.toast(if (Db.toggleFav(item.url)) "Added to Favorites" else "Removed from Favorites") }

    override fun onCardKey(row: RowDesc, pos: Int, keyCode: Int, e: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> { if (pos % cols == 0) { focusCat(); true } else false }
        KeyEvent.KEYCODE_DPAD_UP -> { if (pos < cols) { act.focusTabs(); true } else false }
        else -> false
    }
}
