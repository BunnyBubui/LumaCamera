package th.ogay.luma.autoedit.directors

import org.junit.Assert.*
import org.junit.Test
import th.ogay.luma.autoedit.core.*

class GamingContextFusionTest {
    private fun possible(id: String = "hud-1", asset: String = "game") =
        AnalysisEvent(id, asset, 4_800_000, 6_000_000, AnalysisEventType.POSSIBLE_KILL,
            0.60, 0.80, "hud-fixture",
            listOf(EventEvidence("hud", 5_200_000, 0.85, "on-screen result")),
            "fps-profile")

    private fun loud(asset: String = "game", first: Long = 5_100_000) =
        AnalysisEvent("sound-" + asset, asset, first, first + 200_000,
            AnalysisEventType.HIGH_ACTIVITY, 0.80, 0.75, "audio-fixture",
            listOf(EventEvidence("audio_onset", first, 0.75, "sudden loudness")))

    @Test fun nearbyAudioIncreasesEditorialImportanceButCannotConfirmKill() {
        val initial = possible()
        val enhanced = GamingContextFusion.enrich(listOf(initial, loud())).first()
        assertEquals(AnalysisEventType.POSSIBLE_KILL, enhanced.type)
        assertEquals(initial.confidence, enhanced.confidence, 0.0)
        assertTrue(enhanced.importance > initial.importance)
        assertTrue(enhanced.importance - initial.importance <= 0.10)
        assertTrue(enhanced.evidence.any { it.kind == "audio_context" })
        val plan = GamingHighlightDirector.propose(
            listOf(MediaAssetRef("game", "content://source", 10_000_000, 1920, 1080)),
            listOf(initial, loud()))
        assertEquals(0, plan.selected.single().confirmedKills)
        assertTrue(plan.selected.single().score > 0)
    }

    @Test fun audioOnlyCannotGenerateVideoHighlight() {
        val result = GamingHighlightDirector.propose(
            listOf(MediaAssetRef("game", "content://source", 10_000_000, 1920, 1080)),
            listOf(loud()))
        assertNull(result.plan)
        assertTrue(result.selected.isEmpty())
    }

    @Test fun otherAssetAudioDoesNotCorrelateOrUpgradeEvidence() {
        val initial = possible()
        val changed = GamingContextFusion.enrich(listOf(initial, loud(asset = "other")))
        assertEquals(initial, changed.first())
    }

    @Test fun unconnectedAudioDoesNotIncreaseScore() {
        val initial = possible()
        val changed = GamingContextFusion.enrich(listOf(initial, loud(first = 8_000_000)))
        assertEquals(initial, changed.first())
    }

    @Test fun enrichmentIsIdempotentAndInputOrderSafe() {
        val x = possible()
        val sound = loud()
        val first = GamingContextFusion.enrich(listOf(x, sound))
        val again = GamingContextFusion.enrich(first)
        assertEquals(first, again)
        val reversed = GamingContextFusion.enrich(listOf(sound, x))
        assertEquals(first.first(), reversed.last())
    }
}
