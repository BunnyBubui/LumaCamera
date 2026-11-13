package th.ogay.luma

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Bitmap
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
        const val HOUSE = "M3.5 11 12 4l8.5 7 M6 9.5V20h4.5v-5.5h3V20H18V9.5"
        const val BACK_SMALL = "M14.5 6.5 9 12l5.5 5.5"
    }

    private var editKind = "image"
    private val pick = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        @Suppress("DEPRECATION") val uri = r.data?.getParcelableArrayListExtra<Uri>(GalleryActivity.RESULT_URIS)?.firstOrNull() ?: return@registerForActivityResult
        when (editKind) {
            // Pick a photo, then the AI sheet (model, size, strength, preview) opens right here; the result lands in Lumora's gallery.
            "ai_image" -> th.ogay.luma.ai.EnhanceUi.showForUri(this, uri) { notice("เริ่มเพิ่มรายละเอียดแล้ว · ดูความคืบหน้าในแกลเลอรี")
                startActivity(Intent(this, GalleryActivity::class.java).putExtra("tab", "luma").putExtra("from", "home")) }
            "ai_video" -> startActivity(VideoEditorActivity.intent(this, uri).putExtra("ai_export", true))
            "video" -> startActivity(Intent(this, VideoEditorActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            else -> startActivity(Intent(this, PhotoEditorActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }

    private val cards = ArrayList<View>()
    private val loader = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("camera", MODE_PRIVATE) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val host = FrameLayout(this)
        installRoot(host)
        host.setBackgroundColor(Glass.BG)
        // One still, faint light in the theme colour at the top right (the old moving blue/purple lights are gone).
        host.addView(Glow(this), -1, -1)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(32)) }
        // The page scrolls under the status and navigation bars instead of being cut off at a hard line below them;
        // a soft shade under the status bar keeps the clock readable over the blocks.
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipToPadding = false; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS; addView(col) }
        host.addView(scroll, -1, -1)
        val shade = View(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Glass.alpha(Glass.BG, 0xf2), Glass.alpha(Glass.BG, 0xa0), Glass.alpha(Glass.BG, 0))) }
        host.addView(shade, FrameLayout.LayoutParams(-1, dp(40)))
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(host) { _, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            host.setPadding(0, 0, 0, 0)
            scroll.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            shade.layoutParams = (shade.layoutParams as FrameLayout.LayoutParams).apply { height = bars.top + dp(28) }
            insets
        }

        // Header: the mark and the name on the left; theme colours and back-to-camera on the right.
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val logo = LumoraLogoView(this).apply { progress = if (Glass.reduced(this@HomeActivity)) 1f else 0f }
        header.addView(logo, dp(40), dp(40))
        val title = text("Lumora", 24f, Glass.TEXT, true).apply { setPadding(dp(10), 0, 0, 0) }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(roundButton(Icons.GEAR, "ธีมสี") { showThemeSettings { changed -> if (changed) recreate() } }, dp(48), dp(48))
        header.addView(roundButton(Icons.CLOSE, "กลับไปที่กล้อง") { close() }, dp(48), dp(48))
        col.addView(header, LinearLayout.LayoutParams(-1, dp(52)))

        fun pickFor(kind: String, ek: String) { editKind = ek; pick.launch(Intent(this, GalleryActivity::class.java).putExtra(GalleryActivity.EXTRA_PICK, kind)) }
        fun gap(v: Int) = View(this).also { col.addView(it, LinearLayout.LayoutParams(1, dp(v))) }

        // Row 1: the tall camera block, with gallery and film stacked beside it.
        gap(14)
        val top = LinearLayout(this)
        top.addView(hero(), LinearLayout.LayoutParams(0, dp(210), 1f))
        top.addView(View(this), dp(10), 1)
        val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        side.addView(galleryBlock(), LinearLayout.LayoutParams(-1, 0, 1f))
        side.addView(View(this), 1, dp(10))
        side.addView(filmBlock(), LinearLayout.LayoutParams(-1, 0, 1f))
        top.addView(side, LinearLayout.LayoutParams(0, dp(210), 1f))
        col.addView(top, -1, -2)

        // Row 2: the video editor across the full width, with the saved projects as a chip.
        gap(10)
        col.addView(videoBlock { pickFor("video", "video") }, LinearLayout.LayoutParams(-1, dp(104)))

        // Row 3: photo editor and AI (photo or video picked by the small switch).
        gap(10)
        val third = LinearLayout(this)
        third.addView(block("ตัดต่อภาพ", "สี เส้นโค้ง ครอป", { pickFor("image", "image") }) { c ->
            c.addView(chip(EditorKit.ADJUST, false), dp(38), dp(38)) }, LinearLayout.LayoutParams(0, dp(100), 1f))
        third.addView(View(this), dp(10), 1)
        third.addView(aiBlock { video -> if (video) pickFor("video", "ai_video") else pickFor("image", "ai_image") }, LinearLayout.LayoutParams(0, dp(100), 1f))
        col.addView(third, -1, -2)

        // Photo modes as short chips, two per row.
        col.addView(text("โหมดถ่าย", 15f, Glass.TEXT2, true).apply { setPadding(dp(4), dp(24), 0, dp(10)) }, -1, -2)
        listOf(
            listOf(modeChip(Icons.PORTRAIT, "บุคคล", "หน้าชัด หลังเบลอ") { returnMode(PhotoMode.PORTRAIT) }, modeChip(Icons.PRO, "โปร", "ปรับ ISO ชัตเตอร์ โฟกัส") { returnMode(PhotoMode.PRO) }),
            listOf(modeChip(Icons.HIRES, "ความละเอียดสูง", "ใช้เซนเซอร์เต็มที่") { returnMode(PhotoMode.HIRES) },
                modeChip(Icons.VIDEO, "วิดีโอ", "ถ่ายวิดีโอ") { startActivity(Intent(this, VideoActivity::class.java).putExtra("mode", "VIDEO")) }),
            listOf(modeChip(Icons.FLASH, "ถ่ายเร็ว", "จับจังหวะที่ขยับเร็ว") { returnMode(PhotoMode.SPORT) }, modeChip(Icons.PHOTO, "ภาพถ่าย", "ถ่ายยาวหลายวินาทีได้") { returnMode(PhotoMode.PHOTO) }),
        ).forEach { pair ->
            val row = LinearLayout(this)
            row.addView(pair[0], LinearLayout.LayoutParams(0, dp(60), 1f))
            row.addView(View(this), dp(10), 1)
            row.addView(pair[1], LinearLayout.LayoutParams(0, dp(60), 1f))
            col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }

        // Entrance: the mark draws, the name settles, the blocks rise one after another.
        if (!Glass.reduced(this)) {
            ValueAnimator.ofFloat(0f, 1f).apply { duration = 1100; startDelay = 100; addUpdateListener { logo.progress = it.animatedValue as Float } }.start()
            title.alpha = 0f; title.translationX = -dp(12).toFloat(); title.animate().alpha(1f).translationX(0f).setStartDelay(500).setDuration(500).setInterpolator(Glass.EASE).start()
            cards.forEachIndexed { i, v -> v.alpha = 0f; v.translationY = dp(26).toFloat(); v.scaleX = 0.96f; v.scaleY = 0.96f
                v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setStartDelay(180L + i * 45L).setDuration(460).setInterpolator(Glass.SPRING).start() }
        }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) { override fun handleOnBackPressed() = close() })
    }

    override fun onDestroy() { loader.shutdownNow(); super.onDestroy() }

    private fun close() { finish(); @Suppress("DEPRECATION") overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out) }
    private fun returnMode(m: PhotoMode) { setResult(RESULT_OK, Intent().putExtra(RESULT_MODE, m.name)); close() }

    // ---------- Pieces ----------
    private fun cardBg(r: Int) = GradientDrawable().apply { cornerRadius = dp(r).toFloat(); setColor(0x0dffffff); setStroke(dp(1), 0x14ffffff) }

    /** A clickable block with the quiet card look; [top] fills the upper part, the title and note sit at the bottom. */
    private fun block(title: String, note: String, open: () -> Unit, top: (LinearLayout) -> Unit): FrameLayout = FrameLayout(this).apply {
        background = cardBg(24); isClickable = true; contentDescription = "$title $note"; glassTouch(); clipToOutline = true
        val c = LinearLayout(this@HomeActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(13), dp(14), dp(12)) }
        val upper = LinearLayout(this@HomeActivity).apply { gravity = Gravity.CENTER_VERTICAL }
        top(upper)
        c.addView(upper, -1, -2)
        c.addView(View(this@HomeActivity), LinearLayout.LayoutParams(1, 0, 1f))
        c.addView(text(title, 15f, Glass.TEXT, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        addView(c, -1, -1)
        setOnClickListener { Glass.tick(this); open() }
        cards += this
    }

    /** The 38dp icon square: neutral for ordinary tools, theme colour for AI. */
    private fun chip(icon: String, accent: Boolean) = FrameLayout(this).apply {
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(if (accent) Glass.alpha(Glass.ACCENT, 46) else 0x14ffffff) }
        addView(IconView(this@HomeActivity, icon, 2f).apply { tint = if (accent) Glass.ACCENT else Glass.TEXT }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
    }

    private fun roundButton(icon: String, desc: String, click: () -> Unit) = FrameLayout(this).apply {
        isClickable = true; contentDescription = desc
        val face = FrameLayout(this@HomeActivity).apply { background = glass(22, 0x2effffff) }
        face.addView(IconView(this@HomeActivity, icon, 2f).apply { tint = Glass.TEXT }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        addView(face, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER))
        glassTouch(); setOnClickListener { Glass.tick(this); click() }
    }

    /** The camera block: tall, in the theme colour, with dark text on it. */
    private fun hero(): View = FrameLayout(this).apply {
        val hsv = FloatArray(3); Color.colorToHSV(Glass.ACCENT, hsv)
        val light = Theme.mix(Glass.ACCENT, 0xffffffff.toInt(), 0.22f)
        val deep = Color.HSVToColor(floatArrayOf((hsv[0] - 10f + 360f) % 360f, (hsv[1] + 0.12f).coerceAtMost(1f), hsv[2]))
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(light, deep)).apply { cornerRadius = dp(24).toFloat() }
        isClickable = true; contentDescription = "ถ่ายภาพ กลับไปที่กล้อง"; glassTouch()
        val on = Glass.ON_ACCENT
        val c = LinearLayout(this@HomeActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        c.addView(FrameLayout(this@HomeActivity).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x52ffffff) }
            addView(IconView(this@HomeActivity, Icons.PHOTO, 2f).apply { tint = on }, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER)) }, dp(56), dp(56))
        c.addView(View(this@HomeActivity), LinearLayout.LayoutParams(1, 0, 1f))
        c.addView(text("ถ่ายภาพ", 24f, on, true))
        c.addView(text("กลับไปที่กล้อง · ${CameraActivity.ALL.size} โหมด", 12.5f, Glass.alpha(on, 0xcc)).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        addView(c, -1, -1)
        setOnClickListener { Glass.tick(this); returnMode(PhotoMode.PHOTO) }
        cards += this
    }

    /** Gallery: the four newest photos as a small collage (grey squares until they load, or without permission). */
    private fun galleryBlock(): View = FrameLayout(this).apply {
        background = cardBg(24); isClickable = true; contentDescription = "แกลเลอรี รูปและวิดีโอทั้งหมด"; glassTouch(); clipToOutline = true
        val shades = intArrayOf(0xff5f5f66.toInt(), 0xff6b5a46.toInt(), 0xff55604a.toInt(), 0xff6b4f46.toInt())
        val tiles = (0 until 4).map { i -> ImageView(this@HomeActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(shades[i], Theme.mix(shades[i], 0xff000000.toInt(), 0.6f))).apply { cornerRadius = dp(8).toFloat() }
            outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(8).toFloat()) }
        } }
        tiles.forEachIndexed { i, v -> addView(v, FrameLayout.LayoutParams(dp(36), dp(30)).apply { leftMargin = dp(10 + (i % 2) * 40); topMargin = dp(10 + (i / 2) * 34) }) }
        addView(text("แกลเลอรี", 15f, Glass.TEXT, true), FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(14); bottomMargin = dp(12) })
        setOnClickListener { Glass.tick(this); startActivity(Intent(this@HomeActivity, GalleryActivity::class.java).putExtra("from", "home")) }
        cards += this
        loadRecent(tiles)
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

    /** Film: four colour dots standing for the looks, and the film icon. */
    private fun filmBlock(): View = block("ฟิล์ม", "โทนสีและเกรนฟิล์ม", { returnMode(PhotoMode.FILM) }) { c ->
        listOf(0xffb8893a, 0xff5a6f8a, 0xffa8707a, 0xff707070).forEachIndexed { i, col ->
            c.addView(View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(col.toInt()) } },
                LinearLayout.LayoutParams(dp(16), dp(16)).apply { if (i > 0) leftMargin = dp(5) })
        }
        c.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        c.addView(IconView(this, Icons.FILM, 1.8f).apply { tint = Glass.TEXT }, dp(20), dp(20))
    }

    /** Video editor: title, a projects chip that opens saved work, and a small timeline picture. */
    private fun videoBlock(open: () -> Unit): View = FrameLayout(this).apply {
        background = cardBg(24); isClickable = true; contentDescription = "ตัดต่อวิดีโอ เลเยอร์ สโลว์ 4K"; glassTouch()
        val c = LinearLayout(this@HomeActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(12)) }
        val top = LinearLayout(this@HomeActivity).apply { gravity = Gravity.CENTER_VERTICAL }
        val words = LinearLayout(this@HomeActivity).apply { orientation = LinearLayout.VERTICAL }
        words.addView(text("ตัดต่อวิดีโอ", 16f, Glass.TEXT, true)); words.addView(text("เลเยอร์ สโลว์ 4K", 11.5f, Glass.TEXT3))
        top.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        val count = runCatching { StudioProjects.list(this@HomeActivity).size }.getOrDefault(0)
        val projects = text(if (count > 0) "โปรเจกต์ $count งาน" else "โปรเจกต์", 12f, Glass.ACCENT).apply {
            gravity = Gravity.CENTER; setPadding(dp(11), 0, dp(11), 0)
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Glass.alpha(Glass.ACCENT, 46)) } }
        top.addView(FrameLayout(this@HomeActivity).apply {
            isClickable = true; contentDescription = "โปรเจกต์วิดีโอ งานตัดต่อที่ทำค้างไว้"; glassTouch()
            addView(projects, FrameLayout.LayoutParams(-2, dp(28), Gravity.CENTER))
            setOnClickListener { Glass.tick(this); startActivity(Intent(this@HomeActivity, StudioProjectsActivity::class.java)) }
        }, -2, dp(48))
        c.addView(top, -1, -2)
        c.addView(View(this@HomeActivity), LinearLayout.LayoutParams(1, 0, 1f))
        c.addView(MiniTimeline(this@HomeActivity), LinearLayout.LayoutParams(dp(218), dp(30)))
        addView(c, -1, -1)
        setOnClickListener { Glass.tick(this); open() }
        cards += this
    }

    /** AI sharpening: one block, with a small photo / video switch that decides what the gallery shows. */
    private fun aiBlock(open: (video: Boolean) -> Unit): View {
        var video = prefs.getBoolean("home_ai_video", false)
        lateinit var self: FrameLayout
        lateinit var refresh: () -> Unit
        val seg = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(2), dp(2), dp(2), dp(2))
            background = GradientDrawable().apply { cornerRadius = dp(13).toFloat(); setColor(0x14ffffff) } }
        val parts = listOf("ภาพ" to false, "วิดีโอ" to true).map { (label, v) ->
            text(label, 11f, Glass.TEXT2).apply { gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(8), 0); isClickable = true
                contentDescription = "แต่งชัด AI แบบ$label"
                setOnClickListener { Glass.tick(this); video = v; prefs.edit().putBoolean("home_ai_video", v).apply(); refresh() } } to v
        }
        parts.forEach { (t, _) -> seg.addView(t, LinearLayout.LayoutParams(-2, dp(22))) }
        refresh = {
            parts.forEach { (t: TextView, v) -> val on = v == video
                t.setTextColor(if (on) Glass.ACCENT else Glass.TEXT2)
                t.background = if (on) GradientDrawable().apply { cornerRadius = dp(11).toFloat(); setColor(Glass.alpha(Glass.ACCENT, 46)) } else null }
            self.contentDescription = "แต่งชัดด้วย AI ${if (video) "วิดีโอ" else "ภาพ"}"
        }
        self = block("แต่งชัดด้วย AI", "", { open(video) }) { c ->
            c.addView(chip(Icons.AI, true), dp(38), dp(38))
            c.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            // Taller hit area than the drawn switch so it is easy to tap.
            c.addView(FrameLayout(this).apply { addView(seg, FrameLayout.LayoutParams(-2, dp(26), Gravity.CENTER)) }, -2, dp(40))
        }
        refresh()
        return self
    }

    private fun modeChip(icon: String, title: String, note: String, open: () -> Unit): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, dp(10), 0); background = cardBg(20)
        isClickable = true; contentDescription = "$title $note"; glassTouch()
        addView(IconView(this@HomeActivity, icon, 1.8f).apply { tint = Glass.TEXT }, dp(20), dp(20))
        addView(text(title, 14f, Glass.TEXT).apply { setPadding(dp(10), 0, 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        setOnClickListener { Glass.tick(this); open() }
        cards += this
    }

    /** Three clips and a playhead: only a picture of the editor, it does nothing on its own. */
    private class MiniTimeline(c: Context) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private val d = c.resources.displayMetrics.density
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(cv: Canvas) {
            val h = height.toFloat(); val y0 = 6 * d; val y1 = h - 2 * d; val rad = 6 * d
            fun clip(x: Float, w: Float, fill: Int) { r.set(x * d, y0, (x + w) * d, y1); p.style = Paint.Style.FILL; p.color = fill; cv.drawRoundRect(r, rad, rad, p) }
            clip(0f, 70f, 0x2effffff); clip(74f, 56f, 0x1fffffff); clip(134f, 84f, Glass.alpha(Glass.ACCENT, 46))
            p.style = Paint.Style.STROKE; p.strokeWidth = d; p.color = Glass.alpha(Glass.ACCENT, 0x80); r.set(134 * d + d / 2, y0 + d / 2, 218 * d - d / 2, y1 - d / 2); cv.drawRoundRect(r, rad, rad, p)
            p.style = Paint.Style.FILL; p.color = Glass.ACCENT; cv.drawRect(104 * d, 0f, 106 * d, h, p)
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
