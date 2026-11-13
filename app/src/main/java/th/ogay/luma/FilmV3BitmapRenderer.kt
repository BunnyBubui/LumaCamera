package th.ogay.luma

import android.graphics.Bitmap

/**
 * Standalone v3 saved-photo renderer. Callers must opt in explicitly.
 * No legacy camera/render paths are changed by adding this class.
 *
 * Processes in 16-row batches to limit temporary memory. The same FilmLut3D
 * transform can be reused for thumbnails and a future GPU implementation.
 */
internal object FilmV3BitmapRenderer {
    fun render(source: Bitmap, lookId: String, strength: Float = 1f): Bitmap {
        require(source.width > 0 && source.height > 0)
        require(strength.isFinite())
        val look = requireNotNull(FilmCollectionV3.byId(lookId)) { "Unknown v3 look: $lookId" }
        val lut = FilmCollectionV3.bake(look, 17)
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
            ?: error("Unable to allocate output bitmap")
        if (strength <= 0f) return result
        val width = result.width
        val batchHeight = minOf(16, result.height)
        val pixels = IntArray(width * batchHeight)
        var y = 0
        while (y < result.height) {
            val rows = minOf(batchHeight, result.height - y)
            result.getPixels(pixels, 0, width, 0, y, width, rows)
            for (i in 0 until width * rows) pixels[i] = lut.grade(pixels[i], strength)
            result.setPixels(pixels, 0, width, 0, y, width, rows)
            y += rows
        }
        return result
    }
}
