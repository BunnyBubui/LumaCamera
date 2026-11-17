package th.ogay.luma

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * Home: the main parts of Lumora in one place, opened from the top-left button of the photo screen (the app
 * itself still opens straight into the camera). Photo modes return to the camera in that mode; the editors
 * open Lumora's gallery to choose what to edit.
 *
 * Layout is the "Home B · บล็อก (Bento)" board: blocks of different sizes (a tall camera block, gallery and film
 * beside it, a wide video-editor block, photo editor and AI), then the photo modes as short chips. Every colour
 * comes from the theme, so the page follows whatever the user picked in ธีมสี.
 */
@androidx.annotation.OptIn(markerClass = [androidx.camera.camera2.interop.ExperimentalCamera2Interop::class])
class HomeActivity : ComponentActivity() {
    companion object {
        /** Result extra: the photo mode the camera should switch to. */
        const val RESULT_MODE = "mode"
        /** Set when the camera opened Home on top of itself; unset when Home is the first screen of the app. */
        const val FROM_CAMERA = "from_camera"
        /** Set on the camera that Home started, so its house button returns to the Home beneath. */
        const val FROM_HOME = "from_home"
        const val HOUSE = "M3.5 11 12 4l8.5 7 M6 9.5V20h4.5v-5.5h3V20H18V9.5"
        const val BACK_SMALL = "M14.5 6.5 9 12l5.5 5.5"
    }

    private var editKind = "image"
    private val pick = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        @Suppress("DEPRECATION") val uri = r.data?.getParcelableArrayListExtra<Uri>(GalleryActivity.RESULT_URIS)?.firstOrNull() ?: return@registerForActivityResult
        when (editKind) {
            // Pick a photo, then the AI sheet (model, size, strength, preview) opens right here; the result lands in Lumora's gallery.
            "ai_image" -> th.ogay.luma.ai.EnhanceUi.showForUri(this, uri) { notice("เริ่มเพิ่มรายละเอียดแล้ว ดูความคืบหน้าในแกลเลอรี")
                startActivity(Intent(this, GalleryActivity::class.java).putExtra("tab", "luma").putExtra("from", "home")) }
            "ai_video" -> startActivity(VideoEditorActivity.intent(this, uri).putExtra("ai_export", true))
            "video" -> startActivity(Intent(this, VideoEditorActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            else -> startActivity(Intent(this, PhotoEditorActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }

    private var hostRef: FrameLayout? = null
    /** The bars shut when Home hands over to the camera; they open again when the person comes back. */
    private var shutter: Curtain? = null
    override fun onRestart() {
        super.onRestart()
        shutter?.let { it.open(320); shutter = null }
    }
    private var aiOverlay: View? = null
    private val cards = ArrayList<View>()
    private val loader = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("camera", MODE_PRIVATE) }

    @android.annotation.SuppressLint("NewApi")
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        // Follows the phone's auto-rotate setting; on its side the page keeps a phone's width in the middle.
        val host = FrameLayout(this); hostRef = host
        installRoot(host)
        host.setBackgroundColor(Glass.BG)
        // One still, faint light in the theme colour at the top right (the old moving blue/purple lights are gone).
        host.addView(Glow(this), -1, -1)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(32)) }
        // The page scrolls under the status and navigation bars instead of being cut off at a hard line below them;
        // a soft shade under the status bar keeps the clock readable over the blocks.
        val scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; clipToPadding = false; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            // On a tablet, an unfolded phone or a phone on its side the page keeps a phone's width in the middle instead of stretching.
            val wide = resources.displayMetrics.widthPixels > dp(640)
            addView(col, FrameLayout.LayoutParams(if (wide) dp(560) else -1, -2, Gravity.CENTER_HORIZONTAL)) }
        host.addView(scroll, -1, -1)
        if (state == null && !intent.getBooleanExtra(FROM_CAMERA, false) && !Glass.reduced(this)) {
            col.alpha = 0f
            Cine.intro(this) {
                col.alpha = 1f
                for (i in 0 until col.childCount) Cine.rise(col.getChildAt(i), 120L + i * 55L, dp(14).toFloat(), 480)
            }
        }
        val shade = View(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Glass.alpha(Glass.BG, 0xf2), Glass.alpha(Glass.BG, 0xa0), Glass.alpha(Glass.BG, 0))) }
        host.addView(shade, FrameLayout.LayoutParams(-1, dp(40)))
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(host) { _, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            host.setPadding(0, 0, 0, 0)
            scroll.setPadding(bars.left, 0, bars.right, bars.bottom)
            topInset = bars.top; heroHeader?.setPadding(dp(16), bars.top + dp(10), dp(12), 0)
            shade.visibility = View.GONE
            insets
        }

        // "Home 2": the video editor fills the top of the page edge to edge (the latest project, a timeline and one big button),
        // with the camera, gallery, film and photo editor as four tiles beneath.
        col.setPadding(0, 0, 0, dp(14))
        val logo = LumoraLogoView(this).apply { progress = if (Glass.reduced(this@HomeActivity)) 1f else 0f }
        val title = text("Lumora", 22f, Color.WHITE, true).apply { setPadding(dp(10), 0, 0, 0) }
        fun pickFor(kind: String, ek: String) { editKind = ek; pick.launch(Intent(this, GalleryActivity::class.java).putExtra(GalleryActivity.EXTRA_PICK, kind)) }
        fun chooseAi() = showAiChooser { video -> if (video) pickFor("video", "ai_video") else pickFor("image", "ai_image") }

        val hero = studioHero(logo, title)
        col.addView(hero, LinearLayout.LayoutParams(-1, 0, 2.7f))
        projectRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(12), dp(16), dp(4)) }
        col.addView(projectRow, LinearLayout.LayoutParams(-1, -2))
        showProject(null, 0)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(6), dp(12), 0); minimumHeight = dp(190) }
        val rowA = LinearLayout(this); val rowB = LinearLayout(this)
        rowA.addView(tile(Icons.PHOTO, "ถ่ายภาพ", "กล้อง 9 โหมด", "ถ่ายภาพ กลับไปที่กล้อง", null) { returnMode(PhotoMode.PHOTO) }, LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = dp(4) })
        rowA.addView(tile(Icons.LIBRARY, "แกลเลอรี", "รูปและวิดีโอ", "แกลเลอรี รูปและวิดีโอทั้งหมด", null) { startActivity(Intent(this, GalleryActivity::class.java).putExtra("from", "home")) }, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(4) })
        rowB.addView(tile(Icons.FILM, "ฟิล์ม", "ลุคฟิล์มและเกรน", "ฟิล์ม โทนสีและเกรนฟิล์ม", null) { returnMode(PhotoMode.FILM) }, LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = dp(4) })
        rowB.addView(tile(EditorKit.ADJUST, "ปรับแต่งด้วย AI", "ภาพหรือวิดีโอ", "ปรับแต่งภาพหรือวิดีโอด้วย AI", "AI" to { chooseAi() }) { chooseAi() }, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(4) })
        grid.addView(rowA, LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(4) })
        grid.addView(rowB, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(4) })
        col.addView(grid, LinearLayout.LayoutParams(-1, 0, 2f))
        loader.execute {
            val list = runCatching { StudioProjects.list(this) }.getOrDefault(emptyList())
            val shown = list.filter { !it.missing }.take(if (Device.low(this)) 3 else 6)
            runOnUiThread { if (!isDestroyed) { projects = shown; projectCount = list.size; showProject(shown.firstOrNull(), list.size); heroPager?.let { it.count = shown.size } } }
            // A sharp frame from the video itself (the saved thumbnail is only a timeline tile); the project on show first.
            shown.forEachIndexed { i, info ->
                val bmp = sharpFrame(info) ?: runCatching { android.graphics.BitmapFactory.decodeFile(info.thumb.path) }.getOrNull()
                runOnUiThread { if (!isDestroyed && bmp != null) heroPager?.setPicture(i, bmp) }
            }
        }

        // Entrance: the mark draws, the name settles, the blocks rise one after another.
        if (!Glass.reduced(this)) {
            ValueAnimator.ofFloat(0f, 1f).apply { duration = 1100; startDelay = 100; addUpdateListener { logo.progress = it.animatedValue as Float } }.start()
            title.alpha = 0f; title.translationX = -dp(12).toFloat(); title.animate().alpha(1f).translationX(0f).setStartDelay(500).setDuration(500).setInterpolator(Glass.EASE).start()
            // Cards grow from 0.9 and settle without ever leaving the screen; on Android 12+ they also lose a soft blur as they land.
            cards.forEachIndexed { i, v -> v.alpha = 0f; v.scaleX = 0.9f; v.scaleY = 0.9f
                val blur = Build.VERSION.SDK_INT >= 31
                if (blur) v.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(18f, 18f, Shader.TileMode.CLAMP))
                ValueAnimator.ofFloat(0f, 1f).apply { startDelay = 120L + i * 40L; duration = 620; interpolator = Glass.EASE
                    addUpdateListener { a -> val f = a.animatedValue as Float; v.alpha = f; val sc = 0.9f + 0.1f * f; v.scaleX = sc; v.scaleY = sc
                        if (blur) { val b = 18f * (1f - f); v.setRenderEffect(if (b < 0.5f) null else android.graphics.RenderEffect.createBlurEffect(b, b, Shader.TileMode.CLAMP)) } } }.start() }
        }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) { override fun handleOnBackPressed() { if (aiOverlay != null) hideAiChooser() else close() } })
    }

    override fun onDestroy() { loader.shutdownNow(); super.onDestroy() }

    private fun close() { finish(); @Suppress("DEPRECATION") overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out) }
    private fun returnMode(m: PhotoMode) {
        if (intent.getBooleanExtra(FROM_CAMERA, false)) { setResult(RESULT_OK, Intent().putExtra(RESULT_MODE, m.name)); close(); return }
        // First screen of the app: open the camera in that mode, with Home left beneath it.
        getSharedPreferences("camera", MODE_PRIVATE).edit().putString("pending_mode", m.name).apply()
        Glass.tick(window.decorView, android.view.HapticFeedbackConstants.CLOCK_TICK)
        val go = { startActivity(Intent(this, CameraActivity::class.java).putExtra(FROM_HOME, true)); @Suppress("DEPRECATION") overridePendingTransition(0, 0) }
        if (Glass.reduced(this)) go() else shutter = Cine.leaveHome(this, go)
    }

    // ---------- Pieces ----------
    /** A tool block: dark base with a soft light in the tool's colour, as on the mode cards. [fill] adds what is drawn on it. */
    private fun toolCard(tint: Int, title: String, desc: String, open: () -> Unit, fill: FrameLayout.() -> Unit): FrameLayout = FrameLayout(this).apply {
        background = ModeBackdrop(this@HomeActivity, tint, Motif.NONE, 24); clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(24).toFloat()) }
        isClickable = true; contentDescription = desc; glassTouch()
        fill()
        if (title.isNotEmpty()) addView(text(title, 15f, Glass.TEXT, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
            FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(14); rightMargin = dp(10); bottomMargin = dp(12) })
        setOnClickListener { Glass.tick(this); open() }
        cards += this
    }

    /** The 36dp icon square: a dark glass tile with the tool's colour on the icon. */
    private fun iconChip(icon: String, tint: Int) = FrameLayout(this).apply {
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0x4d000000); setStroke(dp(1), 0x1affffff) }
        addView(IconView(this@HomeActivity, icon, 2f).apply { this.tint = tint }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
    }

    private fun FrameLayout.putChip(icon: String, tint: Int, gravity: Int, left: Int = 12, top: Int = 12) =
        addView(iconChip(icon, tint), FrameLayout.LayoutParams(dp(36), dp(36), gravity).apply { leftMargin = dp(left); topMargin = dp(top); rightMargin = dp(left) })

    private fun roundButton(icon: String, desc: String, click: () -> Unit) = FrameLayout(this).apply {
        isClickable = true; contentDescription = desc
        val face = FrameLayout(this@HomeActivity).apply { background = glass(22, 0x2effffff) }
        face.addView(IconView(this@HomeActivity, icon, 2f).apply { tint = Glass.TEXT }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        addView(face, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER))
        glassTouch(); setOnClickListener { Glass.tick(this); click() }
    }

    /** The camera block: same dark card as the others, a little stronger: bigger glow, larger icon, thin outline in the theme colour. */
    /** The neutral tint of every card except the video editor: a soft grey taken from the text colour, so the theme colour stays for the one thing that matters. */
    private fun quiet(): Int = Theme.mix(Glass.TEXT2, Glass.BG, 0.45f)

    private fun hero(): View = FrameLayout(this).apply {
        val acc = quiet()
        background = android.graphics.drawable.LayerDrawable(arrayOf(ModeBackdrop(this@HomeActivity, acc, Motif.HALO, 24),
            GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(0); setStroke(dp(1), Glass.alpha(acc, 110)) }))
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(24).toFloat()) }
        isClickable = true; contentDescription = "ถ่ายภาพ กลับไปที่กล้อง"; glassTouch()
        addView(IconView(this@HomeActivity, Icons.PHOTO, 1.2f).apply { tint = Glass.alpha(acc, 0x30) }, FrameLayout.LayoutParams(dp(120), dp(120), Gravity.TOP or Gravity.END).apply { topMargin = dp(18); rightMargin = dp(-14) })
        addView(FrameLayout(this@HomeActivity).apply {
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Glass.alpha(acc, 60)); setStroke(dp(1), Glass.alpha(acc, 140)) }
            addView(IconView(this@HomeActivity, Icons.PHOTO, 2f).apply { tint = acc }, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER)) }, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.TOP or Gravity.START).apply { topMargin = dp(16); leftMargin = dp(16) })
        val words = LinearLayout(this@HomeActivity).apply { orientation = LinearLayout.VERTICAL }
        words.addView(text("ถ่ายภาพ", 22f, Glass.TEXT, true))
        words.addView(text("กลับไปที่กล้อง", 12.5f, Glass.TEXT2).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        addView(words, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(16); rightMargin = dp(12); bottomMargin = dp(16) })
        setOnClickListener { Glass.tick(this); returnMode(PhotoMode.PHOTO) }
        cards += this
    }

    /** Gallery: the newest photos fill the whole card as a 2 × 2 mosaic (grey tiles until they load, or without permission). */
    private fun galleryBlock(): View {
        val shades = intArrayOf(0xff5f5f66.toInt(), 0xff6b5a46.toInt(), 0xff55604a.toInt(), 0xff6b4f46.toInt())
        val tiles = (0 until 4).map { i -> ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(shades[i], Theme.mix(shades[i], 0xff000000.toInt(), 0.6f)))
        } }
        return toolCard(quiet(), "แกลเลอรี", "แกลเลอรี รูปและวิดีโอทั้งหมด", {
            startActivity(Intent(this, GalleryActivity::class.java).putExtra("from", "home")) }) {
            val mosaic = LinearLayout(this@HomeActivity).apply { orientation = LinearLayout.VERTICAL }
            for (r in 0 until 2) {
                val line = LinearLayout(this@HomeActivity)
                line.addView(tiles[r * 2], LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = dp(1) })
                line.addView(tiles[r * 2 + 1], LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(1) })
                mosaic.addView(line, LinearLayout.LayoutParams(-1, 0, 1f).apply { if (r == 0) bottomMargin = dp(1) })
            }
            addView(mosaic, -1, -1)
            addView(View(this@HomeActivity).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x0d000000, 0xb8000000.toInt())) }, -1, -1)
            putChip(Icons.LIBRARY, Glass.TEXT, Gravity.TOP or Gravity.END, 10, 8)
            loadRecent(tiles)
        }
    }

    private fun mediaGranted(): Boolean {
        val perms = when { Build.VERSION.SDK_INT >= 34 -> arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES)
            else -> arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE) }
        return perms.any { ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
    }

    /** Small thumbnails only (Android 10+ gives them ready-made); nothing is asked for here, the gallery asks. */
    private fun loadRecent(into: List<ImageView>) {
        if (Build.VERSION.SDK_INT < 29 || !mediaGranted()) return
        val size = android.util.Size(dp(72), dp(60))
        loader.execute {
            val uris = ArrayList<Uri>()
            runCatching {
                contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID), null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                    while (c.moveToNext() && uris.size < into.size) uris += android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                }
            }
            uris.forEachIndexed { i, u ->
                val bmp: Bitmap = runCatching { contentResolver.loadThumbnail(u, size, null) }.getOrNull() ?: return@forEachIndexed
                runOnUiThread { if (!isDestroyed) into[i].setImageBitmap(bmp) }
            }
        }
    }

    /** Film: amber light, perforation holes down both edges and four dots for the looks. */
    private fun filmBlock(): View = toolCard(quiet(), "ฟิล์ม", "ฟิล์ม โทนสีและเกรนฟิล์ม", { returnMode(PhotoMode.FILM) }) {
        addView(Perforations(this@HomeActivity), FrameLayout.LayoutParams(-1, -1))
        val dots = LinearLayout(this@HomeActivity).apply { gravity = Gravity.CENTER_VERTICAL }
        listOf(0xffb8893a, 0xff5a6f8a, 0xffa8707a, 0xff707070).forEachIndexed { i, col ->
            dots.addView(View(this@HomeActivity).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(col.toInt()) } },
                LinearLayout.LayoutParams(dp(16), dp(16)).apply { if (i > 0) leftMargin = dp(5) })
        }
        addView(dots, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { leftMargin = dp(22); topMargin = dp(16) })
    }

    /**
     * Video editor: coral light, a small timeline picture, a chip that opens the project list and (once a project
     * exists) a chip that goes straight back into the latest one. The editor saves its work by itself, so the latest
     * project is always where you stopped.
     */
    private fun videoBlock(open: () -> Unit): View {
        val coral = Glass.ACCENT
        val chips = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fun pill(label: String, filled: Boolean, desc: String, go: () -> Unit) = FrameLayout(this).apply {
            isClickable = true; contentDescription = desc; glassTouch()
            addView(text(label, 12f, if (filled) Glass.ON_ACCENT else coral, filled).apply { gravity = Gravity.CENTER; setPadding(dp(11), 0, dp(11), 0)
                background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(if (filled) coral else Glass.alpha(coral, 46)) } }, FrameLayout.LayoutParams(-2, dp(28), Gravity.CENTER))
            setOnClickListener { Glass.tick(this); go() }
        }
        val card = toolCard(coral, "", "ตัดต่อวิดีโอ เลเยอร์ สโลว์ 4K", open) {
            val words = LinearLayout(this@HomeActivity).apply { orientation = LinearLayout.VERTICAL }
            words.addView(text("ตัดต่อวิดีโอ", 16f, Glass.TEXT, true)); words.addView(text("เลเยอร์ สโลว์ 4K", 11.5f, Glass.TEXT3))
            addView(words, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { leftMargin = dp(16); topMargin = dp(14) })
            addView(chips, FrameLayout.LayoutParams(-2, dp(48), Gravity.TOP or Gravity.END).apply { topMargin = dp(4); rightMargin = dp(6) })
            addView(MiniTimeline(this@HomeActivity, coral), FrameLayout.LayoutParams(dp(226), dp(34), Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(16); bottomMargin = dp(10) })
        }
        loader.execute {
            val list = runCatching { StudioProjects.list(this) }.getOrDefault(emptyList())
            runOnUiThread {
                if (isDestroyed || list.isEmpty()) return@runOnUiThread
                val last = list.firstOrNull { !it.missing }
                if (last != null) chips.addView(pill("ต่อจากเดิม", true, "ต่อโปรเจกต์ล่าสุด ${last.name}") {
                    startActivity(VideoEditorActivity.intent(this, last.source).putExtra("project", last.id)) })
                chips.addView(pill("โปรเจกต์ ${list.size}", false, "โปรเจกต์วิดีโอ งานตัดต่อที่ทำค้างไว้") { startActivity(Intent(this, StudioProjectsActivity::class.java)) },
                    LinearLayout.LayoutParams(-2, -2).apply { if (last != null) leftMargin = dp(4) })
            }
        }
        return card
    }

    /** Photo editor: lime light and three slider lines drawn faintly on the right. */
    private fun photoBlock(open: () -> Unit): View = toolCard(quiet(), "ตัดต่อภาพ", "ตัดต่อภาพ สี เส้นโค้ง ครอป", open) {
        addView(SlidersArt(this@HomeActivity, quiet()), FrameLayout.LayoutParams(-1, -1))
        putChip(EditorKit.ADJUST, quiet(), Gravity.TOP or Gravity.START)
    }

    /** AI sharpening: magenta light with faint sparkles and a small photo / video switch that decides what the gallery shows. */
    private fun aiBlock(open: () -> Unit): View {
        val mg = quiet()
        return toolCard(mg, "แต่งชัดด้วย AI", "แต่งชัดด้วย AI เลือกภาพหรือวิดีโอ", open) {
            for ((x, y, sz, a) in listOf(listOf(100, 36, 30, 0x2e), listOf(140, 62, 16, 0x38), listOf(84, 66, 12, 0x2e)))
                addView(IconView(this@HomeActivity, Icons.AI, 1.6f).apply { tint = Glass.alpha(mg, a) }, FrameLayout.LayoutParams(dp(sz), dp(sz), Gravity.TOP or Gravity.START).apply { leftMargin = dp(x); topMargin = dp(y) })
            putChip(Icons.AI, mg, Gravity.TOP or Gravity.START)
        }
    }

    /** Full-screen chooser for AI enhance: photo on the left, video on the right, over a slowly drifting aurora. */
    private fun showAiChooser(go: (Boolean) -> Unit) {
        val host = hostRef ?: return
        if (aiOverlay != null) return
        val photoHue = quiet(); val videoHue = Glass.ACCENT
        val root = FrameLayout(this).apply { setBackgroundColor(Glass.BG); isClickable = true }
        root.addView(Aurora(this, photoHue, videoHue), -1, -1)
        fun half(icon: String, hue: Int, title: String, desc: String, steps: List<String>, video: Boolean) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(14), dp(24), dp(14), dp(24)); isClickable = true; glassTouch()
            contentDescription = "$title $desc"
            addView(View(this@HomeActivity), LinearLayout.LayoutParams(1, 0, 1f))
            addView(FrameLayout(this@HomeActivity).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Glass.alpha(hue, 46)); setStroke(dp(1), Glass.alpha(hue, 120)) }
                addView(IconView(this@HomeActivity, icon, 2f).apply { tint = hue }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER)) }, dp(96), dp(96))
            addView(text(title, 22f, Glass.TEXT, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(6)) })
            addView(text(desc, 13f, Glass.TEXT2).apply { gravity = Gravity.CENTER; maxLines = 4; ellipsize = null; setLineSpacing(0f, 1.15f) },
                LinearLayout.LayoutParams(-1, -2).apply { leftMargin = dp(6); rightMargin = dp(6) })
            addView(stepFlow(steps, hue), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(22) })
            addView(View(this@HomeActivity), LinearLayout.LayoutParams(1, 0, 1.3f))
            setOnClickListener { Glass.tick(this); hideAiChooser(); go(video) }
        }
        val row = LinearLayout(this)
        row.addView(half(Icons.PHOTO, photoHue, "ภาพ", "เพิ่มรายละเอียดและความคมชัดให้ภาพนิ่ง", listOf("เลือกภาพ", "เลือกโมเดล", "ดูตัวอย่าง"), false), LinearLayout.LayoutParams(0, -1, 1f))
        row.addView(View(this).apply { setBackgroundColor(0x1fffffff) }, LinearLayout.LayoutParams(dp(1), -1).apply { topMargin = dp(120); bottomMargin = dp(60) })
        row.addView(half(Icons.VIDEO, videoHue, "วิดีโอ", "เพิ่มความคมชัดให้คลิป แล้วส่งออกเป็นไฟล์ใหม่", listOf("เลือกคลิป", "ปรับแต่ง", "ส่งออก"), true), LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(row, -1, -1)
        root.addView(text("แต่งชัดด้วย AI", 18f, Glass.TEXT, true).apply { gravity = Gravity.CENTER_VERTICAL }, FrameLayout.LayoutParams(-2, dp(48), Gravity.TOP or Gravity.START).apply { leftMargin = dp(20); topMargin = dp(10) })
        root.addView(roundButton(Icons.CLOSE, "ปิด") { hideAiChooser() }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply { topMargin = dp(10); rightMargin = dp(12) })
        // Keep clear of the status bar even before the first inset pass reaches this view.
        val sbId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val sbH = if (sbId > 0) resources.getDimensionPixelSize(sbId) else dp(28)
        root.setPadding(0, sbH, 0, 0)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets -> val b = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()); v.setPadding(b.left, maxOf(b.top, sbH), b.right, b.bottom); insets }
        host.addView(root, -1, -1); aiOverlay = root
        if (!Glass.reduced(this)) {
            root.alpha = 0f; root.animate().alpha(1f).setDuration(260).start()
            val l = row.getChildAt(0); val r = row.getChildAt(2)
            l.translationX = -dp(40).toFloat(); r.translationX = dp(40).toFloat(); l.alpha = 0f; r.alpha = 0f
            l.animate().translationX(0f).alpha(1f).setDuration(520).setInterpolator(Glass.SPRING).start()
            r.animate().translationX(0f).alpha(1f).setStartDelay(60).setDuration(520).setInterpolator(Glass.SPRING).start()
        }
    }
    /** The three steps as a small vertical flow: numbered nodes joined by a line, each with its label. */
    private fun stepFlow(steps: List<String>, hue: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        steps.forEachIndexed { i, st ->
            addView(LinearLayout(this@HomeActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(text("${i + 1}", 12f, Color.BLACK, true).apply { gravity = Gravity.CENTER
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(hue) } }, LinearLayout.LayoutParams(dp(24), dp(24)))
                addView(text(st, 13f, Glass.TEXT, true).apply { setPadding(dp(12), dp(6), dp(14), dp(6))
                    background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Glass.alpha(hue, 30)); setStroke(dp(1), Glass.alpha(hue, 70)) } },
                    LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
            })
            if (i < steps.size - 1) addView(View(this@HomeActivity).apply { setBackgroundColor(Glass.alpha(hue, 110)) },
                LinearLayout.LayoutParams(dp(2), dp(16)).apply { leftMargin = dp(11) })
        }
    }

    private fun hideAiChooser() {
        val v = aiOverlay ?: return; aiOverlay = null
        if (Glass.reduced(this)) hostRef?.removeView(v) else v.animate().alpha(0f).setDuration(180).withEndAction { hostRef?.removeView(v) }.start()
    }

    /** Two soft coloured lights that drift slowly across a dark page (one per side of the AI chooser). */
    private class Aurora(c: Context, private val left: Int, private val right: Int) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG); private var t = 0f
        private val anim = ValueAnimator.ofFloat(0f, 1f).apply { duration = 9000; repeatCount = ValueAnimator.INFINITE; addUpdateListener { t = it.animatedValue as Float; invalidate() } }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); if (!Glass.reduced(context)) anim.start() }
        override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
        override fun onDraw(cv: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val a = (t * 2 * Math.PI).toFloat()
            fun light(cx: Float, cy: Float, rad: Float, col: Int) { p.shader = RadialGradient(cx, cy, rad, Glass.alpha(col, 90), Glass.alpha(col, 0), Shader.TileMode.CLAMP); cv.drawCircle(cx, cy, rad, p) }
            light(w * 0.25f + kotlin.math.sin(a) * w * 0.08f, h * 0.35f + kotlin.math.cos(a) * h * 0.05f, w * 0.7f, left)
            light(w * 0.75f + kotlin.math.cos(a) * w * 0.08f, h * 0.6f + kotlin.math.sin(a) * h * 0.05f, w * 0.7f, right)
        }
    }

    /** Light patterns drawn over a mode card's glow. */
    private enum class Motif { NONE, GRID, DOTS, STREAK, HALO }

    /**
     * A mode card with its own backdrop: a dark base, a soft light in the mode's colour, a faint pattern, and the
     * mode icon large and faint in the corner. The colours belong to the mode, not to the theme.
     */
    private fun modeCard(key: String, icon: String, title: String, hue: Int, motif: Motif, open: () -> Unit): View = FrameLayout(this).apply {
        val t = hue
        background = ModeBackdrop(this@HomeActivity, t, motif)
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(22).toFloat()) }
        isClickable = true; contentDescription = title; glassTouch()
        addView(IconView(this@HomeActivity, icon, 1.2f).apply { tint = Glass.alpha(t, 0x2a) }, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.TOP or Gravity.END).apply { topMargin = dp(14); rightMargin = dp(12) })
        addView(FrameLayout(this@HomeActivity).apply {
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0x47000000); setStroke(dp(1), 0x1affffff) }
            addView(IconView(this@HomeActivity, icon, 2f).apply { tint = t }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        }, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP or Gravity.START).apply { topMargin = dp(12); leftMargin = dp(12) })
        addView(text(title, 15f, Glass.TEXT, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
            FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(14); rightMargin = dp(10); bottomMargin = dp(12) })
        setOnClickListener { Glass.tick(this); open() }
        cards += this
    }

    private class ModeBackdrop(c: Context, private val tint: Int, private val motif: Motif, private val radiusDp: Int = 22) : android.graphics.drawable.Drawable() {
        private val d = c.resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = d; color = 0x1affffff }
        private var glow: RadialGradient? = null
        private var gw = 0f
        override fun draw(cv: Canvas) {
            val w = bounds.width().toFloat(); val h = bounds.height().toFloat(); val rad = radiusDp * d
            val base = Theme.mix(Glass.BG, tint, 0.07f)
            r.set(0f, 0f, w, h)
            p.shader = null; p.style = Paint.Style.FILL; p.color = base; cv.drawRoundRect(r, rad, rad, p)
            if (glow == null || gw != w) { gw = w
                glow = RadialGradient(w * 0.82f, h * 0.18f, w * 0.85f, Glass.alpha(tint, 0x4d), Glass.alpha(tint, 0), Shader.TileMode.CLAMP) }
            p.shader = glow; cv.drawRoundRect(r, rad, rad, p); p.shader = null
            cv.save(); cv.clipRect(0f, 0f, w, h)
            p.color = 0x0dffffff; p.strokeWidth = d; p.style = Paint.Style.STROKE
            when (motif) {
                Motif.GRID -> { var x = 0f; while (x < w) { cv.drawLine(x, 0f, x, h, p); x += 22 * d }; var y = 0f; while (y < h) { cv.drawLine(0f, y, w, y, p); y += 22 * d } }
                Motif.DOTS -> { p.style = Paint.Style.FILL; p.color = 0x38ffffff; var y = 8 * d; while (y < h) { var x = 8 * d; while (x < w) { cv.drawCircle(x, y, 0.7f * d, p); x += 16 * d }; y += 16 * d } }
                Motif.STREAK -> { p.color = 0x12ffffff; p.strokeWidth = 2 * d; var x = -h; while (x < w) { cv.drawLine(x, h, x + h * 0.18f, 0f, p); x += 20 * d } }
                Motif.HALO -> { p.style = Paint.Style.FILL; p.color = Glass.alpha(tint, 0x29); cv.drawCircle(w * 0.5f, h * 0.38f, h * 0.45f, p) }
                Motif.NONE -> {}
            }
            cv.restore()
            // Darken toward the bottom so the title stays readable over any glow.
            p.style = Paint.Style.FILL; p.shader = LinearGradient(0f, h * 0.45f, 0f, h, 0x00000000, 0x66000000, Shader.TileMode.CLAMP); cv.drawRoundRect(r, rad, rad, p); p.shader = null
            r.inset(d / 2, d / 2); cv.drawRoundRect(r, rad, rad, rim)
        }
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(f: android.graphics.ColorFilter?) {}
        @Suppress("OVERRIDE_DEPRECATION") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** Three clips (the first with a little waveform) and a playhead, in the tool colour: only a picture of the editor. */
    private var projectRow: LinearLayout? = null
    private var heroPager: HeroPager? = null
    private var projects: List<StudioProjects.Info> = emptyList()
    private var projectCount = 0

    override fun onResume() { super.onResume(); heroPager?.running = true }
    override fun onPause() { heroPager?.running = false; super.onPause() }

    /** The first moments of a project's video at screen size. */
    private fun sharpFrame(info: StudioProjects.Info): Bitmap? = runCatching {
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(this, info.source)
            val d = (r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000L
            val t = (d / 5).coerceAtMost(2_000_000L)
            val w = if (Device.low(this)) 720 else 1080; val h = w * 4 / 3
            if (android.os.Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(t, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h)
            // Older Android hands back the frame at the video's own size (a 4K frame is about 33 MB), so it is made smaller here.
            else r.getFrameAtTime(t)?.let { b -> val k = h.toFloat() / maxOf(b.width, b.height)
                if (k < 1f) Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true).also { if (it !== b) b.recycle() } else b }
        } finally { runCatching { r.release() } }
    }.getOrNull()
    private var heroHeader: View? = null
    private var topInset = 0

    /** The icon colour: the theme colour, a little deeper on a pale page so it stays readable. */
    private fun iconTone(): Int = if (Theme.light) Theme.mix(Glass.ACCENT, Theme.BLACK, 0.22f) else Glass.ACCENT

    /** The line under the picture: which project "ต่อจากเดิม" opens, and the one big button. */
    private fun showProject(last: StudioProjects.Info?, count: Int) {
        val row = projectRow ?: return
        row.removeAllViews()
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        words.addView(text(last?.name ?: "ตัดต่อวิดีโอ", 16f, Glass.TEXT, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        words.addView(text(if (last != null) "แก้ไขล่าสุด ${ago(last.updated)}" + (if (count > 1) " $count โปรเจกต์" else "") else "เลเยอร์ สโลว์ 4K ส่งออก", 12f, Glass.TEXT3).apply { maxLines = 1 })
        row.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        fun pill(label: String, go: () -> Unit) = text(label, 14f, Glass.ON_ACCENT, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(18), 0, dp(18), 0); isClickable = true; glassTouch(); contentDescription = label
            background = glass(22, Glass.ACCENT); setOnClickListener { Glass.tick(it); go() }
        }
        row.addView(if (last != null) pill("ต่อจากเดิม") { startActivity(VideoEditorActivity.intent(this, last.source).putExtra("project", last.id)) }
            else pill("เริ่มตัดต่อ") { startActivity(Intent(this, StudioProjectsActivity::class.java)) }, LinearLayout.LayoutParams(-2, dp(42)))
    }

    private fun ago(t: Long): String {
        val m = ((System.currentTimeMillis() - t) / 60_000).coerceAtLeast(0)
        return when { m < 2 -> "เมื่อครู่"; m < 60 -> "$m นาทีก่อน"; m < 60 * 24 -> "${m / 60} ชั่วโมงก่อน"; else -> "${m / (60 * 24)} วันก่อน" }
    }

    /** A button on the picture: dark glass with a white icon, whatever the theme. */
    private fun heroButton(icon: String, desc: String, click: () -> Unit) = FrameLayout(this).apply {
        isClickable = true; contentDescription = desc
        val face = FrameLayout(this@HomeActivity).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x66000000) } }
        face.addView(IconView(this@HomeActivity, icon, 2f).apply { tint = Color.WHITE }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        addView(face, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER))
        glassTouch(); setOnClickListener { Glass.tick(this); click() }
    }

    /** The video editor: the latest project's picture (or a drawn scene), the title bar, a play mark and the timeline. Tapping anywhere opens the editor. */
    private fun studioHero(logo: View, title: View): FrameLayout = FrameLayout(this).apply {
        minimumHeight = dp(270); contentDescription = "ตัดต่อวิดีโอ เปิดตัวตัดต่อ"; isClickable = true; glassTouch()
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, -dp(30), v.width, v.height, dp(28).toFloat()) }
        addView(HeroScene(this@HomeActivity, if (Theme.light) 0xffc9d6ea.toInt() else 0xff2a3550.toInt(), if (Theme.light) 0xff9fb2ce.toInt() else 0xff161b2a.toInt(), Glass.ACCENT), -1, -1)
        val pager = HeroPager(this@HomeActivity) { i -> projects.getOrNull(i)?.let { showProject(it, projectCount) } }
        heroPager = pager
        addView(pager, -1, -1)
        addView(View(this@HomeActivity).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x66000000, 0x00000000, 0x99000000.toInt())) }, -1, -1)
        val header = LinearLayout(this@HomeActivity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(10), dp(12), 0) }
        header.addView(logo, dp(36), dp(36)); header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(heroButton(Icons.GEAR, "ธีมสี") { showThemeSettings { changed -> if (changed) recreate() } }, dp(46), dp(46))
        header.addView(heroButton(Icons.CLOSE, "กลับไปที่กล้อง") { close() }, dp(46), dp(46))
        heroHeader = header
        addView(header, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        // The one label on the picture: a clear glass button above the timeline.
        addView(LinearLayout(this@HomeActivity).apply {
            gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL; setPadding(dp(20), 0, dp(22), 0); isClickable = true; glassTouch(); contentDescription = "ตัดต่อวิดีโอ"
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(0x4D000000); setStroke(dp(1), 0x99ffffff.toInt()) }
            addView(IconView(this@HomeActivity, Icons.VIDEO, 2f).apply { tint = Color.WHITE }, LinearLayout.LayoutParams(dp(20), dp(20)))
            addView(text("ตัดต่อวิดีโอ", 15f, Color.WHITE, true).apply { setPadding(dp(8), 0, 0, 0) })
            setOnClickListener { Glass.tick(this); startActivity(Intent(this@HomeActivity, StudioProjectsActivity::class.java)) }
        }, FrameLayout.LayoutParams(-2, dp(48), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(14 + 66 + 12) })
        addView(StudioStrip(this@HomeActivity, Glass.ACCENT), FrameLayout.LayoutParams(-1, dp(66), Gravity.BOTTOM).apply { setMargins(dp(14), 0, dp(14), dp(14)) })
        setOnClickListener { Glass.tick(this); startActivity(Intent(this@HomeActivity, StudioProjectsActivity::class.java)) }
        cards += this
    }

    /** One tile: a chip with the icon in the theme colour, the name and a short line. [badge] is a small tappable label (AI) in the corner. */
    private fun tile(icon: String, name: String, note: String, desc: String, badge: Pair<String, () -> Unit>?, open: () -> Unit): FrameLayout = FrameLayout(this).apply {
        background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Glass.PANEL); setStroke(dp(1), Glass.alpha(Glass.TEXT, 0x22)) }
        isClickable = true; contentDescription = desc; glassTouch()
        addView(FrameLayout(this@HomeActivity).apply {
            background = GradientDrawable().apply { cornerRadius = dp(11).toFloat(); setColor(Glass.ACCENT_SOFT) }
            addView(IconView(this@HomeActivity, icon, 2f).apply { tint = iconTone() }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        }, FrameLayout.LayoutParams(dp(38), dp(38), Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(12), 0, 0) })
        if (badge != null) addView(text(badge.first, 12f, Glass.ON_ACCENT, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(10), 0, dp(10), 0); background = glass(11, Glass.ACCENT); isClickable = true; glassTouch(); contentDescription = "แต่งชัดด้วย AI"
            setOnClickListener { Glass.tick(it); badge.second() } }, FrameLayout.LayoutParams(-2, dp(26), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(12), dp(12), 0) })
        val words = LinearLayout(this@HomeActivity).apply { orientation = LinearLayout.VERTICAL }
        words.addView(text(name, 15f, Glass.TEXT, true).apply { maxLines = 1 }); words.addView(text(note, 12f, Glass.TEXT3).apply { maxLines = 1 })
        addView(words, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(14), 0, dp(10), dp(12)) })
        setOnClickListener { Glass.tick(this); open() }
        cards += this
    }

    /**
     * The project pictures side by side: drag to slide to the next or the earlier one; on its own the picture on show zooms in slowly
     * and, after a few seconds, slides to the next one (not at all when animations are reduced, or when there is one project).
     */
    private class HeroPager(c: Context, private val onPage: (Int) -> Unit) : FrameLayout(c) {
        private val views = ArrayList<ImageView>()
        private val zoom = ArrayList<Float>()
        private var offset = 0f
        private var zoomAnim: android.animation.ValueAnimator? = null
        private var slide: android.animation.ValueAnimator? = null
        private var downX = 0f; private var startOffset = 0f; private var dragging = false
        private var velocity: android.view.VelocityTracker? = null
        private val slop = android.view.ViewConfiguration.get(c).scaledTouchSlop
        private val still = Glass.reduced(c)
        private val tick = Runnable { if (running && count > 1 && !dragging) go(1) }
        var running = false
            set(v) { field = v; removeCallbacks(tick); if (v) { startZoom(); schedule() } else zoomAnim?.cancel() }
        var count = 0
            set(v) { field = v; views.forEach { removeView(it) }; views.clear(); zoom.clear()
                repeat(v) { _ ->
                    views += ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.INVISIBLE }.also { addView(it, -1, -1) }; zoom += 0f }
                offset = 0f; layoutPages(); startZoom(); schedule() }
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        private val page get() = if (count == 0) 0 else Math.floorMod(Math.round(offset), count)

        fun setPicture(i: Int, b: Bitmap) { views.getOrNull(i)?.let { it.setImageBitmap(b); it.visibility = View.VISIBLE; layoutPages() } }

        private fun schedule() { removeCallbacks(tick); if (!still && count > 1 && running) postDelayed(tick, 5000) }
        private fun startZoom() {
            zoomAnim?.cancel()
            if (still || count == 0 || !running) return
            val i = page
            zoomAnim = android.animation.ValueAnimator.ofFloat(zoom[i], 1f).apply {
                duration = 5200; interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener { zoom[i] = it.animatedValue as Float; layoutPages() }; start() }
        }
        /** Moves [step] pictures ahead (or back) with a smooth slide, then zooms the new one. */
        private fun go(step: Int) {
            if (count < 2) return
            slide?.cancel(); zoomAnim?.cancel()
            val to = Math.round(offset) + step
            slide = android.animation.ValueAnimator.ofFloat(offset, to.toFloat()).apply {
                duration = 700; interpolator = android.view.animation.DecelerateInterpolator(1.6f)
                addUpdateListener { offset = it.animatedValue as Float; layoutPages() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: android.animation.Animator) {
                        for (k in zoom.indices) if (k != page) zoom[k] = 0f
                        onPage(page); layoutPages(); startZoom(); schedule() } })
                start() }
        }
        private fun layoutPages() {
            val w = width.toFloat(); if (w == 0f) return
            val n = count
            views.forEachIndexed { i, v ->
                var d = (((i - offset) % n) + n) % n; if (d > n / 2f) d -= n
                val near = Math.abs(d) < 1.01f
                // While it waits its turn the picture zooms in and drifts a little to one side (the side alternates per picture).
                v.translationX = d * w + (if (i % 2 == 0) -1f else 1f) * 0.025f * w * zoom[i]
                val sc = 1f + 0.14f * zoom[i]
                v.scaleX = sc; v.scaleY = sc
                v.alpha = 1f - 0.25f * Math.min(1f, Math.abs(d))
                v.visibility = if (near && v.drawable != null) View.VISIBLE else View.INVISIBLE
            }
        }
        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); layoutPages() }
        override fun onDetachedFromWindow() { removeCallbacks(tick); zoomAnim?.cancel(); slide?.cancel(); super.onDetachedFromWindow() }

        override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
            if (count == 0) { if (e.action == android.view.MotionEvent.ACTION_UP) (parent as? View)?.performClick(); return true }
            if (velocity == null) velocity = android.view.VelocityTracker.obtain()
            velocity?.addMovement(e)
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downX = e.x; startOffset = offset; dragging = false; removeCallbacks(tick); parent?.requestDisallowInterceptTouchEvent(true); slide?.cancel() }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (!dragging && Math.abs(e.x - downX) > slop && count > 1) dragging = true
                    if (dragging) { offset = startOffset - (e.x - downX) / width; layoutPages() }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    velocity?.computeCurrentVelocity(1000); val vx = velocity?.xVelocity ?: 0f
                    if (!dragging) { if (e.actionMasked == android.view.MotionEvent.ACTION_UP) (parent as? View)?.performClick(); startZoom(); schedule() }
                    else {
                        val moved = offset - startOffset
                        val step = when { vx < -900f || moved > 0.3f -> 1; vx > 900f || moved < -0.3f -> -1; else -> 0 }
                        dragging = false
                        val base = Math.round(startOffset)
                        val from = offset
                        val target = (base + step).toFloat()
                        slide = android.animation.ValueAnimator.ofFloat(from, target).apply {
                            duration = 420; interpolator = android.view.animation.DecelerateInterpolator(1.6f)
                            addUpdateListener { offset = it.animatedValue as Float; layoutPages() }
                            addListener(object : android.animation.AnimatorListenerAdapter() {
                                override fun onAnimationEnd(a: android.animation.Animator) {
                                    for (k in zoom.indices) if (k != page) zoom[k] = 0f
                                    onPage(page); layoutPages(); startZoom(); schedule() } })
                            start() }
                    }
                    velocity?.recycle(); velocity = null
                }
            }
            return true
        }
        override fun performClick(): Boolean = super.performClick()
    }

    /** A drawn scene (sky, sun in the theme colour, a ridge) for a project that has no picture yet. */
    private class HeroScene(c: Context, private val sky: Int, private val ridge: Int, private val sun: Int) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG); private val d = c.resources.displayMetrics.density
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(cv: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            p.color = sky; cv.drawRect(0f, 0f, w, h, p)
            p.color = sun; cv.drawCircle(w * 0.76f, h * 0.36f, 26 * d, p)
            p.color = ridge; cv.drawOval(-w * 0.25f, h * 0.62f, w * 1.25f, h * 1.3f, p)
        }
    }

    /** Ruler, three tracks (video blue, sound teal, text violet) and the playhead in the theme colour, on a dark glass strip. */
    private class StudioStrip(c: Context, private val tint: Int) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG); private val r = RectF(); private val d = c.resources.displayMetrics.density
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(cv: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            p.style = Paint.Style.FILL; p.color = 0x94000000.toInt(); r.set(0f, 0f, w, h); cv.drawRoundRect(r, 12 * d, 12 * d, p)
            val x0 = 10 * d; val x1 = w - 10 * d; val span = x1 - x0
            p.color = 0x88ffffff.toInt()
            for (i in 0..16) { val x = x0 + span * i / 16f; cv.drawRect(x, 7 * d, x + d, (if (i % 4 == 0) 14 else 11) * d, p) }
            p.color = 0xff6fa8ff.toInt(); r.set(x0, 18 * d, x0 + span * 0.86f, 24 * d); cv.drawRoundRect(r, 3 * d, 3 * d, p)
            p.color = 0xff4fd1b2.toInt(); val a0 = x0 + span * 0.08f; val a1 = x0 + span * 0.70f; var x = a0; var i = 0
            while (x < a1) { val bh = (3 + (i * 7) % 9) * d; cv.drawRoundRect(x, 36 * d - bh, x + 2.2f * d, 36 * d + bh, d, d, p); x += 4 * d; i++ }
            p.color = 0xffb79cff.toInt(); r.set(x0 + span * 0.30f, 48 * d, x0 + span * 0.62f, 54 * d); cv.drawRoundRect(r, 3 * d, 3 * d, p)
            val hx = x0 + span * 0.44f; p.color = tint; cv.drawRect(hx - d, 4 * d, hx + d, h - 4 * d, p); cv.drawCircle(hx, 5 * d, 4 * d, p)
        }
    }

    private class MiniTimeline(c: Context, private val tint: Int) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private val d = c.resources.displayMetrics.density
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(cv: Canvas) {
            val h = height.toFloat(); val y0 = 6 * d; val y1 = h - 2 * d; val rad = 8 * d
            fun clip(x: Float, w: Float, fill: Int) { r.set(x * d, y0, (x + w) * d, y1); p.style = Paint.Style.FILL; p.color = fill; cv.drawRoundRect(r, rad, rad, p) }
            clip(0f, 70f, 0x24ffffff); clip(74f, 56f, 0x1affffff); clip(134f, 92f, Glass.alpha(tint, 56))
            p.style = Paint.Style.STROKE; p.strokeWidth = d; p.color = Glass.alpha(tint, 0x88); r.set(134 * d + d / 2, y0 + d / 2, 226 * d - d / 2, y1 - d / 2); cv.drawRoundRect(r, rad, rad, p)
            p.style = Paint.Style.FILL; p.color = 0x47ffffff
            val mid = (y0 + y1) / 2
            for (i in 0 until 10) { val bh = (6 + (i * 7) % 11 * 1.6f) * d / 2; cv.drawRoundRect(10 * d + i * 5 * d, mid - bh, 10 * d + i * 5 * d + 3 * d, mid + bh, d, d, p) }
            p.color = tint; cv.drawRect(118 * d, 0f, 120 * d, h, p)
            cv.drawRoundRect(113 * d, 0f, 125 * d, 6 * d, 3 * d, 3 * d, p)
        }
    }

    /** Film perforation holes along the left and right edges. */
    private class Perforations(c: Context) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x38ffffff }
        private val r = RectF()
        private val d = c.resources.displayMetrics.density
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(cv: Canvas) {
            var y = 5 * d
            while (y < height - 4 * d) {
                r.set(3 * d, y, 8 * d, y + 6 * d); cv.drawRoundRect(r, d, d, p)
                r.set(width - 8 * d, y, width - 3 * d, y + 6 * d); cv.drawRoundRect(r, d, d, p)
                y += 12 * d
            }
        }
    }

    /** Three slider lines with round knobs, faint, as a picture of the colour tools. */
    private class SlidersArt(c: Context, private val tint: Int) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val d = c.resources.displayMetrics.density
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(cv: Canvas) {
            val x0 = width - 76 * d; val w = 62 * d
            val knobs = floatArrayOf(0.30f, 0.70f, 0.48f)
            for (i in 0 until 3) {
                val y = 24 * d + i * 20 * d
                p.color = 0x24ffffff; p.style = Paint.Style.FILL; cv.drawRoundRect(x0, y - d, x0 + w, y + d, d, d, p)
                p.color = Glass.alpha(tint, 0xcc); cv.drawCircle(x0 + w * knobs[i], y, 6 * d, p)
            }
        }
    }

    /** The faint theme-coloured light in the top-right corner, drawn once (no animation). */
    private class Glow(c: Context) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val m = android.graphics.Matrix()
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            val rx = w * 0.9f; val ry = h * 0.32f
            val sh = RadialGradient(0f, 0f, 1f, Glass.alpha(Glass.ACCENT, 0x1c), 0x00000000, Shader.TileMode.CLAMP)
            m.setScale(rx, ry); m.postTranslate(w * 0.85f, 0f); sh.setLocalMatrix(m); p.shader = sh
        }
        override fun onDraw(cv: Canvas) { cv.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p) }
    }
}
