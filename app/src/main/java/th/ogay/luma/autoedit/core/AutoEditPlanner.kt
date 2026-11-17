package th.ogay.luma.autoedit.core

import java.security.MessageDigest

/**
 * Phase 1 accepts already-reviewed candidate spans; it does NOT detect kills,
 * choose highlights or claim that high audio volume is evidence of a kill.
 */
data class ReviewedSpan(
    val id: String,
    val sourceAssetId: String,
    val sourceInUs: Long,
    val sourceOutUs: Long,
    val reason: String,
    val importance: Double
)

/**
 * A deterministic draft timeline compiler shared by future directors.
 * Candidate order is editorial order. Every chosen source range retains
 * its own original audio, and the media files are never modified.
 */
object AutoEditPlanner {
    fun draftFromReviewedSpans(
        preset: AutoEditPreset,
        assets: List<MediaAssetRef>,
        spans: List<ReviewedSpan>,
        generationSeed: Long = 0L
    ): EditDecisionList {
        require(assets.isNotEmpty()) { "No source assets" }
        require(spans.isNotEmpty()) { "No reviewed spans to edit" }
        val assetLookup = assets.associateBy { it.id }
        require(assetLookup.size == assets.size) { "Duplicate asset IDs" }

        var outputStartUs = 0L
        val decisions = spans.map { s ->
            val asset = requireNotNull(assetLookup[s.sourceAssetId]) { "Missing source asset" }
            require(s.id.isNotBlank() && s.reason.isNotBlank())
            require(s.sourceInUs >= 0 && s.sourceOutUs > s.sourceInUs)
            require(s.sourceOutUs <= asset.durationUs)
            require(s.importance.isFinite() && s.importance in 0.0..1.0)
            val spanUs = s.sourceOutUs - s.sourceInUs
            require(outputStartUs <= Long.MAX_VALUE - spanUs) { "Timeline overflow" }
            ClipDecision(
                id = s.id, sourceAssetId = s.sourceAssetId,
                sourceInUs = s.sourceInUs, sourceOutUs = s.sourceOutUs,
                outputStartUs = outputStartUs, reason = s.reason,
                importance = s.importance, originalAudioPolicy = "keep"
            ).also { outputStartUs += spanUs }
        }
        val fingerprint = buildString {
            append("autoedit-v2\n").append(generationSeed).append('\n')
            append(preset.schemaVersion).append('|').append(preset.presetId).append('|')
            append(preset.mode).append('|').append(preset.editingEnergy).append('|')
            append(preset.cameraMotion).append('|').append(preset.soundEffects).append('|')
            append(preset.preserveSourceAudio).append('\n')
            assets.forEach { append(it.id).append('|').append(it.uri).append('|')
                .append(it.durationUs).append('\n') }
            spans.forEach { append(it.id).append('|').append(it.sourceAssetId).append('|')
                .append(it.sourceInUs).append('|').append(it.sourceOutUs).append('|')
                .append(it.reason).append('|').append(it.importance).append('\n') }
        }
        val bytes = MessageDigest.getInstance("SHA-256").digest(fingerprint.toByteArray(Charsets.UTF_8))
        val id = "ae2-" + bytes.take(12).joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        val result = EditDecisionList(
            planId = id, presetSnapshot = preset, analysisVersion = "phase1-reviewed-input",
            sourceAssets = assets.toList(), clips = decisions,
            generationSeed = generationSeed
        )
        check(EdlValidator.validate(result).isEmpty()) { "Planner emitted an invalid EDL" }
        return result
    }
}
