package th.ogay.luma.autoedit

import org.junit.Assert.*
import org.junit.Test
import th.ogay.luma.autoedit.core.*
import th.ogay.luma.autoedit.directors.*
import th.ogay.luma.autoedit.effects.*

/**
 * Contract-level E2E with human-supplied synthetic events.
 * Does NOT test real video recognition, preview audio DSP or export.
 */
class AutoEditEndToEndContractTest {
    private val asset = MediaAssetRef(
        id = "match1", uri = "content://game/match1",
        durationUs = 60_000_000, videoWidth = 1920, videoHeight = 1080
    )

    private fun hudKill(id: String, atUs: Long) = AnalysisEvent(
        id = id, assetId = "match1", sourceStartUs = atUs,
        sourceEndUs = atUs + 300_000, type = AnalysisEventType.CONFIRMED_KILL,
        importance = 0.86, confidence = 0.94, detectorVersion = "unit-test-hud",
        evidence = listOf(EventEvidence(
            "hud", atUs + 100_000, 0.94, "verified fixture result"
        )),
        gameProfileId = "fixture-fps"
    )

    @Test fun syntheticKillStreakToEditableTimelineToLicensedSoundCues() {
        val input = listOf(
            hudKill("kill1", 10_000_000),
            hudKill("kill2", 13_500_000),
            hudKill("kill3", 17_000_000)
        )
        val preset = AutoEditPreset.gamingDefault()
        val game = GamingHighlightDirector.propose(
            assets = listOf(asset), events = input, preset = preset, generationSeed = 2026
        )
        assertEquals(1, game.selected.size)
        assertEquals(3, game.selected.single().confirmedKills)
        assertNotNull(game.plan)
        assertEquals(1, game.plan!!.clips.size)
        assertTrue(EdlValidator.validate(game.plan!!).isEmpty())
        assertTrue(game.plan!!.clips.all { it.originalAudioPolicy == "keep" })

        val sounds = AutoSoundDirector.propose(
            plan = game.plan!!,
            library = SoundLibrary(mapOf(
                SoundRole.IMPACT to LicensedSfx("content://original-sfx/impact", true)
            ))
        )
        assertEquals(3, sounds.decisions.size)
        assertEquals(3, sounds.plan.sfxCues.size)
        assertTrue(EdlValidator.validate(sounds.plan).isEmpty())
        assertEquals(game.plan!!.sourceAssets, sounds.plan.sourceAssets)
        assertEquals(game.plan!!.clips, sounds.plan.clips)
        assertTrue(sounds.plan.sfxCues.all { it.gainDb <= -8 })
        assertEquals(preset, sounds.plan.presetSnapshot)
    }

    @Test fun syntheticAudioWithoutHudProducesNoClipOrSound() {
        val loud = AnalysisEvent(
            id = "loud", assetId = "match1", sourceStartUs = 5_000_000,
            sourceEndUs = 6_000_000, type = AnalysisEventType.HIGH_ACTIVITY,
            importance = 0.99, confidence = 0.99, detectorVersion = "unit-test-audio",
            evidence = listOf(EventEvidence("audio_peak", 5_500_000, 0.99))
        )
        val game = GamingHighlightDirector.propose(
            listOf(asset), listOf(loud), generationSeed = 2026
        )
        assertNull(game.plan)
        assertTrue(game.selected.isEmpty())
    }

    @Test fun syntheticPossibleHudIsNeverSilentlyPromotedToConfirmedKill() {
        val possible = hudKill("possible", 10_000_000)
            .copy(type = AnalysisEventType.POSSIBLE_KILL)
        val game = GamingHighlightDirector.propose(
            listOf(asset), listOf(possible)
        )
        assertEquals(0, game.selected.single().confirmedKills)
        assertEquals(AnalysisEventType.POSSIBLE_KILL,
            game.plan!!.analysisEvents.single().type)
        val sounds = AutoSoundDirector.propose(
            game.plan!!,
            SoundLibrary(mapOf(
                SoundRole.IMPACT to LicensedSfx("content://original-sfx/impact", true)
            ))
        )
        assertTrue(sounds.plan.sfxCues.isEmpty())
        assertTrue(sounds.decisions.isEmpty())
    }
}
