package th.ogay.luma.autoedit.effects

import th.ogay.luma.autoedit.core.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Phase 5 plans *metadata* for audio; the existing Media3 exporter must later render it. */
enum class SoundRole { IMPACT, STREAK, RISER, VICTORY }

data class LicensedSfx(
    val uri: String,
    val licensedForUse: Boolean,
    val peakTrimDb: Double = 0.0
) {
    init {
        require(uri.isNotBlank())
        require(peakTrimDb.isFinite() && peakTrimDb in -24.0..6.0)
    }
}

/** Missing/unlicensed sound assets NEVER cause a fabricated or broken cue. */
data class SoundLibrary(val assets: Map<SoundRole, LicensedSfx> = emptyMap())

data class SpeechRange(
    val sourceAssetId: String,
    val sourceStartUs: Long,
    val sourceEndUs: Long
) {
    init { require(sourceStartUs >= 0 && sourceEndUs > sourceStartUs) }
}

data class MusicDuckRange(
    val outputStartUs: Long,
    val outputEndUs: Long,
    val remainingMusicGain: Double = 0.3,
    val rampUs: Long = 300_000
)

data class SoundDecision(
    val eventId: String, val role: SoundRole,
    val sourceOutputUs: Long, val cueOutputUs: Long,
    val gainDb: Double, val beatAligned: Boolean
)

data class SkippedSoundDecision(val eventId: String, val reason: String)

data class SoundDirectorResult(
    val plan: EditDecisionList,
    val decisions: List<SoundDecision>,
    val skipped: List<SkippedSoundDecision>,
    val musicDuckRanges: List<MusicDuckRange>,
    val notes: List<String>
)

data class SoundDirectorOptions(
    val maxBeatShiftUs: Long = 80_000,
    val sameRoleCooldownUs: Long = 550_000,
    val totalCueCooldownUs: Long = 140_000,
    val thirtySecondWindowUs: Long = 30_000_000
) {
    init {
        require(maxBeatShiftUs in 0..200_000)
        require(sameRoleCooldownUs in 0..3_000_000)
        require(totalCueCooldownUs in 0..1_000_000)
        require(thirtySecondWindowUs in 5_000_000..120_000_000)
    }
}

object AutoSoundDirector {
    private val verificationKinds = setOf("hud", "game_metadata")
    private val contextualKinds = setOf("hud", "game_metadata", "ocr", "visual")

    private fun valid(e: AnalysisEvent, kinds: Set<String>): Boolean =
        e.evidence.any { it.kind.lowercase() in kinds && it.confidence >= 0.70 } &&
            e.confidence >= 0.68

    private fun roleFor(e: AnalysisEvent): SoundRole? = when (e.type) {
        AnalysisEventType.CONFIRMED_KILL ->
            if (valid(e, verificationKinds)) SoundRole.IMPACT else null
        AnalysisEventType.MULTIKILL ->
            if (valid(e, verificationKinds)) SoundRole.STREAK else null
        AnalysisEventType.CLUTCH ->
            if (valid(e, contextualKinds)) SoundRole.RISER else null
        AnalysisEventType.VICTORY ->
            if (valid(e, contextualKinds)) SoundRole.VICTORY else null
        else -> null
    }

    /**
     * Includes playback-speed mapping. An event outside the selected edit cannot
     * generate a cue. A source event can map once per selected repeat of a clip.
     */
    private fun outputTime(event: AnalysisEvent, clip: ClipDecision): Long? {
        if (event.assetId != clip.sourceAssetId ||
            event.sourceStartUs !in clip.sourceInUs until clip.sourceOutUs) return null
        val offset = event.sourceStartUs - clip.sourceInUs
        return clip.outputStartUs + clip.mapper().sourceToOutput(offset)
    }

    private fun nearestBeat(timeUs: Long, beats: List<Long>, limitUs: Long): Long? {
        if (beats.isEmpty()) return null
        val idx = beats.binarySearch(timeUs)
        if (idx >= 0) return beats[idx]
        val insertion = -idx - 1
        val near = listOfNotNull(beats.getOrNull(insertion - 1), beats.getOrNull(insertion))
            .minByOrNull { abs(it - timeUs) } ?: return null
        return if (abs(near - timeUs) <= limitUs) near else null
    }

    private fun sourceVoiceRanges(
        plan: EditDecisionList,
        additional: List<SpeechRange>
    ): List<SpeechRange> = additional + plan.analysisEvents
        .filter { it.type == AnalysisEventType.SPEECH }
        .map { SpeechRange(it.assetId, it.sourceStartUs, it.sourceEndUs) }

    private fun musicDuckRanges(plan: EditDecisionList, ranges: List<SpeechRange>): List<MusicDuckRange> {
        val out = mutableListOf<MusicDuckRange>()
        for (clip in plan.clips) {
            for (voice in ranges) {
                if (voice.sourceAssetId != clip.sourceAssetId) continue
                val start = max(clip.sourceInUs, voice.sourceStartUs)
                val end = min(clip.sourceOutUs, voice.sourceEndUs)
                if (end <= start) continue
                val warp = clip.mapper()
                out += MusicDuckRange(
                    clip.outputStartUs + warp.sourceToOutput(start - clip.sourceInUs),
                    clip.outputStartUs + warp.sourceToOutput(end - clip.sourceInUs)
                )
            }
        }
        val merged = mutableListOf<MusicDuckRange>()
        for (range in out.sortedBy { it.outputStartUs }) {
            val prev = merged.lastOrNull()
            if (prev != null && range.outputStartUs <= prev.outputEndUs + 150_000) {
                merged[merged.lastIndex] = prev.copy(outputEndUs = max(prev.outputEndUs, range.outputEndUs))
            } else merged += range
        }
        return merged
    }

    private fun baseGain(role: SoundRole, intensity: Double): Double {
        val db = when (role) {
            SoundRole.IMPACT -> -18.0 + 8.0 * intensity
            SoundRole.STREAK -> -21.0 + 8.0 * intensity
            SoundRole.RISER -> -25.0 + 9.0 * intensity
            SoundRole.VICTORY -> -20.0 + 7.0 * intensity
        }
        return db.coerceIn(-30.0, -8.0)
    }

    /**
     * Add SFX cues only from explicitly available, licensed audio assets.
     * Does NOT mute source/game/speech audio. Music duck ranges are metadata
     * that Phase 6/export integration can apply to background music only.
     */
    fun propose(
        plan: EditDecisionList,
        library: SoundLibrary,
        musicBeatsOutputUs: List<Long> = emptyList(),
        additionalSpeechRanges: List<SpeechRange> = emptyList(),
        options: SoundDirectorOptions = SoundDirectorOptions()
    ): SoundDirectorResult {
        require(plan.presetSnapshot.preserveSourceAudio) { "This director cannot mute original audio" }
        require(plan.clips.all { it.originalAudioPolicy == "keep" })
        require(EdlValidator.validate(plan).isEmpty()) { "Refusing invalid edit plan" }
        require(musicBeatsOutputUs.all { it >= 0 })
        val beats = musicBeatsOutputUs.distinct().sorted()
        val speech = musicDuckRanges(plan, sourceVoiceRanges(plan, additionalSpeechRanges))
        val maxPerWindow = 6 + (8 * plan.presetSnapshot.soundEffects).toInt()
        val maxOutputUs = plan.clips.maxOfOrNull { it.outputStartUs + it.mapper().outputDurationUs } ?: 0L
        val scheduled = mutableListOf<SoundDecision>()
        val skipped = mutableListOf<SkippedSoundDecision>()
        val addedCues = mutableListOf<SfxCue>()
        val events = plan.analysisEvents.sortedWith(compareBy({ it.assetId }, { it.sourceStartUs }, { it.id }))
        for (clip in plan.clips) {
            for (event in events) {
                val at = outputTime(event, clip) ?: continue
                val role = roleFor(event)
                if (role == null) {
                    skipped += SkippedSoundDecision(event.id, "unverified_or_unsupported_event")
                    continue
                }
                val asset = library.assets[role]
                if (asset == null || !asset.licensedForUse) {
                    skipped += SkippedSoundDecision(event.id, "missing_or_unlicensed_sfx")
                    continue
                }
                val aligned = nearestBeat(at, beats, options.maxBeatShiftUs)
                val cueTime = (aligned ?: at).coerceIn(0, maxOutputUs - 1)
                if (scheduled.any { it.role == role && abs(it.cueOutputUs - cueTime) < options.sameRoleCooldownUs }) {
                    skipped += SkippedSoundDecision(event.id, "same_role_cooldown")
                    continue
                }
                if (scheduled.any { abs(it.cueOutputUs - cueTime) < options.totalCueCooldownUs }) {
                    skipped += SkippedSoundDecision(event.id, "overlapping_cue")
                    continue
                }
                if (scheduled.count { abs(it.cueOutputUs - cueTime) < options.thirtySecondWindowUs } >= maxPerWindow) {
                    skipped += SkippedSoundDecision(event.id, "too_many_sfx")
                    continue
                }
                val underSpeech = speech.any { cueTime in it.outputStartUs..it.outputEndUs }
                val gain = (baseGain(role, plan.presetSnapshot.soundEffects) +
                    asset.peakTrimDb - if (underSpeech) 6.0 else 0.0).coerceIn(-48.0, -8.0)
                val id = "auto-sfx-" + (addedCues.size + 1)
                if (plan.sfxCues.any { it.id == id }) {
                    skipped += SkippedSoundDecision(event.id, "id_conflict_with_existing_cue")
                    continue
                }
                addedCues += SfxCue(id, asset.uri, cueTime, gain,
                    "Auto Edit " + role.name + " for event " + event.id)
                scheduled += SoundDecision(event.id, role, at, cueTime, gain, aligned != null)
            }
        }
        val updatedPlan = plan.copy(
            sfxCues = plan.sfxCues + addedCues,
            diagnostics = plan.diagnostics + mapOf(
                "audioDirector" to "auto-sound-v1",
                "automatedSfxCount" to addedCues.size.toString(),
                "preserveOriginalAudio" to "true"
            )
        )
        check(EdlValidator.validate(updatedPlan).isEmpty()) { "Sound director generated invalid EDL" }
        return SoundDirectorResult(updatedPlan, scheduled, skipped, speech, listOf(
            "All cue sources are explicitly provided licensed SFX assets.",
            "Original game and microphone audio is unchanged.",
            "Music ducking is a recommendation, not an applied audio export effect.",
            "Preview/export DSP and true-peak QA are required before release."
        ))
    }
}
