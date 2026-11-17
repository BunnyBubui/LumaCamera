package th.ogay.luma.autoedit.effects

import org.junit.Assert.*
import org.junit.Test
import th.ogay.luma.autoedit.core.*

class AutoSoundDirectorTest {
    private val asset = MediaAssetRef("g", "content://sample/game.mp4", 20_000_000, 1920, 1080)
    private val original = AutoEditPreset.gamingDefault()

    private fun event(
        id: String = "kill-1", at: Long = 5_000_000,
        kind: String = "hud",
        type: AnalysisEventType = AnalysisEventType.CONFIRMED_KILL
    ) = AnalysisEvent(
        id, "g", at, at + 200_000, type, 0.9, 0.95, "fixture",
        listOf(EventEvidence(kind, at + 50_000, 0.93, "recognized result"))
    )

    private fun plan(events: List<AnalysisEvent> = listOf(event()),
                     clip: ClipDecision = ClipDecision(
                         "clip1", "g", 2_000_000, 10_000_000, 0, "highlight", 0.8
                     )) = EditDecisionList(
        planId = "p1", presetSnapshot = original,
        analysisVersion = "fixture", sourceAssets = listOf(asset),
        clips = listOf(clip), analysisEvents = events
    )

    private fun licensed(vararg roles: SoundRole): SoundLibrary =
        SoundLibrary(roles.associateWith { LicensedSfx("content://sfx/" + it.name, true) })

    @Test fun confirmedKillAddsQuietSfxCueAndPreservesOriginalAudio() {
        val output = AutoSoundDirector.propose(plan(), licensed(SoundRole.IMPACT))
        assertEquals(1, output.decisions.size)
        assertEquals(1, output.plan.sfxCues.size)
        assertEquals(3_000_000, output.plan.sfxCues[0].outputStartUs)
        assertTrue(output.plan.sfxCues[0].gainDb < -8.0)
        assertEquals(original, output.plan.presetSnapshot)
        assertEquals("keep", output.plan.clips[0].originalAudioPolicy)
        assertTrue(EdlValidator.validate(output.plan).isEmpty())
    }

    @Test fun unlicensedAndMissingSfxNeverProduceInvalidUri() {
        val absent = AutoSoundDirector.propose(plan(), SoundLibrary())
        val unlicensed = AutoSoundDirector.propose(plan(), SoundLibrary(mapOf(
            SoundRole.IMPACT to LicensedSfx("content://sfx/impact", false))))
        assertEquals(0, absent.plan.sfxCues.size)
        assertEquals(0, unlicensed.plan.sfxCues.size)
        assertTrue(unlicensed.skipped.any { it.reason == "missing_or_unlicensed_sfx" })
    }

    @Test fun unverifiedAudioSpikeDoesNotCreateImpact() {
        val suspicious = event(kind = "audio_peak")
        val result = AutoSoundDirector.propose(plan(listOf(suspicious)),
            licensed(SoundRole.IMPACT))
        assertTrue(result.decisions.isEmpty())
        assertTrue(result.plan.sfxCues.isEmpty())
    }

    @Test fun beatSnapOnlyOccursWhenCloseEnough() {
        val close = AutoSoundDirector.propose(plan(), licensed(SoundRole.IMPACT),
            musicBeatsOutputUs = listOf(3_060_000))
        assertTrue(close.decisions.single().beatAligned)
        assertEquals(3_060_000, close.decisions.single().cueOutputUs)
        val distant = AutoSoundDirector.propose(plan(), licensed(SoundRole.IMPACT),
            musicBeatsOutputUs = listOf(3_500_000))
        assertFalse(distant.decisions.single().beatAligned)
        assertEquals(3_000_000, distant.decisions.single().cueOutputUs)
    }

    @Test fun speedChangesUseOutputClockForSoundCue() {
        val fast = ClipDecision("fast", "g", 2_000_000, 10_000_000, 0, "highlight", 0.9,
            speedSegments = listOf(SpeedSegment(0, 8_000_000, 2.0)))
        val result = AutoSoundDirector.propose(plan(clip = fast),
            licensed(SoundRole.IMPACT))
        assertEquals(1_500_000, result.decisions.single().cueOutputUs)
    }

    @Test fun voiceRangesDuckMusicAndQuietSfxButNeverMuteGameplay() {
        val voice = SpeechRange("g", 4_500_000, 6_000_000)
        val result = AutoSoundDirector.propose(
            plan(), licensed(SoundRole.IMPACT),
            additionalSpeechRanges = listOf(voice)
        )
        assertEquals(1, result.musicDuckRanges.size)
        val duck = result.musicDuckRanges.single()
        assertEquals(2_500_000, duck.outputStartUs)
        assertEquals(4_000_000, duck.outputEndUs)
        assertEquals(0.3, duck.remainingMusicGain, 0.0)
        assertTrue(result.decisions.single().gainDb < -15.0)
        assertEquals("keep", result.plan.clips.single().originalAudioPolicy)
    }

    @Test fun rapidCueCollisionDoesNotStackHugeImpactSounds() {
        val second = event(id = "kill-2", at = 5_150_000)
        val result = AutoSoundDirector.propose(
            plan(events = listOf(event(), second)), licensed(SoundRole.IMPACT))
        assertEquals(1, result.plan.sfxCues.size)
        assertTrue(result.skipped.any { it.reason == "same_role_cooldown" })
    }

    @Test fun eventsOutsideRetainedClipNeverGenerateCues() {
        val result = AutoSoundDirector.propose(
            plan(events = listOf(event(at = 15_000_000))), licensed(SoundRole.IMPACT))
        assertTrue(result.plan.sfxCues.isEmpty())
    }

    @Test fun duplicatedInputOrderHasDeterministicOutput() {
        val a = event(id = "early", at = 4_000_000)
        val b = event(id = "later", at = 8_000_000, type = AnalysisEventType.VICTORY)
        val first = AutoSoundDirector.propose(
            plan(events = listOf(a, b)), licensed(SoundRole.IMPACT, SoundRole.VICTORY))
        val second = AutoSoundDirector.propose(
            plan(events = listOf(b, a)), licensed(SoundRole.IMPACT, SoundRole.VICTORY))
        assertEquals(first.plan.sfxCues, second.plan.sfxCues)
        assertEquals(first.decisions, second.decisions)
    }

    @Test fun sourceAudioRemovalIsNeverAnAllowedSoundStrategy() {
        val unsafe = plan().copy(
            presetSnapshot = AutoEditPreset(preserveSourceAudio = false))
        try {
            AutoSoundDirector.propose(unsafe, licensed(SoundRole.IMPACT))
            fail("Sound Director should reject original audio removal")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }
}
