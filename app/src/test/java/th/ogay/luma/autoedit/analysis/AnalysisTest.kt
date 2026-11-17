package th.ogay.luma.autoedit.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import th.ogay.luma.autoedit.core.AnalysisEventType

/** Annotated fixtures: every series is built by hand so the expected events are known. */
private class Fake(
    override val durationUs: Long, private val diffs: FloatArray? = null, private val loud: FloatArray? = null,
    private val speech: List<LongRange>? = null, override val rotationDegrees: Int = 0,
    override val width: Int = 1920, override val height: Int = 1080
) : AnalysisSource {
    override val assetId = "a"
    override val frameStepUs = 250_000L; override val loudnessStepUs = 50_000L
    override fun frameDiffs() = diffs; override fun loudness() = loud; override fun speechSpans() = speech; override fun frameAt(us: Long): Frame? = null
}

class AnalysisTest {
    private fun flat(n: Int, v: Float) = FloatArray(n) { v }
    private val all = listOf(SceneAnalyzer(), AudioEventAnalyzer(), HudAnalyzer())

    @Test fun staticSceneHasNoEvents() {
        val r = AnalysisPipeline(all).run(Fake(20_000_000, flat(80, 0.01f), flat(400, 0.02f)))
        assertTrue(r.events.isEmpty())
    }
    @Test fun realCutIsASceneChangeButAFlashIsNot() {
        val d = flat(80, 0.01f); d[20] = 0.6f; d[21] = 0.6f; d[22] = 0.01f // stays changed? diffs are per step, a cut is one jump
        d[50] = 0.7f // single spike: flash
        val ev = AnalysisPipeline(listOf(SceneAnalyzer())).run(Fake(20_000_000, d)).events
        assertTrue(ev.any { it.type == AnalysisEventType.SCENE_CHANGE && it.sourceStartUs == 5_000_000L })
        val flash = ev.first { it.sourceStartUs == 12_500_000L }
        assertEquals(AnalysisEventType.HIGH_ACTIVITY, flash.type); assertTrue(flash.confidence < 0.5)
    }
    @Test fun fastGameMovementWithoutJumpsIsNotACut() {
        val d = FloatArray(80) { 0.08f + (it % 3) * 0.01f }
        assertTrue(AnalysisPipeline(listOf(SceneAnalyzer())).run(Fake(20_000_000, d)).events.none { it.type == AnalysisEventType.SCENE_CHANGE })
    }
    @Test fun silenceMakesNoAudioEvents() {
        assertTrue(AnalysisPipeline(listOf(AudioEventAnalyzer())).run(Fake(20_000_000, loud = flat(400, 0.01f))).events.isEmpty())
    }
    @Test fun fakeKillLikeSoundIsOnlyHighActivityNeverAKill() {
        val l = flat(400, 0.05f); for (i in 100..130) l[i] = 0.95f // a loud burst
        val ev = AnalysisPipeline(all).run(Fake(20_000_000, loud = l)).events
        assertTrue(ev.isNotEmpty())
        assertTrue(ev.all { it.type == AnalysisEventType.HIGH_ACTIVITY })
        assertTrue(ev.none { it.type == AnalysisEventType.POSSIBLE_KILL || it.type == AnalysisEventType.CONFIRMED_KILL })
    }
    @Test fun musicWithSteadyLoudnessIsNotActivity() {
        val l = FloatArray(400) { 0.5f + (if (it % 10 == 0) 0.02f else 0f) } // loud but constant: median is high so bar is above it
        val ev = AnalysisPipeline(listOf(AudioEventAnalyzer())).run(Fake(20_000_000, loud = l)).events
        assertTrue(ev.none { it.id.startsWith("loud-") })
    }
    @Test fun voiceSpansBecomeSpeechEvents() {
        val ev = AnalysisPipeline(listOf(AudioEventAnalyzer())).run(Fake(20_000_000, speech = listOf(2_000_000L..5_000_000L))).events
        assertEquals(1, ev.size); assertEquals(AnalysisEventType.SPEECH, ev[0].type); assertEquals(2_000_000L, ev[0].sourceStartUs)
    }
    @Test fun absentHudDetectorAddsNothingAndEveryHudEventIsPossibleNotConfirmed() {
        assertTrue(AnalysisPipeline(listOf(HudAnalyzer())).run(Fake(10_000_000)).events.isEmpty())
        val det = object : HudDetector { override val profileId = "game-x"; override val available = true
            override fun hits(source: AnalysisSource, check: () -> Unit) = listOf(HudHit(4_000_000, 0.8, "banner"), HudHit(99_000_000, 0.9, "outside")) }
        val ev = AnalysisPipeline(listOf(HudAnalyzer(det))).run(Fake(10_000_000)).events
        assertEquals(1, ev.size); assertEquals(AnalysisEventType.POSSIBLE_KILL, ev[0].type); assertEquals("hud", ev[0].evidence[0].kind); assertEquals("game-x", ev[0].gameProfileId)
    }
    @Test fun rotatedSourceReportsDisplaySize() {
        val s = Fake(1_000_000, rotationDegrees = 90, width = 1920, height = 1080)
        assertEquals(1080, s.displayWidth); assertEquals(1920, s.displayHeight)
    }
    @Test fun cancelStopsThePipeline() {
        val t = CancelToken(); t.cancel()
        try { AnalysisPipeline(all).run(Fake(1_000_000, flat(8, 0f)), t); fail() } catch (e: AnalysisCancelled) { }
    }
    @Test fun oneFailingAnalyzerDoesNotLoseTheOthers() {
        val bad = object : Analyzer { override val name = "bad"; override val version = "1"
            override fun analyze(source: AnalysisSource, budget: AnalysisBudget, check: () -> Unit): List<th.ogay.luma.autoedit.core.AnalysisEvent> = throw IllegalStateException("boom") }
        val r = AnalysisPipeline(listOf(bad, AudioEventAnalyzer())).run(Fake(10_000_000, speech = listOf(0L..1_000_000L)))
        assertEquals(1, r.events.size); assertTrue(r.diagnostics["bad"]!!.startsWith("failed"))
    }
    @Test fun budgetCapsEventsAndRefusesTooLongSources() {
        val l = FloatArray(4000) { if (it % 20 < 3) 0.9f else 0.05f }
        val r = AnalysisPipeline(listOf(AudioEventAnalyzer()), AnalysisBudget(maxEvents = 5)).run(Fake(200_000_000, loud = l))
        assertEquals(5, r.events.size); assertEquals(r.events.sortedBy { it.sourceStartUs }, r.events)
        val long = AnalysisPipeline(all, AnalysisBudget(maxDurationUs = 1_000)).run(Fake(2_000))
        assertTrue(long.events.isEmpty() && long.diagnostics.containsKey("error"))
    }
    @Test fun eventsStayInsideTheSourceAndAreDeterministic() {
        val l = flat(400, 0.05f); for (i in 390..399) l[i] = 0.95f
        val a = AnalysisPipeline(all).run(Fake(20_000_000, flat(80, 0.01f), l)); val b = AnalysisPipeline(all).run(Fake(20_000_000, flat(80, 0.01f), l))
        assertEquals(a.events, b.events); assertTrue(a.events.all { it.sourceEndUs <= 20_000_000L })
    }
}
