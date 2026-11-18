package th.ogay.luma

import android.os.Looper
import android.view.Choreographer

/**
 * Vsync-paced *UI orchestration* for the studio editor. This does not pace MediaPlayer decoding,
 * guarantee that a video frame rendered, or measure presented-frame FPS.
 *
 * Create/use on the main thread. Keep the frame listener small: read current playback state,
 * update labels and invalidate lightweight views; never seek, decode or perform I/O here.
 *
 * Wiring for VideoEditorActivity (owned by AI-2):
 * - setFrameListener { frameTimeNanos, uiIntervalNanos -> updatePlaybackUi() }
 * - setAttached(true/false) from the editor view's attach callbacks.
 * - setForeground(true) in onResume, false in onPause.
 * - setPlaybackActive(true/false) on play/pause/end; setScrubbing(true/false) on touch scrub.
 * - requestFrame() for a paused seek or other one-shot visual update.
 * - close() in onDestroy. No callback survives close or a detached/background state.
 */
class StudioFrameScheduler internal constructor(private val source: FrameSource) {
    /** Time values are monotonic Choreographer nanoseconds, NOT wall-clock time. */
    internal fun interface FrameCallback { fun onFrame(frameTimeNanos: Long) }

    /** Injectable to test callback lifetime without Android's main looper or real display timing. */
    internal interface FrameSource {
        fun postFrameCallback(callback: FrameCallback)
        fun removeFrameCallback(callback: FrameCallback)
    }

    constructor() : this(AndroidFrameSource())

    private val owner = Thread.currentThread()
    private var listener: ((frameTimeNanos: Long, uiIntervalNanos: Long) -> Unit)? = null
    private var attached = false
    private var foreground = false
    private var playbackActive = false
    private var scrubbing = false
    private var oneShot = false
    private var pending = false
    private var closed = false
    private var lastDeliveredNanos = Long.MIN_VALUE

    /**
     * Optional throttling of UI *work*, not video decoding. Zero (default) emits on each vsync.
     * No fixed 33 ms assumption is used; actual callback timestamps determine the interval.
     */
    var minimumUiIntervalNanos: Long = 0L
        set(value) {
            checkOwner()
            require(value >= 0L) { "minimumUiIntervalNanos must be nonnegative" }
            field = value
        }

    /** A zero interval means the first tick after activation; never interpret it as a video FPS. */
    fun setFrameListener(callback: ((frameTimeNanos: Long, uiIntervalNanos: Long) -> Unit)?) {
        checkOwner()
        if (closed) return
        listener = callback
        sync()
    }

    fun setAttached(value: Boolean) {
        checkOwner()
        if (closed) return
        attached = value
        sync()
    }

    fun setForeground(value: Boolean) {
        checkOwner()
        if (closed) return
        foreground = value
        sync()
    }

    fun setPlaybackActive(value: Boolean) {
        checkOwner()
        if (closed) return
        playbackActive = value
        sync()
    }

    fun setScrubbing(value: Boolean) {
        checkOwner()
        if (closed) return
        scrubbing = value
        sync()
    }

    /** Coalesces repeated paused edits into one UI callback on the next eligible vsync. */
    fun requestFrame() {
        checkOwner()
        if (closed) return
        oneShot = true
        sync()
    }

    /** Stop playback/scrubbing ticks without discarding a reusable listener or lifecycle wiring. */
    fun stop() {
        checkOwner()
        if (closed) return
        playbackActive = false
        scrubbing = false
        oneShot = false
        sync()
    }

    fun close() {
        checkOwner()
        if (closed) return
        closed = true
        listener = null
        sync()
    }

    private fun checkOwner() {
        check(Thread.currentThread() === owner) { "StudioFrameScheduler must run on its creating thread (main thread on Android)" }
    }

    private fun eligible(): Boolean =
        !closed && attached && foreground && listener != null && (playbackActive || scrubbing || oneShot)

    private val callback = FrameCallback { timeNs ->
        pending = false
        if (eligible()) {
            val previous = lastDeliveredNanos
            // Skip a broken or out-of-order timestamp rather than reporting a negative interval.
            if (previous == Long.MIN_VALUE || timeNs > previous) {
                val delta = if (previous == Long.MIN_VALUE) 0L else timeNs - previous
                if (previous == Long.MIN_VALUE || delta >= minimumUiIntervalNanos) {
                    lastDeliveredNanos = timeNs
                    oneShot = false
                    listener?.invoke(timeNs, delta)
                }
            }
        }
        sync() // Re-arm at most one callback, even if the listener changes state during delivery.
    }

    private fun sync() {
        if (!eligible()) {
            if (pending) {
                source.removeFrameCallback(callback)
                pending = false
            }
            lastDeliveredNanos = Long.MIN_VALUE // no huge paused/background/rotation delta
        } else if (!pending) {
            pending = true
            source.postFrameCallback(callback)
        }
    }

    /** One cached Android callback; no per-frame wrapper allocations. */
    private class AndroidFrameSource : FrameSource {
        private val choreographer: Choreographer
        private var queued: FrameCallback? = null
        private val platformCallback = Choreographer.FrameCallback { frameTimeNanos ->
            val client = queued
            queued = null
            client?.onFrame(frameTimeNanos)
        }

        init {
            check(Looper.myLooper() == Looper.getMainLooper()) { "Create StudioFrameScheduler on the main thread" }
            choreographer = Choreographer.getInstance()
        }

        override fun postFrameCallback(callback: FrameCallback) {
            check(queued == null) { "Only one frame callback may be pending" }
            queued = callback
            choreographer.postFrameCallback(platformCallback)
        }

        override fun removeFrameCallback(callback: FrameCallback) {
            if (queued === callback) {
                queued = null
                choreographer.removeFrameCallback(platformCallback)
            }
        }
    }
}
