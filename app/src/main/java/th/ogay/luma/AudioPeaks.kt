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
    /** Left and right loudness of a fully decoded file (same steps and scale as the main waveform), for the level meter. */
    private val stereo = HashMap<String, Array<FloatArray>>()
    fun sides(uri: Uri): Array<FloatArray>? = synchronized(cache) { stereo[uri.toString()] }

    fun get(uri: Uri): FloatArray? = synchronized(cache) { cache[uri.toString()] }
    /** Like [load], but on the calling thread: returns the waveform, decoding it first when needed. Call it off the main thread. */
    fun getOrDecode(c: Context, uri: Uri): FloatArray { get(uri)?.let { return it }
        val side = arrayOfNulls<FloatArray>(2)
        val peaks = runCatching { decode(c.applicationContext, uri, side = side) }.getOrDefault(FloatArray(0))
        synchronized(cache) { cache[uri.toString()] = peaks; keepSides(uri.toString(), side) }; return peaks }
    private fun keepSides(key: String, side: Array<FloatArray?>) { val l = side[0]; val r = side[1]; if (l != null && r != null) stereo[key] = arrayOf(l, r) }

    /**
     * The loudness of just [fromMs]..[toMs] of a file, indexed by absolute time like the full waveform (steps before [fromMs] stay empty).
     * Only that stretch is decoded, so a one-hour file trimmed to one minute costs one minute, not an hour. A waveform already
     * decoded is reused when it reaches that far. Call it off the main thread.
     */
    fun range(c: Context, uri: Uri, fromMs: Long, toMs: Long, onProgress: ((Float) -> Unit)? = null): FloatArray {
        get(uri)?.takeIf { it.isNotEmpty() && toMs <= LIMIT_MS }?.let { return it }
        return runCatching { decode(c.applicationContext, uri, fromMs.coerceAtLeast(0), toMs, onProgress) }.getOrDefault(FloatArray(0))
    }

    /** Starts decoding if it has not been done; [done] runs on the calling executor's thread when the waveform is ready. */
    fun load(c: Context, uri: Uri, io: Executor, done: () -> Unit) {
        val key = uri.toString()
        synchronized(cache) { if (key in cache || !loading.add(key)) return }
        val app = c.applicationContext
        io.execute {
            val side = arrayOfNulls<FloatArray>(2)
            val peaks = runCatching { decode(app, uri, side = side) }.getOrDefault(FloatArray(0))
            synchronized(cache) { cache[key] = peaks; keepSides(key, side); loading.remove(key) }
            done()
        }
    }

    private fun decode(c: Context, uri: Uri, fromMs: Long = 0, toMs: Long = LIMIT_MS, onProgress: ((Float) -> Unit)? = null, side: Array<FloatArray?>? = null): FloatArray {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(c, uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return FloatArray(0)
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val fileMs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else toMs
            val durMs = minOf(fileMs, toMs).coerceAtLeast(1)
            if (fromMs > 0) ex.seekTo(fromMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val out = FloatArray((durMs / STEP_MS).toInt() + 2)
            val outL = FloatArray(out.size); val outR = FloatArray(out.size)
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1) else 1
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).apply { configure(format, null, null, 0); start() }
            val info = MediaCodec.BufferInfo(); var inDone = false; var outDone = false; var float = false; var guard = 0
            // A decoder that stops answering must not hold the thread for hours: give up after 5 s with no input taken and no output given.
            var lastLife = android.os.SystemClock.elapsedRealtime()
            while (!outDone && guard++ < 2_000_000) {
                if (android.os.SystemClock.elapsedRealtime() - lastLife > 5_000) break
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(2000)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0 || ex.sampleTime / 1000 > toMs) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                        lastLife = android.os.SystemClock.elapsedRealtime()
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 2000)
                if (o >= 0 || o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) lastLife = android.os.SystemClock.elapsedRealtime()
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { val f = codec.outputFormat; if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1); float = f.containsKey("pcm-encoding") && f.getInteger("pcm-encoding") == 4 }
                else if (o >= 0) {
                    if (info.size > 0) {
                        val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder()); buf.position(info.offset); buf.limit(info.offset + info.size)
                        var peak = 0f; var pl = 0f; var pr = 0f
                        // Every 4th frame is enough to find how loud this short piece is; the left and right channels are read apart.
                        if (float) { val fb = buf.asFloatBuffer(); var k = 0; while (k + channels <= fb.limit()) { val a = kotlin.math.abs(fb.get(k)); val b = if (channels > 1) kotlin.math.abs(fb.get(k + 1)) else a
                            if (a > pl) pl = a; if (b > pr) pr = b; k += channels * 4 } }
                        else { val sb = buf.asShortBuffer(); var k = 0; while (k + channels <= sb.limit()) { val a = kotlin.math.abs(sb.get(k).toInt()) / 32768f; val b = if (channels > 1) kotlin.math.abs(sb.get(k + 1).toInt()) / 32768f else a
                            if (a > pl) pl = a; if (b > pr) pr = b; k += channels * 4 } }
                        peak = maxOf(pl, pr)
                        val at = (info.presentationTimeUs / 1000 / STEP_MS).toInt()
                        if (at in out.indices) { if (peak > out[at]) out[at] = peak; if (pl > outL[at]) outL[at] = pl; if (pr > outR[at]) outR[at] = pr }
                        if (onProgress != null && at % 20 == 0) onProgress(((info.presentationTimeUs / 1000 - fromMs).toFloat() / (toMs - fromMs).coerceAtLeast(1)).coerceIn(0f, 1f))
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    codec.releaseOutputBuffer(o, false)
                }
            }
            // A decoder may hand out pieces longer than one step: empty steps take their neighbour's value so the wave has no holes.
            for (i in 1 until out.size) if (out[i] == 0f) out[i] = out[i - 1] * 0.85f
            for (i in 1 until out.size) { if (outL[i] == 0f) outL[i] = outL[i - 1] * 0.85f; if (outR[i] == 0f) outR[i] = outR[i - 1] * 0.85f }
            val top = out.maxOrNull()?.coerceAtLeast(0.05f) ?: 1f
            for (i in out.indices) { out[i] = (out[i] / top).coerceIn(0f, 1f); outL[i] = (outL[i] / top).coerceIn(0f, 1f); outR[i] = (outR[i] / top).coerceIn(0f, 1f) }
            if (side != null && fromMs == 0L) { side[0] = outL; side[1] = outR }
            return out
        } finally { runCatching { codec?.stop() }; runCatching { codec?.release() }; runCatching { ex.release() } }
    }
}
