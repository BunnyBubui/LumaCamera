package th.ogay.luma

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Round-11 fixes, checked the way a finger uses them: hold a tile and slide onto a menu row to pick it,
 * menus stay fully on screen, the self-timer opens as a slider, and the countdown shows a big number.
 */
@RunWith(AndroidJUnit4::class)
class MenuAndTimerTest {
    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(inst)
    private val context: Context get() = inst.targetContext
    private val prefs get() = context.getSharedPreferences("camera", Context.MODE_PRIVATE)
    private fun until(ms: Long, what: String, check: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < end) { if (check()) return; SystemClock.sleep(150) }
        assertTrue("$what (timed out after $ms ms) · ${GlassNav.dump(device)}", check())
    }
    private fun ready() = until(35000, "shutter never became ready") { device.findObject(GlassNav.SHUTTER)?.isEnabled == true }
    private fun launch() = ActivityScenario.launch<CameraActivity>(Intent(context, CameraActivity::class.java))
    private fun touch(down: Long, action: Int, x: Float, y: Float) {
        val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        try { inst.sendPointerSync(e) } finally { e.recycle() }
    }

    @Before fun reset() {
        prefs.edit().clear()
            .putString("photo_mode", "PHOTO").putString("output", "JPEG").putString("timer", "0").putString("aspect", "4:3")
            .putBoolean("watermark", false).putBoolean("gps", false).putBoolean("panel_hint_seen", true).putBoolean("grid", false).commit()
    }

    @Test fun holdingATileAndSlidingOntoARowPicksIt() {
        launch().use {
            ready(); GlassNav.openPanel(device)
            val tile = GlassNav.inPanel(device, By.descStartsWith("เส้นกริด"))
            assertNotNull("Grid tile is missing", tile)
            val t = tile!!.visibleBounds
            val down = SystemClock.uptimeMillis()
            touch(down, MotionEvent.ACTION_DOWN, t.exactCenterX(), t.exactCenterY())
            // The finger stays down: holding opens the menu under it after a short delay.
            val row = device.wait(Until.findObject(GlassNav.menuRow("3 × 3")), 3000)
            assertNotNull("Holding the tile did not open its menu", row)
            SystemClock.sleep(700) // let the card finish springing open so the row bounds are final
            val r = row!!.visibleBounds
            val steps = 14
            for (k in 1..steps) {
                val f = k / steps.toFloat()
                touch(down, MotionEvent.ACTION_MOVE, t.exactCenterX() + (r.exactCenterX() - t.exactCenterX()) * f, t.exactCenterY() + (r.exactCenterY() - t.exactCenterY()) * f)
                SystemClock.sleep(16)
            }
            touch(down, MotionEvent.ACTION_UP, r.exactCenterX(), r.exactCenterY())
            until(3000, "Lifting over \"3 × 3\" did not pick it") { Grids.kind(prefs) == 1 }
            until(3000, "The menu stayed open after a pick") { !device.hasObject(By.desc("ปิดเมนู")) }
        }
    }

    @Test fun menuWithNotesFitsOnScreen() {
        launch().use {
            ready(); GlassNav.openPanel(device)
            GlassNav.inPanel(device, By.descStartsWith("เส้นกริด"))!!.click()
            val last = device.wait(Until.findObject(GlassNav.menuRow("กากบาทกลาง")), 3000)
            assertNotNull("Last grid option is missing", last)
            SystemClock.sleep(700)
            val b = last!!.visibleBounds
            val screen = device.displayHeight
            assertTrue("Last row is cut at the bottom: $b, screen $screen", b.bottom <= screen && b.height() >= (40 * context.resources.displayMetrics.density).toInt())
        }
    }

    @Test fun timerTileOpensASliderAndTheCountdownShowsBigNumbers() {
        launch().use {
            ready(); GlassNav.openPanel(device)
            GlassNav.inPanel(device, By.descStartsWith("ตั้งเวลา"))!!.click()
            assertNotNull("The timer slider did not open", device.wait(Until.findObject(By.text("ตั้งเวลาถ่าย")), 4000))
            device.pressBack(); SystemClock.sleep(600)
            prefs.edit().putString("timer", "2").commit()
            val before = context.luma.shots.all().map { it.id }.toSet()
            ready(); device.findObject(GlassNav.SHUTTER).click()
            assertNotNull("No big countdown number", device.wait(Until.findObject(By.desc("อีก 2 วินาที")), 2500))
            until(60000, "The timed photo was not saved") { context.luma.shots.latest()?.id?.let { id -> id !in before } == true }
        }
    }
}
