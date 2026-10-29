package app.cinemana

import app.cinemana.dl.*
import org.junit.Assert.*
import org.junit.Test

class EngineTest {
    @Test fun planCoversFileWithoutGaps() {
        val total = 100L shl 20; val p = Planner.plan(total, 8)
        assertEquals(8, p.size); assertEquals(0L, p.first().first); assertEquals(total - 1, p.last().last)
        p.zipWithNext { a, b -> assertEquals(a.last + 1, b.first) }
    }
    @Test fun planHandlesUnevenSizes() { val p = Planner.plan(50_000_003L, 7); assertEquals(50_000_003L, p.sumOf { it.last - it.first + 1 }) }
    @Test fun smallFilesUseOneConnection() = assertEquals(1, Planner.plan(1L shl 20, 8).size)
    @Test fun stateRoundTrips() {
        val s = Saved(1000, true, listOf(Seg(0, 499, 120), Seg(500, 999, 1000)))
        val d = SegState.decode(SegState.encode(s))!!
        assertEquals(1000L, d.total); assertTrue(d.ranged); assertEquals(120L, d.segs[0].pos); assertEquals(0L, d.segs[1].left)
    }
    @Test fun corruptStateIsIgnored() = assertNull(SegState.decode("garbage"))
    @Test fun speedAndEta() {
        var t = 0L; val m = SpeedMeter(0) { t }
        t = 1_000_000_000; assertEquals(1000.0, m.sample(1000), 0.01); assertEquals(5L, m.etaSeconds(5000))
    }
    @Test fun etaUnknownBeforeFirstSample() = assertEquals(-1L, SpeedMeter().etaSeconds(100))
    @Test fun formatting() {
        assertEquals("1.5 KB", Fmt.bytes(1536)); assertEquals("01:15", Fmt.eta(75)); assertEquals("1:02:05", Fmt.eta(3725)); assertEquals("--:--", Fmt.eta(-1))
    }
}
