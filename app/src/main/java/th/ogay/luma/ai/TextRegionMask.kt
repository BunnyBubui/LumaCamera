package th.ogay.luma.ai

/**
 * Conservative spatial protection for OCR-detected text lines. Coordinates are in the
 * UNROTATED source JPEG grid (the same grid used by BitmapRegionDecoder).
 *
 * A mask marks only source pixels under a detected box; no text or characters are
 * ever written into the output. The SR renderer uses it to attenuate neural detail.
 */
internal data class TextGuardRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init { require(right > left && bottom > top) }
}

internal class TextRegionMask(val regions: List<TextGuardRect>) {
    val count: Int get() = regions.size

    /**
     * Returns a compact source-resolution mask for a tile, or null if there is no overlap.
     * The renderer samples this mask by (outputX / scale, outputY / scale).
     */
    fun forCore(left: Int, top: Int, width: Int, height: Int): ByteArray? {
        require(left >= 0 && top >= 0 && width > 0 && height > 0)
        var data: ByteArray? = null
        val right = left + width
        val bottom = top + height
        for (box in regions) {
            val x0 = maxOf(left, box.left)
            val y0 = maxOf(top, box.top)
            val x1 = minOf(right, box.right)
            val y1 = minOf(bottom, box.bottom)
            if (x0 >= x1 || y0 >= y1) continue
            if (data == null) data = ByteArray(width * height)
            val mask = data
            for (y in y0 until y1) {
                java.util.Arrays.fill(mask, (y - top) * width + x0 - left,
                    (y - top) * width + x1 - left, 1.toByte())
            }
        }
        return data
    }
}
