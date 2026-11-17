package th.ogay.luma.autoedit.motion

import th.ogay.luma.autoedit.core.AnalysisEvent
import th.ogay.luma.autoedit.core.ClipDecision
import th.ogay.luma.autoedit.core.EditDecisionList

/**
 * Adds camera moves and speed ramps to a draft EDL without touching which source ranges are used. The result is a new
 * EDL (the input is not changed); it stays editable and validates with [th.ogay.luma.autoedit.core.EdlValidator].
 * Speed ramps change a clip's output length, which would move any sound cue, so they are skipped when cues exist.
 */
object MotionDirector {
    fun apply(plan: EditDecisionList, focusByClip: Map<String, List<Focus>> = emptyMap(), safe: SafeZone = SafeZone.NONE): EditDecisionList {
        val preset = plan.presetSnapshot
        val notes = LinkedHashMap(plan.diagnostics)
        val ramps = plan.sfxCues.isEmpty()
        if (!ramps) notes["motion.speed"] = "skipped: sound cues exist and would shift"
        var outStart = 0L
        val clips = plan.clips.mapIndexed { i, c ->
            val peak = peakOf(c, plan.analysisEvents)
            val segs = if (ramps && c.speedSegments.isEmpty()) SpeedRampPlanner.plan(c.sourceOutUs - c.sourceInUs, peak?.let { it - c.sourceInUs }, preset.editingEnergy) else c.speedSegments
            val withSpeed = c.copy(speedSegments = segs)
            val span = withSpeed.mapper().outputDurationUs
            val peakOut = peak?.let { runCatching { withSpeed.mapper().sourceToOutput((it - c.sourceInUs).coerceIn(0, c.sourceOutUs - c.sourceInUs)) }.getOrNull() }
            val move = MotionPlanner.chooseMove(i, span, peakOut != null, preset.cameraMotion)
            val keys = if (c.motionKeyframes.isEmpty()) MotionPlanner.plan(move, span, preset.cameraMotion, peakOut, focusByClip[c.id].orEmpty(), safe) else c.motionKeyframes
            val done = withSpeed.copy(outputStartUs = outStart, motionKeyframes = keys)
            outStart += span
            done
        }
        notes["motion"] = "planned for ${clips.count { it.motionKeyframes.isNotEmpty() }} of ${clips.size} clips"
        return plan.copy(clips = clips, diagnostics = notes)
    }

    /** The source time of the most important event inside the clip, or null when none lies in it. */
    private fun peakOf(c: ClipDecision, events: List<AnalysisEvent>): Long? =
        events.filter { it.assetId == c.sourceAssetId && it.sourceStartUs >= c.sourceInUs && it.sourceStartUs < c.sourceOutUs }
            .maxByOrNull { it.importance * it.confidence }?.sourceStartUs
}
