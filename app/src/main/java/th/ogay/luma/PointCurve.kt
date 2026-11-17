package th.ogay.luma

import kotlin.math.abs

/**
 * A tone or speed curve the user can add points to and remove points from. It is stored as nine slots at x = 0, ⅛, … 1;
 * a slot holding a negative number is not used, so a curve has between two (the ends) and nine points. The old curves with
 * five points at 0, ¼, ½, ¾ and 1 are the slots 0, 2, 4, 6 and 8 of the same layout.
 */
object PointCurve {
    const val SLOTS = 9
    private const val OFF = -1f

    fun identity() = FloatArray(SLOTS) { if (it % 2 == 0) it / 8f else OFF }
    /** [sets] curves one after the other, each a straight line. */
    fun identitySets(sets: Int) = FloatArray(SLOTS * sets) { identity()[it % SLOTS] }
    fun fromFive(f: FloatArray, at: Int = 0) = FloatArray(SLOTS) { if (it % 2 == 0) f[at + it / 2] else OFF }
    fun slotsOf(p: FloatArray, at: Int): List<Int> = (0 until SLOTS).filter { p[at + it] >= 0f }
    fun isIdentity(p: FloatArray, at: Int) = slotsOf(p, at).all { abs(p[at + it] - it / 8f) < 1e-3f }
    fun reset(p: FloatArray, at: Int) { identity().copyInto(p, at) }
    fun count(p: FloatArray, at: Int) = slotsOf(p, at).size

    /** Saved curves of either layout (five points each or nine slots each), or null when [saved] is neither. */
    fun upgrade(saved: FloatArray?, sets: Int): FloatArray? {
        if (saved == null) return null
        if (saved.size == SLOTS * sets) return saved.copyOf()
        if (saved.size == 5 * sets) return FloatArray(SLOTS * sets) { k -> val s = k / SLOTS; val i = k % SLOTS; if (i % 2 == 0) saved[s * 5 + i / 2] else OFF }
        return null
    }

    /** The curve's output at [x] (0..1), smooth and never overshooting between points. */
    fun eval(p: FloatArray, at: Int, x: Float): Float {
        val idx = slotsOf(p, at); val n = idx.size
        if (n < 2) return x.coerceIn(0f, 1f)
        val xs = FloatArray(n) { idx[it] / 8f }; val ys = FloatArray(n) { p[at + idx[it]] }
        val h = FloatArray(n - 1) { xs[it + 1] - xs[it] }; val d = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / h[it] }
        val m = FloatArray(n)
        m[0] = d[0]; m[n - 1] = d[n - 2]
        for (k in 1 until n - 1) m[k] = if (d[k - 1] * d[k] <= 0f) 0f else { val w1 = 2 * h[k] + h[k - 1]; val w2 = h[k] + 2 * h[k - 1]; (w1 + w2) / (w1 / d[k - 1] + w2 / d[k]) }
        val t0 = x.coerceIn(0f, 1f)
        var i = 0; while (i < n - 2 && t0 > xs[i + 1]) i++
        val t = (t0 - xs[i]) / h[i]; val t2 = t * t; val t3 = t2 * t
        val out = (2 * t3 - 3 * t2 + 1) * ys[i] + (t3 - 2 * t2 + t) * h[i] * m[i] + (-2 * t3 + 3 * t2) * ys[i + 1] + (t3 - t2) * h[i] * m[i + 1]
        return out.coerceIn(0f, 1f)
    }
    fun sample(p: FloatArray, at: Int, n: Int) = FloatArray(n) { eval(p, at, it / (n - 1f)) }

    /** Where a new point would best go: the middle of the widest gap between two points. */
    fun widestGap(p: FloatArray, at: Int): Float {
        val s = slotsOf(p, at); var best = 0.5f; var gap = -1
        for (k in 0 until s.size - 1) if (s[k + 1] - s[k] > gap) { gap = s[k + 1] - s[k]; best = (s[k] + s[k + 1]) / 16f }
        return best
    }
    /** Adds a point on the curve at the free slot nearest [near]; returns its slot, or -1 when all nine are used. */
    fun add(p: FloatArray, at: Int, near: Float): Int {
        val free = (0 until SLOTS).filter { p[at + it] < 0f }
        val slot = free.minByOrNull { abs(it / 8f - near) } ?: return -1
        p[at + slot] = eval(p, at, slot / 8f); return slot
    }
    /** Takes an inner point away (the two ends stay, and so do at least two points). */
    fun remove(p: FloatArray, at: Int, slot: Int): Boolean {
        if (slot !in 1 until SLOTS - 1 || p[at + slot] < 0f || count(p, at) <= 2) return false
        p[at + slot] = OFF; return true
    }
}
