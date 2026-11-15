package th.ogay.luma

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Makes a finished clip bigger and/or smoother: each source frame is enlarged by the Detail SR model (or simply
 * resized when it gets smaller), then frames are laid on the new frame-rate grid, with optical-flow frames
 * wherever the grid falls between two source frames. The sound is copied as it is.
 * AI runs on source frames only; the in-between frames are made at the large size afterwards, which costs far
 * less than running the model on them too.
 */
object VideoEnhancer {
    class Plan(val shortSide: Int, val fps: Float, val ai: Boolean, val profile: th.ogay.luma.ai.EnhanceProfile = th.ogay.luma.ai.EnhanceProfile.NATURAL,
        /** In-between frames: 0 finer (wider motion measuring), 1 balanced, 2 faster (narrow and the quickest flow preset). */ val flowMode: Int = 1,
        /** Fixed bitrate in Mbps chosen in the export sheet (0 = automatic). */ val mbps: Int = 0) {
        val flowWidth get() = when (flowMode) { 0 -> 640; 2 -> 320; else -> 480 }
    }
    /** [frame]: a fresh picture of the frame just made, about twice a second, for the progress page. */
    class Progress(val fraction: Float, val etaMs: Long, val frames: Int, val total: Int, val backend: String, val frame: android.graphics.Bitmap? = null)

    /** Output size for [shortSide] keeping the shape (even numbers, coded orientation). */
    fun size(w: Int, h: Int, shortSide: Int): Pair<Int, Int> {
        val s = shortSide.toDouble() / minOf(w, h)
        return ((w * s).roundToInt() / 2 * 2).coerceAtLeast(2) to ((h * s).roundToInt() / 2 * 2).coerceAtLeast(2)
    }

    fun run(c: Context, input: File, out: File, plan: Plan, cancel: AtomicBoolean, progress: (Progress) -> Unit = {}): File {
        check(OpenCVLoader.initLocal()) { "OpenCV not available" }
        val dec = FrameDecoder(c, Uri.fromFile(input))
        // Decoding and encoding run beside the flow and warping work on other threads; the three overlap.
        val frames = AsyncFrames(dec)
        var enc: AsyncEncoder? = null; var sr: FrameSr? = null; var blend: FlowBlender? = null
        var prev: Mat? = null; var prevGray: Mat? = null; var prevPts = 0L; var firstPts = -1L
        var paired = false
        val stepUs = 1_000_000.0 / plan.fps
        var k = 0L
        val started = SystemClock.elapsedRealtime()
        var lastPicture = 0L
        val total = dec.durationUs.coerceAtLeast(1)
        try {
            while (!cancel.get()) {
                val f = frames.next() ?: break
                if (firstPts < 0) firstPts = f.ptsUs
                val pts = f.ptsUs - firstPts
                if (enc == null) {
                    val (w, h) = size(f.rgb.cols(), f.rgb.rows(), plan.shortSide)
                    enc = AsyncEncoder(FrameEncoder(out, w, h, plan.fps, dec.rotation, input, plan.mbps * 1_000_000))
                    if (plan.ai && w > f.rgb.cols() * 1.05) sr = FrameSr(c, plan.profile)
                    if (plan.fps > dec.fps * 1.05f) blend = FlowBlender(w, h, plan.flowMode == 2)
                }
                val gray = if (blend != null) FlowBlender.gray(f.rgb, plan.flowWidth) else null
                val big = when {
                    sr != null -> sr.upscale(f.rgb, enc.width, enc.height) { cancel.get() }.also { f.rgb.release() }
                    f.rgb.cols() != enc.width || f.rgb.rows() != enc.height -> Mat().also { m ->
                        Imgproc.resize(f.rgb, m, Size(enc.width.toDouble(), enc.height.toDouble()), 0.0, 0.0, if (enc.width > f.rgb.cols()) Imgproc.INTER_CUBIC else Imgproc.INTER_AREA); f.rgb.release() }
                    else -> f.rgb
                }
                val p = prev
                if (p == null) {
                    while (k * stepUs <= pts) { enc.encode(big); k++ }
                } else {
                    paired = false
                    // Still pictures and cuts get the nearest frame: nothing to measure, and a warp across a cut only smears.
                    var kind = -1
                    while (k * stepUs < pts) {
                        val t = (k * stepUs - prevPts) / (pts - prevPts).coerceAtLeast(1)
                        val b = blend
                        if (b != null && kind < 0) { val d = FlowBlender.change(prevGray!!, gray!!); kind = if (d < FlowBlender.STILL || d > FlowBlender.CUT) 1 else 0 }
                        when {
                            b != null && t > 0.04 && t < 0.96 && kind == 0 -> { if (!paired) { b.pair(p, prevGray!!, big, gray!!); paired = true }; enc.encode(b.at(t)) }
                            t < 0.5 -> enc.encode(p)
                            else -> enc.encode(big)
                        }
                        k++
                    }
                    p.release(); prevGray?.release()
                }
                prev = big; prevGray = gray; prevPts = pts
                val done = (pts.toFloat() / total).coerceIn(0.001f, 1f)
                val spent = SystemClock.elapsedRealtime() - started
                val now = SystemClock.elapsedRealtime()
                val shot = if (now - lastPicture > 450) { lastPicture = now; picture(big, dec.rotation) } else null
                progress(Progress(done, (spent / done * (1 - done)).toLong(), enc.frames, (total / 1_000_000.0 * plan.fps).toInt(), sr?.backend ?: "", shot))
            }
            if (cancel.get()) error("cancelled")
            val e = enc ?: error("ไม่พบเฟรมในคลิป")
            // Hold the last frame until the grid reaches it.
            prev?.let { last -> while (k * stepUs <= prevPts) { e.encode(last); k++ } }
            e.finish()
            return out
        } catch (t: Throwable) {
            runCatching { enc?.close() }; enc = null; runCatching { out.delete() }
            throw t
        } finally {
            runCatching { enc?.close() }; frames.close(); runCatching { sr?.close() }; blend?.close(); prev?.release(); prevGray?.release()
        }
    }

    /** A 540-pixel-wide copy of an RGB frame for the progress page: sharp enough to see the new detail. */
    private fun picture(rgb: Mat, rotation: Int): android.graphics.Bitmap? = runCatching {
        val w0 = minOf(540, rgb.cols()); val h0 = (w0.toDouble() * rgb.rows() / rgb.cols()).roundToInt().coerceAtLeast(1)
        val small = Mat(); Imgproc.resize(rgb, small, Size(w0.toDouble(), h0.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        // Turned upright the way the clip is shown.
        when (((rotation % 360) + 360) % 360) { 90 -> org.opencv.core.Core.rotate(small, small, org.opencv.core.Core.ROTATE_90_CLOCKWISE); 180 -> org.opencv.core.Core.rotate(small, small, org.opencv.core.Core.ROTATE_180); 270 -> org.opencv.core.Core.rotate(small, small, org.opencv.core.Core.ROTATE_90_COUNTERCLOCKWISE) }
        val w = small.cols(); val h = small.rows()
        val rgba = Mat(); Imgproc.cvtColor(small, rgba, Imgproc.COLOR_RGB2RGBA); small.release()
        android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).also { org.opencv.android.Utils.matToBitmap(rgba, it); rgba.release() }
    }.getOrNull()
}
