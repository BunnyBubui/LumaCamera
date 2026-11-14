package th.ogay.luma

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/** A thin rounded progress bar in the app's accent colour. */
class ModelBar(c: Context) : View(c) {
    var fraction = 0f; set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33ffffff }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Glass.ACCENT }
    private val r = RectF()
    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); r.set(0f, 0f, width.toFloat(), h); c.drawRoundRect(r, h / 2, h / 2, track)
        if (fraction > 0f) { r.set(0f, 0f, (width * fraction).coerceAtLeast(h), h); c.drawRoundRect(r, h / 2, h / 2, fill) }
    }
}

/**
 * The card for a downloadable model: its name and how good it is, whether it is on the phone, a progress bar while it
 * downloads, and one button (download, cancel or delete). Tapping the card opens the list of models to choose from.
 * It listens to [ModelStore] by itself, so the bar keeps moving without rebuilding the panel, and a download goes on in
 * the background when the screen is left.
 */
class ModelCard(private val a: Activity, private val menu: GlassMenu, private val models: List<ModelSpec>, private val icon: String,
                private val title: String, private val onDownload: (ModelSpec) -> Unit) : LinearLayout(a) {
    private val name = a.text("", 14.5f, Glass.TEXT, true)
    private val badge = a.text("", 10.5f, Glass.ON_ACCENT, true).apply { background = a.glass(8, Glass.ACCENT); setPadding(a.dp(6), 0, a.dp(6), a.dp(1)) }
    private val status = a.text("", 11.5f, Glass.TEXT2)
    private val about = a.text("", 11.5f, Glass.TEXT3)
    private val bar = ModelBar(a)
    private val detail = a.text("", 11f, Glass.TEXT2)
    private val progress = LinearLayout(a).apply { orientation = VERTICAL; visibility = GONE }
    private val actionHost = FrameLayout(a)
    private val chevron = IconView(a, "M7 10l5 5 5-5", 2f).apply { tint = Glass.TEXT2 }
    private val listener: (ModelStore.Job) -> Unit = { refresh() }

    init {
        orientation = VERTICAL; background = a.glass(18, 0x1affffff); setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
        val head = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; isClickable = true; glassTouch(); contentDescription = title }
        head.addView(FrameLayout(a).apply { background = a.glass(13, 0x24ffffff); addView(IconView(a, icon, 1.8f).apply { tint = Glass.ACCENT }, FrameLayout.LayoutParams(a.dp(20), a.dp(20), Gravity.CENTER)) }, a.dp(40), a.dp(40))
        val info = LinearLayout(a).apply { orientation = VERTICAL; setPadding(a.dp(10), 0, a.dp(6), 0) }
        info.addView(LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; addView(name); addView(badge, LayoutParams(-2, -2).apply { leftMargin = a.dp(7) }); addView(chevron, LayoutParams(a.dp(16), a.dp(16)).apply { leftMargin = a.dp(2) }) })
        info.addView(status)
        head.addView(info, LayoutParams(0, -2, 1f))
        head.addView(actionHost, LayoutParams(-2, a.dp(36)))
        head.setOnClickListener { v -> menu.show(v, title, models.map { m -> GlassMenu.Option(m.name + " · " + m.badge, icon, m.short + " · " + WhisperModels.sizeText(a, m) + if (ModelStore.installed(a, m)) " · ติดตั้งแล้ว" else "") }, models.indexOf(ModelStore.selected(a, models[0].group, models))) { k ->
            ModelStore.select(a, models[k]); refresh() } }
        addView(head, -1, -2)
        addView(about, LayoutParams(-1, -2).apply { topMargin = a.dp(6) })
        progress.addView(bar, LayoutParams(-1, a.dp(5)).apply { topMargin = a.dp(8) })
        progress.addView(detail, LayoutParams(-1, -2).apply { topMargin = a.dp(5) })
        addView(progress, -1, -2)
        refresh()
    }

    private fun pill(label: String, path: String, accent: Boolean, click: () -> Unit) = LinearLayout(a).apply {
        gravity = Gravity.CENTER; setPadding(a.dp(12), 0, a.dp(14), 0); isClickable = true; contentDescription = label
        background = a.glass(18, if (accent) Glass.ACCENT else 0x2effffff); glassTouch()
        addView(IconView(a, path, 2f).apply { tint = if (accent) Glass.ON_ACCENT else Glass.TEXT }, LayoutParams(a.dp(16), a.dp(16)).apply { rightMargin = a.dp(5) })
        addView(a.text(label, 12.5f, if (accent) Glass.ON_ACCENT else Glass.TEXT, true))
        setOnClickListener { Glass.tick(this); click() }
    }

    fun refresh() {
        val m = ModelStore.selected(a, models[0].group, models); val job = ModelStore.job(m); val have = ModelStore.installed(a, m)
        name.text = m.name; badge.text = m.badge; about.text = m.short
        status.text = when { job != null -> "กำลังดาวน์โหลด"; have -> "พร้อมใช้ · ${WhisperModels.sizeText(a, m)} ในเครื่อง"; else -> "ยังไม่ได้ดาวน์โหลด · ${WhisperModels.sizeText(a, m)}" }
        status.setTextColor(if (have && job == null) Glass.ACCENT else Glass.TEXT2)
        progress.visibility = if (job != null) VISIBLE else GONE
        if (job != null) {
            bar.fraction = job.fraction
            val total = (job.totalBytes.takeIf { it > 0 } ?: (m.approxMb * 1_048_576L)) / 1_048_576
            detail.text = "${(job.fraction * 100).toInt()}% · ${job.doneBytes / 1_048_576} / $total MB · ออกจากหน้านี้ได้ ดาวน์โหลดต่อเบื้องหลัง"
        }
        actionHost.removeAllViews()
        actionHost.addView(when {
            job != null -> pill("ยกเลิก", "M6 6l12 12 M18 6L6 18", false) { job.cancel() }
            have -> pill("ลบ", "M5 7h14 M10 11v6 M14 11v6 M9 7V4h6v3 M7 7l1 13h8l1-13", false) { ModelStore.delete(a, m); refresh() }
            else -> pill("ดาวน์โหลด", Icons.DOWNLOAD, true) { onDownload(m) }
        })
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); ModelStore.listen(listener); refresh() }
    override fun onDetachedFromWindow() { ModelStore.unlisten(listener); super.onDetachedFromWindow() }
}
