package th.ogay.luma.autoedit.core

import org.junit.Assert.*
import org.junit.Test

class AutoEditPlannerTest {
    private val assets = listOf(
        MediaAssetRef("a", "content://game/one", 8_000_000, 1920, 1080),
        MediaAssetRef("b", "content://game/two", 8_000_000, 1920, 1080)
    )
    private val spans = listOf(
        ReviewedSpan("kill", "b", 1_000_000, 2_000_000, "user-reviewed kill", 0.9),
        ReviewedSpan("setup", "a", 3_000_000, 4_500_000, "setup", 0.6)
    )

    @Test fun sameInputAndSeedProduceIdenticalEditablePlan() {
        val a = AutoEditPlanner.draftFromReviewedSpans(
            AutoEditPreset.gamingDefault(), assets, spans, 42L)
        val b = AutoEditPlanner.draftFromReviewedSpans(
            AutoEditPreset.gamingDefault(), assets, spans, 42L)
        assertEquals(a, b)
        assertTrue(EdlValidator.validate(a).isEmpty())
        assertEquals("b", a.clips[0].sourceAssetId)
        assertEquals(1_000_000, a.clips[1].outputStartUs)
        assertTrue(a.clips.all { it.originalAudioPolicy == "keep" })
        assertTrue(a.sfxCues.isEmpty())
        assertTrue(a.analysisEvents.isEmpty())
    }

    @Test fun changingSeedChangesPlanIdWithoutChangingSourceRanges() {
        val a = AutoEditPlanner.draftFromReviewedSpans(
            AutoEditPreset.gamingDefault(), assets, spans, 1L)
        val b = AutoEditPlanner.draftFromReviewedSpans(
            AutoEditPreset.gamingDefault(), assets, spans, 2L)
        assertNotEquals(a.planId, b.planId)
        assertEquals(a.clips, b.clips)
    }

    @Test fun unknownAssetAndInvalidRangeFailBeforeTimelineIsCreated() {
        try {
            AutoEditPlanner.draftFromReviewedSpans(AutoEditPreset.gamingDefault(),
                assets, listOf(spans[0].copy(sourceAssetId = "missing")))
            fail("Expected source rejection")
        } catch (_: IllegalArgumentException) { /* expected */ }
        try {
            AutoEditPlanner.draftFromReviewedSpans(AutoEditPreset.gamingDefault(),
                assets, listOf(spans[0].copy(sourceOutUs = 12_000_000)))
            fail("Expected source bounds rejection")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }
}
