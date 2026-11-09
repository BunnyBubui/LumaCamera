package th.ogay.luma

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

fun Context.showCaptureSettings(rawAvailable: Boolean, changed: () -> Unit) {
    val prefs = getSharedPreferences("camera", Context.MODE_PRIVATE)
    val dialog = Dialog(this)
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(18), dp(22), dp(20))
        setBackgroundColor(BG)
    }
    content.addView(label("ตั้งค่ากล้อง", 23f), -1, dp(52))
    fun toggle(title: String, key: String, default: Boolean) {
        val row = Switch(this).apply {
            text = title
            textSize = 16f
            setTextColor(WHITE)
            isChecked = prefs.getBoolean(key, default)
            if (key == "sound" && androidx.camera.core.CameraInfo.mustPlayShutterSound()) {
                text = "เสียงชัตเตอร์ · ระบบกำหนดให้เปิด"
                isChecked = true
                isEnabled = false
            }
            setOnCheckedChangeListener { _, enabled ->
                prefs.edit().putBoolean(key, enabled).apply()
                changed()
            }
        }
        content.addView(row, -1, -2)
    }
    content.addView(label("AI รวมเฟรม", 17f, LIME), -1, dp(40))
    val choices = LinearLayout(this)
    for (count in listOf(4, 8, 16, 32)) {
        choices.addView(pill("$count") {
            prefs.edit().putInt("frames", count).apply()
            for (i in 0 until choices.childCount) {
                (choices.getChildAt(i) as TextView).selectionStyle(i == listOf(4, 8, 16, 32).indexOf(count))
            }
            changed()
        }.apply {
            contentDescription = "ถ่าย AI $count เฟรม"
            selectionStyle(prefs.getInt("frames", 8) == count)
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4) })
    }
    content.addView(choices)
    content.addView(label("16–32 เฟรมใช้เวลาถ่ายนานขึ้น เหมาะกับฉากนิ่ง", 13f, MUTED), -1, dp(44))
    toggle("เสียงชัตเตอร์", "sound", true)
    toggle("แรงสั่นเมื่อแตะ", "haptic", true)
    toggle("ลดการเคลื่อนไหว", "reduce_motion", false)
    toggle("เส้นกริด", "grid", false)
    toggle("AI อัตโนมัติในโหมด JPEG", "auto", true)
    content.addView(label(if (rawAvailable) "กล้องนี้เปิดให้เก็บ RAW + JPEG ได้" else "กล้องนี้ยังไม่เปิด RAW + JPEG ให้แอป", 14f, MUTED), -1, dp(48))
    content.addView(pill("ปิด") { dialog.dismiss() }, -1, dp(48))
    dialog.setContentView(ScrollView(this).apply { addView(content) })
    dialog.window?.apply {
        setBackgroundDrawableResource(android.R.color.transparent)
        setGravity(Gravity.BOTTOM)
        setLayout(-1, -2)
    }
    dialog.show()
    dialog.window?.setLayout(-1, -2)
}
