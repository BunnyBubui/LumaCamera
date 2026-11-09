package th.ogay.luma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhiteBalanceTest {
    @Test fun planckLocusMatchesKnownPoints() {
        val a = WhiteBalance.planckXy(2856.0); assertEquals(0.4476, a[0], 0.002); assertEquals(0.4074, a[1], 0.002)
        val d = WhiteBalance.planckXy(6500.0); assertEquals(0.3135, d[0], 0.002); assertEquals(0.3237, d[1], 0.002)
    }
    @Test fun identitySensorGivesInverseOfWhite() {
        val id = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val w = WhiteBalance.whiteXyz(5000.0); val g = WhiteBalance.gains(id, null, 5000.0)
        assertEquals(1 / w[0], g[0], 1e-6); assertEquals(1.0, g[1], 0.0); assertEquals(1 / w[2], g[2], 1e-6)
    }
    @Test fun warmerLightNeedsMoreBlueGain() {
        val id = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        assertTrue(WhiteBalance.gains(id, null, 3000.0)[2] > WhiteBalance.gains(id, null, 7000.0)[2])
        assertTrue(WhiteBalance.gains(id, null, 3000.0)[0] < WhiteBalance.gains(id, null, 7000.0)[0])
    }
    @Test fun magentaTintLowersGreenRelativeToRedAndBlue() {
        val id = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val flat = WhiteBalance.gains(id, null, 5500.0); val magenta = WhiteBalance.gains(id, null, 5500.0, 30.0)
        assertTrue(magenta[0] > flat[0]); assertTrue(magenta[2] > flat[2])
    }
    @Test fun interpolationWeightFollowsInverseTemperature() {
        assertEquals(1.0, WhiteBalance.weight(2856.0, 2856.0, 6504.0), 1e-9)
        assertEquals(0.0, WhiteBalance.weight(6504.0, 2856.0, 6504.0), 1e-9)
        assertEquals(0.0, WhiteBalance.weight(9000.0, 2856.0, 6504.0), 1e-9)
    }
    @Test fun forwardMatrixKeepsWhiteNeutral() {
        // D50 white as the forward matrix image of camera white.
        val fm = doubleArrayOf(0.9642, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.8249)
        val m = WhiteBalance.cameraToSrgb(fm)
        val out = WhiteBalance.mulVec(m, doubleArrayOf(1.0, 1.0, 1.0))
        out.forEach { assertEquals(1.0, it, 1e-6) }
    }
    @Test fun contrastCurveIsMonotoneAndSpansRange() {
        for (level in -3..3) {
            val c = ToneCurves.contrast(level)
            for (i in 1 until 32) assertTrue(c[i * 2 + 1] >= c[i * 2 - 1])
            if (level >= 0) { assertEquals(0f, c[1], 1e-4f); assertEquals(1f, c[63], 1e-4f) }
        }
    }
}
