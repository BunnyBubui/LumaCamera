package th.ogay.luma

import android.Manifest
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.Size
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Glass gallery: Luma shots and the whole device library in two tabs, a centred category strip,
 * long-press peek, a search field that morphs out of its icon, and the original detail view with glass chips.
 */
class GalleryActivity : ComponentActivity() {
    /** One grid cell: a Luma shot or a device photo/video. */
    class Item(val key: String, val uri: Uri, val time: Long, val video: Boolean, val name: String, val album: String, val shot: Shot?)
    private var selected: String? = null
    private var useAi = false
    private var showingId: String? = null
    private var device = false
    private var category = 0
    private var queryText = ""
    private lateinit var host: FrameLayout
    private lateinit var root: LinearLayout
    private lateinit var inspect: InspectView
    private lateinit var toggle: LinearLayout
    private lateinit var status: TextView
    private var readVersion = 0
    private val thumbs = Executors.newFixedThreadPool(3)
    private var peek: View? = null
    private val prefs by lazy { getSharedPreferences("camera", MODE_PRIVATE) }
    private val mediaPermissions get() = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    private val mediaRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { reload() }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        selected = state?.getString("id"); useAi = state?.getBoolean("ai") ?: false
        device = state?.getBoolean("device") ?: prefs.getBoolean("gallery_device", false)
        host = FrameLayout(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        host.addView(root, -1, -1)
        installRoot(host)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (peek != null) closePeek() else if (selected != null) { selected = null; useAi = false; reload() } else finish() }
        })
        luma.shots.changes.observe(this) { if (!device || selected != null) reload() }
        if (state == null && !device) {
            luma.fileIo.execute {
                val first = luma.shots.latest()?.id
                runOnUiThread { if (!isDestroyed) { selected = first; reload() } }
            }
        } else reload()
    }
    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putString("id", selected); out.putBoolean("ai", useAi); out.putBoolean("device", device) }
    override fun onDestroy() { thumbs.shutdownNow(); super.onDestroy() }
    private fun mediaGranted() = mediaPermissions.any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun reload() {
        val id = selected; val version = ++readVersion
        luma.fileIo.execute {
            val shot = id?.let { luma.shots.get(it) }
            val items = when {
                shot != null || id?.startsWith("device:") == true -> emptyList()
                device -> if (mediaGranted()) deviceItems() else emptyList()
                else -> luma.shots.all().map { Item(it.id, it.uri, it.time, false, "", "Luma", it) }
            }
            runOnUiThread {
                if (version == readVersion && !isDestroyed) when {
                    shot != null -> showDetail(shot)
                    id?.startsWith("device:") == true -> showDeviceItem(Uri.parse(id.removePrefix("device:")))
                    else -> { selected = null; showGrid(items) }
                }
            }
        }
    }
    private fun deviceItems(): List<Item> {
        val out = mutableListOf<Item>()
        fun read(base: Uri, video: Boolean) {
            val cols = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DISPLAY_NAME, "bucket_display_name")
            runCatching {
                contentResolver.query(base, cols, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                    while (c.moveToNext() && out.size < 5000) {
                        val uri = ContentUris.withAppendedId(base, c.getLong(0))
                        out += Item("device:$uri", uri, c.getLong(1) * 1000, video, c.getString(2) ?: "", c.getString(3) ?: "อื่น ๆ", null)
                    }
                }
            }
        }
        read(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false); read(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
        return out.sortedByDescending { it.time }
    }

    // ---------- Grid ----------
    private fun topBar(title: String, back: () -> Unit): LinearLayout {
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(6)) }
        bar.addView(roundButton(Icons.CHEVRON_LEFT, "ย้อนกลับ", back), dp(48), dp(48))
        bar.addView(text(title, 20f, Color.WHITE, true).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(bar, -1, -2); return bar
    }
    private fun roundButton(icon: String, desc: String, click: () -> Unit) = FrameLayout(this).apply {
        background = glass(22); contentDescription = desc; isClickable = true; glassTouch()
        addView(IconView(this@GalleryActivity, icon, 2.1f), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)); setOnClickListener { click() }
    }
    private fun categories(items: List<Item>): List<Pair<String, (Item) -> Boolean>> =
        if (!device) listOf<Pair<String, (Item) -> Boolean>>("ทั้งหมด" to { _ -> true }, "AI" to { it -> it.shot?.ai != null }, "RAW" to { it -> it.shot?.output?.needsRaw == true }, "พิเศษ" to { it -> it.shot?.captureNote != null })
        else listOf<Pair<String, (Item) -> Boolean>>("ทั้งหมด" to { _ -> true }, "รูปภาพ" to { it -> !it.video }, "วิดีโอ" to { it -> it.video }) +
            items.groupingBy { it.album }.eachCount().entries.sortedByDescending { it.value }.take(12).map { (album, _) -> album to { it: Item -> it.album == album } }

    private fun showGrid(all: List<Item>) {
        showingId = null; root.removeAllViews()
        val bar = topBar("คลังภาพ") { finish() }
        val searchBox = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; background = glass(22); setPadding(dp(12), 0, dp(6), 0) }
        val searchIcon = IconView(this, Icons.SEARCH, 2f)
        val field = EditText(this).apply { hint = "ค้นหา"; setHintTextColor(Glass.TEXT3); setTextColor(Color.WHITE); textSize = 15f; background = null; setSingleLine(); visibility = View.GONE; setText(queryText); setPadding(dp(6), 0, 0, 0) }
        val close = IconView(this, Icons.CLOSE, 2f).apply { tint = Glass.TEXT2; visibility = View.GONE; contentDescription = "ปิดการค้นหา"; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES; isClickable = true }
        searchBox.addView(searchIcon, dp(20), dp(20)); searchBox.addView(field, LinearLayout.LayoutParams(0, -1, 1f)); searchBox.addView(close, dp(40), dp(40))
        searchBox.contentDescription = "ค้นหา"
        bar.addView(searchBox, LinearLayout.LayoutParams(dp(48), dp(48)))
        fun morph(open: Boolean) {
            val from = searchBox.width; val to = if (open) bar.width - bar.paddingLeft - bar.paddingRight - dp(60) else dp(48)
            field.visibility = if (open) View.VISIBLE else View.GONE; close.visibility = field.visibility
            android.animation.ValueAnimator.ofInt(from, to).apply {
                duration = if (Glass.reduced(this@GalleryActivity)) 0 else 420; interpolator = Glass.EASE
                addUpdateListener { searchBox.layoutParams = (searchBox.layoutParams as LinearLayout.LayoutParams).apply { width = it.animatedValue as Int } }
                start()
            }
            val ime = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            if (open) { field.requestFocus(); ime.showSoftInput(field, 0) } else { field.setText(""); ime.hideSoftInputFromWindow(field.windowToken, 0) }
        }
        searchBox.setOnClickListener { if (field.visibility != View.VISIBLE) morph(true) }
        close.setOnClickListener { morph(false) }
        if (queryText.isNotEmpty()) searchBox.post { morph(true) }

        // Tabs: sliding glass highlight between Luma and the whole device.
        val tabs = FrameLayout(this).apply { background = glass(20, 0x1fffffff) }
        val knob = View(this).apply { background = glass(17, 0x38ffffff) }
        tabs.addView(knob, FrameLayout.LayoutParams(0, -1).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        val tabRow = LinearLayout(this)
        listOf("ถ่ายด้วย Luma", "ทั้งเครื่อง").forEachIndexed { i, t ->
            tabRow.addView(text(t, 14f, if ((i == 1) == device) Color.WHITE else Glass.TEXT2, true).apply {
                gravity = Gravity.CENTER; isClickable = true; contentDescription = t; isSelected = (i == 1) == device
                androidx.core.view.ViewCompat.setStateDescription(this, if ((i == 1) == device) "เลือกอยู่" else null)
                setOnClickListener { if ((i == 1) != device) { device = i == 1; category = 0; prefs.edit().putBoolean("gallery_device", device).apply(); slideKnob(knob, tabs, i); reload() } }
            }, LinearLayout.LayoutParams(0, -1, 1f))
        }
        tabs.addView(tabRow, -1, -1)
        tabs.post { slideKnob(knob, tabs, if (device) 1 else 0, false) }
        root.addView(tabs, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(dp(16), dp(4), dp(16), dp(8)) })

        if (device && !mediaGranted()) {
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = glass(24); setPadding(dp(20), dp(20), dp(20), dp(20))
                addView(IconView(this@GalleryActivity, Icons.ALBUM, 1.6f), dp(40), dp(40))
                addView(text("อนุญาตให้ Luma อ่านรูปและวิดีโอในเครื่อง", 15f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(4)) })
                addView(text("ใช้เพื่อแสดงในคลังภาพเท่านั้น ไม่มีการอัปโหลด", 12.5f, Glass.TEXT2).apply { gravity = Gravity.CENTER })
                addView(text("อนุญาต", 14f, 0xff1a1400.toInt(), true).apply { gravity = Gravity.CENTER; background = glass(20, Glass.ACCENT); isClickable = true; glassTouch(); setOnClickListener { mediaRequest.launch(mediaPermissions) } },
                    LinearLayout.LayoutParams(dp(140), dp(42)).apply { topMargin = dp(14) })
            }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(30), dp(16), 0) })
            return
        }
        val cats = categories(all); if (category >= cats.size) category = 0
        val strip = CenterStrip(this).apply { capsuleHeightDp = 40; contentDescription = "หมวด" }
        val labels = mutableListOf<TextView>()
        strip.setItems(cats.mapIndexed { i, (name, _) ->
            val t = text(name, 13.5f, if (i == category) Color.WHITE else Glass.TEXT2, i == category).apply { gravity = Gravity.CENTER; isClickable = true; maxLines = 1; contentDescription = "หมวด$name" }
            labels += t
            CenterStrip.Item(name, (t.paint.measureText(name) / resources.displayMetrics.density + 28).roundToInt().coerceAtLeast(64), t)
        }, category)
        strip.onLive = { live -> labels.forEachIndexed { i, t -> t.setTextColor(if (i == live) Color.WHITE else Glass.TEXT2) } }
        root.addView(strip, -1, dp(48))

        val grid = GridView(this).apply { numColumns = 3; horizontalSpacing = dp(3); verticalSpacing = dp(3); setPadding(dp(3), dp(6), dp(3), dp(3)); clipToPadding = false; selector = android.graphics.drawable.ColorDrawable(0) }
        val empty = text("", 15f, Glass.TEXT2).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        val frame = FrameLayout(this).apply { addView(grid, -1, -1); addView(empty, -1, -1) }
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        var shown = all
        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int) = shown[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, previous: View?, parent: ViewGroup?): View {
                val cell = (previous as? FrameLayout) ?: FrameLayout(this@GalleryActivity).apply {
                    clipToOutline = true; outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(6).toFloat()) } }
                    addView(ImageView(this@GalleryActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }, -1, -1)
                    addView(IconView(this@GalleryActivity, Icons.VIDEO, 2f).apply { shadow = true }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(6), dp(6)) })
                    addView(text("AI", 10f, Glass.ACCENT, true).apply { setShadowLayer(4f, 0f, 1f, 0x99000000.toInt()) }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(4), dp(6), 0) })
                }
                val image = cell.getChildAt(0) as ImageView; val item = shown[position]
                cell.getChildAt(1).visibility = if (item.video) View.VISIBLE else View.GONE
                cell.getChildAt(2).visibility = if (item.shot?.ai != null) View.VISIBLE else View.GONE
                image.tag = item.key; image.setImageDrawable(null); image.setBackgroundColor(PANEL)
                cell.layoutParams = AbsListView.LayoutParams(-1, (grid.width / 3).coerceAtLeast(dp(92)))
                cell.contentDescription = "${if (item.video) "วิดีโอ" else "รูป"} ${formatTime(item.time)} แตะค้างเพื่อดูตัวอย่าง"
                thumbs.execute {
                    val bitmap = thumbnail(item, 320)
                    runOnUiThread { if (!isDestroyed && image.tag == item.key) { image.alpha = 0f; image.setImageBitmap(bitmap); image.animate().alpha(1f).setDuration(160).start() } }
                }
                return cell
            }
        }
        grid.adapter = adapter
        fun apply() {
            val q = queryText.trim().lowercase()
            shown = all.filter(cats[category].second).filter { q.isEmpty() || q in "${it.name} ${it.album} ${formatTime(it.time)} ${it.shot?.captureNote ?: ""} ${it.shot?.reason?.description ?: ""}".lowercase() }
            adapter.notifyDataSetChanged()
            empty.text = if (all.isEmpty()) (if (device) "ไม่พบรูปหรือวิดีโอในเครื่อง" else "รูปที่ถ่ายด้วย Luma จะปรากฏที่นี่") else "ไม่พบรายการที่ตรงกัน"
            empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
            if (!Glass.reduced(this)) { grid.alpha = 0.4f; grid.animate().alpha(1f).setDuration(220).start() }
        }
        strip.onSelect = { i -> category = i; apply() }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { queryText = s.toString(); apply() }
        })
        apply()
        grid.setOnItemClickListener { _, _, p, _ -> open(shown[p]) }
        grid.setOnItemLongClickListener { _, v, p, _ -> showPeek(shown[p], v); true }
        grid.setOnTouchListener { _, e -> if (peek != null && (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL)) { closePeek(); true } else peek != null }
    }
    private fun slideKnob(knob: View, tabs: View, i: Int, animate: Boolean = true) {
        val w = (tabs.width - dp(6)) / 2
        knob.layoutParams = (knob.layoutParams as FrameLayout.LayoutParams).apply { width = w }
        val x = (i * w).toFloat()
        if (animate && !Glass.reduced(this)) knob.animate().translationX(x).setInterpolator(Glass.SPRING).setDuration(420).start() else knob.translationX = x
    }
    private fun thumbnail(item: Item, size: Int): Bitmap? = runCatching {
        if (item.shot == null && Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(item.uri, Size(size, size), null)
        else PhotoMedia.thumbnail(this, item.uri, size)
    }.getOrNull()
    private fun open(item: Item) { selected = item.shot?.id ?: item.key; useAi = false; reload() }

    // ---------- Long-press peek ----------
    private fun showPeek(item: Item, from: View) {
        closePeek()
        Glass.tick(from, HapticFeedbackConstants.LONG_PRESS)
        val dim = View(this).apply { setBackgroundColor(0x66000000); alpha = 0f }
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = glass(26, Glass.FILL_POP); setPadding(dp(8), dp(8), dp(8), dp(12)); visibility = View.INVISIBLE }
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true }
        card.addView(image, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.5f).toInt()))
        card.addView(text("${if (item.video) "วิดีโอ" else "รูปภาพ"} · ${formatTime(item.time)}", 13f, Glass.TEXT2).apply { setPadding(dp(8), dp(10), dp(8), 0) })
        val layer = FrameLayout(this).apply { addView(dim, -1, -1); addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { setMargins(dp(24), 0, dp(24), 0) }) }
        host.addView(layer, -1, -1); peek = layer
        dim.animate().alpha(1f).setDuration(220).start(); Glass.blur(root, 14f)
        thumbs.execute { val b = thumbnail(item, 1080); runOnUiThread { if (peek === layer) image.setImageBitmap(b) } }
        card.post {
            val loc = IntArray(2); from.getLocationInWindow(loc); val c = IntArray(2); card.getLocationInWindow(c)
            Liquid.show(card, (loc[0] - c[0] + from.width / 2f).coerceIn(0f, card.width.toFloat()), (loc[1] - c[1] + from.height / 2f).coerceIn(0f, card.height.toFloat()), true)
        }
        layer.setOnClickListener { closePeek() }
    }
    private fun closePeek() {
        val layer = peek as? FrameLayout ?: return; peek = null
        Glass.blur(root, 0f)
        layer.getChildAt(0).animate().alpha(0f).setDuration(200).start()
        val card = layer.getChildAt(1)
        Liquid.hide(card, card.width / 2f, card.height / 2f, false) { host.removeView(layer) }
    }

    // ---------- Device item ----------
    private fun showDeviceItem(uri: Uri) {
        showingId = uri.toString(); root.removeAllViews()
        topBar("ในเครื่อง") { selected = null; reload() }
        inspect = InspectView(this); root.addView(inspect, LinearLayout.LayoutParams(-1, 0, 1f))
        val video = contentResolver.getType(uri)?.startsWith("video") == true
        if (!video) inspect.show(uri)
        toggle = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(8), dp(8), dp(14)) }
        root.addView(toggle, -1, -2)
        if (video) chip("เปิดวิดีโอ", false) { runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } }
        chip("แชร์", false, Icons.SHARE) { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(contentResolver.getType(uri) ?: "image/*").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "แชร์")) }
        if (!Glass.reduced(this)) { inspect.alpha = 0f; inspect.animate().alpha(1f).setDuration(260).start() }
    }

    // ---------- Luma shot detail (logic unchanged) ----------
    private fun chip(label: String, active: Boolean, icon: String = "", click: () -> Unit) {
        val c = LinearLayout(this).apply {
            gravity = Gravity.CENTER; background = glass(22, if (active) 0x33ffd447 else Glass.FILL); setPadding(dp(16), 0, dp(16), 0)
            isClickable = true; contentDescription = label; glassTouch(); setOnClickListener { Glass.blurPulse(root, 4f, 200); click() }
            if (icon.isNotEmpty()) addView(IconView(this@GalleryActivity, icon, 2f).apply { tint = if (active) Glass.ACCENT else Color.WHITE }, dp(18), dp(18))
            addView(text(label, 14f, if (active) Glass.ACCENT else Color.WHITE, active).apply { setPadding(if (icon.isEmpty()) 0 else dp(6), 0, 0, 0) })
        }
        toggle.addView(c, LinearLayout.LayoutParams(-2, dp(48)).apply { setMargins(dp(4), 0, dp(4), 0) })
    }
    private fun formatTime(t: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(t))
    private fun showDetail(s: Shot) {
        if (showingId != s.id) {
            showingId = s.id; root.removeAllViews()
            topBar("ภาพถ่าย") { selected = null; useAi = false; reload() }.addView(roundButton(Icons.INFO, "ข้อมูลภาพ") { details(s) }, dp(48), dp(48))
            inspect = InspectView(this); root.addView(inspect, LinearLayout.LayoutParams(-1, 0, 1f))
            status = text("", 13f, Glass.TEXT2).apply { gravity = Gravity.CENTER; setPadding(dp(12), dp(6), dp(12), dp(6)) }
            root.addView(status, -1, -2)
            toggle = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(8), dp(14)) }
            root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; isFillViewport = true; addView(toggle) }, -1, -2)
            if (!Glass.reduced(this)) { inspect.alpha = 0f; inspect.animate().alpha(1f).setDuration(260).start() }
        }
        if (s.ai == null) useAi = false
        inspect.show(if (useAi) s.ai!! else s.uri)
        status.text = when (s.stage) {
            Stage.CAPTURING -> "กำลังถ่าย ${s.frames}/${s.requestedFrames} เฟรม"
            Stage.QUEUED -> "รอประมวลผล AI"
            Stage.RUNNING -> "กำลังสร้างสำเนา AI"
            Stage.FAILED -> "AI ยังไม่สำเร็จ · ${s.error}"
            Stage.DONE -> if (useAi) "AI ${s.aiVersion}   ·   ${pixelsText(scaledSize(s.size, TARGET_PIXELS).pixels)}" else "ต้นฉบับ   ·   ${pixelsText(s.size.pixels)}"
            Stage.ORIGINAL -> if (s.output == CaptureOutput.RAW) "ภาพตัวอย่าง RAW · DNG เก็บแยก" else "${pixelsText(s.size.pixels)}   ·   ${s.reason.description}"
        }
        toggle.removeAllViews()
        if (s.raw != null) {
            chip("เปิด DNG", false, Icons.RAW) {
                try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(s.raw, "image/x-adobe-dng").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                catch (_: android.content.ActivityNotFoundException) { notice("บันทึก DNG ใน Pictures/Luma แล้ว เปิดด้วยแอปที่รองรับ RAW") }
            }
        }
        if (s.ai != null) {
            chip(if (s.output == CaptureOutput.RAW) "ตัวอย่าง RAW" else "ต้นฉบับ", !useAi) { useAi = false; showDetail(s) }
            chip("ภาพ AI", useAi, Icons.AI) { useAi = true; showDetail(s) }
        } else if (s.stage != Stage.RUNNING && s.stage != Stage.QUEUED && s.stage != Stage.CAPTURING) {
            chip(if (s.stage == Stage.FAILED) "ลอง AI อีกครั้ง" else "ปรับด้วย AI", false, Icons.AI) {
                luma.fileIo.execute {
                    val current = luma.shots.get(s.id)
                    if (current != null && current.stage in setOf(Stage.ORIGINAL, Stage.FAILED)) {
                        luma.shots.update(s.id, Stage.QUEUED)
                        EnhanceWorker.enqueue(applicationContext, s.id, replace = true)
                    }
                }
            }
        }
        chip("แชร์", false, Icons.SHARE) { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, if (useAi) s.ai else s.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "แชร์")) }
    }
    private fun details(s: Shot) {
        val ai = scaledSize(s.size, TARGET_PIXELS)
        val text = buildList {
            add(formatTime(s.time)); add("ต้นฉบับ  ${s.size.w} × ${s.size.h} px")
            add("กล้อง${if (s.rear) "หลัง" else "หน้า"}  ·  ${zoomText(s.zoom)}")
            add(if (s.softwareCrop) "ซูมครอปจาก 1×" else "ซูมด้วยระบบกล้อง")
            add("รูปแบบ ${s.output.label}")
            if (s.output.needsRaw) add("DNG เก็บข้อมูลเซนเซอร์เต็มตามที่กล้องส่ง ภาพตัวอย่าง JPEG อาจมีครอป/ซูม")
            if (s.output.enhance) { add("รับ ${s.frames}/${s.requestedFrames} เฟรม · ผ่านการจัดแนว ${s.fusedFrames} เฟรม"); add("ส่วนที่เคลื่อนไหวมากใช้ภาพอ้างอิง") }
            s.captureNote?.let { add(it) }
            add("Physical camera ID  ${s.lens ?: "เครื่องไม่รายงาน"}")
            add("พื้นที่ครอป  ${s.cropped?.let(::pixelsText) ?: "เครื่องไม่รายงาน"}")
            add(s.reason.description)
            if (s.ai != null) { add("AI ${s.aiVersion}  ${ai.w} × ${ai.h} px"); add("เวลาประมวลผล  ${s.elapsed} ms") }
        }.joinToString("\n")
        AlertDialog.Builder(this).setTitle("ข้อมูลภาพ").setMessage(text).setPositiveButton("ปิด", null).show()
    }
}

