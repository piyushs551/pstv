package com.pstv.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartTest {
    @Test fun bollywoodComedy() {
        val q = Smart.parse("bollywood comedy")!!
        assertEquals("hi", q.lang); assertEquals(listOf("comedy"), q.genres); assertTrue(q.hasFilters); assertTrue(q.kinds.isEmpty())
    }
    @Test fun christmasMoviesIsAKeywordSearchForMovies() {
        val q = Smart.parse("christmas movies")!!
        assertEquals(setOf(Kind.MOVIE), q.kinds); assertEquals(listOf("christmas"), q.keywords); assertFalse(q.hasFilters)
    }
    @Test fun hollywoodAction() { val q = Smart.parse("Hollywood action")!!; assertEquals("US", q.country); assertEquals(listOf("action"), q.genres) }
    @Test fun decadeAndLanguage() {
        val q = Smart.parse("90s hindi romance")!!
        assertEquals(1990, q.yearFrom); assertEquals(1999, q.yearTo); assertEquals("hi", q.lang); assertEquals(listOf("romance"), q.genres)
    }
    @Test fun latestSortsByDate() { assertEquals("date", Smart.parse("latest bollywood")!!.sort) }
    @Test fun noThemeMeansNull() { assertNull(Smart.parse("the")); assertNull(Smart.parse("movies")) }
}
