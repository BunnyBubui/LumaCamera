package th.ogay.luma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipMotionTest {
    @Test fun zoomsStartAndEndWhereTheyShould() {
        assertEquals(1f, ClipMotion.at(ClipMotion.ZOOM_IN, 0f).first, 1e-4f)
        assertEquals(1.15f, ClipMotion.at(ClipMotion.ZOOM_IN, 1f).first, 1e-4f)
        assertEquals(1.15f, ClipMotion.at(ClipMotion.ZOOM_OUT, 0f).first, 1e-4f)
        assertEquals(1f, ClipMotion.at(ClipMotion.NONE, 0.5f).first, 0f)
    }
    @Test fun pansNeverShowAnEdge() {
        for (m in listOf(ClipMotion.PAN_LEFT, ClipMotion.PAN_RIGHT)) for (i in 0..20) {
            val (s, x) = ClipMotion.at(m, i / 20f)
            assertTrue("edge at $i", kotlin.math.abs(x) <= s - 1f + 1e-4f)
        }
        assertTrue(ClipMotion.at(ClipMotion.PAN_LEFT, 0f).second > 0f && ClipMotion.at(ClipMotion.PAN_LEFT, 1f).second < 0f)
    }
    @Test fun backwardsFramesRunFromTheEnd() {
        val t = ClipMaker.backwardsTimes(1_000, 2_000)
        assertEquals(30, t.size); assertEquals(1_999L, t.first()); assertTrue(t.last() >= 1_000); assertTrue(t.zipWithNext().all { (a, b) -> a > b })
    }
    @Test fun outputSizesAreEvenAndCapped() {
        assertEquals(1080 to 1920, ClipMaker.outSize(2160, 3840))
        assertEquals(1280 to 720, ClipMaker.outSize(1280, 720, 720))
        val (w, h) = ClipMaker.outSize(1281, 721); assertTrue(w % 2 == 0 && h % 2 == 0)
    }
    @Test fun cutoutEdgeIsSoft() {
        assertEquals(0, Cutout.alpha(0.2f)); assertEquals(255, Cutout.alpha(0.9f))
        assertTrue(Cutout.alpha(0.5f) in 100..155)
    }
}
