package th.ogay.luma

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.OverlaySettings
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects

/**
 * A layer over the edited video, Canva style. [kind] is [VIDEO] (picture in picture, with its own sound),
 * [IMAGE] or [SOUND]. [at] and [length] are milliseconds of the finished video; [from] is where the layer's own
 * clip starts. [x], [y] are the layer's centre on the frame (0..1), [size] its width as a share of the frame
 * width, [aspect] the layer's own width / height, [volume] its loudness.
 */
data class VideoLayer(
    val kind: Int, val source: Uri, val at: Long, val length: Long, val from: Long = 0L,
    val x: Float = 0.5f, val y: Float = 0.5f, val size: Float = 0.45f, val aspect: Float = 1f, val volume: Float = 1f,
) {
    val end get() = at + length
    val visual get() = kind != SOUND
    val audible get() = kind != IMAGE

    companion object {
        const val VIDEO = 0; const val IMAGE = 1; const val SOUND = 2
        /** Packs a list of layers into a Bundle-friendly string (one line per layer). */
        fun save(list: List<VideoLayer>) = list.joinToString("\n") { l -> listOf(l.kind, l.source, l.at, l.length, l.from, l.x, l.y, l.size, l.aspect, l.volume).joinToString("\t") }
        fun load(text: String?): List<VideoLayer> = text.orEmpty().lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val p = line.split("\t"); if (p.size < 10) return@mapNotNull null
            runCatching { VideoLayer(p[0].toInt(), Uri.parse(p[1]), p[2].toLong(), p[3].toLong(), p[4].toLong(), p[5].toFloat(), p[6].toFloat(), p[7].toFloat(), p[8].toFloat(), p[9].toFloat()) }.getOrNull()
        }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object VideoLayers {
    /** Picture layers as overlays for the whole video (their times are times of the finished video). */
    fun overlays(c: Context, layers: List<VideoLayer>): List<BitmapOverlay> = layers.filter { it.visual }.map { l ->
        if (l.kind == VideoLayer.IMAGE) TimedOverlay(l, picture(c, l.source, 1280)) else MovingOverlay(c, l)
    }

    /** One audio sequence per layer with sound: silence until the layer starts, then its sound. */
    fun sounds(c: Context, layers: List<VideoLayer>, gain: (Float) -> AudioProcessor): List<EditedMediaItemSequence> = layers.filter { it.audible && it.volume > 0f && hasAudio(c, it.source) }.map { l ->
        val clip = MediaItem.Builder().setUri(l.source).setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder().setStartPositionMs(l.from).setEndPositionMs(l.from + l.length).build()).build()
        val item = EditedMediaItem.Builder(clip).setRemoveVideo(true)
            .setEffects(Effects(if (l.volume != 1f) listOf(gain(l.volume)) else emptyList(), emptyList())).build()
        val seq = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
        if (l.at > 0) seq.addGap(l.at * 1000)
        seq.addItem(item).build()
    }

    /** True when the file has a sound track (a silent video layer adds no sound sequence, which would fail). */
    fun hasAudio(c: Context, uri: Uri): Boolean = runCatching {
        MediaMetadataRetriever().run { try { setDataSource(c, uri); extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes" } finally { release() } }
    }.getOrDefault(false)

    /** A picture decoded no larger than [max] pixels on its long side, upright. */
    fun picture(c: Context, uri: Uri, max: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= max) sample *= 2
        val raw = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: error("อ่านรูปไม่ได้")
        val turn = runCatching { c.contentResolver.openInputStream(uri)?.use { androidx.exifinterface.media.ExifInterface(it).rotationDegrees } }.getOrNull() ?: 0
        val upright = if (turn == 0) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, android.graphics.Matrix().apply { postRotate(turn.toFloat()) }, true)
        val s = minOf(1f, max.toFloat() / maxOf(upright.width, upright.height))
        return if (s >= 1f) upright else Bitmap.createScaledBitmap(upright, (upright.width * s).toInt().coerceAtLeast(1), (upright.height * s).toInt().coerceAtLeast(1), true)
    }

    /** Width / height of a layer's picture as shown (rotation applied). */
    fun aspect(c: Context, uri: Uri, video: Boolean): Float = runCatching {
        if (video) MediaMetadataRetriever().run {
            try {
                setDataSource(c, uri)
                val w = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toFloat(); val h = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toFloat()
                val r = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (r % 180 == 90) h / w else w / h
            } finally { release() }
        } else picture(c, uri, 256).let { it.width.toFloat() / it.height }
    }.getOrDefault(1f)

    /** Length in ms of a video or sound file. */
    fun duration(c: Context, uri: Uri): Long = runCatching {
        MediaMetadataRetriever().run { try { setDataSource(c, uri); extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() } finally { release() } }
    }.getOrDefault(0L)

    /** Shared placement: centred on (x, y), [size] of the frame width wide, hidden outside the layer's time. */
    private abstract class Placed(private val layer: VideoLayer) : BitmapOverlay() {
        private var shown: OverlaySettings = StaticOverlaySettings.Builder().build()
        private var hidden: OverlaySettings = StaticOverlaySettings.Builder().setAlphaScale(0f).build()
        abstract val pixelWidth: Int
        override fun configure(videoSize: Size) {
            super.configure(videoSize)
            val scale = layer.size * videoSize.width / pixelWidth.coerceAtLeast(1)
            fun settings(alpha: Float) = StaticOverlaySettings.Builder().setScale(scale, scale).setAlphaScale(alpha)
                .setBackgroundFrameAnchor((layer.x * 2 - 1).coerceIn(-1f, 1f), (1 - layer.y * 2).coerceIn(-1f, 1f)).setOverlayFrameAnchor(0f, 0f).build()
            shown = settings(1f); hidden = settings(0f)
        }
        fun inside(us: Long) = us / 1000 in layer.at until layer.end
        override fun getOverlaySettings(presentationTimeUs: Long) = if (inside(presentationTimeUs)) shown else hidden
    }

    private class TimedOverlay(layer: VideoLayer, private val bitmap: Bitmap) : Placed(layer) {
        override val pixelWidth get() = bitmap.width
        override fun getBitmap(presentationTimeUs: Long) = bitmap
    }

    /** Picture-in-picture video: frames are decoded in order as the export moves forward. */
    private class MovingOverlay(c: Context, private val layer: VideoLayer) : Placed(layer) {
        private val frames = runCatching { LayerFrames(c, layer.source, layer.from, 720) }.getOrNull()
        private val blank = Bitmap.createBitmap(16, (16 / layer.aspect).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        override val pixelWidth get() = frames?.bitmap?.width ?: blank.width
        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            val f = frames ?: return blank
            if (inside(presentationTimeUs)) runCatching { f.advance((presentationTimeUs / 1000 - layer.at) * 1000) }
            return f.bitmap
        }
        override fun release() { super.release(); frames?.close() }
    }
}

/**
 * Decodes a video forward with MediaCodec and keeps the latest frame as an upright ARGB bitmap no wider than
 * [maxWidth]. Only the frame that is asked for is converted to colour; frames in between are skipped.
 */
class LayerFrames(c: Context, uri: Uri, private val fromMs: Long, maxWidth: Int) : AutoCloseable {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val rotation: Int
    private val info = MediaCodec.BufferInfo()
    private var inputDone = false
    private var outputDone = false
    private var lastPts = Long.MIN_VALUE
    val bitmap: Bitmap
    private val pixels: IntArray

    init {
        extractor.setDataSource(c, uri, null)
        val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        rotation = MediaMetadataRetriever().run { try { setDataSource(c, uri); extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0 } finally { release() } }
        val w = format.getInteger(MediaFormat.KEY_WIDTH); val h = format.getInteger(MediaFormat.KEY_HEIGHT)
        val dispW = if (rotation % 180 == 90) h else w; val dispH = if (rotation % 180 == 90) w else h
        val s = minOf(1f, maxWidth.toFloat() / dispW)
        bitmap = Bitmap.createBitmap((dispW * s).toInt().coerceAtLeast(2), (dispH * s).toInt().coerceAtLeast(2), Bitmap.Config.ARGB_8888)
        pixels = IntArray(bitmap.width * bitmap.height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        extractor.seekTo(fromMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
    }

    /** Moves to [layerUs] after the layer's start (only forward) and updates [bitmap]. */
    fun advance(layerUs: Long) {
        val target = fromMs * 1000 + layerUs
        var guard = 0
        while (!outputDone && lastPts < target && guard++ < 2000) step(target)
    }

    private fun step(target: Long) {
        if (!inputDone) {
            val i = codec.dequeueInputBuffer(2000)
            if (i >= 0) {
                val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                else { codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0); extractor.advance() }
            }
        }
        val o = codec.dequeueOutputBuffer(info, 2000)
        if (o >= 0) {
            if (info.size > 0 && info.presentationTimeUs >= target) codec.getOutputImage(o)?.let { convert(it) }
            if (info.size > 0) lastPts = info.presentationTimeUs
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            codec.releaseOutputBuffer(o, false)
        }
    }

    /** YUV 4:2:0 (any layout) to ARGB, sampled straight into the output size and turned upright. */
    private fun convert(img: android.media.Image) {
        val crop = img.cropRect
        val srcW = crop.width(); val srcH = crop.height()
        val y = img.planes[0]; val u = img.planes[1]; val v = img.planes[2]
        val yb = y.buffer; val ub = u.buffer; val vb = v.buffer
        val ow = bitmap.width; val oh = bitmap.height
        val dispW = if (rotation % 180 == 90) srcH else srcW; val dispH = if (rotation % 180 == 90) srcW else srcH
        var k = 0
        for (oy in 0 until oh) {
            val dy = oy * dispH / oh
            for (ox in 0 until ow) {
                val dx = ox * dispW / ow
                val sx: Int; val sy: Int
                when (rotation) {
                    90 -> { sx = dy; sy = srcH - 1 - dx }
                    180 -> { sx = srcW - 1 - dx; sy = srcH - 1 - dy }
                    270 -> { sx = srcW - 1 - dy; sy = dx }
                    else -> { sx = dx; sy = dy }
                }
                val px = sx + crop.left; val py = sy + crop.top
                val yy = (yb.get(py * y.rowStride + px * y.pixelStride).toInt() and 255) - 16
                val cu = (ub.get((py / 2) * u.rowStride + (px / 2) * u.pixelStride).toInt() and 255) - 128
                val cv = (vb.get((py / 2) * v.rowStride + (px / 2) * v.pixelStride).toInt() and 255) - 128
                val l = 1192 * yy.coerceAtLeast(0)
                val r = ((l + 1634 * cv) shr 10).coerceIn(0, 255)
                val g = ((l - 833 * cv - 400 * cu) shr 10).coerceIn(0, 255)
                val b = ((l + 2066 * cu) shr 10).coerceIn(0, 255)
                pixels[k++] = Color.rgb(r, g, b)
            }
        }
        bitmap.setPixels(pixels, 0, ow, 0, 0, ow, oh)
    }

    override fun close() {
        runCatching { codec.stop() }; runCatching { codec.release() }; runCatching { extractor.release() }
    }
}
