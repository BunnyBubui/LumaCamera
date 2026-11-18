package th.ogay.luma

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device-only UI smoke checks. Bitmap snapshots are not frame-delivery telemetry. */
@RunWith(AndroidJUnit4::class)
class StudioPreviewDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(inst)

    private fun findTexture(view: View): TextureView? {
        if (view is TextureView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            findTexture(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    /** Pixel check catches a preview that never displayed a frame, not a one-frame black seam. */
    private fun hasVisibleFrame(scenario: ActivityScenario<VideoEditorActivity>): Boolean {
        var visible = false
        scenario.onActivity { activity ->
            val texture = findTexture(activity.window.decorView)
            if (texture?.isAvailable == true) {
                val frame: Bitmap? = runCatching { texture.bitmap }.getOrNull()
                if (frame != null) {
                    try {
                        var lit = 0
                        for (y in 1..5) for (x in 1..5) {
                            val rgb = frame.getPixel(x * frame.width / 6, y * frame.height / 6)
                            if (android.graphics.Color.red(rgb) + android.graphics.Color.green(rgb) + android.graphics.Color.blue(rgb) > 90) lit++
                        }
                        visible = lit >= 5
                    } finally { frame.recycle() }
                }
            }
        }
        return visible
    }

    private fun awaitFrame(scenario: ActivityScenario<VideoEditorActivity>, timeoutMs: Long = 15_000L) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        do {
            if (hasVisibleFrame(scenario)) return
            SystemClock.sleep(150)
        } while (SystemClock.elapsedRealtime() < end)
        assertTrue("No decoded frame visible on primary TextureView", hasVisibleFrame(scenario))
    }

    @Test(timeout = 180_000) fun firstFramePauseResumeAndRapidTimelineScrubDoesNotCrash() {
        val clip = TestMedia.clip(inst, 4000, 640, 360)
        try {
            ActivityScenario.launch<VideoEditorActivity>(VideoEditorActivity.intent(inst.targetContext, Uri.fromFile(clip))).use { scenario ->
                awaitFrame(scenario)
                val transport = device.wait(Until.findObject(By.desc("เล่นหรือหยุด")), 10_000)
                assertNotNull("Preview play/pause accessibility control missing", transport)
                transport!!.click() // play
                SystemClock.sleep(600)
                transport.click() // pause
                val timeline = device.wait(Until.findObject(By.descStartsWith("ไทม์ไลน์")), 10_000)
                assertNotNull("Timeline accessibility view missing", timeline)
                val r = timeline!!.visibleBounds
                assertTrue("Timeline has no interactive bounds", r.width() > 70 && r.height() > 0)
                repeat(4) { i ->
                    val y = r.centerY()
                    val from = if (i % 2 == 0) r.left + r.width() / 4 else r.right - r.width() / 4
                    val to = if (i % 2 == 0) r.right - r.width() / 4 else r.left + r.width() / 4
                    device.swipe(from, y, to, y, 12)
                }
                awaitFrame(scenario)
                transport.click() // resume
                SystemClock.sleep(600)
                awaitFrame(scenario)
                transport.click() // pause
            }
        } finally { clip.delete() }
    }

    /** Explicit opt-in: adb shell am instrument ... -e studio.longSoak true. Not part of fast CI. */
    @Test(timeout = 750_000) fun optedInTenMinutePreviewLoopDoesNotCrash() {
        assumeTrue("Enable with -e studio.longSoak true", InstrumentationRegistry.getArguments().getString("studio.longSoak") == "true")
        val clip = TestMedia.clip(inst, 4000, 640, 360)
        try {
            ActivityScenario.launch<VideoEditorActivity>(VideoEditorActivity.intent(inst.targetContext, Uri.fromFile(clip))).use { scenario ->
                scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                awaitFrame(scenario)
                val transport = device.wait(Until.findObject(By.desc("เล่นหรือหยุด")), 10_000)
                assertNotNull("Preview play/pause control missing", transport)
                transport!!.click()
                val start = SystemClock.elapsedRealtime()
                while (SystemClock.elapsedRealtime() - start < 600_000L) {
                    SystemClock.sleep(5_000)
                    // Scenario callback throws if the editor crashed; periodically verify a view is attached.
                    scenario.onActivity { activity ->
                        assertNotNull("Primary TextureView disappeared", findTexture(activity.window.decorView))
                    }
                }
                assertTrue("Playback elapsed below 10 minutes", SystemClock.elapsedRealtime() - start >= 600_000L)
            }
        } finally { clip.delete() }
    }
}
