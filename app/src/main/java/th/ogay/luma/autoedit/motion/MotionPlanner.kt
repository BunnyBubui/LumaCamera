package th.ogay.luma.autoedit.motion

import th.ogay.luma.autoedit.core.MotionKeyframe
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Where the subject (player, face, action) is in the frame at a moment of the clip; offset on the clip's output clock. */
data class Focus(val outputOffsetUs: Long, val x: Double, val y: Double)

/**
 * Plans slow, bounded camera moves for the main picture: a push-in towards the action, a pull-out, or none for very
 * short clips. Every number is limited: the zoom stays below [MAX_SCALE], the centre moves no faster than
 * [MAX_PAN_PER_SECOND] frames per second, and HUD areas stay visible. No randomness: same input, same keyframes.
 */
object MotionPlanner {
    const val MAX_SCALE = 1.35
    const val MAX_PAN_PER_SECOND = 0.20
    /** A clip shorter than this stays still; a move on it would be a twitch. */
    const val MIN_CLIP_US = 800_000L

    enum class Move { PUSH_IN, PULL_OUT, STILL }

    /** Zoom for a [cameraMotion] setting 0..1: nothing at 0, up to [MAX_SCALE] at 1, then limited by the HUD. */
    fun targetScale(cameraMotion: Double, safe: SafeZone = SafeZone.NONE): Double {
        require(cameraMotion.isFinite() && cameraMotion in 0.0..1.0)
        return min(1.0 + cameraMotion * (MAX_SCALE - 1.0), min(MAX_SCALE, safe.maxScale())).coerceAtLeast(1.0)
    }

    /** Move for clip number [index]: the clip with the peak pushes in; others alternate in and out so the edit does not feel repeated. */
    fun chooseMove(index: Int, outputSpanUs: Long, hasPeak: Boolean, cameraMotion: Double): Move = when {
        cameraMotion <= 0.02 || outputSpanUs < MIN_CLIP_US -> Move.STILL
        hasPeak -> Move.PUSH_IN
        index % 2 == 0 -> Move.PUSH_IN else -> Move.PULL_OUT
    }

    /**
     * Keyframes for one clip of [outputSpanUs]. [peakOffsetUs] (output clock) is where the push-in should be tightest;
     * [focus] is the subject track, if any. Returns an empty list for a still clip.
     */
    fun plan(move: Move, outputSpanUs: Long, cameraMotion: Double, peakOffsetUs: Long? = null, focus: List<Focus> = emptyList(), safe: SafeZone = SafeZone.NONE): List<MotionKeyframe> {
        if (move == Move.STILL || outputSpanUs < MIN_CLIP_US) return emptyList()
        val s = targetScale(cameraMotion, safe)
        if (s <= 1.0001) return emptyList()
        val times = ArrayList<Long>().apply { add(0L); if (peakOffsetUs != null && peakOffsetUs in 1 until outputSpanUs) add(peakOffsetUs); add(outputSpanUs) }.distinct().sorted()
        val last = times.size - 1
        val out = ArrayList<MotionKeyframe>()
        var prev: Pair<Double, Double>? = null; var prevT = 0L
        times.forEachIndexed { k, t ->
            val scale = when (move) {
                Move.PUSH_IN -> if (peakOffsetUs != null && t == peakOffsetUs) s else if (k == 0) 1.0 else if (peakOffsetUs != null && t == times[last]) max(1.0, 1.0 + (s - 1.0) * 0.5) else s
                else -> if (k == 0) s else 1.0
            }
            val want = focusAt(focus, t)
            var (cx, cy) = safe.fit(want.first, want.second, scale)
            val p = prev
            if (p != null) { // limit how fast the centre may move
                val limit = MAX_PAN_PER_SECOND * (t - prevT) / 1_000_000.0
                cx = p.first + (cx - p.first).coerceIn(-limit, limit); cy = p.second + (cy - p.second).coerceIn(-limit, limit)
                val fitted = safe.fit(cx, cy, scale); cx = fitted.first; cy = fitted.second
            }
            out += MotionKeyframe(t, cx.coerceIn(0.0, 1.0), cy.coerceIn(0.0, 1.0), scale.coerceIn(1.0, 2.0))
            prev = cx to cy; prevT = t
        }
        return out
    }

    private fun focusAt(f: List<Focus>, t: Long): Pair<Double, Double> {
        if (f.isEmpty()) return 0.5 to 0.5
        val s = f.sortedBy { it.outputOffsetUs }
        val nearest = s.minByOrNull { abs(it.outputOffsetUs - t) }!!
        return nearest.x.coerceIn(0.0, 1.0) to nearest.y.coerceIn(0.0, 1.0)
    }
}
