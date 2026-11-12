package th.ogay.luma

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.video.DISOpticalFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Smooth slow motion by optical flow. Every pair of neighbouring frames gets [factor] − 1 new frames in between:
 * DIS optical flow is measured both ways on a small grey copy, scaled up, and each new frame is the two
 * neighbours warped toward the in-between moment and blended (the linear-motion estimate used by Super SloMo).
 * The result keeps the source frame rate, so it is [factor] times longer; it has no sound.
 */
object SlowFlow {
    /** New frames per source frame needed for [speed] (0.1× → 10). */
    fun factor(speed: Float) = ceil(1f / speed - 1e-3f).toInt().coerceIn(1, 16)

    /** [width]/[height] as shown (rotation applied). */
    class Result(val file: File, val factor: Int, val frames: Int, val width: Int, val height: Int)

    /**
     * Renders [fromMs, toMs) of [uri] to [out]. [progress] gets 0..1; [cancel] stops early (the file is deleted).
     * Long side is capped at [maxSide] pixels to keep the work and memory in check.
     */
    fun render(c: Context, uri: Uri, fromMs: Long, toMs: Long, factor: Int, out: File, cancel: AtomicBoolean,
               maxSide: Int = 1920, progress: (Float) -> Unit = {}): Result {
        check(OpenCVLoader.initLocal()) { "OpenCV not available" }
        val extractor = MediaExtractor().apply { setDataSource(c, uri, null) }
        val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        extractor.selectTrack(track)
        val inFormat = extractor.getTrackFormat(track)
        val rotation = MediaMetadataRetriever().run { try { setDataSource(c, uri); extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0 } finally { release() } }
        val fps = (if (inFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) runCatching { inFormat.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrElse { inFormat.getFloat(MediaFormat.KEY_FRAME_RATE) } else 30f).coerceIn(10f, 240f)
        val decoder = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
        inFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        decoder.configure(inFormat, null, null, 0); decoder.start()
        extractor.seekTo(fromMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        var encoder: MediaCodec? = null; var muxer: MediaMuxer? = null; var muxTrack = -1; var muxing = false
        var w = 0; var h = 0
        val dis = DISOpticalFlow.create(DISOpticalFlow.PRESET_FAST)
        val info = MediaCodec.BufferInfo(); val encInfo = MediaCodec.BufferInfo()
        var prev: Mat? = null; var prevGray: Mat? = null
        var written = 0
        val frameUs = (1_000_000 / fps).toLong()
        val totalUs = ((toMs - fromMs) * 1000).coerceAtLeast(1)
        // Scratch matrices reused for every pair.
        val grid = Mat(); val f01 = Mat(); val f10 = Mat(); val small01 = Mat(); val small10 = Mat()
        val ft0 = Mat(); val ft1 = Mat(); val map0 = Mat(); val map1 = Mat(); val w0 = Mat(); val w1 = Mat(); val mid = Mat(); val none = Mat()
        var flowW = 0; var flowH = 0

        fun drain(end: Boolean) {
            val e = encoder ?: return
            var waits = 0
            while (true) {
                val o = e.dequeueOutputBuffer(encInfo, if (end) 10_000 else 0)
                when {
                    o == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end || ++waits > 500) return
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { muxTrack = muxer!!.addTrack(e.outputFormat); muxer!!.start(); muxing = true }
                    o >= 0 -> {
                        val buf = e.getOutputBuffer(o)!!
                        if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) encInfo.size = 0
                        if (encInfo.size > 0 && muxing) { buf.position(encInfo.offset); buf.limit(encInfo.offset + encInfo.size); muxer!!.writeSampleData(muxTrack, buf, encInfo) }
                        e.releaseOutputBuffer(o, false)
                        if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }
        fun encode(rgb: Mat, last: Boolean = false) {
            val e = encoder!!
            var i: Int
            do { i = e.dequeueInputBuffer(10_000); if (i < 0) drain(false) } while (i < 0 && !cancel.get())
            if (i < 0) return
            val pts = written.toLong() * frameUs
            val img = e.getInputImage(i)!!
            Yuv.writeRgb(rgb, img)
            e.queueInputBuffer(i, 0, w * h * 3 / 2, pts, if (last) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
            written++
            drain(false)
        }
        fun start(srcW: Int, srcH: Int) {
            val s = minOf(1f, maxSide.toFloat() / maxOf(srcW, srcH))
            w = ((srcW * s).roundToInt() / 2 * 2).coerceAtLeast(2); h = ((srcH * s).roundToInt() / 2 * 2).coerceAtLeast(2)
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, (w * h * fps * 0.2f).toInt().coerceIn(2_000_000, 40_000_000))
                setFloat(MediaFormat.KEY_FRAME_RATE, fps); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply { configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); start() }
            muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply { setOrientationHint(rotation) }
            // Flow is measured on a copy about 480 pixels wide, enough for motion and many times faster.
            val fs = minOf(1f, 480f / w); flowW = (w * fs).roundToInt().coerceAtLeast(16); flowH = (h * fs).roundToInt().coerceAtLeast(16)
            // grid(x, y) = (x, y): the identity map that the flow is added to.
            grid.create(h, w, CvType.CV_32FC2)
            val row = FloatArray(w * 2)
            for (y in 0 until h) { for (x in 0 until w) { row[2 * x] = x.toFloat(); row[2 * x + 1] = y.toFloat() }; grid.put(y, 0, row) }
        }
        fun pair(a: Mat, aGray: Mat, b: Mat, bGray: Mat) {
            dis.calc(aGray, bGray, small01); dis.calc(bGray, aGray, small10)
            val sx = w.toDouble() / flowW; val sy = h.toDouble() / flowH
            Imgproc.resize(small01, f01, Size(w.toDouble(), h.toDouble())); Core.multiply(f01, Scalar(sx, sy), f01)
            Imgproc.resize(small10, f10, Size(w.toDouble(), h.toDouble())); Core.multiply(f10, Scalar(sx, sy), f10)
            for (k in 1 until factor) {
                if (cancel.get()) return
                val t = k.toDouble() / factor
                // F(t→0) = −(1−t)t·F01 + t²·F10 ;  F(t→1) = (1−t)²·F01 − t(1−t)·F10
                Core.addWeighted(f01, -(1 - t) * t, f10, t * t, 0.0, ft0)
                Core.addWeighted(f01, (1 - t) * (1 - t), f10, -t * (1 - t), 0.0, ft1)
                Core.add(grid, ft0, map0); Core.add(grid, ft1, map1)
                Imgproc.remap(a, w0, map0, none, Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
                Imgproc.remap(b, w1, map1, none, Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
                Core.addWeighted(w0, 1 - t, w1, t, 0.0, mid)
                encode(mid)
            }
        }

        var inputDone = false; var outputDone = false; var guard = 0
        try {
            while (!outputDone && !cancel.get() && guard++ < 2_000_000) {
                if (!inputDone) {
                    val i = decoder.dequeueInputBuffer(5000)
                    if (i >= 0) {
                        val n = extractor.readSampleData(decoder.getInputBuffer(i)!!, 0)
                        if (n < 0 || extractor.sampleTime > toMs * 1000 + 100_000) { decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { decoder.queueInputBuffer(i, 0, n, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val o = decoder.dequeueOutputBuffer(info, 5000)
                if (o < 0) continue
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val pts = info.presentationTimeUs
                if (info.size > 0 && pts >= fromMs * 1000 && pts < toMs * 1000) {
                    val img = decoder.getOutputImage(o)
                    if (img != null) {
                        if (encoder == null) start(img.cropRect.width(), img.cropRect.height())
                        val rgb = Yuv.toRgb(img, w, h)
                        val gray = Mat(); Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY); Imgproc.resize(gray, gray, Size(flowW.toDouble(), flowH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                        val p = prev; val pg = prevGray
                        if (p != null && pg != null) { pair(p, pg, rgb, gray); p.release(); pg.release() }
                        encode(rgb)
                        prev = rgb; prevGray = gray
                        progress(((pts - fromMs * 1000).toFloat() / totalUs).coerceIn(0f, 1f))
                    }
                }
                decoder.releaseOutputBuffer(o, false)
                if (eos || pts >= toMs * 1000) outputDone = true
            }
            if (cancel.get()) error("cancelled")
            val e = encoder ?: error("ไม่พบเฟรมในช่วงที่เลือก")
            // End of stream on an empty buffer, then write out what is left.
            var i: Int; do { i = e.dequeueInputBuffer(10_000); if (i < 0) drain(false) } while (i < 0)
            e.queueInputBuffer(i, 0, 0, written.toLong() * frameUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
            return if (rotation % 180 == 90) Result(out, factor, written, h, w) else Result(out, factor, written, w, h)
        } catch (t: Throwable) {
            runCatching { out.delete() }
            throw t
        } finally {
            runCatching { decoder.stop() }; runCatching { decoder.release() }; runCatching { extractor.release() }
            runCatching { encoder?.stop() }; runCatching { encoder?.release() }
            runCatching { if (muxing) muxer?.stop() }; runCatching { muxer?.release() }
            listOf(prev, prevGray, grid, f01, f10, small01, small10, ft0, ft1, map0, map1, w0, w1, mid, none).forEach { it?.release() }
        }
    }
}

/** YUV 4:2:0 images (any plane layout) to and from OpenCV RGB matrices. */
object Yuv {
    /** Decoded image → RGB Mat of [w]×[h] (resized if the image is larger). */
    fun toRgb(img: android.media.Image, w: Int, h: Int): Mat {
        val crop = img.cropRect; val iw = crop.width() / 2 * 2; val ih = crop.height() / 2 * 2
        val i420 = ByteArray(iw * ih * 3 / 2)
        copyPlane(img.planes[0], crop.left, crop.top, iw, ih, i420, 0)
        copyPlane(img.planes[1], crop.left / 2, crop.top / 2, iw / 2, ih / 2, i420, iw * ih)
        copyPlane(img.planes[2], crop.left / 2, crop.top / 2, iw / 2, ih / 2, i420, iw * ih * 5 / 4)
        val yuv = Mat(ih * 3 / 2, iw, CvType.CV_8UC1); yuv.put(0, 0, i420)
        val rgb = Mat(); Imgproc.cvtColor(yuv, rgb, Imgproc.COLOR_YUV2RGB_I420); yuv.release()
        if (iw != w || ih != h) Imgproc.resize(rgb, rgb, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
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
