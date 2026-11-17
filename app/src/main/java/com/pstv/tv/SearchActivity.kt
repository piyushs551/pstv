package com.pstv.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchActivity : BaseActivity(), CardHost {
    override val baseActivity get() = this
    private val row = RowDesc("search", "Search") { _, _ -> emptyList() }.also { it.done = true; it.started = true }
    private val handler = Handler(Looper.getMainLooper())
    private var job: Job? = null
    private lateinit var results: RecyclerView
    private lateinit var count: TextView
    private lateinit var adapter: CardAdapter

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_search)
        results = findViewById(R.id.results)
        count = findViewById(R.id.count)
        results.layoutManager = GridLayoutManager(this, 6)
        adapter = CardAdapter(row, this) { Kind.MOVIE }
        results.adapter = adapter

        val q = findViewById<EditText>(R.id.query)
        q.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { handler.removeCallbacksAndMessages(null); handler.postDelayed({ run(s?.toString().orEmpty()) }, 250) }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        q.setOnKeyListener { _, code, e ->
            if (code == KeyEvent.KEYCODE_DPAD_DOWN && e.action == KeyEvent.ACTION_DOWN && row.items.isNotEmpty()) { results.getChildAt(0)?.requestFocus(); true } else false
        }
        q.requestFocus()
    }

    private fun run(text: String) {
        job?.cancel()
        job = scope.launch {
            val found = withContext(Dispatchers.IO) { Db.search(text) }
            row.items.clear(); row.items.addAll(found)
            adapter.notifyDataSetChanged()
            count.text = if (text.isBlank()) "" else "${found.size} result${if (found.size == 1) "" else "s"}"
        }
    }

    override fun onCardFocused(row: RowDesc, pos: Int, item: Item) {}
    override fun onCardKey(row: RowDesc, pos: Int, keyCode: Int, e: KeyEvent): Boolean {
        // From the first grid row, UP returns to the search box.
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && pos < 6) { findViewById<EditText>(R.id.query).requestFocus(); return true }
        return false
    }
    override fun onCardClick(item: Item) = Nav.open(this, item)
    override fun onCardLongClick(item: Item) { toast(if (Db.toggleFav(item.url)) "Added to Favorites" else "Removed from Favorites") }
}
