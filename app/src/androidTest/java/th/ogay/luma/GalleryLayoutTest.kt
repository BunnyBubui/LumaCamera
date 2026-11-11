package th.ogay.luma

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The Lumora / device tabs once stretched over the whole screen and hid the grid. */
@RunWith(AndroidJUnit4::class)
class GalleryLayoutTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(inst)
    private val context: Context get() = inst.targetContext

    @Test fun tabsStayShortAndLeaveRoomForTheGrid() {
        ActivityScenario.launch<GalleryActivity>(Intent(context, GalleryActivity::class.java).putExtra("tab", "luma")).use {
            val tab = device.wait(Until.findObject(By.desc("ถ่ายด้วย Lumora")), 10000)
            assertNotNull("Lumora tab is missing", tab)
            val density = context.resources.displayMetrics.density
            val h = tab!!.visibleBounds.height() / density
            assertTrue("Tab is $h dp tall; it should hug its text", h < 120)
            val grid = device.findObject(By.desc("รายการรูปและวิดีโอ"))
            assertNotNull("Grid is missing", grid)
            assertTrue("Grid has no room (${grid!!.visibleBounds.height()} px)", grid.visibleBounds.height() > device.displayHeight / 3)
        }
    }
}
