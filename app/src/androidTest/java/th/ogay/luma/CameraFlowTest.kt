package th.ogay.luma

import android.Manifest
import android.content.Context
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class CameraFlowTest {
    @get:Rule val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val device=UiDevice.getInstance(inst)
    private val context:Context get()=inst.targetContext
    private fun until(ms:Long, check:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+ms
        while(SystemClock.elapsedRealtime()<end){if(check())return;SystemClock.sleep(250)}
        assertTrue("Timed out after $ms ms",check())
    }
    @Test fun multiFrameAiCapturesFourRealImages() = multiFrameFlow(4)
    @Test fun multiFrameAiCapturesThirtyTwoRealImages() = multiFrameFlow(32)

    @Test fun rawAndAiCapturesDngAndFourFrames() {
        val provider = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(context).get()
        val info = androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA.filter(provider.availableCameraInfos).first()
        org.junit.Assume.assumeTrue(androidx.camera.core.ImageCapture.getImageCaptureCapabilities(info).supportedOutputFormats
            .contains(androidx.camera.core.ImageCapture.OUTPUT_FORMAT_RAW_JPEG))
        multiFrameFlow(4, CaptureOutput.BOTH)
    }

    private fun multiFrameFlow(count: Int, output: CaptureOutput = CaptureOutput.AI) {
        context.getSharedPreferences("camera", Context.MODE_PRIVATE).edit()
            .putString("output", output.name).putInt("frames", count).putBoolean("software", true).commit()
        val old = context.luma.shots.all().map { it.id }.toSet()
        ActivityScenario.launch(CameraActivity::class.java).use {
            until(35000) { device.findObject(By.desc("ถ่ายภาพ"))?.isEnabled == true }
            device.findObject(By.desc("ซูม 2×")).click()
            until(10000) { device.findObject(By.desc("ถ่ายภาพ"))?.isEnabled == true }
            device.findObject(By.desc("ถ่ายภาพ")).click()
            var shot: Shot? = null
            until(120000) {
                shot = context.luma.shots.latest()
                shot != null && shot!!.id !in old && shot!!.stage != Stage.CAPTURING
            }
            assertEquals(count, shot!!.frames)
            assertEquals(output, shot!!.output)
            until(180000) {
                shot = context.luma.shots.get(shot!!.id)
                shot!!.stage in setOf(Stage.DONE, Stage.FAILED)
            }
            assertEquals(shot!!.error, Stage.DONE, shot!!.stage)
            assertTrue(shot!!.fusedFrames in 1..count)
            assertNotNull(shot!!.ai)
            if (output.needsRaw) assertNotNull(shot!!.raw) else assertNull(shot!!.raw)
            device.findObject(By.desc("เปิดรูปถ่ายล่าสุด")).click()
            device.wait(Until.findObject(By.text("ภาพ AI")), 15000).click()
            assertNotNull(device.wait(Until.findObject(By.textContains("MF1_SR1")), 10000))
        }
    }

    @Test fun rawSelectionReportsHardwareSupport() {
        context.getSharedPreferences("camera", Context.MODE_PRIVATE).edit().putString("output", "JPEG").commit()
        val before = context.luma.shots.all().map { it.id }.toSet()
        val provider = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(context).get()
        val info = androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA.filter(provider.availableCameraInfos).first()
        val supportsRaw = androidx.camera.core.ImageCapture.getImageCaptureCapabilities(info).supportedOutputFormats
            .contains(androidx.camera.core.ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
        ActivityScenario.launch(CameraActivity::class.java).use {
            until(35000) { device.findObject(By.desc("ถ่ายภาพ"))?.isEnabled == true }
            device.findObject(By.desc("รูปแบบ RAW")).click()
            if (supportsRaw) {
                until(25000) { device.findObject(By.desc("ถ่ายภาพ"))?.isEnabled == true }
                device.findObject(By.desc("ถ่ายภาพ")).click()
                var shot: Shot? = null
                until(60000) {
                    shot = context.luma.shots.latest()
                    shot != null && shot!!.id !in before && shot!!.output == CaptureOutput.RAW && shot!!.stage == Stage.ORIGINAL
                }
                assertNotNull(shot!!.raw)
                context.contentResolver.openInputStream(shot!!.raw!!)!!.use { stream ->
                    val header = ByteArray(4)
                    assertEquals(4, stream.read(header))
                    assertTrue(header[0] == 'I'.code.toByte() || header[0] == 'M'.code.toByte())
                }
            } else {
                assertEquals("JPEG", context.getSharedPreferences("camera", Context.MODE_PRIVATE).getString("output", ""))
            }
        }
    }

    @Test fun cropCapturePublishesOriginalAndOneAiCopy() {
        val before=context.luma.shots.all().map{it.id}.toSet()
        context.getSharedPreferences("camera",Context.MODE_PRIVATE).edit().putString("output","JPEG").putBoolean("auto",true).putBoolean("software",false).commit()
        ActivityScenario.launch(CameraActivity::class.java).use {
            val menu=device.wait(Until.findObject(By.desc("การตั้งค่ากล้อง")),30000)
            assertNotNull("Camera screen did not open",menu);menu.click()
            device.wait(Until.findObject(By.text("ครอปจากกล้องหลัก 1×")),10000).click()
            val stop=device.wait(Until.findObject(By.desc("ซูม 2×")),25000)
            assertNotNull("Missing app-controlled zoom",stop);stop.click()
            until(35000){device.findObject(By.desc("ถ่ายภาพ"))?.isEnabled==true}
            device.findObject(By.desc("ถ่ายภาพ")).click()
            var shot:Shot?=null
            until(60000){shot=context.luma.shots.latest(); shot!=null && shot!!.id !in before}
            val id=shot!!.id
            assertEquals(2f,shot!!.zoom,0.02f);assertTrue(shot!!.softwareCrop)
            assertTrue(shot!!.reason.startsAi)
            assertTrue(context.contentResolver.openInputStream(shot!!.uri)!!.use{it.read()>=0})
            until(180000){shot=context.luma.shots.get(id);shot?.stage in setOf(Stage.DONE,Stage.FAILED)}
            assertEquals(shot!!.error,Stage.DONE,shot!!.stage)
            val uri=shot!!.ai!!
            val dims=BitmapFactory.Options().apply{inJustDecodeBounds=true}
            context.contentResolver.openInputStream(uri)!!.use { stream->BitmapFactory.decodeStream(stream,null,dims) }
            assertTrue(abs(dims.outWidth.toLong()*dims.outHeight-TARGET_PIXELS)<120_000)
            assertEquals(uri,PhotoMedia.published(context,shot!!.aiName))
            val album=device.wait(Until.findObject(By.desc("เปิดรูปถ่ายล่าสุด")),15000) ?: device.findObject(By.desc("เปิดรูปถ่าย"))
            album.click()
            device.wait(Until.findObject(By.text("ภาพ AI")),20000).click()
            assertNotNull(device.wait(Until.findObject(By.textContains("AI SR1")),10000))
        }
    }
}
