package th.ogay.luma.ai

import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import android.graphics.Matrix
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.exifinterface.media.ExifInterface
import th.ogay.luma.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Only the AI workflow: the existing camera/gallery layout stays owned by Glass UI. */
object EnhanceUi {
    private val previews = Executors.newSingleThreadExecutor()

    /** One choice in the model dropdown: what it is called, what it suits, and its own slider range. */
    private class Model(
        val profile: EnhanceProfile, val name: String, val badge: String, val icon: String,
        val short: String, val about: String, val key: String, val start: Int, val max: Int,
    )
    // A leaf for the natural model (soft, true to life); a cut gem for the classic one (hard, crisp facets).
    private const val LEAF = "M5 19C5 10.5 10.5 5 19 5c0 8.5-5.5 14-14 14z M5 19l8.5-8.5 M9.5 14.5h3.5 M12.5 11.5V8"
    private const val GEM = "M7 4h10l4 5-9 11L3 9z M3 9h18 M12 20L8.5 9 12 4l3.5 5z"
    private val MODELS = listOf(
        Model(EnhanceProfile.NATURAL, "Natural SR3", "แนะนำ", LEAF,
            "คน ใบหน้า ตัวหนังสือ · เนียนเหมือนภาพจริง",
            "เหมาะกับภาพคน ใบหน้า ผิว ภาพในร่ม และภาพที่มีป้ายหรือตัวหนังสือ โมเดลหาบริเวณคนและตัวหนังสือก่อน แล้วเติมรายละเอียดตรงนั้นให้เบาลง ผิวจึงไม่เป็นเม็ด และตัวอักษรไม่ถูกแต่งจนอ่านเป็นคำอื่น ผลที่ได้ดูเป็นภาพถ่ายจริงมากกว่าคมจัด",
            "strength_natural", 70, 100),
        Model(EnhanceProfile.CLASSIC, "Classic SR2", "คมชัด", GEM,
            "วิว ตึก ใบไม้ พื้นผิว · คมเต็มที่",
            "เหมาะกับภาพวิว สถาปัตยกรรม ใบไม้ หิน ผ้า และภาพซูมไกลที่ต้องการความคมมากที่สุด โมเดลเติมรายละเอียดเท่ากันทั้งภาพและดันได้ถึง 150% แต่กับภาพคนอาจทำให้ผิวดูหยาบ และอาจทำให้ตัวหนังสือเพี้ยน",
            "strength2", 120, 150),
    )

    fun show(activity: ComponentActivity, shot: Shot) {
        // A video export holds the GPU and memory; the upscale waits until it is done.
        if (ExportJob.running()) { activity.notice("กำลังส่งออกวิดีโออยู่ · เพิ่มรายละเอียดภาพได้เมื่อเสร็จแล้ว"); return }
        val prefs = activity.getSharedPreferences("enhance",0)
        var scale = prefs.getInt("scale",2).coerceIn(2,4)
        var model = MODELS.firstOrNull { it.profile.name == prefs.getString("profile","NATURAL") } ?: MODELS[0]
        var strength = prefs.getInt(model.key, model.start).coerceIn(0, model.max)
        fun spec() = EnhanceSpec(scale, strength, model.profile)
        val a = activity

        // The panel fills the window so the model dropdown can open over it, like every other dropdown in the app.
        val host = FrameLayout(a)
        val dialog = object : android.app.Dialog(a) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean = GlassMenu.route(ev) { super.dispatchTouchEvent(it) } ?: super.dispatchTouchEvent(ev)
        }
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val menu = GlassMenu(host)
        val card = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; background = a.glass(28, Glass.FILL_STRONG); setPadding(a.dp(18), a.dp(18), a.dp(18), a.dp(16)); isClickable = true }
        val column = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        card.addView(ScrollView(a).apply { isVerticalScrollBarEnabled = false; addView(column) }, LinearLayout.LayoutParams(-1, -2, 1f))
        val cardW = minOf((a.resources.displayMetrics.widthPixels * 0.92f).toInt(), a.dp(440))
        host.addView(card, FrameLayout.LayoutParams(cardW, -2, Gravity.CENTER).apply { topMargin = a.dp(24); bottomMargin = a.dp(24) })
        host.setOnClickListener { dialog.dismiss() }
        fun label(value: String, size: Float = 13f, color: Int = Glass.TEXT2) = a.text(value, size, color).apply { maxLines = 12; ellipsize = null; setLineSpacing(0f, 1.18f) }

        // Header: the AI mark, the title and where the work happens.
        val head = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(FrameLayout(a).apply { background = a.glass(14, Glass.ACCENT_SOFT)
            addView(IconView(a, Icons.AI, 1.8f).apply { tint = Glass.ACCENT }, FrameLayout.LayoutParams(a.dp(22), a.dp(22), Gravity.CENTER)) }, a.dp(40), a.dp(40))
        head.addView(LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(12), 0, 0, 0)
            addView(a.text("เพิ่มรายละเอียดด้วย AI", 18f, Color.WHITE, true))
            addView(label("ประมวลผลในเครื่อง ไม่ส่งภาพออกไปไหน", 12f, Glass.TEXT3)) }, LinearLayout.LayoutParams(0, -2, 1f))
        column.addView(head, -1, -2)

        // Model dropdown: tap, or hold and slide, exactly like the other dropdown buttons.
        column.addView(label("โมเดล", 12.5f, Glass.TEXT3).apply { setPadding(a.dp(4), a.dp(16), 0, a.dp(6)) })
        val modelIcon = IconView(a, model.icon, 1.8f).apply { tint = Glass.ACCENT }
        val modelName = a.text(model.name, 15.5f, Color.WHITE, true)
        val modelBadge = a.text(model.badge, 11f, Glass.ON_ACCENT, true).apply { background = a.glass(9, Glass.ACCENT); setPadding(a.dp(7), a.dp(1), a.dp(7), a.dp(2)) }
        val modelShort = label(model.short, 12f)
        val picker = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; background = a.glass(20, 0x1affffff); setPadding(a.dp(10), a.dp(10), a.dp(12), a.dp(10))
            isClickable = true; isFocusable = true; glassTouch() }
        picker.addView(FrameLayout(a).apply { background = a.glass(13, 0x24ffffff); addView(modelIcon, FrameLayout.LayoutParams(a.dp(22), a.dp(22), Gravity.CENTER)) }, a.dp(42), a.dp(42))
        picker.addView(LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(12), 0, a.dp(8), 0)
            addView(LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; addView(modelName); addView(modelBadge, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = a.dp(8) }) })
            addView(modelShort) }, LinearLayout.LayoutParams(0, -2, 1f))
        picker.addView(IconView(a, Icons.UPDOWN, 2f).apply { tint = Glass.TEXT3 }, a.dp(18), a.dp(18))
        column.addView(picker, LinearLayout.LayoutParams(-1, -2).apply { })
        val about = label(model.about, 12.5f).apply { setPadding(a.dp(4), a.dp(10), a.dp(4), a.dp(4)) }
        column.addView(about, -1, -2)

        // Scale: a sliding pill for ×2 / ×3 / ×4, in the same style as the gallery tabs.
        column.addView(label("ขนาด", 12.5f, Glass.TEXT3).apply { setPadding(a.dp(4), a.dp(14), 0, a.dp(6)) })
        val size = a.text("", 14.5f, Color.WHITE, true).apply { maxLines = 3; ellipsize = null; setPadding(a.dp(4), a.dp(8), 0, a.dp(2)) }
        fun updateSize() {
            size.text = runCatching { spec().output(shot.size).let { "${it.w} × ${it.h} px · ${pixelsText(it.pixels)}" } }.getOrElse { it.message ?: "ขนาดเกินขีดจำกัด" }
        }
        val seg = FrameLayout(a).apply { background = a.glass(20, 0x14ffffff); setPadding(a.dp(3), a.dp(3), a.dp(3), a.dp(3)) }
        val knob = View(a).apply { background = a.glass(17, Glass.ACCENT_SOFT) }
        seg.addView(knob, FrameLayout.LayoutParams(0, -1))
        val cells = LinearLayout(a)
        val cellViews = (2..4).map { n -> a.text("×$n", 15f, Color.WHITE, true).apply { gravity = Gravity.CENTER; isClickable = true; contentDescription = "ขยาย ×$n" }.also { cells.addView(it, LinearLayout.LayoutParams(0, -1, 1f)) } }
        seg.addView(cells, FrameLayout.LayoutParams(-1, -1))
        fun place(animate: Boolean) { val w = (seg.width - a.dp(6)) / 3f; knob.layoutParams = (knob.layoutParams as FrameLayout.LayoutParams).apply { width = w.toInt() }; knob.requestLayout()
            val x = (scale - 2) * w; if (animate) knob.animate().translationX(x).setInterpolator(Glass.SPRING).setDuration(360).start() else knob.translationX = x }
        cellViews.forEachIndexed { i, c -> c.setOnClickListener { scale = i + 2; place(true); Glass.tick(c); updateSize() } }
        seg.post { place(false) }
        column.addView(seg, LinearLayout.LayoutParams(-1, a.dp(50)))
        column.addView(size)

        // Strength: the same slider as before; its range follows the chosen model.
        val amount = label("", 12.5f, Glass.TEXT3).apply { setPadding(a.dp(4), a.dp(14), 0, a.dp(2)) }
        fun amountText() { amount.text = "ความแรงรายละเอียด $strength%" + if (strength > 100) " · คมกว่าการขยายปกติ" else "" }
        val slider = GlassSlider(a).apply { grad = intArrayOf(0xff7db8ff.toInt(), Color.WHITE, Glass.ACCENT); contentDescription = "ความแรงรายละเอียด"
            onChange = { strength = it * 5; amountText() } }
        fun fitSlider() { slider.values = (0..model.max / 5).map { "${it * 5}" }; slider.index = strength / 5; amountText() }
        fitSlider()
        column.addView(amount)
        column.addView(slider, LinearLayout.LayoutParams(-1, a.dp(56)))
        column.addView(label("×3 และ ×4 ใช้โมเดล ×4 แล้วปรับขนาด จึงใช้เวลาใกล้กัน ตัวอย่างใช้ส่วนกลางภาพ ส่วนภาพเต็มประมวลผลเบื้องหลังและอาจใช้เวลาหลายนาที", 11.5f, Glass.TEXT3).apply { setPadding(a.dp(4), a.dp(4), a.dp(4), 0) })
        updateSize()

        fun choose(m: Model) {
            if (m === model) return
            prefs.edit().putInt(model.key, strength).apply()
            model = m; strength = prefs.getInt(m.key, m.start).coerceIn(0, m.max)
            modelIcon.path = m.icon; modelName.text = m.name; modelBadge.text = m.badge; modelShort.text = m.short
            about.animate().alpha(0f).setDuration(90).withEndAction { about.text = m.about; about.animate().alpha(1f).setDuration(180).start() }.start()
            fitSlider(); updateSize()
        }
        picker.contentDescription = "เลือกโมเดล ${model.name}"
        picker.setOnClickListener { }
        picker.pressMenu(menu) { anchor ->
            menu.show(anchor, "เลือกโมเดล AI", MODELS.map { GlassMenu.Option(it.name + " · " + it.badge, it.icon, it.short) }, MODELS.indexOf(model)) { i ->
                choose(MODELS[i]); picker.contentDescription = "เลือกโมเดล ${model.name}"
            }
        }

        column.addView(GlassDialog.actions(a,
            "ปิด" to { dialog.dismiss() },
            "ดูตัวอย่าง" to { preview(a, shot, spec()) },
            "สร้างภาพ" to start@{
                val requested = try { spec().also { it.output(shot.size) } } catch (e: IllegalArgumentException) { a.notice(e.message ?: "ขนาดภาพเกินขีดจำกัด"); return@start }
                // One heavy job at a time: a video export and an AI upscale both take the whole GPU and memory.
                if (ExportJob.running()) { a.notice("กำลังส่งออกวิดีโออยู่ รอให้เสร็จก่อนจึงเพิ่มรายละเอียดภาพได้"); return@start }
                prefs.edit().putInt("scale", scale).putInt(model.key, strength).putString("profile", model.profile.name).apply()
                // Progress and "finished" are shown as a notification once the app is left; Android 13+ asks the user first.
                LiveNotice.askPermissions(a)
                val app = a.luma
                app.fileIo.execute {
                    if (app.shots.upscaling()) { a.runOnUiThread { if (!a.isDestroyed) a.notice("มีภาพที่กำลังเพิ่มรายละเอียดอยู่ รอให้เสร็จก่อน") }; return@execute }
                    if (app.shots.queueEnhancement(shot.id, requested)) EnhanceWorker.enqueue(a.applicationContext, shot.id, true)
                }
                dialog.dismiss()
            }), LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(14) })

        dialog.setContentView(host)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT)
            setDimAmount(0.45f)
        }
        dialog.setOnShowListener { if (!Glass.reduced(a)) { card.alpha = 0f; card.translationY = a.dp(22).toFloat(); card.scaleX = 0.96f; card.scaleY = 0.96f
            card.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setInterpolator(Glass.SPRING).setDuration(380).start() } }
        dialog.setOnDismissListener { menu.dismiss(false) }
        dialog.show()
    }

    @Suppress("DEPRECATION")
    private fun preview(activity: ComponentActivity, shot: Shot, spec: EnhanceSpec) {
        val stopped = AtomicBoolean(false)
        val image = ImageView(activity).apply { adjustViewBounds=true; scaleType=ImageView.ScaleType.FIT_CENTER; contentDescription="ตัวอย่างรายละเอียดกลางภาพ" }
        var original: Bitmap? = null; var enhanced: Bitmap? = null; var after=true
        var titleView: TextView? = null
        val name = if (spec.profile == EnhanceProfile.NATURAL) "Natural" else "Classic"
        fun setTitle(t: String) { titleView?.text = t }
        val dialog = GlassDialog.panel(activity, "กำลังสร้างตัวอย่าง $name…") { column, d ->
            image.clipToOutline = true
            image.outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, activity.dp(18).toFloat()) } }
            column.addView(image, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(6) })
            column.addView(GlassDialog.actions(activity, "ปิด" to { d.dismiss() }, "ก่อน / หลัง" to {
                if (enhanced != null) { after=!after
                    image.animate().alpha(0.35f).setDuration(90).withEndAction { image.setImageBitmap(if(after) enhanced else original); image.animate().alpha(1f).setDuration(160).start() }.start()
                    setTitle(if(after) "หลัง AI $name ×${spec.scale}" else "ต้นฉบับขยาย ×${spec.scale}") }
            }), LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(12) })
        }
        titleView = dialog.findViewById<View>(android.R.id.content)?.findViewWithTag<TextView>("title")
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
                            val left = (decoder.width-w)/2; val top = (decoder.height-h)/2
                            // Natural looks for text and people first so the preview matches the full export.
                            val whole = Dimensions(decoder.width,decoder.height)
                            val textScan = if (spec.profile == EnhanceProfile.NATURAL) DetailTextGuard.scan(activity.applicationContext,shot.uri,whole) { stopped.get() } else null
                            val personScan = if (spec.profile == EnhanceProfile.NATURAL) DetailPersonGuard.scan(activity.applicationContext,shot.uri,whole) { stopped.get() } else null
                            val input = DetailSuperResolution.readInput(decoder,left,top)
                            val before = IntArray(w*h) { i -> input[(i/w+EnhanceSpec.HALO)*EnhanceSpec.INPUT+i%w+EnhanceSpec.HALO] }
                            val raw = Bitmap.createBitmap(before,w,h,Bitmap.Config.ARGB_8888)
                            a=Bitmap.createScaledBitmap(raw,w*spec.scale,h*spec.scale,true)
                            if(a!==raw) raw.recycle()
                            DetailRuntime(activity.applicationContext).use { runtime ->
                                val result = runtime.run(input)
                                val output=IntArray(w*spec.scale*h*spec.scale)
                                DetailResampler.copyCore(result,input,spec.scale,spec.strength,w,h,output,w*spec.scale,0,spec.profile,
                                    textScan?.mask?.forCore(left,top,w,h),personScan?.mask?.forCore(left,top,w,h))
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
                    else { original=first; enhanced=second; image.setImageBitmap(second); setTitle("หลัง AI $name ×${spec.scale}") }
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
