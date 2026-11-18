package com.pstv.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyTest {
    private fun ok(a: String, b: String, ya: Int? = null, yb: Int? = null) = fuzzyAccept(fuzzyTokens(normTitle(a)), fuzzyTokens(normTitle(b)), ya, yb)

    @Test fun spacingAndHyphen() = assertTrue(ok("Spider-Man: No Way Home", "Spiderman No Way Home"))
    @Test fun yearAndLanguageTagsIgnored() { assertTrue(ok("Kal Ho Naa Ho", "Kal Ho Naa Ho 2003 Hindi HD")); assertTrue(ok("Inception", "Inception 2010 4K")) }
    @Test fun leadingTheIgnored() = assertTrue(ok("The Matrix", "Matrix"))
    @Test fun sequelsAreNotTheSameTitle() {
        assertFalse(ok("The Dark Knight", "The Dark Knight Rises"))
        assertFalse(ok("The Dark Knight", "The Dark Knight Rises", 2008, 2012))
        assertFalse(ok("Avatar", "Avatar The Way of Water"))
    }
    @Test fun missingSubtitleOnlyWhenYearsAgree() {
        assertFalse(ok("Mission: Impossible - Dead Reckoning Part One", "Mission Impossible Dead Reckoning"))
        assertTrue(ok("Mission: Impossible - Dead Reckoning Part One", "Mission Impossible Dead Reckoning", 2023, 2023))
    }
    @Test fun differentYearsRejected() = assertFalse(ok("Dune", "Dune", 2021, 1984))
}
