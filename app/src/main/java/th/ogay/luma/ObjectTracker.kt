package th.ogay.luma

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * Small template tracker for tap-and-hold subject tracking in video.
 * It looks at tiny greyscale copies of the live view, remembers the patch under the finger and searches for it
 * around its last position a few times a second. It follows one object that keeps its look; it is not a subject
 * recogniser, so fast turns, crossings or big light changes can lose it (then [step] returns false).
 */
class ObjectTracker(private val grab: () -> Bitmap?) {
    var x = 0.5f; private set
    var y = 0.5f; private set
    var active = false; private set
    private val w = 96; private var h = 0
    private val half = 8; private val reach = 12
    private var patch: IntArray? = null
    private var px = 0; private var py = 0; private var misses = 0

    private fun grey(): IntArray? {
        val full = grab() ?: return null
        val hh = (w * full.height.toFloat() / full.width).toInt().coerceAtLeast(2 * half + 3)
        val small = Bitmap.createScaledBitmap(full, w, hh, true); if (small !== full) full.recycle()
        val pixels = IntArray(w * hh); small.getPixels(pixels, 0, w, 0, 0, w, hh); small.recycle(); h = hh
        for (i in pixels.indices) { val c = pixels[i]; pixels[i] = (((c shr 16) and 255) * 77 + ((c shr 8) and 255) * 151 + (c and 255) * 28) shr 8 }
        return pixels
    }
    private fun cut(frame: IntArray, cx: Int, cy: Int): IntArray { val n = 2 * half + 1; return IntArray(n * n) { i -> frame[(cy - half + i / n) * w + cx - half + i % n] } }

    /** Starts following whatever is under the normalised view point. */
    fun start(nx: Float, ny: Float): Boolean {
        val frame = grey() ?: return false
        px = (nx * w).toInt().coerceIn(half, w - half - 1); py = (ny * h).toInt().coerceIn(half, h - half - 1)
        patch = cut(frame, px, py); x = px / w.toFloat(); y = py / h.toFloat(); misses = 0; active = true
        return true
    }
    fun stop() { active = false; patch = null }

    /** One search step; false when the object has been lost for a while (tracking then stops itself). */
    fun step(): Boolean {
        val tpl = patch ?: return false
        val frame = grey() ?: return true
        val n = 2 * half + 1
        var best = Int.MAX_VALUE; var bx = px; var by = py
        for (cy in (py - reach).coerceAtLeast(half)..(py + reach).coerceAtMost(h - half - 1)) for (cx in (px - reach).coerceAtLeast(half)..(px + reach).coerceAtMost(w - half - 1)) {
            var sum = 0; var i = 0
            loop@ for (ty in 0 until n) { val row = (cy - half + ty) * w + cx - half; for (tx in 0 until n) { sum += abs(frame[row + tx] - tpl[i++]); if (sum >= best) break@loop } }
            // A small pull towards the last position keeps the box still on flat surfaces.
            sum += (abs(cx - px) + abs(cy - py)) * 6
            if (sum < best) { best = sum; bx = cx; by = cy }
        }
        val mean = best / (n * n)
        if (mean > 34) { if (++misses > 7) { stop(); return false }; return true }
        misses = 0; px = bx; py = by; x = px / w.toFloat(); y = py / h.toFloat()
        // Let the remembered patch adapt slowly so gradual turns and light changes do not lose the object.
        val now = cut(frame, px, py); for (i in tpl.indices) tpl[i] = (tpl[i] * 7 + now[i]) / 8
        return true
    }
}
