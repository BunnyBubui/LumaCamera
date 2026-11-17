package th.ogay.luma.autoedit.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AutoEditProjectAdapterTest {
    private fun sample(): EditDecisionList = EditDecisionList(
        planId = "example-gaming", presetSnapshot = AutoEditPreset.gamingDefault(),
        analysisVersion = "baseline-v1",
        sourceAssets = listOf(MediaAssetRef("asset1", "content://recording/1",
            8_000_000, 1920, 1080)),
        clips = listOf(ClipDecision("clip1", "asset1", 1_000_000, 3_000_000,
            0, "confirmed gameplay action", 0.9,
            speedSegments = listOf(SpeedSegment(0, 1_000_000, 0.5),
                SpeedSegment(1_000_000, 2_000_000, 2.0)),
            motionKeyframes = listOf(MotionKeyframe(0, 0.5, 0.5, 1.0)))),
        analysisEvents = listOf(AnalysisEvent("e1", "asset1", 1_200_000, 1_500_000,
            AnalysisEventType.CONFIRMED_KILL, 0.9, 0.94, "hud-v1",
            listOf(EventEvidence("hud", 1_300_000, 0.96, "visible kill feed")),
            gameProfileId = "test-fps")),
        sfxCues = listOf(SfxCue("s1", "content://sfx/impact", 500_000, -9.0, "hit")),
        diagnostics = mapOf("quality" to "draft"), generationSeed = 1234
    )

    @Test fun jsonRoundtripIsDeterministicAndLossless() {
        val original = sample()
        val encoded = AutoEditProjectAdapter.encode(original)
        val decoded = AutoEditProjectAdapter.decode(encoded)
        assertEquals(original, decoded)
        assertEquals(encoded, AutoEditProjectAdapter.encode(decoded))
        assertTrue(decoded.presetSnapshot.preserveSourceAudio)
    }

    @Test fun unsupportedFutureSchemaFailsWithoutModifyingExistingProjects() {
        val invalid = JSONObject(AutoEditProjectAdapter.encode(sample()))
            .put("schemaVersion", 999).toString()
        try {
            AutoEditProjectAdapter.decode(invalid)
            fail("Expected unsupported schema rejection")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    @Test fun corruptOrUntrustedPlanIsRejected() {
        val invalid = JSONObject(AutoEditProjectAdapter.encode(sample()))
            .put("sourceAssets", org.json.JSONArray()).toString()
        try {
            AutoEditProjectAdapter.decode(invalid)
            fail("Expected missing source rejection")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }
}
