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
        private val CROPS = listOf("ต้นฉบับ" to 0f, "1:1" to 1f, "4:5" to 4f / 5, "16:9" to 16f / 9, "9:16" to 9f / 16, "2.39:1" to 2.39f,
            "3:4" to 3f / 4, "4:3" to 4f / 3, "2:3" to 2f / 3, "3:2" to 3f / 2, "21:9" to 21f / 9, "1.85:1" to 1.85f, "กำหนดเอง" to -1f)
        /** The order the crop shapes are shown in (numbers point into [CROPS], whose order is kept for saved projects). */
        private val CROP_ORDER = listOf(0, 12, 1, 2, 6, 8, 4, 3, 7, 9, 10, 5, 11)
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
        /** Internal menu numbers in the order the tool bar shows them. */
        private val TOOL_IDS = listOf(0, 2, 3, 4, 5, 14, 8, 9, 11, 12, 13, 15, 16)
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
        /** Label to show, and the English phrase the translator is given (empty = no context). */
        private val TRANS_CONTEXTS = listOf("ทั่วไป" to "", "หนังจีนกำลังภายใน" to "a Chinese martial-arts (wuxia) film, with classical, formal speech and honorifics",
            "อนิเมะ" to "a Japanese anime, with natural spoken style", "ซีรีส์เกาหลี" to "a Korean drama, with natural spoken style", "ดราม่า/ซีรีส์" to "a drama series, natural spoken style",
            "ตลก" to "a comedy show, keep the jokes and casual tone", "สารคดี/ข่าว" to "a documentary or news report, formal and precise", "สอน/เทคนิค" to "a technical tutorial, keep technical terms accurate", "ย้อนยุค/ประวัติศาสตร์" to "a historical drama, with old-fashioned formal speech")
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
        /** Loudness of this part's own sound (0..2) and its speed curve (null = steady [speed]). */
        var volume = 1f; var curve: FloatArray? = null
        val len get() = StudioMath.outLen(to - from, speed, curve)
        fun copy() = Extra(uri, duration, w, h, name, thumb).also { it.from = from; it.to = to; it.speed = speed; it.muted = muted; it.volume = volume; it.curve = curve?.copyOf() }
    }
    private var mainFrames: List<Bitmap> = emptyList()
    private val clipFrames = HashMap<String, List<Bitmap>>()
    private fun buildExtras(uris: List<String>, ranges: List<String>?, get: (String) -> Extra?): List<Extra> = uris.mapIndexedNotNull { i, u ->
        get(u)?.copy()?.also { e -> ranges?.getOrNull(i)?.split(",")?.takeIf { it.size >= 2 }?.let { r ->
            e.from = (r[0].toLongOrNull() ?: 0L).coerceIn(0, e.duration); e.to = (r[1].toLongOrNull() ?: e.duration).coerceIn(e.from, e.duration); e.speed = (r.getOrNull(2)?.toFloatOrNull() ?: 1f).coerceIn(0.001f, 5000f); e.muted = r.getOrNull(3) == "m"
            e.volume = r.getOrNull(4)?.toFloatOrNull() ?: 1f; e.curve = r.getOrNull(5)?.split(';')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 5 }?.toFloatArray() } } }
    private var curveSpeedSet = 0f
    /** While a clip with a speed curve plays, the preview's speed follows the curve (the player itself goes from 0.1× to 8×). */
    private fun followCurve(p: MediaPlayer, pos: Long) {
        val e = extras.getOrNull(playIndex - 1)
        val c = if (e != null) e.curve else mainCurve; if (c == null) return
        val sp = (if (e != null) StudioMath.speedAt(pos - e.from, e.to - e.from, e.speed, c) else StudioMath.speedAt(pos - start, end - start, speed, c)).coerceIn(0.1f, 8f)
        if (abs(sp - curveSpeedSet) / sp > 0.02f) { curveSpeedSet = sp; runCatching { p.playbackParams = p.playbackParams.setSpeed(sp) } }
    }
    /** Speed curve of the first clip; null = steady speed. */
    private var mainCurve: FloatArray? = null
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
            if (ok && f != null && f.length() > 0) addLayers(listOf(Uri.fromFile(f)), VideoLayer.SOUND, recAt, home = 16) else { notice("อัดเสียงไม่สำเร็จ"); showTool(16, false) }
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
        showTool(16, false)
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
    /** Every speed the video plays at (each step of a curve counts); sound is kept only when all stay between 0.25× and 4×. */
    private fun allSpeeds(): List<Float> = StudioMath.partSpeeds(speed, if (cutValid()) null else mainCurve).toList() + extras.flatMap { StudioMath.partSpeeds(it.speed, it.curve).toList() }
    private fun mainLen() = if (mainCurve != null && !cutValid()) StudioMath.outLen(end - start, speed, mainCurve) else ((end - start - cutMs()) / speed).toLong()
    private fun refreshTimeline() {
        if (!::timeline.isInitialized) return
        val list = ArrayList<StudioTimeline.Clip>(extras.size + 1)
        val seq = order()
        seq.forEach { k -> if (k == 0) list += StudioTimeline.Clip(mainLen().coerceAtLeast(1), start, end, duration, ((end - start).toFloat() / mainLen().coerceAtLeast(1)).coerceAtLeast(0.0001f), mainFrames.ifEmpty { listOfNotNull(firstFrame) }, extras.isNotEmpty())
            else extras[k - 1].let { e -> ensureFrames(e); list += StudioTimeline.Clip(e.len.coerceAtLeast(1), e.from, e.to, e.duration, ((e.to - e.from).toFloat() / e.len.coerceAtLeast(1)).coerceAtLeast(0.0001f), framesOf(e), true) } }
        // Lanes: the music first, then each layer on the first lane where it does not sit on top of another (four lanes at most).
        val lanes = ArrayList<MutableList<StudioTimeline.Bar>>()
        music?.let { m -> val pk = AudioPeaks.get(m); if (pk == null) AudioPeaks.load(this, m, luma.readIo) { runOnUiThread { if (!isDestroyed) refreshTimeline() } }
            lanes += mutableListOf(StudioTimeline.Bar(-1, 0, list.sumOf { it.len }, 0xff34d399.toInt(), musicName, false, pk, 0L, (pk?.size ?: 0).toLong() * AudioPeaks.STEP_MS)) }
        val firstLayerLane = lanes.size
        val laneOf = StudioMath.pack(layers.map { it.at to it.end }, 12 - firstLayerLane)
        layers.forEachIndexed { i, l ->
            val pk = if (l.kind == VideoLayer.SOUND) AudioPeaks.get(l.source) ?: run { AudioPeaks.load(this, l.source, luma.readIo) { runOnUiThread { if (!isDestroyed) refreshTimeline() } }; null } else null
            val bar = StudioTimeline.Bar(i, l.at, l.length, when (l.kind) { VideoLayer.VIDEO -> 0xff4dc3ff.toInt(); VideoLayer.IMAGE -> 0xffff6fb5.toInt(); VideoLayer.TEXT -> 0xffffd60a.toInt(); else -> 0xffa78bfa.toInt() },
                (if (l.hidden) "(ซ่อน) " else "") + layerName(l), !l.locked, pk, l.from, 0L, l.keyList.map { it.t })
            val at = firstLayerLane + laneOf[i]
            while (lanes.size <= at) lanes += mutableListOf<StudioTimeline.Bar>()
            lanes[at] += bar
        }
        timeline.clips = list; timeline.lanes = lanes; timeline.selected = if (barActive) -1 else seq.indexOf(playIndex).coerceAtLeast(0); timeline.joined = transition != 0; timeline.marks = markers
        timeline.selectedBar = if (barActive && layerPick in layers.indices) layerPick else -2
        if (lanes.size != laneCount) { laneCount = lanes.size; val full = dp(68) + (laneDp() * resources.displayMetrics.density * lanes.size).toInt(); timeline.layoutParams = timeline.layoutParams.apply { height = full }
            timelineScroll.layoutParams = timelineScroll.layoutParams.apply { height = minOf(full, dp(68) + (laneDp() * resources.displayMetrics.density * 3).toInt()) } }
        timeline.playhead = outNow()
        timeline.invalidate()
    }
    /** Which clip, and where in its source file, a moment of the finished video falls. */
    private fun locate(t: Long): Pair<Int, Long> {
        var r = t.coerceAtLeast(0); val o = order()
        o.forEachIndexed { n, k -> val len = lenOf(k)
            if (r < len || n == o.size - 1) { r = r.coerceAtMost(len)
                if (k == 0) { if (mainCurve != null && !cutValid()) return 0 to (start + StudioMath.outToSrc(r, end - start, speed, mainCurve)).coerceIn(start, end)
                    var at = start + (r * speed).toLong(); if (cutValid() && at >= cutFrom) at += cutMs(); return 0 to at.coerceIn(start, end) }
                val e = extras[k - 1]; return k to (e.from + StudioMath.outToSrc(r, e.to - e.from, e.speed, e.curve)).coerceIn(e.from, e.to) }
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
    /** Width ÷ height of the "กำหนดเอง" crop. */
    private var customCrop = 1f
    private fun cropRatio() = CROPS[cropIndex].second.let { if (it < 0f) customCrop else it }
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
    private lateinit var timelineScroll: LaneWindow
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
            followCurve(p, pos)
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
            val lines = StudioMath.writeSrt(layers.filter { it.kind == VideoLayer.TEXT && VideoLayers.shapeOf(it.text) < 0 }.map { StudioMath.Cue(it.at, it.end, it.text) })
            contentResolver.openOutputStream(uri)?.use { it.write(lines.toByteArray()) }; notice("บันทึกไฟล์ซับแล้ว")
        }.onFailure { notice("บันทึกไฟล์ซับไม่สำเร็จ") }
    }
    /** Puts caption lines on the video as text layers low on the picture; returns how many fit inside the video. */
    private fun addCaptionLayers(made: List<Pair<VideoLayer, Bitmap>>): Int {
        val total = totalOut(); var added = 0
        made.forEach { (l0, _) -> if (l0.at < total - 200) {
            val l = l0.copy(caption = true, wrap = captionWrap(captionScale())); val pic = runCatching { VideoLayers.textPicture(l, TEXT_PREVIEW) }.getOrNull() ?: return@forEach
            layerThumbs[l.source] = pic
            layers += l.copy(length = l.length.coerceAtMost(total - l.at), x = 0.5f, y = 0.86f, size = captionSize(pic, captionScale()), aspect = pic.width.toFloat() / pic.height); added++ } }
        splitLongCaptions()
        rebuildLayers(); refreshTimeline(); showTool(15, false)
        return added
    }
    // ---- Automatic captions ----
    private var speech: Any? = null
    /** The finished video as pieces of files: (file, from, to, where it starts in the video, speed). A middle part taken out of the first clip splits it in two. */
    private fun soundPieces(): List<SpeechPiece> {
        val out = ArrayList<SpeechPiece>(); var at = 0L
        for (k in order()) {
            if (k == 0) {
                val ranges = if (cutValid()) listOf(start to cutFrom, cutTo to end) else listOf(start to end)
                ranges.forEach { (a, b) -> if (!mainMuted && keepSound) out += SpeechPiece(source, a, b, at, speed); at += ((b - a) / speed).toLong() }
            } else { val e = extras[k - 1]; if (!e.muted && keepSound) out += SpeechPiece(e.uri, e.from, e.to, at, e.speed); at += e.len }
        }
        return out
    }
    private fun autoCaptions() {
        if (speech != null) { stopCaptions(); return }
        val m = WhisperModels.selected(this)
        if (!ModelStore.installed(this, m)) { downloadModel(m, startAfter = true); return }
        startCaptions(captionLang(), m); showTool(15, false)
    }
    private var captionUi: CaptionProgress? = null
    /** The model whose download should start the captions by themselves when it finishes (set by the "create captions" button). */
    private var startWhenReady: String? = null
    private var hearingModels = false
    /** Where each tool's list was scrolled to, so a button that rebuilds the panel does not throw the list back to the top. */
    private val toolScroll = HashMap<Int, Int>()
    private fun keepScroll(sv: android.widget.ScrollView): android.widget.ScrollView {
        val key = tool
        sv.setOnScrollChangeListener { _, _, y, _, _ -> toolScroll[key] = y }
        sv.post { sv.scrollTo(0, toolScroll[key] ?: 0) }
        return sv
    }
    /** The translator whose download should start the translation by itself when it finishes. */
    private var translateAfter: String? = null
    private var translating = false
    private var translateStopped = false
    private var detectedTag: String? = null
    private val modelEvents: (ModelStore.Job) -> Unit = { job ->
        if (job.finished && !isDestroyed) {
            val m = job.spec
            when {
                job.error != null -> { notice(job.error!!); startWhenReady = null; translateAfter = null }
                translateAfter == m.id -> { translateAfter = null; notice("ดาวน์โหลดเสร็จแล้ว · เริ่มแปลคำบรรยาย"); runLlmTranslate(m, captionTarget()) }
                startWhenReady == m.id && speech == null -> { startWhenReady = null; notice("ดาวน์โหลดเสร็จแล้ว · เริ่มสร้างคำบรรยาย"); startCaptions(captionLang(), m) }
                else -> notice("ดาวน์โหลด ${m.name} เสร็จแล้ว")
            }
            if (tool == 15) showTool(15, false)
        }
    }
    /** Downloads a Whisper model in the background (it goes on if this screen is closed); with [startAfter] the captions start when it is ready. */
    private fun downloadModel(m: ModelSpec, startAfter: Boolean = false) {
        if (!hearingModels) { ModelStore.listen(modelEvents); hearingModels = true }
        LiveNotice.askPermissions(this)
        if (startAfter) startWhenReady = m.id
        ModelStore.download(this, m)
        notice(if (startAfter) "ต้องดาวน์โหลด ${m.name} ก่อน · เสร็จแล้วจะเริ่มสร้างคำบรรยายให้เอง" else "เริ่มดาวน์โหลด ${m.name} · ปิดหน้านี้ได้ ดาวน์โหลดต่อเบื้องหลัง")
        if (tool == 15) showTool(15, false)
    }
    private fun stopCaptions() {
        (speech as? StudioSpeech)?.stop(); speech = null; captionUi?.remove(); captionUi = null
        notice("หยุดสร้างคำบรรยายแล้ว"); if (tool == 15) showTool(15, false)
    }
    private fun startCaptions(lang: String, model: ModelSpec) {
        val whole = soundPieces()
        if (whole.isEmpty()) { notice("วิดีโอนี้ไม่มีเสียงให้แปลง"); return }
        val auto = lang == StudioSpeech.AUTO
        val ui = CaptionProgress(this, firstFrame, listOf("เตรียมเสียงจากคลิป", "หาช่วงที่มีเสียงพูด", if (auto) "ตรวจภาษาที่พูด" else "ใช้ภาษาที่เลือกไว้", "ถอดเสียงด้วย ${model.name}", "ใส่คำบรรยายบนไทม์ไลน์")) { stopCaptions() }
        val clock = AsrClock(this, model.id, whole.sumOf { it.to - it.from })
        fun show(stage: Int, note: String, words: String = "") = ui.render(stage, clock.fraction(), note, words, clock.leftMs())
        captionUi = ui; show(0, "กำลังเตรียมเสียงจากคลิป…")
        notice("กำลังหาช่วงที่มีเสียงพูด…")
        luma.readIo.execute {
            // Each clip is cut at its pauses so every caption line gets its own start and end; long stretches are cut every 8 seconds.
            val pieces = ArrayList<SpeechPiece>()
            runOnUiThread { captionUi?.render(1, 0.08f, "อ่านระดับเสียงเพื่อหาช่วงที่มีคนพูด…") }
            for (p in whole) {
                val pk = AudioPeaks.range(this, p.uri, p.from, p.to).takeIf { it.isNotEmpty() } ?: continue
                for ((a, b) in StudioMath.keepRanges(pk, AudioPeaks.STEP_MS.toLong(), p.from, p.to, quiet = 0.1f, minMs = 350, padMs = 120)) {
                    var x = a; while (x < b) { val y = if (b - x > 10_000) x + 8_000 else b; pieces += SpeechPiece(p.uri, x, y, p.outAt + ((x - p.from) / p.speed).toLong(), p.speed); x = y }
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (pieces.isEmpty()) { captionUi?.remove(); captionUi = null; notice("ไม่พบช่วงที่มีเสียงพูด"); return@runOnUiThread }
                val used = pieces.take(80)
                clock.speech(used.sumOf { it.to - it.from })
                show(if (auto) 2 else 3, "พบ ${used.size} ช่วงที่มีเสียงพูด" + if (used.size < pieces.size) " (ใช้ 80 ช่วงแรก)" else "")
                var spoken = lang
                val job = StudioSpeech(this, lang, used, model, { i, n ->
                    val p = used.getOrNull(i); val span = if (p != null) " · ${clock(p.from)}–${clock(p.to)} ของคลิป" else ""
                    val langName = LANGS.firstOrNull { it.second == spoken }?.first
                    clock.transcribing(used.take(i).sumOf { it.to - it.from })
                    show(3, "ช่วงที่ ${i + 1} จาก $n$span" + (if (auto && langName != null) " · ภาษา $langName" else ""))
                    captionStatus?.takeIf { tool == 15 }?.let { it.text = "กำลังถอดเสียง ${i + 1}/$n" } }) { cues, problem ->
                    speech = null; if (isDestroyed) return@StudioSpeech
                    clock.finish(); ui.render(4, 0.97f, "กำลังใส่คำบรรยาย ${cues.size} บรรทัด…", "", 5_000)
                    if (tool == 15) showTool(15, false)
                    val made = StudioMath.holdCues(cues).map { c -> val l = VideoLayer(VideoLayer.TEXT, VideoLayer.textSource(c.text, Color.WHITE, 1), c.from, c.to - c.from, text = c.text, style = 1); l to VideoLayers.textPicture(l, TEXT_PREVIEW) }
                    val added = if (made.isNotEmpty()) addCaptionLayers(made) else 0
                    ui.render(4, 1f, if (added > 0) "เสร็จแล้ว · เพิ่ม $added บรรทัด" else (problem ?: "ไม่พบเสียงพูดที่แปลงได้")); ui.remove(); captionUi = null
                    if (added > 0) TaskNotice.finish(this, "สร้างคำบรรยายเสร็จแล้ว", "เพิ่ม $added บรรทัด · แตะเพื่อกลับไปตรวจคำ")
                    notice(when { added > 0 && problem == null -> "เพิ่มคำบรรยาย $added บรรทัด · ตรวจคำและเวลาก่อนส่งออก"; added > 0 -> "เพิ่มคำบรรยาย $added บรรทัด · $problem"; else -> problem ?: "ไม่พบเสียงพูดที่แปลงได้" })
                }
                job.onStage = { t -> show(2, t) }
                job.onDetected = { code -> val tag = LANGS.firstOrNull { StudioSpeech.whisperCode(it.second) == code }?.second ?: code; spoken = tag; detectedTag = tag; val n = LANGS.firstOrNull { it.second == tag }?.first ?: code; show(3, "พบว่าพูดภาษา $n") }
                job.onText = { t -> ui.render(3, ui.lastFraction, ui.lastNote, t, clock.leftMs()) }
                speech = job; job.start()
            }
        }
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
                val added = addCaptionLayers(made.take(SRT_LIMIT))
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
        source = state?.getString("mainSource")?.let { Uri.parse(it) } ?: intent.data ?: run { finish(); return }
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
        projectName = state?.getString("projectName") ?: intent.getStringExtra("name")?.takeIf { it.isNotBlank() } ?: ""
        if (state == null && intent.hasExtra("project")) StudioProjects.load(this, projectId)?.let { p -> projectName = p.name; reopened = p.state; restore(p.state); tool = 0; layerPick = -1 }
        named = state?.getBoolean("named") ?: (!intent.hasExtra("project") && !intent.getStringExtra("name").isNullOrBlank())
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
        // Quiet status that appears for a moment each time the project has been saved on its own.
        saveLabel = text("", 12f, Glass.TEXT3).apply { alpha = 0f; setPadding(dp(10), 0, 0, 0); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        bar.addView(saveLabel, LinearLayout.LayoutParams(0, -2, 1f))
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
        captionView = text("", 20f, Glass.TEXT, true).apply { gravity = Gravity.CENTER; maxLines = 3; setShadowLayer(6f, 0f, 2f, 0x99000000.toInt()); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
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
        transport.addView(EditorKit.round(this, "M7 4v16 M7 5h10l-2.5 3.5L17 12H7", "ปักหรือถอนมาร์กเกอร์ตรงหัวอ่าน") {
            val now = outNow(); val near = markers.firstOrNull { abs(it - now) <= 150 }
            if (near != null) markers.remove(near) else { markers += now; markers.sort() }
            Glass.tick(timeline); refreshTimeline() }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(6) })
        root.addView(transport, -1, -2)
        timeline = StudioTimeline(this).apply { contentDescription = "ไทม์ไลน์ ลากเพื่อเลื่อนเวลา แตะคลิปเพื่อเลือก ลากขอบคลิปที่เลือกเพื่อตัด กดค้างแล้วลากเพื่อสลับลำดับ" }
        timeline.onSeek = { t, done -> if (player?.isPlaying == true) togglePlay(); val (k, at) = locate(t); jumpTo(k, at, done) }
        timeline.onPickBar = { id -> if (id >= 0) pickBar(id) }
        timeline.onAdd = { pickClip.launch(arrayOf("video/*")) }
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
        timeline.onSelect = { i -> val k = order().getOrNull(i) ?: 0; val was = barActive; barActive = false
            if (k != playIndex) jumpTo(k, if (k == 0) start else extras[k - 1].from)
            if (tool == 0 || tool == 2 || tool == 9 || was) showTool(tool, false); refreshTimeline() }
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
        // Many lanes: the timeline is taller than its window and scrolls up and down inside it (three lanes show at once).
        timeline.laneDp = laneDp()
        timelineScroll = LaneWindow(this).apply { addView(timeline, FrameLayout.LayoutParams(-1, dp(68))) }
        root.addView(timelineScroll, LinearLayout.LayoutParams(-1, dp(68)).apply { topMargin = dp(2) })
        trim = TrimBar(this).apply {
            contentDescription = "ช่วงคลิป ลากขอบซ้ายขวาเพื่อตัด"
        }
        // Set outside apply{}: inside it, start/end/split would name the bar's own fields, not the edit's.
        trim.onRange = { s, e -> val x = extras.getOrNull(playIndex - 1)
            if (x != null) { x.from = s; x.to = e; seek(trim.playhead.coerceIn(s, e)); showTime() } else { start = s; end = e; if (split !in (s + 300)..(e - 300)) split = -1; trim.split = split; seek(trim.playhead.coerceIn(s, e)); showTime() } }
        trim.onSeek = { ms -> seek(ms) }
        // The old film strip still keeps the playhead and the picked clip's ends, but the timeline above is what is shown.
        trim.visibility = View.GONE
        root.addView(trim, LinearLayout.LayoutParams(-1, 0))
        tools = FrameLayout(this).apply { clipChildren = false }
        root.addView(tools, LinearLayout.LayoutParams(-1, dp(124)))
        toolBar = EditorKit.ToolBar(this, listOf(EditorKit.SCISSORS to "ตัด", EditorKit.SPEED to "ความเร็ว", EditorKit.CROP to "ครอป", EditorKit.TEXT to "ข้อความ", Icons.FILM to "ฟิลเตอร์",
            EditorKit.ADJUST to "ปรับแต่งภาพ", EditorKit.ROTATE to "หมุน", Icons.SOUND to "เสียง", JOIN to "ทรานซิชัน", STICKER to "สติกเกอร์", LAYERS to "เลเยอร์",
            "M4 6h16v10H8l-4 4z M8 10h8 M8 13h5" to "คำบรรยาย", "M12 4a3 3 0 0 0-3 3v5a3 3 0 0 0 6 0V7a3 3 0 0 0-3-3z M6 11a6 6 0 0 0 12 0 M12 17v3" to "อัดเสียง")) { showTool(TOOL_IDS[it]) }
        root.addView(toolBar, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(4), 0, dp(4), dp(8)) })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = leave() })

        trim.duration = duration; trim.start = start; trim.end = end; trim.split = split
        luma.readIo.execute {
            val frames = frames(10)
            runOnUiThread { if (!isDestroyed) { mainFrames = frames; if (playIndex == 0) trim.frames = frames; firstFrame = frames.firstOrNull(); if (named) saveProject(); if (tool == 5) showTool(5, false) } }
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
        convertLegacyMusic()
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
        val clipVol = clipVolume(playIndex).coerceAtMost(1f)
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
        val r = cropRatio(); if (r == 0f) return RectF(video)
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

    /** The first clip now plays another file: read its size and length again and make new film-strip pictures. */
    private fun switchSource(uri: Uri) {
        source = uri; readSource(); mainFrames = emptyList()
        if (!::trim.isInitialized) return
        luma.readIo.execute { val f = frames(10); runOnUiThread { if (!isDestroyed && source == uri) { mainFrames = f; firstFrame = f.firstOrNull() ?: firstFrame; if (playIndex == 0) trim.frames = f; refreshTimeline() } } }
    }
    /**
     * Removes the first clip. The clip that played right after it (or right before, when it was last) takes its
     * place, so the first clip's crop, turn and other settings stay with the video.
     */
    private fun deleteMain() {
        if (extras.isEmpty()) { notice("วิดีโอต้องมีอย่างน้อยหนึ่งคลิป"); return }
        val idx = mainPos().coerceAtMost(extras.size - 1); val e = extras.removeAt(idx)
        mainAt = idx; cutFrom = -1; cutTo = -1; split = -1
        switchSource(e.uri); start = e.from; end = e.to; speed = e.speed; mainMuted = e.muted; firstFrame = e.thumb ?: firstFrame
        playIndex = 0; trim.split = -1; showClipOnBar(); trim.playhead = start; openPlayer(); showTime(); showTool(10, false)
        notice("ลบคลิปแล้ว")
    }

    // ---------- Automatic analysis ----------
    private var analysing = false
    /** Where clip [k] starts in the finished video. */
    private fun clipStart(k: Int): Long { var at = 0L; for (o in order()) { if (o == k) break; at += lenOf(o) }; return at }
    /** The clip on screen as (file, from, to, speed); null when it cannot be cut into pieces (first clip with a speed change or a middle part taken out). */
    private fun clipSpan(k: Int): List<Any>? { val e = extras.getOrNull(k - 1)
        if (e != null) return if (e.curve != null) null else listOf(e.uri, e.from, e.to, e.speed)
        if (speed != 1f || cutValid() || mainCurve != null) return null
        return listOf(source, start, end, 1f) }
    /** Puts pieces [ranges] (ms of the source) in the place of clip [k], next to each other in the same order. */
    private fun replaceWithRanges(k: Int, ranges: List<Pair<Long, Long>>) {
        if (ranges.isEmpty()) return
        val e = extras.getOrNull(k - 1)
        if (e == null) {
            start = ranges[0].first; end = ranges[0].second; split = -1
            extraCache.getOrPut(source.toString()) { Extra(source, duration, videoW, videoH, "คลิปแรก", firstFrame) }
            ranges.drop(1).forEachIndexed { j, (a, b) -> extras.add(mainPos() + j, Extra(source, duration, videoW, videoH, "คลิปแรก", firstFrame).also { it.from = a; it.to = b }) }
        } else {
            e.from = ranges[0].first; e.to = ranges[0].second; val wasBefore = k - 1 < mainPos()
            ranges.drop(1).forEachIndexed { j, (a, b) -> extras.add(k + j, e.copy().also { it.from = a; it.to = b }) }
            if (wasBefore) mainAt = mainPos() + ranges.size - 1
        }
        showClipOnBar(); trim.playhead = trim.playhead.coerceIn(trim.start, trim.end); showTime(); if (tool == 10) showTool(10, false)
    }
    /** Finds scene changes in the clip on screen by comparing the colours of frames 0.2 s apart, and marks them on the timeline. */
    private fun findScenes() {
        if (analysing) return
        val k = playIndex; val span = clipSpan(k) ?: listOf(source, start, end, speed)
        val uri = span[0] as Uri; val from = span[1] as Long; val to = span[2] as Long; val sp = span[3] as Float
        analysing = true; notice("กำลังหาจุดเปลี่ยนฉาก…")
        luma.readIo.execute {
            val step = 200L
            val cuts = runCatching {
                LayerFrames(this, uri, from, 64).use { f ->
                    var prev: FloatArray? = null; val diffs = ArrayList<Float>(); val px = IntArray(f.bitmap.width * f.bitmap.height); var t = 0L
                    while (from + t < to && !isDestroyed) {
                        f.advance(t * 1000); f.bitmap.getPixels(px, 0, f.bitmap.width, 0, 0, f.bitmap.width, f.bitmap.height)
                        val h = FloatArray(48); for (p in px) { h[(p shr 16 and 255) shr 4]++; h[16 + ((p shr 8 and 255) shr 4)]++; h[32 + ((p and 255) shr 4)]++ }
                        for (i in h.indices) h[i] /= px.size.toFloat()
                        diffs += prev?.let { a -> var d = 0f; for (i in h.indices) d += abs(h[i] - a[i]); d / 6f } ?: 0f
                        prev = h; t += step
                    }
                    StudioMath.sceneCuts(diffs.toFloatArray(), step)
                }
            }.getOrDefault(emptyList())
            runOnUiThread {
                analysing = false; if (isDestroyed) return@runOnUiThread
                val base = clipStart(k); val added = cuts.map { base + (it / sp).toLong() }.filter { m -> markers.none { abs(it - m) < 200 } }
                markers += added; markers.sort(); refreshTimeline()
                notice(if (added.isEmpty()) "ไม่พบจุดเปลี่ยนฉากในคลิปนี้" else "พบ ${added.size} จุดเปลี่ยนฉาก · ปักมาร์กเกอร์ไว้แล้ว แตะ \"แยกตามมาร์กเกอร์\" เพื่อตัดเป็นคลิปย่อย")
            }
        }
    }
    /** Cuts the clip on screen at every marker inside it. */
    private fun splitAtMarkers() {
        val k = playIndex; val span = clipSpan(k) ?: run { notice("คลิปนี้ปรับความเร็วหรือตัดช่วงกลางแล้ว จึงแยกอัตโนมัติไม่ได้"); return }
        val from = span[1] as Long; val to = span[2] as Long; val sp = span[3] as Float; val base = clipStart(k)
        val cuts = markers.map { from + ((it - base) * sp).toLong() }.filter { it > from + 300 && it < to - 300 }.distinct().sorted()
        if (cuts.isEmpty()) { notice("ไม่มีมาร์กเกอร์ในคลิปนี้"); return }
        val edges = listOf(from) + cuts + to
        replaceWithRanges(k, edges.zipWithNext())
        notice("แยกเป็น ${edges.size - 1} คลิปแล้ว")
    }
    /** Takes the quiet stretches out of the clip on screen, leaving the pieces with sound next to each other. */
    private fun cutSilence() {
        if (analysing) return
        val k = playIndex; val span = clipSpan(k) ?: run { notice("คลิปนี้ปรับความเร็วหรือตัดช่วงกลางแล้ว จึงตัดช่วงเงียบไม่ได้"); return }
        val uri = span[0] as Uri; val from = span[1] as Long; val to = span[2] as Long
        if (isMuted(k)) { notice("คลิปนี้ปิดเสียงอยู่"); return }
        val done = { pk: FloatArray? ->
            analysing = false
            if (pk == null || pk.isEmpty()) notice("คลิปนี้ไม่มีเสียงให้วิเคราะห์")
            else {
                val keep = StudioMath.keepRanges(pk, AudioPeaks.STEP_MS.toLong(), from, to)
                when {
                    keep.isEmpty() -> notice("ทั้งคลิปเงียบเกินไป จึงไม่ตัด")
                    keep.size == 1 && keep[0].first == from && keep[0].second == to -> notice("ไม่พบช่วงเงียบที่ยาวพอจะตัด")
                    else -> { val removed = (to - from) - keep.sumOf { it.second - it.first }; replaceWithRanges(k, keep); notice("ตัดช่วงเงียบออก ${clock(removed)} · เหลือ ${keep.size} ช่วง") }
                }
            }
        }
        AudioPeaks.get(uri)?.let { done(it); return }
        analysing = true; notice("กำลังฟังหาช่วงเงียบ…")
        AudioPeaks.load(this, uri, luma.readIo) { runOnUiThread { if (!isDestroyed) done(AudioPeaks.get(uri)) } }
    }
    /** Marks the beats of the music on the timeline, so clip edges and layers snap to them. */
    private fun markBeats() {
        val song = layers.firstOrNull { it.music } ?: run { notice("เพิ่มเพลงก่อน"); return }
        val m = song.source
        val run = { pk: FloatArray ->
            val loop = pk.size.toLong() * AudioPeaks.STEP_MS; val total = totalOut(); val beats = StudioMath.beats(pk, AudioPeaks.STEP_MS.toLong())
            // Beats are times in the song; the bar starts at song.at on the timeline and plays from song.from in the song.
            val all = beats.map { song.at + it - song.from }.filter { it >= song.at && it < song.end && it < total }.take(400)
            val added = all.filter { b -> markers.none { abs(it - b) < 120 } }
            markers += added; markers.sort(); refreshTimeline()
            notice(if (added.isEmpty()) "ไม่พบจังหวะที่ชัดในเพลงนี้" else "ปักมาร์กเกอร์ตามจังหวะเพลง ${added.size} จุด · ขอบคลิปและเลเยอร์จะดูดเข้าหาจังหวะ")
        }
        AudioPeaks.get(m)?.let { run(it); return }
        notice("กำลังฟังจังหวะเพลง…")
        AudioPeaks.load(this, m, luma.readIo) { runOnUiThread { if (!isDestroyed) AudioPeaks.get(m)?.takeIf { it.isNotEmpty() }?.let(run) ?: notice("อ่านเสียงเพลงนี้ไม่ได้") } }
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
        if (sp != 1f || (e?.curve ?: mainCurve) != null) { notice("แยกเสียงได้เฉพาะคลิปความเร็วปกติ"); return }
        if (e == null && cutValid()) { notice("คลิปนี้ตัดช่วงกลางออกแล้ว จึงแยกเสียงไม่ได้"); return }
        val src = e?.uri ?: source; val from = e?.from ?: start; val len = e?.len ?: mainLen()
        var at = 0L; for (o in order()) { if (o == k) break; at += lenOf(o) }
        layers += VideoLayer(VideoLayer.SOUND, src, at, len, from, volume = if (e == null) volume else 1f)
        setMuted(k, true); layerPick = layers.size - 1; rebuildLayers(); refreshTimeline()
        notice("แยกเสียงเป็นแถบสีม่วงแล้ว · ลากแถบเพื่อให้เสียงมาก่อนหรือต่อเลยภาพ")
    }
    /** The music goes down to 30 % while a sound layer plays, in the preview the same way as in the export. */
    private fun duckGain(): Float = if (!duck) 1f else StudioAudio.duck(outNow(), layers.filter { it.audible && it.volume > 0f && !it.hidden && !it.music }.map { it.at to it.end })

    // ---------- Colour studio ----------
    private val grade = StudioGrade()
    private var lutPath = ""; private var lutName = ""
    private var colourTab = 0; private var curveChannel = 0; private var hslBand = 0; private var wheelRange = 1
    private var hist: HistView? = null
    private var scopeMode = 0
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
    /**
     * Scopes of the picture on screen, after the look. Mode 0 histogram: how many pixels at each brightness, per colour.
     * Mode 1 waveform: brightness (up) of every column of the picture (across). Mode 2 vectorscope: hue (angle) and
     * saturation (distance from the centre) of every pixel, with a line where skin tones fall.
     */
    private class HistView(c: Context) : View(c) {
        var mode = 0
        private val bins = Array(3) { FloatArray(64) }
        private var dots: FloatArray = FloatArray(0); private var dotCount = 0
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG); private val path = Path()
        private val colors = intArrayOf(0x99ff5a5a.toInt(), 0x9952d96b.toInt(), 0x995a9bff.toInt())
        init { contentDescription = "กราฟสีของภาพ แตะเพื่อเปลี่ยนแบบ" }
        fun feed(b: Bitmap, cube: Array<Array<IntArray>>?) {
            bins.forEach { it.fill(0f) }
            val w = b.width; val h = b.height; val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
            val n = cube?.size ?: 0
            if (dots.size < px.size * 2) dots = FloatArray(px.size * 2); dotCount = 0
            for ((idx, p) in px.withIndex()) {
                var c = p
                if (cube != null) c = cube[((p shr 16 and 255) * (n - 1) + 127) / 255][((p shr 8 and 255) * (n - 1) + 127) / 255][((p and 255) * (n - 1) + 127) / 255]
                val r = c shr 16 and 255; val g = c shr 8 and 255; val bl = c and 255
                bins[0][r shr 2]++; bins[1][g shr 2]++; bins[2][bl shr 2]++
                when (mode) {
                    1 -> { dots[dotCount++] = (idx % w).toFloat() / (w - 1); dots[dotCount++] = (0.2126f * r + 0.7152f * g + 0.0722f * bl) / 255f }
                    2 -> { dots[dotCount++] = (-0.1687f * r - 0.3313f * g + 0.5f * bl) / 255f; dots[dotCount++] = (0.5f * r - 0.4187f * g - 0.0813f * bl) / 255f }
                }
            }
            val top = bins.maxOf { a -> a.drop(1).dropLast(1).maxOrNull() ?: 1f }.coerceAtLeast(1f)
            bins.forEach { a -> for (i in a.indices) a[i] = (a[i] / top).coerceAtMost(1f) }
            invalidate()
        }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            paint.style = Paint.Style.FILL; paint.color = 0x1fffffff; c.drawRoundRect(0f, 0f, w, h, h * 0.18f, h * 0.18f, paint)
            when (mode) {
                0 -> { paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                    bins.forEachIndexed { k, a -> path.reset(); path.moveTo(0f, h)
                        a.forEachIndexed { i, v -> path.lineTo(i * w / (a.size - 1), h - v * (h - 4)) }
                        path.lineTo(w, h); path.close(); paint.color = colors[k]; c.drawPath(path, paint) }
                    paint.xfermode = null }
                1 -> { paint.color = 0x55b4ffb4; var i = 0; while (i < dotCount) { c.drawRect(4 + dots[i] * (w - 8), h - 3 - dots[i + 1] * (h - 6), 5 + dots[i] * (w - 8), h - 2 - dots[i + 1] * (h - 6), paint); i += 2 } }
                else -> { val cx = w / 2; val cy = h / 2; val r = h / 2 - 2
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f; paint.color = 0x40ffffff; c.drawCircle(cx, cy, r, paint)
                    // Skin tones sit near this line, whatever the skin colour.
                    val a = Math.toRadians(123.0); paint.color = 0x80ffb070.toInt(); c.drawLine(cx, cy, cx + (r * Math.cos(a)).toFloat(), cy - (r * Math.sin(a)).toFloat(), paint)
                    paint.style = Paint.Style.FILL; paint.color = 0x77ffffff
                    var i = 0; while (i < dotCount) { val x = cx + dots[i] * 2 * r; val y = cy - dots[i + 1] * 2 * r; c.drawRect(x, y, x + 1.5f, y + 1.5f, paint); i += 2 } }
            }
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
        hist = HistView(this).also { v -> v.mode = scopeMode; v.isClickable = true
            v.setOnClickListener { scopeMode = (scopeMode + 1) % 3; v.mode = scopeMode; notice(listOf("ฮิสโตแกรม", "เวฟฟอร์ม", "เวกเตอร์สโคป")[scopeMode]) }
            top.addView(v, LinearLayout.LayoutParams(0, dp(40), 1f)) }
        top.addView(text("กดค้างดูต้นฉบับ", 11f, Glass.TEXT2, true).apply { gravity = Gravity.CENTER; background = glass(14, 0x26ffffff); setPadding(dp(10), 0, dp(10), 0); contentDescription = "กดค้างเพื่อดูภาพก่อนปรับสี"
            setOnTouchListener { v, e -> when (e.actionMasked) { MotionEvent.ACTION_DOWN -> { comparing = true; applyLook(); v.alpha = 0.6f }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { comparing = false; applyLook(); v.alpha = 1f } }; true } }, LinearLayout.LayoutParams(-2, dp(30)).apply { leftMargin = dp(8) })
        top.addView(text("รีเซ็ต", 11f, Glass.TEXT2, true).apply { gravity = Gravity.CENTER; background = glass(14, 0x26ffffff); setPadding(dp(10), 0, dp(10), 0); isClickable = true; glassTouch(); contentDescription = "รีเซ็ตสีทั้งหมด"
            setOnClickListener { grade.reset(); lutPath = ""; lutName = ""; applyLook(); showTool(COLOR_TOOL, false) } }, LinearLayout.LayoutParams(-2, dp(30)).apply { leftMargin = dp(6) })
        col.addView(top, -1, -2)
        // Five kinds of adjustment as icon tabs: light and finishing, colour wheels, curves, HSL and LUT.
        val tabs = LinearLayout(this).apply { gravity = Gravity.CENTER }
        listOf(EditorKit.ADJUST to "ปรับแสง", "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18 M12 3v18 M3 12h18" to "โทนสี", "M4 20C9 20 9 4 20 4" to "เส้นโค้ง",
            "M12 3c4 5 6 8 6 11a6 6 0 0 1-12 0c0-3 2-6 6-11z" to "HSL", "M4 4h16v16H4z M4 9h16 M4 14h16 M9 4v16 M14 4v16" to "LUT").forEachIndexed { i, (icon, n) -> val on = i == colourTab
            tabs.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true; glassTouch(); contentDescription = n
                background = if (on) glass(14, Glass.ACCENT_SOFT) else null
                addView(IconView(this@VideoEditorActivity, icon, 2f).apply { tint = if (on) Glass.ACCENT else Color.WHITE }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { gravity = Gravity.CENTER_HORIZONTAL })
                addView(text(n, 10.5f, if (on) Glass.ACCENT else Glass.TEXT2, true).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER; setPadding(0, dp(3), 0, 0) }, LinearLayout.LayoutParams(-1, -2))
                setOnClickListener { colourTab = i; showTool(COLOR_TOOL, false) } }, LinearLayout.LayoutParams(dp(62), dp(50)).apply { setMargins(dp(2), 0, dp(2), 0) }) }
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
            0 -> {
                // Light, colour and finishing as dials: pick one, set it with the slider; tap a picked dial again to reset it.
                val names = EditorKit.ADJUSTMENTS.dropLast(1) + listOf("ขอบมืด", "เกรนฟิล์ม", "คมชัด")
                val icons = EditorKit.ADJUST_ICONS.take(6) + listOf(EditorKit.ADJUST_ICONS[6], "M6 7h.01 M12 5h.01 M18 8h.01 M8 13h.01 M15 12h.01 M6 18h.01 M12 17h.01 M18 16h.01", "M12 4l8 16H4z")
                fun get(k: Int) = if (k < 6) adjust[k] else fx[k - 6]
                fun set(k: Int, v: Float) { if (k < 6) adjust[k] = v else fx[k - 6] = v.coerceAtLeast(0f) }
                val value = text("", 12.5f, Glass.TEXT2, true).apply { gravity = Gravity.CENTER }
                val dials = names.mapIndexed { k, n -> EditorKit.Dial(this, icons[k], n).apply { amount = get(k) } }
                val slider = GlassSlider(this).apply { values = (-100..100).map { if (it > 0) "+$it" else "$it" }; grad = intArrayOf(0xff8e8e93.toInt(), Color.WHITE, Glass.ACCENT) }
                fun show(k: Int) { adjustIndex = k; dials.forEachIndexed { j, d -> d.lit = j == k }; value.text = "${names[k]}  ${sign(get(k))}"; slider.index = (get(k) * 100).roundToInt() + 100; slider.contentDescription = names[k] }
                body.addView(EditorKit.strip(this, dials.map { it to 58 }, adjustIndex.coerceIn(0, names.size - 1), 0, { k -> show(k) },
                    tapAgain = { k -> set(k, 0f); dials[k].amount = 0f; show(k); applyLook() }) { k -> show(k) }, LinearLayout.LayoutParams(-1, dp(56)))
                body.addView(value, -1, -2)
                slider.onChange = { i -> set(adjustIndex, (i - 100) / 100f); dials[adjustIndex].amount = get(adjustIndex); value.text = "${names[adjustIndex]}  ${sign(get(adjustIndex))}"; applyLook() }
                body.addView(slider, LinearLayout.LayoutParams(-1, dp(40)).apply { setMargins(dp(12), 0, dp(12), 0) })
                show(adjustIndex.coerceIn(0, names.size - 1))
            }
            1 -> { // Colour wheels as three sliders each: which colour to push into the shadows, midtones or highlights, how strongly, and how much brighter or darker.
                chips(listOf("เงา", "กลาง", "สว่าง"), null, wheelRange) { wheelRange = it; showTool(COLOR_TOOL, false) }
                val k = wheelRange * 3
                slider("สีที่เติม", grade.wheels[k], 0, rainbow) { grade.wheels[k] = it }
                slider("ความแรงสี", grade.wheels[k + 1], 0, dim) { grade.wheels[k + 1] = it }
                slider("ความสว่าง", grade.wheels[k + 2], -100, dim) { grade.wheels[k + 2] = it }
            }
            2 -> {
                chips(listOf("ทั้งหมด", "แดง", "เขียว", "น้ำเงิน"), null, curveChannel) { curveChannel = it; showTool(COLOR_TOOL, false) }
                val edit = CurveEdit(this).apply { points = grade.curves; at = curveChannel * 5; color = intArrayOf(Color.WHITE, 0xffff5a5a.toInt(), 0xff52d96b.toInt(), 0xff5a9bff.toInt())[curveChannel]
                    onChange = { applyLook() } }
                body.addView(edit, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(dp(24), dp(2), dp(24), dp(2)) })
            }
            3 -> {
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

    // ---------- Cut menu: works on whatever is picked on the timeline ----------
    /** True when a bar under the clips (a layer, text, sound or music) is picked; false when a clip is. */
    private var barActive = false
    /** Picks the bar of layer [i] on the timeline and opens the menu that edits it, unless the cut menu is open. */
    private fun pickBar(i: Int) {
        val l = layers.getOrNull(i) ?: return
        barActive = true; layerPick = i
        val home = when (l.kind) { VideoLayer.TEXT -> if (VideoLayers.shapeOf(l.text) >= 0) 12 else 4; VideoLayer.SOUND -> 9; else -> LAYER_TOOL }
        if (tool == 0 || tool == home || (tool == 15 && l.caption) || (tool == 16 && l.kind == VideoLayer.SOUND)) showTool(tool, false) else showTool(home)
        placeLayers(); refreshTimeline()
    }
    private fun pickClip() { if (barActive) { barActive = false; if (tool == 0 || tool == 2 || tool == 9) showTool(tool, false); refreshTimeline() } }
    /** Splits layer [i] at [t] (ms of the video) into two layers that follow each other; keyframes go to the half they belong to. */
    private fun splitLayer(i: Int, t: Long) {
        val l = layers[i]; val off = t - l.at
        if (off < 200 || l.length - off < 200) { notice("เลื่อนหัวอ่านให้อยู่ในแถบนี้ ห่างจากขอบอย่างน้อย 0.2 วินาที"); return }
        val first = l.withKeys(l.keyList.filter { it.t <= off }).copy(length = off, fadeOut = 0)
        val second = l.withKeys(l.keyList.filter { it.t >= off }.map { VideoLayer.Key(it.t - off, it.x, it.y, it.size, it.opacity, it.ease) })
            .copy(at = t, length = l.length - off, from = if (l.still) l.from else l.from + off, fadeIn = 0)
        layers[i] = first; layers.add(i + 1, second); layerPick = i + 1; rebuildLayers(); refreshTimeline()
    }
    /** Cuts the start (or the end) of layer [i] off at [t]. */
    private fun trimLayer(i: Int, t: Long, head: Boolean) {
        val l = layers[i]; val off = t - l.at
        if (off < 100 || l.length - off < 100) { notice("เลื่อนหัวอ่านให้อยู่ในแถบนี้ก่อน"); return }
        layers[i] = if (head) l.withKeys(l.keyList.filter { it.t >= off }.map { VideoLayer.Key(it.t - off, it.x, it.y, it.size, it.opacity, it.ease) })
            .copy(at = t, length = l.length - off, from = if (l.still) l.from else l.from + off)
        else l.withKeys(l.keyList.filter { it.t <= off }).copy(length = off)
        rebuildLayers(); refreshTimeline()
    }
    /** Cuts the start ([head]) or the end of the clip on screen off at the playhead. */
    private fun trimClip(head: Boolean) {
        val e = extras.getOrNull(playIndex - 1); val at = trim.playhead
        if (e != null) {
            if (head) { if (e.to - at < 100) { notice("หัวอ่านอยู่ใกล้ท้ายคลิปเกินไป"); return }; e.from = at.coerceIn(0, e.to - 100) }
            else { if (at - e.from < 100) { notice("หัวอ่านอยู่ใกล้ต้นคลิปเกินไป"); return }; e.to = at.coerceIn(e.from + 100, e.duration) }
            showClipOnBar(); jumpTo(playIndex, if (head) e.from else (e.to - 1).coerceAtLeast(e.from))
        } else {
            if (head) { if (end - at < 100) { notice("หัวอ่านอยู่ใกล้ท้ายคลิปเกินไป"); return }; setRange(at, end) }
            else { if (at - start < 100) { notice("หัวอ่านอยู่ใกล้ต้นคลิปเกินไป"); return }; setRange(start, at) }
        }
        showTime(); Glass.tick(timeline)
    }
    /** Extra cut actions for bars that play a file: pick another part of the file without moving the bar, and move a video layer's sound to its own bar. */
    private fun barExtras(l: VideoLayer): List<View> {
        if (l.still) return emptyList()
        val i = layerPick; val out = ArrayList<View>()
        fun slip(d: Long) { val c = layers[i]; val cap = layerLength.getOrPut(c.source) { VideoLayers.duration(this, c.source) }
            val from = (c.from + d).coerceIn(0, (cap - c.length).coerceAtLeast(0))
            if (from == c.from) notice(if (d < 0) "อยู่ที่ต้นไฟล์แล้ว" else "อยู่ที่ท้ายไฟล์แล้ว") else { layers[i] = c.copy(from = from); releaseLayerSound(); refreshTimeline(); notice("เล่นตั้งแต่ ${clock(from)} ของไฟล์") } }
        out += EditorKit.action(this, "M9 7l-5 5 5 5 M4 12h12 M20 5v14", "เนื้อหา −1 วิ") { slip(-1000) }
        out += EditorKit.action(this, "M15 7l5 5-5 5 M20 12H8 M4 5v14", "เนื้อหา +1 วิ") { slip(1000) }
        if (l.kind == VideoLayer.VIDEO && l.volume > 0f && VideoLayers.hasAudio(this, l.source)) out += EditorKit.action(this, "M4 12h6 M14 12h6 M10 8v8 M14 8v8", "แยกเสียง") {
            val c = layers[i]; layers[i] = c.copy(volume = 0f)
            layers += VideoLayer(VideoLayer.SOUND, c.source, c.at, c.length, c.from, volume = c.volume, fadeIn = c.fadeIn, fadeOut = c.fadeOut)
            layerPick = layers.size - 1; releaseLayerSound(); rebuildLayers(); refreshTimeline(); showTool(0, false); notice("แยกเสียงเป็นแถบสีม่วงแล้ว") }
        return out
    }
    private fun cutTools(col: LinearLayout) {
        val l = layers.getOrNull(layerPick)
        if (barActive && l != null) {
            col.addView(text("แถบ: " + layerName(l), 12f, Glass.TEXT2, true).apply { maxLines = 1; gravity = Gravity.CENTER; setPadding(dp(16), 0, dp(16), dp(6)) }, -1, -2)
            col.addView(EditorKit.actionRow(this, listOf(
                EditorKit.action(this, EditorKit.SPLIT, "แยกตรงนี้") { splitLayer(layerPick, outNow()); showTool(0, false) },
                EditorKit.action(this, EditorKit.START, "เริ่มที่นี่") { trimLayer(layerPick, outNow(), true); showTool(0, false) },
                EditorKit.action(this, EditorKit.END, "จบที่นี่") { trimLayer(layerPick, outNow(), false); showTool(0, false) },
                EditorKit.action(this, "M8 8h11v11H8z M5 16V5h11", "ทำสำเนา") { val c = layers[layerPick]; val total = totalOut()
                    layers.add(layerPick + 1, c.copy(at = c.end.coerceAtMost((total - c.length).coerceAtLeast(0)))); layerPick++; rebuildLayers(); refreshTimeline(); showTool(0, false) },
                EditorKit.action(this, "M5 7h14 M9 7V4h6v3 M7 7l1 13h8l1-13", "ลบ") { layers.removeAt(layerPick); layerPick = -1; barActive = false; rebuildLayers(); refreshTimeline(); showTool(0, false) }) + barExtras(l)), -1, -2)
            return
        }
        val e = extras.getOrNull(playIndex - 1)
        val list = mutableListOf(
            EditorKit.action(this, EditorKit.SPLIT, "แยกตรงนี้") { splitAtPlayhead() },
            EditorKit.action(this, EditorKit.START, "เริ่มที่นี่") { trimClip(true) },
            EditorKit.action(this, EditorKit.END, "จบที่นี่") { trimClip(false) },
            EditorKit.action(this, Icons.RESET, "ความยาวเดิม") { if (e != null) { e.from = 0; e.to = e.duration; showClipOnBar() } else { setRange(0, duration); cutFrom = -1; cutTo = -1 }; showTime(); showTool(0, false) },
            EditorKit.action(this, "M8 8h11v11H8z M5 16V5h11", "ทำสำเนา") {
                if (e != null) { extras.add(playIndex, e.copy()); if (playIndex - 1 < mainPos()) mainAt = mainPos() + 1 }
                else { extraCache.getOrPut(source.toString()) { Extra(source, duration, videoW, videoH, "คลิปแรก", firstFrame) }
                    extras.add(mainPos(), Extra(source, duration, videoW, videoH, "คลิปแรก", firstFrame).also { it.from = start; it.to = end; it.speed = speed; it.curve = mainCurve?.copyOf(); it.volume = volume }) }
                showTime(); showTool(0, false) },
            EditorKit.action(this, "M4 12h6 M14 12h6 M10 8v8 M14 8v8", "แยกเสียง") { detachSound(); showTool(0, false) },
            EditorKit.action(this, "M3 5h18v14H3z M12 5v14", "หาฉาก") { findScenes() },
            EditorKit.action(this, "M7 4v16 M7 5h10l-2.5 3.5L17 12H7", "แยกตามมาร์กเกอร์") { splitAtMarkers() },
            EditorKit.action(this, "M3 12h3 M18 12h3 M8 8v8 M12 5v14 M16 8v8", "ตัดช่วงเงียบ") { cutSilence() })
        if (e != null) {
            // Slip: the clip keeps its length and place, and shows an earlier or later part of its file.
            list += EditorKit.action(this, "M9 7l-5 5 5 5 M4 12h12 M20 5v14", "เนื้อหา −0.5 วิ") { val d = minOf(500L, e.from); if (d <= 0) notice("อยู่ที่ต้นไฟล์แล้ว") else { e.from -= d; e.to -= d; showClipOnBar(); jumpTo(playIndex, (trim.playhead - d).coerceIn(e.from, e.to)); showTime() } }
            list += EditorKit.action(this, "M15 7l5 5-5 5 M20 12H8 M4 5v14", "เนื้อหา +0.5 วิ") { val d = minOf(500L, e.duration - e.to); if (d <= 0) notice("อยู่ที่ท้ายไฟล์แล้ว") else { e.from += d; e.to += d; showClipOnBar(); jumpTo(playIndex, (trim.playhead + d).coerceIn(e.from, e.to)); showTime() } }
        }
        list += EditorKit.action(this, "M5 7h14 M9 7V4h6v3 M7 7l1 13h8l1-13", "ลบ") {
            if (e == null) deleteMain() else { val c = playIndex - 1; extras.removeAt(c); if (c < mainPos()) mainAt = mainPos() - 1
                playIndex = order().getOrNull(order().indexOf(0).coerceAtLeast(0)) ?: 0; jumpTo(0, start); showTime(); showTool(0, false) } }
        col.addView(EditorKit.actionRow(this, list), -1, -2)
    }

    // ---------- Tools ----------
    private fun showTool(req: Int, animate: Boolean = true) {
        // Older menus now live inside others: split-file and clips in cut, music in sound, adjust in the image menu.
        val i = when (req) { 1, 10 -> 0; 6 -> 9; 7 -> COLOR_TOOL; else -> req }
        val dir = if (animate) TOOL_IDS.indexOf(i).compareTo(TOOL_IDS.indexOf(tool)) else 0
        if (tool != i) toolScroll.clear()
        tool = i; toolBar.select(TOOL_IDS.indexOf(i).coerceAtLeast(0), animate)
        // The layer lanes need room; the preview gives way smoothly.
        if (i != COLOR_TOOL) { handler.removeCallbacks(histTick); hist = null }
        val want = dp(when (i) { LAYER_TOOL -> 312; COLOR_TOOL -> 286; 9 -> 260; 2 -> if (speedTab == 1) 276 else 178; 4 -> 236; 3 -> 150; 5 -> 196; 12, 15, 16 -> 220; else -> 124 })
        if (tools.layoutParams.height != want) {
            if (!animate || Glass.reduced(this)) { tools.layoutParams.height = want; tools.requestLayout() }
            else android.animation.ValueAnimator.ofInt(tools.layoutParams.height, want).apply { duration = 260; interpolator = Glass.EASE
                addUpdateListener { tools.layoutParams.height = it.animatedValue as Int; tools.requestLayout() } }.start()
        }
        if (i != LAYER_TOOL) lanes = null
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        fun note(s: String) = col.addView(text(s, 12f, Glass.TEXT2, true).apply { maxLines = 1; gravity = Gravity.CENTER; setPadding(dp(16), 0, dp(16), dp(6)) }, -1, -2)
        when (i) {
            0 -> cutTools(col)
            -100 -> { if (cutValid()) note("ตัดช่วง ${clock(cutFrom - start)}–${clock(cutTo - start)} ออก")
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
            3 -> cropTools(col)
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
            11 -> transitionTools(col)
            12 -> stickerTools(col)
            15 -> captionTools(col)
            16 -> voiceTools(col)
            else -> layerTools(col)
        }
        EditorKit.swap(tools, col, dir)
    }
    /** Speed as one slider, like the photo adjustments: 0.1× (slow) through 1× to 500× (fast). */
    private var speedTab = 0
    /** Speed of the clip on screen: a steady speed (any value, or set by the length the clip should take) or a speed curve. */
    private fun speedTools(col: LinearLayout) {
        col.gravity = Gravity.TOP
        val e = extras.getOrNull(playIndex - 1)
        val src = if (e != null) e.to - e.from else end - start
        fun base() = e?.speed ?: speed
        fun curve() = if (e != null) e.curve else mainCurve
        fun setBase(v: Float) { if (e != null) e.speed = v else speed = v; showTime(); player?.let { p -> if (p.isPlaying) runCatching { p.playbackParams = p.playbackParams.setSpeed(v.coerceIn(0.1f, 8f)) } } }
        fun setCurve(c: FloatArray?) { if (e != null) e.curve = c else { mainCurve = c; if (c != null) smooth = false }; curveSpeedSet = 0f; showTime() }
        val head = text("คลิป ${order().indexOf(playIndex) + 1} · ยาว ${clock(lenOf(playIndex))}" + (if (curve() == null) " · ${speedName(base())}" else " · เส้นโค้ง"), 13f, Glass.TEXT2, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(2), 0, dp(4)) }
        col.addView(head, -1, -2)
        if (e == null && cutValid()) { col.addView(text("คลิปนี้ตัดช่วงกลางออกไว้ จึงใช้ได้เฉพาะความเร็วคงที่", 11.5f, Glass.TEXT3).apply { gravity = Gravity.CENTER }, -1, -2) }
        val tabs = LinearLayout(this).apply { gravity = Gravity.CENTER }
        listOf("ความเร็วคงที่", "เส้นโค้งความเร็ว").forEachIndexed { i, n -> val on = i == speedTab
            tabs.addView(text(n, 12.5f, if (on) Glass.ACCENT else Color.WHITE, on).apply { gravity = Gravity.CENTER; background = glass(16, if (on) Glass.ACCENT_SOFT else 0x1fffffff); setPadding(dp(14), 0, dp(14), 0)
                isClickable = true; glassTouch(); contentDescription = n; setOnClickListener { if (i == 1 && e == null && cutValid()) notice("เอาการตัดช่วงกลางออกก่อน") else { speedTab = i; showTool(2, false) } } }, LinearLayout.LayoutParams(-2, dp(32)).apply { setMargins(dp(3), 0, dp(3), 0) }) }
        col.addView(tabs, -1, -2)
        if (speedTab == 0) {
            val slider = GlassSlider(this).apply { values = SPEEDS.map { speedName(it) }; index = SPEEDS.indices.minByOrNull { abs(SPEEDS[it] - base()) } ?: 10
                grad = intArrayOf(0xff64d2ff.toInt(), Color.WHITE, Glass.ACCENT); contentDescription = "ความเร็ววิดีโอ"
                onChange = { k -> setCurve(null); setBase(SPEEDS[k]); head.text = "คลิป ${order().indexOf(playIndex) + 1} · ยาว ${clock(lenOf(playIndex))} · ${speedName(SPEEDS[k])}" } }
            col.addView(slider, LinearLayout.LayoutParams(-1, dp(44)).apply { setMargins(dp(12), dp(2), dp(12), 0) })
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
            // Any length: the speed follows from how long the clip should take, down to a hundredth of a second.
            row.addView(EditorKit.chip(this, "กำหนดความยาว", "M12 7v5l3 2 M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18") {
                val field = GlassDialog.field(this, "วินาที", String.format(Locale.US, "%.2f", lenOf(playIndex) / 1000f), true); field.selectAll()
                GlassDialog.ask(this, "ความยาวของคลิป", "M12 7v5l3 2 M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18", "ตั้งได้ตั้งแต่ 0.01 วินาที · ความเร็วจะปรับตามเอง", field, focus = field) {
                    val sec = field.text.toString().toFloatOrNull()
                    if (sec == null || sec < 0.01f) { notice("ใส่ตัวเลขตั้งแต่ 0.01 ขึ้นไป"); false } else { setCurve(null); setBase((src / (sec * 1000f)).coerceIn(0.001f, 5000f)); showTool(2, false); true } } }, LinearLayout.LayoutParams(-2, dp(34)).apply { setMargins(dp(3), 0, dp(3), 0) })
            row.addView(EditorKit.chip(this, "ใส่ตัวเลขความเร็ว", EditorKit.SPEED) {
                val field = GlassDialog.field(this, "เท่า", base().toString(), true); field.selectAll()
                GlassDialog.ask(this, "ความเร็ว (เท่า)", EditorKit.SPEED, "0.1 ช้าลงสิบเท่า · 2 เร็วขึ้นสองเท่า", field, focus = field) {
                    val v = field.text.toString().toFloatOrNull()
                    if (v == null || v <= 0f) { notice("ใส่ตัวเลขมากกว่า 0"); false } else { setCurve(null); setBase(v.coerceIn(0.001f, 5000f)); showTool(2, false); true } } }, LinearLayout.LayoutParams(-2, dp(34)).apply { setMargins(dp(3), 0, dp(3), 0) })
            col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(4) })
            val sp = allSpeeds()
            if (hasAudio && sp.any { it !in SOUND_MIN..SOUND_MAX }) col.addView(text("มีบางคลิปช้ากว่า 0.25× หรือเร็วกว่า 4× · วิดีโอที่ส่งออกจะไม่มีเสียงคลิป", 11f, Glass.TEXT3).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(4), dp(16), 0) }, -1, -2)
            if (e == null && speed < 1f && mainCurve == null) col.addView(EditorKit.chip(this, if (smooth) "สโลว์ลื่น (Optical flow) · เปิด" else "สโลว์ลื่น (Optical flow) · ปิด", SMOOTH, smooth) {
                smooth = !smooth; showTool(2, false)
                if (smooth) notice("สร้างเฟรมแทรก ${SlowFlow.factor(speed) - 1} เฟรมต่อเฟรมตอนส่งออก · ใช้เวลานานขึ้น · ไม่มีเสียงคลิป")
            }, LinearLayout.LayoutParams(-2, dp(32)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(4) })
        } else {
            // Ready-made curves (the middle line is the clip's own speed; up is faster, down is slower), then bend it by hand.
            val presets = listOf("มอนทาจ" to floatArrayOf(0.8f, 0.75f, 0.25f, 0.75f, 0.8f), "ฮีโร่" to floatArrayOf(0.5f, 0.5f, 0.1f, 0.5f, 0.5f),
                "บูลเล็ต" to floatArrayOf(0.85f, 0.85f, 0.05f, 0.85f, 0.85f), "ค่อย ๆ เร็วขึ้น" to floatArrayOf(0.35f, 0.45f, 0.55f, 0.7f, 0.85f),
                "ค่อย ๆ ช้าลง" to floatArrayOf(0.85f, 0.7f, 0.55f, 0.45f, 0.35f), "จางเข้า-ออก" to floatArrayOf(0.25f, 0.5f, 0.6f, 0.5f, 0.25f))
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), 0, dp(10), 0) }
            presets.forEach { (n, c) -> row.addView(EditorKit.chip(this, n, curveIcon(c), curve()?.contentEquals(c) == true) { setCurve(c.copyOf()); showTool(2, false) }, LinearLayout.LayoutParams(-2, dp(32)).apply { setMargins(dp(3), 0, dp(3), 0) }) }
            if (curve() != null) row.addView(EditorKit.chip(this, "ล้างเส้นโค้ง", Icons.CLOSE) { setCurve(null); showTool(2, false) }, LinearLayout.LayoutParams(-2, dp(32)).apply { setMargins(dp(3), 0, dp(3), 0) })
            col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            val points = curve()?.copyOf() ?: floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f, 0.5f)
            val edit = CurveEdit(this).apply { this.points = points; at = 0; color = Glass.ACCENT; contentDescription = "เส้นโค้งความเร็ว ลากจุดขึ้นเพื่อเร็วขึ้น ลงเพื่อช้าลง"
                onChange = { setCurve(points.copyOf()); head.text = "คลิป ${order().indexOf(playIndex) + 1} · ยาว ${clock(lenOf(playIndex))} · เส้นโค้ง" } }
            col.addView(edit, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(dp(24), dp(4), dp(24), 0) })
            col.addView(LinearLayout(this).apply { setPadding(dp(24), 0, dp(24), 0)
                addView(text("ช้าลง 8×", 10.5f, Glass.TEXT3), LinearLayout.LayoutParams(0, -2, 1f)); addView(text("เส้นกลาง = ${speedName(base())}", 10.5f, Glass.TEXT3).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(text("เร็วขึ้น 8×", 10.5f, Glass.TEXT3).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f)) }, -1, -2)
        }
    }
    private fun speedName(s: Float) = (if (s == s.toInt().toFloat()) "${s.toInt()}" else if (s >= 10) String.format(Locale.US, "%.1f", s) else String.format(Locale.US, "%.2f", s).trimEnd('0').trimEnd('.')) + "×"
    private fun setRange(s: Long, e: Long) {
        if (e - s < 100) { notice("ช่วงสั้นเกินไป"); return }
        start = s; end = e; if (split !in (s + 300)..(e - 300)) split = -1
        trim.start = s; trim.end = e; trim.split = split; seek(s)
    }

    private val menu by lazy { GlassMenu(window.decorView as FrameLayout) }
    /** A glass pill with a label and a small down arrow; tapping it opens a [GlassMenu] list, like the other choosers in the app. */
    /** A small screen outline with a dot where the text will sit, so each position row shows its place. */
    /** The preset drawn as a tiny graph (up = faster) so each chip shows its own shape. */
    private fun curveIcon(c: FloatArray): String = c.indices.joinToString(" ") { i -> String.format(Locale.US, "%s%.1f %.1f", if (i == 0) "M" else "L", 3f + 18f * i / (c.size - 1), 20f - 16f * c[i]) } + " M3 20h18"
    private fun posIcon(x: Float, y: Float): String { val px = 4f + 16f * x; val py = 6f + 12f * y; return "M4 6h16v12H4z M$px $py h.01 M${px - 2.5f} $py h5" }
    private fun dropButton(label: String, click: (View) -> Unit): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; background = glass(16, 0x26ffffff); setPadding(dp(12), 0, dp(10), 0); isClickable = true; glassTouch(); contentDescription = label
        addView(text(label, 12.5f, Glass.TEXT, true).apply { maxLines = 1 })
        addView(IconView(this@VideoEditorActivity, "M7 10l5 5 5-5", 2f).apply { tint = Glass.TEXT2 }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { leftMargin = dp(4) })
        setOnClickListener { click(this) }
    }
    private val SWATCHES = intArrayOf(Color.WHITE, Color.BLACK, 0xff8e8e93.toInt(), 0xffff453a.toInt(), Glass.ACCENT, 0xffffd60a.toInt(), 0xff30d158.toInt(), 0xff64d2ff.toInt(),
        0xff0a84ff.toInt(), 0xff5e5ce6.toInt(), 0xffbf5af2.toInt(), 0xffff6fb5.toInt(), 0xffac8e68.toInt(), 0xff1c3d5a.toInt())
    /** Colour picker: hue, saturation and brightness sliders with a live swatch and its hex code. */
    private fun pickColor(initial: Int, done: (Int) -> Unit) {
        val hsv = FloatArray(3); Color.colorToHSV(initial, hsv)
        val swatch = View(this); val hex = text("", 14f, Glass.TEXT, true).apply { gravity = Gravity.CENTER }
        fun paint() { val c = Color.HSVToColor(hsv); swatch.background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(c); setStroke(dp(1), 0x55ffffff) }; hex.text = String.format("#%06X", c and 0xffffff) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(10), dp(20), 0) }
        box.addView(LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; addView(swatch, dp(56), dp(40)); addView(hex, LinearLayout.LayoutParams(0, -2, 1f)) }, -1, -2)
        fun row(name: String, grad: IntArray, at: Int, set: (Int) -> Unit) {
            box.addView(text(name, 12f, Glass.TEXT2, true).apply { setPadding(0, dp(10), 0, 0) })
            box.addView(GlassSlider(this).apply { values = (0..100).map { "$it" }; this.grad = grad; index = at; contentDescription = name; onChange = { set(it); paint() } }, LinearLayout.LayoutParams(-1, dp(40)))
        }
        row("เฉดสี", intArrayOf(0xffff3b30.toInt(), 0xffffcc00.toInt(), 0xff34c759.toInt(), 0xff32ade6.toInt(), 0xff5856d6.toInt(), 0xffff2d55.toInt(), 0xffff3b30.toInt()), (hsv[0] / 3.6f).roundToInt()) { hsv[0] = it * 3.6f }
        row("ความอิ่มสี", intArrayOf(Color.WHITE, Glass.ACCENT), (hsv[1] * 100).roundToInt()) { hsv[1] = it / 100f }
        row("ความสว่าง", intArrayOf(Color.BLACK, Color.WHITE), (hsv[2] * 100).roundToInt()) { hsv[2] = it / 100f }
        paint()
        (box.parent as? android.view.ViewGroup)?.removeView(box); GlassDialog.ask(this, "เลือกสี", "M12 3a9 9 0 1 0 0 18c1.5 0 2-1 1.5-2s0-2 1.5-2h2a3 3 0 0 0 3-3 9 9 0 0 0-8-11z", null, box, "ใช้สีนี้") { done(Color.HSVToColor(hsv)); true }
    }
    /** A row of colour dots plus a rainbow dot that opens [pickColor]. */
    private fun colorRow(now: Int, set: (Int) -> Unit): View {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(4), dp(12), dp(4)) }
        row.addView(View(this).apply { isClickable = true; contentDescription = "เลือกสีเอง"
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xffff3b30.toInt(), 0xffffcc00.toInt(), 0xff34c759.toInt(), 0xff0a84ff.toInt(), 0xffbf5af2.toInt())).apply { shape = GradientDrawable.OVAL; setStroke(dp(if (now !in SWATCHES) 3 else 1), if (now !in SWATCHES) Color.WHITE else 0x66ffffff) }
            setOnClickListener { pickColor(now, set) } }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(8) })
        SWATCHES.forEach { c -> row.addView(View(this).apply { isClickable = true; contentDescription = "สี"; setOnClickListener { Glass.tick(this); set(c) }
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); setStroke(dp(if (c == now) 3 else 1), if (c == now) Glass.ACCENT else 0x66ffffff) } },
            LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(8) }) }
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    }
    /** Text on the video: write and add, then font, place, alignment and style from drop-down lists, and the colour. Edits the text picked on the timeline. */
    private fun textTools(col: LinearLayout) {
        col.gravity = Gravity.TOP
        // The single caption of older projects becomes a text layer, so all text is edited the same way.
        if (caption.isNotBlank()) { val c = caption; caption = ""; applyLook(); handler.post { addText(c, if (place == 0) 0.15f else if (place == 1) 0.5f else 0.85f, 0L, totalOut()) }; return }
        val i = layerPick; val cur = layers.getOrNull(i)?.takeIf { barActive && it.kind == VideoLayer.TEXT && VideoLayers.shapeOf(it.text) < 0 }
        val field = EditText(this).apply {
            hint = if (cur == null) "พิมพ์ข้อความใหม่" else "แก้ข้อความ"; setHintTextColor(Glass.TEXT3); setTextColor(Glass.TEXT); textSize = 15f; maxLines = 3; setText(cur?.text ?: "")
            background = glass(20, 0x24ffffff); setPadding(dp(14), dp(8), dp(14), dp(8)); contentDescription = "ข้อความบนวิดีโอ"
            if (cur != null) addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { val w = s.toString(); handler.removeCallbacks(textEdit); textEdit = Runnable { if (w.isNotBlank() && i in layers.indices) { retext(i, words = w, rebuildTool = false) } }; handler.postDelayed(textEdit, 350) }
            })
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(2), dp(14), dp(6)) }
        top.addView(field, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(EditorKit.chip(this, if (cur == null) "เพิ่ม" else "เพิ่มใหม่", ADD, cur == null) {
            val w = if (cur == null) field.text.toString().trim() else ""
            if (w.isEmpty()) askText("") { addText(it) } else addText(w)
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(field.windowToken, 0) }, LinearLayout.LayoutParams(-2, dp(40)).apply { leftMargin = dp(8) })
        col.addView(top, -1, -2)
        if (cur == null) { col.addView(text("พิมพ์แล้วแตะ \"เพิ่ม\" · แตะแถบสีเหลืองบนไทม์ไลน์เพื่อแก้ข้อความที่มีอยู่", 11.5f, Glass.TEXT3).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(10), dp(16), 0) }, -1, -2); return }
        val drops = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, dp(14), 0) }
        fun drop(v: View) = drops.addView(v, LinearLayout.LayoutParams(-2, dp(36)).apply { rightMargin = dp(8) })
        val fi = VideoLayers.font(cur.style).coerceIn(0, VideoLayers.FONT_NAMES.size - 1)
        drop(dropButton("แบบอักษร: " + VideoLayers.FONT_NAMES[fi]) { v -> menu.show(v, "แบบอักษร", VideoLayers.FONT_NAMES.map { GlassMenu.Option(it, EditorKit.TEXT) }, fi) { k -> retext(i, style = (layers[i].style and 0xF00.inv()) or (k shl 8)) } })
        val places = listOf("บนกลาง" to (0.5f to 0.14f), "กลางภาพ" to (0.5f to 0.5f), "ล่างกลาง" to (0.5f to 0.86f), "บนซ้าย" to (0.27f to 0.14f), "บนขวา" to (0.73f to 0.14f), "ล่างซ้าย" to (0.27f to 0.86f), "ล่างขวา" to (0.73f to 0.86f))
        drop(dropButton("ตำแหน่ง") { v -> menu.show(v, "ตำแหน่งข้อความ", places.map { GlassMenu.Option(it.first, posIcon(it.second.first, it.second.second)) }, -1) { k -> layers[i] = posed(layers[i], x = places[k].second.first, y = places[k].second.second); placeLayers() } })
        val aligns = listOf("จัดกลาง", "ชิดซ้าย", "ชิดขวา"); val ai = VideoLayers.align(cur.style).coerceIn(0, 2)
        drop(dropButton(aligns[ai]) { v -> menu.show(v, "จัดบรรทัด", aligns.map { GlassMenu.Option(it) }, ai) { k -> retext(i, style = (layers[i].style and 0x30000.inv()) or (k shl 16)) } })
        val styles = listOf("กล่องพื้น" to 1, "ขอบตัวอักษร" to 2, "ตัวบาง" to 4)
        drop(dropButton("สไตล์") { v -> menu.show(v, "สไตล์ (แตะเพื่อเปิดหรือปิด)", styles.map { (n, bit) -> GlassMenu.Option(n, if (layers[i].style and bit != 0) Icons.CHECK else "") }, -1) { k -> retext(i, style = layers[i].style xor styles[k].second) } })
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(drops) }, -1, -2)
        col.addView(colorRow(cur.color) { c -> retext(i, color = c) }, -1, -2)
        val p = cur.rawPose(outNow().coerceIn(cur.at, cur.end))
        val sizeRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(10), 0) }
        val sizeLabel = text("ขนาด ${(p[2] * 100).roundToInt()}%", 12f, Glass.TEXT2, true).apply { minWidth = dp(84) }
        sizeRow.addView(sizeLabel)
        sizeRow.addView(GlassSlider(this).apply { values = (5..100).map { "$it%" }; index = ((p[2] * 100).roundToInt() - 5).coerceIn(0, 95); contentDescription = "ขนาดข้อความ"
            onChange = { k -> sizeLabel.text = "ขนาด ${k + 5}%"; layers[i] = posed(layers[i], size = (k + 5) / 100f); placeLayers() } }, LinearLayout.LayoutParams(0, dp(38), 1f))
        col.addView(sizeRow, -1, -2)
    }
    private var textEdit = Runnable {}

    private fun cropTools(col: LinearLayout) {
        val names = CROP_ORDER.map { k -> CROPS[k].first.let { if (CROPS[k].second < 0f) "กำหนดเอง" else it } }
        val custom = "M4 9V4h5 M15 4h5v5 M20 15v5h-5 M9 20H4v-5"
        val items = CROP_ORDER.mapIndexed { j, k -> EditorKit.StripItem(this, when { k == 0 -> Icons.VIDEO; CROPS[k].second < 0f -> custom; else -> aspectIconPath(CROPS[k].first) },
            if (CROPS[k].second < 0f && cropIndex == k) cropName(customCrop) else names[j]) }
        fun askCustom() {
            val w = GlassDialog.field(this, "กว้าง", cropParts(customCrop).first, true)
            val h = GlassDialog.field(this, "สูง", cropParts(customCrop).second, true)
            val box = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; addView(w, LinearLayout.LayoutParams(0, -2, 1f)); addView(text(" : ", 18f), -2, -2); addView(h, LinearLayout.LayoutParams(0, -2, 1f)) }
            GlassDialog.ask(this, "สัดส่วนที่ต้องการ", EditorKit.CROP, "ใส่กว้าง : สูง เช่น 16 : 9", box, focus = w) {
                val a2 = w.text.toString().toFloatOrNull(); val b2 = h.text.toString().toFloatOrNull()
                if (a2 == null || b2 == null || a2 <= 0f || b2 <= 0f || a2 / b2 !in 0.1f..10f) { notice("ใส่ตัวเลขที่สัดส่วนอยู่ระหว่าง 1:10 ถึง 10:1"); false } else { customCrop = a2 / b2; cropIndex = CROPS.indexOfFirst { it.second < 0f }; layoutVideo(); showTool(3, false); true } }
        }
        col.addView(EditorKit.strip(this, items.map { it to 64 }, CROP_ORDER.indexOf(cropIndex).coerceAtLeast(0), 60, { j -> items.forEachIndexed { q, v -> v.lit(q == j) } }, tapAgain = { j -> if (CROPS[CROP_ORDER[j]].second < 0f) askCustom() }) { j ->
            val k = CROP_ORDER[j]; if (CROPS[k].second < 0f && cropIndex != k) askCustom() else { cropIndex = k; layoutVideo() } }, LinearLayout.LayoutParams(-1, dp(76)))
        col.addView(text(if (CROPS[cropIndex].second < 0f) "สัดส่วน ${cropName(customCrop)} · แตะ \"กำหนดเอง\" อีกครั้งเพื่อแก้" else "แตะ \"กำหนดเอง\" เพื่อใส่สัดส่วนเอง", 11.5f, Glass.TEXT3).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) }, -1, -2)
    }
    private fun cropParts(r: Float): Pair<String, String> { for (b in 1..20) { val a = r * b; if (abs(a - a.roundToInt()) < 0.02f) return a.roundToInt().toString() to b.toString() }; return String.format(Locale.US, "%.2f", r) to "1" }
    private fun cropName(r: Float) = cropParts(r).let { "${it.first}:${it.second}" }
    private var filmGroup = ""
    private fun filmTools(col: LinearLayout) {
        // Families first, the same ones as the film mode of the camera; the strip shows the films of the chosen family.
        if (filmGroup.isEmpty()) filmGroup = film?.group ?: Films.groups.first()
        val tabs = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), 0, dp(10), 0) }
        Films.groups.forEach { g -> val on = g == filmGroup
            tabs.addView(LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; background = if (on) GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0x33ff9f45); setStroke(dp(1), Glass.ACCENT) } else glass(16, 0x1fffffff)
                setPadding(dp(10), 0, dp(12), 0); isClickable = true; glassTouch(); contentDescription = "กลุ่ม $g"
                addView(IconView(this@VideoEditorActivity, Films.groupIcon(g), 1.9f).apply { tint = if (on) Glass.ACCENT else Color.WHITE }, dp(18), dp(18))
                addView(text(g, 12f, if (on) Glass.ACCENT else Color.WHITE, on).apply { setPadding(dp(6), 0, 0, 0) })
                setOnClickListener { filmGroup = g; showTool(5, false) } }, LinearLayout.LayoutParams(-2, dp(34)).apply { rightMargin = dp(6) }) }
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabs) }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        filmStrip(col, listOf<Film?>(null) + Films.inGroup(filmGroup))
    }
    private fun filmStrip(col: LinearLayout, looks: List<Film?>) {
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
    // ---------- Sound, captions and voice menus ----------
    /** Older projects kept one song outside the timeline; it becomes a music bar on the timeline. */
    private fun convertLegacyMusic() {
        val m = music ?: return
        val len = VideoLayers.duration(this, m).let { if (it > 0) it else totalOut() }.coerceAtMost(totalOut()).coerceAtLeast(200)
        layers += VideoLayer(VideoLayer.SOUND, m, 0L, len, 0L, volume = musicVolume, music = true)
        music = null; musicName = ""; musicPlayer?.runCatching { release() }; musicPlayer = null
        rebuildLayers(); refreshTimeline()
    }
    private val pickSong = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; addLayers(listOf(uri), VideoLayer.SOUND, outNow(), home = 9, music = true) }
    }
    private fun clipVolume(k: Int) = if (k == 0) volume else extras.getOrNull(k - 1)?.volume ?: 1f
    private fun setClipVolume(k: Int, v: Float) { if (k == 0) volume = v else extras.getOrNull(k - 1)?.volume = v; applySound() }
    /** A big speaker button that shows clearly whether the sound is on. */
    private fun muteButton(muted: Boolean, click: () -> Unit): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; background = glass(20, if (muted) 0x33ff453a else Glass.ACCENT_SOFT); setPadding(dp(12), 0, dp(14), 0); isClickable = true; glassTouch()
        contentDescription = if (muted) "เปิดเสียง" else "ปิดเสียง"
        addView(IconView(this@VideoEditorActivity, if (muted) "M4 9v6h4l5 4V5L8 9H4z M16 9l5 6 M21 9l-5 6" else "M4 9v6h4l5 4V5L8 9H4z M16 8.5a5 5 0 0 1 0 7 M18.5 6a8.5 8.5 0 0 1 0 12", 2f).apply { tint = if (muted) 0xffff6b6b.toInt() else Glass.ACCENT }, dp(22), dp(22))
        addView(text(if (muted) "ปิดเสียงอยู่" else "เปิดเสียงอยู่", 12.5f, if (muted) 0xffff6b6b.toInt() else Glass.ACCENT, true).apply { setPadding(dp(8), 0, 0, 0) })
        setOnClickListener { Glass.tick(this); click() }
    }
    private fun soundTools(col: LinearLayout) {
        col.gravity = Gravity.TOP
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(keepScroll(android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(box) }), LinearLayout.LayoutParams(-1, 0, 1f))
        fun slider(label: String, steps: List<String>, at: Int, set: (Int) -> Unit) {
            val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(12), 0) }
            r.addView(text(label, 12f, Glass.TEXT2, true), dp(96), -2)
            r.addView(GlassSlider(this).apply { values = steps; index = at.coerceIn(0, steps.size - 1); grad = intArrayOf(0xff8e8e93.toInt(), Glass.ACCENT); contentDescription = label
                onChange = { i -> set(i) } }, LinearLayout.LayoutParams(0, dp(40), 1f))
            box.addView(r, -1, -2)
        }
        val pct = (0..200 step 5).map { "$it%" }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(2), dp(14), dp(6)) }
        val l = layers.getOrNull(layerPick)?.takeIf { barActive && it.audible }
        if (l != null) {
            val i = layerPick
            head.addView(text((if (l.music) "เพลง: " else "เสียง: ") + layerName(l), 13f, Glass.TEXT, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(muteButton(l.volume == 0f) { layers[i] = layers[i].copy(volume = if (layers[i].volume == 0f) 1f else 0f); releaseLayerSound(); showTool(9, false) })
            box.addView(head, -1, -2)
            slider("ความดัง", pct, (l.volume * 20).roundToInt()) { v -> layers[i] = layers[i].copy(volume = v / 20f); layerSound[i]?.runCatching { val x = (v / 20f).coerceAtMost(1f); setVolume(x, x) } }
            val sec = (0..3000 step 100).map { if (it == 0) "ไม่มี" else String.format(Locale.US, "%.1f วิ", it / 1000f) }
            slider("ค่อย ๆ ดังขึ้น", sec, (l.fadeIn / 100).toInt()) { v -> layers[i] = layers[i].copy(fadeIn = v * 100L) }
            slider("ค่อย ๆ เบาลง", sec, (l.fadeOut / 100).toInt()) { v -> layers[i] = layers[i].copy(fadeOut = v * 100L) }
        } else {
            val k = playIndex
            head.addView(text("เสียงคลิป ${order().indexOf(k) + 1}", 13f, Glass.TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(muteButton(isMuted(k) || !keepSound) { if (!keepSound) { keepSound = true; setMuted(k, false) } else setMuted(k, !isMuted(k)); showTool(9, false) })
            box.addView(head, -1, -2)
            slider("ความดังคลิปนี้", pct, (clipVolume(k) * 20).roundToInt()) { v -> setClipVolume(k, v / 20f) }
            val all = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(2), 0, dp(4)) }
            all.addView(EditorKit.chip(this, "ใช้ความดังนี้กับทุกคลิป", Icons.SOUND) { val v = clipVolume(k); volume = v; extras.forEach { it.volume = v }; applySound(); notice("ทุกคลิปดัง ${(v * 100).roundToInt()}%") }, LinearLayout.LayoutParams(-2, dp(34)).apply { setMargins(dp(3), 0, dp(3), 0) })
            all.addView(EditorKit.chip(this, if (keepSound) "ปิดเสียงทุกคลิป" else "เปิดเสียงทุกคลิป", "M4 9v6h4l5 4V5L8 9H4z", !keepSound) { keepSound = !keepSound; applySound(); showTool(9, false) }, LinearLayout.LayoutParams(-2, dp(34)).apply { setMargins(dp(3), 0, dp(3), 0) })
            box.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(all) }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }
        val music = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(4)) }
        music.addView(EditorKit.chip(this, "เพิ่มเพลง", EditorKit.MUSIC) { pickSong.launch(arrayOf("audio/*")) }, LinearLayout.LayoutParams(-2, dp(34)).apply { setMargins(dp(3), 0, dp(3), 0) })
        if (layers.any { it.music }) music.addView(EditorKit.chip(this, "มาร์กจังหวะเพลง", "M9 18V6l10-2v12") { markBeats() }, LinearLayout.LayoutParams(-2, dp(34)).apply { setMargins(dp(3), 0, dp(3), 0) })
        box.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(music) }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val db = (-12..12).map { if (it > 0) "+$it dB" else "$it dB" }
        listOf("เสียงทุ้ม", "เสียงกลาง", "เสียงแหลม").forEachIndexed { q, n -> slider(n, db, eq[q].roundToInt() + 12) { v -> eq[q] = (v - 12).toFloat(); applySound() } }
        val toggles = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(6), dp(8), dp(4)) }
        fun toggle(label: String, on: Boolean, f: () -> Unit) = toggles.addView(text(label, 12f, if (on) Glass.ACCENT else Color.WHITE, on).apply { gravity = Gravity.CENTER; background = glass(16, if (on) Glass.ACCENT_SOFT else 0x26ffffff)
            setPadding(dp(12), 0, dp(12), 0); isClickable = true; glassTouch(); contentDescription = label; setOnClickListener { f(); applySound(); showTool(9, false) } }, LinearLayout.LayoutParams(-2, dp(34)).apply { setMargins(dp(3), 0, dp(3), 0) })
        toggle("ตัดเสียงลม/หึ่ง", lowCut) { lowCut = !lowCut }
        toggle("เสียงพูดชัด", voice) { voice = !voice }
        toggle("ลดเพลงตอนมีเสียงพูด", duck) { duck = !duck }
        box.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(toggles) }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        box.addView(text("แตะแถบเพลงหรือเสียงบนไทม์ไลน์เพื่อปรับแถบนั้น · ลากแถบเพื่อเลือกช่วงที่เล่น · ตัดและแยกได้ที่เมนูตัด", 11f, Glass.TEXT3).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(2), dp(16), dp(8)) }, -1, -2)
    }

    private val LANGS = listOf("ภาษาไทย" to "th-TH", "English" to "en-US", "日本語" to "ja-JP", "한국어" to "ko-KR", "中文 (普通话)" to "zh-CN", "粵語" to "yue-Hant-HK",
        "Tiếng Việt" to "vi-VN", "Bahasa Indonesia" to "id-ID", "Bahasa Melayu" to "ms-MY", "Filipino" to "fil-PH", "हिन्दी" to "hi-IN", "Español" to "es-ES",
        "Français" to "fr-FR", "Deutsch" to "de-DE", "Italiano" to "it-IT", "Português" to "pt-BR", "Русский" to "ru-RU", "العربية" to "ar-SA")
    private var captionStatus: TextView? = null
    private fun captionLang() = getSharedPreferences("camera", MODE_PRIVATE).getString("caption_lang", CAPTION_AUTO) ?: CAPTION_AUTO
    /** Restyles every caption line together: words, look and place stay in step. */
    private fun styleCaptions(style: ((Int) -> Int)? = null, color: Int? = null, y: Float? = null) {
        layers.indices.filter { layers[it].caption }.forEach { i -> val l = layers[i]
            val n = l.copy(style = style?.invoke(l.style) ?: l.style, color = color ?: l.color).let { it.copy(source = VideoLayer.textSource(it.text, it.color, it.style)) }
            val r = redraw(l, n) ?: return@forEach
            layers[i] = r.copy(y = y ?: n.y, keys = if (y != null) "" else n.keys)
        }
        rebuildLayers(); refreshTimeline(); showTool(15, false)
    }
    private fun captionTarget() = getSharedPreferences("camera", MODE_PRIVATE).getString("caption_target", "en-US") ?: "en-US"
    /** The language the lines are in: the one chosen, else what Whisper heard, else the other of Thai and English. */
    private fun captionFrom(to: String): String {
        val l = captionLang(); if (l != CAPTION_AUTO) return l
        return detectedTag ?: if (TransLang.code(to) == "th") "en-US" else "th-TH"
    }
    /** "แปลเลย": ML Kit when it is the chosen translator, else a model that runs through llama.cpp (stops the running one if pressed again). */
    private fun translateNow() {
        if (translating) { translateStopped = true; LlamaTranslator.cancel(); notice("กำลังหยุดแปล…"); return }
        val to = captionTarget(); val m = TranslatorModels.selected(this)
        if (layers.none { it.caption }) { notice("ยังไม่มีคำบรรยายให้แปล · สร้างคำบรรยายก่อน"); return }
        when {
            m.builtin -> translateCaptions(captionFrom(to), to)
            m.unavailable != null -> notice("${m.name}: ${m.unavailable}")
            !ModelStore.installed(this, m) -> { translateAfter = m.id; downloadModel(m); notice("ต้องดาวน์โหลด ${m.name} ก่อน · เสร็จแล้วจะเริ่มแปลให้เอง") }
            else -> runLlmTranslate(m, to)
        }
    }
    private fun runLlmTranslate(m: ModelSpec, to: String) {
        val groups = captionGroups(); if (groups.isEmpty() || translating) return
        val from = captionFrom(to)
        if (TranslatorModels.infoOf(m)?.supports(from, to) != true) {
            val ok = TranslatorModels.ALL.filter { TranslatorModels.infoOf(it)?.supports(from, to) == true }.joinToString(" · ") { it.name }
            notice("${m.name} แปล ${TransLang.name(from)} → ${TransLang.name(to)} ไม่ได้" + (if (TranslatorModels.infoOf(m)?.thEnOnly == true) " (รองรับเฉพาะไทย↔อังกฤษ)" else "") + "\nลองใช้: " + ok.ifEmpty { "ML Kit" }); return }
        val firsts = groups.map { layers[it.first()].text }; val texts = groups.map { captionSource(it) }; val out = arrayOfNulls<String>(groups.size)
        translating = true; translateStopped = false
        if (tool == 15) showTool(15, false)
        val ui = CaptionProgress(this, firstFrame, listOf("โหลดโมเดล ${m.name}", "แปลทีละบรรทัด", "ใส่ข้อความที่แปลลงไทม์ไลน์"),
            listOf(ExportProgress.Backdrop.FLOW, ExportProgress.Backdrop.AI, ExportProgress.Backdrop.SAVE),
            listOf("กำลังโหลดโมเดลแปล", "กำลังแปล ${TransLang.name(from)} → ${TransLang.name(to)}", "กำลังใส่ข้อความที่แปล"), noticeTitle = "แปลคำบรรยาย") {
            translateStopped = true; LlamaTranslator.cancel(); notice("กำลังหยุดแปล…") }
        captionUi = ui; ui.render(0, 0.03f, "กำลังเปิด ${m.name} · ครั้งแรกอาจใช้เวลาสักครู่")
        val app = applicationContext
        Thread({
            LlamaTranslator.context = transContext()
            val err = LlamaTranslator.run(app, m, from, to, texts, { runOnUiThread { ui.render(1, 0.1f, "เริ่มแปลบรรทัดแรก…") } }) { done, total, t -> out[done - 1] = t
                runOnUiThread { ui.render(1, 0.1f + 0.85f * done / total, "บรรทัดที่ $done จาก $total", t ?: "") } }
            LlamaTranslator.release()
            runOnUiThread { finishLlmTranslate(groups, firsts, texts, out, err, to) }
        }, "luma-translate").start()
    }
    private fun finishLlmTranslate(groups: List<List<Int>>, firsts: List<String>, texts: List<String>, out: Array<String?>, err: String?, to: String) {
        translating = false
        captionUi?.remove(); captionUi = null
        if (isDestroyed) return
        if (err != null) { notice(err); if (tool == 15) showTool(15, false); return }
        if (translateStopped) { notice("หยุดแปลแล้ว · คำบรรยายยังเป็นต้นฉบับ"); if (tool == 15) showTool(15, false); return }
        val done = applyTranslations(groups, firsts, texts, out, to)
        splitLongCaptions()
        rebuildLayers(); refreshTimeline(); showTool(15, false)
        notice(if (done > 0) "แปลคำบรรยาย $done บรรทัดแล้ว · ตรวจคำก่อนส่งออก" else "แปลไม่สำเร็จ ลองใหม่อีกครั้งหรือเปลี่ยนโมเดล")
        if (done > 0) TaskNotice.finish(this, "แปลคำบรรยายเสร็จแล้ว", "แปล $done บรรทัด · แตะเพื่อกลับไปตรวจคำ")
    }
    private fun translateCaptions(fromTag: String, toTag: String) {
        val from = com.google.mlkit.nl.translate.TranslateLanguage.fromLanguageTag(fromTag.substringBefore('-'))
        val to = com.google.mlkit.nl.translate.TranslateLanguage.fromLanguageTag(toTag.substringBefore('-'))
        if (from == null || to == null) { notice("ภาษานี้ยังแปลในเครื่องไม่ได้"); return }
        if (from == to) { notice("ภาษาที่พูดกับภาษาที่แปลเป็นภาษาเดียวกัน"); return }
        val groups = captionGroups()
        if (groups.isEmpty()) return
        val firsts = groups.map { layers[it.first()].text }; val sources = groups.map { captionSource(it) }
        val client = com.google.mlkit.nl.translate.Translation.getClient(com.google.mlkit.nl.translate.TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build())
        notice("กำลังเตรียมโมเดลภาษา (โหลดครั้งแรกต้องต่ออินเทอร์เน็ต)…")
        client.downloadModelIfNeeded().addOnSuccessListener {
            var left = groups.size; val out = arrayOfNulls<String>(groups.size)
            groups.indices.forEach { k -> client.translate(sources[k]).addOnCompleteListener { t ->
                out[k] = t.result?.takeIf { t.isSuccessful && it.isNotBlank() }
                if (--left == 0 && !isDestroyed) {
                    val done = applyTranslations(groups, firsts, sources, out, toTag)
                    client.close(); splitLongCaptions(); rebuildLayers(); refreshTimeline(); showTool(15, false)
                    notice(if (done > 0) "แปลคำบรรยาย $done บรรทัดแล้ว · ตรวจคำก่อนส่งออก" else "แปลไม่สำเร็จ ลองใหม่อีกครั้ง")
                } } }
        }.addOnFailureListener { client.close(); notice("โหลดโมเดลภาษาไม่ได้ · ต่ออินเทอร์เน็ตแล้วลองใหม่") }
    }
    /** How big the caption lines are made, in percent of the usual size; it applies to new lines and, when changed, to every line already there. */
    /** Share of the picture a caption line may fill (about 94% of the video width), whatever the letter size. */
    private fun captionWrap(percent: Int) = (0.94f / (0.62f * percent / 100f)).coerceIn(0.4f, 4f)
    private fun captionSize(pic: Bitmap, percent: Int) = (pic.width.toFloat() / TEXT_PREVIEW * 0.62f * percent / 100f).coerceIn(0.005f, 0.98f)
    /** Draws [n] in place of [l] and keeps the letters the same size on the video when the picture gets wider or narrower. */
    private fun redraw(l: VideoLayer, n: VideoLayer): VideoLayer? {
        val oldW = layerThumbs[l.source]?.width ?: 0
        val pic = runCatching { VideoLayers.textPicture(n, TEXT_PREVIEW) }.getOrNull() ?: return null
        layerThumbs[n.source] = pic
        val k = if (oldW > 0) pic.width.toFloat() / oldW else 1f
        return n.copy(aspect = pic.width.toFloat() / pic.height, size = (l.size * k).coerceIn(if (l.caption) 0.005f else 0.05f, 0.98f))
    }
    private fun transContext() = getSharedPreferences("camera", MODE_PRIVATE).getString("trans_context", "") ?: ""
    private fun setTransContext(v: String) = getSharedPreferences("camera", MODE_PRIVATE).edit().putString("trans_context", v.trim().take(120)).apply()
    private fun askTransContext() {
        val box = android.widget.EditText(this).apply { setText(transContext()); hint = "เช่น หนังจีนกำลังภายใน ภาษาโบราณ"; setTextColor(Glass.TEXT); setHintTextColor(Glass.TEXT3); textSize = 15f; background = glass(14, 0x1affffff); setPadding(dp(14), dp(10), dp(14), dp(10)); isSingleLine = true }
        GlassDialog.ask(this, "บริบทของคำบรรยาย", message = "บอกว่าเป็นเรื่องแนวไหน โมเดลจะเลือกคำและน้ำเสียงให้ตรง", content = box, ok = "ใช้", focus = box) { setTransContext(box.text.toString()); showTool(15, false); true }
    }
    private fun nextGroup() = (layers.maxOfOrNull { it.grp } ?: 0) + 1
    /** Caption lines cut from one sentence share a group, so the sentence is translated whole; a line on its own is its own group. Layer numbers per group, in time order. */
    private fun captionGroups(): List<List<Int>> =
        layers.indices.filter { layers[it].caption }.groupBy { if (layers[it].grp != 0) layers[it].grp else -(it + 1) }.values.map { g -> g.sortedBy { layers[it].at } }
    /** What to translate for a group: the words before any earlier translation, else the group's lines joined. */
    private fun captionSource(g: List<Int>) = layers[g.first()].orig.ifEmpty { g.joinToString(" ") { layers[it].text } }
    /** Puts the translated sentence in place of its group (one line, as long as the whole group) and then cuts it again for length. Returns how many were done. */
    private fun applyTranslations(groups: List<List<Int>>, firsts: List<String>, sources: List<String>, out: Array<String?>, to: String): Int {
        var done = 0; val drop = HashSet<Int>()
        groups.forEachIndexed { k, g ->
            val words = out[k] ?: return@forEachIndexed
            val j = g.first(); val l = layers.getOrNull(j) ?: return@forEachIndexed
            if (l.text != firsts[k] || g.any { layers.getOrNull(it)?.caption != true }) return@forEachIndexed
            val end = g.maxOf { layers[it].end }
            val n = l.copy(text = words, length = end - l.at, orig = sources[k], trTo = to, grp = if (l.grp != 0) l.grp else nextGroup(), keys = "")
                .let { it.copy(source = VideoLayer.textSource(it.text, it.color, it.style)) }
            layers[j] = redraw(l, n) ?: return@forEachIndexed
            g.drop(1).forEach { drop += it }; done++
        }
        if (drop.isNotEmpty()) { drop.sortedDescending().forEach { layers.removeAt(it) }; layerPick = -1; barActive = false }
        return done
    }
    /** How many letters fit on one caption line at the chosen size (about 22 at 100%); two lines are allowed before a caption is cut in two. */
    private fun captionMaxChars() = (22f * 100f / captionScale()).toInt().coerceIn(12, 60) * 2
    /** A caption longer than that is split into consecutive captions, the time shared by length, so none runs off the picture. Callers rebuild the layers after. */
    private fun splitLongCaptions(): Boolean {
        val max = captionMaxChars(); var i = 0; var changed = false
        while (i < layers.size) {
            val l = layers[i]
            val parts = if (l.caption) StudioMath.splitCue(StudioMath.Cue(l.at, l.end, l.text), max) else emptyList()
            if (parts.size > 1) {
                val g = if (l.grp != 0) l.grp else nextGroup()
                val made = parts.map { p ->
                    val n = l.copy(text = p.text, at = p.from, length = p.to - p.from, keys = "", grp = g).let { it.copy(source = VideoLayer.textSource(it.text, it.color, it.style)) }
                    val pic = VideoLayers.textPicture(n, TEXT_PREVIEW); layerThumbs[n.source] = pic
                    n.copy(aspect = pic.width.toFloat() / pic.height, size = captionSize(pic, captionScale())) }
                layers.removeAt(i); layers.addAll(i, made); i += made.size; changed = true
            } else i++
        }
        return changed
    }
    private fun captionScale() = getSharedPreferences("camera", MODE_PRIVATE).getInt("caption_scale", 100)
    /** Gives every caption line the same letter size (and the same line width), so no line looks bigger or smaller than the rest. */
    private fun evenCaptions(percent: Int) {
        layers.indices.filter { layers[it].caption }.forEach { i -> val l = layers[i]
            val n = l.copy(wrap = captionWrap(percent)); val pic = runCatching { VideoLayers.textPicture(n, TEXT_PREVIEW) }.getOrNull() ?: return@forEach
            layerThumbs[n.source] = pic; layers[i] = n.copy(aspect = pic.width.toFloat() / pic.height, size = captionSize(pic, percent)) }
    }
    private fun applyCaptionScale(percent: Int) {
        val old = captionScale(); if (percent == old) return
        getSharedPreferences("camera", MODE_PRIVATE).edit().putInt("caption_scale", percent).apply()
        evenCaptions(percent); splitLongCaptions(); rebuildLayers(); refreshTimeline()
    }
    private fun sizeCard(): View {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = glass(18, 0x1affffff); setPadding(dp(14), dp(10), dp(14), dp(10)) }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(text("ขนาดตัวอักษร", 14f, Glass.TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        val value = text("${captionScale()}%", 13f, Glass.ACCENT, true); head.addView(value)
        card.addView(head, -1, -2)
        var dragging = false; var pending = captionScale()
        val slider = GlassSlider(this).apply { values = (50..200 step 5).map { "$it%" }; index = ((captionScale() - 50) / 5).coerceIn(0, 30)
            grad = intArrayOf(0xff8e8e93.toInt(), Color.WHITE, Glass.ACCENT); contentDescription = "ขนาดตัวอักษรคำบรรยาย"
            onChange = { k -> pending = 50 + k * 5; value.text = "$pending%"; if (!dragging) applyCaptionScale(pending) }
            onDrag = { d -> dragging = d; if (!d) applyCaptionScale(pending) } }
        card.addView(slider, LinearLayout.LayoutParams(-1, dp(40)))
        card.addView(text("ใช้กับทุกบรรทัดพร้อมกัน ปรับได้ทั้งก่อนและหลังสร้าง", 11.5f, Glass.TEXT3))
        return card
    }
    /** The caption panel scrolls, so everything stays reachable however tall it gets. */
    private fun captionTools(host: LinearLayout) {
        host.gravity = Gravity.TOP
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        captionBody(box)
        host.addView(keepScroll(android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(box) }), LinearLayout.LayoutParams(-1, 0, 1f))
    }
    private fun captionBody(col: LinearLayout) {
        // Lines made by an older version have no shared width: bring them to one size once.
        if (layers.any { it.caption && it.wrap == 0f }) { evenCaptions(captionScale()); rebuildLayers(); refreshTimeline() }
        val lang = captionLang(); val name = if (lang == CAPTION_AUTO) "ตรวจจับอัตโนมัติ" else LANGS.firstOrNull { it.second == lang }?.first ?: lang
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(2), dp(14), dp(6)) }
        // The first row is "automatic"; the rest are the languages the recogniser can be told to read.
        val spoken = listOf("ตรวจจับอัตโนมัติ" to CAPTION_AUTO) + LANGS
        top.addView(dropButton("ภาษาที่พูด: $name") { v -> menu.show(v, "ภาษาที่พูดในวิดีโอ", spoken.map { GlassMenu.Option(it.first) }, spoken.indexOfFirst { it.second == lang }) { k ->
            getSharedPreferences("camera", MODE_PRIVATE).edit().putString("caption_lang", spoken[k].second).apply(); showTool(15, false) } }, LinearLayout.LayoutParams(-2, dp(38)))
        col.addView(top, -1, -2)
        // Speech model: a card that shows the chosen Whisper model, its download progress, and download / cancel / delete.
        col.addView(ModelCard(this, menu, WhisperModels.ALL, Icons.MIC, "เลือกโมเดลถอดเสียง") { m -> downloadModel(m) }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(14), 0, dp(14), dp(8)) })
        col.addView(sizeCard(), LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(14), 0, dp(14), dp(10)) })
        val go = LinearLayout(this).apply { gravity = Gravity.CENTER; background = glass(22, if (speech != null) 0x33ff453a else Glass.ACCENT); isClickable = true; glassTouch()
            contentDescription = if (speech != null) "หยุดสร้างคำบรรยาย" else "สร้างคำบรรยายอัตโนมัติ"
            addView(IconView(this@VideoEditorActivity, if (speech != null) "M7 7h10v10H7z" else "M4 6h16v10H8l-4 4z M8 10h8 M8 13h5", 2.2f).apply { tint = if (speech != null) Color.WHITE else Glass.ON_ACCENT }, dp(20), dp(20))
            addView(text(if (speech != null) "หยุด" else "สร้างคำบรรยายจากเสียงพูด", 14.5f, if (speech != null) Color.WHITE else Glass.ON_ACCENT, true).apply { setPadding(dp(8), 0, 0, 0) })
            setOnClickListener { autoCaptions() } }
        col.addView(go, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(dp(16), 0, dp(16), dp(4)) })
        // A line of status shows only while something is working; nothing sits under the button otherwise.
        captionStatus = if (speech != null || translating) text(if (translating) "กำลังแปล…" else "กำลังถอดเสียง…", 11.5f, Glass.TEXT3).apply { gravity = Gravity.CENTER; setPadding(dp(16), 0, dp(16), dp(4)) } else null
        captionStatus?.let { col.addView(it, -1, -2) }
        val srt = LinearLayout(this)
        srt.addView(EditorKit.chip(this, "นำเข้า SRT", EditorKit.START) { pickSrt.launch(arrayOf("*/*")) }, LinearLayout.LayoutParams(0, dp(42), 1f))
        val count = layers.count { it.caption }
        if (layers.any { it.kind == VideoLayer.TEXT && VideoLayers.shapeOf(it.text) < 0 }) srt.addView(EditorKit.chip(this, "ส่งออก SRT", EditorKit.END) { saveSrt.launch("subtitles.srt") }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(8) })
        col.addView(srt, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(14), dp(2), dp(14), 0) })
        if (count > 0) {
        // Show or hide every caption line on the video together (hidden lines stay, so they can still be exported as .srt).
        val shown = layers.filter { it.caption }.none { it.hidden }
        val showRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(6), dp(18), 0) }
        showRow.addView(IconView(this, "M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z", 2f).apply { tint = Glass.TEXT2 }, dp(20), dp(20))
        showRow.addView(text("ใส่คำบรรยายบนวิดีโอ", 14f, Glass.TEXT, true).apply { setPadding(dp(10), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        showRow.addView(GlassSwitch(this).apply { set(shown, false); onToggle = { on -> layers.indices.filter { layers[it].caption }.forEach { i -> layers[i] = layers[i].copy(hidden = !on) }; rebuildLayers(); refreshTimeline(); showTool(15, false) } }, dp(52), dp(30))
        col.addView(showRow, -1, dp(40))
        }
        // Translate every caption line on the phone (ML Kit); the language model downloads once, then it works offline.
        val targetTag = captionTarget()
        val targetName = LANGS.firstOrNull { it.second == targetTag }?.first ?: targetTag
        val trRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(8), dp(14), 0) }
        trRow.addView(dropButton("แปลเป็น: $targetName") { v -> menu.show(v, "แปลคำบรรยายเป็นภาษา", LANGS.map { GlassMenu.Option(it.first, "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z M3 12h18 M12 3c3 3 3 15 0 18 M12 3c-3 3-3 15 0 18") }, LANGS.indexOfFirst { it.second == targetTag }) { k ->
            getSharedPreferences("camera", MODE_PRIVATE).edit().putString("caption_target", LANGS[k].second).apply(); showTool(15, false) } }, LinearLayout.LayoutParams(-2, dp(38)))
        trRow.addView(dropButton("บริบท: " + transContext().let { cur -> TRANS_CONTEXTS.firstOrNull { it.second == cur && cur.isNotEmpty() }?.first ?: cur.ifEmpty { "ทั่วไป" } }) { v ->
            val opts = TRANS_CONTEXTS.map { it.first }
            menu.show(v, "บอกบริบทให้แปลตรงแนว", opts.map { GlassMenu.Option(it) } + GlassMenu.Option("พิมพ์เอง…"), TRANS_CONTEXTS.indexOfFirst { it.second == transContext() }) { k ->
                if (k < TRANS_CONTEXTS.size) { setTransContext(TRANS_CONTEXTS[k].second); showTool(15, false) } else askTransContext() } }, LinearLayout.LayoutParams(-2, dp(38)).apply { leftMargin = dp(8) })
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(trRow) }, -1, -2)
        // Translator model: ML Kit (small) or a model that runs on the phone, with the same download card as the speech model.
        col.addView(ModelCard(this, menu, TranslatorModels.ALL, "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z M3 12h18 M12 3c3 3 3 15 0 18 M12 3c-3 3-3 15 0 18", "เลือกโมเดลแปลภาษา") { m -> downloadModel(m) }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(14), dp(8), dp(14), 0) })
        // The translate button is as prominent as the speech one: orange, full width; it turns into "stop" while translating.
        val globe = "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z M3 12h18 M12 3c3 3 3 15 0 18 M12 3c-3 3-3 15 0 18"
        val tgo = LinearLayout(this).apply { gravity = Gravity.CENTER; background = glass(22, if (translating) 0x33ff453a else Glass.ACCENT); isClickable = true; glassTouch()
            // Lines already translated into another language than the one chosen now: the button offers to do them again.
            val doneTo = layers.firstOrNull { it.caption && it.trTo.isNotEmpty() }?.trTo
            val label = if (translating) "หยุดแปล" else if (doneTo != null && doneTo != targetTag) "แปลใหม่ · $targetName" else "แปลคำบรรยาย"
            contentDescription = label
            addView(IconView(this@VideoEditorActivity, if (translating) "M7 7h10v10H7z" else globe, 2.2f).apply { tint = if (translating) Color.WHITE else Glass.ON_ACCENT }, dp(20), dp(20))
            addView(text(label, 14.5f, if (translating) Color.WHITE else Glass.ON_ACCENT, true).apply { setPadding(dp(8), 0, 0, 0) })
            setOnClickListener { translateNow() } }
        col.addView(tgo, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(dp(16), dp(10), dp(16), dp(4)) })
        if (count == 0) return
        // Remove every caption line at once, where it can be seen (not only inside the sideways row below); it asks first, and the undo button brings them back.
        val red = 0xffff6b63.toInt()
        val wipe = LinearLayout(this).apply { gravity = Gravity.CENTER; background = glass(22, 0x26ff453a); isClickable = true; glassTouch(); contentDescription = "ลบคำบรรยายทั้งหมด"
            addView(IconView(this@VideoEditorActivity, "M5 7h14 M9 7V4h6v3 M7 7l1 13h8l1-13", 2.2f).apply { tint = red }, dp(20), dp(20))
            addView(text("ลบคำบรรยายทั้งหมด ($count บรรทัด)", 14.5f, red, true).apply { setPadding(dp(8), 0, 0, 0) })
            setOnClickListener { GlassDialog.ask(this@VideoEditorActivity, "ลบคำบรรยายทั้งหมด?", message = "ลบ $count บรรทัดออกจากไทม์ไลน์ · กดย้อนกลับด้านบนเพื่อเอาคืนได้", ok = "ลบ") {
                layers.removeAll { it.caption }; layerPick = -1; barActive = false; rebuildLayers(); refreshTimeline(); showTool(15, false); notice("ลบคำบรรยายทั้งหมดแล้ว"); true } } }
        col.addView(wipe, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(dp(16), dp(6), dp(16), dp(4)) })
        val drops = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(8), dp(14), 0) }
        drops.addView(text("คำบรรยาย $count บรรทัด", 12f, Glass.TEXT2, true).apply { setPadding(0, 0, dp(8), 0) })
        drops.addView(dropButton("ตำแหน่ง") { v -> menu.show(v, "ตำแหน่งคำบรรยาย", listOf(GlassMenu.Option("ล่าง", posIcon(0.5f, 0.86f)), GlassMenu.Option("กลาง", posIcon(0.5f, 0.5f)), GlassMenu.Option("บน", posIcon(0.5f, 0.14f))), -1) { k -> styleCaptions(y = listOf(0.86f, 0.5f, 0.14f)[k]) } }, LinearLayout.LayoutParams(-2, dp(34)).apply { rightMargin = dp(6) })
        val looks = listOf("กล่องพื้น" to 1, "ขอบตัวอักษร" to 2, "ไม่มีพื้น" to 0)
        drops.addView(dropButton("รูปแบบ") { v -> menu.show(v, "รูปแบบคำบรรยาย", looks.map { GlassMenu.Option(it.first) }, -1) { k -> styleCaptions(style = { s -> (s and 3.inv()) or looks[k].second }) } }, LinearLayout.LayoutParams(-2, dp(34)).apply { rightMargin = dp(6) })
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(drops) }, -1, -2)
        col.addView(colorRow(layers.first { it.caption }.color) { c -> styleCaptions(color = c) }, -1, -2)
    }

    private var recLabel: TextView? = null
    private val recTick = object : Runnable { override fun run() {
        val v = recLabel ?: return; if (recorder == null) return
        val ms = outNow() - recAt; v.text = "กำลังอัด " + clock(ms.coerceAtLeast(0)); handler.postDelayed(this, 200)
    } }
    private fun voiceTools(col: LinearLayout) {
        col.gravity = Gravity.TOP
        val recording = recorder != null
        val button = FrameLayout(this).apply { isClickable = true; glassTouch(); contentDescription = if (recording) "หยุดอัด" else "เริ่มอัดเสียง"
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x26ffffff); setStroke(dp(3), if (recording) 0xffff453a.toInt() else Color.WHITE) }
            addView(View(this@VideoEditorActivity).apply { background = GradientDrawable().apply { if (recording) cornerRadius = dp(6).toFloat() else shape = GradientDrawable.OVAL; setColor(0xffff453a.toInt()) } },
                FrameLayout.LayoutParams(dp(if (recording) 28 else 52), dp(if (recording) 28 else 52), Gravity.CENTER))
            setOnClickListener { toggleRecord() } }
        col.addView(button, LinearLayout.LayoutParams(dp(78), dp(78)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8) })
        recLabel = text(if (recording) "กำลังอัด…" else "แตะเพื่อเริ่มอัดตรงหัวอ่าน", 14f, if (recording) 0xffff6b6b.toInt() else Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(2)) }
        col.addView(recLabel, -1, -2)
        col.addView(text(if (recording) "แตะปุ่มอีกครั้งเมื่อพูดจบ" else "วิดีโอจะเล่นโดยไม่มีเสียงระหว่างอัด เสียงที่อัดจะวางบนไทม์ไลน์ตรงจุดที่เริ่ม", 11.5f, Glass.TEXT3).apply { gravity = Gravity.CENTER; setPadding(dp(16), 0, dp(16), dp(6)) }, -1, -2)
        val voices = layers.indices.filter { layers[it].kind == VideoLayer.SOUND && layers[it].source.path?.contains("/studio/voice_") == true }
        if (voices.isNotEmpty()) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), 0) }
            voices.forEachIndexed { n, i -> row.addView(EditorKit.chip(this, "เสียงพูด ${n + 1} · ${clock(layers[i].length)}", "M12 4a3 3 0 0 0-3 3v5a3 3 0 0 0 6 0V7a3 3 0 0 0-3-3z", barActive && layerPick == i) {
                val (k, at) = locate(layers[i].at); jumpTo(k, at); barActive = true; layerPick = i; refreshTimeline(); showTool(16, false) }, LinearLayout.LayoutParams(-2, dp(34)).apply { rightMargin = dp(6) }) }
            col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }, -1, -2)
        }
        if (recording) { handler.removeCallbacks(recTick); handler.post(recTick) }
    }

    private fun musicTools(col: LinearLayout) {
        if (music != null) col.addView(text(musicName, 12f, Glass.TEXT2, true).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setPadding(dp(16), 0, dp(16), dp(6)) }, -1, -2)
        val items = mutableListOf<View>(EditorKit.action(this, EditorKit.MUSIC, if (music == null) "เลือกเพลง" else "เปลี่ยนเพลง", music != null) { pickMusic.launch(arrayOf("audio/*")) })
        if (hasAudio) items += EditorKit.action(this, Icons.SOUND, "เสียงคลิป", keepSound) { keepSound = !keepSound; applySound(); showTool(6, false) }
        if (music != null) items += EditorKit.action(this, "M9 18V6l10-2v12 M9 18a3 3 0 1 1-6 0 3 3 0 0 1 6 0 M19 16a3 3 0 1 1-6 0 3 3 0 0 1 6 0", "มาร์กจังหวะ") { markBeats() }
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
            addView(ImageView(this@VideoEditorActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(thumb); clipToOutline = true; background = glass(11, Glass.PANEL) }, -1, -1)
            addView(text(label, 10.5f, Glass.TEXT, true).apply { setShadowLayer(4f, 0f, 1f, 0xcc000000.toInt()); setPadding(dp(5), 0, dp(5), dp(3)) }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
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
                    GlassDialog.choose(this, "ความเร็วของคลิปนี้", EditorKit.SPEED, speeds.map { speedName(it) to EditorKit.SPEED }, speeds.indexOfFirst { it == e.speed }) { w -> e.speed = speeds[w]; showTime(); showTool(10, false) } },
                // "Earlier" and "later" follow the playing order, so a clip can also step over the clip the editor was opened with.
                EditorKit.action(this, LEFT, "เลื่อนก่อน") { if (chosen == mainPos()) mainAt = mainPos() + 1 else if (chosen > 0) { extras.add(chosen - 1, extras.removeAt(chosen)); playIndex = chosen }; showTime(); showTool(10, false) },
                EditorKit.action(this, RIGHT, "เลื่อนหลัง") { if (chosen == mainPos() - 1) mainAt = mainPos() - 1 else if (chosen < extras.size - 1) { extras.add(chosen + 1, extras.removeAt(chosen)); playIndex = chosen + 2 }; showTime(); showTool(10, false) },
                EditorKit.action(this, Icons.SOUND, if (e.muted) "เปิดเสียง" else "ปิดเสียง", e.muted) { setMuted(chosen + 1, !e.muted); showTool(10, false) },
                EditorKit.action(this, "M4 12h6 M14 12h6 M10 8v8 M14 8v8", "แยกเสียง") { detachSound(); showTool(10, false) },
                EditorKit.action(this, "M3 5h18v14H3z M12 5v14", "หาฉาก") { findScenes() },
                EditorKit.action(this, "M7 4v16 M7 5h10l-2.5 3.5L17 12H7", "แยกตามมาร์กเกอร์") { splitAtMarkers() },
                EditorKit.action(this, "M3 12h3 M18 12h3 M8 8v8 M12 5v14 M16 8v8", "ตัดช่วงเงียบ") { cutSilence() },
                // Slip: the clip keeps its length and place, and shows an earlier or later part of its file.
                EditorKit.action(this, "M9 7l-5 5 5 5 M4 12h12 M20 5v14", "เนื้อหา −0.5 วิ") { val d = minOf(500L, e.from); if (d <= 0) notice("อยู่ที่ต้นไฟล์แล้ว") else { e.from -= d; e.to -= d; showClipOnBar(); jumpTo(chosen + 1, (trim.playhead - d).coerceIn(e.from, e.to)); showTime() } },
                EditorKit.action(this, "M15 7l5 5-5 5 M20 12H8 M4 5v14", "เนื้อหา +0.5 วิ") { val d = minOf(500L, e.duration - e.to); if (d <= 0) notice("อยู่ที่ท้ายไฟล์แล้ว") else { e.from += d; e.to += d; showClipOnBar(); jumpTo(chosen + 1, (trim.playhead + d).coerceIn(e.from, e.to)); showTime() } },
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
            EditorKit.action(this, "M4 12h6 M14 12h6 M10 8v8 M14 8v8", "แยกเสียง") { detachSound(); showTool(10, false) },
            EditorKit.action(this, "M3 5h18v14H3z M12 5v14", "หาฉาก") { findScenes() },
            EditorKit.action(this, "M7 4v16 M7 5h10l-2.5 3.5L17 12H7", "แยกตามมาร์กเกอร์") { splitAtMarkers() },
            EditorKit.action(this, "M3 12h3 M18 12h3 M8 8v8 M12 5v14 M16 8v8", "ตัดช่วงเงียบ") { cutSilence() },
            EditorKit.action(this, Icons.CLOSE, "ลบ") { deleteMain() })), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
    }
    /** Cuts the clip on screen in two at the playhead. The two halves stay next to each other and can then be moved, trimmed or removed separately. */
    private fun splitAtPlayhead() {
        val at = trim.playhead; val c = playIndex - 1; val e = extras.getOrNull(c)
        if (e != null) {
            if (e.curve != null) { notice("คลิปที่มีเส้นโค้งความเร็วแยกไม่ได้ · ล้างเส้นโค้งก่อน"); return }
            if (at - e.from < 100 || e.to - at < 100) { notice("เลื่อนหัวอ่านให้ห่างจากขอบคลิปอย่างน้อย 0.1 วินาที"); return }
            val second = e.copy().also { it.from = at }; e.to = at; extras.add(c + 1, second); if (c < mainPos()) mainAt = mainPos() + 1
        } else {
            if (cutValid() || mainCurve != null) { notice("คลิปนี้ตัดช่วงกลางหรือมีเส้นโค้งความเร็ว จึงแยกไม่ได้"); return }
            if (at - start < 100 || end - at < 100) { notice("เลื่อนหัวอ่านให้ห่างจากขอบคลิปอย่างน้อย 0.1 วินาที"); return }
            extraCache.getOrPut(source.toString()) { Extra(source, duration, videoW, videoH, "คลิปแรก", firstFrame) }
            extras.add(mainPos(), Extra(source, duration, videoW, videoH, "คลิปแรก", firstFrame).also { it.from = at; it.to = end; it.speed = speed; it.volume = volume; it.muted = mainMuted })
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
    /** Emoji, shapes and the user's own pictures or GIFs; a picked shape can change colour or be drawn as an outline. */
    private fun stickerTools(col: LinearLayout) {
        col.gravity = Gravity.TOP
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), 0, dp(10), 0) }
        STICKERS.forEach { e -> row.addView(text(e, 24f).apply { gravity = Gravity.CENTER; isClickable = true; contentDescription = "สติกเกอร์ $e"; background = glass(20, 0x1affffff); glassTouch()
            setOnClickListener { stickers += VideoSticker(e, 0.5f + (stickers.size % 3 - 1) * 0.12f, 0.45f, false); rebuildStickers(); showTool(12, false) } }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(6) }) }
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }, -1, -2)
        val chips = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(4)) }
        chips.addView(EditorKit.chip(this, "รูป / GIF ของฉัน", ADD) { pickSticker.launch(arrayOf("image/*")) }, LinearLayout.LayoutParams(-2, dp(36)).apply { setMargins(dp(4), 0, dp(4), 0) })
        if (stickers.isNotEmpty()) chips.addView(EditorKit.chip(this, "ลบอีโมจิล่าสุด", "M9 14L4 9l5-5 M4 9h10a6 6 0 0 1 0 12h-3") { stickers.removeAt(stickers.size - 1); rebuildStickers(); showTool(12, false) }, LinearLayout.LayoutParams(-2, dp(36)).apply { setMargins(dp(4), 0, dp(4), 0) })
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        col.addView(text("ลากอีโมจิบนภาพเพื่อย้าย แตะเพื่อปรับขนาด · รูปของคุณปรับต่อได้ที่เมนูเลเยอร์", 11.5f, Glass.TEXT3).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(8), dp(16), 0) }, -1, -2)
    }
    private val pickSticker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; addLayers(listOf(uri), VideoLayer.IMAGE, home = 12, size = 0.3f) }
    }
    private fun laneDp() = getSharedPreferences("camera", MODE_PRIVATE).getInt("lane_dp", 30).toFloat()
    /** Layers menu: one "+ เพิ่ม" button for a picture or a video on top, then the picked layer's controls. */
    private fun layerTools(col: LinearLayout) {
        col.gravity = Gravity.TOP; col.setPadding(dp(14), 0, dp(14), 0)
        col.addView(dropButton("+ เพิ่ม") { v -> menu.show(v, "เพิ่มเลเยอร์", listOf(GlassMenu.Option("ภาพ", ADD, "รูปซ้อนบนวิดีโอ"), GlassMenu.Option("วิดีโอ", Icons.VIDEO, "ภาพซ้อนภาพ")), -1) { k ->
            pendingKind = if (k == 0) VideoLayer.IMAGE else VideoLayer.VIDEO
            pickFromGallery.launch(Intent(this, GalleryActivity::class.java).putExtra(GalleryActivity.EXTRA_PICK, if (k == 0) "image" else "video")) } }.also { (it as LinearLayout).gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(dp(150), dp(38)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(4); bottomMargin = dp(6) })
        // Row height of the lanes: small, medium or large (all rows stay equal); the timeline resizes at once.
        val sizes = listOf(24 to "แถวเล็ก", 30 to "แถวกลาง", 40 to "แถวใหญ่")
        // Same chip size as the other panels (42 dp, equal widths) so the top edge is not cut.
        col.addView(LinearLayout(this).apply { gravity = Gravity.CENTER; clipChildren = false; clipToPadding = false; setPadding(0, dp(2), 0, dp(2)); sizes.forEach { (v, n) -> addView(EditorKit.chip(this@VideoEditorActivity, n, "M4 7h16 M4 12h16 M4 17h16", laneDp().toInt() == v) {
            getSharedPreferences("camera", MODE_PRIVATE).edit().putInt("lane_dp", v).apply(); timeline.laneDp = v.toFloat(); laneCount = -1; refreshTimeline(); showTool(LAYER_TOOL, false) }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(3), 0, dp(3), dp(4)) }) } }, -1, -2)
        val l = layers.getOrNull(layerPick)
        if (l == null || !l.visual || !barActive) {
            col.addView(text("เพิ่มภาพหรือวิดีโอซ้อนบนคลิป · แตะแถบบนไทม์ไลน์หรือแตะบนภาพเพื่อปรับเลเยอร์นั้น", 12f, Glass.TEXT3).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(24), dp(16), 0) }, -1, -2)
            return
        }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(controls, LinearLayout.LayoutParams(-1, 0, 1f))
        layerControls(controls)
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
        if (e != null) return before + StudioMath.srcToOut((trim.playhead - e.from).coerceAtLeast(0), e.to - e.from, e.speed, e.curve)
        if (mainCurve != null && !cutValid()) return before + StudioMath.srcToOut((trim.playhead - start).coerceAtLeast(0), end - start, speed, mainCurve)
        var t = trim.playhead - start
        if (cutValid() && trim.playhead >= cutTo) t -= cutMs()
        return before + (t.coerceAtLeast(0) / speed).toLong()
    }
    private fun clampedLayers(): List<VideoLayer> { val total = totalOut()
        return layers.filter { !it.hidden }.mapNotNull { l -> val at = l.at.coerceIn(0, (total - 200).coerceAtLeast(0)); val len = l.length.coerceAtMost(total - at); if (len < 200) null else l.copy(at = at, length = len) } }
    private fun layerName(l: VideoLayer) = if (l.kind == VideoLayer.TEXT) VideoLayers.shapeOf(l.text).let { k -> if (k >= 0) "รูปทรง" + VideoLayers.SHAPES[k] else l.text.replace('\n', ' ') } else when (l.kind) { VideoLayer.VIDEO -> "วิดีโอ"; VideoLayer.IMAGE -> "รูป"; else -> "เสียง" } + " · " + clock(l.length)
    /** Changes where a layer is, how big or how solid. With keyframes the change is written as a keyframe at the playhead; without, it changes the whole layer. */
    private fun posed(l: VideoLayer, x: Float? = null, y: Float? = null, size: Float? = null, opacity: Float? = null): VideoLayer {
        if (l.keys.isEmpty()) return l.copy(x = x ?: l.x, y = y ?: l.y, size = size ?: l.size, opacity = opacity ?: l.opacity)
        val now = outNow().coerceIn(l.at, l.end); val t = now - l.at; val cur = l.rawPose(now)
        return l.withKeys(l.keyList.filter { abs(it.t - t) > 120 } + VideoLayer.Key(t, x ?: cur[0], y ?: cur[1], size ?: cur[2], opacity ?: cur[3]))
    }
    private fun askText(initial: String, done: (String) -> Unit) {
        val field = GlassDialog.field(this, "พิมพ์ข้อความ", initial, false, 2)
        GlassDialog.ask(this, "ข้อความ", EditorKit.TEXT, null, field, focus = field) { field.text.toString().trim().takeIf { it.isNotEmpty() }?.let(done); true }
    }
    private fun addShape(kind: Int) {
        val words = VideoLayers.SHAPE + kind
        val draft = VideoLayer(VideoLayer.TEXT, VideoLayer.textSource(words, Glass.ACCENT, 0), 0L, 3000L, text = words, color = Glass.ACCENT)
        val pic = runCatching { VideoLayers.textPicture(draft, TEXT_PREVIEW) }.getOrNull() ?: return
        layerThumbs[draft.source] = pic
        val total = totalOut(); val len = 3000L.coerceAtMost(total).coerceAtLeast(200); val at = outNow().coerceAtMost((total - len).coerceAtLeast(0))
        layers += draft.copy(at = at, length = len, size = 0.3f, aspect = pic.width.toFloat() / pic.height)
        layerPick = layers.size - 1; barActive = true; rebuildLayers(); refreshTimeline(); showTool(12, false)
    }
    private fun addText(words: String, y: Float = 0.8f, startAt: Long? = null, length: Long = 3000L) {
        val draft = VideoLayer(VideoLayer.TEXT, VideoLayer.textSource(words, Color.WHITE, 0), 0L, 3000L, text = words)
        val pic = runCatching { VideoLayers.textPicture(draft, TEXT_PREVIEW) }.getOrNull() ?: return
        layerThumbs[draft.source] = pic
        val total = totalOut(); val len = length.coerceAtMost(total).coerceAtLeast(200); val at = (startAt ?: outNow()).coerceAtMost((total - len).coerceAtLeast(0))
        layers += draft.copy(at = at, length = len, x = 0.5f, y = y, size = (pic.width.toFloat() / TEXT_PREVIEW * 0.9f).coerceIn(0.12f, 0.95f), aspect = pic.width.toFloat() / pic.height)
        layerPick = layers.size - 1; barActive = true; rebuildLayers(); refreshTimeline(); showTool(4, false)
        notice("เพิ่มข้อความแล้ว · ลากบนภาพเพื่อย้าย ลากแถบสีเหลืองบนไทม์ไลน์เพื่อกำหนดช่วงเวลา")
    }
    /** New words, colour or style for a text layer. The letters keep their height: a longer text gets wider, not smaller. */
    private fun retext(i: Int, words: String = layers[i].text, color: Int = layers[i].color, style: Int = layers[i].style, rebuildTool: Boolean = true) {
        val l = layers.getOrNull(i) ?: return; val oldW = layerThumbs[l.source]?.width ?: 0
        val n = l.copy(text = words, color = color, style = style, source = VideoLayer.textSource(words, color, style))
        val pic = runCatching { VideoLayers.textPicture(n, TEXT_PREVIEW) }.getOrNull() ?: return
        layerThumbs[n.source] = pic
        val k = if (oldW > 0) pic.width.toFloat() / oldW else 1f
        layers[i] = n.copy(aspect = pic.width.toFloat() / pic.height, size = (l.size * k).coerceIn(0.05f, 1f))
        rebuildLayers(); refreshTimeline(); if (rebuildTool) showTool(tool, false)
    }

    private fun addLayers(uris: List<Uri>, kind: Int, startAt: Long? = null, home: Int = -1, size: Float = 0f, music: Boolean = false) {
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
                    layers += VideoLayer(kind, uri, at, len, 0L, 0.5f + (n % 3 - 1) * 0.15f, 0.42f + (n % 2) * 0.12f, if (size > 0f) size else if (kind == VideoLayer.IMAGE) 0.4f else 0.45f, aspect, music = music)
                }
                layerPick = layers.size - 1; barActive = true
                rebuildLayers(); refreshTimeline(); showTool(if (home >= 0) home else if (kind == VideoLayer.SOUND) 9 else LAYER_TOOL, false)
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
            // A GIF moves on the preview too (Android 9 and newer play it as an animated picture).
            if (l.kind == VideoLayer.IMAGE && android.os.Build.VERSION.SDK_INT >= 28 && gifCache.getOrPut(l.source) { VideoLayers.isGif(this, l.source) } && !l.matte.active)
                runCatching { (android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(contentResolver, l.source)) as? android.graphics.drawable.AnimatedImageDrawable)?.let { d -> v.setImageDrawable(d); v.background = null; d.start() } }
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
                    MotionEvent.ACTION_UP -> if (!moved) { Glass.tick(view); pickBar(i) }
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
                runCatching { val v = l.volume.coerceAtMost(1f) * l.fade(now) * (if (l.music) duckGain() else 1f); p.setVolume(v, v) }
            } else if (mp?.isPlaying == true) mp.pause()
        }
    }
    private fun releaseLayerSound() { layerSound.values.forEach { it.runCatching { release() } }; layerSound.clear() }

    /** Add video, picture and sound layers (video and pictures from Lumora's own gallery), then edit the chosen one. */
    private val gifCache = HashMap<Uri, Boolean>()
    private var copiedPose: FloatArray? = null
    private val rematte = Runnable { if (!isDestroyed) { val keep = layerPick; rebuildLayers(); layerPick = keep; placeLayers() } }
    private fun layerControls(box: LinearLayout) {
        box.removeAllViews()
        val i = layerPick; val l = layers.getOrNull(i) ?: return
        fun refresh() { placeLayers(); refreshTimeline() }
        val actions = ArrayList<View>()
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
        actions += EditorKit.action(this, "M6 11h12v9H6z M8 11V8a4 4 0 0 1 8 0v3", if (l.locked) "ปลดล็อก" else "ล็อก", l.locked) { layers[i] = layers[i].copy(locked = !layers[i].locked); placeLayers(); refreshTimeline(); layerControls(box) }
        actions += EditorKit.action(this, "M8 8h11v11H8z M5 16V5h11", "ทำสำเนา") { val cur = layers[i]; val total = totalOut()
            layers += cur.copy(at = (cur.end).coerceAtMost((total - cur.length).coerceAtLeast(0)), x = (cur.x + 0.04f).coerceAtMost(1f), y = (cur.y + 0.04f).coerceAtMost(1f))
            layerPick = layers.size - 1; rebuildLayers(); refreshTimeline(); showTool(tool, false) }
        actions += EditorKit.action(this, "M5 7h14 M9 7V4h6v3 M7 7l1 13h8l1-13", "ลบ") { layers.removeAt(i); layerPick = (i - 1).coerceAtLeast(if (layers.isEmpty()) -1 else 0); rebuildLayers(); barActive = false; refreshTimeline(); showTool(tool, false) }
        box.addView(EditorKit.actionRow(this, actions), -1, -2)
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
        if (l.visual) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(6), dp(12), dp(2)) }
            fun chip(name: String, f: () -> Unit) = row.addView(text(name, 12f, Glass.TEXT).apply { gravity = Gravity.CENTER; background = glass(16, 0x26ffffff)
                setPadding(dp(12), 0, dp(12), 0); isClickable = true; glassTouch(); contentDescription = name; setOnClickListener { f(); refresh(); layerControls(box) } }, LinearLayout.LayoutParams(-2, dp(32)).apply { rightMargin = dp(6) })
            // Place presets set where the layer is and how big (as a keyframe at the playhead when the layer has keyframes).
            chip("กลางภาพ") { layers[i] = posed(layers[i], x = 0.5f, y = 0.5f) }
            chip("เต็มจอ") { layers[i] = posed(layers[i], x = 0.5f, y = 0.5f, size = 1f) }
            chip("มุมขวาบน") { layers[i] = posed(layers[i], x = 0.76f, y = 0.2f, size = 0.36f) }
            chip("มุมซ้ายล่าง") { layers[i] = posed(layers[i], x = 0.24f, y = 0.8f, size = 0.36f) }
            // Animation presets write keyframes at the start and the end of the layer from where it is now.
            fun animate(name: String, inOut: (FloatArray, Boolean) -> FloatArray) = chip(name) { val cur = layers[i]; val p = cur.rawPose(cur.at + cur.length / 2)
                val d = minOf(450L, cur.length / 3); val a = inOut(p, true); val z = inOut(p, false)
                layers[i] = cur.withKeys(listOf(VideoLayer.Key(0, a[0], a[1], a[2], a[3]), VideoLayer.Key(d, p[0], p[1], p[2], p[3]), VideoLayer.Key(cur.length - d, p[0], p[1], p[2], p[3]), VideoLayer.Key(cur.length, z[0], z[1], z[2], z[3]))).copy(fadeIn = 0, fadeOut = 0) }
            animate("เข้า-ออก: จาง") { p, _ -> floatArrayOf(p[0], p[1], p[2], 0f) }
            animate("เข้า-ออก: เลื่อนขึ้น") { p, first -> floatArrayOf(p[0], p[1] + if (first) 0.08f else -0.08f, p[2], 0f) }
            animate("เข้า-ออก: ขยาย") { p, _ -> floatArrayOf(p[0], p[1], p[2] * 0.6f, 0f) }
            animate("เข้า-ออก: เลื่อนข้าง") { p, first -> floatArrayOf(if (first) -0.3f else 1.3f, p[1], p[2], 1f) }
            if (l.keys.isNotEmpty()) chip("ล้างแอนิเมชัน") { val cur = layers[i]; val p = cur.rawPose(outNow().coerceIn(cur.at, cur.end)); layers[i] = cur.copy(keys = "", x = p[0], y = p[1], size = p[2], opacity = p[3]) }
            box.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }, LinearLayout.LayoutParams(-1, -2))
        }
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
        box.addView(keepScroll(android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(sliders) }), LinearLayout.LayoutParams(-1, 0, 1f))
    }

    // ---------- Undo, redo and the saved project ----------
    private var projectId = ""; private var projectName = ""
    /** True when the person named the project on purpose (New project): it exists from the start, even before any edit. */
    private var named = false
    private var saveLabel: TextView? = null
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
        val keepTool = tool; val keepPick = layerPick; val oldSource = source
        restore(b); if (source != oldSource && playIndex == 0) { showClipOnBar(); openPlayer() }; tool = keepTool; layerPick = keepPick.coerceAtMost(layers.size - 1)
        prepareMusic(); applySound(); applyLook(); layoutVideo(); rebuildStickers(); rebuildLayers(); loadLayerThumbs()
        val uris = b.getStringArrayList("extras") ?: arrayListOf()
        fun done() { if (playIndex > extras.size) playClip(0, false) else { showClipOnBar(); trim.playhead = trim.playhead.coerceIn(trim.start, trim.end) }; showTool(tool, false); showTime(); committed = state(); committedKey = StudioProjects.key(committed!!); pendingKey = committedKey; applying = false }
        if (uris.all { it in extraCache }) { extras.clear(); extras += buildExtras(uris, b.getStringArrayList("extraRanges")) { extraCache[it] }; done() } else restoreExtras(b) { done() }
        if (playIndex == 0) seek(trim.playhead.coerceIn(start, end))
    }
    private fun saveProject() {
        if (projectId.isEmpty()) return
        // A project file appears only once something was really edited; just opening a video leaves no trace.
        if (!named && !changed() && !StudioProjects.exists(this, projectId)) return
        val snap = state(); val pic = firstFrame; val id = projectId; val name = projectName
        StudioProjects.io.execute {
            val ok = runCatching { StudioProjects.save(applicationContext, id, name, source, snap, pic) }.isSuccess
            if (ok) runOnUiThread { if (!isDestroyed) saveLabel?.let { l -> l.text = "บันทึกอัตโนมัติแล้ว"; l.animate().cancel(); l.alpha = 1f; l.animate().alpha(0f).setStartDelay(1800).setDuration(500).start() } }
        }
    }

    /**
     * Leaving never asks and never loses work: the edit has been saved as a project all along (after every change,
     * and again here), so the latest state is what opens next time from Home. Exporting stays a separate choice.
     */
    private fun leave() {
        if (overlay != null) { goBackground(); return }
        if (!changed() && !named) { finish(); return }
        saveProject()
        notice("บันทึกงานล่าสุดแล้ว · กลับมาตัดต่อต่อได้จากหน้าหลัก")
        finish()
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
        val r = cropRatio()
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
        if (c.audioOnly) { exportAudio(c); return }
        val bigger = c.shortSide > src.shortSide * 1.05f; val smoother = c.fps > src.fps * 1.05f
        if (!changed() && !bigger && !smoother && c.shortSide >= src.shortSide - 8 && c.fps >= src.fps - 0.6f) { notice("ยังไม่มีการแก้ไข"); return }
        enhance = if (bigger || smoother) VideoEnhancer.Plan(c.shortSide, c.fps, c.ai, c.profile, c.flowMode, c.mbps) else null
        shrinkShort = if (enhance == null && c.shortSide < src.shortSide - 8) c.shortSide else 0
        shrinkFps = if (enhance == null && c.fps < src.fps - 0.6f) c.fps else 0f
        // Several clips or layers become one file, so the split (two files of the main clip) is left out then.
        val whole = extras.isNotEmpty() || layers.isNotEmpty() || enhance != null
        if (split >= 0 && whole) notice("ส่งออกรวมเป็นไฟล์เดียว ไม่แยก")
        val ranges = if (split >= 0 && !whole) listOf(start to split, split to end) else listOf(start to end)
        val steps = ArrayList<String>(); val weights = ArrayList<Float>(); val kinds = ArrayList<Int>()
        val slow = smooth && speed < 1f
        // Weights are estimated minutes of work, so the percentage and the time left follow real effort. (The AI / in-between step used to be
        // weighted far above the edit step, so a long clip sat at 0% through the whole edit.)
        val editMin = (src.durationMs / 60_000.0 * 0.35).toFloat().coerceAtLeast(0.2f)
        if (slow) { steps += "สร้างเฟรมสโลว์ลื่นด้วย Optical flow"; weights += editMin * 3f; kinds += ExportProgress.Backdrop.FLOW }
        val stageEdit = steps.size; steps += "ตัดต่อและใส่เอฟเฟกต์"; weights += editMin; kinds += ExportProgress.Backdrop.EDIT
        var stageEnhance = -1
        enhance?.let { e ->
            stageEnhance = steps.size
            steps += listOfNotNull(if (bigger) (if (e.ai) "AI สร้างพิกเซลเพิ่มเป็น ${ExportSheet.sideName(c.shortSide)}" else "ขยายเป็น ${ExportSheet.sideName(c.shortSide)}") else null,
                if (smoother) "สร้างเฟรมแทรกเป็น ${ExportSheet.fpsName(c.fps)} fps" else null).joinToString(" และ")
            weights += ExportSheet.minutes(src, c.shortSide, c.fps, e.ai).toFloat().coerceAtLeast(0.5f)
            kinds += if (bigger && e.ai) ExportProgress.Backdrop.AI else ExportProgress.Backdrop.FLOW
        }
        val stageSave = steps.size; steps += "บันทึกลงแกลเลอรี"; weights += 0.1f; kinds += ExportProgress.Backdrop.SAVE
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
        startJob(plan, still)
    }
    private fun startJob(plan: ExportJob.Plan, still: Bitmap? = firstFrame) {
        if (ExportJob.running()) { notice("มีงานส่งออกอยู่แล้ว · รอให้เสร็จก่อน"); return }
        LiveNotice.askPermissions(this)
        ExportJob.start(this, plan)
        exportButton.alpha = 0.5f
        prog = ExportProgress(this, still, plan.steps, plan.kinds, { cancelExport() }, { goBackground() })
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

    /** Exports only the sound of the edit (clips, music, voice and sound layers) as an .m4a file into the music folder. */
    private fun exportAudio(c: ExportChoice) {
        val clipSound = hasAudio && keepSound && allSpeeds().all { it in SOUND_MIN..SOUND_MAX }
        if (!clipSound && layers.none { it.audible && !it.hidden && it.volume > 0f }) { notice("วิดีโอนี้ไม่มีเสียงให้ส่งออก"); return }
        enhance = null; shrinkShort = 0; shrinkFps = 0f; soundHz = c.audioHz; soundMono = c.audioMono
        val id = System.currentTimeMillis(); val dir = File(filesDir, "video-exports/$id").apply { mkdirs() }
        val wav = c.audioFormat == 1
        val plan = ExportJob.Plan(if (wav) listOf("รวมเสียงทั้งหมด", "แปลงเป็น WAV", "บันทึกลงโฟลเดอร์เพลง") else listOf("รวมเสียงทั้งหมด", "บันทึกลงโฟลเดอร์เพลง"),
            if (wav) listOf(1f, 0.4f, 0.3f) else listOf(1f, 0.3f), firstFrame, 0, 0, if (wav) 1 else -1, if (wav) 2 else 1,
            slow = null, parts = { _ -> flow = null; listOf(composition(start, end, audioOnly = true) to File(dir, "Lumora_AUDIO_$id.m4a")) },
            enhance = null, finalFile = null, note = "เสียงจาก Lumora", cleanup = {},
            kinds = if (wav) listOf(ExportProgress.Backdrop.EDIT, ExportProgress.Backdrop.EDIT, ExportProgress.Backdrop.SAVE) else listOf(ExportProgress.Backdrop.EDIT, ExportProgress.Backdrop.SAVE),
            audioKbps = if (wav) 320 else c.audioKbps.coerceAtLeast(32), wav = wav)
        startJob(plan)
    }
    /** Sound-only export settings from the sheet (sample rate, mono), read when the sound composition is built. */
    private var soundHz = 0; private var soundMono = false
    private fun composition(from: Long, to: Long, audioOnly: Boolean = false): Composition {
        // With smooth slow motion the main clip is the rendered file: n times longer, so times scale by n and the
        // speed left to apply is speed × n (about 1×). It has no sound.
        val f = flow; val n = f?.factor ?: 1
        fun at(t: Long) = if (f == null) t else (t - start) * n
        val vw = f?.width ?: videoW; val vh = f?.height ?: videoH
        val cut = cutValid() && cutFrom >= from && cutTo <= to
        return VideoEdit.composition(VideoEditSpec(f?.let { Uri.fromFile(it.file) } ?: source, at(from), at(to), if (turns % 2 == 1) vh else vw, if (turns % 2 == 1) vw else vh,
        speed * n, cropRatio(), caption, place, textColor, textBig, if (looked()) colour() else null, music, f == null && hasAudio && keepSound && allSpeeds().all { it in SOUND_MIN..SOUND_MAX },
        turns, mirror, volume, musicVolume, if (cut) at(cutFrom) else -1L, if (cut) at(cutTo) else -1L, textBox,
        extras.map { VideoPart(it.uri, it.from, it.to, it.speed, it.muted, it.volume, it.curve) }, if (extras.isEmpty()) 0 else transition, stickers.toList(), clampedLayers(), shrinkShort, shrinkFps, mainPos(), fx[0], fx[1], fx[2], grade = if (grade.active()) gradeCube(33) else null, audio = audioSettings(), mainMuted = mainMuted, mainCurve = if (f == null) mainCurve else null, audioOnly = audioOnly, duck = duck, audioHz = if (audioOnly) soundHz else 0, audioMono = audioOnly && soundMono), applicationContext)
    }

    /** Export problems stay on screen until dismissed, so the reason can be read (and reported). */
    private fun failed(reason: String) { if (!isFinishing) GlassDialog.show(this, "ส่งออกไม่ได้", "$reason\nวิดีโอต้นฉบับยังอยู่ครบ ลองปิดฟิลเตอร์หรือข้อความแล้วส่งออกอีกครั้ง") }
    private fun hideProgress() { flow = null; prog?.remove(); prog = null; overlay = null; exportButton.alpha = 1f; ExportJob.unlisten(jobListener) }
    private fun cancelExport() { ExportJob.cancel() }

    // ---------- Lifecycle ----------
    override fun onPause() { player?.let { if (it.isPlaying) togglePlay() }; if (prog != null) th.ogay.luma.ai.AiPace.watching = false; super.onPause(); if (::trim.isInitialized && overlay == null) saveProject() }
    override fun onResume() { super.onResume(); if (prog != null) th.ogay.luma.ai.AiPace.watching = true }
    override fun onDestroy() { (speech as? StudioSpeech)?.stop(); speech = null; TaskNotice.stop(this); if (translating) { translateStopped = true; LlamaTranslator.cancel() }; if (hearingModels) { ModelStore.unlisten(modelEvents); hearingModels = false }; fxEq?.runCatching { release() }; fxEq = null; recorder?.runCatching { stop() }; recorder?.runCatching { release() }; recorder = null; handler.removeCallbacksAndMessages(null); releasePlayer(); musicPlayer?.runCatching { release() }; releaseLayerSound(); super.onDestroy() }
    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out); writeState(out)
        out.putString("projectId", projectId); out.putString("projectName", projectName); out.putBoolean("named", named)
    }
    private fun state() = Bundle().also { writeState(it) }
    private fun writeState(out: Bundle) {
        out.putString("mainSource", source.toString())
        out.putLong("start", start); out.putLong("end", end); out.putLong("split", split); out.putFloat("speed", speed); out.putInt("crop", cropIndex); out.putFloat("customCrop", customCrop)
        out.putString("caption", caption); out.putInt("place", place); out.putInt("textColor", textColor); out.putBoolean("big", textBig)
        out.putString("film", film?.id); out.putFloat("strength", filmStrength); out.putString("music", music?.toString()); out.putString("musicName", musicName)
        out.putBoolean("sound", keepSound); out.putInt("tool", tool)
        out.putFloatArray("adjust", adjust); out.putFloatArray("fx", fx); out.putFloatArray("curves", grade.curves.copyOf()); out.putFloatArray("hsl", grade.hsl.copyOf()); out.putFloatArray("wheels", grade.wheels.copyOf()); out.putString("lutPath", lutPath); out.putString("lutName", lutName); out.putFloat("lutAmount", grade.lutAmount); out.putFloatArray("eq", eq.copyOf()); out.putBoolean("lowCut", lowCut); out.putBoolean("voice", voice); out.putBoolean("duck", duck); out.putBoolean("mainMuted", mainMuted); out.putInt("turns", turns); out.putBoolean("mirror", mirror); out.putFloat("volume", volume); out.putFloat("musicVolume", musicVolume)
        out.putLong("cutFrom", cutFrom); out.putLong("cutTo", cutTo); out.putBoolean("textBox", textBox)
        out.putStringArrayList("extras", ArrayList(extras.map { it.uri.toString() })); out.putStringArrayList("extraRanges", ArrayList(extras.map { "${it.from},${it.to},${it.speed}," + (if (it.muted) "m" else "") + ",${it.volume}," + (it.curve?.joinToString(";") ?: "") }));
        mainCurve?.let { out.putFloatArray("mainCurve", it.copyOf()) }; out.putInt("mainAt", mainAt); out.putStringArrayList("markers", ArrayList(markers.map { it.toString() })); out.putInt("transition", transition)
        out.putStringArrayList("stickers", ArrayList(stickers.map { "${it.x}|${it.y}|${it.big}|${it.text}" }))
        out.putString("layers", VideoLayer.save(layers)); out.putInt("layerPick", layerPick); out.putBoolean("smooth", smooth)
    }
    private fun restore(s: Bundle) {
        s.getString("mainSource")?.let { Uri.parse(it) }?.takeIf { it != source }?.let { switchSource(it) }
        start = s.getLong("start").coerceIn(0, (duration - 300).coerceAtLeast(0)); end = s.getLong("end", duration).coerceIn(start, duration); customCrop = s.getFloat("customCrop", 1f); split = s.getLong("split", -1); speed = s.getFloat("speed", 1f); cropIndex = s.getInt("crop")
        caption = s.getString("caption") ?: ""; place = s.getInt("place", 2); textColor = s.getInt("textColor", Color.WHITE); textBig = s.getBoolean("big")
        film = Films.byId(s.getString("film")); filmStrength = s.getFloat("strength", 1f); music = s.getString("music")?.let { Uri.parse(it) }; musicName = s.getString("musicName") ?: ""
        keepSound = s.getBoolean("sound", true); tool = s.getInt("tool")
        s.getFloatArray("adjust")?.takeIf { it.size == adjust.size }?.copyInto(adjust); fx.fill(0f); restoreGrade(s); mainCurve = s.getFloatArray("mainCurve")?.takeIf { it.size == 5 }?.copyOf(); eq.fill(0f); s.getFloatArray("eq")?.takeIf { it.size == 3 }?.copyInto(eq); lowCut = s.getBoolean("lowCut"); voice = s.getBoolean("voice"); duck = s.getBoolean("duck", true); mainMuted = s.getBoolean("mainMuted"); s.getFloatArray("fx")?.takeIf { it.size == fx.size }?.copyInto(fx); turns = s.getInt("turns"); mirror = s.getBoolean("mirror"); volume = s.getFloat("volume", 1f); musicVolume = s.getFloat("musicVolume", 1f)
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
            if (keep == video) { // No crop: still outline the frame so its edge is clear, like the program monitor in Premiere.
                line.strokeWidth = resources.displayMetrics.density; c.drawRect(keep.left + 0.5f, keep.top + 0.5f, keep.right - 0.5f, keep.bottom - 0.5f, line); return }
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
            c.drawColor(Glass.PANEL)
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
