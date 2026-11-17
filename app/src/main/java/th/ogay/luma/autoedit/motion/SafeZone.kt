package th.ogay.luma.autoedit.motion

import kotlin.math.max
import kotlin.math.min

/** An area of the frame that must stay visible (a minimap, ammo counter, kill feed), as fractions of the frame. */
data class Protected(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    init { require(left in 0.0..1.0 && right in 0.0..1.0 && top in 0.0..1.0 && bottom in 0.0..1.0 && right > left && bottom > top) }
}

/**
 * HUD-safe cropping: a zoomed window may not cut off any protected area. The window must contain the box that wraps all
 * protected areas, so the most zoom allowed is 1 / (the longer side of that box), and the centre has to stay where the
 * box still fits. An area touching two opposite edges allows no zoom at all.
 */
class SafeZone(private val areas: List<Protected>) {
    private val x0 = areas.minOfOrNull { it.left } ?: 0.5; private val x1 = areas.maxOfOrNull { it.right } ?: 0.5
    private val y0 = areas.minOfOrNull { it.top } ?: 0.5; private val y1 = areas.maxOfOrNull { it.bottom } ?: 0.5

    /** The biggest zoom that keeps every protected area in view. */
    fun maxScale(): Double = if (areas.isEmpty()) Double.MAX_VALUE else 1.0 / max(x1 - x0, y1 - y0)

    /** The centre nearest to ([x], [y]) at zoom [scale] that keeps the protected box in view and the window inside the frame. */
    fun fit(x: Double, y: Double, scale: Double): Pair<Double, Double> {
        val h = 0.5 / scale
        var lo = h; var hi = 1 - h; var loY = h; var hiY = 1 - h
        if (areas.isNotEmpty()) { lo = max(lo, x1 - h); hi = min(hi, x0 + h); loY = max(loY, y1 - h); hiY = min(hiY, y0 + h) }
        return (if (lo <= hi) x.coerceIn(lo, hi) else (lo + hi) / 2) to (if (loY <= hiY) y.coerceIn(loY, hiY) else (loY + hiY) / 2)
    }
    companion object { val NONE = SafeZone(emptyList()) }
}
