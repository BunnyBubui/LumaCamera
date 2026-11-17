package th.ogay.luma.autoedit.core

import kotlin.math.roundToLong

/**
 * A rate segment uses source offsets relative to one clip's sourceInUs,
 * never absolute source timestamps. Rates are playback multipliers.
 */
data class SpeedSegment(
    val sourceOffsetStartUs: Long,
    val sourceOffsetEndUs: Long,
    val rate: Double
)

/**
 * Deterministic piecewise source/output clock mapper, independent of Android.
 * Intermediate boundaries are rounded only once and shared by both mappings.
 * All public timestamps are microseconds.
 */
class TimeWarpMapper private constructor(private val pieces: List<Piece>) {
    private data class Piece(
        val sourceStart: Long,
        val sourceEnd: Long,
        val outputStart: Long,
        val outputEnd: Long
    )

    val sourceDurationUs: Long get() = pieces.last().sourceEnd
    val outputDurationUs: Long get() = pieces.last().outputEnd

    fun sourceToOutput(sourceOffsetUs: Long): Long {
        require(sourceOffsetUs in 0..sourceDurationUs)
        if (sourceOffsetUs == sourceDurationUs) return outputDurationUs
        val p = pieces.first { sourceOffsetUs < it.sourceEnd }
        return p.outputStart + ((sourceOffsetUs - p.sourceStart).toDouble() *
            (p.outputEnd - p.outputStart) / (p.sourceEnd - p.sourceStart)).roundToLong()
    }

    fun outputToSource(outputOffsetUs: Long): Long {
        require(outputOffsetUs in 0..outputDurationUs)
        if (outputOffsetUs == outputDurationUs) return sourceDurationUs
        val p = pieces.first { outputOffsetUs < it.outputEnd }
        return p.sourceStart + ((outputOffsetUs - p.outputStart).toDouble() *
            (p.sourceEnd - p.sourceStart) / (p.outputEnd - p.outputStart)).roundToLong()
    }

    companion object {
        fun forClip(sourceDurationUs: Long, segments: List<SpeedSegment> = emptyList()): TimeWarpMapper {
            require(sourceDurationUs > 0)
            val ranges = if (segments.isEmpty()) {
                listOf(SpeedSegment(0, sourceDurationUs, 1.0))
            } else segments
            var sourceEnd = 0L
            var outputEnd = 0L
            val pieces = ranges.map { s ->
                require(s.sourceOffsetStartUs == sourceEnd) { "Speed segments must be ordered and contiguous" }
                require(s.sourceOffsetEndUs > s.sourceOffsetStartUs && s.sourceOffsetEndUs <= sourceDurationUs)
                require(s.rate.isFinite() && s.rate in 0.125..8.0)
                val len = ((s.sourceOffsetEndUs - s.sourceOffsetStartUs) / s.rate)
                    .roundToLong().coerceAtLeast(1)
                require(outputEnd <= Long.MAX_VALUE - len) { "Output duration overflow" }
                val p = Piece(sourceEnd, s.sourceOffsetEndUs, outputEnd, outputEnd + len)
                sourceEnd = s.sourceOffsetEndUs
                outputEnd += len
                p
            }
            require(sourceEnd == sourceDurationUs) { "Speed segments must cover the clip" }
            return TimeWarpMapper(pieces)
        }
    }
}
