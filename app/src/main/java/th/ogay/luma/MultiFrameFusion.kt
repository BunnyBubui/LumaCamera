package th.ogay.luma

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.math.roundToInt

data class FusionResult(val file: File, val size: Dimensions, val used: Int)

@Suppress("DEPRECATION")
class MultiFrameFusion(private val context: Context) {
    private data class Input(val file: File, val dx: Int, val dy: Int)

    private fun thumbnail(file: File, target: Dimensions): IntArray {
        val size = PhotoMedia.dimensions(file)
        var sample = 1
        while (maxOf(size.w, size.h) / (sample * 2) >= 320) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("อ่านเฟรมไม่ได้")
        val scaled = Bitmap.createScaledBitmap(decoded, target.w, target.h, true)
        if (scaled !== decoded) decoded.recycle()
        return try {
            val pixels = IntArray(target.w * target.h)
            scaled.getPixels(pixels, 0, target.w, 0, 0, target.w, target.h)
            IntArray(pixels.size) { FrameFusionEngine.luma(pixels[it]) }
        } finally {
            scaled.recycle()
        }
    }

    private fun tile(file: File, area: Rect, source: Dimensions, output: Dimensions): Bitmap {
        val decoder = BitmapRegionDecoder.newInstance(file.path, false) ?: throw IOException("อ่านเฟรมไม่ได้")
        return try {
            val left = (area.left.toDouble() * source.w / output.w).toInt().coerceIn(0, source.w - 1)
            val top = (area.top.toDouble() * source.h / output.h).toInt().coerceIn(0, source.h - 1)
            val right = (area.right.toDouble() * source.w / output.w).roundToInt().coerceIn(left + 1, source.w)
            val bottom = (area.bottom.toDouble() * source.h / output.h).roundToInt().coerceIn(top + 1, source.h)
            val decoded = decoder.decodeRegion(Rect(left, top, right, bottom), null) ?: throw IOException("อ่านส่วนเฟรมไม่ได้")
            val scaled = Bitmap.createScaledBitmap(decoded, area.width(), area.height(), true)
            if (scaled !== decoded) decoded.recycle()
            scaled
        } finally {
            decoder.recycle()
        }
    }

    fun process(shot: Shot, stopped: () -> Boolean): FusionResult {
        val folder = File(context.filesDir, "bursts/${shot.id}")
        val frames = folder.listFiles { file -> file.name.matches(Regex("[0-9]{4}\\.jpg")) }
            ?.sortedBy { it.name }.orEmpty()
        if (frames.isEmpty()) throw IOException("ไม่พบเฟรมต้นทางสำหรับรวมภาพ")
        val source = PhotoMedia.dimensions(frames.first())
        val output = if (source.pixels > TARGET_PIXELS) scaledSize(source, TARGET_PIXELS) else source
        val small = scaledSize(source, 256L * 192)
        val reference = thumbnail(frames.first(), small)
        val inputs = mutableListOf(Input(frames.first(), 0, 0))
        frames.drop(1).forEach { file ->
            if (stopped()) throw CancellationException()
            val dims = PhotoMedia.dimensions(file)
            if (dims == source) {
                val alignment = FrameFusionEngine.align(reference, thumbnail(file, small), small.w, small.h)
                if (alignment.error <= 22) {
                    inputs += Input(file, (alignment.dx * output.w.toFloat() / small.w).roundToInt(),
                        (alignment.dy * output.h.toFloat() / small.h).roundToInt())
                }
            }
        }
        val result = Bitmap.createBitmap(output.w, output.h, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(result)
            for (top in 0 until output.h step 384) {
                for (left in 0 until output.w step 384) {
                    if (stopped()) throw CancellationException()
                    val area = Rect(left, top, minOf(left + 384, output.w), minOf(top + 384, output.h))
                    val base = tile(inputs.first().file, area, source, output)
                    val pixels = IntArray(base.width * base.height)
                    base.getPixels(pixels, 0, base.width, 0, 0, base.width, base.height)
                    base.recycle()
                    val accumulator = FrameFusionEngine.Accumulator(pixels)
                    for (input in inputs.drop(1)) {
                        val shifted = Rect(area).apply { offset(input.dx, input.dy) }
                        val inside = Rect(shifted)
                        if (!inside.intersect(0, 0, output.w, output.h)) continue
                        val bitmap = tile(input.file, inside, source, output)
                        val candidate = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(candidate, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        val aligned = IntArray(pixels.size)
                        val valid = BooleanArray(pixels.size)
                        val xOffset = inside.left - shifted.left
                        val yOffset = inside.top - shifted.top
                        for (y in 0 until bitmap.height) {
                            val to = (y + yOffset) * area.width() + xOffset
                            candidate.copyInto(aligned, to, y * bitmap.width, (y + 1) * bitmap.width)
                            java.util.Arrays.fill(valid, to, to + bitmap.width, true)
                        }
                        bitmap.recycle()
                        accumulator.add(aligned, valid)
                    }
                    val merged = Bitmap.createBitmap(accumulator.finish(), area.width(), area.height(), Bitmap.Config.ARGB_8888)
                    canvas.drawBitmap(merged, left.toFloat(), top.toFloat(), Paint())
                    merged.recycle()
                }
            }
            val destination = File(folder, "fused.jpg")
            val partial = File(folder, "fused.part")
            partial.outputStream().use { check(result.compress(Bitmap.CompressFormat.JPEG, 96, it)) }
            check(partial.renameTo(destination)) { "บันทึกภาพรวมไม่สำเร็จ" }
            val exif = ExifInterface(frames.first())
            PhotoMedia.tag(destination, exif.rotationDegrees, exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
            return FusionResult(destination, output, inputs.size)
        } finally {
            result.recycle()
        }
    }
}
