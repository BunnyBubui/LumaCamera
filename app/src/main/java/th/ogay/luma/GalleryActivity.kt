package th.ogay.luma

import android.Manifest
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.Size
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.text.TextUtils
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import th.ogay.luma.ai.EnhanceUi

/**
 * Glass gallery. Two sources: everything made with Lumora (photos, videos and their reports — this replaces the old
 * file-list "คลัง Lumora") and the whole device, which adds a sideways strip of device albums with a "ดูเพิ่มเติม" page.
 * Long-press peeks; the viewer swipes between items and always has a button back to the full grid.
 */
class GalleryActivity : ComponentActivity() {
    /** One grid cell: a Lumora shot, a Lumora video/file from the media ledger, or a device photo/video. */
    class Item(val key: String, val uri: Uri, val time: Long, val video: Boolean, val name: String, val album: String, val shot: Shot?, val mime: String = "", val note: String = "") {
        val file get() = shot == null && !video && !mime.startsWith("image/") && mime.isNotEmpty()
    }
    class Album(val name: String, val count: Int, val cover: Item)
    private var selected: String? = null
    private var useAi = false
    private var showingId: String? = null
    private var device = false
    private var category = 0
    private var album: String? = null
    private var allAlbums = false
    private var queryText = ""
    private var lastItems: List<Item> = emptyList()
    private lateinit var host: FrameLayout
    private lateinit var root: LinearLayout
    private lateinit var inspect: InspectView
    private lateinit var ring: AiRing
    private lateinit var toggle: LinearLayout
    private lateinit var status: TextView
    private var readVersion = 0
    private val thumbs = Executors.newFixedThreadPool(3)
    /** Decoded thumbnails, so scrolling back up shows them at once instead of decoding again (about 1/8 of the heap). */
    private val cache = object : android.util.LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt()) { override fun sizeOf(k: String, v: Bitmap) = v.byteCount / 1024 }
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
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        selected = state?.getString("id"); useAi = state?.getBoolean("ai") ?: false
        device = state?.getBoolean("device") ?: when (intent.getStringExtra("tab")) { "luma" -> false; "device" -> true; else -> prefs.getBoolean("gallery_device", false) }
        album = state?.getString("album")
        intent.getStringExtra("category")?.let { want -> category = if (want == "วิดีโอ") LUMA_CATS.indexOf("วิดีโอ").coerceAtLeast(0) else 0 }
        host = FrameLayout(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        host.addView(root, -1, -1)
        installRoot(host)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    sortMenu.showing -> sortMenu.dismiss(true)
                    peek != null -> closePeek()
                    selected != null -> { selected = null; useAi = false; reload() }
                    allAlbums -> { allAlbums = false; reload() }
                    else -> finish()
                }
            }
        })
        luma.shots.changes.observe(this) { if (!device || selected != null) reload() }
        luma.shots.progress.observe(this) { p: Pair<String, Int> -> val id = p.first; val percent = p.second
            if (selected == id && showingId == id && ::ring.isInitialized && ring.visibility == View.VISIBLE) ring.update(percent.toFloat(), ring.caption)
        }
        // Opened from the camera's thumbnail: show the newest photo first; the grid is one tap (or Back) away.
        if (state == null && !device && intent.getStringExtra("tab") == null) {
            luma.fileIo.execute {
                val first = luma.shots.latest()?.id
                runOnUiThread { if (!isDestroyed) { selected = first; reload() } }
            }
        } else reload()
    }
    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putString("id", selected); out.putBoolean("ai", useAi); out.putBoolean("device", device); out.putString("album", album) }
    override fun onDestroy() { thumbs.shutdownNow(); super.onDestroy() }
    private fun mediaGranted() = mediaPermissions.any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    /** Android 14+: the person allowed only some photos. */
    private fun partialAccess() = Build.VERSION.SDK_INT >= 34 &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED

    private fun reload() {
        val id = selected; val version = ++readVersion
        luma.readIo.execute {
            val shot = id?.let { luma.shots.get(it) }
            val items = when {
                device -> if (mediaGranted()) deviceItems() else emptyList()
                else -> lumaItems()
            }
            runOnUiThread {
                if (version == readVersion && !isDestroyed) {
                    lastItems = items
                    when {
                        shot != null -> showDetail(shot)
                        id?.startsWith("device:") == true -> showDeviceItem(Uri.parse(id.removePrefix("device:")))
                        id?.startsWith("ledger:") == true -> items.firstOrNull { it.key == id }?.let { showLedgerItem(it) } ?: run { selected = null; showGrid(items) }
                        allAlbums -> showAlbums(items)
                        else -> { selected = null; showGrid(items) }
                    }
                }
            }
        }
    }
    /** Photos from the shot database plus videos and files from the media ledger. Video reports (.json) stay with their video. */
    private fun lumaItems(): List<Item> {
        val shots = luma.shots.all().map { Item(it.id, it.uri, it.time, false, "", "Lumora", it) }
        val known = shots.map { it.uri.toString() }.toSet()
        val ledger = MediaLedger.all(this).filter { it.uri.toString() !in known && it.mime != "application/json" }.map {
            Item("ledger:${it.uri}", it.uri, it.time, it.mime.startsWith("video/"), it.title, "Lumora", null, it.mime, it.note)
        }
        return (shots + ledger).sortedByDescending { it.time }
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
    private fun albums(items: List<Item>): List<Album> =
        items.groupBy { it.album }.map { (name, list) -> Album(name, list.size, list.first()) }.sortedByDescending { it.count }

    // ---------- Shared pieces ----------
    private fun topBar(title: String, back: () -> Unit): LinearLayout {
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(6)) }
        bar.addView(roundButton(Icons.CHEVRON_LEFT, "ย้อนกลับ", back), dp(48), dp(48))
        bar.addView(text(title, 20f, Color.WHITE, true).apply { setPadding(dp(12), 0, dp(4), 0); maxLines = 3; ellipsize = null; tag = "title" }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(bar, -1, -2); return bar
    }
    // A brighter fill than the default glass so the top controls read clearly on the dark page.
    private fun roundButton(icon: String, desc: String, click: () -> Unit) = FrameLayout(this).apply {
        background = glass(22, 0x40ffffff); contentDescription = desc; isClickable = true; glassTouch()
        addView(IconView(this@GalleryActivity, icon, 2.1f), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)); setOnClickListener { click() }
    }
    private fun rounded(v: View, r: Int) { v.clipToOutline = true; v.outlineProvider = object : ViewOutlineProvider() { override fun getOutline(view: View, o: Outline) { o.setRoundRect(0, 0, view.width, view.height, dp(r).toFloat()) } } }
    private fun sectionHead(title: String, end: String = "") = LinearLayout(this).apply {
        gravity = Gravity.BOTTOM; setPadding(dp(16), dp(10), dp(16), dp(8))
        addView(text(title, 15f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        addView(text(end, 12.5f, Glass.TEXT2))
    }
    private val SORTS = listOf("ใหม่ก่อน", "เก่าก่อน", "วิดีโอก่อน", "AI ก่อน")
    private val sortMenu by lazy { GlassMenu(host) }
    private var sortOrder: Int
        get() = prefs.getInt("gallery_sort", 0).coerceIn(0, 3)
        set(v) { prefs.edit().putInt("gallery_sort", v).apply() }
    private val LUMA_CATS = listOf("ทั้งหมด", "AI", "RAW", "วิดีโอ", "พิเศษ", "ไฟล์")
    private fun categories(items: List<Item>): List<Pair<String, (Item) -> Boolean>> =
        if (!device) listOf<Pair<String, (Item) -> Boolean>>(
            "ทั้งหมด" to { it -> !it.file }, "AI" to { it -> it.shot?.ai != null }, "RAW" to { it -> it.shot?.output?.needsRaw == true },
            "วิดีโอ" to { it -> it.video }, "พิเศษ" to { it -> it.shot?.captureNote != null }, "ไฟล์" to { it -> it.file })
        else listOf<Pair<String, (Item) -> Boolean>>("ทั้งหมด" to { _ -> true }, "รูปภาพ" to { it -> !it.video }, "วิดีโอ" to { it -> it.video })

    // ---------- Grid ----------
    private fun showGrid(all: List<Item>) {
        showingId = null; root.removeAllViews()
        val bar = topBar("แกลเลอรี") { finish() }
        val searchBox = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; background = glass(22, 0x40ffffff); setPadding(dp(14), 0, dp(6), 0) }
        val searchIcon = IconView(this, Icons.SEARCH, 2f)
        val field = EditText(this).apply { hint = "ค้นหา"; setHintTextColor(Glass.TEXT3); setTextColor(Color.WHITE); textSize = 15f; background = null; setSingleLine(); visibility = View.GONE; setText(queryText); setPadding(dp(6), 0, 0, 0) }
        val close = IconView(this, Icons.CLOSE, 2f).apply { tint = Glass.TEXT2; visibility = View.GONE; contentDescription = "ปิดการค้นหา"; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES; isClickable = true }
        searchBox.addView(searchIcon, dp(20), dp(20)); searchBox.addView(field, LinearLayout.LayoutParams(0, -1, 1f)); searchBox.addView(close, dp(40), dp(40))
        searchBox.contentDescription = "ค้นหา"
        bar.addView(searchBox, LinearLayout.LayoutParams(dp(48), dp(48)))
        fun morph(open: Boolean) {
            val from = searchBox.width; val to = if (open) bar.width - bar.paddingLeft - bar.paddingRight - dp(60) else dp(48)
            field.visibility = if (open) View.VISIBLE else View.GONE; close.visibility = field.visibility
            bar.findViewWithTag<View>("title")?.let { t -> t.animate().alpha(if (open) 0f else 1f).setDuration(180).start() }
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

        // Tabs: sliding glass highlight between Lumora and the whole device.
        var tabsDrag = false
        lateinit var knobRef: View
        val tabs = object : FrameLayout(this) {
            var downX = 0f
            private fun pos(x: Float) = ((x - dp(3) - (width - dp(6)) / 4f) / ((width - dp(6)) / 2f)).coerceIn(0f, 1f) * ((width - dp(6)) / 2f)
            override fun onInterceptTouchEvent(e: android.view.MotionEvent): Boolean {
                when (e.actionMasked) { android.view.MotionEvent.ACTION_DOWN -> downX = e.x
                    android.view.MotionEvent.ACTION_MOVE -> if (!tabsDrag && Math.abs(e.x - downX) > dp(8)) { tabsDrag = true; parent.requestDisallowInterceptTouchEvent(true); knobRef.animate().cancel() } }
                return tabsDrag
            }
            override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
                if (!tabsDrag && e.actionMasked != android.view.MotionEvent.ACTION_DOWN) return false
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_MOVE -> knobRef.translationX = pos(e.x)
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        tabsDrag = false
                        val i = if (pos(e.x) > (width - dp(6)) / 4f) 1 else 0
                        slideKnob(knobRef, this, i)
                        if ((i == 1) != device) { device = i == 1; category = 0; album = null; prefs.edit().putBoolean("gallery_device", device).apply(); reload() }
                    }
                }
                return true
            }
        }.apply { background = glass(20, 0x24ffffff) }
        val knob = View(this).apply { background = glass(17, Glass.ACCENT_SOFT) }; knobRef = knob
        tabs.addView(knob, FrameLayout.LayoutParams(0, -1).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        val tabRow = LinearLayout(this)
        listOf("ถ่ายด้วย Lumora", "ทั้งเครื่อง").forEachIndexed { i, t ->
            tabRow.addView(text(t, 14f, if ((i == 1) == device) Color.WHITE else Glass.TEXT2, true).apply {
                gravity = Gravity.CENTER; isClickable = true; contentDescription = t; isSelected = (i == 1) == device
                androidx.core.view.ViewCompat.setStateDescription(this, if ((i == 1) == device) "เลือกอยู่" else null)
                setOnClickListener { if ((i == 1) != device) { device = i == 1; category = 0; album = null; prefs.edit().putBoolean("gallery_device", device).apply(); slideKnob(knob, tabs, i); reload() } }
            }, LinearLayout.LayoutParams(0, -1, 1f))
        }
        tabs.addView(tabRow, -1, -1)
        tabs.post { slideKnob(knob, tabs, if (device) 1 else 0, false) }
        root.addView(tabs, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(dp(16), dp(4), dp(16), dp(8)) })

        if (device && !mediaGranted()) {
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = glass(24); setPadding(dp(20), dp(20), dp(20), dp(20))
                addView(IconView(this@GalleryActivity, Icons.ALBUM, 1.6f), dp(40), dp(40))
                addView(text("อนุญาตให้ Lumora อ่านรูปและวิดีโอในเครื่อง", 15f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(4)) })
                addView(text("ใช้เพื่อแสดงในแกลเลอรีเท่านั้น ไม่มีการอัปโหลด", 12.5f, Glass.TEXT2).apply { gravity = Gravity.CENTER })
                addView(text("อนุญาต", 14f, Glass.ON_ACCENT, true).apply { gravity = Gravity.CENTER; background = glass(20, Glass.ACCENT); isClickable = true; glassTouch(); setOnClickListener { mediaRequest.launch(mediaPermissions) } },
                    LinearLayout.LayoutParams(dp(140), dp(48)).apply { topMargin = dp(14) })
            }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(30), dp(16), 0) })
            return
        }

        // Header part that scrolls away with the grid: partial-access notice and the album strip.
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (device && partialAccess()) header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = glass(22); setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(text("Lumora เห็นเฉพาะรูปที่คุณเลือกให้ อนุญาตทั้งหมดเพื่อดูทุกอัลบั้ม", 13f, Color.WHITE).apply { maxLines = 4 })
            addView(LinearLayout(this@GalleryActivity).apply {
                setPadding(0, dp(10), 0, 0)
                addView(text("เลือกรูปเพิ่ม", 13.5f, Color.WHITE, true).apply { gravity = Gravity.CENTER; background = glass(16, 0x1affffff); isClickable = true; glassTouch(); setOnClickListener { mediaRequest.launch(mediaPermissions) } }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(8) })
                addView(text("อนุญาตทั้งหมด", 13.5f, Glass.ON_ACCENT, true).apply { gravity = Gravity.CENTER; background = glass(16, Glass.ACCENT); isClickable = true; glassTouch()
                    setOnClickListener { runCatching { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } } }, LinearLayout.LayoutParams(0, dp(48), 1f))
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), 0, dp(16), dp(8)) })
        val albumPart = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (device) {
            val list = albums(all)
            if (album != null && list.none { it.name == album }) album = null
            albumPart.addView(sectionHead("อัลบั้มในเครื่อง", "${list.size} อัลบั้ม"))
            albumPart.addView(albumStrip(list), -1, dp(150))
            header.addView(albumPart, -1, -2)
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
        header.addView(strip, -1, dp(48))
        val gridHead = sectionHead("", ""); header.addView(gridHead)
        val gridTitle = gridHead.getChildAt(0) as TextView

        val grid = GridView(this).apply {
            numColumns = 3; horizontalSpacing = dp(5); verticalSpacing = dp(5); setPadding(dp(8), 0, dp(8), dp(8)); clipToPadding = false; selector = android.graphics.drawable.ColorDrawable(0)
            // Longer, softer flings.
            setFriction(android.view.ViewConfiguration.getScrollFriction() * 0.75f); isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_ALWAYS
        }
        val empty = text("", 15f, Glass.TEXT2).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        // GridView cannot hold a header, so the header sits above it in a column that shares the space.
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(header, -1, -2); addView(FrameLayout(this@GalleryActivity).apply { addView(grid, -1, -1); addView(empty, -1, -1) }, LinearLayout.LayoutParams(-1, 0, 1f)) }
        root.addView(column, LinearLayout.LayoutParams(-1, 0, 1f))
        // Scrolling into the grid folds the album strip away; it returns at the top. The gap between the two
        // thresholds keeps the resize from bouncing back and forth.
        grid.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(v: AbsListView, s: Int) {}
            override fun onScroll(v: AbsListView, first: Int, visible: Int, total: Int) {
                if (!device) return
                val atTop = first == 0 && (v.childCount == 0 || v.getChildAt(0).top >= v.paddingTop)
                if (first >= 6 && albumPart.visibility == View.VISIBLE) albumPart.visibility = View.GONE
                else if (atTop && albumPart.visibility != View.VISIBLE) albumPart.visibility = View.VISIBLE
            }
        })
        var shown = all
        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int) = shown[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, previous: View?, parent: ViewGroup?): View = cell(previous as? FrameLayout, shown[position], grid.width)
        }
        grid.adapter = adapter
        fun apply() {
            val q = queryText.trim().lowercase()
            val picked = all.filter(cats[category].second).filter { album == null || it.album == album }
                .filter { q.isEmpty() || q in "${it.name} ${it.album} ${formatTime(it.time)} ${it.note} ${it.shot?.captureNote ?: ""} ${it.shot?.reason?.description ?: ""}".lowercase() }
            shown = when (sortOrder) {
                1 -> picked.sortedBy { it.time }
                2 -> picked.sortedWith(compareBy<Item> { !it.video }.thenByDescending { it.time })
                3 -> picked.sortedWith(compareBy<Item> { it.shot?.ai == null }.thenByDescending { it.time })
                else -> picked
            }
            adapter.notifyDataSetChanged()
            gridTitle.text = album ?: if (device) "รูปทั้งหมด" else "ถ่ายด้วย Lumora"
            (gridHead.getChildAt(1) as TextView).text = "${shown.size} รายการ · ${SORTS[sortOrder]} ▾"
            empty.text = if (all.isEmpty()) (if (device) "ไม่พบรูปหรือวิดีโอในเครื่อง" else "รูปและวิดีโอที่ถ่ายด้วย Lumora จะปรากฏที่นี่") else "ไม่พบรายการที่ตรงกัน"
            empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
            if (!Glass.reduced(this)) { grid.alpha = 0.4f; grid.animate().alpha(1f).setDuration(220).start() }
        }
        strip.onSelect = { i -> category = i; apply() }
        // Sort order: a glass dropdown on the count at the right of the grid heading.
        (gridHead.getChildAt(1) as TextView).apply {
            isClickable = true; minHeight = dp(40); gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), 0, 0, 0); contentDescription = "เรียงลำดับรายการ"
            setOnClickListener { v -> sortMenu.show(v, "เรียงตาม", SORTS.map { GlassMenu.Option(it) }, sortOrder) { i -> sortOrder = i; prefs.edit().putInt("gallery_sort", i).apply(); apply(); grid.setSelection(0) } }
        }
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

    /** One grid cell. A thumbnail that cannot be decoded keeps a visible placeholder instead of an empty hole. */
    private fun cell(previous: FrameLayout?, item: Item, gridWidth: Int): View {
        val cell = previous ?: FrameLayout(this).apply {
            rounded(this, 14)
            addView(View(this@GalleryActivity).apply { background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xff1b2747.toInt(), 0xff0d1428.toInt())) }, -1, -1)
            addView(IconView(this@GalleryActivity, Icons.ALBUM, 1.6f).apply { tint = 0x66ffffff }, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            addView(ImageView(this@GalleryActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }, -1, -1)
            addView(IconView(this@GalleryActivity, Icons.VIDEO, 2f).apply { shadow = true }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(6), dp(6)) })
            addView(text("AI", 10f, Glass.ACCENT, true).apply { setShadowLayer(4f, 0f, 1f, 0x99000000.toInt()) }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(4), dp(6), 0) })
            addView(text("", 10.5f, Color.WHITE).apply { maxLines = 2; gravity = Gravity.CENTER; setPadding(dp(6), 0, dp(6), dp(8)) }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        val placeholder = cell.getChildAt(1) as IconView; val image = cell.getChildAt(2) as ImageView
        cell.getChildAt(3).visibility = if (item.video) View.VISIBLE else View.GONE
        cell.getChildAt(4).visibility = if (item.shot?.ai != null) View.VISIBLE else View.GONE
        val caption = cell.getChildAt(5) as TextView; caption.text = if (item.file) item.name else ""; caption.visibility = if (item.file) View.VISIBLE else View.GONE
        placeholder.path = if (item.file) Icons.DOC else if (item.video) Icons.VIDEO else Icons.ALBUM; placeholder.visibility = View.VISIBLE
        image.tag = item.key; image.setImageDrawable(null); image.animate().cancel(); image.alpha = 1f
        cell.layoutParams = AbsListView.LayoutParams(-1, ((gridWidth - dp(26)) / 3).coerceAtLeast(dp(92))); cell.invalidateOutline(); cell.post { cell.invalidateOutline() }
        cell.contentDescription = "${if (item.video) "วิดีโอ" else if (item.file) "ไฟล์ ${item.name}" else "รูป"} ${formatTime(item.time)} แตะค้างเพื่อดูตัวอย่าง"
        val cached = cache.get(item.key + 320)
        if (cached != null) { placeholder.visibility = View.GONE; image.setImageBitmap(cached) }
        else if (!item.file) {
            // New cells rise in slightly as they scroll into view.
            if (!Glass.reduced(this)) { cell.scaleX = 0.93f; cell.scaleY = 0.93f; cell.alpha = 0.5f; cell.animate().scaleX(1f).scaleY(1f).alpha(1f).setInterpolator(Glass.EASE).setDuration(260).start() }
            thumbs.execute {
            val bitmap = thumbnail(item, 320)
            runOnUiThread {
                if (!isDestroyed && image.tag == item.key && bitmap != null) {
                    placeholder.visibility = View.GONE; image.setImageBitmap(bitmap)
                    if (!Glass.reduced(this)) { image.alpha = 0f; image.animate().alpha(1f).setDuration(160).start() }
                }
            }
        }
        }
        return cell
    }

    /** Sideways strip of album cards; tapping filters the grid (tap again for all), the last card opens every album. */
    private fun albumStrip(list: List<Album>): View {
        val row = LinearLayout(this).apply { setPadding(dp(12), 0, dp(12), 0) }
        fun card(name: String, sub: String, on: Boolean, cover: Item?, desc: String, click: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; isClickable = true; isFocusable = true; contentDescription = desc; setPadding(dp(4), 0, dp(4), 0)
            isSelected = on; androidx.core.view.ViewCompat.setStateDescription(this, if (on) "เลือกอยู่" else null)
            val frame = FrameLayout(this@GalleryActivity).apply { background = glass(20, if (on) Glass.ACCENT_SOFT else 0x1affffff); setPadding(dp(if (on) 3 else 0), dp(if (on) 3 else 0), dp(if (on) 3 else 0), dp(if (on) 3 else 0)) }
            val img = ImageView(this@GalleryActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            rounded(img, 18); frame.addView(img, -1, -1)
            if (cover == null) frame.addView(IconView(this@GalleryActivity, Icons.CHEVRON_RIGHT, 2.2f), FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
            else { img.tag = cover.key; thumbs.execute { val b = thumbnail(cover, 240); runOnUiThread { if (!isDestroyed && img.tag == cover.key && b != null) img.setImageBitmap(b) } } }
            addView(frame, dp(104), dp(104))
            addView(text(name, 13f, if (on) Glass.ACCENT else Color.WHITE, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(2), dp(6), 0, 0) }, dp(104), -2)
            addView(text(sub, 12f, Glass.TEXT2).apply { maxLines = 1; setPadding(dp(2), 0, 0, 0) }, dp(104), -2)
            glassTouch(); setOnClickListener { click() }
        }
        list.take(12).forEach { a ->
            row.addView(card(a.name, "${a.count} รายการ", album == a.name, a.cover, "อัลบั้ม ${a.name} ${a.count} รายการ") { album = if (album == a.name) null else a.name; reload() })
        }
        row.addView(card("ดูเพิ่มเติม", "อัลบั้มทั้งหมด", false, null, "ดูอัลบั้มทั้งหมด") { allAlbums = true; reload() })
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false; addView(row) }
    }

    /** Every device album as a two-column grid of covers. */
    private fun showAlbums(items: List<Item>) {
        showingId = null; root.removeAllViews()
        topBar("อัลบั้มทั้งหมด") { allAlbums = false; reload() }
        val list = albums(items)
        val grid = GridView(this).apply { numColumns = 2; horizontalSpacing = dp(12); verticalSpacing = dp(16); setPadding(dp(16), dp(8), dp(16), dp(24)); clipToPadding = false; selector = android.graphics.drawable.ColorDrawable(0) }
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = list.size
            override fun getItem(p: Int) = list[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, previous: View?, parent: ViewGroup?): View {
                val a = list[p]
                val box = (previous as? LinearLayout) ?: LinearLayout(this@GalleryActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    val img = ImageView(this@GalleryActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = glass(20, 0x1affffff) }
                    rounded(img, 20); addView(img, -1, dp(150))
                    addView(text("", 14f, Color.WHITE, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(2), dp(8), 0, 0) })
                    addView(text("", 12f, Glass.TEXT2).apply { setPadding(dp(2), 0, 0, 0) })
                }
                val img = box.getChildAt(0) as ImageView; img.setImageDrawable(null); img.tag = a.cover.key
                (box.getChildAt(1) as TextView).text = a.name; (box.getChildAt(2) as TextView).text = "${a.count} รายการ"
                box.contentDescription = "อัลบั้ม ${a.name} ${a.count} รายการ"
                thumbs.execute { val b = thumbnail(a.cover, 360); runOnUiThread { if (!isDestroyed && img.tag == a.cover.key && b != null) img.setImageBitmap(b) } }
                return box
            }
        }
        grid.setOnItemClickListener { _, _, p, _ -> album = list[p].name; allAlbums = false; category = 0; reload() }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun slideKnob(knob: View, tabs: View, i: Int, animate: Boolean = true) {
        val w = (tabs.width - dp(6)) / 2
        knob.layoutParams = (knob.layoutParams as FrameLayout.LayoutParams).apply { width = w }
        val x = (i * w).toFloat()
        if (animate && !Glass.reduced(this)) knob.animate().translationX(x).setInterpolator(Glass.SPRING).setDuration(420).start() else knob.translationX = x
    }
    /** Thumbnail with fallbacks: the system thumbnail, Lumora's own decoder, a sampled decode, then a video frame. */
    private fun thumbnail(item: Item, size: Int): Bitmap? {
        cache.get(item.key + size)?.let { return it }
        fun system() = if (Build.VERSION.SDK_INT >= 29) runCatching { contentResolver.loadThumbnail(item.uri, Size(size, size), null) }.getOrNull() else null
        fun own() = runCatching { PhotoMedia.thumbnail(this, item.uri, size) }.getOrNull()
        fun sampled() = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(item.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var s = 1; while (bounds.outWidth / (s * 2) >= size && bounds.outHeight / (s * 2) >= size) s *= 2
            contentResolver.openInputStream(item.uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }
        }.getOrNull()
        fun frame() = runCatching { MediaMetadataRetriever().let { r -> try { r.setDataSource(this, item.uri); r.getFrameAtTime(0)?.let { b -> val k = size.toFloat() / maxOf(b.width, b.height); if (k < 1f) Bitmap.createScaledBitmap(b, (b.width * k).roundToInt().coerceAtLeast(1), (b.height * k).roundToInt().coerceAtLeast(1), true).also { if (it !== b) b.recycle() } else b } } finally { r.release() } } }.getOrNull()
        // RAW: the DNG itself when its preview JPEG is gone or the item is a DNG file.
        fun raw() = (item.shot?.raw ?: item.uri.takeIf { item.mime.contains("dng") || item.uri.toString().endsWith(".dng", true) })?.let { u -> runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var s = 1; while (bounds.outWidth / (s * 2) >= size && bounds.outHeight / (s * 2) >= size) s *= 2
            contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }
        }.getOrNull() }
        val bitmap = if (item.video) system() ?: frame() else if (item.shot != null) own() ?: system() ?: sampled() ?: raw() else system() ?: own() ?: sampled() ?: raw()
        return bitmap?.let { cache.put(item.key + size, it); it }
    }
    private fun open(item: Item) {
        if (item.file) { MediaLedger.view(this, item.uri, item.mime); return }
        if (item.video && item.key.startsWith("device:")) { VideoPlayerActivity.open(this, item.uri); return }
        selected = item.shot?.id ?: item.key; useAi = false; reload()
    }
    /** Next or previous item of the last grid, for swiping in the viewer. */
    private fun step(dir: Int) {
        val visible = lastItems.filter { !it.file }
        val i = visible.indexOfFirst { (it.shot?.id ?: it.key) == selected }
        val next = visible.getOrNull(i + dir) ?: return
        selected = next.shot?.id ?: next.key; useAi = false
        // Only the picture moves; the bars stay still. It carries on from where the finger left it and the next one glides in.
        val moving = if (::inspect.isInitialized && inspect.parent === root) inspect else null
        if (moving != null && !Glass.reduced(this)) {
            val w = moving.width.coerceAtLeast(1).toFloat()
            moving.turning = true
            moving.animate().translationX(-dir * (w * 0.55f).coerceAtLeast(kotlin.math.abs(moving.translationX) + dp(40))).alpha(0f).setDuration(130).setInterpolator(Glass.EASE).withEndAction {
                reload()
                if (::inspect.isInitialized && inspect.parent === root) { inspect.turning = false; inspect.translationX = dir * w * 0.35f; inspect.alpha = 0f; inspect.animate().translationX(0f).alpha(1f).setDuration(260).setInterpolator(Glass.SPRING).start() }
            }.start()
        } else reload()
    }
    /** Viewer top bar: back to the grid, plus a grid button so the full library is always one tap away. */
    private fun viewerBar(title: String): LinearLayout {
        val bar = topBar(title) { selected = null; useAi = false; reload() }
        bar.addView(roundButton(Icons.LIBRARY, "ดูรูปทั้งหมด") { selected = null; useAi = false; reload() }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(8) })
        return bar
    }
    private fun viewer(): InspectView = InspectView(this).also { v ->
        v.onSwipe = { step(it) }
        ring = AiRing(this)
        val frame = FrameLayout(this).apply { addView(v, FrameLayout.LayoutParams(-1, -1)); addView(ring, FrameLayout.LayoutParams(-1, -1)) }
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    // ---------- Long-press peek ----------
    private fun showPeek(item: Item, from: View) {
        closePeek()
        Glass.tick(from, HapticFeedbackConstants.LONG_PRESS)
        val dim = View(this).apply { setBackgroundColor(0x66000000); alpha = 0f }
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = glass(26, Glass.FILL_POP); setPadding(dp(8), dp(8), dp(8), dp(12)); visibility = View.INVISIBLE }
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true }
        card.addView(image, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.5f).toInt()))
        card.addView(text("${if (item.video) "วิดีโอ" else "รูปภาพ"} · ${formatTime(item.time)}", 13f, Glass.TEXT2).apply { setPadding(dp(8), dp(10), dp(8), 0) })
        val more = listOfNotNull(item.name.takeIf { it.isNotBlank() }, item.album.takeIf { it.isNotBlank() },
            item.shot?.let { pixelsText(it.size.pixels) }, item.shot?.takeIf { it.ai != null }?.let { "AI ×${it.aiScale.coerceAtLeast(1)} · ${pixelsText(it.aiSize.pixels)}" }).joinToString(" · ")
        if (more.isNotEmpty()) card.addView(text(more, 12f, Glass.TEXT3).apply { setPadding(dp(8), dp(4), dp(8), 0); maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
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
        viewerBar("ในเครื่อง")
        inspect = viewer()
        val video = contentResolver.getType(uri)?.startsWith("video") == true
        if (!video) inspect.show(uri)
        toggle = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(8), dp(8), dp(14)) }
        root.addView(toggle, -1, -2)
        if (video) chip("เล่นวิดีโอ", false, Icons.VIDEO) { VideoPlayerActivity.open(this, uri) }
        chip("แชร์", false, Icons.SHARE) { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(contentResolver.getType(uri) ?: "image/*").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "แชร์")) }
        if (!Glass.reduced(this)) { inspect.alpha = 0f; inspect.animate().alpha(1f).setDuration(260).start() }
    }

    // ---------- Lumora video or file from the ledger ----------
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun showLedgerItem(item: Item) {
        showingId = item.key; root.removeAllViews()
        viewerBar(if (item.video) "วิดีโอ" else "ไฟล์")
        val frame = FrameLayout(this).apply { setBackgroundColor(Glass.NAVY_DEEP) }
        val still = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        frame.addView(still, -1, -1)
        frame.addView(FrameLayout(this).apply { background = glass(36, 0x40ffffff); contentDescription = "เล่นวิดีโอ"; isClickable = true; glassTouch()
            addView(IconView(this@GalleryActivity, "M9 6.5v11l9-5.5z", 2f), FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER)); setOnClickListener { VideoPlayerActivity.open(this@GalleryActivity, item.uri) } },
            FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER))
        // Swipe sideways on the still to move between items, like the photo viewer.
        val swipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(a: MotionEvent?, b: MotionEvent, vx: Float, vy: Float): Boolean { if (kotlin.math.abs(vx) > kotlin.math.abs(vy) * 1.5f && kotlin.math.abs(vx) > dp(330)) { step(if (vx < 0) 1 else -1); return true }; return false }
        })
        frame.setOnTouchListener { _, e -> swipe.onTouchEvent(e) }
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        thumbs.execute { val b = thumbnail(item, 1080); runOnUiThread { if (showingId == item.key && b != null) still.setImageBitmap(b) } }
        status = text(listOf(item.name, item.note, formatTime(item.time)).filter { it.isNotBlank() }.joinToString("\n"), 13f, Glass.TEXT2).apply { gravity = Gravity.CENTER; maxLines = 6; setPadding(dp(16), dp(8), dp(16), dp(6)) }
        root.addView(status, -1, -2)
        toggle = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(8), dp(14)) }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; isFillViewport = true; addView(toggle) }, -1, -2)
        chip("แชร์", false, Icons.SHARE) { MediaLedger.share(this, item.uri, item.mime) }
        if (item.video) {
            chip("แต่ง / AI", false, Icons.AI) { startActivity(Intent(this, ClipEditorActivity::class.java).setData(item.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            val report = File(filesDir, "reports/${item.name}.json")
            if (report.exists()) chip("ผลตรวจไฟล์", false, Icons.INFO) { clipReport(report) }
        }
    }
    /** The file check written after recording, in plain words rather than raw JSON. */
    private fun clipReport(file: File) {
        val j = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        val lens = j.optJSONObject("capture")?.optJSONObject("lens")
        val lines = buildList {
            add("${j.optInt("displayWidth", j.optInt("width"))} × ${j.optInt("displayHeight", j.optInt("height"))}")
            add(String.format(Locale.US, "fps ที่วัดได้ %.2f", j.optDouble("measuredFps")))
            if (j.has("requestedCaptureFps")) add("fps ที่ขอ ${j.optInt("requestedCaptureFps")}")
            add("${j.optLong("frames")} เฟรม · ${if (j.optBoolean("audio")) "มีเสียง" else "ไม่มีเสียง"}")
            j.optString("codec").takeIf { it.isNotEmpty() }?.let { add("Codec $it") }
            j.optJSONObject("capture")?.optString("profile")?.takeIf { it.isNotEmpty() }?.let { add("โปรไฟล์ $it") }
            j.optJSONObject("capture")?.optString("fpsPath")?.takeIf { it.isNotEmpty() }?.let { add("60 fps ผ่าน Camera2") }
            if (lens != null) add(String.format(Locale.US, "เลนส์ %s · %.1f มม. · เทียบ %d มม. · f/%.1f", lens.optString("lens"), lens.optDouble("focalLengthMm"), lens.optInt("equivalent35mm"), lens.optDouble("aperture")))
        }
        AlertDialog.Builder(this).setTitle("ผลตรวจไฟล์วิดีโอ").setMessage(lines.joinToString("\n")).setPositiveButton("ปิด", null)
            .setNeutralButton("ส่งออก JSON") { _, _ -> MediaLedger.share(this, MediaLedger.privateUri(this, file), "application/json") }.show()
    }

    // ---------- Lumora shot detail (logic unchanged) ----------
    private fun chip(label: String, active: Boolean, icon: String = "", click: () -> Unit) {
        val c = LinearLayout(this).apply {
            gravity = Gravity.CENTER; background = glass(22, if (active) Glass.ACCENT_SOFT else Glass.FILL); setPadding(dp(16), 0, dp(16), 0)
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
            viewerBar("ภาพถ่าย").addView(roundButton(Icons.INFO, "ข้อมูลภาพ") { details(s) }, dp(48), dp(48))
            inspect = viewer()
            status = text("", 13f, Glass.TEXT2).apply { gravity = Gravity.CENTER; maxLines = 3; ellipsize = null; setPadding(dp(12), dp(6), dp(12), dp(6)) }
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
            Stage.RUNNING -> "กำลังเพิ่มรายละเอียด AI ×${s.aiScale.coerceAtLeast(1)}"
            Stage.FAILED -> "AI ยังไม่สำเร็จ · ${s.error}"
            Stage.DONE -> if (useAi) "AI ${s.aiModel}   ·   ${pixelsText(s.aiSize.pixels)}" else "ต้นฉบับ   ·   ${pixelsText(s.size.pixels)}"
            Stage.ORIGINAL -> if (s.output.rawOnly) "ภาพตัวอย่าง RAW · DNG เก็บแยก" else pixelsText(s.size.pixels) + if (s.reason == Decision.OFF) "" else "   ·   ${s.reason.description}"
        }
        when (s.stage) {
            Stage.RUNNING -> ring.update(s.aiProgress.toFloat(), "เพิ่มรายละเอียด AI ×${s.aiScale.coerceAtLeast(1)}")
            Stage.QUEUED -> ring.update(null, "รอคิวประมวลผล AI")
            Stage.CAPTURING -> ring.update(100f * s.frames / s.requestedFrames.coerceAtLeast(1), "กำลังถ่าย ${s.frames}/${s.requestedFrames} เฟรม")
            else -> ring.hide()
        }
        toggle.removeAllViews()
        if (s.raw != null) {
            chip("เปิด DNG", false, Icons.RAW) {
                try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(s.raw, "image/x-adobe-dng").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                catch (_: android.content.ActivityNotFoundException) { notice("บันทึก DNG ใน Pictures/Lumora แล้ว เปิดด้วยแอปที่รองรับ RAW") }
            }
        }
        if (s.ai != null) {
            chip(if (s.output.rawOnly) "ตัวอย่าง RAW" else "ต้นฉบับ", !useAi) { useAi = false; showDetail(s) }
            chip("ภาพ AI", useAi, Icons.AI) { useAi = true; showDetail(s) }
        }
        if (s.stage !in setOf(Stage.RUNNING,Stage.QUEUED,Stage.CAPTURING)) {
            chip(if (s.ai != null) "ปรับ AI ใหม่" else "ปรับด้วย AI",false,Icons.AI) { EnhanceUi.show(this,s) }
        } else if (s.stage in setOf(Stage.RUNNING,Stage.QUEUED)) {
            chip("ยกเลิก AI",false) { luma.fileIo.execute { EnhanceWorker.cancel(applicationContext,s.id) } }
        }
        chip("แชร์", false, Icons.SHARE) { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, if (useAi) s.ai else s.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "แชร์")) }
    }
    private fun details(s: Shot) {
        val ai = s.aiSize
        luma.fileIo.execute {
            // Lens details come from the EXIF the camera screen wrote (focal length, 35 mm equivalent, f-number).
            val exif = runCatching { contentResolver.openInputStream(s.uri)?.use { androidx.exifinterface.media.ExifInterface(it) } }.getOrNull()
            val lens = exif?.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_LENS_MODEL)
            val eq = exif?.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)
            val fn = exif?.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_F_NUMBER)?.let { r -> r.split('/').let { p -> if (p.size == 2) (p[0].toDoubleOrNull() ?: 0.0) / (p[1].toDoubleOrNull() ?: 1.0) else r.toDoubleOrNull() } }
            val text = buildList {
                add(formatTime(s.time)); add("ต้นฉบับ  ${s.size.w} × ${s.size.h} px")
                add("กล้อง${if (s.rear) "หลัง" else "หน้า"}  ·  ${zoomText(s.zoom)}")
                if (eq != null || fn != null) add("เลนส์  ${eq?.let { "เทียบ $it มม." } ?: ""}${if (fn != null && fn > 0) "  ·  f/${String.format(Locale.US, "%.1f", fn)}" else ""}")
                lens?.let { add(it) }
                add(if (s.softwareCrop) "ซูมครอปในแอป ขยายด้วย AI" else "ซูมด้วยระบบกล้อง")
                add("รูปแบบ ${s.output.label}")
                if (s.output.needsRaw) add("DNG เก็บข้อมูลเซนเซอร์เต็มตามที่กล้องส่ง ภาพตัวอย่าง JPEG อาจมีครอป/ซูม")
                if (s.output.enhance) { add("รับ ${s.frames}/${s.requestedFrames} เฟรม · ผ่านการจัดแนว ${s.fusedFrames} เฟรม"); add("ส่วนที่เคลื่อนไหวมากใช้ภาพอ้างอิง") }
                s.captureNote?.let { add(it) }
                add("Physical camera ID  ${s.lens ?: "เครื่องไม่รายงาน"}")
                add("พื้นที่ครอป  ${s.cropped?.let(::pixelsText) ?: "เครื่องไม่รายงาน"}")
                if (s.reason != Decision.OFF) add(s.reason.description)
                if (s.ai != null) { add("AI ${s.aiVersion}  ${ai.w} × ${ai.h} px"); add("เวลาประมวลผล  ${s.elapsed} ms") }
                s.aiReport?.let { report -> runCatching { JSONObject(report) }.getOrNull()?.let { j ->
                    add("ประมวลผลด้วย ${j.optString("backend")} · ความแรงรายละเอียด ${j.optInt("strength")}%")
                    add("รายละเอียดบางส่วนเป็นการคาดเดาของ AI")
                } }
            }.joinToString("\n")
            runOnUiThread { if (!isDestroyed) GlassDialog.show(this, "ข้อมูลภาพ", text) }
        }
    }
}

