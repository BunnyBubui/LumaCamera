package th.ogay.luma.autoedit.directors

import org.junit.Assert.*
import org.junit.Test
import th.ogay.luma.autoedit.core.*

class GamingHighlightDirectorTest {
    private val video = MediaAssetRef("game", "content://test/game.mp4", 45_000_000, 1920, 1080)
    private val video2 = MediaAssetRef("game2", "content://test/game2.mp4", 45_000_000, 1920, 1080)

    private fun kill(
        id: String, second: Long, asset: String = "game",
        kind: String = "hud", confidence: Double = 0.94,
        evidenceConfidence: Double = 0.96, profile: String? = "fps"
    ) = AnalysisEvent(
        id, asset, second * 1_000_000, second * 1_000_000 + 500_000,
        AnalysisEventType.CONFIRMED_KILL, 0.85, confidence,
        "hud-detector-test", listOf(EventEvidence(
            kind, second * 1_000_000 + 100_000,
            evidenceConfidence, "visible kill feed " + id
        )), gameProfileId = profile
    )

    @Test fun multipleConfirmedKillsAreOneUninterruptedHighlight() {
        val events = listOf(kill("a", 5), kill("b", 9), kill("c", 12))
        val result = GamingHighlightDirector.propose(listOf(video), events)
        assertEquals(1, result.selected.size)
        assertEquals(3, result.selected.first().confirmedKills)
        assertTrue(result.selected.first().reason.contains("3 confirmed kills"))
        assertEquals(3, result.selected.first().eventIds.size)
        assertTrue(result.plan != null)
        assertEquals(1, result.plan!!.clips.size)
        assertTrue(result.plan!!.clips.first().sourceInUs <= 5_000_000)
        assertTrue(result.plan!!.clips.first().sourceOutUs >= 12_500_000)
        assertEquals("keep", result.plan!!.clips.first().originalAudioPolicy)
        assertTrue(EdlValidator.validate(result.plan!!).isEmpty())
    }

    @Test fun audioSpikeAloneNeverCreatesConfirmedKill() {
        val suspicious = kill("fake", 10, kind = "audio_peak")
        val result = GamingHighlightDirector.propose(listOf(video), listOf(suspicious))
        assertNull(result.plan)
        assertTrue(result.selected.isEmpty())
        assertEquals("insufficient_visual_evidence_or_confidence", result.rejected.single().reason)
    }

    @Test fun weakHudConfidenceIsRejected() {
        val result = GamingHighlightDirector.propose(listOf(video), listOf(
            kill("weak", 10, evidenceConfidence = 0.4)))
        assertTrue(result.selected.isEmpty())
        assertNull(result.plan)
    }

    @Test fun unverifiedSoundBurstAndCameraMotionAreNotHighlights() {
        val sound = AnalysisEvent("sfx", "game", 4_000_000, 4_500_000,
            AnalysisEventType.HIGH_ACTIVITY, 0.99, 0.99, "audio-v1",
            listOf(EventEvidence("audio_peak", 4_200_000, 1.0)))
        val scene = sound.copy(id = "scene", type = AnalysisEventType.SCENE_CHANGE)
        val result = GamingHighlightDirector.propose(listOf(video), listOf(sound, scene))
        assertNull(result.plan)
        assertEquals(2, result.rejected.size)
    }

    @Test fun distinctRapidKillsAreNotOverDeduplicated() {
        val k1 = kill("first", 10)
        val k2 = kill("second", 10).copy(
            sourceStartUs = 10_250_000, sourceEndUs = 10_600_000,
            evidence = listOf(EventEvidence("hud", 10_300_000, 0.94, "second elimination"))
        )
        val result = GamingHighlightDirector.propose(listOf(video), listOf(k1, k2))
        assertEquals(2, result.selected.single().confirmedKills)
        assertTrue(result.rejected.isEmpty())
    }

    @Test fun noMultiKillAcrossDifferentFiles() {
        val result = GamingHighlightDirector.propose(listOf(video, video2),
            listOf(kill("a", 10), kill("b", 10, asset = "game2")))
        assertEquals(2, result.selected.size)
        assertTrue(result.selected.all { it.confirmedKills == 1 })
    }

    @Test fun separateGameProfilesCannotBeFalselyReportedAsOneStreak() {
        val events = listOf(
            kill("a", 10, profile = "first-game"),
            kill("b", 10, profile = "different-game")
        )
        val result = GamingHighlightDirector.propose(listOf(video), events)
        assertEquals(1, result.selected.size)
        assertEquals(1, result.selected.single().confirmedKills)
    }

    @Test fun identicalVisualEventRetriesAreDeduplicated() {
        val original = kill("one", 10)
        val retry = original.copy(id = "retried")
        val result = GamingHighlightDirector.propose(listOf(video), listOf(original, retry))
        assertEquals(1, result.selected.single().confirmedKills)
        assertTrue(result.rejected.any { it.reason == "duplicate_visual_observation" })
    }

    @Test fun distantKillsAreSeparateHighlights() {
        val result = GamingHighlightDirector.propose(listOf(video),
            listOf(kill("a", 2), kill("b", 22)))
        assertEquals(2, result.selected.size)
    }

    @Test fun preAndPostRollAreClampedToSourceBoundaries() {
        val nearStart = kill("begin", 0)
        val nearEnd = kill("end", 44).copy(sourceEndUs = 44_900_000)
        val result = GamingHighlightDirector.propose(listOf(video),
            listOf(nearStart, nearEnd))
        assertTrue(result.selected.any { it.startUs == 0L })
        assertTrue(result.selected.any { it.endUs == video.durationUs })
    }

    @Test fun budgetNeverTruncatesContinuousKillStreak() {
        val events = listOf(kill("a", 5), kill("b", 9), kill("c", 13))
        val result = GamingHighlightDirector.propose(
            listOf(video), events,
            options = GamingDirectorOptions(maxOutputUsOverride = 1_000_000))
        assertEquals(1, result.selected.size)
        assertEquals(3, result.selected.first().confirmedKills)
        assertTrue(result.selected.first().durationUs > 1_000_000)
    }

    @Test fun energyChangesEditorialPacingButNotConfidence() {
        val event = kill("a", 15)
        val slow = GamingHighlightDirector.propose(listOf(video), listOf(event),
            preset = AutoEditPreset(editingEnergy = 0.2))
        val energetic = GamingHighlightDirector.propose(listOf(video), listOf(event),
            preset = AutoEditPreset(editingEnergy = 0.9))
        assertTrue(slow.selected.single().durationUs > energetic.selected.single().durationUs)
        assertEquals(slow.selected.single().confirmedKills,
            energetic.selected.single().confirmedKills)
    }

    @Test fun noSourceMutationAndDeterministicOutput() {
        val events = listOf(kill("a", 5), kill("b", 14))
        val first = GamingHighlightDirector.propose(listOf(video), events, generationSeed = 19)
        val again = GamingHighlightDirector.propose(listOf(video), events.reversed(), generationSeed = 19)
        assertEquals(first.selected, again.selected)
        assertEquals(first.plan, again.plan)
        assertEquals("content://test/game.mp4", first.plan!!.sourceAssets.single().uri)
        assertTrue(first.plan!!.sfxCues.isEmpty())
        assertTrue(first.plan!!.clips.all { it.motionKeyframes.isEmpty() })
    }

    @Test fun lowConfidencePossibleKillCannotBecomeConfirmed() {
        val possible = kill("weak", 10).copy(type = AnalysisEventType.POSSIBLE_KILL)
        val result = GamingHighlightDirector.propose(listOf(video), listOf(possible))
        assertEquals(0, result.selected.single().confirmedKills)
        assertTrue(result.selected.single().reason.contains("requires review"))
    }

    @Test fun missingAssetsAreRejectedWithoutInventingClips() {
        val result = GamingHighlightDirector.propose(listOf(video), listOf(
            kill("wrong", 10, asset = "missing")))
        assertNull(result.plan)
        assertEquals("unknown_asset", result.rejected.single().reason)
    }
}
