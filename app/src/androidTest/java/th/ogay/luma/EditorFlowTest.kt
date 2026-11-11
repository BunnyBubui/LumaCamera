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
    private fun picture(w: Int, h: Int): File {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(b).apply { drawColor(Color.rgb(40, 90, 160)); drawCircle(w / 3f, h / 2f, h / 4f, Paint().apply { color = Color.YELLOW }) }
        return File(context.cacheDir, "editor-test-${System.nanoTime()}.jpg").also { f -> f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
    }

    @Test fun photoEditorSavesATurnedCopy() {
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
    @Test fun videoEditorExportsAClip() {
        // A two-second clip made from a still, so the test does not depend on the emulator camera.
        val still = picture(640, 360)
        val clip = File(context.cacheDir, "editor-test-${System.nanoTime()}.mp4")
        val made = CountDownLatch(1); var failure: Exception? = null
        inst.runOnMainSync {
            val item = EditedMediaItem.Builder(MediaItem.Builder().setUri(Uri.fromFile(still)).setMimeType(MimeTypes.IMAGE_JPEG).setImageDurationMs(2000).build()).setFrameRate(30).build()
            Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264).addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) { made.countDown() }
                override fun onError(composition: Composition, result: ExportResult, e: ExportException) { failure = e; made.countDown() }
            }).build().start(item, clip.path)
        }
        assertTrue("Test clip was not made in time", made.await(60, TimeUnit.SECONDS))
        assertTrue("Test clip failed: $failure", failure == null && clip.length() > 0)

        val before = MediaLedger.all(context).map { it.uri.toString() }.toSet()
        ActivityScenario.launch<VideoEditorActivity>(VideoEditorActivity.intent(context, Uri.fromFile(clip))).use {
            assertNotNull("Speed tab is missing · ${GlassNav.dump(device)}", device.wait(Until.findObject(By.desc("ความเร็ว")), 10000))
            device.findObject(By.desc("ความเร็ว")).click()
            device.wait(Until.findObject(By.desc("2×")), 5000)!!.click()
            device.findObject(By.desc("ฟิลเตอร์")).click()
            device.wait(Until.findObject(By.descStartsWith("ฟิลเตอร์ ")), 5000)
            device.findObjects(By.descStartsWith("ฟิลเตอร์ ")).getOrNull(1)?.click()
            device.findObject(By.desc("ข้อความ")).click()
            device.wait(Until.findObject(By.desc("ข้อความบนวิดีโอ")), 5000)!!.text = "Lumora"
            device.findObject(By.desc("ส่งออก")).click()
            until(120000, "No edited clip was published") { MediaLedger.all(context).any { it.uri.toString() !in before && it.title.startsWith("Lumora_EDIT_") } }
        }
        still.delete(); clip.delete()
    }
}
