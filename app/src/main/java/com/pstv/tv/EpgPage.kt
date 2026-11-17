package com.pstv.tv

import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Guide grid: channel column + a fixed 2.5 h window (4 dp per minute). Left/Right at the edges shift the window
 * by one hour, so only a handful of blocks exist at any time (low RAM, no horizontal scroller to keep in sync).
 */
class EpgPage(private val act: HomeActivity, root: View) {
    private val rv: RecyclerView = root.findViewById(R.id.epg_rows)
    private val head: FrameLayout = root.findViewById(R.id.epg_head)
    private val msg: TextView = root.findViewById(R.id.epg_msg)

    private val minuteDp = 4
    private val windowMin = 180
    private val labelDp = 150
    private val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())

    private var channels: List<Item> = emptyList()
    private var progs: Map<String, List<Prog>> = emptyMap()
    private var winStart = 0L
    private var focusRow = 0

    init {
        rv.layoutManager = LinearLayoutManager(act)
        rv.itemAnimator = null
        rv.setItemViewCacheSize(4)
        rv.adapter = Adapter()
    }

    private fun roundDown(t: Long) = t - t % 1_800_000L
    private fun winEnd() = winStart + windowMin * 60_000L

    fun show(focus: Boolean) {
        if (winStart == 0L) winStart = roundDown(System.currentTimeMillis() - 30 * 60_000L)
        act.scope.launch {
            val only = Prefs.epgOnlyWithData
            val list = withContext(Dispatchers.IO) { Db.epgChannels(only, 0, 150) }
            channels = list
            if (list.isEmpty()) {
                msg.visibility = View.VISIBLE
                msg.text = if (Epg.url().isBlank()) "No guide source yet.\nOpen Settings > Guide (EPG) and enter an XMLTV URL. Xtream logins use the provider's guide automatically."
                else if (Epg.refreshing) "Downloading the guide…" else "No guide data for your channels yet. Settings > Guide (EPG) > Refresh guide now."
                rv.adapter?.notifyDataSetChanged()
                return@launch
            }
            msg.visibility = View.GONE
            loadWindow(then = { if (focus) focusFirst() })
        }
    }

    private fun loadWindow(then: (() -> Unit)? = null) {
        act.scope.launch {
            val ids = channels.mapNotNull { it.tvg }.distinct()
            progs = withContext(Dispatchers.IO) { Db.epgRange(ids, winStart, winEnd()) }
            buildHeader()
            rv.adapter?.notifyDataSetChanged()
            then?.invoke()
        }
    }

    private fun buildHeader() {
        head.removeAllViews()
        for (m in 0 until windowMin step 30) {
            head.addView(TextView(act).apply {
                text = fmt.format(Date(winStart + m * 60_000L)); textSize = 12f; setTextColor(act.getColor(R.color.text_dim))
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(act, labelDp + 24 + m * minuteDp) }
            })
        }
    }

    fun focusFirst() = focusBlock(focusRow, first = true)

    private fun focusBlock(row: Int, first: Boolean, tries: Int = 0) {
        (rv.layoutManager as LinearLayoutManager).scrollToPosition(row)
        val vh = rv.findViewHolderForAdapterPosition(row)
        val frame = (vh?.itemView as? LinearLayout)?.getChildAt(1) as? FrameLayout
        val target = frame?.let { if (first) it.getChildAt(0) else it.getChildAt(it.childCount - 1) }
        if (target != null) target.requestFocus() else if (tries < 8) rv.post { focusBlock(row, first, tries + 1) }
    }

    private fun shift(deltaMin: Int, row: Int, first: Boolean) {
        val ns = winStart + deltaMin * 60_000L
        val now = System.currentTimeMillis()
        if (ns < now - 3 * 3_600_000L || ns > now + 30 * 3_600_000L) return
        winStart = ns; focusRow = row
        loadWindow { focusBlock(row, first) }
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = channels.size

        override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
            val c = p.context
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 58))
                setPadding(dp(c, 24), 0, 0, 0)
                gravity = Gravity.CENTER_VERTICAL
            }
            val label = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(dp(c, labelDp), ViewGroup.LayoutParams.MATCH_PARENT)
                addView(ImageView(c).apply { id = R.id.card_img; layoutParams = LinearLayout.LayoutParams(dp(c, 44), dp(c, 30)); scaleType = ImageView.ScaleType.FIT_CENTER })
                addView(TextView(c).apply { id = R.id.card_label; textSize = 11f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(c.getColor(R.color.text)); setPadding(dp(c, 6), 0, dp(c, 4), 0) })
            }
            row.addView(label)
            row.addView(FrameLayout(c).apply { layoutParams = LinearLayout.LayoutParams(dp(c, windowMin * minuteDp), ViewGroup.LayoutParams.MATCH_PARENT) })
            return object : RecyclerView.ViewHolder(row) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            val c = act
            val ch = channels[pos]
            val row = h.itemView as LinearLayout
            val label = row.getChildAt(0) as LinearLayout
            Images.card(label.getChildAt(0) as ImageView, ch.logo, ch.name, dp(c, 44), dp(c, 30))
            (label.getChildAt(1) as TextView).text = cleanTitle(ch.name)
            val frame = row.getChildAt(1) as FrameLayout
            frame.removeAllViews()
            val list = ch.tvg?.let { progs[it] }.orEmpty()
            val ws = winStart; val we = winEnd()
            var added = 0
            for (p in list) {
                val s = maxOf(p.start, ws); val e = minOf(p.stop, we)
                val w = ((e - s) / 60_000.0 * minuteDp * c.resources.displayMetrics.density).toInt() - dp(c, 3)
                if (w < dp(c, 24)) continue
                frame.addView(block(c, ch, pos, p.title, "${fmt.format(Date(p.start))} - ${fmt.format(Date(p.stop))}", w, ((s - ws) / 60_000.0 * minuteDp * c.resources.displayMetrics.density).toInt()))
                added++
            }
            if (added == 0) frame.addView(block(c, ch, pos, "No schedule", "Play channel", dp(c, windowMin * minuteDp - 3), 0))
        }
    }

    private fun block(c: android.content.Context, ch: Item, pos: Int, title: String, time: String, w: Int, left: Int): View {
        return TextView(c).apply {
            text = "$title\n$time"; textSize = 11f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(c.getColor(R.color.text)); setBackgroundResource(R.drawable.row_bg); isFocusable = true
            setPadding(dp(c, 8), dp(c, 4), dp(c, 6), dp(c, 4)); gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(w, dp(c, 52)).apply { leftMargin = left; topMargin = dp(c, 3) }
            setOnFocusChangeListener { _, f -> if (f) focusRow = pos }
            setOnClickListener { Nav.play(act, ch) }
            setOnKeyListener { v, k, e ->
                if (e.action != KeyEvent.ACTION_DOWN) false
                else {
                    val parent = v.parent as FrameLayout
                    when (k) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> if (parent.indexOfChild(v) == 0) { shift(-60, pos, false); true } else false
                        KeyEvent.KEYCODE_DPAD_RIGHT -> if (parent.indexOfChild(v) == parent.childCount - 1) { shift(60, pos, true); true } else false
                        KeyEvent.KEYCODE_DPAD_UP -> if (pos == 0) { act.focusTabs(); true } else false
                        else -> false
                    }
                }
            }
        }
    }
}
