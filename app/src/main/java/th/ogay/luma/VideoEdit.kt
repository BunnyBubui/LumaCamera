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
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.VideoEncoderSettings
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
    /** Between two different clips: 0 cut, 1 fade through black, 2 flash of white, 3 zoom in, 4 slide, 5 wipe, 6 blur. */
    val transition: Int = 0,
    /** Emoji stickers over the whole video. */
    val stickers: List<VideoSticker> = emptyList(),
    /** Video, picture and sound layers on top, timed in ms of the finished video. */
    val layers: List<VideoLayer> = emptyList(),
    /** Smaller output: short side in pixels (0 = as edited) and a lower frame rate (0 = as recorded). */
    val outShort: Int = 0, val outFps: Float = 0f,
    /** How many of [extras] play before the main clip (0 = the main clip is first). */
    val mainAt: Int = 0,
    /** Finishing effects over the whole picture, each 0..1. */
    val vignette: Float = 0f, val grain: Float = 0f, val sharpen: Float = 0f,
    /** The whole colour (filter, adjustments and colour studio) as one [r][g][b] table; when set it replaces [look]. */
    val grade: Array<Array<IntArray>>? = null,
    /** Shaping of the clips' own sound (equaliser, low cut, voice), silence for the first clip, and ducking of the music under sound layers. */
    val audio: StudioAudio.Settings = StudioAudio.Settings(), val mainMuted: Boolean = false, val duck: Boolean = false,
    /** Speed curve of the main clip (5 points, see [StudioMath.curveMul]); null = steady [speed]. */
    val mainCurve: FloatArray? = null,
    /** Only the sound: no picture track, no picture effects. */
    val audioOnly: Boolean = false,
    /** Sound only: sample rate in Hz (0 = as recorded) and a mono mix. */
    val audioHz: Int = 0, val audioMono: Boolean = false,
    /** Slow camera move over the first clip ([ClipMotion]). */
    val mainMotion: Int = 0,
    /** The same as [VideoPart.join] for the main clip when it is not the first. */
    val mainJoin: Int = -1, val mainJoinMs: Long = 0L,
    /** Length in ms of the main clip's whole file (0 = unknown); an overlapping transition borrows picture from beyond the clip, and needs to know there is some. */
    val mainSourceMs: Long = 0L,
    /** Where the first clip sits in the frame: shares of the frame's width and height (right and down are positive); its size does not change. */
    val mainOffX: Float = 0f, val mainOffY: Float = 0f)

/** A clip appended after the main one. [from]/[to] in ms of that clip. */
data class VideoPart(val source: Uri, val from: Long, val to: Long, val speed: Float = 1f, val muted: Boolean = false, val volume: Float = 1f, val curve: FloatArray? = null, val motion: Int = 0,
    /** The change into this clip from the one before: -1 follows [VideoSpec.transition], otherwise 0..6 as there; [joinMs] its length (0 = the usual). */
    val join: Int = -1, val joinMs: Long = 0L, val sourceMs: Long = 0L, val mirror: Boolean = false,
    /** Place in the frame, as [VideoEditSpec.mainOffX] and [VideoEditSpec.mainOffY]. */
    val offX: Float = 0f, val offY: Float = 0f)

/** An emoji on the picture: [x], [y] are 0..1 across the visible (cropped) frame, [big] doubles its size. */
data class VideoSticker(val text: String, val x: Float, val y: Float, val big: Boolean = false)

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object VideoEdit {
    /** Sound louder or quieter for mono and stereo tracks. */
    private fun gain(v: Float): AudioProcessor = ChannelMixingAudioProcessor().apply { for (n in 1..2) putChannelMixingMatrix(ChannelMixingMatrix.create(n, n).scaleBy(v)) }
    /** Picture height after the crop (the frame, not turned: videoW and videoH are the clip's own sides), the frame the caption and stickers are measured against. */
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
    /** Whole length of a transition across a cut between two clips (it is split between the end of the first clip and the start of the next, see [joinAlign]). */
    const val TRANSITION_MS = 600L
    // A seam's length and place are kept together in one number: length in ms below JOIN_STEP, place above it.
    private const val JOIN_STEP = 1_000_000L
    /** Whole length in ms of the transition stored as [packed] (0 = the usual). */
    fun joinLength(packed: Long): Long = (packed % JOIN_STEP).takeIf { it > 0 } ?: TRANSITION_MS
    /** Where it sits: 0 across the cut (half on each clip), 1 ending at the cut (all on the end of the first clip), 2 starting at the cut (all on the start of the next clip). */
    fun joinAlign(packed: Long): Int = (packed / JOIN_STEP).toInt().coerceIn(0, 2)
    fun joinPack(ms: Long, align: Int): Long = ms % JOIN_STEP + align.coerceIn(0, 2) * JOIN_STEP
    /** Part of the transition drawn on the end of the first clip. */
    fun joinOutMs(packed: Long): Long = when (joinAlign(packed)) { 0 -> joinLength(packed) / 2; 1 -> joinLength(packed); else -> 0L }
    /** Part of the transition drawn on the start of the next clip. */
    fun joinInMs(packed: Long): Long = when (joinAlign(packed)) { 0 -> joinLength(packed) / 2; 2 -> joinLength(packed); else -> 0L }

    /**
     * Fade or flash at the start and/or end of one clip. Times are counted from the clip's own first frame (the
     * pipeline adds the clip's place in the sequence to every timestamp), after any speed change.
     */
    private class Dip(private val durationUs: Long, inMs: Long, outMs: Long, private val white: Boolean) : RgbMatrix {
        private var first = C.TIME_UNSET
        private val spanIn = minOf(inMs * 1000, durationUs / 2); private val spanOut = minOf(outMs * 1000, durationUs / 2)
        override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
            if (first == C.TIME_UNSET) first = presentationTimeUs
            val t = presentationTimeUs - first
            var k = 1f
            if (spanIn > 0 && t < spanIn) k = minOf(k, t.toFloat() / spanIn)
            if (spanOut > 0 && t > durationUs - spanOut) k = minOf(k, ((durationUs - t).toFloat() / spanOut).coerceAtLeast(0f))
            val o = if (white) 1 - k else 0f
            return floatArrayOf(k, 0f, 0f, 0f, 0f, k, 0f, 0f, 0f, 0f, k, 0f, o, o, o, 1f)
        }
    }
    /** Kind number of the overlapping change in [VideoEditSpec.transition] and [VideoPart.join]. */
    const val OVERLAP = 7
    private class Cover(val from: Long, val to: Long, val onTop: Boolean)
    private class Lap(val from: Long, val to: Long, val incomingOnTop: Boolean)
    /** Draws the first video track over the second, fading it in and out where a clip arrives or leaves over the other. */
    private class Dissolve(private val w: Int, private val h: Int, private val covers: List<Cover>, private val laps: List<Lap>) : androidx.media3.common.VideoCompositorSettings {
        override fun getOutputSize(inputSizes: List<androidx.media3.common.util.Size>) = androidx.media3.common.util.Size(w, h)
        override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): androidx.media3.common.OverlaySettings {
            if (inputId != 0) return StaticOverlaySettings.Builder().build()
            val t = presentationTimeUs
            val top = covers.any { it.onTop && t >= it.from && t <= it.to }; val bottom = covers.any { !it.onTop && t >= it.from && t <= it.to }
            val alpha = when {
                !top -> 0f
                !bottom -> 1f
                else -> laps.firstOrNull { t >= it.from && t <= it.to }?.let { l -> val k = ((t - l.from).toFloat() / (l.to - l.from).coerceAtLeast(1)).coerceIn(0f, 1f); if (l.incomingOnTop) k else 1 - k } ?: 1f
            }
            return StaticOverlaySettings.Builder().setAlphaScale(alpha).build()
        }
    }
    /** Zoom in: a clip starts 12 % closer and settles to its normal size. */
    private class Punch(private val durationUs: Long, spanMs: Long) : MatrixTransformation {
        private var first = C.TIME_UNSET
        private val span = minOf(spanMs * 1000, durationUs / 2).coerceAtLeast(1)
        override fun getMatrix(presentationTimeUs: Long): android.graphics.Matrix {
            if (first == C.TIME_UNSET) first = presentationTimeUs
            val f = ((presentationTimeUs - first).toFloat() / span).coerceIn(0f, 1f)
            val s = 1f + 0.12f * (1 - f) * (1 - f)
            return android.graphics.Matrix().apply { setScale(s, s) }
        }
    }

    /** A slow camera move over a whole clip. [offsetUs] is where this piece starts inside its clip; [totalUs] the clip's length. */
    /** Moves the picture inside the frame by [x] and [y] (normalised device units: the frame is 2 wide and 2 high); the edges it leaves show black. */
    private class Shift(private val x: Float, private val y: Float) : MatrixTransformation {
        override fun getMatrix(presentationTimeUs: Long): android.graphics.Matrix = android.graphics.Matrix().apply { setTranslate(x, y) }
    }
    private class Drift(private val motion: Int, private val offsetUs: Long, private val totalUs: Long) : MatrixTransformation {
        private var first = C.TIME_UNSET
        override fun getMatrix(presentationTimeUs: Long): android.graphics.Matrix {
            if (first == C.TIME_UNSET) first = presentationTimeUs
            val (sc, x) = ClipMotion.at(motion, (presentationTimeUs - first + offsetUs).toFloat() / totalUs.coerceAtLeast(1))
            return android.graphics.Matrix().apply { setScale(sc, sc); postTranslate(x, 0f) }
        }
    }

    /** [context] is needed only when the spec has picture or video layers (to decode them). */
    fun composition(s: VideoEditSpec, context: Context? = null): Composition {
        val cut = s.cutFrom in (s.from + 1) until s.cutTo && s.cutTo < s.to
        val ranges = if (cut) listOf(s.from to s.cutFrom, s.cutTo to s.to) else listOf(s.from to s.to)
        val multi = s.extras.isNotEmpty()
        val effects = mutableListOf<Effect>()
        if (s.turns % 4 != 0 || s.mirror) effects += ScaleAndRotateTransformation.Builder().setScale(if (s.mirror) -1f else 1f, 1f).setRotationDegrees(360f - (s.turns % 4) * 90f).build()
        // A quarter turn turns only the picture: the frame keeps the shape the clip came in (or the chosen crop shape) and the
        // turned picture sits inside it with black bars, so the file does not change size until the frame is changed.
        val sideways = s.turns % 2 == 1
        // One clip: look and text belong to the clip. Several clips: they belong to the whole video (below), so they
        // run across the joins and every clip is brought to the first clip's frame.
        val table = s.grade ?: s.look?.let { EditorKit.cube(it) }
        if (!multi) table?.let { effects += SingleColorLut.createFromCube(it) }
        if (sideways) effects += Presentation.createForAspectRatio(if (s.crop > 0f) s.crop else s.videoW.toFloat() / s.videoH, Presentation.LAYOUT_SCALE_TO_FIT)
        else if (s.crop > 0f) effects += Presentation.createForAspectRatio(s.crop, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        val finishing = s.vignette > 0f || s.grain > 0f || s.sharpen > 0f
        if (!multi && finishing) effects += StudioFx(s.vignette, s.grain, s.sharpen)
        val texts = overlays(s)
        if (!multi && texts.isNotEmpty()) effects += OverlayEffect(texts)
        val sound = if (s.keepAudio && s.volume != 1f) listOf(gain(s.volume)) else emptyList()
        // Each clip with its length after the speed change, and which clip (0 = main) it comes from.
        data class Piece(val item: MediaItem, val clip: Int, val durationUs: Long, val speed: Float, val uri: Uri, val a: Long, val b: Long)
        // A clip with a speed curve becomes several pieces of steady speed that follow each other.
        fun pieces(uri: Uri, a: Long, b: Long, base: Float, curve: FloatArray?, clip: Int): List<Piece> {
            if (curve == null) return listOf(Piece(clipped(uri, a, b), clip, ((b - a) * 1000 / base).toLong(), base, uri, a, b))
            val sp = StudioMath.partSpeeds(base, curve); val n = StudioMath.CURVE_PARTS
            return (0 until n).map { k -> val x = a + (b - a) * k / n; val y = a + (b - a) * (k + 1) / n; Piece(clipped(uri, x, y), clip, ((y - x) * 1000 / sp[k]).toLong().coerceAtLeast(1), sp[k], uri, x, y) }
        }
        val mainPieces = ranges.flatMap { (a, b) -> pieces(s.source, a, b, s.speed, if (ranges.size == 1) s.mainCurve else null, 0) }
        val extraPieces = s.extras.flatMapIndexed { i, p -> pieces(p.source, p.from, p.to, p.speed, p.curve, i + 1) }
        val lead = s.mainAt.coerceIn(0, extraPieces.size)
        val pieces = extraPieces.take(lead) + mainPieces + extraPieces.drop(lead)
        // ---- Overlapping change (kind [OVERLAP]): both clips show at once, so the second clip is opened before its start and the first runs on past its end ----
        fun kindAt(clip: Int) = (if (clip == 0) s.mainJoin else s.extras[clip - 1].join).let { if (it < 0) s.transition else it }
        fun packedAt(clip: Int) = if (clip == 0) s.mainJoinMs else s.extras[clip - 1].joinMs
        fun sourceMsOf(clip: Int) = if (clip == 0) s.mainSourceMs else s.extras[clip - 1].sourceMs
        val n = pieces.size
        val pre = LongArray(n); val post = LongArray(n); val lap = BooleanArray(n)   // ms borrowed before the piece, after it; whether the change into it overlaps
        if (!s.audioOnly) for (j in 1 until n) {
            val p0 = pieces[j - 1]; val p1 = pieces[j]
            if (p0.clip == p1.clip || kindAt(p1.clip) != OVERLAP || p0.speed != 1f || p1.speed != 1f) continue
            val packed = packedAt(p1.clip)
            val before = minOf(joinOutMs(packed), p0.durationUs / 2000, p1.a)                                    // from the second clip, before its start
            val after = minOf(joinInMs(packed), p1.durationUs / 2000, (sourceMsOf(p0.clip) - p0.b).coerceAtLeast(0))  // from the first clip, after its end
            if (before + after < 50) continue
            lap[j] = true; pre[j] = before; post[j - 1] = after
        }
        val overlapOn = lap.any { it }
        val cutAt = LongArray(n); run { var acc = 0L; for (j in 0 until n) { cutAt[j] = acc; acc += pieces[j].durationUs } }
        val items = pieces.mapIndexed { i, piece ->
            val own: MutableList<Effect> = if (piece.clip == 0) effects.toMutableList() else mutableListOf()
            if (piece.clip > 0 && s.extras[piece.clip - 1].mirror) own += ScaleAndRotateTransformation.Builder().setScale(-1f, 1f).build()
            val motion = if (piece.clip == 0) s.mainMotion else s.extras[piece.clip - 1].motion
            if (motion != ClipMotion.NONE) {
                // Pieces of one clip follow each other; the move runs across all of them, not once per piece.
                val run = pieces.indices.filter { pieces[it].clip == piece.clip }
                val offset = run.takeWhile { it < i }.sumOf { pieces[it].durationUs }
                own += Drift(motion, offset, run.sumOf { pieces[it].durationUs })
            }
            // Place in the frame. A later clip is first brought to the frame's shape, so the share means the same as on the first clip.
            val offX = if (piece.clip == 0) s.mainOffX else s.extras[piece.clip - 1].offX; val offY = if (piece.clip == 0) s.mainOffY else s.extras[piece.clip - 1].offY
            if (offX != 0f || offY != 0f) {
                if (piece.clip > 0) { val (fw, fh) = frameSize(s); own += Presentation.createForWidthAndHeight(fw / 2 * 2, fh / 2 * 2, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP) }
                own += Shift(offX * 2f, -offY * 2f)
            }
            // Fast speeds (up to thousands ×) would hand the encoder hundreds of frames per second; keep about 30 of them.
            if (piece.speed != 1f) { if (!s.keepAudio) own += SpeedChangeEffect(piece.speed); if (piece.speed > 1f) own += FrameDropEffect.createDefaultFrameDropEffect(30f) }
            // Every clip owns the change into it; the clip before gets the same change as its way out. The change is split by its place:
            // across the cut it takes half of each clip, ending at the cut it all sits on the end of the first clip, starting at the cut on the start of the next.
            fun kindOf(clip: Int) = (if (clip == 0) s.mainJoin else s.extras[clip - 1].join).let { if (it < 0) s.transition else it }
            fun packedOf(clip: Int) = if (clip == 0) s.mainJoinMs else s.extras[clip - 1].joinMs
            val joinIn = i > 0 && pieces[i - 1].clip != piece.clip && !lap[i]; val joinOut = i < pieces.size - 1 && pieces[i + 1].clip != piece.clip && !(i + 1 < n && lap[i + 1])
            val pieceUs = piece.durationUs + (pre[i] + post[i]) * 1000
            val kIn = if (joinIn) kindOf(piece.clip) else 0; val msIn = if (joinIn) joinInMs(packedOf(piece.clip)) else 0L
            val kOut = if (joinOut) kindOf(pieces[i + 1].clip) else 0; val msOut = if (joinOut) joinOutMs(packedOf(pieces[i + 1].clip)) else 0L
            fun draw(kind: Int, inMs: Long, outMs: Long) {
                if (kind == 0 || kind == OVERLAP || (inMs <= 0 && outMs <= 0)) return
                when (kind) {
                    3 -> if (inMs > 0) own += Punch(pieceUs, inMs)
                    4 -> own += StudioSlide(pieceUs, inMs, outMs)
                    5 -> own += StudioTransition(StudioTransition.WIPE, pieceUs, inMs, outMs)
                    6 -> own += StudioTransition(StudioTransition.BLUR, pieceUs, inMs, outMs)
                    else -> own += Dip(pieceUs, inMs, outMs, kind == 2)
                }
            }
            if (kIn == kOut) draw(kIn, msIn, msOut) else { draw(kIn, msIn, 0); draw(kOut, 0, msOut) }
            if (overlapOn) { val (fw, fh) = frameSize(s); own += Presentation.createForWidthAndHeight(fw / 2 * 2, fh / 2 * 2, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP) }
            val muted = if (piece.clip == 0) s.mainMuted else s.extras[piece.clip - 1].muted
            val partVolume = if (piece.clip == 0) 1f else s.extras[piece.clip - 1].volume
            val own_sound = if (muted) listOf(gain(0f)) else (if (piece.clip == 0) sound else if (partVolume != 1f) listOf(gain(partVolume)) else emptyList()) + listOfNotNull(if (s.audio.active) StudioAudio.Shape(s.audio) else null)
            val edited = EditedMediaItem.Builder(if (pre[i] + post[i] > 0) clipped(piece.uri, piece.a - pre[i], piece.b + post[i]) else piece.item).setEffects(Effects(own_sound, if (s.audioOnly) emptyList() else own)).setRemoveAudio(!s.keepAudio).setRemoveVideo(s.audioOnly)
            // With sound, setSpeed changes picture and sound together. Without a sound track it stalls the export (no
            // output sample ever reaches the muxer), so a picture-only clip changes speed through a video effect instead.
            if (piece.speed != 1f && s.keepAudio) edited.setSpeed(object : SpeedProvider { override fun getSpeed(timeUs: Long) = piece.speed; override fun getNextSpeedChangeTimeUs(timeUs: Long) = C.TIME_UNSET })
            edited.build()
        }
        val main = if (s.audioOnly) EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO)).apply { items.forEach { addItem(it) } }.build()
            else if (s.keepAudio) EditedMediaItemSequence.withAudioAndVideoFrom(items) else EditedMediaItemSequence.withVideoFrom(items)
        // Overlapping changes: even pieces on one video track, odd pieces on another, each with gaps where it is not playing; the first track
        // is drawn over the second, and its opacity makes the dissolve.
        val bothTracks = if (!overlapOn) emptyList() else (0..1).map { tr ->
            val b = EditedMediaItemSequence.Builder(if (s.keepAudio) setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO) else setOf(C.TRACK_TYPE_VIDEO))
            var cursor = 0L
            for (j in 0 until n) if (j % 2 == tr) {
                val start = cutAt[j] - pre[j] * 1000; val gap = start - cursor
                if (gap > 1000) b.addGap(gap)
                b.addItem(items[j]); cursor = start + pieces[j].durationUs + (pre[j] + post[j]) * 1000
            }
            // Both tracks run to the end of the video: when the first track stopped early, the export stopped drawing with it
            // (the picture froze while the sound went on), because the compositor follows the first track's frames.
            val whole = cutAt[n - 1] + pieces[n - 1].durationUs + post[n - 1] * 1000
            if (whole - cursor > 1000) b.addGap(whole - cursor)
            b.build()
        }
        val whole = mutableListOf<Effect>()
        // Layers are measured on the finished frame, so they go last, over everything else.
        val layerOverlays = if (!s.audioOnly && s.layers.any { it.visual }) VideoLayers.overlays(requireNotNull(context) { "layers need a context" }, s.layers) else emptyList()
        if (multi) {
            val (w, h) = frameSize(s)
            whole += Presentation.createForWidthAndHeight(w / 2 * 2, h / 2 * 2, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
            table?.let { whole += SingleColorLut.createFromCube(it) }
            if (finishing) whole += StudioFx(s.vignette, s.grain, s.sharpen)
            if (texts.isNotEmpty()) whole += OverlayEffect(texts)
        }
        if (layerOverlays.isNotEmpty()) whole += OverlayEffect(layerOverlays)
        if (s.outShort > 0) whole += Presentation.createForShortSide(s.outShort)
        if (s.outFps > 0f) whole += FrameDropEffect.createDefaultFrameDropEffect(s.outFps)
        val song = s.music
        // Sound only and the clips have no sound to keep: the file is made from the music and sound layers alone.
        val sequences = if (s.audioOnly && !s.keepAudio) mutableListOf() else if (overlapOn) bothTracks.toMutableList() else mutableListOf(main)
        if (song != null) {
            val spans = if (s.duck) s.layers.filter { it.audible && it.volume > 0f }.map { it.at to it.end } else emptyList()
            val track = EditedMediaItem.Builder(MediaItem.fromUri(song)).setRemoveVideo(true).setEffects(Effects(listOfNotNull(if (s.musicVolume != 1f) gain(s.musicVolume) else null, if (spans.isNotEmpty()) StudioAudio.Duck(spans) else null), emptyList())).build()
            sequences += EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO)).addItem(track).setIsLooping(true).build()
        }
        if (s.layers.any { it.audible }) sequences += VideoLayers.sounds(requireNotNull(context) { "layers need a context" }, s.layers, s.duck) { gain(it) }
        val builder = Composition.Builder(sequences)
        if (overlapOn) { val (fw, fh) = frameSize(s); builder.setVideoCompositorSettings(Dissolve(fw / 2 * 2, fh / 2 * 2, (0 until n).map { j -> Cover(cutAt[j] - pre[j] * 1000, cutAt[j] + pieces[j].durationUs + post[j] * 1000, j % 2 == 0) },
            (1 until n).filter { lap[it] }.map { j -> Lap(cutAt[j] - pre[j] * 1000, cutAt[j] + post[j - 1] * 1000, j % 2 == 0) })) }
        if (whole.isNotEmpty() && !s.audioOnly) builder.setEffects(Effects(emptyList(), whole))
        if (s.audioOnly && (s.audioHz > 0 || s.audioMono)) {
            // The whole mix is shaped last: first to one channel, then to the chosen sample rate.
            val end = ArrayList<AudioProcessor>()
            if (s.audioMono) end += ChannelMixingAudioProcessor().apply { for (n in 1..2) putChannelMixingMatrix(ChannelMixingMatrix.create(n, 1)) }
            if (s.audioHz > 0) end += androidx.media3.common.audio.SonicAudioProcessor().apply { setOutputSampleRateHz(s.audioHz) }
            builder.setEffects(Effects(end, emptyList()))
        }
        return builder.build()
    }
    private fun clipped(uri: Uri, from: Long, to: Long) = MediaItem.Builder().setUri(uri)
        .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(from).setEndPositionMs(to).build()).build()

    /**
     * Transformer with the clip editor's decoder choice: on emulators the Goldfish decoder can drop surface frames,
     * so Android's software decoder goes first and only one frame is in flight. Phones keep the normal decoders.
     */
    /** [looper] runs the export and its callbacks on that thread (the export job); default is the caller's. */
    fun transformer(c: Context, listener: Transformer.Listener, looper: android.os.Looper? = null, audioKbps: Int = 0): Transformer {
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
            .setMaxDelayBetweenMuxerSamplesMs(90_000).setVideoMimeType(if (c.getSharedPreferences("camera", Context.MODE_PRIVATE).getBoolean("export_hevc", false)) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264).setPortraitEncodingEnabled(true).addListener(listener)
            .apply { if (looper != null) setLooper(looper)
                // A fixed bitrate chosen in the export sheet (0 = let the encoder decide).
                val mbps = c.getSharedPreferences("camera", Context.MODE_PRIVATE).getInt("export_mbps", 0)
                // Sound only: the AAC bitrate chosen in the sheet.
                if (audioKbps > 0) setEncoderFactory(DefaultEncoderFactory.Builder(c).setRequestedAudioEncoderSettings(androidx.media3.transformer.AudioEncoderSettings.Builder().setBitrate(audioKbps * 1000).build()).build())
                else if (mbps > 0) setEncoderFactory(DefaultEncoderFactory.Builder(c).setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(mbps * 1_000_000).build()).build()) }.build()
    }
}
