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
 * Turns the segmenter's confidence into "how sharp should this pixel stay" (1 = subject, 0 = fully blurred background).
 * With a person in view the outline is tightened and feathered. With nobody in view the segmenter only returns
 * scattered patches, which looked like floating blobs of blur; then the picture gets a lens-like falloff instead:
 * sharp around the focus point, smoothly softer towards the edges.
 */
object PortraitMask {
    fun subject(weights: FloatArray, w: Int, h: Int, focus: Pair<Float, Float>?): FloatArray {
        var sum = 0.0
        for (i in weights.indices) { val t = ((weights[i] - 0.35f) / 0.4f).coerceIn(0f, 1f); val v = t * t * (3 - 2 * t); weights[i] = v; sum += v }
        if (sum / weights.size < 0.04) {
            val fx = (focus?.first ?: 0.5f) * w; val fy = (focus?.second ?: 0.5f) * h
            val m = minOf(w, h).toFloat(); val r0 = 0.2f * m; val r1 = 0.8f * m
            for (y in 0 until h) { val dy = y - fy; val row = y * w
                for (x in 0 until w) { val dx = x - fx; val t = ((kotlin.math.sqrt(dx * dx + dy * dy) - r0) / (r1 - r0)).coerceIn(0f, 1f); weights[row + x] = 1f - t * t * (3 - 2 * t) } }
        } else feather(weights, w, h, maxOf(1, w / 140))
        return weights
    }
    /** Two running-mean passes soften the outline so the edge between sharp and blurred is not a hard cut. */
    private fun feather(p: FloatArray, w: Int, h: Int, r: Int) {
        val tmp = FloatArray(p.size); val div = (2 * r + 1).toFloat()
        for (y in 0 until h) { val row = y * w; var s = 0f
            for (k in -r..r) s += p[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) { tmp[row + x] = s / div; s += p[row + (x + r + 1).coerceIn(0, w - 1)] - p[row + (x - r).coerceIn(0, w - 1)] } }
        for (x in 0 until w) { var s = 0f
            for (k in -r..r) s += tmp[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) { p[y * w + x] = s / div; s += tmp[(y + r + 1).coerceIn(0, h - 1) * w + x] - tmp[(y - r).coerceIn(0, h - 1) * w + x] } }
    }
}

/**
 * Live portrait look. A small copy of the viewfinder is segmented about ten times a second (ML Kit selfie, stream
 * mode); only that subject mask is computed on the side. On Android 13 and later the blur itself runs on the GPU on
 * every live frame (a shader on the viewfinder that reads the mask), so the background never trails behind the
 * picture the way a drawn-over copy did. Older Android keeps the drawn-over blurred copy.
 */
class PortraitPreview(private val preview: PreviewView, private val view: ImageView, private val aperture: () -> Float,
                      private val focus: () -> Pair<Float, Float>? = { null }, private val onShader: (Boolean) -> Unit = {}) {
    private val width = 256
    private val segmenter by lazy { Segmentation.getClient(SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.STREAM_MODE).build()) }
    private val worker = Executors.newSingleThreadExecutor()
    private var running = false
    @Volatile private var inFlight = false
    private var closed = false
    private var smooth: FloatArray? = null
    private val shader: android.graphics.RuntimeShader? = if (android.os.Build.VERSION.SDK_INT >= 33) runCatching { android.graphics.RuntimeShader(AGSL) }.getOrNull() else null
    /** True while the GPU blur owns the viewfinder's render effect (the Log view must not clear it). */
    var shaderOn = false; private set
    private val tick = object : Runnable { override fun run() {
        if (!running) return
        if (!inFlight) frame()
        preview.postDelayed(this, if (shader != null) 80 else 120)
    } }

    fun start() { if (running || closed) return; running = true; smooth = null; preview.post(tick) }
    fun stop() {
        if (!running) return
        running = false; preview.removeCallbacks(tick)
        if (shaderOn && android.os.Build.VERSION.SDK_INT >= 31) { shaderOn = false; preview.setRenderEffect(null); onShader(false) }
        view.animate().alpha(0f).setDuration(160).withEndAction { if (!running) view.setImageDrawable(null) }.start()
    }
    fun close() { stop(); closed = true; worker.shutdown(); runCatching { segmenter.close() } }

    private fun frame() {
        val small = preview.smallFrame(width) ?: return
        val f = aperture(); val at = focus()
        inFlight = true
        segmenter.process(InputImage.fromBitmap(small, 0))
            .addOnSuccessListener(worker) { mask ->
                val out = runCatching {
                    val buf = mask.buffer; buf.rewind()
                    val weights = FloatArray(mask.width * mask.height); buf.asFloatBuffer().get(weights)
                    val subject = PortraitMask.subject(weights, mask.width, mask.height, at)
                    // A little memory between frames keeps the outline from shimmering.
                    val prev = smooth
                    if (prev != null && prev.size == subject.size) for (i in subject.indices) subject[i] = prev[i] * 0.45f + subject[i] * 0.55f
                    smooth = subject
                    if (shader != null) maskBitmap(subject, mask.width, mask.height) else compose(small, subject, mask.width, mask.height, f)
                }.getOrNull()
                preview.post {
                    inFlight = false
                    if (!running || out == null) return@post
                    if (shader != null && android.os.Build.VERSION.SDK_INT >= 33) applyShader(out, f)
                    else { view.setImageBitmap(out); if (view.alpha < 1f) view.animate().alpha(1f).setDuration(200).start() }
                }
            }
            .addOnFailureListener(worker) { preview.post { inFlight = false } }
    }

    @androidx.annotation.RequiresApi(33)
    private fun applyShader(mask: Bitmap, aperture: Float) {
        val s = shader ?: return
        if (preview.width == 0 || preview.height == 0) return
        runCatching {
            s.setInputShader("mask", android.graphics.BitmapShader(mask, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP).apply { filterMode = android.graphics.BitmapShader.FILTER_MODE_LINEAR })
            s.setFloatUniform("maskScale", mask.width / preview.width.toFloat(), mask.height / preview.height.toFloat())
            // Same strength as the saved photo (sigma 22·1.4/f on a 2000 px wide picture), as a disc radius on this view.
            s.setFloatUniform("radius", (22f * 1.4f / aperture.coerceIn(1f, 22f)) * preview.width / 2000f * 2.4f)
            preview.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(s, "content"))
            if (!shaderOn) { shaderOn = true; onShader(true) }
        }
    }

    private fun maskBitmap(subject: FloatArray, w: Int, h: Int): Bitmap {
        val px = IntArray(w * h)
        for (i in px.indices) { val v = (subject[i] * 255).toInt().coerceIn(0, 255); px[i] = (0xff shl 24) or (v shl 16) or (v shl 8) or v }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Older Android: background pixels blurred and opaque, the subject transparent, so the live preview shows through. */
    private fun compose(src: Bitmap, subject: FloatArray, mw: Int, mh: Int, aperture: Float): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        val sigma = (22f * 1.4f / aperture.coerceIn(1f, 22f)) * w / 2000f
        val r = sigma.coerceIn(1f, 14f).toInt()
        val blur = px.copyOf(); repeat(3) { boxBlur(blur, w, h, r) }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val a = ((1f - subject[(y * mh / h) * mw + x * mw / w]) * 255).toInt().coerceIn(0, 255)
            blur[i] = (a shl 24) or (blur[i] and 0xffffff)
        }
        return Bitmap.createBitmap(blur, w, h, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        /** Disc blur whose radius follows the mask: the subject stays untouched, the background gets a lens-like bokeh. */
        const val AGSL = """
            uniform shader content;
            uniform shader mask;
            uniform float2 maskScale;
            uniform float radius;
            half4 main(float2 p) {
                half4 c = content.eval(p);
                float m = mask.eval(p * maskScale).r;
                float r = radius * (1.0 - m);
                if (r < 0.75) { return c; }
                half4 acc = c;
                float total = 1.0;
                for (int i = 1; i <= 18; i++) {
                    float fi = float(i);
                    float a = fi * 2.39996323;
                    float2 q = p + float2(cos(a), sin(a)) * (sqrt(fi / 18.0) * r);
                    acc += content.eval(q);
                    total += 1.0;
                }
                return acc / total;
            }
        """
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
