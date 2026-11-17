package th.ogay.luma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PointCurveTest {
    @Test fun straightCurveIsIdentityAndStaysStraightAfterAddingAPoint() {
        val p = PointCurve.identity(); assertTrue(PointCurve.isIdentity(p, 0)); assertEquals(3, PointCurve.count(p, 0).coerceAtMost(3))
        for (i in 0..10) assertEquals(i / 10f, PointCurve.eval(p, 0, i / 10f), 1e-4f)
        val slot = PointCurve.add(p, 0, PointCurve.widestGap(p, 0)); assertTrue(slot in 1..7)
        assertEquals(6, PointCurve.count(p, 0)); assertTrue(PointCurve.isIdentity(p, 0))
        assertEquals(0.5f, PointCurve.eval(p, 0, 0.5f), 1e-3f)
    }
    @Test fun addedPointBendsTheCurveAndRemovingItRestoresIt() {
        val p = PointCurve.identity(); val s = PointCurve.add(p, 0, 0.125f); assertEquals(1, s)
        p[s] = 0.5f; assertEquals(0.5f, PointCurve.eval(p, 0, 0.125f), 1e-4f); assertFalse(PointCurve.isIdentity(p, 0))
        assertTrue(PointCurve.remove(p, 0, s)); assertTrue(PointCurve.isIdentity(p, 0))
    }
    @Test fun endsAndTheLastTwoPointsCannotBeRemovedAndNineIsTheMost() {
        val p = PointCurve.identity()
        assertFalse(PointCurve.remove(p, 0, 0)); assertFalse(PointCurve.remove(p, 0, 8))
        while (PointCurve.add(p, 0, 0.5f) >= 0) { }
        assertEquals(9, PointCurve.count(p, 0))
        for (k in 1..7) assertTrue(PointCurve.remove(p, 0, k)); assertEquals(2, PointCurve.count(p, 0))
        assertTrue(PointCurve.isIdentity(p, 0))
    }
    @Test fun curveNeverOvershootsBetweenPoints() {
        val p = PointCurve.identity(); p[2] = 0.2f; p[4] = 0.9f; p[6] = 0.95f; PointCurve.add(p, 0, 0.125f)
        var last = -1f; for (i in 0..200) { val v = PointCurve.eval(p, 0, i / 200f); assertTrue(v in 0f..1f); assertTrue(v >= last - 1e-5f); last = v }
    }
    @Test fun oldFivePointCurvesUpgradeToTheSameCurve() {
        val old = floatArrayOf(0f, 0.1f, 0.5f, 0.9f, 1f, 0f, .25f, .5f, .75f, 1f)
        val up = PointCurve.upgrade(old, 2)!!; assertEquals(18, up.size)
        assertEquals(0.1f, PointCurve.eval(up, 0, 0.25f), 1e-5f); assertTrue(PointCurve.isIdentity(up, 9))
        assertEquals(18, PointCurve.upgrade(up, 2)!!.size)
    }
}
