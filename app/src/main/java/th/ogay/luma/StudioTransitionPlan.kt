package th.ogay.luma

/**
 * Stateless, microsecond-based timeline evaluator for joins between two *logical* clips.
 *
 * [cutUs] is the outgoing clip's trimmed/speed-adjusted end on the project timeline,
 * NOT a source timestamp or elapsed wall-clock time. Calling [sample] in any order
 * (including while seeking backwards or paused) produces the same result.
 *
 * Mirrors VideoEdit.joinOutMs/joinInMs and its per-clip half-duration cap. For kind 7,
 * it also mirrors VideoEdit.composition's borrowed source handles and 50 ms threshold.
 * Nothing here changes Media3 export. See [Sample] for per-effect limitations.
 */
internal object StudioTransitionPlan {
    const val CUT = 0
    const val DIP_BLACK = 1
    const val FLASH_WHITE = 2
    const val ZOOM = 3
    const val SLIDE = 4
    const val WIPE = 5
    const val BLUR = 6
    const val OVERLAP = 7

    private const val PACK_STEP_MS = 1_000_000L
    private const val DEFAULT_LENGTH_MS = 600L
    private const val MIN_OVERLAP_MS = 50L

    /** All positions are source microseconds. Source length may be unknown (0), except for overlap. */
    data class Clip(
        val fromUs: Long,
        val toUs: Long,
        val sourceDurationUs: Long = 0L,
        val speed: Double = 1.0,
        /** Required when [hasSpeedCurve] is true; otherwise calculated from trim and speed. */
        val outputDurationUs: Long? = null,
        val hasSpeedCurve: Boolean = false
    ) {
        internal fun durationUs(): Long? {
            if (fromUs < 0L || toUs <= fromUs || !speed.isFinite() || speed <= 0.0) return null
            if (hasSpeedCurve && outputDurationUs == null) return null
            val d = outputDurationUs ?: ((toUs - fromUs).toDouble() / speed).toLong()
            return d.takeIf { it > 0L }
        }
    }

    /** [packedMs] is the existing joinMs serialization: alignment in millions, length below. */
    data class Join(
        val kind: Int,
        val packedMs: Long = 0L,
        /** Explicit 0 forces a cut; packed length 0 itself means the legacy 600 ms default. */
        val durationOverrideUs: Long? = null,
        /** True when both sides are pieces of the same logical clip, not merely the same URI. */
        val sameLogicalClip: Boolean = false
    )

    enum class Fallback {
        NONE, HARD_CUT, INVALID_KIND, INVALID_CLIP, MISSING_SPEED_MAP,
        INVALID_DURATION, OVERLAP_UNSUPPORTED_SPEED, OVERLAP_MISSING_HANDLES
    }

    enum class Backdrop { NONE, BLACK, WHITE }

    data class Plan(
        val left: Clip,
        val right: Clip,
        val cutUs: Long,
        val requestedKind: Int,
        /** 0 if the transition is not possible; callers must play a hard cut. */
        val kind: Int,
        val startUs: Long,
        val endUs: Long,
        val beforeUs: Long,
        val afterUs: Long,
        val fallback: Fallback
    ) {
        val isOverlap: Boolean get() = kind == OVERLAP
    }

    /**
     * [progress] is 0..1 over the entire join window. [phaseProgress] is 0..1
     * over the outgoing portion (before cut) or the incoming portion (after cut).
     * [easedProgress] applies the slide/zoom easing to that local portion.
     *
     * Video alphas are *global* weights: DIP/FLASH uses [backdrop] behind the
     * active clip; WIPE's per-pixel mask, BLUR and SLIDE geometry must be applied
     * separately. For OVERLAP, outgoing/incoming weights linearly crossfade.
     * Audio gains are normalized envelopes; multiply by clip volume/mute state.
     * The current export mixes overlapping audio tracks without these gain ramps:
     * video parity is modeled, audio parity needs a separate export fix.
     */
    data class Sample(
        val timelineUs: Long,
        val active: Boolean,
        val progress: Float,
        val phaseProgress: Float,
        val easedProgress: Float,
        val outgoingVideoAlpha: Float,
        val incomingVideoAlpha: Float,
        val outgoingAudioGain: Float,
        val incomingAudioGain: Float,
        val backdrop: Backdrop,
        val outgoingSourceUs: Long?,
        val incomingSourceUs: Long?
    )

    /** Pure equivalents of VideoEdit.joinLength / joinAlign / joinOutMs / joinInMs. */
    fun lengthMs(packed: Long): Long = (packed % PACK_STEP_MS).takeIf { it > 0L } ?: DEFAULT_LENGTH_MS
    fun alignment(packed: Long): Int = (packed / PACK_STEP_MS).coerceIn(0L, 2L).toInt()
    fun outMs(packed: Long): Long = when (alignment(packed)) {
        0 -> lengthMs(packed) / 2L
        1 -> lengthMs(packed)
        else -> 0L
    }
    fun inMs(packed: Long): Long = when (alignment(packed)) {
        0 -> lengthMs(packed) / 2L
        2 -> lengthMs(packed)
        else -> 0L
    }

    fun build(left: Clip, right: Clip, cutUs: Long, join: Join): Plan {
        val cut = cutUs.coerceAtLeast(0L)
        fun hard(why: Fallback) = Plan(left, right, cut, join.kind, CUT, cut, cut, 0L, 0L, why)
        if (join.kind == CUT || join.sameLogicalClip) return hard(Fallback.HARD_CUT)
        if (join.kind !in DIP_BLACK..OVERLAP) return hard(Fallback.INVALID_KIND)
        if (left.hasSpeedCurve && left.outputDurationUs == null ||
            right.hasSpeedCurve && right.outputDurationUs == null) return hard(Fallback.MISSING_SPEED_MAP)
        val leftLen = left.durationUs() ?: return hard(Fallback.INVALID_CLIP)
        val rightLen = right.durationUs() ?: return hard(Fallback.INVALID_CLIP)
        val totalOverride = join.durationOverrideUs
        if (totalOverride != null && totalOverride <= 0L) return hard(Fallback.INVALID_DURATION)
        val outMs = if (totalOverride == null) outMs(join.packedMs) else {
            val whole = totalOverride / 1_000L
            when (alignment(join.packedMs)) { 0 -> whole / 2L; 1 -> whole; else -> 0L }
        }
        val inMs = if (totalOverride == null) inMs(join.packedMs) else {
            val whole = totalOverride / 1_000L
            when (alignment(join.packedMs)) { 0 -> whole / 2L; 2 -> whole; else -> 0L }
        }
        if (join.kind == OVERLAP) {
            if (join.sameLogicalClip || left.speed != 1.0 || right.speed != 1.0 ||
                left.hasSpeedCurve || right.hasSpeedCurve) return hard(Fallback.OVERLAP_UNSUPPORTED_SPEED)
            // VideoEdit's half-clip cap and borrowed source handles are millisecond-quantized.
            if (left.sourceDurationUs <= 0L || right.sourceDurationUs <= 0L ||
                left.toUs > left.sourceDurationUs || right.toUs > right.sourceDurationUs) {
                return hard(Fallback.OVERLAP_MISSING_HANDLES)
            }
            val before = minOf(outMs, leftLen / 2_000L, right.fromUs / 1_000L).coerceAtLeast(0L)
            val after = minOf(inMs, rightLen / 2_000L,
                (left.sourceDurationUs - left.toUs).coerceAtLeast(0L) / 1_000L).coerceAtLeast(0L)
            if (before + after < MIN_OVERLAP_MS) return hard(Fallback.OVERLAP_MISSING_HANDLES)
            return window(left, right, cut, join.kind, before * 1_000L, after * 1_000L)
        }
        // Export caps in/out effects independently at half each clip's *output* length.
        val before = minOf(outMs * 1_000L, leftLen / 2L).coerceAtLeast(0L)
        val after = minOf(inMs * 1_000L, rightLen / 2L).coerceAtLeast(0L)
        if (before == 0L && after == 0L) return hard(Fallback.INVALID_DURATION)
        return window(left, right, cut, join.kind, before, after)
    }

    private fun window(left: Clip, right: Clip, cut: Long, kind: Int, before: Long, after: Long): Plan =
        Plan(left, right, cut, kind, kind, (cut - before).coerceAtLeast(0L),
            if (cut > Long.MAX_VALUE - after) Long.MAX_VALUE else cut + after,
            before, after, Fallback.NONE)

    /** No mutable player state, animations, or clocks: safe for repeated/descending timestamps. */
    fun sample(plan: Plan, timelineUs: Long): Sample {
        val t = timelineUs.coerceAtLeast(0L)
        val incomingSide = t >= plan.cutUs
        val active = plan.kind != CUT && t >= plan.startUs && t <= plan.endUs
        val windowDuration = plan.endUs - plan.startUs
        val p = when {
            plan.kind == CUT || windowDuration <= 0L -> if (incomingSide) 1f else 0f
            t <= plan.startUs -> 0f
            t >= plan.endUs -> 1f
            else -> ((t - plan.startUs).toDouble() / windowDuration.toDouble()).toFloat().coerceIn(0f, 1f)
        }
        val phase = when {
            !active -> if (incomingSide) 1f else 0f
            incomingSide -> if (plan.afterUs > 0L) ((t - plan.cutUs).toDouble() / plan.afterUs).toFloat().coerceIn(0f, 1f) else 1f
            else -> if (plan.beforeUs > 0L) ((t - plan.startUs).toDouble() / plan.beforeUs).toFloat().coerceIn(0f, 1f) else 1f
        }
        val eased = if (plan.kind == SLIDE || plan.kind == ZOOM) {
            if (incomingSide) 1f - (1f - phase) * (1f - phase) else phase * phase
        } else phase
        var outgoingAlpha = if (incomingSide) 0f else 1f
        var incomingAlpha = if (incomingSide) 1f else 0f
        var outgoingAudio = outgoingAlpha
        var incomingAudio = incomingAlpha
        val backdrop = when (plan.kind) {
            DIP_BLACK -> Backdrop.BLACK
            FLASH_WHITE -> Backdrop.WHITE
            else -> Backdrop.NONE
        }
        if (active && plan.kind == OVERLAP) {
            outgoingAlpha = 1f - p; incomingAlpha = p
            outgoingAudio = 1f - p; incomingAudio = p
        } else if (active && (plan.kind == DIP_BLACK || plan.kind == FLASH_WHITE)) {
            if (incomingSide) incomingAlpha = phase else outgoingAlpha = 1f - phase
        }
        // Only expose a source position for a clip whose content must be decoded.
        val leftVisible = outgoingAlpha > 0f || (plan.kind == OVERLAP && active)
        val rightVisible = incomingAlpha > 0f || (plan.kind == OVERLAP && active)
        fun mapSource(clip: Clip, base: Long): Long {
            val offset = ((t - plan.cutUs).toDouble() * clip.speed).toLong()
            val raw = if (offset > 0L && base > Long.MAX_VALUE - offset) Long.MAX_VALUE
                else if (offset < 0L && base < Long.MIN_VALUE - offset) Long.MIN_VALUE
                else base + offset
            val lower = if (plan.isOverlap) 0L else clip.fromUs
            val upper = if (plan.isOverlap) clip.sourceDurationUs else clip.toUs
            return raw.coerceIn(lower, upper.coerceAtLeast(lower))
        }
        return Sample(
            t, active, p, phase, eased,
            outgoingAlpha.coerceIn(0f, 1f), incomingAlpha.coerceIn(0f, 1f),
            outgoingAudio.coerceIn(0f, 1f), incomingAudio.coerceIn(0f, 1f),
            backdrop,
            if (leftVisible) mapSource(plan.left, plan.left.toUs) else null,
            if (rightVisible) mapSource(plan.right, plan.right.fromUs) else null
        )
    }
}
