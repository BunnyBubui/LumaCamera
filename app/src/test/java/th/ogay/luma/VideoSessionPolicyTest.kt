package th.ogay.luma

import org.junit.Assert.*
import org.junit.Test

class VideoSessionPolicyTest {
    private fun r(lo: Int, hi: Int) = VideoSessionPolicy.Rate(lo, hi)
    @Test fun deviceWideSixtyDoesNotLeakIntoThirtyOnlySession() {
        assertNull(VideoSessionPolicy.select(listOf(r(15, 30), r(30, 30)), 60))
    }
    @Test fun keepsReportedVariableRangeRatherThanInventingFixedSixty() {
        assertEquals(r(30, 60), VideoSessionPolicy.select(listOf(r(15, 30), r(30, 60)), 60))
    }
    @Test fun fixedRangeWinsIndependentOfDriverOrder() {
        val ranges = listOf(r(15, 60), r(60, 60), r(30, 30), r(30, 30), r(120, 120))
        assertEquals(listOf(r(30, 30), r(60, 60)), VideoSessionPolicy.normal(ranges.reversed()))
    }
    @Test fun rejectsInvalidAndHighSpeedRangesInNormalVideo() {
        assertEquals(emptyList<VideoSessionPolicy.Rate>(), VideoSessionPolicy.normal(listOf(r(0, 30), r(60, 30), r(120, 240))))
    }
    @Test fun orientationWorksWithSystemAutoRotateOff() {
        assertEquals(0, VideoSessionPolicy.rotation(0)); assertEquals(3, VideoSessionPolicy.rotation(90))
        assertEquals(2, VideoSessionPolicy.rotation(180)); assertEquals(1, VideoSessionPolicy.rotation(270))
        assertNull(VideoSessionPolicy.rotation(-1))
    }
}
