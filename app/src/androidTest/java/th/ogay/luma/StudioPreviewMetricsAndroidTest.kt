package th.ogay.luma

import android.view.Choreographer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Device/emulator-only smoke test of the real Android UI callback source.
 * This tests callback collection, not decoded video frame rate or preview smoothness.
 */
@RunWith(AndroidJUnit4::class)
class StudioPreviewMetricsAndroidTest {
    @Test(timeout = 30_000)
    fun choreographerCadenceIsMeasuredWithoutConfusingItWithVideoFrames() {
        val metrics = StudioPreviewMetrics(enabled = true)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val done = CountDownLatch(1)
        val callbacks = AtomicInteger()
        lateinit var probe: Choreographer.FrameCallback
        instrumentation.runOnMainSync {
            val choreographer = Choreographer.getInstance()
            probe = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    metrics.frame(StudioPreviewMetrics.FrameSource.UI_CALLBACK, frameTimeNanos)
                    if (callbacks.incrementAndGet() < 30) {
                        choreographer.postFrameCallback(this)
                    } else {
                        done.countDown()
                    }
                }
            }
            choreographer.postFrameCallback(probe)
        }
        try {
            assertTrue("No Choreographer callbacks received", done.await(15, TimeUnit.SECONDS))
            val s = metrics.snapshot()
            assertEquals(30L, s.frameCallbacks.getValue(StudioPreviewMetrics.FrameSource.UI_CALLBACK))
            assertEquals(29L, s.frameIntervals.getValue(StudioPreviewMetrics.FrameSource.UI_CALLBACK).totalCount)
            assertTrue(s.frameCallbacks[StudioPreviewMetrics.FrameSource.VIDEO_MAIN_TEXTURE] == null)
        } finally {
            instrumentation.runOnMainSync { Choreographer.getInstance().removeFrameCallback(probe) }
        }
    }
}
