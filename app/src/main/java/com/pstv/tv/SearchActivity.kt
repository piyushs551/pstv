package com.pstv.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Search: pill field, filter chips, recent searches, results grouped as Movies / TV shows / Channels (rows of cards). */
class SearchActivity : BaseActivity(), CardHost {
    override val baseActivity get() = this
    private lateinit var q: EditText
    private lateinit var rv: RecyclerView
    private lateinit var chips: List<TextView>
    private lateinit var recentBox: android.widget.LinearLayout
    private lateinit var recentLabel: View
    private lateinit var recentScroll: View
    private lateinit var emptyMsg: TextView
    private var adapter: RowsAdapter? = null
    private var rows = ArrayList<RowDesc>()
    private var filter = 0                                   // 0 All, 1 Movies, 2 TV shows, 3 Live
    private val handler = Handler(Looper.getMainLooper())
    private var job: Job? = null
    private var navBusyUntil = 0L

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_search)
        q = findViewById(R.id.query); rv = findViewById(R.id.rows)
        recentBox = findViewById(R.id.recent_box); recentLabel = findViewById(R.id.recent_label); recentScroll = findViewById(R.id.recent_scroll)
        emptyMsg = findViewById(R.id.empty_msg)
        rv.layoutManager = LinearLayoutManager(this); rv.itemAnimator = null
        chips = listOf(R.id.chip_all, R.id.chip_movies, R.id.chip_shows, R.id.chip_live).map { findViewById<TextView>(it) }
        chips.forEachIndexed { i, c ->
            c.setOnClickListener { setFilter(i) }
            c.setOnKeyListener { _, k, e ->
                if (e.action != KeyEvent.ACTION_DOWN) false
                else when (k) { KeyEvent.KEYCODE_DPAD_UP -> { q.requestFocus(); true }; KeyEvent.KEYCODE_DPAD_DOWN -> { focusRow(0); true }; else -> false }
            }
        }
        setFilter(0, run = false)
        q.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { handler.removeCallbacksAndMessages(null); handler.postDelayed({ run() }, 250) }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        q.setOnEditorActionListener { _, _, _ ->
            remember(q.text.toString())
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(q.windowToken, 0)
            run(); focusRow(0); true
        }
        q.setOnKeyListener { _, code, e -> if (code == KeyEvent.KEYCODE_DPAD_DOWN && e.action == KeyEvent.ACTION_DOWN) { chips[filter].requestFocus(); true } else false }
        showRecent()
        run()
        q.requestFocus()
    }

    // ---- state ----------------------------------------------------------------------------------

    private fun setFilter(i: Int, run: Boolean = true) {
        filter = i
        chips.forEachIndexed { k, c -> c.isActivated = k == i }
        if (run) run()
    }

    private fun recents(): List<String> = Prefs.rawGet("recent_searches").split('\n').filter { it.isNotBlank() }

    private fun remember(text: String) {
        val t = text.trim(); if (t.length < 2) return
        Prefs.rawPut("recent_searches", (listOf(t) + recents().filter { !it.equals(t, true) }).take(8).joinToString("\n"))
        showRecent()
    }

    private fun showRecent() {
        recentBox.removeAllViews()
        val list = recents()
        for (s in list) recentBox.addView(TextView(this).apply {
            text = s; setBackgroundResource(R.drawable.tab_bg); setTextColor(resources.getColorStateList(R.drawable.tab_text, null)); type(R.dimen.type_caption)
            typeface = Fonts.medium(); isFocusable = true; setPadding(dp(context, 12), dp(context, 5), dp(context, 12), dp(context, 5))
            layoutParams = android.widget.LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(context, 6) }
            setOnClickListener { q.setText(s); q.setSelection(s.length); run() }
        })
        val show = list.isNotEmpty() && q.text.isNullOrBlank()
        recentLabel.visibility = if (show) View.VISIBLE else View.GONE
        recentScroll.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun run() {
        showRecent()
        val text = q.text.toString().trim()
        job?.cancel()
        job = scope.launch {
            val built = withContext(Dispatchers.IO) { if (text.length < 2) suggestions() else results(text) }
            emptyMsg.visibility = if (built.isEmpty()) View.VISIBLE else View.GONE
            emptyMsg.text = "No results for “$text”. Try fewer letters, or check the filter above."
            rows = ArrayList(built)
            adapter = RowsAdapter(rows, this@SearchActivity).also { rv.adapter = it }
        }
    }

    private fun results(text: String): List<RowDesc> {
        val found = Db.search(text, 300)
        fun row(key: String, title: String, kind: Kind): RowDesc? {
            val l = found.filter { it.kind == kind }
            return if (l.isEmpty()) null else RowDesc(key, "$title  ·  ${l.size}") { o, _ -> if (o == 0) l else emptyList() }
        }
        return listOfNotNull(
            if (filter == 0 || filter == 1) row("m", "Movies", Kind.MOVIE) else null,
            if (filter == 0 || filter == 2) row("s", "TV shows", Kind.SERIES) else null,
            if (filter == 0 || filter == 3) row("l", "Channels", Kind.LIVE) else null,
        )
    }

    /** Nothing typed yet: suggestions (TMDB trending if available, plus what was added recently). */
    private fun suggestions(): List<RowDesc> {
        val out = ArrayList<RowDesc>()
        if ((filter == 0 || filter == 1)) {
            if (Tmdb.enabled) out += RowDesc("tm", "Trending movies", ranked = true, lazy = true) { o, _ -> if (o == 0) Tmdb.trendingInPlaylist(Kind.MOVIE) else emptyList() }
            out += RowDesc("nm", "Recently added movies") { o, l -> Db.latest(Kind.MOVIE, o, l) }
        }
        if ((filter == 0 || filter == 2)) {
            if (Tmdb.enabled) out += RowDesc("ts", "Trending series", ranked = true, lazy = true) { o, _ -> if (o == 0) Tmdb.trendingInPlaylist(Kind.SERIES) else emptyList() }
            out += RowDesc("ns", "Recently added series") { o, l -> Db.latest(Kind.SERIES, o, l) }
        }
        if (filter == 3) out += RowDesc("nl", "Latest channels") { o, l -> Db.latest(Kind.LIVE, o, l) }
        return out
    }

    // ---- focus ----------------------------------------------------------------------------------

    private fun focusRow(i: Int, tries: Int = 0) {
        val a = adapter ?: return
        if (i !in rows.indices) return
        (rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(i, 0)
        val vh = rv.findViewHolderForAdapterPosition(i) as? RowsAdapter.VH
        if (vh == null) { if (tries < 8) rv.post { focusRow(i, tries + 1) }; return }
        a.focusRow(vh, rows[i])
    }

    override fun onCardKey(row: RowDesc, pos: Int, keyCode: Int, e: KeyEvent): Boolean {
        val idx = rows.indexOf(row)
        val now = android.os.SystemClock.uptimeMillis()
        if (e.repeatCount > 0 && now < navBusyUntil) return true
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> { navBusyUntil = now + 170; focusRow(idx + 1); true }
            KeyEvent.KEYCODE_DPAD_UP -> { navBusyUntil = now + 170; if (idx <= 0) chips[filter].requestFocus() else focusRow(idx - 1); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (pos > 0) { navBusyUntil = now + 70; (currentFocus?.parent as? RecyclerView)?.let { moveInRow(it, pos - 1) } }; true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (pos < row.items.size - 1) { navBusyUntil = now + 70; (currentFocus?.parent as? RecyclerView)?.let { moveInRow(it, pos + 1) } }; true }
            else -> false
        }
    }

    override fun onCardClick(item: Item) { remember(q.text.toString()); Nav.open(this, item) }
    override fun onCardLongClick(item: Item) = Nav.info(this, item)
}
