package com.pstv.tv

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class)
class CategoriesTest {
    private fun g(n: String) = Group(Kind.MOVIE, n, 1)

    @Test fun smartOrderPutsHindiAndIndianFirstThenNewestYear() {
        Prefs.catSmart = true
        val out = Categories.smartSort(listOf(g("ENGLISH | MOVIES"), g("INDIA | HINDI 2022"), g("HINDI MOVIES 2024"), g("TAMIL 2023"), g("BOLLYWOOD"), g("ENGLISH 2025"))).map { it.name }
        assertEquals(listOf("HINDI MOVIES 2024", "INDIA | HINDI 2022", "BOLLYWOOD", "TAMIL 2023", "ENGLISH 2025", "ENGLISH | MOVIES"), out)
    }

    @Test fun smartOrderCanBeSwitchedOff() {
        Prefs.catSmart = false
        val src = listOf(g("B"), g("HINDI"), g("A"))
        assertEquals(src, Categories.smartSort(src))
        Prefs.catSmart = true
    }
}
