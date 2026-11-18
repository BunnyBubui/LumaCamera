package th.ogay.luma

/**
 * State of a preloaded MediaPlayer for the next editor clip.
 *
 * A prepared player (even after seek completion) is NOT a displayed frame. The caller must
 * attach a Surface, start decoding and observe TextureView.onSurfaceTextureUpdated on that
 * very target before making its picture visible. MediaPlayer's VIDEO_RENDERING_START event
 * alone does not prove that a frame reached a surface after setSurface().
 *
 * No Android dependencies: the state machine can be exercised with deterministic JVM tests.
 */
internal class StudioClipHandoff(private val timeoutMs: Long = 1_000L) {
    enum class State { IDLE, PREPARING, SEEKING, SEEK_READY, WAITING_FRAME, FRAME_READY }

    var state: State = State.IDLE
        private set
    var generation: Long = 0L
        private set
    private var renderingSinceMs = -1L

    fun begin(): Long {
        generation++
        state = State.PREPARING
        renderingSinceMs = -1L
        return generation
    }

    fun reset() {
        generation++
        state = State.IDLE
        renderingSinceMs = -1L
    }

    fun prepared(token: Long, seekRequired: Boolean): Boolean {
        if (token != generation || state != State.PREPARING) return false
        state = if (seekRequired) State.SEEKING else State.SEEK_READY
        return true
    }

    fun seekCompleted(token: Long): Boolean {
        if (token != generation || state != State.SEEKING) return false
        state = State.SEEK_READY
        return true
    }

    fun beginRendering(token: Long, uptimeMs: Long): Boolean {
        if (token != generation || state != State.SEEK_READY) return false
        state = State.WAITING_FRAME
        renderingSinceMs = uptimeMs
        return true
    }

    fun firstFrameRendered(token: Long): Boolean {
        if (token != generation || state != State.WAITING_FRAME) return false
        state = State.FRAME_READY
        return true
    }

    fun canAttach(token: Long) = token == generation && state == State.SEEK_READY
    fun canShow(token: Long) = token == generation && state == State.FRAME_READY

    fun renderTimedOut(token: Long, uptimeMs: Long): Boolean =
        token == generation && state == State.WAITING_FRAME &&
            renderingSinceMs >= 0 && uptimeMs - renderingSinceMs >= timeoutMs
}
