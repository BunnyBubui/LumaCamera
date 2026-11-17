package th.ogay.luma.autoedit.analysis

import th.ogay.luma.autoedit.core.AnalysisEvent

/**
 * Everything an analyzer may read about one source video. The numbers are plain arrays so the analyzers run on the JVM
 * and in unit tests; [AndroidAnalysisSource] fills them from a real file. A missing signal is null, never a fake value.
 */
interface AnalysisSource {
    val assetId: String
    val durationUs: Long
    val width: Int
    val height: Int
    /** Container rotation (0, 90, 180, 270). Analyzers work on the picture as shown, see [displayWidth]. */
    val rotationDegrees: Int
    /** Mean absolute difference 0..1 between neighbouring sampled frames, one value per [frameStepUs]; null if frames cannot be read. */
    fun frameDiffs(): FloatArray?
    val frameStepUs: Long
    /** Loudness 0..1 per [loudnessStepUs] from the start of the file; null when the file has no readable sound. */
    fun loudness(): FloatArray?
    val loudnessStepUs: Long
    /** Voice spans (start to end, µs) from a voice detector; null when none ran. */
    fun speechSpans(): List<LongRange>?
    /** Frames for HUD reading, only asked for by a [HudDetector]. */
    fun frameAt(us: Long): Frame?
}

class Frame(val width: Int, val height: Int, val luma: ByteArray)

val AnalysisSource.displayWidth get() = if (rotationDegrees % 180 == 0) width else height
val AnalysisSource.displayHeight get() = if (rotationDegrees % 180 == 0) height else width

class CancelToken { @Volatile var cancelled = false; fun cancel() { cancelled = true } }
class AnalysisCancelled : RuntimeException("analysis cancelled")

/** Limits that keep a long recording from exhausting memory or time on a phone. */
data class AnalysisBudget(
    val maxSeriesSamples: Int = 200_000,
    val maxEvents: Int = 400,
    val maxDurationUs: Long = 4L * 3600 * 1_000_000
)

class AnalysisResult(val events: List<AnalysisEvent>, val diagnostics: Map<String, String>)

interface Analyzer {
    val name: String
    val version: String
    /** Events found in [source]; must call [check] now and then so a cancel stops it quickly. */
    fun analyze(source: AnalysisSource, budget: AnalysisBudget, check: () -> Unit): List<AnalysisEvent>
}

/** Runs the analyzers one after another with progress and cancel. One failing analyzer never throws away the others. */
class AnalysisPipeline(private val analyzers: List<Analyzer>, private val budget: AnalysisBudget = AnalysisBudget()) {
    fun version() = analyzers.joinToString("+") { it.name + "@" + it.version }

    fun run(source: AnalysisSource, cancel: CancelToken = CancelToken(), progress: (Float, String) -> Unit = { _, _ -> }): AnalysisResult {
        val notes = LinkedHashMap<String, String>()
        if (source.durationUs <= 0) return AnalysisResult(emptyList(), mapOf("error" to "empty source"))
        if (source.durationUs > budget.maxDurationUs) return AnalysisResult(emptyList(), mapOf("error" to "source longer than the analysis limit"))
        val all = ArrayList<AnalysisEvent>()
        analyzers.forEachIndexed { i, a ->
            if (cancel.cancelled) throw AnalysisCancelled()
            progress(i.toFloat() / analyzers.size, a.name)
            try { all += a.analyze(source, budget) { if (cancel.cancelled) throw AnalysisCancelled() }; notes[a.name] = "ok" }
            catch (e: AnalysisCancelled) { throw e }
            catch (e: Exception) { notes[a.name] = "failed: " + (e.message ?: e.javaClass.simpleName) }
        }
        progress(1f, "done")
        val kept = all.sortedWith(compareBy({ it.sourceStartUs }, { it.type.ordinal }, { it.id }))
            .let { if (it.size > budget.maxEvents) { notes["events"] = "trimmed from ${it.size} to ${budget.maxEvents}"; it.sortedByDescending { e -> e.importance * e.confidence }.take(budget.maxEvents).sortedBy { e -> e.sourceStartUs } } else it }
        return AnalysisResult(kept, notes)
    }
}
