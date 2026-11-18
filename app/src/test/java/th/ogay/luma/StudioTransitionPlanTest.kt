package th.ogay.luma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioTransitionPlanTest {
    private val s = StudioTransitionPlan
    private fun clip(fromMs: Long = 1_000L, toMs: Long = 5_000L,
                     sourceMs: Long = 8_000L, speed: Double = 1.0,
                     outUs: Long? = null, curve: Boolean = false) =
        StudioTransitionPlan.Clip(fromMs * 1_000L, toMs * 1_000L,
            sourceMs * 1_000L, speed, outUs, curve)
    private val left = clip()
    private val right = clip()
    private val cut = 10_000_000L
    private fun plan(kind: Int, packed: Long = 0L,
                     a: StudioTransitionPlan.Clip = left,
                     b: StudioTransitionPlan.Clip = right) =
        s.build(a, b, cut, StudioTransitionPlan.Join(kind, packed))
    private fun near(actual: Float, expected: Float) = assertEquals(expected.toDouble(), actual.toDouble(), 0.00001)

    @Test fun packedJoinMatchesExporterAlignmentAndDefault() {
        assertEquals(600L, s.lengthMs(0))
        assertEquals(0, s.alignment(0))
        assertEquals(300L, s.outMs(0)); assertEquals(300L, s.inMs(0))
        assertEquals(600L, s.outMs(1_000_000L)); assertEquals(0L, s.inMs(1_000_000L))
        assertEquals(0L, s.outMs(2_000_900L)); assertEquals(900L, s.inMs(2_000_900L))
        assertEquals(250L, s.outMs(501L)); assertEquals(250L, s.inMs(501L)) // exporter floors both halves
    }

    @Test fun overlapProgressAndVideoAudioWeightsAtFivePoints() {
        val p = plan(s.OVERLAP)
        assertEquals(s.OVERLAP, p.kind)
        assertEquals(300_000L, p.beforeUs); assertEquals(300_000L, p.afterUs)
        listOf(0f, .25f, .5f, .75f, 1f).forEachIndexed { i, fraction ->
            val f = s.sample(p, cut - 300_000L + i * 150_000L)
            near(f.progress, fraction)
            near(f.outgoingVideoAlpha, 1f - fraction)
            near(f.incomingVideoAlpha, fraction)
            near(f.outgoingAudioGain, 1f - fraction)
            near(f.incomingAudioGain, fraction)
            assertTrue(f.active)
        }
    }

    @Test fun overlapEndAlignedUsesOnlyIncomingPreroll() {
        val p = plan(s.OVERLAP, 1_000_600L)
        assertEquals(600_000L, p.beforeUs); assertEquals(0L, p.afterUs)
        near(s.sample(p, cut - 300_000L).progress, .5f)
        near(s.sample(p, cut).incomingVideoAlpha, 1f)
        assertEquals(right.fromUs - 600_000L, s.sample(p, cut - 600_000L).incomingSourceUs)
    }

    @Test fun overlapStartAlignedUsesOutgoingPostroll() {
        val p = plan(s.OVERLAP, 2_000_600L)
        assertEquals(0L, p.beforeUs); assertEquals(600_000L, p.afterUs)
        near(s.sample(p, cut + 300_000L).progress, .5f)
        assertEquals(left.toUs + 300_000L, s.sample(p, cut + 300_000L).outgoingSourceUs)
    }

    @Test fun clipsCapBothTransitionHalves() {
        val a = clip(1_000, 1_200)
        val b = clip(1_000, 1_150)
        val p = plan(s.OVERLAP, 0L, a, b)
        assertEquals(100_000L, p.beforeUs)
        assertEquals(75_000L, p.afterUs)
        val q = plan(s.DIP_BLACK, 0L, a, b)
        assertEquals(100_000L, q.beforeUs); assertEquals(75_000L, q.afterUs)
    }

    @Test fun insufficientHandlesFallBackToHardCut() {
        val a = clip(0, 1_000, 1_000)
        val b = clip(0, 1_000, 1_000)
        val p = plan(s.OVERLAP, 0L, a, b)
        assertEquals(s.CUT, p.kind)
        assertEquals(StudioTransitionPlan.Fallback.OVERLAP_MISSING_HANDLES, p.fallback)
        assertEquals(cut, p.startUs); assertEquals(cut, p.endUs)
        near(s.sample(p, cut - 1).outgoingVideoAlpha, 1f)
        near(s.sample(p, cut).incomingVideoAlpha, 1f)
    }

    @Test fun overlapRejectsUnknownSourceAndUnsupportedSpeeds() {
        assertEquals(StudioTransitionPlan.Fallback.OVERLAP_MISSING_HANDLES,
            plan(s.OVERLAP, a = clip(sourceMs = 0)).fallback)
        assertEquals(StudioTransitionPlan.Fallback.OVERLAP_UNSUPPORTED_SPEED,
            plan(s.OVERLAP, a = clip(speed = 2.0)).fallback)
        assertEquals(StudioTransitionPlan.Fallback.OVERLAP_UNSUPPORTED_SPEED,
            plan(s.OVERLAP, a = clip(curve = true, outUs = 4_000_000L)).fallback)
        assertEquals(StudioTransitionPlan.Fallback.HARD_CUT,
            s.build(left, right, cut, StudioTransitionPlan.Join(s.OVERLAP, sameLogicalClip = true)).fallback)
    }

    @Test fun packedZeroMeansDefaultButExplicitZeroMeansHardCut() {
        assertEquals(600_000L, plan(s.DIP_BLACK).beforeUs + plan(s.DIP_BLACK).afterUs)
        val zero = s.build(left, right, cut, StudioTransitionPlan.Join(s.DIP_BLACK, 0L, durationOverrideUs = 0L))
        assertEquals(s.CUT, zero.kind)
        assertEquals(StudioTransitionPlan.Fallback.INVALID_DURATION, zero.fallback)
        assertEquals(s.CUT, plan(s.CUT).kind)
    }

    @Test fun fadeThroughBlackAndFlashWhiteHaveBackgroundNotCrossDissolve() {
        for ((kind, background) in listOf(s.DIP_BLACK to StudioTransitionPlan.Backdrop.BLACK,
                s.FLASH_WHITE to StudioTransitionPlan.Backdrop.WHITE)) {
            val p = plan(kind)
            val firstQuarter = s.sample(p, cut - 150_000L)
            val middle = s.sample(p, cut)
            val thirdQuarter = s.sample(p, cut + 150_000L)
            near(firstQuarter.outgoingVideoAlpha, .5f)
            near(firstQuarter.incomingVideoAlpha, 0f)
            near(middle.outgoingVideoAlpha, 0f)
            near(middle.incomingVideoAlpha, 0f)
            near(thirdQuarter.outgoingVideoAlpha, 0f)
            near(thirdQuarter.incomingVideoAlpha, .5f)
            assertEquals(background, middle.backdrop)
            near(firstQuarter.outgoingAudioGain, 1f)
            near(thirdQuarter.incomingAudioGain, 1f)
        }
    }

    @Test fun noCrossfadeForGeometricAndBlurKinds() {
        for (kind in s.ZOOM..s.BLUR) {
            val p = plan(kind)
            near(s.sample(p, cut - 150_000L).outgoingVideoAlpha, 1f)
            near(s.sample(p, cut + 150_000L).incomingVideoAlpha, 1f)
            near(s.sample(p, cut - 150_000L).incomingVideoAlpha, 0f)
            near(s.sample(p, cut + 150_000L).outgoingVideoAlpha, 0f)
            assertEquals(StudioTransitionPlan.Backdrop.NONE, s.sample(p, cut).backdrop)
        }
        val slide = plan(s.SLIDE)
        near(s.sample(slide, cut - 150_000L).easedProgress, .25f)
        near(s.sample(slide, cut + 150_000L).easedProgress, .75f)
    }

    @Test fun ordinaryTransitionsUseSpeedAdjustedOutputTimeAndCaps() {
        val a = clip(fromMs = 2_000, toMs = 3_000, speed = 2.0)
        val b = clip(fromMs = 1_000, toMs = 2_000, speed = .5)
        val p = plan(s.DIP_BLACK, 1_000_900L, a, b)
        assertEquals(250_000L, p.beforeUs)
        assertEquals(0L, p.afterUs)
        assertEquals(a.toUs - 250_000L * 2L, s.sample(p, cut - 250_000L).outgoingSourceUs)
    }

    @Test fun curvesRequireExplicitOutputDurationMap() {
        val unknown = plan(s.SLIDE, a = clip(curve = true))
        assertEquals(StudioTransitionPlan.Fallback.MISSING_SPEED_MAP, unknown.fallback)
        val mapped = plan(s.SLIDE, a = clip(curve = true, outUs = 200_000L))
        assertEquals(100_000L, mapped.beforeUs)
        assertEquals(StudioTransitionPlan.Fallback.NONE, mapped.fallback)
    }

    @Test fun nonOverlapAlignmentsAndOddLengthsAreStable() {
        val ending = plan(s.DIP_BLACK, 1_000_600L)
        val starting = plan(s.DIP_BLACK, 2_000_600L)
        assertEquals(600_000L, ending.beforeUs); assertEquals(0L, ending.afterUs)
        assertEquals(0L, starting.beforeUs); assertEquals(600_000L, starting.afterUs)
        near(s.sample(ending, cut).incomingVideoAlpha, 1f)
        near(s.sample(starting, cut).incomingVideoAlpha, 0f)
        assertEquals(250L, s.outMs(501L))
        assertEquals(250L, s.inMs(501L))
    }

    @Test fun seekPauseAndReverseEvaluationIsExactlyRepeatable() {
        val p = plan(s.OVERLAP)
        val times = listOf(cut - 400_000L, cut - 150_000L, cut, cut + 150_000L, cut + 400_000L)
        val first = times.associateWith { s.sample(p, it) }
        times.asReversed().forEach { assertEquals(first[it], s.sample(p, it)) }
        assertFalse(s.sample(p, cut - 400_000L).active)
        assertFalse(s.sample(p, cut + 400_000L).active)
        assertNull(s.sample(p, cut - 400_000L).incomingSourceUs)
        assertNotNull(s.sample(p, cut + 400_000L).incomingSourceUs)
    }

    @Test fun overlapPositionsUseBorrowedSourceNotTrimmedPositions() {
        val p = plan(s.OVERLAP)
        val start = s.sample(p, cut - 300_000L)
        val end = s.sample(p, cut + 300_000L)
        assertEquals(left.toUs - 300_000L, start.outgoingSourceUs)
        assertEquals(right.fromUs - 300_000L, start.incomingSourceUs)
        assertEquals(left.toUs + 300_000L, end.outgoingSourceUs)
        assertEquals(right.fromUs + 300_000L, end.incomingSourceUs)
    }

    @Test fun guardInvalidClipsAndKindsWithoutNan() {
        val invalid = clip(fromMs = 2_000, toMs = 1_000)
        val p = plan(s.OVERLAP, a = invalid)
        assertEquals(s.CUT, p.kind)
        assertEquals(StudioTransitionPlan.Fallback.INVALID_CLIP, p.fallback)
        assertEquals(StudioTransitionPlan.Fallback.INVALID_KIND, plan(15).fallback)
        assertEquals(StudioTransitionPlan.Fallback.INVALID_CLIP, plan(s.SLIDE, a = clip(speed = Double.NaN)).fallback)
        val v = s.sample(p, cut)
        assertTrue(v.progress.isFinite())
        near(v.outgoingVideoAlpha + v.incomingVideoAlpha, 1f)
    }

    @Test fun fivePointGridAcrossAllEffectsAndAlignments() {
        val fractions = listOf(0f, .25f, .5f, .75f, 1f)
        for (kind in s.DIP_BLACK..s.OVERLAP) for (alignment in 0..2) {
            val p = plan(kind, alignment * 1_000_000L + 600L)
            assertEquals(kind, p.kind)
            fractions.forEachIndexed { index, fraction ->
                val t = p.startUs + (p.endUs - p.startUs) * index / 4L
                val value = s.sample(p, t)
                near(value.progress, fraction)
                listOf(value.progress, value.phaseProgress, value.easedProgress,
                    value.outgoingVideoAlpha, value.incomingVideoAlpha,
                    value.outgoingAudioGain, value.incomingAudioGain).forEach {
                    assertTrue(it.isFinite() && it in 0f..1f)
                }
                if (kind == s.OVERLAP) {
                    near(value.outgoingVideoAlpha + value.incomingVideoAlpha, 1f)
                    near(value.outgoingAudioGain + value.incomingAudioGain, 1f)
                }
            }
        }
    }
}
