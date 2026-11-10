package th.ogay.luma

import android.graphics.Bitmap
import android.widget.ImageView
import androidx.camera.view.PreviewView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.util.concurrent.Executors

/**
 * Live portrait look: a small copy of the viewfinder is segmented (ML Kit selfie, stream mode) and its blurred
 * background is drawn over the preview, leaving the person sharp. Work stays small (320 px wide, one frame in
 * flight, about 8 a second) so the viewfinder itself never waits. The blur matches the strength of the saved photo.
 */
class PortraitPreview(private val preview: PreviewView, private val view: ImageView, private val aperture: () -> Float) {
    private val width = 320
    private val segmenter by lazy { Segmentation.getClient(SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.STREAM_MODE).build()) }
    private val worker = Executors.newSingleThreadExecutor()
    private var running = false
    @Volatile private var inFlight = false
    private var closed = false
    private val tick = object : Runnable { override fun run() {
        if (!running) return
        if (!inFlight) frame()
        preview.postDelayed(this, 120)
    } }

    fun start() { if (running || closed) return; running = true; preview.post(tick) }
    fun stop() {
        if (!running) return
        running = false; preview.removeCallbacks(tick)
        view.animate().alpha(0f).setDuration(160).withEndAction { if (!running) view.setImageDrawable(null) }.start()
    }
    fun close() { stop(); closed = true; worker.shutdown(); runCatching { segmenter.close() } }

    private fun frame() {
        val small = preview.smallFrame(width) ?: return
        val f = aperture()
        inFlight = true
        segmenter.process(InputImage.fromBitmap(small, 0))
            .addOnSuccessListener(worker) { mask ->
                val out = runCatching { compose(small, mask, f) }.getOrNull()
                preview.post {
                    inFlight = false
                    if (running && out != null) { view.setImageBitmap(out); if (view.alpha < 1f) view.animate().alpha(1f).setDuration(200).start() }
                }
            }
            .addOnFailureListener(worker) { preview.post { inFlight = false } }
    }

    /** Background pixels blurred and opaque, the person transparent, so the live preview shows through. */
    private fun compose(src: Bitmap, mask: SegmentationMask, aperture: Float): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        // Same strength as PhotoEffects.portrait (sigma 22·1.4/f on a ~2000 px wide photo), scaled to this width.
        val sigma = (22f * 1.4f / aperture.coerceIn(1f, 22f)) * w / 2000f
        val r = sigma.coerceIn(1f, 14f).toInt()
        val blur = px.copyOf(); repeat(3) { boxBlur(blur, w, h, r) }
        val buf = mask.buffer; buf.rewind()
        val weights = FloatArray(mask.width * mask.height); buf.asFloatBuffer().get(weights)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val m = weights[(y * mask.height / h) * mask.width + x * mask.width / w]
            val person = ((m - 0.15f) / 0.7f).coerceIn(0f, 1f)
            val a = ((1f - person) * 255).toInt()
            blur[i] = (a shl 24) or (blur[i] and 0xffffff)
        }
        return Bitmap.createBitmap(blur, w, h, Bitmap.Config.ARGB_8888)
    }

    /** One horizontal and one vertical running-sum pass; three rounds approximate a Gaussian. */
    private fun boxBlur(p: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(p.size)
        val div = 2 * r + 1
        for (y in 0 until h) {
            var sr = 0; var sg = 0; var sb = 0; val row = y * w
            for (k in -r..r) { val c = p[row + k.coerceIn(0, w - 1)]; sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255 }
            for (x in 0 until w) {
                tmp[row + x] = (sr / div shl 16) or (sg / div shl 8) or (sb / div)
                val out = p[row + (x - r).coerceIn(0, w - 1)]; val inn = p[row + (x + r + 1).coerceIn(0, w - 1)]
                sr += ((inn shr 16) and 255) - ((out shr 16) and 255); sg += ((inn shr 8) and 255) - ((out shr 8) and 255); sb += (inn and 255) - (out and 255)
            }
        }
        for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0
            for (k in -r..r) { val c = tmp[k.coerceIn(0, h - 1) * w + x]; sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255 }
            for (y in 0 until h) {
                p[y * w + x] = (sr / div shl 16) or (sg / div shl 8) or (sb / div)
                val out = tmp[(y - r).coerceIn(0, h - 1) * w + x]; val inn = tmp[(y + r + 1).coerceIn(0, h - 1) * w + x]
                sr += ((inn shr 16) and 255) - ((out shr 16) and 255); sg += ((inn shr 8) and 255) - ((out shr 8) and 255); sb += (inn and 255) - (out and 255)
            }
        }
    }
}
