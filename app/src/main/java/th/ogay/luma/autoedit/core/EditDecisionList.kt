package th.ogay.luma.autoedit.core

data class MediaAssetRef(
    val id: String,
    val uri: String,
    val durationUs: Long,
    val videoWidth: Int,
    val videoHeight: Int,
    val rotationDegrees: Int = 0
)

data class MotionKeyframe(
    val outputOffsetUs: Long,
    val centerX: Double,
    val centerY: Double,
    val scale: Double
)

data class ClipDecision(
    val id: String,
    val sourceAssetId: String,
    val sourceInUs: Long,
    val sourceOutUs: Long,
    val outputStartUs: Long,
    val reason: String,
    val importance: Double,
    val speedSegments: List<SpeedSegment> = emptyList(),
    val motionKeyframes: List<MotionKeyframe> = emptyList(),
    val originalAudioPolicy: String = "keep"
) {
    fun mapper(): TimeWarpMapper = TimeWarpMapper.forClip(sourceOutUs - sourceInUs, speedSegments)
}

data class SfxCue(
    val id: String,
    val uri: String,
    val outputStartUs: Long,
    val gainDb: Double,
    val reason: String
)

/** The v1 EDL is a single, non-overlapping primary picture timeline. */
data class EditDecisionList(
    val schemaVersion: Int = 1,
    val planId: String,
    val presetSnapshot: AutoEditPreset,
    val analysisVersion: String,
    val sourceAssets: List<MediaAssetRef>,
    val clips: List<ClipDecision>,
    val analysisEvents: List<AnalysisEvent> = emptyList(),
    val sfxCues: List<SfxCue> = emptyList(),
    val diagnostics: Map<String, String> = emptyMap(),
    val generationSeed: Long = 0L
)

data class EdlIssue(val code: String, val description: String)

/** Gate between an AI/editorial plan and any future Studio-project mutation. */
object EdlValidator {
    fun validate(plan: EditDecisionList): List<EdlIssue> {
        val errors = mutableListOf<EdlIssue>()
        fun fail(code: String, text: String) { errors += EdlIssue(code, text) }
        if (plan.schemaVersion != 1) fail("schema", "Unsupported EDL schema")
        if (plan.planId.isBlank()) fail("id", "Missing plan ID")
        if (plan.analysisVersion.isBlank()) fail("version", "Missing analysis version")
        if (plan.sourceAssets.isEmpty()) fail("assets", "At least one source asset is required")
        if (plan.sourceAssets.map { it.id }.distinct().size != plan.sourceAssets.size) fail("assets", "Duplicate asset IDs")
        if (plan.clips.map { it.id }.distinct().size != plan.clips.size) fail("clips", "Duplicate clip IDs")

        val assets = plan.sourceAssets.associateBy { it.id }
        for (a in plan.sourceAssets) {
            if (a.id.isBlank() || a.uri.isBlank() || a.durationUs <= 0 ||
                a.videoWidth <= 0 || a.videoHeight <= 0 ||
                a.rotationDegrees !in listOf(0, 90, 180, 270)) fail("asset", "Invalid asset: " + a.id)
        }
        var nextOutputUs = 0L
        for (clip in plan.clips) {
            val asset = assets[clip.sourceAssetId]
            if (asset == null) fail("missing_asset", "Unknown source for clip " + clip.id)
            if (clip.id.isBlank() || clip.reason.isBlank()) fail("clip", "Missing clip ID or reason")
            if (!clip.importance.isFinite() || clip.importance !in 0.0..1.0) fail("importance", "Invalid score")
            if (clip.sourceInUs < 0 || clip.sourceOutUs <= clip.sourceInUs ||
                (asset != null && clip.sourceOutUs > asset.durationUs)) {
                fail("source_range", "Invalid source range for " + clip.id)
                continue
            }
            if (clip.outputStartUs != nextOutputUs) fail("output_clock", "Timeline must be gap-free and ordered")
            if (clip.originalAudioPolicy != "keep" && plan.presetSnapshot.preserveSourceAudio) {
                fail("audio", "Preset requires the source audio to remain intact")
            }
            val mapper = try { clip.mapper() } catch (e: IllegalArgumentException) {
                fail("speed", "Invalid speed segments for " + clip.id); null
            }
            if (mapper != null) {
                val spanUs = mapper.outputDurationUs
                if (nextOutputUs > Long.MAX_VALUE - spanUs) fail("overflow", "Timeline overflow")
                else nextOutputUs += spanUs
                var prev = -1L
                for (kf in clip.motionKeyframes) {
                    if (kf.outputOffsetUs < 0 || kf.outputOffsetUs > spanUs || kf.outputOffsetUs <= prev ||
                        !kf.centerX.isFinite() || kf.centerX !in 0.0..1.0 ||
                        !kf.centerY.isFinite() || kf.centerY !in 0.0..1.0 ||
                        !kf.scale.isFinite() || kf.scale !in 1.0..2.0) {
                        fail("motion", "Invalid motion keyframe for " + clip.id)
                    }
                    prev = kf.outputOffsetUs
                }
            }
        }
        if (plan.analysisEvents.map { it.id }.distinct().size != plan.analysisEvents.size) fail("event", "Duplicate event IDs")
        for (event in plan.analysisEvents) {
            val asset = assets[event.assetId]
            if (asset == null || event.sourceEndUs > asset.durationUs ||
                event.evidence.any { it.sourceUs > asset.durationUs }) {
                fail("event", "Event outside a known asset: " + event.id)
            }
            if (event.type == AnalysisEventType.CONFIRMED_KILL &&
                event.evidence.none { it.kind == "hud" || it.kind == "game_metadata" }) {
                fail("evidence", "A confirmed kill needs corroborating HUD or game metadata")
            }
        }
        if (plan.sfxCues.map { it.id }.distinct().size != plan.sfxCues.size) fail("sfx", "Duplicate SFX cue IDs")
        for (cue in plan.sfxCues) {
            if (cue.id.isBlank() || cue.uri.isBlank() || cue.outputStartUs < 0 ||
                cue.outputStartUs >= nextOutputUs || !cue.gainDb.isFinite() || cue.gainDb !in -60.0..12.0) {
                fail("sfx", "Invalid SFX cue " + cue.id)
            }
        }
        return errors
    }
}
