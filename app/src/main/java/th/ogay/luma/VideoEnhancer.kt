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
    class Plan(val shortSide: Int, val fps: Float, val ai: Boolean)
    class Progress(val fraction: Float, val etaMs: Long, val frames: Int, val backend: String)

    /** Output size for [shortSide] keeping the shape (even numbers, coded orientation). */
    fun size(w: Int, h: Int, shortSide: Int): Pair<Int, Int> {
        val s = shortSide.toDouble() / minOf(w, h)
        return ((w * s).roundToInt() / 2 * 2).coerceAtLeast(2) to ((h * s).roundToInt() / 2 * 2).coerceAtLeast(2)
    }

    fun run(c: Context, input: File, out: File, plan: Plan, cancel: AtomicBoolean, progress: (Progress) -> Unit = {}): File {
        check(OpenCVLoader.initLocal()) { "OpenCV not available" }
        val dec = FrameDecoder(c, Uri.fromFile(input))
        var enc: FrameEncoder? = null; var sr: FrameSr? = null; var blend: FlowBlender? = null
        var prev: Mat? = null; var prevGray: Mat? = null; var prevPts = 0L; var firstPts = -1L
        var paired = false
        val stepUs = 1_000_000.0 / plan.fps
        var k = 0L
        val started = SystemClock.elapsedRealtime()
        val total = dec.durationUs.coerceAtLeast(1)
        try {
            while (!cancel.get()) {
                val f = dec.next() ?: break
                if (firstPts < 0) firstPts = f.ptsUs
                val pts = f.ptsUs - firstPts
                if (enc == null) {
                    val (w, h) = size(f.rgb.cols(), f.rgb.rows(), plan.shortSide)
                    enc = FrameEncoder(out, w, h, plan.fps, dec.rotation, input)
                    if (plan.ai && w > f.rgb.cols() * 1.05) sr = FrameSr(c)
                    if (plan.fps > dec.fps * 1.05f) blend = FlowBlender(w, h)
                }
                val gray = if (blend != null) FlowBlender.gray(f.rgb) else null
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
                    while (k * stepUs < pts) {
                        val t = (k * stepUs - prevPts) / (pts - prevPts).coerceAtLeast(1)
                        val b = blend
                        when {
                            b != null && t > 0.04 && t < 0.96 -> { if (!paired) { b.pair(p, prevGray!!, big, gray!!); paired = true }; enc.encode(b.at(t)) }
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
                progress(Progress(done, (spent / done * (1 - done)).toLong(), enc.frames, sr?.backend ?: ""))
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
            runCatching { enc?.close() }; dec.close(); runCatching { sr?.close() }; blend?.close(); prev?.release(); prevGray?.release()
        }
    }
}
