package th.ogay.luma

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.math.cos
import kotlin.math.sin

/**
 * Home: the main parts of Lumora in one place, opened from the top-left button of the photo screen (the app
 * itself still opens straight into the camera). Photo modes return to the camera in that mode; the editors
 * open Lumora's gallery to choose what to edit.
 */
@androidx.annotation.OptIn(markerClass = [androidx.camera.camera2.interop.ExperimentalCamera2Interop::class])
class HomeActivity : ComponentActivity() {
    companion object {
        /** Result extra: the photo mode the camera should switch to. */
        const val RESULT_MODE = "mode"
        const val HOUSE = "M3.5 11 12 4l8.5 7 M6 9.5V20h4.5v-5.5h3V20H18V9.5"
        const val BACK_SMALL = "M14.5 6.5 9 12l5.5 5.5"
    }

    private class Tile(val icon: String, val title: String, val note: String, val color: Int, val open: () -> Unit)

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

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val host = FrameLayout(this)
        installRoot(host)
        host.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xff131a38.toInt(), 0xff0b0f1e.toInt(), 0xff140d26.toInt()))
        val aurora = Aurora(this)
        host.addView(aurora, -1, -1)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(32)) }
        // The page scrolls under the status and navigation bars instead of being cut off at a hard line below them:
        // the lights fill the whole screen, the list keeps its insets as padding, and a soft shade sits under the
        // status bar so the clock stays readable over the cards.
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipToPadding = false; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS; addView(col) }
        host.addView(scroll, -1, -1)
        val shade = View(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xf2131a38.toInt(), 0xa0131a38.toInt(), 0x00131a38)) }
        host.addView(shade, FrameLayout.LayoutParams(-1, dp(40)))
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(host) { _, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            host.setPadding(0, 0, 0, 0)
            scroll.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            shade.layoutParams = (shade.layoutParams as FrameLayout.LayoutParams).apply { height = bars.top + dp(28) }
            insets
        }

        // Header: back to the camera, the mark drawing itself, and the name.
        col.addView(FrameLayout(this).apply { background = glass(24, 0x33ffffff); isClickable = true; contentDescription = "กลับไปที่กล้อง"; glassTouch()
            addView(IconView(this@HomeActivity, Icons.CLOSE, 2f), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)); setOnClickListener { Glass.tick(this); close() } },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        val brand = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(4)) }
        val logo = LumoraLogoView(this).apply { progress = if (Glass.reduced(this@HomeActivity)) 1f else 0f }
        brand.addView(logo, dp(66), dp(66))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        val title = text("Lumora", 31f, Color.WHITE, true)
        names.addView(title); names.addView(text("เลือกสิ่งที่อยากทำ", 14f, Glass.TEXT2))
        brand.addView(names)
        col.addView(brand, -1, -2)

        val cards = ArrayList<View>()
        // One orange card for the camera; everything else is the same quiet glass card.
        col.addView(big(Icons.PHOTO, "ถ่ายภาพ", "กลับไปที่กล้อง", intArrayOf(0xffffb020.toInt(), 0xffff8a1f.toInt()), 104, 24f) {
            setResult(RESULT_OK, Intent().putExtra(RESULT_MODE, PhotoMode.PHOTO.name)); close() }.also { cards += it }, LinearLayout.LayoutParams(-1, dp(104)).apply { topMargin = dp(14) })
        fun pickFor(kind: String, ek: String) { editKind = ek; pick.launch(Intent(this, GalleryActivity::class.java).putExtra(GalleryActivity.EXTRA_PICK, kind)) }
        section(col, "ตัดต่อ")
        grid(col, cards, listOf(
            Tile(EditorKit.ADJUST, "ตัดต่อภาพ", "สี เส้นโค้ง ครอป", 0xffff6fb5.toInt()) { pickFor("image", "image") },
            Tile(EditorKit.SCISSORS, "ตัดต่อวิดีโอ", "เลเยอร์ สโลว์ 4K", 0xff4dc3ff.toInt()) { pickFor("video", "video") },
            Tile(Icons.AI, "แต่งภาพชัด AI", "×2 ถึง ×4", 0xff2dd4bf.toInt()) { pickFor("image", "ai_image") },
            Tile(Icons.AI, "แต่งวิดีโอชัด AI", "เลือกโมเดล ส่งออก", 0xffa78bfa.toInt()) { pickFor("video", "ai_video") },
            Tile(Icons.FILM, "ฟิล์ม", "โทนสีและเกรนฟิล์ม", 0xffffb020.toInt()) { setResult(RESULT_OK, Intent().putExtra(RESULT_MODE, PhotoMode.FILM.name)); close() },
            Tile(Icons.STACK, "โปรเจกต์วิดีโอ", "งานตัดต่อที่ทำค้างไว้", 0xff4dc3ff.toInt()) { startActivity(Intent(this, StudioProjectsActivity::class.java)) }))

        section(col, "โหมดถ่าย")
        grid(col, cards, listOf(
            mode(Icons.PORTRAIT, "บุคคล", "หน้าชัด หลังเบลอ", 0xffffb020.toInt(), PhotoMode.PORTRAIT),
            mode(Icons.FLASH, "ถ่ายเร็ว", "จับจังหวะที่ขยับเร็ว", 0xff9b7bff.toInt(), PhotoMode.SPORT),
            mode(Icons.PRO, "โปร", "ปรับ ISO ชัตเตอร์ โฟกัส", 0xff4dc3ff.toInt(), PhotoMode.PRO),
            mode(Icons.HIRES, "ความละเอียดสูง", "ใช้เซนเซอร์เต็มที่", 0xff34d399.toInt(), PhotoMode.HIRES),
            Tile(Icons.VIDEO, "วิดีโอ", "ถ่ายวิดีโอ", 0xffff453a.toInt()) { startActivity(Intent(this, VideoActivity::class.java).putExtra("mode", "VIDEO")) },
            mode(Icons.PHOTO, "ภาพถ่าย", "ถ่ายยาวหลายวินาทีได้", 0xffff6fb5.toInt(), PhotoMode.PHOTO)))
        section(col, "คลังภาพ")
        grid(col, cards, listOf(
            Tile(Icons.LIBRARY, "แกลเลอรี", "รูปและวิดีโอทั้งหมด", 0xffff9f0a.toInt()) { startActivity(Intent(this, GalleryActivity::class.java).putExtra("from", "home")) },
            Tile(Icons.AI, "ภาพจาก AI", "รูปที่ Lumora ปรับแล้ว", 0xff2dd4bf.toInt()) { startActivity(Intent(this, GalleryActivity::class.java).putExtra("tab", "luma").putExtra("from", "home")) }))

        // Entrance: the mark draws, the name settles, the cards rise one after another.
        if (!Glass.reduced(this)) {
            ValueAnimator.ofFloat(0f, 1f).apply { duration = 1100; startDelay = 100; addUpdateListener { logo.progress = it.animatedValue as Float } }.start()
            title.alpha = 0f; title.translationX = -dp(12).toFloat(); title.animate().alpha(1f).translationX(0f).setStartDelay(500).setDuration(500).setInterpolator(Glass.EASE).start()
            cards.forEachIndexed { i, v -> v.alpha = 0f; v.translationY = dp(26).toFloat(); v.scaleX = 0.96f; v.scaleY = 0.96f
                v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setStartDelay(180L + i * 45L).setDuration(460).setInterpolator(Glass.SPRING).start() }
        }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) { override fun handleOnBackPressed() = close() })
    }

    private fun close() { finish(); @Suppress("DEPRECATION") overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out) }

    private fun mode(icon: String, title: String, note: String, color: Int, m: PhotoMode) = Tile(icon, title, note, color) {
        setResult(RESULT_OK, Intent().putExtra(RESULT_MODE, m.name)); close()
    }

    /** A large gradient card with a soft light sweeping across it. */
    private fun big(icon: String, title: String, note: String, colors: IntArray, height: Int, size: Float, stacked: Boolean = false, open: () -> Unit): View = FrameLayout(this).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { cornerRadius = dp(26).toFloat() }
        isClickable = true; contentDescription = "$title $note"; glassTouch(); clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(26).toFloat()) }
        val badge = FrameLayout(this@HomeActivity).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x38ffffff) }
            addView(IconView(this@HomeActivity, icon, 2.2f), FrameLayout.LayoutParams(dp(if (stacked) 24 else 28), dp(if (stacked) 24 else 28), Gravity.CENTER)) }
        val words = LinearLayout(this@HomeActivity).apply { orientation = LinearLayout.VERTICAL }
        words.addView(text(title, size, Color.WHITE, true)); words.addView(text(note, 12.5f, 0xe6ffffff.toInt()).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        if (stacked) {
            val c = LinearLayout(this@HomeActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(14), dp(14)) }
            c.addView(badge, dp(48), dp(48)); c.addView(View(this@HomeActivity), LinearLayout.LayoutParams(1, 0, 1f)); c.addView(words)
            addView(c, -1, -1)
        } else {
            val row = LinearLayout(this@HomeActivity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), 0, dp(18), 0) }
            row.addView(badge, dp(if (height > 100) 60 else 50), dp(if (height > 100) 60 else 50))
            row.addView(words.apply { setPadding(dp(14), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(IconView(this@HomeActivity, "M9 5l7 7-7 7", 2.4f), dp(22), dp(22))
            addView(row, -1, -1)
        }
        setOnClickListener { Glass.tick(this); open() }
    }

    private fun section(col: LinearLayout, name: String) =
        col.addView(text(name, 15f, Glass.TEXT2, true).apply { setPadding(dp(4), dp(24), 0, dp(10)) }, -1, -2)

    /** Two cards per row. */
    private fun grid(col: LinearLayout, cards: MutableList<View>, tiles: List<Tile>) {
        tiles.chunked(2).forEach { pair ->
            val row = LinearLayout(this)
            pair.forEachIndexed { i, tile ->
                val card = card(tile); cards += card
                row.addView(card, LinearLayout.LayoutParams(0, dp(96), 1f).apply { if (i == 0) rightMargin = dp(6) else leftMargin = dp(6) })
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(6) })
            col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
    }

    /** Quiet glass card: a faint tinted icon chip, title, one line of note and a small arrow. */
    private fun card(t: Tile): View = FrameLayout(this).apply {
        background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(0x14ffffff); setStroke(dp(1), 0x1fffffff) }
        isClickable = true; contentDescription = "${t.title} ${t.note}"; glassTouch()
        val c = LinearLayout(this@HomeActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(13), dp(12), dp(12)) }
        val top = LinearLayout(this@HomeActivity).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(FrameLayout(this@HomeActivity).apply {
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor((t.color and 0x00ffffff) or 0x2e000000) }
            addView(IconView(this@HomeActivity, t.icon, 2f).apply { tint = t.color }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        }, dp(38), dp(38))
        top.addView(View(this@HomeActivity), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(IconView(this@HomeActivity, "M9.5 6l6 6-6 6", 2f).apply { tint = 0x55ffffff }, dp(16), dp(16))
        c.addView(top, -1, -2)
        c.addView(View(this@HomeActivity), LinearLayout.LayoutParams(1, 0, 1f))
        c.addView(text(t.title, 15f, Color.WHITE, true).apply { maxLines = 1 })
        c.addView(text(t.note, 11.5f, Glass.TEXT3).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        addView(c, -1, -1)
        setOnClickListener { Glass.tick(this); t.open() }
    }

    private fun View.outlineAmbientShadowColor(c: Int) { if (android.os.Build.VERSION.SDK_INT >= 28) { outlineAmbientShadowColor = c; outlineSpotShadowColor = c } }

    /**
     * Slow coloured lights drifting behind everything. The four gradients are made once and only moved and scaled
     * with a matrix each frame (no new shader per frame), and redraws are capped at about 30 per second: the lights
     * move far too slowly to need more, and it leaves the frame budget for scrolling.
     */
    private class Aurora(c: Context) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var t = 0f
        private val colors = intArrayOf(0x30ff9f0a, 0x308e7dff, 0x2064d2ff, 0x20ff5e8a)
        private val shaders = colors.map { RadialGradient(0f, 0f, 100f, it, 0x00000000, Shader.TileMode.CLAMP) }
        private val m = android.graphics.Matrix()
        private var lastFrame = 0L
        private val anim = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply { duration = 24_000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { val now = android.os.SystemClock.uptimeMillis(); if (now - lastFrame >= 32) { lastFrame = now; t = it.animatedValue as Float; invalidate() } } }
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); if (!Glass.reduced(context)) anim.start() }
        override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
        override fun onDraw(cv: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); if (w <= 0f) return
            shaders.forEachIndexed { i, sh ->
                val a = t + i * 1.6f
                val x = w * (0.5f + 0.42f * cos(a * (0.7f + i * 0.13f)))
                val y = h * (0.25f + 0.2f * i + 0.08f * sin(a * 1.1f))
                val r = w * (0.55f + 0.1f * sin(a + i))
                m.setScale(r / 100f, r / 100f); m.postTranslate(x, y); sh.setLocalMatrix(m)
                p.shader = sh
                cv.drawCircle(x, y, r, p)
            }
        }
    }

    /** A soft diagonal light that passes over a big card every few seconds. */
    private class Sheen(c: Context) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var x = -1f
        private val anim = ValueAnimator.ofFloat(-0.6f, 1.6f).apply { duration = 2600; startDelay = 900; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.RESTART
            addUpdateListener { x = it.animatedValue as Float; invalidate() } }
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); if (!Glass.reduced(context)) anim.start() }
        override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
        private var shader: LinearGradient? = null; private var forW = 0; private var forH = 0
        private val m = android.graphics.Matrix()
        override fun onDraw(cv: Canvas) {
            if (x !in -0.5f..1.5f) return
            val w = width.toFloat(); val cx = x * w
            // The gradient is built once per size; each frame only slides it across.
            if (shader == null || forW != width || forH != height) { forW = width; forH = height
                shader = LinearGradient(-w * 0.25f, 0f, w * 0.25f, height.toFloat(), intArrayOf(0x00ffffff, 0x33ffffff, 0x00ffffff), null, Shader.TileMode.CLAMP) }
            m.setTranslate(cx, 0f); shader!!.setLocalMatrix(m); p.shader = shader
            cv.drawRect(0f, 0f, w, height.toFloat(), p)
        }
    }
}
