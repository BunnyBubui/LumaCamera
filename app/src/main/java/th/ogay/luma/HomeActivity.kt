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
        third.addView(photoBlock { pickFor("image", "image") }, LinearLayout.LayoutParams(0, dp(100), 1f))
        third.addView(View(this), dp(10), 1)
        third.addView(aiBlock { video -> if (video) pickFor("video", "ai_video") else pickFor("image", "ai_image") }, LinearLayout.LayoutParams(0, dp(100), 1f))
        col.addView(third, -1, -2)

        // Photo modes as short chips, two per row.
        col.addView(text("โหมดถ่าย", 15f, Glass.TEXT2, true).apply { setPadding(dp(4), dp(24), 0, dp(10)) }, -1, -2)
        listOf(
            listOf(modeCard("PORTRAIT", Icons.PORTRAIT, "บุคคล", 0xffff9a7a, Motif.HALO) { returnMode(PhotoMode.PORTRAIT) },
                modeCard("PRO", Icons.PRO, "โปร", 0xff5fd3c6, Motif.GRID) { returnMode(PhotoMode.PRO) }),
            listOf(modeCard("HIRES", Icons.HIRES, "ความละเอียดสูง", 0xffc9d2e0, Motif.GRID) { returnMode(PhotoMode.HIRES) },
                modeCard("VIDEO", Icons.VIDEO, "วิดีโอ", 0xffff5b50, Motif.NONE) { startActivity(Intent(this, VideoActivity::class.java).putExtra("mode", "VIDEO")) }),
            listOf(modeCard("SLOW", Icons.SLOWMO, "สโลว์โมชัน", 0xff6cc8e8, Motif.STREAK) { startActivity(Intent(this, VideoActivity::class.java).putExtra("mode", "SLOW")) },
                modeCard("NIGHT", Icons.NIGHT, "กลางคืน", 0xffb9a6ff, Motif.DOTS) { returnMode(PhotoMode.NIGHT) }),
        ).forEach { pair ->
            val row = LinearLayout(this)
            row.addView(pair[0], LinearLayout.LayoutParams(0, dp(96), 1f))
            row.addView(View(this), dp(10), 1)
            row.addView(pair[1], LinearLayout.LayoutParams(0, dp(96), 1f))
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

    /** Gallery: the newest photos fill the whole card as a 2 × 2 mosaic (grey tiles until they load, or without permission). */
    private fun galleryBlock(): View {
        val shades = intArrayOf(0xff5f5f66.toInt(), 0xff6b5a46.toInt(), 0xff55604a.toInt(), 0xff6b4f46.toInt())
        val tiles = (0 until 4).map { i -> ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(shades[i], Theme.mix(shades[i], 0xff000000.toInt(), 0.6f)))
        } }
        return toolCard(0xff5f5f66.toInt(), "แกลเลอรี", "แกลเลอรี รูปและวิดีโอทั้งหมด", {
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
    private fun filmBlock(): View = toolCard(0xffe8b04a.toInt(), "ฟิล์ม", "ฟิล์ม โทนสีและเกรนฟิล์ม", { returnMode(PhotoMode.FILM) }) {
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
        val coral = 0xffff6f91.toInt()
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
    private fun photoBlock(open: () -> Unit): View = toolCard(0xffa6e05a.toInt(), "ตัดต่อภาพ", "ตัดต่อภาพ สี เส้นโค้ง ครอป", open) {
        addView(SlidersArt(this@HomeActivity, 0xffa6e05a.toInt()), FrameLayout.LayoutParams(-1, -1))
        putChip(EditorKit.ADJUST, 0xffa6e05a.toInt(), Gravity.TOP or Gravity.START)
    }

    /** AI sharpening: magenta light with faint sparkles and a small photo / video switch that decides what the gallery shows. */
    private fun aiBlock(open: (video: Boolean) -> Unit): View {
        val mg = 0xffe58ae0.toInt()
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
                t.setTextColor(if (on) mg else Glass.TEXT2)
                t.background = if (on) GradientDrawable().apply { cornerRadius = dp(11).toFloat(); setColor(Glass.alpha(mg, 46)) } else null }
            self.contentDescription = "แต่งชัดด้วย AI ${if (video) "วิดีโอ" else "ภาพ"}"
        }
        self = toolCard(mg, "แต่งชัดด้วย AI", "แต่งชัดด้วย AI", { open(video) }) {
            for ((x, y, sz, a) in listOf(listOf(100, 36, 30, 0x2e), listOf(140, 62, 16, 0x38), listOf(84, 66, 12, 0x2e)))
                addView(IconView(this@HomeActivity, Icons.AI, 1.6f).apply { tint = Glass.alpha(mg, a) }, FrameLayout.LayoutParams(dp(sz), dp(sz), Gravity.TOP or Gravity.START).apply { leftMargin = dp(x); topMargin = dp(y) })
            putChip(Icons.AI, mg, Gravity.TOP or Gravity.START)
            addView(FrameLayout(this@HomeActivity).apply { addView(seg, FrameLayout.LayoutParams(-2, dp(26), Gravity.CENTER)) },
                FrameLayout.LayoutParams(-2, dp(40), Gravity.TOP or Gravity.END).apply { topMargin = dp(8); rightMargin = dp(10) })
        }
        refresh()
        return self
    }

    /** Light patterns drawn over a mode card's glow. */
    private enum class Motif { NONE, GRID, DOTS, STREAK, HALO }

    /**
     * A mode card with its own backdrop: a dark base, a soft light in the mode's colour, a faint pattern, and the
     * mode icon large and faint in the corner. The colours belong to the mode, not to the theme.
     */
    private fun modeCard(key: String, icon: String, title: String, hue: Long, motif: Motif, open: () -> Unit): View = FrameLayout(this).apply {
        val t = hue.toInt()
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
