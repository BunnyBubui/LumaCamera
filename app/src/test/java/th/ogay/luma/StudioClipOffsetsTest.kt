package th.ogay.luma

import org.junit.Assert.assertEquals
import org.junit.Test

class StudioClipOffsetsTest {
    @Test fun emptyTimelineHasNoClip() {
        val index = StudioClipOffsets(longArrayOf())
        assertEquals(0L, index.total)
        assertEquals(0L, index.startOf(0))
        assertEquals(-1, index.clipAt(0))
    }

    @Test fun boundariesBelongToNextClip() {
        val index = StudioClipOffsets(longArrayOf(100, 200, 300))
        assertEquals(600L, index.total)
        assertEquals(0L, index.startOf(0))
        assertEquals(100L, index.startOf(1))
        assertEquals(300L, index.startOf(2))
        assertEquals(600L, index.startOf(3))
        assertEquals(-1, index.clipAt(-1))
        assertEquals(0, index.clipAt(0))
        assertEquals(0, index.clipAt(99))
        assertEquals(1, index.clipAt(100))
        assertEquals(1, index.clipAt(299))
        assertEquals(2, index.clipAt(300))
        assertEquals(2, index.clipAt(599))
        assertEquals(-1, index.clipAt(600))
    }

    @Test fun zeroLengthItemsAreSkippedAndNegativeLengthsClamped() {
        val index = StudioClipOffsets(longArrayOf(0, 10, -20, 0, 10, 0))
        assertEquals(20L, index.total)
        assertEquals(1, index.clipAt(0))
        assertEquals(4, index.clipAt(10))
        assertEquals(-1, index.clipAt(20))
    }

    @Test fun overflowSaturatesRatherThanWrapping() {
        val index = StudioClipOffsets(longArrayOf(Long.MAX_VALUE - 5L, 10L))
        assertEquals(Long.MAX_VALUE, index.total)
        assertEquals(Long.MAX_VALUE - 5L, index.startOf(1))
        assertEquals(1, index.clipAt(Long.MAX_VALUE - 2L))
        assertEquals(-1, index.clipAt(Long.MAX_VALUE))
    }

    @Test fun largeTimelineMatchesLinearReference() {
        val lengths = LongArray(1_000) { i -> if (i % 17 == 0) 0 else (i % 23 + 1).toLong() * 33L }
        val index = StudioClipOffsets(lengths)
        var total = 0L
        for (length in lengths) total += length
        assertEquals(total, index.total)
        for (t in 0 until total step 29) {
            var at = 0L; var expected = -1
            for (i in lengths.indices) {
                if (t >= at && t < at + lengths[i]) { expected = i; break }
                at += lengths[i]
            }
            assertEquals("timeline ms=$t", expected, index.clipAt(t))
        }
    }
}
