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

    /** Home lists the features, and a photo-mode card answers the camera with that mode. */
    @Test(timeout = 60_000) fun homeShowsFeaturesAndReturnsAMode() {
        val scenario = ActivityScenario.launchActivityForResult(HomeActivity::class.java)
        Thread.sleep(1500)
        scenario.onActivity { a ->
            val root = a.window.decorView
            for (name in listOf("ถ่ายภาพ", "บุคคล", "วิดีโอ", "แกลเลอรี", "ตัดต่อภาพ", "ตัดต่อวิดีโอ", "ฟิล์ม")) assertNotNull("Card $name", find(root, name))
            find(root, "โปร")!!.performClick()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val result = scenario.result
        assertEquals(Activity.RESULT_OK, result.resultCode)
        assertEquals(PhotoMode.PRO.name, result.resultData.getStringExtra(HomeActivity.RESULT_MODE))
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
}
