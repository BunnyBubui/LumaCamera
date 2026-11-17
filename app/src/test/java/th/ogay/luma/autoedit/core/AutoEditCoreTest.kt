package th.ogay.luma.autoedit.core

import org.junit.Assert.*
import org.junit.Test

class AutoEditCoreTest {
    private val asset = MediaAssetRef("g1", "content://game/video", 10_000_000, 1920, 1080)

    private fun plan(
        clip: ClipDecision = ClipDecision("c1", "g1", 1_000_000, 3_000_000, 0, "action", 0.8),
        events: List<AnalysisEvent> = emptyList()
    ) = EditDecisionList(
        planId = "p1", presetSnapshot = AutoEditPreset.gamingDefault(),
        analysisVersion = "p1-baseline", sourceAssets = listOf(asset),
        clips = listOf(clip), analysisEvents = events, generationSeed = 7
    )

    @Test fun defaultGamingPresetIsLocked() {
        val p = AutoEditPreset.gamingDefault()
        assertEquals("gaming_highlight_v2", p.presetId)
        assertEquals("gaming", p.mode)
        assertEquals(0.65, p.editingEnergy, 0.0)
        assertEquals(0.45, p.cameraMotion, 0.0)
        assertEquals(0.60, p.soundEffects, 0.0)
        assertTrue(p.preserveSourceAudio)
        assertFalse(AutoEditFeatureFlags.ENABLED)
        assertFalse(p.automaticPublish)
    }

    @Test fun normalPlanPassesAndRetainsOriginalAudio() {
        assertTrue(EdlValidator.validate(plan()).isEmpty())
        assertTrue(EdlValidator.validate(plan(ClipDecision(
            "c1", "g1", 1_000_000, 3_000_000, 0, "action", 0.8,
            originalAudioPolicy = "mute"
        ))).any { it.code == "audio" })
    }

    @Test fun missingSourceAndOutOfBoundsClipAreRejected() {
        assertTrue(EdlValidator.validate(plan(ClipDecision(
            "c1", "missing", 0, 2_000_000, 0, "action", 0.7
        ))).any { it.code == "missing_asset" })
        assertTrue(EdlValidator.validate(plan(ClipDecision(
            "c1", "g1", 9_000_000, 12_000_000, 0, "action", 0.7
        ))).any { it.code == "source_range" })
    }

    @Test fun timelineMustBeContiguous() {
        val first = ClipDecision("c1", "g1", 0, 1_000_000, 0, "setup", 0.5)
        val second = ClipDecision("c2", "g1", 1_000_000, 2_000_000, 1_100_000, "kill", 0.9)
        val p = plan().copy(clips = listOf(first, second))
        assertTrue(EdlValidator.validate(p).any { it.code == "output_clock" })
    }

    @Test fun audioSpikeAloneCannotDeclareConfirmedKill() {
        val suspicious = AnalysisEvent("e1", "g1", 1_000_000, 1_300_000,
            AnalysisEventType.CONFIRMED_KILL, 0.9, 0.9, "detector-1",
            listOf(EventEvidence("audio_peak", 1_100_000, 0.9)))
        assertTrue(EdlValidator.validate(plan(events = listOf(suspicious)))
            .any { it.code == "evidence" })
        val supported = suspicious.copy(evidence = listOf(EventEvidence(
            "hud", 1_100_000, 0.95, "recognized elimination feed")))
        assertTrue(EdlValidator.validate(plan(events = listOf(supported))).isEmpty())
    }

    @Test fun motionKeyframesStayWithinOutputClip() {
        val clip = ClipDecision("c1", "g1", 0, 1_000_000, 0, "action", 0.8,
            motionKeyframes = listOf(MotionKeyframe(100_000, 0.5, 0.5, 1.05)))
        assertTrue(EdlValidator.validate(plan(clip)).isEmpty())
        assertTrue(EdlValidator.validate(plan(clip.copy(
            motionKeyframes = listOf(MotionKeyframe(2_000_000, 0.5, 0.5, 1.05))
        ))).any { it.code == "motion" })
    }

    @Test fun timeMapperHandlesSpeedRampBoundariesAndInverse() {
        val mapper = TimeWarpMapper.forClip(1_000_000, listOf(
            SpeedSegment(0, 500_000, 0.5),
            SpeedSegment(500_000, 1_000_000, 2.0)
        ))
        assertEquals(1_250_000, mapper.outputDurationUs)
        assertEquals(1_000_000, mapper.sourceToOutput(500_000))
        assertEquals(500_000, mapper.outputToSource(1_000_000))
        for (source in listOf(0L, 100_000L, 500_000L, 750_000L, 1_000_000L)) {
            assertTrue(kotlin.math.abs(source - mapper.outputToSource(mapper.sourceToOutput(source))) <= 2)
        }
    }

    @Test fun speedSegmentsMustExactlyCoverClip() {
        try {
            TimeWarpMapper.forClip(1_000_000, listOf(SpeedSegment(0, 900_000, 1.0)))
            fail("Expected invalid speed segment coverage")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    @Test fun invalidPresetIntensityCannotBeUsedAsConfidence() {
        try {
            AutoEditPreset(editingEnergy = Double.NaN)
            fail("Expected invalid intensity")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }
}
