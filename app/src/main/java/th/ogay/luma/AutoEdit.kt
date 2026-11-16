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
    class ClipInput(val clip: Int, val from: Long, val to: Long, val peaks: FloatArray, val stepMs: Long, val diffs: FloatArray, val diffStep: Long,
                    /** Words spoken in this clip (from captions), as (from, to, text) in ms of the file. */
                    val speech: List<Triple<Long, Long, String>> = emptyList()) {
        val hasAudio get() = peaks.isNotEmpty()
    }

    /** A part of clip [clip] (index into the inputs) from [from] to [to] ms of its file. */
    class Piece(val clip: Int, val from: Long, val to: Long, val score: Float = 0f) { val len get() = to - from }

    class Plan(val style: Style, val pieces: List<Piece>, val beforeMs: Long,
               val transition: Int = style.transition, val crop: String = style.crop, val filmGroup: String? = style.filmGroup,
               /** Captions asked for by the model (null = the user's switch decides); an opening title ("" = none); who planned it. */
               val captions: Boolean? = null, val title: String = "", val by: String = "",
               /** Music: "auto" (the style's), "none", a library track id or an https link; sound effects (null = the user's switch). */
               val music: String = "auto", val sfx: Boolean? = null) {
        val afterMs get() = pieces.sumOf { it.len }
        /** The plan as JSON text: the same shape a model will be asked to write. */
        fun toJson(): String = buildString {
            append("{\"style\":\"").append(style.id).append("\",\"transition\":").append(transition)
            append(",\"crop\":\"").append(crop).append("\",\"pieces\":[")
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
        var out = planPieces(clips, style, target in 1 until before, target)
        if (!beats.isNullOrEmpty()) out = snap(out, clips, beats)
        return Plan(style, out, before)
    }

    /** The pieces of a plan: split, scored and, when [pick], chosen up to [target] ms. */
    private fun planPieces(clips: List<ClipInput>, style: Style, pick: Boolean, target: Long): List<Piece> {
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
        // Pieces that touch are one piece (except when every candidate is wanted as a separate shot); scraps under 300 ms go.
        if (pick && target > clips.sumOf { it.to - it.from }) return chosen.filter { it.len >= 300 }
        val merged = ArrayList<Piece>()
        for (p in chosen) { val last = merged.lastOrNull()
            if (last != null && last.clip == p.clip && p.from - last.to in 0..60) merged[merged.lastIndex] = Piece(p.clip, last.from, p.to, maxOf(last.score, p.score)) else merged += p }
        return merged.filter { it.len >= 300 }
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

    // ---------- Typed instructions: a language model writes a short brief, the planner does the cutting ----------

    val TRANSITION_IDS = listOf("cut", "fade", "flash", "zoom", "slide", "wipe", "blur")
    val CROP_IDS = listOf("keep", "9:16", "1:1", "16:9", "2.39:1")
    val ORDER_IDS = listOf("time", "reverse", "best", "keep")

    /** What the model decided. [keep]/[drop] are shot numbers from [shots]; [seconds] 0 = the style's length. */
    class Brief(val style: Style, val seconds: Int, val crop: String, val transition: Int, val captions: Boolean, val film: Boolean,
                val order: String, val keep: List<Int>, val drop: List<Int>, val title: String,
                val music: String = "auto", val sfx: Boolean? = null)

    /**
     * Neutral shots the model can point at by number: quiet stretches out, split at scene changes and into pieces of at most
     * a few seconds. At most [max] shots, so the list fits in a small model's memory (the pieces grow longer when there are many).
     */
    fun shots(clips: List<ClipInput>, max: Int = 60): List<Piece> {
        var segMax = 6_000L; var out: List<Piece>
        do {
            val st = Style("shots", "", "", 1, 1_500, segMax, true, 0.05f, 700, 150, 0, "", null, false)
            out = candidates(clips, st)
            segMax *= 2
        } while (out.size > max && segMax < 3_600_000L)
        return out
    }

    /** Every candidate piece of [style] before any is picked (the same split and scores [plan] uses when it picks). */
    private fun candidates(clips: List<ClipInput>, style: Style): List<Piece> {
        val huge = clips.sumOf { it.to - it.from } * 2 + 10_000
        // A target just under the total makes plan() split long shots without leaving any out: ask for everything but cut.
        return planPieces(clips, style, pick = true, target = huge)
    }

    /** The shot list as the model reads it: number, clip, seconds, loudness and motion 0..9, and any words spoken. */
    fun describe(clips: List<ClipInput>, shots: List<Piece>): String = buildString {
        val total = clips.sumOf { it.to - it.from }
        append("Footage: ").append(clips.size).append(" clip(s), ").append(sec(total)).append(" s in all.\n")
        append("Shots (id: clip, start-end in seconds of that clip's file, L=loudness 0-9, M=motion 0-9, speech):\n")
        shots.forEachIndexed { i, p ->
            val c = clips.firstOrNull { it.clip == p.clip }
            val (l, m) = c?.let { levels(it, p) } ?: (0 to 0)
            append(i).append(": c").append(p.clip + 1).append(' ').append(sec(p.from)).append('-').append(sec(p.to)).append(" L").append(l).append(" M").append(m)
            val words = c?.speech?.filter { it.second > p.from && it.first < p.to }?.joinToString(" ") { it.third }?.replace('"', '\'')?.replace('\n', ' ')?.trim().orEmpty()
            if (words.isNotEmpty()) append(" \"").append(words.take(70)).append('"')
            append('\n')
        }
    }

    /** The whole request for the model. */
    fun prompt(instruction: String, clips: List<ClipInput>, shots: List<Piece>, styleHint: Style, music: List<Pair<String, String>> = emptyList()): String = buildString {
        append("You are a professional video editor working inside a phone app. Plan an edit of the user's footage.\n\n")
        append(describe(clips, shots)).append('\n')
        append("Styles: viral = fast cuts, at most 30 s, vertical. vlog = remove silence, keep the story. talk = keep every spoken word, captions. ")
        append("highlight = the best moments, about 60 s. cinematic = long calm shots, wide picture, film look.\n")
        append("The user picked \"").append(styleHint.id).append("\" as a starting point; change it if the request says otherwise.\n")
        if (music.isNotEmpty()) { append("Music library (id: description):\n"); music.forEach { append(it.first).append(": ").append(it.second).append('\n') } }
        append("User request: \"").append(instruction.replace('"', '\'').take(600)).append("\"\n\n")
        append("Reply with one JSON object only, no other text:\n")
        append("{\"style\": viral|vlog|talk|highlight|cinematic, \"seconds\": target length in seconds (0 = let the style decide), ")
        append("\"crop\": keep|9:16|1:1|16:9|2.39:1, \"transition\": cut|fade|flash|zoom|slide|wipe|blur, \"captions\": true|false, \"film\": true|false, ")
        append("\"order\": time|reverse|best|keep, \"keep\": [shot ids to use, in order when order is keep; [] = let the app choose], ")
        append("\"drop\": [shot ids to leave out], \"music\": auto|none|a music library id, \"sfx\": true|false (whooshes on cuts, a pop on the title), ")
        append("\"title\": a short opening title in the user's language, or \"\"}\n")
        append("List shot ids in keep or drop only when the request is about specific content, people, words or moments.")
    }

    /** Grammar for llama.cpp, so a small model can only answer with a valid brief. */
    fun grammar(musicIds: List<String> = emptyList()): String {
        fun alt(v: List<String>) = v.joinToString(" | ") { "\"\\\"" + it + "\\\"\"" }
        return """
root ::= "{" ws "\"style\":" ws style "," ws "\"seconds\":" ws num "," ws "\"crop\":" ws crop "," ws "\"transition\":" ws trans "," ws "\"captions\":" ws bool "," ws "\"film\":" ws bool "," ws "\"order\":" ws order "," ws "\"keep\":" ws ids "," ws "\"drop\":" ws ids "," ws "\"music\":" ws music "," ws "\"sfx\":" ws bool "," ws "\"title\":" ws str ws "}"
style ::= ${alt(STYLES.map { it.id })}
crop ::= ${alt(CROP_IDS)}
trans ::= ${alt(TRANSITION_IDS)}
order ::= ${alt(ORDER_IDS)}
music ::= ${alt(listOf("auto", "none") + musicIds.filter { it.matches(Regex("[A-Za-z0-9_]+")) })}
bool ::= "true" | "false"
num ::= [0-9] | [0-9] [0-9] | [0-9] [0-9] [0-9] | [0-9] [0-9] [0-9] [0-9]
ids ::= "[" ws ( num ( "," ws num )* )? ws "]"
str ::= "\"" [^"\\\n]* "\""
ws ::= " "?
""".trim()
    }

    /** Reads a brief out of a model's answer (extra text around the JSON is ignored). Null when there is no usable object. */
    fun parseBrief(answer: String, fallback: Style): Brief? {
        val a = answer.indexOf('{'); val b = answer.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        val j = answer.substring(a, b + 1)
        fun str(k: String) = Regex("\"" + k + "\"\\s*:\\s*\"([^\"]*)\"").find(j)?.groupValues?.get(1)
        fun num(k: String) = Regex("\"" + k + "\"\\s*:\\s*\"?(\\d+)").find(j)?.groupValues?.get(1)?.toIntOrNull()
        fun bool(k: String) = Regex("\"" + k + "\"\\s*:\\s*(true|false)").find(j)?.groupValues?.get(1)?.toBoolean()
        fun ids(k: String) = Regex("\"" + k + "\"\\s*:\\s*\\[([^\\]]*)\\]").find(j)?.groupValues?.get(1)?.split(',')?.mapNotNull { it.trim().trim('"').toIntOrNull() }.orEmpty()
        val styleId = str("style")
        if (styleId == null && num("seconds") == null && ids("keep").isEmpty()) return null
        val style = STYLES.firstOrNull { it.id == styleId } ?: fallback
        val crop = str("crop")?.takeIf { it in CROP_IDS } ?: style.crop.ifEmpty { "keep" }
        val trans = TRANSITION_IDS.indexOf(str("transition")).takeIf { it >= 0 } ?: style.transition
        return Brief(style, (num("seconds") ?: 0).coerceIn(0, 3_600), if (crop == "keep") "" else crop, trans, bool("captions") ?: style.captions,
            bool("film") ?: (style.filmGroup != null), str("order")?.takeIf { it in ORDER_IDS } ?: "time", ids("keep").distinct(), ids("drop").distinct(), str("title")?.trim()?.take(60).orEmpty(),
            str("music")?.trim()?.takeIf { it.isNotEmpty() } ?: "auto", bool("sfx"))
    }

    /** Turns a brief into a plan. Without shot numbers it is the style's own plan with the brief's length and finish. */
    fun planFromBrief(clips: List<ClipInput>, shots: List<Piece>, brief: Brief, beats: List<Long>? = null, by: String = ""): Plan {
        val st = brief.style; val film = if (brief.film) (st.filmGroup ?: "ฟิล์มหนัง") else null
        val target = brief.seconds * 1000L
        fun finish(pieces: List<Piece>, before: Long) = Plan(st, pieces, before, brief.transition, brief.crop, film, brief.captions, brief.title, by, brief.music, brief.sfx)
        val before = clips.sumOf { (it.to - it.from).coerceAtLeast(0) }
        if (brief.keep.isEmpty() && brief.drop.isEmpty() && brief.order == "time") return finish(plan(clips, st, target, beats).pieces, before)
        var pool = if (brief.keep.isNotEmpty()) brief.keep.mapNotNull { shots.getOrNull(it) } else shots.filterIndexed { i, _ -> i !in brief.drop }
        if (pool.isEmpty()) pool = shots
        val limit = if (target > 0) target else st.targetMs
        if (limit > 0 && pool.sumOf { it.len } > limit) {
            val take = HashSet<Piece>(); var sum = 0L
            for (p in pool.sortedByDescending { it.score }) if (sum + p.len <= limit + limit / 20) { take += p; sum += p.len }
            if (take.isEmpty()) pool.firstOrNull()?.let { take += Piece(it.clip, it.from, minOf(it.to, it.from + limit), it.score) }
            pool = pool.filter { it in take }.ifEmpty { take.toList() }
        }
        val order = clips.map { it.clip }
        val byTime = pool.sortedWith(compareBy({ order.indexOf(it.clip) }, { it.from }))
        var out = when (brief.order) { "reverse" -> byTime.reversed(); "best" -> pool.sortedByDescending { it.score }; "keep" -> pool; else -> byTime }
        if (brief.order == "time") {
            val merged = ArrayList<Piece>()
            for (p in out) { val last = merged.lastOrNull(); if (last != null && last.clip == p.clip && p.from - last.to in 0..60) merged[merged.lastIndex] = Piece(p.clip, last.from, p.to, maxOf(last.score, p.score)) else merged += p }
            out = merged
        }
        if (!beats.isNullOrEmpty()) out = snap(out, clips, beats)
        return finish(out, before)
    }

    private fun sec(ms: Long) = String.format(java.util.Locale.US, "%.1f", ms / 1000.0)
    private fun levels(c: ClipInput, p: Piece): Pair<Int, Int> {
        val l = if (c.hasAudio) (mean(c.peaks, p.from / c.stepMs, p.to / c.stepMs) / percentile(c.peaks, 0.9f).coerceAtLeast(0.02f) * 9).toInt() else 0
        val m = (mean(c.diffs, (p.from - c.from) / c.diffStep, (p.to - c.from) / c.diffStep) / percentile(c.diffs, 0.9f).coerceAtLeast(0.01f) * 9).toInt()
        return l.coerceIn(0, 9) to m.coerceIn(0, 9)
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
