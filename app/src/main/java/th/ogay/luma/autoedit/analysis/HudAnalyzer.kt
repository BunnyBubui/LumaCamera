package th.ogay.luma.autoedit.analysis

import th.ogay.luma.autoedit.core.AnalysisEvent
import th.ogay.luma.autoedit.core.AnalysisEventType
import th.ogay.luma.autoedit.core.EventEvidence

/** One thing a game-specific detector read off the HUD. [label] is free text such as "elimination banner". */
class HudHit(val sourceUs: Long, val confidence: Double, val label: String)

/**
 * Reads on-screen game information. There is no general detector: each game needs its own, validated profile, so the
 * default is [NoHudDetector], which says so instead of guessing.
 */
interface HudDetector {
    val profileId: String?
    val available: Boolean
    /** Sample times (µs) the detector wants to look at, spaced so the budget holds. */
    fun hits(source: AnalysisSource, check: () -> Unit): List<HudHit>
}

object NoHudDetector : HudDetector {
    override val profileId: String? = null
    override val available = false
    override fun hits(source: AnalysisSource, check: () -> Unit): List<HudHit> = emptyList()
}

/**
 * Turns HUD hits into POSSIBLE_KILL events with "hud" evidence. They stay POSSIBLE: confirming a kill is the Gaming
 * Director's job (phase 3), which can combine HUD with other evidence.
 */
class HudAnalyzer(private val detector: HudDetector = NoHudDetector, private val windowUs: Long = 1_500_000) : Analyzer {
    override val name = "hud"
    override val version = "1"
    override fun analyze(source: AnalysisSource, budget: AnalysisBudget, check: () -> Unit): List<AnalysisEvent> {
        if (!detector.available) return emptyList()
        return detector.hits(source, check).filter { it.sourceUs in 0 until source.durationUs && it.confidence in 0.0..1.0 }.map { h ->
            val s = (h.sourceUs - windowUs / 3).coerceAtLeast(0); val e = (h.sourceUs + windowUs).coerceAtMost(source.durationUs).coerceAtLeast(s + 1)
            AnalysisEvent("hud-${h.sourceUs}", source.assetId, s, e, AnalysisEventType.POSSIBLE_KILL, 0.6 + h.confidence * 0.3, h.confidence.coerceAtMost(0.9), "hud@1",
                listOf(EventEvidence("hud", h.sourceUs, h.confidence, h.label)), detector.profileId)
        }
    }
}
