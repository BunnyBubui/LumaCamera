package th.ogay.luma

import java.util.EnumMap
import java.util.Locale
import kotlin.math.ceil

/**
 * Phase 1: opt-in diagnostics for Studio preview. Does not own a MediaPlayer, Surface or UI.
 *
 * All measurements use a monotonic nanosecond clock. Frame callbacks are NOT proof that frames
 * reached the display; UI callbacks, video texture updates and decoder drop reports are separate.
 * No logs or allocations occur on the hot path when [enabled] is false.
 *
 * Phase 2 owns integration into VideoEditorActivity; see docs/EDITOR_PREVIEW_PHASE1_DIAGNOSTICS.md.
 */
class StudioPreviewMetrics(
    val enabled: Boolean = false,
    private val clock: NanoClock = NanoClock { System.nanoTime() },
    windowSize: Int = 256,
    maxPending: Int = 128
) {
    fun interface NanoClock { fun nowNs(): Long }

    enum class Span {
        PRELOAD, PREPARE, SEEK, FIRST_RENDER, SURFACE_HANDOFF, JOIN, MAIN_THREAD_WORK,
        AUDIO_DISCONTINUITY
    }
    enum class FrameSource { UI_CALLBACK, VIDEO_MAIN_TEXTURE, VIDEO_INCOMING_TEXTURE }
    enum class State { IDLE, PREPARING, READY, PLAYING, PAUSED, SEEKING, SWITCHING, RELEASED, ERROR }
    enum class Event {
        JOIN_REQUEST, JOIN_FINISHED, PRELOAD_NOT_READY, PRELOAD_CANCELLED, FIRST_FRAME_REPORTED,
        FIRST_TEXTURE_UPDATE, SURFACE_RECREATED, VISIBLE_BLACK_FRAME_OBSERVED,
        AUDIO_GLITCH_CONFIRMED, SEEK_TIMEOUT, PLAYER_ERROR
    }
    enum class JoinKind { SAME_SOURCE_SPLIT, DIFFERENT_SOURCE_CUT, OVERLAP, OTHER }

    /** Bounded sample-window percentile statistics; totalCount includes overwritten samples. */
    data class Stats(
        val totalCount: Long,
        val sampleCount: Int,
        val p50Ms: Double?,
        val p95Ms: Double?,
        val maxMs: Double?
    )
    data class StateChange(val from: State?, val to: State, val timestampNs: Long)
    data class Snapshot(
        val durations: Map<Span, Stats>,
        val frameIntervals: Map<FrameSource, Stats>,
        val frameCallbacks: Map<FrameSource, Long>,
        val states: List<StateChange>,
        val events: Map<Event, Long>,
        val joins: Map<JoinKind, Long>,
        val reportedDroppedDecoderFrames: Long,
        val invalidSamples: Long,
        val evictedSpans: Long,
        val cancelledSpans: Long,
        val activeSpans: Int
    )

    private data class Pending(val span: Span, val startedNs: Long)

    private class Window(private val capacity: Int) {
        private val values = LongArray(capacity)
        private var cursor = 0
        private var count = 0
        private var total = 0L

        fun add(valueNs: Long) {
            values[cursor] = valueNs
            cursor = (cursor + 1) % capacity
            if (count < capacity) count++
            total++
        }

        fun stats(): Stats {
            if (count == 0) return Stats(total, 0, null, null, null)
            val sorted = values.copyOf(count).apply { sort() }
            fun percentile(p: Double): Double {
                val index = (ceil(count * p).toInt() - 1).coerceIn(0, count - 1)
                return sorted[index] / 1_000_000.0
            }
            return Stats(total, count, percentile(0.50), percentile(0.95), sorted.last() / 1_000_000.0)
        }
    }

    private val lock = Any()
    private val capacity = windowSize.coerceIn(1, 4096)
    private val pendingLimit = maxPending.coerceIn(1, 1024)
    private val durations = EnumMap<Span, Window>(Span::class.java)
    private val intervals = EnumMap<FrameSource, Window>(FrameSource::class.java)
    private val lastFrameNs = EnumMap<FrameSource, Long>(FrameSource::class.java)
    private val frameCounts = EnumMap<FrameSource, Long>(FrameSource::class.java)
    private val eventCounts = EnumMap<Event, Long>(Event::class.java)
    private val joinCounts = EnumMap<JoinKind, Long>(JoinKind::class.java)
    private val pending = LinkedHashMap<Long, Pending>()
    private val changes = ArrayDeque<StateChange>()
    private var previousState: State? = null
    private var nextToken = 1L
    private var invalid = 0L
    private var evicted = 0L
    private var cancelled = 0L
    private var decoderDrops = 0L

    /** 0 means instrumentation is disabled. Call [finish] or [cancel] exactly once. */
    fun begin(span: Span): Long {
        if (!enabled) return 0L
        val t = clock.nowNs()
        return synchronized(lock) {
            if (pending.size >= pendingLimit) {
                val oldest = pending.keys.first()
                pending.remove(oldest)
                evicted++
            }
            val id = nextToken++
            pending[id] = Pending(span, t)
            id
        }
    }

    /** True only for a completed, non-negative measured interval. Unknown tokens are ignored. */
    fun finish(token: Long): Boolean {
        if (!enabled || token == 0L) return false
        val t = clock.nowNs()
        return synchronized(lock) {
            val p = pending.remove(token) ?: return@synchronized false
            if (t < p.startedNs) {
                invalid++
                false
            } else {
                durations.getOrPut(p.span) { Window(capacity) }.add(t - p.startedNs)
                true
            }
        }
    }

    fun cancel(token: Long) {
        if (!enabled || token == 0L) return
        synchronized(lock) { if (pending.remove(token) != null) cancelled++ }
    }

    /**
     * Count an observed callback and the interval from the previous callback of the SAME stream.
     * UI_CALLBACK is the callback cadence, not decoded video FPS. For Choreographer pass
     * frameTimeNanos; for texture updates use the clock default.
     */
    fun frame(source: FrameSource, timestampNs: Long = if (enabled) clock.nowNs() else 0L) {
        if (!enabled) return
        synchronized(lock) {
            frameCounts[source] = (frameCounts[source] ?: 0L) + 1L
            val last = lastFrameNs[source]
            if (last != null && timestampNs <= last) {
                invalid++
            } else {
                if (last != null) intervals.getOrPut(source) { Window(capacity) }.add(timestampNs - last)
                lastFrameNs[source] = timestampNs
            }
        }
    }

    /** Only count data actually reported by a decoder, never infer dropped video frames from a UI tick. */
    fun decoderReportedDrops(count: Long) {
        if (!enabled || count <= 0) return
        synchronized(lock) { decoderDrops += count }
    }

    /** Call only when a condition was observed, e.g. a verified black video frame, not a delayed tick. */
    fun mark(event: Event, count: Long = 1L) {
        if (!enabled || count <= 0L) return
        synchronized(lock) { eventCounts[event] = (eventCounts[event] ?: 0L) + count }
    }

    fun join(kind: JoinKind) {
        if (!enabled) return
        synchronized(lock) { joinCounts[kind] = (joinCounts[kind] ?: 0L) + 1L }
    }

    /** Consecutive identical states do not generate duplicate transitions. */
    fun state(to: State) {
        if (!enabled) return
        val t = clock.nowNs()
        synchronized(lock) {
            if (previousState == to) return
            changes.addLast(StateChange(previousState, to, t))
            if (changes.size > 64) changes.removeFirst()
            previousState = to
        }
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(
            durations.mapValues { it.value.stats() },
            intervals.mapValues { it.value.stats() },
            frameCounts.toMap(),
            changes.toList(),
            eventCounts.toMap(),
            joinCounts.toMap(),
            decoderDrops, invalid, evicted, cancelled, pending.size
        )
    }

    /** No Android Log calls; the caller MUST guard invocation with BuildConfig.DEBUG. */
    fun debugReport(tags: Map<String, String> = emptyMap()): String {
        if (!enabled) return "Lumora Studio preview metrics: disabled"
        val s = snapshot()
        fun value(v: Double?): String = if (v == null) "n/a" else String.format(Locale.US, "%.2f", v)
        val lines = mutableListOf("Lumora Studio preview diagnostics (measured callbacks only)")
        if (tags.isNotEmpty()) lines += "context: " + tags.entries.joinToString(" ") { it.key + "=" + it.value }
        for ((kind, stats) in s.durations) {
            lines += "span." + kind + " count=" + stats.totalCount + " sampled=" + stats.sampleCount +
                " p50ms=" + value(stats.p50Ms) + " p95ms=" + value(stats.p95Ms) + " maxms=" + value(stats.maxMs)
        }
        for ((kind, stats) in s.frameIntervals) {
            lines += "frame_interval." + kind + " callbacks=" + (s.frameCallbacks[kind] ?: 0L) +
                " sampled=" + stats.sampleCount + " p50ms=" + value(stats.p50Ms) +
                " p95ms=" + value(stats.p95Ms) + " maxms=" + value(stats.maxMs)
        }
        lines += "frame_callbacks=" + s.frameCallbacks
        lines += "events=" + s.events + " join_kinds=" + s.joins
        lines += "reported_decoder_drops=" + s.reportedDroppedDecoderFrames +
            " invalid=" + s.invalidSamples + " pending=" + s.activeSpans +
            " evicted=" + s.evictedSpans + " cancelled=" + s.cancelledSpans
        lines += "state_transitions=" + s.states.joinToString(" | ") { (it.from?.name ?: "NONE") + "->" + it.to.name }
        return lines.joinToString("\n")
    }

    fun reset() {
        if (!enabled) return
        synchronized(lock) {
            durations.clear(); intervals.clear(); lastFrameNs.clear(); frameCounts.clear()
            eventCounts.clear(); joinCounts.clear(); pending.clear(); changes.clear()
            previousState = null; invalid = 0L; evicted = 0L; cancelled = 0L; decoderDrops = 0L
        }
    }
}
