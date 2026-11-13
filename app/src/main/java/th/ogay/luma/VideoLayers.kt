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
    /** How solid the layer is (1 = fully), and how long it takes to fade in and out, in ms. */
    val opacity: Float = 1f, val fadeIn: Long = 0L, val fadeOut: Long = 0L,
    /** For a [TEXT] layer: the words, their colour and [style] (1 = dark box behind, 2 = outline, 4 = light weight). */
    val text: String = "", val color: Int = Color.WHITE, val style: Int = 0,
    /** Keyframes, "ms,x,y,size,opacity" joined by ';'. ms counts from the layer's start. Between two keyframes the layer glides from one to the other. */
    val keys: String = "",
    /** Rotation of a picture layer in degrees, clockwise. */
    val turn: Float = 0f,
    /** Chroma key colour (0 = off) and how far around it counts as that colour (0..1). */
    val key: Int = 0, val keyTol: Float = 0.4f,
    /** Shape mask ([StudioMatte.NONE], ELLIPSE, ROUNDED), its soft edge (0..1) and the corner rounding of the picture (0..1). */
    val shape: Int = 0, val feather: Float = 0.1f, val round: Float = 0f,
    /** Left out of the preview and the export, but kept in the project. */
    val hidden: Boolean = false,
    /** Cannot be moved on the picture or on the timeline. */
    val locked: Boolean = false,
    /** A sound layer that is background music (it is turned down while other sound layers play). */
    val music: Boolean = false,
    /** A text layer made as a caption (from speech or an .srt file); the caption menu styles and removes these together. */
    val caption: Boolean = false,
) {
    val matte get() = StudioMatte.Spec(key, keyTol, shape, feather, round)
    val end get() = at + length
    val visual get() = kind != SOUND
    val audible get() = kind == VIDEO || kind == SOUND
    val still get() = kind == IMAGE || kind == TEXT
    val animated get() = keys.isNotEmpty() || fadeIn > 0 || fadeOut > 0

    class Key(val t: Long, val x: Float, val y: Float, val size: Float, val opacity: Float, val ease: Int = StudioMath.EASE_SMOOTH)
    val keyList: List<Key> by lazy { keys.split(';').mapNotNull { k -> val p = k.split(','); if (p.size < 5) null else runCatching { Key(p[0].toLong(), p[1].toFloat(), p[2].toFloat(), p[3].toFloat(), p[4].toFloat(), p.getOrNull(5)?.toIntOrNull() ?: StudioMath.EASE_SMOOTH) }.getOrNull() }.sortedBy { it.t } }
    fun withKeys(list: List<Key>) = copy(keys = list.sortedBy { it.t }.joinToString(";") { "${it.t},${it.x},${it.y},${it.size},${it.opacity},${it.ease}" })

    /** Centre x, centre y, size and opacity at [outMs] of the finished video, from the keyframes (without the fades). */
    fun rawPose(outMs: Long): FloatArray {
        val k = keyList
        if (k.isEmpty()) return floatArrayOf(x, y, size, opacity)
        return StudioMath.glide(LongArray(k.size) { k[it].t }, Array(k.size) { floatArrayOf(k[it].x, k[it].y, k[it].size, k[it].opacity) }, outMs - at, IntArray(k.size) { k[it].ease })
    }
    /** As [rawPose], with the fade in and fade out applied to the opacity. The preview and the export both draw from this. */
    fun pose(outMs: Long): FloatArray { val p = rawPose(outMs); p[3] = (p[3] * fade(outMs)).coerceIn(0f, 1f); return p }
    /** 0..1: how far the fade in or fade out has let the layer through at [outMs]. Used for the picture and for the sound. */
    fun fade(outMs: Long): Float = StudioMath.fade(outMs - at, length, fadeIn, fadeOut)

    companion object {
        const val VIDEO = 0; const val IMAGE = 1; const val SOUND = 2; const val TEXT = 3
        /** A text layer has no file; its "address" is made from its words and look, so a changed text is a new picture. */
        fun textSource(text: String, color: Int, style: Int): Uri = Uri.parse("lumoratext:" + Uri.encode("$color/$style/$text"))
        /** Packs a list of layers into a Bundle-friendly string (one line per layer). */
        fun save(list: List<VideoLayer>) = list.joinToString("\n") { l -> listOf(l.kind, l.source, l.at, l.length, l.from, l.x, l.y, l.size, l.aspect, l.volume,
            l.opacity, l.fadeIn, l.fadeOut, Uri.encode(l.text).ifEmpty { "-" }, l.color, l.style, l.keys.ifEmpty { "-" }, l.turn, l.key, l.keyTol, l.shape, l.feather, l.round, if (l.hidden) 1 else 0, if (l.locked) 1 else 0, if (l.music) 1 else 0, if (l.caption) 1 else 0).joinToString("\t") }
        fun load(text: String?): List<VideoLayer> = text.orEmpty().lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val p = line.split("\t"); if (p.size < 10) return@mapNotNull null
            runCatching { VideoLayer(p[0].toInt(), Uri.parse(p[1]), p[2].toLong(), p[3].toLong(), p[4].toLong(), p[5].toFloat(), p[6].toFloat(), p[7].toFloat(), p[8].toFloat(), p[9].toFloat(),
                p.getOrNull(10)?.toFloatOrNull() ?: 1f, p.getOrNull(11)?.toLongOrNull() ?: 0L, p.getOrNull(12)?.toLongOrNull() ?: 0L,
                p.getOrNull(13)?.takeIf { it != "-" }?.let { Uri.decode(it) } ?: "", p.getOrNull(14)?.toIntOrNull() ?: Color.WHITE, p.getOrNull(15)?.toIntOrNull() ?: 0,
                p.getOrNull(16)?.takeIf { it != "-" } ?: "", p.getOrNull(17)?.toFloatOrNull() ?: 0f,
                p.getOrNull(18)?.toIntOrNull() ?: 0, p.getOrNull(19)?.toFloatOrNull() ?: 0.4f, p.getOrNull(20)?.toIntOrNull() ?: 0, p.getOrNull(21)?.toFloatOrNull() ?: 0.1f,
                p.getOrNull(22)?.toFloatOrNull() ?: 0f, p.getOrNull(23) == "1", p.getOrNull(24) == "1", p.getOrNull(25) == "1", p.getOrNull(26) == "1") }.getOrNull()
        }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object VideoLayers {
    /** Picture layers as overlays for the whole video (their times are times of the finished video). */
    fun overlays(c: Context, layers: List<VideoLayer>): List<BitmapOverlay> = layers.filter { it.visual }.map { l ->
        when (l.kind) { VideoLayer.IMAGE -> TimedOverlay(l, matted(picture(c, l.source, 1280), l.matte)); VideoLayer.TEXT -> TimedOverlay(l, textPicture(l)); else -> MovingOverlay(c, l) }
    }
    /** A copy of [b] with the layer's chroma key and mask applied ([b] itself when there is nothing to apply). */
    fun matted(b: Bitmap, m: StudioMatte.Spec): Bitmap {
        if (!m.active) return b
        val w = b.width; val h = b.height; val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        StudioMatte.apply(px, w, h, m)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(px, 0, w, 0, 0, w, h) }
    }

    /** Fonts a text layer can use (bits 8–9 of [VideoLayer.style]) and alignments (bits 12–13). */
    val FONTS = listOf("sans-serif", "serif", "monospace", "sans-serif-condensed")
    fun font(style: Int) = (style shr 8) and 3
    fun align(style: Int) = (style shr 12) and 3
    /** A text layer whose words start with this mark is a shape: the number after it picks which one. */
    const val SHAPE = "\u0001shape:"
    val SHAPES = listOf("สี่เหลี่ยม", "วงกลม", "ลูกศร", "ดาว", "หัวใจ", "เส้น")
    fun shapeOf(text: String) = if (text.startsWith(SHAPE)) text.removePrefix(SHAPE).toIntOrNull()?.coerceIn(0, SHAPES.size - 1) ?: 0 else -1

    /** A shape in the layer's colour; with the outline style only its edge is drawn. */
    private fun shapePicture(l: VideoLayer, kind: Int, width: Int): Bitmap {
        val w = width / 2; val h = if (kind == 2 || kind == 5) w / 2 else w
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val cv = android.graphics.Canvas(out)
        val outline = l.style and 2 != 0
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = l.color; style = if (outline) android.graphics.Paint.Style.STROKE else android.graphics.Paint.Style.FILL; strokeWidth = w * 0.05f; strokeJoin = android.graphics.Paint.Join.ROUND }
        val m = if (outline) w * 0.04f else 0f; val fw = w.toFloat(); val fh = h.toFloat()
        val path = android.graphics.Path()
        when (kind) {
            0 -> cv.drawRoundRect(m, m, fw - m, fh - m, fw * 0.08f, fw * 0.08f, p)
            1 -> cv.drawOval(m, m, fw - m, fh - m, p)
            2 -> { path.moveTo(m, fh * 0.35f); path.lineTo(fw * 0.6f, fh * 0.35f); path.lineTo(fw * 0.6f, m); path.lineTo(fw - m, fh / 2); path.lineTo(fw * 0.6f, fh - m); path.lineTo(fw * 0.6f, fh * 0.65f); path.lineTo(m, fh * 0.65f); path.close(); cv.drawPath(path, p) }
            3 -> { for (i in 0 until 10) { val r = if (i % 2 == 0) fw / 2 - m else fw * 0.2f; val a = Math.PI / 5 * i - Math.PI / 2
                    val x = fw / 2 + r * Math.cos(a).toFloat(); val y = fh / 2 + r * Math.sin(a).toFloat(); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }; path.close(); cv.drawPath(path, p) }
            4 -> { path.moveTo(fw / 2, fh * 0.9f); path.cubicTo(fw * 0.05f, fh * 0.55f, fw * 0.1f, fh * 0.08f, fw / 2, fh * 0.3f); path.cubicTo(fw * 0.9f, fh * 0.08f, fw * 0.95f, fh * 0.55f, fw / 2, fh * 0.9f); path.close(); cv.drawPath(path, p) }
            else -> cv.drawRoundRect(m, fh * 0.38f, fw - m, fh * 0.62f, fh * 0.12f, fh * 0.12f, p.apply { style = android.graphics.Paint.Style.FILL })
        }
        return out
    }

    /** The words of a text layer drawn on a clear picture, in its font and alignment, with a soft shadow, a dark box or an outline. */
    fun textPicture(l: VideoLayer, width: Int = 1080): Bitmap {
        shapeOf(l.text).takeIf { it >= 0 }?.let { return shapePicture(l, it, width) }
        val words = l.text.ifBlank { " " }
        val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = width * 0.105f; color = l.color
            typeface = android.graphics.Typeface.create(FONTS[font(l.style)], if (l.style and 4 != 0) android.graphics.Typeface.NORMAL else android.graphics.Typeface.BOLD)
            if (l.style and 3 == 0) setShadowLayer(textSize * 0.14f, 0f, textSize * 0.04f, 0xb0000000.toInt()) }
        val pad = (paint.textSize * 0.4f).toInt(); val room = width - pad * 2
        val alignment = when (align(l.style)) { 1 -> android.text.Layout.Alignment.ALIGN_NORMAL; 2 -> android.text.Layout.Alignment.ALIGN_OPPOSITE; else -> android.text.Layout.Alignment.ALIGN_CENTER }
        fun layout(p: android.text.TextPaint, w: Int) = android.text.StaticLayout.Builder.obtain(words, 0, words.length, p, w).setAlignment(alignment).setIncludePad(true).build()
        val first = layout(paint, room)
        val wide = kotlin.math.ceil((0 until first.lineCount).maxOf { first.getLineWidth(it) }).toInt().coerceIn(1, room)
        // Laid out again at the width of the longest line, so left and right alignment line up inside the picture.
        val lay = layout(paint, wide)
        val bw = wide + pad * 2; val bh = lay.height + pad * 2
        val out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888); val cv = android.graphics.Canvas(out)
        val dark = Color.red(l.color) * 0.3f + Color.green(l.color) * 0.59f + Color.blue(l.color) * 0.11f < 110
        if (l.style and 1 != 0) cv.drawRoundRect(0f, 0f, bw.toFloat(), bh.toFloat(), pad * 0.6f, pad * 0.6f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) 0xd9ffffff.toInt() else 0xb3000000.toInt() })
        cv.translate(pad.toFloat(), pad.toFloat())
        if (l.style and 2 != 0) layout(android.text.TextPaint(paint).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = textSize * 0.16f; strokeJoin = android.graphics.Paint.Join.ROUND; color = if (dark) Color.WHITE else Color.BLACK; clearShadowLayer() }, wide).draw(cv)
        lay.draw(cv)
        return out
    }

    /** One audio sequence per layer with sound: silence until the layer starts, then its sound. */
    fun sounds(c: Context, layers: List<VideoLayer>, gain: (Float) -> AudioProcessor): List<EditedMediaItemSequence> = layers.filter { it.audible && it.volume > 0f && hasAudio(c, it.source) }.map { l ->
        val clip = MediaItem.Builder().setUri(l.source).setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder().setStartPositionMs(l.from).setEndPositionMs(l.from + l.length).build()).build()
        val item = EditedMediaItem.Builder(clip).setRemoveVideo(true)
            .setEffects(Effects(listOfNotNull(if (l.volume != 1f) gain(l.volume) else null, if (l.fadeIn > 0 || l.fadeOut > 0) FadeAudio(l.fadeIn, l.fadeOut, l.length) else null), emptyList())).build()
        val seq = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
        if (l.at > 0) seq.addGap(l.at * 1000)
        seq.addItem(item).build()
    }

    /** Turns the sound up from silence over [inMs] at the start and down to silence over [outMs] at the end of a [lengthMs] long piece. */
    private class FadeAudio(private val inMs: Long, private val outMs: Long, private val lengthMs: Long) : androidx.media3.common.audio.BaseAudioProcessor() {
        private var frames = 0L
        override fun onConfigure(f: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat = if (f.encoding == C.ENCODING_PCM_16BIT) f else AudioProcessor.AudioFormat.NOT_SET
        override fun queueInput(input: java.nio.ByteBuffer) {
            val n = input.remaining(); if (n == 0) return
            val out = replaceOutputBuffer(n); val ch = inputAudioFormat.channelCount.coerceAtLeast(1); val rate = inputAudioFormat.sampleRate.coerceAtLeast(1)
            while (input.remaining() >= 2 * ch) {
                val ms = frames * 1000 / rate; var g = 1f
                if (inMs > 0 && ms < inMs) g = ms.toFloat() / inMs
                if (outMs > 0 && ms > lengthMs - outMs) g = minOf(g, ((lengthMs - ms).toFloat() / outMs).coerceIn(0f, 1f))
                repeat(ch) { out.putShort((input.short * g).toInt().toShort()) }
                frames++
            }
            while (input.hasRemaining()) out.put(input.get())
            out.flip()
        }
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
        private var frameW = 0
        abstract val pixelWidth: Int
        private fun settings(x: Float, y: Float, size: Float, alpha: Float): OverlaySettings {
            val scale = size * frameW / pixelWidth.coerceAtLeast(1)
            return StaticOverlaySettings.Builder().setScale(scale, scale).setAlphaScale(alpha).setRotationDegrees(-layer.turn)
                .setBackgroundFrameAnchor((x * 2 - 1).coerceIn(-1f, 1f), (1 - y * 2).coerceIn(-1f, 1f)).setOverlayFrameAnchor(0f, 0f).build()
        }
        override fun configure(videoSize: Size) {
            super.configure(videoSize)
            frameW = videoSize.width
            shown = settings(layer.x, layer.y, layer.size, layer.opacity); hidden = settings(layer.x, layer.y, layer.size, 0f)
        }
        fun inside(us: Long) = us / 1000 in layer.at until layer.end
        /** A layer that moves or fades gets its place worked out for every frame, from the same numbers the preview uses. */
        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
            if (!inside(presentationTimeUs)) return hidden
            if (!layer.animated) return shown
            val p = layer.pose(presentationTimeUs / 1000); return settings(p[0], p[1], p[2], p[3])
        }
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
        private val matte = layer.matte
        private val out = frames?.bitmap?.takeIf { matte.active }?.let { Bitmap.createBitmap(it.width, it.height, Bitmap.Config.ARGB_8888) }
        private val px = out?.let { IntArray(it.width * it.height) }
        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            val f = frames ?: return blank
            if (inside(presentationTimeUs)) runCatching { f.advance((presentationTimeUs / 1000 - layer.at) * 1000) }
            if (out == null || px == null) return f.bitmap
            // The decoded frame stays untouched; the cut-out goes into a second picture every frame.
            f.bitmap.getPixels(px, 0, out.width, 0, 0, out.width, out.height); StudioMatte.apply(px, out.width, out.height, matte); out.setPixels(px, 0, out.width, 0, 0, out.width, out.height)
            return out
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
