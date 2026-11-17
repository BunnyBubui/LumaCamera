package th.ogay.luma.autoedit.directors

import th.ogay.luma.autoedit.core.*
import kotlin.math.max
import kotlin.math.min

/**
 * Editorial decisions using Phase 2 AnalysisEvent evidence. No image, audio or
 * game recognition takes place here. Units are source microseconds.
 */
data class GamingDirectorOptions(
    val streakWindowUs: Long = 6_500_000L,
    val minConfidence: Double = 0.68,
    val minEvidenceConfidence: Double = 0.70,
    val maxHighlightsOverride: Int? = null,
    val maxOutputUsOverride: Long? = null
) {
    init {
        require(streakWindowUs in 500_000L..30_000_000L)
        require(minConfidence.isFinite() && minConfidence in 0.0..1.0)
        require(minEvidenceConfidence.isFinite() && minEvidenceConfidence in 0.0..1.0)
        require(maxHighlightsOverride == null || maxHighlightsOverride in 1..100)
        require(maxOutputUsOverride == null || maxOutputUsOverride > 0)
    }
}

data class RejectedGamingEvent(val eventId: String, val reason: String)
data class GamingHighlight(
    val assetId: String,
    val startUs: Long,
    val endUs: Long,
    val eventIds: List<String>,
    val confirmedKills: Int,
    val score: Double,
    val reason: String
) {
    val durationUs get() = endUs - startUs
}

data class GamingEditProposal(
    val selected: List<GamingHighlight>,
    val rejected: List<RejectedGamingEvent>,
    val plan: EditDecisionList?,
    val notes: List<String>
)

object GamingHighlightDirector {
    private val corroborating = setOf("hud", "game_metadata")
    private val visual = setOf("hud", "game_metadata", "ocr", "visual")
    private data class Seed(val event: AnalysisEvent, val confirmed: Boolean)
    private data class Window(val assetId: String, val profileId: String?, val startUs: Long,
                              val endUs: Long, val seeds: List<Seed>)

    private fun hasEvidence(e: AnalysisEvent, kinds: Set<String>, threshold: Double) =
        e.evidence.any { it.kind.lowercase() in kinds && it.confidence >= threshold }

    private fun confirmed(e: AnalysisEvent, options: GamingDirectorOptions) =
        e.type == AnalysisEventType.CONFIRMED_KILL &&
            e.confidence >= options.minConfidence &&
            hasEvidence(e, corroborating, options.minEvidenceConfidence)

    private fun relevant(e: AnalysisEvent, options: GamingDirectorOptions): Boolean {
        if (e.confidence < options.minConfidence) return false
        return when (e.type) {
            AnalysisEventType.CONFIRMED_KILL -> confirmed(e, options)
            AnalysisEventType.MULTIKILL -> hasEvidence(e, corroborating, options.minEvidenceConfidence)
            AnalysisEventType.POSSIBLE_KILL, AnalysisEventType.CLUTCH,
            AnalysisEventType.VICTORY -> hasEvidence(e, visual, options.minEvidenceConfidence)
            else -> false // audio peaks and rapid scene changes alone do not indicate a kill
        }
    }

    private fun score(seeds: List<Seed>): Double {
        val strongest = seeds.maxOf { it.event.importance * 0.52 + it.event.confidence * 0.28 }
        val confirmedKills = seeds.count { it.confirmed }
        val eventBonus = seeds.maxOf {
            when (it.event.type) {
                AnalysisEventType.VICTORY -> 0.22
                AnalysisEventType.CLUTCH -> 0.21
                AnalysisEventType.MULTIKILL -> 0.20
                AnalysisEventType.CONFIRMED_KILL -> if (it.confirmed) 0.15 else 0.0
                AnalysisEventType.POSSIBLE_KILL -> 0.025
                else -> 0.0
            }
        }
        return (strongest + eventBonus + min(0.17, max(0, confirmedKills - 1) * 0.065))
            .coerceIn(0.0, 1.0)
    }

    private fun reason(seeds: List<Seed>): String {
        val count = seeds.count { it.confirmed }
        val parts = mutableListOf<String>()
        if (count > 1) parts += count.toString() + " confirmed kills in continuous streak"
        else if (count == 1) parts += "confirmed kill"
        if (seeds.any { it.event.type == AnalysisEventType.MULTIKILL }) parts += "multi-kill indicator"
        if (seeds.any { it.event.type == AnalysisEventType.CLUTCH }) parts += "clutch"
        if (seeds.any { it.event.type == AnalysisEventType.VICTORY }) parts += "victory"
        if (parts.isEmpty()) parts += "possible visual highlight (requires review)"
        return parts.joinToString(" + ")
    }

    /** Deduplicates only exactly repeated visual observations, not distinct rapid kills. */
    private fun readSeeds(
        assets: Map<String, MediaAssetRef>, events: List<AnalysisEvent>,
        o: GamingDirectorOptions, rejected: MutableList<RejectedGamingEvent>
    ): List<Seed> {
        val ids = mutableSetOf<String>()
        val seenObservations = mutableSetOf<String>()
        val seeds = mutableListOf<Seed>()
        for (e in events.sortedWith(compareBy({ it.assetId }, { it.sourceStartUs }, { it.id }))) {
            val a = assets[e.assetId]
            val reason = when {
                !ids.add(e.id) -> "duplicate_event_id"
                a == null -> "unknown_asset"
                e.sourceEndUs > a.durationUs || e.evidence.any { it.sourceUs > a.durationUs } -> "out_of_range"
                !relevant(e, o) -> "insufficient_visual_evidence_or_confidence"
                else -> null
            }
            if (reason != null) { rejected += RejectedGamingEvent(e.id, reason); continue }
            val signature = e.evidence.filter { it.kind.lowercase() in visual }
                .sortedWith(compareBy({ it.kind }, { it.sourceUs }, { it.explanation }))
                .joinToString("|") { it.kind + ":" + it.sourceUs + ":" + it.explanation }
            val key = e.assetId + "/" + e.gameProfileId + "/" + e.type + "/" +
                e.sourceStartUs + "/" + e.sourceEndUs + "/" + signature
            if (signature.isNotBlank() && !seenObservations.add(key)) {
                rejected += RejectedGamingEvent(e.id, "duplicate_visual_observation")
            } else seeds += Seed(e, confirmed(e, o))
        }
        return seeds
    }

    private fun sourceWindows(
        seeds: List<Seed>, assets: Map<String, MediaAssetRef>,
        preset: AutoEditPreset, o: GamingDirectorOptions
    ): List<Window> {
        val preUs = ((2.9 - 1.4 * preset.editingEnergy) * 1_000_000).toLong()
        val postUs = ((2.6 - 1.1 * preset.editingEnergy) * 1_000_000).toLong()
        val groups = mutableListOf<List<Seed>>()
        // Never group streaks across files or different game profiles.
        for (group in seeds.groupBy { it.event.assetId to it.event.gameProfileId }.values) {
            val kills = group.filter { it.confirmed }
                .sortedWith(compareBy({ it.event.sourceStartUs }, { it.event.id }))
            var streak = mutableListOf<Seed>()
            for (next in kills) {
                val prev = streak.lastOrNull()
                if (prev != null &&
                    next.event.sourceStartUs > prev.event.sourceEndUs &&
                    next.event.sourceStartUs - prev.event.sourceEndUs > o.streakWindowUs) {
                    groups += streak
                    streak = mutableListOf()
                }
                streak += next
            }
            if (streak.isNotEmpty()) groups += streak
            val groupedIds = kills.map { it.event.id }.toSet()
            group.filter { it.event.id !in groupedIds }.forEach { groups += listOf(it) }
        }
        return groups.map { group ->
            val a = requireNotNull(assets[group.first().event.assetId])
            val first = group.minOf { it.event.sourceStartUs }
            val last = group.maxOf { it.event.sourceEndUs }
            Window(a.id, group.first().event.gameProfileId, (first - preUs).coerceAtLeast(0L),
                if (postUs >= a.durationUs - last) a.durationUs else last + postUs,
                group)
        }
    }

    private fun mergeWindows(windows: List<Window>): List<Window> {
        val out = mutableListOf<Window>()
        for (w in windows.sortedWith(compareBy({ it.assetId }, { it.profileId ?: "" }, { it.startUs }, { it.endUs }))) {
            val prev = out.lastOrNull()
            if (prev != null && prev.assetId == w.assetId && prev.profileId == w.profileId && w.startUs <= prev.endUs) {
                out[out.lastIndex] = prev.copy(
                    endUs = max(prev.endUs, w.endUs),
                    seeds = (prev.seeds + w.seeds).distinctBy { it.event.id })
            } else out += w
        }
        return out
    }

    /**
     * Produce editable, non-destructive suggestions. Does not mutate original
     * media, add SFX, render frames or invent events when detectors are absent.
     */
    fun propose(
        assets: List<MediaAssetRef>,
        events: List<AnalysisEvent>,
        preset: AutoEditPreset = AutoEditPreset.gamingDefault(),
        options: GamingDirectorOptions = GamingDirectorOptions(),
        generationSeed: Long = 0L
    ): GamingEditProposal {
        require(preset.mode == "gaming")
        require(assets.isNotEmpty() && assets.all { it.id.isNotBlank() && it.durationUs > 0 })
        require(assets.map { it.id }.distinct().size == assets.size)
        val rejected = mutableListOf<RejectedGamingEvent>()
        val seeds = readSeeds(assets.associateBy { it.id }, events, options, rejected)
        if (seeds.isEmpty()) return GamingEditProposal(emptyList(), rejected, null,
            listOf("No corroborated gameplay highlights; original video stays unchanged."))
        val candidates = mergeWindows(sourceWindows(
            seeds, assets.associateBy { it.id }, preset, options))
            .map { w -> GamingHighlight(
                w.assetId, w.startUs, w.endUs,
                w.seeds.map { it.event.id }.distinct().sorted(),
                w.seeds.count { it.confirmed }, score(w.seeds), reason(w.seeds)
            ) }
            .sortedWith(compareByDescending<GamingHighlight> { it.score }
                .thenBy { it.assetId }.thenBy { it.startUs })
        val maxHighlights = options.maxHighlightsOverride ?: 8 + (8 * preset.editingEnergy).toInt()
        val budgetUs = options.maxOutputUsOverride ?:
            ((145.0 - 85.0 * preset.editingEnergy) * 1_000_000).toLong()
        val selected = mutableListOf<GamingHighlight>()
        var totalUs = 0L
        for (c in candidates) {
            if (selected.size == maxHighlights) break
            // Do not repeat overlapping time ranges from incompatible detector profiles.
            if (selected.any { it.assetId == c.assetId && c.startUs < it.endUs && c.endUs > it.startUs }) continue
            // An over-budget *first* streak is retained whole, never cut mid-event.
            if (selected.isNotEmpty() && c.durationUs > budgetUs - totalUs) continue
            selected += c
            totalUs += c.durationUs
        }
        val spans = selected.mapIndexed { i, c ->
            ReviewedSpan("gaming-" + (i + 1), c.assetId, c.startUs, c.endUs, c.reason, c.score)
        }
        val base = AutoEditPlanner.draftFromReviewedSpans(preset, assets, spans, generationSeed)
        val chosenIds = selected.flatMap { it.eventIds }.toSet()
        val plan = base.copy(
            analysisVersion = "gaming-director-v1",
            analysisEvents = seeds.map { it.event }.filter { it.id in chosenIds }
                .distinctBy { it.id }.sortedWith(compareBy({ it.assetId }, { it.sourceStartUs }, { it.id })),
            diagnostics = mapOf(
                "director" to "gaming-v1",
                "selectedHighlights" to selected.size.toString(),
                "originalAudioPreserved" to "true",
                "editingEnergy" to preset.editingEnergy.toString()
            )
        )
        return GamingEditProposal(selected, rejected, plan, listOf(
            "Strongest supported highlight first; selected time windows are kept intact.",
            "Confirm game semantics before publishing. No automated sound/motion in this phase."
        ))
    }
}
