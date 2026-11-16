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
     * [width] pixels across the copy of the picture; [blur] how many times smaller it is made and grown back (1 = not blurred);
     * [strength] brightness of the light (0..1); [saturation] colour boost; [spread] how far the photo's light may smear sideways
     * (1 = freely, small = it stays above and below the picture); [everyMs] time between video frames used as light.
     */
    class Spec(val width: Int, val blur: Int, val strength: Float, val saturation: Float, val spread: Float, val everyMs: Long)

    private val SPECS = listOf(
        Spec(0, 1, 0f, 1f, 0f, 0L),
        Spec(16, 3, 0.45f, 1.2f, 1f, 150L),
        Spec(32, 3, 0.65f, 1.3f, 1f, 110L),
        Spec(96, 2, 0.80f, 1.35f, 0.5f, 90L),
        Spec(256, 1, 0.92f, 1.4f, 0.25f, 66L),
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

    /** A small, softened copy of [b] for the light; [b] itself is left untouched. Null when it cannot be made. */
    fun soften(b: Bitmap, spec: Spec): Bitmap? = runCatching {
        if (b.isRecycled || b.width <= 0 || spec.width <= 0) return@runCatching null
        val w = spec.width; val h = heightFor(spec, b.width, b.height)
        val small = Bitmap.createScaledBitmap(b, w, h, true)
        if (spec.blur <= 1) return@runCatching small
        val tiny = Bitmap.createScaledBitmap(small, (w / spec.blur).coerceAtLeast(2), (h / spec.blur).coerceAtLeast(2), true)
        Bitmap.createScaledBitmap(tiny, w, h, true).also { if (it !== small && small !== b) small.recycle(); if (it !== tiny) tiny.recycle() }
    }.getOrNull()
}
