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
                        // Three stages overlap: the next strip is decoded and the finished strip is encoded on their own
                        // threads, and each tile's resampling runs while the GPU works on the next tile.
                        val decodeExec = java.util.concurrent.Executors.newSingleThreadExecutor()
                        val renderExec = java.util.concurrent.Executors.newSingleThreadExecutor()
                        val encodeExec = java.util.concurrent.Executors.newSingleThreadExecutor()
                        val decodeT = java.util.concurrent.atomic.AtomicLong(); val renderT = java.util.concurrent.atomic.AtomicLong(); val encodeT = java.util.concurrent.atomic.AtomicLong()
                        fun timed(acc: java.util.concurrent.atomic.AtomicLong, f: () -> Unit) { val t = SystemClock.elapsedRealtime(); f(); acc.addAndGet(SystemClock.elapsedRealtime()-t) }
                        try {
                            val stripeLen = output.w*minOf(EnhanceSpec.CORE,source.h)*spec.scale
                            val stripes = arrayOf(IntArray(stripeLen), IntArray(stripeLen))
                            val stripeWrites = arrayOfNulls<java.util.concurrent.Future<*>>(2)
                            val buffers = Array(2) { IntArray(EnhanceSpec.INPUT*EnhanceSpec.INPUT) }
                            val renders = arrayOfNulls<java.util.concurrent.Future<*>>(2)
                            fun decodeRow(top: Int) = decodeExec.submit(java.util.concurrent.Callable { var r: Row? = null; timed(decodeT) { r = Row.decode(decoder,top) }; r!! })
                            var nextRow = decodeRow(0)
                            var stripeIndex = 0; var slot = 0
                            for (top in 0 until source.h step EnhanceSpec.CORE) {
                                val h = minOf(EnhanceSpec.CORE,source.h-top)
                                val row = nextRow.get()
                                if (top+EnhanceSpec.CORE < source.h) nextRow = decodeRow(top+EnhanceSpec.CORE)
                                stripeWrites[stripeIndex]?.get()
                                val stripe = stripes[stripeIndex]
                                for (left in 0 until source.w step EnhanceSpec.CORE) {
                                    checkpoint()
                                    val w = minOf(EnhanceSpec.CORE,source.w-left)
                                    renders[slot]?.get()
                                    val pixels = buffers[slot]
                                    timed(decodeT) { row.tile(left,pixels) }
                                    val t = SystemClock.elapsedRealtime()
                                    android.os.Trace.beginSection("Lumora.ai.tile")
                                    val enhanced = try { runtime.run(pixels) } finally { android.os.Trace.endSection() }
                                    inferMs += SystemClock.elapsedRealtime()-t
                                    renders[slot] = renderExec.submit { timed(renderT) { DetailResampler.copyCore(enhanced,pixels,spec.scale,spec.strength,w,h,stripe,output.w,left*spec.scale) } }
                                    slot = 1-slot
                                    count++
                                    val percent = (count*99/(columns*rows)).coerceAtMost(99)
                                    if (percent != lastProgress) { lastProgress = percent; progress(percent) }
                                }
                                renders.forEach { it?.get() }
                                checkpoint()
                                val rowsOut = h*spec.scale
                                stripeWrites[stripeIndex] = encodeExec.submit { timed(encodeT) { jpeg.write(stripe,rowsOut) } }
                                stripeIndex = 1-stripeIndex
                            }
                            stripeWrites.forEach { it?.get() }
                        } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }
                        finally { decodeExec.shutdownNow(); renderExec.shutdownNow(); encodeExec.shutdown(); encodeExec.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS) }
                        decodeMs = decodeT.get(); renderMs = renderT.get(); encodeMs = encodeT.get()
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

    /** A full-width strip of the source covering one row of tiles plus the halo above and below. */
    class Row private constructor(private val data: IntArray, private val width: Int, private val height: Int, private val y0: Int, private val top: Int) {
        /** Same clamping as [readInput]: pixels outside the image repeat the nearest edge pixel. */
        fun tile(left: Int, out: IntArray) {
            val n = EnhanceSpec.INPUT; val x0 = left-EnhanceSpec.HALO
            for (j in 0 until n) {
                val y = (y0+j-top).coerceIn(0,height-1); val base = y*width
                val o = j*n
                for (i in 0 until n) out[o+i] = data[base+(x0+i).coerceIn(0,width-1)]
            }
        }
        companion object {
            fun decode(decoder: BitmapRegionDecoder, top: Int): Row {
                val n = EnhanceSpec.INPUT; val y0 = top-EnhanceSpec.HALO
                val rect = Rect(0,y0.coerceIn(0,decoder.height-1),decoder.width,(y0+n).coerceIn(1,decoder.height))
                val bitmap = decoder.decodeRegion(rect, BitmapFactory.Options().apply { inPreferredConfig=android.graphics.Bitmap.Config.ARGB_8888 })
                    ?: throw IOException("อ่านส่วนภาพไม่ได้")
                return try {
                    val data = IntArray(bitmap.width*bitmap.height)
                    bitmap.getPixels(data,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                    Row(data,bitmap.width,bitmap.height,y0,rect.top)
                } finally { bitmap.recycle() }
            }
        }
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
