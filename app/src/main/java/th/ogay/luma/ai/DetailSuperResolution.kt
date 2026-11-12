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
                // Scan once per image; restore text conservatively without redrawing glyphs.
                // Detection is optional: OCR failures fall back to the original Natural guard.
                AiProgress.begin(shot.id, if (spec.profile == EnhanceProfile.NATURAL) "กำลังหาคนและตัวหนังสือในภาพ" else "กำลังเตรียมภาพ"); AiProgress.model = if (spec.profile == EnhanceProfile.NATURAL) "Natural SR3" else "Classic SR2"
                val textScan = if (spec.profile == EnhanceProfile.NATURAL)
                    DetailTextGuard.scan(context, shot.uri, source, stopped) else null
                val personScan = if (spec.profile == EnhanceProfile.NATURAL)
                    DetailPersonGuard.scan(context, shot.uri, source, stopped) else null
                // Budget for a large, noisy, high-quality JPEG and its MediaStore copy.
                if (context.cacheDir.usableSpace < maxOf(64L*1024*1024, output.pixels*6))
                    throw IOException("พื้นที่ว่างไม่พอสำหรับภาพ ×${spec.scale} กรุณาเลือกสเกลเล็กลง")
                val columns = (source.w+EnhanceSpec.CORE-1)/EnhanceSpec.CORE
                val rows = (source.h+EnhanceSpec.CORE-1)/EnhanceSpec.CORE
                checkpoint()
                AiProgress.tiles(columns*rows)
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
                        val reuse = Row.Reuse()
                        fun timed(acc: java.util.concurrent.atomic.AtomicLong, f: () -> Unit) { val t = SystemClock.elapsedRealtime(); f(); acc.addAndGet(SystemClock.elapsedRealtime()-t) }
                        try {
                            val stripeLen = output.w*minOf(EnhanceSpec.CORE,source.h)*spec.scale
                            val stripes = arrayOf(IntArray(stripeLen), IntArray(stripeLen))
                            val stripeWrites = arrayOfNulls<java.util.concurrent.Future<*>>(2)
                            val buffers = Array(2) { IntArray(EnhanceSpec.INPUT*EnhanceSpec.INPUT) }
                            val renders = arrayOfNulls<java.util.concurrent.Future<*>>(2)
                            // Two strip buffers take turns (one being read while the next is decoded) and one bitmap is
                            // reused for every decode: no multi-megabyte allocation per row, so no garbage collection
                            // pause about once a second that made the whole phone hitch.
                            val rowData = arrayOfNulls<IntArray>(2); var rowTurn = 0
                            fun decodeRow(top: Int): java.util.concurrent.Future<Row> { val turn = rowTurn; rowTurn = 1-rowTurn
                                return decodeExec.submit(java.util.concurrent.Callable { AiPace.helperPriority(context); var r: Row? = null; timed(decodeT) { r = Row.decode(decoder,top,rowData,turn,reuse) }; r!! }) }
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
                                    AiPace.afterTile(context, SystemClock.elapsedRealtime()-t)
                                    val textMask = textScan?.mask?.forCore(left,top,w,h)
                                    val personMask = personScan?.mask?.forCore(left,top,w,h)
                                    renders[slot] = renderExec.submit { AiPace.helperPriority(context); timed(renderT) { DetailResampler.copyCore(enhanced,pixels,spec.scale,spec.strength,w,h,stripe,output.w,left*spec.scale,spec.profile,textMask,personMask) } }
                                    slot = 1-slot
                                    count++
                                    AiProgress.tile(count)
                                    val percent = (count*99/(columns*rows)).coerceAtMost(99)
                                    if (percent != lastProgress) { lastProgress = percent; progress(percent) }
                                }
                                renders.forEach { it?.get() }
                                checkpoint()
                                val rowsOut = h*spec.scale
                                stripeWrites[stripeIndex] = encodeExec.submit { AiPace.helperPriority(context); timed(encodeT) { jpeg.write(stripe,rowsOut) } }
                                stripeIndex = 1-stripeIndex
                            }
                            stripeWrites.forEach { it?.get() }
                        } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }
                        finally { decodeExec.shutdownNow(); renderExec.shutdownNow(); encodeExec.shutdown(); encodeExec.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS); reuse.bitmap?.recycle() }
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
                        .put("nativeScale",4).put("strength",spec.strength)
                        .put("profile",spec.profile.name).put("outputModel",spec.modelId)
                        .put("textGuardStatus",textScan?.status ?: "not-requested")
                        .put("textGuardRegions",textScan?.boxCount ?: 0)
                        .put("textGuardScanMs",textScan?.elapsedMs ?: 0)
                        .put("personGuardStatus",personScan?.status ?: "not-requested")
                        .put("personGuardScanMs",personScan?.elapsedMs ?: 0)
                        .put("backend",runtime.backend)
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
        /** The decode bitmap kept between rows. */
        class Reuse { var bitmap: android.graphics.Bitmap? = null }
        companion object {
            fun decode(decoder: BitmapRegionDecoder, top: Int, buffers: Array<IntArray?>? = null, turn: Int = 0, reuse: Reuse? = null): Row {
                val n = EnhanceSpec.INPUT; val y0 = top-EnhanceSpec.HALO
                val rect = Rect(0,y0.coerceIn(0,decoder.height-1),decoder.width,(y0+n).coerceIn(1,decoder.height))
                val w = rect.width(); val h = rect.height()
                val old = reuse?.bitmap?.takeIf { !it.isRecycled && it.isMutable && it.width == w && it.height >= h }
                val options = BitmapFactory.Options().apply { inPreferredConfig=android.graphics.Bitmap.Config.ARGB_8888; inMutable = true; if (old != null) inBitmap = old }
                val bitmap = (try { decoder.decodeRegion(rect, options) } catch (_: IllegalArgumentException) {
                    decoder.decodeRegion(rect, BitmapFactory.Options().apply { inPreferredConfig=android.graphics.Bitmap.Config.ARGB_8888; inMutable = true }) })
                    ?: throw IOException("อ่านส่วนภาพไม่ได้")
                val size = w*h
                val data = buffers?.get(turn)?.takeIf { it.size >= size } ?: IntArray(n*w.coerceAtLeast(1)).let { if (it.size >= size) it else IntArray(size) }.also { buffers?.set(turn, it) }
                bitmap.getPixels(data,0,w,0,0,w,minOf(h,bitmap.height))
                if (reuse != null) { if (reuse.bitmap !== bitmap) { reuse.bitmap?.recycle(); reuse.bitmap = bitmap } } else bitmap.recycle()
                return Row(data,w,h,y0,rect.top)
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
