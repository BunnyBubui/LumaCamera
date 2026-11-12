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
        private val TRANSITIONS = listOf("ตัดตรง", "จางดำ", "แฟลชขาว", "ซูมเข้า")
        private val STICKERS = listOf("😀", "😂", "😍", "🥳", "😎", "🔥", "❤️", "✨", "👍", "🎉", "⭐", "🌸", "☀️", "🌙", "📍", "💯")
        private const val CLIPS = "M3 6h7v12H3z M14 6h7v12h-7z"
        private const val JOIN = "M3 5h6v14H3z M15 5h6v14h-6z M10 12h4 M12.5 10l1.5 2-1.5 2"
        private const val STICKER = "M12 3a9 9 0 1 0 0.01 0 M8.5 14q3.5 3 7 0 M9 9.5v1 M15 9.5v1"
        private const val ADD = "M12 5v14 M5 12h14"
        private const val LAYERS = "M12 3l9 5-9 5-9-5z M3 12.5l9 5 9-5 M3 17l9 5 9-5"
        private const val LAYER_TOOL = 13
        private const val SMOOTH = "M3 17c3 0 4-10 9-10s6 10 9 10"
        private const val LEFT = "M15 6l-6 6 6 6"
        private const val RIGHT = "M9 6l6 6-6 6"
    }

    /** A clip added after the main one; it plays whole and takes the main clip's frame shape. */
    private class Extra(val uri: Uri, val duration: Long, val w: Int, val h: Int, val name: String, var thumb: Bitmap?)

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
                if (pos >= end - 30) { if (extras.isNotEmpty()) playClip(1) else { p.seekTo(start.toInt()); musicPlayer?.seekTo(0) } }
                else if (cutValid() && pos in cutFrom until cutTo) p.seekTo(cutTo.toInt())
                trim.playhead = pos.coerceIn(start, end)
            }
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
                if (extra == null) notice("เปิดวิดีโอนี้ไม่ได้") else { extraCache[uri.toString()] = extra; extras += extra; showTime(); showTool(10, false); notice("เพิ่มคลิปแล้ว · ส่งออกรวมเป็นไฟล์เดียว") }
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
        transport.addView(EditorKit.chip(this, "เพิ่มคลิป", ADD) { pickClip.launch(arrayOf("video/*")) }, LinearLayout.LayoutParams(-2, dp(44)))
        root.addView(transport, -1, -2)
        trim = TrimBar(this).apply {
            contentDescription = "ช่วงคลิป ลากขอบซ้ายขวาเพื่อตัด"
        }
        // Set outside apply{}: inside it, start/end/split would name the bar's own fields, not the edit's.
        trim.onRange = { s, e -> start = s; end = e; if (split !in (s + 300)..(e - 300)) split = -1; trim.split = split; seek(trim.playhead.coerceIn(s, e)); showTime() }
        trim.onSeek = { ms -> seek(ms) }
        root.addView(trim, LinearLayout.LayoutParams(-1, dp(64)).apply { setMargins(dp(12), dp(4), dp(12), dp(4)) })
        tools = FrameLayout(this).apply { clipChildren = false }
        root.addView(tools, LinearLayout.LayoutParams(-1, dp(124)))
        toolBar = EditorKit.ToolBar(this, listOf(EditorKit.SCISSORS to "ตัด", EditorKit.SPLIT to "แยก", EditorKit.SPEED to "ความเร็ว", EditorKit.CROP to "ครอบ", EditorKit.TEXT to "ข้อความ", Icons.FILM to "ฟิลเตอร์", EditorKit.MUSIC to "เพลง", EditorKit.ADJUST to "ปรับ", EditorKit.ROTATE to "หมุน", Icons.SOUND to "เสียง", CLIPS to "คลิป", JOIN to "ทรานซิชัน", STICKER to "สติกเกอร์", LAYERS to "เลเยอร์")) { showTool(it) }
        root.addView(toolBar, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(4), 0, dp(4), dp(8)) })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = leave() })

        trim.duration = duration; trim.start = start; trim.end = end; trim.split = split
        luma.readIo.execute {
            val frames = frames(10)
            runOnUiThread { if (!isDestroyed) { trim.frames = frames; firstFrame = frames.firstOrNull(); if (tool == 5) showTool(5, false) } }
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
    override fun onPause() { super.onPause(); if (::trim.isInitialized && overlay == null) saveProject() }
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
            val list = uris.mapNotNull { u -> extraCache[u] ?: readExtra(Uri.parse(u)) }
            runOnUiThread { if (!isDestroyed) { list.forEach { extraCache[it.uri.toString()] = it }; extras.clear(); extras += list; showTime(); if (tool == 10) showTool(10, false); done() } }
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
        releasePlayer()
        if (playIndex > extras.size) playIndex = 0
        val clip = if (playIndex == 0) source else extras[playIndex - 1].uri
        player = MediaPlayer().apply {
            runCatching {
                setDataSource(this@VideoEditorActivity, clip); setSurface(s); isLooping = false
                setOnPreparedListener { mp ->
                    prepared = true; if (playIndex == 0) seekTo(this@VideoEditorActivity.start.toInt()); applySound(); layoutVideo(); arrived()
                    if (autoPlay) { autoPlay = false; if (playIndex == 0) runCatching { mp.playbackParams = mp.playbackParams.setSpeed(speed) }; mp.start(); musicPlayer?.runCatching { start() }; playIcon.path = "M8 6v12 M16 6v12" }
                }
                setOnCompletionListener { if (playIndex == 0) seekTo(this@VideoEditorActivity.start.toInt()) else playClip(if (playIndex < extras.size) playIndex + 1 else 0) }
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
        if (k == 0) musicPlayer?.runCatching { seekTo(0) }
        switching = true
        val go = Runnable { playIndex = k; switching = false; openPlayer(); showTime() }
        if ((transition == 1 || transition == 2) && !Glass.reduced(this)) {
            dip.setBackgroundColor(if (transition == 2) Color.WHITE else Color.BLACK)
            dip.animate().cancel(); dip.animate().alpha(1f).setDuration(VideoEdit.TRANSITION_MS / 2).withEndAction(go).start()
        } else go.run()
    }
    /** The new clip is ready: uncover it (fade, flash) or let it settle from a little closer (zoom). */
    private fun arrived() {
        if (Glass.reduced(this)) { dip.alpha = 0f; return }
        if (dip.alpha > 0f) dip.animate().alpha(0f).setDuration(VideoEdit.TRANSITION_MS).start()
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
    private fun applySound() { val v = if (keepSound) volume.coerceAtMost(1f) else 0f; if (playIndex > 0) player?.runCatching { playbackParams = playbackParams.setSpeed(1f) }; player?.runCatching { setVolume(v, v) }; musicPlayer?.runCatching { setVolume(musicVolume.coerceAtMost(1f), musicVolume.coerceAtMost(1f)) } }
    private fun togglePlay() {
        val p = player ?: return; if (!prepared) return
        if (p.isPlaying) { p.pause(); musicPlayer?.pause(); syncLayerSound(false); playIcon.path = "M9 6.5v11l9-5.5z" }
        else {
            if (playIndex == 0 && p.currentPosition >= end - 60) p.seekTo(start.toInt())
            runCatching { p.playbackParams = p.playbackParams.setSpeed(if (playIndex == 0) speed else 1f) }
            p.start(); musicPlayer?.let { mp -> runCatching { mp.seekTo(((p.currentPosition - start).coerceAtLeast(0) % max(1, mp.duration)).toInt()); mp.start() } }
            playIcon.path = "M8 6v12 M16 6v12"
        }
    }
    private fun seek(ms: Long) {
        trim.playhead = ms
        // Touching the main clip's timeline brings the preview back to it.
        if (playIndex != 0) { playClip(0, false); return }
        val p = player ?: return
        if (prepared) { if (android.os.Build.VERSION.SDK_INT >= 26) p.seekTo(ms, MediaPlayer.SEEK_CLOSEST) else p.seekTo(ms.toInt()) }
        showTime()
    }
    private fun clock(ms: Long) = String.format(Locale.US, "%d:%02d.%d", ms / 60000, ms / 1000 % 60, ms / 100 % 10)
    private fun showTime() {
        val length = ((end - start) / speed).toLong()
        val more = extras.sumOf { it.duration }
        timeLabel.text = (if (playIndex > 0) "คลิป ${playIndex + 1}/${extras.size + 1}  ·  " else "${clock(trim.playhead - start)} / ${clock(end - start)}") +
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
        texture.setLayerPaint(if (!looked()) null else Paint().apply { colorFilter = ColorMatrixColorFilter(colour()) })
        captionView.setBackgroundColor(if (textBox) 0x99000000.toInt() else 0); captionView.setPadding(if (textBox) dp(8) else 0, 0, if (textBox) dp(8) else 0, 0)
        captionView.text = caption; captionView.setTextColor(textColor); captionView.visibility = if (caption.isBlank()) View.GONE else View.VISIBLE
        layoutVideo(); if (::timeLabel.isInitialized) showTime()
    }

    // ---------- Tools ----------
    private fun showTool(i: Int, animate: Boolean = true) {
        val dir = if (animate) i.compareTo(tool) else 0
        tool = i; toolBar.select(i, animate)
        // The layer lanes need room; the preview gives way smoothly.
        val want = dp(if (i == LAYER_TOOL) 276 else if (i == 2 && speed < 1f) 150 else 124)
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
        val names = EditorKit.ADJUSTMENTS.dropLast(1)
        val dials = names.mapIndexed { k, n -> EditorKit.Dial(this, EditorKit.ADJUST_ICONS[k], n).apply { amount = adjust[k] } }
        val slider = GlassSlider(this).apply { values = (-100..100).map { if (it > 0) "+$it" else "$it" }; grad = intArrayOf(0xff8e8e93.toInt(), Color.WHITE, Glass.ACCENT) }
        fun show(k: Int) { adjustIndex = k; dials.forEachIndexed { j, d -> d.lit = j == k }; value.text = "${names[k]}  ${sign(adjust[k])}"; slider.index = (adjust[k] * 100).roundToInt() + 100; slider.contentDescription = names[k] }
        col.addView(value, -1, -2)
        col.addView(EditorKit.strip(this, dials.map { it to 58 }, adjustIndex.coerceIn(0, names.size - 1), 0, { k -> show(k) },
            tapAgain = { k -> adjust[k] = 0f; dials[k].amount = 0f; show(k); applyLook() }) { k -> show(k) }, LinearLayout.LayoutParams(-1, dp(56)))
        slider.onChange = { i -> adjust[adjustIndex] = (i - 100) / 100f; dials[adjustIndex].amount = adjust[adjustIndex]; value.text = "${names[adjustIndex]}  ${sign(adjust[adjustIndex])}"; applyLook() }
        col.addView(slider, LinearLayout.LayoutParams(-1, dp(40)).apply { setMargins(dp(12), 0, dp(12), 0) })
        show(adjustIndex.coerceIn(0, names.size - 1))
    }
    /** Loudness of the clip's sound and of the music, 0–200 %. */
    private fun soundTools(col: LinearLayout) {
        fun row(icon: String, label: String, value: Float, enabled: Boolean, set: (Float) -> Unit) {
            val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(12), 0); alpha = if (enabled) 1f else 0.4f }
            r.addView(IconView(this, icon, 2f).apply { tint = Glass.TEXT2 }, dp(20), dp(20))
            r.addView(text(label, 12f, Glass.TEXT2, true).apply { setPadding(dp(8), 0, 0, 0) }, dp(84), -2)
            r.addView(GlassSlider(this).apply { values = (0..200 step 5).map { "$it%" }; index = (value * 20).roundToInt(); grad = intArrayOf(0xff8e8e93.toInt(), Glass.ACCENT); contentDescription = label; isEnabled = enabled
                onChange = { i -> set(i / 20f); applySound() } }, LinearLayout.LayoutParams(0, dp(44), 1f))
            col.addView(r, -1, -2)
        }
        row(Icons.SOUND, "เสียงคลิป", volume, hasAudio && keepSound) { volume = it }
        row(EditorKit.MUSIC, "เพลง", musicVolume, music != null) { musicVolume = it }
        if (music == null) col.addView(text("เลือกเพลงได้ที่แท็บเพลง", 11.5f, Glass.TEXT3).apply { gravity = Gravity.CENTER }, -1, -2)
    }
    private fun musicTools(col: LinearLayout) {
        if (music != null) col.addView(text(musicName, 12f, Glass.TEXT2, true).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setPadding(dp(16), 0, dp(16), dp(6)) }, -1, -2)
        val items = mutableListOf<View>(EditorKit.action(this, EditorKit.MUSIC, if (music == null) "เลือกเพลง" else "เปลี่ยนเพลง", music != null) { pickMusic.launch(arrayOf("audio/*")) })
        if (hasAudio) items += EditorKit.action(this, Icons.SOUND, "เสียงคลิป", keepSound) { keepSound = !keepSound; applySound(); showTool(6, false) }
        if (music != null) items += EditorKit.action(this, Icons.CLOSE, "ลบเพลง") { music = null; musicName = ""; musicPlayer?.runCatching { release() }; musicPlayer = null; showTool(6, false) }
        col.addView(EditorKit.actions(this, items), -1, -2)
    }

    private fun changed() = start != 0L || end != duration || split >= 0 || speed != 1f || cropIndex != 0 || caption.isNotBlank() || film != null || music != null || !keepSound || looked() || turns != 0 || mirror || volume != 1f || cutValid() || extras.isNotEmpty() || stickers.isNotEmpty() || layers.isNotEmpty()

    // ---------- Clips, transitions, stickers ----------
    /** The clips in playing order: the main one, then the added ones, which can be moved or removed. */
    private fun clipTools(col: LinearLayout) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), 0) }
        fun card(thumb: Bitmap?, label: String, on: Boolean, click: () -> Unit) = FrameLayout(this).apply {
            background = glass(14, if (on) Glass.ACCENT_SOFT else 0x26ffffff); setPadding(dp(3), dp(3), dp(3), dp(3)); isClickable = true; contentDescription = label; glassTouch(); setOnClickListener { click() }
            addView(ImageView(this@VideoEditorActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(thumb); clipToOutline = true; background = glass(11, 0xff151d33.toInt()) }, -1, -1)
            addView(text(label, 10.5f, Color.WHITE, true).apply { setShadowLayer(4f, 0f, 1f, 0xcc000000.toInt()); setPadding(dp(5), 0, dp(5), dp(3)) }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
        }
        row.addView(card(firstFrame, "1 · " + clock(end - start), playIndex == 0) { playClip(0, false) }, LinearLayout.LayoutParams(dp(76), dp(56)).apply { rightMargin = dp(6) })
        extras.forEachIndexed { i, e -> row.addView(card(e.thumb, "${i + 2} · " + clock(e.duration), playIndex == i + 1) { playClip(i + 1, false); showTool(10, false) }, LinearLayout.LayoutParams(dp(76), dp(56)).apply { rightMargin = dp(6) }) }
        row.addView(FrameLayout(this).apply { background = glass(14, 0x26ffffff); isClickable = true; contentDescription = "เพิ่มคลิป"; glassTouch(); setOnClickListener { pickClip.launch(arrayOf("video/*")) }
            addView(IconView(this@VideoEditorActivity, ADD, 2f), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)) }, LinearLayout.LayoutParams(dp(56), dp(56)))
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }, -1, -2)
        val chosen = playIndex - 1
        if (chosen in extras.indices) col.addView(EditorKit.actions(this, listOf(
            EditorKit.action(this, LEFT, "เลื่อนก่อน") { if (chosen > 0) { extras.add(chosen - 1, extras.removeAt(chosen)); playIndex = chosen } ; showTool(10, false) },
            EditorKit.action(this, RIGHT, "เลื่อนหลัง") { if (chosen < extras.size - 1) { extras.add(chosen + 1, extras.removeAt(chosen)); playIndex = chosen + 2 }; showTool(10, false) },
            EditorKit.action(this, Icons.CLOSE, "เอาออก") { extras.removeAt(chosen); playClip(0, false); showTime(); showTool(10, false) })), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        else col.addView(text(if (extras.isEmpty()) "เพิ่มคลิปเพื่อต่อกันเป็นวิดีโอเดียว" else "แตะคลิปที่เพิ่มเพื่อเลื่อนลำดับหรือเอาออก · ตัดและความเร็วใช้กับคลิปแรก", 11.5f, Glass.TEXT3).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(8), dp(16), 0) }, -1, -2)
    }
    private fun transitionTools(col: LinearLayout) {
        val icons = listOf(EditorKit.SCISSORS, "M4 12h16 M4 6h16 M4 18h16", "M12 3v4 M12 17v4 M3 12h4 M17 12h4 M6 6l2.5 2.5 M15.5 15.5L18 18 M18 6l-2.5 2.5 M8.5 15.5L6 18", "M10 4H4v6 M14 4h6v6 M10 20H4v-6 M14 20h6v-6")
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
    private fun totalOut() = ((end - start - cutMs()) / speed).toLong() + extras.sumOf { it.duration }
    /** Where the preview is, in ms of the finished video. */
    private fun outNow(): Long {
        val main = ((end - start - cutMs()) / speed).toLong()
        if (playIndex > 0) return main + extras.take(playIndex - 1).sumOf { it.duration } + (player?.currentPosition?.toLong() ?: 0L)
        var t = trim.playhead - start
        if (cutValid() && trim.playhead >= cutTo) t -= cutMs()
        return (t.coerceAtLeast(0) / speed).toLong()
    }
    private fun clampedLayers(): List<VideoLayer> { val total = totalOut()
        return layers.mapNotNull { l -> val at = l.at.coerceIn(0, (total - 200).coerceAtLeast(0)); val len = l.length.coerceAtMost(total - at); if (len < 200) null else l.copy(at = at, length = len) } }
    private fun layerName(l: VideoLayer) = when (l.kind) { VideoLayer.VIDEO -> "วิดีโอ"; VideoLayer.IMAGE -> "รูป"; else -> "เสียง" } + " · " + clock(l.length)

    private fun addLayers(uris: List<Uri>, kind: Int) {
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
                val total = totalOut(); val now = outNow()
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
            need.forEach { l -> layerThumb(l.source, l.kind)?.let { layerThumbs[l.source] = it } }
            runOnUiThread { if (!isDestroyed) rebuildLayers() }
        }
    }
    /** One picture per visual layer on the preview: drag it to move, tap it to select it. */
    private fun rebuildLayers() {
        releaseLayerSound()
        layerViews.forEach { stage.removeView(it) }; layerViews.clear()
        layers.forEachIndexed { i, l ->
            if (!l.visual) return@forEachIndexed
            val v = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(layerThumbs[l.source]); setBackgroundColor(0x33ffffff); tag = i
                contentDescription = "เลเยอร์${layerName(l)} ลากเพื่อย้าย"; clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(view: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, view.width, view.height, dp(4).toFloat()) } }
            var dx = 0f; var dy = 0f; var moved = false
            v.setOnTouchListener { view, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; moved = false; view.parent?.requestDisallowInterceptTouchEvent(true) }
                    MotionEvent.ACTION_MOVE -> {
                        if (abs(e.rawX - dx) + abs(e.rawY - dy) > dp(6)) moved = true
                        if (moved && visible.width() > 0) {
                            val k = layers[i]
                            layers[i] = k.copy(x = (k.x + (e.rawX - dx) / visible.width()).coerceIn(0f, 1f), y = (k.y + (e.rawY - dy) / visible.height()).coerceIn(0f, 1f))
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
            val w = (l.size * visible.width()).toInt().coerceAtLeast(dp(12)); val h = (w / l.aspect).toInt().coerceAtLeast(dp(12))
            (v.layoutParams as FrameLayout.LayoutParams).let { if (it.width != w || it.height != h) { it.width = w; it.height = h; v.layoutParams = it } }
            v.translationX = visible.left + l.x * visible.width() - w / 2f; v.translationY = visible.top + l.y * visible.height() - h / 2f
            v.alpha = when { now in l.at until l.end -> 1f; tool == LAYER_TOOL && i == layerPick -> 0.4f; else -> 0f }
            v.isEnabled = v.alpha > 0f
        }
    }
    /** Plays layer sounds along with the preview. */
    private fun syncLayerSound(playing: Boolean) {
        if (layers.none { it.audible }) return
        val now = outNow()
        layers.forEachIndexed { i, l ->
            if (!l.audible) return@forEachIndexed
            val mp = layerSound[i]
            if (playing && now in l.at until l.end) {
                val p = mp ?: runCatching { MediaPlayer().apply { setDataSource(this@VideoEditorActivity, l.source); prepare() } }.getOrNull()?.also { layerSound[i] = it } ?: return@forEachIndexed
                if (!p.isPlaying) runCatching { val v = l.volume.coerceAtMost(1f); p.setVolume(v, v); p.seekTo((l.from + now - l.at).toInt()); p.start() }
            } else if (mp?.isPlaying == true) mp.pause()
        }
    }
    private fun releaseLayerSound() { layerSound.values.forEach { it.runCatching { release() } }; layerSound.clear() }

    /** Add video, picture and sound layers (video and pictures from Lumora's own gallery), then edit the chosen one. */
    private fun layerTools(col: LinearLayout) {
        val add = LinearLayout(this).apply { gravity = Gravity.CENTER }
        fun chip(label: String, icon: String, f: () -> Unit) = add.addView(EditorKit.chip(this, label, icon) { f() }, LinearLayout.LayoutParams(-2, dp(36)).apply { setMargins(dp(4), 0, dp(4), 0) })
        chip("วิดีโอ", Icons.VIDEO) { pendingKind = VideoLayer.VIDEO; pickFromGallery.launch(Intent(this, GalleryActivity::class.java).putExtra(GalleryActivity.EXTRA_PICK, "video")) }
        chip("รูป", ADD) { pendingKind = VideoLayer.IMAGE; pickFromGallery.launch(Intent(this, GalleryActivity::class.java).putExtra(GalleryActivity.EXTRA_PICK, "image")) }
        chip("เพลง / เสียง", EditorKit.MUSIC) { pickSound.launch(arrayOf("audio/*")) }
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(add) }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(6) })
        if (layers.isEmpty()) {
            col.addView(text("ซ้อนวิดีโอ รูป หรือเสียงบนคลิป\nวิดีโอและรูปเลือกจากแกลเลอรีของ Lumora", 12f, Glass.TEXT3).apply { gravity = Gravity.CENTER }, -1, -2)
            return
        }
        if (layerPick !in layers.indices) layerPick = layers.size - 1
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val lane = LayerLanes(this).apply {
            layers = this@VideoEditorActivity.layers.toList(); total = totalOut(); playhead = outNow(); selected = layerPick; names = { layerName(it) }
            setPadding(dp(12), 0, dp(12), 0)
            onSelect = { k -> layerPick = k; placeLayers(); layerControls(controls) }
            onMove = { k, at -> this@VideoEditorActivity.layers[k] = this@VideoEditorActivity.layers[k].copy(at = at); layers = this@VideoEditorActivity.layers.toList(); placeLayers() }
        }
        lanes = lane
        col.addView(android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(lane) }, LinearLayout.LayoutParams(-1, dp(minOf(3, layers.size) * 30 + 4)))
        col.addView(controls, -1, -2)
        layerControls(controls)
    }
    private fun layerControls(box: LinearLayout) {
        box.removeAllViews()
        val i = layerPick; val l = layers.getOrNull(i) ?: return
        fun refresh() { lanes?.let { it.layers = layers.toList(); it.total = totalOut() }; placeLayers() }
        box.addView(EditorKit.actionRow(this, listOf(
            EditorKit.action(this, EditorKit.START, "เริ่มที่นี่") { val now = outNow(); val total = totalOut()
                layers[i] = layers[i].copy(at = now.coerceAtMost((total - 200).coerceAtLeast(0)), length = layers[i].length.coerceAtMost(total - now).coerceAtLeast(200)); refresh() },
            EditorKit.action(this, EditorKit.END, "จบที่นี่") { val now = outNow()
                if (now - layers[i].at < 300) notice("เลื่อนไปหลังจุดเริ่มของเลเยอร์ก่อน") else { layers[i] = layers[i].copy(length = now - layers[i].at); refresh() } },
            EditorKit.action(this, "M5 12h14 M15 8l4 4-4 4 M9 8l-4 4 4 4", "ยาวสุด") { val total = totalOut(); val cap = if (l.kind == VideoLayer.IMAGE) total else VideoLayers.duration(this, l.source) - l.from
                layers[i] = layers[i].copy(length = cap.coerceAtMost(total - layers[i].at).coerceAtLeast(200)); refresh() },
            EditorKit.action(this, "M5 7h14 M9 7V4h6v3 M7 7l1 13h8l1-13", "ลบ") { layers.removeAt(i); layerPick = (i - 1).coerceAtLeast(if (layers.isEmpty()) -1 else 0); rebuildLayers(); showTool(LAYER_TOOL, false) })), -1, -2)
        fun slider(name: String, steps: List<Int>, now: Int, unit: String, set: (Int) -> Unit) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(10), 0) }
            val label = text("$name $now$unit", 12f, Glass.TEXT2, true).apply { minWidth = dp(96) }
            row.addView(label)
            row.addView(GlassSlider(this).apply { values = steps.map { "$it$unit" }; index = steps.indexOfFirst { it >= now }.coerceAtLeast(0); contentDescription = name
                onChange = { k -> label.text = "$name ${steps[k]}$unit"; set(steps[k]) } }, LinearLayout.LayoutParams(0, dp(40), 1f))
            box.addView(row, -1, -2)
        }
        if (l.visual) slider("ขนาด", (10..100 step 5).toList(), (l.size * 100).toInt(), "%") { v -> layers[i] = layers[i].copy(size = v / 100f); placeLayers() }
        if (l.audible) slider("เสียง", (0..200 step 10).toList(), (l.volume * 100).toInt(), "%") { v -> layers[i] = layers[i].copy(volume = v / 100f); layerSound[i]?.runCatching { val x = (v / 100f).coerceAtMost(1f); setVolume(x, x) } }
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
        trim.start = start; trim.end = end; trim.split = split
        prepareMusic(); applySound(); applyLook(); layoutVideo(); rebuildStickers(); rebuildLayers(); loadLayerThumbs()
        val uris = b.getStringArrayList("extras") ?: arrayListOf()
        fun done() { if (playIndex > extras.size) playClip(0, false); showTool(tool, false); showTime(); committed = state(); committedKey = StudioProjects.key(committed!!); pendingKey = committedKey; applying = false }
        if (uris.all { it in extraCache }) { extras.clear(); extras += uris.map { extraCache[it]!! }; done() } else restoreExtras(b) { done() }
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
        extras.map { VideoPart(it.uri, 0, it.duration) }, if (extras.isEmpty()) 0 else transition, stickers.toList(), clampedLayers(), shrinkShort, shrinkFps), applicationContext)
    }

    /** Export problems stay on screen until dismissed, so the reason can be read (and reported). */
    private fun failed(reason: String) { if (!isFinishing) GlassDialog.show(this, "ส่งออกไม่ได้", "$reason\nวิดีโอต้นฉบับยังอยู่ครบ ลองปิดฟิลเตอร์หรือข้อความแล้วส่งออกอีกครั้ง") }
    private fun hideProgress() { flow = null; prog?.remove(); prog = null; overlay = null; exportButton.alpha = 1f; ExportJob.unlisten(jobListener) }
    private fun cancelExport() { ExportJob.cancel() }

    // ---------- Lifecycle ----------
    override fun onPause() { player?.let { if (it.isPlaying) togglePlay() }; if (prog != null) th.ogay.luma.ai.AiPace.watching = false; super.onPause() }
    override fun onResume() { super.onResume(); if (prog != null) th.ogay.luma.ai.AiPace.watching = true }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); releasePlayer(); musicPlayer?.runCatching { release() }; releaseLayerSound(); super.onDestroy() }
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
        out.putFloatArray("adjust", adjust); out.putInt("turns", turns); out.putBoolean("mirror", mirror); out.putFloat("volume", volume); out.putFloat("musicVolume", musicVolume)
        out.putLong("cutFrom", cutFrom); out.putLong("cutTo", cutTo); out.putBoolean("textBox", textBox)
        out.putStringArrayList("extras", ArrayList(extras.map { it.uri.toString() })); out.putInt("transition", transition)
        out.putStringArrayList("stickers", ArrayList(stickers.map { "${it.x}|${it.y}|${it.big}|${it.text}" }))
        out.putString("layers", VideoLayer.save(layers)); out.putInt("layerPick", layerPick); out.putBoolean("smooth", smooth)
    }
    private fun restore(s: Bundle) {
        start = s.getLong("start"); end = s.getLong("end", duration).coerceIn(start, duration); split = s.getLong("split", -1); speed = s.getFloat("speed", 1f); cropIndex = s.getInt("crop")
        caption = s.getString("caption") ?: ""; place = s.getInt("place", 2); textColor = s.getInt("textColor", Color.WHITE); textBig = s.getBoolean("big")
        film = Films.byId(s.getString("film")); filmStrength = s.getFloat("strength", 1f); music = s.getString("music")?.let { Uri.parse(it) }; musicName = s.getString("musicName") ?: ""
        keepSound = s.getBoolean("sound", true); tool = s.getInt("tool")
        s.getFloatArray("adjust")?.takeIf { it.size == adjust.size }?.copyInto(adjust); turns = s.getInt("turns"); mirror = s.getBoolean("mirror"); volume = s.getFloat("volume", 1f); musicVolume = s.getFloat("musicVolume", 1f)
        cutFrom = s.getLong("cutFrom", -1L); cutTo = s.getLong("cutTo", -1L); textBox = s.getBoolean("textBox")
        transition = s.getInt("transition"); stickers.clear()
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
