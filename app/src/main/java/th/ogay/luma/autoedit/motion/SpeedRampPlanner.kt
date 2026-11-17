package th.ogay.luma.autoedit.motion

import th.ogay.luma.autoedit.core.SpeedSegment
import kotlin.math.max
import kotlin.math.min

/**
 * Slow motion around the peak of a clip: normal speed, a short ease down, the slow peak, an ease up, normal speed.
 * Segments are offsets from the clip's own start (the Phase 1 convention), contiguous, and cover the whole clip.
 */
object SpeedRampPlanner {
    /** Rates are limited to a gentle range; the EDL allows 0.125x to 8x, a highlight does not need more. */
    const val MIN_RATE = 0.4
    private const val MIN_CLIP_US = 1_500_000L

    /** Slowest rate for an editing-energy setting: no slow motion at 0, down to [MIN_RATE] at 1. */
    fun peakRate(energy: Double): Double { require(energy.isFinite() && energy in 0.0..1.0); return if (energy < 0.05) 1.0 else 1.0 - energy * (1.0 - MIN_RATE) }

    /** Empty list means "play at normal speed" (no segments needed). */
    fun plan(sourceDurationUs: Long, peakSourceOffsetUs: Long?, energy: Double, peakHoldUs: Long = 500_000, easeUs: Long = 250_000): List<SpeedSegment> {
        if (peakSourceOffsetUs == null || sourceDurationUs < MIN_CLIP_US) return emptyList()
        val rate = peakRate(energy); if (rate >= 0.999) return emptyList()
        val half = peakHoldUs / 2
        // Keep the peak and its two eases inside the clip, with at least 150 ms of normal speed at each end where there is room.
        val edge = 150_000L
        val span = peakHoldUs + 2 * easeUs
        if (sourceDurationUs < span + 2 * edge) return emptyList()
        val start = (peakSourceOffsetUs - half - easeUs).coerceIn(edge, sourceDurationUs - edge - span)
        val mid = (rate + 1.0) / 2
        val cuts = listOf(0L, start, start + easeUs, start + easeUs + peakHoldUs, start + span, sourceDurationUs)
        val rates = listOf(1.0, mid, rate, mid, 1.0)
        return (0 until 5).map { SpeedSegment(cuts[it], cuts[it + 1], rates[it]) }.filter { it.sourceOffsetEndUs > it.sourceOffsetStartUs }
            .let { segs -> // contiguity after filtering is guaranteed because cuts are strictly increasing here
                segs
            }
    }
}
