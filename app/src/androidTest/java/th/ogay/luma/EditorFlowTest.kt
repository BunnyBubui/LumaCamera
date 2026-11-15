package th.ogay.luma

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The photo editor saves a turned copy; the video editor exports a sped-up clip with a caption and a look. */
@RunWith(AndroidJUnit4::class)
class EditorFlowTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(inst)
    private val context: Context get() = inst.targetContext
    private fun until(ms: Long, what: String, check: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < end) { if (check()) return; SystemClock.sleep(200) }
        assertTrue("$what · ${GlassNav.dump(device)}", check())
    }
    /** Waits for a control to appear, then taps it; the editor animates between tools, so an immediate lookup can miss. */
    private fun tap(sel: androidx.test.uiautomator.BySelector, what: String, ms: Long = 10000) {
        val o = device.wait(Until.findObject(sel), ms)
        assertNotNull("$what is missing · ${GlassNav.dump(device)}", o)
        o!!.click()
    }
    private fun picture(w: Int, h: Int): File {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(b).apply { drawColor(Color.rgb(40, 90, 160)); drawCircle(w / 3f, h / 2f, h / 4f, Paint().apply { color = Color.YELLOW }) }
        return File(context.cacheDir, "editor-test-${System.nanoTime()}.jpg").also { f -> f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
    }

    @Test(timeout = 120_000) fun photoEditorSavesATurnedCopy() {
        val before = context.luma.shots.all().map { it.id }.toSet()
        val src = picture(400, 300)
        ActivityScenario.launch<PhotoEditorActivity>(PhotoEditorActivity.intent(context, Uri.fromFile(src))).use {
            val turn = device.wait(Until.findObject(By.desc("หมุน 90°")), 10000)
            assertNotNull("Rotate chip is missing · ${GlassNav.dump(device)}", turn)
            turn!!.click()
            device.findObject(By.desc("ปรับ")).click()
            assertNotNull(device.wait(Until.findObject(By.desc("ความสว่าง")), 5000))
            device.findObject(By.desc("บันทึก")).click()
            until(30000, "No edited copy was saved") { context.luma.shots.all().any { it.id !in before } }
            val shot = context.luma.shots.all().first { it.id !in before }
            assertEquals("Turned copy should be 300 × 400", Dimensions(300, 400), shot.size)
        }
        src.delete()
    }

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    @Test(timeout = 300_000) fun videoEditorExportsAClip() {
        val clip = TestMedia.clip(inst)
        val before = MediaLedger.all(context).map { it.uri.toString() }.toSet()
        ActivityScenario.launch<VideoEditorActivity>(VideoEditorActivity.intent(context, Uri.fromFile(clip))).use {
            assertNotNull("Speed tab is missing · ${GlassNav.dump(device)}", device.wait(Until.findObject(By.desc("ความเร็ว")), 10000))
            device.findObject(By.desc("ความเร็ว")).click()
            // The speed tool now uses a slider plus an explicit numeric entry, not a "2×" chip.
            assertNotNull("Numeric speed control missing · ${GlassNav.dump(device)}",
                device.wait(Until.findObject(By.desc("ใส่ตัวเลขความเร็ว")), 10000))
            device.findObject(By.desc("ใส่ตัวเลขความเร็ว")).click()
            val speedInput = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000)
            assertNotNull("Speed input missing · ${GlassNav.dump(device)}", speedInput)
            speedInput!!.text = "2"
            tap(By.text("ตกลง"), "OK button")
            tap(By.desc("ฟิลเตอร์"), "Filter tab")
            device.wait(Until.findObject(By.descStartsWith("ฟิลเตอร์ ")), 5000)
            device.findObjects(By.descStartsWith("ฟิลเตอร์ ")).getOrNull(1)?.click()
            tap(By.desc("ข้อความ"), "Text tab")
            device.wait(Until.findObject(By.desc("ข้อความบนวิดีโอ")), 5000)!!.text = "Lumora"
            tap(By.desc("ส่งออก"), "Export button")
            // The export sheet opens first (resolution and frame rate); keep the source settings.
            val startBtn = device.wait(Until.findObject(By.desc("เริ่มส่งออก")), 8000)
            if (startBtn == null) {
                val log = device.executeShellCommand("logcat -d -t 600").lines().filter { l -> Regex("AndroidRuntime|FATAL|th\\.ogay\\.luma|Lumora|lumora").containsMatchIn(l) && !l.contains("StrictMode") }.takeLast(25).joinToString("\n")
                throw AssertionError("Export sheet did not open (free ${context.filesDir.usableSpace / 1_000_000} MB)\n$log\n${GlassNav.dump(device).take(400)}")
            }
            tap(By.desc("เริ่มส่งออก"), "Start export button")
            // A short clip can finish (and the editor close) before the progress card is ever seen, so only the result is checked.
            until(150000, "No edited clip was published") { device.findObject(By.text("ส่งออกไม่ได้")) != null || MediaLedger.all(context).any { it.uri.toString() !in before && it.title.startsWith("Lumora_EDIT_") } }
            assertTrue("Export failed · ${device.findObject(By.textContains("วิดีโอต้นฉบับยังอยู่ครบ"))?.text}", device.findObject(By.text("ส่งออกไม่ได้")) == null)
        }
        clip.delete()
    }
}
