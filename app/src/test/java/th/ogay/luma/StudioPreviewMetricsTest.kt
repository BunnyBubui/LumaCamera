package th.ogay.luma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class StudioPreviewMetricsTest {
    private class FakeClock {
        val now = AtomicLong(0L)
        val clock = StudioPreviewMetrics.NanoClock { now.get() }
        fun ms(value: Long) { now.addAndGet(value * 1_000_000L) }
    }

    @Test fun disabledIsNoOpAndNeverCollectsData() {
        val m = StudioPreviewMetrics()
        assertEquals(0L, m.begin(StudioPreviewMetrics.Span.PRELOAD))
        m.frame(StudioPreviewMetrics.FrameSource.UI_CALLBACK, 16_000_000L)
        m.mark(StudioPreviewMetrics.Event.JOIN_REQUEST)
        m.state(StudioPreviewMetrics.State.PLAYING)
        m.decoderReportedDrops(10)
        assertFalse(m.finish(17))
        assertTrue(m.snapshot().events.isEmpty())
        assertTrue(m.snapshot().frameCallbacks.isEmpty())
        assertEquals(0L, m.snapshot().reportedDroppedDecoderFrames)
        assertEquals("Lumora Studio preview metrics: disabled", m.debugReport())
    }

    @Test fun timingPercentilesUseMonotonicNanoseconds() {
        val f = FakeClock()
        val m = StudioPreviewMetrics(true, f.clock)
        for (n in listOf(10L, 20L, 30L, 40L)) {
            val token = m.begin(StudioPreviewMetrics.Span.PRELOAD)
            f.ms(n)
            assertTrue(m.finish(token))
        }
        val s = m.snapshot().durations.getValue(StudioPreviewMetrics.Span.PRELOAD)
        assertEquals(4L, s.totalCount)
        assertEquals(4, s.sampleCount)
        assertEquals(20.0, s.p50Ms!!, 0.000001)
        assertEquals(40.0, s.p95Ms!!, 0.000001)
        assertEquals(40.0, s.maxMs!!, 0.000001)
    }

    @Test fun rollingSamplesAreBoundedButLifetimeCountIsRetained() {
        val f = FakeClock()
        val m = StudioPreviewMetrics(true, f.clock, windowSize = 2)
        for (n in listOf(10L, 20L, 30L)) {
            val t = m.begin(StudioPreviewMetrics.Span.SEEK)
            f.ms(n)
            m.finish(t)
        }
        val s = m.snapshot().durations.getValue(StudioPreviewMetrics.Span.SEEK)
        assertEquals(3L, s.totalCount)
        assertEquals(2, s.sampleCount)
        assertEquals(20.0, s.p50Ms!!, 0.000001)
        assertEquals(30.0, s.p95Ms!!, 0.000001)
    }

    @Test fun uiAndTextureFrameIntervalsAreNotMixed() {
        val m = StudioPreviewMetrics(true)
        val ui = StudioPreviewMetrics.FrameSource.UI_CALLBACK
        val video = StudioPreviewMetrics.FrameSource.VIDEO_MAIN_TEXTURE
        m.frame(ui, 1_000_000L)
        m.frame(video, 2_000_000L)
        m.frame(ui, 17_000_000L)
        m.frame(video, 35_000_000L)
        m.frame(ui, 33_000_000L)
        val s = m.snapshot()
        assertEquals(3L, s.frameCallbacks.getValue(ui))
        assertEquals(2L, s.frameCallbacks.getValue(video))
        assertEquals(16.0, s.frameIntervals.getValue(ui).p50Ms!!, 0.000001)
        assertEquals(33.0, s.frameIntervals.getValue(video).p95Ms!!, 0.000001)
        assertEquals(2L, s.frameIntervals.getValue(ui).totalCount)
    }

    @Test fun invalidTimestampsDoNotCreateNegativeSamples() {
        val f = FakeClock()
        val m = StudioPreviewMetrics(true, f.clock)
        val source = StudioPreviewMetrics.FrameSource.UI_CALLBACK
        m.frame(source, 10_000_000L)
        m.frame(source, 10_000_000L)
        m.frame(source, 9_000_000L)
        m.frame(source, 26_000_000L)
        val token = m.begin(StudioPreviewMetrics.Span.SEEK)
        f.ms(-5)
        assertFalse(m.finish(token))
        assertEquals(3L, m.snapshot().invalidSamples)
        assertEquals(16.0, m.snapshot().frameIntervals.getValue(source).p50Ms!!, 0.000001)
        assertFalse(m.snapshot().durations.containsKey(StudioPreviewMetrics.Span.SEEK))
    }

    @Test fun pendingEvictionCancellationAndResetAreSafe() {
        val m = StudioPreviewMetrics(true, maxPending = 2)
        val a = m.begin(StudioPreviewMetrics.Span.PRELOAD)
        val b = m.begin(StudioPreviewMetrics.Span.SEEK)
        val c = m.begin(StudioPreviewMetrics.Span.PREPARE)
        assertFalse(m.finish(a))
        m.cancel(b)
        assertTrue(m.finish(c))
        assertEquals(1L, m.snapshot().evictedSpans)
        assertEquals(1L, m.snapshot().cancelledSpans)
        assertEquals(0, m.snapshot().activeSpans)
        m.reset()
        assertEquals(0L, m.snapshot().evictedSpans)
        assertTrue(m.snapshot().durations.isEmpty())
    }

    @Test fun stateChangesAreBoundedAndDoNotDoubleCountTheSameState() {
        val f = FakeClock()
        val m = StudioPreviewMetrics(true, f.clock)
        m.state(StudioPreviewMetrics.State.PREPARING)
        m.state(StudioPreviewMetrics.State.PREPARING)
        f.ms(7)
        m.state(StudioPreviewMetrics.State.PLAYING)
        val first = m.snapshot()
        assertEquals(2, first.states.size)
        assertEquals(null, first.states[0].from)
        assertEquals(StudioPreviewMetrics.State.PREPARING, first.states[0].to)
        assertEquals(StudioPreviewMetrics.State.PREPARING, first.states[1].from)
        assertEquals(7_000_000L, first.states[1].timestampNs)
        repeat(100) {
            m.state(if (it % 2 == 0) StudioPreviewMetrics.State.PAUSED else StudioPreviewMetrics.State.PLAYING)
        }
        assertEquals(64, m.snapshot().states.size)
        assertEquals(2, first.states.size) // snapshot stays independent
    }

    @Test fun marksOnlyExplicitlyObservedDropsAndBlackouts() {
        val m = StudioPreviewMetrics(true)
        m.join(StudioPreviewMetrics.JoinKind.SAME_SOURCE_SPLIT)
        m.join(StudioPreviewMetrics.JoinKind.OVERLAP)
        m.mark(StudioPreviewMetrics.Event.FIRST_FRAME_REPORTED)
        m.decoderReportedDrops(0L)
        m.decoderReportedDrops(3L)
        assertEquals(3L, m.snapshot().reportedDroppedDecoderFrames)
        assertEquals(1L, m.snapshot().events.getValue(StudioPreviewMetrics.Event.FIRST_FRAME_REPORTED))
        assertFalse(m.snapshot().events.containsKey(StudioPreviewMetrics.Event.VISIBLE_BLACK_FRAME_OBSERVED))
        assertTrue(m.debugReport(mapOf("device" to "test")).contains("p50"))
    }

    @Test fun parallelCallbacksDoNotLoseCountersOrTokens() {
        val m = StudioPreviewMetrics(true)
        val threads = 4
        val loops = 200
        val done = CountDownLatch(threads)
        val start = CountDownLatch(1)
        repeat(threads) {
            Thread {
                start.await()
                repeat(loops) {
                    val token = m.begin(StudioPreviewMetrics.Span.MAIN_THREAD_WORK)
                    m.mark(StudioPreviewMetrics.Event.JOIN_REQUEST)
                    m.finish(token)
                }
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue("workers timed out", done.await(10, TimeUnit.SECONDS))
        val s = m.snapshot()
        assertEquals((threads * loops).toLong(), s.events.getValue(StudioPreviewMetrics.Event.JOIN_REQUEST))
        assertEquals((threads * loops).toLong(), s.durations.getValue(StudioPreviewMetrics.Span.MAIN_THREAD_WORK).totalCount)
        assertEquals(0, s.activeSpans)
    }
}
