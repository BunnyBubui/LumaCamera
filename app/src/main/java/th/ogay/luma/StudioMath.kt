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
    fun glide(times: LongArray, values: Array<FloatArray>, t: Long): FloatArray {
        require(times.isNotEmpty() && times.size == values.size)
        if (t <= times.first()) return values.first().copyOf()
        if (t >= times.last()) return values.last().copyOf()
        var i = 0; while (i < times.size - 2 && times[i + 1] <= t) i++
        val a = values[i]; val b = values[i + 1]
        var f = ((t - times[i]).toFloat() / (times[i + 1] - times[i]).coerceAtLeast(1)).coerceIn(0f, 1f); f = f * f * (3 - 2 * f)
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
    fun parseSrt(text: String): List<Cue> = text.removePrefix("﻿").replace("\r", "").split(Regex("""\n\s*\n""")).mapNotNull { block ->
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
}
