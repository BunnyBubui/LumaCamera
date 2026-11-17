package th.ogay.luma.autoedit.analysis

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import th.ogay.luma.AudioPeaks
import kotlin.math.abs

/**
 * Reads a real video for the analyzers. Frames are taken at 48x27 pixels, so memory stays small however long the video
 * is; sound uses the app's existing [AudioPeaks] (50 ms steps). Call [frameDiffs] and [loudness] off the main thread.
 * Speech spans are not read here (null): the voice detector belongs to the caption pipeline and is not run for analysis.
 */
class AndroidAnalysisSource(
    private val context: Context, private val uri: Uri, override val assetId: String, override val durationUs: Long,
    override val width: Int, override val height: Int, override val rotationDegrees: Int = 0,
    override val frameStepUs: Long = 250_000, private val cancelled: () -> Boolean = { false }
) : AnalysisSource {
    override val loudnessStepUs: Long = AudioPeaks.STEP_MS * 1000L
    private var diffs: FloatArray? = null; private var diffsDone = false

    override fun frameDiffs(): FloatArray? {
        if (diffsDone) return diffs
        diffsDone = true
        val r = MediaMetadataRetriever()
        diffs = try {
            r.setDataSource(context, uri)
            val n = (durationUs / frameStepUs).toInt().coerceIn(0, 100_000)
            val out = FloatArray(n); var prev: IntArray? = null
            for (i in 0 until n) {
                if (cancelled()) throw AnalysisCancelled()
                val b = r.getScaledFrameAtTime(i * frameStepUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, SW, SH) ?: continue
                val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height); b.recycle()
                val p = prev
                if (p != null && p.size == px.size) { var s = 0L; for (k in px.indices) s += abs(luma(px[k]) - luma(p[k])); out[i] = s / 255f / px.size }
                prev = px
            }
            out
        } catch (e: AnalysisCancelled) { throw e } catch (e: Exception) { null } finally { runCatching { r.release() } }
        return diffs
    }
    override fun loudness(): FloatArray? = runCatching { AudioPeaks.getOrDecode(context, uri).takeIf { it.isNotEmpty() } }.getOrNull()
    override fun speechSpans(): List<LongRange>? = null
    override fun frameAt(us: Long): Frame? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val b: Bitmap = r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180) ?: return null
            val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
            val f = Frame(b.width, b.height, ByteArray(px.size) { luma(px[it]).toByte() }); b.recycle(); f
        } catch (e: Exception) { null } finally { runCatching { r.release() } }
    }
    private fun luma(c: Int) = (((c shr 16) and 255) * 77 + ((c shr 8) and 255) * 150 + (c and 255) * 29) shr 8
    private companion object { const val SW = 48; const val SH = 27 }
}
