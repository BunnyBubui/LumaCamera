package th.ogay.luma.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CameraSessionGuardTest {
    @Test fun secondBackendCannotOpenWhileFirstHoldsCamera() {
        val g = CameraSessionGuard()
        assertTrue(g.acquire(CameraBackendKind.CAMERAX))
        assertTrue(g.acquire(CameraBackendKind.CAMERAX))   // re-binding the same backend is fine
        assertFalse(g.acquire(CameraBackendKind.CAMERA_UNIT))
        assertEquals(CameraBackendKind.CAMERAX, g.owner())
    }

    @Test fun switchingNeedsReleaseFirst() {
        val g = CameraSessionGuard()
        g.acquire(CameraBackendKind.CAMERA_UNIT)
        assertTrue(g.release(CameraBackendKind.CAMERA_UNIT))
        assertTrue(g.acquire(CameraBackendKind.CAMERAX))
    }

    @Test fun lateReleaseFromOldBackendDoesNotFreeNewSession() {
        val g = CameraSessionGuard()
        g.acquire(CameraBackendKind.CAMERAX)
        assertFalse(g.release(CameraBackendKind.CAMERA_UNIT))
        assertEquals(CameraBackendKind.CAMERAX, g.owner())
    }

    @Test fun fallbackReleasesCameraUnitThroughBackend() {
        val g = CameraSessionGuard()
        val backend = OppoCameraUnitBackend(probe = { CameraUnitProbe.NOT_PROBED }, bridge = NoBridge, guard = g)
        g.acquire(CameraBackendKind.CAMERA_UNIT)
        backend.release()
        assertNull(g.owner())
        assertTrue(g.acquire(CameraBackendKind.CAMERAX))
    }

    @Test fun concurrentAcquireHasExactlyOneWinner() {
        repeat(50) {
            val g = CameraSessionGuard()
            val pool = Executors.newFixedThreadPool(2)
            val start = CountDownLatch(1)
            val wins = AtomicInteger()
            for (kind in CameraBackendKind.entries) pool.execute { start.await(); if (g.acquire(kind)) wins.incrementAndGet() }
            start.countDown()
            pool.shutdown(); pool.awaitTermination(5, TimeUnit.SECONDS)
            assertEquals(1, wins.get())
        }
    }

    private object NoBridge : CameraUnitBridge {
        override val bundled = false
        override fun probe(context: android.content.Context, authCode: String?, timeoutMs: Long) = CameraUnitProbe(CameraUnitStatus.NOT_BUNDLED)
        override fun release() {}
    }
}
