package th.ogay.luma.autoedit.analysis

import th.ogay.luma.StudioMath
import th.ogay.luma.autoedit.core.AnalysisEvent
import th.ogay.luma.autoedit.core.AnalysisEventType
import th.ogay.luma.autoedit.core.EventEvidence
import kotlin.math.max
import kotlin.math.min

/**
 * Scene changes from the frame-difference series, reusing [StudioMath.sceneCuts]. A jump that is undone one sample later
 * is a flash (muzzle flash, explosion, screen shake), not a new scene, so it is kept at low confidence as HIGH_ACTIVITY
 * and never as SCENE_CHANGE.
 */
class SceneAnalyzer(private val minGapMs: Long = 800) : Analyzer {
    override val name = "scene"
    override val version = "1"

    override fun analyze(source: AnalysisSource, budget: AnalysisBudget, check: () -> Unit): List<AnalysisEvent> {
        val raw = source.frameDiffs() ?: return emptyList()
        val diffs = if (raw.size > budget.maxSeriesSamples) raw.copyOf(budget.maxSeriesSamples) else raw
        val stepMs = max(1L, source.frameStepUs / 1000)
        check()
        val typical = diffs.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }
        val out = ArrayList<AnalysisEvent>()
        for (ms in StudioMath.sceneCuts(diffs, stepMs, minGapMs)) {
            val i = (ms / stepMs).toInt().coerceIn(1, diffs.size - 1)
            val jump = diffs[i]
            val after = if (i + 1 < diffs.size) diffs[i + 1] else 0f
            val flash = i + 1 < diffs.size && after < jump * 0.35f && after <= typical * 2f
            val strength = (jump / (typical * 4f).coerceAtLeast(0.18f)).coerceIn(1f, 4f)
            val conf = ((strength - 1f) / 3f * 0.6f + 0.35f).toDouble().coerceIn(0.35, 0.95)
            val at = ms * 1000
            val start = at; val end = min(source.durationUs, at + source.frameStepUs).coerceAtLeast(start + 1)
            if (start >= source.durationUs) continue
            check()
            if (flash) out += AnalysisEvent("scene-flash-$at", source.assetId, start, end, AnalysisEventType.HIGH_ACTIVITY, 0.35, conf * 0.5, "scene@1",
                listOf(EventEvidence("scene_diff", at, conf * 0.5, "Brief jump in the picture that is gone one sample later")))
            else out += AnalysisEvent("scene-$at", source.assetId, start, end, AnalysisEventType.SCENE_CHANGE, 0.4 + conf * 0.2, conf, "scene@1",
                listOf(EventEvidence("scene_diff", at, conf, "Picture changed and stayed changed")))
        }
        return out
    }
}
