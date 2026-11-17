package th.ogay.luma

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * New clip files made from existing ones: a still frame held for a while (freeze frame) and a clip played backwards.
 * Each gets a silent sound track, so a video that keeps its sound can still join it to clips that have sound.
 */
object ClipMaker {
    const val FPS = 30f

    /** Output size for a picture of [w]×[h]: the short side at most [maxShort], both sides even. */
    fun outSize(w: Int, h: Int, maxShort: Int = 1080): Pair<Int, Int> {
        val k = minOf(1f, maxShort.toFloat() / minOf(w, h).coerceAtLeast(1))
        return ((w * k).toInt() / 2 * 2).coerceAtLeast(2) to ((h * k).toInt() / 2 * 2).coerceAtLeast(2)
    }

    /** Times (ms of the file) of the frames of a backwards copy of [from, to), latest first. */
    fun backwardsTimes(from: Long, to: Long, fps: Float = FPS): List<Long> {
        val n = ((to - from) * fps / 1000f).toInt().coerceAtLeast(1)
        return (0 until n).map { i -> (to - 1 - (i * 1000f / fps).toLong()).coerceAtLeast(from) }
    }

    private fun dir(c: Context) = File(c.filesDir, "clips").apply { mkdirs() }

    /** A silent AAC track of [ms] in an .m4a file. */
    fun silence(c: Context, ms: Long): File {
        val f = File(c.cacheDir, "silence_${ms}.m4a"); if (f.isFile && f.length() > 100) return f
        val rate = 44100
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC); setInteger(MediaFormat.KEY_BIT_RATE, 64_000); setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384) }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
        val mux = MediaMuxer(f.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4); var track = -1; var started = false
        val total = rate * ms / 1000; var fed = 0L; var inputDone = false; val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                if (!inputDone) { val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) { val buf = codec.getInputBuffer(i)!!; buf.clear()
                        val n = minOf(4_096L, total - fed).toInt()
                        if (n <= 0) { codec.queueInputBuffer(i, 0, 0, fed * 1_000_000 / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { buf.put(ByteArray(n * 2)); codec.queueInputBuffer(i, 0, n * 2, fed * 1_000_000 / rate, 0); fed += n } } }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = mux.addTrack(codec.outputFormat); mux.start(); started = true }
                else if (o >= 0) {
                    val b = codec.getOutputBuffer(o)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started) { b.position(info.offset); b.limit(info.offset + info.size); mux.writeSampleData(track, b, info) }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally { runCatching { codec.stop() }; runCatching { codec.release() }; runCatching { if (started) mux.stop() }; runCatching { mux.release() } }
        return f
    }

    private fun rgb(b: Bitmap, w: Int, h: Int): Mat {
        val s = if (b.width == w && b.height == h) b else Bitmap.createScaledBitmap(b, w, h, true)
        val rgba = Mat(); Utils.bitmapToMat(s.copy(Bitmap.Config.ARGB_8888, false), rgba)
        val out = Mat(); Imgproc.cvtColor(rgba, out, Imgproc.COLOR_RGBA2RGB); rgba.release()
        return out
    }

    /** The frame at [atMs] of [uri] held for [holdMs]. Runs on the calling thread. */
    fun freeze(c: Context, uri: Uri, atMs: Long, holdMs: Long = 2_000): File {
        check(OpenCVLoader.initLocal()) { "เปิดระบบภาพไม่สำเร็จ" }
        val r = MediaMetadataRetriever()
        val frame = try { r.setDataSource(c, uri); r.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST) } finally { runCatching { r.release() } }
            ?: error("อ่านภาพตรงนี้ไม่ได้")
        val (w, h) = outSize(frame.width, frame.height)
        val out = File(dir(c), "freeze_${System.currentTimeMillis()}.mp4")
        val m = rgb(frame, w, h)
        try { FrameEncoder(out, w, h, FPS, 0, silence(c, holdMs)).use { e -> repeat((holdMs * FPS / 1000).toInt().coerceAtLeast(1)) { e.encode(m) }; e.finish() } }
        catch (t: Throwable) { out.delete(); throw t } finally { m.release() }
        return out
    }

    /** [from, to) of [uri] played backwards (no sound of its own). [progress] gets 0..1 and returns false to stop. */
    fun reverse(c: Context, uri: Uri, from: Long, to: Long, progress: (Float) -> Boolean): File {
        check(OpenCVLoader.initLocal()) { "เปิดระบบภาพไม่สำเร็จ" }
        val times = backwardsTimes(from, to)
        val r = MediaMetadataRetriever(); r.setDataSource(c, uri)
        val out = File(dir(c), "reverse_${System.currentTimeMillis()}.mp4")
        try {
            val first = r.getFrameAtTime(times[0] * 1000, MediaMetadataRetriever.OPTION_CLOSEST) ?: error("อ่านภาพของคลิปนี้ไม่ได้")
            // 720p is plenty for a backwards moment and keeps reading each frame quick.
            val (w, h) = outSize(first.width, first.height, 720)
            FrameEncoder(out, w, h, FPS, 0, silence(c, to - from)).use { e ->
                var last: Bitmap = first
                times.forEachIndexed { i, t ->
                    val b = if (i == 0) first else r.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST) ?: last
                    val m = rgb(b, w, h); e.encode(m); m.release(); last = b
                    if (!progress((i + 1).toFloat() / times.size)) error("หยุดแล้ว")
                }
                e.finish()
            }
        } catch (t: Throwable) { out.delete(); throw t } finally { runCatching { r.release() } }
        return out
    }
}
