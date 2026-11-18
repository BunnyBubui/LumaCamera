package th.ogay.luma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioClipHandoffTest {
    @Test fun prepareAndSeekDoNotClaimAVisibleFrame() {
        val h = StudioClipHandoff()
        val token = h.begin()
        assertFalse(h.canAttach(token))
        assertTrue(h.prepared(token, seekRequired = true))
        assertEquals(StudioClipHandoff.State.SEEKING, h.state)
        assertFalse(h.canShow(token))
        assertTrue(h.seekCompleted(token))
        assertTrue(h.canAttach(token))
        assertFalse(h.canShow(token))
        assertTrue(h.beginRendering(token, 100L))
        assertFalse(h.canShow(token))
        assertTrue(h.firstFrameRendered(token))
        assertTrue(h.canShow(token))
    }

    @Test fun noSeekStillNeedsTextureUpdate() {
        val h = StudioClipHandoff()
        val token = h.begin()
        assertTrue(h.prepared(token, seekRequired = false))
        assertFalse(h.canShow(token))
        assertTrue(h.beginRendering(token, 150L))
        assertTrue(h.firstFrameRendered(token))
        assertTrue(h.canShow(token))
    }

    @Test fun stalePlayerCallbackCannotPromoteNewGeneration() {
        val h = StudioClipHandoff()
        val old = h.begin()
        h.prepared(old, seekRequired = false)
        val current = h.begin()
        assertFalse(h.firstFrameRendered(old))
        assertFalse(h.seekCompleted(old))
        assertFalse(h.canShow(old))
        assertTrue(h.prepared(current, seekRequired = false))
        assertTrue(h.beginRendering(current, 0L))
        assertTrue(h.firstFrameRendered(current))
        assertTrue(h.canShow(current))
    }

    @Test fun renderTimeoutIsBoundedAndOnlyAppliesToWaitingFrame() {
        val h = StudioClipHandoff(timeoutMs = 900L)
        val token = h.begin()
        h.prepared(token, seekRequired = true)
        assertFalse(h.renderTimedOut(token, 5000L))
        h.seekCompleted(token)
        h.beginRendering(token, 100L)
        assertFalse(h.renderTimedOut(token, 999L))
        assertTrue(h.renderTimedOut(token, 1000L))
        h.firstFrameRendered(token)
        assertFalse(h.renderTimedOut(token, 2000L))
    }

    @Test fun resetInvalidatesLateCallbacksAndClearsReadiness() {
        val h = StudioClipHandoff()
        val old = h.begin()
        h.prepared(old, seekRequired = false)
        h.beginRendering(old, 20L)
        h.reset()
        assertEquals(StudioClipHandoff.State.IDLE, h.state)
        assertFalse(h.firstFrameRendered(old))
        assertFalse(h.canShow(old))
        val current = h.begin()
        assertTrue(current > old)
    }

    @Test fun duplicateCallbacksDoNotChangeTheState() {
        val h = StudioClipHandoff()
        val token = h.begin()
        assertTrue(h.prepared(token, seekRequired = false))
        assertFalse(h.prepared(token, seekRequired = false))
        assertFalse(h.seekCompleted(token))
        assertTrue(h.beginRendering(token, 1L))
        assertFalse(h.beginRendering(token, 2L))
        assertTrue(h.firstFrameRendered(token))
        assertFalse(h.firstFrameRendered(token))
    }
}
