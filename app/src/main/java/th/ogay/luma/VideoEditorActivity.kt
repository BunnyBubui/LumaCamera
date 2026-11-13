package th.ogay.luma

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.SingleColorLut
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.effect.TextureOverlay
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Video editor: trim, split, speed, crop, text, stickers, film look, music, and further clips joined with a transition.
 * The preview plays the clips in turn with the look, crop frame, text and stickers drawn over them; "ส่งออก" writes
 * new clips (two when split, one when several clips are joined) with media3 Transformer and the originals stay untouched.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class VideoEditorActivity : ComponentActivity() {
    companion object {
        fun intent(c: Context, uri: Uri): Intent = Intent(c, VideoEditorActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        /** 0.1× to 500×, closer together near 1× where small changes matter. */
        private val SPEEDS = listOf(0.1f, 0.15f, 0.2f, 0.25f, 0.3f, 0.4f, 0.5f, 0.6f, 0.75f, 0.9f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f, 5f, 6f, 8f,
            10f, 12f, 15f, 20f, 25f, 30f, 40f, 50f, 60f, 75f, 100f, 125f, 150f, 200f, 250f, 300f, 400f, 500f)
        /** Sound follows the picture only in this range; outside it the sound is left out. */
        private const val SOUND_MIN = 0.25f; private const val SOUND_MAX = 4f
        private val CROPS = listOf("ต้นฉบับ" to 0f, "1:1" to 1f, "4:5" to 4f / 5, "16:9" to 16f / 9, "9:16" to 9f / 16, "2.39:1" to 2.39f)
        private val PLACES = listOf("บน", "กลาง", "ล่าง")
        private val TEXT_COLORS = intArrayOf(Color.WHITE, Color.BLACK, Glass.ACCENT, 0xffffd60a.toInt(), 0xff0a84ff.toInt())
        private val TRANSITIONS = listOf("ตัดตรง", "จางดำ", "แฟลชขาว", "ซูมเข้า", "เลื่อน", "ปาด", "เบลอ")
        private val STICKERS = listOf("😀", "😂", "😍", "🥳", "😎", "🔥", "❤️", "✨", "👍", "🎉", "⭐", "🌸", "☀️", "🌙", "📍", "💯")
        private const val CLIPS = "M3 6h7v12H3z M14 6h7v12h-7z"
        private const val JOIN = "M3 5h6v14H3z M15 5h6v14h-6z M10 12h4 M12.5 10l1.5 2-1.5 2"
        private const val STICKER = "M12 3a9 9 0 1 0 0.01 0 M8.5 14q3.5 3 7 0 M9 9.5v1 M15 9.5v1"
        private const val ADD = "M12 5v14 M5 12h14"
        private const val LAYERS = "M12 3l9 5-9 5-9-5z M3 12.5l9 5 9-5 M3 17l9 5 9-5"
        private const val LAYER_TOOL = 13
        private const val COLOR_TOOL = 14
        private val EASES = listOf("นุ่ม", "คงที่", "ค่อย ๆ เร่ง", "ค่อย ๆ หยุด", "หยุดนิ่ง")
        private const val GREEN_KEY = 0xff00b140.toInt()
        private const val BLUE_KEY = 0xff0047bb.toInt()
        /** Looks each picture colour up in a [n]³ colour table laid out as n slices side by side (x = blue·n + red, y = green), blending the 8 nearest entries. */
        private const val LUT_AGSL = """uniform shader content; uniform shader lut; uniform float n;
            half3 at(float r, float g, float b) { return lut.eval(float2(b * n + r + 0.5, g + 0.5)).rgb; }
            half4 main(float2 p) {
                half4 c = content.eval(p); float a = max(float(c.a), 0.0001);
                float3 v = clamp(float3(c.rgb) / a, 0.0, 1.0) * (n - 1.0); float3 i = floor(v); float3 f = v - i; float3 j = min(i + 1.0, n - 1.0);
                half3 c00 = mix(at(i.r, i.g, i.b), at(j.r, i.g, i.b), f.r); half3 c10 = mix(at(i.r, j.g, i.b), at(j.r, j.g, i.b), f.r);
                half3 c01 = mix(at(i.r, i.g, j.b), at(j.r, i.g, j.b), f.r); half3 c11 = mix(at(i.r, j.g, j.b), at(j.r, j.g, j.b), f.r);
                half3 o = mix(mix(c00, c10, f.g), mix(c01, c11, f.g), f.b);
                return half4(o * half(a), c.a);
            }"""
        /** Width in pixels the words of a text layer are drawn at for the preview (the export draws them again at full size). */
        private const val TEXT_PREVIEW = 720
        private const val SRT_LIMIT = 60
        private const val SMOOTH = "M3 17c3 0 4-10 9-10s6 10 9 10"
        private const val LEFT = "M15 6l-6 6 6 6"
        private const val RIGHT = "M9 6l6 6-6 6"
    }

    /** A clip added after the main one; it plays whole and takes the main clip's frame shape. */
    /** One added clip on the sequence: the file, and the part of it [from, to) that plays. Two parts may share one file (after a split or a copy). */
    private class Extra(val uri: Uri, val duration: Long, val w: Int, val h: Int, val name: String, var thumb: Bitmap?) {
        var from = 0L; var to = duration
        /** Playback speed of this part (1 = as recorded); [len] is the time it takes in the finished video. */
        var speed = 1f
        /** True when this part plays without its own sound (for example after its sound was moved to a layer). */
        var muted = false
        val len get() = ((to - from) / speed).toLong()
        fun copy() = Extra(uri, duration, w, h, name, thumb).also { it.from = from; it.to = to; it.speed = speed; it.muted = muted }
    }
    private var mainFrames: List<Bitmap> = emptyList()
    private val clipFrames = HashMap<String, List<Bitmap>>()
    private fun buildExtras(uris: List<String>, ranges: List<String>?, get: (String) -> Extra?): List<Extra> = uris.mapIndexedNotNull { i, u ->
        get(u)?.copy()?.also { e -> ranges?.getOrNull(i)?.split(",")?.takeIf { it.size >= 2 }?.let { r ->
            e.from = (r[0].toLongOrNull() ?: 0L).coerceIn(0, e.duration); e.to = (r[1].toLongOrNull() ?: e.duration).coerceIn(e.from, e.duration); e.speed = (r.getOrNull(2)?.toFloatOrNull() ?: 1f).coerceIn(0.1f, 8f); e.muted = r.getOrNull(3) == "m" } } }
    // ---- Playing order ----
    /** How many added clips play before the clip the editor was opened with. 0 = that clip is first, as before. */
    private var mainAt = 0
    private fun mainPos() = mainAt.coerceIn(0, extras.size)
    /** Clip numbers in playing order: 0 is the clip the editor was opened with, k ≥ 1 is added clip k. */
    private fun order(): List<Int> = StudioMath.order(mainAt, extras.size)
    private fun nextOf(k: Int): Int { val o = order(); return o[(o.indexOf(k) + 1).mod(o.size)] }
    private fun lenOf(k: Int) = if (k == 0) mainLen() else extras.getOrNull(k - 1)?.len ?: 0L
    /** Markers: moments of the finished video the user flagged; shown on the ruler, and edges snap to them. */
    private val markers = mutableListOf<Long>()
    /** Finishing effects, each 0..1: dark edges, film grain, sharpening. */
    private val fx = FloatArray(3)
    private var fxView: FxView? = null
    private var comparing = false
    /** Shows the dark edges and the grain over the preview, inside the part of the picture that is kept. */
    private class FxView(c: Context) : View(c) {
        private val area = RectF(); private var vig = 0f; private var grain = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG); private val m = android.graphics.Matrix()
        private val edge = android.graphics.RadialGradient(0f, 0f, 1f, intArrayOf(0, 0, Color.BLACK), floatArrayOf(0f, 0.41f, 1f), android.graphics.Shader.TileMode.CLAMP)
        private val noise: android.graphics.BitmapShader by lazy { val n = 128; val px = IntArray(n * n); val r = java.util.Random(11)
            for (i in px.indices) { val v = 128 + (r.nextGaussian() * 60).toInt().coerceIn(-127, 127); px[i] = Color.rgb(v, v, v) }
            android.graphics.BitmapShader(Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888), android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT) }
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        fun sync(r: RectF, v: Float, g: Float) { if (r != area || v != vig || g != grain) { area.set(r); vig = v; grain = g; invalidate() } }
        override fun onDraw(c: Canvas) {
            if (area.isEmpty || (vig <= 0f && grain <= 0f)) return
            c.save(); c.clipRect(area)
            if (vig > 0f) { m.setScale(area.width() * 0.85f, area.height() * 0.85f); m.postTranslate(area.centerX(), area.centerY()); edge.setLocalMatrix(m)
                paint.shader = edge; paint.alpha = (vig * 0.85f * 255).toInt().coerceIn(0, 255); c.drawRect(area, paint) }
            if (grain > 0f) { paint.shader = noise; paint.alpha = (grain * 70).toInt().coerceIn(0, 255); c.drawRect(area, paint) }
            paint.shader = null; c.restore()
        }
    }
    // ---- Voice recording ----
    private var recorder: android.media.MediaRecorder? = null
    private var recFile: java.io.File? = null
    private var recAt = 0L
    /** Starts recording the microphone while the video plays; a second tap stops and puts the recording on the timeline where it began. */
    private fun toggleRecord() {
        val r = recorder
        if (r != null) {
            val ok = runCatching { r.stop() }.isSuccess; runCatching { r.release() }; recorder = null
            if (player?.isPlaying == true) togglePlay(); applySound()
            val f = recFile; recFile = null
            if (ok && f != null && f.length() > 0) addLayers(listOf(Uri.fromFile(f)), VideoLayer.SOUND, recAt) else { notice("อัดเสียงไม่สำเร็จ"); showTool(LAYER_TOOL, false) }
            return
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 71); notice("อนุญาตให้ใช้ไมโครโฟนก่อน แล้วแตะอัดเสียงอีกครั้ง"); return }
        val f = java.io.File(java.io.File(filesDir, "studio").apply { mkdirs() }, "voice_${System.currentTimeMillis()}.m4a")
        val rec = if (android.os.Build.VERSION.SDK_INT >= 31) android.media.MediaRecorder(this) else @Suppress("DEPRECATION") android.media.MediaRecorder()
        val started = runCatching { rec.setAudioSource(android.media.MediaRecorder.AudioSource.MIC); rec.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC); rec.setAudioSamplingRate(48000); rec.setAudioEncodingBitRate(128_000); rec.setOutputFile(f.path); rec.prepare(); rec.start() }.isSuccess
        if (!started) { runCatching { rec.release() }; notice("เปิดไมโครโฟนไม่ได้"); return }
        recorder = rec; recFile = f; recAt = outNow()
        // The video plays silently while recording, so its own sound does not leak into the microphone.
        if (player?.isPlaying != true) togglePlay()
        player?.runCatching { setVolume(0f, 0f) }; musicPlayer?.runCatching { setVolume(0f, 0f) }
        notice("กำลังอัดเสียง · แตะ \"หยุดอัด\" เมื่อพูดจบ"); showTool(LAYER_TOOL, false)
    }
    /** The film strip under the preview shows the clip that is on screen: the first clip, or the added clip that was picked. */
    private fun showClipOnBar() {
        val e = extras.getOrNull(playIndex - 1)
        if (e == null) { trim.duration = duration; trim.end = end; trim.start = start; trim.end = end; trim.split = split; trim.frames = mainFrames; return }
        trim.duration = e.duration; trim.end = e.to; trim.start = e.from; trim.end = e.to; trim.split = -1
        ensureFrames(e); trim.frames = framesOf(e)
    }
    private val framesLoading = HashSet<String>()
    private fun framesOf(e: Extra): List<Bitmap> = if (e.uri == source && mainFrames.isNotEmpty()) mainFrames else clipFrames[e.uri.toString()] ?: listOfNotNull(e.thumb)
    private fun ensureFrames(e: Extra) {
        val key = e.uri.toString()
        if (e.uri == source || key in clipFrames || !framesLoading.add(key)) return
        luma.readIo.execute {
            val list = runCatching { val r = MediaMetadataRetriever(); try { r.setDataSource(this, e.uri)
                (0 until 10).mapNotNull { i -> val t = e.duration * 1000 * (2 * i + 1) / 20
                    if (android.os.Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 160) else r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) } } finally { runCatching { r.release() } } }.getOrDefault(emptyList())
            runOnUiThread { if (!isDestroyed) { framesLoading.remove(key); if (list.isNotEmpty()) clipFrames[key] = list; if (extras.getOrNull(playIndex - 1)?.uri == e.uri) trim.frames = framesOf(e); refreshTimeline() } }
        }
    }

    // ---------- Timeline ----------
    private lateinit var timeline: StudioTimeline
    private var laneCount = -1
    private var pendingSeek = -1L; private var lastSeekAt = 0L
    private var barBase: VideoLayer? = null
    private val layerLength = HashMap<Uri, Long>()
    private fun mainLen() = ((end - start - cutMs()) / speed).toLong()
    private fun refreshTimeline() {
        if (!::timeline.isInitialized) return
        val list = ArrayList<StudioTimeline.Clip>(extras.size + 1)
        val seq = order()
        seq.forEach { k -> if (k == 0) list += StudioTimeline.Clip(mainLen().coerceAtLeast(1), start, end, duration, speed, mainFrames.ifEmpty { listOfNotNull(firstFrame) }, extras.isNotEmpty())
            else extras[k - 1].let { e -> ensureFrames(e); list += StudioTimeline.Clip(e.len.coerceAtLeast(1), e.from, e.to, e.duration, e.speed, framesOf(e), true) } }
        // Lanes: the music first, then each layer on the first lane where it does not sit on top of another (four lanes at most).
        val lanes = ArrayList<MutableList<StudioTimeline.Bar>>()
        music?.let { m -> val pk = AudioPeaks.get(m); if (pk == null) AudioPeaks.load(this, m, luma.readIo) { runOnUiThread { if (!isDestroyed) refreshTimeline() } }
            lanes += mutableListOf(StudioTimeline.Bar(-1, 0, list.sumOf { it.len }, 0xff34d399.toInt(), musicName, false, pk, 0L, (pk?.size ?: 0).toLong() * AudioPeaks.STEP_MS)) }
        val firstLayerLane = lanes.size
        val laneOf = StudioMath.pack(layers.map { it.at to it.end }, 4 - firstLayerLane)
        layers.forEachIndexed { i, l ->
            val pk = if (l.kind == VideoLayer.SOUND) AudioPeaks.get(l.source) ?: run { AudioPeaks.load(this, l.source, luma.readIo) { runOnUiThread { if (!isDestroyed) refreshTimeline() } }; null } else null
            val bar = StudioTimeline.Bar(i, l.at, l.length, when (l.kind) { VideoLayer.VIDEO -> 0xff4dc3ff.toInt(); VideoLayer.IMAGE -> 0xffff6fb5.toInt(); VideoLayer.TEXT -> 0xffffd60a.toInt(); else -> 0xffa78bfa.toInt() },
                (if (l.hidden) "(ซ่อน) " else "") + layerName(l), !l.locked, pk, l.from, 0L, l.keyList.map { it.t })
            val at = firstLayerLane + laneOf[i]
            while (lanes.size <= at) lanes += mutableListOf<StudioTimeline.Bar>()
            lanes[at] += bar
        }
        timeline.clips = list; timeline.lanes = lanes; timeline.selected = seq.indexOf(playIndex).coerceAtLeast(0); timeline.joined = transition != 0; timeline.marks = markers
        timeline.selectedBar = if (tool == LAYER_TOOL && layerPick in layers.indices) layerPick else if (tool == 6 && music != null) -1 else -2
        if (lanes.size != laneCount) { laneCount = lanes.size; timeline.layoutParams = timeline.layoutParams.apply { height = dp(68 + 22 * lanes.size) } }
        timeline.playhead = outNow()
        timeline.invalidate()
    }
    /** Which clip, and where in its source file, a moment of the finished video falls. */
    private fun locate(t: Long): Pair<Int, Long> {
        var r = t.coerceAtLeast(0); val o = order()
        o.forEachIndexed { n, k -> val len = lenOf(k)
            if (r < len || n == o.size - 1) { r = r.coerceAtMost(len)
                if (k == 0) { var at = start + (r * speed).toLong(); if (cutValid() && at >= cutFrom) at += cutMs(); return 0 to at.coerceIn(start, end) }
                val e = extras[k - 1]; return k to (e.from + (r * e.speed).toLong()).coerceIn(e.from, e.to) }
            r -= len }
        return 0 to start
    }
    /** Shows clip [k] at [at] ms of its source, without the transition animation: used while scrubbing and picking on the timeline. */
    private fun jumpTo(k: Int, at: Long, exact: Boolean = true) {
        if (k != playIndex || player == null) { playIndex = k.coerceIn(0, extras.size); pendingSeek = at; showClipOnBar(); trim.playhead = at.coerceIn(trim.start, trim.end); openPlayer() }
        else {
            trim.playhead = at.coerceIn(trim.start, trim.end)
            val now = android.os.SystemClock.uptimeMillis()
            if (!prepared) pendingSeek = at
            else if (exact || now - lastSeekAt > 70) { lastSeekAt = now; player?.runCatching { if (android.os.Build.VERSION.SDK_INT >= 26) seekTo(at, if (exact) MediaPlayer.SEEK_CLOSEST else MediaPlayer.SEEK_CLOSEST_SYNC) else seekTo(at.toInt()) } }
        }
        showTime()
    }

    // ---- Source ----
    private lateinit var source: Uri
    private var duration = 0L; private var videoW = 16; private var videoH = 9; private var hasAudio = true

    // ---- Edit state ----
    /** Opened for "AI sharpen": the export sheet starts one size above the source so the model is in play. */
    private var aiStart = false
    private var start = 0L; private var end = 0L; private var split = -1L
    private var speed = 1f; private var cropIndex = 0
    private var caption = ""; private var place = 2; private var textColor = Color.WHITE; private var textBig = false
    private var film: Film? = null; private var filmStrength = 1f
    private var music: Uri? = null; private var musicName = ""; private var keepSound = true
    private val adjust = FloatArray(EditorKit.ADJUSTMENTS.size); private var adjustIndex = 0
    private var turns = 0; private var mirror = false
    private var volume = 1f; private var musicVolume = 1f
    private var cutFrom = -1L; private var cutTo = -1L
    private var textBox = false
    private val extras = mutableListOf<Extra>()
    private var transition = 0
    private val stickers = mutableListOf<VideoSticker>()
    // ---- Layers (video, picture, sound on top) ----
    private val layers = mutableListOf<VideoLayer>()
    private val layerThumbs = HashMap<Uri, Bitmap>()
    private val layerViews = mutableListOf<ImageView>()
    private val layerSound = HashMap<Int, MediaPlayer>()
    private var layerPick = -1
    private var lanes: LayerLanes? = null
    private var pendingKind = VideoLayer.VIDEO
    // ---- Smooth slow motion (optical flow, made at export) ----
    private var smooth = false
    private var flow: SlowFlow.Result? = null
    private val flowCancel = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun cutValid() = cutFrom >= 0 && cutTo > cutFrom

    // ---- Views ----
    private lateinit var stage: FrameLayout
    private lateinit var texture: TextureView
    private lateinit var mask: CropMask
    private lateinit var captionView: TextView
    private lateinit var playIcon: IconView
    private lateinit var timeLabel: TextView
    private lateinit var trim: TrimBar
    private lateinit var tools: FrameLayout
    private lateinit var exportButton: TextView
    private lateinit var toolBar: EditorKit.ToolBar
    private var tool = 0
    private var firstFrame: Bitmap? = null
    private lateinit var dip: View
    private val stickerViews = mutableListOf<TextView>()
    /** The part of the stage that is kept (after crop), where stickers and the caption are placed. */
    private val visible = RectF()
    /** Which clip the preview plays: 0 the main clip, k the k-th added clip. */
    private var playIndex = 0; private var autoPlay = false; private var switching = false

    // ---- Playback ----
    private var player: MediaPlayer? = null; private var musicPlayer: MediaPlayer? = null
    private var surface: Surface? = null; private var prepared = false
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() {
        val p = player ?: return
        if (prepared && p.isPlaying) {
            val pos = p.currentPosition.toLong()
            if (playIndex == 0 && !switching) {
                if (pos >= end - 30) { if (extras.isNotEmpty()) playClip(nextOf(0)) else { p.seekTo(start.toInt()); musicPlayer?.seekTo(0) } }
                else if (cutValid() && pos in cutFrom until cutTo) p.seekTo(cutTo.toInt())
                trim.playhead = pos.coerceIn(start, end)
            } else if (playIndex > 0 && !switching) extras.getOrNull(playIndex - 1)?.let { e ->
                if (pos >= e.to - 30) playClip(nextOf(playIndex)) else trim.playhead = pos.coerceIn(e.from, e.to) }
            showTime(); syncLayerSound(true)
        }
        handler.postDelayed(this, 33)
    } }

    // ---- Export ----
    private var transformer: Transformer? = null
    private val progress = ProgressHolder()
    private var overlay: View? = null

    private val pickClip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        luma.readIo.execute {
            val extra = readExtra(uri)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (extra == null) notice("เปิดวิดีโอนี้ไม่ได้") else { extraCache[uri.toString()] = extra; extras += extra.copy(); showTime(); showTool(10, false); notice("เพิ่มคลิปแล้ว · ส่งออกรวมเป็นไฟล์เดียว") }
            }
        }
    }
    /** Our own gallery as the chooser for video and picture layers. */
    private val pickFromGallery = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        @Suppress("DEPRECATION") val uris = r.data?.getParcelableArrayListExtra<Uri>(GalleryActivity.RESULT_URIS)
        if (r.resultCode == RESULT_OK && !uris.isNullOrEmpty()) addLayers(uris, pendingKind)
    }
    private val pickSound = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; addLayers(listOf(uri), VideoLayer.SOUND) }
    }
    private val pickSrt = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) importSrt(uri) }
    private val saveSrt = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        if (uri != null) runCatching {
            val lines = StudioMath.writeSrt(layers.filter { it.kind == VideoLayer.TEXT }.map { StudioMath.Cue(it.at, it.end, it.text) })
            contentResolver.openOutputStream(uri)?.use { it.write(lines.toByteArray()) }; notice("บันทึกไฟล์ซับแล้ว")
        }.onFailure { notice("บันทึกไฟล์ซับไม่สำเร็จ") }
    }
    /** Reads an .srt file and adds each line as a text layer at its time, low on the picture with a dark box behind. */
    private fun importSrt(uri: Uri) {
        luma.readIo.execute {
            val made = runCatching {
                StudioMath.parseSrt(contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()).take(SRT_LIMIT + 1).map { (from, to, words) ->
                    val draft = VideoLayer(VideoLayer.TEXT, VideoLayer.textSource(words, Color.WHITE, 1), from, to - from, text = words, style = 1)
                    val pic = VideoLayers.textPicture(draft, TEXT_PREVIEW); draft to pic
                }
            }.getOrDefault(emptyList())
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (made.isEmpty()) { notice("อ่านไฟล์ซับนี้ไม่ได้ · ต้องเป็นไฟล์ .srt"); return@runOnUiThread }
                val total = totalOut(); var added = 0
                made.take(SRT_LIMIT).forEach { (l, pic) -> if (l.at < total - 200) { layerThumbs[l.source] = pic
                    layers += l.copy(length = l.length.coerceAtMost(total - l.at), x = 0.5f, y = 0.86f, size = (pic.width.toFloat() / TEXT_PREVIEW * 0.62f).coerceIn(0.1f, 0.95f), aspect = pic.width.toFloat() / pic.height); added++ } }
                layerPick = layers.size - 1; rebuildLayers(); showTool(LAYER_TOOL, false)
                notice(if (added == 0) "เวลาของซับอยู่นอกความยาววิดีโอ" else "เพิ่มซับ $added บรรทัด" + if (made.size > SRT_LIMIT) " · รับได้สูงสุด $SRT_LIMIT บรรทัดต่อครั้ง" else "")
            }
        }
    }
    private val pickMusic = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            music = uri
            musicName = runCatching { contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull() ?: "เพลงที่เลือก"
            prepareMusic(); showTool(tool, false)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        source = intent.data ?: run { finish(); return }
        // One heavy job at a time: while a video exports the editor shows that export instead; while an AI upscale
        // runs it waits. The photo editor is not affected.
        if (ExportJob.running()) { notice("กำลังส่งออกวิดีโออยู่ · ตัดต่อคลิปอื่นได้เมื่อเสร็จแล้ว"); startActivity(Intent(this, ExportActivity::class.java)); finish(); return }
        if (luma.shots.upscaling()) { notice("กำลังเพิ่มรายละเอียดภาพด้วย AI อยู่ · ตัดต่อวิดีโอได้เมื่อเสร็จแล้ว"); finish(); return }
        if (!readSource()) { notice(if (intent.hasExtra("project")) "ไม่พบไฟล์วิดีโอต้นฉบับของโปรเจกต์นี้" else "เปิดวิดีโอนี้ไม่ได้"); finish(); return }
        end = duration
        // From Home "แต่งวิดีโอชัด AI": open the export sheet at once with an AI model chosen.
        if (state == null && intent.getBooleanExtra("ai_export", false)) {
            getSharedPreferences("camera", MODE_PRIVATE).let { if (it.getInt("video_ai_model", 1) == 0) it.edit().putInt("video_ai_model", 1).apply() }
            aiStart = true
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ if (!isDestroyed && !isFinishing) export() }, 700)
        }
        state?.let { restore(it) }
        var reopened: Bundle? = null
        projectId = state?.getString("projectId") ?: intent.getStringExtra("project") ?: StudioProjects.newId()
        projectName = state?.getString("projectName") ?: ""
        if (state == null && intent.hasExtra("project")) StudioProjects.load(this, projectId)?.let { p -> projectName = p.name; reopened = p.state; restore(p.state); tool = 0; layerPick = -1 }
        if (projectName.isEmpty()) projectName = "โปรเจกต์ " + android.text.format.DateFormat.format("d MMM HH:mm", System.currentTimeMillis())
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        installRoot(root)
        // The caption field sits low on the screen: the page shrinks above the keyboard instead of hiding under it.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout() or androidx.core.view.WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }

        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(6)) }
        bar.addView(EditorKit.round(this, Icons.CLOSE, "ปิดตัวตัดต่อ") { leave() }, dp(48), dp(48))
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        undoButton = EditorKit.round(this, "M9 14L4 9l5-5 M4 9h10a6 6 0 0 1 0 12h-3", "ย้อนกลับ") { stepHistory(true) }
        redoButton = EditorKit.round(this, "M15 14l5-5-5-5 M20 9H10a6 6 0 0 0 0 12h3", "ทำซ้ำ") { stepHistory(false) }
        bar.addView(undoButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(8) })
        bar.addView(redoButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(10) })
        paintHistory()
        exportButton = EditorKit.primary(this, "ส่งออก") { export() }
        bar.addView(exportButton, LinearLayout.LayoutParams(-2, dp(48)))
        root.addView(bar, -1, -2)

        stage = FrameLayout(this).apply { clipChildren = true }
        texture = TextureView(this)
        stage.addView(texture, FrameLayout.LayoutParams(0, 0, Gravity.CENTER))
        fxView = FxView(this).also { stage.addView(it, -1, -1) }
        mask = CropMask(this); stage.addView(mask, -1, -1)
        captionView = text("", 20f, Color.WHITE, true).apply { gravity = Gravity.CENTER; maxLines = 3; setShadowLayer(6f, 0f, 2f, 0x99000000.toInt()); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        stage.addView(captionView, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        // Covers the picture for a moment when the preview moves to the next clip (fade or flash transitions).
        dip = View(this).apply { alpha = 0f; isClickable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        stage.addView(dip, -1, -1)
        stage.setOnClickListener { togglePlay() }; stage.contentDescription = "พรีวิววิดีโอ แตะเพื่อเล่นหรือหยุด"
        // Only a real size change re-fits the video: a layout pass alone must not, or setting the caption's width
        // asks for another layout and the page re-lays itself every frame.
        stage.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob -> if (r - l != or_ - ol || b - t != ob - ot) stage.post { layoutVideo() } }
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))

        val transport = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(6), dp(14), dp(2)) }
        playIcon = IconView(this, "M9 6.5v11l9-5.5z", 2f)
        transport.addView(FrameLayout(this).apply { background = glass(24, 0x40ffffff); isClickable = true; contentDescription = "เล่นหรือหยุด"; glassTouch(); setOnClickListener { togglePlay() }
            addView(playIcon, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)) }, dp(48), dp(48))
        timeLabel = text("", 13f, Glass.TEXT2).apply { setPadding(dp(12), 0, 0, 0) }
        transport.addView(timeLabel, LinearLayout.LayoutParams(0, -2, 1f))
        timeLabel.maxLines = 2
        transport.addView(EditorKit.round(this, EditorKit.SPLIT, "แยกคลิปตรงหัวอ่าน") { splitAtPlayhead() }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(6) })
        transport.addView(EditorKit.round(this, "M7 4v16 M7 5h10l-2.5 3.5L17 12H7", "ปักหรือถอนมาร์กเกอร์ตรงหัวอ่าน") {
            val now = outNow(); val near = markers.firstOrNull { abs(it - now) <= 150 }
            if (near != null) markers.remove(near) else { markers += now; markers.sort() }
            Glass.tick(timeline); refreshTimeline() }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(6) })
        transport.addView(EditorKit.chip(this, "เพิ่มคลิป", ADD) { pickClip.launch(arrayOf("video/*")) }, LinearLayout.LayoutParams(-2, dp(44)))
        root.addView(transport, -1, -2)
        timeline = StudioTimeline(this).apply { contentDescription = "ไทม์ไลน์ ลากเพื่อเลื่อนเวลา แตะคลิปเพื่อเลือก ลากขอบคลิปที่เลือกเพื่อตัด กดค้างแล้วลากเพื่อสลับลำดับ" }
        timeline.onSeek = { t, done -> if (player?.isPlaying == true) togglePlay(); val (k, at) = locate(t); jumpTo(k, at, done) }
        timeline.onPickBar = { id -> if (id >= 0) { layerPick = id; showTool(LAYER_TOOL, tool != LAYER_TOOL); placeLayers() } else showTool(6, tool != 6) }
        timeline.onBar = { id, at, len, shift, done -> layers.getOrNull(id)?.let { cur ->
            val base = barBase ?: cur.also { barBase = it }
            var from = base.from; var begin = at; var length = len
            if (!base.still) {
                // A video or sound layer cannot show more than its file holds: the ends stop where the file does.
                val cap = layerLength.getOrPut(base.source) { VideoLayers.duration(this, base.source) }
                from = base.from + shift
                if (from < 0) { begin -= from; length += from; from = 0 }
                if (cap > 0) length = length.coerceAtMost(cap - from)
            }
            layers[id] = cur.copy(at = begin.coerceAtLeast(0), length = length.coerceAtLeast(200), from = from)
            if (done) barBase = null
            placeLayers(); refreshTimeline() } }
        timeline.onSelect = { i -> val k = order().getOrNull(i) ?: 0; if (k != playIndex) jumpTo(k, if (k == 0) start else extras[k - 1].from); if (tool == 10) showTool(10, false) }
        timeline.onTrim = { i, f, t, done -> val k = order().getOrNull(i) ?: 0
            if (k == 0) { start = f; end = t; if (split !in (f + 300)..(t - 300)) split = -1; if (cutValid() && (cutFrom < f + 100 || cutTo > t - 100)) { cutFrom = -1; cutTo = -1 } }
            else extras.getOrNull(k - 1)?.let { it.from = f; it.to = t }
            if (playIndex == k) { showClipOnBar(); trim.playhead = trim.playhead.coerceIn(f, t); if (done) jumpTo(k, trim.playhead) }
            showTime()
        }
        timeline.onMove = { i, j ->
            val o = order().toMutableList()
            if (i in o.indices && j in o.indices) { val playing = extras.getOrNull(playIndex - 1); o.add(j, o.removeAt(i))
                val old = extras.toList(); extras.clear(); o.filter { it != 0 }.forEach { extras += old[it - 1] }; mainAt = o.indexOf(0)
                if (playing != null) playIndex = extras.indexOf(playing) + 1 }
            showTime(); if (tool == 10) showTool(10, false)
        }
        root.addView(timeline, LinearLayout.LayoutParams(-1, dp(68)).apply { topMargin = dp(2) })
        trim = TrimBar(this).apply {
            contentDescription = "ช่วงคลิป ลากขอบซ้ายขวาเพื่อตัด"
        }
        // Set outside apply{}: inside it, start/end/split would name the bar's own fields, not the edit's.
        trim.onRange = { s, e -> val x = extras.getOrNull(playIndex - 1)
            if (x != null) { x.from = s; x.to = e; seek(trim.playhead.coerceIn(s, e)); showTime() } else { start = s; end = e; if (split !in (s + 300)..(e - 300)) split = -1; trim.split = split; seek(trim.playhead.coerceIn(s, e)); showTime() } }
        trim.onSeek = { ms -> seek(ms) }
        root.addView(trim, LinearLayout.LayoutParams(-1, dp(64)).apply { setMargins(dp(12), dp(4), dp(12), dp(4)) })
        tools = FrameLayout(this).apply { clipChildren = false }
        root.addView(tools, LinearLayout.LayoutParams(-1, dp(124)))
        toolBar = EditorKit.ToolBar(this, listOf(EditorKit.SCISSORS to "ตัด", EditorKit.SPLIT to "แยกไฟล์", EditorKit.SPEED to "ความเร็ว", EditorKit.CROP to "ครอบ", EditorKit.TEXT to "ข้อความ", Icons.FILM to "ฟิลเตอร์", EditorKit.MUSIC to "เพลง", EditorKit.ADJUST to "ปรับ", EditorKit.ROTATE to "หมุน", Icons.SOUND to "เสียง", CLIPS to "คลิป", JOIN to "ทรานซิชัน", STICKER to "สติกเกอร์", LAYERS to "เลเยอร์", "M12 3a9 9 0 1 0 0 18c1.1 0 2-.9 2-2 0-.5-.2-1-.5-1.3-.3-.4-.5-.8-.5-1.3 0-1.1.9-2 2-2h2.3A4.7 4.7 0 0 0 21 9.7C21 5.9 17 3 12 3z M7.5 11h.01 M10 7.5h.01 M14.5 7.5h.01" to "สี")) { showTool(it) }
        root.addView(toolBar, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(4), 0, dp(4), dp(8)) })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = leave() })

        trim.duration = duration; trim.start = start; trim.end = end; trim.split = split
        luma.readIo.execute {
            val frames = frames(10)
            runOnUiThread { if (!isDestroyed) { mainFrames = frames; if (playIndex == 0) trim.frames = frames; firstFrame = frames.firstOrNull(); if (tool == 5) showTool(5, false) } }
        }
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { surface = Surface(s); openPlayer() }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean { releasePlayer(); surface?.release(); surface = null; return true }
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
        applyLook(); showTool(tool, false); showTime()
        if (music != null) prepareMusic()
        // Undo starts watching only after the saved clips are back, or their return would count as an edit.
        val saved = state ?: reopened
        if (saved?.getStringArrayList("extras").isNullOrEmpty()) handler.postDelayed(watcher, 600)
        else { applying = true; restoreExtras(saved!!) { applying = false; handler.postDelayed(watcher, 600) } }
        rebuildStickers(); loadLayerThumbs()
    }
    private fun readExtra(uri: Uri): Extra? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(this, uri)
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (d <= 0 || w <= 0 || h <= 0) return@runCatching null
            val thumb = if (android.os.Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(d * 500, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 160) else r.getFrameAtTime(d * 500)
            val name = runCatching { contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull() ?: "คลิป"
            Extra(uri, d, if (rot % 180 != 0) h else w, if (rot % 180 != 0) w else h, name, thumb)
        } finally { runCatching { r.release() } }
    }.getOrNull()
    private fun restoreExtras(s: Bundle, done: () -> Unit = {}) {
        val uris = s.getStringArrayList("extras") ?: run { done(); return }
        luma.readIo.execute {
            val read = uris.distinct().mapNotNull { u -> (extraCache[u] ?: readExtra(Uri.parse(u)))?.let { u to it } }.toMap()
            runOnUiThread { if (!isDestroyed) { extraCache.putAll(read); extras.clear(); extras += buildExtras(uris, s.getStringArrayList("extraRanges")) { extraCache[it] }; showTime(); if (tool == 10) showTool(10, false); done() } }
        }
    }

    /** MediaMetadataRetriever is AutoCloseable only from Android 10, so it is released by hand. */
    private fun <T> retrieve(block: (MediaMetadataRetriever) -> T): T { val r = MediaMetadataRetriever(); try { r.setDataSource(this, source); return block(r) } finally { runCatching { r.release() } } }
    private fun readSource(): Boolean = runCatching {
        retrieve { r ->
            duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot % 180 != 0) { videoW = h; videoH = w } else { videoW = w; videoH = h }
            hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
        }
        duration > 0 && videoW > 0 && videoH > 0
    }.getOrDefault(false)

    private fun frames(n: Int): List<Bitmap> = runCatching {
        retrieve { r ->
            (0 until n).mapNotNull { i ->
                val t = duration * 1000 * (2 * i + 1) / (2 * n)
                val b = if (android.os.Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 160) else r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                b
            }
        }
    }.getOrDefault(emptyList())

    // ---------- Playback ----------
    private fun openPlayer() {
        val s = surface ?: return
        releasePlayer(); fxEq?.runCatching { release() }; fxEq = null
        if (playIndex > extras.size) playIndex = 0
        val clip = if (playIndex == 0) source else extras[playIndex - 1].uri
        player = MediaPlayer().apply {
            runCatching {
                setDataSource(this@VideoEditorActivity, clip); setSurface(s); isLooping = false
                setOnPreparedListener { mp ->
                    prepared = true; applySound(); if (playIndex == 0) seekTo(this@VideoEditorActivity.start.toInt()) else extras.getOrNull(playIndex - 1)?.let { if (it.from > 0) seekTo(it.from.toInt()) }; if (pendingSeek >= 0) { seekTo(pendingSeek.toInt()); pendingSeek = -1 }; applySound(); layoutVideo(); arrived()
                    if (autoPlay) { autoPlay = false; val sp = if (playIndex == 0) speed else extras.getOrNull(playIndex - 1)?.speed ?: 1f; if (sp != 1f) runCatching { mp.playbackParams = mp.playbackParams.setSpeed(sp) }; mp.start(); musicPlayer?.runCatching { start() }; playIcon.path = "M8 6v12 M16 6v12" }
                }
                setOnCompletionListener { if (playIndex == 0 && extras.isEmpty()) seekTo(this@VideoEditorActivity.start.toInt()) else playClip(nextOf(playIndex)) }
                prepareAsync()
            }.onFailure { notice("เล่นพรีวิวไม่ได้ · ${it.localizedMessage}") }
        }
        handler.removeCallbacks(tick); handler.post(tick)
    }
    /** Moves the preview to clip [k] and keeps playing, with the chosen transition drawn over the change. */
    private fun playClip(k: Int, play: Boolean = true) {
        val wasPlaying = play && (player?.isPlaying == true || autoPlay)
        autoPlay = wasPlaying
        // Back at the start after the last clip: the music starts again too.
        if (k == order().first()) musicPlayer?.runCatching { seekTo(0) }
        switching = true
        val go = Runnable { playIndex = k; switching = false; showClipOnBar(); trim.playhead = trim.start; openPlayer(); showTime() }
        val half = VideoEdit.TRANSITION_MS / 2
        if (Glass.reduced(this) || extras.isEmpty()) go.run()
        else when (transition) {
            1, 2 -> { dip.setBackgroundColor(if (transition == 2) Color.WHITE else Color.BLACK); dip.pivotX = 0f; dip.scaleX = 1f
                dip.animate().cancel(); dip.animate().alpha(1f).setDuration(half).withEndAction(go).start() }
            4 -> texture.animate().translationX(-texture.width.toFloat()).setInterpolator(Glass.EASE).setDuration(half).withEndAction { go.run() }.start()
            5 -> { dip.setBackgroundColor(Color.BLACK); dip.alpha = 1f; dip.pivotX = dip.width.toFloat(); dip.scaleX = 0f
                dip.animate().cancel(); dip.animate().scaleX(1f).setDuration(half).withEndAction(go).start() }
            6 -> if (android.os.Build.VERSION.SDK_INT >= 31) android.animation.ValueAnimator.ofFloat(0f, 1f).apply { duration = half
                addUpdateListener { blurPreview(it.animatedValue as Float) }; addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(a: android.animation.Animator) { blurIn = true; go.run() } }); start() } else go.run()
            else -> go.run()
        }
    }
    /** The new clip is ready: uncover it (fade, flash) or let it settle from a little closer (zoom). */
    private fun arrived() {
        if (Glass.reduced(this)) { dip.alpha = 0f; return }
        if (transition == 5 && dip.alpha > 0f) { dip.pivotX = 0f; dip.animate().cancel(); dip.animate().scaleX(0f).setDuration(VideoEdit.TRANSITION_MS).withEndAction { dip.alpha = 0f; dip.scaleX = 1f }.start() }
        else if (dip.alpha > 0f) dip.animate().alpha(0f).setDuration(VideoEdit.TRANSITION_MS).start()
        if (texture.translationX != 0f) { texture.translationX = texture.width.toFloat(); texture.animate().translationX(0f).setInterpolator(Glass.EASE).setDuration(VideoEdit.TRANSITION_MS).start() }
        if (blurIn && android.os.Build.VERSION.SDK_INT >= 31) { blurIn = false; android.animation.ValueAnimator.ofFloat(1f, 0f).apply { duration = VideoEdit.TRANSITION_MS
            addUpdateListener { blurPreview(it.animatedValue as Float) }; addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(a: android.animation.Animator) { paintLook() } }); start() } }
        if (transition == 3 && playIndex > 0) {
            texture.scaleY = 1.12f; texture.scaleX = 1.12f
            texture.animate().scaleX(1f).scaleY(1f).setInterpolator(Glass.EASE).setDuration(VideoEdit.TRANSITION_MS).start()
        }
    }
    private fun releasePlayer() {
        handler.removeCallbacks(tick); prepared = false
        player?.runCatching { release() }; player = null
        musicPlayer?.runCatching { pause() }
        if (::playIcon.isInitialized) playIcon.path = "M9 6.5v11l9-5.5z"
    }
    private fun prepareMusic() {
        musicPlayer?.runCatching { release() }; musicPlayer = null
        val m = music ?: return
        musicPlayer = runCatching { MediaPlayer().apply { setDataSource(this@VideoEditorActivity, m); isLooping = true; prepare() } }.getOrElse { notice("เปิดเพลงนี้ไม่ได้"); music = null; null }
    }
    private fun applySound() {
        val clipVol = if (playIndex == 0) volume.coerceAtMost(1f) else 1f
        val v = if (keepSound && !isMuted(playIndex) && recorder == null) clipVol else 0f; player?.runCatching { setVolume(v, v) }
        val m = if (recorder == null) musicVolume.coerceAtMost(1f) * duckGain() else 0f; musicPlayer?.runCatching { setVolume(m, m) }
        previewEq()
    }
    private fun togglePlay() {
        val p = player ?: return; if (!prepared) return
        if (p.isPlaying) { p.pause(); musicPlayer?.pause(); syncLayerSound(false); playIcon.path = "M9 6.5v11l9-5.5z" }
        else {
            if (playIndex == 0 && p.currentPosition >= end - 60) p.seekTo(start.toInt())
            extras.getOrNull(playIndex - 1)?.let { if (p.currentPosition >= it.to - 60) p.seekTo(it.from.toInt()) }
            runCatching { p.playbackParams = p.playbackParams.setSpeed(if (playIndex == 0) speed else extras.getOrNull(playIndex - 1)?.speed ?: 1f) }
            p.start(); musicPlayer?.let { mp -> runCatching { mp.seekTo(((p.currentPosition - start).coerceAtLeast(0) % max(1, mp.duration)).toInt()); mp.start() } }
            playIcon.path = "M8 6v12 M16 6v12"
        }
    }
    private fun seek(ms: Long) {
        trim.playhead = ms
        val p = player ?: return
        if (prepared) { if (android.os.Build.VERSION.SDK_INT >= 26) p.seekTo(ms, MediaPlayer.SEEK_CLOSEST) else p.seekTo(ms.toInt()) }
        showTime()
    }
    private fun clock(ms: Long) = String.format(Locale.US, "%d:%02d.%d", ms / 60000, ms / 1000 % 60, ms / 100 % 10)
    private fun showTime() {
        fxView?.sync(visible, if (comparing) 0f else fx[0], if (comparing) 0f else fx[1])
        refreshTimeline()
        val length = ((end - start) / speed).toLong()
        val more = extras.sumOf { it.len }
        timeLabel.text = (if (playIndex > 0) "คลิป ${playIndex + 1}/${extras.size + 1}  ·  ${clock(trim.playhead - trim.start)} / ${clock(trim.end - trim.start)}" else "${clock(trim.playhead - start)} / ${clock(end - start)}") +
            (if (extras.isNotEmpty()) "  ·  รวม ${clock(length + more)}" else if (speed != 1f) "  ·  ได้คลิป ${clock(length)}" else "") + (if (split >= 0 && extras.isEmpty() && layers.isEmpty()) "  ·  แยกเป็น 2 คลิป" else "")
        if (layers.isNotEmpty()) { placeLayers(); lanes?.playhead = outNow() }
    }

    /** Fits the video in the stage and places the crop frame and caption over it. */
    private fun layoutVideo() {
        if (stage.width == 0) return
        val pad = dp(8)
        val aw = stage.width - 2 * pad; val ah = stage.height - 2 * pad
        // A quarter turn swaps the picture's sides: fit the turned shape, then turn the view itself.
        val side = turns % 2 == 1; val ew = if (side) videoH else videoW; val eh = if (side) videoW else videoH
        val k = min(aw.toFloat() / ew, ah.toFloat() / eh)
        val w = (ew * k).toInt(); val h = (eh * k).toInt()
        val video = RectF((stage.width - w) / 2f, (stage.height - h) / 2f, (stage.width + w) / 2f, (stage.height + h) / 2f)
        val visible = cropRect(video); mask.set(video, visible); this.visible.set(visible)
        val extra = extras.getOrNull(playIndex - 1)
        if (extra == null) {
            val tw = (videoW * k).toInt(); val th = (videoH * k).toInt()
            (texture.layoutParams as FrameLayout.LayoutParams).let { if (it.width != tw || it.height != th) { it.width = tw; it.height = th; texture.layoutParams = it } }
            texture.rotation = turns * 90f; texture.scaleX = if (mirror) -1f else 1f; texture.clipBounds = null
        } else {
            // An added clip fills the kept frame and is cut at the sides, as the export does.
            val f = max(visible.width() / extra.w, visible.height() / extra.h)
            val tw = (extra.w * f).toInt(); val th = (extra.h * f).toInt()
            (texture.layoutParams as FrameLayout.LayoutParams).let { if (it.width != tw || it.height != th) { it.width = tw; it.height = th; texture.layoutParams = it } }
            texture.rotation = 0f; texture.scaleX = 1f
            val cx = (tw - visible.width()) / 2f; val cy = (th - visible.height()) / 2f
            texture.clipBounds = Rect(cx.toInt(), cy.toInt(), (cx + visible.width()).toInt(), (cy + visible.height()).toInt())
        }
        placeStickers(); placeLayers()
        captionView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, (if (textBig) 0.11f else 0.07f) * visible.height())
        val widest = visible.width().toInt() - dp(16); if (captionView.maxWidth != widest) captionView.maxWidth = widest
        captionView.post {
            captionView.translationX = visible.centerX() - stage.width / 2f
            val y = when (place) { 0 -> visible.top + visible.height() * 0.06f + captionView.height / 2f; 1 -> visible.centerY(); else -> visible.bottom - visible.height() * 0.06f - captionView.height / 2f }
            captionView.translationY = y - stage.height / 2f
        }
    }
    private fun cropRect(video: RectF): RectF {
        val r = CROPS[cropIndex].second; if (r == 0f) return RectF(video)
        val now = video.width() / video.height()
        return if (r > now) { val h = video.width() / r; RectF(video.left, video.centerY() - h / 2, video.right, video.centerY() + h / 2) }
        else { val w = video.height() * r; RectF(video.centerX() - w / 2, video.top, video.centerX() + w / 2, video.bottom) }
    }
    private fun colour(): ColorMatrix = EditorKit.film(film, filmStrength).apply { postConcat(EditorKit.adjust(adjust)) }
    private fun looked() = film != null || adjust.any { it != 0f }
    private fun applyLook() {
        paintLook()
        captionView.setBackgroundColor(if (textBox) 0x99000000.toInt() else 0); captionView.setPadding(if (textBox) dp(8) else 0, 0, if (textBox) dp(8) else 0, 0)
        captionView.text = caption; captionView.setTextColor(textColor); captionView.visibility = if (caption.isBlank()) View.GONE else View.VISIBLE
        layoutVideo(); if (::timeLabel.isInitialized) showTime()
    }

    // ---------- Sound studio ----------
    /** Bass, middle and treble of the clips' own sound in dB (-12..12). */
    private val eq = FloatArray(3)
    private var lowCut = false; private var voice = false; private var duck = true; private var mainMuted = false
    private var fxEq: android.media.audiofx.Equalizer? = null
    private fun audioSettings() = StudioAudio.Settings(eq[0], eq[1], eq[2], lowCut, voice)
    /** The equaliser on the preview: the phone's own equaliser on the player, its bands set from the three sliders. */
    private fun previewEq() {
        val p = player ?: return
        if (eq.all { it == 0f }) { fxEq?.runCatching { enabled = false }; return }
        val e = fxEq?.takeIf { runCatching { it.id; true }.getOrDefault(false) } ?: runCatching { android.media.audiofx.Equalizer(0, p.audioSessionId) }.getOrNull()?.also { fxEq = it } ?: return
        runCatching {
            val (lo, hi) = e.bandLevelRange.let { it[0].toInt() to it[1].toInt() }
            for (b in 0 until e.numberOfBands) { val hz = e.getCenterFreq(b.toShort()) / 1000f
                val db = when { hz < 400 -> eq[0]; hz < 3500 -> eq[1]; else -> eq[2] }
                e.setBandLevel(b.toShort(), (db * 100).toInt().coerceIn(lo, hi).toShort()) }
            e.enabled = true
        }
    }
    /** Clip number [k] (0 = the first clip) plays without its sound or with it again. */
    private fun setMuted(k: Int, on: Boolean) { if (k == 0) mainMuted = on else extras.getOrNull(k - 1)?.muted = on; applySound() }
    private fun isMuted(k: Int) = if (k == 0) mainMuted else extras.getOrNull(k - 1)?.muted == true
    /**
     * Moves the sound of the clip on screen to a sound layer at the same time, and silences the clip. The layer can
     * then be dragged earlier or later on the timeline, so the sound starts before the picture (J-cut) or carries on
     * over the next clip (L-cut), or be trimmed and faded on its own.
     */
    private fun detachSound() {
        val k = playIndex; val e = extras.getOrNull(k - 1)
        if (isMuted(k)) { notice("คลิปนี้ไม่มีเสียงให้แยกแล้ว"); return }
        val sp = e?.speed ?: speed
        if (sp != 1f) { notice("แยกเสียงได้เฉพาะคลิปความเร็วปกติ"); return }
        if (e == null && cutValid()) { notice("คลิปนี้ตัดช่วงกลางออกแล้ว จึงแยกเสียงไม่ได้"); return }
        val src = e?.uri ?: source; val from = e?.from ?: start; val len = e?.len ?: mainLen()
        var at = 0L; for (o in order()) { if (o == k) break; at += lenOf(o) }
        layers += VideoLayer(VideoLayer.SOUND, src, at, len, from, volume = if (e == null) volume else 1f)
        setMuted(k, true); layerPick = layers.size - 1; rebuildLayers(); refreshTimeline()
        notice("แยกเสียงเป็นแถบสีม่วงแล้ว · ลากแถบเพื่อให้เสียงมาก่อนหรือต่อเลยภาพ")
    }
    /** The music goes down to 30 % while a sound layer plays, in the preview the same way as in the export. */
    private fun duckGain(): Float = if (!duck) 1f else StudioAudio.duck(outNow(), layers.filter { it.audible && it.volume > 0f && !it.hidden }.map { it.at to it.end })

    // ---------- Colour studio ----------
    private val grade = StudioGrade()
    private var lutPath = ""; private var lutName = ""
    private var colourTab = 0; private var curveChannel = 0; private var hslBand = 0; private var wheelRange = 1
    private var hist: HistView? = null
    private var lastCube: Array<Array<IntArray>>? = null
    private fun gradeCube(n: Int) = grade.build(if (looked()) colour().array else null, n)
    @androidx.annotation.RequiresApi(33)
    private fun lutEffect(cube: Array<Array<IntArray>>): RenderEffect {
        val n = cube.size; val px = IntArray(n * n * n)
        for (r in 0 until n) for (g in 0 until n) for (b in 0 until n) px[g * n * n + b * n + r] = cube[r][g][b]
        val bmp = Bitmap.createBitmap(px, n * n, n, Bitmap.Config.ARGB_8888)
        val lut = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_NEAREST }
        val shader = RuntimeShader(LUT_AGSL).apply { setInputShader("lut", lut); setFloatUniform("n", n.toFloat()) }
        return RenderEffect.createRuntimeShaderEffect(shader, "content")
    }
    /** Blur over the preview for the blur transition ([k] 0..1), on top of the colour look. */
    private var blurIn = false
    private fun blurPreview(k: Float) {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        if (k <= 0.01f) { paintLook(); return }
        val blur = RenderEffect.createBlurEffect(k * k * dp(18), k * k * dp(18), Shader.TileMode.CLAMP)
        val look = if (android.os.Build.VERSION.SDK_INT >= 33 && grade.active() && !comparing) lutEffect(lastCube ?: gradeCube(25)) else null
        texture.setRenderEffect(if (look != null) RenderEffect.createChainEffect(blur, look) else blur)
    }
    /** Puts the whole look (filter, adjustments and grade) on the preview: through one colour table when the grade is used, else as a colour matrix. */
    private fun paintLook() {
        val graded = grade.active() && !comparing
        if (graded && android.os.Build.VERSION.SDK_INT >= 33) {
            val cube = gradeCube(25); lastCube = cube
            texture.setLayerPaint(null); texture.setRenderEffect(lutEffect(cube))
        } else {
            if (android.os.Build.VERSION.SDK_INT >= 31) texture.setRenderEffect(null)
            lastCube = if (graded) gradeCube(17) else null
            texture.setLayerPaint(if (!looked() || comparing) null else Paint().apply { colorFilter = ColorMatrixColorFilter(colour()) })
        }
    }
    /** Brightness spread of the picture on screen, after the look: red, green and blue, dark on the left, bright on the right. */
    private class HistView(c: Context) : View(c) {
        private val bins = Array(3) { FloatArray(64) }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG); private val path = Path()
        private val colors = intArrayOf(0x99ff5a5a.toInt(), 0x9952d96b.toInt(), 0x995a9bff.toInt())
        init { contentDescription = "ฮิสโตแกรม" }
        fun feed(b: Bitmap, cube: Array<Array<IntArray>>?) {
            bins.forEach { it.fill(0f) }
            val w = b.width; val h = b.height; val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
            val n = cube?.size ?: 0
            for (p in px) {
                var c = p
                if (cube != null) c = cube[((p shr 16 and 255) * (n - 1) + 127) / 255][((p shr 8 and 255) * (n - 1) + 127) / 255][((p and 255) * (n - 1) + 127) / 255]
                bins[0][(c shr 16 and 255) shr 2]++; bins[1][(c shr 8 and 255) shr 2]++; bins[2][(c and 255) shr 2]++
            }
            val top = bins.maxOf { a -> a.drop(1).dropLast(1).maxOrNull() ?: 1f }.coerceAtLeast(1f)
            bins.forEach { a -> for (i in a.indices) a[i] = (a[i] / top).coerceAtMost(1f) }
            invalidate()
        }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            paint.style = Paint.Style.FILL; paint.color = 0x1fffffff; c.drawRoundRect(0f, 0f, w, h, h * 0.18f, h * 0.18f, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
            bins.forEachIndexed { k, a -> path.reset(); path.moveTo(0f, h)
                a.forEachIndexed { i, v -> path.lineTo(i * w / (a.size - 1), h - v * (h - 4)) }
                path.lineTo(w, h); path.close(); paint.color = colors[k]; c.drawPath(path, paint) }
            paint.xfermode = null
        }
    }
    private val histTick = object : Runnable { override fun run() {
        val v = hist ?: return
        if (tool != COLOR_TOOL || isDestroyed) return
        runCatching { texture.getBitmap(96, 54) }.getOrNull()?.let { b -> v.feed(b, if (comparing) null else lastCube); b.recycle() }
        handler.postDelayed(this, 450)
    } }
    /** One tone curve to bend by dragging its five points up and down. */
    private class CurveEdit(c: Context) : View(c) {
        var points = FloatArray(20); var at = 0; var color = Color.WHITE
        var onChange: () -> Unit = {}
        private val d = resources.displayMetrics.density
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.4f * resources.displayMetrics.density }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG); private val grid = Paint().apply { color = 0x26ffffff }
        private var drag = -1
        init { contentDescription = "เส้นโค้งโทน ลากจุดขึ้นลง"; isClickable = true }
        private fun x(i: Float) = 10 * d + i * (width - 20 * d); private fun y(v: Float) = height - 8 * d - v * (height - 16 * d)
        override fun onDraw(c: Canvas) {
            for (k in 0..4) { val gx = x(k / 4f); c.drawLine(gx, y(0f), gx, y(1f), grid); val gy = y(k / 4f); c.drawLine(x(0f), gy, x(1f), gy, grid) }
            line.color = color; var px = x(0f); var py = y(StudioGrade.curve(points, at, 0f))
            for (s in 1..60) { val t = s / 60f; val nx = x(t); val ny = y(StudioGrade.curve(points, at, t)); c.drawLine(px, py, nx, ny, line); px = nx; py = ny }
            for (k in 0..4) { dot.color = if (k == drag) Glass.ACCENT else Color.WHITE; c.drawCircle(x(k / 4f), y(points[at + k]), (if (k == drag) 8f else 6f) * d, dot) }
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); drag = ((e.x - x(0f)) / (x(1f) - x(0f)) * 4 + 0.5f).toInt().coerceIn(0, 4); invalidate() }
                MotionEvent.ACTION_MOVE -> if (drag >= 0) { points[at + drag] = ((height - 8 * d - e.y) / (height - 16 * d)).coerceIn(0f, 1f); invalidate(); onChange() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { drag = -1; invalidate() }
            }
            return true
        }
    }
    private fun colourTools(col: LinearLayout) {
        col.gravity = Gravity.TOP
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(2), dp(12), dp(4)) }
        hist = HistView(this).also { top.addView(it, LinearLayout.LayoutParams(0, dp(40), 1f)) }
        top.addView(text("กดค้างดูต้นฉบับ", 11f, Glass.TEXT2, true).apply { gravity = Gravity.CENTER; background = glass(14, 0x26ffffff); setPadding(dp(10), 0, dp(10), 0); contentDescription = "กดค้างเพื่อดูภาพก่อนปรับสี"
            setOnTouchListener { v, e -> when (e.actionMasked) { MotionEvent.ACTION_DOWN -> { comparing = true; applyLook(); v.alpha = 0.6f }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { comparing = false; applyLook(); v.alpha = 1f } }; true } }, LinearLayout.LayoutParams(-2, dp(30)).apply { leftMargin = dp(8) })
        top.addView(text("รีเซ็ต", 11f, Glass.TEXT2, true).apply { gravity = Gravity.CENTER; background = glass(14, 0x26ffffff); setPadding(dp(10), 0, dp(10), 0); isClickable = true; glassTouch(); contentDescription = "รีเซ็ตสีทั้งหมด"
            setOnClickListener { grade.reset(); lutPath = ""; lutName = ""; applyLook(); showTool(COLOR_TOOL, false) } }, LinearLayout.LayoutParams(-2, dp(30)).apply { leftMargin = dp(6) })
        col.addView(top, -1, -2)
        val tabs = LinearLayout(this).apply { gravity = Gravity.CENTER }
        listOf("โทนสี", "เส้นโค้ง", "HSL", "LUT").forEachIndexed { i, n -> tabs.addView(text(n, 12.5f, if (i == colourTab) Glass.ACCENT else Color.WHITE, i == colourTab).apply { gravity = Gravity.CENTER
            background = glass(16, if (i == colourTab) Glass.ACCENT_SOFT else 0x1fffffff); setPadding(dp(14), 0, dp(14), 0); isClickable = true; glassTouch(); contentDescription = n
            setOnClickListener { colourTab = i; showTool(COLOR_TOOL, false) } }, LinearLayout.LayoutParams(-2, dp(32)).apply { setMargins(dp(3), 0, dp(3), 0) }) }
        col.addView(tabs, -1, -2)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, 0) }
        col.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        fun slider(name: String, now: Float, lo: Int, grad: IntArray, set: (Float) -> Unit) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(10), 0) }
            val steps = (lo..100).toList(); val label = text("$name ${sign(now)}", 12f, Glass.TEXT2, true).apply { minWidth = dp(104) }
            row.addView(label)
            row.addView(GlassSlider(this).apply { values = steps.map { if (it > 0 && lo < 0) "+$it" else "$it" }; this.grad = grad; index = ((now * 100).roundToInt() - lo).coerceIn(0, steps.size - 1); contentDescription = name
                onChange = { k -> val v = steps[k] / 100f; label.text = "$name ${sign(v)}"; set(v); applyLook() } }, LinearLayout.LayoutParams(0, dp(38), 1f))
            body.addView(row, -1, -2)
        }
        fun chips(names: List<String>, colors: List<Int>?, at: Int, pick: (Int) -> Unit) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(8), dp(2)) }
            names.forEachIndexed { i, n ->
                val v: View = if (colors != null) View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colors[i]); setStroke(dp(if (i == at) 3 else 1), if (i == at) Color.WHITE else 0x55ffffff) } }
                else text(n, 12f, if (i == at) Glass.ACCENT else Color.WHITE, i == at).apply { gravity = Gravity.CENTER; background = glass(14, if (i == at) Glass.ACCENT_SOFT else 0x1fffffff); setPadding(dp(12), 0, dp(12), 0) }
                v.isClickable = true; v.contentDescription = n; v.setOnClickListener { Glass.tick(v); pick(i) }
                row.addView(v, if (colors != null) LinearLayout.LayoutParams(dp(26), dp(26)).apply { setMargins(dp(5), 0, dp(5), 0) } else LinearLayout.LayoutParams(-2, dp(30)).apply { setMargins(dp(3), 0, dp(3), 0) })
            }
            body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }
        val rainbow = intArrayOf(0xffff3b30.toInt(), 0xffffcc00.toInt(), 0xff34c759.toInt(), 0xff32ade6.toInt(), 0xff5856d6.toInt(), 0xffff2d55.toInt(), 0xffff3b30.toInt())
        val dim = intArrayOf(0xff8e8e93.toInt(), Color.WHITE, Glass.ACCENT)
        when (colourTab) {
            0 -> { // Colour wheels as three sliders each: which colour to push into the shadows, midtones or highlights, how strongly, and how much brighter or darker.
                chips(listOf("เงา", "กลาง", "สว่าง"), null, wheelRange) { wheelRange = it; showTool(COLOR_TOOL, false) }
                val k = wheelRange * 3
                slider("สีที่เติม", grade.wheels[k], 0, rainbow) { grade.wheels[k] = it }
                slider("ความแรงสี", grade.wheels[k + 1], 0, dim) { grade.wheels[k + 1] = it }
                slider("ความสว่าง", grade.wheels[k + 2], -100, dim) { grade.wheels[k + 2] = it }
            }
            1 -> {
                chips(listOf("ทั้งหมด", "แดง", "เขียว", "น้ำเงิน"), null, curveChannel) { curveChannel = it; showTool(COLOR_TOOL, false) }
                val edit = CurveEdit(this).apply { points = grade.curves; at = curveChannel * 5; color = intArrayOf(Color.WHITE, 0xffff5a5a.toInt(), 0xff52d96b.toInt(), 0xff5a9bff.toInt())[curveChannel]
                    onChange = { applyLook() } }
                body.addView(edit, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(dp(24), dp(2), dp(24), dp(2)) })
            }
            2 -> {
                val names = listOf("แดง", "ส้ม", "เหลือง", "เขียว", "ฟ้า", "น้ำเงิน", "ม่วง", "ชมพู")
                chips(names, StudioGrade.BANDS.map { StudioGrade.hueColour(it / 360f).let { c -> Color.rgb((c[0] * 255).toInt(), (c[1] * 255).toInt(), (c[2] * 255).toInt()) } }, hslBand) { hslBand = it; showTool(COLOR_TOOL, false) }
                val k = hslBand * 3
                body.addView(text("ปรับเฉพาะสี${names[hslBand]}ในภาพ", 11.5f, Glass.TEXT3).apply { gravity = Gravity.CENTER }, -1, -2)
                slider("เฉดสี", grade.hsl[k], -100, rainbow) { grade.hsl[k] = it }
                slider("ความอิ่มสี", grade.hsl[k + 1], -100, dim) { grade.hsl[k + 1] = it }
                slider("ความสว่าง", grade.hsl[k + 2], -100, dim) { grade.hsl[k + 2] = it }
            }
            else -> {
                // A LUT (.cube file) is a ready-made colour table from another app or a colourist.
                val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
                row.addView(EditorKit.chip(this, if (grade.lut == null) "นำเข้าไฟล์ .cube" else "เปลี่ยน LUT", ADD) { pickLut.launch(arrayOf("*/*")) }, LinearLayout.LayoutParams(-2, dp(36)).apply { setMargins(dp(4), 0, dp(4), 0) })
                if (grade.lut != null) row.addView(EditorKit.chip(this, "เอาออก", Icons.CLOSE) { grade.lut = null; lutPath = ""; lutName = ""; applyLook(); showTool(COLOR_TOOL, false) }, LinearLayout.LayoutParams(-2, dp(36)).apply { setMargins(dp(4), 0, dp(4), 0) })
                body.addView(row, -1, -2)
                body.addView(text(if (grade.lut == null) "รองรับ .cube แบบ 3D ขนาดไม่เกิน 33" else lutName, 12f, Glass.TEXT2, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(4)) }, -1, -2)
                if (grade.lut != null) slider("ความแรง", grade.lutAmount, 0, dim) { grade.lutAmount = it }
            }
        }
        handler.removeCallbacks(histTick); handler.post(histTick)
    }
    private val pickLut = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        luma.readIo.execute {
            val result = runCatching {
                val name = (contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "lut.cube").replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
                val dir = java.io.File(filesDir, "studio/luts").apply { mkdirs() }; val f = java.io.File(dir, System.currentTimeMillis().toString(36) + "_" + name)
                contentResolver.openInputStream(uri)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
                val table = f.reader().use { CubeLut.read(it) }
                Triple(f.path, name, table)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { (path, name, table) -> grade.lut = table; grade.lutAmount = 1f; lutPath = path; lutName = name; applyLook(); showTool(COLOR_TOOL, false) }
                    .onFailure { notice("อ่านไฟล์ LUT ไม่ได้ · " + (it.message ?: "ต้องเป็นไฟล์ .cube")) }
            }
        }
    }

    // ---------- Tools ----------
    private fun showTool(i: Int, animate: Boolean = true) {
        val dir = if (animate) i.compareTo(tool) else 0
        tool = i; toolBar.select(i, animate)
        // Cut, split-into-two-files and speed belong to the first clip, so the preview goes back to it.
        if (i in 0..2 && playIndex != 0 && !switching) playClip(0, false)
        // The layer lanes need room; the preview gives way smoothly.
        if (i != COLOR_TOOL) { handler.removeCallbacks(histTick); hist = null }
        val want = dp(if (i == LAYER_TOOL) 276 else if (i == COLOR_TOOL) 268 else if (i == 9) 236 else if (i == 2 && speed < 1f) 150 else 124)
        if (tools.layoutParams.height != want) {
            if (!animate || Glass.reduced(this)) { tools.layoutParams.height = want; tools.requestLayout() }
            else android.animation.ValueAnimator.ofInt(tools.layoutParams.height, want).apply { duration = 260; interpolator = Glass.EASE
                addUpdateListener { tools.layoutParams.height = it.animatedValue as Int; tools.requestLayout() } }.start()
        }
        if (i != LAYER_TOOL) lanes = null
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        fun note(s: String) = col.addView(text(s, 12f, Glass.TEXT2, true).apply { maxLines = 1; gravity = Gravity.CENTER; setPadding(dp(16), 0, dp(16), dp(6)) }, -1, -2)
        when (i) {
            0 -> { if (cutValid()) note("ตัดช่วง ${clock(cutFrom - start)}–${clock(cutTo - start)} ออก")
                // One row that scrolls sideways: two rows of buttons did not fit above the tool bar.
                col.addView(EditorKit.actionRow(this, listOf(
                    EditorKit.action(this, EditorKit.START, "เริ่มที่นี่") { setRange(trim.playhead, end) },
                    EditorKit.action(this, EditorKit.END, "จบที่นี่") { setRange(start, trim.playhead) },
                    EditorKit.action(this, Icons.RESET, "ทั้งคลิป") { setRange(0, duration); cutFrom = -1; cutTo = -1; showTool(0, false) },
                    // Take a middle part out: mark where it starts and ends with the playhead.
                    EditorKit.action(this, CUT_IN, "เริ่มตัดออก", cutFrom >= 0) { cutFrom = trim.playhead; if (cutTo <= cutFrom) cutTo = -1; split = -1; trim.split = -1; showTool(0, false) },
                    EditorKit.action(this, CUT_OUT, "จบตัดออก", cutValid()) { if (cutFrom < 0 || trim.playhead - cutFrom < 300) notice("เลื่อนไปหลังจุดเริ่มตัดออกก่อน") else { cutTo = trim.playhead; split = -1; trim.split = -1; showTime(); showTool(0, false) } },
                    EditorKit.action(this, Icons.CLOSE, "ไม่ตัดออก") { cutFrom = -1; cutTo = -1; showTool(0, false) })), -1, -2) }
            1 -> { if (split >= 0) note("2 คลิป · แยกที่ ${clock(split - start)}")
                col.addView(EditorKit.actions(this, listOf(
                    EditorKit.action(this, EditorKit.SPLIT, "แยกที่นี่", split >= 0) {
                        val at = trim.playhead; if (at - start < 300 || end - at < 300) notice("เลื่อนให้ห่างจากขอบช่วงอย่างน้อย 0.3 วินาที") else { split = at; trim.split = split; cutFrom = -1; cutTo = -1; showTime(); showTool(1, false) } },
                    EditorKit.action(this, Icons.CLOSE, "ไม่แยก") { split = -1; trim.split = -1; showTime(); showTool(1, false) })), -1, -2) }
            2 -> speedTools(col)
            3 -> { val items = CROPS.map { (name, _) -> EditorKit.StripItem(this, if (name == "ต้นฉบับ") Icons.VIDEO else aspectIconPath(name), name) }
                col.addView(EditorKit.strip(this, items.map { it to 64 }, cropIndex, 60, { k -> items.forEachIndexed { j, v -> v.lit(j == k) } }) { k -> cropIndex = k; layoutVideo() },
                    LinearLayout.LayoutParams(-1, dp(70))) }
            4 -> textTools(col)
            5 -> filmTools(col)
            6 -> musicTools(col)
            7 -> adjustTools(col)
            COLOR_TOOL -> colourTools(col)
            8 -> col.addView(EditorKit.actions(this, listOf(
                    EditorKit.action(this, EditorKit.ROTATE, "หมุน 90°") { turns = (turns + 1) % 4; layoutVideo()
                        if (!Glass.reduced(this)) { texture.rotation = texture.rotation - 90f; texture.animate().rotation(turns * 90f).setInterpolator(Glass.EASE).setDuration(320).start() } },
                    EditorKit.action(this, Icons.MIRROR, "กลับด้าน", mirror) { mirror = !mirror; layoutVideo(); showTool(8, false) },
                    EditorKit.action(this, Icons.RESET, "คืนค่า") { turns = 0; mirror = false; layoutVideo(); showTool(8, false) })), -1, -2)
            9 -> soundTools(col)
            10 -> clipTools(col)
            11 -> transitionTools(col)
            12 -> stickerTools(col)
            else -> layerTools(col)
        }
        EditorKit.swap(tools, col, dir)
    }
    /** Speed as one slider, like the photo adjustments: 0.1× (slow) through 1× to 500× (fast). */
    private fun speedTools(col: LinearLayout) {
        val value = text(speedName(speed), 17f, if (speed == 1f) Color.WHITE else Glass.ACCENT, true).apply { gravity = Gravity.CENTER }
        val note = text("", 11.5f, Glass.TEXT2, true).apply { gravity = Gravity.CENTER; maxLines = 1 }
        fun describe(v: Float) {
            value.text = speedName(v); value.setTextColor(if (v == 1f) Color.WHITE else Glass.ACCENT)
            note.text = when {
                v == 1f -> "ความเร็วปกติ"
                hasAudio && (v < SOUND_MIN || v > SOUND_MAX) -> "ช้ากว่า 0.25× หรือเร็วกว่า 4× จะไม่มีเสียง"
                v < 1f -> "ช้าลง · ได้คลิป ${clock(((end - start) / v).toLong())}"
                else -> "เร็วขึ้น · ได้คลิป ${clock(((end - start) / v).toLong())}"
            }
        }
        val slider = GlassSlider(this).apply {
            values = SPEEDS.map { speedName(it) }; index = SPEEDS.indexOfFirst { it >= speed }.coerceAtLeast(0)
            grad = intArrayOf(0xff64d2ff.toInt(), Color.WHITE, Glass.ACCENT); contentDescription = "ความเร็ววิดีโอ"
            onChange = { k ->
                var v = SPEEDS[k]
                // Keep at least one second of video.
                if ((end - start) / v < 1000f) { v = SPEEDS.last { (end - start) / it >= 1000f || it == 1f }; index = SPEEDS.indexOf(v); notice("คลิปช่วงนี้เร่งได้สูงสุด ${speedName(v)}") }
                if ((v == 1f) != (speed == 1f) && v == 1f) Glass.tick(this, android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                speed = v; describe(v); showTime()
                player?.let { p -> if (p.isPlaying && playIndex == 0) runCatching { p.playbackParams = p.playbackParams.setSpeed(v.coerceIn(0.1f, 8f)) } }
            }
        }
        val presets = LinearLayout(this).apply { gravity = Gravity.CENTER }
        listOf(0.1f, 0.5f, 1f, 2f, 10f, 100f, 500f).forEach { p ->
            presets.addView(EditorKit.chip(this, speedName(p), "", speed == p) { slider.index = SPEEDS.indexOf(p); slider.onChange(SPEEDS.indexOf(p)); showTool(2, false) },
                LinearLayout.LayoutParams(-2, dp(30)).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        col.addView(LinearLayout(this).apply { gravity = Gravity.CENTER; addView(value); addView(note.apply { setPadding(dp(10), 0, 0, 0) }) }, -1, -2)
        col.addView(slider, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(dp(12), 0, dp(12), 0) })
        col.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(presets) }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        describe(speed)
        // Below 1×: optionally make new in-between frames by optical flow, so slow motion runs smoothly.
        if (speed < 1f) col.addView(EditorKit.chip(this, if (smooth) "สโลว์ลื่น (Optical flow) · เปิด" else "สโลว์ลื่น (Optical flow) · ปิด", SMOOTH, smooth) {
            smooth = !smooth; showTool(2, false)
            if (smooth) notice("สร้างเฟรมแทรก ${SlowFlow.factor(speed) - 1} เฟรมต่อเฟรมตอนส่งออก · ใช้เวลานานขึ้น · ไม่มีเสียงคลิป")
        }, LinearLayout.LayoutParams(-2, dp(32)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(4) })
    }
    private fun speedName(s: Float) = (if (s == s.toInt().toFloat()) "${s.toInt()}" else "$s") + "×"
    private fun setRange(s: Long, e: Long) {
        if (e - s < 500) { notice("ช่วงสั้นเกินไป"); return }
        start = s; end = e; if (split !in (s + 300)..(e - 300)) split = -1
        trim.start = s; trim.end = e; trim.split = split; seek(s)
    }

    private fun textTools(col: LinearLayout) {
        val field = EditText(this).apply {
            hint = "พิมพ์ข้อความ"; setHintTextColor(Glass.TEXT3); setTextColor(Color.WHITE); textSize = 15f; setSingleLine(); setText(caption)
            background = glass(20, 0x24ffffff); setPadding(dp(14), 0, dp(14), 0); contentDescription = "ข้อความบนวิดีโอ"
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { caption = s.toString(); applyLook() }
            })
        }
        col.addView(field, LinearLayout.LayoutParams(-1, dp(44)).apply { setMargins(dp(16), 0, dp(16), dp(8)) })
        // Place, size and colour as small icon buttons in one row.
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), 0) }
        fun small(icon: String, desc: String, on: Boolean, click: () -> Unit) = row.addView(FrameLayout(this).apply {
            background = glass(20, if (on) Glass.ACCENT_SOFT else 0x26ffffff); contentDescription = desc; isClickable = true; isSelected = on; glassTouch(); setOnClickListener { click() }
            addView(IconView(this@VideoEditorActivity, icon, 2f).apply { tint = if (on) Glass.ACCENT else Color.WHITE }, FrameLayout.LayoutParams(dp(19), dp(19), Gravity.CENTER)) },
            LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(6) })
        listOf(EditorKit.TOP to "ข้อความด้านบน", EditorKit.MIDDLE to "ข้อความตรงกลาง", EditorKit.BOTTOM to "ข้อความด้านล่าง").forEachIndexed { k, (icon, desc) -> small(icon, desc, k == place) { place = k; layoutVideo(); showTool(4, false) } }
        small(EditorKit.TEXT, "ตัวใหญ่", textBig) { textBig = !textBig; layoutVideo(); showTool(4, false) }
        small("M4 6h16v12H4z M8 10h8 M8 14h5", "กล่องพื้นหลัง", textBox) { textBox = !textBox; applyLook(); showTool(4, false) }
        row.addView(View(this), LinearLayout.LayoutParams(dp(4), 1))
        TEXT_COLORS.forEach { color -> row.addView(View(this).apply { isClickable = true; contentDescription = "สีข้อความ"; setOnClickListener { Glass.tick(this); textColor = color; applyLook(); showTool(4, false) }
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setStroke(dp(if (color == textColor) 3 else 1), if (color == textColor) Color.WHITE else 0x66ffffff) } },
            LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(6) }) }
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }, -1, -2)
    }

    private fun filmTools(col: LinearLayout) {
        val looks = listOf<Film?>(null) + Films.groups.flatMap { Films.inGroup(it) }
        val cards = looks.map { EditorKit.FilmCard(this, firstFrame, it) }
        val strength = GlassSlider(this).apply { values = (0..100).map { "$it%" }; index = (filmStrength * 100).toInt(); grad = intArrayOf(0xff8e8e93.toInt(), Glass.ACCENT); contentDescription = "ความเข้มฟิลเตอร์" }
        fun mark(k: Int) { cards.forEachIndexed { j, c -> c.lit(j == k) }; strength.animate().alpha(if (looks[k] == null) 0f else 1f).setDuration(180).start(); strength.isEnabled = looks[k] != null }
        val start = looks.indexOfFirst { it?.id == film?.id }.coerceAtLeast(0)
        col.addView(EditorKit.strip(this, cards.map { it to 70 }, start, 88, { k -> mark(k); if (film?.id != looks[k]?.id) { film = looks[k]; applyLook() } }) { k -> film = looks[k]; mark(k); applyLook() },
            LinearLayout.LayoutParams(-1, dp(92)))
        strength.onChange = { i -> filmStrength = i / 100f; applyLook() }
        col.addView(strength, LinearLayout.LayoutParams(-1, dp(32)).apply { setMargins(dp(12), 0, dp(12), 0) })
        mark(start)
    }

    private val CUT_IN = "M6 4v16 M10 8l4 4-4 4 M18 4v16"
    private val CUT_OUT = "M6 4v16 M14 8l-4 4 4 4 M18 4v16"
    private fun sign(v: Float) = (v * 100).roundToInt().let { if (it > 0) "+$it" else "$it" }
    /** Light and colour for the whole clip: dials to choose, a slider to set (shown live on the preview). */
    private fun adjustTools(col: LinearLayout) {
        val value = text("", 13f, Glass.TEXT2, true).apply { gravity = Gravity.CENTER }
        // The six colour adjustments run from -100 to +100; the three finishing effects (dark edges, grain, sharpening) from 0 to 100.
        val names = EditorKit.ADJUSTMENTS.dropLast(1) + listOf("ขอบมืด", "เกรนฟิล์ม", "คมชัด")
        val icons = EditorKit.ADJUST_ICONS.take(6) + listOf(EditorKit.ADJUST_ICONS[6], "M6 7h.01 M12 5h.01 M18 8h.01 M8 13h.01 M15 12h.01 M6 18h.01 M12 17h.01 M18 16h.01", "M12 4l8 16H4z")
        fun get(k: Int) = if (k < 6) adjust[k] else fx[k - 6]
        fun set(k: Int, v: Float) { if (k < 6) adjust[k] = v else fx[k - 6] = v.coerceAtLeast(0f) }
        val dials = names.mapIndexed { k, n -> EditorKit.Dial(this, icons[k], n).apply { amount = get(k) } }
        val slider = GlassSlider(this).apply { values = (-100..100).map { if (it > 0) "+$it" else "$it" }; grad = intArrayOf(0xff8e8e93.toInt(), Color.WHITE, Glass.ACCENT) }
        fun show(k: Int) { adjustIndex = k; dials.forEachIndexed { j, d -> d.lit = j == k }; value.text = "${names[k]}  ${sign(get(k))}"; slider.index = (get(k) * 100).roundToInt() + 100; slider.contentDescription = names[k] }
        val head = FrameLayout(this)
        head.addView(value, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        // Hold to see the picture without any adjustment, let go to see it with them again.
        head.addView(text("กดค้างดูต้นฉบับ", 11f, Glass.TEXT2, true).apply { gravity = Gravity.CENTER; background = glass(14, 0x26ffffff); setPadding(dp(10), 0, dp(10), 0); contentDescription = "กดค้างเพื่อดูภาพก่อนปรับ"
            setOnTouchListener { v, e -> when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { comparing = true; applyLook(); v.alpha = 0.6f }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { comparing = false; applyLook(); v.alpha = 1f } }; true } }, FrameLayout.LayoutParams(-2, dp(28), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(12) })
        col.addView(head, -1, -2)
        col.addView(EditorKit.strip(this, dials.map { it to 58 }, adjustIndex.coerceIn(0, names.size - 1), 0, { k -> show(k) },
            tapAgain = { k -> set(k, 0f); dials[k].amount = 0f; show(k); applyLook() }) { k -> show(k) }, LinearLayout.LayoutParams(-1, dp(56)))
        slider.onChange = { i -> set(adjustIndex, (i - 100) / 100f); dials[adjustIndex].amount = get(adjustIndex); value.text = "${names[adjustIndex]}  ${sign(get(adjustIndex))}"; applyLook() }
        col.addView(slider, LinearLayout.LayoutParams(-1, dp(40)).apply { setMargins(dp(12), 0, dp(12), 0) })
        show(adjustIndex.coerceIn(0, names.size - 1))
    }
    /** Loudness of the clip's sound and of the music, 0–200 %. */
    private fun soundTools(col: LinearLayout) {
        col.gravity = Gravity.TOP
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(box) }, LinearLayout.LayoutParams(-1, 0, 1f))
        fun row(icon: String, label: String, steps: List<String>, at: Int, enabled: Boolean, set: (Int) -> Unit) {
            val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(12), 0); alpha = if (enabled) 1f else 0.4f }
            r.addView(IconView(this, icon, 2f).apply { tint = Glass.TEXT2 }, dp(20), dp(20))
            r.addView(text(label, 12f, Glass.TEXT2, true).apply { setPadding(dp(8), 0, 0, 0) }, dp(84), -2)
            r.addView(GlassSlider(this).apply { values = steps; index = at; grad = intArrayOf(0xff8e8e93.toInt(), Glass.ACCENT); contentDescription = label; isEnabled = enabled
                onChange = { i -> set(i); applySound() } }, LinearLayout.LayoutParams(0, dp(40), 1f))
            box.addView(r, -1, -2)
        }
        val pct = (0..200 step 5).map { "$it%" }
        row(Icons.SOUND, "เสียงคลิปแรก", pct, (volume * 20).roundToInt(), hasAudio && keepSound) { volume = it / 20f }
        row(EditorKit.MUSIC, "เพลง", pct, (musicVolume * 20).roundToInt(), music != null) { musicVolume = it / 20f }
        val db = (-12..12).map { if (it > 0) "+$it dB" else "$it dB" }
        listOf("เสียงทุ้ม", "เสียงกลาง", "เสียงแหลม").forEachIndexed { k, n -> row("M4 18V9 M12 18V5 M20 18v-7 M2 9h4 M10 5h4 M18 11h4", n, db, eq[k].roundToInt() + 12, keepSound) { eq[k] = (it - 12).toFloat() } }
        val toggles = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(6), dp(8), dp(4)) }
        fun toggle(label: String, on: Boolean, f: () -> Unit) = toggles.addView(text(label, 12f, if (on) Glass.ACCENT else Color.WHITE, on).apply { gravity = Gravity.CENTER; background = glass(16, if (on) Glass.ACCENT_SOFT else 0x26ffffff)
            setPadding(dp(12), 0, dp(12), 0); isClickable = true; glassTouch(); contentDescription = label; setOnClickListener { f(); applySound(); showTool(9, false) } }, LinearLayout.LayoutParams(-2, dp(34)).apply { setMargins(dp(3), 0, dp(3), 0) })
        toggle("ตัดเสียงลม/หึ่ง", lowCut) { lowCut = !lowCut }
        toggle("เสียงพูดชัด", voice) { voice = !voice }
        toggle("ลดเพลงตอนมีเสียงพูด", duck) { duck = !duck }
        box.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(toggles) }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        box.addView(text("ตัดเสียงลมและเสียงพูดชัดมีผลในไฟล์ที่ส่งออก · พรีวิวได้ยินเฉพาะอีควอไลเซอร์และการลดเพลง", 11f, Glass.TEXT3).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(2), dp(16), dp(8)) }, -1, -2)
    }
    private fun musicTools(col: LinearLayout) {
        if (music != null) col.addView(text(musicName, 12f, Glass.TEXT2, true).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setPadding(dp(16), 0, dp(16), dp(6)) }, -1, -2)
        val items = mutableListOf<View>(EditorKit.action(this, EditorKit.MUSIC, if (music == null) "เลือกเพลง" else "เปลี่ยนเพลง", music != null) { pickMusic.launch(arrayOf("audio/*")) })
        if (hasAudio) items += EditorKit.action(this, Icons.SOUND, "เสียงคลิป", keepSound) { keepSound = !keepSound; applySound(); showTool(6, false) }
        if (music != null) items += EditorKit.action(this, Icons.CLOSE, "ลบเพลง") { music = null; musicName = ""; musicPlayer?.runCatching { release() }; musicPlayer = null; showTool(6, false) }
        col.addView(EditorKit.actions(this, items), -1, -2)
    }

    private fun restoreGrade(s: Bundle) {
        val keep = StudioGrade().also { it.copyFrom(grade) }
        grade.reset()
        s.getFloatArray("curves")?.takeIf { it.size == 20 }?.copyInto(grade.curves); s.getFloatArray("hsl")?.takeIf { it.size == 24 }?.copyInto(grade.hsl); s.getFloatArray("wheels")?.takeIf { it.size == 9 }?.copyInto(grade.wheels)
        grade.lutAmount = s.getFloat("lutAmount", 1f)
        val path = s.getString("lutPath").orEmpty(); lutName = s.getString("lutName").orEmpty()
        // The same LUT file as before is kept as it is; only a different file is read again.
        grade.lut = if (path.isEmpty()) null else if (path == lutPath && keep.lut != null) keep.lut else runCatching { java.io.File(path).reader().use { CubeLut.read(it) } }.getOrNull()
        lutPath = if (grade.lut == null) "" else path
    }
    private fun changed() = start != 0L || end != duration || split >= 0 || speed != 1f || cropIndex != 0 || caption.isNotBlank() || film != null || music != null || !keepSound || looked() || turns != 0 || mirror || volume != 1f || cutValid() || extras.isNotEmpty() || stickers.isNotEmpty() || layers.isNotEmpty() || markers.isNotEmpty() || fx.any { it > 0f } || grade.active() || audioSettings().active || mainMuted

    // ---------- Clips, transitions, stickers ----------
    /** The clips in playing order: the main one, then the added ones, which can be moved or removed. */
    private fun clipTools(col: LinearLayout) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), 0) }
        fun card(thumb: Bitmap?, label: String, on: Boolean, click: () -> Unit) = FrameLayout(this).apply {
            background = glass(14, if (on) Glass.ACCENT_SOFT else 0x26ffffff); setPadding(dp(3), dp(3), dp(3), dp(3)); isClickable = true; contentDescription = label; glassTouch(); setOnClickListener { click() }
            addView(ImageView(this@VideoEditorActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(thumb); clipToOutline = true; background = glass(11, 0xff151d33.toInt()) }, -1, -1)
            addView(text(label, 10.5f, Color.WHITE, true).apply { setShadowLayer(4f, 0f, 1f, 0xcc000000.toInt()); setPadding(dp(5), 0, dp(5), dp(3)) }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
        }
        order().forEachIndexed { n, k ->
            if (k == 0) row.addView(card(firstFrame, "${n + 1} · " + clock(mainLen()), playIndex == 0) { playClip(0, false); showTool(10, false) }, LinearLayout.LayoutParams(dp(76), dp(56)).apply { rightMargin = dp(6) })
            else extras[k - 1].let { e -> row.addView(card(e.thumb, "${n + 1} · " + clock(e.len) + (if (e.speed != 1f) " · ${speedName(e.speed)}" else ""), playIndex == k) { playClip(k, false); showTool(10, false) }, LinearLayout.LayoutParams(dp(76), dp(56)).apply { rightMargin = dp(6) }) } }
        row.addView(FrameLayout(this).apply { background = glass(14, 0x26ffffff); isClickable = true; contentDescription = "เพิ่มคลิป"; glassTouch(); setOnClickListener { pickClip.launch(arrayOf("video/*")) }
            addView(IconView(this@VideoEditorActivity, ADD, 2f), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)) }, LinearLayout.LayoutParams(dp(56), dp(56)))
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }, -1, -2)
        val chosen = playIndex - 1
        val copyIcon = "M8 8h11v11H8z M5 16V5h11"
        fun go(k: Int) { playIndex = k.coerceIn(0, extras.size); showClipOnBar(); playClip(playIndex, false); showTime(); showTool(10, false) }
        if (chosen in extras.indices) { val e = extras[chosen]
            col.addView(EditorKit.actionRow(this, listOf(
                EditorKit.action(this, EditorKit.SPLIT, "แยกตรงนี้") { splitAtPlayhead() },
                EditorKit.action(this, copyIcon, "ทำสำเนา") { extras.add(chosen + 1, e.copy()); if (chosen < mainPos()) mainAt = mainPos() + 1; go(chosen + 2) },
                EditorKit.action(this, EditorKit.SPEED, "ความเร็ว", e.speed != 1f) {
                    val speeds = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)
                    android.app.AlertDialog.Builder(this).setTitle("ความเร็วของคลิปนี้").setSingleChoiceItems(speeds.map { speedName(it) }.toTypedArray(), speeds.indexOfFirst { it == e.speed }) { d, w ->
                        e.speed = speeds[w]; d.dismiss(); showTime(); showTool(10, false) }.show() },
                // "Earlier" and "later" follow the playing order, so a clip can also step over the clip the editor was opened with.
                EditorKit.action(this, LEFT, "เลื่อนก่อน") { if (chosen == mainPos()) mainAt = mainPos() + 1 else if (chosen > 0) { extras.add(chosen - 1, extras.removeAt(chosen)); playIndex = chosen }; showTime(); showTool(10, false) },
                EditorKit.action(this, RIGHT, "เลื่อนหลัง") { if (chosen == mainPos() - 1) mainAt = mainPos() - 1 else if (chosen < extras.size - 1) { extras.add(chosen + 1, extras.removeAt(chosen)); playIndex = chosen + 2 }; showTime(); showTool(10, false) },
                EditorKit.action(this, Icons.SOUND, if (e.muted) "เปิดเสียง" else "ปิดเสียง", e.muted) { setMuted(chosen + 1, !e.muted); showTool(10, false) },
                EditorKit.action(this, "M4 12h6 M14 12h6 M10 8v8 M14 8v8", "แยกเสียง") { detachSound(); showTool(10, false) },
                EditorKit.action(this, Icons.CLOSE, "ลบ") { extras.removeAt(chosen); if (chosen < mainPos()) mainAt = mainPos() - 1; go(if (chosen < extras.size) chosen + 1 else chosen) })), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        } else col.addView(EditorKit.actionRow(this, listOf(
            // Splitting the first clip turns its second half into a clip of its own, which can then be moved, trimmed or removed.
            EditorKit.action(this, EditorKit.SPLIT, "แยกตรงนี้") { splitAtPlayhead() },
            EditorKit.action(this, copyIcon, "ทำสำเนา") {
                if (speed != 1f || cutValid()) notice("สำเนาของคลิปนี้จะไม่มีความเร็วและการตัดช่วงกลาง")
                extraCache.getOrPut(source.toString()) { Extra(source, duration, videoW, videoH, "คลิปแรก", firstFrame) }
                extras.add(mainPos(), Extra(source, duration, videoW, videoH, "คลิปแรก", firstFrame).also { it.from = start; it.to = end }); go(mainPos() + 1) },
            EditorKit.action(this, LEFT, "เลื่อนก่อน") { if (mainPos() > 0) mainAt = mainPos() - 1; showTime(); showTool(10, false) },
            EditorKit.action(this, RIGHT, "เลื่อนหลัง") { if (mainPos() < extras.size) mainAt = mainPos() + 1; showTime(); showTool(10, false) },
            EditorKit.action(this, Icons.SOUND, if (mainMuted) "เปิดเสียง" else "ปิดเสียง", mainMuted) { setMuted(0, !mainMuted); showTool(10, false) },
            EditorKit.action(this, "M4 12h6 M14 12h6 M10 8v8 M14 8v8", "แยกเสียง") { detachSound(); showTool(10, false) })), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
    }
    /** Cuts the clip on screen in two at the playhead. The two halves stay next to each other and can then be moved, trimmed or removed separately. */
    private fun splitAtPlayhead() {
        val at = trim.playhead; val c = playIndex - 1; val e = extras.getOrNull(c)
        if (e != null) {
            if (at - e.from < 300 || e.to - at < 300) { notice("เลื่อนหัวอ่านให้ห่างจากขอบคลิปอย่างน้อย 0.3 วินาที"); return }
            val second = e.copy().also { it.from = at }; e.to = at; extras.add(c + 1, second); if (c < mainPos()) mainAt = mainPos() + 1
        } else {
            if (speed != 1f || cutValid()) { notice("คลิปนี้ปรับความเร็วหรือตัดช่วงกลางออกแล้ว จึงแยกเป็นสองคลิปไม่ได้"); return }
            if (at - start < 300 || end - at < 300) { notice("เลื่อนหัวอ่านให้ห่างจากขอบคลิปอย่างน้อย 0.3 วินาที"); return }
            extraCache.getOrPut(source.toString()) { Extra(source, duration, videoW, videoH, "คลิปแรก", firstFrame) }
            extras.add(mainPos(), Extra(source, duration, videoW, videoH, "คลิปแรก", firstFrame).also { it.from = at; it.to = end })
            end = at; split = -1
        }
        Glass.tick(timeline); showClipOnBar(); trim.playhead = trim.playhead.coerceIn(trim.start, trim.end); showTime(); if (tool == 10) showTool(10, false)
    }
    private fun transitionTools(col: LinearLayout) {
        val icons = listOf(EditorKit.SCISSORS, "M4 12h16 M4 6h16 M4 18h16", "M12 3v4 M12 17v4 M3 12h4 M17 12h4 M6 6l2.5 2.5 M15.5 15.5L18 18 M18 6l-2.5 2.5 M8.5 15.5L6 18", "M10 4H4v6 M14 4h6v6 M10 20H4v-6 M14 20h6v-6", "M4 12h12 M12 7l5 5-5 5 M20 5v14", "M4 4h8v16H4z M15 4v16 M19 4v16", "M12 4a8 8 0 1 0 0 16 8 8 0 0 0 0-16 M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8")
        val items = TRANSITIONS.mapIndexed { i, n -> EditorKit.StripItem(this, icons[i], n) }
        if (extras.isEmpty()) col.addView(text("ทรานซิชันอยู่ระหว่างคลิป · เพิ่มคลิปที่แท็บคลิปก่อน", 11.5f, Glass.TEXT3).apply { gravity = Gravity.CENTER; setPadding(dp(16), 0, dp(16), dp(6)) }, -1, -2)
        col.addView(EditorKit.strip(this, items.map { it to 70 }, transition, 60, { k -> items.forEachIndexed { j, v -> v.lit(j == k) } }) { k -> transition = k },
            LinearLayout.LayoutParams(-1, dp(70)))
    }
    /** Emoji stickers: tap one to put it on the picture, drag it there to move it, tap it to make it bigger or smaller. */
    private fun stickerTools(col: LinearLayout) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), 0, dp(10), 0) }
        STICKERS.forEach { e -> row.addView(text(e, 24f).apply { gravity = Gravity.CENTER; isClickable = true; contentDescription = "สติกเกอร์ $e"; background = glass(20, 0x1affffff); glassTouch()
            setOnClickListener { stickers += VideoSticker(e, 0.5f + (stickers.size % 3 - 1) * 0.12f, 0.45f, false); rebuildStickers(); showTool(12, false) } }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(6) }) }
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }, -1, -2)
        if (stickers.isEmpty()) col.addView(text("ลากสติกเกอร์บนภาพเพื่อย้าย · แตะเพื่อปรับขนาด", 11.5f, Glass.TEXT3).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }, -1, -2)
        else col.addView(EditorKit.actions(this, listOf(
            EditorKit.action(this, "M9 14L4 9l5-5 M4 9h10a6 6 0 0 1 0 12h-3", "ลบอันล่าสุด") { stickers.removeAt(stickers.size - 1); rebuildStickers(); showTool(12, false) },
            EditorKit.action(this, Icons.CLOSE, "ลบทั้งหมด") { stickers.clear(); rebuildStickers(); showTool(12, false) })), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
    }
    private fun rebuildStickers() {
        stickerViews.forEach { stage.removeView(it) }; stickerViews.clear()
        stickers.forEachIndexed { i, k ->
            val v = text(k.text, 20f).apply { gravity = Gravity.CENTER; includeFontPadding = false; contentDescription = "สติกเกอร์ ${k.text} ลากเพื่อย้าย แตะเพื่อปรับขนาด" }
            var dx = 0f; var dy = 0f; var moved = false
            v.setOnTouchListener { view, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; moved = false; view.parent?.requestDisallowInterceptTouchEvent(true) }
                    MotionEvent.ACTION_MOVE -> {
                        if (abs(e.rawX - dx) + abs(e.rawY - dy) > dp(6)) moved = true
                        if (moved && visible.width() > 0) {
                            val s = stickers[i]
                            stickers[i] = s.copy(x = (s.x + (e.rawX - dx) / visible.width()).coerceIn(0f, 1f), y = (s.y + (e.rawY - dy) / visible.height()).coerceIn(0f, 1f))
                            dx = e.rawX; dy = e.rawY; placeStickers()
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) { stickers[i] = stickers[i].copy(big = !stickers[i].big); Glass.tick(view); placeStickers() }
                }
                true
            }
            stage.addView(v, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START)); stickerViews += v
        }
        stage.post { placeStickers() }
    }
    /** Same size and place as in the export: a share of the kept frame's height, centred on the sticker's point. */
    private fun placeStickers() {
        if (visible.isEmpty) return
        stickerViews.forEachIndexed { i, v ->
            val k = stickers.getOrNull(i) ?: return@forEachIndexed
            v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, (if (k.big) VideoEdit.STICKER_BIG else VideoEdit.STICKER) * visible.height() * 0.8f)
            v.post { v.translationX = visible.left + k.x * visible.width() - v.width / 2f; v.translationY = visible.top + k.y * visible.height() - v.height / 2f }
        }
    }
    // ---------- Layers ----------
    private fun cutMs() = if (cutValid()) cutTo - cutFrom else 0L
    /** Length of the finished video in ms (main clip after cut and speed, then the added clips). */
    private fun totalOut() = ((end - start - cutMs()) / speed).toLong() + extras.sumOf { it.len }
    /** Where the preview is, in ms of the finished video. */
    private fun outNow(): Long {
        var before = 0L
        for (k in order()) { if (k == playIndex) break; before += lenOf(k) }
        val e = extras.getOrNull(playIndex - 1)
        if (e != null) return before + ((trim.playhead - e.from).coerceAtLeast(0) / e.speed).toLong()
        var t = trim.playhead - start
        if (cutValid() && trim.playhead >= cutTo) t -= cutMs()
        return before + (t.coerceAtLeast(0) / speed).toLong()
    }
    private fun clampedLayers(): List<VideoLayer> { val total = totalOut()
        return layers.filter { !it.hidden }.mapNotNull { l -> val at = l.at.coerceIn(0, (total - 200).coerceAtLeast(0)); val len = l.length.coerceAtMost(total - at); if (len < 200) null else l.copy(at = at, length = len) } }
    private fun layerName(l: VideoLayer) = if (l.kind == VideoLayer.TEXT) l.text.replace('\n', ' ') else when (l.kind) { VideoLayer.VIDEO -> "วิดีโอ"; VideoLayer.IMAGE -> "รูป"; else -> "เสียง" } + " · " + clock(l.length)
    /** Changes where a layer is, how big or how solid. With keyframes the change is written as a keyframe at the playhead; without, it changes the whole layer. */
    private fun posed(l: VideoLayer, x: Float? = null, y: Float? = null, size: Float? = null, opacity: Float? = null): VideoLayer {
        if (l.keys.isEmpty()) return l.copy(x = x ?: l.x, y = y ?: l.y, size = size ?: l.size, opacity = opacity ?: l.opacity)
        val now = outNow().coerceIn(l.at, l.end); val t = now - l.at; val cur = l.rawPose(now)
        return l.withKeys(l.keyList.filter { abs(it.t - t) > 120 } + VideoLayer.Key(t, x ?: cur[0], y ?: cur[1], size ?: cur[2], opacity ?: cur[3]))
    }
    private fun askText(initial: String, done: (String) -> Unit) {
        val field = EditText(this).apply { setText(initial); setSelection(initial.length); hint = "พิมพ์ข้อความ"; minLines = 2; maxLines = 5 }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(field) }
        val dialog = android.app.AlertDialog.Builder(this).setTitle("ข้อความ").setView(box)
            .setPositiveButton("ตกลง") { _, _ -> field.text.toString().trim().takeIf { it.isNotEmpty() }?.let(done) }.setNegativeButton("ยกเลิก", null).create()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE); dialog.show(); field.requestFocus()
    }
    private fun addText(words: String) {
        val draft = VideoLayer(VideoLayer.TEXT, VideoLayer.textSource(words, Color.WHITE, 0), 0L, 3000L, text = words)
        val pic = runCatching { VideoLayers.textPicture(draft, TEXT_PREVIEW) }.getOrNull() ?: return
        layerThumbs[draft.source] = pic
        val total = totalOut(); val len = 3000L.coerceAtMost(total).coerceAtLeast(200); val at = outNow().coerceAtMost((total - len).coerceAtLeast(0))
        layers += draft.copy(at = at, length = len, x = 0.5f, y = 0.8f, size = (pic.width.toFloat() / TEXT_PREVIEW * 0.9f).coerceIn(0.12f, 0.95f), aspect = pic.width.toFloat() / pic.height)
        layerPick = layers.size - 1; rebuildLayers(); showTool(LAYER_TOOL, false)
        notice("เพิ่มข้อความแล้ว · ลากบนภาพเพื่อย้าย ลากแถบสีเหลืองบนไทม์ไลน์เพื่อกำหนดช่วงเวลา")
    }
    /** New words, colour or style for a text layer. The letters keep their height: a longer text gets wider, not smaller. */
    private fun retext(i: Int, words: String = layers[i].text, color: Int = layers[i].color, style: Int = layers[i].style) {
        val l = layers.getOrNull(i) ?: return; val oldW = layerThumbs[l.source]?.width ?: 0
        val n = l.copy(text = words, color = color, style = style, source = VideoLayer.textSource(words, color, style))
        val pic = runCatching { VideoLayers.textPicture(n, TEXT_PREVIEW) }.getOrNull() ?: return
        layerThumbs[n.source] = pic
        val k = if (oldW > 0) pic.width.toFloat() / oldW else 1f
        layers[i] = n.copy(aspect = pic.width.toFloat() / pic.height, size = (l.size * k).coerceIn(0.05f, 1f))
        rebuildLayers(); showTool(LAYER_TOOL, false)
    }

    private fun addLayers(uris: List<Uri>, kind: Int, startAt: Long? = null) {
        luma.readIo.execute {
            val found = uris.mapNotNull { uri -> runCatching {
                val visual = kind != VideoLayer.SOUND
                val d = if (kind == VideoLayer.IMAGE) 3000L else VideoLayers.duration(this, uri)
                if (d <= 0) return@runCatching null
                if (visual) layerThumb(uri, kind)?.let { layerThumbs[uri] = it }
                Triple(uri, d, if (visual) VideoLayers.aspect(this, uri, kind == VideoLayer.VIDEO) else 1f)
            }.getOrNull() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (found.isEmpty()) { notice("เปิดไฟล์นี้ไม่ได้"); return@runOnUiThread }
                val total = totalOut(); val now = startAt ?: outNow()
                found.forEach { (uri, d, aspect) ->
                    val len = d.coerceAtMost(total).coerceAtLeast(200)
                    val at = now.coerceAtMost((total - len).coerceAtLeast(0))
                    val n = layers.count { it.visual }
                    layers += VideoLayer(kind, uri, at, len, 0L, 0.5f + (n % 3 - 1) * 0.15f, 0.42f + (n % 2) * 0.12f, if (kind == VideoLayer.IMAGE) 0.4f else 0.45f, aspect)
                }
                layerPick = layers.size - 1
                rebuildLayers(); showTool(LAYER_TOOL, false)
                notice(when (kind) { VideoLayer.VIDEO -> "เพิ่มวิดีโอซ้อนแล้ว · ลากบนภาพเพื่อย้าย"; VideoLayer.IMAGE -> "เพิ่มรูปแล้ว · ลากบนภาพเพื่อย้าย"; else -> "เพิ่มเสียงแล้ว" })
            }
        }
    }
    private fun layerThumb(uri: Uri, kind: Int): Bitmap? = runCatching {
        if (kind == VideoLayer.IMAGE) VideoLayers.picture(this, uri, 480) else MediaMetadataRetriever().run {
            try { setDataSource(this@VideoEditorActivity, uri)
                if (android.os.Build.VERSION.SDK_INT >= 27) getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 480) else getFrameAtTime(0)
            } finally { release() } }
    }.getOrNull()
    private fun loadLayerThumbs() {
        if (layers.isEmpty()) return
        val need = layers.filter { it.visual && it.source !in layerThumbs }
        luma.readIo.execute {
            need.forEach { l -> (if (l.kind == VideoLayer.TEXT) runCatching { VideoLayers.textPicture(l, TEXT_PREVIEW) }.getOrNull() else layerThumb(l.source, l.kind))?.let { layerThumbs[l.source] = it } }
            runOnUiThread { if (!isDestroyed) rebuildLayers() }
        }
    }
    /** One picture per visual layer on the preview: drag it to move, tap it to select it. */
    private fun rebuildLayers() {
        releaseLayerSound()
        layerViews.forEach { stage.removeView(it) }; layerViews.clear()
        layers.forEachIndexed { i, l ->
            if (!l.visual) return@forEachIndexed
            val v = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(layerThumbs[l.source]?.let { b -> if (l.matte.active) VideoLayers.matted(b, l.matte) else b }); setBackgroundColor(if (l.matte.active) 0 else 0x33ffffff); tag = i
                contentDescription = "เลเยอร์${layerName(l)} ลากเพื่อย้าย"; clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(view: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, view.width, view.height, dp(4).toFloat()) } }
            if (l.kind == VideoLayer.TEXT) { v.scaleType = ImageView.ScaleType.FIT_XY; v.background = null; v.clipToOutline = false }
            var dx = 0f; var dy = 0f; var moved = false
            v.setOnTouchListener { view, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; moved = false; view.parent?.requestDisallowInterceptTouchEvent(true) }
                    MotionEvent.ACTION_MOVE -> {
                        if (abs(e.rawX - dx) + abs(e.rawY - dy) > dp(6)) moved = true
                        if (moved && visible.width() > 0) {
                            val k = layers[i]; val cur = k.rawPose(outNow())
                            layers[i] = posed(k, x = (cur[0] + (e.rawX - dx) / visible.width()).coerceIn(0f, 1f), y = (cur[1] + (e.rawY - dy) / visible.height()).coerceIn(0f, 1f))
                            dx = e.rawX; dy = e.rawY; placeLayers()
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) { layerPick = i; Glass.tick(view); showTool(LAYER_TOOL, tool != LAYER_TOOL) }
                }
                true
            }
            stage.addView(v, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.START)); layerViews += v
        }
        stage.post { placeLayers() }
    }
    /** Same place and size as the export; shown only during the layer's time (faint while it is being edited). */
    private fun placeLayers() {
        if (visible.isEmpty || layerViews.isEmpty()) return
        val now = outNow()
        layerViews.forEach { v ->
            val i = v.tag as Int; val l = layers.getOrNull(i) ?: return@forEach
            val p = l.pose(now.coerceIn(l.at, l.end))
            val w = (p[2] * visible.width()).toInt().coerceAtLeast(dp(12)); val h = (w / l.aspect).toInt().coerceAtLeast(dp(12))
            (v.layoutParams as FrameLayout.LayoutParams).let { if (it.width != w || it.height != h) { it.width = w; it.height = h; v.layoutParams = it } }
            v.translationX = visible.left + p[0] * visible.width() - w / 2f; v.translationY = visible.top + p[1] * visible.height() - h / 2f
            v.rotation = l.turn
            val inTime = now in l.at until l.end; val picked = tool == LAYER_TOOL && i == layerPick
            // The picked layer never disappears completely while it is being edited, even when faded or outside its time.
            v.alpha = when { inTime -> if (picked) p[3].coerceAtLeast(0.25f) else p[3]; picked -> 0.4f; else -> 0f }
            v.isEnabled = (inTime || picked) && !l.locked
            if (l.hidden) { v.alpha = if (picked) 0.2f else 0f; v.isEnabled = false }
        }
    }
    /** Plays layer sounds along with the preview. */
    private fun syncLayerSound(playing: Boolean) {
        if (layers.none { it.audible }) return
        if (duck && music != null && recorder == null) musicPlayer?.runCatching { val m = musicVolume.coerceAtMost(1f) * duckGain(); setVolume(m, m) }
        val now = outNow()
        layers.forEachIndexed { i, l ->
            if (!l.audible) return@forEachIndexed
            val mp = layerSound[i]
            if (playing && now in l.at until l.end && !l.hidden) {
                val p = mp ?: runCatching { MediaPlayer().apply { setDataSource(this@VideoEditorActivity, l.source); prepare() } }.getOrNull()?.also { layerSound[i] = it } ?: return@forEachIndexed
                if (!p.isPlaying) runCatching { p.seekTo((l.from + now - l.at).toInt()); p.start() }
                runCatching { val v = l.volume.coerceAtMost(1f) * l.fade(now); p.setVolume(v, v) }
            } else if (mp?.isPlaying == true) mp.pause()
        }
    }
    private fun releaseLayerSound() { layerSound.values.forEach { it.runCatching { release() } }; layerSound.clear() }

    /** Add video, picture and sound layers (video and pictures from Lumora's own gallery), then edit the chosen one. */
    private fun layerTools(col: LinearLayout) {
        val add = LinearLayout(this).apply { gravity = Gravity.CENTER }
        fun chip(label: String, icon: String, f: () -> Unit) = add.addView(EditorKit.chip(this, label, icon) { f() }, LinearLayout.LayoutParams(-2, dp(36)).apply { setMargins(dp(4), 0, dp(4), 0) })
        chip("ข้อความ", EditorKit.TEXT) { askText("") { addText(it) } }
        chip("วิดีโอ", Icons.VIDEO) { pendingKind = VideoLayer.VIDEO; pickFromGallery.launch(Intent(this, GalleryActivity::class.java).putExtra(GalleryActivity.EXTRA_PICK, "video")) }
        chip("รูป", ADD) { pendingKind = VideoLayer.IMAGE; pickFromGallery.launch(Intent(this, GalleryActivity::class.java).putExtra(GalleryActivity.EXTRA_PICK, "image")) }
        chip("เพลง / เสียง", EditorKit.MUSIC) { pickSound.launch(arrayOf("audio/*")) }
        add.addView(EditorKit.chip(this, if (recorder != null) "หยุดอัด" else "อัดเสียงพูด", "M12 4a3 3 0 0 0-3 3v5a3 3 0 0 0 6 0V7a3 3 0 0 0-3-3z M6 11a6 6 0 0 0 12 0 M12 17v3", recorder != null) { toggleRecord() }, LinearLayout.LayoutParams(-2, dp(36)).apply { setMargins(dp(4), 0, dp(4), 0) })
        // SRT is the common subtitle file: numbered lines of text, each with a start and an end time.
        chip("นำเข้าซับ SRT", EditorKit.START) { pickSrt.launch(arrayOf("*/*")) }
        if (layers.any { it.kind == VideoLayer.TEXT }) chip("ส่งออกซับ SRT", EditorKit.END) { saveSrt.launch("subtitles.srt") }
        col.gravity = Gravity.TOP
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(add) }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(6); topMargin = dp(4) })
        if (layers.isEmpty()) {
            col.addView(text("ซ้อนข้อความ วิดีโอ รูป หรือเสียงบนคลิป\nแต่ละชิ้นเป็นแถบสีบนไทม์ไลน์ ลากแถบเพื่อกำหนดเวลาได้", 12f, Glass.TEXT3).apply { gravity = Gravity.CENTER; setPadding(0, dp(40), 0, 0) }, -1, -2)
            return
        }
        if (layerPick !in layers.indices) layerPick = layers.size - 1
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(controls, LinearLayout.LayoutParams(-1, 0, 1f))
        layerControls(controls)
    }
    private var copiedPose: FloatArray? = null
    private val rematte = Runnable { if (!isDestroyed) { val keep = layerPick; rebuildLayers(); layerPick = keep; placeLayers() } }
    private fun layerControls(box: LinearLayout) {
        box.removeAllViews()
        val i = layerPick; val l = layers.getOrNull(i) ?: return
        fun refresh() { placeLayers(); refreshTimeline() }
        val actions = ArrayList<View>()
        if (l.kind == VideoLayer.TEXT) actions += EditorKit.action(this, EditorKit.PEN, "แก้ข้อความ") { askText(layers[i].text) { retext(i, words = it) } }
        actions += EditorKit.action(this, EditorKit.START, "เริ่มที่นี่") { val now = outNow(); val total = totalOut()
            layers[i] = layers[i].copy(at = now.coerceAtMost((total - 200).coerceAtLeast(0)), length = layers[i].length.coerceAtMost(total - now).coerceAtLeast(200)); refresh() }
        actions += EditorKit.action(this, EditorKit.END, "จบที่นี่") { val now = outNow()
            if (now - layers[i].at < 300) notice("เลื่อนไปหลังจุดเริ่มของเลเยอร์ก่อน") else { layers[i] = layers[i].copy(length = now - layers[i].at); refresh() } }
        actions += EditorKit.action(this, "M5 12h14 M15 8l4 4-4 4 M9 8l-4 4 4 4", "ยาวสุด") { val total = totalOut(); val cap = if (l.still) total else VideoLayers.duration(this, l.source) - l.from
            layers[i] = layers[i].copy(length = cap.coerceAtMost(total - layers[i].at).coerceAtLeast(200)); refresh() }
        if (l.visual) {
            // A keyframe remembers where the layer is, how big and how solid at one moment. With two or more, the layer glides between them.
            val t = outNow() - l.at; val here = l.keyList.firstOrNull { abs(it.t - t) <= 150 }
            actions += EditorKit.action(this, "M12 4l8 8-8 8-8-8z M12 9v6 M9 12h6", "คีย์เฟรม +", here != null) {
                val cur = layers[i]; val now = outNow()
                if (now !in cur.at..cur.end) notice("เลื่อนหัวอ่านเข้ามาในช่วงเวลาของเลเยอร์นี้ก่อน")
                else { val p = cur.rawPose(now); val first = cur.keys.isEmpty()
                    layers[i] = cur.withKeys(cur.keyList.filter { abs(it.t - (now - cur.at)) > 120 } + VideoLayer.Key(now - cur.at, p[0], p[1], p[2], p[3]))
                    if (first) notice("ตั้งคีย์เฟรมแรกแล้ว · เลื่อนเวลาไปจุดอื่น แล้วย้าย ปรับขนาด หรือปรับความทึบ เลเยอร์จะค่อย ๆ เปลี่ยนระหว่างสองจุด")
                    refresh(); layerControls(box) } }
            if (l.keys.isNotEmpty()) actions += EditorKit.action(this, "M12 4l8 8-8 8-8-8z M9 12h6", if (here != null && l.keyList.size > 1) "ลบคีย์นี้" else "ลบคีย์ทั้งหมด") {
                val cur = layers[i]; val at = outNow() - cur.at; val near = cur.keyList.firstOrNull { abs(it.t - at) <= 150 }
                // Without keyframes the layer stays where it is right now.
                val p = cur.rawPose(outNow().coerceIn(cur.at, cur.end))
                layers[i] = if (near != null && cur.keyList.size > 1) cur.withKeys(cur.keyList - near) else cur.copy(keys = "", x = p[0], y = p[1], size = p[2], opacity = p[3])
                refresh(); layerControls(box) }
        }
        if (l.visual && l.keys.isNotEmpty()) {
            val t = outNow() - l.at; val here = l.keyList.firstOrNull { abs(it.t - t) <= 150 }
            fun jump(to: Long) { val (k, at) = locate(l.at + to); jumpTo(k, at); timeline.playhead = outNow(); layerControls(box); placeLayers() }
            actions.add(0, EditorKit.action(this, LEFT, "คีย์ก่อนหน้า") { l.keyList.lastOrNull { it.t < t - 150 }?.let { jump(it.t) } ?: notice("ไม่มีคีย์เฟรมก่อนหน้านี้") })
            actions.add(1, EditorKit.action(this, RIGHT, "คีย์ถัดไป") { l.keyList.firstOrNull { it.t > t + 150 }?.let { jump(it.t) } ?: notice("ไม่มีคีย์เฟรมหลังจากนี้") })
            if (here != null) actions += EditorKit.action(this, "M4 18c6 0 10-12 16-12", "การเคลื่อน: " + EASES[here.ease.coerceIn(0, EASES.size - 1)]) {
                val cur = layers[i]; layers[i] = cur.withKeys(cur.keyList.map { if (it === here || it.t == here.t) VideoLayer.Key(it.t, it.x, it.y, it.size, it.opacity, (it.ease + 1) % EASES.size) else it }); refresh(); layerControls(box) }
            actions += EditorKit.action(this, "M8 8h11v11H8z M5 16V5h11", "คัดลอกคีย์") { val p = layers[i].rawPose(outNow().coerceIn(layers[i].at, layers[i].end)); copiedPose = p; notice("คัดลอกตำแหน่ง ขนาด และความทึบแล้ว") }
        }
        if (l.visual && copiedPose != null) actions += EditorKit.action(this, "M9 4h6v3H9z M6 6h12v14H6z", "วางคีย์") { val p = copiedPose!!; val cur = layers[i]; val now = outNow()
            if (now !in cur.at..cur.end) notice("เลื่อนหัวอ่านเข้ามาในช่วงเวลาของเลเยอร์นี้ก่อน")
            else { layers[i] = cur.withKeys(cur.keyList.filter { abs(it.t - (now - cur.at)) > 120 } + VideoLayer.Key(now - cur.at, p[0], p[1], p[2], p[3])); refresh(); layerControls(box) } }
        actions += EditorKit.action(this, "M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6", if (l.hidden) "แสดง" else "ซ่อน", l.hidden) { layers[i] = layers[i].copy(hidden = !layers[i].hidden); rebuildLayers(); refreshTimeline(); layerControls(box) }
        actions += EditorKit.action(this, "M6 11h12v9H6z M8 11V8a4 4 0 0 1 8 0v3", if (l.locked) "ปลดล็อก" else "ล็อก", l.locked) { layers[i] = layers[i].copy(locked = !layers[i].locked); placeLayers(); refreshTimeline(); layerControls(box) }
        actions += EditorKit.action(this, "M8 8h11v11H8z M5 16V5h11", "ทำสำเนา") { val cur = layers[i]; val total = totalOut()
            layers += cur.copy(at = (cur.end).coerceAtMost((total - cur.length).coerceAtLeast(0)), x = (cur.x + 0.04f).coerceAtMost(1f), y = (cur.y + 0.04f).coerceAtMost(1f))
            layerPick = layers.size - 1; rebuildLayers(); showTool(LAYER_TOOL, false) }
        actions += EditorKit.action(this, "M5 7h14 M9 7V4h6v3 M7 7l1 13h8l1-13", "ลบ") { layers.removeAt(i); layerPick = (i - 1).coerceAtLeast(if (layers.isEmpty()) -1 else 0); rebuildLayers(); showTool(LAYER_TOOL, false) }
        box.addView(EditorKit.actionRow(this, actions), -1, -2)
        if (l.kind == VideoLayer.TEXT) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(4), dp(12), dp(2)) }
            (TEXT_COLORS + intArrayOf(0xffff453a.toInt(), 0xff30d158.toInt(), 0xffff6fb5.toInt())).forEach { color -> row.addView(View(this).apply { isClickable = true; contentDescription = "สีข้อความ"; setOnClickListener { Glass.tick(this); retext(i, color = color) }
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setStroke(dp(if (color == l.color) 3 else 1), if (color == l.color) Glass.ACCENT else 0x66ffffff) } },
                LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(8) }) }
            fun toggle(name: String, bit: Int) = row.addView(text(name, 12.5f, if (l.style and bit != 0) Glass.ACCENT else Color.WHITE, l.style and bit != 0).apply { gravity = Gravity.CENTER; background = glass(16, if (l.style and bit != 0) Glass.ACCENT_SOFT else 0x26ffffff)
                setPadding(dp(12), 0, dp(12), 0); isClickable = true; glassTouch(); setOnClickListener { retext(i, style = layers[i].style xor bit) } }, LinearLayout.LayoutParams(-2, dp(32)).apply { rightMargin = dp(6) })
            toggle("กล่องพื้น", 1); toggle("ขอบตัวอักษร", 2); toggle("ตัวบาง", 4)
            box.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }, -1, -2)
        }
        val sliders = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun slider(name: String, steps: List<Int>, now: Int, show: (Int) -> String, set: (Int) -> Unit) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(10), 0) }
            val at = steps.indices.minByOrNull { abs(steps[it] - now) } ?: 0
            val label = text("$name ${show(steps[at])}", 12f, Glass.TEXT2, true).apply { minWidth = dp(112) }
            row.addView(label)
            row.addView(GlassSlider(this).apply { values = steps.map(show); index = at; contentDescription = name
                onChange = { k -> label.text = "$name ${show(steps[k])}"; set(steps[k]) } }, LinearLayout.LayoutParams(0, dp(40), 1f))
            sliders.addView(row, -1, -2)
        }
        val seconds: (Int) -> String = { if (it == 0) "ไม่มี" else String.format(Locale.US, "%.1f วิ", it / 1000f) }
        if (l.visual) {
            val p = l.rawPose(outNow().coerceIn(l.at, l.end))
            slider("ขนาด", (5..100 step 5).toList(), (p[2] * 100).roundToInt(), { "$it%" }) { v -> layers[i] = posed(layers[i], size = v / 100f); refresh() }
            slider("ความทึบ", (0..100 step 5).toList(), (p[3] * 100).roundToInt(), { "$it%" }) { v -> layers[i] = posed(layers[i], opacity = v / 100f); refresh() }
            slider("หมุน", (-180..180 step 5).toList(), l.turn.roundToInt(), { "$it°" }) { v -> layers[i] = layers[i].copy(turn = v.toFloat()); refresh() }
        }
        // Fading applies to the picture and to the sound of a layer alike.
        slider("ค่อย ๆ เข้า", (0..3000 step 100).toList(), l.fadeIn.toInt(), seconds) { v -> layers[i] = layers[i].copy(fadeIn = v.toLong()); refresh() }
        slider("ค่อย ๆ ออก", (0..3000 step 100).toList(), l.fadeOut.toInt(), seconds) { v -> layers[i] = layers[i].copy(fadeOut = v.toLong()); refresh() }
        if (l.kind == VideoLayer.IMAGE || l.kind == VideoLayer.VIDEO) {
            // Cut-outs: a chroma key for green or blue screens, a shape mask with a soft edge, and rounded corners.
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(6), dp(12), dp(2)) }
            fun chip(name: String, on: Boolean, f: () -> Unit) = row.addView(text(name, 12f, if (on) Glass.ACCENT else Color.WHITE, on).apply { gravity = Gravity.CENTER; background = glass(16, if (on) Glass.ACCENT_SOFT else 0x26ffffff)
                setPadding(dp(12), 0, dp(12), 0); isClickable = true; glassTouch(); contentDescription = name; setOnClickListener { f(); rebuildLayers(); layerControls(box) } }, LinearLayout.LayoutParams(-2, dp(32)).apply { rightMargin = dp(6) })
            chip("คีย์สีเขียว", l.key == GREEN_KEY) { layers[i] = layers[i].copy(key = if (layers[i].key == GREEN_KEY) 0 else GREEN_KEY) }
            chip("คีย์สีน้ำเงิน", l.key == BLUE_KEY) { layers[i] = layers[i].copy(key = if (layers[i].key == BLUE_KEY) 0 else BLUE_KEY) }
            chip("มาสก์วงรี", l.shape == StudioMatte.ELLIPSE) { layers[i] = layers[i].copy(shape = if (layers[i].shape == StudioMatte.ELLIPSE) 0 else StudioMatte.ELLIPSE) }
            chip("มาสก์สี่เหลี่ยม", l.shape == StudioMatte.ROUNDED) { layers[i] = layers[i].copy(shape = if (layers[i].shape == StudioMatte.ROUNDED) 0 else StudioMatte.ROUNDED) }
            box.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }, LinearLayout.LayoutParams(-1, -2))
            // Changing a cut-out redraws the picture, so it happens when the finger lifts rather than on every step.
            fun matteSlider(name: String, now: Float, set: (Float) -> VideoLayer) = slider(name, (0..100 step 5).toList(), (now * 100).roundToInt(), { "$it%" }) { v -> layers[i] = set(v / 100f); handler.removeCallbacks(rematte); handler.postDelayed(rematte, 250) }
            if (l.key != 0) matteSlider("ความไวคีย์", l.keyTol) { layers[i].copy(keyTol = it) }
            if (l.shape != 0) matteSlider("ขอบนุ่ม", l.feather) { layers[i].copy(feather = it) }
            matteSlider("มุมโค้ง", l.round) { layers[i].copy(round = it) }
        }
        if (l.audible) slider("เสียง", (0..200 step 10).toList(), (l.volume * 100).toInt(), { "$it%" }) { v -> layers[i] = layers[i].copy(volume = v / 100f); layerSound[i]?.runCatching { val x = (v / 100f).coerceAtMost(1f); setVolume(x, x) } }
        box.addView(android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(sliders) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    // ---------- Undo, redo and the saved project ----------
    private var projectId = ""; private var projectName = ""
    private val undo = ArrayDeque<Bundle>(); private val redo = ArrayDeque<Bundle>()
    private var committed: Bundle? = null; private var committedKey = ""; private var pendingKey = ""
    private var applying = false
    private val extraCache = HashMap<String, Extra>()
    private var undoButton: View? = null; private var redoButton: View? = null
    /**
     * Looks at the edit a few times a second. When it has changed and then stayed the same for one look (so a slider
     * being dragged counts as one change, not fifty), the state before the change goes on the undo list and the
     * project file is rewritten.
     */
    private val watcher = object : Runnable { override fun run() {
        if (isDestroyed) return
        if (!applying && overlay == null) {
            val now = state(); val k = StudioProjects.key(now)
            if (committed == null) { committed = now; committedKey = k }
            else if (k != committedKey) {
                if (k == pendingKey) { undo.addLast(committed!!); while (undo.size > 80) undo.removeFirst(); redo.clear(); committed = now; committedKey = k; paintHistory(); saveProject() }
                else pendingKey = k
            }
        }
        handler.postDelayed(this, 350)
    } }
    private fun paintHistory() { undoButton?.let { it.alpha = if (undo.isEmpty()) 0.35f else 1f }; redoButton?.let { it.alpha = if (redo.isEmpty()) 0.35f else 1f } }
    private fun stepHistory(back: Boolean) {
        val from = if (back) undo else redo; val to = if (back) redo else undo
        if (from.isEmpty()) { notice(if (back) "ไม่มีอะไรให้ย้อนกลับ" else "ไม่มีอะไรให้ทำซ้ำ"); return }
        to.addLast(state()); applyState(from.removeLast()); paintHistory(); saveProject()
    }
    /** Puts a saved state back on screen: every value, then every view that shows one. */
    private fun applyState(b: Bundle) {
        applying = true
        val keepTool = tool; val keepPick = layerPick
        restore(b); tool = keepTool; layerPick = keepPick.coerceAtMost(layers.size - 1)
        prepareMusic(); applySound(); applyLook(); layoutVideo(); rebuildStickers(); rebuildLayers(); loadLayerThumbs()
        val uris = b.getStringArrayList("extras") ?: arrayListOf()
        fun done() { if (playIndex > extras.size) playClip(0, false) else { showClipOnBar(); trim.playhead = trim.playhead.coerceIn(trim.start, trim.end) }; showTool(tool, false); showTime(); committed = state(); committedKey = StudioProjects.key(committed!!); pendingKey = committedKey; applying = false }
        if (uris.all { it in extraCache }) { extras.clear(); extras += buildExtras(uris, b.getStringArrayList("extraRanges")) { extraCache[it] }; done() } else restoreExtras(b) { done() }
        if (playIndex == 0) seek(trim.playhead.coerceIn(start, end))
    }
    private fun saveProject() {
        if (projectId.isEmpty()) return
        // A project file appears only once something was really edited; just opening a video leaves no trace.
        if (!changed() && !StudioProjects.exists(this, projectId)) return
        val snap = state(); val pic = firstFrame; val id = projectId; val name = projectName
        luma.readIo.execute { runCatching { StudioProjects.save(applicationContext, id, name, source, snap, pic) } }
    }

    private fun leave() {
        if (overlay != null) { goBackground(); return }
        if (!changed()) { finish(); return }
        saveProject()
        EditorKit.askSave(this, "ส่งออกเป็นคลิปใหม่ไหม", "งานตัดต่อถูกเก็บเป็นโปรเจกต์แล้ว กลับมาแก้ต่อได้จากหน้าหลัก · วิดีโอต้นฉบับยังอยู่ครบ", "ส่งออก", { export() }, { finish() })
    }

    // ---------- Export ----------
    private var prog: ExportProgress? = null
    /** Bigger or smoother than the edit itself: done after the edit by [VideoEnhancer]. */
    private var enhance: VideoEnhancer.Plan? = null
    /** Smaller or fewer frames than the edit: done inside the edit export. */
    private var shrinkShort = 0; private var shrinkFps = 0f


    private var cachedFps = 0f

    /** Short and long side of the finished frame (after the turn and the crop). */
    private fun frameSides(): Pair<Int, Int> {
        var w = (flow?.width ?: videoW).toFloat(); var h = (flow?.height ?: videoH).toFloat()
        if (turns % 2 == 1) { val t = w; w = h; h = t }
        val r = CROPS[cropIndex].second
        if (r > 0f) { if (r > w / h) h = w / r else w = h * r }
        return minOf(w, h).toInt() to maxOf(w, h).toInt()
    }
    private fun sourceFps(): Float {
        if (cachedFps > 0f) return cachedFps
        cachedFps = runCatching {
            MediaMetadataRetriever().run { try { setDataSource(this@VideoEditorActivity, source)
                val n = if (android.os.Build.VERSION.SDK_INT >= 28) extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toFloatOrNull() else null
                val d = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toFloatOrNull() ?: 0f
                if (n != null && n > 1 && d > 0) n * 1000f / d else null } finally { release() } }
        }.getOrNull()?.coerceIn(5f, 240f) ?: 30f
        return cachedFps
    }
    /** Opens the export sheet (resolution and frame rate); the export starts from there. */
    private fun export() {
        if (transformer != null || prog != null) return
        if (filesDir.usableSpace < 200L * 1024 * 1024) { notice("พื้นที่ว่างไม่พอส่งออก"); return }
        player?.let { if (it.isPlaying) togglePlay() }
        val (short, long) = frameSides()
        // A full-size still from the middle of the clip, so the sheet can preview what each AI model does to this video.
        val still = runCatching { retrieve { r ->
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val f = r.getFrameAtTime(d * 500, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@retrieve null
            val file = java.io.File(cacheDir, "model-preview.jpg")
            file.outputStream().use { f.compress(Bitmap.CompressFormat.JPEG, 95, it) }; f.recycle(); Uri.fromFile(file) } }.getOrNull()
        val src = ExportSheet.Source(short, long, sourceFps(), totalOut(), firstFrame, still)
        ExportSheet.show(this, src, upscale = aiStart) { c -> startExport(c, src) }; aiStart = false
    }
    private fun startExport(c: ExportChoice, src: ExportSheet.Source) {
        if (ExportJob.running()) { notice("มีงานส่งออกอยู่แล้ว · รอให้เสร็จก่อน"); return }
        if (luma.shots.upscaling()) { notice("กำลังเพิ่มรายละเอียดภาพด้วย AI อยู่ · ส่งออกได้เมื่อเสร็จแล้ว"); return }
        val bigger = c.shortSide > src.shortSide * 1.05f; val smoother = c.fps > src.fps * 1.05f
        if (!changed() && !bigger && !smoother && c.shortSide >= src.shortSide - 8 && c.fps >= src.fps - 0.6f) { notice("ยังไม่มีการแก้ไข"); return }
        enhance = if (bigger || smoother) VideoEnhancer.Plan(c.shortSide, c.fps, c.ai, c.profile) else null
        shrinkShort = if (enhance == null && c.shortSide < src.shortSide - 8) c.shortSide else 0
        shrinkFps = if (enhance == null && c.fps < src.fps - 0.6f) c.fps else 0f
        // Several clips or layers become one file, so the split (two files of the main clip) is left out then.
        val whole = extras.isNotEmpty() || layers.isNotEmpty() || enhance != null
        if (split >= 0 && whole) notice("ส่งออกรวมเป็นไฟล์เดียว ไม่แยก")
        val ranges = if (split >= 0 && !whole) listOf(start to split, split to end) else listOf(start to end)
        val steps = ArrayList<String>(); val weights = ArrayList<Float>(); val kinds = ArrayList<Int>()
        val slow = smooth && speed < 1f
        if (slow) { steps += "สร้างเฟรมสโลว์ลื่นด้วย Optical flow"; weights += 3f; kinds += ExportProgress.Backdrop.FLOW }
        val stageEdit = steps.size; steps += "ตัดต่อและใส่เอฟเฟกต์"; weights += 1f; kinds += ExportProgress.Backdrop.EDIT
        var stageEnhance = -1
        enhance?.let { e ->
            stageEnhance = steps.size
            steps += listOfNotNull(if (bigger) (if (e.ai) "AI สร้างพิกเซลเพิ่มเป็น ${ExportSheet.sideName(c.shortSide)}" else "ขยายเป็น ${ExportSheet.sideName(c.shortSide)}") else null,
                if (smoother) "สร้างเฟรมแทรกเป็น ${ExportSheet.fpsName(c.fps)} fps" else null).joinToString(" และ")
            weights += (ExportSheet.minutes(src, c.shortSide, c.fps, e.ai) * 4).toFloat().coerceAtLeast(2f)
            kinds += if (bigger && e.ai) ExportProgress.Backdrop.AI else ExportProgress.Backdrop.FLOW
        }
        val stageSave = steps.size; steps += "บันทึกลงแกลเลอรี"; weights += 0.3f; kinds += ExportProgress.Backdrop.SAVE
        val id = System.currentTimeMillis()
        val dir = File(filesDir, "video-exports/$id").apply { mkdirs() }
        val slowFile = File(cacheDir, "slowflow-$id.mp4")
        val n = SlowFlow.factor(speed); val app = applicationContext; val src0 = source; val a = start; val b = end
        // A sharper still than the trim strip's thumbnail for the progress page (about 540 px).
        val still = runCatching { android.media.MediaMetadataRetriever().run { try { setDataSource(this@VideoEditorActivity, source)
            if (android.os.Build.VERSION.SDK_INT >= 27) getScaledFrameAtTime(start * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 540, 960) else getFrameAtTime(start * 1000) } finally { release() } } }.getOrNull() ?: firstFrame
        val plan = ExportJob.Plan(steps, weights, still, 0, stageEdit, stageEnhance, stageSave,
            slow = if (slow) { cancel, progress -> SlowFlow.render(app, src0, a, b, n, slowFile, cancel, progress = progress) } else null,
            parts = { f -> flow = f; ranges.mapIndexed { k, (from, to) -> composition(from, to) to File(dir, "Lumora_EDIT_${id}${if (ranges.size > 1) "_${k + 1}" else ""}${if (enhance != null) "_edit" else ""}.mp4") } },
            enhance = enhance, finalFile = enhance?.let { File(dir, "Lumora_EDIT_${id}.mp4") }, note = "ตัดต่อใน Lumora",
            cleanup = { slowFile.delete() }, kinds = kinds.toList())
        LiveNotice.askPermissions(this)
        ExportJob.start(this, plan)
        exportButton.alpha = 0.5f
        prog = ExportProgress(this, still, steps, kinds, { cancelExport() }, { goBackground() })
        overlay = prog?.view
        ExportJob.listen(jobListener); onJob()
    }
    private val jobListener: () -> Unit = { onJob() }
    /** Mirrors the export job on screen; when it ends, finishes like before (or shows what went wrong). */
    private fun onJob() {
        val s = ExportJob.state ?: return
        if (isDestroyed) return
        prog?.render(s)
        when {
            s.finished -> { ExportJob.unlisten(jobListener); handler.postDelayed({
                hideProgress(); notice(if (s.outputs.size > 1) "ส่งออก ${s.outputs.size} คลิปแล้ว · ต้นฉบับยังอยู่" else "ส่งออกคลิปใหม่แล้ว · ต้นฉบับยังอยู่")
                // The gallery then opens the clip that was just made, not the original it was cut from.
                setResult(RESULT_OK, Intent().apply { s.uris.lastOrNull()?.let { putExtra("open", "ledger:$it") } }); finish() }, 450) }
            s.error != null -> { ExportJob.unlisten(jobListener); hideProgress(); failed(s.error ?: "") }
            s.cancelled -> { ExportJob.unlisten(jobListener); hideProgress() }
        }
    }
    /** Leaves the editor; the export goes on with its live notification. */
    private fun goBackground() {
        ExportJob.unlisten(jobListener)
        notice("ส่งออกต่อเบื้องหลัง · ดูความคืบหน้าในการแจ้งเตือน")
        setResult(RESULT_OK); finish()
    }

    private fun composition(from: Long, to: Long): Composition {
        // With smooth slow motion the main clip is the rendered file: n times longer, so times scale by n and the
        // speed left to apply is speed × n (about 1×). It has no sound.
        val f = flow; val n = f?.factor ?: 1
        fun at(t: Long) = if (f == null) t else (t - start) * n
        val vw = f?.width ?: videoW; val vh = f?.height ?: videoH
        val cut = cutValid() && cutFrom >= from && cutTo <= to
        return VideoEdit.composition(VideoEditSpec(f?.let { Uri.fromFile(it.file) } ?: source, at(from), at(to), if (turns % 2 == 1) vh else vw, if (turns % 2 == 1) vw else vh,
        speed * n, CROPS[cropIndex].second, caption, place, textColor, textBig, if (looked()) colour() else null, music, f == null && hasAudio && keepSound && speed in SOUND_MIN..SOUND_MAX,
        turns, mirror, volume, musicVolume, if (cut) at(cutFrom) else -1L, if (cut) at(cutTo) else -1L, textBox,
        extras.map { VideoPart(it.uri, it.from, it.to, it.speed, it.muted) }, if (extras.isEmpty()) 0 else transition, stickers.toList(), clampedLayers(), shrinkShort, shrinkFps, mainPos(), fx[0], fx[1], fx[2], grade = if (grade.active()) gradeCube(33) else null, audio = audioSettings(), mainMuted = mainMuted, duck = duck && music != null), applicationContext)
    }

    /** Export problems stay on screen until dismissed, so the reason can be read (and reported). */
    private fun failed(reason: String) { if (!isFinishing) GlassDialog.show(this, "ส่งออกไม่ได้", "$reason\nวิดีโอต้นฉบับยังอยู่ครบ ลองปิดฟิลเตอร์หรือข้อความแล้วส่งออกอีกครั้ง") }
    private fun hideProgress() { flow = null; prog?.remove(); prog = null; overlay = null; exportButton.alpha = 1f; ExportJob.unlisten(jobListener) }
    private fun cancelExport() { ExportJob.cancel() }

    // ---------- Lifecycle ----------
    override fun onPause() { player?.let { if (it.isPlaying) togglePlay() }; if (prog != null) th.ogay.luma.ai.AiPace.watching = false; super.onPause(); if (::trim.isInitialized && overlay == null) saveProject() }
    override fun onResume() { super.onResume(); if (prog != null) th.ogay.luma.ai.AiPace.watching = true }
    override fun onDestroy() { fxEq?.runCatching { release() }; fxEq = null; recorder?.runCatching { stop() }; recorder?.runCatching { release() }; recorder = null; handler.removeCallbacksAndMessages(null); releasePlayer(); musicPlayer?.runCatching { release() }; releaseLayerSound(); super.onDestroy() }
    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out); writeState(out)
        out.putString("projectId", projectId); out.putString("projectName", projectName)
    }
    private fun state() = Bundle().also { writeState(it) }
    private fun writeState(out: Bundle) {
        out.putLong("start", start); out.putLong("end", end); out.putLong("split", split); out.putFloat("speed", speed); out.putInt("crop", cropIndex)
        out.putString("caption", caption); out.putInt("place", place); out.putInt("textColor", textColor); out.putBoolean("big", textBig)
        out.putString("film", film?.id); out.putFloat("strength", filmStrength); out.putString("music", music?.toString()); out.putString("musicName", musicName)
        out.putBoolean("sound", keepSound); out.putInt("tool", tool)
        out.putFloatArray("adjust", adjust); out.putFloatArray("fx", fx); out.putFloatArray("curves", grade.curves.copyOf()); out.putFloatArray("hsl", grade.hsl.copyOf()); out.putFloatArray("wheels", grade.wheels.copyOf()); out.putString("lutPath", lutPath); out.putString("lutName", lutName); out.putFloat("lutAmount", grade.lutAmount); out.putFloatArray("eq", eq.copyOf()); out.putBoolean("lowCut", lowCut); out.putBoolean("voice", voice); out.putBoolean("duck", duck); out.putBoolean("mainMuted", mainMuted); out.putInt("turns", turns); out.putBoolean("mirror", mirror); out.putFloat("volume", volume); out.putFloat("musicVolume", musicVolume)
        out.putLong("cutFrom", cutFrom); out.putLong("cutTo", cutTo); out.putBoolean("textBox", textBox)
        out.putStringArrayList("extras", ArrayList(extras.map { it.uri.toString() })); out.putStringArrayList("extraRanges", ArrayList(extras.map { "${it.from},${it.to},${it.speed}," + (if (it.muted) "m" else "") })); out.putInt("mainAt", mainAt); out.putStringArrayList("markers", ArrayList(markers.map { it.toString() })); out.putInt("transition", transition)
        out.putStringArrayList("stickers", ArrayList(stickers.map { "${it.x}|${it.y}|${it.big}|${it.text}" }))
        out.putString("layers", VideoLayer.save(layers)); out.putInt("layerPick", layerPick); out.putBoolean("smooth", smooth)
    }
    private fun restore(s: Bundle) {
        start = s.getLong("start"); end = s.getLong("end", duration).coerceIn(start, duration); split = s.getLong("split", -1); speed = s.getFloat("speed", 1f); cropIndex = s.getInt("crop")
        caption = s.getString("caption") ?: ""; place = s.getInt("place", 2); textColor = s.getInt("textColor", Color.WHITE); textBig = s.getBoolean("big")
        film = Films.byId(s.getString("film")); filmStrength = s.getFloat("strength", 1f); music = s.getString("music")?.let { Uri.parse(it) }; musicName = s.getString("musicName") ?: ""
        keepSound = s.getBoolean("sound", true); tool = s.getInt("tool")
        s.getFloatArray("adjust")?.takeIf { it.size == adjust.size }?.copyInto(adjust); fx.fill(0f); restoreGrade(s); eq.fill(0f); s.getFloatArray("eq")?.takeIf { it.size == 3 }?.copyInto(eq); lowCut = s.getBoolean("lowCut"); voice = s.getBoolean("voice"); duck = s.getBoolean("duck", true); mainMuted = s.getBoolean("mainMuted"); s.getFloatArray("fx")?.takeIf { it.size == fx.size }?.copyInto(fx); turns = s.getInt("turns"); mirror = s.getBoolean("mirror"); volume = s.getFloat("volume", 1f); musicVolume = s.getFloat("musicVolume", 1f)
        cutFrom = s.getLong("cutFrom", -1L); cutTo = s.getLong("cutTo", -1L); textBox = s.getBoolean("textBox")
        transition = s.getInt("transition"); stickers.clear(); mainAt = s.getInt("mainAt")
        markers.clear(); s.getStringArrayList("markers")?.mapNotNullTo(markers) { it.toLongOrNull() }
        s.getStringArrayList("stickers")?.forEach { line -> val p = line.split("|", limit = 4); if (p.size == 4) stickers += VideoSticker(p[3], p[0].toFloatOrNull() ?: 0.5f, p[1].toFloatOrNull() ?: 0.5f, p[2] == "true") }
        layers.clear(); layers += VideoLayer.load(s.getString("layers")); layerPick = s.getInt("layerPick", -1); smooth = s.getBoolean("smooth")
    }

    /** Darkens what the crop removes and outlines what stays. */
    class CropMask(context: Context) : View(context) {
        private val video = RectF(); private val keep = RectF()
        private val dim = Paint().apply { color = 0xb3000000.toInt() }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xccffffff.toInt() }
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        fun set(v: RectF, k: RectF) { video.set(v); keep.set(k); invalidate() }
        override fun onDraw(c: Canvas) {
            if (keep == video) return
            c.drawRect(video.left, video.top, video.right, keep.top, dim); c.drawRect(video.left, keep.bottom, video.right, video.bottom, dim)
            c.drawRect(video.left, keep.top, keep.left, keep.bottom, dim); c.drawRect(keep.right, keep.top, video.right, keep.bottom, dim)
            line.strokeWidth = resources.displayMetrics.density * 1.5f; c.drawRect(keep, line)
        }
    }

    /** Film-strip timeline: frames across, two handles for the kept range, a playhead and the split mark. */
    class TrimBar(context: Context) : View(context) {
        var frames: List<Bitmap> = emptyList(); set(v) { field = v; invalidate() }
        var duration = 1L; var start = 0L; set(v) { field = v; invalidate() }
        var end = 1L; set(v) { field = v; invalidate() }
        var split = -1L; set(v) { field = v; invalidate() }
        var playhead = 0L; set(v) { field = v; invalidate() }
        var onRange: (Long, Long) -> Unit = { _, _ -> }
        var onSeek: (Long) -> Unit = {}
        private val d = resources.displayMetrics.density
        private val handle = 16 * d
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val dim = Paint().apply { color = 0xaa000000.toInt() }
        private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Glass.ACCENT }
        private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val grip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Glass.ON_ACCENT; strokeCap = Paint.Cap.ROUND }
        private var drag = 0
        private fun x(ms: Long) = handle + (width - 2 * handle) * ms / duration.coerceAtLeast(1).toFloat()
        private fun ms(x: Float) = ((x - handle) / (width - 2 * handle) * duration).toLong().coerceIn(0, duration)
        override fun onDraw(c: Canvas) {
            val top = 4 * d; val bottom = height - 4 * d; val left = handle; val right = width - handle
            val clip = Path().apply { addRoundRect(RectF(left, top, right, bottom), 8 * d, 8 * d, Path.Direction.CW) }
            c.save(); c.clipPath(clip)
            c.drawColor(0xff151d33.toInt())
            if (frames.isNotEmpty()) {
                val cell = (right - left) / frames.size
                frames.forEachIndexed { i, b ->
                    val dst = RectF(left + i * cell, top, left + (i + 1) * cell, bottom)
                    val s = max(dst.width() / b.width, dst.height() / b.height); val sw = dst.width() / s; val sh = dst.height() / s
                    val src = Rect(((b.width - sw) / 2).toInt(), ((b.height - sh) / 2).toInt(), ((b.width + sw) / 2).toInt(), ((b.height + sh) / 2).toInt())
                    c.drawBitmap(b, src, dst, paint)
                }
            }
            c.drawRect(left, top, x(start), bottom, dim); c.drawRect(x(end), top, right, bottom, dim)
            c.restore()
            val s = x(start); val e = x(end)
            accent.style = Paint.Style.STROKE; accent.strokeWidth = 3 * d; c.drawRect(s, top + 1.5f * d, e, bottom - 1.5f * d, accent)
            accent.style = Paint.Style.FILL
            c.drawRoundRect(RectF(s - handle, top, s, bottom), 6 * d, 6 * d, accent); c.drawRoundRect(RectF(e, top, e + handle, bottom), 6 * d, 6 * d, accent)
            grip.strokeWidth = 2.5f * d; val my = (top + bottom) / 2
            c.drawLine(s - handle / 2, my - 8 * d, s - handle / 2, my + 8 * d, grip); c.drawLine(e + handle / 2, my - 8 * d, e + handle / 2, my + 8 * d, grip)
            if (split >= 0) { val sx = x(split); accent.strokeWidth = 3 * d; c.drawLine(sx, top - 2 * d, sx, bottom + 2 * d, accent); c.drawCircle(sx, top, 4 * d, accent) }
            val px = x(playhead); white.strokeWidth = 2.5f * d; c.drawLine(px, 0f, px, height.toFloat(), white)
        }
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val ds = abs(ev.x - (x(start) - handle / 2)); val de = abs(ev.x - (x(end) + handle / 2))
                    drag = when { ds < 24 * d && ds <= de -> 1; de < 24 * d -> 2; else -> 3 }
                    if (drag == 3) onSeek(ms(ev.x).coerceIn(start, end))
                }
                MotionEvent.ACTION_MOVE -> when (drag) {
                    1 -> { start = ms(ev.x + handle / 2).coerceIn(0, end - 500); onRange(start, end) }
                    2 -> { end = ms(ev.x - handle / 2).coerceIn(start + 500, duration); onRange(start, end) }
                    3 -> onSeek(ms(ev.x).coerceIn(start, end))
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drag = 0
            }
            return true
        }
    }
}
