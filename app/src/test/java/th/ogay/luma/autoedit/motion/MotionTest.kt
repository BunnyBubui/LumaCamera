package th.ogay.luma.autoedit.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import th.ogay.luma.autoedit.core.*

class MotionTest {
    private val assets = listOf(MediaAssetRef("a", "content://x", 30_000_000, 1920, 1080))
    private fun plan(preset: AutoEditPreset = AutoEditPreset.gamingDefault(), events: List<AnalysisEvent> = emptyList(), sfx: List<SfxCue> = emptyList()): EditDecisionList {
        val spans = listOf(ReviewedSpan("s1", "a", 2_000_000, 6_000_000, "r", 0.9), ReviewedSpan("s2", "a", 10_000_000, 12_000_000, "r", 0.8), ReviewedSpan("s3", "a", 20_000_000, 20_500_000, "short", 0.5))
        return AutoEditPlanner.draftFromReviewedSpans(preset, assets, spans, 1L).copy(analysisEvents = events, sfxCues = sfx)
    }
    private fun kill(at: Long) = AnalysisEvent("e$at", "a", at, at + 300_000, AnalysisEventType.POSSIBLE_KILL, 0.9, 0.8, "t", listOf(EventEvidence("hud", at, 0.8)))

    @Test fun plannedEdlValidatesAndKeepsSourceRanges() {
        val base = plan(events = listOf(kill(4_000_000)))
        val out = MotionDirector.apply(base)
        assertTrue(EdlValidator.validate(out).toString(), EdlValidator.validate(out).isEmpty())
        assertEquals(base.clips.map { it.sourceInUs to it.sourceOutUs }, out.clips.map { it.sourceInUs to it.sourceOutUs })
        assertTrue(out.clips.all { it.originalAudioPolicy == "keep" })
        assertFalse(base.clips[0].motionKeyframes.isNotEmpty()) // input untouched
    }
    @Test fun zoomIsBoundedAndWindowStaysInsideTheFrame() {
        for (m in listOf(0.0, 0.3, 0.45, 1.0)) {
            val keys = MotionPlanner.plan(MotionPlanner.Move.PUSH_IN, 4_000_000, m, 2_000_000, listOf(Focus(2_000_000, 0.95, 0.05)))
            keys.forEach { k -> assertTrue(k.scale in 1.0..MotionPlanner.MAX_SCALE + 1e-9)
                val w = Pose(k.centerX, k.centerY, k.scale).window(); assertTrue(w.left >= -1e-9 && w.top >= -1e-9 && w.right <= 1 + 1e-9 && w.bottom <= 1 + 1e-9) }
        }
        assertTrue(MotionPlanner.plan(MotionPlanner.Move.PUSH_IN, 4_000_000, 0.0).isEmpty())
    }
    @Test fun panIsRateLimited() {
        val keys = MotionPlanner.plan(MotionPlanner.Move.PUSH_IN, 4_000_000, 1.0, 2_000_000, listOf(Focus(0, 0.1, 0.5), Focus(2_000_000, 0.9, 0.5), Focus(4_000_000, 0.1, 0.5)))
        for (i in 1 until keys.size) { val dt = (keys[i].outputOffsetUs - keys[i - 1].outputOffsetUs) / 1e6
            assertTrue(kotlin.math.abs(keys[i].centerX - keys[i - 1].centerX) <= MotionPlanner.MAX_PAN_PER_SECOND * dt + 1e-9) }
    }
    @Test fun hudSafeZoneIsNeverCropped() {
        val safe = SafeZone(listOf(Protected(0.30, 0.30, 0.70, 0.70))) // wide centre HUD: max zoom 1/0.4 = 2.5, capped by 1.35
        val hud = SafeZone(listOf(Protected(0.0, 0.0, 0.3, 0.3), Protected(0.7, 0.7, 1.0, 1.0))) // opposite corners: no zoom
        assertEquals(1.0, MotionPlanner.targetScale(1.0, hud), 1e-9)
        assertTrue(MotionPlanner.plan(MotionPlanner.Move.PUSH_IN, 4_000_000, 1.0, 2_000_000, emptyList(), hud).isEmpty())
        val keys = MotionPlanner.plan(MotionPlanner.Move.PUSH_IN, 4_000_000, 1.0, 2_000_000, listOf(Focus(2_000_000, 0.95, 0.95)), safe)
        keys.forEach { k -> val w = Pose(k.centerX, k.centerY, k.scale).window(); assertTrue(w.left <= 0.30 + 1e-9 && w.right >= 0.70 - 1e-9 && w.top <= 0.30 + 1e-9 && w.bottom >= 0.70 - 1e-9) }
        val corner = SafeZone(listOf(Protected(0.0, 0.0, 0.2, 0.2))) // HUD in a corner: zoom must still contain it
        assertEquals(1.0 / 0.2, corner.maxScale(), 1e-9)
        val c = MotionPlanner.plan(MotionPlanner.Move.PUSH_IN, 4_000_000, 1.0, 2_000_000, listOf(Focus(2_000_000, 0.9, 0.9)), corner)
        c.forEach { k -> val w = Pose(k.centerX, k.centerY, k.scale).window(); assertTrue(w.left <= 1e-9 && w.top <= 1e-9) }
    }
    @Test fun shortClipsStayStillAndMovesAlternate() {
        assertEquals(MotionPlanner.Move.STILL, MotionPlanner.chooseMove(0, 500_000, true, 0.45))
        assertEquals(MotionPlanner.Move.STILL, MotionPlanner.chooseMove(0, 5_000_000, true, 0.0))
        assertEquals(MotionPlanner.Move.PUSH_IN, MotionPlanner.chooseMove(0, 5_000_000, false, 0.45))
        assertEquals(MotionPlanner.Move.PULL_OUT, MotionPlanner.chooseMove(1, 5_000_000, false, 0.45))
        val out = MotionDirector.apply(plan())
        assertTrue(out.clips[2].motionKeyframes.isEmpty())
    }
    @Test fun speedRampCoversClipStaysInRangeAndKeepsTheTimelineGapFree() {
        val segs = SpeedRampPlanner.plan(4_000_000, 2_000_000, 0.65)
        assertEquals(0L, segs.first().sourceOffsetStartUs); assertEquals(4_000_000L, segs.last().sourceOffsetEndUs)
        for (i in 1 until segs.size) assertEquals(segs[i - 1].sourceOffsetEndUs, segs[i].sourceOffsetStartUs)
        assertTrue(segs.all { it.rate in SpeedRampPlanner.MIN_RATE..1.0 })
        assertTrue(segs.minOf { it.rate } < 1.0)
        val out = MotionDirector.apply(plan(events = listOf(kill(4_000_000))))
        assertTrue(EdlValidator.validate(out).isEmpty())
        assertTrue(out.clips[0].speedSegments.isNotEmpty() && out.clips[1].outputStartUs > 4_000_000L)
        assertTrue(SpeedRampPlanner.plan(4_000_000, 2_000_000, 0.0).isEmpty()); assertTrue(SpeedRampPlanner.plan(1_000_000, 500_000, 0.65).isEmpty())
    }
    @Test fun speedRampIsSkippedWhenSoundCuesWouldShift() {
        val cue = SfxCue("c", "content://sfx", 1_000_000, 0.0, "r")
        val out = MotionDirector.apply(plan(events = listOf(kill(4_000_000)), sfx = listOf(cue)))
        assertTrue(out.clips.all { it.speedSegments.isEmpty() }); assertTrue(out.diagnostics.containsKey("motion.speed")); assertTrue(EdlValidator.validate(out).isEmpty())
    }
    @Test fun previewAndExportShareOnePoseFunction() {
        val keys = listOf(MotionKeyframe(0, 0.5, 0.5, 1.0), MotionKeyframe(1_000_000, 0.6, 0.5, 1.2))
        assertEquals(1.0, MotionPath.poseAt(keys, -5).scale, 1e-9); assertEquals(1.2, MotionPath.poseAt(keys, 9_000_000).scale, 1e-9)
        assertEquals(1.1, MotionPath.poseAt(keys, 500_000).scale, 1e-9) // smoothstep is 0.5 at the middle
        assertEquals(MotionPath.STILL, MotionPath.poseAt(emptyList(), 100))
        val a = MotionPath.poseAt(keys, 333_333); val b = MotionPath.poseAt(keys, 333_333); assertEquals(a, b)
    }
    @Test fun deterministic() { val e = listOf(kill(4_000_000)); assertEquals(MotionDirector.apply(plan(events = e)), MotionDirector.apply(plan(events = e))) }
}
