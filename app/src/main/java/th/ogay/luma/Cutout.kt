package th.ogay.luma

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Takes the background out of a picture, keeping the people in it (the same on-device person finder as Portrait). The result
 * is a PNG with see-through background, used as a picture layer. Pictures without a person come back almost empty.
 */
object Cutout {
    /** How solid a pixel is for the finder's confidence [c] (0..1): a soft edge between 35 % and 65 %. */
    fun alpha(c: Float): Int { val x = ((c - 0.35f) / 0.3f).coerceIn(0f, 1f); return (x * x * (3 - 2 * x) * 255).toInt() }

    /** Runs on the calling thread. Throws with a short Thai reason when it cannot. */
    fun make(c: Context, uri: Uri): File {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val src = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: error("เปิดภาพไม่ได้")
        val img = src.copy(Bitmap.Config.ARGB_8888, true)
        val seg = Segmentation.getClient(SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build())
        try {
            val mask = Tasks.await(seg.process(InputImage.fromBitmap(img, 0)), 30, TimeUnit.SECONDS)
            val buf = mask.buffer; buf.rewind(); val conf = FloatArray(mask.width * mask.height); buf.asFloatBuffer().get(conf)
            val px = IntArray(img.width * img.height); img.getPixels(px, 0, img.width, 0, 0, img.width, img.height)
            var kept = 0
            for (y in 0 until img.height) for (x in 0 until img.width) {
                val a = alpha(conf[(y * mask.height / img.height) * mask.width + x * mask.width / img.width])
                val i = y * img.width + x; px[i] = (a shl 24) or (px[i] and 0xffffff); if (a > 128) kept++
            }
            if (kept < px.size / 200) error("ไม่พบคนในภาพนี้ จึงลบพื้นหลังไม่ได้")
            img.setPixels(px, 0, img.width, 0, 0, img.width, img.height)
            val out = File(File(c.filesDir, "layers").apply { mkdirs() }, "cutout_${System.currentTimeMillis()}.png")
            out.outputStream().use { img.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return out
        } finally { seg.close() }
    }
}
