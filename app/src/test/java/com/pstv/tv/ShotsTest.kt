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
            for (g in listOf("News", "Sports", "Kids")) for (i in 1..12) sink(Item(0, Kind.LIVE, g, "Sample Channel $g $i", null, "http://x/live/$g$i"))
            for (g in listOf("Action", "Drama")) for (i in 1..12) sink(Item(0, Kind.MOVIE, g, "Sample Movie $g $i (20${10 + i})", null, "http://x/movie/$g$i.mp4", year = 2010 + i, overview = "Placeholder plot text for layout preview only."))
            for (i in 1..12) sink(Item(0, Kind.SERIES, "Shows", "Sample Series $i", null, "xs:$i"))
        }
        Db.toggleFav("http://x/live/News1"); Db.toggleFav("http://x/movie/Action3.mp4")
        Db.saveProgress("http://x/movie/Drama2.mp4", 1_800_000, 6_000_000)
    }

    @Test fun render() {
        seed()
        val home = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        shot(home, "1_home")
        val search = Robolectric.buildActivity(SearchActivity::class.java).setup().get()
        search.findViewById<EditText>(R.id.query).setText("sample movie")
        shot(search, "2_search")
        shot(Robolectric.buildActivity(SettingsActivity::class.java).setup().get(), "3_settings")
        val d = Robolectric.buildActivity(DetailActivity::class.java, Intent(androidx.test.core.app.ApplicationProvider.getApplicationContext<App>(), DetailActivity::class.java).putExtra("url", "http://x/movie/Action3.mp4")).setup().get()
        shot(d, "4_detail")
    }
}
