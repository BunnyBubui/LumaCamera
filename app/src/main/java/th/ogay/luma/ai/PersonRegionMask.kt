package th.ogay.luma.ai

/**
 * Per-person protection mask from a low-resolution, on-device segmentation scan.
 * Coordinates always refer to the unrotated JPEG grid, matching SR tiles.
 *
 * Segmentation is not face recognition. It may include clothes/hair and miss
 * people at a distance, so masks are intentionally conservative.
 */
internal class PersonRegionMask(
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    private val maskWidth: Int,
    private val maskHeight: Int,
    private val confidence: FloatArray
) {
    init {
        require(sourceWidth > 0 && sourceHeight > 0)
        require(maskWidth > 0 && maskHeight > 0)
        require(confidence.size == maskWidth * maskHeight)
    }

    /** Returns a small byte mask only when a person's presence overlaps the tile. */
    fun forCore(left: Int, top: Int, width: Int, height: Int): ByteArray? {
        require(left >= 0 && top >= 0 && width > 0 && height > 0)
        require(left.toLong() + width <= sourceWidth && top.toLong() + height <= sourceHeight)
        var selected: ByteArray? = null
        for (y in 0 until height) {
            val py = (((top.toLong() + y) * maskHeight) / sourceHeight).toInt()
                .coerceIn(0, maskHeight - 1)
            for (x in 0 until width) {
                val px = (((left.toLong() + x) * maskWidth) / sourceWidth).toInt()
                    .coerceIn(0, maskWidth - 1)
                if (confidence[py * maskWidth + px] >= 0.65f) {
                    if (selected == null) selected = ByteArray(width * height)
                    selected[y * width + x] = 1
                }
            }
        }
        return selected
    }
}
