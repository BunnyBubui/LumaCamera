package th.ogay.luma.ai

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.SystemClock
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import th.ogay.luma.PhotoMedia
import th.ogay.luma.Shot
import th.ogay.luma.Dimensions
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

/** No whole output bitmap. A 12MP x4 export retains at most 512 output scanlines. */
@Suppress("DEPRECATION")
class DetailSuperResolution(private val context: Context) {
    data class Report(val size: Dimensions, val json: String)
    fun process(shot: Shot, file: File, spec: EnhanceSpec, stopped: () -> Boolean,
                progress: (Int) -> Unit = {}): Report = DetailComputeGate.run(stopped) {
        processInternal(shot,file,spec,stopped,progress)
    }
    private fun processInternal(shot: Shot, file: File, spec: EnhanceSpec, stopped: () -> Boolean,
                                progress: (Int) -> Unit): Report {
        val start = SystemClock.elapsedRealtime()
        val exif = context.contentResolver.openInputStream(shot.uri)?.use { ExifInterface(it) }
        var decodeMs = 0L; var inferMs = 0L; var renderMs = 0L; var encodeMs = 0L
        var count = 0; var lastProgress = -1
        fun checkpoint() { if (stopped()) throw CancellationException() }
        context.contentResolver.openInputStream(shot.uri)?.use { input ->
            val decoder = BitmapRegionDecoder.newInstance(input, false) ?: throw IOException("อ่านภาพต้นฉบับไม่ได้")
            try {
                val source = Dimensions(decoder.width, decoder.height)
                val output = spec.output(source)
                // Budget for a large, noisy, high-quality JPEG and its MediaStore copy.
                if (context.cacheDir.usableSpace < maxOf(64L*1024*1024, output.pixels*6))
                    throw IOException("พื้นที่ว่างไม่พอสำหรับภาพ ×${spec.scale} กรุณาเลือกสเกลเล็กลง")
                val columns = (source.w+EnhanceSpec.CORE-1)/EnhanceSpec.CORE
                val rows = (source.h+EnhanceSpec.CORE-1)/EnhanceSpec.CORE
                checkpoint()
                val initStart = SystemClock.elapsedRealtime()
                DetailRuntime(context).use { runtime ->
                    val modelInitMs = SystemClock.elapsedRealtime()-initStart
                    StripJpeg(file, output.w, output.h).use { jpeg ->
                        val stripe = IntArray(output.w*minOf(EnhanceSpec.CORE,source.h)*spec.scale)
                        for (top in 0 until source.h step EnhanceSpec.CORE) {
                            val h = minOf(EnhanceSpec.CORE,source.h-top)
                            for (left in 0 until source.w step EnhanceSpec.CORE) {
                                checkpoint()
                                val w = minOf(EnhanceSpec.CORE,source.w-left)
                                var t = SystemClock.elapsedRealtime()
                                val pixels = readInput(decoder,left,top)
                                decodeMs += SystemClock.elapsedRealtime()-t
                                t = SystemClock.elapsedRealtime()
                                val enhanced = runtime.run(pixels)
                                inferMs += SystemClock.elapsedRealtime()-t
                                checkpoint()
                                t = SystemClock.elapsedRealtime()
                                DetailResampler.copyCore(enhanced,pixels,spec.scale,spec.strength,w,h,stripe,output.w,left*spec.scale)
                                renderMs += SystemClock.elapsedRealtime()-t
                                count++
                                val percent = (count*99/(columns*rows)).coerceAtMost(99)
                                if (percent != lastProgress) { lastProgress = percent; progress(percent) }
                            }
                            checkpoint()
                            val encodeStart=SystemClock.elapsedRealtime()
                            jpeg.write(stripe,h*spec.scale)
                            encodeMs+=SystemClock.elapsedRealtime()-encodeStart
                        }
                        checkpoint()
                        val encodeStart=SystemClock.elapsedRealtime()
                        jpeg.finish()
                        encodeMs+=SystemClock.elapsedRealtime()-encodeStart
                    }
                    PhotoMedia.tag(file,exif?.rotationDegrees ?: 0,
                        exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),ai=true,version=shot.aiVersion)
                    progress(100)
                    val report = JSONObject().put("model",EnhanceSpec.MODEL).put("scale",spec.scale)
                        .put("nativeScale",4).put("strength",spec.strength).put("backend",runtime.backend)
                        .put("fallback",runtime.fallbackReason ?: JSONObject.NULL)
                        .put("inputWidth",source.w).put("inputHeight",source.h)
                        .put("outputWidth",output.w).put("outputHeight",output.h)
                        .put("tiles",count).put("tileCore",EnhanceSpec.CORE).put("halo",EnhanceSpec.HALO)
                        .put("decodeMs",decodeMs).put("inferenceMs",inferMs).put("renderMs",renderMs)
                        .put("modelInitMs",modelInitMs).put("encodeMs",encodeMs)
                        .put("totalMs",SystemClock.elapsedRealtime()-start)
                    return Report(output,report.toString())
                }
            } finally { decoder.recycle() }
        } ?: throw IOException("ไม่พบไฟล์ต้นฉบับ")
    }

    companion object {
        /** Clamp the outer image border; all interior tiles retain the complete receptive field. */
        fun readInput(decoder: BitmapRegionDecoder, left: Int, top: Int): IntArray {
            val n = EnhanceSpec.INPUT; val halo = EnhanceSpec.HALO
            val x0 = left-halo; val y0 = top-halo
            val rect = Rect(x0.coerceIn(0,decoder.width-1),y0.coerceIn(0,decoder.height-1),
                (x0+n).coerceIn(1,decoder.width),(y0+n).coerceIn(1,decoder.height))
            val bitmap = decoder.decodeRegion(rect, BitmapFactory.Options().apply { inPreferredConfig=android.graphics.Bitmap.Config.ARGB_8888 })
                ?: throw IOException("อ่านส่วนภาพไม่ได้")
            return try {
                val decoded = IntArray(bitmap.width*bitmap.height)
                bitmap.getPixels(decoded,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                IntArray(n*n) { i ->
                    val x = (x0+i%n-rect.left).coerceIn(0,bitmap.width-1)
                    val y = (y0+i/n-rect.top).coerceIn(0,bitmap.height-1)
                    decoded[y*bitmap.width+x]
                }
            } finally { bitmap.recycle() }
        }
    }
}
