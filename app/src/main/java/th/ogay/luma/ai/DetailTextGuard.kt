package th.ogay.luma.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import th.ogay.luma.Dimensions
import th.ogay.luma.ocr.ThaiOcrCancelledException
import th.ogay.luma.ocr.ThaiOcrEngine
import java.util.concurrent.CancellationException
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Conservative optional OCR detector. A failure NEVER fails an otherwise valid
 * SR export: the existing contrast-based Natural guard still runs.
 *
 * No recognition strings or pixel data are saved. Detection happens on a
 * downsampled, unrotated view of the original JPEG; coordinates are mapped to
 * the source JPEG grid, not EXIF-rotated preview coordinates.
 */
internal object DetailTextGuard {
    data class Scan(val mask: TextRegionMask, val status: String, val elapsedMs: Long) {
        val boxCount get() = mask.count
    }

    fun scan(context: Context, uri: Uri, source: Dimensions, cancelled: () -> Boolean): Scan {
        val started = SystemClock.elapsedRealtime()
        fun done(regions: List<TextGuardRect>, status: String) =
            Scan(TextRegionMask(regions), status, SystemClock.elapsedRealtime() - started)
        if (cancelled()) throw CancellationException()
        if (source.w < 1 || source.h < 1) return done(emptyList(), "invalid-source")
        var sample = 1
        while (maxOf(source.w, source.h) / sample > 1280) sample *= 2
        val decoded = try {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                })
            }
        } catch (e: Exception) {
            if (cancelled()) throw CancellationException()
            return done(emptyList(), "decode-unavailable:${e.javaClass.simpleName}")
        } ?: return done(emptyList(), "decode-unavailable")
        try {
            if (cancelled()) throw CancellationException()
            val engine = ThaiOcrEngine()
            val boxes = try {
                engine.detectTextBoxes(context, decoded, 8000L, cancelled)
            } catch (_: ThaiOcrCancelledException) {
                throw CancellationException()
            } catch (e: Exception) {
                if (cancelled()) throw CancellationException()
                return done(emptyList(), "detector-unavailable:${e.javaClass.simpleName}")
            } catch (e: LinkageError) {
                return done(emptyList(), "detector-linkage-error")
            } catch (_: OutOfMemoryError) {
                // OCR must not prevent a valid photo from using the Natural fallback.
                return done(emptyList(), "detector-memory-unavailable")
            } finally { engine.close() }
            if (cancelled()) throw CancellationException()
            val sx = source.w.toFloat() / decoded.width
            val sy = source.h.toFloat() / decoded.height
            val pad = maxOf(2, (3f * maxOf(sx,sy)).roundToInt())
            val regions = boxes.take(200).mapNotNull { rect ->
                val x0 = (rect.left * sx).toInt().coerceIn(0, source.w - 1)
                val y0 = (rect.top * sy).toInt().coerceIn(0, source.h - 1)
                val x1 = ceil(rect.right * sx).toInt().coerceIn(x0 + 1, source.w)
                val y1 = ceil(rect.bottom * sy).toInt().coerceIn(y0 + 1, source.h)
                val left = (x0-pad).coerceAtLeast(0)
                val top = (y0-pad).coerceAtLeast(0)
                val right = (x1+pad).coerceAtMost(source.w)
                val bottom = (y1+pad).coerceAtMost(source.h)
                if (right-left < 2 || bottom-top < 2) null
                else TextGuardRect(left,top,right,bottom)
            }
            return done(regions, if (regions.isEmpty()) "no-text-detected" else "active")
        } finally { decoded.recycle() }
    }
}
