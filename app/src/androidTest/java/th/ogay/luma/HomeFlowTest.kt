package th.ogay.luma

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeFlowTest {
    private fun find(v: View, desc: String): View? {
        val label = v.contentDescription?.toString()
        // Match a complete title, not a shared prefix (e.g. "โปร" must not click "โปรเจกต์วิดีโอ").
        if (label == desc || label?.startsWith("$desc ") == true) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), desc)?.let { return it }
        return null
    }

    /** Opened by the camera, Home lists the features and the camera card answers the camera with Photo. */
    @Test(timeout = 60_000) fun homeShowsFeaturesAndReturnsAMode() {
        val scenario = ActivityScenario.launchActivityForResult<HomeActivity>(
            android.content.Intent(InstrumentationRegistry.getInstrumentation().targetContext, HomeActivity::class.java).putExtra(HomeActivity.FROM_CAMERA, true))
        Thread.sleep(1500)
        scenario.onActivity { a ->
            val root = a.window.decorView
            for (name in listOf("ถ่ายภาพ", "แกลเลอรี", "ตัดต่อภาพ", "ตัดต่อวิดีโอ")) assertNotNull("$name is missing", find(root, name))
            find(root, "ถ่ายภาพ")!!.performClick()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val result = scenario.result
        assertEquals(Activity.RESULT_OK, result.resultCode)
        assertEquals(PhotoMode.PHOTO.name, result.resultData.getStringExtra(HomeActivity.RESULT_MODE))
    }

    /** The opening animation removes itself after it has played. */
    @Test(timeout = 60_000) fun introRemovesItself() {
        val scenario = ActivityScenario.launch(HomeActivity::class.java)
        var host: android.widget.FrameLayout? = null
        scenario.onActivity { a -> host = android.widget.FrameLayout(a).also { (a.window.decorView as ViewGroup).addView(it, -1, -1); LumoraIntro.show(it) } }
        scenario.onActivity { assertTrue(host!!.childCount == 1) }
        Thread.sleep(3000)
        scenario.onActivity { assertEquals(0, host!!.childCount) }
    }

    /** The gallery opens from Home as the first screen of the app and shows something (it came up black on a phone). */
    @Test(timeout = 90_000) fun galleryOpensFromHomeAsTheFirstScreen() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val scenario = ActivityScenario.launch(HomeActivity::class.java)
        Thread.sleep(2500)
        scenario.onActivity { a -> find(a.window.decorView, "แกลเลอรี")!!.performClick() }
        Thread.sleep(4000)
        val dump = GlassNav.dump(device)
        val log = device.executeShellCommand("logcat -d -t 800").lines().filter { l -> Regex("AndroidRuntime|FATAL|th\\.ogay\\.luma|Lumora").containsMatchIn(l) && !l.contains("StrictMode") }.takeLast(20).joinToString("\n")
        assertTrue("Gallery shows nothing after Home opened it\n$log\n$dump", dump.length > 120 && !dump.contains("แกลเลอรี รูปและวิดีโอทั้งหมด"))
    }
}
