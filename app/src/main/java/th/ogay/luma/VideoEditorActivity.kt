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

/**
 * Video editor: trim, split, speed, crop, text, film look and music. The preview plays the source with the look,
 * crop frame and text drawn over it; "ส่งออก" writes new clips (two when split) with media3 Transformer and the
 * original stays untouched.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class VideoEditorActivity : ComponentActivity() {
    companion object {
        fun intent(c: Context, uri: Uri): Intent = Intent(c, VideoEditorActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        private val SPEEDS = listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 4f)
        private val CROPS = listOf("ต้นฉบับ" to 0f, "1:1" to 1f, "4:5" to 4f / 5, "16:9" to 16f / 9, "9:16" to 9f / 16, "2.39:1" to 2.39f)
        private val PLACES = listOf("บน", "กลาง", "ล่าง")
        private val TEXT_COLORS = intArrayOf(Color.WHITE, Color.BLACK, Glass.ACCENT, 0xffffd60a.toInt(), 0xff0a84ff.toInt())
    }

    // ---- Source ----
    private lateinit var source: Uri
    private var duration = 0L; private var videoW = 16; private var videoH = 9; private var hasAudio = true

    // ---- Edit state ----
    private var start = 0L; private var end = 0L; private var split = -1L
    private var speed = 1f; private var cropIndex = 0
    private var caption = ""; private var place = 2; private var textColor = Color.WHITE; private var textBig = false
    private var film: Film? = null; private var filmStrength = 1f
    private var music: Uri? = null; private var musicName = ""; private var keepSound = true

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

    // ---- Playback ----
    private var player: MediaPlayer? = null; private var musicPlayer: MediaPlayer? = null
    private var surface: Surface? = null; private var prepared = false
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() {
        val p = player ?: return
        if (prepared && p.isPlaying) {
            val pos = p.currentPosition.toLong()
            if (pos >= end - 30) { p.seekTo(start.toInt()); musicPlayer?.seekTo(0) }
            trim.playhead = pos.coerceIn(start, end); showTime()
        }
        handler.postDelayed(this, 33)
    } }

    // ---- Export ----
    private var transformer: Transformer? = null
    private val progress = ProgressHolder()
    private var overlay: View? = null; private var overlayText: TextView? = null

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
        if (!readSource()) { notice("เปิดวิดีโอนี้ไม่ได้"); finish(); return }
        end = duration
        state?.let { restore(it) }
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
        exportButton = EditorKit.primary(this, "ส่งออก") { export() }
        bar.addView(exportButton, LinearLayout.LayoutParams(-2, dp(48)))
        root.addView(bar, -1, -2)

        stage = FrameLayout(this).apply { clipChildren = true }
        texture = TextureView(this)
        stage.addView(texture, FrameLayout.LayoutParams(0, 0, Gravity.CENTER))
        mask = CropMask(this); stage.addView(mask, -1, -1)
        captionView = text("", 20f, Color.WHITE, true).apply { gravity = Gravity.CENTER; maxLines = 3; setShadowLayer(6f, 0f, 2f, 0x99000000.toInt()); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        stage.addView(captionView, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
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
        toolBar = EditorKit.ToolBar(this, listOf(EditorKit.SCISSORS to "ตัด", EditorKit.SPLIT to "แยก", EditorKit.SPEED to "ความเร็ว", EditorKit.CROP to "ครอบ", EditorKit.TEXT to "ข้อความ", Icons.FILM to "ฟิลเตอร์", EditorKit.MUSIC to "เพลง")) { showTool(it) }
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
        player = MediaPlayer().apply {
            runCatching {
                setDataSource(this@VideoEditorActivity, source); setSurface(s); isLooping = false
                setOnPreparedListener { prepared = true; seekTo(this@VideoEditorActivity.start.toInt()); applySound(); layoutVideo() }
                setOnCompletionListener { seekTo(this@VideoEditorActivity.start.toInt()) }
                prepareAsync()
            }.onFailure { notice("เล่นพรีวิวไม่ได้ · ${it.localizedMessage}") }
        }
        handler.removeCallbacks(tick); handler.post(tick)
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
    private fun applySound() { val v = if (keepSound) 1f else 0f; player?.runCatching { setVolume(v, v) } }
    private fun togglePlay() {
        val p = player ?: return; if (!prepared) return
        if (p.isPlaying) { p.pause(); musicPlayer?.pause(); playIcon.path = "M9 6.5v11l9-5.5z" }
        else {
            if (p.currentPosition >= end - 60) p.seekTo(start.toInt())
            runCatching { p.playbackParams = p.playbackParams.setSpeed(speed) }
            p.start(); musicPlayer?.let { mp -> runCatching { mp.seekTo(((p.currentPosition - start).coerceAtLeast(0) % max(1, mp.duration)).toInt()); mp.start() } }
            playIcon.path = "M8 6v12 M16 6v12"
        }
    }
    private fun seek(ms: Long) {
        trim.playhead = ms; val p = player ?: return
        if (prepared) { if (android.os.Build.VERSION.SDK_INT >= 26) p.seekTo(ms, MediaPlayer.SEEK_CLOSEST) else p.seekTo(ms.toInt()) }
        showTime()
    }
    private fun clock(ms: Long) = String.format(Locale.US, "%d:%02d.%d", ms / 60000, ms / 1000 % 60, ms / 100 % 10)
    private fun showTime() {
        val length = ((end - start) / speed).toLong()
        timeLabel.text = "${clock(trim.playhead - start)} / ${clock(end - start)}" + (if (speed != 1f) "  ·  ได้คลิป ${clock(length)}" else "") + (if (split >= 0) "  ·  แยกเป็น 2 คลิป" else "")
    }

    /** Fits the video in the stage and places the crop frame and caption over it. */
    private fun layoutVideo() {
        if (stage.width == 0) return
        val pad = dp(8)
        val aw = stage.width - 2 * pad; val ah = stage.height - 2 * pad
        val k = min(aw.toFloat() / videoW, ah.toFloat() / videoH)
        val w = (videoW * k).toInt(); val h = (videoH * k).toInt()
        (texture.layoutParams as FrameLayout.LayoutParams).let { if (it.width != w || it.height != h) { it.width = w; it.height = h; texture.layoutParams = it } }
        val video = RectF((stage.width - w) / 2f, (stage.height - h) / 2f, (stage.width + w) / 2f, (stage.height + h) / 2f)
        val visible = cropRect(video); mask.set(video, visible)
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
    private fun colour(): ColorMatrix = EditorKit.film(film, filmStrength)
    private fun applyLook() {
        texture.setLayerPaint(if (film == null) null else Paint().apply { colorFilter = ColorMatrixColorFilter(colour()) })
        captionView.text = caption; captionView.setTextColor(textColor); captionView.visibility = if (caption.isBlank()) View.GONE else View.VISIBLE
        layoutVideo(); if (::timeLabel.isInitialized) showTime()
    }

    // ---------- Tools ----------
    private fun showTool(i: Int, animate: Boolean = true) {
        val dir = if (animate) i.compareTo(tool) else 0
        tool = i; toolBar.select(i, animate)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        fun note(s: String) = col.addView(text(s, 12f, Glass.TEXT2, true).apply { maxLines = 1; gravity = Gravity.CENTER; setPadding(dp(16), 0, dp(16), dp(6)) }, -1, -2)
        when (i) {
            0 -> col.addView(EditorKit.actions(this, listOf(
                    EditorKit.action(this, EditorKit.START, "เริ่มที่นี่") { setRange(trim.playhead, end) },
                    EditorKit.action(this, EditorKit.END, "จบที่นี่") { setRange(start, trim.playhead) },
                    EditorKit.action(this, Icons.RESET, "ทั้งคลิป") { setRange(0, duration) })), -1, -2)
            1 -> { if (split >= 0) note("2 คลิป · แยกที่ ${clock(split - start)}")
                col.addView(EditorKit.actions(this, listOf(
                    EditorKit.action(this, EditorKit.SPLIT, "แยกที่นี่", split >= 0) {
                        val at = trim.playhead; if (at - start < 300 || end - at < 300) notice("เลื่อนให้ห่างจากขอบช่วงอย่างน้อย 0.3 วินาที") else { split = at; trim.split = split; showTime(); showTool(1, false) } },
                    EditorKit.action(this, Icons.CLOSE, "ไม่แยก") { split = -1; trim.split = -1; showTime(); showTool(1, false) })), -1, -2) }
            2 -> { val items = SPEEDS.map { EditorKit.StripItem(this, "", speedName(it), 15f) }
                // Sliding through the speeds plays the preview at each one.
                col.addView(EditorKit.strip(this, items.map { it to 62 }, SPEEDS.indexOf(speed).coerceAtLeast(0), 46, { k -> items.forEachIndexed { j, v -> v.lit(j == k) }
                    player?.let { p -> if (p.isPlaying) runCatching { p.playbackParams = p.playbackParams.setSpeed(SPEEDS[k]) } } }) { k -> speed = SPEEDS[k]; showTime() },
                    LinearLayout.LayoutParams(-1, dp(64))) }
            3 -> { val items = CROPS.map { (name, _) -> EditorKit.StripItem(this, if (name == "ต้นฉบับ") Icons.VIDEO else aspectIconPath(name), name) }
                col.addView(EditorKit.strip(this, items.map { it to 64 }, cropIndex, 60, { k -> items.forEachIndexed { j, v -> v.lit(j == k) } }) { k -> cropIndex = k; layoutVideo() },
                    LinearLayout.LayoutParams(-1, dp(70))) }
            4 -> textTools(col)
            5 -> filmTools(col)
            else -> musicTools(col)
        }
        EditorKit.swap(tools, col, dir)
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

    private fun musicTools(col: LinearLayout) {
        if (music != null) col.addView(text(musicName, 12f, Glass.TEXT2, true).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setPadding(dp(16), 0, dp(16), dp(6)) }, -1, -2)
        val items = mutableListOf<View>(EditorKit.action(this, EditorKit.MUSIC, if (music == null) "เลือกเพลง" else "เปลี่ยนเพลง", music != null) { pickMusic.launch(arrayOf("audio/*")) })
        if (hasAudio) items += EditorKit.action(this, Icons.SOUND, "เสียงคลิป", keepSound) { keepSound = !keepSound; applySound(); showTool(6, false) }
        if (music != null) items += EditorKit.action(this, Icons.CLOSE, "ลบเพลง") { music = null; musicName = ""; musicPlayer?.runCatching { release() }; musicPlayer = null; showTool(6, false) }
        col.addView(EditorKit.actions(this, items), -1, -2)
    }

    private fun changed() = start != 0L || end != duration || split >= 0 || speed != 1f || cropIndex != 0 || caption.isNotBlank() || film != null || music != null || !keepSound
    private fun leave() {
        if (transformer != null) { cancelExport(); return }
        if (!changed()) { finish(); return }
        EditorKit.askSave(this, "บันทึกการตัดต่อไหม", "ส่งออกเป็นคลิปใหม่ วิดีโอต้นฉบับยังอยู่ครบ", "ส่งออก", { export() }, { finish() })
    }

    // ---------- Export ----------
    private fun export() {
        if (transformer != null) return
        if (!changed()) { notice("ยังไม่มีการแก้ไข"); return }
        if (filesDir.usableSpace < 200L * 1024 * 1024) { notice("พื้นที่ว่างไม่พอส่งออก"); return }
        player?.let { if (it.isPlaying) togglePlay() }
        val parts = if (split >= 0) listOf(start to split, split to end) else listOf(start to end)
        showProgress()
        exportPart(parts, 0, System.currentTimeMillis(), mutableListOf())
    }
    private fun exportPart(parts: List<Pair<Long, Long>>, index: Int, id: Long, done: MutableList<File>) {
        val dir = File(filesDir, "video-exports/$id").apply { mkdirs() }
        val file = File(dir, "Lumora_EDIT_${id}${if (parts.size > 1) "_${index + 1}" else ""}.mp4")
        val (from, to) = parts[index]
        try {
            val composition = composition(from, to)
            val t = VideoEdit.transformer(this, object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    done += file
                    if (index + 1 < parts.size) { exportPart(parts, index + 1, id, done); return }
                    transformer = null; overlayText?.text = "กำลังบันทึกไฟล์…"
                    luma.fileIo.execute {
                        val ok = runCatching { done.forEach { f -> MediaLedger.publishVideo(this@VideoEditorActivity, f, f.name, "ตัดต่อใน Lumora") } }
                        runOnUiThread {
                            hideProgress()
                            ok.onSuccess { notice(if (done.size > 1) "ส่งออก ${done.size} คลิปแล้ว · ต้นฉบับยังอยู่" else "ส่งออกคลิปใหม่แล้ว · ต้นฉบับยังอยู่"); setResult(RESULT_OK); finish() }
                                .onFailure { failed("บันทึกไฟล์ไม่ได้ · ${it.localizedMessage}") }
                        }
                    }
                }
                override fun onError(composition: Composition, result: ExportResult, e: ExportException) {
                    transformer = null; file.delete(); hideProgress(); failed("${e.errorCodeName} · ${e.cause?.message ?: e.localizedMessage}")
                }
            })
            transformer = t; t.start(composition, file.path)
            overlayText?.text = if (parts.size > 1) "กำลังส่งออกคลิป ${index + 1}/${parts.size}" else "กำลังส่งออก"
            handler.post(poll)
        } catch (e: Exception) { transformer = null; file.delete(); hideProgress(); failed("เริ่มไม่ได้ · ${e.localizedMessage}") }
    }
    private val poll = object : Runnable { override fun run() {
        val t = transformer ?: return
        if (t.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE) overlayText?.text = (overlayText?.text?.toString()?.substringBefore(" · ") ?: "กำลังส่งออก") + " · ${progress.progress}%"
        handler.postDelayed(this, 300)
    } }

    private fun composition(from: Long, to: Long): Composition = VideoEdit.composition(VideoEditSpec(source, from, to, videoW, videoH,
        speed, CROPS[cropIndex].second, caption, place, textColor, textBig, if (film != null) colour() else null, music, hasAudio && keepSound))

    /** Export problems stay on screen until dismissed, so the reason can be read (and reported). */
    private fun failed(reason: String) { if (!isFinishing) GlassDialog.show(this, "ส่งออกไม่ได้", "$reason\nวิดีโอต้นฉบับยังอยู่ครบ ลองปิดฟิลเตอร์หรือข้อความแล้วส่งออกอีกครั้ง") }
    private fun showProgress() {
        exportButton.alpha = 0.5f
        val layer = FrameLayout(this).apply { setBackgroundColor(0x99000000.toInt()); isClickable = true }
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = glass(24, Glass.FILL_POP); setPadding(dp(24), dp(22), dp(24), dp(18)) }
        card.addView(ProcessingSpinner(this), dp(40), dp(40))
        overlayText = text("กำลังส่งออก", 15f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(4)) }
        card.addView(overlayText, -2, -2)
        card.addView(text("เปิดหน้านี้ไว้จนเสร็จ", 12.5f, Glass.TEXT2), -2, -2)
        card.addView(EditorKit.chip(this, "ยกเลิก", Icons.CLOSE) { cancelExport() }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
        layer.addView(card, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        (window.decorView as FrameLayout).addView(layer, -1, -1); overlay = layer
    }
    private fun hideProgress() { handler.removeCallbacks(poll); overlay?.let { (it.parent as? FrameLayout)?.removeView(it) }; overlay = null; overlayText = null; exportButton.alpha = 1f }
    private fun cancelExport() { transformer?.cancel(); transformer = null; hideProgress(); notice("ยกเลิกการส่งออกแล้ว") }

    // ---------- Lifecycle ----------
    override fun onPause() { player?.let { if (it.isPlaying) togglePlay() }; super.onPause() }
    override fun onStop() { if (transformer != null) cancelExport(); super.onStop() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); releasePlayer(); musicPlayer?.runCatching { release() }; super.onDestroy() }
    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putLong("start", start); out.putLong("end", end); out.putLong("split", split); out.putFloat("speed", speed); out.putInt("crop", cropIndex)
        out.putString("caption", caption); out.putInt("place", place); out.putInt("textColor", textColor); out.putBoolean("big", textBig)
        out.putString("film", film?.id); out.putFloat("strength", filmStrength); out.putString("music", music?.toString()); out.putString("musicName", musicName)
        out.putBoolean("sound", keepSound); out.putInt("tool", tool)
    }
    private fun restore(s: Bundle) {
        start = s.getLong("start"); end = s.getLong("end", duration).coerceIn(start, duration); split = s.getLong("split", -1); speed = s.getFloat("speed", 1f); cropIndex = s.getInt("crop")
        caption = s.getString("caption") ?: ""; place = s.getInt("place", 2); textColor = s.getInt("textColor", Color.WHITE); textBig = s.getBoolean("big")
        film = Films.byId(s.getString("film")); filmStrength = s.getFloat("strength", 1f); music = s.getString("music")?.let { Uri.parse(it) }; musicName = s.getString("musicName") ?: ""
        keepSound = s.getBoolean("sound", true); tool = s.getInt("tool")
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
