package th.ogay.luma

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.video.DISOpticalFlow
import th.ogay.luma.ai.DetailResampler
import th.ogay.luma.ai.DetailRuntime
import th.ogay.luma.ai.EnhanceSpec
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Reads a video's frames in order as RGB matrices (coded orientation; [rotation] says how to show them). */
class FrameDecoder(c: Context, uri: Uri, private val fromUs: Long = 0L, private val toUs: Long = Long.MAX_VALUE / 4) : AutoCloseable {
    class Frame(val ptsUs: Long, val rgb: Mat)

    private val extractor = MediaExtractor().apply { setDataSource(c, uri, null) }
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private var inputDone = false
    private var outputDone = false
    val rotation: Int
    val fps: Float
    val durationUs: Long

    init {
        val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val meta = MediaMetadataRetriever().apply { setDataSource(c, uri) }
        try {
            rotation = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            durationUs = (meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
            val count = if (android.os.Build.VERSION.SDK_INT >= 28) meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull() else null
            val declared = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrElse { runCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE) }.getOrDefault(0f) } else 0f
            // Counted frames over the length is the truest rate; the container's own number comes next.
            fps = (if (count != null && count > 1 && durationUs > 0) count * 1_000_000f / durationUs else if (declared > 0f) declared else 30f).coerceIn(5f, 240f)
        } finally { meta.release() }
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0); codec.start()
        extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
    }

    /** The next frame inside the range, or null at the end. The caller owns (and releases) the matrix. */
    fun next(): Frame? {
        var guard = 0
        while (!outputDone && guard++ < 100_000) {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(5000)
                if (i >= 0) {
                    val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (n < 0 || extractor.sampleTime > toUs + 100_000) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0); extractor.advance() }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 5000)
            if (o < 0) continue
            val pts = info.presentationTimeUs
            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            var frame: Frame? = null
            if (info.size > 0 && pts >= fromUs && pts < toUs) codec.getOutputImage(o)?.let { frame = Frame(pts, Yuv.toRgb(it)) }
            codec.releaseOutputBuffer(o, false)
            if (eos || pts >= toUs) outputDone = true
            if (frame != null) return frame
        }
        return null
    }

    override fun close() { runCatching { codec.stop() }; runCatching { codec.release() }; runCatching { extractor.release() } }
}

/**
 * Encodes RGB frames at a fixed rate into an MP4 (H.264, or HEVC when H.264 cannot do the size and rate), and at
 * the end copies the sound track of [audioFrom] unchanged.
 */
class FrameEncoder(private val out: File, val width: Int, val height: Int, private val fps: Float, rotation: Int, private val audioFrom: File? = null) : AutoCloseable {
    private val codec: MediaCodec
    private val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply { setOrientationHint(rotation) }
    private val info = MediaCodec.BufferInfo()
    private var videoTrack = -1; private var audioTrack = -1; private var started = false
    private val audio: MediaExtractor? = audioFrom?.let { f -> runCatching { MediaExtractor().apply { setDataSource(f.path) } }.getOrNull() }
    private val audioIndex = audio?.let { a -> (0 until a.trackCount).firstOrNull { a.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } } ?: -1
    var frames = 0; private set
    private val frameUs = 1_000_000.0 / fps

    init {
        val mime = pickMime(width, height, fps) ?: error("เครื่องนี้เข้ารหัสวิดีโอ ${width}×${height} ${fps.roundToInt()} fps ไม่ได้")
        val fmt = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate(width, height, fps, mime))
            setFloat(MediaFormat.KEY_FRAME_RATE, fps); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec = MediaCodec.createEncoderByType(mime).apply { configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); start() }
    }

    fun encode(rgb: Mat) {
        var i: Int
        do { i = codec.dequeueInputBuffer(10_000); if (i < 0) drain(false) } while (i < 0)
        Yuv.writeRgb(rgb, codec.getInputImage(i)!!)
        codec.queueInputBuffer(i, 0, width * height * 3 / 2, (frames * frameUs).toLong(), 0)
        frames++
        drain(false)
    }

    /** Ends the video, adds the sound and closes the file. */
    fun finish() {
        var i: Int
        do { i = codec.dequeueInputBuffer(10_000); if (i < 0) drain(false) } while (i < 0)
        codec.queueInputBuffer(i, 0, 0, (frames * frameUs).toLong(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(true)
        check(started) { "ไม่มีเฟรมให้บันทึก" }
        val a = audio
        if (a != null && audioTrack >= 0) {
            a.selectTrack(audioIndex)
            val buf = ByteBuffer.allocate(1 shl 20); val ai = MediaCodec.BufferInfo()
            while (true) {
                val n = a.readSampleData(buf, 0); if (n < 0) break
                ai.set(0, n, a.sampleTime, if (a.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(audioTrack, buf, ai); a.advance()
            }
        }
        muxer.stop()
    }

    private fun drain(end: Boolean) {
        var waits = 0
        while (true) {
            val o = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            when {
                o == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end || ++waits > 600) return
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    videoTrack = muxer.addTrack(codec.outputFormat)
                    if (audioIndex >= 0) audioTrack = muxer.addTrack(audio!!.getTrackFormat(audioIndex))
                    muxer.start(); started = true
                }
                o >= 0 -> {
                    val b = codec.getOutputBuffer(o)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started) { b.position(info.offset); b.limit(info.offset + info.size); muxer.writeSampleData(videoTrack, b, info) }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    override fun close() {
        runCatching { codec.stop() }; runCatching { codec.release() }; runCatching { muxer.release() }; runCatching { audio?.release() }
    }

    companion object {
        /** H.264 if it can encode this size and rate, else HEVC, else null. */
        fun pickMime(w: Int, h: Int, fps: Float): String? = listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC).firstOrNull { mime ->
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { ci ->
                ci.isEncoder && ci.supportedTypes.any { it.equals(mime, true) } && runCatching {
                    val v = ci.getCapabilitiesForType(mime).videoCapabilities
                    v.areSizeAndRateSupported(w, h, fps.toDouble()) || v.areSizeAndRateSupported(h, w, fps.toDouble())
                }.getOrDefault(false)
            }
        }
        fun bitrate(w: Int, h: Int, fps: Float, mime: String = MediaFormat.MIMETYPE_VIDEO_AVC): Int {
            val bpp = if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) 0.07f else 0.11f
            return (w.toLong() * h * fps * bpp).toInt().coerceIn(2_000_000, 100_000_000)
        }
    }
}

/**
 * In-between frames of two neighbours by optical flow, at [w]×[h]. DIS flow is measured both ways on small grey
 * copies, scaled up, and the frame at time t is both neighbours warped toward t (Super SloMo's linear-motion
 * estimate) and blended.
 */
class FlowBlender(private val w: Int, private val h: Int) : AutoCloseable {
    private val dis = DISOpticalFlow.create(DISOpticalFlow.PRESET_FAST)
    private val grid = Mat(h, w, CvType.CV_32FC2)
    private val f01 = Mat(); private val f10 = Mat(); private val s01 = Mat(); private val s10 = Mat()
    private val ft0 = Mat(); private val ft1 = Mat(); private val m0 = Mat(); private val m1 = Mat()
    private val w0 = Mat(); private val w1 = Mat(); private val out = Mat(); private val none = Mat()
    private var a: Mat? = null; private var b: Mat? = null

    init {
        val row = FloatArray(w * 2)
        for (y in 0 until h) { for (x in 0 until w) { row[2 * x] = x.toFloat(); row[2 * x + 1] = y.toFloat() }; grid.put(y, 0, row) }
    }

    /** [a], [b]: RGB at w×h; [aGray], [bGray]: grey copies of the same small size. */
    fun pair(a: Mat, aGray: Mat, b: Mat, bGray: Mat) {
        dis.calc(aGray, bGray, s01); dis.calc(bGray, aGray, s10)
        val sx = w.toDouble() / aGray.cols(); val sy = h.toDouble() / aGray.rows()
        Imgproc.resize(s01, f01, Size(w.toDouble(), h.toDouble())); Core.multiply(f01, Scalar(sx, sy), f01)
        Imgproc.resize(s10, f10, Size(w.toDouble(), h.toDouble())); Core.multiply(f10, Scalar(sx, sy), f10)
        this.a = a; this.b = b
    }

    /** The frame at [t] (0 = a, 1 = b). The matrix is reused by the next call. */
    fun at(t: Double): Mat {
        // F(t→0) = −(1−t)t·F01 + t²·F10 ;  F(t→1) = (1−t)²·F01 − t(1−t)·F10
        Core.addWeighted(f01, -(1 - t) * t, f10, t * t, 0.0, ft0)
        Core.addWeighted(f01, (1 - t) * (1 - t), f10, -t * (1 - t), 0.0, ft1)
        Core.add(grid, ft0, m0); Core.add(grid, ft1, m1)
        Imgproc.remap(a!!, w0, m0, none, Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
        Imgproc.remap(b!!, w1, m1, none, Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
        Core.addWeighted(w0, 1 - t, w1, t, 0.0, out)
        return out
    }

    override fun close() { listOf(grid, f01, f10, s01, s10, ft0, ft1, m0, m1, w0, w1, out, none).forEach { it.release() } }

    companion object {
        /** Grey copy about 480 pixels wide for measuring motion. */
        fun gray(rgb: Mat): Mat {
            val s = minOf(1.0, 480.0 / rgb.cols())
            val g = Mat(); Imgproc.cvtColor(rgb, g, Imgproc.COLOR_RGB2GRAY)
            Imgproc.resize(g, g, Size((rgb.cols() * s).roundToInt().coerceAtLeast(16).toDouble(), (rgb.rows() * s).roundToInt().coerceAtLeast(16).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            return g
        }
    }
}

/**
 * Lumora's Detail SR model on video frames: the frame is cut into 192-pixel tiles with a 36-pixel border, each
 * tile is enlarged ×4 by the model and brought to the needed ×2–×4, then the whole frame is fitted to the
 * exact output size. Runs on one thread (GPU when available).
 */
class FrameSr(c: Context) : AutoCloseable {
    // The GPU delegate crashes in native code on the emulator (the Detail SR device test runs on CPU there too).
    private val rt = DetailRuntime(c, forceCpu = emulator())
    val backend get() = rt.backend

    fun upscale(rgb: Mat, outW: Int, outH: Int, cancelled: () -> Boolean): Mat {
        val w = rgb.cols(); val h = rgb.rows()
        val scale = ceil(maxOf(outW.toDouble() / w, outH.toDouble() / h) - 1e-3).toInt().coerceIn(2, 4)
        val bytes = ByteArray(w * h * 3); rgb.get(0, 0, bytes)
        val px = IntArray(w * h) { i -> val j = i * 3; (0xff shl 24) or ((bytes[j].toInt() and 255) shl 16) or ((bytes[j + 1].toInt() and 255) shl 8) or (bytes[j + 2].toInt() and 255) }
        val core = EnhanceSpec.CORE; val halo = EnhanceSpec.HALO; val n = EnhanceSpec.INPUT
        val bw = w * scale; val bh = h * scale
        val big = ByteArray(bw * bh * 3)
        val band = IntArray(bw * core * scale)
        val input = IntArray(n * n)
        var y0 = 0
        while (y0 < h) {
            val coreH = minOf(core, h - y0)
            var x0 = 0
            while (x0 < w) {
                if (cancelled()) error("cancelled")
                val coreW = minOf(core, w - x0)
                for (i in 0 until n * n) {
                    val x = (x0 - halo + i % n).coerceIn(0, w - 1); val y = (y0 - halo + i / n).coerceIn(0, h - 1)
                    input[i] = px[y * w + x]
                }
                DetailResampler.copyCore(rt.run(input), input, scale, 100, coreW, coreH, band, bw, x0 * scale)
                x0 += core
            }
            for (yy in 0 until coreH * scale) {
                val dst = ((y0 * scale + yy) * bw) * 3; val src = yy * bw
                for (xx in 0 until bw) { val p = band[src + xx]; val k = dst + xx * 3; big[k] = (p shr 16).toByte(); big[k + 1] = (p shr 8).toByte(); big[k + 2] = p.toByte() }
            }
            y0 += core
        }
        val m = Mat(bh, bw, CvType.CV_8UC3); m.put(0, 0, big)
        if (bw != outW || bh != outH) Imgproc.resize(m, m, Size(outW.toDouble(), outH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        return m
    }

    override fun close() = rt.close()

    private companion object {
        fun emulator() = android.os.Build.HARDWARE.let { it.contains("ranchu") || it.contains("goldfish") } || android.os.Build.FINGERPRINT.contains("generic")
    }
}

/** YUV 4:2:0 images (any plane layout) to and from OpenCV RGB matrices. */
object Yuv {
    /** Decoded image → RGB Mat at its own (even) size. */
    fun toRgb(img: android.media.Image): Mat {
        val crop = img.cropRect; val iw = crop.width() / 2 * 2; val ih = crop.height() / 2 * 2
        val i420 = ByteArray(iw * ih * 3 / 2)
        copyPlane(img.planes[0], crop.left, crop.top, iw, ih, i420, 0)
        copyPlane(img.planes[1], crop.left / 2, crop.top / 2, iw / 2, ih / 2, i420, iw * ih)
        copyPlane(img.planes[2], crop.left / 2, crop.top / 2, iw / 2, ih / 2, i420, iw * ih * 5 / 4)
        val yuv = Mat(ih * 3 / 2, iw, CvType.CV_8UC1); yuv.put(0, 0, i420)
        val rgb = Mat(); Imgproc.cvtColor(yuv, rgb, Imgproc.COLOR_YUV2RGB_I420); yuv.release()
        return rgb
    }
    private fun copyPlane(p: android.media.Image.Plane, x0: Int, y0: Int, pw: Int, ph: Int, out: ByteArray, off: Int) {
        val b = p.buffer; val rs = p.rowStride; val ps = p.pixelStride
        if (ps == 1) {
            for (y in 0 until ph) { b.position((y0 + y) * rs + x0); b.get(out, off + y * pw, pw) }
        } else {
            val row = ByteArray(rs)
            for (y in 0 until ph) {
                val start = (y0 + y) * rs + x0 * ps
                val n = minOf(rs, b.capacity() - start); b.position(start); b.get(row, 0, n)
                var k = off + y * pw; var x = 0
                while (x < pw) { out[k++] = row[x * ps]; x++ }
            }
        }
        b.rewind()
    }
    /** RGB Mat → an encoder input image (I420 written through the image's own strides). */
    fun writeRgb(rgb: Mat, img: android.media.Image) {
        val w = rgb.cols(); val h = rgb.rows()
        val yuv = Mat(); Imgproc.cvtColor(rgb, yuv, Imgproc.COLOR_RGB2YUV_I420)
        val data = ByteArray(w * h * 3 / 2); yuv.get(0, 0, data); yuv.release()
        fun put(p: android.media.Image.Plane, pw: Int, ph: Int, off: Int) {
            val b = p.buffer; val rs = p.rowStride; val ps = p.pixelStride
            if (ps == 1) for (y in 0 until ph) { b.position(y * rs); b.put(data, off + y * pw, pw) }
            else for (y in 0 until ph) { val base = y * rs; for (x in 0 until pw) b.put(base + x * ps, data[off + y * pw + x]) }
        }
        put(img.planes[0], w, h, 0); put(img.planes[1], w / 2, h / 2, w * h); put(img.planes[2], w / 2, h / 2, w * h * 5 / 4)
    }
}
