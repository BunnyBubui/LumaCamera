package th.ogay.luma.ocr

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Pure geometry and decoding steps of the OCR pipeline. Nothing here touches Android or OpenCV,
 * so the JVM unit tests cover it directly. The same steps exist in model/ocr/thai_ocr_ref.py.
 */
internal object OcrLayout {
    const val DET_MAX_SIDE = 1280
    const val DET_MIN_SIDE = 64
    const val DET_THRESH = 0.3
    const val BOX_THRESH = 0.6
    const val UNCLIP_RATIO = 1.5
    const val MIN_BOX_SIDE = 3.0
    const val REC_HEIGHT = 48
    const val DROP_SCORE = 0.5f

    /** Detector input (height, width): long side at most 1280, short side at least 64, multiples of 32. */
    fun detSize(height: Int, width: Int): Pair<Int, Int> {
        require(height > 0 && width > 0) { "Image must not be empty" }
        var scale = min(1.0, DET_MAX_SIDE.toDouble() / max(height, width))
        if (min(height, width) * scale < DET_MIN_SIDE) scale = DET_MIN_SIDE.toDouble() / min(height, width)
        // Math.rint rounds half to even, matching Python's round() in the reference implementation.
        fun snap(v: Int) = max(32, Math.rint(v * scale / 32).toInt() * 32)
        return snap(height) to snap(width)
    }

    /** DBNet unclip distance for a rectangle: area x ratio / perimeter. */
    fun unclipDistance(width: Double, height: Double): Double =
        width * height * UNCLIP_RATIO / (2 * (width + height))

    /** Orders four corners as top-left, top-right, bottom-right, bottom-left. Input and output: x0,y0..x3,y3. */
    fun orderQuad(points: FloatArray): FloatArray {
        require(points.size == 8) { "A quad needs four points" }
        val p = (0 until 4).map { points[2 * it] to points[2 * it + 1] }.sortedBy { it.first }
        val left = p.take(2).sortedBy { it.second }
        val right = p.drop(2).sortedBy { it.second }
        return floatArrayOf(
            left[0].first, left[0].second, right[0].first, right[0].second,
            right[1].first, right[1].second, left[1].first, left[1].second
        )
    }

    /** Output width and height of the rectified crop for an ordered quad. */
    fun cropSize(q: FloatArray): Pair<Int, Int> {
        fun d(a: Int, b: Int) = hypot((q[2 * a] - q[2 * b]).toDouble(), (q[2 * a + 1] - q[2 * b + 1]).toDouble())
        val w = Math.rint(max(d(0, 1), d(3, 2))).toInt()
        val h = Math.rint(max(d(0, 3), d(1, 2))).toInt()
        return max(w, 1) to max(h, 1)
    }

    /** Median direction of the top edge of elongated boxes, in radians; 0 when no box is elongated. */
    fun frameAngle(quads: List<FloatArray>): Double {
        val angles = quads.mapNotNull { q ->
            val dx = (q[2] - q[0]).toDouble(); val dy = (q[3] - q[1]).toDouble()
            val side = hypot((q[6] - q[0]).toDouble(), (q[7] - q[1]).toDouble())
            if (hypot(dx, dy) >= 2 * side) atan2(dy, dx) else null
        }.sorted()
        if (angles.isEmpty()) return 0.0
        val mid = angles.size / 2
        return if (angles.size % 2 == 1) angles[mid] else (angles[mid - 1] + angles[mid]) / 2
    }

    /** Axis-aligned left, top, right, bottom of each quad after rotating the page by -angle. */
    fun extents(quads: List<FloatArray>, angle: Double): List<DoubleArray> {
        val c = cos(-angle); val s = sin(-angle)
        return quads.map { q ->
            var l = Double.MAX_VALUE; var t = Double.MAX_VALUE; var r = -Double.MAX_VALUE; var b = -Double.MAX_VALUE
            for (i in 0 until 4) {
                val x = q[2 * i] * c - q[2 * i + 1] * s
                val y = q[2 * i] * s + q[2 * i + 1] * c
                l = min(l, x); r = max(r, x); t = min(t, y); b = max(b, y)
            }
            doubleArrayOf(l, t, r, b)
        }
    }

    /**
     * Indices of quads to keep. A quad is dropped when a box at least twice as tall covers its centre
     * horizontally and sits within one of its own heights vertically: detached tone marks and dots that
     * the detector boxes separately. Small text far from a large line is kept.
     */
    fun keepAfterFragments(quads: List<FloatArray>, angle: Double): List<Int> {
        val e = extents(quads, angle)
        return e.indices.filter { i ->
            val h = e[i][3] - e[i][1]
            val cx = (e[i][0] + e[i][2]) / 2
            val cy = (e[i][1] + e[i][3]) / 2
            e.indices.none { j ->
                val h2 = e[j][3] - e[j][1]
                j != i && h2 >= 2 * h && cx >= e[j][0] && cx <= e[j][2] && kotlin.math.abs(cy - (e[j][1] + e[j][3]) / 2) < h2
            }
        }
    }

    /**
     * Joins recognised boxes into text. Boxes share a line when their vertical overlap in the page frame
     * exceeds half the smaller height; lines run top to bottom, boxes left to right, separated by one space.
     */
    fun groupLines(quads: List<FloatArray>, texts: List<String>, angle: Double): String {
        require(quads.size == texts.size)
        val e = extents(quads, angle)
        val order = e.indices.sortedWith(compareBy({ (e[it][1] + e[it][3]) / 2 }, { e[it][0] }))
        class Line(var top: Double, var bottom: Double) { val items = mutableListOf<Pair<Double, String>>() }
        val lines = mutableListOf<Line>()
        for (i in order) {
            val l = e[i][0]; val top = e[i][1]; val bottom = e[i][3]
            val line = lines.firstOrNull { min(bottom, it.bottom) - max(top, it.top) > 0.5 * min(bottom - top, it.bottom - it.top) }
            if (line == null) {
                lines += Line(top, bottom).apply { items += l to texts[i] }
            } else {
                line.items += l to texts[i]; line.top = min(line.top, top); line.bottom = max(line.bottom, bottom)
            }
        }
        return lines.sortedBy { it.top }.joinToString("\n") { line -> line.items.sortedBy { it.first }.joinToString(" ") { it.second } }
    }

    /**
     * Column positions that cut a line wider than [maxRatio] x height into pieces the recogniser accepts.
     * Each cut falls on the column with the least ink between 60% and 100% of the allowed width.
     * [ink] holds the amount of ink per column. Returns boundaries including 0 and the width.
     */
    fun splitPoints(ink: IntArray, height: Int, maxRatio: Double): IntArray {
        val width = ink.size
        val limit = (height * maxRatio).toInt()
        if (width <= limit || limit < 2) return intArrayOf(0, width)
        val cuts = mutableListOf(0)
        while (width - cuts.last() > limit) {
            val start = cuts.last()
            val lo = start + (limit * 0.6).toInt()
            val hi = start + limit
            var best = lo
            for (x in lo until hi) if (ink[x] < ink[best]) best = x
            cuts += best
        }
        cuts += width
        return cuts.toIntArray()
    }

    /**
     * Greedy CTC decoding. [probs] is steps x classes, row-major. Class 0 is the blank; class k maps to
     * table[k]. Returns the text and the mean probability of the kept steps (0 when nothing was kept).
     */
    fun ctcDecode(probs: FloatArray, steps: Int, classes: Int, table: List<String>): Pair<String, Float> {
        require(probs.size == steps * classes && table.size == classes) { "Recogniser output does not match the dictionary" }
        val text = StringBuilder()
        var sum = 0.0
        var kept = 0
        var previous = -1
        for (t in 0 until steps) {
            var best = 0
            val row = t * classes
            for (k in 1 until classes) if (probs[row + k] > probs[row + best]) best = k
            if (best != previous && best != 0) {
                text.append(table[best]); sum += probs[row + best]; kept++
            }
            previous = best
        }
        return text.toString() to if (kept == 0) 0f else (sum / kept).toFloat()
    }

    /** Recogniser classes: blank, then one entry per dictionary line, then a space. */
    fun classTable(dictionary: List<String>): List<String> = listOf("") + dictionary + " "
}
