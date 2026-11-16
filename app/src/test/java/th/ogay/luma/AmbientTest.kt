package th.ogay.luma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientTest {
    @Test fun eachLevelKeepsMoreDetailAndGlowsStronger() {
        for (l in 2..4) {
            assertTrue(Ambient.spec(l).width > Ambient.spec(l - 1).width)
            assertTrue(Ambient.spec(l).strength > Ambient.spec(l - 1).strength)
            assertTrue(Ambient.spec(l).blur <= Ambient.spec(l - 1).blur)
            assertTrue(Ambient.spec(l).everyMs <= Ambient.spec(l - 1).everyMs)
        }
        assertEquals(0, Ambient.spec(0).width)
    }
    @Test fun levelsOutsideTheRangeAreClamped() {
        assertEquals(Ambient.spec(0).width, Ambient.spec(-3).width)
        assertEquals(Ambient.spec(4).width, Ambient.spec(9).width)
    }
    @Test fun smallCopyKeepsTheAspectAndStaysBounded() {
        val s = Ambient.spec(3)
        assertEquals(54, Ambient.heightFor(s, 1920, 1080))
        assertEquals(s.width * 4, Ambient.heightFor(s, 10, 100000))
        assertEquals(4, Ambient.heightFor(s, 0, 100))
    }
}
