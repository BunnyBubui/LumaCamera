package th.ogay.luma

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import java.util.concurrent.Executor

/**
 * Loudness of a sound file over time, for drawing its waveform on the timeline: one number (0..1) for every
 * [STEP_MS] milliseconds. The file is decoded once in the background and the result is kept while the app runs.
 */
object AudioPeaks {
    const val STEP_MS = 50
    private const val LIMIT_MS = 20 * 60_000L
    private val cache = HashMap<String, FloatArray>()
    private val loading = HashSet<String>()

    fun get(uri: Uri): FloatArray? = synchronized(cache) { cache[uri.toString()] }

    /** Starts decoding if it has not been done; [done] runs on the calling executor's thread when the waveform is ready. */
    fun load(c: Context, uri: Uri, io: Executor, done: () -> Unit) {
        val key = uri.toString()
        synchronized(cache) { if (key in cache || !loading.add(key)) return }
        val app = c.applicationContext
        io.execute {
            val peaks = runCatching { decode(app, uri) }.getOrDefault(FloatArray(0))
            synchronized(cache) { cache[key] = peaks; loading.remove(key) }
            done()
        }
    }

    private fun decode(c: Context, uri: Uri): FloatArray {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(c, uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return FloatArray(0)
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val durMs = (if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else LIMIT_MS).coerceIn(1, LIMIT_MS)
            val out = FloatArray((durMs / STEP_MS).toInt() + 2)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).apply { configure(format, null, null, 0); start() }
            val info = MediaCodec.BufferInfo(); var inDone = false; var outDone = false; var float = false; var guard = 0
            while (!outDone && guard++ < 2_000_000) {
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(2000)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0 || ex.sampleTime / 1000 > LIMIT_MS) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 2000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { val f = codec.outputFormat; float = f.containsKey("pcm-encoding") && f.getInteger("pcm-encoding") == 4 }
                else if (o >= 0) {
                    if (info.size > 0) {
                        val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder()); buf.position(info.offset); buf.limit(info.offset + info.size)
                        var peak = 0f
                        // Every 8th sample is enough to find how loud this short piece is.
                        if (float) { val fb = buf.asFloatBuffer(); var k = 0; while (k < fb.limit()) { val v = kotlin.math.abs(fb.get(k)); if (v > peak) peak = v; k += 8 } }
                        else { val sb = buf.asShortBuffer(); var k = 0; while (k < sb.limit()) { val v = kotlin.math.abs(sb.get(k).toInt()) / 32768f; if (v > peak) peak = v; k += 8 } }
                        val at = (info.presentationTimeUs / 1000 / STEP_MS).toInt()
                        if (at in out.indices && peak > out[at]) out[at] = peak
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    codec.releaseOutputBuffer(o, false)
                }
            }
            // A decoder may hand out pieces longer than one step: empty steps take their neighbour's value so the wave has no holes.
            for (i in 1 until out.size) if (out[i] == 0f) out[i] = out[i - 1] * 0.85f
            val top = out.maxOrNull()?.coerceAtLeast(0.05f) ?: 1f
            for (i in out.indices) out[i] = (out[i] / top).coerceIn(0f, 1f)
            return out
        } finally { runCatching { codec?.stop() }; runCatching { codec?.release() }; runCatching { ex.release() } }
    }
}
