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

/** OPPO-style gallery: bottom tabs รูปภาพ / อัลบั้ม, an album page with its own back, and a grid that keeps its room. */
@RunWith(AndroidJUnit4::class)
class GalleryLayoutTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(inst)
    private val context: Context get() = inst.targetContext

    @Test(timeout = 120_000) fun bottomTabsLeaveRoomForTheGridAndOpenAlbums() {
        ActivityScenario.launch<GalleryActivity>(Intent(context, GalleryActivity::class.java).putExtra("tab", "device")).use {
            val photos = device.wait(Until.findObject(By.desc("รูปภาพ")), 10000)
            assertNotNull("Photos tab is missing", photos)
            val density = context.resources.displayMetrics.density
            val h = photos!!.visibleBounds.height() / density
            assertTrue("Photos tab is $h dp tall", h < 100)
            val grid = device.findObject(By.desc("รายการรูปและวิดีโอ"))
            assertNotNull("Grid is missing", grid)
            assertTrue("Grid has no room (${grid!!.visibleBounds.height()} px)", grid.visibleBounds.height() > device.displayHeight / 3)

            device.findObject(By.desc("อัลบั้ม")).click()
            val lumora = device.wait(Until.findObject(By.descStartsWith("อัลบั้ม ถ่ายด้วย Lumora")), 8000)
            assertNotNull("Lumora album card is missing · ${GlassNav.dump(device)}", lumora)
            lumora!!.click()
            assertNotNull("Lumora album page did not open · ${GlassNav.dump(device)}", device.wait(Until.findObject(By.text("ถ่ายด้วย Lumora")), 8000))
            assertNotNull(device.findObject(By.desc("รายการรูปและวิดีโอ")))
            device.findObject(By.desc("ย้อนกลับ")).click()
            assertNotNull("Back did not return to the albums tab", device.wait(Until.findObject(By.descStartsWith("อัลบั้ม ถ่ายด้วย Lumora")), 8000))
        }
    }
}
