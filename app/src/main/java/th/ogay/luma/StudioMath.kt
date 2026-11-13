package th.ogay.luma

/**
 * The arithmetic of the video editor that needs no Android: keyframe gliding, fades, subtitle files, the playing
 * order of clips and which lane a bar goes on. Kept apart so it can be checked by ordinary unit tests.
 */
object StudioMath {
    /**
     * Values at time [t] from keyframes at [times] (rising) with [values] each. Before the first keyframe the first
     * values hold, after the last the last; in between the values glide with a soft start and stop.
     */
    /** Easing between two keyframes: 0 soft start and stop, 1 constant speed, 2 slow start, 3 slow stop, 4 hold (jump at the next keyframe). */
    const val EASE_SMOOTH = 0; const val EASE_LINEAR = 1; const val EASE_IN = 2; const val EASE_OUT = 3; const val EASE_HOLD = 4
    fun ease(kind: Int, f: Float): Float = when (kind) { EASE_LINEAR -> f; EASE_IN -> f * f * f; EASE_OUT -> 1 - (1 - f) * (1 - f) * (1 - f); EASE_HOLD -> if (f < 1f) 0f else 1f; else -> f * f * (3 - 2 * f) }
    fun glide(times: LongArray, values: Array<FloatArray>, t: Long, eases: IntArray? = null): FloatArray {
        require(times.isNotEmpty() && times.size == values.size)
        if (t <= times.first()) return values.first().copyOf()
        if (t >= times.last()) return values.last().copyOf()
        var i = 0; while (i < times.size - 2 && times[i + 1] <= t) i++
        val a = values[i]; val b = values[i + 1]
        val f = ease(eases?.getOrNull(i) ?: EASE_SMOOTH, ((t - times[i]).toFloat() / (times[i + 1] - times[i]).coerceAtLeast(1)).coerceIn(0f, 1f))
        return FloatArray(a.size) { a[it] + (b[it] - a[it]) * f }
    }

    /** 0..1: how much of a piece [length] ms long is let through at [t] ms into it, with a fade in and a fade out. */
    fun fade(t: Long, length: Long, fadeIn: Long, fadeOut: Long): Float {
        var f = 1f
        if (fadeIn > 0 && t < fadeIn) f = (t.toFloat() / fadeIn).coerceIn(0f, 1f)
        if (fadeOut > 0 && t > length - fadeOut) f = minOf(f, ((length - t).toFloat() / fadeOut).coerceIn(0f, 1f))
        return f
    }

    /** Clip numbers in playing order when [lead] of the [extras] added clips play before clip 0 (the clip the editor opened with). */
    fun order(lead: Int, extras: Int): List<Int> { val m = lead.coerceIn(0, extras.coerceAtLeast(0)); return (1..m).toList() + 0 + ((m + 1)..extras).toList() }

    /** Lane for each bar ([start, end) in ms): the first lane where it does not overlap an earlier bar, at most [lanes] lanes (then the last one is shared). */
    fun pack(bars: List<Pair<Long, Long>>, lanes: Int): IntArray {
        val used = ArrayList<MutableList<Pair<Long, Long>>>(); val out = IntArray(bars.size)
        bars.forEachIndexed { i, b ->
            val free = used.indexOfFirst { lane -> lane.none { it.first < b.second && b.first < it.second } }
            val at = if (free >= 0) free else if (used.size < lanes.coerceAtLeast(1)) { used += mutableListOf<Pair<Long, Long>>(); used.size - 1 } else used.size - 1
            used[at] += b; out[i] = at
        }
        return out
    }

    /** One subtitle line: shown from [from] to [to] ms. */
    data class Cue(val from: Long, val to: Long, val text: String)
    private val srtTime = Regex("""(\d+):(\d+):(\d+)[,.](\d+)\s*-->\s*(\d+):(\d+):(\d+)[,.](\d+)""")
    /** Reads the text of an .srt file. Lines without a valid time or without words are skipped; formatting tags are removed. */
    fun parseSrt(text: String): List<Cue> = text.removePrefix(0xFEFF.toChar().toString()).replace("\r", "").split(Regex("""\n\s*\n""")).mapNotNull { block ->
        val rows = block.trim().lines(); val ti = rows.indexOfFirst { srtTime.containsMatchIn(it) }; if (ti < 0) return@mapNotNull null
        val g = srtTime.find(rows[ti])!!.groupValues.drop(1).map { it.toLong() }
        // Milliseconds may be written with fewer than three digits ("1,5" means 500 ms).
        fun ms(v: Long, digits: Int) = if (digits >= 3) v else if (digits == 2) v * 10 else v * 100
        val d = srtTime.find(rows[ti])!!.groupValues
        val from = g[0] * 3600000 + g[1] * 60000 + g[2] * 1000 + ms(g[3], d[4].length); val to = g[4] * 3600000 + g[5] * 60000 + g[6] * 1000 + ms(g[7], d[8].length)
        val words = rows.drop(ti + 1).joinToString("\n").replace(Regex("<[^>]+>"), "").trim()
        if (words.isEmpty() || to <= from) null else Cue(from, to, words)
    }
    fun writeSrt(cues: List<Cue>): String {
        fun stamp(ms: Long) = String.format(java.util.Locale.US, "%02d:%02d:%02d,%03d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000)
        return cues.sortedBy { it.from }.mapIndexed { n, c -> "${n + 1}\n${stamp(c.from)} --> ${stamp(c.to)}\n${c.text}\n" }.joinToString("\n")
    }

    // ---- Automatic analysis ----
    /**
     * Scene changes from the colour difference between each sampled frame and the one before ([diff], 0..1, one per
     * [stepMs]): a change is a jump well above the typical difference, at least [minGapMs] after the previous one.
     * Returns times in ms from the first sample.
     */
    fun sceneCuts(diff: FloatArray, stepMs: Long, minGapMs: Long = 800): List<Long> {
        if (diff.size < 3) return emptyList()
        val sorted = diff.sorted(); val typical = sorted[sorted.size / 2]
        val bar = maxOf(0.18f, typical * 4f)
        val out = ArrayList<Long>(); var last = -minGapMs
        for (i in 1 until diff.size) { val t = i * stepMs; if (diff[i] >= bar && t - last >= minGapMs && diff[i] >= diff[i - 1]) { out += t; last = t } }
        return out
    }
    /**
     * Parts of [from, to) (ms) to keep when quiet stretches are taken out. [peaks] is loudness 0..1 per [stepMs] from
     * the start of the file. A stretch counts as quiet when it stays under [quiet] for at least [minMs]; [padMs] of
     * the quiet is kept at each side so words are not clipped. Pieces shorter than 300 ms are dropped.
     */
    fun keepRanges(peaks: FloatArray, stepMs: Long, from: Long, to: Long, quiet: Float = 0.06f, minMs: Long = 700, padMs: Long = 150): List<Pair<Long, Long>> {
        val silent = ArrayList<Pair<Long, Long>>(); var runStart = -1L
        var t = from - from % stepMs
        while (t < to) {
            val v = peaks.getOrElse((t / stepMs).toInt()) { 0f }
            if (v < quiet) { if (runStart < 0) runStart = t } else { if (runStart >= 0 && t - runStart >= minMs) silent += runStart to t; runStart = -1 }
            t += stepMs
        }
        if (runStart >= 0 && to - runStart >= minMs) silent += runStart to to
        val keep = ArrayList<Pair<Long, Long>>(); var at = from
        for ((a, b) in silent) { val cutA = maxOf(a + padMs, from); val cutB = minOf(b - padMs, to); if (cutB <= cutA) continue
            if (cutA - at >= 300) keep += at to cutA; at = cutB }
        if (to - at >= 300) keep += at to to
        return keep
    }
    /** Beats: moments where the loudness rises sharply above the average of the last half second. Times in ms. */
    fun beats(peaks: FloatArray, stepMs: Long, minGapMs: Long = 280): List<Long> {
        val out = ArrayList<Long>(); val window = (500 / stepMs).toInt().coerceAtLeast(2); var last = -minGapMs
        for (i in window until peaks.size) {
            var avg = 0f; for (k in i - window until i) avg += peaks[k]; avg /= window
            val t = i * stepMs
            if (peaks[i] > 0.3f && peaks[i] > avg * 1.45f && peaks[i] >= peaks[i - 1] && t - last >= minGapMs) { out += t; last = t }
        }
        return out
    }

    // ---- Speed curves ----
    /** A speed curve is 5 points (0..1) across the clip; 0.5 is the clip's own speed, 1 is 8× faster, 0 is 8× slower. It plays as this many steps of steady speed. */
    const val CURVE_PARTS = 10
    fun curveMul(curve: FloatArray, x: Float): Float = Math.pow(2.0, 3.0 * (StudioGrade.curve(curve, 0, x) * 2 - 1)).toFloat()
    fun partSpeeds(base: Float, curve: FloatArray?): FloatArray = FloatArray(CURVE_PARTS) { k -> base * (if (curve == null) 1f else curveMul(curve, (k + 0.5f) / CURVE_PARTS)) }
    /** Time a clip [src] ms of its file long takes in the finished video. */
    fun outLen(src: Long, base: Float, curve: FloatArray?): Long {
        if (curve == null) return (src / base).toLong()
        val sp = partSpeeds(base, curve); var t = 0.0; for (k in 0 until CURVE_PARTS) t += src.toDouble() / CURVE_PARTS / sp[k]; return t.toLong()
    }
    /** Where [off] ms into the file part falls in the finished clip. */
    fun srcToOut(off: Long, src: Long, base: Float, curve: FloatArray?): Long {
        if (curve == null) return (off / base).toLong()
        val sp = partSpeeds(base, curve); val part = src.toDouble() / CURVE_PARTS; var t = 0.0; var left = off.toDouble().coerceIn(0.0, src.toDouble())
        for (k in 0 until CURVE_PARTS) { val d = minOf(left, part); t += d / sp[k]; left -= d; if (left <= 0) break }
        return t.toLong()
    }
    /** The reverse of [srcToOut]: which ms of the file part plays [t] ms into the finished clip. */
    fun outToSrc(t: Long, src: Long, base: Float, curve: FloatArray?): Long {
        if (curve == null) return (t * base).toLong()
        val sp = partSpeeds(base, curve); val part = src.toDouble() / CURVE_PARTS; var left = t.toDouble().coerceAtLeast(0.0); var s = 0.0
        for (k in 0 until CURVE_PARTS) { val d = part / sp[k]; if (left <= d) return (s + left * sp[k]).toLong().coerceAtMost(src); left -= d; s += part }
        return src
    }
    fun speedAt(off: Long, src: Long, base: Float, curve: FloatArray?): Float {
        if (curve == null || src <= 0) return base
        return partSpeeds(base, curve)[((off.toDouble() / src) * CURVE_PARTS).toInt().coerceIn(0, CURVE_PARTS - 1)]
    }
}
