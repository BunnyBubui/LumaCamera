package th.ogay.luma.autoedit.analysis

import th.ogay.luma.StudioMath
import th.ogay.luma.autoedit.core.AnalysisEvent
import th.ogay.luma.autoedit.core.AnalysisEventType
import th.ogay.luma.autoedit.core.EventEvidence
import kotlin.math.max
import kotlin.math.min

/**
 * Sound-based hints: speech spans, loud stretches (HIGH_ACTIVITY) and onsets. A loud moment proves nothing about a kill,
 * so this analyzer never emits POSSIBLE_KILL or CONFIRMED_KILL; it only says "something loud happened here".
 */
class AudioEventAnalyzer(private val activityMinMs: Long = 600) : Analyzer {
    override val name = "audio"
    override val version = "1"

    override fun analyze(source: AnalysisSource, budget: AnalysisBudget, check: () -> Unit): List<AnalysisEvent> {
        val out = ArrayList<AnalysisEvent>()
        val stepMs = max(1L, source.loudnessStepUs / 1000)
        val loud = source.loudness()?.let { if (it.size > budget.maxSeriesSamples) it.copyOf(budget.maxSeriesSamples) else it }

        // Speech: a voice detector when there is one, else long stretches that are neither quiet nor in a loud burst (lower confidence).
        val spans = source.speechSpans()
        if (spans != null) spans.forEach { r ->
            val s = r.first.coerceIn(0, source.durationUs - 1); val e = (r.last + 1).coerceIn(s + 1, source.durationUs)
            out += AnalysisEvent("speech-$s", source.assetId, s, e, AnalysisEventType.SPEECH, 0.5, 0.8, "audio@1", listOf(EventEvidence("vad", s, 0.8, "Voice detector heard speech")))
        }
        check()
        if (loud == null || loud.isEmpty()) return out

        // Onsets (sharp rises) as short events.
        for (ms in StudioMath.beats(loud, stepMs)) {
            val s = ms * 1000; if (s >= source.durationUs) continue
            val v = loud[(ms / stepMs).toInt().coerceIn(0, loud.size - 1)]
            out += AnalysisEvent("onset-$s", source.assetId, s, min(source.durationUs, s + 200_000).coerceAtLeast(s + 1), AnalysisEventType.HIGH_ACTIVITY,
                0.3 + v * 0.3, 0.4 + v * 0.3, "audio@1", listOf(EventEvidence("audio_onset", s, 0.4 + v * 0.3, "Loudness rose sharply")))
        }
        check()
        // Sustained loudness well above the file's own median: a fight, a crowd, music hitting.
        val median = loud.sorted()[loud.size / 2]
        val bar = max(0.35f, median * 2.2f)
        var run = -1
        fun close(end: Int) {
            if (run < 0) return
            val lenMs = (end - run) * stepMs
            if (lenMs >= activityMinMs) {
                var peak = 0f; for (k in run until end) peak = max(peak, loud[k])
                val s = run * stepMs * 1000; val e = min(source.durationUs, end * stepMs * 1000).coerceAtLeast(s + 1)
                if (s < source.durationUs) out += AnalysisEvent("loud-$s", source.assetId, s, e, AnalysisEventType.HIGH_ACTIVITY, 0.4 + peak * 0.3, 0.45 + peak * 0.2, "audio@1",
                    listOf(EventEvidence("audio_energy", s, 0.45 + peak * 0.2, "Loud for ${lenMs} ms against a median of ${"%.2f".format(median)}")))
            }
            run = -1
        }
        for (i in loud.indices) { if (loud[i] >= bar) { if (run < 0) run = i } else close(i); if (i % 4096 == 0) check() }
        close(loud.size)
        return out
    }
}
