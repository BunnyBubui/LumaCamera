package th.ogay.luma

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserDpdfNetModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/** The speech-cleaning models that can be downloaded (all run on the sherpa-onnx library that already carries Whisper). */
object DenoiseModels {
    private const val BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speech-enhancement-models"
    private fun one(id: String, file: String, name: String, badge: String, short: String, about: String, mb: Int) =
        ModelSpec(id, "denoise", name, badge, short, about, mb, listOf(file to "$BASE/$file"))
    val ALL = listOf(
        one("gtcrn", "gtcrn_simple.onnx", "GTCRN", "เล็กสุด", "ไฟล์ไม่ถึง 1 MB เร็วมาก ทำงานที่ 16 kHz",
            "โมเดลเล็กมาก ลดเสียงรบกวนจากเสียงพูดได้เร็ว ผลลัพธ์เป็นเสียง 16 kHz จึงไม่คมเท่ารุ่นใหญ่ เหมาะกับลองดูก่อน", 1),
        one("dpdfnet2", "dpdfnet2.onnx", "DPDFNet 2", "สมดุล", "ราว 10 MB ใหญ่กว่า GTCRN ผลควรละเอียดกว่า",
            "โมเดลลดเสียงรบกวนขนาดกลาง ใช้เวลามากกว่า GTCRN เล็กน้อย", 10),
        one("dpdfnet2_48khz_hr", "dpdfnet2_48khz_hr.onnx", "DPDFNet 2 48 kHz", "คมสุด", "ราว 11 MB ให้เสียง 48 kHz",
            "รุ่นที่ให้เสียงเต็มช่วง 48 kHz เหมาะกับคลิปที่ต้องการเสียงใส ใช้เวลานานที่สุดในสามรุ่น", 11),
    )
    fun selected(c: Context) = ModelStore.selected(c, "denoise", ALL)
}

/** Two sound files made from one clip: the speech with the noise taken away, and what was taken away. */
class DenoiseResult(val clean: File, val removed: File, val seconds: Float, val tookMs: Long, val rate: Int, val modelName: String)

/** Cleans the speech of a clip window by window, so a long clip never has to fit in memory at once. */
object Separation {
    private const val WINDOW_MS = 30_000L
    private const val OVERLAP_MS = 500L

    fun durationMs(c: Context, uri: Uri): Long {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(c, uri, null)
            val t = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return 0L
            val f = ex.getTrackFormat(t); if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L
        } catch (e: Throwable) { 0L } finally { runCatching { ex.release() } }
    }

    private fun build(c: Context, m: ModelSpec): OfflineSpeechDenoiser {
        val path = ModelStore.file(c, m, m.files[0].first).absolutePath
        val model = if (m.id == "gtcrn") OfflineSpeechDenoiserModelConfig(gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(model = path), numThreads = 2, provider = "cpu")
        else OfflineSpeechDenoiserModelConfig(dpdfnet = OfflineSpeechDenoiserDpdfNetModelConfig(model = path), numThreads = 2, provider = "cpu")
        return OfflineSpeechDenoiser(null, OfflineSpeechDenoiserConfig(model = model))
    }

    private class WavOut(file: File, val rate: Int) {
        private val raf = RandomAccessFile(file, "rw").apply { setLength(0); write(ByteArray(44)) }
        var samples = 0L
        fun write(a: FloatArray, from: Int, to: Int) {
            val b = ByteBuffer.allocate((to - from) * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in from until to) b.putShort((a[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            raf.write(b.array()); samples += to - from
        }
        fun close() {
            val bytes = samples * 2
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()); h.putInt((36 + bytes).toInt()); h.put("WAVEfmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
            h.putInt(rate); h.putInt(rate * 2); h.putShort(2); h.putShort(16); h.put("data".toByteArray()); h.putInt(bytes.toInt())
            raf.seek(0); raf.write(h.array()); raf.close()
        }
    }

    /**
     * Runs the chosen model over the whole sound of [uri]. [progress] gets 0..1. Throws when [cancel] is set or the model fails.
     * Windows of 30 s overlap by half a second and are joined with a short cross-fade, so there is no click at the joins.
     */
    fun denoise(c: Context, uri: Uri, m: ModelSpec, outDir: File, cancel: AtomicBoolean, progress: (Float) -> Unit): DenoiseResult {
        val t0 = System.currentTimeMillis()
        val total = durationMs(c, uri); require(total > 0) { "ไม่พบเสียงในไฟล์นี้" }
        outDir.mkdirs()
        val denoiser = build(c, m)
        try {
            val rate = denoiser.sampleRate
            val clean = File(outDir, "clean.wav"); val removed = File(outDir, "removed.wav")
            val outC = WavOut(clean, rate); val outR = WavOut(removed, rate)
            val ov = (rate * OVERLAP_MS / 1000).toInt()
            var tailC: FloatArray? = null; var tailR: FloatArray? = null
            var at = 0L
            while (at < total) {
                if (cancel.get()) error("cancelled")
                val last = at + WINDOW_MS >= total
                val to = if (last) total else at + WINDOW_MS + OVERLAP_MS
                val input = StudioPcm.extractFloat(c, uri, at, to, rate)
                if (input.isEmpty()) break
                val den = denoiser.run(input, rate).samples
                val n = min(input.size, den.size)
                val cl = den.copyOf(n); val rm = FloatArray(n) { input[it] - den[it] }
                // Cross-fade the start of this window with the held tail of the last one.
                val headC = tailC; val headR = tailR
                if (headC != null && headR != null) {
                    val k = min(min(headC.size, ov), n)
                    for (i in 0 until k) { val w = (i + 1f) / (k + 1f); cl[i] = headC[i] * (1 - w) + cl[i] * w; rm[i] = headR[i] * (1 - w) + rm[i] * w }
                }
                val keep = if (last) n else (n - ov).coerceAtLeast(0)
                outC.write(cl, 0, keep); outR.write(rm, 0, keep)
                tailC = if (last) null else cl.copyOfRange(keep, n); tailR = if (last) null else rm.copyOfRange(keep, n)
                at += WINDOW_MS
                progress((at.toFloat() / total).coerceIn(0f, 1f))
            }
            outC.close(); outR.close()
            return DenoiseResult(clean, removed, outC.samples.toFloat() / rate, System.currentTimeMillis() - t0, rate, m.name)
        } finally { runCatching { denoiser.release() } }
    }
}
