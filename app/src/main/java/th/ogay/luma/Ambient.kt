package th.ogay.luma

import android.content.Context
import android.graphics.Bitmap

/**
 * The light that spills past a picture or a video onto the screen around it. Five levels, set apart for photos and for videos
 * in the gallery settings: off, low, medium, high and highest. Each step keeps more detail (less blur, a bigger copy of the
 * picture), hugs the picture's own edge more closely and glows a little stronger, at the price of more work for the phone.
 */
object Ambient {
    const val KEY_PHOTO = "reflect_photo"
    const val KEY_VIDEO = "reflect_video"
    val LABELS = listOf("ปิด", "ต่ำ", "กลาง", "สูง", "สูงสุด")

    /**
     * [width] pixels across the copy of the picture (more = finer colour); [blur] how soft it is, as a share of that width
     * (the light is always blurred; a higher level blurs less in step with its finer copy, so colours stay closer to the edge);
     * [strength] brightness of the light (0..1); [saturation] colour boost; [spread] how far the photo's light may smear sideways
     * (1 = freely, small = it stays above and below the picture); [everyMs] time between video frames used as light.
     */
    class Spec(val width: Int, val blur: Float, val strength: Float, val saturation: Float, val spread: Float, val everyMs: Long)

    private val SPECS = listOf(
        Spec(0, 0f, 0f, 1f, 0f, 0L),
        Spec(16, 0.10f, 0.45f, 1.2f, 1f, 150L),
        Spec(32, 0.08f, 0.65f, 1.3f, 1f, 110L),
        Spec(96, 0.06f, 0.80f, 1.35f, 0.5f, 90L),
        Spec(256, 0.045f, 0.92f, 1.4f, 0.25f, 66L),
    )
    fun spec(level: Int): Spec = SPECS[level.coerceIn(0, SPECS.lastIndex)]

    /** The stored level; older settings had only an on/off switch, which becomes off or medium. */
    fun level(c: Context, key: String): Int {
        val p = c.getSharedPreferences("camera", 0)
        val legacy = if (p.getBoolean("gallery_reflect", true)) 2 else 0
        return p.getInt(key, legacy).coerceIn(0, SPECS.lastIndex)
    }
    fun photo(c: Context) = level(c, KEY_PHOTO)
    fun video(c: Context) = level(c, KEY_VIDEO)

    /** Height of the small copy for a picture of [srcW]×[srcH]. */
    fun heightFor(spec: Spec, srcW: Int, srcH: Int): Int = if (srcW <= 0) 4 else (spec.width.toFloat() * srcH / srcW).toInt().coerceIn(4, spec.width * 4)

    /** A small, softly blurred copy of [b] for the light; [b] itself is left untouched. Null when it cannot be made. */
    fun soften(b: Bitmap, spec: Spec): Bitmap? = runCatching {
        if (b.isRecycled || b.width <= 0 || spec.width <= 0) return@runCatching null
        val w = spec.width; val h = heightFor(spec, b.width, b.height)
        val small = Bitmap.createScaledBitmap(b, w, h, true)
        val px = IntArray(w * h); small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== b) small.recycle()
        boxBlur(px, w, h, Math.round(w * spec.blur).coerceAtLeast(1))
        Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }.getOrNull()

    /** Three passes of a box blur in each direction, which comes close to a Gaussian blur; edges repeat their last pixel. */
    fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
        if (r < 1 || w < 2 || h < 2) return
        val buf = IntArray(maxOf(w, h))
        repeat(3) {
            for (y in 0 until h) line(px, y * w, 1, w, r, buf)
            for (x in 0 until w) line(px, x, w, h, r, buf)
        }
    }
    private fun line(px: IntArray, start: Int, step: Int, n: Int, r: Int, buf: IntArray) {
        var sr = 0; var sg = 0; var sb = 0
        val d = 2 * r + 1
        for (i in -r..r) { val c = px[start + step * i.coerceIn(0, n - 1)]; sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255 }
        for (i in 0 until n) {
            buf[i] = (0xff shl 24) or ((sr / d) shl 16) or ((sg / d) shl 8) or (sb / d)
            val add = px[start + step * (i + r + 1).coerceIn(0, n - 1)]; val rem = px[start + step * (i - r).coerceIn(0, n - 1)]
            sr += ((add shr 16) and 255) - ((rem shr 16) and 255); sg += ((add shr 8) and 255) - ((rem shr 8) and 255); sb += (add and 255) - (rem and 255)
        }
        for (i in 0 until n) px[start + step * i] = buf[i]
    }
}
