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
    companion object {
        /** Extra for opening the gallery as a chooser: "video", "image" or "media". */
        const val EXTRA_PICK = "pick"
        /** Result extra: the chosen content URIs. */
        const val RESULT_URIS = "uris"
    }
    /** One grid cell: a Lumora shot, a Lumora video/file from the media ledger, or a device photo/video. */
    class Item(val key: String, val uri: Uri, val time: Long, val video: Boolean, val name: String, val album: String, val shot: Shot?, val mime: String = "", val note: String = "", val taken: Long = 0L, val size: Long = 0L) {
        /** When the picture was taken (falls back to when it was added). */
        val shotAt get() = if (taken > 0) taken else time
        val file get() = shot == null && !video && !mime.startsWith("image/") && mime.isNotEmpty()
    }
    class Album(val name: String, val count: Int, val cover: Item)
    private var selected: String? = null
    /** The viewer was opened by the camera's thumbnail; leaving it for the grid clears this. */
    private var viewerFromCamera = false
    private val launchAlbum: String? by lazy { if (intent.getStringExtra("tab") == "luma") "lumora" else null }
    private var useAi = false
    private var showingId: String? = null
    private var device = false
    private var category = 0
    private var album: String? = null
    /** Bottom tab: 0 รูปภาพ (every picture the app may read), 1 อัลบั้ม. */
    private var section = 0
    /** Album page opened from the albums tab: "lumora", "video", "fav", "raw", "ai" or "bucket" (named by [album]). */
    private var albumKind: String? = null
    /** Albums tab in its manage mode: pin, hide and drag to reorder. */
    private var managing = false
    private var showHidden = false
    /** -1 or 1 while switching bottom tabs, so the new page slides in from that side. */
    private var tabMove = 0
    private val editPhoto = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringExtra(PhotoEditorActivity.RESULT_ID)?.let { if (r.resultCode == RESULT_OK) { selected = it; useAi = false; reload() } }
    }
    /** After an export the editor names the new clip; the viewer moves onto it. */
    private val editVideo = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringExtra("open")?.let { if (r.resultCode == RESULT_OK) { selected = it; useAi = false; viewerFromCamera = false; reload() } }
    }
    private var queryText = ""
    /**
     * Where each grid page was scrolled to: the first row on screen (by item, so new photos do not shift it) and how far
     * it sits from the top. A page is one combination of tab, album, kind and search, so every page remembers its own
     * place and a picture opened and closed again brings the grid back to where it was.
     */
    private class Spot(val key: String?, val pos: Int, val top: Int)
    private val gridSpots = HashMap<String, Spot>()
    private var albumsSpot = 0
    /** The picture last on screen in the viewer, brought into view when the grid comes back. */
    private var lastViewed: String? = null
    private fun spotKey() = "$section|$albumKind|$album|$category|${queryText.trim().lowercase()}"
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
    // ---- Selection: pick several items (or all) to share, mark as favourite or delete. ----
    private val picked = LinkedHashSet<String>()
    private var selecting = false
    private var favouritesOnly = false
    private var selectBar: View? = null
    private var visibleItems: List<Item> = emptyList()
    private var refreshCells: () -> Unit = {}
    private var pendingForget: List<String> = emptyList()
    private val mediaChange = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == RESULT_OK && pendingForget.isNotEmpty()) { val ids = pendingForget; luma.fileIo.execute { stopWork(ids); luma.shots.forget(ids) } }
        if (r.resultCode == RESULT_OK) afterGrant?.invoke()
        if (r.resultCode == RESULT_OK) pendingNext?.let { selected = it.ifEmpty { null }; useAi = false }
        pendingNext = null
        afterGrant = null; pendingForget = emptyList(); endSelection(false); reload()
    }
    private var afterGrant: (() -> Unit)? = null
    /** After a system delete request: the picture to show next ("" = back to the grid). */
    private var pendingNext: String? = null
    private lateinit var aiBadge: AiBadge
    private var aiBusy = false; private var aiRunning: String? = null
    /** "shot" or "device" while that viewer is on screen, so a page turn can reuse it instead of rebuilding the page. */
    private var viewerKind = ""
    private var currentShot: Shot? = null
    /** Fit-to-screen copies of the pictures beside the open one, ready to slide in. */
    private val near = HashMap<String, Bitmap>()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        selected = state?.getString("id"); useAi = state?.getBoolean("ai") ?: false
        section = state?.getInt("section") ?: when (intent.getStringExtra("tab")) { "luma" -> 1; "device" -> 0; else -> prefs.getInt("gallery_section", 0) }
        albumKind = if (state != null) state.getString("albumKind") else if (intent.getStringExtra("tab") == "luma") "lumora" else null
        album = state?.getString("album")
        intent.getStringExtra("category")?.let { want -> category = if (want == "วิดีโอ") LUMA_CATS.indexOf("วิดีโอ").coerceAtLeast(0) else 0 }
        host = FrameLayout(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        host.addView(root, -1, -1)
        aiBadge = AiBadge(this).apply { setOnClickListener { aiRunning?.let { selected = it; useAi = false; reload() } } }
        host.addView(aiBadge, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.TOP or Gravity.END).apply { topMargin = dp(76); rightMargin = dp(14) })
        intent.getStringExtra("open")?.let { if (state == null) selected = it }
        if (pickKind != null) host.post { startSelection(null) }
        installRoot(host)
        // The photo viewer runs under the status bar (its bars float over the picture); every other page keeps clear of it.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(host) { v, insets ->
            val b = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            bars = android.graphics.Rect(b.left, b.top, b.right, b.bottom); padHost(); insets
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    sortMenu.showing -> sortMenu.dismiss(true)
                    selecting && !(pickKind != null && albumKind != null) -> endSelection()
                    peek != null -> closePeek()
                    // The newest photo opened straight from the camera: Back returns to the camera, not to the grid.
                    selected != null && viewerFromCamera -> finish()
                    selected != null -> { selected = null; useAi = false; reload() }
                    managing -> { managing = false; reload() }
                    // An album opened directly (Home's "AI pictures"): Back returns to where it was opened from.
                    albumKind != null && albumKind == launchAlbum -> finish()
                    albumKind != null -> closeAlbum()
                    else -> finish()
                }
            }
        })
        fun checkAi() = luma.readIo.execute { val busy = runCatching { luma.shots.all().any { it.stage == Stage.RUNNING || it.stage == Stage.QUEUED } }.getOrDefault(false)
            runOnUiThread { aiBusy = busy; if (!busy) aiBadge.done() } }
        luma.shots.changes.observe(this) { if (!device || selected != null) reload(); checkAi() }
        luma.shots.progress.observe(this) { p: Pair<String, Int> -> val id = p.first; val percent = p.second
            if (selected == id && showingId == id && ::ring.isInitialized && ring.visibility == View.VISIBLE && th.ogay.luma.ai.AiProgress.shotId != id) ring.update(percent.toFloat(), ring.caption)
            // Anywhere else in the gallery a small ring in the corner shows the AI is still working; a tap goes to that photo.
            aiRunning = id; if (aiBusy && selected != id) aiBadge.update(percent.toFloat()) else aiBadge.done()
        }
        checkAi()
        // Opened from the camera's thumbnail: show the newest photo first. Choosing media (pick mode) or coming from
        // Home always starts at the grid.
        if (state == null && !device && intent.getStringExtra("tab") == null && selected == null && pickKind == null && intent.getStringExtra("from") == null) {
            luma.fileIo.execute {
                val first = luma.shots.latest()?.id
                runOnUiThread { if (!isDestroyed) { selected = first; viewerFromCamera = first != null; reload() } }
            }
        } else reload()
    }
    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putString("id", selected); out.putBoolean("ai", useAi); out.putInt("section", section); out.putString("albumKind", albumKind); out.putString("album", album) }
    override fun onPause() { th.ogay.luma.ai.AiPace.watching = false; inlineVideo?.pause(); releasePeekVideo(); super.onPause() }
    override fun onDestroy() { thumbs.shutdownNow(); super.onDestroy() }
    private fun mediaGranted() = mediaPermissions.any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    /** Android 14+: the person allowed only some photos. */
    private fun partialAccess() = Build.VERSION.SDK_INT >= 34 &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED

    private fun reload() {
        val id = selected; val version = ++readVersion
        if (id != null) lastViewed = id
        val kind = albumKind; val albumsTab = section == 1 && kind == null
        favouritesOnly = kind == "fav"
        luma.readIo.execute {
            val shot = id?.let { luma.shots.get(it) }
            val granted = mediaGranted()
            // Everything the app may read, or only Lumora's own pictures before access is given (and in the Lumora album).
            val useDevice = kind != "lumora" && granted
            val items = if (useDevice) deviceItems() else lumaItems()
            val lumora = if (!albumsTab) emptyList() else if (useDevice) lumaItems() else items
            val favourites = if (albumsTab && granted) favouriteCount() else 0
            runOnUiThread {
                if (version == readVersion && !isDestroyed) {
                    device = useDevice; lastItems = items
                    when {
                        shot != null -> showDetail(shot)
                        id?.startsWith("device:") == true -> showDeviceItem(Uri.parse(id.removePrefix("device:")))
                        id?.startsWith("ledger:") == true -> items.firstOrNull { it.key == id }?.let { showLedgerItem(it) } ?: run { selected = null; showGrid(items) }
                        albumsTab -> { selected = null; showAlbums(items, lumora, favourites) }
                        else -> { selected = null; showGrid(items) }
                    }
                    tabMove = 0
                }
            }
        }
    }
    private fun favouriteCount(): Int {
        if (Build.VERSION.SDK_INT < 30) return 0
        var n = 0
        for (base in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI))
            runCatching { contentResolver.query(base, arrayOf(MediaStore.MediaColumns._ID), "is_favorite = 1", null, null)?.use { n += it.count } }
        return n
    }
    /** Photos from the shot database plus videos and files from the media ledger. Video reports (.json) stay with their video. */
    private fun lumaItems(): List<Item> {
        val shots = luma.shots.all().map { Item(it.id, it.uri, it.time, false, "", "Lumora", it) }
        val known = shots.map { it.uri.toString() }.toSet()
        val ledger = MediaLedger.all(this).filter { it.uri.toString() !in known && it.mime != "application/json" }.map {
            Item("ledger:${it.uri}", it.uri, it.time, it.mime.startsWith("video/"), it.title, "Lumora", null, it.mime, it.note)
        }
        // Files already in the Lumora folders from an earlier install (the app's database went with the uninstall)
        // join the list again as phone items, so they are seen and can still go through the AI.
        val seen = known + ledger.map { it.uri.toString() } + luma.shots.all().mapNotNull { it.ai?.toString() }
        val earlier = mutableListOf<Item>()
        fun read(base: Uri, video: Boolean) = runCatching {
            contentResolver.query(base, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DISPLAY_NAME),
                "bucket_display_name = ?", arrayOf("Lumora"), null)?.use { c ->
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(base, c.getLong(0))
                    if (uri.toString() !in seen) earlier += Item("device:$uri", uri, c.getLong(1) * 1000, video, c.getString(2) ?: "", "Lumora", null)
                }
            }
        }
        read(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false); read(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
        return (shots + ledger + earlier).sortedByDescending { it.time }
    }
    /** Set when another screen opens the gallery to choose media: "video", "image" or "media". */
    private val pickKind: String? by lazy { intent.getStringExtra(EXTRA_PICK) }
    private fun pickAllows(item: Item) = when (pickKind) { "video" -> item.video; "image" -> !item.video; else -> true }
    private fun returnPicked() {
        val uris = ArrayList(pickedItems().map { it.shot?.ai ?: it.uri })
        if (uris.isEmpty()) { notice("แตะรายการที่ต้องการก่อน"); return }
        setResult(RESULT_OK, Intent().putParcelableArrayListExtra(RESULT_URIS, uris).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        finish()
    }
    private fun startSelection(first: Item?) {
        selecting = true; first?.let { picked += it.key }; refreshCells(); updateSelectBar()
    }
    /** [cancel] true when the person closes selecting; false when the page only changes (an album opens) and a picker must stay open. */
    private fun endSelection(cancel: Boolean = true) {
        if (pickKind != null) { if (cancel) finish(); return }
        selecting = false; picked.clear(); refreshCells()
        selectBar?.let { b -> b.animate().translationY(b.height.toFloat() + dp(40)).alpha(0f).setDuration(200).withEndAction { (b.parent as? ViewGroup)?.removeView(b) }.start() }; selectBar = null
    }
    private fun togglePick(item: Item) {
        if (item.file) return
        if (!picked.remove(item.key)) picked += item.key
        Glass.tick(window.decorView); refreshCells(); updateSelectBar()
    }
    private fun pickedItems() = visibleItems.filter { it.key in picked }
    /** Bottom glass bar while selecting: count, share, favourite, delete, done — icons only, names spoken. */
    private fun updateSelectBar() {
        val content = findViewById<View>(android.R.id.content) as FrameLayout
        val bar = selectBar as? LinearLayout ?: LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; background = glass(28, Glass.FILL_POP); setPadding(dp(18), 0, dp(8), 0); elevation = dp(8).toFloat()
            addView(text("", 15f, Glass.TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
            fun act(icon: String, desc: String, f: () -> Unit) = addView(FrameLayout(this@GalleryActivity).apply { isClickable = true; glassTouch(); contentDescription = desc
                addView(IconView(this@GalleryActivity, icon), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)); setOnClickListener { Glass.tick(this); f() } }, dp(52), dp(52))
            if (pickKind != null) {
                // Choosing for the editor: one accent "add" button and a close button.
                addView(text("เพิ่ม", 14.5f, Glass.ON_ACCENT, true).apply { gravity = Gravity.CENTER; background = glass(20, Glass.ACCENT); isClickable = true; glassTouch()
                    contentDescription = "เพิ่มรายการที่เลือก"; setOnClickListener { Glass.tick(this); returnPicked() } }, LinearLayout.LayoutParams(dp(84), dp(44)).apply { rightMargin = dp(4) })
                act(Icons.CLOSE, "ยกเลิก") { endSelection() }
            } else {
            act(Icons.SHARE, "แชร์") { shareSelected() }
            act("M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z", "ใส่รายการโปรด") { favouriteSelected() }
            act("M5 7h14 M9 7V4h6v3 M7 7l1 13h8l1-13 M10 11v6 M14 11v6", "ลบ") { deleteSelected() }
            act(Icons.CLOSE, "เสร็จ") { endSelection() }
            }
            content.addView(this, FrameLayout.LayoutParams(-1, dp(64), Gravity.BOTTOM).apply { setMargins(dp(14), 0, dp(14), dp(28)) })
            translationY = dp(100).toFloat(); animate().translationY(0f).setInterpolator(Glass.SPRING).setDuration(380).start()
            selectBar = this
        }
        (bar.getChildAt(0) as TextView).text = if (picked.isEmpty()) (if (pickKind == "video") "เลือกวิดีโอ" else if (pickKind == "image") "เลือกรูป" else "เลือกรายการ") else "เลือก ${picked.size}"
    }
    private fun shareSelected() {
        val uris = ArrayList(pickedItems().map { it.shot?.ai ?: it.uri }); if (uris.isEmpty()) return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "แชร์"))
    }
    private fun favouriteSelected() = favourite(pickedItems())
    private fun favourite(items: List<Item>) {
        val uris = items.map { it.uri }.filter { it.authority == MediaStore.AUTHORITY }
        if (uris.isEmpty()) return
        if (android.os.Build.VERSION.SDK_INT < 30) { notice("ต้องใช้ Android 11 ขึ้นไป"); return }
        runCatching { mediaChange.launch(androidx.activity.result.IntentSenderRequest.Builder(MediaStore.createFavoriteRequest(contentResolver, uris, true).intentSender).build()) }.onFailure { notice("ใส่รายการโปรดไม่ได้") }
    }
    private fun deleteSelected() { val items = pickedItems(); if (items.isNotEmpty()) confirmDelete(items) }
    /** The app's own question first, in its own look, with the pictures that are about to go. */
    /** True when this install created the file, so it may move it to the bin without Android asking again. */
    private fun ownFile(u: Uri): Boolean = Build.VERSION.SDK_INT < 29 || runCatching {
        contentResolver.query(u, arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME), null, null, null)?.use { c -> c.moveToFirst() && c.getString(0) == packageName } ?: false
    }.getOrDefault(false)
    private fun confirmDelete(items: List<Item>) {
        // Pictures from other apps: Android shows its own "move to bin?" question, so a second one from Lumora is skipped.
        if (Build.VERSION.SDK_INT >= 30) {
            luma.readIo.execute {
                val anyOwn = items.any { it.shot != null || it.key.startsWith("ledger:") || (it.uri.authority == MediaStore.AUTHORITY && ownFile(it.uri)) }
                runOnUiThread { if (!isDestroyed) { if (anyOwn && prefs.getBoolean("gallery_ask_delete", true)) askDelete(items) else trash(items) } }
            }
        } else if (prefs.getBoolean("gallery_ask_delete", true)) askDelete(items) else trash(items)
    }
    private fun askDelete(items: List<Item>) {
        GlassDialog.panel(this, if (items.size == 1) "ลบ${if (items[0].video) "วิดีโอ" else "รูป"}นี้ไหม" else "ลบ ${items.size} รายการไหม") { col, d ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(10)) }
            items.take(3).forEachIndexed { n, item ->
                val pic = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0x14ffffff); rounded(this, 16); rotation = (n - (minOf(items.size, 3) - 1) / 2f) * 5f }
                row.addView(pic, LinearLayout.LayoutParams(dp(92), dp(116)).apply { setMargins(dp(5), 0, dp(5), 0) })
                thumbs.execute { val b = thumbnail(item, 320); runOnUiThread { if (b != null && !isDestroyed) pic.setImageBitmap(b) } }
            }
            col.addView(row, -1, -2)
            if (items.size > 3) col.addView(text("และอีก ${items.size - 3} รายการ", 12.5f, Glass.TEXT3).apply { gravity = Gravity.CENTER })
            val note = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; background = glass(16, 0x14ffffff); setPadding(dp(12), dp(10), dp(12), dp(10)) }
            note.addView(IconView(this, TRASH, 1.8f).apply { tint = RED }, dp(20), dp(20))
            note.addView(text("ย้ายไปถังขยะของเครื่อง กู้คืนได้จากแอปรูปภาพของเครื่องภายใน 30 วัน" + if (items.any { it.shot?.ai != null }) " · ภาพ AI ของรูปนี้จะถูกลบด้วย" else "", 12.5f, Glass.TEXT2).apply { maxLines = 4; ellipsize = null; setPadding(dp(10), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            col.addView(note, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            val buttons = LinearLayout(this)
            buttons.addView(text("ยกเลิก", 14f, Glass.TEXT, true).apply { gravity = Gravity.CENTER; background = glass(22, 0x1affffff); isClickable = true; glassTouch(); setOnClickListener { d.dismiss() } }, LinearLayout.LayoutParams(0, dp(48), 1f))
            buttons.addView(text("ลบ", 14f, Glass.TEXT, true).apply { gravity = Gravity.CENTER; background = glass(22, RED); isClickable = true; glassTouch(); setOnClickListener { d.dismiss(); trash(items) } }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) })
            col.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        }.show()
    }
    /** A deleted picture's AI job stops too; otherwise its ring and notification would keep turning for nothing. */
    private fun stopWork(ids: List<String>) = ids.forEach { runCatching { EnhanceWorker.cancel(applicationContext, it) } }
    private fun trash(items: List<Item>) {
        val uris = items.flatMap { listOfNotNull(it.uri, it.shot?.ai, it.shot?.raw) }.filter { it.authority == MediaStore.AUTHORITY }.distinct()
        val ids = items.mapNotNull { it.shot?.id }
        if (android.os.Build.VERSION.SDK_INT < 30) { notice("ต้องใช้ Android 11 ขึ้นไป"); return }
        val open = items.any { (it.shot?.id ?: it.key) == selected || it.key == selected }
        // The viewer stays open on the picture beside the deleted one (the next, or the previous at the end).
        val gone = items.map { it.key }.toSet()
        val next = if (!open) null else listOfNotNull(neighbourItem(1), neighbourItem(-1)).firstOrNull { it.key !in gone && !it.file }
        val nextId = next?.let { it.shot?.id ?: it.key }
        luma.fileIo.execute {
            // Files this install created can go to the bin directly. For any other file Android insists on asking
            // in its own dialog, so only those are handed to the system request.
            val rest = uris.filter { u -> !runCatching { contentResolver.update(u, android.content.ContentValues().apply { put("is_trashed", 1) }, null, null) > 0 }.getOrDefault(false) }
            if (rest.isEmpty()) runCatching { stopWork(ids); luma.shots.forget(ids) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (rest.isEmpty()) { if (open) { selected = nextId; useAi = false }; endSelection(); notice("ย้ายไปถังขยะแล้ว"); reload() }
                else if (android.os.Build.VERSION.SDK_INT >= 30) { pendingForget = ids; pendingNext = if (open) (nextId ?: "") else null; runCatching { mediaChange.launch(androidx.activity.result.IntentSenderRequest.Builder(MediaStore.createTrashRequest(contentResolver, rest, true).intentSender).build()) }.onFailure { pendingForget = emptyList(); notice("ลบไม่ได้") } }
            }
        }
    }
    private fun share(item: Item) = startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(contentResolver.getType(item.uri) ?: if (item.video) "video/*" else "image/*").putExtra(Intent.EXTRA_STREAM, item.shot?.ai ?: item.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "แชร์"))
    private fun rename(item: Item) {
        if (item.uri.authority != MediaStore.AUTHORITY) { notice("ไฟล์นี้เปลี่ยนชื่อไม่ได้"); return }
        luma.fileIo.execute {
            val now = item.name.ifBlank { runCatching { contentResolver.query(item.uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull() ?: "" }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val ext = now.substringAfterLast('.', ""); val stem = if (ext.isEmpty()) now else now.dropLast(ext.length + 1)
                GlassDialog.panel(this, "เปลี่ยนชื่อ") { col, d ->
                    val field = EditText(this).apply { setText(stem); setSelection(stem.length); setTextColor(Glass.TEXT); textSize = 15f; setSingleLine(); background = glass(16, 0x1fffffff); setPadding(dp(14), 0, dp(14), 0) }
                    col.addView(field, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) })
                    col.addView(GlassDialog.actions(this, "ยกเลิก" to { d.dismiss() }, "บันทึก" to {
                        val clean = field.text.toString().trim().replace(Regex("[\\\\/:*?\"<>|]"), "_"); d.dismiss()
                        if (clean.isNotEmpty() && clean != stem) {
                            val name = if (ext.isEmpty()) clean else "$clean.$ext"
                            fun write() = runCatching { contentResolver.update(item.uri, android.content.ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }, null, null) > 0 }.getOrDefault(false)
                            luma.fileIo.execute { val ok = write(); runOnUiThread {
                                if (ok) { notice("เปลี่ยนชื่อแล้ว"); reload() }
                                else if (android.os.Build.VERSION.SDK_INT >= 30) { afterGrant = { luma.fileIo.execute { write() } }; runCatching { mediaChange.launch(androidx.activity.result.IntentSenderRequest.Builder(MediaStore.createWriteRequest(contentResolver, listOf(item.uri)).intentSender).build()) }.onFailure { afterGrant = null; notice("เปลี่ยนชื่อไม่ได้") } }
                                else notice("เปลี่ยนชื่อไม่ได้") } }
                        }
                    }), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
                }.show()
            }
        }
    }
    /** The details panel for any photo or video, from Lumora or from other apps. */
    private fun itemInfo(item: Item) {
        item.shot?.let { details(it); return }
        luma.fileIo.execute {
            var size = 0L; var name = item.name
            runCatching { contentResolver.query(item.uri, arrayOf(MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) { size = c.getLong(0); if (name.isBlank()) name = c.getString(1) ?: "" } } }
            if (size <= 0 && item.uri.scheme == "file") size = File(item.uri.path ?: "").length()
            val rows = ArrayList<Triple<String, String, String>>(); val tech = ArrayList<Pair<String, String>>()
            if (item.video) {
                val m = android.media.MediaMetadataRetriever()
                runCatching {
                    m.setDataSource(this, item.uri)
                    fun k(key: Int) = m.extractMetadata(key)
                    val w = k(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0; val h = k(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    val turn = k(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    val ms = k(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val frames = if (android.os.Build.VERSION.SDK_INT >= 28) k(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull() else null
                    val fps = if (frames != null && ms > 0) frames * 1000.0 / ms else null
                    val sw = if (turn % 180 == 90) h else w; val sh = if (turn % 180 == 90) w else h
                    rows += Triple(Icons.VIDEO, "$sw × $sh", listOfNotNull(fps?.let { String.format(Locale.US, "%.1f fps", it) }, String.format(Locale.US, "%d:%02d", ms / 60000, ms / 1000 % 60)).joinToString(" · "))
                    rows += Triple(Icons.FILE, name.ifBlank { "วิดีโอ" }, listOfNotNull(if (size > 0) String.format(Locale.US, "%.1f MB", size / 1_048_576.0) else null, k(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.let { "${it / 1_000_000} Mbps" }).joinToString(" · "))
                    rows += Triple(Icons.SOUND, if (k(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes") "มีเสียง" else "ไม่มีเสียง", "")
                    frames?.let { tech += Icons.STACK to "$it เฟรม" }
                    k(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.let { tech += Icons.INFO to it }
                }
                m.release()
                // What Lumora checked after recording, if this is its own clip.
                runCatching { JSONObject(File(filesDir, "reports/${item.name}.json").readText()) }.getOrNull()?.let { j ->
                    j.optString("codec").takeIf { it.isNotEmpty() }?.let { tech += Icons.INFO to "Codec $it" }
                    j.optJSONObject("capture")?.optJSONObject("lens")?.let { l -> tech += Icons.SEARCH to String.format(Locale.US, "เลนส์ %s · %.1f มม. · f/%.1f", l.optString("lens"), l.optDouble("focalLengthMm"), l.optDouble("aperture")) }
                }
            } else {
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                runCatching { contentResolver.openInputStream(item.uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) } }
                val exif = runCatching { contentResolver.openInputStream(item.uri)?.use { androidx.exifinterface.media.ExifInterface(it) } }.getOrNull()
                fun e(t: String) = exif?.getAttribute(t)?.takeIf { it.isNotBlank() }
                val px = o.outWidth.toLong() * o.outHeight
                rows += Triple(Icons.PHOTO, "${o.outWidth} × ${o.outHeight}", if (px > 0) pixelsText(px) else "")
                val camera = listOfNotNull(e(androidx.exifinterface.media.ExifInterface.TAG_MAKE), e(androidx.exifinterface.media.ExifInterface.TAG_MODEL)).distinct().joinToString(" ")
                val shutter = exif?.getAttributeDouble(androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_TIME, 0.0)?.takeIf { it > 0 }?.let { if (it < 1) "1/${(1 / it).toInt()} วิ" else String.format(Locale.US, "%.1f วิ", it) }
                val fn = exif?.getAttributeDouble(androidx.exifinterface.media.ExifInterface.TAG_F_NUMBER, 0.0)?.takeIf { it > 0 }?.let { String.format(Locale.US, "f/%.1f", it) }
                val iso = e(androidx.exifinterface.media.ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { "ISO $it" }
                val focal = e(androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)?.let { "$it มม." }
                if (camera.isNotBlank() || shutter != null) rows += Triple(Icons.FLIP, camera.ifBlank { "กล้อง" }, listOfNotNull(focal, fn, shutter, iso).joinToString(" · "))
                rows += Triple(Icons.FILE, name.ifBlank { "รูปภาพ" }, listOfNotNull(if (size > 0) String.format(Locale.US, "%.1f MB", size / 1_048_576.0) else null, item.album.takeIf { it.isNotBlank() }).joinToString(" · "))
                e(androidx.exifinterface.media.ExifInterface.TAG_LENS_MODEL)?.let { tech += Icons.SEARCH to it }
                e(androidx.exifinterface.media.ExifInterface.TAG_SOFTWARE)?.let { tech += Icons.INFO to "ซอฟต์แวร์ $it" }
                e(androidx.exifinterface.media.ExifInterface.TAG_WHITE_BALANCE)?.let { tech += Icons.INFO to "สมดุลแสงขาว ${if (it == "0") "อัตโนมัติ" else "กำหนดเอง"}" }
            }
            runOnUiThread { if (!isDestroyed) infoPanel({ thumbnail(item, 540) }, formatTime(item.time), rows, tech) }
        }
    }

    private fun deviceItems(): List<Item> {
        val out = mutableListOf<Item>()
        // A photo the AI has worked on is one item: the AI file is hidden here and the original opens with the compare button.
        val shots = runCatching { luma.shots.all() }.getOrDefault(emptyList())
        val mergeAi = prefs.getBoolean("gallery_merge_ai", true)
        val aiUris = shots.mapNotNull { it.ai?.toString() }.toSet(); val byUri = shots.associateBy { it.uri.toString() }
        fun read(base: Uri, video: Boolean) {
            val cols = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DISPLAY_NAME, "bucket_display_name", "datetaken", MediaStore.MediaColumns.SIZE)
            runCatching {
                contentResolver.query(base, cols, if (favouritesOnly && android.os.Build.VERSION.SDK_INT >= 30) "is_favorite = 1" else null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                    while (c.moveToNext() && out.size < 5000) {
                        val uri = ContentUris.withAppendedId(base, c.getLong(0))
                        if (mergeAi && uri.toString() in aiUris) continue
                        out += Item("device:$uri", uri, c.getLong(1) * 1000, video, c.getString(2) ?: "", c.getString(3) ?: "อื่น ๆ", byUri[uri.toString()], taken = if (c.isNull(4)) 0L else c.getLong(4), size = if (c.isNull(5)) 0L else c.getLong(5))
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
        bar.addView(text(title, 20f, Glass.TEXT, true).apply { setPadding(dp(12), 0, dp(4), 0); maxLines = 3; ellipsize = null; tag = "title" }, LinearLayout.LayoutParams(0, -2, 1f))
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
        addView(text(title, 15f, Glass.TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        addView(text(end, 12.5f, Glass.TEXT2))
    }
    /** Sort orders, as in the phone's own gallery: by date taken, date added, size and name; then video or AI first. */
    private val SORTS = listOf("วันที่ถ่าย · ใหม่ก่อน", "วันที่ถ่าย · เก่าก่อน", "วันที่เพิ่ม · ใหม่ก่อน", "ขนาดไฟล์ · ใหญ่ก่อน", "ชื่อไฟล์ · ก–ฮ", "วิดีโอก่อน", "AI ก่อน")
    private val SORT_ICONS = listOf(Icons.CHEVRON_UP, Icons.UPDOWN, Icons.PLUS, Icons.FILE, "M5 18L9 6l4 12 M6.4 14h5.2 M15 6h5l-5 12h5", Icons.VIDEO, Icons.AI)
    private val SORT_SHORT = listOf("ใหม่ก่อน", "เก่าก่อน", "เพิ่มล่าสุด", "ใหญ่ก่อน", "ชื่อ", "วิดีโอก่อน", "AI ก่อน")
    private val sortMenu by lazy { GlassMenu(host) }
    private var sortOrder: Int
        get() = prefs.getInt("gallery_sort2", 0).coerceIn(0, 6)
        set(v) { prefs.edit().putInt("gallery_sort2", v).apply() }
    private val LUMA_CATS = listOf("ทั้งหมด", "AI", "RAW", "วิดีโอ", "พิเศษ", "ไฟล์")
    private val RENAME = "M4 20h16 M6.5 16l5.5-12 5.5 12 M8.5 11.5h7"
    private val TRASH = "M5 7h14 M9 7V4h6v3 M7 7l1 13h8l1-13 M10 11v6 M14 11v6"
    private val HEART = "M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z"
    private val EYE = "M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6"
    private val EYE_OFF = "M3 3l18 18 M10.6 5.1A10 10 0 0 1 12 5c6.4 0 10 7 10 7a17 17 0 0 1-3.2 3.9 M6.6 6.6C3.9 8.4 2 12 2 12s3.6 7 10 7a9.7 9.7 0 0 0 5.4-1.6 M9.9 9.9a3 3 0 0 0 4.2 4.2"
    private val PENCIL = "M4 20l1-4L16 5l3 3L8 19z M14 7l3 3"
    private val RED = 0xffe5484d.toInt()
    private fun categories(items: List<Item>): List<Pair<String, (Item) -> Boolean>> =
        if (!device) listOf<Pair<String, (Item) -> Boolean>>(
            "ทั้งหมด" to { it -> !it.file }, "AI" to { it -> it.shot?.ai != null }, "RAW" to { it -> it.shot?.output?.needsRaw == true },
            "วิดีโอ" to { it -> it.video }, "พิเศษ" to { it -> it.shot?.captureNote != null }, "ไฟล์" to { it -> it.file })
        else listOf<Pair<String, (Item) -> Boolean>>("ทั้งหมด" to { _ -> true }, "รูปภาพ" to { it -> !it.video }, "วิดีโอ" to { it -> it.video })

    // ---------- Grid ----------
    private var searchAnim: android.animation.ValueAnimator? = null
    private fun showGrid(all: List<Item>) {
        th.ogay.luma.ai.AiPace.watching = false
        showingId = null; viewerKind = ""; immersive(false); root.removeAllViews()
        val bar = topBar(if (albumKind != null) albumTitle() else "รูปภาพ") { if (albumKind != null) closeAlbum() else finish() }
        val searchBox = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; background = glass(22, 0x40ffffff); setPadding(dp(14), 0, dp(6), 0) }
        val searchIcon = IconView(this, Icons.SEARCH, 2f)
        val field = EditText(this).apply { hint = "ค้นหา"; setHintTextColor(Glass.TEXT3); setTextColor(Glass.TEXT); textSize = 15f; background = null; setSingleLine(); visibility = View.GONE; setText(queryText); setSelection(queryText.length); setPadding(dp(8), 0, dp(4), 0)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { v, _, _ -> (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0); v.clearFocus(); true } }
        val close = IconView(this, Icons.CLOSE, 2f).apply { tint = Glass.TEXT2; setPadding(dp(11), dp(11), dp(11), dp(11)); visibility = View.GONE; contentDescription = "ปิดการค้นหา"; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES; isClickable = true }
        searchBox.addView(searchIcon, dp(20), dp(20)); searchBox.addView(field, LinearLayout.LayoutParams(0, -1, 1f)); searchBox.addView(close, dp(40), dp(40))
        searchBox.contentDescription = "ค้นหา"
        bar.addView(searchBox, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(10) })
        lateinit var more: View
        fun openMore(anchor: View) {
            val options = listOf(GlassMenu.Option("ตั้งค่าแกลเลอรี", Icons.GEAR), GlassMenu.Option("เลือก", Icons.CHECK), GlassMenu.Option("เลือกทั้งหมด", Icons.STACK), GlassMenu.Option("รายการโปรด", "M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z"))
            sortMenu.show(anchor, "แกลเลอรี", options, -1) { i -> when (i) {
                0 -> gallerySettings()
                1 -> startSelection(null)
                2 -> { startSelection(null); picked.addAll(visibleItems.filter { !it.file }.map { it.key }); refreshCells(); updateSelectBar() }
                else -> openAlbum("fav")
            } }
        }
        more = roundButton("M12 5.2v.1 M12 12v.1 M12 18.8v.1", "ตัวเลือกเพิ่มเติม") { openMore(more) }
        more.pressMenu(sortMenu) { openMore(it) }
        bar.addView(more, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(8) })
        (bar.findViewWithTag<View>("title") as? TextView)?.apply { maxLines = 1; minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL }
        fun morph(open: Boolean, animate: Boolean = true) {
            val from = searchBox.width; val to = if (open) bar.width - bar.paddingLeft - bar.paddingRight - dp(58) else dp(48)
            if (from == to && field.visibility == (if (open) View.VISIBLE else View.GONE)) return
            field.visibility = if (open) View.VISIBLE else View.GONE; close.visibility = field.visibility
            val quick = !animate || Glass.reduced(this@GalleryActivity)
            // The title stays in the row and only fades, so nothing below moves; it returns softly once the box has closed.
            bar.findViewWithTag<View>("title")?.let { t -> t.animate().cancel(); if (open) t.animate().alpha(0f).setDuration(if (quick) 0 else 140).start() else t.animate().alpha(1f).setStartDelay(if (quick) 0 else 240).setDuration(if (quick) 0 else 260).withEndAction { t.animate().setStartDelay(0) }.start() }
            // The ⋮ button is not needed while typing. It shrinks to nothing in the same motion as the box grows, so
            // the box's right edge glides into its place; removing it in one step made the box jump sideways.
            more.animate().cancel(); field.animate().cancel()
            val mlp = more.layoutParams as LinearLayout.LayoutParams
            val moreFrom = if (more.visibility == View.GONE) 0f else mlp.width / dp(48).toFloat()
            val moreTo = if (open) 0f else 1f
            more.visibility = View.VISIBLE
            if (open) { field.alpha = 0f; field.animate().alpha(1f).setStartDelay(if (quick) 0 else 160).setDuration(if (quick) 0 else 220).withEndAction { field.animate().setStartDelay(0) }.start() }
            searchAnim?.cancel()
            searchAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (quick) 0 else 420; interpolator = Glass.EASE
                addUpdateListener {
                    val f = it.animatedValue as Float
                    val m = (moreFrom + (moreTo - moreFrom) * f).coerceIn(0f, 1f)
                    mlp.width = (dp(48) * m).toInt(); mlp.leftMargin = (dp(8) * m).toInt(); more.layoutParams = mlp
                    more.alpha = ((m - 0.4f) / 0.6f).coerceIn(0f, 1f); more.scaleX = 0.6f + 0.4f * m; more.scaleY = more.scaleX
                    searchBox.layoutParams = (searchBox.layoutParams as LinearLayout.LayoutParams).apply { width = (from + (to - from) * f).toInt() }
                }
                addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(a: android.animation.Animator) {
                    if (open) more.visibility = View.GONE else { mlp.width = dp(48); mlp.leftMargin = dp(8); more.layoutParams = mlp; more.alpha = 1f; more.scaleX = 1f; more.scaleY = 1f } } })
                start()
            }
            val ime = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            if (open && animate) { field.requestFocus(); ime.showSoftInput(field, 0) } else if (!open) { field.setText(""); ime.hideSoftInputFromWindow(field.windowToken, 0) }
        }
        searchBox.setOnClickListener { if (field.visibility != View.VISIBLE) morph(true) }
        close.setOnClickListener { morph(false) }
        // A refresh while searching rebuilds the grid: reopen the box at once, without the animation or a second keyboard pop.
        if (queryText.isNotEmpty()) searchBox.post { morph(true, false) }


        // Header above the grid: access notices and, in the Lumora album, the kind strip.
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (!device && albumKind == null && !mediaGranted()) header.addView(accessCard(), LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(4), dp(16), dp(8)) })
        if (device && partialAccess()) header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = glass(22); setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(text("Lumora เห็นเฉพาะรูปที่คุณเลือกให้ อนุญาตทั้งหมดเพื่อดูทุกอัลบั้ม", 13f, Glass.TEXT).apply { maxLines = 4 })
            addView(LinearLayout(this@GalleryActivity).apply {
                setPadding(0, dp(10), 0, 0)
                addView(text("เลือกรูปเพิ่ม", 13.5f, Glass.TEXT, true).apply { gravity = Gravity.CENTER; background = glass(16, 0x1affffff); isClickable = true; glassTouch(); setOnClickListener { mediaRequest.launch(mediaPermissions) } }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(8) })
                addView(text("อนุญาตทั้งหมด", 13.5f, Glass.ON_ACCENT, true).apply { gravity = Gravity.CENTER; background = glass(16, Glass.ACCENT); isClickable = true; glassTouch()
                    setOnClickListener { runCatching { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } } }, LinearLayout.LayoutParams(0, dp(48), 1f))
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), 0, dp(16), dp(8)) })

        val cats = categories(all); if (category >= cats.size || device) category = 0
        val strip = CenterStrip(this).apply { capsuleHeightDp = 40; contentDescription = "หมวด" }
        val labels = mutableListOf<TextView>()
        strip.setItems(cats.mapIndexed { i, (name, _) ->
            val t = text(name, 13.5f, if (i == category) Color.WHITE else Glass.TEXT2, i == category).apply { gravity = Gravity.CENTER; isClickable = true; maxLines = 1; contentDescription = "หมวด$name" }
            labels += t
            CenterStrip.Item(name, (t.paint.measureText(name) / resources.displayMetrics.density + 28).roundToInt().coerceAtLeast(64), t)
        }, category)
        strip.onLive = { live -> labels.forEachIndexed { i, t -> t.setTextColor(if (i == live) Color.WHITE else Glass.TEXT2) } }
        if (!device) header.addView(strip, -1, dp(48))
        val gridHead = sectionHead("", ""); header.addView(gridHead)
        val gridTitle = gridHead.getChildAt(0) as TextView

        val grid = GridView(this).apply {
            contentDescription = "รายการรูปและวิดีโอ"; numColumns = prefs.getInt("gallery_columns", 3).coerceIn(2, 5); horizontalSpacing = dp(5); verticalSpacing = dp(5); setPadding(dp(8), 0, dp(8), dp(8)); clipToPadding = false; selector = android.graphics.drawable.ColorDrawable(0)
            // Longer, softer flings.
            setFriction(android.view.ViewConfiguration.getScrollFriction() * 0.75f); isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_ALWAYS
        }
        val empty = text("", 15f, Glass.TEXT2).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        // GridView cannot hold a header, so the header sits above it in a column that shares the space.
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(header, -1, -2); addView(FrameLayout(this@GalleryActivity).apply { addView(grid, -1, -1); addView(empty, -1, -1) }, LinearLayout.LayoutParams(-1, 0, 1f)) }
        root.addView(column, LinearLayout.LayoutParams(-1, 0, 1f))
        if (albumKind == null) root.addView(bottomNav(), LinearLayout.LayoutParams(-1, dp(60)).apply { setMargins(dp(40), dp(4), dp(40), dp(12)) })
        pageView = if (albumKind == null || pickKind != null) column else null; swipeKeep = strip
        if (closingViewer) { closingViewer = false; if (!Glass.reduced(this)) { column.alpha = 0f; column.scaleX = 0.96f; column.scaleY = 0.96f; column.animate().alpha(1f).scaleX(1f).scaleY(1f).setInterpolator(Glass.EASE).setDuration(300).start() } }
        else slideIn(column)
        var shown = all
        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int) = shown[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, previous: View?, parent: ViewGroup?): View = cell(previous as? FrameLayout, shown[position], grid.width)
        }
        grid.adapter = adapter
        // Scroll memory: written only while armed, so the old list's position never lands on a new page's key.
        var armed = false
        fun restore(first: Boolean) {
            val spot = gridSpots[spotKey()]; val want = if (first) lastViewed else null
            grid.post {
                if (isDestroyed) return@post
                var pos = 0; var top = 0
                if (spot != null) {
                    val i = spot.key?.let { k -> shown.indexOfFirst { itemKey(it) == k } } ?: -1
                    pos = if (i >= 0) i else spot.pos.coerceAtMost((shown.size - 1).coerceAtLeast(0)); top = spot.top
                }
                if (shown.isNotEmpty()) grid.setSelectionFromTop(pos, top)
                grid.post {
                    // Coming back from the viewer after swiping on to another picture: make sure that one is on screen.
                    if (want != null) {
                        val idx = shown.indexOfFirst { itemKey(it) == want }
                        if (idx >= 0 && (idx < grid.firstVisiblePosition || idx > grid.lastVisiblePosition)) grid.setSelectionFromTop(idx, grid.height / 3)
                        lastViewed = null
                    }
                    armed = true
                }
            }
        }
        grid.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(v: AbsListView, state: Int) {}
            override fun onScroll(v: AbsListView, first: Int, visible: Int, total: Int) {
                if (!armed || shown.isEmpty()) return
                val c = v.getChildAt(0) ?: return
                gridSpots[spotKey()] = Spot(shown.getOrNull(first)?.let { itemKey(it) }, first, c.top)
            }
        })
        var firstApply = true
        fun apply() {
            armed = false
            val q = queryText.trim().lowercase()
            val picked = all.filter(cats[category].second).filter { inAlbum(it) }.filter { pickAllows(it) }
                .filter { q.isEmpty() || q in "${it.name} ${it.album} ${formatTime(it.time)} ${it.note} ${it.shot?.captureNote ?: ""} ${it.shot?.reason?.description ?: ""}".lowercase() }
            shown = when (sortOrder) {
                0 -> picked.sortedByDescending { it.shotAt }
                1 -> picked.sortedBy { it.shotAt }
                3 -> picked.sortedByDescending { it.size }
                4 -> picked.sortedBy { it.name.lowercase() }
                5 -> picked.sortedWith(compareBy<Item> { !it.video }.thenByDescending { it.shotAt })
                6 -> picked.sortedWith(compareBy<Item> { it.shot?.ai == null }.thenByDescending { it.shotAt })
                else -> picked.sortedByDescending { it.time }
            }
            navKeys = shown.filter { !it.file }.map { itemKey(it) }
            adapter.notifyDataSetChanged()
            gridTitle.text = if (albumKind != null) "" else if (device) "รูปและวิดีโอทั้งหมด" else "ถ่ายด้วย Lumora"
            (gridHead.getChildAt(1) as TextView).text = "${shown.size} รายการ · ${SORT_SHORT[sortOrder]} ▾"
            empty.text = if (all.isEmpty()) (if (device) "ไม่พบรูปหรือวิดีโอในเครื่อง" else "รูปและวิดีโอที่ถ่ายด้วย Lumora จะปรากฏที่นี่") else "ไม่พบรายการที่ตรงกัน"
            empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
            if (!Glass.reduced(this)) { grid.alpha = 0.4f; grid.scaleX = 0.97f; grid.scaleY = 0.97f; grid.animate().alpha(1f).scaleX(1f).scaleY(1f).setInterpolator(Glass.SPRING).setDuration(360).start() }
            restore(firstApply); firstApply = false
        }
        strip.onSelect = { i -> category = i; apply() }
        // Sort order: a glass dropdown on the count at the right of the grid heading.
        (gridHead.getChildAt(1) as TextView).apply {
            isClickable = true; minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), 0, 0, 0); contentDescription = "เรียงลำดับรายการ"
            pressMenu(sortMenu) { v -> sortMenu.show(v, "เรียงตาม", SORTS.mapIndexed { i, n -> GlassMenu.Option(n, SORT_ICONS[i]) }, sortOrder) { i -> sortOrder = i; gridSpots.remove(spotKey()); apply() } }
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { queryText = s.toString(); apply() }
        })
        apply()
        visibleItems = shown; refreshCells = { visibleItems = shown; adapter.notifyDataSetChanged() }
        grid.setOnItemClickListener { _, _, p, _ -> if (selecting) togglePick(shown[p]) else open(shown[p]) }
        grid.setOnItemLongClickListener { _, v, p, _ -> if (selecting) { togglePick(shown[p]); true } else { showPeek(shown[p], v); true } }
        // While the peek is up the finger that opened it is still down: its moves pick a button, lifting it closes the peek.
        grid.setOnTouchListener { v, e -> if (peek != null) { peekTouch(e)
            // The grid never sees the end of this touch, so it is told the touch was cancelled (clears its pressed cell).
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) { val c = MotionEvent.obtain(e); c.action = MotionEvent.ACTION_CANCEL; v.onTouchEvent(c); c.recycle() }
            true } else false }
    }

    /** One grid cell. A thumbnail that cannot be decoded keeps a visible placeholder instead of an empty hole. */
    private fun cell(previous: FrameLayout?, item: Item, gridWidth: Int): View {
        val cell = previous ?: FrameLayout(this).apply {
            rounded(this, 14)
            addView(View(this@GalleryActivity).apply { background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Glass.PANEL, Glass.BG_DEEP)) }, -1, -1)
            addView(IconView(this@GalleryActivity, Icons.ALBUM, 1.6f).apply { tint = 0x66ffffff }, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            addView(ImageView(this@GalleryActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }, -1, -1)
            addView(IconView(this@GalleryActivity, Icons.VIDEO, 2f).apply { shadow = true }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(6), dp(6)) })
            addView(text("AI", 10f, Glass.ACCENT, true).apply { setShadowLayer(4f, 0f, 1f, 0x99000000.toInt()) }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(4), dp(6), 0) })
            addView(text("", 10.5f, Glass.TEXT).apply { maxLines = 2; gravity = Gravity.CENTER; setPadding(dp(6), 0, dp(6), dp(8)) }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            addView(FrameLayout(this@GalleryActivity).apply { addView(IconView(this@GalleryActivity, Icons.CHECK, 2.6f), FrameLayout.LayoutParams(dp(13), dp(13), Gravity.CENTER)) },
                FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.START).apply { setMargins(dp(6), dp(6), 0, 0) })
            // Child 7: a small turning ring while the photo is still being captured or processed.
            addView(ProcessingSpinner(this@GalleryActivity).apply { visibility = View.GONE }, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(6), dp(6)) })
        }
        val placeholder = cell.getChildAt(1) as IconView; val image = cell.getChildAt(2) as ImageView
        val badge = cell.getChildAt(6) as FrameLayout; val on = item.key in picked
        badge.visibility = if (selecting && !item.file) View.VISIBLE else View.GONE
        badge.background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(if (on) Glass.ACCENT else 0x55000000); setStroke(dp(2), Color.WHITE) }
        badge.getChildAt(0).visibility = if (on) View.VISIBLE else View.INVISIBLE
        cell.foreground = if (on) android.graphics.drawable.ColorDrawable(0x33000000) else null
        // A photo that is still being taken or processed shows the working ring, never the video mark.
        val working = item.shot?.stage.let { it == Stage.CAPTURING || it == Stage.QUEUED || it == Stage.RUNNING }
        cell.getChildAt(7).visibility = if (working) View.VISIBLE else View.GONE
        cell.getChildAt(3).visibility = if (item.video && item.shot == null && !working) View.VISIBLE else View.GONE
        cell.getChildAt(4).visibility = if (item.shot?.ai != null) View.VISIBLE else View.GONE
        val caption = cell.getChildAt(5) as TextView; caption.text = if (item.file) item.name else ""; caption.visibility = if (item.file) View.VISIBLE else View.GONE
        placeholder.path = if (item.file) Icons.DOC else if (item.video) Icons.VIDEO else Icons.ALBUM; placeholder.visibility = View.VISIBLE
        image.tag = item.key; image.setImageDrawable(null); image.animate().cancel(); image.alpha = 1f
        cell.layoutParams = AbsListView.LayoutParams(-1, ((gridWidth - dp(26)) / 3).coerceAtLeast(dp(92))); cell.invalidateOutline(); cell.post { cell.invalidateOutline() }
        cell.contentDescription = "${if (item.video) "วิดีโอ" else if (item.file) "ไฟล์ ${item.name}" else "รูป"} ${formatTime(item.time)}${if (working) " กำลังประมวลผล" else ""} แตะค้างเพื่อดูตัวอย่าง"
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

    // ---------- Albums tab (OPPO-style): my media first, then the phone's albums; pin, hide and reorder ----------
    private fun albumTitle() = when (albumKind) { "lumora" -> "ถ่ายด้วย Lumora"; "video" -> "วิดีโอ"; "fav" -> "รายการโปรด"; "raw" -> "RAW"; "ai" -> "ภาพ AI"; else -> album ?: "อัลบั้ม" }
    private fun openAlbum(kind: String, name: String? = null) { albumKind = kind; album = name; category = 0; endSelection(false); reload() }
    private fun closeAlbum() { albumKind = null; album = null; section = 1; category = 0; reload() }
    private fun isRaw(i: Item) = i.shot?.output?.needsRaw == true || i.name.endsWith(".dng", true) || i.mime == "image/x-adobe-dng"
    /** Whether an item belongs on the open page. Hidden albums stay out of the รูปภาพ tab. */
    private fun inAlbum(i: Item): Boolean = when (albumKind) {
        null -> !(device && i.album in prefList("album_hidden"))
        "video" -> i.video; "raw" -> isRaw(i); "ai" -> i.shot?.ai != null; "bucket" -> i.album == album
        else -> true
    }
    private fun prefList(key: String): List<String> = prefs.getString(key, "")!!.split('\u001f').filter { it.isNotEmpty() }
    private fun savePrefList(key: String, v: List<String>) = prefs.edit().putString(key, v.joinToString("\u001f")).apply()
    /** Pinned albums first, then the saved order, then the biggest. */
    private fun ordered(list: List<Album>): List<Album> {
        val order = prefList("album_order"); val pinned = prefList("album_pinned")
        fun at(n: String) = order.indexOf(n).let { if (it < 0) Int.MAX_VALUE else it }
        return list.sortedWith(compareBy<Album> { it.name !in pinned }.thenBy { at(it.name) }.thenByDescending { it.count })
    }

    /** Glass bar at the bottom: รูปภาพ | อัลบั้ม, with a highlight that follows a swipe between the two pages. */
    private fun bottomNav(): View = GlassTabs(this, listOf(Icons.ALBUM to "รูปภาพ", Icons.STACK to "อัลบั้ม"), section) { i ->
        if (i != section) { Glass.tick(root); navTabs?.animateTo(i); switchSection(i, false) }
    }.also { navTabs = it }
    private fun switchSection(i: Int, swiped: Boolean) {
        tabMove = if (i > section) 1 else -1; swipeEnter = swiped
        section = i; managing = false; albumKind = null; album = null; prefs.edit().putInt("gallery_section", i).apply(); endSelection(false)
        if (swiped) reload() else root.postDelayed({ if (!isDestroyed) reload() }, if (Glass.reduced(this)) 0 else 120)
    }

    // ---------- Picker: swipe sideways to move between albums ----------
    private var pkX = 0f; private var pkY = 0f; private var pkOn = false; private var pkOff = true
    private fun pickerAlbums(): List<String?> = listOf<String?>(null) + albums(lastItems.filter { pickAllows(it) }).map { it.name }
    /** In the picker a sideways swipe on the grid goes to the next or previous album (the first page is everything). */
    private fun albumSwipe(e: MotionEvent, slop: Int): Boolean {
        val page = pageView ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pkOn = false; pkOff = selected != null || !page.isAttachedToWindow || sortMenu.showing || inside(swipeKeep, e) || (albumKind != null && albumKind != "bucket"); pkX = e.rawX; pkY = e.rawY }
            MotionEvent.ACTION_POINTER_DOWN -> if (!pkOn) pkOff = true
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - pkX; val dy = e.rawY - pkY
                if (!pkOn && !pkOff) {
                    if (kotlin.math.abs(dx) > slop * 1.5f && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f) { pkOn = true; pkX = e.rawX - dx
                        val c = MotionEvent.obtain(e); c.action = MotionEvent.ACTION_CANCEL; super.dispatchTouchEvent(c); c.recycle()
                    } else if (kotlin.math.abs(dy) > slop * 1.5f) pkOff = true
                }
                if (pkOn) { page.translationX = dx * 0.5f; page.alpha = 1f - 0.4f * kotlin.math.min(1f, kotlin.math.abs(dx) / page.width.coerceAtLeast(1)); return true }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (pkOn) {
                pkOn = false; val dx = e.rawX - pkX; val w = page.width.coerceAtLeast(1)
                val names = pickerAlbums(); val at = if (albumKind == "bucket") names.indexOf(album).coerceAtLeast(0) else 0
                val to = at + if (dx < 0) 1 else -1
                if (e.actionMasked == MotionEvent.ACTION_UP && kotlin.math.abs(dx) > w * 0.2f && to in names.indices) {
                    tabMove = if (dx < 0) 1 else -1; swipeEnter = true
                    albumKind = if (names[to] == null) null else "bucket"; album = names[to]; category = 0
                    Glass.tick(page); notice(names[to] ?: "ทุกอัลบั้ม"); reload()
                } else page.animate().translationX(0f).alpha(1f).setInterpolator(Glass.SPRING).setDuration(360).start()
                return true
            }
        }
        return false
    }
    // ---------- Swiping between the two tabs ----------
    private var navTabs: GlassTabs? = null
    /** The tab page that a sideways swipe moves (the grid column or the albums list); null on other screens. */
    private var pageView: View? = null
    /** A view that keeps its own sideways drags (the category strip). */
    private var swipeKeep: View? = null
    private var swipeEnter = false
    private var swX = 0f; private var swY = 0f; private var swiping = false; private var swOff = false
    private var swTracker: android.view.VelocityTracker? = null
    private fun canSwipe() = pageView?.isAttachedToWindow == true && selected == null && albumKind == null && !selecting && peek == null && !managing && !sortMenu.showing
    private fun inside(v: View?, e: MotionEvent): Boolean {
        if (v == null || !v.isShown) return false
        val at = IntArray(2); v.getLocationOnScreen(at)
        return e.rawX >= at[0] && e.rawX <= at[0] + v.width && e.rawY >= at[1] && e.rawY <= at[1] + v.height
    }
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        // A dropdown opened by holding a button keeps following that finger: slide to a row and lift to pick it.
        GlassMenu.route(e) { super.dispatchTouchEvent(it) }?.let { return it }
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        if (pickKind != null && albumSwipe(e, slop)) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { swiping = false; swOff = !canSwipe() || inside(swipeKeep, e) || inside(navTabs, e); swX = e.rawX; swY = e.rawY
                swTracker?.recycle(); swTracker = android.view.VelocityTracker.obtain().also { it.addMovement(e) } }
            MotionEvent.ACTION_POINTER_DOWN -> if (!swiping) swOff = true
            MotionEvent.ACTION_MOVE -> {
                swTracker?.addMovement(e)
                val dx = e.rawX - swX; val dy = e.rawY - swY
                if (!swiping && !swOff) {
                    if (kotlin.math.abs(dx) > slop * 1.5f && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f) {
                        val to = section + if (dx < 0) 1 else -1
                        if (to in 0..1) {
                            swiping = true; swX = e.rawX - dx
                            // The page under the finger gives the touch up (a pressed cell is released, a scroll stops).
                            val c = MotionEvent.obtain(e); c.action = MotionEvent.ACTION_CANCEL; super.dispatchTouchEvent(c); c.recycle()
                        } else swOff = true
                    } else if (kotlin.math.abs(dy) > slop * 1.5f) swOff = true
                }
                if (swiping) { dragPage(dx); return true }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (swiping) {
                swiping = false; val t = swTracker; t?.addMovement(e); t?.computeCurrentVelocity(1000); val vx = t?.xVelocity ?: 0f
                endPage(e.rawX - swX, if (e.actionMasked == MotionEvent.ACTION_CANCEL) 0f else vx); return true
            }
        }
        return super.dispatchTouchEvent(e)
    }
    /** The page follows the finger; dragging past the last tab only stretches a little. */
    private fun dragPage(dx: Float) {
        val page = pageView ?: return; val w = page.width.coerceAtLeast(1).toFloat()
        val allowed = if (section == 0) dx.coerceAtMost(0f) else dx.coerceAtLeast(0f)
        val d = allowed + (dx - allowed) * 0.2f
        page.translationX = d; page.alpha = 1f - 0.45f * kotlin.math.min(1f, kotlin.math.abs(d) / w)
        navTabs?.pos = section - d / w
    }
    private fun endPage(dx: Float, vx: Float) {
        val page = pageView ?: return; val w = page.width.coerceAtLeast(1).toFloat()
        val to = 1 - section; val toward = if (section == 0) -1 else 1
        val go = (dx * toward > w * 0.28f && vx * toward > -300) || (vx * toward > 900 && dx * toward > 0)
        if (go) {
            Glass.tick(page)
            page.animate().translationX(toward * w * 0.6f).alpha(0f).setInterpolator(Glass.EASE).setDuration(200).start()
            navTabs?.animateTo(to); switchSection(to, true)
        } else {
            page.animate().translationX(0f).alpha(1f).setInterpolator(Glass.SPRING).setDuration(420).start()
            navTabs?.animateTo(section)
        }
    }
    /** The new page slides in from the side of the tab that was tapped. */
    private fun slideIn(v: View) {
        if (tabMove == 0 || Glass.reduced(this)) return
        // After a swipe the new page carries on from the side the finger pushed it, a little further than after a tap.
        v.alpha = 0f; v.translationX = tabMove * (if (swipeEnter) resources.displayMetrics.widthPixels * 0.35f else dp(48).toFloat())
        v.animate().alpha(1f).translationX(0f).setInterpolator(Glass.EASE).setDuration(if (swipeEnter) 380 else 300).start()
        swipeEnter = false
    }
    private fun accessCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = glass(22); setPadding(dp(16), dp(14), dp(16), dp(14))
        addView(text("ตอนนี้แสดงเฉพาะรูปที่ถ่ายด้วย Lumora อนุญาตให้อ่านรูปในเครื่องเพื่อดูทุกรูปและอัลบั้ม", 13f, Glass.TEXT).apply { maxLines = 8; ellipsize = null })
        addView(text("อนุญาต", 13.5f, Glass.ON_ACCENT, true).apply { gravity = Gravity.CENTER; background = glass(18, Glass.ACCENT); isClickable = true; glassTouch(); setOnClickListener { mediaRequest.launch(mediaPermissions) } },
            LinearLayout.LayoutParams(dp(120), dp(48)).apply { topMargin = dp(10) })
    }

    private fun showAlbums(items: List<Item>, lumora: List<Item>, favourites: Int) {
        showingId = null; viewerKind = ""; immersive(false); root.removeAllViews()
        val bar = topBar("อัลบั้ม") { finish() }
        if (device) bar.addView(EditorKit.chip(this, if (managing) "เสร็จ" else "จัดการ", if (managing) Icons.CHECK else Icons.GEAR, managing) { managing = !managing; reload() }, LinearLayout.LayoutParams(-2, dp(48)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(16)) }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        // The albums page remembers how far it was scrolled; the listener starts only after the place is restored.
        scroll.post { if (!isDestroyed) { scroll.scrollTo(0, albumsSpot); scroll.setOnScrollChangeListener { _, _, y, _, _ -> albumsSpot = y } } }
        val buckets = if (device) ordered(albums(items)) else emptyList()
        val hidden = prefList("album_hidden"); val pinned = prefList("album_pinned")
        if (managing) {
            col.addView(text("ลาก ≡ หรือกดค้างแถวเพื่อเรียง · ปักหมุดให้อยู่บนสุด · ซ่อนเพื่อเอาออกจากแท็บรูปภาพ", 12.5f, Glass.TEXT2).apply { maxLines = 3; setPadding(dp(16), dp(4), dp(16), dp(10)) }, -1, -2)
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(list, -1, -2)
            manageList(list, buckets.toMutableList(), scroll)
        } else {
            col.addView(sectionHead("สื่อของฉัน"))
            val mine = listOf(
                Triple("lumora", "ถ่ายด้วย Lumora", lumora.filter { !it.file }),
                Triple("video", "วิดีโอ", items.filter { it.video }),
                Triple("fav", "รายการโปรด", emptyList()),
                Triple("raw", "RAW", items.filter { isRaw(it) }),
                Triple("ai", "ภาพ AI", items.filter { it.shot?.ai != null }))
                .filter { (k, _, l) -> k == "lumora" || k == "video" || (k == "fav" && favourites > 0) || l.isNotEmpty() }
            val icons = mapOf("lumora" to Icons.PHOTO, "video" to Icons.VIDEO, "fav" to HEART, "raw" to Icons.RAW, "ai" to Icons.AI)
            albumGrid(col, mine.map { (k, name, l) -> AlbumCard(name, if (k == "fav") favourites else l.size, l.firstOrNull(), icons[k] ?: Icons.ALBUM, false) { openAlbum(k) } })
            if (device) {
                val shown = buckets.filter { it.name !in hidden }
                col.addView(sectionHead("อัลบั้มในเครื่อง", "${shown.size} อัลบั้ม"))
                albumGrid(col, shown.map { a -> AlbumCard(a.name, a.count, a.cover, Icons.ALBUM, a.name in pinned) { openAlbum("bucket", a.name) } })
                val gone = buckets.filter { it.name in hidden }
                if (gone.isNotEmpty()) {
                    col.addView(LinearLayout(this).apply {
                        gravity = Gravity.CENTER_VERTICAL; isClickable = true; minimumHeight = dp(52); setPadding(dp(16), 0, dp(16), 0); contentDescription = "อัลบั้มที่ซ่อน ${gone.size} อัลบั้ม"
                        addView(IconView(this@GalleryActivity, EYE_OFF, 2f).apply { tint = Glass.TEXT2 }, dp(20), dp(20))
                        addView(text("อัลบั้มที่ซ่อน · ${gone.size}", 14f, Glass.TEXT2, true).apply { setPadding(dp(10), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
                        addView(IconView(this@GalleryActivity, if (showHidden) Icons.CHEVRON_UP else Icons.CHEVRON_RIGHT, 2f).apply { tint = Glass.TEXT2 }, dp(18), dp(18))
                        glassTouch(); setOnClickListener { showHidden = !showHidden; reload() }
                    }, -1, -2)
                    if (showHidden) albumGrid(col, gone.map { a -> AlbumCard(a.name, a.count, a.cover, EYE_OFF, false) { openAlbum("bucket", a.name) } })
                }
            } else col.addView(accessCard(), LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(12), dp(16), 0) })
        }
        root.addView(bottomNav(), LinearLayout.LayoutParams(-1, dp(60)).apply { setMargins(dp(40), dp(4), dp(40), dp(12)) })
        pageView = scroll; swipeKeep = null
        slideIn(scroll)
    }
    private class AlbumCard(val name: String, val count: Int, val cover: Item?, val icon: String, val pinned: Boolean, val open: () -> Unit)
    /** Two columns of album covers with the name and count under each. */
    private fun albumGrid(col: LinearLayout, cards: List<AlbumCard>) {
        cards.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { setPadding(dp(12), 0, dp(12), dp(14)) }
            pair.forEach { a ->
                row.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; isClickable = true; isFocusable = true; contentDescription = "อัลบั้ม ${a.name} ${a.count} รายการ${if (a.pinned) " ปักหมุด" else ""}"
                    setPadding(dp(4), 0, dp(4), 0)
                    val frame = FrameLayout(this@GalleryActivity).apply { background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Glass.PANEL, Glass.BG_DEEP)) }
                    rounded(frame, 20)
                    frame.addView(IconView(this@GalleryActivity, a.icon, 1.8f).apply { tint = 0x88ffffff.toInt() }, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
                    val img = ImageView(this@GalleryActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                    frame.addView(img, -1, -1)
                    if (a.pinned) frame.addView(FrameLayout(this@GalleryActivity).apply { background = glass(14, 0x99000000.toInt())
                        addView(IconView(this@GalleryActivity, Icons.PIN, 2f).apply { tint = Glass.ACCENT }, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER)) }, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(8), dp(8), 0) })
                    a.cover?.let { c -> img.tag = c.key; thumbs.execute { val b = thumbnail(c, 360); runOnUiThread { if (!isDestroyed && img.tag == c.key && b != null) { img.setImageBitmap(b); if (!Glass.reduced(this@GalleryActivity)) { img.alpha = 0f; img.animate().alpha(1f).setDuration(180).start() } } } } }
                    addView(frame, LinearLayout.LayoutParams(-1, dp(156)))
                    addView(text(a.name, 14f, Glass.TEXT, true).apply { setPadding(dp(2), dp(8), 0, 0) }, -1, -2)
                    addView(text("${a.count} รายการ", 12f, Glass.TEXT2).apply { setPadding(dp(2), dp(2), 0, 0) }, -1, -2)
                    glassTouch(); setOnClickListener { Glass.tick(this); a.open() }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            col.addView(row, -1, -2)
        }
    }

    /**
     * Manage mode: one row per album with a drag handle, pin and hide. Dragging the handle lifts the row; the rows it
     * passes slide out of the way, and the new order is saved when the finger lifts. Pinned albums stay on top.
     */
    private fun manageList(list: LinearLayout, albums: MutableList<Album>, scroll: ScrollView) {
        list.removeAllViews()
        val pinned = prefList("album_pinned").toMutableList(); val hidden = prefList("album_hidden").toMutableList()
        val rowH = dp(68)
        fun commit() {
            val sorted = albums.sortedBy { it.name !in pinned }   // stable: keeps the dragged order inside each group
            albums.clear(); albums.addAll(sorted)
            savePrefList("album_order", albums.map { it.name }); savePrefList("album_pinned", pinned); savePrefList("album_hidden", hidden)
            manageList(list, albums, scroll)
        }
        albums.forEachIndexed { index, a ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(12), 0); background = glass(18, 0x14ffffff) }
            val handle = FrameLayout(this).apply { contentDescription = "ลากเพื่อเรียง ${a.name}"
                addView(IconView(this@GalleryActivity, "M5 8h14 M5 12h14 M5 16h14", 2f).apply { tint = Glass.TEXT2 }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)) }
            row.addView(handle, dp(48), dp(48))
            val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = glass(12, 0x22ffffff) }
            rounded(img, 12); row.addView(img, dp(48), dp(48))
            img.tag = a.cover.key; thumbs.execute { val b = thumbnail(a.cover, 160); runOnUiThread { if (!isDestroyed && img.tag == a.cover.key && b != null) img.setImageBitmap(b) } }
            row.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0)
                addView(text(a.name, 14.5f, if (a.name in hidden) Glass.TEXT3 else Color.WHITE, true)); addView(text("${a.count} รายการ${if (a.name in hidden) " · ซ่อนอยู่" else ""}", 12f, Glass.TEXT2)) }, LinearLayout.LayoutParams(0, -2, 1f))
            val isPinned = a.name in pinned; val isHidden = a.name in hidden
            row.addView(FrameLayout(this).apply { isClickable = true; contentDescription = if (isPinned) "เลิกปักหมุด ${a.name}" else "ปักหมุด ${a.name}"; isSelected = isPinned; glassTouch()
                addView(IconView(this@GalleryActivity, Icons.PIN, 2f).apply { tint = if (isPinned) Glass.ACCENT else Glass.TEXT2 }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
                setOnClickListener { Glass.tick(this); if (isPinned) pinned.remove(a.name) else pinned.add(a.name); commit() } }, dp(48), dp(48))
            row.addView(FrameLayout(this).apply { isClickable = true; contentDescription = if (isHidden) "แสดง ${a.name}" else "ซ่อน ${a.name}"; isSelected = isHidden; glassTouch()
                addView(IconView(this@GalleryActivity, if (isHidden) EYE_OFF else EYE, 2f).apply { tint = if (isHidden) Glass.ACCENT else Glass.TEXT2 }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
                setOnClickListener { Glass.tick(this); if (isHidden) hidden.remove(a.name) else hidden.add(a.name); commit() } }, dp(48), dp(48))
            list.addView(row, LinearLayout.LayoutParams(-1, rowH - dp(8)).apply { setMargins(dp(12), dp(4), dp(12), dp(4)) })
            // The ≡ handle sits near the left edge, where Android's back gesture lives: the handle asks the
            // system to leave that strip alone, so a drag there reorders instead of closing the gallery.
            handle.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                if (Build.VERSION.SDK_INT >= 29) v.systemGestureExclusionRects = listOf(android.graphics.Rect(0, 0, v.width, v.height)) }
            var startY = 0f; var target = index; var dragging = false
            val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
            fun begin(y: Float) { dragging = true; startY = y; target = index; scroll.requestDisallowInterceptTouchEvent(true)
                row.elevation = dp(8).toFloat(); row.animate().scaleX(1.03f).scaleY(1.03f).setDuration(120).start(); Glass.tick(row) }
            fun move(y: Float) {
                val dy = y - startY; row.translationY = dy
                val t = (index + Math.round(dy / rowH)).coerceIn(0, albums.lastIndex)
                if (t != target) { target = t; Glass.tick(row)
                    for (k in 0 until list.childCount) if (k != index) {
                        val shift = when { k in (index + 1)..target -> -rowH; k in target until index -> rowH; else -> 0 }
                        list.getChildAt(k).animate().translationY(shift.toFloat()).setInterpolator(Glass.EASE).setDuration(160).start()
                    } }
            }
            fun end(cancel: Boolean) {
                if (!dragging) return; dragging = false
                if (!cancel && target != index) { val moved = albums.removeAt(index); albums.add(target, moved); commit(); return }
                row.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(160).start(); row.elevation = 0f
                for (k in 0 until list.childCount) if (k != index) list.getChildAt(k).animate().translationY(0f).setDuration(160).start()
            }
            handle.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> begin(e.rawY)
                    MotionEvent.ACTION_MOVE -> if (dragging) move(e.rawY)
                    // A cancel (the system took the touch) still keeps the place the finger reached, so nothing jumps back.
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> end(false)
                }
                true
            }
            // Holding anywhere else on the row also picks it up, away from the edge gesture entirely.
            var downY = 0f; val lift = Runnable { begin(downY) }
            row.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downY = e.rawY; row.postDelayed(lift, android.view.ViewConfiguration.getLongPressTimeout().toLong()) }
                    MotionEvent.ACTION_MOVE -> if (dragging) move(e.rawY) else if (kotlin.math.abs(e.rawY - downY) > slop) row.removeCallbacks(lift)
                    MotionEvent.ACTION_UP -> { row.removeCallbacks(lift); end(false) }
                    MotionEvent.ACTION_CANCEL -> { row.removeCallbacks(lift); end(!dragging) }
                }
                true
            }
        }
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
        selected = item.shot?.id ?: item.key; useAi = false; reload()
    }
    /** Keys in the order the open grid shows them (album, category, search and sort applied); the viewer pages through exactly these. */
    private var navKeys: List<String> = emptyList()
    private fun itemKey(i: Item) = i.shot?.id ?: i.key
    private fun neighbourItem(dir: Int): Item? {
        val byKey = lastItems.associateBy { itemKey(it) }
        var i = navKeys.indexOf(selected)
        if (i < 0) {
            // Opened without a grid (camera, editor result): same album, newest first.
            navKeys = lastItems.filter { !it.file && inAlbum(it) }.sortedByDescending { it.shotAt }.map { itemKey(it) }
            i = navKeys.indexOf(selected)
        }
        if (i < 0) return null
        return navKeys.getOrNull(i + dir)?.let { byKey[it] }
    }
    /** Loads the two pictures beside the open one (fit-to-screen copies), so a page turn has something to slide in at once. */
    private fun preloadNear() {
        val want = listOfNotNull(neighbourItem(-1), neighbourItem(1))
        val keys = want.map { it.key }.toSet()
        near.keys.filter { it !in keys }.forEach { near.remove(it) }
        want.filter { it.key !in near }.forEach { item -> thumbs.execute { val b = runCatching { thumbnail(item, 1440) }.getOrNull(); runOnUiThread { if (!isDestroyed && b != null) near[item.key] = b } } }
    }
    /** Next or previous item of the last grid: the viewer has already slid it in, so only the bars and buttons are refreshed. */
    private fun step(dir: Int) {
        val next = neighbourItem(dir)
        if (next == null) { if (::inspect.isInitialized) inspect.stay(); return }
        stepping = true
        selected = next.shot?.id ?: next.key; useAi = false; lastViewed = selected
        val same = !next.video && ::inspect.isInitialized && inspect.isAttachedToWindow && viewerKind == (if (next.shot != null) "shot" else "device")
        if (!same) { reload(); return }
        inspect.show(next.uri, near[next.key] ?: cache.get(next.key))
        val shot = next.shot
        if (shot != null) luma.readIo.execute { val s = luma.shots.get(shot.id); runOnUiThread { if (!isDestroyed && selected == shot.id) { if (s != null) showDetail(s) else reload() } } }
        else showDeviceItem(next.uri)
    }
    /** Viewer top bar: back to the grid, plus a grid button so the full library is always one tap away. */
    private fun viewerBar(title: String): LinearLayout {
        val bar = topBar(title) { if (viewerFromCamera) finish() else { selected = null; useAi = false; reload() } }
        bar.addView(roundButton(Icons.LIBRARY, "ดูรูปทั้งหมด") { viewerFromCamera = false; selected = null; useAi = false; reload() }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(8) })
        return bar
    }
    private fun viewer(): InspectView = InspectView(this).also { v ->
        v.onSwipe = { step(it) }
        v.neighbour = { d -> neighbourItem(d)?.let { near[it.key] ?: cache.get(it.key) } }; v.hasNeighbour = { d -> neighbourItem(d) != null }
        ring = AiRing(this)
        val frame = FrameLayout(this).apply { addView(v, FrameLayout.LayoutParams(-1, -1)); addView(ring, FrameLayout.LayoutParams(-1, -1)) }
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        // Opening springs the photo up from a little smaller, like the phone's own gallery.
        if (!Glass.reduced(this) && !stepping) { frame.scaleX = 0.86f; frame.scaleY = 0.86f; frame.alpha = 0f
            frame.animate().scaleX(1f).scaleY(1f).alpha(1f).setInterpolator(Glass.SPRING).setDuration(420).start() }
        stepping = false
    }
    /** True while swiping to the next/previous photo, which has its own slide and must not bounce. */
    private var stepping = false
    private var bars = android.graphics.Rect()
    private var immersive = false
    private fun padHost() { host.setPadding(bars.left, if (immersive) 0 else bars.top, bars.right, if (immersive) 0 else bars.bottom) }
    private fun immersive(on: Boolean) { if (immersive != on) { immersive = on; padHost() } }
    /**
     * Viewer layout: the picture fills the screen and the top bar and the action row float over it, the picture's
     * blurred reflection showing through, so there is no band of page colour above or below it.
     */
    private fun floatBars() {
        val frame = inspect.parent as? FrameLayout ?: return
        val kids = (0 until root.childCount).map { root.getChildAt(it) }.filter { it !== frame }
        kids.forEach { root.removeView(it) }
        val top = kids.firstOrNull()
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, bars.bottom) }
        kids.drop(1).forEach { bottom.addView(it, -1, -2) }
        if (top != null) frame.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = bars.top })
        frame.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        viewerTop = top; viewerBottom = bottom
        inspect.shadeTop = (bars.top + dp(84)).toFloat(); inspect.shadeBottom = (bars.bottom + dp(130)).toFloat()
        inspect.onDismissDrag = { p -> val a = 1f - p * 2.2f; viewerTop?.alpha = a.coerceIn(0f, 1f); viewerBottom?.alpha = a.coerceIn(0f, 1f) }
        inspect.onDismiss = if (!prefs.getBoolean("gallery_swipe_close", true)) null else ({ if (viewerFromCamera) finish() else { selected = null; useAi = false; closingViewer = true; reload() } })
        inspect.reflections = prefs.getBoolean("gallery_reflect", true)
    }
    private var viewerTop: View? = null; private var viewerBottom: View? = null
    /** True when the grid comes back from a swipe-down, so it fades up instead of sliding. */
    private var closingViewer = false

    // ---------- Long-press peek ----------
    private class PeekAction(val view: FrameLayout, val icon: IconView, val label: String, val tint: Int, val run: () -> Unit)
    private var peekActions: List<PeekAction> = emptyList()
    private var peekHot: PeekAction? = null
    private var peekLabel: TextView? = null
    /**
     * Hold a picture: a large preview rises with five actions down its right side. Without lifting, slide the finger
     * onto an action (it grows and names itself) and let go to run it; letting go anywhere else just closes the preview.
     */
    private fun showPeek(item: Item, from: View) {
        closePeek()
        Glass.tick(from, HapticFeedbackConstants.LONG_PRESS)
        val dim = View(this).apply { setBackgroundColor(0x7a000000); alpha = 0f }
        val card = LinearLayout(this).apply { background = glass(30, Glass.FILL_POP); setPadding(dp(20), dp(18), dp(10), dp(20)); visibility = View.INVISIBLE; clipChildren = false; clipToPadding = false }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true
            // Rounded to sit inside the card's corners instead of running into them.
            outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) {
                val d = v as ImageView; val b = d.drawable
                if (b == null || b.intrinsicWidth <= 0) { o.setRoundRect(0, 0, v.width, v.height, dp(20).toFloat()); return }
                val sc = minOf(v.width.toFloat() / b.intrinsicWidth, v.height.toFloat() / b.intrinsicHeight)
                val w = (b.intrinsicWidth * sc).toInt(); val h = (b.intrinsicHeight * sc).toInt(); val l = (v.width - w) / 2; val t = (v.height - h) / 2
                o.setRoundRect(l, t, l + w, t + h, dp(20).toFloat()) } }
            clipToOutline = true }
        val stage = FrameLayout(this).apply { addView(image, -1, -1) }
        left.addView(stage, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.46f).toInt()))
        // A held video plays (silently, on a loop) right in the preview, over its first frame.
        if (item.video) peekVideo(stage, item.uri)
        // Facts as a row of small tags rather than a line of text.
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(12), dp(4), 0) }
        head.addView(FrameLayout(this).apply { background = glass(12, 0x1fffffff); addView(IconView(this@GalleryActivity, if (item.video) Icons.VIDEO else Icons.PHOTO, 1.8f).apply { tint = Glass.ACCENT }, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER)) }, dp(30), dp(30))
        head.addView(text(formatTime(item.time), 14.5f, Glass.TEXT, true).apply { setPadding(dp(10), 0, 0, 0); maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f))
        left.addView(head, -1, -2)
        val tags = FlowLayout(this).apply { gap = dp(6); setPadding(dp(4), dp(8), dp(4), 0) }
        fun tag(t: String, accent: Boolean = false) = tags.addView(text(t, 11.5f, if (accent) Glass.ACCENT else Glass.TEXT2, accent).apply { gravity = Gravity.CENTER; maxLines = 1; background = glass(11, if (accent) Glass.ACCENT_SOFT else 0x14ffffff); setPadding(dp(9), 0, dp(9), 0) }, ViewGroup.LayoutParams(-2, dp(24)))
        item.album.takeIf { it.isNotBlank() }?.let { tag(it) }
        item.shot?.let { tag(pixelsText(it.size.pixels)) }
        item.shot?.takeIf { it.ai != null }?.let { tag("AI ×${it.aiScale.coerceAtLeast(1)} · ${pixelsText(it.aiSize.pixels)}", true) }
        if (tags.childCount > 0) left.addView(tags, -1, -2)
        val label = text("เลื่อนนิ้วไปที่ปุ่มแล้วปล่อย", 12f, Glass.TEXT3).apply { setPadding(dp(4), dp(8), dp(4), 0); maxLines = 1 }
        left.addView(label, -1, -2); peekLabel = label
        card.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        val rail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; clipChildren = false }
        val actions = ArrayList<PeekAction>()
        fun act(icon: String, name: String, tint: Int, run: () -> Unit) {
            val ic = IconView(this, icon, 2f).apply { this.tint = tint }
            val b = FrameLayout(this).apply { background = glass(23, 0x1fffffff); contentDescription = name; isClickable = true; addView(ic, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)); setOnClickListener { closePeek(); run() } }
            rail.addView(b, LinearLayout.LayoutParams(dp(46), dp(46)).apply { setMargins(dp(8), dp(4), dp(4), dp(4)) }); actions += PeekAction(b, ic, name, tint, run)
        }
        act(HEART, "รายการโปรด", Color.WHITE) { favourite(listOf(item)) }
        if (!item.file) act(EditorKit.ADJUST, if (item.video) "ตัดต่อ" else "แก้ไขภาพ", Color.WHITE) {
            if (item.video) editVideo.launch(VideoEditorActivity.intent(this, item.uri)) else editPhoto.launch(PhotoEditorActivity.intent(this, item.shot?.ai ?: item.uri)) }
        if (!item.video && !item.file) act(Icons.AI, "ปรับด้วย AI", Glass.ACCENT) { item.shot?.let { EnhanceUi.show(this, it) } ?: enhanceDevice(item.uri) }
        // A video goes to the editor, which opens its export sheet straight away with an AI model chosen.
        if (item.video && !item.file) act(Icons.AI, "ปรับด้วย AI", Glass.ACCENT) { editVideo.launch(VideoEditorActivity.intent(this, item.uri).putExtra("ai_export", true)) }
        act(RENAME, "เปลี่ยนชื่อ", Color.WHITE) { rename(item) }
        act(Icons.SHARE, "แชร์", Color.WHITE) { share(item) }
        act(Icons.INFO, "รายละเอียด", Color.WHITE) { itemInfo(item) }
        act(TRASH, "ลบ", RED) { confirmDelete(listOf(item)) }
        card.addView(rail, LinearLayout.LayoutParams(-2, -1))
        peekActions = actions; peekHot = null
        val layer = FrameLayout(this).apply { addView(dim, -1, -1); addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { setMargins(dp(16), 0, dp(16), 0) }) }
        // The name of the button under the finger floats out to its left, over the picture.
        val tip = text("", 14f, Glass.TEXT, true).apply { background = glass(16, Glass.alpha(Glass.PANEL, 0xee)); setPadding(dp(14), dp(8), dp(14), dp(8)); alpha = 0f; elevation = dp(8).toFloat() }
        layer.addView(tip, FrameLayout.LayoutParams(-2, -2)); peekTip = tip
        host.addView(layer, -1, -1); peek = layer
        dim.animate().alpha(1f).setDuration(220).start(); Glass.blur(root, 14f)
        thumbs.execute { val b = thumbnail(item, 1080); runOnUiThread { if (peek === layer) { image.setImageBitmap(b); image.invalidateOutline() } } }
        card.post {
            val loc = IntArray(2); from.getLocationInWindow(loc); val c = IntArray(2); card.getLocationInWindow(c)
            Liquid.show(card, (loc[0] - c[0] + from.width / 2f).coerceIn(0f, card.width.toFloat()), (loc[1] - c[1] + from.height / 2f).coerceIn(0f, card.height.toFloat()), true)
        }
        layer.setOnClickListener { closePeek() }
    }
    /** The finger that opened the peek: over an action it lights up; lifting runs it (or just closes the peek). */
    private fun peekTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val pad = dp(10); val at = IntArray(2)
                val hit = peekActions.firstOrNull { a -> a.view.getLocationOnScreen(at); e.rawX >= at[0] - pad && e.rawX <= at[0] + a.view.width + pad && e.rawY >= at[1] - pad / 2 && e.rawY <= at[1] + a.view.height + pad / 2 }
                if (hit !== peekHot) {
                    peekHot?.let { a -> a.view.animate().scaleX(1f).scaleY(1f).setDuration(160).start(); a.view.background = glass(23, 0x1fffffff); a.icon.tint = a.tint }
                    peekHot = hit
                    if (hit != null) { Glass.tick(hit.view); hit.view.animate().scaleX(1.28f).scaleY(1.28f).setInterpolator(Glass.SPRING).setDuration(260).start(); hit.view.background = glass(23, if (hit.tint == RED) RED else Glass.ACCENT); hit.icon.tint = if (hit.tint == RED) Color.WHITE else Glass.ON_ACCENT }
                    peekLabel?.animate()?.alpha(if (hit != null) 0f else 1f)?.setDuration(160)?.start()
                    peekTip?.let { t -> showTip(t, hit) }
                }
            }
            MotionEvent.ACTION_UP -> { val run = peekHot?.run; closePeek(); run?.invoke() }
            MotionEvent.ACTION_CANCEL -> closePeek()
        }
    }
    private var peekTip: TextView? = null
    /** Moves the floating name next to the button and pops it in from the button's side (or hides it). */
    private fun showTip(t: TextView, hit: PeekAction?) {
        if (hit == null) { t.animate().alpha(0f).translationX(t.translationX + dp(10)).scaleX(0.9f).scaleY(0.9f).setDuration(140).start(); return }
        t.text = hit.label; t.setTextColor(if (hit.tint == RED) RED else Color.WHITE)
        t.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val at = IntArray(2); val base = IntArray(2); hit.view.getLocationInWindow(at); (t.parent as View).getLocationInWindow(base)
        val x = at[0] - base[0] - t.measuredWidth - dp(14).toFloat(); val y = at[1] - base[1] + hit.view.height / 2f - t.measuredHeight / 2f
        t.pivotX = t.measuredWidth.toFloat(); t.pivotY = t.measuredHeight / 2f
        t.animate().cancel()
        if (t.alpha < 0.05f) { t.translationX = x + dp(18); t.translationY = y; t.scaleX = 0.7f; t.scaleY = 0.7f }
        t.animate().alpha(1f).translationX(x).translationY(y).scaleX(1f).scaleY(1f).setInterpolator(Glass.SPRING).setDuration(320).start()
    }
    private var peekPlayer: android.media.MediaPlayer? = null
    private fun peekVideo(stage: FrameLayout, uri: android.net.Uri) {
        val tv = android.view.TextureView(this).apply { alpha = 0f; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(20).toFloat()) } }
            clipToOutline = true }
        stage.addView(tv, FrameLayout.LayoutParams(0, 0, Gravity.CENTER))
        fun fit(vw: Int, vh: Int) {
            if (vw <= 0 || vh <= 0 || stage.width <= 0) return
            val sc = minOf(stage.width.toFloat() / vw, stage.height.toFloat() / vh)
            tv.layoutParams = FrameLayout.LayoutParams((vw * sc).toInt(), (vh * sc).toInt(), Gravity.CENTER)
        }
        tv.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                releasePeekVideo()
                val mp = android.media.MediaPlayer(); peekPlayer = mp
                runCatching {
                    mp.setDataSource(this@GalleryActivity, uri); mp.setSurface(android.view.Surface(st)); mp.isLooping = true; mp.setVolume(0f, 0f)
                    mp.setOnVideoSizeChangedListener { _, vw, vh -> fit(vw, vh) }
                    mp.setOnInfoListener { _, what, _ -> if (what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) tv.animate().alpha(1f).setDuration(200).start(); false }
                    mp.setOnErrorListener { _, _, _ -> true }
                    mp.setOnPreparedListener { if (peekPlayer === it) it.start() }
                    mp.prepareAsync()
                }.onFailure { releasePeekVideo() }
            }
            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean { releasePeekVideo(); return true }
            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
        }
        // Until the clip reports its size the view is 0×0; a first guess keeps the surface alive meanwhile.
        stage.post { if (tv.width == 0) tv.layoutParams = FrameLayout.LayoutParams(stage.width, stage.height, Gravity.CENTER) }
    }
    private fun releasePeekVideo() { val mp = peekPlayer ?: return; peekPlayer = null; runCatching { mp.stop() }; runCatching { mp.release() } }
    private fun closePeek() {
        releasePeekVideo()
        peekTip = null
        val layer = peek as? FrameLayout ?: return; peek = null; peekActions = emptyList(); peekHot = null; peekLabel = null
        Glass.blur(root, 0f)
        layer.getChildAt(0).animate().alpha(0f).setDuration(200).start()
        val card = layer.getChildAt(1)
        Liquid.hide(card, card.width / 2f, card.height / 2f, false) { host.removeView(layer) }
    }

    // ---------- Device item ----------
    private fun showDeviceItem(uri: Uri) {
        val video = contentResolver.getType(uri)?.startsWith("video") == true
        if (video) { showVideoItem(lastItems.firstOrNull { it.uri == uri } ?: Item("device:$uri", uri, System.currentTimeMillis(), true, "", "", null)); return }
        val reuse = !video && viewerKind == "device" && ::inspect.isInitialized && inspect.isAttachedToWindow
        showingId = uri.toString()
        if (!reuse) {
            root.removeAllViews()
            viewerBar("ในเครื่อง").addView(roundButton(Icons.INFO, "ข้อมูลภาพ") { itemInfo(lastItems.firstOrNull { it.uri.toString() == showingId } ?: Item("device:$showingId", Uri.parse(showingId), System.currentTimeMillis(), false, "", "", null)) }, dp(48), dp(48))
            inspect = viewer(); viewerKind = if (video) "" else "device"
            toggle = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(6), dp(6), dp(6), dp(10)) }
            root.addView(toggle, -1, -2)
            if (!video) { immersive(true); floatBars() } else immersive(false)
            if (!Glass.reduced(this)) { inspect.alpha = 0f; inspect.animate().alpha(1f).setDuration(260).start() }
        }
        if (!video) inspect.show(uri)
        toggle.removeAllViews()
        val item = Item("device:$uri", uri, System.currentTimeMillis(), video, "", "", null)
        if (video) { chip("เล่นวิดีโอ", false, Icons.VIDEO) { VideoPlayerActivity.open(this, uri) }; chip("ตัดต่อ", false, EditorKit.SCISSORS) { startActivity(VideoEditorActivity.intent(this, uri)) } }
        else { chip("แก้ไข", false, EditorKit.PEN) { editPhoto.launch(PhotoEditorActivity.intent(this, uri)) }; chip("ปรับด้วย AI", false, Icons.AI) { enhanceDevice(uri) } }
        chip("แชร์", false, Icons.SHARE) { share(item) }
        chip("ลบ", false, TRASH) { confirmDelete(listOf(lastItems.firstOrNull { it.uri == uri } ?: item)) }
        preloadNear()
    }

    /** Any photo on the phone can go through the AI: it joins the Lumora list as a reference to the original (never moved or deleted). */
    private fun enhanceDevice(uri: Uri) = EnhanceUi.showForUri(this, uri)

    // ---------- Lumora video or file from the ledger ----------
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun showLedgerItem(item: Item) {
        if (item.video) { showVideoItem(item); return }
        showingId = item.key; viewerKind = ""; immersive(false); root.removeAllViews()
        viewerBar(if (item.video) "วิดีโอ" else "ไฟล์")
        val frame = FrameLayout(this).apply { setBackgroundColor(Glass.BG_DEEP) }
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
        toggle = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(6), dp(2), dp(6), dp(10)) }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; isFillViewport = true; addView(toggle) }, -1, -2)
        chip("แชร์", false, Icons.SHARE) { MediaLedger.share(this, item.uri, item.mime) }
        chip("รายละเอียด", false, Icons.INFO) { itemInfo(item) }
        chip("ลบ", false, TRASH) { confirmDelete(listOf(item)) }
    }

    // ---------- Any video (Lumora's or the phone's): the player in the viewer, swipe for the next item ----------
    private var inlineVideo: InlineVideo? = null
    /**
     * While the open photo's AI runs, its ring reads the live tile count about three times a second: the number
     * moves smoothly, and the time left and tiles done show under it, like the video export page.
     */
    private val aiPoll = object : Runnable { override fun run() {
        val id = selected ?: return
        if (!::ring.isInitialized || ring.visibility != View.VISIBLE || showingId != id || isDestroyed) return
        val p = th.ogay.luma.ai.AiProgress
        if (p.shotId == id) {
            if (p.total > 0) ring.update(p.fraction() * 99f, ring.caption, listOf("${String.format(Locale.US, "%,d", p.done)} / ${String.format(Locale.US, "%,d", p.total)} ไทล์", p.model).filter { it.isNotEmpty() }.joinToString(" · "), p.etaText())
            else ring.update(null, ring.caption, p.step)
        }
        host.postDelayed(this, 330)
    } }
    private var stepDir = 0
    private fun showVideoItem(item: Item) {
        showingId = item.key; viewerKind = ""; root.removeAllViews()
        // Like the photo viewer: the clip fills the screen and the bars float over its glow.
        immersive(true)
        val player = InlineVideo(this, item.uri, cache.get(item.key), prefs.getBoolean("gallery_reflect", true)).apply { onSwipe = { stepDir = it; step(it) } }
        inlineVideo = player
        val frame = FrameLayout(this).apply { addView(player, -1, -1) }
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        val top = viewerBar("วิดีโอ").also { root.removeView(it) }
        top.addView(roundButton(Icons.INFO, "รายละเอียด") { itemInfo(item) }, dp(48), dp(48))
        toggle = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(6), dp(2), dp(6), dp(10)) }
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, bars.bottom) }
        player.shades(bars.top + dp(84), bars.bottom + dp(150))
        bottom.addView(toggle, -1, -2)
        frame.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = bars.top })
        frame.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        viewerTop = top; viewerBottom = bottom
        bottom.post { player.controlsInset(bottom.height) }
        player.onChrome = { on -> listOf(top, bottom).forEach { it.animate().alpha(if (on) 1f else 0f).setDuration(220).start() } }
        // Moving to the next clip slides it in from the side the finger pushed, instead of a flash.
        if (!Glass.reduced(this)) { player.alpha = 0f; player.translationX = stepDir * dp(56).toFloat()
            player.animate().alpha(1f).translationX(0f).setInterpolator(Glass.EASE).setDuration(260).start() }
        stepDir = 0
        thumbs.execute { val b = thumbnail(item, 1080); runOnUiThread { if (showingId == item.key) player.poster(b) } }
        chip("แชร์", false, Icons.SHARE) { if (item.key.startsWith("device:")) share(item) else MediaLedger.share(this, item.uri, item.mime.ifEmpty { "video/mp4" }) }
        chip("ตัดต่อ", false, EditorKit.SCISSORS) { editVideo.launch(VideoEditorActivity.intent(this, item.uri)) }
        chip("รายละเอียด", false, Icons.INFO) { itemInfo(item) }
        chip("ลบ", false, TRASH) { confirmDelete(listOf(item)) }
        preloadNear()
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
    /** Viewer action, OPPO style: an icon over a short name, sharing the row equally so nothing runs off the edge. */
    private fun chip(label: String, active: Boolean, icon: String = "", click: () -> Unit) = toggle.addView(action(icon.ifEmpty { Icons.CLOSE }, label, active, if (icon == TRASH) RED else 0).apply {
        setOnClickListener { Glass.blurPulse(root, 4f, 200); click() } }, LinearLayout.LayoutParams(0, -2, 1f))
    private fun action(icon: String, label: String, active: Boolean = false, tint: Int = 0) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true; isFocusable = true; contentDescription = label; minimumHeight = dp(58); setPadding(dp(2), dp(6), dp(2), dp(4))
        val c = if (tint != 0) tint else if (active) Glass.ACCENT else Color.WHITE
        addView(IconView(this@GalleryActivity, icon, 2f).apply { this.tint = c }, LinearLayout.LayoutParams(dp(23), dp(23)))
        addView(text(label, 11f, if (tint != 0) tint else if (active) Glass.ACCENT else Glass.TEXT2, true).apply { gravity = Gravity.CENTER; maxLines = 3; ellipsize = null; setPadding(0, dp(5), 0, 0) }, -1, -2)
        glassTouch()
    }
    /** Gallery settings, laid out like the camera settings. */
    private fun gallerySettings() {
        SettingsPage(this, "ตั้งค่าแกลเลอรี") { reload() }.apply {
            section("การแสดงผล")
            action("ธีมสี", Icons.SUN, Theme.name()) { showThemeSettings { changedTheme -> if (changedTheme) { close(); recreate() } } }
            choose("จำนวนคอลัมน์", Icons.LIBRARY, "gallery_columns", listOf("2 คอลัมน์", "3 คอลัมน์", "4 คอลัมน์", "5 คอลัมน์"), listOf(2, 3, 4, 5), 3)
            choose("เรียงตามเริ่มต้น", Icons.UPDOWN, "gallery_sort2", SORTS, SORTS.indices.toList(), 0, SORT_ICONS)
            toggle("รวมภาพ AI กับต้นฉบับ", Icons.AI, "gallery_merge_ai", true, "ภาพที่ AI ปรับแล้วรวมเป็นรายการเดียวกับต้นฉบับ")
            section("การดูภาพ")
            toggle("เปิดภาพ AI ก่อน", Icons.AI, "gallery_open_ai", true, "กดค้างปุ่มต้นฉบับเพื่อเทียบ")
            toggle("พื้นหลังสะท้อนภาพ", Icons.PHOTO, "gallery_reflect", true, "ขอบบนล่างเป็นภาพสะท้อนนุ่ม ๆ แทนพื้นสีเรียบ")
            toggle("ปัดลงเพื่อปิดภาพ", Icons.CHEVRON_UP, "gallery_swipe_close", true)
            section("วิดีโอ")
            toggle("เล่นวิดีโออัตโนมัติ", Icons.VIDEO, "video_autoplay", false, "เริ่มเล่นทันทีที่เปิดหรือปัดมาที่วิดีโอ ปิดไว้จะเล่นเมื่อแตะปุ่มเล่น ปัดดูได้ลื่นกว่า")
            choose("เสียงเมื่อเริ่มเล่น", Icons.SOUND, "video_sound_i", listOf("เปิดเสียงทุกครั้ง", "ปิดเสียงทุกครั้ง"), listOf(1, 0), 1, notes = listOf("ได้ยินเสียงทันที", "แตะปุ่มลำโพงเพื่อเปิดเสียง"))
            section("การลบ")
            toggle("ถามก่อนลบภาพของ Lumora", TRASH, "gallery_ask_delete", true, "ภาพจากแอปอื่น ระบบจะถามเองเสมอ")
            section("AI")
            toggle("ประหยัดแรงเครื่องเมื่ออยู่นอกหน้าประมวลผล", Icons.STACK, "ai_saver", true, "AI ทำงานช้าลงเมื่อไม่ได้ดูความคืบหน้า เครื่องจึงลื่นขึ้น")
            section("อัลบั้ม")
            action("คืนลำดับ ปักหมุด และการซ่อนอัลบั้ม", Icons.RESET) { prefs.edit().remove("album_order").remove("album_pinned").remove("album_hidden").apply(); notice("คืนค่าอัลบั้มแล้ว") }
            action("ล้างภาพย่อในหน่วยความจำ", Icons.RESET, "ใช้เมื่อภาพย่อไม่ตรงกับภาพจริง") { cache.evictAll(); near.clear(); notice("ล้างแล้ว") }
        }.show()
    }
    private fun formatTime(t: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(t))
    private fun showDetail(s: Shot) {
        currentShot = s
        val reuse = viewerKind == "shot" && ::inspect.isInitialized && inspect.isAttachedToWindow
        if (!reuse) {
            root.removeAllViews()
            viewerBar("ภาพถ่าย").addView(roundButton(Icons.INFO, "ข้อมูลภาพ") { currentShot?.let { details(it) } }, dp(48), dp(48))
            inspect = viewer(); viewerKind = "shot"
            status = text("", 13f, Glass.TEXT2).apply { gravity = Gravity.CENTER; maxLines = 3; ellipsize = null; setPadding(dp(12), dp(6), dp(12), dp(6)) }
            root.addView(status, -1, -2)
            toggle = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(6), dp(2), dp(6), dp(10)) }
            root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; isFillViewport = true; addView(toggle) }, -1, -2)
            immersive(true); floatBars()
            if (!Glass.reduced(this)) { inspect.alpha = 0f; inspect.animate().alpha(1f).setDuration(260).start() }
        }
        if (showingId != s.id) { showingId = s.id; preloadNear() }
        // A photo with an AI result is one item: it opens on the AI picture, and the compare button shows the original underneath.
        useAi = s.ai != null && prefs.getBoolean("gallery_open_ai", true)
        inspect.show(if (useAi) s.ai!! else s.uri)
        status.text = when (s.stage) {
            Stage.CAPTURING -> "กำลังถ่าย ${s.frames}/${s.requestedFrames} เฟรม"
            Stage.QUEUED, Stage.RUNNING -> ""
            Stage.FAILED -> "AI ยังไม่สำเร็จ · ${s.error}"
            Stage.DONE -> ""
            Stage.ORIGINAL -> if (s.output.rawOnly) "ภาพตัวอย่าง RAW · DNG เก็บแยก" else ""
        }
        status.visibility = if (status.text.isEmpty()) View.GONE else View.VISIBLE
        // The AI works at full speed only while its progress is on screen.
        th.ogay.luma.ai.AiPace.watching = s.stage == Stage.RUNNING || s.stage == Stage.QUEUED
        when (s.stage) {
            Stage.RUNNING -> { ring.update(s.aiProgress.toFloat(), "เพิ่มรายละเอียด AI ×${s.aiScale.coerceAtLeast(1)}"); host.removeCallbacks(aiPoll); host.post(aiPoll) }
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
        if (s.ai != null && useAi) {
            // One compare button: hold to see the original over the AI picture, release to return. No reload, no flicker.
            inspect.setCompare(s.uri)
            val compare = action("M12 3v18 M4 6h5v12H4z M15 6h5v12h-5z", "ต้นฉบับ").apply { contentDescription = "กดค้างเพื่อดูต้นฉบับ"
                setOnTouchListener { v, e -> when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { Glass.tick(v); inspect.holdCompare(true); status.text = "ต้นฉบับ"; status.visibility = View.VISIBLE; v.isPressed = true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { inspect.holdCompare(false); status.text = ""; status.visibility = View.GONE; v.isPressed = false; if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick() }
                }; true }
                setOnClickListener { } }
            toggle.addView(compare, LinearLayout.LayoutParams(0, -2, 1f))
        }
        if (s.stage !in setOf(Stage.RUNNING,Stage.QUEUED,Stage.CAPTURING) && (s.raw == null || s.uri != s.raw))
            chip("แก้ไข", false, EditorKit.PEN) { editPhoto.launch(PhotoEditorActivity.intent(this, if (useAi && s.ai != null) s.ai!! else s.uri)) }
        if (s.stage !in setOf(Stage.RUNNING,Stage.QUEUED,Stage.CAPTURING)) {
            chip(if (s.ai != null) "ปรับ AI ใหม่" else "ปรับด้วย AI",false,Icons.AI) { EnhanceUi.show(this,s) }
        } else if (s.stage in setOf(Stage.RUNNING,Stage.QUEUED)) {
            chip("ยกเลิก AI",false) { luma.fileIo.execute { EnhanceWorker.cancel(applicationContext,s.id) } }
        }
        chip("แชร์", false, Icons.SHARE) { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, if (useAi) s.ai else s.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "แชร์")) }
        chip("ลบ", false, TRASH) { confirmDelete(listOf(Item(s.id, s.uri, s.time, false, "", "Lumora", s))) }
    }
    private fun infoPanel(preview: () -> Bitmap?, time: String, rows: List<Triple<String, String, String>>, tech: List<Pair<String, String>>) {
        GlassDialog.panel(this, time) { col, d ->
            // A small preview on top, then one row per fact: icon, value, quiet detail.
            val pic = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0x14ffffff); outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(18).toFloat()) } }; clipToOutline = true }
            col.addView(pic, LinearLayout.LayoutParams(-1, dp(150)).apply { topMargin = dp(4); bottomMargin = dp(10) })
            thumbs.execute { val b = runCatching { preview() }.getOrNull(); runOnUiThread { if (b != null) pic.setImageBitmap(b) } }
            rows.forEach { (icon, value, note) ->
                val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(7), 0, dp(7)) }
                r.addView(FrameLayout(this).apply { background = glass(14, 0x1affffff); addView(IconView(this@GalleryActivity, icon).apply { tint = Glass.ACCENT }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)) }, dp(36), dp(36))
                val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
                t.addView(text(value, 14.5f, Glass.TEXT, true).apply { maxLines = 2; ellipsize = null })
                if (note.isNotEmpty()) t.addView(text(note, 12f, Glass.TEXT3).apply { maxLines = 2; ellipsize = null })
                r.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
                col.addView(r, -1, -2)
            }
            if (tech.isNotEmpty()) {
                val more = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; background = glass(16, 0x10ffffff); setPadding(dp(10), dp(6), dp(10), dp(6)) }
                tech.forEach { (icon, line) ->
                    more.addView(LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(5), 0, dp(5))
                        addView(IconView(this@GalleryActivity, icon, 1.8f).apply { tint = Glass.TEXT2 }, dp(17), dp(17))
                        addView(text(line, 12f, Glass.TEXT2).apply { maxLines = 4; ellipsize = null; setPadding(dp(10), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f)) }, -1, -2)
                }
                val toggle = text("รายละเอียดทางเทคนิค ▾", 12.5f, Glass.TEXT2).apply { gravity = Gravity.CENTER_VERTICAL; minHeight = dp(44); isClickable = true
                    setOnClickListener { more.visibility = if (more.visibility == View.VISIBLE) View.GONE else View.VISIBLE; text = if (more.visibility == View.VISIBLE) "รายละเอียดทางเทคนิค ▴" else "รายละเอียดทางเทคนิค ▾" } }
                col.addView(toggle, -1, -2); col.addView(more, -1, -2)
            }
            col.addView(GlassDialog.actions(this, "ปิด" to { d.dismiss() }), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }.show()
    }
    private fun details(s: Shot) {
        val ai = s.aiSize
        luma.fileIo.execute {
            // Lens details come from the EXIF the camera screen wrote (focal length, 35 mm equivalent, f-number).
            val exif = runCatching { contentResolver.openInputStream(s.uri)?.use { androidx.exifinterface.media.ExifInterface(it) } }.getOrNull()
            val lens = exif?.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_LENS_MODEL)
            val eq = exif?.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)
            val fn = exif?.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_F_NUMBER)?.let { r -> r.split('/').let { p -> if (p.size == 2) (p[0].toDoubleOrNull() ?: 0.0) / (p[1].toDoubleOrNull() ?: 1.0) else r.toDoubleOrNull() } }
            val fnText = fn?.takeIf { it > 0 }?.let { String.format(Locale.US, "f/%.1f", it) }
            val report = s.aiReport?.let { r -> runCatching { JSONObject(r) }.getOrNull() }
            // Icon rows instead of a wall of text: the essentials first, technical details folded at the bottom.
            val rows = buildList<Triple<String, String, String>> {
                add(Triple(Icons.PHOTO, "${s.size.w} × ${s.size.h}", pixelsText(s.size.pixels)))
                add(Triple(Icons.FLIP, if (s.rear) "กล้องหลัง" else "กล้องหน้า", listOfNotNull(zoomText(s.zoom), eq?.let { "$it มม." }, fnText).joinToString(" · ")))
                add(Triple(Icons.FILE, s.output.label, if (s.softwareCrop) "ครอปในแอป" else "ซูมระบบ"))
                if (s.output.enhance) add(Triple(Icons.STACK, "${s.fusedFrames}/${s.requestedFrames} เฟรม", "รวมหลายเฟรม"))
                s.captureNote?.let { add(Triple(Icons.INFO, it, "")) }
                if (s.ai != null) add(Triple(Icons.AI, "AI ×${s.aiScale.coerceAtLeast(1)} · ${ai.w} × ${ai.h}", listOfNotNull(pixelsText(ai.pixels), report?.optString("backend")?.takeIf { it.isNotEmpty() }, "${s.elapsed / 1000.0} วิ".takeIf { s.elapsed > 0 }).joinToString(" · ")))
            }
            val tech = buildList<Pair<String, String>> {
                lens?.let { add(Icons.SEARCH to it) }
                add(Icons.FRAME to "กล้องตัวที่ ${s.lens ?: "-"} · ครอป ${s.cropped?.let(::pixelsText) ?: "ไม่มี"}")
                if (s.reason != Decision.OFF) add(Icons.INFO to s.reason.description)
                if (s.output.needsRaw) add(Icons.RAW to "DNG เก็บข้อมูลเซนเซอร์เต็มตามที่กล้องส่ง")
                report?.takeIf { it.has("totalMs") }?.let { j -> add(Icons.TIMER to "เวลา AI ${j.optLong("inferenceMs")} · อ่าน ${j.optLong("decodeMs")} · ผสาน ${j.optLong("renderMs")} · บันทึก ${j.optLong("encodeMs")} · รวม ${j.optLong("totalMs")} ms") }
                if (s.ai != null) add(Icons.AI to "รายละเอียดบางส่วนเป็นการคาดเดาของ AI")
            }
            runOnUiThread { if (!isDestroyed) infoPanel({ PhotoMedia.thumbnail(this, s.ai ?: s.uri, 540) }, formatTime(s.time), rows, tech) }
        }
    }
}

