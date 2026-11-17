package th.ogay.luma.autoedit.directors

import th.ogay.luma.autoedit.core.AnalysisEvent
import th.ogay.luma.autoedit.core.AnalysisEventType
import th.ogay.luma.autoedit.core.EventEvidence
import kotlin.math.max
import kotlin.math.min

/**
 * Conservatively enrich visually-supported *possible* game moments with audio
 * context from Claude Phase 2. Audio can improve editorial ordering, but it
 * can NEVER create or confirm a kill by itself.
 */
object GamingContextFusion {
    private val visualKinds = setOf("hud", "game_metadata", "ocr", "visual")
    private val audioKinds = setOf("audio_energy", "audio_onset")
    private const val MAX_DISTANCE_US = 500_000L

    private fun intervalDistance(atUs: Long, event: AnalysisEvent): Long =
        when {
            atUs < event.sourceStartUs -> event.sourceStartUs - atUs
            atUs > event.sourceEndUs -> atUs - event.sourceEndUs
            else -> 0L
        }

    fun enrich(events: List<AnalysisEvent>): List<AnalysisEvent> {
        val audio = events.filter { it.type == AnalysisEventType.HIGH_ACTIVITY &&
            it.evidence.any { ev -> ev.kind.lowercase() in audioKinds } }
            .sortedWith(compareBy({ it.assetId }, { it.sourceStartUs }, { it.id }))

        return events.map { e ->
            if (e.type != AnalysisEventType.POSSIBLE_KILL ||
                e.evidence.none { it.kind.lowercase() in visualKinds } ||
                e.evidence.any { it.kind == "audio_context" }) {
                e
            } else {
                // Anchor at actual HUD observation, not the beginning of the
                // analyzer's padded source window.
                val hudAt = e.evidence.firstOrNull { it.kind.lowercase() in visualKinds }
                    ?.sourceUs ?: e.sourceStartUs
                val near = audio.asSequence().filter { it.assetId == e.assetId &&
                    intervalDistance(hudAt, it) <= MAX_DISTANCE_US }
                    .sortedWith(compareBy<AnalysisEvent> { intervalDistance(hudAt, it) }
                        .thenByDescending { it.confidence }.thenBy { it.id })
                    .firstOrNull()
                if (near == null) e
                else {
                    // Score boost is intentionally small; detection confidence
                    // and event type stay unchanged.
                    val boost = min(0.10, max(0.0, near.confidence * 0.12))
                    e.copy(
                        importance = (e.importance + boost).coerceAtMost(1.0),
                        evidence = e.evidence + EventEvidence(
                            kind = "audio_context",
                            sourceUs = near.sourceStartUs,
                            confidence = near.confidence,
                            explanation = "Nearby game audio activity; not proof of a kill"
                        )
                    )
                }
            }
        }
    }
}
