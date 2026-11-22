package com.pstv.tv

import android.os.Bundle
import android.view.KeyEvent
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FavoritesActivity : BaseActivity(), CardHost {
    override val baseActivity get() = this
    private val row = RowDesc("favs", "Favorites") { _, _ -> emptyList() }.also { it.done = true; it.started = true }
    private lateinit var grid: RecyclerView
    private lateinit var adapter: CardAdapter

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_favorites)
        grid = findViewById(R.id.fav_grid)
        grid.layoutManager = GridLayoutManager(this, 4)
        grid.itemAnimator = null
        adapter = CardAdapter(row, this)
        grid.adapter = adapter
        reload(focus = true)
    }

    private fun reload(focus: Boolean = false) {
        scope.launch {
            val f = withContext(Dispatchers.IO) { Db.favorites(500) }
            row.items.clear(); row.items.addAll(f); adapter.notifyDataSetChanged()
            findViewById<TextView>(R.id.fav_msg).text = if (f.isEmpty()) "No favorites yet. Press and hold OK on any card to add one." else "Press and hold OK on a card for options, where you can remove it."
            if (focus) grid.post { grid.getChildAt(0)?.requestFocus() }
        }
    }

    override fun onCardClick(item: Item) = Nav.open(this, item)
    override fun onCardLongClick(item: Item) = Nav.info(this, item) { reload() }
    override fun onCardMenu(row: RowDesc, item: Item, anchor: android.view.View) = CardMenu.show(this, anchor, CardMenu.forItem(this, item) { reload(true) })
}
