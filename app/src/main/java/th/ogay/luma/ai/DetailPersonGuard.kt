package th.ogay.luma.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import th.ogay.luma.Dimensions
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Offline optional person-region scan: reuses the ML Kit selfie segmenter
 * already bundled for Lumora Portrait. Does not reconstruct or identify faces.
 *
 * An unavailable model, timeout or memory pressure only disables person
 * protection for this export. It never replaces pixels in the source photo.
 */
internal object DetailPersonGuard {
    data class Scan(val mask: PersonRegionMask?, val status: String, val elapsedMs: Long)
    fun scan(context: Context, uri: Uri, source: Dimensions, stopped: () -> Boolean): Scan {
        val start = SystemClock.elapsedRealtime()
        fun result(mask: PersonRegionMask?, status: String) =
            Scan(mask, status, SystemClock.elapsedRealtime() - start)
        if (stopped()) throw CancellationException()
        if (source.w <= 0 || source.h <= 0) return result(null, "invalid-image")
        var sample = 1
        while (maxOf(source.w,source.h)/sample > 1024) sample *= 2
        val bitmap = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input,null,BitmapFactory.Options().apply {
                    inSampleSize=sample
                    inPreferredConfig=Bitmap.Config.ARGB_8888
                })
            }
        } catch (e: Exception) {
            if (stopped()) throw CancellationException()
            return result(null, "decode-unavailable:${e.javaClass.simpleName}")
        } catch (_: OutOfMemoryError) {
            return result(null, "decode-memory-unavailable")
        } ?: return result(null, "decode-unavailable")
        try {
            if (stopped()) throw CancellationException()
            val segmenter = try {
                Segmentation.getClient(
                    SelfieSegmenterOptions.Builder()
                        .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                        .build())
            } catch (e: Exception) {
                return result(null, "segmenter-unavailable:${e.javaClass.simpleName}")
            } catch (_: LinkageError) {
                return result(null, "segmenter-linkage-error")
            }
            try {
                val mask = try {
                    Tasks.await(segmenter.process(InputImage.fromBitmap(bitmap,0)),
                        12,TimeUnit.SECONDS)
                } catch (e: Exception) {
                    if (stopped()) throw CancellationException()
                    return result(null, "segmenter-failed:${e.javaClass.simpleName}")
                } catch (_: OutOfMemoryError) {
                    return result(null, "segmenter-memory-unavailable")
                } catch (_: LinkageError) {
                    return result(null, "segmenter-linkage-error")
                }
                if (stopped()) throw CancellationException()
                val count=mask.width.toLong()*mask.height
                if (count<=0 || count>5_000_000) return result(null, "invalid-mask")
                val byteBuffer = mask.buffer
                byteBuffer.rewind()
                val buffer = byteBuffer.asFloatBuffer()
                if (buffer.remaining()<count) return result(null, "invalid-mask-buffer")
                val weights=try { FloatArray(count.toInt()) }
                    catch (_: OutOfMemoryError) { return result(null, "mask-memory-unavailable") }
                buffer.get(weights)
                return result(
                    PersonRegionMask(source.w,source.h,mask.width,mask.height,weights),
                    if (weights.any { it>=0.65f }) "active" else "no-person-detected")
            } finally {
                segmenter.close()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
