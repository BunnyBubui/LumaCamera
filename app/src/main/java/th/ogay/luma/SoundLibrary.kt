package th.ogay.luma

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Sound for automatic editing. The built-in music and sound effects are synthesised here from plain waves and noise (no
 * recordings, nothing anyone else owns), rendered to WAV files at the length a video needs. The user can also import their own
 * tracks into the library; those are measured once (tempo, loudness) so auto edit can pick and cut to them.
 */
object SoundLibrary {
    const val RATE = 44100

    /** A track. Built-in tracks have no [file] until rendered; [bpm] is exact for them and estimated for imported ones. */
    class Track(val id: String, val name: String, val note: String, val bpm: Float, val energy: Int, val builtIn: Boolean, val file: File? = null, val durationMs: Long = 0L)

    val BUILT_IN = listOf(
        Track("energy_128", "Energetic 128", "กลองหนัก จังหวะเร็ว เหมาะกับไวรัล", 128f, 9, true),
        Track("upbeat_116", "Upbeat 116", "สดใส เดินเรื่อง เหมาะกับ vlog และไฮไลต์", 116f, 7, true),
        Track("lofi_84", "Chill lo-fi 84", "นุ่ม ผ่อนคลาย เบาใต้เสียงพูดได้", 84f, 4, true),
        Track("cine_70", "Cinematic 70", "แพดยาว กลองทิมปานี บรรยากาศภาพยนตร์", 70f, 5, true),
    )

    /** The track a style uses when the user leaves the music on "ตามแนว"; null = no music. */
    fun forStyle(styleId: String): String? = when (styleId) { "viral" -> "energy_128"; "vlog", "highlight" -> "upbeat_116"; "cinematic" -> "cine_70"; else -> null }

    enum class Sfx(val label: String) { WHOOSH("วูช"), POP("ป๊อป"), RISER("ไรเซอร์"), IMPACT("อิมแพกต์") }

    /** Beat times (ms) of a steady [bpm] over [lengthMs]. */
    fun beatTimes(bpm: Float, lengthMs: Long): List<Long> {
        val out = ArrayList<Long>(); val step = 60_000.0 / bpm; var t = 0.0
        while (t < lengthMs) { out += Math.round(t); t += step }
        return out
    }

    // ---------- Synthesis ----------

    private class Spec(val bpm: Float, val kick: String, val snare: String, val hat: String, val bass: String, val pad: Float, val bright: Float,
                       val swing: Float, val chords: List<IntArray>, val timpani: Boolean, val drone: Boolean, val introBars: Int)

    private val I = intArrayOf(0, 4, 7); private val V = intArrayOf(7, 11, 14); private val vi = intArrayOf(9, 12, 16); private val IV = intArrayOf(5, 9, 12)
    private val I7 = intArrayOf(0, 4, 7, 11); private val vi7 = intArrayOf(9, 12, 16, 19); private val ii7 = intArrayOf(2, 5, 9, 12); private val V7 = intArrayOf(7, 11, 14, 17)
    // 16 steps per bar: x = hit, o = soft hit, . = rest.
    private fun spec(id: String) = when (id) {
        "energy_128" -> Spec(128f, "x...x...x...x...", "....x.......x...", "o.x.o.x.o.x.o.x.", "..x...x...x...x.", 0.55f, 1f, 0f, listOf(vi, IV, I, V), false, false, 1)
        "upbeat_116" -> Spec(116f, "x.....x.x.......", "....x.......x...", "x.x.x.x.x.x.x.x.", "x..x..x.x..x..x.", 0.6f, 0.8f, 0f, listOf(I, V, vi, IV), false, false, 1)
        "lofi_84" -> Spec(84f, "x......x..x.....", "....x.......x...", "o.o.o.o.o.o.o.o.", "x.......x.......", 0.7f, 0.35f, 0.18f, listOf(I7, vi7, ii7, V7), false, false, 1)
        else -> Spec(70f, "................", "................", "................", "x...............", 0.9f, 0.3f, 0f, listOf(vi, IV, I, V), true, true, 0)
    }

    private fun midi(n: Int) = 440.0 * Math.pow(2.0, (n - 69) / 12.0)

    /** One drum hit. */
    private fun kick(): FloatArray { val n = (0.32 * RATE).toInt(); var ph = 0.0
        return FloatArray(n) { i -> val t = i.toDouble() / RATE; val f = 45 + 95 * exp(-t * 28); ph += 2 * PI * f / RATE; (sin(ph) * exp(-t * 9) * 0.95).toFloat() } }
    private fun snare(r: Random): FloatArray { val n = (0.2 * RATE).toInt(); var lp = 0.0
        return FloatArray(n) { i -> val t = i.toDouble() / RATE; val nz = r.nextDouble(-1.0, 1.0); lp += (nz - lp) * 0.45
            ((nz - lp) * 0.55 * exp(-t * 22) + sin(2 * PI * 185 * t) * 0.35 * exp(-t * 30)).toFloat() } }
    private fun hat(r: Random): FloatArray { val n = (0.05 * RATE).toInt(); var prev = 0.0
        return FloatArray(n) { i -> val t = i.toDouble() / RATE; val nz = r.nextDouble(-1.0, 1.0); val hp = nz - prev; prev = nz; (hp * 0.22 * exp(-t * 90)).toFloat() } }
    private fun timpani(r: Random): FloatArray { val n = (1.4 * RATE).toInt(); var ph = 0.0; var lp = 0.0
        return FloatArray(n) { i -> val t = i.toDouble() / RATE; val f = 62 + 30 * exp(-t * 12); ph += 2 * PI * f / RATE; lp += (r.nextDouble(-1.0, 1.0) - lp) * 0.05
            (sin(ph) * 0.8 * exp(-t * 2.6) + lp * 1.4 * exp(-t * 14)).toFloat() } }

    /** Adds [src]·[gain] into [dst] at [at], wrapping past the end (the loop is tiled, so a tail that runs over plays at its start). */
    private fun mixWrap(dst: FloatArray, src: FloatArray, at: Int, gain: Float) { val n = dst.size; for (i in src.indices) { val k = (at + i) % n; dst[k] += src[i] * gain } }

    /** One four-bar loop of track [id]; [drums] false leaves out drums and bass (used for the intro). */
    private fun loop(id: String, drums: Boolean): FloatArray {
        val s = spec(id); val r = Random(id.hashCode())
        val step = 60.0 / s.bpm / 4; val len = Math.round(step * 64 * RATE).toInt()
        val out = FloatArray(len)
        val k = kick(); val sn = snare(r); val h = hat(r); val tp = timpani(r)
        for (st in 0 until 64) {
            val pos = st % 16; val bar = st / 16; val chord = s.chords[bar % s.chords.size]
            val at = Math.round((st * step + (if (st % 2 == 1) s.swing * step else 0.0)) * RATE).toInt()
            fun hit(p: String) = when (p[pos]) { 'x' -> 1f; 'o' -> 0.5f; else -> 0f }
            if (drums) {
                hit(s.kick).takeIf { it > 0 }?.let { mixWrap(out, k, at, it * 0.9f) }
                hit(s.snare).takeIf { it > 0 }?.let { mixWrap(out, sn, at, it * 0.75f) }
                hit(s.hat).takeIf { it > 0 }?.let { mixWrap(out, h, at, it * 0.8f) }
            }
            val b = hit(s.bass)
            if (b > 0 && (drums || s.drone)) {
                val f = midi(48 + chord[0] - 12); val n = (step * 1.8 * RATE).toInt(); var ph = 0.0
                mixWrap(out, FloatArray(n) { i -> val t = i.toDouble() / RATE; ph += 2 * PI * f / RATE
                    val env = minOf(1.0, t * 200) * exp(-t * 3.5); ((sin(ph) + 0.35 * sin(2 * ph) + 0.15 * sin(3 * ph)) * env * 0.42).toFloat() }, at, b)
            }
            if (s.timpani && drums && pos == 0 && bar % 2 == 0) mixWrap(out, tp, at, 0.8f)
        }
        // Pad: the chord of each bar, two slightly detuned voices per note, soft attack, darker for a calmer track.
        val barLen = len / 4
        val pad = FloatArray(len)
        for (bar in 0 until 4) {
            val chord = s.chords[bar % s.chords.size]
            for (note in chord) for (d in listOf(-0.004, 0.004)) {
                val f = midi(60 + note) * (1 + d); val w = 2 * PI * f / RATE; val start = bar * barLen; val n = (barLen * 1.25).toInt()
                for (i in 0 until n) { val t = i.toDouble() / RATE; val env = minOf(1.0, t / 0.35) * (if (i > barLen) 1.0 - (i - barLen).toDouble() / (n - barLen) else 1.0)
                    val v = (sin(w * i) + s.bright * 0.4 * sin(2 * w * i) + s.bright * 0.2 * sin(3 * w * i)) * env
                    pad[(start + i) % len] += (v * 0.07 * s.pad / chord.size * 3).toFloat() }
            }
        }
        var lp = 0f; val a = 0.08f + 0.25f * s.bright
        for (i in 0 until len) { lp += (pad[i] - lp) * a; out[i] += lp }
        if (s.drone) { val f = midi(36 + s.chords[0][0]); for (i in 0 until len) out[i] += (sin(2 * PI * f * i / RATE) * 0.12).toFloat() }
        return out
    }

    /** Built-in track [id] rendered to exactly [lengthMs]: one bar without drums (for 8 s or more), then the full loop, with a soft start and end. */
    fun renderMusic(id: String, lengthMs: Long): FloatArray {
        val n = (lengthMs * RATE / 1000).toInt().coerceAtLeast(RATE)
        val full = loop(id, true); val bars = if (lengthMs >= 8_000) spec(id).introBars else 0
        val intro = if (bars > 0) loop(id, false) else full
        // The first [bars] bars without drums, then the full loop; bars sit on the same grid, so the beat never shifts.
        val introLen = bars * full.size / 4
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = if (i < introLen) intro[i % intro.size] else full[i % full.size]
        master(out, 0.6f, 1.8f)
        return out
    }

    /** One sound effect. */
    fun renderSfx(s: Sfx): FloatArray {
        val r = Random(s.ordinal + 7)
        return when (s) {
            Sfx.WHOOSH -> { val n = (0.5 * RATE).toInt(); var lp = 0.0; var lp2 = 0.0
                FloatArray(n) { i -> val x = i.toDouble() / n; val cut = 0.02 + 0.35 * sin(PI * x); val nz = r.nextDouble(-1.0, 1.0); lp += (nz - lp) * cut; lp2 += (lp - lp2) * cut
                    (lp2 * 2.2 * Math.pow(sin(PI * x), 2.0)).toFloat() } }
            Sfx.POP -> { val n = (0.14 * RATE).toInt(); var ph = 0.0
                FloatArray(n) { i -> val t = i.toDouble() / RATE; ph += 2 * PI * (280 + 900 * exp(-t * 60)) / RATE; (sin(ph) * exp(-t * 38) * 0.9).toFloat() } }
            Sfx.RISER -> { val n = (1.6 * RATE).toInt(); var ph = 0.0; var lp = 0.0
                FloatArray(n) { i -> val x = i.toDouble() / n; ph += 2 * PI * (180 + 1100 * x * x) / RATE; lp += (r.nextDouble(-1.0, 1.0) - lp) * (0.05 + 0.5 * x)
                    ((sin(ph) * 0.35 + lp * 0.9) * x * x).toFloat() } }
            Sfx.IMPACT -> { val n = (1.2 * RATE).toInt(); var ph = 0.0; var lp = 0.0
                FloatArray(n) { i -> val t = i.toDouble() / RATE; ph += 2 * PI * (48 + 70 * exp(-t * 18)) / RATE; lp += (r.nextDouble(-1.0, 1.0) - lp) * 0.12
                    (sin(ph) * exp(-t * 3) + lp * 1.6 * exp(-t * 16)).toFloat() } }
        }.also { master(it, 0f, 0f) }
    }

    /** One track holding every effect at its time (ms), so many effects cost one sound layer. */
    fun renderSfxBed(lengthMs: Long, hits: List<Pair<Long, Sfx>>): FloatArray {
        val out = FloatArray((lengthMs * RATE / 1000).toInt().coerceAtLeast(1))
        val cache = HashMap<Sfx, FloatArray>()
        for ((t, s) in hits) { val src = cache.getOrPut(s) { renderSfx(s) }; val at = (t * RATE / 1000).toInt()
            for (i in src.indices) { val k = at + i; if (k in out.indices) out[k] += src[i] * 0.8f } }
        for (i in out.indices) out[i] = tanh(out[i].toDouble()).toFloat()
        return out
    }

    /** Soft clip, peak to about −1 dB, and fades in and out ([fadeIn]/[fadeOut] seconds). */
    private fun master(a: FloatArray, fadeIn: Float, fadeOut: Float) {
        for (i in a.indices) a[i] = tanh(a[i] * 1.2).toFloat()
        val peak = a.maxOfOrNull { abs(it) } ?: 0f
        if (peak > 0f) { val g = 0.89f / peak; for (i in a.indices) a[i] *= g }
        val fi = (fadeIn * RATE).toInt().coerceAtMost(a.size / 2); val fo = (fadeOut * RATE).toInt().coerceAtMost(a.size / 2)
        for (i in 0 until fi) a[i] *= i.toFloat() / fi
        for (i in 0 until fo) a[a.size - 1 - i] *= i.toFloat() / fo
    }

    fun writeWav(f: File, pcm: FloatArray) {
        val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in pcm) data.putShort((v.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + pcm.size * 2); h.put("WAVE".toByteArray()); h.put("fmt ".toByteArray())
        h.putInt(16); h.putShort(1); h.putShort(1); h.putInt(RATE); h.putInt(RATE * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(pcm.size * 2)
        f.parentFile?.mkdirs()
        FileOutputStream(f).use { it.write(h.array()); it.write(data.array()) }
    }

    // ---------- Files on the phone ----------

    private fun dir(c: Context) = File(c.filesDir, "sound").apply { mkdirs() }
    private fun libDir(c: Context) = File(dir(c), "library").apply { mkdirs() }

    /** Built-in track [id] as a file at least [lengthMs] long (lengths are rounded up to 5 s so files are reused). */
    fun musicFile(c: Context, id: String, lengthMs: Long): File {
        val secs = ((lengthMs + 4_999) / 5_000 * 5).coerceAtLeast(5)
        val f = File(dir(c), "music_${id}_${secs}s.wav")
        if (!f.isFile || f.length() < 1000) writeWav(f, renderMusic(id, secs * 1000))
        return f
    }
    /** A new effects track for one edit. */
    fun sfxBedFile(c: Context, lengthMs: Long, hits: List<Pair<Long, Sfx>>): File =
        File(dir(c), "sfx_${System.currentTimeMillis()}.wav").also { writeWav(it, renderSfxBed(lengthMs, hits)) }

    /** The user's imported tracks. */
    fun imported(c: Context): List<Track> = runCatching {
        val a = JSONArray(c.getSharedPreferences("camera", 0).getString("sound_library", "[]"))
        (0 until a.length()).mapNotNull { i -> val o = a.getJSONObject(i); val f = File(o.getString("file"))
            if (!f.isFile) null else Track(o.getString("id"), o.getString("name"), "นำเข้าเอง · ${o.getInt("bpm")} BPM", o.getDouble("bpm").toFloat(), o.getInt("energy"), false, f, o.getLong("dur")) }
    }.getOrDefault(emptyList())
    fun all(c: Context) = BUILT_IN + imported(c)
    fun byId(c: Context, id: String?) = all(c).firstOrNull { it.id == id }

    /** Copies [uri] into the library and measures it. Runs on the calling thread; null when it cannot be read. */
    fun import(c: Context, uri: Uri, name: String): Track? = runCatching {
        val ext = name.substringAfterLast('.', "m4a").lowercase().take(4).ifEmpty { "m4a" }
        val id = "u" + System.currentTimeMillis().toString(36)
        val f = File(libDir(c), "$id.$ext")
        c.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it, 1 shl 16) } }
        val t = measure(c, f, id, name.substringBeforeLast('.'))
        if (t == null) { f.delete(); return@runCatching null }
        val p = c.getSharedPreferences("camera", 0); val a = JSONArray(p.getString("sound_library", "[]"))
        a.put(JSONObject().put("id", t.id).put("name", t.name).put("file", f.path).put("bpm", t.bpm.toDouble()).put("energy", t.energy).put("dur", t.durationMs))
        p.edit().putString("sound_library", a.toString()).apply()
        t
    }.getOrNull()

    /** Takes a track out of the library and deletes its file. Projects that used it lose that music. */
    fun remove(c: Context, id: String) {
        val p = c.getSharedPreferences("camera", 0); val a = JSONArray(p.getString("sound_library", "[]")); val keep = JSONArray()
        for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (o.optString("id") == id) File(o.optString("file")).delete() else keep.put(o) }
        p.edit().putString("sound_library", keep.toString()).apply()
    }

    /** Downloads an audio file a tool pointed to (at most 40 MB) into the library. */
    fun download(c: Context, url: String): Track? = runCatching {
        if (!url.startsWith("https://")) return@runCatching null
        val h = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 60_000; instanceFollowRedirects = true }
        try {
            val type = h.contentType.orEmpty()
            val name = url.substringAfterLast('/').substringBefore('?').ifEmpty { "track.mp3" }
            if (!type.startsWith("audio/") && name.substringAfterLast('.', "").lowercase() !in setOf("mp3", "m4a", "aac", "wav", "ogg", "flac")) return@runCatching null
            val tmp = File(c.cacheDir, "dl_" + System.currentTimeMillis())
            h.inputStream.use { i -> tmp.outputStream().use { o -> val buf = ByteArray(1 shl 16); var total = 0L
                while (true) { val n = i.read(buf); if (n < 0) break; total += n; if (total > 40L shl 20) error("too big"); o.write(buf, 0, n) } } }
            import(c, Uri.fromFile(tmp), name).also { tmp.delete() }
        } finally { h.disconnect() }
    }.getOrNull()

    private fun measure(c: Context, f: File, id: String, name: String): Track? {
        val uri = Uri.fromFile(f)
        val dur = VideoLayers.duration(c, uri).takeIf { it > 0 } ?: return null
        val pk = AudioPeaks.range(c, uri, 0, dur)
        if (pk.isEmpty()) return null
        return Track(id, name.take(40), "", estimateBpm(StudioMath.beats(pk, AudioPeaks.STEP_MS.toLong())), (pk.average() / 0.5 * 9).toInt().coerceIn(0, 9), false, f, dur)
    }

    /** Tempo from beat times: the most common gap, folded into 70–160 BPM. 0 when there are too few beats. */
    fun estimateBpm(beats: List<Long>): Float {
        val gaps = beats.zipWithNext { a, b -> b - a }.filter { it in 300..1_500 }
        if (gaps.size < 4) return 0f
        var bpm = 60_000f / gaps.sorted()[gaps.size / 2]
        while (bpm < 70f) bpm *= 2; while (bpm > 160f) bpm /= 2
        return Math.round(bpm * 2) / 2f
    }

    /** Beat times of [t] (ms from its start) over [lengthMs]. */
    fun beatsOf(c: Context, t: Track, lengthMs: Long): List<Long> =
        if (t.builtIn) beatTimes(t.bpm, lengthMs)
        else t.file?.let { f -> runCatching { StudioMath.beats(AudioPeaks.range(c, Uri.fromFile(f), 0, minOf(lengthMs, t.durationMs)), AudioPeaks.STEP_MS.toLong()) }.getOrNull() }.orEmpty()
}
