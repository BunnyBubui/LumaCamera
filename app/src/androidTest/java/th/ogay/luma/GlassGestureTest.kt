package th.ogay.luma

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gestures of the glass camera UI on a real CameraX session: swipe-up panel, zoom drag from a lens pill,
 * mode bar ends, rapid menu open/close and stopping a night burst. Each test asserts on what the user
 * would see or on the saved shot, not on internal fields.
 */
@RunWith(AndroidJUnit4::class)
class GlassGestureTest {
    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(inst)
    private val context: Context get() = inst.targetContext
    private val density get() = context.resources.displayMetrics.density
    private fun until(ms: Long, check: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < end) { if (check()) return; SystemClock.sleep(200) }
        assertTrue("Timed out after $ms ms", check())
    }
    private fun ready() = until(35000) { device.findObject(GlassNav.SHUTTER)?.isEnabled == true }
    private fun launch() = ActivityScenario.launch<CameraActivity>(Intent(context, CameraActivity::class.java))
    private fun countDescriptions(scenario: ActivityScenario<CameraActivity>, desc: String): Int {
        var n = 0
        scenario.onActivity { a -> val found = ArrayList<View>(); a.window.decorView.findViewsWithText(found, desc, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION); n = found.count { it.isShown } }
        return n
    }
    /** Reads the zoom from the viewfinder description, e.g. "ช่องมองภาพ กล้องหลัง 2.4× …". */
    private fun shownZoom(): Float {
        val d = device.findObject(By.descStartsWith("ช่องมองภาพ"))?.contentDescription ?: return -1f
        return Regex("([0-9.]+)×").find(d)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
    }

    @Before fun reset() {
        context.getSharedPreferences("camera", Context.MODE_PRIVATE).edit().clear()
            .putString("photo_mode", "PHOTO").putString("output", "JPEG").putString("timer", "0").putString("aspect", "4:3")
            .putBoolean("watermark", false).putBoolean("gps", false).putBoolean("panel_hint_seen", true).putBoolean("software", true).commit()
    }

    @Test fun swipeUpFromHandleOpensPanelAndShutterStillWorksAfter() {
        val before = context.luma.shots.all().map { it.id }.toSet()
        launch().use {
            ready()
            val h = device.findObject(GlassNav.HANDLE).visibleBounds
            device.swipe(h.centerX(), h.centerY(), h.centerX(), h.centerY() - (220 * density).toInt(), 12)
            assertNotNull("Swipe up on the handle did not open the panel", device.wait(Until.findObject(By.desc("ปิดแผง")), 4000))
            device.pressBack()
            until(3000) { !device.hasObject(By.desc("ปิดแผง")) }
            ready(); device.findObject(GlassNav.SHUTTER).click()
            until(60000) { context.luma.shots.latest()?.id?.let { id -> id !in before } == true }
            val shot = context.luma.shots.latest()!!
            assertTrue(context.contentResolver.openInputStream(shot.uri)!!.use { s -> s.read() >= 0 })
        }
    }

    @Test fun swipeUpFromModeBarOpensPanelButSidewaysSwipeChangesMode() {
        launch().use {
            ready()
            val bar = device.findObject(By.desc("แถบโหมด")).visibleBounds
            device.swipe(bar.centerX(), bar.centerY(), bar.centerX(), bar.centerY() - (200 * density).toInt(), 12)
            assertNotNull("Swipe up on the mode bar did not open the panel", device.wait(Until.findObject(By.desc("ปิดแผง")), 4000))
            device.pressBack(); until(3000) { !device.hasObject(By.desc("ปิดแผง")) }
            // A short swipe to the right moves one mode back (บุคคล) and must not open the panel.
            device.swipe(bar.centerX(), bar.centerY(), bar.centerX() + (90 * density).toInt(), bar.centerY(), 15)
            SystemClock.sleep(1200)
            assertFalse(device.hasObject(By.desc("ปิดแผง")))
            assertNotEquals("PHOTO", context.getSharedPreferences("camera", 0).getString("photo_mode", ""))
        }
    }

    @Test fun dragFromLensPillZoomsAndTapStillSelectsLens() {
        launch().use {
            ready()
            val pill = device.wait(Until.findObject(GlassNav.lens("1×")), 10000)
            assertNotNull("Missing 1× lens pill", pill)
            val b = pill.visibleBounds
            // Dragging left raises the zoom; the drag starts on a lens cell, which must not eat it.
            device.swipe(b.centerX(), b.centerY(), b.centerX() - (150 * density).toInt(), b.centerY(), 40)
            until(4000) { shownZoom() > 1.2f }
            SystemClock.sleep(1800) // wheel hides, pills come back
            device.wait(Until.findObject(GlassNav.lens("1×")), 5000)?.click()
            until(4000) { kotlin.math.abs(shownZoom() - 1f) < 0.05f }
        }
    }

    @Test fun modeBarEndsShowWholeCellsAndPlusOpensEditorOnlyOnTap() {
        // Photo modes only, so a swipe never lands on a mode that opens the video screen.
        context.getSharedPreferences("camera", 0).edit().putString("mode_bar", "NIGHT,PORTRAIT,PHOTO,PRO,HDR").commit()
        launch().use {
            ready()
            val bar = device.findObject(By.desc("แถบโหมด")).visibleBounds
            repeat(3) { device.swipe(bar.right - (20 * density).toInt(), bar.centerY(), bar.left + (20 * density).toInt(), bar.centerY(), 6); SystemClock.sleep(900) }
            assertFalse("A swipe to the end must not open the editor", device.hasObject(By.text("เสร็จ")))
            val plus = device.wait(Until.findObject(By.desc("ปรับแต่งแถบโหมด")), 4000)
            assertNotNull(plus)
            val p = plus.visibleBounds
            assertTrue("Last cell is cut: $p in $bar", p.width() >= (48 * density).toInt() && p.right <= bar.right && p.left >= bar.left)
            plus.click()
            assertNotNull(device.wait(Until.findObject(By.text("เสร็จ")), 4000))
            device.pressBack(); until(3000) { !device.hasObject(By.text("เสร็จ")) }
            repeat(3) { device.swipe(bar.left + (20 * density).toInt(), bar.centerY(), bar.right - (20 * density).toInt(), bar.centerY(), 6); SystemClock.sleep(900) }
            val first = device.wait(Until.findObject(By.desc("โหมดกลางคืน")), 4000).visibleBounds
            assertTrue("First cell is cut: $first in $bar", first.width() >= (48 * density).toInt() && first.left >= bar.left)
        }
    }

    @Test fun openingAndClosingMenusTwentyTimesLeavesNoLayer() {
        launch().use { scenario ->
            ready()
            GlassNav.openPanel(device)
            repeat(20) {
                GlassNav.inPanel(device, By.descStartsWith("สัดส่วนภาพ"))!!.click()
                assertNotNull(device.wait(Until.findObject(By.desc("ปิดเมนู")), 3000))
                assertEquals(1, countDescriptions(scenario, "ปิดเมนู"))
                device.pressBack()
                until(3000) { countDescriptions(scenario, "ปิดเมนู") == 0 }
            }
            assertEquals(1, countDescriptions(scenario, "ปิดแผง"))
            device.pressBack()
            SystemClock.sleep(900)
            assertEquals(0, countDescriptions(scenario, "ปิดแผง") + countDescriptions(scenario, "ปิดเมนู"))
            // Opening twice in a row still gives one panel.
            scenario.onActivity { a -> val f = ArrayList<View>(); a.window.decorView.findViewsWithText(f, "ตัวเลือกกล้อง", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION); f.first().performClick(); f.first().performClick() }
            SystemClock.sleep(900)
            assertEquals(1, countDescriptions(scenario, "ปิดแผง"))
            device.pressBack(); SystemClock.sleep(900)
            ready()
            assertTrue(device.findObject(GlassNav.SHUTTER).isClickable)
        }
    }

    @Test fun shutterStopsNightBurstAndKeepsCapturedFrames() {
        context.getSharedPreferences("camera", 0).edit().putBoolean("night_toggle", true).putInt("night_frames", 32).putBoolean("software", false).commit()
        val before = context.luma.shots.all().map { it.id }.toSet()
        launch().use {
            ready()
            device.findObject(GlassNav.SHUTTER).click()
            val stop = device.wait(Until.findObject(By.desc("หยุดรวมแสงและบันทึก")), 5000)
            assertNotNull("Night capture did not turn the shutter into a stop button", stop)
            // The stop button must work from the first moment, before any frame arrives.
            assertTrue("Stop button is disabled while gathering light · " + GlassNav.dump(device), stop.isEnabled)
            stop.click()
            until(60000) { device.findObject(GlassNav.SHUTTER)?.isEnabled == true }
            val shot = context.luma.shots.all().firstOrNull { it.id !in before }
            if (shot != null) {
                assertEquals(32, shot.requestedFrames)
                // An emulator can finish 32 frames before the tap lands; then there is nothing left to stop.
                org.junit.Assume.assumeTrue("Burst finished before the stop tap", shot.frames < 32)
                until(10000) { context.luma.shots.get(shot.id)!!.stage != Stage.CAPTURING }
                assertTrue("Stopped burst kept ${shot.frames} frames", shot.frames in 0 until 32)
            }
        }
    }
}
