package th.ogay.luma

import android.Manifest
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoSessionDeviceTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.CAMERA)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private fun waitFor(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 40000
        while (SystemClock.elapsedRealtime() < until) { if (condition()) return; SystemClock.sleep(200) }
        assertTrue("Video session did not finish discovery", condition())
    }
    @Test fun sixtyFpsIsRetainedAcrossRecreateOrExplicitlyRejected() {
        val prefs = context.getSharedPreferences("camera", 0)
        val before = prefs.getInt("video_fps", 30)
        prefs.edit().putInt("video_fps", 60).commit()
        try {
            ActivityScenario.launch<VideoActivity>(Intent(context, VideoActivity::class.java)).use { activity ->
                fun settled() = device.findObject(By.desc("เริ่มบันทึกวิดีโอ"))?.isEnabled == true || device.findObject(By.textContains("ชุดนี้ใช้ไม่ได้")) != null
                waitFor { settled() }
                assertEquals(60, prefs.getInt("video_fps", -1))
                assertNotNull(device.findObject(By.textContains("60")))
                activity.recreate()
                waitFor { settled() }
                assertEquals(60, prefs.getInt("video_fps", -1))
                assertNotNull(device.findObject(By.textContains("60")))
            }
        } finally { prefs.edit().putInt("video_fps", before).commit() }
    }
    @Test fun slowMotionEntryUsesSameDiscoveryBeforeSettingsAndDoesNotRequestAudio() {
        ActivityScenario.launch<VideoActivity>(Intent(context, VideoActivity::class.java).putExtra("mode", "SLOW")).use {
            waitFor { device.findObject(By.desc("เริ่มบันทึกวิดีโอ"))?.isEnabled == true || device.findObject(By.textContains("ชุดนี้ใช้ไม่ได้")) != null }
            assertNotNull(device.findObject(By.textContains("SLOW MOTION")))
            assertNotNull(device.findObject(By.textContains("ไม่มีเสียง")))
            val ready = device.findObject(By.desc("เริ่มบันทึกวิดีโอ"))!!.isEnabled
            if (!ready) assertNotNull(device.findObject(By.textContains("high-speed")))
            device.findObject(By.textContains("Slow-motion · ตัวเลือก"))?.click()
            // Unsupported high-speed still exposes the diagnostic dialog, never a microphone prompt.
            assertNull(device.findObject(By.textContains("Allow Luma")))
        }
    }
}
