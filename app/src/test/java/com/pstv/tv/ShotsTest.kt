package com.pstv.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.EditText
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the real layouts with placeholder titles (no real content) to PNG previews. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi", application = App::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShotsTest {
    private val out = File(System.getProperty("shots.dir") ?: "/tmp/shots").apply { mkdirs() }

    private fun settle() { repeat(30) { Thread.sleep(60); shadowOf(Looper.getMainLooper()).idle() } }

    private fun shot(a: Activity, name: String) {
        settle()
        val v = a.window.decorView
        v.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1920, 1080)
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File(out, "$name.png").outputStream().use { Bitmap.createScaledBitmap(bmp, 960, 540, true).compress(Bitmap.CompressFormat.PNG, 80, it) }
    }

    private fun seed() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<App>()
        Db.replaceAll { sink ->
            for (g in listOf("News", "Sports", "Kids")) for (i in 1..12) sink(Item(0, Kind.LIVE, g, "Sample Channel $g $i", null, "http://x/live/$g$i", tvg = "ch$g$i".lowercase()))
            for (g in listOf("Action", "Drama")) for (i in 1..12) sink(Item(0, Kind.MOVIE, g, "Sample Movie $g $i (20${10 + i})", null, "http://x/movie/$g$i.mp4", year = 2010 + i, overview = "Placeholder plot text for layout preview only. It runs over a few lines so the hero plot area can be judged for spacing.", rating = 7.5f, genres = "Action, Drama"))
            for (i in 1..12) sink(Item(0, Kind.SERIES, "Shows", "Sample Series $i", null, "xs:$i"))
        }
        val now = System.currentTimeMillis(); val h = 3_600_000L; val base = now - now % h
        Db.replaceEpg { put -> for (g in listOf("News", "Sports", "Kids")) for (i in 1..12) { var t = base - h; var k = 0; while (t < base + 8 * h) { put("ch$g$i".lowercase(), t, t + (1 + (k + i) % 2) * h, "Sample Show ${k + 1}"); t += (1 + (k + i) % 2) * h; k++ } } }
        Db.touchLive("http://x/live/News2")
        Db.toggleFav("http://x/live/News1"); Db.toggleFav("http://x/movie/Action3.mp4")
        Db.saveProgress("http://x/movie/Drama2.mp4", 1_800_000, 6_000_000)
    }

    @Test fun render() {
        seed()
        val home = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        run {
            val rv = home.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rows)
            rv.getChildAt(0)?.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.row_list)?.getChildAt(0)?.let { it.onFocusChangeListener?.onFocusChange(it, true) }
        }
        shot(home, "1_home")
        fun tab(i: Int, name: String) { home.findViewById<android.widget.LinearLayout>(R.id.tabs).getChildAt(i).performClick(); shot(home, name) }
        tab(2, "5_live"); tab(3, "6_epg"); tab(4, "7_movies")
        run { val rv = home.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rows); rv.getChildAt(0)?.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.row_list)?.getChildAt(0)?.let { it.onFocusChangeListener?.onFocusChange(it, true) }; shot(home, "7_movies") }
        val search = Robolectric.buildActivity(SearchActivity::class.java).setup().get()
        search.findViewById<EditText>(R.id.query).setText("sample movie")
        shot(search, "2_search")
        shot(Robolectric.buildActivity(SettingsActivity::class.java).setup().get(), "3_settings")
        val d = Robolectric.buildActivity(DetailActivity::class.java, Intent(androidx.test.core.app.ApplicationProvider.getApplicationContext<App>(), DetailActivity::class.java).putExtra("url", "http://x/movie/Action3.mp4")).setup().get()
        shot(d, "4_detail")
        val act = Robolectric.buildActivity(SearchActivity::class.java).setup().get()
        val pl = android.view.LayoutInflater.from(act).inflate(R.layout.activity_player, null) as android.widget.FrameLayout
        pl.findViewById<View>(R.id.overlay).visibility = View.VISIBLE
        pl.findViewById<android.widget.TextView>(R.id.p_title).text = "Sample Series 4"
        pl.findViewById<android.widget.TextView>(R.id.p_sub).text = "Season 1 Episode 8"
        pl.findViewById<android.widget.TextView>(R.id.p_time).text = "00:04 / 27:36"
        pl.findViewById<android.widget.ProgressBar>(R.id.p_bar).progress = 120
        val chips = pl.findViewById<android.widget.LinearLayout>(R.id.p_chips)
        for (t in listOf("FULL HD", "HEVC", "23.98 Hz", "STEREO")) chips.addView(android.widget.TextView(act).apply { text = t; setBackgroundResource(R.drawable.chip_bg); setTextColor(-1); setPadding(20, 6, 20, 6); type(R.dimen.type_micro) })
        pl.findViewById<View>(R.id.side).visibility = View.VISIBLE
        pl.findViewById<android.widget.TextView>(R.id.side_title).text = "Subtitles"
        val sl = pl.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.side_list)
        sl.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(act)
        sl.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            val rows = listOf("✓   Off", "     Track 1 [English]", "     Find subtitles online…", "     Size:  100%")
            override fun getItemCount() = rows.size
            override fun onCreateViewHolder(p: android.view.ViewGroup, t: Int) = object : androidx.recyclerview.widget.RecyclerView.ViewHolder(android.widget.TextView(p.context).apply { setBackgroundResource(R.drawable.row_bg); setTextColor(-1); setPadding(30, 24, 30, 24); layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(-1, -2) }) {}
            override fun onBindViewHolder(h: androidx.recyclerview.widget.RecyclerView.ViewHolder, i: Int) { (h.itemView as android.widget.TextView).text = rows[i] }
        }
        pl.setBackgroundColor(0xFF2A3A4A.toInt())
        pl.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)); pl.layout(0, 0, 1920, 1080)
        settle()
        val pb = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888); pl.draw(Canvas(pb))
        File(out, "8_player.png").outputStream().use { Bitmap.createScaledBitmap(pb, 960, 540, true).compress(Bitmap.CompressFormat.PNG, 80, it) }
    }
}
