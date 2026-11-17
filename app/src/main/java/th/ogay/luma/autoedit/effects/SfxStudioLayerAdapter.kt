package th.ogay.luma.autoedit.effects

import android.net.Uri
import th.ogay.luma.VideoLayer
import th.ogay.luma.autoedit.core.EditDecisionList
import th.ogay.luma.autoedit.core.EdlValidator
import kotlin.math.min
import kotlin.math.pow

/** Duration must come from a real readable media asset, not a guessed filename. */
fun interface SfxDurationProbe {
    fun durationUs(uri: Uri): Long?
}

data class RejectedSfxLayer(val cueId: String, val reason: String)
data class SfxLayerAdaptation(
    val layers: List<VideoLayer>,
    val addedCount: Int,
    val rejected: List<RejectedSfxLayer>
)

/**
 * Bridge to Lumora's existing VideoLayers.SOUND lane for preview/export.
 *
 * Does not touch the original source or change the project's existing layers.
 * The caller can preview the returned list, then persist it with undo support.
 * The renderer still needs a separately verified URI/duration and sound track.
 */
object SfxStudioLayerAdapter {
    fun adapt(
        plan: EditDecisionList,
        existingLayers: List<VideoLayer>,
        probe: SfxDurationProbe,
        maxSfxDurationUs: Long = 2_500_000L
    ): SfxLayerAdaptation {
        require(maxSfxDurationUs in 100_000L..20_000_000L)
        require(EdlValidator.validate(plan).isEmpty())
        val result = existingLayers.toMutableList()
        val rejected = mutableListOf<RejectedSfxLayer>()
        var added = 0
        val outputEndUs = plan.clips.lastOrNull()?.let {
            it.outputStartUs + it.mapper().outputDurationUs
        } ?: 0L
        for (cue in plan.sfxCues.sortedWith(compareBy({ it.outputStartUs }, { it.id }))) {
            val uri = Uri.parse(cue.uri)
            val scheme = uri.scheme?.lowercase()
            if (scheme !in setOf("content", "file", "android.resource")) {
                rejected += RejectedSfxLayer(cue.id, "unsupported_audio_uri")
                continue
            }
            val durationUs = probe.durationUs(uri)
            if (durationUs == null || durationUs <= 0) {
                rejected += RejectedSfxLayer(cue.id, "unreadable_audio_asset")
                continue
            }
            val remainingUs = outputEndUs - cue.outputStartUs
            if (remainingUs < 10_000L) {
                rejected += RejectedSfxLayer(cue.id, "cue_too_close_to_end")
                continue
            }
            val atMs = cue.outputStartUs / 1000
            val lengthMs = min(min(durationUs, maxSfxDurationUs), remainingUs) / 1000
            if (lengthMs < 10L) {
                rejected += RejectedSfxLayer(cue.id, "insufficient_audio_duration")
                continue
            }
            // Re-applying a reviewed EDL must not double the sound.
            if (result.any { it.kind == VideoLayer.SOUND && it.source == uri && it.at == atMs }) {
                rejected += RejectedSfxLayer(cue.id, "already_on_timeline")
                continue
            }
            // Convert dB to linear amplitude exactly once. Original gameplay sound is unchanged.
            val linearGain = 10.0.pow(cue.gainDb / 20.0).toFloat().coerceIn(0f, 1f)
            result += VideoLayer(
                kind = VideoLayer.SOUND, source = uri, at = atMs,
                length = lengthMs, from = 0L, volume = linearGain,
                fadeIn = min(15L, lengthMs / 5),
                fadeOut = min(120L, lengthMs / 3), music = false
            )
            added++
        }
        return SfxLayerAdaptation(result, added, rejected)
    }
}
