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
        while (SystemClock.elapsedRealtime() < until) { val ready = try { condition() } catch (_: androidx.test.uiautomator.StaleObjectException) { false }; if (ready) return; SystemClock.sleep(200) }
        assertTrue("Video session did not finish discovery", condition())
    }
    @Test fun sixtyFpsIsRetainedAcrossRecreateOrExplicitlyRejected() {
        // The choice lives in "video_fps_s" (string) since the video settings sheet; "video_fps" is only an old fallback.
        val prefs = context.getSharedPreferences("camera", 0)
        val before = prefs.getString("video_fps_s", null)
        prefs.edit().putString("video_fps_s", "60").commit()
        try {
            ActivityScenario.launch<VideoActivity>(Intent(context, VideoActivity::class.java)).use { activity ->
                fun settled() = device.findObject(By.desc("เริ่มบันทึกวิดีโอ"))?.isEnabled == true || device.findObject(By.textContains("ชุดนี้ใช้ไม่ได้")) != null
                waitFor { settled() }
                assertEquals("60", prefs.getString("video_fps_s", null))
                assertNotNull(device.findObject(By.textContains("60")))
                activity.recreate()
                waitFor { settled() }
                assertEquals("60", prefs.getString("video_fps_s", null))
                assertNotNull(device.findObject(By.textContains("60")))
            }
        } finally { prefs.edit().apply { if (before == null) remove("video_fps_s") else putString("video_fps_s", before) }.commit() }
    }
    @org.junit.Ignore("Mode or AI row removed in round 5") @Test fun slowMotionEntryUsesSameDiscoveryBeforeSettingsAndDoesNotRequestAudio() {
        ActivityScenario.launch<VideoActivity>(Intent(context, VideoActivity::class.java).putExtra("mode", "SLOW")).use {
            waitFor { device.findObject(By.desc("เริ่มบันทึกวิดีโอ"))?.isEnabled == true || device.findObject(By.textContains("ชุดนี้ใช้ไม่ได้")) != null }
            assertNotNull(device.findObject(By.textContains("Slow-motion")))
            assertNotNull(device.findObject(By.textContains("ไม่มีเสียง")))
            val ready = device.findObject(By.desc("เริ่มบันทึกวิดีโอ"))!!.isEnabled
            if (!ready) assertNotNull(device.findObject(By.textContains("high-speed")))
            device.findObject(By.textContains("SLOW · ตัวเลือก"))?.click()
            // Unsupported high-speed still exposes the diagnostic dialog, never a microphone prompt.
            assertNull(device.findObject(By.textContains("Allow Lumora")))
        }
    }
    @org.junit.Ignore("Night mode was removed from the mode bar in round 8") @Test fun softwareNightAfterHdrKeepsAiBurstWhenReusingSession() {
        val provider = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(context).get()
        val extensions = androidx.camera.extensions.ExtensionsManager.getInstanceAsync(context, provider).get()
        val selector = androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA
        val nativeNight = extensions.isExtensionAvailable(selector, androidx.camera.extensions.ExtensionMode.NIGHT)
        val prefs = context.getSharedPreferences("camera", 0)
        prefs.edit().putString("photo_mode", "HDR").putString("output", "JPEG").putString("timer", "0").commit()
        ActivityScenario.launch<CameraActivity>(Intent(context, CameraActivity::class.java)).use { scenario ->
            waitFor { device.findObject(By.desc("ถ่ายภาพ"))?.isEnabled == true }
            // Perform the user-facing mode action on the UI thread; the strip is rebuilt after selection.
            scenario.onActivity { activity ->
                val matches = ArrayList<android.view.View>()
                activity.window.decorView.findViewsWithText(matches, "โหมดกลางคืน", android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
                assertTrue("Night mode action must exist", matches.firstOrNull()?.performClick() == true)
            }
            var settled = false
            waitFor {
                scenario.onActivity { activity ->
                    // Glass UI: the status row shows a night icon described as "กลางคืน · <path>".
                    val labels = ArrayList<android.view.View>()
                    activity.window.decorView.findViewsWithText(labels, "กลางคืน ·", android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
                    val shutter = ArrayList<android.view.View>()
                    activity.window.decorView.findViewsWithText(shutter, "ถ่ายภาพ", android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
                    settled = labels.isNotEmpty() && shutter.any { it.contentDescription == "ถ่ายภาพ" && it.isEnabled }
                }
                settled
            }
            if (!nativeNight) scenario.onActivity { activity ->
                val night = ArrayList<android.view.View>()
                activity.window.decorView.findViewsWithText(night, "กลางคืน · AI รวมเฟรม", android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
                assertTrue("Software Night must route through AI after a compatible HDR bind", night.isNotEmpty())
            }
        }
    }
}
