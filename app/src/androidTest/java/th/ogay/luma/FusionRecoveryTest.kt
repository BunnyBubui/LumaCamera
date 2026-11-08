package th.ogay.luma

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class FusionRecoveryTest {
    @Test fun recoversCompletedFramesAndSkipsIncompleteJpeg() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val folder = File(context.filesDir, "bursts/$id").apply { mkdirs() }
        try {
            val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(0xff778899.toInt())
            for (name in listOf("0000.jpg", "0001.jpg")) {
                File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            }
            bitmap.recycle()
            File(folder, "0002.jpg").writeText("interrupted write")
            val shot = Shot(id, 1, Uri.fromFile(File(folder, "0000.jpg")), Dimensions(32, 24),
                1f, true, null, null, Decision.MANUAL_AI, Stage.ORIGINAL, null, 0, null,
                false, output = CaptureOutput.AI, frames = 1, requestedFrames = 32)
            context.luma.shots.insert(shot)
            val result = MultiFrameFusion(context).process(shot) { false }
            assertEquals(2, result.used)
            assertEquals(2, context.luma.shots.get(id)!!.frames)
            assertEquals(Dimensions(32, 24), PhotoMedia.dimensions(result.file))
        } finally {
            context.luma.shots.writableDatabase.delete("shots", "id=?", arrayOf(id))
            folder.deleteRecursively()
        }
    }
}
