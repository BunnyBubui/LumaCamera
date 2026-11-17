package th.ogay.luma

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The saved video-editor projects ("Projects_List / _Empty / _Menu" boards): a list of cards with the latest one
 * highlighted, a calm empty page, a round + at the bottom right to start a new one, and a sheet with the actions
 * of each project. The editor saves by itself, so everything listed here is where its work was left.
 */
class StudioProjectsActivity : ComponentActivity() {
    private val coral get() = Glass.ACCENT
    private lateinit var col: LinearLayout
    private lateinit var count: android.widget.TextView
    private var relinking = ""

    /** A replacement for a missing video: the project keeps its edits and uses the chosen file from now on. */
    private val pickReplacement = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = relinking; relinking = ""
        if (uri == null || id.isEmpty()) return@registerForActivityResult
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        StudioProjects.relink(this, id, uri); notice("เชื่อมวิดีโอใหม่แล้ว"); fill()
    }

    /** New project: choose a video in Lumora's gallery, then the editor opens on it (the project file appears with the first edit). */
    private val pickNew = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        @Suppress("DEPRECATION") val uris = r.data?.getParcelableArrayListExtra<Uri>(GalleryActivity.RESULT_URIS)?.takeIf { it.isNotEmpty() } ?: return@registerForActivityResult
        // Every chosen clip comes along: the first opens the project and the others are added behind it, in the order picked.
        startActivity(VideoEditorActivity.intent(this, uris[0]).putExtra("name", newName)
            .putStringArrayListExtra("share_more", ArrayList(uris.drop(1).map { it.toString() })))
    }
    private var newName = ""
    /** Step 1 names the project, step 2 picks its video, then the editor opens already carrying that name. */
    private fun create() {
        // A new project needs the phone's encoder and memory, so wait until the video being saved is finished.
        if (ExportJob.state?.active == true) { notice("กำลังส่งออกวิดีโออยู่ รอให้เรนเดอร์เสร็จก่อน จึงสร้างโปรเจกต์ใหม่ได้"); return }
        val field = GlassDialog.field(this, "ชื่อโปรเจกต์", "โปรเจกต์ ${StudioProjects.list(this).size + 1}"); field.selectAll()
        GlassDialog.ask(this, "โปรเจกต์ใหม่", Icons.PLUS, "ตั้งชื่อก่อน แล้วเลือกวิดีโอที่จะตัดต่อ", field, "เลือกวิดีโอ", focus = field) {
            newName = field.text.toString().trim().ifEmpty { "โปรเจกต์ใหม่" }; pick()
            true }
    }
    private fun pick() = pickNew.launch(Intent(this, GalleryActivity::class.java).putExtra(GalleryActivity.EXTRA_PICK, "video"))

    private var root: FrameLayout? = null
    private val dropdown by lazy { GlassMenu(root!!) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val host = FrameLayout(this)
        root = host
        installRoot(host)
        host.setBackgroundColor(Glass.BG)
        host.addView(Glow(this, coral), -1, -1)
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        host.addView(page, -1, -1)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(host) { _, insets ->
            val b = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            page.setPadding(b.left, b.top, b.right, 0)
            (fab.layoutParams as FrameLayout.LayoutParams).apply { rightMargin = dp(20) + b.right; bottomMargin = dp(26) + b.bottom }
            col.setPadding(dp(16), dp(4), dp(16), dp(110) + b.bottom)
            insets
        }

        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(4)) }
        bar.addView(EditorKit.round(this, Icons.CLOSE, "ปิด") { finish() }, dp(48), dp(48))
        bar.addView(text("โปรเจกต์วิดีโอ", 22f, Glass.TEXT, true).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        count = text("", 12.5f, coral, true).apply { gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), 0); visibility = View.GONE
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Glass.alpha(coral, 46)) } }
        bar.addView(count, LinearLayout.LayoutParams(-2, dp(28)))
        page.addView(bar, -1, -2)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(110)) }
        page.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipToPadding = false; addView(col) }, LinearLayout.LayoutParams(-1, 0, 1f))
        host.addView(fab, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(20); bottomMargin = dp(26) })
    }

    /** The round + at the bottom right: coral, with a soft glow of the same colour. */
    private val fab by lazy {
        FrameLayout(this).apply {
            isClickable = true; contentDescription = "สร้างโปรเจกต์ใหม่"; glassTouch(); elevation = dp(8).toFloat()
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Theme.mix(coral, 0xffffffff.toInt(), 0.25f), coral)).apply { shape = GradientDrawable.OVAL }
            addView(IconView(this@StudioProjectsActivity, Icons.PLUS, 2.6f).apply { tint = Glass.ON_ACCENT }, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
            setOnClickListener { Glass.tick(this); create() }
        }
    }

    override fun onResume() { super.onResume(); fill() }

    private fun fill() {
        StudioProjects.io.execute {
            val list = StudioProjects.list(this)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                col.removeAllViews()
                count.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
                count.text = "${list.size} งาน"
                if (list.isEmpty()) { col.addView(emptyState(), LinearLayout.LayoutParams(-1, -2)); return@runOnUiThread }
                list.forEachIndexed { i, p -> col.addView(card(p, i == 0), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }) }
            }
        }
    }

    // ---------- Empty ----------
    private fun emptyState(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(16), dp(72), dp(16), 0)
        addView(EmptyArt(this@StudioProjectsActivity, coral), LinearLayout.LayoutParams(dp(240), dp(150)))
        addView(text("ยังไม่มีโปรเจกต์", 21f, Glass.TEXT, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(30) })
        addView(text("เมื่อคุณตัดต่อวิดีโอ งานจะถูกบันทึกไว้ที่นี่โดยอัตโนมัติ และกลับมาแก้ต่อได้ทุกเมื่อ", 14f, Glass.TEXT2).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.25f) },
            LinearLayout.LayoutParams(dp(290), -2).apply { topMargin = dp(10) })
        val btn = LinearLayout(this@StudioProjectsActivity).apply {
            gravity = Gravity.CENTER; setPadding(dp(22), 0, dp(26), 0); isClickable = true; contentDescription = "สร้างโปรเจกต์ใหม่"; glassTouch()
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Theme.mix(coral, 0xffffffff.toInt(), 0.25f), coral)).apply { cornerRadius = dp(26).toFloat() }
            addView(IconView(this@StudioProjectsActivity, Icons.PLUS, 2.6f).apply { tint = Glass.ON_ACCENT }, dp(20), dp(20))
            addView(text("สร้างโปรเจกต์ใหม่", 16f, Glass.ON_ACCENT, true).apply { setPadding(dp(8), 0, 0, 0) })
            setOnClickListener { Glass.tick(this); create() }
        }
        addView(btn, LinearLayout.LayoutParams(-2, dp(52)).apply { topMargin = dp(26) })
        addView(text("หรือกดปุ่ม + มุมขวาล่าง", 12.5f, Glass.TEXT3).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
    }

    // ---------- Cards ----------
    private fun whenText(t: Long): String {
        if (t <= 0) return ""
        val now = Calendar.getInstance(); val then = Calendar.getInstance().apply { time = Date(t) }
        val day = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR) + 365 * (now.get(Calendar.YEAR) - then.get(Calendar.YEAR))
        val clock = SimpleDateFormat("HH:mm", Locale.US).format(Date(t))
        return "แก้ไขล่าสุด " + when (day) { 0 -> "วันนี้ $clock"; 1 -> "เมื่อวาน $clock"; else -> SimpleDateFormat("d MMM", Locale("th")).format(Date(t)) + " $clock" }
    }
    private fun duration(s: Long) = "%d:%02d".format(s / 60, s % 60)

    private fun tag(label: String, tint: Int, fillTint: Boolean) = text(label, 10.5f, if (fillTint) tint else Glass.TEXT2).apply {
        gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(8), 0); maxLines = 1
        background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(if (fillTint) Glass.alpha(tint, 46) else 0x14ffffff) }
    }

    private fun card(p: StudioProjects.Info, latest: Boolean): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(10), dp(4), dp(10)); isClickable = true; glassTouch()
        contentDescription = "เปิดโปรเจกต์ ${p.name}"; alpha = if (p.missing) 0.75f else 1f
        background = GradientDrawable().apply { cornerRadius = dp(24).toFloat()
            if (latest && !p.missing) { setColor(Glass.alpha(coral, 26)); setStroke(dp(1), Glass.alpha(coral, 0x73)) } else { setColor(0x0dffffff); setStroke(dp(1), 0x14ffffff) } }
        val pic = FrameLayout(this@StudioProjectsActivity).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xff55555a.toInt(), 0xff232326.toInt())).apply { cornerRadius = dp(16).toFloat() }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(16).toFloat()) }
            if (p.thumb.exists()) addView(ImageView(this@StudioProjectsActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(BitmapFactory.decodeFile(p.thumb.path)) }, -1, -1)
            else addView(IconView(this@StudioProjectsActivity, Icons.FILM, 1.6f).apply { tint = Glass.TEXT3 }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            if (p.seconds > 0) addView(text(duration(p.seconds), 10.5f, Glass.TEXT).apply { gravity = Gravity.CENTER; setPadding(dp(6), 0, dp(6), 0)
                background = GradientDrawable().apply { cornerRadius = dp(9).toFloat(); setColor(0x99000000.toInt()) } }, FrameLayout.LayoutParams(-2, dp(18), Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(5); bottomMargin = dp(5) })
        }
        addView(pic, dp(76), dp(76))
        val words = LinearLayout(this@StudioProjectsActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(4), 0) }
        words.addView(text(p.name, 15.5f, Glass.TEXT, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        if (p.missing) words.addView(text("ไม่พบวิดีโอต้นฉบับ แตะเพื่อเลือกไฟล์แทน", 12f, 0xffffb27a.toInt()).apply { setPadding(0, dp(3), 0, 0) })
        else {
            words.addView(text(whenText(p.updated), 12f, Glass.TEXT3).apply { setPadding(0, dp(3), 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            val tags = LinearLayout(this@StudioProjectsActivity)
            if (latest) tags.addView(tag("ล่าสุด", coral, true), LinearLayout.LayoutParams(-2, dp(20)).apply { rightMargin = dp(5) })
            p.tags.forEach { tags.addView(tag(it, coral, false), LinearLayout.LayoutParams(-2, dp(20)).apply { rightMargin = dp(5) }) }
            if (tags.childCount > 0) words.addView(tags, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(7) })
        }
        addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        addView(FrameLayout(this@StudioProjectsActivity).apply { isClickable = true; contentDescription = "เมนูของ ${p.name}"; glassTouch()
            addView(Dots(this@StudioProjectsActivity), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)); setOnClickListener { Glass.tick(this); menu(p, this) } }, dp(44), dp(52))
        setOnClickListener { open(p) }
    }

    private fun open(p: StudioProjects.Info) {
        if (p.missing) { relinking = p.id; notice("ไม่พบวิดีโอต้นฉบับ เลือกไฟล์วิดีโอที่จะใช้แทน"); pickReplacement.launch(arrayOf("video/*")) }
        else startActivity(VideoEditorActivity.intent(this, p.source).putExtra("project", p.id))
    }

    // ---------- The sheet of actions for one project ----------
    private fun menu(p: StudioProjects.Info, anchor: View) {
        val acts = ArrayList<Pair<GlassMenu.Option, () -> Unit>>()
        if (!p.missing) acts += GlassMenu.Option("เปิดแก้ไข", EditorKit.SCISSORS) to { open(p) }
        acts += GlassMenu.Option("เปลี่ยนชื่อ", EditorKit.PEN) to { rename(p) }
        acts += GlassMenu.Option("ทำสำเนา", Icons.STACK) to { StudioProjects.duplicate(this, p.id); fill() }
        acts += GlassMenu.Option("เปลี่ยนไฟล์วิดีโอต้นฉบับ", Icons.FILE, "ใช้เมื่อย้ายหรือลบวิดีโอเดิม") to { relinking = p.id; pickReplacement.launch(arrayOf("video/*")) }
        acts += GlassMenu.Option("ลบโปรเจกต์", Icons.CLOSE, "ลบเฉพาะการตัดต่อ วิดีโอต้นฉบับไม่หาย") to { confirmDelete(p) }
        dropdown.show(anchor, p.name, acts.map { it.first }, -1) { i -> acts[i].second() }
    }
    private fun rename(p: StudioProjects.Info) {
        val field = GlassDialog.field(this, "ชื่อโปรเจกต์", p.name)
        GlassDialog.ask(this, "ชื่อโปรเจกต์", Icons.FILE, null, field, "บันทึก", focus = field) { val n = field.text.toString().trim(); if (n.isEmpty()) false else { StudioProjects.rename(this, p.id, n); fill(); true } }
    }
    private fun confirmDelete(p: StudioProjects.Info) {
        GlassDialog.confirm(this, "ลบโปรเจกต์นี้?", "ลบเฉพาะการตัดต่อที่บันทึกไว้ วิดีโอต้นฉบับยังอยู่ครบ", "ลบ", Icons.CLOSE, true) { StudioProjects.delete(this, p.id); fill() }
    }

    // ---------- Drawn pieces ----------
    private class Dots(c: android.content.Context) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(cv: Canvas) { p.color = Glass.TEXT2; val r = width / 10f; for (i in 0..2) cv.drawCircle(width / 2f, height * (0.2f + 0.3f * i), r, p) }
    }
    /** A faint light of the page colour at the top right, like the Home page. */
    private class Glow(c: android.content.Context, private val tint: Int) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG); private val m = android.graphics.Matrix()
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            val sh = RadialGradient(0f, 0f, 1f, Glass.alpha(tint, 0x24), 0x00000000, Shader.TileMode.CLAMP)
            m.setScale(w * 0.9f, h * 0.30f); m.postTranslate(w * 0.85f, 0f); sh.setLocalMatrix(m); p.shader = sh
        }
        override fun onDraw(cv: Canvas) { cv.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p) }
    }
    /** An empty timeline: two clips, a dashed slot for the next one, a playhead, a pair of scissors and sparkles. */
    private class EmptyArt(c: android.content.Context, private val tint: Int) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG); private val r = RectF(); private val d = c.resources.displayMetrics.density
        private val scissors = androidx.core.graphics.PathParser.createPathFromPathData(EditorKit.SCISSORS)
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(cv: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val rad = 30 * d
            r.set(0f, 0f, w, h)
            p.style = Paint.Style.FILL; p.color = 0x0affffff; cv.drawRoundRect(r, rad, rad, p)
            p.shader = RadialGradient(w * 0.8f, h * 0.1f, w * 0.7f, Glass.alpha(tint, 0x47), Glass.alpha(tint, 0), Shader.TileMode.CLAMP); cv.drawRoundRect(r, rad, rad, p); p.shader = null
            p.style = Paint.Style.STROKE; p.strokeWidth = d; p.color = 0x14ffffff; r.inset(d / 2, d / 2); cv.drawRoundRect(r, rad, rad, p)
            fun clip(x: Float, wd: Float, fill: Int) { r.set(x * d, 70 * d, (x + wd) * d, 96 * d); p.style = Paint.Style.FILL; p.color = fill; cv.drawRoundRect(r, 8 * d, 8 * d, p) }
            clip(26f, 70f, 0x24ffffff); clip(100f, 52f, 0x1affffff)
            r.set(156 * d, 70 * d, 216 * d, 96 * d); p.color = Glass.alpha(tint, 46); cv.drawRoundRect(r, 8 * d, 8 * d, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = Glass.alpha(tint, 0xb3); p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(5 * d, 4 * d), 0f); cv.drawRoundRect(r, 8 * d, 8 * d, p); p.pathEffect = null
            p.style = Paint.Style.FILL; p.color = tint; cv.drawRect(124 * d, 60 * d, 126 * d, 106 * d, p); cv.drawRoundRect(119 * d, 56 * d, 131 * d, 64 * d, 3 * d, 3 * d, p)
            p.color = 0x1affffff; cv.drawRect(26 * d, 112 * d, 214 * d, 114 * d, p)
            cv.save(); cv.translate(26 * d, 22 * d); cv.scale(26 * d / 24f, 26 * d / 24f); p.style = Paint.Style.STROKE; p.strokeWidth = 1.8f; p.strokeCap = Paint.Cap.ROUND; p.color = tint; p.alpha = 0xe6; cv.drawPath(scissors, p); cv.restore()
            p.alpha = 0xff; p.style = Paint.Style.FILL; p.color = Glass.alpha(tint, 0x73)
            fun star(cx: Float, cy: Float, s: Float) { val a = s * d; val path = android.graphics.Path(); path.moveTo(cx * d, cy * d - a); path.quadTo(cx * d, cy * d, cx * d + a, cy * d); path.quadTo(cx * d, cy * d, cx * d, cy * d + a); path.quadTo(cx * d, cy * d, cx * d - a, cy * d); path.quadTo(cx * d, cy * d, cx * d, cy * d - a); cv.drawPath(path, p) }
            star(190f, 30f, 11f); star(214f, 56f, 6f)
        }
    }
}
