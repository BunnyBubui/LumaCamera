package th.ogay.luma

import android.content.Context
import android.net.Uri
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Smooth slow motion by optical flow: every pair of neighbouring frames gets [factor] − 1 new frames in between
 * ([FlowBlender]). The result keeps the source frame rate, so it is [factor] times longer; it has no sound.
 */
object SlowFlow {
    /** New frames per source frame needed for [speed] (0.1× → 10). */
    fun factor(speed: Float) = ceil(1f / speed - 1e-3f).toInt().coerceIn(1, 16)

    /** [width]/[height] as shown (rotation applied). */
    class Result(val file: File, val factor: Int, val frames: Int, val width: Int, val height: Int)

    fun render(c: Context, uri: Uri, fromMs: Long, toMs: Long, factor: Int, out: File, cancel: AtomicBoolean,
               maxSide: Int = 1920, progress: (Float) -> Unit = {}): Result {
        check(OpenCVLoader.initLocal()) { "OpenCV not available" }
        val dec = FrameDecoder(c, uri, fromMs * 1000, toMs * 1000)
        val frames = AsyncFrames(dec)
        var enc: AsyncEncoder? = null; var blend: FlowBlender? = null
        var prev: Mat? = null; var prevGray: Mat? = null
        val span = ((toMs - fromMs) * 1000).coerceAtLeast(1)
        try {
            while (!cancel.get()) {
                val f = frames.next() ?: break
                if (enc == null) {
                    val s = minOf(1f, maxSide.toFloat() / maxOf(f.rgb.cols(), f.rgb.rows()))
                    val w = ((f.rgb.cols() * s).roundToInt() / 2 * 2).coerceAtLeast(2); val h = ((f.rgb.rows() * s).roundToInt() / 2 * 2).coerceAtLeast(2)
                    enc = AsyncEncoder(FrameEncoder(out, w, h, dec.fps, dec.rotation)); blend = FlowBlender(w, h)
                }
                val rgb = f.rgb
                if (rgb.cols() != enc.width || rgb.rows() != enc.height) Imgproc.resize(rgb, rgb, Size(enc.width.toDouble(), enc.height.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                val gray = FlowBlender.gray(rgb)
                val p = prev; val pg = prevGray
                if (p != null && pg != null) {
                    blend!!.pair(p, pg, rgb, gray)
                    for (k in 1 until factor) { if (cancel.get()) break; enc.encode(blend.at(k.toDouble() / factor)) }
                    p.release(); pg.release()
                }
                enc.encode(rgb)
                prev = rgb; prevGray = gray
                progress(((f.ptsUs - fromMs * 1000).toFloat() / span).coerceIn(0f, 1f))
            }
            if (cancel.get()) error("cancelled")
            val e = enc ?: error("ไม่พบเฟรมในช่วงที่เลือก")
            e.finish()
            return if (dec.rotation % 180 == 90) Result(out, factor, e.frames, e.height, e.width) else Result(out, factor, e.frames, e.width, e.height)
        } catch (t: Throwable) {
            runCatching { enc?.close() }; enc = null; runCatching { out.delete() }
            throw t
        } finally {
            runCatching { enc?.close() }; frames.close(); blend?.close(); prev?.release(); prevGray?.release()
        }
    }
}
