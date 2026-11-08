package th.ogay.luma

import android.content.Context
import android.graphics.*
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** A single output bitmap plus small JPEG tiles. All coordinates use the unrotated JPEG grid. */
@Suppress("DEPRECATION")
class SuperResolution(private val context: Context) {
    private val filter by lazy { context.assets.open("luma-sr1.bin").use { LearnedFilter(it) } }

    private fun readTile(decoder: BitmapRegionDecoder, grid: Dimensions, tile: Rect): Bitmap {
        val sx = decoder.width.toDouble() / grid.w
        val sy = decoder.height.toDouble() / grid.h
        var sample = 1
        while (2*sample <= minOf(sx, sy)) sample *= 2
        fun start(v: Int, scale: Double, bound: Int) = ((floor(v*scale).toInt() / sample - 1)*sample).coerceIn(0,bound-1)
        fun end(v: Int, scale: Double, lower: Int, bound: Int) = ((ceil(v*scale/sample).toInt()+1)*sample).coerceIn(lower+1,bound)
        val left = start(tile.left, sx, decoder.width)
        val top = start(tile.top, sy, decoder.height)
        val region = Rect(left, top, end(tile.right, sx, left, decoder.width), end(tile.bottom, sy, top, decoder.height))
        val decoded = decoder.decodeRegion(region, BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: throw IOException("อ่านส่วนของภาพไม่ได้")
        return try {
            val result = Bitmap.createBitmap(tile.width(), tile.height(), Bitmap.Config.ARGB_8888)
            val shader = BitmapShader(decoded, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val matrix = Matrix().apply {
                setScale((region.width() / sx / decoded.width).toFloat(), (region.height() / sy / decoded.height).toFloat())
                postTranslate((left/sx - tile.left).toFloat(), (top/sy - tile.top).toFloat())
            }
            shader.setLocalMatrix(matrix)
            Canvas(result).drawPaint(Paint(Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader })
            result
        } finally { decoded.recycle() }
    }

    fun process(shot: Shot, file: File, stopped: () -> Boolean) {
        val resolver = context.contentResolver
        val exif = resolver.openInputStream(shot.uri)?.use { ExifInterface(it) }
        val rotation = exif?.rotationDegrees ?: 0
        val taken = exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        val sourcePixels = minOf(shot.size.pixels, shot.cropped?.takeIf { it > 0 } ?: shot.size.pixels, TARGET_PIXELS)
        val grid = scaledSize(shot.size, sourcePixels)
        val output = scaledSize(shot.size, TARGET_PIXELS)
        val image = Bitmap.createBitmap(output.w, output.h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val blendOriginal = shot.size.pixels >= TARGET_PIXELS && sourcePixels < shot.size.pixels

        try {
            resolver.openInputStream(shot.uri)?.use { input ->
                val decoder = BitmapRegionDecoder.newInstance(input, false) ?: throw IOException("ไฟล์ต้นฉบับอ่านไม่ได้")
                try {
                    val sx = output.w.toFloat()/grid.w
                    val sy = output.h.toFloat()/grid.h
                    for (top in 0 until grid.h step 192) {
                        for (left in 0 until grid.w step 192) {
                            if (stopped()) throw CancellationException()
                            val right = minOf(grid.w, left+192)
                            val bottom = minOf(grid.h, top+192)
                            // An extra output gutter keeps interpolation continuous across tiles.
                            val outer = Rect(left-1,top-1,right+1,bottom+1)
                            val padded = Rect(outer).apply { inset(-2,-2) }
                            val src = readTile(decoder, grid, padded)
                            val sw = src.width; val sh = src.height
                            val rgb = IntArray(sw*sh)
                            src.getPixels(rgb,0,sw,0,0,sw,sh)
                            src.recycle()
                            val pixels = filter.expand(rgb,sw,sh)
                            val tile = Bitmap.createBitmap(pixels, outer.width()*2, outer.height()*2, Bitmap.Config.ARGB_8888)
                            val bounds = Rect((left*sx).roundToInt(),(top*sy).roundToInt(),(right*sx).roundToInt(),(bottom*sy).roundToInt())
                            canvas.save()
                            canvas.clipRect(bounds)
                            canvas.drawBitmap(tile, null, RectF(outer.left*sx,outer.top*sy,outer.right*sx,outer.bottom*sy), paint)
                            canvas.restore()
                            tile.recycle()
                            if (blendOriginal && !bounds.isEmpty) {
                                // Preserve detail from the camera ISP when only its enlarged JPEG is accessible.
                                val original = readTile(decoder, output, bounds)
                                paint.alpha = 153
                                canvas.drawBitmap(original, null, bounds, paint)
                                paint.alpha = 255
                                original.recycle()
                            }
                        }
                    }
                } finally { decoder.recycle() }
            } ?: throw IOException("ไม่พบไฟล์ต้นฉบับ")
            if (stopped()) throw CancellationException()
            file.outputStream().buffered().use { check(image.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        } finally { image.recycle() }
        PhotoMedia.tag(file, rotation, taken, ai = true, version = shot.aiVersion)
    }
}
