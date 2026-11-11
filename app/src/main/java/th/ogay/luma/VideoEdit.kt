package th.ogay.luma

import android.content.Context
import android.graphics.Color
import android.graphics.ColorMatrix
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.Presentation
import androidx.media3.effect.SingleColorLut
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.transformer.Codec
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.Transformer

/** One export of the video editor, as plain values (so a device test can export each effect on its own). */
data class VideoEditSpec(
    val source: Uri, val from: Long, val to: Long, val videoW: Int, val videoH: Int,
    val speed: Float = 1f, val crop: Float = 0f,
    val caption: String = "", val place: Int = 2, val textColor: Int = Color.WHITE, val textBig: Boolean = false,
    val look: ColorMatrix? = null, val music: Uri? = null, val keepAudio: Boolean = true,
    /** Quarter turns clockwise and a left–right mirror, applied before the crop. */
    val turns: Int = 0, val mirror: Boolean = false,
    /** Loudness of the clip's own sound and of the music (1 = as recorded). */
    val volume: Float = 1f, val musicVolume: Float = 1f,
    /** A middle part [cutFrom, cutTo) taken out; the two sides are joined in one file. */
    val cutFrom: Long = -1L, val cutTo: Long = -1L,
    /** A dark box behind the caption. */
    val textBox: Boolean = false,
    /** Further clips played after this one (each whole or trimmed); the result is one file. */
    val extras: List<VideoPart> = emptyList(),
    /** Between two different clips: 0 cut, 1 fade through black, 2 flash of white, 3 zoom in. */
    val transition: Int = 0,
    /** Emoji stickers over the whole video. */
    val stickers: List<VideoSticker> = emptyList())

/** A clip appended after the main one. [from]/[to] in ms of that clip. */
data class VideoPart(val source: Uri, val from: Long, val to: Long)

/** An emoji on the picture: [x], [y] are 0..1 across the visible (cropped) frame, [big] doubles its size. */
data class VideoSticker(val text: String, val x: Float, val y: Float, val big: Boolean = false)

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object VideoEdit {
    /** Sound louder or quieter for mono and stereo tracks. */
    private fun gain(v: Float): AudioProcessor = ChannelMixingAudioProcessor().apply { for (n in 1..2) putChannelMixingMatrix(ChannelMixingMatrix.create(n, n).scaleBy(v)) }
    /** Picture height after the turn and the crop, the frame the caption and stickers are measured against. */
    private fun frameSize(s: VideoEditSpec): Pair<Int, Int> {
        if (s.crop <= 0f) return s.videoW to s.videoH
        val now = s.videoW.toFloat() / s.videoH
        return if (s.crop > now) s.videoW to (s.videoW / s.crop).toInt() else (s.videoH * s.crop).toInt() to s.videoH
    }
    private fun overlays(s: VideoEditSpec): List<TextureOverlay> {
        val frameH = frameSize(s).second.toFloat()
        val out = mutableListOf<TextureOverlay>()
        if (s.caption.isNotBlank()) {
            val text = SpannableString(s.caption).apply { setSpan(ForegroundColorSpan(s.textColor), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (s.textBox) setSpan(android.text.style.BackgroundColorSpan(0x99000000.toInt()), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            // TextOverlay draws at 100 px; scale it to a share of the (cropped) frame height like the preview.
            val scale = ((if (s.textBig) 0.11f else 0.07f) * frameH / TextOverlay.TEXT_SIZE_PIXELS).coerceAtLeast(0.2f)
            val y = when (s.place) { 0 -> 0.88f; 1 -> 0f; else -> -0.88f }
            val settings = StaticOverlaySettings.Builder().setScale(scale, scale).setBackgroundFrameAnchor(0f, y).setOverlayFrameAnchor(0f, if (s.place == 1) 0f else if (s.place == 0) 1f else -1f).build()
            out += TextOverlay.createStaticTextOverlay(text, settings)
        }
        for (k in s.stickers) {
            val scale = ((if (k.big) STICKER_BIG else STICKER) * frameH / TextOverlay.TEXT_SIZE_PIXELS).coerceAtLeast(0.2f)
            val settings = StaticOverlaySettings.Builder().setScale(scale, scale)
                .setBackgroundFrameAnchor((k.x * 2 - 1).coerceIn(-1f, 1f), (1 - k.y * 2).coerceIn(-1f, 1f)).setOverlayFrameAnchor(0f, 0f).build()
            out += TextOverlay.createStaticTextOverlay(SpannableString(k.text), settings)
        }
        return out
    }
    /** Share of the frame height a sticker takes (the preview uses the same numbers). */
    const val STICKER = 0.10f
    const val STICKER_BIG = 0.17f
    /** Length of a transition at each side of a cut between two clips. */
    const val TRANSITION_MS = 400L

    /**
     * Fade or flash at the start and/or end of one clip. Times are counted from the clip's own first frame (the
     * pipeline adds the clip's place in the sequence to every timestamp), after any speed change.
     */
    private class Dip(private val durationUs: Long, private val fadeIn: Boolean, private val fadeOut: Boolean, private val white: Boolean) : RgbMatrix {
        private var first = C.TIME_UNSET
        private val span = minOf(TRANSITION_MS * 1000, durationUs / 2).coerceAtLeast(1)
        override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
            if (first == C.TIME_UNSET) first = presentationTimeUs
            val t = presentationTimeUs - first
            var k = 1f
            if (fadeIn && t < span) k = minOf(k, t.toFloat() / span)
            if (fadeOut && t > durationUs - span) k = minOf(k, ((durationUs - t).toFloat() / span).coerceAtLeast(0f))
            val o = if (white) 1 - k else 0f
            return floatArrayOf(k, 0f, 0f, 0f, 0f, k, 0f, 0f, 0f, 0f, k, 0f, o, o, o, 1f)
        }
    }
    /** Zoom in: a clip starts 12 % closer and settles to its normal size. */
    private class Punch(private val durationUs: Long) : MatrixTransformation {
        private var first = C.TIME_UNSET
        private val span = minOf(TRANSITION_MS * 1000, durationUs / 2).coerceAtLeast(1)
        override fun getMatrix(presentationTimeUs: Long): android.graphics.Matrix {
            if (first == C.TIME_UNSET) first = presentationTimeUs
            val f = ((presentationTimeUs - first).toFloat() / span).coerceIn(0f, 1f)
            val s = 1f + 0.12f * (1 - f) * (1 - f)
            return android.graphics.Matrix().apply { setScale(s, s) }
        }
    }

    fun composition(s: VideoEditSpec): Composition {
        val cut = s.cutFrom in (s.from + 1) until s.cutTo && s.cutTo < s.to
        val ranges = if (cut) listOf(s.from to s.cutFrom, s.cutTo to s.to) else listOf(s.from to s.to)
        val multi = s.extras.isNotEmpty()
        val effects = mutableListOf<Effect>()
        if (s.turns % 4 != 0 || s.mirror) effects += ScaleAndRotateTransformation.Builder().setScale(if (s.mirror) -1f else 1f, 1f).setRotationDegrees(360f - (s.turns % 4) * 90f).build()
        if (s.speed != 1f && !s.keepAudio) effects += SpeedChangeEffect(s.speed)
        // Fast speeds (up to 500×) would hand the encoder hundreds of frames per second; keep about 30 of them.
        if (s.speed > 1f) effects += FrameDropEffect.createDefaultFrameDropEffect(30f)
        // One clip: look and text belong to the clip. Several clips: they belong to the whole video (below), so they
        // run across the joins and every clip is brought to the first clip's frame.
        if (!multi) s.look?.let { effects += SingleColorLut.createFromCube(EditorKit.cube(it)) }
        if (s.crop > 0f) effects += Presentation.createForAspectRatio(s.crop, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        val texts = overlays(s)
        if (!multi && texts.isNotEmpty()) effects += OverlayEffect(texts)
        val sound = if (s.keepAudio && s.volume != 1f) listOf(gain(s.volume)) else emptyList()
        // Each clip with its length after the speed change, and which clip (0 = main) it comes from.
        data class Piece(val item: MediaItem, val clip: Int, val durationUs: Long, val speed: Float)
        val pieces = ranges.map { (a, b) -> Piece(clipped(s.source, a, b), 0, ((b - a) * 1000 / s.speed).toLong(), s.speed) } +
            s.extras.mapIndexed { i, p -> Piece(clipped(p.source, p.from, p.to), i + 1, (p.to - p.from) * 1000, 1f) }
        val items = pieces.mapIndexed { i, piece ->
            val own: MutableList<Effect> = if (piece.clip == 0) effects.toMutableList() else mutableListOf()
            if (s.transition != 0) {
                val joinIn = i > 0 && pieces[i - 1].clip != piece.clip; val joinOut = i < pieces.size - 1 && pieces[i + 1].clip != piece.clip
                if (s.transition == 3) { if (joinIn) own += Punch(piece.durationUs) }
                else if (joinIn || joinOut) own += Dip(piece.durationUs, joinIn, joinOut, s.transition == 2)
            }
            val edited = EditedMediaItem.Builder(piece.item).setEffects(Effects(sound, own)).setRemoveAudio(!s.keepAudio)
            // With sound, setSpeed changes picture and sound together. Without a sound track it stalls the export (no
            // output sample ever reaches the muxer), so a picture-only clip changes speed through a video effect instead.
            if (piece.speed != 1f && s.keepAudio) edited.setSpeed(object : SpeedProvider { override fun getSpeed(timeUs: Long) = piece.speed; override fun getNextSpeedChangeTimeUs(timeUs: Long) = C.TIME_UNSET })
            edited.build()
        }
        val main = if (s.keepAudio) EditedMediaItemSequence.withAudioAndVideoFrom(items) else EditedMediaItemSequence.withVideoFrom(items)
        val whole = mutableListOf<Effect>()
        if (multi) {
            val (w, h) = frameSize(s)
            whole += Presentation.createForWidthAndHeight(w / 2 * 2, h / 2 * 2, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
            s.look?.let { whole += SingleColorLut.createFromCube(EditorKit.cube(it)) }
            if (texts.isNotEmpty()) whole += OverlayEffect(texts)
        }
        val song = s.music
        val builder = if (song == null) Composition.Builder(main) else {
            val track = EditedMediaItem.Builder(MediaItem.fromUri(song)).setRemoveVideo(true).setEffects(Effects(if (s.musicVolume != 1f) listOf(gain(s.musicVolume)) else emptyList(), emptyList())).build()
            Composition.Builder(main, EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO)).addItem(track).setIsLooping(true).build())
        }
        if (whole.isNotEmpty()) builder.setEffects(Effects(emptyList(), whole))
        return builder.build()
    }
    private fun clipped(uri: Uri, from: Long, to: Long) = MediaItem.Builder().setUri(uri)
        .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(from).setEndPositionMs(to).build()).build()

    /**
     * Transformer with the clip editor's decoder choice: on emulators the Goldfish decoder can drop surface frames,
     * so Android's software decoder goes first and only one frame is in flight. Phones keep the normal decoders.
     */
    fun transformer(c: Context, listener: Transformer.Listener): Transformer {
        val emulator = Util.isRunningOnEmulator()
        val selector = MediaCodecSelector { mime, secure, tunnel ->
            val codecs = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunnel)
            if (emulator) codecs.sortedBy { when { it.name.contains("goldfish", true) -> 2; it.softwareOnly -> 0; else -> 1 } } else codecs
        }
        val platform = DefaultDecoderFactory.Builder(c).setMediaCodecSelector(selector).build()
        val decoder: Codec.DecoderFactory = if (!emulator) platform else object : Codec.DecoderFactory by platform {
            override fun createForVideoDecoding(format: Format, surface: Surface, toneMap: Boolean, session: android.media.metrics.LogSessionId?): Codec {
                val delegate = platform.createForVideoDecoding(format, surface, toneMap, session)
                return object : Codec by delegate { override fun getMaxPendingFrameCount() = 1 }
            }
        }
        return Transformer.Builder(c).setAssetLoaderFactory(DefaultAssetLoaderFactory(c, decoder, Clock.DEFAULT, null))
            .setMaxDelayBetweenMuxerSamplesMs(90_000).setVideoMimeType(MimeTypes.VIDEO_H264).setPortraitEncodingEnabled(true).addListener(listener).build()
    }
}
