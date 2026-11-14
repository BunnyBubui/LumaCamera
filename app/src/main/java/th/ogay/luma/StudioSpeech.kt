package th.ogay.luma

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.nio.ByteOrder

/** Sound of part of a file as 16 kHz mono 16-bit samples, the format speech recognition reads. */
object StudioPcm {
    const val RATE = 16000
    fun extract(c: Context, uri: Uri, fromMs: Long, toMs: Long): ShortArray {
        val ex = MediaExtractor(); var codec: MediaCodec? = null
        try {
            ex.setDataSource(c, uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return ShortArray(0)
            ex.selectTrack(track); val format = ex.getTrackFormat(track)
            ex.seekTo(fromMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).apply { configure(format, null, null, 0); start() }
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE); var ch = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT); var float = false
            var mono = FloatArray(((toMs - fromMs) * rate / 1000 + rate).toInt().coerceAtMost(rate * 120)); var n = 0
            val info = MediaCodec.BufferInfo(); var inDone = false; var done = false; var guard = 0
            while (!done && guard++ < 1_000_000) {
                if (!inDone) { val i = codec.dequeueInputBuffer(2000)
                    if (i >= 0) { val size = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (size < 0 || ex.sampleTime / 1000 > toMs + 200) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(i, 0, size, ex.sampleTime, 0); ex.advance() } } }
                val o = codec.dequeueOutputBuffer(info, 2000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { val f = codec.outputFormat; rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    float = f.containsKey("pcm-encoding") && f.getInteger("pcm-encoding") == AudioFormat.ENCODING_PCM_FLOAT }
                else if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder()); buf.position(info.offset); buf.limit(info.offset + info.size)
                    val frames = if (float) info.size / 4 / ch else info.size / 2 / ch
                    val fb = if (float) buf.asFloatBuffer() else null; val sb = if (float) null else buf.asShortBuffer()
                    for (k in 0 until frames) {
                        val t = info.presentationTimeUs / 1000 + k * 1000L / rate
                        if (t >= toMs) { done = true; break }
                        var v = 0f; for (q in 0 until ch) v += if (fb != null) fb.get(k * ch + q) else sb!!.get(k * ch + q) / 32768f
                        if (t >= fromMs) { if (n == mono.size) mono = mono.copyOf(mono.size * 2); mono[n++] = v / ch }
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                    codec.releaseOutputBuffer(o, false)
                }
            }
            // Straight-line resampling to 16 kHz is enough for speech.
            val out = ShortArray((n.toLong() * RATE / rate).toInt())
            for (i in out.indices) { val x = i.toDouble() * rate / RATE; val a = x.toInt().coerceAtMost(n - 1); val b = (a + 1).coerceAtMost(n - 1); val f = (x - a).toFloat()
                out[i] = ((mono[a] * (1 - f) + mono[b] * f).coerceIn(-1f, 1f) * 32767).toInt().toShort() }
            return out
        } finally { runCatching { codec?.stop() }; runCatching { codec?.release() }; runCatching { ex.release() } }
    }
}

/** The spoken-language choice that means "find it for me". */
const val CAPTION_AUTO = "auto"

/** A stretch of sound to turn into one caption line: (file, from ms, to ms, where it starts in the finished video, speed). */
class SpeechPiece(val uri: Uri, val from: Long, val to: Long, val outAt: Long, val speed: Float)

/**
 * Captions from speech with a Whisper model that runs on the phone (sherpa-onnx). The sound is cut into pieces at its
 * pauses beforehand, so every recognised line gets the start and end time of its piece. With [AUTO] Whisper first listens
 * to a few of the longest pieces, takes the language most of them were in, and then reads every piece in that language.
 */
class StudioSpeech(private val a: Activity, private val languageArg: String, private val pieces: List<SpeechPiece>, private val model: ModelSpec,
                   private val progress: (Int, Int) -> Unit, private val finished: (List<StudioMath.Cue>, String?) -> Unit) {
    companion object {
        const val AUTO = CAPTION_AUTO
        /** Whisper's own code for a language tag such as "th-TH" ("" when the language is to be found automatically). */
        fun whisperCode(tag: String): String {
            if (tag == AUTO) return ""
            val base = tag.lowercase().substringBefore('-')
            return if (base == "fil") "tl" else base
        }
    }
    /** Tells what the job is doing in words and which language (Whisper's code) it settled on. */
    var onStage: (String) -> Unit = {}
    var onDetected: (String) -> Unit = {}
    /** The last text recognised, for showing while the job runs. */
    var onText: (String) -> Unit = {}
    @Volatile private var stopped = false
    fun stop() { stopped = true }
    fun start() { Thread({ work() }, "whisper-captions").start() }

    private fun ui(block: () -> Unit) { a.runOnUiThread { if (!stopped && !a.isDestroyed) block() } }

    private fun config(language: String): OfflineRecognizerConfig {
        fun f(n: String) = ModelStore.file(a, model, n).absolutePath
        return OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
            whisper = OfflineWhisperModelConfig(encoder = f("${model.id}-encoder.int8.onnx"), decoder = f("${model.id}-decoder.int8.onnx"), language = language, task = "transcribe"),
            tokens = f("${model.id}-tokens.txt"), numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4), provider = "cpu"))
    }

    private class Heard(val text: String, val lang: String)
    private fun heard(rec: OfflineRecognizer, pcm: ShortArray): Heard {
        val samples = FloatArray(pcm.size) { pcm[it] / 32768f }
        val stream = rec.createStream()
        try {
            stream.acceptWaveform(samples, StudioPcm.RATE); rec.decode(stream)
            val r = rec.getResult(stream)
            return Heard(r.text.trim(), r.lang.removePrefix("<|").removeSuffix("|>").lowercase())
        } finally { stream.release() }
    }
    /**
     * Where real speech is inside a piece. Pieces are cut by loudness, so music and sound effects get through, and Whisper
     * invents words for them; a voice detector (Silero) tells talking from the rest. Null = no speech, so the piece is skipped.
     * Without the detector the whole piece counts, as before.
     */
    private var vad: Vad? = null
    private class Span(val from: Int, val to: Int)
    private fun speechIn(pcm: ShortArray): Span? {
        val v = vad ?: return Span(0, pcm.size)
        return runCatching {
            v.clear(); v.reset()
            val x = FloatArray(pcm.size) { pcm[it] / 32768f }
            var i = 0; while (i < x.size) { v.acceptWaveform(x.copyOfRange(i, minOf(x.size, i + StudioPcm.RATE))); i += StudioPcm.RATE }
            v.flush()
            var first = -1L; var last = 0L; var total = 0L
            while (!v.empty()) { val s = v.front(); if (first < 0) first = s.start; last = s.start + s.samples.size; total += s.samples.size; v.pop() }
            if (first < 0 || total < StudioPcm.RATE / 4) null
            // A little before and after, so the first and last sounds of a sentence are not cut.
            else Span(maxOf(0L, first - StudioPcm.RATE / 5).toInt(), minOf(pcm.size.toLong(), last + StudioPcm.RATE * 3 / 10).toInt())
        }.getOrDefault(Span(0, pcm.size))
    }
    private fun pcmOf(p: SpeechPiece): ShortArray = runCatching { StudioPcm.extract(a, p.uri, p.from, p.to) }.getOrDefault(ShortArray(0))
    /** True when the text has at least one letter or digit, so a line of only dots or symbols is dropped. */
    private fun worthKeeping(t: String) = t.any { it.isLetterOrDigit() }

    private fun work() {
        var rec: OfflineRecognizer? = null
        try {
            ui { onStage("กำลังโหลดโมเดล ${model.name} เข้าเครื่อง…") }
            val code = whisperCode(languageArg)
            rec = OfflineRecognizer(null, config(code))
            vad = runCatching { Vad(a.assets, VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(model = "silero_vad.onnx", threshold = 0.5f, minSilenceDuration = 0.4f,
                minSpeechDuration = 0.2f, windowSize = 512, maxSpeechDuration = 30f), sampleRate = StudioPcm.RATE, numThreads = 1, provider = "cpu", debug = false)) }.getOrNull()
            var language = code
            if (code.isEmpty()) {
                ui { onStage("ฟังเสียงเพื่อตรวจภาษาที่พูด…") }
                val votes = HashMap<String, Int>()
                // The longest few of the first pieces: short or noisy pieces make Whisper's language guess weak.
                for (p in pieces.take(8).sortedByDescending { it.to - it.from }.take(3)) {
                    if (stopped) return
                    val pcm0 = pcmOf(p); if (pcm0.size < StudioPcm.RATE / 2) continue
                    val sp0 = speechIn(pcm0) ?: continue
                    val h = heard(rec, pcm0.copyOfRange(sp0.from, sp0.to))
                    if (h.lang.isNotEmpty() && worthKeeping(h.text)) votes[h.lang] = (votes[h.lang] ?: 0) + h.text.length.coerceAtMost(60)
                }
                language = votes.maxByOrNull { it.value }?.key ?: "en"
                ui { onDetected(language) }
                // Whisper can switch language on a loaded model; if this build cannot, load it again with the language set.
                val switched = runCatching { rec.setConfig(config(language)) }.isSuccess
                if (!switched) { rec.release(); rec = OfflineRecognizer(null, config(language)) }
            }
            val cues = ArrayList<StudioMath.Cue>(); var failures = 0
            for ((i, p) in pieces.withIndex()) {
                if (stopped) return
                ui { progress(i, pieces.size) }
                val pcm = pcmOf(p); if (pcm.size < StudioPcm.RATE / 4) continue
                val span = speechIn(pcm) ?: continue
                val h = runCatching { heard(rec!!, pcm.copyOfRange(span.from, span.to)) }.getOrElse { failures++; null } ?: continue
                val seconds = (span.to - span.from).toDouble() / StudioPcm.RATE
                val words = StudioMath.cleanSpeech(h.text, seconds)
                if (words != null) {
                    // The line shows while the voice is heard, not through the whole piece.
                    val from = p.outAt + (span.from * 1000L / StudioPcm.RATE / p.speed).toLong()
                    val to = p.outAt + (span.to * 1000L / StudioPcm.RATE / p.speed).toLong()
                    cues += StudioMath.Cue(from, maxOf(to, from + 300), words); ui { onText(words) }
                }
            }
            val problem = if (cues.isNotEmpty()) null else if (failures > 0) "ถอดเสียงไม่สำเร็จ" else "ไม่พบเสียงพูดที่ถอดเป็นข้อความได้"
            ui { finished(cues, problem) }
        } catch (t: Throwable) {
            val why = when (t) {
                is UnsatisfiedLinkError, is NoClassDefFoundError -> "เปิดตัวถอดเสียง Whisper ไม่ได้ (${t.message?.take(80)})"
                is OutOfMemoryError -> "หน่วยความจำไม่พอสำหรับโมเดลนี้ ลองเลือกโมเดลที่เล็กลง"
                else -> "ถอดเสียงไม่สำเร็จ: ${t.message?.take(100) ?: t.javaClass.simpleName}"
            }
            ui { finished(emptyList(), why) }
        } finally { runCatching { rec?.release() }; runCatching { vad?.release() }; vad = null }
    }
}
