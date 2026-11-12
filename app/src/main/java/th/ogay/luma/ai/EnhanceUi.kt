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

    /** Any picture on the phone: it joins the Lumora list as a reference to the original (never moved or deleted). */
    fun showForUri(activity: ComponentActivity, uri: android.net.Uri, onQueued: (Shot) -> Unit = {}) {
        val app = activity.luma
        app.fileIo.execute {
            val shot = app.shots.all().firstOrNull { it.uri == uri } ?: run {
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                runCatching { activity.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) } }
                if (o.outWidth <= 0 || o.outHeight <= 0) null
                else Shot(java.util.UUID.randomUUID().toString(), System.currentTimeMillis(), uri, Dimensions(o.outWidth, o.outHeight),
                    1f, true, null, null, Decision.OFF, Stage.ORIGINAL, null, 0, null, false).also { app.shots.insert(it) }
            }
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                if (shot == null) activity.notice("เปิดภาพนี้ไม่ได้") else show(activity, shot) { onQueued(shot) }
            }
        }
    }

    fun show(activity: ComponentActivity, shot: Shot, onQueued: () -> Unit = {}) {
        // A video export holds the GPU and memory; the upscale waits until it is done.
        if (ExportJob.running()) { activity.notice("กำลังส่งออกวิดีโออยู่ · เพิ่มรายละเอียดภาพได้เมื่อเสร็จแล้ว"); return }
        val prefs = activity.getSharedPreferences("enhance",0)
        var scale = prefs.getInt("scale",2).coerceIn(2,4)
        var model = MODELS.firstOrNull { it.profile.name == prefs.getString("profile","NATURAL") } ?: MODELS[0]
        var strength = prefs.getInt(model.key, model.start).coerceIn(0, model.max)
        fun spec() = EnhanceSpec(scale, strength, model.profile)
        val a = activity

        // A glass sheet like the camera's: it rises from the bottom, follows the finger down and closes past a third.
        val sheet = a.screenSheet()
        val menu = GlassMenu(a.window.decorView as FrameLayout) { on -> sheet.recede(on) }
        sheet.onClosed.let { closed -> sheet.onClosed = { menu.dismiss(false); closed() } }
        val column = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(4), 0, a.dp(4), 0) }
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
            val x = (scale - 2) * w; if (animate) knob.animate().translationX(x).setInterpolator(Glass.EASE).setDuration(280).start() else knob.translationX = x }
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
            "ปิด" to { sheet.close() },
            "ดูตัวอย่าง" to { preview(a, shot, spec(), sheet) },
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
                    if (app.shots.queueEnhancement(shot.id, requested)) { EnhanceWorker.enqueue(a.applicationContext, shot.id, true); a.runOnUiThread { if (!a.isDestroyed) onQueued() } }
                }
                sheet.close()
            }), LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(14) })

        sheet.open { body, _ -> body.addView(column, -1, -2) }
    }

    @Suppress("DEPRECATION")
    private fun preview(activity: ComponentActivity, shot: Shot, spec: EnhanceSpec, parent: GlassSheet? = null) {
        val stopped = AtomicBoolean(false)
        val name = if (spec.profile == EnhanceProfile.NATURAL) "Natural SR3" else "Classic SR2"
        val compare = CompareView(activity)
        val title = activity.text("กำลังสร้างตัวอย่าง…", 17f, Color.WHITE, true)
        val sheet = activity.screenSheet()
        parent?.recede(true)
        sheet.onClosed.let { closed -> sheet.onClosed = { stopped.set(true); parent?.recede(false); compare.release(); closed() } }
        sheet.open { col, sh ->
            val head = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(activity.dp(4), 0, activity.dp(4), activity.dp(10)) }
            head.addView(LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL
                addView(title); addView(activity.text("$name · ×${spec.scale} · ความแรง ${spec.strength}% · ส่วนกลางภาพ", 12f, Glass.TEXT3)) }, LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(FrameLayout(activity).apply { background = activity.glass(22, 0x1affffff); isClickable = true; contentDescription = "ปิดตัวอย่าง"; glassTouch()
                addView(IconView(activity, Icons.CLOSE, 2f), FrameLayout.LayoutParams(activity.dp(18), activity.dp(18), Gravity.CENTER)); setOnClickListener { Glass.tick(this); sh.close() } }, activity.dp(44), activity.dp(44))
            col.addView(head, -1, -2)
            val side = minOf(activity.resources.displayMetrics.widthPixels - activity.dp(40), (activity.resources.displayMetrics.heightPixels * 0.55f).toInt())
            col.addView(compare, LinearLayout.LayoutParams(-1, side))
            col.addView(activity.text("ลากเส้นกลางเพื่อเทียบ ซ้ายคือภาพขยายปกติ ขวาคือ AI", 12f, Glass.TEXT3).apply { gravity = Gravity.CENTER; setPadding(0, activity.dp(10), 0, activity.dp(4)) }, -1, -2)
        }
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
                    else { compare.set(first, second); title.text = "ตัวอย่างก่อน / หลัง" }
                }
            } catch(e: Exception) {
                a?.recycle(); b?.recycle()
                activity.runOnUiThread { if(!stopped.get() && !activity.isDestroyed) { sheet.close(); activity.notice(e.message ?: "สร้างตัวอย่างไม่ได้") } }
            } catch(e: LinkageError) {
                a?.recycle(); b?.recycle()
                activity.runOnUiThread { if(!stopped.get() && !activity.isDestroyed) { sheet.close(); activity.notice("โหลดระบบ AI ไม่สำเร็จ") } }
            }
        }
    }

    /**
     * Before / after in one picture: the plain upscale on the left of a divider, the AI result on the right. Drag the
     * divider to compare; small labels name each side. Until the AI result arrives a soft light sweeps across.
     */
    private class CompareView(c: android.content.Context) : View(c) {
        private var before: Bitmap? = null; private var after: Bitmap? = null
        private var split = 0.5f; private var sweep = 0f
        private val d = c.resources.displayMetrics.density
        private val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        private val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 2 * d }
        private val knob = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; setShadowLayer(6 * d, 0f, 2 * d, 0x66000000) }
        private val arrow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xff1c2233.toInt(); style = android.graphics.Paint.Style.STROKE; strokeWidth = 2 * d; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND }
        private val tag = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 12 * d; typeface = android.graphics.Typeface.DEFAULT_BOLD }
        private val tagBg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x8c000000.toInt() }
        private val glow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val clip = android.graphics.Path(); private val box = android.graphics.RectF(); private val src = android.graphics.RectF()
        private val loading = android.animation.ValueAnimator.ofFloat(0f, 1f).apply { duration = 1400; repeatCount = android.animation.ValueAnimator.INFINITE; addUpdateListener { sweep = it.animatedValue as Float; invalidate() } }
        init { contentDescription = "ตัวอย่างก่อนและหลัง AI"; setBackgroundColor(0x14ffffff) }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); if (after == null && !Glass.reduced(context)) loading.start() }
        override fun onDetachedFromWindow() { loading.cancel(); super.onDetachedFromWindow() }
        fun set(b: Bitmap?, a: Bitmap?) { before = b; after = a; loading.cancel(); split = 1f
            // The divider glides in from the right so the change is seen once.
            android.animation.ValueAnimator.ofFloat(1f, 0.5f).apply { duration = 700; interpolator = Glass.EASE; addUpdateListener { split = it.animatedValue as Float; invalidate() } }.start() }
        fun release() { before?.recycle(); after?.recycle(); before = null; after = null }
        override fun onDraw(cv: android.graphics.Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val r = 20 * d
            clip.reset(); clip.addRoundRect(0f, 0f, w, h, r, r, android.graphics.Path.Direction.CW); cv.save(); cv.clipPath(clip)
            val a = after; val b = before
            if (a == null || b == null) {
                glow.shader = android.graphics.LinearGradient(w * (sweep * 1.6f - 0.6f), 0f, w * (sweep * 1.6f - 0.1f), h, intArrayOf(0x00ffffff, 0x2effffff, 0x00ffffff), null, android.graphics.Shader.TileMode.CLAMP)
                cv.drawRect(0f, 0f, w, h, glow); cv.restore(); return
            }
            // Centre-crop the square sample into the view.
            val sc = maxOf(w / a.width, h / a.height); val dw = a.width * sc; val dh = a.height * sc
            box.set((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2)
            val x = w * split
            cv.save(); cv.clipRect(0f, 0f, x, h); cv.drawBitmap(b, null, box, paint); cv.restore()
            cv.save(); cv.clipRect(x, 0f, w, h); cv.drawBitmap(a, null, box, paint); cv.restore()
            fun label(t: String, left: Boolean) { val tw = tag.measureText(t); val px = 10 * d; val bx = if (left) 12 * d else w - 12 * d - tw - 2 * px
                src.set(bx, 12 * d, bx + tw + 2 * px, 12 * d + 24 * d); cv.drawRoundRect(src, 12 * d, 12 * d, tagBg); cv.drawText(t, bx + px, src.centerY() + 4 * d, tag) }
            if (split > 0.18f) label("ก่อน", true); if (split < 0.82f) label("AI", false)
            cv.drawLine(x, 0f, x, h, line); cv.drawCircle(x, h / 2, 16 * d, knob)
            val ay = h / 2; val s = 4 * d
            cv.drawLine(x - 3 * d, ay - s, x - 3 * d - s, ay, arrow); cv.drawLine(x - 3 * d - s, ay, x - 3 * d, ay + s, arrow)
            cv.drawLine(x + 3 * d, ay - s, x + 3 * d + s, ay, arrow); cv.drawLine(x + 3 * d + s, ay, x + 3 * d, ay + s, arrow)
            cv.restore()
        }
        private var lastTick = -1
        override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
            if (after == null) return false
            if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
            split = (e.x / width).coerceIn(0f, 1f)
            val t = (split * 10).toInt(); if (t != lastTick) { lastTick = t; performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
            invalidate(); return true
        }
    }
}
