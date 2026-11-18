package com.pstv.tv

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings > Categories: reorder or hide the category rows of Live, Movies or TV shows. */
class CategoriesActivity : BaseActivity() {
    private lateinit var kind: Kind
    private var groups: MutableList<Group> = ArrayList()
    private val hidden = HashSet<String>()
    private lateinit var list: RecyclerView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_categories)
        kind = Kind.values()[intent.getIntExtra("kind", 1)]
        findViewById<TextView>(R.id.cat_title).text = when (kind) { Kind.LIVE -> "Live categories"; Kind.MOVIE -> "Movie categories"; Kind.SERIES -> "TV show categories" }
        list = findViewById(R.id.cat_list)
        list.layoutManager = LinearLayoutManager(this)
        list.itemAnimator = null
        scope.launch {
            val all = withContext(Dispatchers.IO) { Categories.apply(kind, Db.groups(kind), includeHidden = true) }
            groups = all.toMutableList(); hidden.addAll(Categories.hidden(kind))
            list.adapter = Adapter()
            list.post { (list.layoutManager as LinearLayoutManager).findViewByPosition(0)?.requestFocus() }
        }
    }

    private fun persist() = Categories.save(kind, groups.map { it.name }, hidden)

    private fun move(from: Int, to: Int) {
        val t = to.coerceIn(0, groups.size - 1)
        if (t == from) return
        groups.add(t, groups.removeAt(from)); persist()
        list.adapter?.notifyDataSetChanged()
        refocus(t)
    }

    private fun refocus(pos: Int, tries: Int = 0) {
        val lm = list.layoutManager as LinearLayoutManager
        val v = lm.findViewByPosition(pos)
        if (v != null) { v.requestFocus(); return }
        list.scrollToPosition(pos)
        if (tries < 5) list.post { refocus(pos, tries + 1) }
    }

    private fun options(pos: Int) {
        val g = groups[pos]
        val isHidden = g.name in hidden
        AlertDialog.Builder(this, R.style.Theme_Pstv_Dialog).setTitle(g.name)
            .setItems(arrayOf("Move to top", "Move up", "Move down", "Move to bottom", if (isHidden) "Show" else "Hide", "Reset all to the playlist's order")) { _, i ->
                when (i) {
                    0 -> move(pos, 0)
                    1 -> move(pos, pos - 1)
                    2 -> move(pos, pos + 1)
                    3 -> move(pos, groups.size - 1)
                    4 -> { if (isHidden) hidden.remove(g.name) else hidden.add(g.name); persist(); list.adapter?.notifyItemChanged(pos); refocus(pos) }
                    5 -> scope.launch {
                        Categories.reset(kind); hidden.clear()
                        groups = withContext(Dispatchers.IO) { Db.groups(kind) }.toMutableList()
                        list.adapter?.notifyDataSetChanged(); refocus(0)
                    }
                }
            }.show()
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = groups.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
            val tv = TextView(p.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(context, 4) }
                setPadding(dp(context, 14), dp(context, 9), dp(context, 14), dp(context, 9))
                setBackgroundResource(R.drawable.row_bg); isFocusable = true; maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER_VERTICAL; type(R.dimen.type_body)
                setTextColor(getColor(R.color.text))
            }
            return object : RecyclerView.ViewHolder(tv) {}
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            val g = groups[i]; val tv = h.itemView as TextView
            val hid = g.name in hidden
            tv.text = "${i + 1}.  ${g.name}    ·  ${g.count}" + if (hid) "    (hidden)" else ""
            tv.alpha = if (hid) 0.5f else 1f
            tv.setOnClickListener { val p = h.bindingAdapterPosition; if (p >= 0) options(p) }
        }
    }
}
