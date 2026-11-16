package th.ogay.luma

import android.content.Context
import android.net.Uri
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * Finds where someone is talking in a clip with the same voice detector the captions use (Silero, bundled with the app).
 * Loudness alone cannot tell a voice from music or traffic; this can, so auto edit keeps whole sentences and drops the rest.
 */
object VoiceFinder {
    private const val CHUNK_MS = 30_000L

    /** Talking stretches of [from, to) in ms of the file; null when the detector cannot run or [stop] said so. */
    fun find(c: Context, uri: Uri, from: Long, to: Long, stop: () -> Boolean, progress: (Float) -> Unit = {}): List<Pair<Long, Long>>? {
        val vad = runCatching { Vad(c.assets, VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(model = "silero_vad.onnx", threshold = 0.5f,
            minSilenceDuration = 0.3f, minSpeechDuration = 0.2f, windowSize = 512, maxSpeechDuration = 30f), sampleRate = StudioPcm.RATE, numThreads = 1, provider = "cpu", debug = false)) }.getOrNull() ?: return null
        try {
            val out = ArrayList<Pair<Long, Long>>()
            var at = from
            // Thirty seconds at a time, each counted from its own start, so small decoding differences never add up.
            while (at < to) {
                if (stop()) return null
                val end = minOf(to, at + CHUNK_MS)
                val pcm = runCatching { StudioPcm.extract(c, uri, at, end) }.getOrDefault(ShortArray(0))
                if (pcm.isNotEmpty()) {
                    vad.clear(); vad.reset()
                    val x = FloatArray(pcm.size) { pcm[it] / 32768f }
                    var i = 0; while (i < x.size) { vad.acceptWaveform(x.copyOfRange(i, minOf(x.size, i + StudioPcm.RATE))); i += StudioPcm.RATE }
                    vad.flush()
                    while (!vad.empty()) { val s = vad.front()
                        val a = at + s.start * 1000L / StudioPcm.RATE; val b = a + s.samples.size * 1000L / StudioPcm.RATE
                        out += a to minOf(b, end); vad.pop() }
                }
                at = end
                progress(((at - from).toFloat() / (to - from)).coerceIn(0f, 1f))
            }
            return out
        } finally { runCatching { vad.release() } }
    }
}
