package th.ogay.luma.ai

import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.exifinterface.media.ExifInterface
import th.ogay.luma.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Only the AI workflow: the existing camera/gallery layout stays owned by Glass UI. */
object EnhanceUi {
    private val previews = Executors.newSingleThreadExecutor()
    fun show(activity: ComponentActivity, shot: Shot) {
        val prefs = activity.getSharedPreferences("enhance",0)
        var scale = prefs.getInt("scale",2).coerceIn(2,4)
        // Above 100 the AI detail is pushed past the plain upscale (sharper); 120 is the new default.
        var strength = prefs.getInt("strength2",120).coerceIn(0,150)
        val dialog = GlassDialog.panel(activity, "เพิ่มรายละเอียดด้วย AI") { column, dialog ->
            fun label(value: String, small: Boolean = false) = activity.text(value, if (small) 12.5f else 14f, if (small) Glass.TEXT3 else Glass.TEXT2).apply { maxLines = 6; ellipsize = null; setLineSpacing(0f, 1.15f); setPadding(0, activity.dp(6), 0, activity.dp(6)) }
            column.addView(label("ประมวลผลในเครื่อง ไม่ส่งภาพออกไปไหน\nรายละเอียดบางส่วนเป็นการคาดเดาของ AI"))
            val size = activity.text("", 15f, Color.WHITE, true).apply { maxLines = 3; ellipsize = null; setPadding(0, activity.dp(8), 0, activity.dp(8)) }
            fun updateSize() {
                size.text = runCatching { EnhanceSpec(scale,strength).output(shot.size).let { "×$scale · ${it.w} × ${it.h} px · ${pixelsText(it.pixels)}" } }.getOrElse { it.message ?: "ขนาดเกินขีดจำกัด" }
            }
            // Sliding pill for ×2 / ×3 / ×4, in the same style as the gallery tabs.
            val seg = FrameLayout(activity).apply { background = activity.glass(20, 0x14ffffff); setPadding(activity.dp(3), activity.dp(3), activity.dp(3), activity.dp(3)) }
            val knob = android.view.View(activity).apply { background = activity.glass(17, Glass.ACCENT_SOFT) }
            seg.addView(knob, FrameLayout.LayoutParams(0, -1))
            val row = LinearLayout(activity)
            val cells = (2..4).map { n -> activity.text("×$n", 15f, Color.WHITE, true).apply { gravity = android.view.Gravity.CENTER; isClickable = true; contentDescription = "ขยาย ×$n" }.also { row.addView(it, LinearLayout.LayoutParams(0, -1, 1f)) } }
            seg.addView(row, FrameLayout.LayoutParams(-1, -1))
            fun place(animate: Boolean) { val w = (seg.width - activity.dp(6)) / 3f; knob.layoutParams = (knob.layoutParams as FrameLayout.LayoutParams).apply { width = w.toInt() }; knob.requestLayout()
                val x = (scale - 2) * w; if (animate) knob.animate().translationX(x).setInterpolator(Glass.SPRING).setDuration(360).start() else knob.translationX = x }
            cells.forEachIndexed { i, c -> c.setOnClickListener { scale = i + 2; place(true); Glass.tick(c); updateSize() } }
            seg.post { place(false) }
            column.addView(seg, LinearLayout.LayoutParams(-1, activity.dp(52)))
            column.addView(size)
            val amount = label("ความแรงรายละเอียด $strength%")
            column.addView(amount)
            column.addView(GlassSlider(activity).apply {
                values = (0..30).map { "${it * 5}" }; index = strength / 5; grad = intArrayOf(0xff7db8ff.toInt(), Color.WHITE, Glass.ACCENT); contentDescription = "ความแรงรายละเอียด"
                onChange = { strength = it * 5; amount.text = "ความแรงรายละเอียด $strength%" }
            }, LinearLayout.LayoutParams(-1, activity.dp(56)))
            column.addView(label("×3 และ ×4 ใช้โมเดล ×4 แล้วปรับขนาดผลลัพธ์ จึงใช้เวลาใกล้กัน ตัวอย่างใช้ส่วนกลางภาพ ภาพจริงอาจใช้เวลาหลายนาที", true))
            updateSize()
            column.addView(GlassDialog.actions(activity,
                "ปิด" to { dialog.dismiss() },
                "ดูตัวอย่าง" to { preview(activity,shot,EnhanceSpec(scale,strength)) },
                "สร้างภาพ" to {
                    val ok = try { EnhanceSpec(scale,strength).output(shot.size); true } catch (e: IllegalArgumentException) { activity.notice(e.message ?: "ขนาดภาพเกินขีดจำกัด"); false }
                    if (ok) {
                        prefs.edit().putInt("scale",scale).putInt("strength2",strength).apply()
                        val spec = EnhanceSpec(scale,strength)
                        // Progress and "finished" are shown as a notification once the app is left; Android 13+ asks the user first.
                        // On Android 16 live updates (OPPO's live alerts) are asked for as well.
                        th.ogay.luma.LiveNotice.askPermissions(activity)
                        activity.luma.fileIo.execute { if (activity.luma.shots.queueEnhancement(shot.id,spec)) EnhanceWorker.enqueue(activity.applicationContext,shot.id,true) }
                        dialog.dismiss()
                    }
                }), LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(12) })
        }
        dialog.show()
    }

    @Suppress("DEPRECATION")
    private fun preview(activity: ComponentActivity, shot: Shot, spec: EnhanceSpec) {
        val stopped = AtomicBoolean(false)
        val image = ImageView(activity).apply { adjustViewBounds=true; scaleType=ImageView.ScaleType.FIT_CENTER; contentDescription="ตัวอย่างรายละเอียดกลางภาพ" }
        var original: Bitmap? = null; var enhanced: Bitmap? = null; var after=true
        var titleView: TextView? = null
        fun setTitle(t: String) { titleView?.text = t }
        val dialog = GlassDialog.panel(activity, "กำลังสร้างตัวอย่าง…") { column, d ->
            image.clipToOutline = true
            image.outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: android.view.View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, activity.dp(18).toFloat()) } }
            column.addView(image, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(6) })
            column.addView(GlassDialog.actions(activity, "ปิด" to { d.dismiss() }, "ก่อน / หลัง" to {
                if (enhanced != null) { after=!after
                    image.animate().alpha(0.35f).setDuration(90).withEndAction { image.setImageBitmap(if(after) enhanced else original); image.animate().alpha(1f).setDuration(160).start() }.start()
                    setTitle(if(after) "หลัง AI ×${spec.scale}" else "ต้นฉบับขยาย ×${spec.scale}") }
            }), LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(12) })
        }
        titleView = dialog.findViewById<android.view.View>(android.R.id.content)?.findViewWithTag<TextView>("title")
        dialog.setOnDismissListener { stopped.set(true); image.setImageDrawable(null); original?.recycle(); enhanced?.recycle() }
        dialog.show()
        previews.execute {
            var a: Bitmap? = null; var b: Bitmap? = null
            try {
                DetailComputeGate.run({ stopped.get() }) {
                    val rotation = activity.contentResolver.openInputStream(shot.uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
                    activity.contentResolver.openInputStream(shot.uri)?.use { stream ->
                        val decoder = BitmapRegionDecoder.newInstance(stream,false) ?: error("อ่านภาพไม่ได้")
                        try {
                            val w=minOf(EnhanceSpec.CORE,decoder.width); val h=minOf(EnhanceSpec.CORE,decoder.height)
                            val input = DetailSuperResolution.readInput(decoder,((decoder.width-w)/2),((decoder.height-h)/2))
                            val before = IntArray(w*h) { i -> input[(i/w+EnhanceSpec.HALO)*EnhanceSpec.INPUT+i%w+EnhanceSpec.HALO] }
                            val raw = Bitmap.createBitmap(before,w,h,Bitmap.Config.ARGB_8888)
                            a=Bitmap.createScaledBitmap(raw,w*spec.scale,h*spec.scale,true)
                            if(a!==raw) raw.recycle()
                            DetailRuntime(activity.applicationContext).use { runtime ->
                                val result = runtime.run(input)
                                val output=IntArray(w*spec.scale*h*spec.scale)
                                DetailResampler.copyCore(result,input,spec.scale,spec.strength,w,h,output,w*spec.scale,0)
                                b=Bitmap.createBitmap(output,w*spec.scale,h*spec.scale,Bitmap.Config.ARGB_8888)
                            }
                            if(rotation!=0) {
                                fun rotate(bitmap: Bitmap): Bitmap = Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,
                                    Matrix().apply { setRotate(rotation.toFloat()) },true).also { if(it!==bitmap) bitmap.recycle() }
                                a=rotate(a!!); b=rotate(b!!)
                            }
                        } finally { decoder.recycle() }
                    } ?: error("ไม่พบภาพต้นฉบับ")
                }
                val first=a; val second=b
                activity.runOnUiThread {
                    if(stopped.get() || activity.isDestroyed) { first?.recycle(); second?.recycle() }
                    else { original=first; enhanced=second; image.setImageBitmap(second); setTitle("หลัง AI ×${spec.scale}") }
                }
            } catch(e: Exception) {
                a?.recycle(); b?.recycle()
                activity.runOnUiThread { if(!stopped.get() && !activity.isDestroyed) { dialog.dismiss(); activity.notice(e.message ?: "สร้างตัวอย่างไม่ได้") } }
            } catch(e: LinkageError) {
                a?.recycle(); b?.recycle()
                activity.runOnUiThread { if(!stopped.get() && !activity.isDestroyed) { dialog.dismiss(); activity.notice("โหลดระบบ AI ไม่สำเร็จ") } }
            }
        }
    }
}
