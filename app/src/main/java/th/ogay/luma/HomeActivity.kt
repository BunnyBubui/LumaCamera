package th.ogay.luma

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Home: every part of Lumora in one place, opened from the top-left button of the photo screen (the app itself
 * still opens straight into the camera). Photo modes return to the camera in that mode; everything else opens
 * its own screen.
 */
class HomeActivity : ComponentActivity() {
    companion object {
        /** Result extra: the photo mode the camera should switch to. */
        const val RESULT_MODE = "mode"
        const val HOME_ICON = "M4.5 4.5h6v6h-6z M13.5 4.5h6v6h-6z M4.5 13.5h6v6h-6z M13.5 13.5h6v6h-6z"
    }

    private class Tile(val icon: String, val title: String, val note: String, val color: Int, val open: () -> Unit)

    private var editKind = "image"
    private val pick = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        @Suppress("DEPRECATION") val uri = r.data?.getParcelableArrayListExtra<Uri>(GalleryActivity.RESULT_URIS)?.firstOrNull() ?: return@registerForActivityResult
        val target = when (editKind) { "video" -> VideoEditorActivity::class.java; "document" -> DocumentActivity::class.java; else -> PhotoEditorActivity::class.java }
        startActivity(Intent(this, target).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val host = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xff141a36.toInt(), 0xff0b0f1e.toInt(), 0xff120c22.toInt()))
        }
        installRoot(host)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(28)) }
        host.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col) }, -1, -1)

        // Header: back to the camera, the mark drawing itself, and the name.
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(FrameLayout(this).apply { background = glass(24, 0x33ffffff); isClickable = true; contentDescription = "กลับไปกล้อง"; glassTouch()
            addView(IconView(this@HomeActivity, Icons.CLOSE, 2f), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)); setOnClickListener { close() } }, dp(46), dp(46))
        col.addView(head, -1, -2)
        val brand = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(4)) }
        val logo = LumoraLogoView(this).apply { progress = if (Glass.reduced(this@HomeActivity)) 1f else 0f }
        brand.addView(logo, dp(64), dp(64))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        names.addView(text("Lumora", 30f, Color.WHITE, true))
        names.addView(text("เลือกสิ่งที่อยากทำ", 14f, Glass.TEXT2))
        brand.addView(names)
        col.addView(brand, -1, -2)

        // The camera itself, as one wide card.
        col.addView(hero(), LinearLayout.LayoutParams(-1, dp(118)).apply { topMargin = dp(14) })

        val cards = ArrayList<View>()
        section(col, "ถ่ายภาพ")
        grid(col, cards, listOf(
            mode(Icons.PORTRAIT, "บุคคล", "หน้าชัด หลังเบลอ", 0xffff9f0a.toInt(), PhotoMode.PORTRAIT),
            mode(Icons.FLASH, "ถ่ายเร็ว", "จับจังหวะที่ขยับเร็ว", 0xff8e7dff.toInt(), PhotoMode.SPORT),
            mode(Icons.PRO, "โปร", "ปรับ ISO ชัตเตอร์ โฟกัส", 0xff64d2ff.toInt(), PhotoMode.PRO),
            mode(Icons.HDR, "HDR", "เก็บทั้งที่มืดและสว่าง", 0xffffd60a.toInt(), PhotoMode.HDR),
            mode(Icons.HIRES, "ความละเอียดสูง", "ใช้เซนเซอร์เต็มที่", 0xff30d158.toInt(), PhotoMode.HIRES),
            mode(Icons.FILM, "ฟิล์ม", "โทนสีแบบฟิล์ม", 0xffff5e8a.toInt(), PhotoMode.FILM),
            mode(Icons.PHOTO, "ภาพถ่าย", "ถ่ายยาวหลายวินาทีได้", 0xff5ac8fa.toInt(), PhotoMode.PHOTO),
            Tile(Icons.DOC, "สแกนเอกสาร", "เลือกรูป ตัดขอบ อ่านข้อความ", 0xffbf5af2.toInt()) { editKind = "document"; pick.launch(Intent(this, GalleryActivity::class.java).putExtra(GalleryActivity.EXTRA_PICK, "image")) }))
        section(col, "วิดีโอ")
        grid(col, cards, listOf(
            Tile(Icons.VIDEO, "วิดีโอ", "ถ่ายวิดีโอ", 0xffff453a.toInt()) { startActivity(Intent(this, VideoActivity::class.java).putExtra("mode", "VIDEO")) },
            Tile(Icons.SLOWMO, "สโลว์โมชัน", "เฟรมเรตสูง", 0xff64d2ff.toInt()) { startActivity(Intent(this, VideoActivity::class.java).putExtra("mode", "SLOW")) },
            Tile(Icons.TIMELAPSE, "ไทม์แลปส์", "ย่อเวลายาวให้สั้น", 0xffffd60a.toInt()) { startActivity(Intent(this, SpecialVideoActivity::class.java).putExtra("mode", "TIMELAPSE")) },
            Tile(Icons.RAWV, "RAW วิดีโอ", "ห้องทดลอง RAW", 0xff30d158.toInt()) { startActivity(Intent(this, RawLabActivity::class.java)) }))
        section(col, "แก้ไขและคลัง")
        grid(col, cards, listOf(
            Tile(Icons.LIBRARY, "แกลเลอรี", "รูปและวิดีโอทั้งหมด", 0xffff9f0a.toInt()) { startActivity(Intent(this, GalleryActivity::class.java)) },
            Tile(EditorKit.ADJUST, "แก้ภาพ", "สี เส้นโค้ง ครอป วาด", 0xffff5e8a.toInt()) { editKind = "image"; pick.launch(Intent(this, GalleryActivity::class.java).putExtra(GalleryActivity.EXTRA_PICK, "image")) },
            Tile(EditorKit.SCISSORS, "ตัดต่อวิดีโอ", "เลเยอร์ สโลว์ลื่น เพลง", 0xff8e7dff.toInt()) { editKind = "video"; pick.launch(Intent(this, GalleryActivity::class.java).putExtra(GalleryActivity.EXTRA_PICK, "video")) },
            Tile(Icons.AI, "ภาพจาก AI", "รูปที่ Lumora ปรับแล้ว", 0xff64d2ff.toInt()) { startActivity(Intent(this, GalleryActivity::class.java).putExtra("tab", "luma")) },
            Tile(Icons.GRID, "สตูดิโอ", "คลังงานสตูดิโอ", 0xff30d158.toInt()) { startActivity(Intent(this, StudioLibraryActivity::class.java)) },
            Tile(Icons.PRO, "ตรวจกล้อง", "ความสามารถของเครื่อง", 0xff98989d.toInt()) { startActivity(Intent(this, CameraProbeActivity::class.java)) }))

        // Entrance: the mark draws, then the cards rise in one after another.
        if (!Glass.reduced(this)) {
            android.animation.ValueAnimator.ofFloat(0f, 1f).apply { duration = 1000; startDelay = 120; addUpdateListener { logo.progress = it.animatedValue as Float } }.start()
            cards.forEachIndexed { i, v -> v.alpha = 0f; v.translationY = dp(18).toFloat(); v.animate().alpha(1f).translationY(0f).setStartDelay(160L + i * 35L).setDuration(380).setInterpolator(Glass.EASE).start() }
        }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) { override fun handleOnBackPressed() = close() })
    }

    private fun close() { finish(); @Suppress("DEPRECATION") overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out) }

    private fun mode(icon: String, title: String, note: String, color: Int, m: PhotoMode) = Tile(icon, title, note, color) {
        setResult(RESULT_OK, Intent().putExtra(RESULT_MODE, m.name)); close()
    }

    private fun hero(): View = FrameLayout(this).apply {
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xffff9f0a.toInt(), 0xffff5e8a.toInt(), 0xff8e7dff.toInt())).apply { cornerRadius = dp(26).toFloat() }
        isClickable = true; contentDescription = "ถ่ายภาพ กลับไปกล้อง"; glassTouch()
        val row = LinearLayout(this@HomeActivity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), 0, dp(20), 0) }
        row.addView(FrameLayout(this@HomeActivity).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33ffffff) }
            addView(IconView(this@HomeActivity, Icons.PHOTO, 2.2f), FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER)) }, dp(62), dp(62))
        val t = LinearLayout(this@HomeActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, 0, 0) }
        t.addView(text("ถ่ายภาพ", 22f, Color.WHITE, true)); t.addView(text("กลับไปกล้อง พร้อม AI ปรับภาพ", 13f, 0xddffffff.toInt()))
        row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(IconView(this@HomeActivity, "M9 5l7 7-7 7", 2.4f), dp(22), dp(22))
        addView(row, -1, -1)
        setOnClickListener { Glass.tick(this); setResult(RESULT_OK, Intent().putExtra(RESULT_MODE, PhotoMode.PHOTO.name)); close() }
    }

    private fun section(col: LinearLayout, name: String) =
        col.addView(text(name, 15f, Glass.TEXT2, true).apply { setPadding(dp(4), dp(22), 0, dp(10)) }, -1, -2)

    /** Two cards per row. */
    private fun grid(col: LinearLayout, cards: MutableList<View>, tiles: List<Tile>) {
        tiles.chunked(2).forEach { pair ->
            val row = LinearLayout(this)
            pair.forEachIndexed { i, tile ->
                val card = card(tile); cards += card
                row.addView(card, LinearLayout.LayoutParams(0, dp(108), 1f).apply { if (i == 0) rightMargin = dp(6) else leftMargin = dp(6) })
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(6) })
            col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
    }

    private fun card(t: Tile): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = glass(22, 0x1affffff); setPadding(dp(14), dp(14), dp(12), dp(12))
        isClickable = true; contentDescription = "${t.title} ${t.note}"; glassTouch()
        addView(FrameLayout(this@HomeActivity).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor((t.color and 0x00ffffff) or 0x33000000) }
            addView(IconView(this@HomeActivity, t.icon, 2f).apply { tint = t.color }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        }, dp(42), dp(42))
        addView(View(this@HomeActivity), LinearLayout.LayoutParams(1, 0, 1f))
        addView(text(t.title, 15f, Color.WHITE, true).apply { maxLines = 1 })
        addView(text(t.note, 11.5f, Glass.TEXT3).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        setOnClickListener { Glass.tick(this); t.open() }
    }
}
