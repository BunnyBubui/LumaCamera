package th.ogay.luma.autoedit.motion

import th.ogay.luma.autoedit.core.MotionKeyframe

/** The visible window of the main picture, as fractions of the full frame (0..1). */
data class CropWindow(val left: Double, val top: Double, val right: Double, val bottom: Double)

/** One camera pose: window centre (0..1 of the frame) and zoom (1 = whole frame, 2 = half the width and height). */
data class Pose(val centerX: Double, val centerY: Double, val scale: Double) {
    /** The crop for this pose. It always lies inside the frame because the centre is kept [0.5/scale, 1-0.5/scale]. */
    fun window(): CropWindow {
        val h = 0.5 / scale
        val cx = centerX.coerceIn(h, 1 - h); val cy = centerY.coerceIn(h, 1 - h)
        return CropWindow(cx - h, cy - h, cx + h, cy + h)
    }
}

/**
 * The single place that turns keyframes into a pose at a time. The preview and the export must both call this, so what
 * is seen while editing is what is rendered. Between keyframes it eases (smoothstep); before the first and after the
 * last it holds the nearest pose. Offsets are on the clip's output clock, in microseconds.
 */
object MotionPath {
    val STILL = Pose(0.5, 0.5, 1.0)

    fun poseAt(keys: List<MotionKeyframe>, offsetUs: Long): Pose {
        if (keys.isEmpty()) return STILL
        if (offsetUs <= keys.first().outputOffsetUs) return keys.first().pose()
        if (offsetUs >= keys.last().outputOffsetUs) return keys.last().pose()
        val i = keys.indexOfLast { it.outputOffsetUs <= offsetUs }
        val a = keys[i]; val b = keys[i + 1]
        val t = (offsetUs - a.outputOffsetUs).toDouble() / (b.outputOffsetUs - a.outputOffsetUs)
        val e = t * t * (3 - 2 * t)
        return Pose(a.centerX + (b.centerX - a.centerX) * e, a.centerY + (b.centerY - a.centerY) * e, a.scale + (b.scale - a.scale) * e)
    }
    private fun MotionKeyframe.pose() = Pose(centerX, centerY, scale)
}
