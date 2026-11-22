package com.pstv.tv

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/** Small options menu that floats next to a card (press and hold OK): Play, remove from list, Favorites, Info. */
object CardMenu {
    class Entry(val label: String, val run: () -> Unit)

    /** Standard entries for a title, with [extra] (e.g. "Remove from Continue watching") right after Play. */
    fun forItem(act: BaseActivity, item: Item, extra: List<Entry> = emptyList(), after: () -> Unit = {}): List<Entry> {
        val out = ArrayList<Entry>()
        out += Entry(if (item.kind == Kind.SERIES) "Open" else "Play") { Nav.open(act, item) }
        out += extra
        out += Entry(if (Db.isFav(item.url)) "Remove from Favorites" else "Add to Favorites") {
            act.toast(if (Db.toggleFav(item.url)) "Added to Favorites" else "Removed from Favorites"); after()
        }
        if (item.kind != Kind.LIVE) out += Entry("Info") { Nav.info(act, item, after) }
        return out
    }

    fun show(act: BaseActivity, anchor: View, entries: List<Entry>) {
        if (entries.isEmpty()) return
        val c = act
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.frost_panel)
            setPadding(dp(c, 8), dp(c, 8), dp(c, 8), dp(c, 8))
        }
        val w = dp(c, 250)
        val pw = PopupWindow(box, w, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pw.isOutsideTouchable = true
        for (e in entries) box.addView(TextView(c).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(c, 3) }
            setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10))
            setBackgroundResource(R.drawable.row_bg)
            isFocusable = true; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; type(R.dimen.type_body); gravity = Gravity.CENTER_VERTICAL
            setTextColor(c.getColor(R.color.text)); text = e.label
            setOnClickListener { pw.dismiss(); e.run() }
            setOnKeyListener { _, k, ev -> if (ev.action == KeyEvent.ACTION_DOWN && (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT)) { pw.dismiss(); true } else false }
        })
        box.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val loc = IntArray(2); anchor.getLocationOnScreen(loc)
        val sw = c.resources.displayMetrics.widthPixels; val sh = c.resources.displayMetrics.heightPixels
        val m = dp(c, 16); val gap = dp(c, 10)
        var x = loc[0] + anchor.width + gap
        if (x + w > sw - m) x = loc[0] - w - gap
        x = x.coerceIn(m, maxOf(m, sw - w - m))
        val y = loc[1].coerceIn(m, maxOf(m, sh - box.measuredHeight - m))
        pw.showAtLocation(anchor, Gravity.TOP or Gravity.START, x, y)
        box.post { box.getChildAt(0)?.requestFocus() }
    }
}
