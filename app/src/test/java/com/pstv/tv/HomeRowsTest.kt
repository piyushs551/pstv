package com.pstv.tv

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class)
class HomeRowsTest {
    @Test fun oldContinueWatchingRowBecomesMoviesAndSeries() {
        Prefs.rawPut("homerows", "recent,!favs,newm")
        assertEquals("recentm,recents,!favs,newm", Prefs.homeRows)
        Prefs.rawPut("homerows", "trendm,!recent")
        assertEquals("trendm,!recentm,!recents", Prefs.homeRows)
    }

    @Test fun qualityTagsAreFoundInTitles() {
        assertEquals("UHD 4K", qualityTag("Some Movie UHD 4K (2020)"))
        assertEquals("CAM", qualityTag("Some Movie CAM"))
        assertEquals(null, qualityTag("Plain Title"))
    }
}
