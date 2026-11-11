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
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.SingleColorLut
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
    val look: ColorMatrix? = null, val music: Uri? = null, val keepAudio: Boolean = true)

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object VideoEdit {
    /** The edit as a media3 composition: clipped, sped up or slowed, look, crop and caption; music as a looping second sequence. */
    fun composition(s: VideoEditSpec): Composition {
        val item = MediaItem.Builder().setUri(s.source).setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(s.from).setEndPositionMs(s.to).build()).build()
        val effects = mutableListOf<Effect>()
        s.look?.let { effects += SingleColorLut.createFromCube(EditorKit.cube(it)) }
        if (s.crop > 0f) effects += Presentation.createForAspectRatio(s.crop, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        if (s.caption.isNotBlank()) {
            val text = SpannableString(s.caption).apply { setSpan(ForegroundColorSpan(s.textColor), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            // TextOverlay draws at 100 px; scale it to a share of the (cropped) frame height like the preview.
            val frameH = if (s.crop > 0f && s.crop > s.videoW.toFloat() / s.videoH) s.videoW / s.crop else s.videoH.toFloat()
            val scale = ((if (s.textBig) 0.11f else 0.07f) * frameH / TextOverlay.TEXT_SIZE_PIXELS).coerceAtLeast(0.2f)
            val y = when (s.place) { 0 -> 0.88f; 1 -> 0f; else -> -0.88f }
            val settings = StaticOverlaySettings.Builder().setScale(scale, scale).setBackgroundFrameAnchor(0f, y).setOverlayFrameAnchor(0f, if (s.place == 1) 0f else if (s.place == 0) 1f else -1f).build()
            effects += OverlayEffect(listOf<TextureOverlay>(TextOverlay.createStaticTextOverlay(text, settings)))
        }
        val edited = EditedMediaItem.Builder(item).setEffects(Effects(emptyList(), effects)).setRemoveAudio(!s.keepAudio)
        if (s.speed != 1f) edited.setSpeed(object : SpeedProvider { override fun getSpeed(timeUs: Long) = s.speed; override fun getNextSpeedChangeTimeUs(timeUs: Long) = C.TIME_UNSET })
        val main = if (s.keepAudio) EditedMediaItemSequence.withAudioAndVideoFrom(listOf(edited.build())) else EditedMediaItemSequence.withVideoFrom(listOf(edited.build()))
        val song = s.music ?: return Composition.Builder(main).build()
        val track = EditedMediaItem.Builder(MediaItem.fromUri(song)).setRemoveVideo(true).build()
        val audio = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO)).addItem(track).setIsLooping(true).build()
        return Composition.Builder(main, audio).build()
    }

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
