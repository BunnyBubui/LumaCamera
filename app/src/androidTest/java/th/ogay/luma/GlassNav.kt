package th.ogay.luma

import android.os.SystemClock
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull

/** Navigation through the glass camera UI, shared by the flow tests so selectors live in one place. */
object GlassNav {
    val SHUTTER: BySelector = By.desc("ถ่ายภาพ")
    val HANDLE: BySelector = By.descStartsWith("ตัวเลือกกล้อง")
    val PANEL_SCROLL: BySelector = By.clazz("android.widget.ScrollView").scrollable(true)
    fun lens(zoom: String): BySelector = By.descStartsWith("ซูม $zoom")
    /** Menu rows are described as "label, note". */
    fun menuRow(label: String): BySelector = By.descStartsWith("$label,")

    fun openPanel(device: UiDevice) {
        val handle = device.wait(Until.findObject(HANDLE), 30000)
        assertNotNull("Camera options handle is missing", handle)
        handle.click()
        assertNotNull("Options panel did not open", device.wait(Until.findObject(By.desc("ปิดแผง")), 5000))
        SystemClock.sleep(800) // let the liquid open settle before tapping inside
    }

    /** Finds a view inside the open sheet, scrolling the sheet when it is below the fold. */
    fun inPanel(device: UiDevice, selector: BySelector): UiObject2? {
        device.findObject(selector)?.let { if (it.visibleBounds.height() > 0) return it }
        return device.findObject(PANEL_SCROLL)?.scrollUntil(Direction.DOWN, Until.findObject(selector))
            ?: device.findObject(selector)
    }

    fun closeLayers(device: UiDevice) {
        repeat(3) { if (device.hasObject(By.desc("ปิดเมนู")) || device.hasObject(By.desc("ปิดแผง"))) { device.pressBack(); SystemClock.sleep(500) } }
    }

    /** Picks a capture format from the options panel ("JPEG", "RAW", "AI", "RAW + AI") and returns to the viewfinder. */
    fun chooseFormat(device: UiDevice, label: String) {
        openPanel(device)
        val tile = inPanel(device, By.descStartsWith("รูปแบบไฟล์"))
        assertNotNull("Format tile is missing", tile); tile!!.click()
        val row = device.wait(Until.findObject(menuRow(label)), 5000)
        assertNotNull("Format $label is not offered", row); row.click()
        SystemClock.sleep(600)
        closeLayers(device)
    }

    fun openAllSettings(device: UiDevice) {
        openPanel(device)
        val link = inPanel(device, By.desc("ตั้งค่าทั้งหมด"))
        assertNotNull("Settings link is missing", link); link!!.click()
        assertNotNull("Settings did not open", device.wait(Until.findObject(By.text("ค้นหาการตั้งค่า")), 5000))
    }
}
