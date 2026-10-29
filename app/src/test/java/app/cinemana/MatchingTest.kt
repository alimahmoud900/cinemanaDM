package app.cinemana

import org.junit.Assert.*
import org.junit.Test

class MatchingTest {
    private val eps = listOf(Episode("1", "a", 1, 1), Episode("2", "b", 1, 2), Episode("3", "c", 2, 1), Episode("4", "d", 1, 3))

    @Test fun normalizeStripsAccentsAndPunctuation() = assertEquals("cafe batman", Matching.normalize("  Café: BATMAN! "))
    @Test fun normalizeKeepsArabic() = assertEquals("الرجل الوطواط", Matching.normalize("الرجل-الوطواط"))
    @Test fun splitYearParsesTrailingYear() { assertEquals("Batman" to 2022, Matching.splitYear("Batman (2022)")); assertEquals("2012" to null, Matching.splitYear("2012")) }
    @Test fun imdbMatchIsExact() = assertEquals(100.0, Matching.score("x", null, Title("1", "Other", null, "1999", "tt1"), "TT1"), 0.0)
    @Test fun yearToleranceIsPlusMinusOne() {
        val t = Title("1", "Dune", null, "2021", null)
        assertTrue(Matching.score("Dune", 2022, t) > Matching.score("Dune", 2024, t))
    }
    @Test fun exactTitleBeatsPartial() =
        assertTrue(Matching.score("Dune", null, Title("1", "Dune", null, null, null)) > Matching.score("Dune", null, Title("2", "Dune Part Two", null, null, null)))
    @Test fun episodeSelection() {
        assertEquals(listOf("1", "2", "4"), Matching.selectEpisodes("1-3", eps).filter { it.season == 1 }.map { it.id })
        assertEquals(listOf("3"), Matching.selectEpisodes("S02E01", eps).map { it.id })
    }
    @Test fun safeNameRemovesIllegalChars() = assertEquals("a b c", Matching.safeName("a:b/c"))
    @Test fun qualityPicksNumber() = assertEquals(1080, Matching.quality(Source("1080p", "u")))
    private val srcs = listOf(Source("480p", "a"), Source("1080p", "b"), Source("720p", "c"))
    @Test fun pickSourceByName() { assertEquals("c", Matching.pickSource(srcs, "720")!!.url); assertEquals("b", Matching.pickSource(srcs, "Highest")!!.url); assertEquals("a", Matching.pickSource(srcs, "Lowest")!!.url) }
    @Test fun pickSourceFallsBackToHighest() { assertEquals("b", Matching.pickSource(srcs, "360")!!.url); assertNull(Matching.pickSource(emptyList(), "720")) }
    private val subs = listOf(Sub("ar", "u1", "srt"), Sub("en", "u2", "vtt"), Sub("fr", "u3", "srt"), Sub("ar", "u4", "vtt"))
    @Test fun subtitleModes() {
        assertEquals(listOf("ar", "en"), Matching.pickSubs(subs, "Arabic + English").map { it.lang })
        assertEquals(listOf("en"), Matching.pickSubs(subs, "English").map { it.lang })
        assertTrue(Matching.pickSubs(subs, "None").isEmpty())
        assertEquals(listOf("ar", "en", "fr"), Matching.pickSubs(subs, "All languages").map { it.lang })
    }
    @Test fun episodeFileTemplate() {
        val e = Episode("1", "Pilot", 1, 3)
        assertEquals("Show - S01E03", Matching.episodeFile(Matching.DEFAULT_TEMPLATE, "Show", e))
        assertEquals("Show 1x03 Pilot", Matching.episodeFile("{title} 1x{e} {name}", "Show", e))
    }
}
