package th.ogay.luma

import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity

/** The list of saved video-editor projects: open, rename, copy or delete. */
class StudioProjectsActivity : ComponentActivity() {
    private lateinit var col: LinearLayout
    private var relinking = ""
    /** A replacement for a missing video: the project keeps its edits and uses the chosen file from now on. */
    private val pickReplacement = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        val id = relinking; relinking = ""
        if (uri == null || id.isEmpty()) return@registerForActivityResult
        runCatching { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        StudioProjects.relink(this, id, uri); notice("เชื่อมวิดีโอใหม่แล้ว"); fill()
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xff0b0f1e.toInt()) }
        installRoot(root)
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)) }
        bar.addView(EditorKit.round(this, Icons.CLOSE, "ปิด") { finish() }, dp(48), dp(48))
        bar.addView(text("โปรเจกต์วิดีโอ", 20f, Glass.TEXT, true).apply { setPadding(dp(14), 0, 0, 0) })
        root.addView(bar, -1, -2)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(4), dp(14), dp(24)) }
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }
    override fun onResume() { super.onResume(); fill() }

    private fun fill() {
        luma.readIo.execute {
            val list = StudioProjects.list(this)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                col.removeAllViews()
                if (list.isEmpty()) col.addView(text("ยังไม่มีโปรเจกต์ · เมื่อตัดต่อวิดีโอ งานจะถูกบันทึกไว้ที่นี่เอง", 14f, Glass.TEXT2).apply { gravity = Gravity.CENTER; setPadding(dp(20), dp(60), dp(20), 0) }, -1, -2)
                list.forEach { p -> col.addView(row(p), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }) }
            }
        }
    }
    private fun row(p: StudioProjects.Info): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; background = glass(22, 0x14ffffff); setPadding(dp(10), dp(10), dp(6), dp(10)); isClickable = true; glassTouch()
        contentDescription = "เปิดโปรเจกต์ ${p.name}"
        val pic = ImageView(this@StudioProjectsActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Glass.PANEL); clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(14).toFloat()) }
            if (p.thumb.exists()) setImageBitmap(BitmapFactory.decodeFile(p.thumb.path)) }
        addView(pic, dp(64), dp(64))
        val words = LinearLayout(this@StudioProjectsActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(4), 0) }
        words.addView(text(p.name, 15.5f, Glass.TEXT, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        words.addView(text(if (p.missing) "ไม่พบวิดีโอต้นฉบับ · แตะเพื่อเลือกไฟล์แทน" else "แก้ไขล่าสุด " + android.text.format.DateFormat.format("d MMM HH:mm", p.updated), 12f, if (p.missing) 0xffff6b6b.toInt() else Glass.TEXT3))
        addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        fun small(icon: String, desc: String, f: () -> Unit) = addView(FrameLayout(this@StudioProjectsActivity).apply { isClickable = true; glassTouch(); contentDescription = desc
            addView(IconView(this@StudioProjectsActivity, icon, 1.9f).apply { tint = Glass.TEXT2 }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)); setOnClickListener { f() } }, dp(44), dp(48))
        small(EditorKit.PEN, "เปลี่ยนชื่อ") {
            val field = EditText(this@StudioProjectsActivity).apply { setText(p.name); setSingleLine(); setSelection(p.name.length) }
            AlertDialog.Builder(this@StudioProjectsActivity).setTitle("ชื่อโปรเจกต์").setView(field)
                .setPositiveButton("บันทึก") { _, _ -> val n = field.text.toString().trim(); if (n.isNotEmpty()) { StudioProjects.rename(this@StudioProjectsActivity, p.id, n); fill() } }.setNegativeButton("ยกเลิก", null).show() }
        small(Icons.STACK, "ทำสำเนา") { StudioProjects.duplicate(this@StudioProjectsActivity, p.id); fill() }
        small(Icons.CLOSE, "ลบโปรเจกต์") {
            AlertDialog.Builder(this@StudioProjectsActivity).setTitle("ลบโปรเจกต์นี้?").setMessage("ลบเฉพาะการตัดต่อที่บันทึกไว้ วิดีโอต้นฉบับยังอยู่")
                .setPositiveButton("ลบ") { _, _ -> StudioProjects.delete(this@StudioProjectsActivity, p.id); fill() }.setNegativeButton("ยกเลิก", null).show() }
        setOnClickListener {
            if (p.missing) { relinking = p.id; notice("ไม่พบวิดีโอต้นฉบับ · เลือกไฟล์วิดีโอที่จะใช้แทน"); pickReplacement.launch(arrayOf("video/*")) }
            else startActivity(VideoEditorActivity.intent(this@StudioProjectsActivity, p.source).putExtra("project", p.id)) }
    }
}
