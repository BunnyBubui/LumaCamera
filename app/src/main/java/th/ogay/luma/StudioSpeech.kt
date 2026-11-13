package th.ogay.luma

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import java.io.File
import java.nio.ByteBuffer
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
 * Captions from speech with the phone's own on-device speech recognition (Android 13 or newer). The sound is cut
 * into pieces at its pauses beforehand, so every recognised line gets the start and end time of its piece.
 * [pieces] are (file, from ms, to ms, where the piece starts in the finished video, speed).
 */
@RequiresApi(33)
class StudioSpeech(private val a: Activity, languageArg: String, private val pieces: List<SpeechPiece>,
                   private val progress: (Int, Int) -> Unit, private val finished: (List<StudioMath.Cue>, String?) -> Unit) {
    companion object { const val AUTO = CAPTION_AUTO }
    /** The language in use. With [AUTO] it is chosen first (see [detect]) and then replaced by the language found. */
    private var language = languageArg
    /** Tells what the job is doing in words ("ตรวจภาษา…") and which language it settled on. */
    var onStage: (String) -> Unit = {}
    var onDetected: (String) -> Unit = {}
    /** The last text recognised, for showing while the job runs. */
    var onText: (String) -> Unit = {}
    private var recognizer: SpeechRecognizer? = null
    private val cues = ArrayList<StudioMath.Cue>()
    private var index = 0; private var failures = 0; private var stopped = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val timeout = Runnable { recognizer?.cancel(); deliver(null, 0f) }
    private var pfd: ParcelFileDescriptor? = null; private var file: File? = null
    /** Waiting for the recognizer to answer: receives the text (or null) and how sure it was. */
    private var pending: ((String?, Float) -> Unit)? = null

    private fun intent(lang: String = language, extra: Intent.() -> Unit = {}) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (lang == AUTO) "th-TH" else lang)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true); extra() }

    fun start() {
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(a)) { finished(emptyList(), "เครื่องนี้ไม่มีระบบแปลงเสียงพูดเป็นข้อความแบบทำงานในเครื่อง"); return }
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(a); recognizer = r
        r.setRecognitionListener(listener)
        // Ask first whether the language is ready on this phone; if it can be downloaded, start the download.
        r.checkRecognitionSupport(intent(), a.mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(s: RecognitionSupport) {
                if (language == AUTO) { detect(s.installedOnDeviceLanguages); return }
                val tag = language.lowercase(); fun has(list: List<String>) = list.any { it.lowercase() == tag || it.lowercase().substringBefore('-') == tag.substringBefore('-') }
                when {
                    has(s.installedOnDeviceLanguages) -> run()
                    has(s.supportedOnDeviceLanguages) || has(s.pendingOnDeviceLanguages) -> { runCatching { r.triggerModelDownload(intent()) }; stop(); finished(emptyList(), "กำลังดาวน์โหลดภาษานี้ลงเครื่อง · ลองใหม่อีกครั้งเมื่อดาวน์โหลดเสร็จ") }
                    else -> { stop(); finished(emptyList(), "ระบบแปลงเสียงในเครื่องนี้ยังไม่รองรับภาษานี้") }
                }
            }
            override fun onError(error: Int) { if (language == AUTO) language = "th-TH"; run() }
        })
    }
    fun stop() { stopped = true; handler.removeCallbacks(timeout); pending = null; recognizer?.runCatching { cancel(); destroy() }; recognizer = null; runCatching { langId?.close() }; langId = null; closeFile() }
    private fun closeFile() { runCatching { pfd?.close() }; pfd = null; file?.delete(); file = null }

    /**
     * Picks the spoken language: the longest of the first few pieces is read once in every language installed on the phone
     * (at most six), and the language whose reading is longest and surest wins. The recogniser only reads one language at a
     * time, so this is a measured guess, not a certain answer.
     */
    private fun detect(installed: List<String>) {
        val candidates = installed.distinct().take(6)
        if (candidates.isEmpty()) { stop(); finished(emptyList(), "ยังไม่มีภาษาใดติดตั้งในระบบแปลงเสียง · เลือกภาษาที่พูดเองแล้วลองใหม่"); return }
        if (candidates.size == 1) { language = candidates[0]; onDetected(language); run(); return }
        val sample = pieces.take(5).maxByOrNull { it.to - it.from }
        if (sample == null) { language = candidates[0]; run(); return }
        var best = candidates[0]; var bestScore = -1f; var at = 0
        fun step() {
            if (stopped) return
            if (at >= candidates.size) { language = best; onDetected(best); run(); return }
            val lang = candidates[at++]; onStage("ตรวจภาษาที่พูด · ลอง $lang (${at}/${candidates.size})")
            recognize(sample, 1000 + at, lang) { text, conf ->
                val words = text?.trim().orEmpty()
                fun settle(score: Float) { if (score > bestScore) { bestScore = score; best = lang }; handler.post { step() } }
                if (words.isEmpty()) settle(0f)
                // A second, text-reading AI model (ML Kit language ID) checks the words really are in the language that was tried;
                // reading speech in the wrong language gives words it does not recognise as that language.
                else idConfidence(words, lang) { id -> settle(id * minOf(words.length, 40) * (if (conf > 0f) 0.5f + conf / 2f else 1f)) }
            }
        }
        step()
    }

    private var langId: com.google.mlkit.nl.languageid.LanguageIdentifier? = null
    /** How sure the on-device language-ID model is that [text] is written in [tag] (0 to 1); 0 when it cannot tell. */
    private fun idConfidence(text: String, tag: String, done: (Float) -> Unit) {
        val client = langId ?: com.google.mlkit.nl.languageid.LanguageIdentification.getClient(
            com.google.mlkit.nl.languageid.LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.05f).build()).also { langId = it }
        val base = tag.lowercase().substringBefore('-').let { if (it == "yue") "zh" else it }
        client.identifyPossibleLanguages(text)
            .addOnSuccessListener { list -> done(list.firstOrNull { it.languageTag.lowercase().substringBefore('-') == base }?.confidence ?: 0f) }
            .addOnFailureListener { done(0f) }
    }

    private fun run() {
        if (stopped) return
        if (index >= pieces.size) { stop(); finished(cues, if (cues.isEmpty()) (if (failures > 0) "แปลงเสียงไม่สำเร็จ" else "ไม่พบเสียงพูดที่แปลงได้") else null); return }
        progress(index, pieces.size)
        recognize(pieces[index], index, language) { text, _ -> next(text) }
    }
    /** Reads the sound of one piece in [lang]; [done] runs once with what came out. */
    private fun recognize(p: SpeechPiece, key: Int, lang: String, done: (String?, Float) -> Unit) {
        Thread {
            val pcm = runCatching { StudioPcm.extract(a, p.uri, p.from, p.to) }.getOrDefault(ShortArray(0))
            val f = File(a.cacheDir, "speech_$key.pcm")
            runCatching { f.outputStream().use { o -> val b = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN); pcm.forEach { b.putShort(it) }; o.write(b.array()) } }
            a.runOnUiThread {
                if (stopped) { f.delete(); return@runOnUiThread }
                if (pcm.size < StudioPcm.RATE / 4) { f.delete(); done(null, 0f); return@runOnUiThread }
                file = f; pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                val i = intent(lang) { putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pfd); putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT); putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, StudioPcm.RATE) }
                pending = done
                handler.postDelayed(timeout, 25_000)
                runCatching { recognizer?.startListening(i) }.onFailure { deliver(null, 0f) }
            }
        }.start()
    }
    private fun deliver(text: String?, conf: Float) {
        handler.removeCallbacks(timeout); closeFile()
        val cb = pending; pending = null; cb?.invoke(text, conf)
    }
    private fun next(text: String?) {
        val p = pieces.getOrNull(index)
        if (p != null && !text.isNullOrBlank()) { cues += StudioMath.Cue(p.outAt, p.outAt + ((p.to - p.from) / p.speed).toLong(), text.trim()); onText(text.trim()) }
        index++; handler.post { run() }
    }
    private val listener = object : RecognitionListener {
        override fun onResults(b: Bundle?) {
            val text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            val conf = b?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull() ?: 0f
            deliver(text, conf)
        }
        override fun onError(error: Int) {
            // While the language is still being guessed, a language the recogniser cannot read just counts as "nothing heard".
            if (language == AUTO) { deliver(null, 0f); return }
            if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) { stop(); finished(cues, "ระบบแปลงเสียงในเครื่องนี้ยังไม่รองรับภาษานี้"); return }
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { stop(); finished(cues, "ต้องอนุญาตให้ใช้ไมโครโฟนก่อน"); return }
            if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) failures++
            deliver(null, 0f)
        }
        override fun onReadyForSpeech(p: Bundle?) {}; override fun onBeginningOfSpeech() {}; override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}; override fun onEndOfSpeech() {}; override fun onPartialResults(b: Bundle?) {}; override fun onEvent(t: Int, b: Bundle?) {}
    }
}
