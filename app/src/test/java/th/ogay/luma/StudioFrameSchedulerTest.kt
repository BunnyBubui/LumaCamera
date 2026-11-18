package th.ogay.luma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioFrameSchedulerTest {
    private class FakeFrames : StudioFrameScheduler.FrameSource {
        private val callbacks = LinkedHashSet<StudioFrameScheduler.FrameCallback>()
        var posted = 0
        var removed = 0
        val pending get() = callbacks.size

        override fun postFrameCallback(callback: StudioFrameScheduler.FrameCallback) {
            assertTrue("Duplicate callback registration", callbacks.add(callback))
            posted++
        }

        override fun removeFrameCallback(callback: StudioFrameScheduler.FrameCallback) {
            if (callbacks.remove(callback)) removed++
        }

        fun dispatch(timeNanos: Long) {
            val callback = callbacks.first()
            callbacks.remove(callback)
            callback.onFrame(timeNanos)
        }
    }

    @Test fun waitsForAttachmentAndForeground() {
        val frames = FakeFrames()
        val scheduler = StudioFrameScheduler(frames)
        val observed = mutableListOf<Long>()
        scheduler.setFrameListener { t, _ -> observed.add(t) }
        scheduler.setPlaybackActive(true)
        scheduler.setAttached(true)
        assertEquals(0, frames.pending)
        scheduler.setForeground(true)
        assertEquals(1, frames.pending)
        frames.dispatch(1_000L)
        assertEquals(listOf(1_000L), observed)
        assertEquals(1, frames.pending)
        scheduler.close()
        assertEquals(0, frames.pending)
    }

    @Test fun oneShotRequestsCoalesceAndDoNotLoopWhilePaused() {
        val frames = FakeFrames()
        val scheduler = StudioFrameScheduler(frames)
        var calls = 0
        scheduler.setFrameListener { _, _ -> calls++ }
        scheduler.setAttached(true)
        scheduler.setForeground(true)
        scheduler.requestFrame()
        scheduler.requestFrame()
        assertEquals(1, frames.posted)
        frames.dispatch(40L)
        assertEquals(1, calls)
        assertEquals(0, frames.pending)
        scheduler.close()
    }

    @Test fun pauseBackgroundAndRotationRemoveCallbacksAndResetInterval() {
        val frames = FakeFrames()
        val scheduler = StudioFrameScheduler(frames)
        val gaps = mutableListOf<Long>()
        scheduler.setFrameListener { _, gap -> gaps.add(gap) }
        scheduler.setAttached(true)
        scheduler.setForeground(true)
        scheduler.setPlaybackActive(true)
        frames.dispatch(100L)
        frames.dispatch(120L)
        scheduler.setForeground(false)
        assertEquals(0, frames.pending)
        scheduler.setForeground(true)
        frames.dispatch(10_000L)
        scheduler.setAttached(false)
        assertEquals(0, frames.pending)
        scheduler.setAttached(true)
        frames.dispatch(200_000L)
        assertEquals(listOf(0L, 20L, 0L, 0L), gaps)
        scheduler.stop()
        assertEquals(0, frames.pending)
        scheduler.close()
    }

    @Test fun scrubbingRunsWithoutPlaybackAndStopsCleanly() {
        val frames = FakeFrames()
        val scheduler = StudioFrameScheduler(frames)
        var calls = 0
        scheduler.setFrameListener { _, _ -> calls++ }
        scheduler.setAttached(true)
        scheduler.setForeground(true)
        scheduler.setScrubbing(true)
        frames.dispatch(50L)
        frames.dispatch(60L)
        scheduler.setScrubbing(false)
        assertEquals(2, calls)
        assertEquals(0, frames.pending)
        scheduler.requestFrame()
        frames.dispatch(70L)
        assertEquals(3, calls)
        assertEquals(0, frames.pending)
        scheduler.close()
    }

    @Test fun throttlingUsesObservedNanosAndNoNegativeInterval() {
        val frames = FakeFrames()
        val scheduler = StudioFrameScheduler(frames)
        val observed = mutableListOf<Pair<Long, Long>>()
        scheduler.setFrameListener { t, gap -> observed.add(t to gap) }
        scheduler.minimumUiIntervalNanos = 20L
        scheduler.setAttached(true)
        scheduler.setForeground(true)
        scheduler.setPlaybackActive(true)
        frames.dispatch(100L)
        frames.dispatch(110L)
        frames.dispatch(99L) // out-of-order callback ignored
        frames.dispatch(121L)
        assertEquals(listOf(100L to 0L, 121L to 21L), observed)
        assertEquals(1, frames.pending)
        scheduler.close()
    }

    @Test fun closeAndListenerRemovalAreIdempotent() {
        val frames = FakeFrames()
        val scheduler = StudioFrameScheduler(frames)
        scheduler.setFrameListener { _, _ -> }
        scheduler.setAttached(true)
        scheduler.setForeground(true)
        scheduler.setPlaybackActive(true)
        scheduler.setFrameListener(null)
        assertEquals(0, frames.pending)
        scheduler.setFrameListener { _, _ -> }
        assertEquals(1, frames.pending)
        scheduler.close()
        scheduler.close()
        scheduler.requestFrame()
        assertEquals(0, frames.pending)
        assertTrue(frames.removed >= 2)
        assertFalse(frames.pending > 0)
    }

    @Test fun callbackCanPauseItselfWithoutRearming() {
        val frames = FakeFrames()
        val scheduler = StudioFrameScheduler(frames)
        scheduler.setFrameListener { _, _ -> scheduler.setPlaybackActive(false) }
        scheduler.setAttached(true)
        scheduler.setForeground(true)
        scheduler.setPlaybackActive(true)
        frames.dispatch(1L)
        assertEquals(0, frames.pending)
        scheduler.close()
    }
}
