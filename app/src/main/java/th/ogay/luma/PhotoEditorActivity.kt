package th.ogay.luma

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Photo editor: crop and rotate, light and colour, film looks and a pen. Nothing touches the original; "บันทึก"
 * writes a new JPEG next to it in Pictures/Lumora with the camera EXIF carried over, and it joins the Lumora list.
 *
 * Every edit is kept as numbers (turns, crop rectangle, adjustment values, look, pen strokes), so the preview and
 * the saved file are drawn by the same code at two sizes.
 */
class PhotoEditorActivity : ComponentActivity() {
    companion object {
        const val RESULT_ID = "id"
        fun intent(c: Context, uri: Uri): Intent = Intent(c, PhotoEditorActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        private val ASPECTS = listOf("อิสระ" to 0f, "ต้นฉบับ" to -1f, "1:1" to 1f, "4:3" to 4f / 3, "3:4" to 3f / 4, "16:9" to 16f / 9, "9:16" to 9f / 16)
        private val COLORS = intArrayOf(Color.WHITE, Color.BLACK, 0xffff453a.toInt(), 0xffff9f0a.toInt(), 0xffffd60a.toInt(), 0xff30d158.toInt(), 0xff0a84ff.toInt(), 0xffbf5af2.toInt())
        private val SIZES = listOf("เล็ก" to 0.006f, "กลาง" to 0.012f, "ใหญ่" to 0.024f)
    }
    /** A pen stroke in image coordinates (0..1 of the turned picture); [width] is a share of the shorter side. */
    class Stroke(val color: Int, val width: Float, val points: MutableList<PointF> = mutableListOf())

    // ---- Edit state ----
    private var turns = 0; private var flipped = false
    private val crop = RectF(0f, 0f, 1f, 1f); private var aspect = 0
    private val values = FloatArray(EditorKit.ADJUSTMENTS.size); private var adjustIndex = 0
    private var film: Film? = null; private var filmStrength = 1f
    private val strokes = mutableListOf<Stroke>(); private var penColor = COLORS[2]; private var penSize = 1

    private lateinit var canvasView: EditCanvas
    private lateinit var tools: FrameLayout
    private val tabs = mutableListOf<LinearLayout>()
    private var tool = 0
    private var exif = ExifInterface.ORIENTATION_NORMAL
    private var saving = false
    private lateinit var saveButton: View

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val uri = intent.data ?: run { finish(); return }
        state?.let { restore(it) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        installRoot(root)
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(6)) }
        bar.addView(EditorKit.round(this, Icons.CLOSE, "ยกเลิกการแก้ไข") { leave() }, dp(48), dp(48))
        bar.addView(text("แก้ไขภาพ", 19f, Color.WHITE, true).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        // Hold to see the original, release to return to the edit.
        bar.addView(FrameLayout(this).apply {
            background = glass(24, 0x40ffffff); contentDescription = "กดค้างเพื่อดูต้นฉบับ"; isClickable = true
            addView(IconView(this@PhotoEditorActivity, EditorKit.COMPARE, 2f), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            setOnTouchListener { v, e -> when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { Glass.tick(v); canvasView.original = true; v.isPressed = true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { canvasView.original = false; v.isPressed = false; if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick() }
            }; true }
            setOnClickListener { }
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(8) })
        saveButton = EditorKit.primary(this, "บันทึก") { save(uri) }
        bar.addView(saveButton, LinearLayout.LayoutParams(-2, dp(48)))
        root.addView(bar, -1, -2)

        canvasView = EditCanvas(this)
        root.addView(canvasView, LinearLayout.LayoutParams(-1, 0, 1f))
        tools = FrameLayout(this)
        root.addView(tools, LinearLayout.LayoutParams(-1, dp(150)))
        val tabRow = LinearLayout(this).apply { setPadding(dp(8), 0, dp(8), dp(8)) }
        listOf(EditorKit.CROP to "ครอบตัด", EditorKit.ADJUST to "ปรับ", Icons.FILM to "ฟิลเตอร์", EditorKit.PEN to "วาด").forEachIndexed { i, (icon, name) ->
            val t = EditorKit.tab(this, icon, name) { showTool(i) }; tabs += t; tabRow.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(tabRow, -1, -2)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = leave() })

        luma.readIo.execute {
            val loaded = runCatching { load(uri, 2_400_000) }.getOrNull()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (loaded == null) { notice("เปิดภาพนี้ไม่ได้"); finish(); return@runOnUiThread }
                canvasView.bitmap = loaded; canvasView.refresh(); showTool(tool)
            }
        }
    }

    private fun changed() = turns != 0 || flipped || crop != RectF(0f, 0f, 1f, 1f) || values.any { it != 0f } || film != null || strokes.isNotEmpty()
    private fun leave() {
        if (saving) return
        if (!changed()) { finish(); return }
        GlassDialog.panel(this, "ทิ้งการแก้ไข?") { col, d ->
            col.addView(text("การแก้ไขที่ยังไม่บันทึกจะหายไป ต้นฉบับยังอยู่ครบ", 13.5f, Glass.TEXT2).apply { maxLines = 4 })
            col.addView(GlassDialog.actions(this, "แก้ต่อ" to { d.dismiss() }, "ทิ้ง" to { d.dismiss(); finish() }), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        }
    }

    // ---------- Tools ----------
    private fun showTool(i: Int) {
        tool = i; tabs.forEachIndexed { k, t -> EditorKit.select(t, k == i) }
        canvasView.mode = i; canvasView.refresh()
        tools.removeAllViews()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        tools.addView(col, -1, -1)
        when (i) {
            0 -> cropTools(col)
            1 -> adjustTools(col)
            2 -> filmTools(col)
            else -> penTools(col)
        }
        if (!Glass.reduced(this)) { col.alpha = 0f; col.translationY = dp(10).toFloat(); col.animate().alpha(1f).translationY(0f).setInterpolator(Glass.EASE).setDuration(220).start() }
    }

    private fun cropTools(col: LinearLayout) {
        val chips = mutableListOf<LinearLayout>()
        ASPECTS.forEachIndexed { k, (name, _) -> chips += EditorKit.chip(this, name, aspectIconPath(name), k == aspect) { aspect = k; chips.forEachIndexed { j, c -> EditorKit.mark(c, j == k) }; fitAspect(); canvasView.refresh() } }
        col.addView(EditorKit.row(this, chips), -1, -2)
        col.addView(EditorKit.row(this, listOf(
            EditorKit.chip(this, "หมุน 90°", EditorKit.ROTATE) { turn() },
            EditorKit.chip(this, "กลับด้าน", Icons.MIRROR) { flip() },
            EditorKit.chip(this, "คืนค่า", Icons.RESET) { turns = 0; flipped = false; crop.set(0f, 0f, 1f, 1f); aspect = 0; showTool(0) })), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }
    /** Turn a quarter clockwise. A turn of a mirrored picture equals a mirror of a picture turned the other way. */
    private fun turn() {
        turns = ((if (flipped) turns - 1 else turns + 1) + 4) % 4
        crop.set(1f - crop.bottom, crop.left, 1f - crop.top, crop.right)
        strokes.forEach { s -> s.points.forEach { p -> val u = p.x; p.x = 1f - p.y; p.y = u } }
        val r = ASPECTS[aspect].second; if (r > 0f) { aspect = ASPECTS.indexOfFirst { abs(it.second - 1f / r) < 0.01f }.takeIf { it >= 0 } ?: 0; showTool(0) }
        canvasView.refresh()
    }
    private fun flip() {
        flipped = !flipped
        crop.set(1f - crop.right, crop.top, 1f - crop.left, crop.bottom)
        strokes.forEach { s -> s.points.forEach { p -> p.x = 1f - p.x } }
        canvasView.refresh()
    }
    /** Largest centred crop with the chosen shape. */
    private fun fitAspect() {
        val r = ASPECTS[aspect].second; if (r == 0f) return
        val (w, h) = canvasView.turnedSize(); if (w == 0 || h == 0) return
        val want = if (r < 0f) w.toFloat() / h else r
        // Width and height as shares of the picture, for a crop whose pixel shape is [want].
        var cw = 1f; var ch = w / want / h
        if (ch > 1f) { ch = 1f; cw = h * want / w }
        crop.set((1f - cw) / 2, (1f - ch) / 2, (1f + cw) / 2, (1f + ch) / 2)
    }

    private fun adjustTools(col: LinearLayout) {
        val slider = GlassSlider(this).apply {
            values = (-100..100).map { if (it > 0) "+$it" else "$it" }; grad = intArrayOf(0xff8e8e93.toInt(), Color.WHITE, Glass.ACCENT)
            contentDescription = EditorKit.ADJUSTMENTS[adjustIndex]
        }
        val chips = mutableListOf<LinearLayout>()
        fun pick(k: Int) { adjustIndex = k; chips.forEachIndexed { j, c -> EditorKit.mark(c, j == k) }; slider.index = (this.values[k] * 100).toInt() + 100; slider.contentDescription = EditorKit.ADJUSTMENTS[k] }
        EditorKit.ADJUSTMENTS.forEachIndexed { k, name -> chips += EditorKit.chip(this, name, EditorKit.ADJUST_ICONS[k], k == adjustIndex) { pick(k) } }
        slider.onChange = { idx -> values[adjustIndex] = (idx - 100) / 100f; canvasView.refresh() }
        col.addView(EditorKit.row(this, chips), -1, -2)
        col.addView(slider, LinearLayout.LayoutParams(-1, dp(56)).apply { setMargins(dp(12), dp(8), dp(12), 0) })
        pick(adjustIndex)
    }

    private fun filmTools(col: LinearLayout) {
        val thumb = canvasView.bitmap?.let { b -> val s = 132f / max(b.width, b.height); Bitmap.createScaledBitmap(b, max(1, (b.width * s).toInt()), max(1, (b.height * s).toInt()), true) }
        val row = LinearLayout(this).apply { gravity = Gravity.BOTTOM; setPadding(dp(12), 0, dp(12), 0) }
        val cards = mutableListOf<Pair<Film?, View>>()
        val strength = GlassSlider(this).apply { values = (0..100).map { "$it%" }; index = (filmStrength * 100).toInt(); grad = intArrayOf(0xff8e8e93.toInt(), Glass.ACCENT); contentDescription = "ความเข้มฟิลเตอร์" }
        fun mark() { cards.forEach { (f, v) -> val on = f?.id == film?.id; v.background = glass(14, if (on) Glass.ACCENT else 0x00000000); v.isSelected = on }; strength.visibility = if (film == null) View.INVISIBLE else View.VISIBLE }
        fun card(f: Film?, group: String?): View = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; isClickable = true; contentDescription = "ฟิลเตอร์ ${f?.name ?: "ไม่มี"}"
            addView(text(group ?: "", 10.5f, Glass.TEXT3, true).apply { setPadding(dp(2), 0, 0, dp(2)) }, dp(64), -2)
            val frame = FrameLayout(this@PhotoEditorActivity).apply { setPadding(dp(2), dp(2), dp(2), dp(2)) }
            val img = ImageView(this@PhotoEditorActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(thumb); clipToOutline = true; background = glass(12, 0x22ffffff)
                if (f != null) colorFilter = ColorMatrixColorFilter(Films.matrix(f)) }
            frame.addView(img, -1, -1); addView(frame, dp(64), dp(64)); cards += f to frame
            addView(text(f?.name ?: "ไม่มี", 10.5f, Color.WHITE).apply { gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0) }, dp(66), -2)
            glassTouch(); setOnClickListener { Glass.tick(this); film = f; if (f != null && filmStrength == 0f) { filmStrength = 1f; strength.index = 100 }; mark(); canvasView.refresh() }
        }
        row.addView(card(null, null), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        Films.groups.forEach { g -> Films.inGroup(g).forEachIndexed { k, f -> row.addView(card(f, if (k == 0) g else null), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) }) } }
        strength.onChange = { i -> filmStrength = i / 100f; canvasView.refresh() }
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }, -1, -2)
        col.addView(strength, LinearLayout.LayoutParams(-1, dp(40)).apply { setMargins(dp(12), dp(2), dp(12), 0) })
        mark()
    }

    private fun penTools(col: LinearLayout) {
        val swatches = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), 0) }
        val dots = mutableListOf<View>()
        fun markDots() = dots.forEachIndexed { k, v -> v.background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(COLORS[k]); setStroke(dp(if (COLORS[k] == penColor) 3 else 1), if (COLORS[k] == penColor) Glass.ACCENT else 0x66ffffff) }; v.isSelected = COLORS[k] == penColor }
        COLORS.forEachIndexed { k, color ->
            val v = View(this).apply { isClickable = true; contentDescription = "สีปากกา ${k + 1}"; setOnClickListener { Glass.tick(this); penColor = color; markDots() } }
            dots += v; swatches.addView(v, LinearLayout.LayoutParams(dp(34), dp(34)).apply { setMargins(dp(5), dp(7), dp(5), dp(7)) })
        }
        markDots()
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(swatches) }, -1, -2)
        val sizeChips = mutableListOf<LinearLayout>()
        SIZES.forEachIndexed { k, (name, _) -> sizeChips += EditorKit.chip(this, name, "", k == penSize) { penSize = k; sizeChips.forEachIndexed { j, c -> EditorKit.mark(c, j == k) } } }
        col.addView(EditorKit.row(this, sizeChips + listOf(
            EditorKit.chip(this, "ย้อน", EditorKit.UNDO) { if (strokes.isNotEmpty()) { strokes.removeAt(strokes.lastIndex); canvasView.refresh() } },
            EditorKit.chip(this, "ล้าง", Icons.RESET) { strokes.clear(); canvasView.refresh() })), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }

    // ---------- Pixels ----------
    /** Decodes the picture at most [maxPixels] in size; the EXIF turn is kept aside and applied when drawing. */
    private fun load(uri: Uri, maxPixels: Long): Bitmap? {
        exif = runCatching { contentResolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var s = 1; while (bounds.outWidth.toLong() * bounds.outHeight / (s.toLong() * s) > maxPixels) s *= 2
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s; inPreferredConfig = Bitmap.Config.ARGB_8888 }) }
    }

    /** Matrix from decoded pixels to the turned picture, and that picture's size: first the EXIF turn, then the editor's. */
    fun orient(w: Int, h: Int): Pair<Matrix, Point> {
        val m = Matrix()
        when (exif) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        }
        m.postRotate(turns * 90f); if (flipped) m.postScale(-1f, 1f)
        val r = RectF(0f, 0f, w.toFloat(), h.toFloat()); m.mapRect(r); m.postTranslate(-r.left, -r.top)
        return m to Point(r.width().toInt(), r.height().toInt())
    }
    fun colour(): ColorMatrix = EditorKit.film(film, filmStrength).apply { postConcat(EditorKit.adjust(values)) }

    /** Draws the finished picture: turned, cropped, coloured, vignette and pen strokes. Shared by preview thumbnails and saving. */
    private fun render(src: Bitmap): Bitmap {
        val (m, size) = orient(src.width, src.height)
        val left = crop.left * size.x; val top = crop.top * size.y
        val w = max(1, (crop.width() * size.x).toInt()); val h = max(1, (crop.height() * size.y).toInt())
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); m.postTranslate(-left, -top)
        c.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(colour()) })
        drawVignette(c, RectF(0f, 0f, w.toFloat(), h.toFloat()), values.last())
        val shorter = min(size.x, size.y).toFloat()
        drawStrokes(c, strokes, { x -> x * size.x - left }, { y -> y * size.y - top }, shorter)
        return out
    }

    private fun save(uri: Uri) {
        if (saving || canvasView.bitmap == null) return
        if (!changed()) { notice("ยังไม่มีการแก้ไข"); return }
        saving = true; saveButton.alpha = 0.5f; notice("กำลังบันทึก…")
        luma.fileIo.execute {
            val time = System.currentTimeMillis(); val id = UUID.randomUUID().toString()
            val file = File(cacheDir, "edit-$id.jpg")
            val result = runCatching {
                val full = load(uri, 16_000_000) ?: error("อ่านภาพไม่ได้")
                val out = render(full); full.recycle()
                PhotoEffects.write(out, file, 95)
                val dims = Dimensions(out.width, out.height); out.recycle()
                ExifCarry.write(file, ExifCarry.read(this, uri) + (ExifInterface.TAG_SOFTWARE to "Lumora Editor"))
                runCatching { ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, "${ExifInterface.ORIENTATION_NORMAL}"); saveAttributes() } }
                val published = PhotoMedia.publish(this, file, "Lumora_EDIT_${time}.jpg", time)
                luma.shots.insert(Shot(id, time, published, dims, 1f, true, null, null, Decision.OFF, Stage.ORIGINAL, null, 0, null, false))
                id
            }
            file.delete()
            runOnUiThread {
                saving = false; saveButton.alpha = 1f
                result.onSuccess { notice("บันทึกเป็นภาพใหม่แล้ว · ต้นฉบับยังอยู่"); setResult(RESULT_OK, Intent().putExtra(RESULT_ID, it)); finish() }
                    .onFailure { notice("บันทึกไม่สำเร็จ · ${it.localizedMessage}") }
            }
        }
    }

    // ---------- State across recreation ----------
    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("turns", turns); out.putBoolean("flipped", flipped); out.putFloatArray("crop", floatArrayOf(crop.left, crop.top, crop.right, crop.bottom))
        out.putInt("aspect", aspect); out.putFloatArray("values", values); out.putString("film", film?.id); out.putFloat("strength", filmStrength); out.putInt("tool", tool)
        out.putIntArray("sc", strokes.map { it.color }.toIntArray()); out.putFloatArray("sw", strokes.map { it.width }.toFloatArray())
        out.putFloatArray("sp", strokes.flatMap { s -> listOf(s.points.size.toFloat()) + s.points.flatMap { listOf(it.x, it.y) } }.toFloatArray())
    }
    private fun restore(s: Bundle) {
        turns = s.getInt("turns"); flipped = s.getBoolean("flipped"); s.getFloatArray("crop")?.let { crop.set(it[0], it[1], it[2], it[3]) }
        aspect = s.getInt("aspect"); s.getFloatArray("values")?.copyInto(values); film = Films.byId(s.getString("film")); filmStrength = s.getFloat("strength", 1f); tool = s.getInt("tool")
        val colors = s.getIntArray("sc") ?: return; val widths = s.getFloatArray("sw") ?: return; val pts = s.getFloatArray("sp") ?: return
        var k = 0
        for (i in colors.indices) { val n = pts[k++].toInt(); val st = Stroke(colors[i], widths[i]); repeat(n) { st.points += PointF(pts[k], pts[k + 1]); k += 2 }; strokes += st }
    }

    // ---------- Preview ----------
    /**
     * Shows the edit. In the crop tool the whole turned picture is shown with the crop frame on top (drag a corner,
     * an edge or the middle); in the other tools only the cropped part is shown, filling the space. The pen draws
     * in the pen tool.
     */
    inner class EditCanvas(context: Context) : View(context) {
        var bitmap: Bitmap? = null
        var mode = 0
        var original = false; set(v) { field = v; invalidate() }
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val dim = Paint().apply { color = 0x99000000.toInt() }
        private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
        private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x66ffffff }
        private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
        /** Where the whole turned picture lands on screen, and the visible clip. */
        private val image = RectF(); private val clip = RectF()
        private val draw = Matrix()

        init { contentDescription = "ภาพที่กำลังแก้ไข" }
        fun turnedSize(): Pair<Int, Int> { val b = bitmap ?: return 0 to 0; val (_, s) = orient(b.width, b.height); return s.x to s.y }
        fun refresh() { paint.colorFilter = ColorMatrixColorFilter(colour()); layoutImage(); invalidate() }

        private fun layoutImage() {
            val b = bitmap ?: return; if (width == 0) return
            val (m, s) = orient(b.width, b.height)
            val pad = dp(if (mode == 0) 28 else 12).toFloat()
            val aw = width - 2 * pad; val ah = height - 2 * pad
            if (mode == 0) {
                val k = min(aw / s.x, ah / s.y); val w = s.x * k; val h = s.y * k
                image.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2); clip.set(image)
            } else {
                val cw = crop.width() * s.x; val ch = crop.height() * s.y
                val k = min(aw / cw, ah / ch); val w = cw * k; val h = ch * k
                clip.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
                image.set(clip.left - crop.left * s.x * k, clip.top - crop.top * s.y * k, 0f, 0f)
                image.right = image.left + s.x * k; image.bottom = image.top + s.y * k
            }
            draw.set(m); draw.postScale(image.width() / s.x, image.height() / s.y); draw.postTranslate(image.left, image.top)
        }
        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { layoutImage() }

        private fun cropRect() = RectF(image.left + crop.left * image.width(), image.top + crop.top * image.height(), image.left + crop.right * image.width(), image.top + crop.bottom * image.height())

        override fun onDraw(c: Canvas) {
            val b = bitmap ?: return
            c.save(); c.clipRect(clip)
            paint.colorFilter = if (original) null else ColorMatrixColorFilter(colour())
            c.drawBitmap(b, draw, paint)
            if (!original) {
                val visible = if (mode == 0) cropRect() else clip
                drawVignette(c, visible, values.last())
                val (sw, sh) = turnedSize()
                drawStrokes(c, strokes, { x -> image.left + x * image.width() }, { y -> image.top + y * image.height() }, min(sw, sh) * image.width() / max(1, sw))
            }
            c.restore()
            if (mode == 0) {
                val r = cropRect()
                c.drawRect(image.left, image.top, image.right, r.top, dim); c.drawRect(image.left, r.bottom, image.right, image.bottom, dim)
                c.drawRect(image.left, r.top, r.left, r.bottom, dim); c.drawRect(r.right, r.top, image.right, r.bottom, dim)
                grid.strokeWidth = dp(1).toFloat(); frame.strokeWidth = resources.displayMetrics.density * 1.5f
                for (i in 1..2) { val x = r.left + r.width() * i / 3; val y = r.top + r.height() * i / 3; c.drawLine(x, r.top, x, r.bottom, grid); c.drawLine(r.left, y, r.right, y, grid) }
                c.drawRect(r, frame)
                corner.strokeWidth = dp(4).toFloat(); val l = dp(18).toFloat()
                for ((x, dx) in listOf(r.left to l, r.right to -l)) for ((y, dy) in listOf(r.top to l, r.bottom to -l)) { c.drawLine(x, y, x + dx, y, corner); c.drawLine(x, y, x, y + dy, corner) }
            }
        }

        // ---- Touch: crop frame or pen ----
        private var grab = 0  // bit 1 left, 2 top, 4 right, 8 bottom; 15 = move
        private var lastX = 0f; private var lastY = 0f
        private var stroke: Stroke? = null
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (bitmap == null) return false
            if (mode == 0) return cropTouch(e)
            if (mode == 3) return penTouch(e)
            return false
        }
        private fun cropTouch(e: MotionEvent): Boolean {
            val r = cropRect(); val near = dp(28).toFloat()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    grab = 0
                    if (abs(e.x - r.left) < near) grab = grab or 1; if (abs(e.x - r.right) < near) grab = grab or 4
                    if (abs(e.y - r.top) < near) grab = grab or 2; if (abs(e.y - r.bottom) < near) grab = grab or 8
                    if (e.y < r.top - near || e.y > r.bottom + near) grab = grab and 0b0101.inv()
                    if (e.x < r.left - near || e.x > r.right + near) grab = grab and 0b1010.inv()
                    if (ASPECTS[aspect].second != 0f && Integer.bitCount(grab) == 1) grab = 0
                    if (grab == 0 && r.contains(e.x, e.y)) grab = 15
                    lastX = e.x; lastY = e.y; parent?.requestDisallowInterceptTouchEvent(grab != 0)
                    return grab != 0
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.x - lastX) / image.width(); val dy = (e.y - lastY) / image.height(); lastX = e.x; lastY = e.y
                    moveCrop(dx, dy); invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> grab = 0
            }
            return true
        }
        private fun moveCrop(dx: Float, dy: Float) {
            val min = 0.08f
            if (grab == 15) {
                val x = dx.coerceIn(-crop.left, 1f - crop.right); val y = dy.coerceIn(-crop.top, 1f - crop.bottom); crop.offset(x, y); return
            }
            if (grab and 1 != 0) crop.left = (crop.left + dx).coerceIn(0f, crop.right - min)
            if (grab and 4 != 0) crop.right = (crop.right + dx).coerceIn(crop.left + min, 1f)
            if (grab and 2 != 0) crop.top = (crop.top + dy).coerceIn(0f, crop.bottom - min)
            if (grab and 8 != 0) crop.bottom = (crop.bottom + dy).coerceIn(crop.top + min, 1f)
            val ratio = ASPECTS[aspect].second; if (ratio == 0f) return
            // A fixed shape: the width leads, the height follows from the anchored corner and is clamped to the picture.
            val (w, h) = turnedSize(); val want = if (ratio < 0f) w.toFloat() / h else ratio
            var ch = crop.width() * w / want / h
            val anchorBottom = grab and 2 != 0
            val room = if (anchorBottom) crop.bottom else 1f - crop.top
            if (ch > room) { ch = room; val cw = ch * h * want / w; if (grab and 1 != 0) crop.left = crop.right - cw else crop.right = crop.left + cw }
            if (anchorBottom) crop.top = crop.bottom - ch else crop.bottom = crop.top + ch
        }
        private fun penTouch(e: MotionEvent): Boolean {
            val u = ((e.x - image.left) / image.width()).coerceIn(crop.left, crop.right); val v = ((e.y - image.top) / image.height()).coerceIn(crop.top, crop.bottom)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); stroke = Stroke(penColor, SIZES[penSize].second).also { it.points += PointF(u, v); strokes += it } }
                MotionEvent.ACTION_MOVE -> { val s = stroke ?: return true; val p = s.points.last()
                    if (hypot((u - p.x) * image.width(), (v - p.y) * image.height()) > 2f) s.points += PointF(u, v) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stroke = null
            }
            invalidate(); return true
        }
    }

    private fun drawVignette(c: Canvas, r: RectF, amount: Float) {
        if (amount == 0f || r.width() <= 0f) return
        val radius = hypot(r.width(), r.height()) / 2
        val edge = if (amount > 0) ((amount * 200).toInt() shl 24) else ((-amount * 160).toInt() shl 24) or 0xffffff
        c.drawRect(r, Paint().apply { shader = RadialGradient(r.centerX(), r.centerY(), radius, intArrayOf(0, 0, edge), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP) })
    }
    private fun drawStrokes(c: Canvas, list: List<Stroke>, x: (Float) -> Float, y: (Float) -> Float, shorter: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        for (s in list) {
            p.color = s.color; p.strokeWidth = max(1f, s.width * shorter)
            if (s.points.size == 1) { p.style = Paint.Style.FILL; c.drawCircle(x(s.points[0].x), y(s.points[0].y), p.strokeWidth / 2, p); p.style = Paint.Style.STROKE; continue }
            val path = Path(); path.moveTo(x(s.points[0].x), y(s.points[0].y))
            for (i in 1 until s.points.size) {
                val a = s.points[i - 1]; val b = s.points[i]
                path.quadTo(x(a.x), y(a.y), (x(a.x) + x(b.x)) / 2, (y(a.y) + y(b.y)) / 2)
            }
            path.lineTo(x(s.points.last().x), y(s.points.last().y)); c.drawPath(path, p)
        }
    }
}
