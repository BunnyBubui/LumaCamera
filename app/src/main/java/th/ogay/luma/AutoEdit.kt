package th.ogay.luma

/**
 * Automatic editing, step one: the rule planner. The editor measures each clip (loudness every [ClipInput.stepMs], how much
 * the picture changes every [ClipInput.diffStep]) and this turns those numbers into an edit plan: which parts of which clip
 * play, in what order. The plan is plain data, so a language model on the phone or behind an API can later write the same
 * kind of plan from a typed instruction, and the editor applies any plan the same way.
 */
object AutoEdit {
    /**
     * One editing style. [targetMs] the longest the finished video should run (0 = as long as the material allows);
     * [segMin]/[segMax] the length of one shot when shots are picked; [cutSilence] whether quiet stretches go;
     * [quiet], [minQuietMs], [pad] how hard silence is cut; [transition] index into the editor's transitions;
     * [crop] "9:16", "2.39:1" or "" (keep); [filmGroup] a film look group, or null; [captions] captions by default.
     */
    class Style(
        val id: String, val name: String, val note: String,
        val targetMs: Long, val segMin: Long, val segMax: Long,
        val cutSilence: Boolean, val quiet: Float, val minQuietMs: Long, val pad: Long,
        val transition: Int, val crop: String, val filmGroup: String?, val captions: Boolean,
    )

    val STYLES = listOf(
        Style("viral", "ไวรัลสั้น", "ตัดเร็ว เก็บช่วงที่เสียงดังและภาพเปลี่ยน ไม่เกิน 30 วิ แนวตั้ง", 30_000, 1_200, 2_800, true, 0.06f, 400, 80, 0, "9:16", null, true),
        Style("vlog", "Vlog", "ตัดช่วงเงียบ คงลำดับเรื่องเดิม เปลี่ยนคลิปแบบจางดำ", 0, 1_500, 8_000, true, 0.05f, 700, 180, 1, "", null, true),
        Style("talk", "คนพูด / สัมภาษณ์", "ตัดช่วงเงียบแน่น เก็บทุกคำพูด ใส่ซับ", 0, 600, 60_000, true, 0.05f, 350, 110, 0, "", null, true),
        Style("highlight", "ไฮไลต์", "เลือกช่วงเด่นจากคลิปยาว ราว 60 วิ", 60_000, 2_000, 5_000, false, 0.05f, 700, 150, 3, "", null, false),
        Style("cinematic", "ภาพยนตร์", "ช็อตยาว จอกว้าง ลุคฟิล์ม เปลี่ยนแบบจางดำ", 90_000, 3_000, 7_000, false, 0.05f, 700, 150, 1, "2.39:1", "ฟิล์มหนัง", false),
    )

    /** What was measured for one clip. [peaks] is indexed by absolute time in the file (step [stepMs]); [diffs] starts at [from]. */
    class ClipInput(val clip: Int, val from: Long, val to: Long, val peaks: FloatArray, val stepMs: Long, val diffs: FloatArray, val diffStep: Long) {
        val hasAudio get() = peaks.isNotEmpty()
    }

    /** A part of clip [clip] (index into the inputs) from [from] to [to] ms of its file. */
    class Piece(val clip: Int, val from: Long, val to: Long, val score: Float = 0f) { val len get() = to - from }

    class Plan(val style: Style, val pieces: List<Piece>, val beforeMs: Long) {
        val afterMs get() = pieces.sumOf { it.len }
        /** The plan as JSON text: the same shape a model will be asked to write. */
        fun toJson(): String = buildString {
            append("{\"style\":\"").append(style.id).append("\",\"transition\":").append(style.transition)
            append(",\"crop\":\"").append(style.crop).append("\",\"pieces\":[")
            pieces.forEachIndexed { i, p -> if (i > 0) append(','); append("{\"clip\":").append(p.clip).append(",\"from\":").append(p.from).append(",\"to\":").append(p.to).append('}') }
            append("]}")
        }
    }

    /**
     * Builds a plan. [targetMs] overrides the style's length (0 = use the style's). [beats] are moments of the music on the
     * finished timeline; when given, the end of each shot moves to the nearest beat (within 300 ms) so the cuts land on the music.
     */
    fun plan(clips: List<ClipInput>, style: Style, targetMs: Long = 0L, beats: List<Long>? = null): Plan {
        val before = clips.sumOf { (it.to - it.from).coerceAtLeast(0) }
        val target = if (targetMs > 0) targetMs else style.targetMs
        val pick = target in 1 until before
        val all = ArrayList<Piece>()
        for (c in clips) {
            if (c.to - c.from < 300) continue
            // 1. Sound: drop quiet stretches when the style asks (a clip without sound, or one that is silent through, stays whole).
            var ranges = if (style.cutSilence && c.hasAudio) StudioMath.keepRanges(c.peaks, c.stepMs, c.from, c.to, style.quiet, style.minQuietMs, style.pad) else emptyList()
            if (ranges.isEmpty()) ranges = listOf(c.from to c.to)
            // 2. Picture: split at scene changes, so a shot never starts in the middle of one.
            val cuts = StudioMath.sceneCuts(c.diffs, c.diffStep).map { c.from + it }
            val shots = ArrayList<Pair<Long, Long>>()
            for ((a, b) in ranges) {
                var at = a
                for (t in cuts) if (t - at >= style.segMin / 2 && b - t >= style.segMin / 2) { shots += at to t; at = t }
                shots += at to b
            }
            // 3. When shots are picked, long ones are cut into windows no longer than the style's shot length.
            val loudRef = percentile(c.peaks, 0.9f).coerceAtLeast(0.02f); val moveRef = percentile(c.diffs, 0.9f).coerceAtLeast(0.01f)
            for ((a, b) in shots) {
                val n = if (pick && b - a > style.segMax) ((b - a + style.segMax - 1) / style.segMax).toInt() else 1
                val w = (b - a) / n
                for (i in 0 until n) {
                    val s = a + i * w; val e = if (i == n - 1) b else s + w
                    val loud = if (c.hasAudio) (mean(c.peaks, s / c.stepMs, e / c.stepMs) / loudRef).coerceAtMost(1.5f) else 0.5f
                    val move = (mean(c.diffs, (s - c.from) / c.diffStep, (e - c.from) / c.diffStep) / moveRef).coerceAtMost(1.5f)
                    val atCut = if (cuts.any { kotlin.math.abs(it - s) < 120 }) 0.15f else 0f
                    all += Piece(c.clip, s, e, 0.55f * loud + 0.35f * move + atCut)
                }
            }
        }
        var chosen: List<Piece> = all
        if (pick && all.sumOf { it.len } > target) {
            val take = ArrayList<Piece>(); var sum = 0L
            fun add(p: Piece): Boolean { if (p in take || sum + p.len > target + target / 20) return false; take += p; sum += p.len; return true }
            // Every clip gets its best shot first (when there is room), so a montage of many clips shows each of them.
            val perClip = all.groupBy { it.clip }.values.mapNotNull { g -> g.filter { it.len >= style.segMin }.maxByOrNull { it.score } ?: g.maxByOrNull { it.score } }
            if (perClip.size * style.segMin <= target) perClip.sortedByDescending { it.score }.forEach { add(it) }
            all.filter { it.len >= minOf(style.segMin, 800L) }.sortedByDescending { it.score }.forEach { add(it) }
            if (take.isEmpty()) all.maxByOrNull { it.score }?.let { take += Piece(it.clip, it.from, minOf(it.to, it.from + target), it.score) }
            val order = clips.map { it.clip }
            chosen = take.sortedWith(compareBy({ order.indexOf(it.clip) }, { it.from }))
        }
        // Pieces that touch are one piece; scraps under 300 ms go.
        val merged = ArrayList<Piece>()
        for (p in chosen) { val last = merged.lastOrNull()
            if (last != null && last.clip == p.clip && p.from - last.to in 0..60) merged[merged.lastIndex] = Piece(p.clip, last.from, p.to, maxOf(last.score, p.score)) else merged += p }
        var out: List<Piece> = merged.filter { it.len >= 300 }
        if (!beats.isNullOrEmpty()) out = snap(out, clips, beats)
        return Plan(style, out, before)
    }

    /** Moves each shot's end (but the last) to the nearest beat within 300 ms, staying inside its clip and at least 300 ms long. */
    fun snap(pieces: List<Piece>, clips: List<ClipInput>, beats: List<Long>): List<Piece> {
        val sorted = beats.sorted(); var at = 0L; val out = ArrayList<Piece>()
        pieces.forEachIndexed { i, p ->
            if (i == pieces.lastIndex) { out += p; return@forEachIndexed }
            val end = at + p.len
            val b = sorted.minByOrNull { kotlin.math.abs(it - end) }
            val c = clips.firstOrNull { it.clip == p.clip }
            val next = pieces.getOrNull(i + 1)?.takeIf { it.clip == p.clip && it.from >= p.to }
            var to = p.to
            if (b != null && c != null && kotlin.math.abs(b - end) <= 300) to = (p.to + (b - end)).coerceIn(p.from + 300, minOf(c.to, next?.from ?: c.to))
            out += Piece(p.clip, p.from, to, p.score); at += to - p.from
        }
        return out
    }

    private fun mean(a: FloatArray, from: Long, to: Long): Float {
        if (a.isEmpty()) return 0f
        val s = from.toInt().coerceIn(0, a.size - 1); val e = to.toInt().coerceIn(s + 1, a.size)
        var sum = 0f; for (i in s until e) sum += a[i]; return sum / (e - s)
    }
    private fun percentile(a: FloatArray, q: Float): Float {
        val v = a.filter { it > 0f }; if (v.isEmpty()) return 0f
        val s = v.sorted(); return s[((s.size - 1) * q).toInt()]
    }
}
