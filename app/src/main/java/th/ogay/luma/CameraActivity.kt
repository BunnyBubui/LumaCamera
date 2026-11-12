package th.ogay.luma

import android.Manifest
import android.animation.ValueAnimator
import android.media.MediaActionSound
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageFormat
import android.graphics.Outline
import android.graphics.Paint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Size
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.extensions.ExtensionMode
import android.media.ToneGenerator
import android.media.AudioManager
import android.util.Rational
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

@ExperimentalCamera2Interop
class CameraActivity : ComponentActivity() {
    /** A mode in the customisable bottom bar: a PhotoMode name or a launcher for another screen. */
    class BarMode(val key: String, val title: String, val icon: String)
    companion object {
        val ALL = listOf(
            BarMode("NIGHT", "กลางคืน", Icons.NIGHT), BarMode("PORTRAIT", "บุคคล", Icons.PORTRAIT), BarMode("PHOTO", "ภาพถ่าย", Icons.PHOTO),
            BarMode("VIDEO", "วิดีโอ", Icons.VIDEO), BarMode("PRO", "โปร", Icons.PRO), BarMode("MOVIE", "วิดีโอโปร", Icons.MOVIE),
            BarMode("HDR", "HDR", Icons.HDR), BarMode("ULTRA_HDR", "Ultra HDR", Icons.ULTRA), BarMode("HIRES", "ความละเอียดสูง", Icons.HIRES),
            BarMode("DOCUMENT", "เอกสาร", Icons.DOC), BarMode("PANORAMA", "พาโนรามา", Icons.PANO), BarMode("TIMELAPSE", "Time-lapse", Icons.TIMELAPSE),
            BarMode("SLOW", "Slow-motion", Icons.SLOWMO), BarMode("DUAL", "Dual-view", Icons.DUAL), BarMode("RAWV", "RAW Video", Icons.RAWV))
        const val DEFAULT_BAR = "NIGHT,PORTRAIT,PHOTO,VIDEO,PRO"
        val LOOKS = listOf("มาตรฐาน", "สดใส", "อบอุ่น", "เย็น", "ฟิล์ม", "ขาวดำ")
        val NIGHT_FRAMES = listOf(16, 24, 32)
    }
    private val main by lazy { ContextCompat.getMainExecutor(this) }
    private val prefs by lazy { getSharedPreferences("camera", MODE_PRIVATE) }
    private var mode = PhotoMode.PHOTO
    private var extensions: ExtensionsManager? = null
    private var activeExtension = false
    private var manual: ManualControls? = null
    private var hdr: HdrCapture? = null
    private var timerRunning = false
    private var illuminating=false
    private var timerGeneration = 0
    private val panoFiles = mutableListOf<File>()
    private val tones = ToneGenerator(AudioManager.STREAM_SYSTEM, 40)
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var capture: ImageCapture? = null
    private var metadata: FrameMetadata? = null
    private var rear = true
    private var software = false
    private var ratio = 1f
    private var auto = true
    private var output = CaptureOutput.AI
    private var rawAvailable = false
    private var burst: BurstCapture? = null
    private var zoomAnimation: ValueAnimator? = null
    private val shutterSound = MediaActionSound()
    private lateinit var overlay: CompositionOverlay
    private var flash = ImageCapture.FLASH_MODE_OFF
    private var busy = false
    private var zoomSettling = false
    private var zoomTicket = 0
    private var binding = 0
    private var pendingBind = false
    private var targetRotation = Surface.ROTATION_0
    private var lastThumbUri: Uri? = null
    private lateinit var orientation: OrientationEventListener
    // Glass UI
    private lateinit var host: FrameLayout
    private lateinit var stage: FrameLayout
    private lateinit var preview: PreviewView
    private lateinit var mask: FrameLayout
    private lateinit var frozen: ImageView
    private lateinit var permissionPanel: LinearLayout
    private lateinit var viewfinderNotice: TextView
    private lateinit var statusRow: LinearLayout
    private lateinit var banner: TextView
    private lateinit var expRail: EdgeRail
    private lateinit var focusRail: EdgeRail
    private lateinit var lockButton: FrameLayout
    private lateinit var lockIcon: IconView
    private lateinit var wheel: ZoomWheel
    private lateinit var zooms: LinearLayout
    private lateinit var macroButton: FrameLayout
    private lateinit var nightButton: FrameLayout
    private lateinit var nightCard: LinearLayout
    private lateinit var nightCount: TextView
    private lateinit var miniCard: FrameLayout
    private lateinit var miniImage: ImageView
    private lateinit var miniBox: View
    private lateinit var miniLabel: TextView
    private lateinit var chips: CenterStrip
    private lateinit var chipFrame: FrameLayout
    private lateinit var dial: LinearLayout
    private lateinit var dialTitle: TextView
    private lateinit var dialValue: TextView
    private lateinit var dialReset: TextView
    private lateinit var slider: GlassSlider
    private lateinit var modeStrip: CenterStrip
    private lateinit var handle: IconView
    private lateinit var shutter: ShutterButton
    private lateinit var flip: FrameLayout
    private lateinit var flipIcon: IconView
    private lateinit var thumb: ImageView
    private lateinit var menu: GlassMenu
    private lateinit var panel: GlassSheet
    private lateinit var editor: GlassSheet
    private val modeLabels = mutableListOf<Pair<TextView, IconView>>()
    private var barKeys: List<String> = emptyList()
    private var ev = 0f
    private var aeLock = false
    private var pressLock = false
    private var macro = false
    private var nightOn = false
    private var nightRunning = false
    private var openChip: String? = null
    private var dialing = false
    private val lensShots = HashMap<Float, Bitmap>()
    private var renderedZooms: List<Float> = emptyList()
    private val permissions get() = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT < 29) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }.toTypedArray()
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (granted()) bind() else showPermission()
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        output = runCatching { CaptureOutput.valueOf(prefs.getString("output", CaptureOutput.AI.name)!!) }.getOrDefault(CaptureOutput.AI)
        mode = runCatching { PhotoMode.valueOf(prefs.getString("photo_mode", "PHOTO")!!) }.getOrDefault(PhotoMode.PHOTO)
        if(mode in listOf(PhotoMode.VIDEO, PhotoMode.MORE))mode=PhotoMode.PHOTO
        shutterSound.load(MediaActionSound.SHUTTER_CLICK)
        auto = prefs.getBoolean("auto", true); software = prefs.getBoolean("software", false)
        nightOn = prefs.getBoolean("night_toggle", false)
        rear = state?.getBoolean("rear",true) ?: true
        ratio = state?.getFloat("zoom",1f) ?: 1f
        buildScreen()
        @Suppress("DEPRECATION")
        targetRotation = windowManager.defaultDisplay.rotation
        orientation = object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                targetRotation = when (degrees) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                if (!busy) capture?.targetRotation = targetRotation
            }
        }
        luma.shots.changes.observe(this) { loadThumb() }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!isDestroyed) {
                try {
                    provider = future.get()
                    if (granted()) bind() else showPermission()
                    val ex=ExtensionsManager.getInstanceAsync(this,provider!!)
                    ex.addListener({runCatching{extensions=ex.get();if(!busy && granted())bind()}},main)
                }
                catch (e: Exception) { cameraError("เปิดระบบกล้องไม่ได้ · ${e.cause?.localizedMessage ?: e.localizedMessage}") }
            }
        }, main)
    }
    private fun granted() = permissions.all { ContextCompat.checkSelfPermission(this,it) == PackageManager.PERMISSION_GRANTED }
    override fun onResume() {
        super.onResume(); orientation.enable(); loadThumb()
        if (::modeStrip.isInitialized) syncModeStrip(false)
        if (granted() && provider != null && capture == null) bind()
        if (!granted()) showPermission()
    }
    override fun onPause() {
        orientation.disable()
        zoomAnimation?.cancel()
        burst?.cancel()
        hdr?.cancel()
        timerGeneration++;timerRunning=false
        menu.dismiss(false)
        super.onPause()
    }
    override fun onDestroy() {
        shutterSound.release()
        tones.release()
        super.onDestroy()
    }
    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putBoolean("rear",rear); out.putFloat("zoom",ratio) }
    override fun onConfigurationChanged(config: Configuration) {
        super.onConfigurationChanged(config)
        buildScreen(); loadThumb()
        if (busy) pendingBind = true else if (granted()) bind() else showPermission()
    }

    // ---------- Screen ----------
    private fun buildScreen() {
        host = FrameLayout(this)
        installRoot(host)
        menu = GlassMenu(host) { on -> pushBack(if (on) 0.33f else 0f) }
        panel = GlassSheet(host, { handlePivot() }) { pushBack(it) }
        editor = GlassSheet(host, { handlePivot() }) { pushBack(it) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        host.addView(column, -1, -1)
        stage = FrameLayout(this).apply { setBackgroundColor(BG) }
        column.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        mask = FrameLayout(this).apply {
            clipChildren = true; clipToPadding = true; clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, maskRadius) } }
        }
        stage.addView(mask, FrameLayout.LayoutParams(-1, -1, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        stage.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ -> layoutMask(right - left, bottom - top) }
        preview = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FIT_CENTER }
        mask.addView(preview, -1, -1)
        overlay = CompositionOverlay(this).apply { grid = prefs.getBoolean("grid", false) }
        mask.addView(overlay, -1, -1)
        overlay.monitor(preview)
        frozen = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        mask.addView(frozen, -1, -1)
        preview.previewStreamState.observe(this) { if (it == PreviewView.StreamState.STREAMING && frozen.alpha > 0f) frozen.animate().alpha(0f).setDuration(220).withEndAction { Glass.blur(frozen, 0f); frozen.setImageDrawable(null) }.start() }

        expRail = EdgeRail(this, false); stage.addView(expRail, FrameLayout.LayoutParams(dp(150), -1, Gravity.START))
        focusRail = EdgeRail(this, true); stage.addView(focusRail, FrameLayout.LayoutParams(dp(170), -1, Gravity.END))
        lockIcon = IconView(this, Icons.UNLOCK, 2f)
        lockButton = glassButton(lockIcon, 36, "ล็อกความสว่าง") { toggleAeLock() }.apply { alpha = 0f; visibility = View.GONE }
        stage.addView(lockButton, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(10); bottomMargin = dp(300) })

        statusRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END; contentDescription = "สิ่งที่เปิดอยู่"; isClickable = true; setOnClickListener { openPanel() } }
        stage.addView(statusRow, FrameLayout.LayoutParams(-2, dp(44), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(8), dp(12), 0) })
        banner = text("ล็อก AE/AF", 13f, Glass.ACCENT, true).apply { background = glass(16); setPadding(dp(14), dp(6), dp(14), dp(6)); visibility = View.GONE }
        stage.addView(banner, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(12) })
        viewfinderNotice = text("", 13f).apply { maxLines = 3; gravity = Gravity.CENTER; background = glass(18); setPadding(dp(16), dp(8), dp(16), dp(8)); visibility = View.GONE }
        viewfinderNotice.setOnClickListener { burst?.cancel() }
        stage.addView(viewfinderNotice, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(60) })
        permissionPanel = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(14)); background = glass(24, Glass.FILL_STRONG); visibility = View.GONE }
        stage.addView(permissionPanel, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        buildNightCard(); buildMiniMap()

        wheel = ZoomWheel(this).apply { alpha = 0f }
        stage.addView(wheel, FrameLayout.LayoutParams(-1, dp(150), Gravity.BOTTOM).apply { bottomMargin = dp(4) })
        zooms = LinearLayout(this).apply { gravity = Gravity.CENTER; background = glass(26); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        installZoomDrag(zooms)
        stage.addView(zooms, FrameLayout.LayoutParams(-2, dp(52), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(8) })
        macroButton = glassButton(IconView(this, Icons.FLOWER), 44, "มาโคร ถ่ายใกล้ได้ทุกระยะเลนส์") { toggleMacro() }
        stage.addView(macroButton, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(16), 0, 0, dp(12)) })
        nightButton = glassButton(IconView(this, Icons.NIGHT), 44, "ถ่ายกลางคืน กดค้างเพื่อเลือกเวลา") { toggleNight() }
        nightButton.setOnLongClickListener { chooseNightLength(nightButton); true }
        stage.addView(nightButton, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(16), dp(12)) })

        chipFrame = FrameLayout(this).apply { background = glass(24); visibility = View.GONE }
        chips = CenterStrip(this).apply { capsuleHeightDp = 44; onSelect = { i -> chipSelected(i) }; onLive = { i -> styleChips(i) } }
        chipFrame.addView(chips, FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(1), dp(1), dp(1), dp(1)) })
        stage.addView(chipFrame, FrameLayout.LayoutParams(-1, dp(52), Gravity.BOTTOM).apply { setMargins(dp(16), 0, dp(16), dp(66)) })
        buildDial()

        val dock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        column.addView(dock, -1, -2)
        handle = IconView(this, Icons.CHEVRON_UP, 2f).apply { tint = Glass.TEXT2; contentDescription = "ปัดขึ้นหรือแตะเพื่อดูตัวเลือกกล้อง"; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES; isClickable = true; setOnClickListener { openPanel() } }
        dock.addView(handle, LinearLayout.LayoutParams(dp(96), dp(26)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        nudge()
        modeStrip = CenterStrip(this).apply { onSelect = { i -> pickBar(i) }; onLive = { i -> styleModes(i) } }
        dock.addView(modeStrip, -1, dp(60))
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(36), 0, dp(36), 0) }
        dock.addView(actions, -1, dp(104))
        thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; background = glass(16); clipToOutline = true
            contentDescription = "เปิดแกลเลอรี"; setOnClickListener { startActivity(Intent(this@CameraActivity, GalleryActivity::class.java)) }
            setOnLongClickListener { startActivity(Intent(this@CameraActivity, StudioLibraryActivity::class.java)); true }
            setImageDrawable(null); glassTouch()
        }
        lastThumbUri = null
        actions.addView(thumb, dp(56), dp(56)); actions.space()
        shutter = ShutterButton(this).apply { setOnClickListener { takePhoto() }; glassPress() }
        shutter.setOnLongClickListener { if(!busy&&!timerRunning){if(output==CaptureOutput.JPEG&&mode==PhotoMode.PHOTO)takeSequence() else notice("เลือก JPEG ในโหมดภาพถ่ายเพื่อถ่ายต่อเนื่อง 10 ภาพ")};true }
        actions.addView(shutter, dp(88), dp(88)); actions.space()
        flipIcon = IconView(this, Icons.FLIP)
        flip = glassButton(flipIcon, 56, "สลับกล้องหน้าและหลัง") { if (!busy) { flipIcon.animate().rotationBy(180f).setInterpolator(Glass.EASE).setDuration(420).start(); freezeFrame(); rear = !rear; ratio = 1f; bind() } }
        actions.addView(flip, dp(56), dp(56))
        installDockSwipe(dock)
        installGestures()
        rebuildModeStrip()
        refresh()
    }
    private var maskRadius = 0f

    private fun layoutMask(w0: Int, h0: Int) {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val format = prefs.getString("aspect", "4:3")
        val aspect = when (format) { "1:1" -> 1f; "16:9" -> if (landscape) 16f / 9 else 9f / 16; else -> if (landscape) 4f / 3 else 3f / 4 }
        val w = minOf(w0, (h0 * aspect).roundToInt()); val h = (w / aspect).roundToInt()
        val lp = mask.layoutParams as FrameLayout.LayoutParams
        val top = if (format == "1:1") ((h0 - h) / 3).coerceAtLeast(0) else 0
        if (lp.width != w || lp.height != h || lp.topMargin != top) { lp.width = w; lp.height = h; lp.topMargin = top; mask.layoutParams = lp }
    }

    private fun glassButton(icon: IconView, sizeDp: Int, desc: String, click: () -> Unit) = FrameLayout(this).apply {
        background = glass(sizeDp / 2); contentDescription = desc; isClickable = true; isFocusable = true
        addView(icon, FrameLayout.LayoutParams(dp(sizeDp * 22 / 44), dp(sizeDp * 22 / 44), Gravity.CENTER))
        setOnClickListener { click() }; glassTouch()
    }
    private fun View.glassPress() = setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (!Glass.reduced(context)) v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setInterpolator(Glass.SPRING).setDuration(220).start()
        }
        false
    }
    private fun nudge() {
        if (Glass.reduced(this) || prefs.getBoolean("panel_hint_seen", false)) return
        var count = 0
        val bounce = object : Runnable { override fun run() {
            if (!handle.isAttachedToWindow || count++ >= 3) return
            handle.animate().translationY(-dp(4).toFloat()).setDuration(160).withEndAction { handle.animate().translationY(0f).setInterpolator(Glass.SPRING).setDuration(320).start() }.start()
            handle.postDelayed(this, 3200)
        } }
        handle.postDelayed(bounce, 1200)
    }
    private fun handlePivot(): Float {
        val loc = IntArray(2); val h = IntArray(2); handle.getLocationInWindow(loc); host.getLocationInWindow(h)
        return (host.height - (loc[1] - h[1] + handle.height / 2f) - dp(8)).coerceAtLeast(0f)
    }
    /** Shrinks, rounds and blurs the camera behind an open sheet or menu (0..1). */
    private fun pushBack(f: Float) {
        if (!::mask.isInitialized) return
        val reduced = Glass.reduced(this)
        val s = 1f - 0.06f * f
        mask.animate().scaleX(s).scaleY(s).setInterpolator(Glass.SPRING).setDuration(if (reduced) 0 else 560).start()
        maskRadius = dp(28) * f; mask.invalidateOutline()
        Glass.blur(stage, 12f * f)
    }
    private fun freezeFrame() {
        val b = runCatching { preview.bitmap }.getOrNull() ?: return
        frozen.setImageBitmap(b); frozen.alpha = 1f; Glass.blur(frozen, 16f)
        Glass.blurPulse(stage, 10f, 280)
    }

    private fun buildNightCard() {
        nightCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; background = glass(28); setPadding(dp(16), dp(16), dp(16), dp(14)); visibility = View.GONE }
        nightCount = text("", 30f, Glass.ACCENT, true).apply { gravity = Gravity.CENTER }
        nightCard.addView(IconView(this, "M12 4v16 M4 12h16 M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8z", 1.6f).apply { tint = Glass.ACCENT }, dp(56), dp(56))
        nightCard.addView(nightCount, -2, -2)
        nightCard.addView(text("ถือกล้องให้นิ่ง", 13f, Color.WHITE, true), -2, -2)
        nightCard.addView(text("กำลังรวมแสงกลางคืน · แตะชัตเตอร์เพื่อหยุด", 11.5f, Glass.TEXT2), -2, -2)
        stage.addView(nightCard, FrameLayout.LayoutParams(dp(230), -2, Gravity.CENTER))
    }
    private fun buildMiniMap() {
        miniCard = FrameLayout(this).apply { background = glass(18); setPadding(dp(4), dp(4), dp(4), dp(4)); visibility = View.GONE; contentDescription = "ภาพเต็มจากเลนส์ กรอบคือส่วนที่ซูมอยู่" }
        val inner = FrameLayout(this).apply { clipToOutline = true; outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(14).toFloat()) } } }
        miniImage = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(PANEL) }
        miniBox = View(this).apply { background = android.graphics.drawable.GradientDrawable().apply { setStroke(dp(2), Glass.ACCENT); cornerRadius = dp(3).toFloat() } }
        miniLabel = text("1×", 10.5f, Color.WHITE, true).apply { background = glass(9); setPadding(dp(7), dp(1), dp(7), dp(1)) }
        inner.addView(miniImage, -1, -1); inner.addView(miniBox, FrameLayout.LayoutParams(dp(40), dp(54), Gravity.CENTER))
        inner.addView(miniLabel, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(4), 0, 0, dp(4)) })
        miniCard.addView(inner, -1, -1)
        stage.addView(miniCard, FrameLayout.LayoutParams(dp(98), dp(130), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(56), dp(12), 0) })
    }
    private fun buildDial() {
        dial = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = glass(24); setPadding(dp(16), dp(12), dp(16), dp(12)); visibility = View.GONE }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        dialTitle = text("", 13f, Glass.TEXT2); dialValue = text("", 20f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        dialReset = text("AUTO", 12f, Color.WHITE, true).apply { gravity = Gravity.CENTER; background = glass(16, 0x1fffffff); setPadding(dp(12), 0, dp(12), 0); isClickable = true; setOnClickListener { resetChip() }; glassTouch() }
        head.addView(dialTitle, LinearLayout.LayoutParams(0, -2, 1f)); head.addView(dialValue, LinearLayout.LayoutParams(0, -2, 1f)); head.addView(dialReset, -2, dp(32))
        dial.addView(head, -1, dp(32))
        slider = GlassSlider(this).apply { onChange = { i -> sliderChanged(i) } }
        dial.addView(slider, -1, dp(56))
        stage.addView(dial, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(16), 0, dp(16), dp(130)) })
    }

    // ---------- Mode bar ----------
    private fun barList(): List<String> = (prefs.getString("mode_bar", DEFAULT_BAR) ?: DEFAULT_BAR).split(',').filter { k -> ALL.any { it.key == k } }.let { if ("PHOTO" in it) it else listOf("PHOTO") + it }
    private fun rebuildModeStrip() {
        val bar = barList(); barKeys = if (mode.name in bar) bar else bar + mode.name
        modeLabels.clear()
        val paint = Paint().apply { textSize = 12.5f * resources.displayMetrics.scaledDensity }
        val items = barKeys.map { k ->
            val m = ALL.first { it.key == k }
            val icon = IconView(this, m.icon).apply { tint = Glass.TEXT2 }
            val label = text(m.title, 12.5f, Glass.TEXT2).apply { gravity = Gravity.CENTER }
            val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; contentDescription = "โหมด${m.title}"; isClickable = true; isFocusable = true
                addView(icon, dp(20), dp(20)); addView(label, -2, -2) }
            modeLabels += label to icon
            val w = ((paint.measureText(m.title) / resources.displayMetrics.density) + 24).roundToInt().coerceAtLeast(56)
            CenterStrip.Item(k, w, cell)
        } + CenterStrip.Item("+", 52, LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; contentDescription = "ปรับแต่งแถบโหมด"; isClickable = true
            addView(IconView(this@CameraActivity, Icons.PLUS).apply { tint = Glass.TEXT2 }, dp(20), dp(20)); addView(text("ปรับ", 12.5f, Glass.TEXT2), -2, -2) })
        modeStrip.setItems(items, barKeys.indexOf(mode.name).coerceAtLeast(0))
    }
    private fun styleModes(live: Int) {
        modeLabels.forEachIndexed { i, (t, ic) -> val on = i == live; t.setTextColor(if (on) Glass.ACCENT else Glass.TEXT2); t.typeface = if (on) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT; ic.tint = if (on) Glass.ACCENT else Glass.TEXT2 }
    }
    private fun syncModeStrip(animate: Boolean) { val i = barKeys.indexOf(mode.name); if (i >= 0) modeStrip.select(i, animate) else rebuildModeStrip() }
    private fun pickBar(i: Int) {
        if (i >= barKeys.size) { syncModeStrip(true); openEditor(); return }
        val key = barKeys[i]
        when (key) {
            "VIDEO" -> openVideo("VIDEO"); "MOVIE" -> openVideo("MOVIE"); "DUAL" -> openVideo("DUAL")
            "TIMELAPSE" -> openSpecial("TIMELAPSE"); "SLOW" -> openSpecial("SLOW")
            "RAWV" -> { provider?.unbindAll(); capture = null; startActivity(Intent(this, RawLabActivity::class.java)) }
            else -> runCatching { PhotoMode.valueOf(key) }.getOrNull()?.let { selectMode(it) }
        }
    }
    private fun step(dir: Int) { val i = barKeys.indexOf(mode.name) + dir; if (i in barKeys.indices) { modeStrip.select(i, true); pickBar(i) } }

    // ---------- Status, rails, gestures ----------
    private fun statusIcon(d: String, inner: String = "", after: String = "", name: String) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(3), 0, dp(3), 0); contentDescription = name }
        row.addView(IconView(this, d).apply { tint = Glass.ACCENT; this.inner = inner; shadow = true }, dp(24), dp(24))
        if (after.isNotEmpty()) row.addView(text(after, 12f, Glass.ACCENT, true).apply { setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), 0x99000000.toInt()) }, -2, -2)
        statusRow.addView(row, -2, dp(44))
    }
    private fun refreshStatus() {
        statusRow.removeAllViews()
        if (flash != ImageCapture.FLASH_MODE_OFF) statusIcon(Icons.FLASH, after = if (flash == ImageCapture.FLASH_MODE_AUTO) "A" else "", name = "แฟลช${if (flash == ImageCapture.FLASH_MODE_AUTO) "อัตโนมัติ" else "เปิด"}")
        val timer = prefs.getString("timer", "0") ?: "0"; if (timer != "0") statusIcon(Icons.TIMER, after = "${timer}s", name = "ตั้งเวลา $timer วินาที")
        val aspect = prefs.getString("aspect", "4:3") ?: "4:3"; if (aspect != "4:3") statusIcon(Icons.FRAME, inner = aspect, name = "สัดส่วนภาพ $aspect")
        if (output != CaptureOutput.JPEG && mode in listOf(PhotoMode.PHOTO, PhotoMode.PRO)) statusIcon(Icons.FILE, after = output.label.replace(" + ", "+"), name = "รูปแบบไฟล์ ${output.label}")
        if (aeLock || pressLock) statusIcon(Icons.LOCK, after = "AE", name = "ล็อกความสว่าง")
        if (macro) statusIcon(Icons.FLOWER, name = "มาโคร เปิด")
        if (nightActive()) statusIcon(Icons.NIGHT, after = listOf("3s", "5s", "10s")[nightIndex()], name = "กลางคืน")
        if (auto && output == CaptureOutput.JPEG && mode == PhotoMode.PHOTO) statusIcon(Icons.AI, name = "AI อัตโนมัติ เปิด")
        statusRow.contentDescription = "สิ่งที่เปิดอยู่: " + (0 until statusRow.childCount).joinToString(", ") { statusRow.getChildAt(it).contentDescription }
    }
    private fun nightIndex() = NIGHT_FRAMES.indexOf(prefs.getInt("night_frames", 16)).coerceAtLeast(0)
    private fun nightActive() = mode == PhotoMode.NIGHT || (nightOn && mode == PhotoMode.PHOTO)

    private fun setEv(v: Float, fromGesture: Boolean) {
        ev = v.coerceIn(-2f, 2f); expRail.value = ev / 2; expRail.label = (if (ev > 0) "+" else if (ev < 0) "" else "±") + String.format(Locale.US, "%.1f", ev) + if (aeLock) " · ล็อก" else ""
        val cam = camera ?: return; val st = cam.cameraInfo.exposureState
        if (!st.isExposureCompensationSupported) { if (fromGesture) hint("กล้องนี้ไม่เปิดการชดเชยแสงให้แอป", 1500); return }
        val step = st.exposureCompensationStep.toFloat(); val r = st.exposureCompensationRange
        val idx = (ev / step).roundToInt().coerceIn(r.lower, r.upper)
        if (idx != st.exposureCompensationIndex) { cam.cameraControl.setExposureCompensationIndex(idx); if (idx == 0) Glass.tick(expRail) }
    }
    private fun showRail(r: EdgeRail) { r.show(true); r.removeCallbacks(hideRails); r.postDelayed(hideRails, 1600); if (r === expRail) showLockButton(true) }
    private val hideRails = Runnable { expRail.show(false); focusRail.show(false); showLockButton(aeLock) }
    private fun showLockButton(on: Boolean) {
        if (on) { lockButton.visibility = View.VISIBLE; lockButton.animate().alpha(1f).translationX(0f).setDuration(240).start() }
        else lockButton.animate().alpha(0f).translationX(-dp(30).toFloat()).setDuration(240).withEndAction { if (!aeLock) lockButton.visibility = View.GONE }.start()
    }
    private fun toggleAeLock() {
        aeLock = !aeLock; manual?.lock(aeLock); lockIcon.path = if (aeLock) Icons.LOCK else Icons.UNLOCK; lockIcon.tint = if (aeLock) Glass.ACCENT else Color.WHITE
        hint(if (aeLock) "ล็อกความสว่างแล้ว แตะโฟกัสจะไม่ล้างค่า" else "ปลดล็อกความสว่าง", 1500); setEv(ev, false); showRail(expRail); refresh()
    }
    private fun setFocusRail(f: Float?) {
        val m = manual
        if (m == null || m.minFocus <= 0f) { if (f != null) hint("กล้องนี้ไม่เปิดโฟกัสมือให้แอป", 1500); return }
        if (f == null) { m.setFocus(null); focusRail.value = 0f; focusRail.label = "AF"; return }
        focusRail.value = f.coerceIn(0f, 1f); m.setFocus(focusRail.value * m.minFocus)
        val diopters = focusRail.value * m.minFocus
        focusRail.label = "MF " + if (diopters < 0.05f) "∞" else (1f / diopters).let { if (it >= 1) String.format(Locale.US, "%.1f ม.", it) else "${(it * 100).roundToInt()} ซม." }
    }
    private fun installGestures() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean { applyZoom(ratio * detector.scaleFactor); refresh(); return true }
        })
        var axis = 0; var zone = 0; var ev0 = 0f; var fd0 = 0f; var sx = 0f; var sy = 0f
        val taps = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean { axis = 0; sx = e.x; sy = e.y; zone = if (e.x > preview.width * 0.7f) 2 else 1; ev0 = ev; fd0 = focusRail.value; return true }
            override fun onLongPress(e: MotionEvent) {
                val cam = camera ?: return
                val point = preview.meteringPointFactory.createPoint(e.x, e.y)
                cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).disableAutoCancel().build())
                manual?.lock(true); pressLock = true; overlay.focus(e.x, e.y, true)
                Glass.tick(preview, HapticFeedbackConstants.LONG_PRESS)
                banner.visibility = View.VISIBLE; Liquid.show(banner, banner.width / 2f, banner.height / 2f, false)
                axis = 1; zone = 1; sy = e.y; ev0 = ev; showRail(expRail); refresh()
            }
            override fun onScroll(first: MotionEvent?, last: MotionEvent, dx: Float, dy: Float): Boolean {
                if (first == null) return false
                val mx = last.x - first.x; val my = last.y - first.y
                if (axis == 0) { if (abs(my) > dp(10) && abs(my) > abs(mx)) { axis = 1; sy = last.y } else if (abs(mx) > dp(10)) axis = 2 }
                if (axis != 1) return false
                val move = last.y - sy
                if (zone == 2) { showRail(focusRail); setFocusRail((fd0 + move / dp(220)).coerceIn(0f, 1f)) }
                else { showRail(expRail); setEv(((ev0 - move / dp(90)) * 10).roundToInt() / 10f, true) }
                return true
            }
            override fun onFling(first: MotionEvent?, last: MotionEvent, vx: Float, vy: Float): Boolean {
                if (first != null && axis == 2 && abs(last.x - first.x) > dp(40) && !busy) { step(if (vx < 0) 1 else -1); return true }
                return false
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val cam = camera ?: return false
                if (pressLock) { pressLock = false; manual?.lock(aeLock); Liquid.hide(banner, banner.width / 2f, banner.height / 2f, false); hint("ปลดล็อก AE/AF", 1200) }
                else manual?.lock(aeLock)
                val point = preview.meteringPointFactory.createPoint(e.x, e.y)
                val op = FocusMeteringAction.Builder(point).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                if (cam.cameraInfo.isFocusMeteringSupported(op)) cam.cameraControl.startFocusAndMetering(op)
                overlay.focus(e.x, e.y)
                if (!aeLock && ev != 0f) { setEv(0f, false); hint("ล้างค่าความสว่างแล้ว", 1200) }
                if (focusRail.value > 0f) setFocusRail(null)
                showRail(expRail)
                if (prefs.getBoolean("focus_sound", false)) tones.startTone(ToneGenerator.TONE_PROP_BEEP, 50)
                if (prefs.getBoolean("tap_shutter", false)) takePhoto()
                preview.performClick(); refresh(); return true
            }
        })
        preview.setOnTouchListener { _, e ->
            scale.onTouchEvent(e); taps.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) { if (axis == 1) { expRail.removeCallbacks(hideRails); expRail.postDelayed(hideRails, 1600) } }
            true
        }
    }
    private fun installDockSwipe(dock: View) {
        var sy = 0f; var sx = 0f
        dock.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY }
                MotionEvent.ACTION_UP -> if (sy - e.rawY > dp(36) && abs(sy - e.rawY) > abs(e.rawX - sx)) openPanel()
            }
            true
        }
        handle.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) { sx = e.rawX; sy = e.rawY }; if (e.actionMasked == MotionEvent.ACTION_UP && sy - e.rawY > dp(24)) { openPanel(); true } else false }
    }

    // ---------- Zoom ----------
    private fun installZoomDrag(capsule: LinearLayout) {
        var sx = 0f; var z0 = 1f; var moved = false
        capsule.setOnTouchListener { _, e -> zoomTouch(e, { sx = it }, { sx }, { z0 = it }, { z0 }, { moved = it }, { moved }) }
    }
    private fun zoomTouch(e: MotionEvent, setSx: (Float) -> Unit, getSx: () -> Float, setZ0: (Float) -> Unit, getZ0: () -> Float, setMoved: (Boolean) -> Unit, getMoved: () -> Boolean): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { setSx(e.rawX); setZ0(ratio); setMoved(false); wheel.removeCallbacks(hideWheel); zoomAnimation?.cancel() }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - getSx()
                if (!getMoved() && abs(dx) < dp(6)) return false
                if (!getMoved()) { setMoved(true); dialing = true; zooms.animate().alpha(0f).setDuration(200).start(); wheel.show(true); zooms.parent.requestDisallowInterceptTouchEvent(true) }
                val (lo, hi) = range()
                var z = (getZ0() * 2f.pow(-dx / dp(95))).coerceIn(lo, hi)
                renderedZooms.firstOrNull { abs(ln(z / it) / ln(2f)) < 0.075f }?.let { lens -> if (abs(ratio - lens) > 0.001f) { Glass.tick(wheel); snapshotLens(lens) }; z = lens }
                applyZoom(z); wheel.zoom = ratio; refreshZoomUi(); return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (getMoved()) { wheel.postDelayed(hideWheel, 1300); return true }
        }
        return getMoved()
    }
    private val hideWheel = Runnable { dialing = false; wheel.show(false); zooms.animate().alpha(1f).setDuration(200).start(); refresh() }
    private fun snapshotLens(lens: Float) { runCatching { preview.bitmap }.getOrNull()?.let { lensShots[lens]?.recycle(); lensShots[lens] = it } }
    private fun refreshZoomUi() {
        val (lo, hi) = range()
        wheel.minZoom = lo; wheel.maxZoom = hi; wheel.lenses = renderedZooms; wheel.zoom = ratio
        val active = renderedZooms.indexOfLast { ratio >= it - 0.001f }.coerceAtLeast(0)
        for (i in renderedZooms.indices) {
            val pill = zooms.getChildAt(i) as? FrameLayout ?: continue; val t = pill.getChildAt(0) as TextView; val on = i == active
            t.text = if (on) zoomText(ratio) else zoomText(renderedZooms[i]).removeSuffix("×").replace("0.", ".")
            t.setTextColor(if (on) Glass.ACCENT else Color.WHITE)
            val size = dp(if (on) 40 else 32); val lp = t.layoutParams as FrameLayout.LayoutParams
            if (lp.width != size) { lp.width = size; lp.height = size; t.layoutParams = lp }
            (t.background as? GlassDrawable)?.fill = if (on) 0x8c000000.toInt() else 0x1affffff
        }
        val showMini = ratio > 10f && rear
        val lens = if (ratio >= 3.5f && renderedZooms.contains(3.5f)) 3.5f else 1f
        if (showMini && miniCard.visibility != View.VISIBLE) { miniCard.visibility = View.VISIBLE; Liquid.show(miniCard, miniCard.width.toFloat(), 0f, false) }
        if (!showMini && miniCard.visibility == View.VISIBLE) Liquid.hide(miniCard, miniCard.width.toFloat(), 0f, false)
        if (showMini) {
            miniLabel.text = zoomText(lens); lensShots[lens]?.let { miniImage.setImageBitmap(it) }
            val f = (lens / ratio).coerceIn(0.05f, 1f).pow(0.5f); val lp = miniBox.layoutParams
            lp.width = (dp(90) * f).roundToInt(); lp.height = (dp(120) * f).roundToInt(); miniBox.layoutParams = lp
        }
    }

    // ---------- Pro chips and dial ----------
    private fun chipKeys(): List<String> = if (mode != PhotoMode.PRO) emptyList() else buildList {
        val m = manual; if (m != null && m.canManual) { add("ISO"); add("S") }
        add("WB"); if ((m?.minFocus ?: 0f) > 0f) add("AF"); add("EV")
    }
    private val chipTexts = mutableListOf<Pair<TextView, TextView>>()
    private fun rebuildChips() {
        val keys = chipKeys(); chipTexts.clear()
        if (keys.isEmpty()) { chipFrame.visibility = View.GONE; closeDial(); return }
        chipFrame.visibility = View.VISIBLE
        val items = keys.map { k ->
            val t = text(chipTitle(k), 10.5f, Glass.TEXT2).apply { gravity = Gravity.CENTER }; val v = text(chipValue(k), 13f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
            chipTexts += t to v
            CenterStrip.Item(k, 72, LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true; contentDescription = chipTitle(k); addView(t, -2, -2); addView(v, -2, -2) })
        }
        chips.setItems(items, keys.indexOf(openChip ?: prefs.getString("pro_chip", "ISO")).coerceAtLeast(0))
    }
    private fun styleChips(live: Int) {
        val keys = chipKeys()
        chipTexts.forEachIndexed { i, (t, v) -> val on = i == live; t.setTextColor(if (on) Color.WHITE else Glass.TEXT3); v.text = chipValue(keys[i]); v.setTextColor(chipColor(keys[i])); (v.parent as? View)?.animate()?.scaleX(if (on) 1f else 0.92f)?.scaleY(if (on) 1f else 0.92f)?.setDuration(300)?.start() }
    }
    private fun chipTitle(k: String) = when (k) { "ISO" -> "ISO"; "S" -> "ชัตเตอร์"; "WB" -> "WB"; "AF" -> "โฟกัส"; else -> "EV" }
    private fun chipValues(k: String): List<String> {
        val m = manual
        return when (k) {
            "ISO" -> listOf("AUTO") + (m?.isoValues ?: emptyList()).map { it.toString() }
            "S" -> listOf("AUTO") + (m?.shutterValues ?: emptyList()).map { it.first }
            "WB" -> (m?.wbValues ?: listOf("AUTO" to 1)).map { it.first }
            "AF" -> listOf("AUTO", "∞", "ไกล", "กลาง", "ใกล้", "มาโคร")
            else -> evValues().map { (if (it > 0) "+" else if (it == 0f) "±" else "") + String.format(Locale.US, "%.1f", it) }
        }
    }
    private fun evValues(): List<Float> { val st = camera?.cameraInfo?.exposureState ?: return listOf(0f); if (!st.isExposureCompensationSupported) return listOf(0f); val step = st.exposureCompensationStep.toFloat(); val r = st.exposureCompensationRange; return (r.lower..r.upper).map { it * step }.filter { abs(it) <= 2.01f } }
    private fun chipIndex(k: String): Int {
        val m = manual
        return when (k) {
            "ISO" -> m?.currentIso?.let { (m.isoValues.indexOf(it) + 1).coerceAtLeast(0) } ?: 0
            "S" -> m?.currentShutter?.let { s -> (m.shutterValues.indexOfFirst { it.second == s } + 1).coerceAtLeast(0) } ?: 0
            "WB" -> (m?.wbValues?.indexOfFirst { it.second == m.currentWb } ?: 0).coerceAtLeast(0)
            "AF" -> m?.currentFocus?.let { f -> if (m.minFocus <= 0f) 0 else 1 + ((f / m.minFocus) * 4).roundToInt().coerceIn(0, 4) } ?: 0
            else -> evValues().let { list -> list.indexOfFirst { abs(it - (camera?.cameraInfo?.exposureState?.let { s -> s.exposureCompensationIndex * s.exposureCompensationStep.toFloat() } ?: 0f)) < 0.01f }.coerceAtLeast(0) }
        }
    }
    private fun chipValue(k: String) = chipValues(k).getOrElse(chipIndex(k)) { "AUTO" }
    private fun chipAuto(k: String) = if (k == "EV") evValues().indexOfFirst { abs(it) < 0.01f }.coerceAtLeast(0) else 0
    private fun chipSep(k: String) = k in listOf("ISO", "S", "AF")
    private fun chipGrad(k: String) = when (k) {
        "ISO" -> intArrayOf(0xff5ac8fa.toInt(), 0xff7be3b0.toInt(), Glass.ACCENT, 0xffff9f0a.toInt(), 0xffff453a.toInt())
        "S" -> intArrayOf(0xff64d2ff.toInt(), 0xffbf9bff.toInt(), Glass.ACCENT, 0xffff9f0a.toInt())
        "WB" -> intArrayOf(0xffff9a3d.toInt(), 0xffffd9a8.toInt(), Color.WHITE, 0xffcfe5ff.toInt(), 0xff7db8ff.toInt())
        "AF" -> intArrayOf(0xff8e8e93.toInt(), 0xffbbf0ff.toInt(), 0xff5ac8fa.toInt())
        else -> intArrayOf(0xff3a3a3c.toInt(), 0xff8e8e93.toInt(), Color.WHITE)
    }
    private fun chipColor(k: String): Int { val i = chipIndex(k); if (i == chipAuto(k)) return Color.WHITE; val n = chipValues(k).size; val lo = if (chipSep(k)) 1 else 0; return Glass.mix(chipGrad(k), (i - lo).toFloat() / (n - 1 - lo).coerceAtLeast(1)) }
    private fun chipSelected(i: Int) {
        val k = chipKeys().getOrNull(i) ?: return; prefs.edit().putString("pro_chip", k).apply()
        if (openChip == k) closeDial() else openDial(k)
    }
    private fun openDial(k: String) {
        val first = openChip == null; openChip = k
        dialTitle.text = chipTitle(k); val values = chipValues(k)
        slider.lo = if (chipSep(k)) 1 else 0; slider.values = values; slider.grad = chipGrad(k); slider.index = chipIndex(k)
        dialReset.text = if (k == "EV") "±0" else "AUTO"; updateDialValue(k)
        if (first || dial.visibility != View.VISIBLE) { dial.visibility = View.VISIBLE; Liquid.show(dial, dial.width / 2f, dial.height.toFloat(), false) }
        else Liquid.show(dial, dial.width / 2f, dial.height.toFloat(), false)
        zooms.animate().alpha(0f).setDuration(160).start()
    }
    private fun closeDial() { if (openChip == null) return; openChip = null; Liquid.hide(dial, dial.width / 2f, dial.height.toFloat(), false); zooms.animate().alpha(1f).setDuration(200).start() }
    private fun updateDialValue(k: String) { dialValue.text = chipValue(k); dialValue.setTextColor(chipColor(k)); val auto = chipIndex(k) == chipAuto(k); dialReset.setTextColor(if (auto) 0xff1a1400.toInt() else Color.WHITE); (dialReset.background as? GlassDrawable)?.let { it.fill = if (auto) Glass.ACCENT else 0x1fffffff; it.invalidateSelf() } }
    private fun sliderChanged(i: Int) { applyChip(openChip ?: return, i) }
    private fun applyChip(k: String, i: Int) {
        val m = manual ?: return
        when (k) {
            "ISO" -> m.setIso(if (i <= 0) null else m.isoValues.getOrNull(i - 1))
            "S" -> m.setShutter(if (i <= 0) null else m.shutterValues.getOrNull(i - 1)?.second)
            "WB" -> m.wbValues.getOrNull(i)?.let { m.setWb(it.second) }
            "AF" -> m.setFocus(if (i <= 0) null else m.minFocus * (i - 1) / 4f)
            else -> evValues().getOrNull(i)?.let { setEv(it, false) }
        }
        updateDialValue(k); styleChips(chips.selected)
    }
    private fun resetChip() { val k = openChip ?: return; slider.index = chipAuto(k); applyChip(k, chipAuto(k)) }

    // ---------- Macro and night ----------
    private fun toggleMacro() {
        if (!rear) { hint("มาโครใช้ได้กับกล้องหลัง", 1400); return }
        val m = manual; if (m == null || m.minFocus <= 0f) { hint("กล้องนี้ไม่เปิดโฟกัสใกล้ให้แอป", 1600); return }
        macro = !macro; m.setFocus(if (macro) m.minFocus else null)
        Glass.blurPulse(stage, 6f, 240); hint(if (macro) "มาโคร · โฟกัสใกล้สุดราว ${(100f / m.minFocus).roundToInt()} ซม. ที่ ${zoomText(ratio)}" else "ปิดมาโคร", 1600); refresh()
    }
    private fun toggleNight() {
        if (mode == PhotoMode.NIGHT) { hint("โหมดกลางคืนเปิดอยู่แล้ว · กดค้างเพื่อเลือกเวลา", 1500); return }
        nightOn = !nightOn; prefs.edit().putBoolean("night_toggle", nightOn).apply()
        hint(if (nightOn) "ถ่ายกลางคืน · กดชัตเตอร์แล้วถือนิ่ง" else "ปิดถ่ายกลางคืน", 1500); refresh()
    }
    private fun chooseNightLength(anchor: View) {
        menu.show(anchor, "เวลารวมแสงกลางคืน", listOf(GlassMenu.Option("อัตโนมัติ", Icons.NIGHT, "16 เฟรม ประมาณ 3 วินาที"), GlassMenu.Option("ยาว", Icons.NIGHT, "24 เฟรม ฉากมืดมาก"), GlassMenu.Option("ยาวที่สุด", Icons.NIGHT, "32 เฟรม ใช้ขาตั้ง")), nightIndex()) { i ->
            prefs.edit().putInt("night_frames", NIGHT_FRAMES[i]).apply(); if (mode != PhotoMode.NIGHT) { nightOn = true; prefs.edit().putBoolean("night_toggle", true).apply() }; refresh()
        }
    }
    private fun nightProgress(done: Int, total: Int) {
        if (!nightRunning) { nightRunning = true; nightCard.visibility = View.VISIBLE; Liquid.show(nightCard, nightCard.width / 2f, nightCard.height / 2f, false); macroButton.animate().alpha(0f).start(); nightButton.animate().alpha(0f).start(); zooms.animate().alpha(0f).start() }
        shutter.progress = done.toFloat() / total.coerceAtLeast(1)
        nightCount.text = "${((total - done) * 0.18f).roundToInt().coerceAtLeast(1)}"
    }
    private fun endNight() {
        if (!nightRunning) return; nightRunning = false; shutter.progress = -1f
        Liquid.hide(nightCard, nightCard.width / 2f, nightCard.height / 2f, false)
        macroButton.animate().alpha(1f).start(); nightButton.animate().alpha(1f).start(); zooms.animate().alpha(1f).start()
    }

    // ---------- Options panel ----------
    private fun openPanel() {
        if (panel.showing || busy) return
        prefs.edit().putBoolean("panel_hint_seen", true).apply(); closeDial()
        panel.open { box, sheet ->
            box.addView(sectionTitle("ถ่ายภาพ"))
            val grid = GridLayout(this).apply { columnCount = 3 }
            fun tile(title: String, icon: String, value: String, on: Boolean, inner: String = "", isMenu: Boolean, act: (View) -> Unit) {
                val t = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = glass(20, if (on) 0x1fffd447 else 0x12ffffff); isClickable = true
                    contentDescription = "$title $value"; setOnClickListener { act(it) }; glassTouch()
                    addView(IconView(this@CameraActivity, icon).apply { tint = if (on) Glass.ACCENT else Glass.TEXT2; this.inner = inner }, dp(24), dp(24))
                    addView(text(title, 12f).apply { gravity = Gravity.CENTER }, -2, -2)
                    addView(text(value + if (isMenu) " ▾" else "", 11.5f, if (on) Glass.ACCENT else Glass.TEXT2, true).apply { gravity = Gravity.CENTER }, -2, -2)
                }
                grid.addView(t, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0; height = dp(88); setMargins(dp(4), dp(4), dp(4), dp(4)) })
            }
            val flashNames = listOf("ปิด", "อัตโนมัติ", "เปิด"); val flashModes = listOf(ImageCapture.FLASH_MODE_OFF, ImageCapture.FLASH_MODE_AUTO, ImageCapture.FLASH_MODE_ON)
            fun reopen() { sheet.close { openPanel() } }
            tile("แฟลช", if (flash == ImageCapture.FLASH_MODE_OFF) Icons.FLASH_OFF else Icons.FLASH, flashNames[flashModes.indexOf(flash).coerceAtLeast(0)], flash != ImageCapture.FLASH_MODE_OFF, isMenu = true) { v ->
                menu.show(v, "แฟลช", listOf(GlassMenu.Option("ปิด", Icons.FLASH_OFF), GlassMenu.Option("อัตโนมัติ", Icons.FLASH, "เปิดเมื่อแสงน้อย"), GlassMenu.Option("เปิด", Icons.FLASH)), flashModes.indexOf(flash)) { i -> flash = flashModes[i]; capture?.flashMode = flash; refresh(); reopen() }
            }
            val timers = listOf("0", "3", "10"); val timer = prefs.getString("timer", "0") ?: "0"
            tile("ตั้งเวลา", Icons.TIMER, if (timer == "0") "ปิด" else "$timer วิ", timer != "0", isMenu = true) { v ->
                menu.show(v, "ตั้งเวลา", listOf(GlassMenu.Option("ปิด", Icons.TIMER), GlassMenu.Option("3 วินาที", Icons.TIMER), GlassMenu.Option("10 วินาที", Icons.TIMER)), timers.indexOf(timer)) { i -> prefs.edit().putString("timer", timers[i]).apply(); refresh(); reopen() }
            }
            val aspects = listOf("4:3", "1:1", "16:9"); val aspect = prefs.getString("aspect", "4:3") ?: "4:3"
            tile("สัดส่วนภาพ", Icons.FRAME, aspect, aspect != "4:3", aspect, true) { v ->
                menu.show(v, "สัดส่วนภาพ", listOf(GlassMenu.Option("4:3", Icons.FRAME, "เต็มเซนเซอร์ ละเอียดที่สุด", "4:3"), GlassMenu.Option("1:1", Icons.FRAME, "จัตุรัส", "1:1"), GlassMenu.Option("16:9", Icons.FRAME, "จอกว้าง ครอปบนล่าง", "16:9")), aspects.indexOf(aspect)) { i ->
                    if (!busy) { prefs.edit().putString("aspect", aspects[i]).apply(); layoutMask(stage.width, stage.height); freezeFrame(); bind() }; reopen()
                }
            }
            val outs = CaptureOutput.entries.filter { !it.needsRaw || rawAvailable }
            tile("รูปแบบไฟล์", Icons.FILE, output.label, output != CaptureOutput.JPEG, isMenu = true) { v ->
                menu.show(v, "รูปแบบไฟล์", outs.map { GlassMenu.Option(it.label, if (it.enhance) Icons.AI else Icons.FILE, when (it) { CaptureOutput.JPEG -> "ภาพที่กล้องประมวลผล"; CaptureOutput.RAW -> "DNG จากเซนเซอร์"; CaptureOutput.AI -> "รวมหลายเฟรม + SR1"; else -> "DNG พร้อมภาพ AI" }) }, outs.indexOf(output)) { i -> setOutput(outs[i]); reopen() }
            }
            tile("AI อัตโนมัติ", Icons.AI, if (auto) "เปิด" else "ปิด", auto, isMenu = false) { auto = !auto; prefs.edit().putBoolean("auto", auto).apply(); refresh(); reopen() }
            val grid0 = prefs.getBoolean("grid", false)
            tile("เส้นกริด", Icons.GRID, if (grid0) "เปิด" else "ปิด", grid0, isMenu = false) { prefs.edit().putBoolean("grid", !grid0).apply(); overlay.grid = !grid0; reopen() }
            box.addView(grid, -1, -2)

            val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(14), 0, dp(6)) }
            head.addView(text("ปรับแต่งภาพ", 14f, 0xdbffffff.toInt(), true), LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(text("คืนค่า", 12f).apply { gravity = Gravity.CENTER; background = glass(16, 0x1affffff); setPadding(dp(12), 0, dp(12), 0); isClickable = true; glassTouch()
                setOnClickListener { prefs.edit().putInt("look", 0).putInt("look_bright", 0).putInt("look_warm", 0).apply(); applyLook(); reopen() } }, -2, dp(32))
            box.addView(head)
            val looks = CenterStrip(this, 10).apply { capsuleHeightDp = 0; capsule.visibility = View.GONE; fadeDp = 44f }
            val frames = mutableListOf<View>()
            looks.setItems(LOOKS.mapIndexed { i, name ->
                val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; isClickable = true; contentDescription = "สไตล์$name" }
                val frame = FrameLayout(this).apply { background = glass(20, 0x14ffffff); setPadding(dp(3), dp(3), dp(3), dp(3)) }
                frame.addView(View(this).apply { background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xff34506a.toInt(), 0xff34506a.toInt(), 0xff1a2631.toInt())).apply { cornerRadius = dp(17).toFloat() }; lookFilter(this, i) }, -1, -1)
                frames += frame
                cell.addView(frame, dp(68), dp(68)); cell.addView(text(name, 12f, Glass.TEXT2).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }, -2, -2)
                CenterStrip.Item(name, 72, cell)
            }, prefs.getInt("look", 0))
            looks.onLive = { live -> frames.forEachIndexed { i, f -> (f.background as? GlassDrawable)?.let { it.fill = if (i == live) 0xebffffff.toInt() else 0x14ffffff; it.invalidateSelf() }; (f.parent as View).animate().scaleX(if (i == live) 1f else 0.9f).scaleY(if (i == live) 1f else 0.9f).setDuration(300).start() } }
            looks.onSelect = { i -> prefs.edit().putInt("look", i).apply(); applyLook() }
            box.addView(looks, -1, dp(108))
            box.addView(lookSlider("ความสว่าง", Icons.SUN, "look_bright"))
            box.addView(lookSlider("ความอุ่น", Icons.THERMO, "look_warm"))
            box.addView(text("โทนนี้แสดงบนภาพพรีวิว ยังไม่ได้ใส่ลงไฟล์ที่บันทึก", 11.5f, Glass.TEXT3).apply { setPadding(dp(2), dp(2), 0, dp(10)) })

            val links = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = glass(20) }
            fun link(title: String, icon: String, act: () -> Unit) {
                links.addView(LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(16), 0); isClickable = true; contentDescription = title; setOnClickListener { act() }
                    addView(IconView(this@CameraActivity, icon), dp(20), dp(20)); addView(text(title, 14f).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(IconView(this@CameraActivity, Icons.CHEVRON_RIGHT, 2f).apply { tint = 0x80ffffff.toInt() }, dp(16), dp(16)) }, -1, dp(52))
            }
            link("ปรับแต่งแถบโหมด", Icons.BAR) { sheet.close { openEditor() } }
            links.addView(View(this).apply { setBackgroundColor(0x1affffff) }, LinearLayout.LayoutParams(-1, 1).apply { leftMargin = dp(48) })
            link("ตั้งค่าทั้งหมด", Icons.GEAR) { sheet.close { showAllSettings() } }
            box.addView(links, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
    }
    private fun sectionTitle(t: String) = text(t, 14f, 0xdbffffff.toInt(), true).apply { setPadding(dp(2), dp(4), 0, dp(8)) }
    private fun lookSlider(title: String, icon: String, key: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val head = LinearLayout(this@CameraActivity).apply { gravity = Gravity.CENTER_VERTICAL }
        val value = text("", 13f, Glass.TEXT2)
        head.addView(IconView(this@CameraActivity, icon), dp(16), dp(16)); head.addView(text(title, 13f).apply { setPadding(dp(6), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f)); head.addView(value)
        addView(head, -1, dp(24))
        val values = (-100..100 step 10).map { (if (it > 0) "+" else "") + it }
        val s = GlassSlider(this@CameraActivity).apply { this.values = values; grad = if (key == "look_warm") intArrayOf(0xff7db8ff.toInt(), Color.WHITE, 0xffff9a3d.toInt()) else intArrayOf(0xff3a3a3c.toInt(), Color.WHITE); index = (prefs.getInt(key, 0) + 100) / 10 }
        value.text = values[s.index]
        s.onChange = { i -> value.text = values[i]; prefs.edit().putInt(key, i * 10 - 100).apply(); applyLook() }
        addView(s, -1, dp(48))
    }
    private fun lookMatrix(look: Int, bright: Int, warm: Int): ColorMatrix {
        val m = ColorMatrix()
        when (look) {
            1 -> m.setSaturation(1.35f)
            2 -> m.set(floatArrayOf(1.08f, 0f, 0f, 0f, 8f, 0f, 1.02f, 0f, 0f, 2f, 0f, 0f, 0.9f, 0f, -6f, 0f, 0f, 0f, 1f, 0f))
            3 -> m.set(floatArrayOf(0.94f, 0f, 0f, 0f, -4f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1.08f, 0f, 8f, 0f, 0f, 0f, 1f, 0f))
            4 -> { m.setSaturation(0.85f); m.postConcat(ColorMatrix(floatArrayOf(0.92f, 0f, 0f, 0f, 14f, 0f, 0.92f, 0f, 0f, 10f, 0f, 0f, 0.92f, 0f, 6f, 0f, 0f, 0f, 1f, 0f))) }
            5 -> m.setSaturation(0f)
        }
        val b = bright * 0.6f; val w = warm * 0.12f
        m.postConcat(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, b + w, 0f, 1f, 0f, 0f, b, 0f, 0f, 1f, 0f, b - w, 0f, 0f, 0f, 1f, 0f)))
        return m
    }
    private fun lookFilter(v: View, look: Int) { v.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = ColorMatrixColorFilter(lookMatrix(look, 0, 0)) }) }
    private fun applyLook() {
        val look = prefs.getInt("look", 0); val b = prefs.getInt("look_bright", 0); val w = prefs.getInt("look_warm", 0)
        val tv = (0 until preview.childCount).map { preview.getChildAt(it) }.firstOrNull { it is TextureView } as? TextureView ?: return
        tv.setLayerPaint(if (look == 0 && b == 0 && w == 0) null else Paint().apply { colorFilter = ColorMatrixColorFilter(lookMatrix(look, b, w)) })
    }
    private fun setOutput(choice: CaptureOutput) {
        if (busy) return
        if (choice.needsRaw && !rawAvailable) { notice("กล้องนี้ยังไม่เปิด RAW + JPEG ให้แอป"); return }
        if (choice == output) return
        if (choice.needsRaw || (mode == PhotoMode.ULTRA_HDR && choice != CaptureOutput.JPEG)) mode = PhotoMode.PHOTO
        output = choice
        prefs.edit().putString("output", choice.name).putString("photo_mode", mode.name).apply()
        freezeFrame(); bind(); syncModeStrip(true)
    }

    // ---------- Mode bar editor ----------
    private fun openEditor() {
        editor.open { box, sheet ->
            val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(text("ปรับแต่งแถบโหมด", 20f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(text("เสร็จ", 14f, 0xff1a1400.toInt(), true).apply { gravity = Gravity.CENTER; background = glass(22, Glass.ACCENT); setPadding(dp(16), 0, dp(16), 0); isClickable = true; glassTouch(); setOnClickListener { sheet.close() } }, -2, dp(44))
            box.addView(head, -1, dp(52))
            box.addView(text("ลากที่จับเพื่อเรียงใหม่ แตะ + หรือ − เพื่อเพิ่มหรือนำออก แตะชื่อโหมดเพิ่มเติมเพื่อใช้ทันที ภาพถ่ายต้องอยู่ในแถบเสมอ", 13f, Glass.TEXT2).apply { maxLines = 4; setPadding(0, dp(4), 0, dp(12)) })
            val inBar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = glass(22, 0x08ffffff); setPadding(dp(6), dp(6), dp(6), dp(6)) }
            val more = GridLayout(this).apply { columnCount = 2 }
            fun render() {
                inBar.removeAllViews(); more.removeAllViews()
                val bar = barList().toMutableList()
                bar.forEachIndexed { i, k ->
                    val m = ALL.first { it.key == k }
                    val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(6), 0); background = glass(18, 0x1affffff) }
                    val grip = IconView(this, "M9 6h.01 M15 6h.01 M9 12h.01 M15 12h.01 M9 18h.01 M15 18h.01", 3f).apply { tint = Glass.TEXT2; contentDescription = "ลากเพื่อเรียง${m.title}"; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES }
                    row.addView(grip, dp(28), dp(44)); row.addView(IconView(this, m.icon), dp(18), dp(18))
                    row.addView(text(m.title, 13.5f).apply { setPadding(dp(8), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
                    if (k == "PHOTO") row.addView(IconView(this, Icons.LOCK, 2f).apply { tint = Glass.TEXT3; contentDescription = "ล็อกไว้" }, dp(32), dp(16))
                    else row.addView(glassButton(IconView(this, Icons.MINUS, 2.4f), 32, "นำ${m.title}ออกจากแถบ") { saveBar(bar.filter { it != k }); render() }, dp(32), dp(32))
                    var startY = 0f
                    grip.setOnTouchListener { _, e ->
                        when (e.actionMasked) {
                            MotionEvent.ACTION_DOWN -> { startY = e.rawY; row.elevation = dp(8).toFloat(); row.animate().scaleX(1.03f).scaleY(1.03f).setDuration(120).start(); inBar.parent.requestDisallowInterceptTouchEvent(true) }
                            MotionEvent.ACTION_MOVE -> row.translationY = e.rawY - startY
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                val to = (i + (row.translationY / dp(50)).roundToInt()).coerceIn(0, bar.size - 1)
                                if (to != i) { bar.removeAt(i); bar.add(to, k); saveBar(bar); Glass.tick(row) }
                                render()
                            }
                        }
                        true
                    }
                    inBar.addView(row, LinearLayout.LayoutParams(-1, dp(44)).apply { setMargins(0, dp(3), 0, dp(3)) })
                }
                ALL.filter { it.key !in bar }.forEach { m ->
                    val cell = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; background = glass(18, 0x12ffffff); setPadding(dp(10), 0, dp(6), 0) }
                    val use = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; isClickable = true; contentDescription = "ใช้โหมด${m.title}ทันที"; glassTouch()
                        addView(IconView(this@CameraActivity, m.icon), dp(20), dp(20)); addView(text(m.title, 13.5f).apply { setPadding(dp(8), 0, 0, 0) })
                        setOnClickListener { sheet.close { useTemporary(m.key) } } }
                    cell.addView(use, LinearLayout.LayoutParams(0, dp(44), 1f))
                    cell.addView(glassButton(IconView(this, Icons.PLUS, 2.4f).apply { tint = Glass.ACCENT }, 36, "เพิ่ม${m.title}ลงแถบ") { saveBar(barList() + m.key); render() }, dp(36), dp(36))
                    more.addView(cell, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0; height = dp(52); setMargins(dp(4), dp(4), dp(4), dp(4)) })
                }
            }
            render()
            box.addView(sectionTitle("ในแถบล่าง")); box.addView(inBar, -1, -2)
            box.addView(sectionTitle("โหมดเพิ่มเติม").apply { setPadding(dp(2), dp(16), 0, dp(8)) }); box.addView(more, -1, -2)
        }
    }
    private fun saveBar(list: List<String>) { prefs.edit().putString("mode_bar", (if ("PHOTO" in list) list else listOf("PHOTO") + list).distinct().joinToString(",")).apply(); rebuildModeStrip() }
    private fun useTemporary(key: String) {
        val m = ALL.first { it.key == key }
        val pm = runCatching { PhotoMode.valueOf(key) }.getOrNull()
        if (pm != null) { selectMode(pm); hint("ใช้โหมด${m.title} · กด + เพื่อเพิ่มลงแถบถาวร", 1800) }
        else { barKeys = barList() + key; pickBar(barKeys.size - 1) }
    }

    // ---------- Refresh ----------
    private fun refreshZoomPills() {
        val (lo, hi) = range()
        val stops = (listOfNotNull(lo.takeIf { it < 0.98f }, 1f, 2f, 3.5f).filter { it in lo..hi }).distinct()
        if (stops != renderedZooms || zooms.childCount != stops.size) {
            zooms.removeAllViews(); renderedZooms = stops
            stops.forEach { v ->
                val cell = FrameLayout(this).apply { isClickable = true; isFocusable = true; contentDescription = "ซูม ${zoomText(v)} ลากซ้ายขวาเพื่อซูมละเอียด" }
                cell.addView(text(zoomText(v), 12f, Color.WHITE, true).apply { gravity = Gravity.CENTER; background = GlassDrawable(0x1affffff, dp(20).toFloat(), resources.displayMetrics.density) }, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
                cell.setOnClickListener { snapshotLens(v); animateZoom(v) }
                zooms.addView(cell, LinearLayout.LayoutParams(dp(44), dp(44)).apply { setMargins(dp(2), 0, dp(2), 0) })
            }
        }
        refreshZoomUi()
    }
    private fun refresh() {
        if (!::shutter.isInitialized) return
        shutter.isEnabled = capture != null && granted() && !busy && !zoomSettling && (!output.needsRaw || rawAvailable) || nightRunning
        shutter.alpha = if (shutter.isEnabled) 1f else 0.45f
        shutter.contentDescription = if (nightRunning) "หยุดรวมแสงและบันทึก" else "ถ่ายภาพ"
        flip.isEnabled = !busy
        refreshStatus()
        val showSide = !dialing && openChip == null && !nightRunning && capture != null
        macroButton.visibility = if (showSide && rear) View.VISIBLE else View.GONE
        (macroButton.getChildAt(0) as IconView).tint = if (macro) Glass.ACCENT else Color.WHITE
        nightButton.visibility = if (showSide) View.VISIBLE else View.GONE
        (nightButton.getChildAt(0) as IconView).tint = if (nightActive()) Glass.ACCENT else Color.WHITE
        refreshZoomPills()
        zooms.visibility = if (capture != null && renderedZooms.size > 1 && rear) View.VISIBLE else View.GONE
        preview.contentDescription = "ช่องมองภาพ กล้อง${if (rear) "หลัง" else "หน้า"} ${zoomText(ratio)} แตะเพื่อโฟกัส ปัดขึ้นลงเพื่อปรับความสว่าง"
        if (mode == PhotoMode.PRO && chipFrame.visibility != View.VISIBLE) rebuildChips()
        if (mode != PhotoMode.PRO && chipFrame.visibility == View.VISIBLE) { chipFrame.visibility = View.GONE; closeDial() }
        if (chipFrame.visibility == View.VISIBLE) styleChips(chips.selected)
        val dlp = dial.layoutParams as FrameLayout.LayoutParams; val want = if (chipFrame.visibility == View.VISIBLE) dp(130) else dp(66)
        if (dlp.bottomMargin != want) { dlp.bottomMargin = want; dial.layoutParams = dlp }
        if (mode == PhotoMode.PANORAMA) hint("พาโนรามา · ถ่ายแล้วแตะชัตเตอร์ค้าง… ต่อภาพ (${panoFiles.size})", 1500)
    }
    private var hintVersion=0
    private fun hint(text:String,ms:Long=1500) {
        val version=++hintVersion
        viewfinderNotice.accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
        val wasShown = viewfinderNotice.visibility == View.VISIBLE
        viewfinderNotice.text=text
        if (!wasShown) { viewfinderNotice.visibility = View.VISIBLE; viewfinderNotice.post { Liquid.show(viewfinderNotice, viewfinderNotice.width / 2f, viewfinderNotice.height / 2f, false) } }
        val delay=if(Build.VERSION.SDK_INT>=29)(getSystemService(ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager).getRecommendedTimeoutMillis(ms.toInt(),android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT).toLong()else ms
        viewfinderNotice.postDelayed({if(version==hintVersion) Liquid.hide(viewfinderNotice, viewfinderNotice.width / 2f, viewfinderNotice.height / 2f, false)},delay)
    }
    private fun showPermission() {
        permissionPanel.removeAllViews();permissionPanel.visibility=View.VISIBLE
        permissionPanel.addView(label("อนุญาตให้ใช้กล้องเพื่อเริ่มถ่ายภาพ",17f))
        val needSettings = prefs.getBoolean("asked",false) && permissions.any {
            ContextCompat.checkSelfPermission(this,it)!=PackageManager.PERMISSION_GRANTED && !shouldShowRequestPermissionRationale(it)
        }
        permissionPanel.addView(pill(if(needSettings) "เปิดการตั้งค่า" else "อนุญาต") {
            if(needSettings) startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            else { prefs.edit().putBoolean("asked",true).apply();permissionRequest.launch(permissions) }
        })
        refresh()
    }
    private fun cameraError(msg: String) {
        capture=null
        permissionPanel.removeAllViews();permissionPanel.visibility=View.VISIBLE
        permissionPanel.addView(label(msg,15f));permissionPanel.addView(pill("ลองเปิดกล้องอีกครั้ง",::bind));refresh()
    }

    // ---------- Camera binding (logic unchanged) ----------
    private fun bind() {
        val provider=provider ?: return
        if (!granted()) {showPermission();return}
        permissionPanel.visibility=View.GONE
        val ticket=++binding
        try {
            fun selector() = if(rear) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
            if(!provider.hasCamera(selector())) rear=!rear
            val baseSelector=selector()
            val extension=when(mode){PhotoMode.HDR->ExtensionMode.HDR;PhotoMode.NIGHT->ExtensionMode.NIGHT;PhotoMode.PORTRAIT->ExtensionMode.BOKEH;else->ExtensionMode.NONE}
            activeExtension=output==CaptureOutput.JPEG && extension!=ExtensionMode.NONE && extensions?.isExtensionAvailable(baseSelector,extension)==true
            val selected=if(activeExtension)extensions!!.getExtensionEnabledCameraSelector(baseSelector,extension) else baseSelector
            val info=provider.availableCameraInfos.firstOrNull { it in baseSelector.filter(provider.availableCameraInfos) }
                ?: throw IOException("ไม่พบกล้องที่เปิดใช้งานได้")
            rawAvailable = ImageCapture.getImageCaptureCapabilities(info).supportedOutputFormats.contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
            if(output.needsRaw&&!rawAvailable){output=CaptureOutput.JPEG;prefs.edit().putString("output",output.name).apply();notice("กล้องนี้ไม่รองรับ RAW · เปลี่ยนเป็น JPEG แล้ว")}
            val cc=Camera2CameraInfo.from(info)
            val caps=cc.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val single=Build.VERSION.SDK_INT>=28 && caps!=null && !caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
            val frames=FrameMetadata(single)
            val framing=ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            val previewCase=Preview.Builder().setResolutionSelector(framing.build()).build().apply { surfaceProvider=preview.surfaceProvider }
            val builder=ImageCapture.Builder().setCaptureMode(if(mode in listOf(PhotoMode.PRO,PhotoMode.HDR,PhotoMode.HIRES,PhotoMode.NIGHT))ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setJpegQuality(prefs.getString("jpeg_quality","95")!!.toInt())
                .setResolutionSelector(framing.setResolutionStrategy(ResolutionStrategy(Size(4000,3000),ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                .setTargetRotation(targetRotation)
            if(mode==PhotoMode.HIRES)builder.setResolutionSelector(ResolutionSelector.Builder().setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE).setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build())
            if(mode==PhotoMode.ULTRA_HDR){
                check(ImageCapture.getImageCaptureCapabilities(info).supportedOutputFormats.contains(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)){"กล้องนี้ไม่เปิดไฟล์ Ultra HDR ให้แอป"}
                builder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)
            }else if (output.needsRaw && rawAvailable) builder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
            Camera2Interop.Extender(builder).setSessionCaptureCallback(frames)
            val useCase=builder.build()
            camera?.cameraInfo?.zoomState?.removeObservers(this)
            provider.unbindAll()
            val group=UseCaseGroup.Builder().addUseCase(previewCase).addUseCase(useCase)
            val aspect=when(prefs.getString("aspect","4:3")){"1:1"->Rational(1,1);"16:9"->Rational(16,9);else->Rational(4,3)}
            if(aspect!=Rational(4,3)&&!activeExtension)group.setViewPort(ViewPort.Builder(aspect,targetRotation).build())
            camera=provider.bindToLifecycle(this,selected,group.build())
            manual=ManualControls(this,camera!!,false,mode==PhotoMode.PRO)
            macro=false;ev=0f;pressLock=false;focusRail.value=0f;focusRail.label="AF";banner.visibility=View.GONE
            if(aeLock)manual?.lock(true)
            if(mode==PhotoMode.PRO)rebuildChips()
            preview.post{applyLook()}
            if(mode==PhotoMode.NIGHT&&!activeExtension&&output==CaptureOutput.JPEG){output=CaptureOutput.AI}

            capture=useCase;metadata=frames
            useCase.flashMode=if(info.hasFlashUnit()) flash else ImageCapture.FLASH_MODE_OFF
            val range=camera?.cameraInfo?.zoomState?.value
            ratio=if(software && rear) ratio.coerceIn(1f,8f) else ratio.coerceIn(range?.minZoomRatio ?: 1f,range?.maxZoomRatio ?: 1f)
            applyZoom(ratio)
            val other=if(rear) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            flip.visibility=if(provider.hasCamera(other)) View.VISIBLE else View.INVISIBLE
            camera?.cameraInfo?.zoomState?.observe(this) { if(ticket==binding) refresh() }
            refresh()
        } catch(e:Exception) {cameraError(e.localizedMessage ?: "เปิดกล้องไม่ได้")}
    }
    private fun range(): Pair<Float,Float> {
        val z=camera?.cameraInfo?.zoomState?.value
        return if(software && rear) 1f to 8f else (z?.minZoomRatio ?: 1f) to (z?.maxZoomRatio ?: 1f)
    }
    private fun applyZoom(requested: Float) {
        val cam=camera ?: return
        if(busy) return
        val (lo,hi)=range()
        ratio=requested.coerceIn(lo,hi)
        val crop=software && rear
        preview.scaleX=if(crop) ratio else 1f; preview.scaleY=preview.scaleX
        val ticket=++zoomTicket
        zoomSettling=true
        val future=cam.cameraControl.setZoomRatio(if(crop) 1f else ratio)
        future.addListener({
            if(ticket==zoomTicket && cam===camera && !isDestroyed) {
                zoomSettling=false
                try { future.get() } catch (_:Exception) {
                    if(!crop) ratio=cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                }
                refresh()
            }
        },main)
        refresh()
    }
    private fun animateZoom(target: Float) {
        zoomAnimation?.cancel()
        if (prefs.getBoolean("reduce_motion", false)) {
            applyZoom(target)
            return
        }
        val start = kotlin.math.ln(ratio.toDouble()).toFloat()
        val end = kotlin.math.ln(target.toDouble()).toFloat()
        zoomAnimation = ValueAnimator.ofFloat(start, end).apply {
            duration = when(prefs.getString("zoom_speed","normal")){"slow"->600;"fast"->140;else->280}
            addUpdateListener { applyZoom(kotlin.math.exp((it.animatedValue as Float).toDouble()).toFloat()) }
            start()
        }
    }

    // ---------- Capture (logic unchanged except night and shutter feedback) ----------
    private fun captureFeedback() {
        if (isDestroyed) return
        if (prefs.getBoolean("sound", true) || CameraInfo.mustPlayShutterSound()) {
            shutterSound.play(MediaActionSound.SHUTTER_CLICK)
        }
    }

    private fun takeBurst() {
        val useCase = capture ?: return
        val night = nightActive()
        val kind = if (night && output == CaptureOutput.JPEG) CaptureOutput.AI else output
        if (output.needsRaw && !rawAvailable) {
            notice("กล้องนี้ไม่เปิด RAW + JPEG ให้แอป")
            return
        }
        busy = true
        hintVersion++
        refresh()
        burst = BurstCapture(
            applicationContext, useCase, kind,
            if (kind.enhance) burstCount(if (night) prefs.getInt("night_frames", 16) else prefs.getInt("frames", 8)) else 1,
            rear, ratio, software && rear, targetRotation,
            started = { captureFeedback() },
            progress = { done, total ->
                if (!isDestroyed && night) nightProgress(done, total)
                else if (!isDestroyed) {
                    viewfinderNotice.accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_NONE
                    viewfinderNotice.text = "ถ่าย $done/$total เฟรม · แตะเพื่อหยุด"
                    viewfinderNotice.visibility = View.VISIBLE
                }
            },
            finished = { message ->
                manual?.apply()
                burst = null
                complete(message)
            }
        )
        val active = camera
        if (active == null) {
            burst?.start()
            return
        }
        val info = Camera2CameraInfo.from(active.cameraInfo)
        val options = CaptureRequestOptions.Builder()
        if (info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
        }
        if (info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
        }
        val locked = Camera2CameraControl.from(active.cameraControl).setCaptureRequestOptions(options.build())
        val pendingBurst=burst
        val launched=java.util.concurrent.atomic.AtomicBoolean()
        fun launchBurst(){if(burst===pendingBurst && launched.compareAndSet(false,true))pendingBurst?.start()}
        locked.addListener({runCatching{locked.get()};launchBurst()},main)
        // Some camera HALs never acknowledge a 3A lock after a session switch.
        shutter.postDelayed({launchBurst()},1500)
    }

    private fun takePhoto() {
        if(nightRunning){burst?.cancel();hint("กำลังบันทึกเฟรมที่ได้แล้ว",1500);return}
        if(busy||timerRunning||illuminating||zoomSettling)return
        shutter.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).withEndAction{shutter.animate().scaleX(1f).scaleY(1f).setInterpolator(Glass.SPRING).setDuration(260).start()}.start()
        val seconds=prefs.getString("timer","0")?.toIntOrNull()?:0
        if(seconds<=0){captureWithLight();return}
        timerRunning=true;val token=++timerGeneration
        fun tick(left:Int){
            if(token!=timerGeneration)return
            if(left==0){timerRunning=false;captureWithLight();return}
            hint("$left",1100);if(prefs.getBoolean("countdown_sound",true))tones.startTone(ToneGenerator.TONE_PROP_BEEP,80)
            shutter.postDelayed({tick(left-1)},1000)
        };tick(seconds)
    }
    private fun captureWithLight(){
        if(!rear&&prefs.getBoolean("screen_flash",false)){
            illuminating=true;val token=timerGeneration;mask.foreground=android.graphics.drawable.ColorDrawable(android.graphics.Color.WHITE);window.attributes=window.attributes.apply{screenBrightness=1f}
            shutter.postDelayed({illuminating=false;if(token==timerGeneration)captureNow();shutter.postDelayed({mask.foreground=null;window.attributes=window.attributes.apply{screenBrightness=-1f}},400)},300)
        }else captureNow()
    }
    private fun captureNow() {
        if(filesDir.usableSpace<32L*1024*1024){hint("พื้นที่ว่างไม่พอสำหรับถ่ายภาพ กรุณาคืนพื้นที่แล้วลองใหม่",5000);return}
        val useCase=capture ?: return
        if(mode==PhotoMode.HDR&&!activeExtension){takeHdr(useCase);return}
        if(mode==PhotoMode.ULTRA_HDR){takeUltra(useCase);return}
        if(busy || zoomSettling) return
        if (output != CaptureOutput.JPEG || (nightOn && mode == PhotoMode.PHOTO)) {
            takeBurst()
            return
        }
        busy=true;refresh()
        val isRear=rear;val useSoftware=software && isRear;val zoom=ratio;val allowAuto=auto && mode==PhotoMode.PHOTO;val frames=metadata;val photoMode=mode;val nativeEffect=activeExtension
        val time=System.currentTimeMillis();val id=UUID.randomUUID().toString()
        useCase.targetRotation=targetRotation
        useCase.takePicture(luma.fileIo,object:ImageCapture.OnImageCapturedCallback(){
            override fun onCaptureStarted() { runOnUiThread { captureFeedback() } }
            override fun onCaptureSuccess(image:ImageProxy) {
                val full=File(cacheDir,"capture-$id.jpg")
                val cut=File(cacheDir,"crop-$id.jpg")
                try {
                    val ts=image.imageInfo.timestamp; val rot=image.imageInfo.rotationDegrees
                    try {
                        if(image.format!=ImageFormat.JPEG) throw IOException("กล้องส่งภาพในรูปแบบที่ยังไม่รองรับ")
                        full.outputStream().channel.use { out ->
                            val data=image.planes[0].buffer.duplicate()
                            while(data.hasRemaining()) out.write(data)
                        }
                    } finally { image.close() }
                    val frame=frames?.forTimestamp(ts)
                    val original=if(useSoftware && zoom>1f) { PhotoMedia.crop(full,zoom,cut);cut } else full
                    val date=SimpleDateFormat("yyyy:MM:dd HH:mm:ss",Locale.US).format(Date(time))
                    PhotoMedia.tag(original,rot,date)
                    if(photoMode==PhotoMode.PORTRAIT&&!nativeEffect){
                        val reference=PhotoMedia.publish(this@CameraActivity,original,"LUMA_PORTRAIT_ORIGINAL_${time}.jpg",time)
                        MediaLedger.add(this@CameraActivity,reference,"Portrait ต้นฉบับ","image/jpeg")
                        PhotoEffects.portrait(original)
                    }
                    if(photoMode!=PhotoMode.PANORAMA)PhotoEffects.decorate(this@CameraActivity,original,isRear)
                    val size=PhotoMedia.dimensions(original)
                    val crop=if(useSoftware && zoom>1f) size.pixels else frame?.crop
                    val actualZoom=if(!useSoftware && frame?.ratio!=null && frame.ratio!=1f) frame.ratio else zoom
                    val reason=decide(allowAuto,isRear,actualZoom,size.pixels,crop)
                    val uri=PhotoMedia.publish(this@CameraActivity,original,"LUMA_${time}_${id}.jpg",time)
                    val stage=if(reason.startsAi) Stage.QUEUED else Stage.ORIGINAL
                    luma.shots.insert(Shot(id,time,uri,size,actualZoom,isRear,frame?.lens,crop,reason,stage,null,0,null,useSoftware))
                    if(photoMode==PhotoMode.PANORAMA){
                        val saved=File(filesDir,"panorama/${UUID.randomUUID()}.jpg");saved.parentFile?.mkdirs();original.copyTo(saved,true);runOnUiThread{panoFiles.add(saved);refresh()}
                    }
                    if(reason.startsAi) EnhanceWorker.enqueue(applicationContext,id)
                    if(photoMode==PhotoMode.DOCUMENT)runOnUiThread{startActivity(Intent(this@CameraActivity,DocumentActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}
                    complete(if(reason.startsAi) "บันทึกต้นฉบับแล้ว · เข้าคิว AI" else "บันทึกรูปแล้ว")
                } catch(_:OutOfMemoryError) {complete("หน่วยความจำไม่พอ ลองใช้โหมดซูมด้วยระบบกล้อง")}
                catch(e:Exception) {complete("บันทึกรูปไม่สำเร็จ · ${e.localizedMessage}")}
                finally {full.delete();cut.delete()}
            }
            override fun onError(exception:ImageCaptureException) {complete("ถ่ายภาพไม่สำเร็จ · ${exception.localizedMessage}")}
        })
    }
    private fun complete(msg:String)=runOnUiThread {
        busy=false
        if(!isDestroyed) {
            endNight()
            if(pendingBind) {pendingBind=false;bind()}
            refresh();hint(msg,5000)
        }
    }
    private fun selectMode(next:PhotoMode){
        if(busy||timerRunning)return
        if(next==PhotoMode.MORE){openEditor();return}
        if(next==PhotoMode.VIDEO){openVideo("VIDEO");return}
        if(panoFiles.isNotEmpty()&&next!=PhotoMode.PANORAMA){notice("ต่อภาพพาโนรามาหรือกลับมาถ่ายต่อได้ในโหมดพาโนรามา")}
        mode=next
        if(next!=PhotoMode.PHOTO&&next!=PhotoMode.PRO){output=CaptureOutput.JPEG;prefs.edit().putString("output",output.name).apply()}
        prefs.edit().putString("photo_mode",next.name).apply()
        if(next!=PhotoMode.PRO){chipFrame.visibility=View.GONE;closeDial()}
        if(next.name !in barKeys) rebuildModeStrip() else syncModeStrip(true)
        Glass.blurPulse(stage,8f,260);freezeFrame();bind()
    }
    private fun openVideo(kind:String){provider?.unbindAll();capture=null;startActivity(Intent(this,VideoActivity::class.java).putExtra("mode",kind))}
    private fun openSpecial(kind:String){provider?.unbindAll();capture=null;startActivity(Intent(this,SpecialVideoActivity::class.java).putExtra("mode",kind))}
    private fun showAllSettings(){showStudioSettings(rawAvailable){auto=prefs.getBoolean("auto",true);overlay.grid=prefs.getBoolean("grid",false)
        if(prefs.getBoolean("gps",false)&&ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED)locationRequest.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION))
        refresh();if(!busy){layoutMask(stage.width,stage.height);bind()}
    }}
    private val locationRequest=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){if(it.values.none{v->v}){prefs.edit().putBoolean("gps",false).apply();notice("ปิด GPS เพราะยังไม่ได้อนุญาตตำแหน่ง")}}
    private fun takeHdr(useCase:ImageCapture){val cam=camera?:return;busy=true;hintVersion++;refresh();captureFeedback()
        hdr=HdrCapture(this,cam,useCase,{frame->hint("HDR · $frame/3 เฟรม",32000)},{file,error->val used=hdr?.usedFrames?:1;val requested=hdr?.requestedFrames?:3;val label=hdr?.label?:"HDR EF1 · Exposure fusion SDR";hdr=null;if(file!=null)luma.fileIo.execute{runCatching{PhotoEffects.decorate(this,file,rear);publishSpecial(file,"$label · ใช้ $used/$requested เฟรม",used,requested)}.onSuccess{complete("บันทึก HDR แล้ว")}.onFailure{complete(it.localizedMessage?:"HDR ไม่สำเร็จ")}}else complete(error?:"HDR ไม่สำเร็จ")});hdr!!.start()
    }
    private fun takeUltra(useCase:ImageCapture){busy=true;refresh();val file=File(cacheDir,"ultra-${System.currentTimeMillis()}.jpg");useCase.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),luma.fileIo,object:ImageCapture.OnImageSavedCallback{
        override fun onCaptureStarted(){runOnUiThread{captureFeedback()}}
        override fun onImageSaved(result:ImageCapture.OutputFileResults){try{publishSpecial(file,"Ultra HDR · gain map จากกล้อง");complete("บันทึก Ultra HDR แล้ว")}catch(e:Exception){complete(e.localizedMessage?:"บันทึกไม่ได้")}finally{file.delete()}}
        override fun onError(e:ImageCaptureException){complete(e.localizedMessage?:"ถ่ายไม่ได้");file.delete()}
    })}
    private fun publishSpecial(file:File,note:String,used:Int=1,requested:Int=used){val id=UUID.randomUUID().toString();val time=System.currentTimeMillis();val uri=PhotoMedia.publish(this,file,"LUMA_${time}_${id}.jpg",time);luma.shots.insert(Shot(id,time,uri,PhotoMedia.dimensions(file),ratio,rear,null,null,Decision.OFF,Stage.ORIGINAL,null,0,null,false,frames=used,requestedFrames=requested,fusedFrames=used,captureNote=note))}
    private fun takeSequence(){val useCase=capture?:return;busy=true;refresh();SequentialPhotos(this,useCase,rear,ratio,{n->hint("ถ่ายต่อเนื่อง $n/10",3000)},{message->complete(message)},started={captureFeedback()}).start()}
    private fun finishPanorama(){if(busy)return;if(panoFiles.size<2){notice("ถ่ายอย่างน้อย 2 ภาพ โดยแต่ละภาพซ้อนทับกันประมาณครึ่งหนึ่ง");return};busy=true;refresh();hint("กำลังจับคู่และต่อภาพ…",120000)
        val input=panoFiles.toList();luma.fileIo.execute{runCatching{val result=PanoramaEngine.stitch(this,input);publishSpecial(result,"Panorama · ORB / homography");result.delete()}.onSuccess{input.forEach{it.delete()};runOnUiThread{panoFiles.clear();complete("บันทึกพาโนรามาแล้ว")}}.onFailure{complete("ต่อภาพไม่ได้ · ${it.localizedMessage} · เฟรมต้นฉบับยังอยู่")}}
    }
    override fun onKeyDown(code:Int,event:KeyEvent):Boolean{if(code==KeyEvent.KEYCODE_VOLUME_UP||code==KeyEvent.KEYCODE_VOLUME_DOWN){when(prefs.getString("volume_action","shutter")){"system"->return super.onKeyDown(code,event);"zoom"->{if(!busy)applyZoom(ratio*if(code==KeyEvent.KEYCODE_VOLUME_UP)1.1f else 0.9f)};else->if(event.repeatCount==0)takePhoto()};return true};return super.onKeyDown(code,event)}
    private fun loadThumb() {
        luma.fileIo.execute {
            val uri=luma.shots.latest()?.uri ?: return@execute
            if(uri==lastThumbUri) return@execute
            val bitmap=PhotoMedia.thumbnail(this,uri,160)
            runOnUiThread { if(!isDestroyed && bitmap!=null) {lastThumbUri=uri;thumb.setImageBitmap(bitmap);thumb.visibility=View.VISIBLE} }
        }
    }
}

