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
        /** Rear camera zoom limit: hardware zoom, then the app's crop enlarged by AI SR1 when the photo is saved. */
        const val SUPER_ZOOM = 500f
        /** Front camera: the system stops at 10×; the app crops up to here. */
        const val FRONT_ZOOM = 20f
        /** Fast-shot types: shutter time in nanoseconds and the ISO that keeps the picture usable. */
        val SPORT_KINDS = listOf(
            Triple("เดิน · เด็กเล่น", 1_000_000_000L / 500, 400), Triple("วิ่ง · กีฬาทั่วไป", 1_000_000_000L / 1000, 800),
            Triple("รถ · มอเตอร์สปอร์ต", 1_000_000_000L / 2000, 1600), Triple("นกบิน · ลูกบอลเร็ว", 1_000_000_000L / 4000, 3200))
        val F_STEPS = listOf(1.2f, 1.4f, 1.6f, 1.8f, 2f, 2.2f, 2.5f, 2.8f, 3.2f, 3.5f, 4f, 4.5f, 5f, 5.6f, 6.3f, 7.1f, 8f, 9f, 10f, 11f, 13f, 14f, 16f)
        fun blurWord(f: Float) = when { f <= 1.4f -> "เบลอมากที่สุด"; f <= 2.8f -> "เบลอชัด"; f <= 5.6f -> "เบลอพอดี"; else -> "เบลอน้อย" }
        val APERTURES = listOf(1.2f, 1.4f, 1.8f, 2f, 2.8f, 4f, 5.6f, 8f, 11f, 16f)
        val ALL = listOf(
            BarMode("PORTRAIT", "บุคคล", Icons.PORTRAIT), BarMode("PHOTO", "ภาพถ่าย", Icons.PHOTO),
            BarMode("VIDEO", "วิดีโอ", Icons.VIDEO), BarMode("PRO", "โปร", Icons.PRO),
            BarMode("HDR", "HDR", Icons.HDR), BarMode("HIRES", "ความละเอียดสูง", Icons.HIRES),
            BarMode("QUICK", "ถ่ายเร็ว", Icons.FLASH), BarMode("FILM", "ฟิล์ม", Icons.FILM))
        /** Flash-fast, flash-quick and sport share one bar entry; a chip picks which. */
        val QUICK_MODES = listOf(PhotoMode.FLASH_NIGHT, PhotoMode.FLASH_FAST, PhotoMode.SPORT)
        val QUICK_NAMES = listOf("แฟลชเร็ว", "แฟลชเร็วพิเศษ", "ถ่ายเร็ว")
        fun barKey(m: PhotoMode) = if (m in QUICK_MODES) "QUICK" else m.name
        const val DEFAULT_BAR = "PORTRAIT,PHOTO,VIDEO,PRO"
        val LOOKS = listOf("มาตรฐาน", "สดใส", "อบอุ่น", "เย็น", "ฟิล์ม", "ขาวดำ")
        val NIGHT_FRAMES = listOf(6, 9, 12)
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
    private val tones = ToneGenerator(AudioManager.STREAM_MUSIC, 40)
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var capture: ImageCapture? = null
    private var metadata: FrameMetadata? = null
    private var rear = true
    private var software = false
    /** Zoom ratio the picture is cropped from once the hardware zoom reaches it (0 = switch lenses normally). */
    private var cropFrom = 0f
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
    private var lastZoomSent = 0L
    private var zoomTicket = 0
    private var binding = 0
    private var pendingBind = false
    // Session reuse and mode coalescing (core, from codex/video-session-recovery).
    private var previewObserved=false
    private var boundKey:List<Any?>?=null
    private var boundPreview:Preview?=null
    private val captureCaps=mutableMapOf<CameraInfo,Set<Int>>()
    private val modeHandler=android.os.Handler(android.os.Looper.getMainLooper())
    private var modePending=false
    private val switchMode=Runnable{modePending=false;if(!isDestroyed&&!isFinishing){bind();loadThumb()}}
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
    private lateinit var zooms: ZoomCapsule
    private lateinit var macroButton: FrameLayout
    private lateinit var nightButton: FrameLayout
    private lateinit var lookButton: FrameLayout
    private lateinit var lookSheet: GlassSheet
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
    private val turner = IconTurner()
    /** Contents that turn upright in landscape: round buttons' icons, the gallery thumb, lens pills and mode icons. */
    private fun turnables(): List<View> = listOfNotNull(thumb, flipIcon, macroButton.getChildAt(0), nightButton.getChildAt(0), apertureButton, lookButton.getChildAt(0), lockIcon, dialValue) +
        modeLabels.map { it.second } + (0 until statusRow.childCount).map { statusRow.getChildAt(it) } +
        (0 until zooms.childCount).map { zooms.getChildAt(it) } + IconTurner.tagged(window.decorView)
    /** New views (a sheet that just opened, rebuilt zoom pills) take the current angle at once. */
    private fun turnNow() { turner.apply(turnables()); expRail.contentAngle = turner.angle; focusRail.contentAngle = turner.angle }
    private var barKeys: List<String> = emptyList()
    private var ev = 0f
    private var aeLock = false
    private var pressLock = false
    private var macro = false
    private var macroReturn: Float? = null
    /** Zoom ratio and closest focus (diopters) of the back lens that focuses nearest, or null when none goes below ~17 cm. */
    private var macroLens: Pair<Float, Float>? = null
    private fun findMacroLens(cam: Camera) {
        macroLens = runCatching {
            val info = Camera2CameraInfo.from(cam.cameraInfo); val m = getSystemService(CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val logical = m.getCameraCharacteristics(info.cameraId)
            fun eq(c: CameraCharacteristics): Float? { val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null; val s = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null; return LensMeta.eq35(f, s.width, s.height).toFloat() }
            val mainEq = eq(logical) ?: return@runCatching null
            val lenses = (if (Build.VERSION.SDK_INT >= 28) logical.physicalCameraIds.mapNotNull { runCatching { m.getCameraCharacteristics(it) }.getOrNull() } else emptyList()) + logical
            lenses.mapNotNull { c -> val d = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: return@mapNotNull null; val e = eq(c) ?: return@mapNotNull null; (e / mainEq) to d }
                .filter { it.second >= 6f }.maxByOrNull { it.second }
        }.getOrNull()
    }
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
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT   // the screen stays upright; only the picture and the icons turn
        output = runCatching { CaptureOutput.valueOf(prefs.getString("output", CaptureOutput.JPEG.name)!!) }.getOrDefault(CaptureOutput.JPEG)
        if (output.enhance) output = CaptureOutput.JPEG
        if (prefs.getInt("night_frames", 6) > 12 || prefs.getInt("night_frames", 6) < 6) prefs.edit().putInt("night_frames", 6).apply()
        mode = runCatching { PhotoMode.valueOf(prefs.getString("photo_mode", "PHOTO")!!) }.getOrDefault(PhotoMode.PHOTO)
        if (ALL.none { it.key == barKey(mode) }) mode = PhotoMode.PHOTO   // a mode that no longer exists (night, pro video) falls back to photo
        if(ALL.none { it.key == barKey(mode) })mode=PhotoMode.PHOTO
        shutterSound.load(MediaActionSound.SHUTTER_CLICK)
        auto = false; cropFrom = cropPref(); software = cropFrom > 0f
        nightOn = false   // the night toggle was removed from the UI
        rear = state?.getBoolean("rear",true) ?: true
        ratio = state?.getFloat("zoom",1f) ?: 1f
        buildScreen()
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (!closeTopLayer()) { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true } }
        })
        @Suppress("DEPRECATION")
        targetRotation = windowManager.defaultDisplay.rotation
        orientation = object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                val next = when (degrees) { in 45..134 -> Surface.ROTATION_270; in 135..224 -> Surface.ROTATION_180; in 225..314 -> Surface.ROTATION_90; else -> Surface.ROTATION_0 }
                turner.update(degrees, { turnables() }, Glass.reduced(this@CameraActivity))
                expRail.contentAngle = turner.angle; focusRail.contentAngle = turner.angle
                if (next != targetRotation) { targetRotation = next; if (!busy) capture?.targetRotation = targetRotation }
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
                    ex.addListener({runCatching{extensions=ex.get();if(!isDestroyed&&!busy&&granted()&&mode in listOf(PhotoMode.NIGHT,PhotoMode.HDR,PhotoMode.PORTRAIT))bind()}},main)
                }
                catch (e: Exception) { cameraError("เปิดระบบกล้องไม่ได้ · ${e.cause?.localizedMessage ?: e.localizedMessage}") }
            }
        }, main)
    }
    /** Closes the top-most glass layer: menu, then sheets, then the pro dial. Returns false when nothing was open. */
    private fun closeTopLayer(): Boolean = when {
        lookSheet.showing -> { lookSheet.close(); true }
        menu.showing -> { menu.dismiss(true); true }
        editor.showing -> { editor.close(); true }
        panel.showing -> { panel.close(); true }
        openChip != null -> { closeDial(); true }
        else -> false
    }
    private fun granted() = permissions.all { ContextCompat.checkSelfPermission(this,it) == PackageManager.PERMISSION_GRANTED }
    override fun onResume() {
        super.onResume(); orientation.enable(); loadThumb();if(modePending){modeHandler.removeCallbacks(switchMode);modeHandler.post(switchMode)}
        if (::modeStrip.isInitialized) syncModeStrip(false)
        // The video screen hands back a photo mode picked on its mode strip.
        prefs.getString("pending_mode", null)?.let { k -> prefs.edit().remove("pending_mode").apply(); if (::modeStrip.isInitialized) { val i = barKeys.indexOf(k); if (i >= 0) { modeStrip.select(i, false); pickBar(i) } else runCatching { PhotoMode.valueOf(k) }.getOrNull()?.let { selectMode(it) } } }
        if (granted() && provider != null && capture == null) bind()
        if (!granted()) showPermission()
    }
    override fun onPause() {
        if (pano != null) endSweep(false)
        orientation.disable()
        modeHandler.removeCallbacks(switchMode)
        zoomAnimation?.cancel()
        burst?.cancel()
        hdr?.cancel()
        timerGeneration++;timerRunning=false
        menu.dismiss(false)
        zooms.finish(); wheel.removeCallbacks(hideWheel); hideWheel.run()
        clearLensShots()
        portraitLive?.stop(); qr?.stop()
        super.onPause()
    }
    override fun onDestroy() {
        shutterSound.release()
        tones.release()
        portraitLive?.close(); qr?.close()
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
        menu = GlassMenu(host) { on -> if (panel.showing || editor.showing || lookSheet.showing) { panel.recede(on); editor.recede(on); lookSheet.recede(on) } else pushBackSmooth(on) }
        panel = GlassSheet(host, { handlePivot() }) { pushBack(it) }
        editor = GlassSheet(host, { handlePivot() }) { pushBack(it) }
        lookSheet = GlassSheet(host, { handlePivot() }) { pushBack(it) }
        // One modal owner: a menu over a sheet adds no second dim, and the sheet below keeps its push-back.
        menu.over = { panel.showing || editor.showing || lookSheet.showing }
        // Portrait stacks the dock under the viewfinder; landscape puts it beside, so the viewfinder keeps its height.
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val column = LinearLayout(this).apply { orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        columnView = column; backdrop = null; pushedBack = false
        host.addView(column, -1, -1)
        stage = FrameLayout(this).apply { setBackgroundColor(BG) }
        column.addView(stage, if (landscape) LinearLayout.LayoutParams(0, -1, 1f) else LinearLayout.LayoutParams(-1, 0, 1f))
        mask = FrameLayout(this).apply {
            clipChildren = true; clipToPadding = true; clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, maskRadius) } }
        }
        stage.addView(mask, FrameLayout.LayoutParams(-1, -1, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        stage.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ -> layoutMask(right - left, bottom - top) }
        // The PreviewView survives a rebuilt screen (rotation) so a reused session keeps its surface.
        preview = (if (::preview.isInitialized) preview.also { (it.parent as? ViewGroup)?.removeView(it) } else PreviewView(this)).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FILL_CENTER }
        mask.addView(preview, -1, -1)
        portraitView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY; alpha = 0f; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        mask.addView(portraitView, -1, -1)
        portraitLive?.close(); portraitLive = PortraitPreview(preview, portraitView) { prefs.getFloat("portrait_f", 2.8f) }
        qr?.close(); qr = QrWatcher(preview, stage)
        overlay = CompositionOverlay(this).apply { gridKind = Grids.kind(prefs) }
        mask.addView(overlay, -1, -1)
        overlay.monitor(preview)
        faces = FaceOverlay(this); mask.addView(faces, -1, -1)
        frozen = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        mask.addView(frozen, -1, -1)
        if (!previewObserved) { previewObserved = true; preview.previewStreamState.observe(this) { if (it == PreviewView.StreamState.STREAMING) unfreeze() } }

        expRail = EdgeRail(this, false); stage.addView(expRail, FrameLayout.LayoutParams(dp(150), -1, Gravity.START).apply { bottomMargin = dp(136) })
        focusRail = EdgeRail(this, true); stage.addView(focusRail, FrameLayout.LayoutParams(dp(170), -1, Gravity.END).apply { bottomMargin = dp(136) })
        lockIcon = IconView(this, Icons.UNLOCK, 2f)
        lockButton = glassButton(lockIcon, 48, "ล็อกความสว่าง") { toggleAeLock() }.apply { alpha = 0f; visibility = View.GONE }
        stage.addView(lockButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START or Gravity.BOTTOM).apply { leftMargin = dp(8); bottomMargin = dp(140) })

        statusRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END; contentDescription = "สิ่งที่เปิดอยู่"; minimumWidth = dp(48); isClickable = true; setOnClickListener { openPanel() } }
        stage.addView(statusRow, FrameLayout.LayoutParams(-2, dp(48), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(12), 0) })
        banner = text("ล็อก AE/AF", 13f, Glass.ACCENT, true).apply { background = glass(16); setPadding(dp(14), dp(6), dp(14), dp(6)); visibility = View.GONE }
        stage.addView(banner, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(12) })
        viewfinderNotice = text("", 13f).apply { maxLines = 6; ellipsize = null; gravity = Gravity.CENTER; minHeight = dp(48); background = glass(18); setPadding(dp(16), dp(8), dp(16), dp(8)); visibility = View.GONE }
        viewfinderNotice.setOnClickListener { burst?.cancel() }
        stage.addView(viewfinderNotice, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(60) })
        permissionPanel = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(20), dp(22), dp(22)); background = glass(24, Glass.FILL_STRONG); visibility = View.GONE }
        stage.addView(permissionPanel, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        buildNightCard(); buildMiniMap()

        wheel = ZoomWheel(this).apply { alpha = 0f }
        stage.addView(wheel, FrameLayout.LayoutParams(-1, dp(150), Gravity.BOTTOM).apply { bottomMargin = dp(4) })
        zooms = ZoomCapsule(this).apply { background = glass(28); setPadding(dp(4), dp(4), dp(4), dp(4)); contentDescription = "ระยะเลนส์ ลากซ้ายขวาเพื่อซูมละเอียด" }
        installZoomDrag()
        stage.addView(zooms, FrameLayout.LayoutParams(-2, dp(56), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(8) })
        macroButton = glassButton(IconView(this, Icons.FLOWER), 48, "มาโคร ถ่ายใกล้ได้ทุกระยะเลนส์") { toggleMacro() }
        stage.addView(macroButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(14), 0, 0, dp(12)) })
        apertureButton = text("", 13f, Glass.ACCENT, true).apply {
            gravity = Gravity.CENTER; background = glass(24); setPadding(dp(8), 0, dp(8), 0); minWidth = dp(56); isClickable = true; isFocusable = true
            setOnClickListener { if (mode == PhotoMode.SPORT) chooseSport(this) else if (openChip == "F") closeDial() else openDial("F") }; glassTouch()
        }
        stage.addView(apertureButton, FrameLayout.LayoutParams(-2, dp(48), Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(10), 0, 0, dp(12)) })
        nightButton = glassButton(IconView(this, Icons.NIGHT), 48, "ถ่ายกลางคืน กดค้างเพื่อเลือกเวลา") { toggleNight() }
        nightButton.setOnLongClickListener { chooseNightLength(nightButton); true }
        stage.addView(nightButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(14), dp(12)) })
        // Look button sits above the night button with a small gap; it opens the colour / film sheet.
        lookButton = glassButton(IconView(this, Icons.FILM), 48, "ปรับแต่งภาพและฟิล์ม") { openLookSheet() }
        stage.addView(lookButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(14), dp(12)) })

        chipFrame = FrameLayout(this).apply { background = glass(24); visibility = View.GONE }
        chips = CenterStrip(this).apply { capsuleHeightDp = 44; onSelect = { i -> chipSelected(i) }; onLive = { i -> styleChips(i) } }
        chipFrame.addView(chips, FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(1), dp(1), dp(1), dp(1)) })
        stage.addView(chipFrame, FrameLayout.LayoutParams(-1, dp(56 * grow()), Gravity.BOTTOM).apply { setMargins(dp(16), 0, dp(16), dp(70)) })
        buildDial()

        val dock = SwipeColumn(this).apply {
            setBackgroundColor(BG); onSwipeUp = { openPanel(true) }
            onDrag = { dy -> if (panel.showing) panel.follow(dy) }; onRelease = { _, v -> if (panel.showing) panel.release(true, v) }
        }
        dockView = dock
        dock.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob -> if (b - t != ob - ot) dock.post { applyStageShape() } }
        androidx.core.view.ViewCompat.addAccessibilityAction(dock, "เปิดตัวเลือกกล้อง") { _, _ -> openPanel(); true }
        if (landscape) { dock.gravity = Gravity.CENTER_VERTICAL; column.addView(dock, dp(320), -1) } else column.addView(dock, -1, -2)
        handle = IconView(this, Icons.CHEVRON_UP, 1.6f).apply { tint = Glass.TEXT3; contentDescription = "ตัวเลือกกล้อง ปัดขึ้นหรือแตะเพื่อเปิด"; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES; isClickable = true; isFocusable = true; setPadding(dp(46), dp(19), dp(46), dp(19)); setOnClickListener { openPanel() } }
        dock.addView(handle, LinearLayout.LayoutParams(dp(120), dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = -dp(10) })
        nudge()
        modeStrip = CenterStrip(this).apply { onSelect = { i -> pickBar(i) }; onLive = { i -> styleModes(i) }; contentDescription = "แถบโหมด" }
        dock.addView(modeStrip, -1, dp(58 * grow()))
        if (android.os.Build.VERSION.SDK_INT >= 29) modeStrip.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ -> v.systemGestureExclusionRects = listOf(android.graphics.Rect(0, 0, r - l, b - t)) }
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(36), 0, dp(36), 0) }
        dock.addView(actions, -1, dp(104))
        thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; background = glass(16); clipToOutline = true
            contentDescription = "เปิดรูปถ่ายล่าสุด"; setOnClickListener { startActivity(Intent(this@CameraActivity, GalleryActivity::class.java)) }
            setOnLongClickListener { startActivity(Intent(this@CameraActivity, GalleryActivity::class.java).putExtra("tab", "luma")); true }
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
        installGestures()
        rebuildModeStrip()
        refresh()
    }
    private var maskRadius = 0f

    /** Rows that hold two lines of text grow with the font scale (1.0 → 1, 2.0 → 1.5). */
    private fun grow(): Float = ((resources.configuration.fontScale + 1f) / 2f).coerceAtLeast(1f)
    private fun dp(x: Float) = (resources.displayMetrics.density * x).roundToInt()
    /** Tall shapes (16:9 and longer) run edge to edge under the control dock, like the phone's own camera. */
    private fun applyStageShape() {
        val dock = dockView ?: return
        if (!::stage.isInitialized || resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) return
        val tall = Aspects.value(prefs.getString("aspect", "4:3") ?: "4:3", false) < 0.6f && dock.height > 0
        val lp = dock.layoutParams as LinearLayout.LayoutParams
        val want = if (tall) -dock.height else 0
        if (lp.topMargin != want) { lp.topMargin = want; dock.layoutParams = lp }
        dock.setBackgroundColor(if (tall) 0x66070b16 else BG)
        stage.clipToPadding = false
        val pad = if (tall) dock.height else 0
        if (stage.paddingBottom != pad) stage.setPadding(0, 0, 0, pad)
    }
    private fun layoutMask(w0: Int, h0: Int) {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val format = prefs.getString("aspect", "4:3")
        val aspect = Aspects.value(format ?: "4:3", landscape)
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
    private var dockView: View? = null
    private lateinit var apertureButton: TextView
    private lateinit var portraitView: ImageView
    private var portraitLive: PortraitPreview? = null
    private var qr: QrWatcher? = null
    private var chromeBlurred = false
    /**
     * Everything behind an open sheet or menu (0..1) fades into one blurred, darkened still of the camera that covers
     * the whole screen — preview, dock and controls alike — so there is no edge where the preview ends, and the blur
     * deepens with the finger instead of switching on at once. The still is static, so drawing it costs almost nothing.
     */
    private fun pushBack(f: Float) {
        if (!::mask.isInitialized) return
        if (mask.scaleX != 1f || maskRadius != 0f) { mask.scaleX = 1f; mask.scaleY = 1f; maskRadius = 0f; mask.invalidateOutline() }
        val b = backdrop ?: View(this).apply {
            setBackgroundColor(0xff070b16.toInt()); alpha = 0f; visibility = View.GONE; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            host.addView(this, host.indexOfChild(columnView) + 1, FrameLayout.LayoutParams(-1, -1)); backdrop = this
        }
        val k = f.coerceIn(0f, 1f)
        if (k > 0.001f) {
            if (b.visibility != View.VISIBLE) { b.visibility = View.VISIBLE; pushedBack = true }
            // The live view keeps playing behind the sheet; it is only dimmed and softened a little.
            // Only dimmed: blurring the whole column (live preview included) on every frame made sheets and menus stutter.
            b.animate().cancel(); b.alpha = 0.5f * k
        } else if (pushedBack) {
            pushedBack = false
            columnView?.let { Glass.blur(it, 0f) }
            b.animate().alpha(0f).setDuration(if (Glass.reduced(this)) 0 else 200).withEndAction { if (!pushedBack) b.visibility = View.GONE }.start()
        }
    }
    private var pushAnim: android.animation.ValueAnimator? = null
    /** Menus without a sheet fade the backdrop in and out instead of snapping. */
    private fun pushBackSmooth(on: Boolean) {
        pushAnim?.cancel()
        if (Glass.reduced(this)) { pushBack(if (on) 1f else 0f); return }
        pushAnim = android.animation.ValueAnimator.ofFloat(if (on) 0f else 1f, if (on) 1f else 0f).apply { duration = 220; interpolator = Glass.EASE; addUpdateListener { pushBack(it.animatedValue as Float) }; start() }
    }
    private var backdrop: View? = null
    private var columnView: View? = null
    private var pushedBack = false
    /** Covers the preview with a downscaled, blurred still (rebinds, sheets). [scale] 3 for rebinds, 8 behind sheets. */
    private fun freezeFrame(scale: Int = 3) {
        if (frozen.alpha > 0.5f && frozen.drawable != null) return
        val full = runCatching { preview.bitmap }.getOrNull() ?: return
        val b = Bitmap.createScaledBitmap(full, (full.width / scale).coerceAtLeast(1), (full.height / scale).coerceAtLeast(1), true).also { if (it !== full) full.recycle() }
        (frozen.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.takeIf { it !== b }?.let { frozen.setImageDrawable(null); it.recycle() }
        frozen.animate().cancel()
        frozen.setImageBitmap(b); frozen.alpha = 1f; Glass.blur(frozen, if (scale > 3) 22f else 8f)
    }

    private fun buildNightCard() {
        nightCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; background = glass(28); setPadding(dp(16), dp(16), dp(16), dp(14)); visibility = View.GONE }
        nightCount = text("", 30f, Glass.ACCENT, true).apply { gravity = Gravity.CENTER }
        nightCard.addView(IconView(this, "M12 4v16 M4 12h16 M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8z", 1.6f).apply { tint = Glass.ACCENT }, dp(56), dp(56))
        nightCard.addView(nightCount, -2, -2)
        nightCard.addView(text("ถือกล้องให้นิ่ง", 13f, Color.WHITE, true), -2, -2)
        nightCard.addView(text("กำลังรวมแสงกลางคืน · แตะชัตเตอร์เพื่อหยุด", 11.5f, Glass.TEXT2).apply { maxLines = 3; ellipsize = null; gravity = Gravity.CENTER }, -2, -2)
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
        head.addView(dialTitle, LinearLayout.LayoutParams(0, -2, 1f)); head.addView(dialValue, LinearLayout.LayoutParams(0, -2, 1f)); dialReset.minWidth = dp(64); head.addView(dialReset, -2, dp(48))
        dial.addView(head, -1, -2)
        slider = GlassSlider(this).apply { onChange = { i -> sliderChanged(i) } }
        // One-step buttons for fine values (1/3 stop, 100 K, one focus step), like OPPO's dial nudges.
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fun nudge(d: Int) { val k = openChip ?: return; val t = (slider.index + d).coerceIn(slider.lo.coerceAtMost(chipAuto(k)), slider.values.size - 1); if (t != slider.index) { slider.index = t; Glass.tick(slider); applyChip(k, t) } }
        row.addView(glassButton(IconView(this, Icons.MINUS, 2.2f), 48, "ลดหนึ่งขั้น") { nudge(-1) }, dp(48), dp(48))
        row.addView(slider, LinearLayout.LayoutParams(0, dp(56), 1f))
        row.addView(glassButton(IconView(this, Icons.PLUS, 2.2f), 48, "เพิ่มหนึ่งขั้น") { nudge(1) }, dp(48), dp(48))
        dial.addView(row, -1, -2)
        stage.addView(dial, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(16), 0, dp(16), dp(130)) })
    }

    // ---------- Mode bar ----------
    private fun barList(): List<String> = (prefs.getString("mode_bar", DEFAULT_BAR) ?: DEFAULT_BAR).split(',').map { if (it == "FLASH_NIGHT" || it == "FLASH_FAST" || it == "SPORT") "QUICK" else it }.distinct().filter { k -> ALL.any { it.key == k } }.let { if ("PHOTO" in it) it else listOf("PHOTO") + it }
    private fun rebuildModeStrip() {
        val bar = barList(); barKeys = if (barKey(mode) in bar) bar else bar + barKey(mode)
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
        modeStrip.setItems(items, barKeys.indexOf(barKey(mode)).coerceAtLeast(0))
    }
    private fun styleModes(live: Int) {
        modeLabels.forEachIndexed { i, (t, ic) -> val on = i == live; t.setTextColor(if (on) Glass.ACCENT else Glass.TEXT2); t.typeface = if (on) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT; ic.tint = if (on) Glass.ACCENT else Glass.TEXT2 }
    }
    private fun syncModeStrip(animate: Boolean) { val i = barKeys.indexOf(barKey(mode)); if (i >= 0) modeStrip.select(i, animate) else rebuildModeStrip() }
    private fun pickBar(i: Int) {
        // "+" opens the editor only when tapped; a swipe that ends past the last mode settles on that mode.
        if (i >= barKeys.size) { if (modeStrip.byTap) { syncModeStrip(true); openEditor() } else { modeStrip.select(barKeys.size - 1, true); pickBar(barKeys.size - 1) }; return }
        val key = barKeys[i]
        when (key) {
            "VIDEO" -> openVideo("VIDEO")
            "TIMELAPSE" -> openSpecial("TIMELAPSE")
            "RAWV" -> { provider?.unbindAll(); capture = null; startActivity(Intent(this, RawLabActivity::class.java)) }
            "QUICK" -> selectMode(quickMode())
            else -> runCatching { PhotoMode.valueOf(key) }.getOrNull()?.let { selectMode(it) }
        }
    }
    /** A portrait rectangle with the ratio's real proportions, so 21:9 looks long and 1:1 square. */
    private fun aspectIcon(name: String): String {
        val p = name.split(':').mapNotNull { it.trim().toFloatOrNull() }
        val r = if (p.size == 2 && p[1] > 0f) maxOf(p[0], p[1]) / minOf(p[0], p[1]) else 4f / 3f
        val h = 20f; val w = h / r; val x = 12f - w / 2; val y = 12f - h / 2
        return String.format(java.util.Locale.US, "M%.2f %.2fh%.2fv%.2fh%.2fz", x, y, w, h, -w)
    }
    private fun quickMode() = runCatching { PhotoMode.valueOf(prefs.getString("quick_kind", "FLASH_NIGHT") ?: "") }.getOrNull()?.takeIf { it in QUICK_MODES } ?: PhotoMode.FLASH_NIGHT
    private fun step(dir: Int) { val i = barKeys.indexOf(barKey(mode)) + dir; if (i in barKeys.indices) { modeStrip.select(i, true); pickBar(i) } }

    // ---------- Status, rails, gestures ----------
    private fun statusIcon(d: String, inner: String = "", after: String = "", name: String) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(3), 0, dp(3), 0); contentDescription = name }
        row.addView(IconView(this, d).apply { tint = Glass.ACCENT; this.inner = inner; shadow = true }, dp(24), dp(24))
        if (after.isNotEmpty()) row.addView(text(after, 12f, Glass.ACCENT, true).apply { setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), 0x99000000.toInt()) }, -2, -2)
        statusRow.addView(row, -2, dp(44))
    }
    private fun refreshStatus() {
        statusRow.removeAllViews()
        statusRow.visibility = if (prefs.getBoolean("show_status", true)) View.VISIBLE else View.GONE
        if (flash != ImageCapture.FLASH_MODE_OFF) statusIcon(Icons.FLASH, after = if (flash == ImageCapture.FLASH_MODE_AUTO) "A" else "", name = "แฟลช${if (flash == ImageCapture.FLASH_MODE_AUTO) "อัตโนมัติ" else "เปิด"}")
        val timer = prefs.getString("timer", "0") ?: "0"; if (timer != "0") statusIcon(Icons.TIMER, after = "${timer}s", name = "ตั้งเวลา $timer วินาที")
        val aspect = prefs.getString("aspect", "4:3") ?: "4:3"; if (aspect != "4:3") statusIcon(Icons.FRAME, inner = aspect, name = "สัดส่วนภาพ $aspect")
        if (output != CaptureOutput.JPEG && mode in listOf(PhotoMode.PHOTO, PhotoMode.PRO)) statusIcon(Icons.FILE, after = output.label.replace(" + ", "+"), name = "รูปแบบไฟล์ ${output.label}")
        if (aeLock || pressLock) statusIcon(Icons.LOCK, after = "AE", name = "ล็อกความสว่าง")
        if (macro) statusIcon(Icons.FLOWER, name = "มาโคร เปิด")
        if (nightActive()) statusIcon(Icons.NIGHT, after = listOf("2s", "3s", "5s")[nightIndex()], name = "กลางคืน · " + if (activeExtension) "ระบบกล้อง" else "AI รวมเฟรม")
        statusRow.contentDescription = "สิ่งที่เปิดอยู่: " + (0 until statusRow.childCount).joinToString(", ") { statusRow.getChildAt(it).contentDescription }
    }
    private fun nightIndex() = NIGHT_FRAMES.indexOf(prefs.getInt("night_frames", 6)).coerceAtLeast(0)
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
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { if (zoomFollower?.isRunning != true) zoomGoal = ratio; return true }
            override fun onScale(detector: ScaleGestureDetector): Boolean { if (zoomLocked()) return true; zoomTo(zoomGoal * detector.scaleFactor); return true }
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
                if (axis == 0) { if (abs(my) > dp(4) && abs(my) > abs(mx)) { axis = 1; sy = last.y } else if (abs(mx) > dp(6)) axis = 2 }
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
                if (openChip != null) { closeDial(); return true }
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
                if (prefs.getBoolean("focus_sound", false) && Sounds.enabled(prefs)) tones.startTone(ToneGenerator.TONE_PROP_BEEP, 50)
                if (prefs.getBoolean("tap_shutter", false)) takePhoto()
                preview.performClick(); refresh(); return true
            }
        })
        // Two fingers belong to zoom only: the one-finger detector is cancelled so exposure, focus and long-press stay put.
        var multi = false
        preview.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) multi = false
            if (e.pointerCount > 1 && !multi) {
                multi = true
                val c = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }; taps.onTouchEvent(c); c.recycle()
                if (axis == 1 && !pressLock) { if (zone == 2) setFocusRail(if (fd0 > 0f) fd0 else null) else setEv(ev0, true) }
                axis = 0
            }
            scale.onTouchEvent(e); if (!multi) taps.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) { if (axis == 1) { expRail.removeCallbacks(hideRails); expRail.postDelayed(hideRails, 1600) } }
            true
        }
    }
    // ---------- Zoom ----------
    /** Zoom gestures pause while an adjuster, menu or sheet is open, so they never fight with it. */
    private fun zoomLocked() = openChip != null || panel.showing || editor.showing || lookSheet.showing || menu.showing
    private var dragZoom0 = 1f
    private fun zoomDragStart() { if (zoomLocked()) return; dragZoom0 = if (zoomFollower?.isRunning == true) zoomGoal else ratio; dialing = true; wheel.removeCallbacks(hideWheel); zoomAnimation?.cancel(); zooms.animate().alpha(0f).setDuration(200).start(); chipFrame.animate().alpha(0f).setDuration(160).start(); wheel.show(true) }
    private fun zoomDragMove(dx: Float) {
        if (zoomLocked()) return
        val (lo, hi) = range()
        var z = (dragZoom0 * 2f.pow(-dx / dp(80))).coerceIn(lo, hi)
        renderedZooms.firstOrNull { abs(ln(z / it) / ln(2f)) < 0.075f }?.let { lens -> if (abs(ratio - lens) > 0.001f) Glass.tick(wheel); z = lens }
        zoomTo(z); wheel.zoom = zoomGoal
    }
    private fun zoomDragEnd() { wheel.removeCallbacks(hideWheel); wheel.postDelayed(hideWheel, 1300) }
    private fun installZoomDrag() {
        zooms.onStart = { zoomDragStart() }; zooms.onDrag = { zoomDragMove(it) }; zooms.onEnd = { zoomDragEnd() }
        // While the wheel is showing it keeps the drag: a finger on the wheel continues from the current zoom.
        var sx = 0f; var on = false
        wheel.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { on = wheel.alpha > 0.5f; if (on) { sx = e.rawX; zoomDragStart(); v.parent.requestDisallowInterceptTouchEvent(true) } }
                MotionEvent.ACTION_MOVE -> if (on) zoomDragMove(e.rawX - sx)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (on) { on = false; zoomDragEnd() }
            }
            on
        }
    }
    // The chip strip steps aside while the zoom wheel is out (they overlapped in PRO and ถ่ายเร็ว).
    private val hideWheel = Runnable { dialing = false; wheel.show(false); zooms.animate().alpha(1f).setDuration(200).start(); chipFrame.animate().alpha(1f).setDuration(200).start(); refresh() }
    private var lastShot = 0L
    /** Keeps one small frame per lens for the minimap; never more than two, each about 240 px wide. */
    private fun snapshotLens(lens: Float) {
        if (lens != 1f && lens != 3.5f) return
        val now = android.os.SystemClock.uptimeMillis(); if (now - lastShot < 1500 && lensShots.containsKey(lens)) return; lastShot = now
        val small = preview.smallFrame(240) ?: return
        // A frame grabbed while the lens is still switching is black; keep the previous map instead.
        val px = IntArray(small.width * small.height); small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        val mean = px.sumOf { ((it shr 16 and 255) + (it shr 8 and 255) + (it and 255)).toLong() } / (3L * px.size)
        if (mean < 4 && lensShots.containsKey(lens)) { small.recycle(); return }
        lensShots.put(lens, small)?.recycle()
    }
    private fun clearLensShots() { lensShots.values.forEach { it.recycle() }; lensShots.clear(); if (::miniImage.isInitialized) miniImage.setImageDrawable(null) }
    private var miniLive = false
    private val miniTicker = object : Runnable { override fun run() {
        if (!miniLive || isDestroyed || miniCard.visibility != View.VISIBLE) { miniLive = false; return }
        if (!busy) preview.smallFrame(180)?.let { small ->
            val old = (miniImage.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            miniImage.setImageBitmap(small); if (old != null && old !== small && !lensShots.containsValue(old)) old.recycle()
        }
        miniCard.postDelayed(this, 160)
    } }
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
        // The map is the frame seen while resting exactly on a lens (1× or 3.5×), refreshed every 1.5 s there.
        if (rear && !busy && !zoomSettling && preview.previewStreamState.value == PreviewView.StreamState.STREAMING)
            renderedZooms.firstOrNull { (it == 1f || it == 3.5f) && abs(ratio - it) < 0.02f }?.let { snapshotLens(it) }
        val showMini = ratio > 10f && rear
        val lens = if (ratio >= 3.5f && renderedZooms.contains(3.5f)) 3.5f else 1f
        if (showMini && miniCard.visibility != View.VISIBLE) { miniCard.visibility = View.VISIBLE; Liquid.show(miniCard, miniCard.width.toFloat(), 0f, false) }
        if (!showMini && miniCard.visibility == View.VISIBLE) Liquid.hide(miniCard, miniCard.width.toFloat(), 0f, false)
        if (showMini) {
            miniLabel.text = zoomText(lens)
            // When the app does the zoom by cropping, the stream is still the whole lens view, so the small map can be live.
            if (appCrop() > 1.01f && abs(minOf(ratio, hardwareMax()) - lens) < 0.05f) { if (!miniLive) { miniLive = true; miniCard.post(miniTicker) } }
            else { miniLive = false; lensShots[lens]?.let { miniImage.setImageBitmap(it) } }
            val f = (lens / ratio).coerceIn(0.05f, 1f).pow(0.5f); val lp = miniBox.layoutParams
            lp.width = (dp(90) * f).roundToInt(); lp.height = (dp(120) * f).roundToInt(); miniBox.layoutParams = lp
        }
    }

    // ---------- Pro chips and dial ----------
    private fun chipKeys(): List<String> = if (mode in QUICK_MODES) listOf("KIND") else if (mode != PhotoMode.PRO) emptyList() else buildList {
        val m = manual; add("ALL"); if (m != null && m.canManual) { add("ISO"); add("S") }
        add("WB"); if (m != null && m.canKelvin) add("TINT"); if ((m?.minFocus ?: 0f) > 0f) add("AF"); add("EV")
        if (m != null) { if (m.contrastValues.isNotEmpty()) add("CON"); if (m.edgeValues.size > 1) add("SHP"); if (m.nrValues.size > 1) add("NR") }
    }
    private val chipTexts = mutableListOf<Pair<TextView, TextView>>()
    private fun rebuildChips() {
        val keys = chipKeys(); chipTexts.clear()
        if (keys.isEmpty()) { chipFrame.visibility = View.GONE; closeDial(); return }
        chipFrame.visibility = View.VISIBLE
        val items = keys.map { k ->
            val t = text(chipTitle(k), 10.5f, Glass.TEXT2).apply { gravity = Gravity.CENTER }; val v = text(chipValue(k), 13f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
            chipTexts += t to v
            // Width follows the longest text at the current font scale so large fonts are not clipped.
            val wDp = (chipValues(k).plus(chipTitle(k)).maxOf { v.paint.measureText(it) } / resources.displayMetrics.density + 24).roundToInt().coerceAtLeast(72)
            CenterStrip.Item(k, wDp, LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true; contentDescription = chipTitle(k); addView(t, -2, -2); addView(v, -2, -2) })
        }
        chips.setItems(items, keys.indexOf(openChip ?: prefs.getString("pro_chip", "ISO")).coerceAtLeast(0))
    }
    private fun styleChips(live: Int) {
        val keys = chipKeys()
        chipTexts.forEachIndexed { i, (t, v) -> val on = i == live; t.setTextColor(if (on) Color.WHITE else Glass.TEXT3); v.text = chipValue(keys[i]); v.setTextColor(chipColor(keys[i])); (v.parent as? View)?.animate()?.scaleX(if (on) 1f else 0.92f)?.scaleY(if (on) 1f else 0.92f)?.setDuration(300)?.start() }
    }
    private fun chipTitle(k: String) = when (k) { "KIND" -> "รูปแบบ"; "ALL" -> "ทั้งหมด"; "ISO" -> "ISO"; "S" -> "ชัตเตอร์"; "WB" -> "WB"; "TINT" -> "ทินต์"; "AF" -> "โฟกัส"; "CON" -> "คอนทราสต์"; "SHP" -> "ความคม"; "NR" -> "ลดนอยส์"; "F" -> "เบลอพื้นหลัง"; else -> "EV" }
    private fun chipValues(k: String): List<String> {
        val m = manual
        return when (k) {
            "ALL" -> listOf("AUTO")
            "KIND" -> QUICK_NAMES
            "F" -> F_STEPS.map { "f/" + fText(it) }
            "ISO" -> listOf("AUTO") + (m?.isoValues ?: emptyList()).map { it.toString() }
            "S" -> listOf("AUTO") + (m?.shutterValues ?: emptyList()).map { it.first }
            "WB" -> (m?.wbValues ?: listOf("AUTO" to 1)).map { it.first }
            "AF" -> (m?.focusValues ?: listOf("AUTO" to null)).map { it.first }
            "TINT" -> (m?.tintValues ?: listOf("0" to 0)).map { it.first }
            "CON" -> (m?.contrastValues ?: listOf("±0" to 0)).map { it.first }
            "SHP" -> (m?.edgeValues ?: listOf("AUTO" to null)).map { it.first }
            "NR" -> (m?.nrValues ?: listOf("AUTO" to null)).map { it.first }
            else -> evValues().map { (if (it > 0) "+" else if (it == 0f) "±" else "") + String.format(Locale.US, "%.1f", it) }
        }
    }
    private fun evValues(): List<Float> { val st = camera?.cameraInfo?.exposureState ?: return listOf(0f); if (!st.isExposureCompensationSupported) return listOf(0f); val step = st.exposureCompensationStep.toFloat(); val r = st.exposureCompensationRange; return (r.lower..r.upper).map { it * step }.filter { abs(it) <= 4.01f } }
    private fun chipIndex(k: String): Int {
        val m = manual
        return when (k) {
            "ALL" -> 0
            "KIND" -> QUICK_MODES.indexOf(mode).coerceAtLeast(0)
            "F" -> F_STEPS.indices.minBy { abs(F_STEPS[it] - prefs.getFloat("portrait_f", 2.8f)) }
            "ISO" -> m?.currentIso?.let { (m.isoValues.indexOf(it) + 1).coerceAtLeast(0) } ?: 0
            "S" -> m?.currentShutter?.let { s -> (m.shutterValues.indexOfFirst { it.second == s } + 1).coerceAtLeast(0) } ?: 0
            "WB" -> (m?.wbValues?.indexOfFirst { it.second == m.currentWb } ?: 0).coerceAtLeast(0)
            "AF" -> m?.currentFocus?.let { f -> if (m.minFocus <= 0f) 0 else 1 + ((f / m.minFocus) * 48).roundToInt().coerceIn(0, 48) } ?: 0
            "TINT" -> (m?.tintValues?.indexOfFirst { it.second == m.currentTint } ?: 0).coerceAtLeast(0)
            "CON" -> (m?.contrastValues?.indexOfFirst { it.second == m.currentContrast } ?: 0).coerceAtLeast(0)
            "SHP" -> (m?.edgeValues?.indexOfFirst { it.second == m.currentEdge } ?: 0).coerceAtLeast(0)
            "NR" -> (m?.nrValues?.indexOfFirst { it.second == m.currentNr } ?: 0).coerceAtLeast(0)
            else -> evValues().let { list -> list.indexOfFirst { abs(it - (camera?.cameraInfo?.exposureState?.let { s -> s.exposureCompensationIndex * s.exposureCompensationStep.toFloat() } ?: 0f)) < 0.01f }.coerceAtLeast(0) }
        }
    }
    private fun chipValue(k: String) = chipValues(k).getOrElse(chipIndex(k)) { "AUTO" }
    private fun chipAuto(k: String) = when (k) {
        "F" -> F_STEPS.indexOfFirst { abs(it - 2.8f) < 0.01f }
        "EV" -> evValues().indexOfFirst { abs(it) < 0.01f }.coerceAtLeast(0)
        "TINT" -> manual?.tintValues?.indexOfFirst { it.second == 0 }?.coerceAtLeast(0) ?: 0
        "CON" -> manual?.contrastValues?.indexOfFirst { it.second == 0 }?.coerceAtLeast(0) ?: 0
        else -> 0
    }
    private fun chipSep(k: String) = k in listOf("ISO", "S", "AF")
    private fun chipGrad(k: String) = when (k) {
        "KIND" -> intArrayOf(0xffffd60a.toInt(), Glass.ACCENT, 0xff64d2ff.toInt())
        "F" -> intArrayOf(0xffbf9bff.toInt(), 0xff7db8ff.toInt(), Color.WHITE, 0xffffd9a8.toInt())
        "ISO" -> intArrayOf(0xff5ac8fa.toInt(), 0xff7be3b0.toInt(), Glass.ACCENT, 0xffff9f0a.toInt(), 0xffff453a.toInt())
        "S" -> intArrayOf(0xff64d2ff.toInt(), 0xffbf9bff.toInt(), Glass.ACCENT, 0xffff9f0a.toInt())
        "WB" -> intArrayOf(0xffff9a3d.toInt(), 0xffffd9a8.toInt(), Color.WHITE, 0xffcfe5ff.toInt(), 0xff7db8ff.toInt())
        "AF" -> intArrayOf(0xff8e8e93.toInt(), 0xffbbf0ff.toInt(), 0xff5ac8fa.toInt())
        "TINT" -> intArrayOf(0xff5fd38a.toInt(), Color.WHITE, 0xffe57bd8.toInt())
        "CON", "SHP", "NR" -> intArrayOf(0xff5a6b8f.toInt(), 0xffb9c6e6.toInt(), Glass.ACCENT)
        else -> intArrayOf(0xff3a3a3c.toInt(), 0xff8e8e93.toInt(), Color.WHITE)
    }
    private fun chipColor(k: String): Int { val i = chipIndex(k); if (i == chipAuto(k)) return Color.WHITE; val n = chipValues(k).size; val lo = if (chipSep(k)) 1 else 0; return Glass.mix(chipGrad(k), (i - lo).toFloat() / (n - 1 - lo).coerceAtLeast(1)) }
    private fun chipSelected(i: Int) {
        val k = chipKeys().getOrNull(i) ?: return; prefs.edit().putString("pro_chip", k).apply()
        if (openChip == k) closeDial() else openDial(k)
    }
    private fun openDial(k: String) {
        // "ทั้งหมด · AUTO" puts every manual value back to automatic in one tap.
        if (k == "ALL") { closeDial(); manual?.reset(); ev = 0f; camera?.cameraControl?.setExposureCompensationIndex(0); hint("ทุกค่ากลับเป็น AUTO", 1300); rebuildChips(); return }
        val first = openChip == null; openChip = k
        dialTitle.text = chipTitle(k); val values = chipValues(k)
        slider.lo = if (chipSep(k)) 1 else 0; slider.values = values; slider.grad = chipGrad(k); slider.index = chipIndex(k)
        dialReset.text = if (k == "EV" || k == "TINT" || k == "CON") "±0" else if (k == "F") "f/2.8" else "AUTO"; if (k == "F") dialTitle.text = "เบลอพื้นหลัง · " + blurWord(F_STEPS[chipIndex("F")]); updateDialValue(k)
        if (first || dial.visibility != View.VISIBLE) { dial.visibility = View.VISIBLE; Liquid.show(dial, dial.width / 2f, dial.height.toFloat(), false) }
        else Liquid.show(dial, dial.width / 2f, dial.height.toFloat(), false)
        zooms.animate().alpha(0f).setDuration(160).start()
    }
    private fun closeDial() { if (openChip == null) return; openChip = null; Liquid.hide(dial, dial.width / 2f, dial.height.toFloat(), false); zooms.animate().alpha(1f).setDuration(200).start() }
    private fun updateDialValue(k: String) { dialValue.text = chipValue(k); dialValue.setTextColor(chipColor(k)); val auto = chipIndex(k) == chipAuto(k); dialReset.setTextColor(if (auto) Glass.ON_ACCENT else Color.WHITE); (dialReset.background as? GlassDrawable)?.let { it.fill = if (auto) Glass.ACCENT else 0x1fffffff; it.invalidateSelf() } }
    private fun sliderChanged(i: Int) { applyChip(openChip ?: return, i) }
    private fun applyChip(k: String, i: Int) {
        if (k == "KIND") {
            // The slider moves freely; the camera switches once it rests, so a sweep across does not rebind three times.
            val pm = QUICK_MODES[i.coerceIn(0, QUICK_MODES.size - 1)]
            dialValue.text = QUICK_NAMES[QUICK_MODES.indexOf(pm)]
            preview.removeCallbacks(quickSwitch); quickTarget = pm; preview.postDelayed(quickSwitch, 320); return
        }
        if (k == "F") { prefs.edit().putFloat("portrait_f", F_STEPS[i.coerceIn(0, F_STEPS.size - 1)]).apply(); updateDialValue(k); dialTitle.text = "เบลอพื้นหลัง · " + blurWord(F_STEPS[i.coerceIn(0, F_STEPS.size - 1)]); refresh(); return }
        val m = manual ?: return
        when (k) {
            "ISO" -> m.setIso(if (i <= 0) null else m.isoValues.getOrNull(i - 1))
            "S" -> m.setShutter(if (i <= 0) null else m.shutterValues.getOrNull(i - 1)?.second)
            "WB" -> m.wbValues.getOrNull(i)?.let { m.setWb(it.second) }
            "AF" -> m.setFocus(m.focusValues.getOrNull(i)?.second)
            "TINT" -> m.tintValues.getOrNull(i)?.let { m.setTint(it.second) }
            "CON" -> m.contrastValues.getOrNull(i)?.let { m.setContrast(it.second) }
            "SHP" -> m.setEdge(m.edgeValues.getOrNull(i)?.second)
            "NR" -> m.setNr(m.nrValues.getOrNull(i)?.second)
            else -> evValues().getOrNull(i)?.let { setEv(it, false) }
        }
        updateDialValue(k); styleChips(chips.selected)
    }
    private var quickTarget: PhotoMode? = null
    private val quickSwitch = Runnable { val pm = quickTarget ?: return@Runnable; if (pm != mode && !busy) { prefs.edit().putString("quick_kind", pm.name).apply(); selectMode(pm) } }
    private fun resetChip() { val k = openChip ?: return; slider.index = chipAuto(k); applyChip(k, chipAuto(k)) }

    // ---------- Macro and night ----------
    private fun toggleMacro() {
        if (!rear) { hint("มาโครใช้ได้กับกล้องหลัง", 1400); return }
        val m = manual; val lens = macroLens; if (m == null || lens == null) { hint("กล้องนี้ไม่เปิดโฟกัสใกล้ให้แอป", 1600); return }
        macro = !macro
        // Close focus lives on the lens that focuses nearest (often the ultra-wide): move there, then focus at its limit.
        if (macro) { macroReturn = ratio; if (abs(lens.first - ratio) > 0.05f) animateZoom(lens.first.coerceIn(range().first, range().second)) } else macroReturn?.let { animateZoom(it) }
        m.setFocus(if (macro) m.minFocus else null)
        hint(if (macro) "มาโคร · ใกล้สุดราว ${(100f / lens.second).roundToInt()} ซม. ที่ ${zoomText(lens.first)}" else "ปิดมาโคร", 1600); refresh()
    }
    private fun toggleNight() {
        if (mode == PhotoMode.NIGHT) { hint("โหมดกลางคืนเปิดอยู่แล้ว · กดค้างเพื่อเลือกเวลา", 1500); return }
        nightOn = !nightOn; prefs.edit().putBoolean("night_toggle", nightOn).apply()
        hint(if (nightOn) "ถ่ายกลางคืน · กดชัตเตอร์แล้วถือนิ่ง" else "ปิดถ่ายกลางคืน", 1500); refresh()
    }
    private fun fText(f: Float) = if (f % 1f == 0f) f.toInt().toString() else String.format(Locale.US, "%.1f", f)
    /** Portrait background blur as an f-number. Phone lenses have a fixed aperture: this sets the strength of the simulated blur. */
    private fun chooseAperture(anchor: View) {
        val current = prefs.getFloat("portrait_f", 2.8f)
        menu.show(anchor, "เบลอพื้นหลัง (จำลอง)", APERTURES.map { GlassMenu.Option("f/${fText(it)}", "", when { it <= 1.4f -> "เบลอมากที่สุด"; it <= 2.8f -> "เบลอชัด"; it <= 5.6f -> "เบลอพอดี"; else -> "เบลอน้อย" }) }, APERTURES.indexOfFirst { abs(it - current) < 0.01f }) { i ->
            prefs.edit().putFloat("portrait_f", APERTURES[i]).apply(); refresh()
            hint("f/${fText(APERTURES[i])} · เบลอจำลองตอนบันทึก ไม่ใช่รูรับแสงจริง", 1800)
        }
    }
    private fun chooseNightLength(anchor: View) {
        closeDial()
        menu.show(anchor, "เวลารวมแสงกลางคืน", listOf(GlassMenu.Option("อัตโนมัติ", Icons.NIGHT, "6 เฟรม ประมาณ 2 วินาที"), GlassMenu.Option("ยาว", Icons.NIGHT, "9 เฟรม ฉากมืดมาก"), GlassMenu.Option("ยาวที่สุด", Icons.NIGHT, "12 เฟรม ใช้ขาตั้ง")), nightIndex()) { i ->
            prefs.edit().putInt("night_frames", NIGHT_FRAMES[i]).apply(); if (mode != PhotoMode.NIGHT) { nightOn = true; prefs.edit().putBoolean("night_toggle", true).apply() }; refresh()
        }
    }
    private fun nightProgress(done: Int, total: Int) {
        if (!nightRunning) {
            nightRunning = true; nightCard.visibility = View.VISIBLE; Liquid.show(nightCard, nightCard.width / 2f, nightCard.height / 2f, false)
            macroButton.animate().alpha(0f).start(); nightButton.animate().alpha(0f).start(); zooms.animate().alpha(0f).start()
            refresh() // busy disabled the shutter; while gathering light it becomes the stop button
        }
        shutter.progress = done.toFloat() / total.coerceAtLeast(1)
        nightCount.text = "${((total - done) * 0.18f).roundToInt().coerceAtLeast(1)}"
    }
    private fun endNight() {
        if (!nightRunning) return; nightRunning = false; shutter.progress = -1f; refresh()
        Liquid.hide(nightCard, nightCard.width / 2f, nightCard.height / 2f, false)
        macroButton.animate().alpha(1f).start(); nightButton.animate().alpha(1f).start(); zooms.animate().alpha(1f).start()
    }

    // ---------- Options panel ----------
    private fun openPanel(interactive: Boolean = false) {
        if (panel.showing || editor.showing || busy) return
        menu.dismiss(false); closeDial()
        prefs.edit().putBoolean("panel_hint_seen", true).apply()
        panel.open(interactive) { box, sheet ->
            box.addView(sectionTitle("ถ่ายภาพ"))
            val grid = GridLayout(this).apply { columnCount = 3 }
            fun tile(title: String, icon: String, value: String, on: Boolean, inner: String = "", isMenu: Boolean, act: (View) -> Unit) {
                val t = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = glass(20, if (on) Glass.ACCENT_SOFT else 0x12ffffff); isClickable = true
                    contentDescription = "$title $value"; if (isMenu) pressMenu(menu) { act(it) } else { setOnClickListener { act(it) }; glassTouch() }
                    addView(LinearLayout(this@CameraActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; tag = IconTurner.TAG
                        addView(IconView(this@CameraActivity, icon).apply { tint = if (on) Glass.ACCENT else Glass.TEXT2; this.inner = inner }, dp(24), dp(24))
                        addView(text(title, 12f).apply { gravity = Gravity.CENTER }, -2, -2)
                        addView(text(value + if (isMenu) " ▾" else "", 11.5f, if (on) Glass.ACCENT else Glass.TEXT2, true).apply { gravity = Gravity.CENTER }, -2, -2) }, -2, -2)
                }
                t.minimumHeight = dp(96); t.setPadding(dp(4), dp(8), dp(4), dp(8)); t.post { turnNow() }
                grid.addView(t, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0; height = GridLayout.LayoutParams.WRAP_CONTENT; setMargins(dp(4), dp(4), dp(4), dp(4)) })
            }
            // Changing a tile rebuilds only the tile grid; rebuilding the whole sheet made the style strip below flash.
            var reopen: () -> Unit = {}
            fun fill() {
                grid.removeAllViews()
                val flashNames = listOf("ปิด", "อัตโนมัติ", "เปิด"); val flashModes = listOf(ImageCapture.FLASH_MODE_OFF, ImageCapture.FLASH_MODE_AUTO, ImageCapture.FLASH_MODE_ON)
                tile("แฟลช", if (flash == ImageCapture.FLASH_MODE_OFF) Icons.FLASH_OFF else Icons.FLASH, flashNames[flashModes.indexOf(flash).coerceAtLeast(0)], flash != ImageCapture.FLASH_MODE_OFF, isMenu = true) { v ->
                    menu.show(v, "แฟลช", listOf(GlassMenu.Option("ปิด", Icons.FLASH_OFF), GlassMenu.Option("อัตโนมัติ", Icons.FLASH, "เปิดเมื่อแสงน้อย"), GlassMenu.Option("เปิด", Icons.FLASH)), flashModes.indexOf(flash)) { i -> flash = flashModes[i]; capture?.flashMode = flash; refresh(); reopen() }
                }
                val timers = listOf("0", "3", "10"); val timer = prefs.getString("timer", "0") ?: "0"
                tile("ตั้งเวลา", Icons.TIMER, if (timer == "0") "ปิด" else "$timer วิ", timer != "0", isMenu = true) { v ->
                    menu.show(v, "ตั้งเวลา", listOf(GlassMenu.Option("ปิด", Icons.TIMER), GlassMenu.Option("3 วินาที", Icons.TIMER), GlassMenu.Option("10 วินาที", Icons.TIMER)), timers.indexOf(timer)) { i -> prefs.edit().putString("timer", timers[i]).apply(); refresh(); reopen() }
                }
                val aspects = Aspects.available(prefs); val aspect = prefs.getString("aspect", "4:3") ?: "4:3"
                tile("สัดส่วนภาพ", aspectIcon(aspect), aspect, aspect != "4:3", "", true) { v ->
                    menu.show(v, "สัดส่วนภาพ", aspects.map { GlassMenu.Option(it, aspectIcon(it), Aspects.note(it)) }, aspects.indexOf(aspect)) { i ->
                        if (!busy) { prefs.edit().putString("aspect", aspects[i]).apply(); applyStageShape(); layoutMask(stage.width, stage.height); freezeFrame(); bind() }; reopen()
                    }
                }
                val outs = CaptureOutput.entries.filter { !it.enhance && (!it.needsRaw || rawAvailable) }
                tile("รูปแบบไฟล์", Icons.FILE, output.label, output != CaptureOutput.JPEG, isMenu = true) { v ->
                    menu.show(v, "รูปแบบไฟล์", outs.map { GlassMenu.Option(it.label, if (it.needsRaw) Icons.RAW else Icons.FILE, when (it) { CaptureOutput.JPEG -> "ไฟล์เล็ก เปิดได้ทุกที่"; CaptureOutput.PNG -> "ไม่สูญเสียคุณภาพ ไฟล์ใหญ่"; CaptureOutput.WEBP -> "เล็กกว่า JPEG คุณภาพใกล้เคียง"; CaptureOutput.RAW -> "DNG จากเซนเซอร์"; else -> "DNG · ดูภาพแบบ Log แบนขณะถ่าย" }) }, outs.indexOf(output)) { i -> setOutput(outs[i]); reopen() }
                }
                val crops = listOf("0", "1", "2", "3.5"); val cropNow = prefs.getString("crop_from", null) ?: if (prefs.getBoolean("software", false)) "1" else "0"
                tile("ครอปเลนส์", Icons.SEARCH, if (cropNow == "0") "ปิด" else "${cropNow}×", cropNow != "0", isMenu = true) { v ->
                    menu.show(v, "ครอปจากเลนส์", listOf(GlassMenu.Option("ปิด", Icons.SEARCH, "สลับเลนส์ตามระยะซูม"), GlassMenu.Option("เลนส์หลัก 1×", Icons.SEARCH, "ซูมด้วยการครอปจากเลนส์หลัก"), GlassMenu.Option("เลนส์ 2×", Icons.SEARCH, "ครอปจากระยะ 2×"), GlassMenu.Option("เลนส์เทเล 3.5×", Icons.SEARCH, "ครอปจากระยะ 3.5×")), crops.indexOf(cropNow).coerceAtLeast(0)) { i ->
                        prefs.edit().putString("crop_from", crops[i]).apply(); cropFrom = crops[i].toFloat(); software = cropFrom > 0f; if (!busy) { boundKey = null; applyZoom(ratio) }; reopen()
                    }
                }
                val grid0 = Grids.kind(prefs)
                tile("เส้นกริด", Grids.icons[grid0], Grids.names[grid0], grid0 > 0, isMenu = true) { v ->
                    menu.show(v, "เส้นกริด", Grids.names.mapIndexed { i, n -> GlassMenu.Option(n, Grids.icons[i], Grids.notes[i]) }, grid0) { i -> Grids.set(prefs, i); overlay.gridKind = i; reopen() }
                }
            }
            fill(); reopen = { fill() }
            box.addView(grid, -1, -2)

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
    /** Preview thumbnail source: one small still of the live view, so every look shows real colours. */
    private var lookSampleCache: Bitmap? = null
    private val lookImages = mutableListOf<ImageView>()
    /** A small still of the live view, read before any colour filter, so every thumbnail starts from the camera's own colour. */
    private fun grabLookSample(): Bitmap? = preview.smallFrame(132)
    /** While the sheet is open the thumbnails follow the live view, a few times a second. */
    private val lookTicker = object : Runnable { override fun run() {
        if (!lookSheet.showing || isDestroyed) return
        if (!busy) grabLookSample()?.let { b -> val old = lookSampleCache; lookSampleCache = b; lookImages.forEach { it.setImageBitmap(b) }; if (old !== b) old?.recycle() }
        preview.postDelayed(this, 900)
    } }
    private fun openLookSheet() {
        lookSampleCache = grabLookSample() ?: lookSampleCache
        val film = mode == PhotoMode.FILM
        lookSheet.open { box, sheet ->
            val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(8)) }
            head.addView(text(if (film) "ฟิล์มและกล้องเก่า" else "ปรับแต่งภาพ", 18f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(text("คืนค่า", 12f).apply { gravity = Gravity.CENTER; background = glass(16, 0x1affffff); setPadding(dp(14), 0, dp(14), 0); minWidth = dp(48); isClickable = true; glassTouch()
                setOnClickListener { prefs.edit().putInt("look", 0).putInt("look_bright", 0).putInt("look_warm", 0).remove("film").apply(); applyLook(); sheet.refresh() } }, -2, dp(48))
            box.addView(head)
            val lookBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
            box.addView(lookBox, -1, -2)
            buildLookSection(lookBox)
            // No footer paragraph: the sheet is pictures first.
        }
        preview.removeCallbacks(lookTicker); preview.postDelayed(lookTicker, 400)
    }
    private class LookCell(val frame: View, val label: TextView, val key: String)
    private fun lookThumb(m: ColorMatrix, label: String, key: String, cells: MutableList<LookCell>, onTap: () -> Unit): View {
        val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; isClickable = true; contentDescription = label; setOnClickListener { onTap() }; glassTouch() }
        val frame = FrameLayout(this).apply { setPadding(dp(3), dp(3), dp(3), dp(3)) }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; lookSampleCache?.let { setImageBitmap(it) } ?: setBackgroundColor(0xff34506a.toInt()); colorFilter = ColorMatrixColorFilter(m); clipToOutline = true; outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(15).toFloat()) } } }
        lookImages += img
        frame.addView(img, -1, -1)
        val name = text(label, 11f, Glass.TEXT2).apply { gravity = Gravity.CENTER; maxLines = 2; ellipsize = null; setPadding(0, dp(6), 0, 0) }
        frame.tag = IconTurner.TAG
        cell.addView(frame, dp(80), dp(80)); cell.addView(name, dp(90), -2); cell.post { turnNow() }
        cells += LookCell(frame, name, key)
        return cell
    }
    private fun markLook(cells: List<LookCell>, chosen: String) = cells.forEach { c -> val on = c.key == chosen
        c.frame.background = glass(18, if (on) 0xebffffff.toInt() else 0x14ffffff); c.label.setTextColor(if (on) Color.WHITE else Glass.TEXT2)
        c.label.typeface = if (on) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT }
    /** Photo modes show the plain colour styles; the film mode shows old-camera and film-stock looks. */
    private fun buildLookSection(host: LinearLayout) {
        host.removeAllViews(); lookImages.clear()
        val row = LinearLayout(this).apply { setPadding(dp(4), dp(6), dp(4), dp(6)); clipChildren = false; clipToPadding = false }
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(row, -2, -2) }
        val cells = mutableListOf<LookCell>()
        if (mode == PhotoMode.FILM) {
            val note = text("", 12.5f, Glass.TEXT2).apply { maxLines = 2; ellipsize = null; setPadding(dp(4), dp(2), dp(4), dp(8)) }
            fun current() = Films.byId(prefs.getString("film", null)) ?: Films.all.first()
            fun describe() { note.contentDescription = current().note }
            var group = current().group
            val groups = LinearLayout(this).apply { setPadding(dp(4), dp(4), dp(4), dp(4)); clipChildren = false; clipToPadding = false }
            val gs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(groups, -2, -2) }
            fun fillFilms() {
                row.removeAllViews(); cells.clear(); lookImages.clear(); scroll.scrollTo(0, 0)
                Films.inGroup(group).forEach { f ->
                    row.addView(lookThumb(Films.matrix(f), f.name, f.id, cells) { prefs.edit().putString("film", f.id).apply(); applyLook(); markLook(cells, f.id); describe(); Glass.tick(row) }, LinearLayout.LayoutParams(dp(94), -2))
                }
                markLook(cells, current().id)
            }
            Films.groups.forEach { g ->
                groups.addView(text(g, 12.5f, if (g == group) Color.BLACK else Glass.TEXT2, g == group).apply { gravity = Gravity.CENTER; setPadding(dp(14), 0, dp(14), 0); isClickable = true; background = glass(18, if (g == group) 0xebffffff.toInt() else 0x14ffffff)
                    setOnClickListener { group = g; for (i in 0 until groups.childCount) { val t = groups.getChildAt(i) as TextView; val on = t.text == g; t.setTextColor(if (on) Color.BLACK else Glass.TEXT2); t.background = glass(18, if (on) 0xebffffff.toInt() else 0x14ffffff) }; fillFilms(); Glass.tick(groups) } }, LinearLayout.LayoutParams(-2, dp(44)).apply { rightMargin = dp(8) })
            }
            host.addView(gs, -1, -2)
            fillFilms(); describe()
            host.addView(scroll, -1, -2)
        } else {
            LOOKS.forEachIndexed { i, name -> row.addView(lookThumb(lookMatrix(i, 0, 0), name, "$i", cells) { prefs.edit().putInt("look", i).apply(); applyLook(); markLook(cells, "$i"); Glass.tick(row) }, LinearLayout.LayoutParams(dp(94), -2)) }
            markLook(cells, "${prefs.getInt("look", 0)}")
            host.addView(scroll, -1, -2)
        }
        host.addView(lookSlider("ความสว่าง", Icons.SUN, "look_bright"))
        host.addView(lookSlider("ความอุ่น", Icons.THERMO, "look_warm"))
    }
    private fun sectionTitle(t: String) = text(t, 14f, 0xdbffffff.toInt(), true).apply { setPadding(dp(2), dp(4), 0, dp(8)) }
    /** One compact row: icon, slider, value. The name is only spoken (TalkBack), the icon says it on screen. */
    private fun lookSlider(title: String, icon: String, key: String) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; contentDescription = title; setPadding(dp(2), dp(4), 0, dp(4))
        val value = text("", 13f, Glass.TEXT2, true).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        val values = (-100..100 step 10).map { (if (it > 0) "+" else "") + it }
        val s = GlassSlider(this@CameraActivity).apply { this.values = values; grad = if (key == "look_warm") intArrayOf(0xff7db8ff.toInt(), Color.WHITE, 0xffff9a3d.toInt()) else intArrayOf(0xff3a3a3c.toInt(), Color.WHITE); index = (prefs.getInt(key, 0) + 100) / 10; contentDescription = title }
        value.text = values[s.index]
        s.onChange = { i -> value.text = values[i]; prefs.edit().putInt(key, i * 10 - 100).apply(); applyLook() }
        addView(IconView(this@CameraActivity, icon).apply { tint = Glass.TEXT2 }, dp(22), dp(22))
        addView(s, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) })
        addView(value, dp(44), -2)
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
        val tv = (0 until preview.childCount).map { preview.getChildAt(it) }.firstOrNull { it is TextureView } as? TextureView ?: return
        val active = Films.active(prefs, mode == PhotoMode.FILM)
        tv.setLayerPaint(if (active == null) null else Paint().apply { colorFilter = ColorMatrixColorFilter(active.first) })
    }
    private fun setOutput(choice: CaptureOutput) {
        if (busy) return
        if (choice.needsRaw && !rawAvailable) { notice("กล้องนี้ยังไม่เปิด RAW + JPEG ให้แอป"); return }
        if (choice == output) return
        if (choice.needsRaw || (mode == PhotoMode.ULTRA_HDR && choice != CaptureOutput.JPEG)) mode = PhotoMode.PHOTO
        output = choice
        prefs.edit().putString("output", choice.name).putString("photo_mode", mode.name).apply()
        applyLogView()
        if (choice == CaptureOutput.RAW_LOG && android.os.Build.VERSION.SDK_INT < 33) notice("ภาพพรีวิวแบบ Log ต้องใช้ Android 13 ขึ้นไป · ไฟล์ DNG ยังบันทึกตามปกติ")
        freezeFrame(); bind(); syncModeStrip(true)
    }

    // ---------- Mode bar editor ----------
    private fun openEditor() {
        editor.open { box, sheet ->
            val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(text("ปรับแต่งแถบโหมด", 20f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(text("เสร็จ", 14f, Glass.ON_ACCENT, true).apply { gravity = Gravity.CENTER; background = glass(22, Glass.ACCENT); setPadding(dp(16), 0, dp(16), 0); isClickable = true; glassTouch(); setOnClickListener { sheet.close() } }, -2, dp(48))
            box.addView(head, -1, dp(52))
            // Short and visual: rows show icon + name; the grip, − and + explain themselves (TalkBack gets actions).
            val inBar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = glass(22, 0x08ffffff); setPadding(dp(6), dp(6), dp(6), dp(6)) }
            val more = GridLayout(this).apply { columnCount = 3 }
            fun pitch(): Float = if (inBar.childCount > 1) (inBar.getChildAt(1).top - inBar.getChildAt(0).top).toFloat().coerceAtLeast(dp(40).toFloat()) else dp(60).toFloat()
            fun render() {
                inBar.removeAllViews(); more.removeAllViews(); inBar.clipChildren = false
                val bar = barList().toMutableList()
                bar.forEachIndexed { i, k ->
                    val m = ALL.first { it.key == k }
                    val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(6), 0); background = glass(18, 0x1affffff) }
                    val grip = IconView(this, "M9 6h.01 M15 6h.01 M9 12h.01 M15 12h.01 M9 18h.01 M15 18h.01", 3f).apply { tint = Glass.TEXT2; contentDescription = "ลากเพื่อเรียง${m.title}"; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES }
                    row.addView(grip, dp(52), dp(52)); row.addView(IconView(this, m.icon), dp(22), dp(22))
                    // Dragging near the screen edge must not turn into the system back gesture (which left the app).
                    if (android.os.Build.VERSION.SDK_INT >= 29) grip.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ -> v.systemGestureExclusionRects = listOf(android.graphics.Rect(0, 0, r - l, b - t)) }
                    row.addView(text(m.title, 14f, Color.WHITE, true).apply { setPadding(dp(10), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
                    if (k == "PHOTO") row.addView(IconView(this, Icons.LOCK, 2f).apply { tint = Glass.TEXT3; contentDescription = "ภาพถ่าย ล็อกไว้ในแถบ" }, dp(48), dp(16))
                    else row.addView(glassButton(IconView(this, Icons.MINUS, 2.4f), 48, "นำ${m.title}ออกจากแถบ") { saveBar(bar.filter { it != k }); render() }, dp(48), dp(48))
                    var startY = 0f
                    grip.setOnTouchListener { _, e ->
                        when (e.actionMasked) {
                            MotionEvent.ACTION_DOWN -> { startY = e.rawY; row.elevation = dp(8).toFloat(); row.animate().alpha(0.92f).setDuration(120).start(); grip.parent.requestDisallowInterceptTouchEvent(true) }
                            MotionEvent.ACTION_MOVE -> row.translationY = (e.rawY - startY).coerceIn(-i * pitch(), (bar.size - 1 - i) * pitch())
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                val to = (i + (row.translationY / pitch()).roundToInt()).coerceIn(0, bar.size - 1)
                                grip.parent?.requestDisallowInterceptTouchEvent(false)
                                if (to != i) { bar.removeAt(i); bar.add(to, k); Glass.tick(row) }
                                // Rebuild after this touch has finished: removing the touched view mid-gesture was unsafe.
                                row.post { if (to != i) saveBar(bar); render() }
                            }
                        }
                        true
                    }
                    // TalkBack cannot drag the grip, so each row also offers move up / move down.
                    if (i > 0) androidx.core.view.ViewCompat.addAccessibilityAction(row, "เลื่อน${m.title}ขึ้น") { _, _ -> bar.removeAt(i); bar.add(i - 1, k); saveBar(bar); render(); true }
                    if (i < bar.size - 1) androidx.core.view.ViewCompat.addAccessibilityAction(row, "เลื่อน${m.title}ลง") { _, _ -> bar.removeAt(i); bar.add(i + 1, k); saveBar(bar); render(); true }
                    row.contentDescription = "${m.title} ลำดับ ${i + 1} จาก ${bar.size}"
                    inBar.addView(row, LinearLayout.LayoutParams(-1, dp(52)).apply { setMargins(0, dp(3), 0, dp(3)) })
                }
                ALL.filter { it.key !in bar }.forEach { m ->
                    // Icon tiles: tap adds the mode to the bar, long-press uses it once without adding.
                    val cell = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = glass(20, 0x12ffffff); setPadding(dp(6), dp(12), dp(6), dp(10))
                        isClickable = true; isLongClickable = true; glassTouch(); contentDescription = "เพิ่ม${m.title}ลงแถบ แตะค้างเพื่อใช้ครั้งเดียว"
                        setOnClickListener { Glass.tick(this); saveBar(barList() + m.key); post { render() } }
                        setOnLongClickListener { sheet.close { useTemporary(m.key) }; true }
                    }
                    val iconBox = FrameLayout(this)
                    iconBox.addView(IconView(this, m.icon), FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
                    iconBox.addView(IconView(this, Icons.PLUS, 2.6f).apply { tint = Glass.ACCENT }, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.TOP or Gravity.END))
                    cell.addView(iconBox, dp(44), dp(36))
                    cell.addView(text(m.title, 12f, Glass.TEXT2).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, 0) }, -1, -2)
                    more.addView(cell, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0; height = GridLayout.LayoutParams.WRAP_CONTENT; setMargins(dp(4), dp(4), dp(4), dp(4)) })
                }
            }
            render()
            box.addView(sectionTitle("ในแถบล่าง")); box.addView(inBar, -1, -2)
            box.addView(sectionTitle("โหมดเพิ่มเติม").apply { setPadding(dp(2), dp(16), 0, dp(8)) }); box.addView(more, -1, -2)
        }
    }
    private fun saveBar(list: List<String>) { if (barKey(mode) !in list && ALL.any { it.key == barKey(mode) }) selectMode(PhotoMode.PHOTO); prefs.edit().putString("mode_bar", (if ("PHOTO" in list) list else listOf("PHOTO") + list).distinct().joinToString(",")).apply(); rebuildModeStrip() }
    private fun useTemporary(key: String) {
        val m = ALL.first { it.key == key }
        val pm = runCatching { PhotoMode.valueOf(key) }.getOrNull()
        if (pm != null) { selectMode(pm); hint("ใช้โหมด${m.title} · กด + เพื่อเพิ่มลงแถบถาวร", 1800) }
        else { barKeys = barList() + key; pickBar(barKeys.size - 1) }
    }

    // ---------- Refresh ----------
    private fun refreshZoomPills() {
        val (lo, hi) = range()
        // The front camera gets its own pills (it has no tele lens): 1×, 2×, 5×, then pinch/drag up to 20×.
        val stops = (if (rear) listOfNotNull(lo.takeIf { it < 0.98f }, 1f, 2f, 3.5f) else listOf(1f, 2f, 5f)).filter { it in lo..hi }.distinct()
        if (stops != renderedZooms || zooms.childCount != stops.size) {
            zooms.removeAllViews(); renderedZooms = stops; zooms.post { turnNow() }
            stops.forEach { v ->
                val cell = FrameLayout(this).apply { isClickable = true; isFocusable = true; contentDescription = "ซูม ${zoomText(v)} ลากซ้ายขวาเพื่อซูมละเอียด" }
                cell.addView(text(zoomText(v), 12f, Color.WHITE, true).apply { setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 12f); gravity = Gravity.CENTER; background = GlassDrawable(0x1affffff, dp(20).toFloat(), resources.displayMetrics.density) }, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
                cell.setOnClickListener { if (!zoomLocked()) animateZoom(v) }
                zooms.addView(cell, LinearLayout.LayoutParams(dp(48), dp(48)).apply { setMargins(dp(1), 0, dp(1), 0) })
            }
        }
        refreshZoomUi()
    }
    private fun refresh() {
        if (!::shutter.isInitialized) return
        shutter.isEnabled = capture != null && granted() && !modePending && !busy && !zoomSettling && (!output.needsRaw || rawAvailable) || nightRunning
        shutter.alpha = if (shutter.isEnabled) 1f else 0.45f
        shutter.contentDescription = if (nightRunning) "หยุดรวมแสงและบันทึก" else if (pano != null) "หยุดและต่อภาพพาโนรามา" else "ถ่ายภาพ"
        flip.isEnabled = !busy
        refreshStatus()
        val showSide = !dialing && openChip == null && !nightRunning && capture != null
        val portrait = mode == PhotoMode.PORTRAIT && !activeExtension
        // Live background blur while composing; paused behind sheets and menus, and while a photo is being taken.
        qr?.let { if (mode == PhotoMode.PHOTO && prefs.getBoolean("qr_scan", true) && capture != null && !busy && !lookSheet.showing && !panel.showing && !editor.showing) it.start() else it.stop() }
        portraitLive?.let { if (portrait && prefs.getBoolean("portrait_live", true) && capture != null && !busy && !lookSheet.showing && !panel.showing && !editor.showing) it.start() else it.stop() }
        macroButton.visibility = if (showSide && rear && !portrait && mode != PhotoMode.SPORT && macroLens != null) View.VISIBLE else View.GONE
        apertureButton.visibility = if (showSide && (portrait || mode == PhotoMode.SPORT)) View.VISIBLE else View.GONE
        val f = prefs.getFloat("portrait_f", 2.8f); apertureButton.text = if (mode == PhotoMode.SPORT) "1/" + (1_000_000_000L / SPORT_KINDS[sportIndex()].second) else "f/${fText(f)}"; apertureButton.contentDescription = "เบลอพื้นหลัง f/${fText(f)} แตะเพื่อเปลี่ยน"
        lookButton.visibility = if (showSide && mode != PhotoMode.PANORAMA && prefs.getBoolean("show_look", true)) View.VISIBLE else View.GONE
        nightButton.visibility = View.GONE
        // A switched-on mode stays solid orange (not just a tinted icon), so it reads as on at a glance.
        fun lit(v: FrameLayout, on: Boolean) { (v.background as? GlassDrawable)?.let { if ((it.fill == Glass.ACCENT) != on) { it.fill = if (on) Glass.ACCENT else Glass.FILL; v.invalidate() } }; (v.getChildAt(0) as IconView).tint = if (on) Glass.ON_ACCENT else Color.WHITE }
        lit(macroButton, macro); lit(nightButton, nightActive())
        refreshZoomPills()
        zooms.visibility = if (capture != null && renderedZooms.size > 1 && prefs.getBoolean("show_zoom", true)) View.VISIBLE else View.GONE
        preview.contentDescription = "ช่องมองภาพ กล้อง${if (rear) "หลัง" else "หน้า"} ${zoomText(ratio)} แตะเพื่อโฟกัส ปัดขึ้นลงเพื่อปรับความสว่าง"
        val chipped = mode == PhotoMode.PRO || mode in QUICK_MODES
        if (chipped && (chipFrame.visibility != View.VISIBLE || chipKeys().size != chipTexts.size)) rebuildChips()
        if (!chipped && chipFrame.visibility == View.VISIBLE) { chipFrame.visibility = View.GONE; closeDial() }
        if (chipFrame.visibility == View.VISIBLE) styleChips(chips.selected)
        val dlp = dial.layoutParams as FrameLayout.LayoutParams; val want = if (chipFrame.visibility == View.VISIBLE) dp(78 + 56 * grow()) else dp(70)
        if (dlp.bottomMargin != want) { dlp.bottomMargin = want; dial.layoutParams = dlp }
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
        permissionPanel.addView(label("อนุญาตให้ใช้กล้องเพื่อเริ่มถ่ายภาพ",17f).apply{setPadding(dp(8),dp(6),dp(8),dp(14))})
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
        try {
            fun selector() = if(rear) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
            if(!provider.hasCamera(selector())) rear=!rear
            val baseSelector=selector()
            val extension=when(mode){PhotoMode.HDR->ExtensionMode.HDR;PhotoMode.NIGHT->ExtensionMode.NIGHT;PhotoMode.PORTRAIT->ExtensionMode.BOKEH;else->ExtensionMode.NONE}
            // Maker portrait/night extensions open slowly (or not at all) on some phones and hide the f-number control,
            // so Lumora's own portrait blur and AI night are the default; the maker versions are an opt-in setting.
            val oemAllowed=extension==ExtensionMode.HDR||extension==ExtensionMode.NIGHT||prefs.getBoolean("oem_extensions",false)
            activeExtension=oemAllowed && output==CaptureOutput.JPEG && extension!=ExtensionMode.NONE && extensions?.isExtensionAvailable(baseSelector,extension)==true
            val selected=if(activeExtension)extensions!!.getExtensionEnabledCameraSelector(baseSelector,extension) else baseSelector
            val info=provider.availableCameraInfos.firstOrNull { it in baseSelector.filter(provider.availableCameraInfos) }
                ?: throw IOException("ไม่พบกล้องที่เปิดใช้งานได้")
            runCatching{Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.JPEG)?.let{ val f=Aspects.detect(it); if(f.isNotEmpty()) prefs.edit().putString("native_aspects",f.joinToString(",")).apply() }}
            val formats=captureCaps.getOrPut(info){ImageCapture.getImageCaptureCapabilities(info).supportedOutputFormats.toSet()}
            rawAvailable = formats.contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
            if(output.needsRaw&&!rawAvailable){output=CaptureOutput.JPEG;prefs.edit().putString("output",output.name).apply();notice("กล้องนี้ไม่รองรับ RAW · เปลี่ยนเป็น JPEG แล้ว")}
                        val key=listOf(rear,activeExtension,if(activeExtension)extension else ExtensionMode.NONE,output.needsRaw,
                mode==PhotoMode.HIRES,mode==PhotoMode.SPORT,mode in listOf(PhotoMode.PRO,PhotoMode.HDR,PhotoMode.HIRES,PhotoMode.NIGHT),prefs.getString("aspect","4:3"),prefs.getString("jpeg_quality","95"))
            if(key==boundKey&&capture?.let{provider.isBound(it)}==true){
                // Same stream combination: keep the session, refresh only per-mode state.
                boundPreview?.setSurfaceProvider(preview.surfaceProvider);capture?.targetRotation=targetRotation
                manual=ManualControls(this,camera!!,false,mode==PhotoMode.PRO).also{it.onNotice={t->notice(t)};it.plainColor=output.needsRaw};afterBind()
                capture?.flashMode=if(info.hasFlashUnit())(if(mode==PhotoMode.FLASH_NIGHT)ImageCapture.FLASH_MODE_ON else if(mode==PhotoMode.FLASH_FAST)ImageCapture.FLASH_MODE_OFF else flash) else ImageCapture.FLASH_MODE_OFF
                unfreeze();lastHardwareZoom=-1f;applyZoom(ratio);refresh();return
            }
            val ticket=++binding
            val cc=Camera2CameraInfo.from(info)
            val caps=cc.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val single=Build.VERSION.SDK_INT>=28 && caps!=null && !caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
            val frames=FrameMetadata(single)
            val framing=ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            // Optional frame-rate lock: the viewfinder keeps 24/30/60 fps in dim light instead of the phone slowing it down.
            val lockFps=prefs.getString("fps_lock","off")?.toIntOrNull()
            val previewBuilder=Preview.Builder().setResolutionSelector(framing.build())
            if(lockFps!=null)runCatching{androidx.camera.camera2.interop.Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,android.util.Range(lockFps,lockFps))}
            val previewCase=previewBuilder.build().apply { surfaceProvider=preview.surfaceProvider }
            val builder=ImageCapture.Builder().setCaptureMode(if(mode in listOf(PhotoMode.PRO,PhotoMode.HIRES))ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setJpegQuality(prefs.getString("jpeg_quality","95")!!.toInt())
                .setResolutionSelector(framing.setResolutionStrategy(ResolutionStrategy(Size(4000,3000),ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                .setTargetRotation(targetRotation)
            if(mode==PhotoMode.HIRES)builder.setResolutionSelector(ResolutionSelector.Builder().setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE).setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build())
            // RAW is taken by RawStill (Camera2); the CameraX capture stays JPEG.
            Camera2Interop.Extender(builder).setSessionCaptureCallback(frames)
            val useCase=builder.build()
            camera?.cameraInfo?.zoomState?.removeObservers(this)
            provider.unbindAll()
            val group=UseCaseGroup.Builder().addUseCase(previewCase).addUseCase(useCase)
            val aspect=Aspects.rational(prefs.getString("aspect","4:3")?:"4:3")
            if(aspect!=Rational(4,3)&&!activeExtension)group.setViewPort(ViewPort.Builder(aspect,targetRotation).build())
            android.os.Trace.beginSection("Lumora.bind")
            try{camera=provider.bindToLifecycle(this,selected,group.build())}finally{android.os.Trace.endSection()}
            manual=ManualControls(this,camera!!,false,mode==PhotoMode.PRO).also{it.onNotice={t->notice(t)};it.plainColor=output.needsRaw}
            afterBind()

            boundKey=key;boundPreview=previewCase;capture=useCase;metadata=frames
            useCase.flashMode=if(info.hasFlashUnit()) (if(mode==PhotoMode.FLASH_NIGHT)ImageCapture.FLASH_MODE_ON else if(mode==PhotoMode.FLASH_FAST)ImageCapture.FLASH_MODE_OFF else flash) else ImageCapture.FLASH_MODE_OFF
            val range=camera?.cameraInfo?.zoomState?.value
            ratio=range().let{(lo,hi)->ratio.coerceIn(lo,hi)}
            zoomGoal=ratio;lastHardwareZoom=-1f;zoomSettling=false;applyZoom(ratio)
            preview.postDelayed({ if(ticket==binding&&!isDestroyed&&!busy){lastHardwareZoom=-1f;applyZoom(ratio)} },350)
            val other=if(rear) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            flip.visibility=if(provider.hasCamera(other)) View.VISIBLE else View.INVISIBLE
            camera?.cameraInfo?.zoomState?.observe(this) { if(ticket==binding) refresh() }
            refresh()
        } catch(e:Exception) {cameraError(e.localizedMessage ?: "เปิดกล้องไม่ได้")}
    }
    /** UI state that belongs to one ManualControls instance; reset after every bind or session reuse. */
    private fun afterBind() {
        macro=false;ev=0f;pressLock=false;focusRail.value=0f;focusRail.label="AF";banner.visibility=View.GONE
        if(aeLock)manual?.lock(true)
        if(mode==PhotoMode.PRO||mode in QUICK_MODES)rebuildChips()
        if(mode==PhotoMode.SPORT)applySport()
        if(mode==PhotoMode.HIRES&&!hiResHintShown){hiResHintShown=true
            val current=camera?.let{runCatching{Camera2CameraInfo.from(it.cameraInfo).cameraId}.getOrNull()}
            val full=HiResShot.find(this,current,rear)
            hint(if(full!=null)"เครื่องเปิดโหมดเต็มเซนเซอร์ ${pixelsText(full.second.width.toLong()*full.second.height)} ให้แอป" else "เครื่องนี้ไม่เปิดเซนเซอร์เต็มให้แอปอื่น · ถ่ายขนาดสูงสุดที่เปิดให้ แล้วใช้ AI ขยาย ×2 เป็นราว 50 MP · ให้",3200)}
        preview.post{applyLook()}
        applyLogView()
        camera?.let { cam -> configureFaces(faces, cam, mask); if (rear) findMacroLens(cam) else macroLens = null }
    }
    private fun chooseSport(v: View) {
        menu.show(v, "ประเภทภาพเคลื่อนไหว", SPORT_KINDS.map { GlassMenu.Option(it.first, Icons.FLASH, "ชัตเตอร์ 1/" + (1_000_000_000L / it.second)) }, sportIndex()) { i ->
            prefs.edit().putInt("sport_kind", i).apply(); applySport(); refresh()
        }
    }
    private fun sportIndex() = prefs.getInt("sport_kind", 1).coerceIn(0, SPORT_KINDS.size - 1)
    /** Fast-shot mode: a fixed short shutter with a matching ISO, chosen by the kind of subject. */
    private fun applySport() {
        val m = manual ?: return; val k = SPORT_KINDS[sportIndex()]
        if (m.isoValues.isEmpty()) { hint("กล้องนี้ไม่เปิดชัตเตอร์แมนนวลให้แอป", 2200); return }
        val iso = m.isoValues.minBy { kotlin.math.abs(it - k.third) }
        m.setIso(iso); m.setShutter(k.second)
    }
    private lateinit var faces: FaceOverlay
    /** RAW Log shows the flat Log look on the live preview only; the DNG stays untouched sensor data. */
    private fun applyLogView(){ if(::preview.isInitialized) LogPreview.apply(preview, output==CaptureOutput.RAW_LOG && mode in listOf(PhotoMode.PHOTO,PhotoMode.PRO)) }
    /** A reused session keeps streaming, so the frozen frame must be lifted here instead of on the next STREAMING state. */
    private fun unfreeze() {
        if (frozen.alpha <= 0f) return
        frozen.animate().alpha(0f).setDuration(220).withEndAction { Glass.blur(frozen, 0f); val old = (frozen.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap; frozen.setImageDrawable(null); old?.recycle() }.start()
    }
    /** Highest ratio the camera zooms by itself; beyond it the app crops the preview and AI SR1 enlarges the photo. */
    private fun cropPref(): Float = (prefs.getString("crop_from", null) ?: if (prefs.getBoolean("software", false)) "1" else "0").toFloatOrNull() ?: 0f
    private fun hardwareMax(): Float = if(software && rear) minOf(cropFrom, camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: cropFrom) else camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
    private fun range(): Pair<Float,Float> {
        val z=camera?.cameraInfo?.zoomState?.value
        val lo=if(software && rear && cropFrom<=1f) 1f else z?.minZoomRatio ?: 1f
        return lo to (if(rear) maxOf(SUPER_ZOOM,hardwareMax()) else maxOf(FRONT_ZOOM,z?.maxZoomRatio ?: 1f))
    }
    /** Part of [ratio] the app adds by cropping (1 = none). */
    private fun appCrop(z: Float = ratio): Float = (z / minOf(z, hardwareMax()).coerceAtLeast(0.01f)).coerceAtLeast(1f)
    /**
     * Sets the zoom now. [light] is for every frame of a pinch or drag: only the zoom UI updates, and the full
     * refresh waits for the gesture to settle, so continuous zoom stays at the display's refresh rate.
     */
    private fun applyZoom(requested: Float, light: Boolean = false) {
        val cam=camera ?: return
        if(busy) return
        val (lo,hi)=range()
        ratio=requested.coerceIn(lo,hi)
        val hw=minOf(ratio,hardwareMax())
        val extra=appCrop()
        preview.pivotX=preview.width/2f;preview.pivotY=preview.height/2f
        preview.scaleX=extra; preview.scaleY=extra
        if(abs(hw-lastHardwareZoom)<0.0005f&&light){refreshZoomUi();return}
        // While gliding, send at most one zoom request per camera frame; the last value is always sent.
        val now=android.os.SystemClock.uptimeMillis()
        if(light&&now-lastZoomSent<30&&abs(ratio-zoomGoal)>0.004f){refreshZoomUi();return}
        lastZoomSent=now
        lastHardwareZoom=hw
        val ticket=++zoomTicket
        zoomSettling=true
        val future=cam.cameraControl.setZoomRatio(hw)
        future.addListener({
            if(ticket==zoomTicket && cam===camera && !isDestroyed) {
                zoomSettling=false
                try { future.get() } catch (_:Exception) {}
                if(zoomFollower?.isRunning!=true) refresh()
            }
        },main)
        if(light) refreshZoomUi() else refresh()
        if(extra>1.01f&&!superHint){superHint=true;hint("เกินซูมของกล้อง · ภาพที่บันทึกจะขยายด้วย AI SR1",2200)}
    }
    private var superHint=false
    private var lastHardwareZoom=-1f
    private var zoomGoal=1f
    private var zoomFollower: android.animation.TimeAnimator?=null
    /**
     * Lens-ring zoom: gestures move a goal and the zoom follows it every display frame with a short exponential
     * ease in log space, so pinch steps and lens changes blend instead of jumping.
     */
    private fun zoomTo(goal: Float) {
        val (lo,hi)=range(); zoomGoal=goal.coerceIn(lo,hi)
        zoomAnimation?.cancel()
        if(Glass.reduced(this)){applyZoom(zoomGoal);return}
        if(zoomFollower?.isRunning==true)return
        zoomFollower=android.animation.TimeAnimator().apply{
            setTimeListener{a,_,dt->
                val cur=kotlin.math.ln(ratio.toDouble());val tgt=kotlin.math.ln(zoomGoal.toDouble())
                val k=1-kotlin.math.exp(-dt.coerceAtLeast(1)/95.0)
                val next=cur+(tgt-cur)*k
                if(abs(tgt-next)<0.002){applyZoom(zoomGoal,true);a.end();refresh()}
                else applyZoom(kotlin.math.exp(next).toFloat(),true)
            }
            start()
        }
    }
    private fun animateZoom(target: Float) {
        zoomAnimation?.cancel()
        if (prefs.getBoolean("reduce_motion", false)) {
            applyZoom(target)
            return
        }
        val start = kotlin.math.ln(ratio.toDouble()).toFloat()
        val end = kotlin.math.ln(target.toDouble()).toFloat()
        zoomFollower?.end(); zoomGoal=target
        zoomAnimation = ValueAnimator.ofFloat(start, end).apply {
            duration = when(prefs.getString("zoom_speed","normal")){"slow"->700;"fast"->220;else->420}
            interpolator = Glass.EASE
            addUpdateListener { applyZoom(kotlin.math.exp((it.animatedValue as Float).toDouble()).toFloat(), it.animatedFraction < 1f) }
            start()
        }
    }

    // ---------- Capture (logic unchanged except night and shutter feedback) ----------
    private fun captureFeedback() {
        if (isDestroyed) return
        lastCaptureAt = android.os.SystemClock.elapsedRealtime()
        Sounds.play(this, Sounds.Kind.SHUTTER)
        flashCapture()
    }

    /** One soft flash with a brief blur on the viewfinder, so every mode shows that a picture was taken. */
    private fun flashCapture() {
        if (!::mask.isInitialized) return
        val f = View(this).apply { setBackgroundColor(Color.WHITE); alpha = 0f; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        mask.addView(f, -1, -1)
        if (Glass.reduced(this)) { f.alpha = 0.2f; f.postDelayed({ mask.removeView(f) }, 90); return }
        f.animate().alpha(0.32f).setDuration(60).withEndAction { f.animate().alpha(0f).setDuration(220).withEndAction { mask.removeView(f) }.start() }.start()
        Glass.blurPulse(preview, 10f, 280)
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
        val count = if (kind.enhance) burstCount(if (night) prefs.getInt("night_frames", 6).coerceAtMost(12) else 8) else 1
        if (night) nightProgress(0, count) // the stop button works from the first moment, not after the 3A lock
        burst = BurstCapture(
            applicationContext, useCase, kind,
            count,
            rear, if (appCrop() > 1.001f) appCrop() else ratio, appCrop() > 1.001f, targetRotation,
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

    private var rebindRetry=false
    private fun takePhoto() {
        if(modePending)return
        // A session that was closed behind our back (lens switch, format change) would fail with "Camera is closed".
        val cap=capture;val prov=provider
        if(cap!=null&&prov!=null&&!busy&&!prov.isBound(cap)&&!rebindRetry){rebindRetry=true;boundKey=null;bind();shutter.postDelayed({rebindRetry=false;takePhoto()},700);return}
        if(nightRunning){burst?.cancel();hint("กำลังบันทึกเฟรมที่ได้แล้ว",1500);return}
        if(busy||timerRunning||illuminating||zoomSettling)return
        if(mode==PhotoMode.PANORAMA){if(pano?.running==true)endSweep(true) else startSweep();return}
        shutter.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).withEndAction{shutter.animate().scaleX(1f).scaleY(1f).setInterpolator(Glass.SPRING).setDuration(260).start()}.start()
        val seconds=prefs.getString("timer","0")?.toIntOrNull()?:0
        if(seconds<=0){captureWithLight();return}
        timerRunning=true;val token=++timerGeneration
        fun tick(left:Int){
            if(token!=timerGeneration)return
            if(left==0){timerRunning=false;captureWithLight();return}
            hint("$left",1100);if(prefs.getBoolean("countdown_sound",true)&&Sounds.enabled(prefs))tones.startTone(ToneGenerator.TONE_PROP_BEEP,80)
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
        if((mode==PhotoMode.FLASH_NIGHT||mode==PhotoMode.FLASH_FAST)&&camera?.cameraInfo?.hasFlashUnit()!=true){hint("กล้องนี้ไม่มีแฟลช",1600);return}
        if(busy || zoomSettling) return
        // Fast flash: the torch comes on for a blink so exposure settles on the lit scene, the picture is taken at once
        // (no pre-flash sequence), and the light goes off again.
        if(mode==PhotoMode.FLASH_FAST&&!torchLit){
            torchLit=true;camera?.cameraControl?.enableTorch(true)
            shutter.postDelayed({ if(!isDestroyed){captureNow();shutter.postDelayed({camera?.cameraControl?.enableTorch(false);torchLit=false},700)} },170)
            return
        }
        // Full-sensor picture (50 MP class) when the phone declares that sensor mode to apps.
        if(mode==PhotoMode.HIRES&&!hiResFailed&&output==CaptureOutput.JPEG){
            val current=camera?.let{runCatching{Camera2CameraInfo.from(it.cameraInfo).cameraId}.getOrNull()}
            HiResShot.find(this,current,rear)?.let{(id,size)->if(size.width.toLong()*size.height>20_000_000L){takeHiRes(id,size);return}}
        }
        // RAW goes through Camera2: CameraX's RAW+JPEG fails on this phone with "unsupported format".
        if (output.needsRaw) {
            val current=camera?.let{runCatching{Camera2CameraInfo.from(it.cameraInfo).cameraId}.getOrNull()}
            if (current != null) { takeRaw(current); return }
        }
        if (output.needsRaw || output.enhance || (nightOn && mode == PhotoMode.PHOTO) || (mode == PhotoMode.NIGHT && !activeExtension)) {
            takeBurst()
            return
        }
        busy=true;refresh()
        val isRear=rear;val zoom=ratio;val extra=appCrop();val useSoftware=extra>1.001f;val cropBy=extra
        val allowAuto=false;val outFormat=output;val frames=metadata;val photoMode=mode;val nativeEffect=activeExtension
        val aperture=prefs.getFloat("portrait_f",2.8f)
        val camId=camera?.let{runCatching{Camera2CameraInfo.from(it.cameraInfo).cameraId}.getOrNull()};val hwZoom=minOf(zoom,hardwareMax())
        val time=System.currentTimeMillis();val id=UUID.randomUUID().toString()
        useCase.targetRotation=targetRotation
        val callback=object:ImageCapture.OnImageCapturedCallback(){
            override fun onCaptureStarted() { runOnUiThread { captureFeedback() } }
            override fun onCaptureSuccess(image:ImageProxy) {
                runOnUiThread { manual?.longExposure(false) }
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
                    val original=if(useSoftware && cropBy>1f) { PhotoMedia.crop(full,cropBy,cut);ExifCarry.copy(full,cut);cut } else full
                    val date=SimpleDateFormat("yyyy:MM:dd HH:mm:ss",Locale.US).format(Date(time))
                    PhotoMedia.tag(original,rot,date)
                    if(photoMode==PhotoMode.PORTRAIT&&!nativeEffect){
                        val reference=PhotoMedia.publish(this@CameraActivity,original,"Lumora_PORTRAIT_ORIGINAL_${time}.jpg",time)
                        MediaLedger.add(this@CameraActivity,reference,"Portrait ต้นฉบับ","image/jpeg")
                        PhotoEffects.portrait(original,aperture)
                    }
                    if(photoMode!=PhotoMode.PANORAMA)PhotoEffects.decorate(this@CameraActivity,original,isRear)
                    val size=PhotoMedia.dimensions(original)
                    if(camId!=null)LensMeta.stamp(original,LensMeta.describe(this@CameraActivity,camId,frame?.lens,zoom,hwZoom,isRear))
                    if(photoMode==PhotoMode.HIRES)runOnUiThread{hint("บันทึก ${size.w}×${size.h} · ${pixelsText(size.pixels)}${if(size.pixels<20_000_000L)" · เครื่องเปิดให้แอปได้เท่านี้" else ""}",2600)}
                    val crop=if(useSoftware && cropBy>1f) size.pixels else frame?.crop
                    val actualZoom=if(!useSoftware && frame?.ratio!=null && frame.ratio!=1f) frame.ratio else zoom
                    val reason=decide(allowAuto,isRear,actualZoom,size.pixels,crop)
                    val uri=if(outFormat==CaptureOutput.PNG||outFormat==CaptureOutput.WEBP){
                        // PNG/WebP encoding takes seconds on a 12 MP frame; the shutter is free again while it runs.
                        runOnUiThread{if(busy){busy=false;refresh();hint(if(outFormat==CaptureOutput.PNG)"กำลังบันทึก PNG…" else "กำลังบันทึก WebP…",1200)}}
                        val converted=PhotoEffects.convert(original,outFormat,prefs.getString("jpeg_quality","95")!!.toInt())
                        try{PhotoMedia.publish(this@CameraActivity,converted,"Lumora_${time}_${id}.${if(outFormat==CaptureOutput.PNG)"png" else "webp"}",time,if(outFormat==CaptureOutput.PNG)"image/png" else "image/webp")}finally{converted.delete()}
                    }else PhotoMedia.publish(this@CameraActivity,original,"Lumora_${time}_${id}.jpg",time)
                    // The phone gives apps only ~12 MP, so the 50 MP mode takes that picture and lets the on-device AI enlarge it 2×.
                    val hiAi=photoMode==PhotoMode.HIRES&&size.pixels<20_000_000L&&prefs.getBoolean("hires_ai",true)&&runCatching{th.ogay.luma.ai.EnhanceSpec(2,60).output(size)}.isSuccess
                    val stage=if(reason.startsAi||hiAi) Stage.QUEUED else Stage.ORIGINAL
                    luma.shots.insert(Shot(id,time,uri,size,actualZoom,isRear,frame?.lens,crop,reason,stage,null,0,null,useSoftware))
                    if(hiAi)luma.shots.queueEnhancement(id,th.ogay.luma.ai.EnhanceSpec(2,60))
                    if(photoMode==PhotoMode.PANORAMA){
                        val saved=File(filesDir,"panorama/${UUID.randomUUID()}.jpg");saved.parentFile?.mkdirs();original.copyTo(saved,true);runOnUiThread{panoFiles.add(saved);refresh()}
                    }
                    if(hiAi)EnhanceWorker.enqueue(applicationContext,id,true) else if(reason.startsAi) EnhanceWorker.enqueue(applicationContext,id)
                    if(photoMode==PhotoMode.DOCUMENT)runOnUiThread{startActivity(Intent(this@CameraActivity,DocumentActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}
                    complete(if(hiAi) "บันทึกต้นฉบับแล้ว · กำลังขยายเป็นราว 50 MP ด้วย AI ดูความคืบหน้าในแกลเลอรี" else if(reason.startsAi) "บันทึกต้นฉบับแล้ว · เข้าคิว AI" else "บันทึกรูปแล้ว")
                } catch(_:OutOfMemoryError) {complete("หน่วยความจำไม่พอ ลองใช้โหมดซูมด้วยระบบกล้อง")}
                catch(e:Exception) {complete("บันทึกรูปไม่สำเร็จ · ${e.localizedMessage}")}
                finally {full.delete();cut.delete()}
            }
            override fun onError(exception:ImageCaptureException) {runOnUiThread { manual?.longExposure(false) };complete("ถ่ายภาพไม่สำเร็จ · ${exception.localizedMessage}")}
        }
        // Shutter slower than the live view allows: switch the sensor to the real time just for this picture.
        val m=manual
        if(m!=null&&m.longExposure(true)){hint("เปิดชัตเตอร์ ${m.shutterLabel} · ถือนิ่ง",1500);shutter.postDelayed({if(!isDestroyed)useCase.takePicture(luma.fileIo,callback)},m.settleMs)}
        else useCase.takePicture(luma.fileIo,callback)
    }
    private var torchLit=false
    private var hiResHintShown=false
    private var hiResFailed=false
    /** One full-sensor JPEG through Camera2 (see [HiResShot]); CameraX is released for the shot and bound again afterwards. */
    private fun takeHiRes(cameraId:String,size:android.util.Size){
        busy=true;refresh();captureFeedback()
        hint("กำลังถ่ายเต็มเซนเซอร์ ${pixelsText(size.width.toLong()*size.height)} · ถือนิ่ง",4000)
        val time=System.currentTimeMillis();val id=UUID.randomUUID().toString();val isRear=rear;val zoom=ratio;val rotation=targetRotation
        val file=File(cacheDir,"hires-$id.jpg")
        freezeFrame();provider?.unbindAll();capture=null;boundKey=null
        HiResShot.capture(applicationContext,cameraId,size,rotation,isRear,file){error->
            luma.fileIo.execute{
                var message="บันทึกรูปแล้ว"
                try{
                    if(error!=null)throw error
                    val dims=PhotoMedia.dimensions(file)
                    val uri=PhotoMedia.publish(this,file,"Lumora_${time}_${id}.jpg",time)
                    luma.shots.insert(Shot(id,time,uri,dims,zoom,isRear,null,null,decide(false,isRear,zoom,dims.pixels,null),Stage.ORIGINAL,null,0,null,false))
                    message="บันทึกเต็มเซนเซอร์ ${dims.w}×${dims.h} · ${pixelsText(dims.pixels)}"
                }catch(e:Throwable){hiResFailed=true;message="ถ่ายเต็มเซนเซอร์ไม่สำเร็จ · ${e.localizedMessage} · ครั้งต่อไปใช้ขนาดปกติ"}
                finally{file.delete()}
                runOnUiThread{pendingBind=true};complete(message)
            }
        }
    }
    private fun takeRaw(cameraId:String){
        busy=true;refresh();captureFeedback()
        val time=System.currentTimeMillis();val id=UUID.randomUUID().toString();val isRear=rear;val zoom=ratio;val rotation=targetRotation
        val dng=File(cacheDir,"raw-$id.dng");val jpg=File(cacheDir,"raw-$id.jpg")
        val lens=LensMeta.describe(this,cameraId,null,zoom,minOf(zoom,hardwareMax()),isRear)
        freezeFrame();provider?.unbindAll();capture=null;boundKey=null
        RawStill.capture(applicationContext,cameraId,rotation,isRear,minOf(zoom,hardwareMax()),dng,jpg){error->
            luma.fileIo.execute{
                var message=if(prefs.getBoolean("raw_jpeg",true))"บันทึก RAW + JPEG แล้ว" else "บันทึก RAW (DNG) แล้ว"
                try{
                    if(error!=null)throw error
                    val dngUri=PhotoMedia.publish(this,dng,"Lumora_${time}_${id}.dng",time,"image/x-adobe-dng")
                    val withJpeg=prefs.getBoolean("raw_jpeg",true)
                    if(withJpeg){LensMeta.stamp(jpg,lens);PhotoEffects.decorate(this,jpg,isRear)}
                    val dims=PhotoMedia.dimensions(jpg)
                    // RAW only: the DNG itself is the shot; the JPEG was only taken to finish the capture and is dropped.
                    val uri=if(withJpeg)PhotoMedia.publish(this,jpg,"Lumora_${time}_${id}.jpg",time) else dngUri
                    luma.shots.insert(Shot(id,time,uri,dims,zoom,isRear,cameraId,null,Decision.OFF,Stage.ORIGINAL,null,0,null,false))
                }catch(e:Throwable){message="ถ่าย RAW ไม่สำเร็จ · ${e.localizedMessage}"}
                finally{dng.delete();jpg.delete()}
                runOnUiThread{pendingBind=true};complete(message)
            }
        }
    }
    private fun complete(msg:String)=runOnUiThread {
        busy=false
        if(!isDestroyed) {
            endNight()
            if(pendingBind) {pendingBind=false;bind()}
            refresh();hint(msg,5000)
        }
    }
    private fun cancelModeSwitch(){modeHandler.removeCallbacks(switchMode);modePending=false}
    private fun selectMode(next:PhotoMode){
        if (pano != null) endSweep(false)
        if(busy||timerRunning||next==mode)return
        if(next==PhotoMode.MORE){openEditor();return}
        if(next==PhotoMode.VIDEO){openVideo("VIDEO");return}
        if(panoFiles.isNotEmpty()&&next!=PhotoMode.PANORAMA){notice("ต่อภาพพาโนรามาหรือกลับมาถ่ายต่อได้ในโหมดพาโนรามา")}
        mode=next
        if(next!=PhotoMode.PHOTO&&next!=PhotoMode.PRO){output=CaptureOutput.JPEG;prefs.edit().putString("output",output.name).apply()}
        prefs.edit().putString("photo_mode",next.name).apply()
        if(next!=PhotoMode.PRO&&next !in QUICK_MODES){chipFrame.visibility=View.GONE;closeDial()}
        if(next.name !in barKeys) rebuildModeStrip() else syncModeStrip(true)
        // Rapid swipes coalesce: only the last mode within 70 ms binds.
        if(!modePending)freezeFrame()
        modePending=true;refresh();modeHandler.removeCallbacks(switchMode);modeHandler.postDelayed(switchMode,70)
    }
    @Suppress("DEPRECATION")
    private fun openVideo(kind:String){cancelModeSwitch();freezeFrame();provider?.unbindAll();capture=null;startActivity(Intent(this,VideoActivity::class.java).putExtra("mode",kind));overridePendingTransition(0,0)}
    @Suppress("DEPRECATION") private fun openSpecial(kind:String){cancelModeSwitch();freezeFrame();provider?.unbindAll();capture=null;startActivity(Intent(this,SpecialVideoActivity::class.java).putExtra("mode",kind));overridePendingTransition(0,0)}
    /** Settings that only change the screen or the saved file: switching them must not restart the camera. */
    private val screenOnlyKeys=setOf("volume_action","zoom_speed","tap_shutter","grid","grid_kind","level","sound_all","sound","sound_level","video_sound","countdown_sound","focus_sound","click_sound","haptic","reduce_motion","watermark","watermark_name","folder","gps","timer")
    private fun cameraSignature()=prefs.all.filterKeys{it !in screenOnlyKeys}.toSortedMap().toString()
    private var settingsSignature=""
    // Applied once the switch animation has finished, not on every tap, so the settings sheet stays smooth.
    private val applySettings=Runnable{applyAllSettings()}
    private fun showAllSettings(){settingsSignature=cameraSignature();showStudioSettings(rawAvailable){host.removeCallbacks(applySettings);host.postDelayed(applySettings,420)}}
    private fun applyAllSettings(){if(isDestroyed)return
        val rebind=cameraSignature()!=settingsSignature;settingsSignature=cameraSignature()
        overlay.gridKind=Grids.kind(prefs)
        if(prefs.getBoolean("gps",false)&&ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED)locationRequest.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION))
        if(!rebind){overlay.invalidate();refresh();return}
        run{auto=false;cropFrom=cropPref();software=cropFrom>0f;boundKey=null;overlay.gridKind=Grids.kind(prefs)
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
    private fun publishSpecial(file:File,note:String,used:Int=1,requested:Int=used){val id=UUID.randomUUID().toString();val time=System.currentTimeMillis();val uri=PhotoMedia.publish(this,file,"Lumora_${time}_${id}.jpg",time);luma.shots.insert(Shot(id,time,uri,PhotoMedia.dimensions(file),ratio,rear,null,null,Decision.OFF,Stage.ORIGINAL,null,0,null,false,frames=used,requestedFrames=requested,fusedFrames=used,captureNote=note))}
    private fun takeSequence(){val useCase=capture?:return;busy=true;refresh();SequentialPhotos(this,useCase,rear,ratio,{n->hint("ถ่ายต่อเนื่อง $n/10",3000)},{message->complete(message)},started={captureFeedback()}).start()}
    private var pano: PanoramaSweep? = null
    private var panoGuide: PanoGuide? = null
    /** One tap starts the sweep; frames come from the live view as the phone turns, a second tap stitches. */
    private fun startSweep() {
        panoFiles.forEach { it.delete() }; panoFiles.clear()
        val guide = PanoGuide(this); panoGuide = guide
        stage.addView(guide, FrameLayout.LayoutParams(-1, dp(80), Gravity.CENTER))
        hint("หมุนกล้องช้า ๆ ไปทางขวา · แตะชัตเตอร์อีกครั้งเพื่อจบ", 4000)
        shutter.contentDescription = "หยุดและต่อภาพพาโนรามา"
        pano = PanoramaSweep(this, { n ->
            val frame = runCatching { preview.bitmap }.getOrNull() ?: return@PanoramaSweep
            Sounds.play(this, Sounds.Kind.DETENT)
            val f = File(filesDir, "panorama/sweep-$n.jpg"); f.parentFile?.mkdirs()
            luma.fileIo.execute { runCatching { java.io.FileOutputStream(f).use { frame.compress(Bitmap.CompressFormat.JPEG, 94, it) }; frame.recycle(); runOnUiThread { panoFiles.add(f) } } }
        }, { prog, tilt -> guide.progress = prog; guide.tilt = tilt }, { endSweep(true) }).also { it.start() }
    }
    private fun endSweep(stitch: Boolean) {
        pano?.stop(); pano = null
        panoGuide?.let { stage.removeView(it) }; panoGuide = null
        shutter.contentDescription = "ถ่ายภาพ"
        // Let the last frame finish writing before stitching.
        if (stitch) shutter.postDelayed({ finishPanorama() }, 350) else { panoFiles.forEach { it.delete() }; panoFiles.clear() }
    }
    private fun finishPanorama(){if(busy)return;if(panoFiles.size<2){notice("ถ่ายอย่างน้อย 2 ภาพ โดยแต่ละภาพซ้อนทับกันประมาณครึ่งหนึ่ง");return};busy=true;refresh();hint("กำลังจับคู่และต่อภาพ…",120000)
        val input=panoFiles.toList();luma.fileIo.execute{runCatching{val result=PanoramaEngine.stitch(this,input);publishSpecial(result,"Panorama · ORB / homography");result.delete()}.onSuccess{input.forEach{it.delete()};runOnUiThread{panoFiles.clear();complete("บันทึกพาโนรามาแล้ว")}}.onFailure{complete("ต่อภาพไม่ได้ · ${it.localizedMessage} · เฟรมต้นฉบับยังอยู่")}}
    }
    override fun onKeyDown(code:Int,event:KeyEvent):Boolean{if(code==KeyEvent.KEYCODE_VOLUME_UP||code==KeyEvent.KEYCODE_VOLUME_DOWN){when(prefs.getString("volume_action","shutter")){"system"->return super.onKeyDown(code,event);"zoom"->{if(!busy&&!zoomLocked())applyZoom(ratio*if(code==KeyEvent.KEYCODE_VOLUME_UP)1.1f else 0.9f)};else->if(event.repeatCount==0)takePhoto()};return true};return super.onKeyDown(code,event)}
    private var lastCaptureAt = 0L
    /** After a capture the new picture shrinks from the viewfinder into the gallery button. */
    private fun flyToThumb(bitmap: android.graphics.Bitmap) {
        if (!::mask.isInitialized || mask.width == 0 || thumb.width == 0) { thumb.setImageBitmap(bitmap); return }
        val hostAt = IntArray(2).also { host.getLocationInWindow(it) }
        val from = IntArray(2).also { mask.getLocationInWindow(it) }; val to = IntArray(2).also { thumb.getLocationInWindow(it) }
        val w = (mask.width * 0.52f).toInt(); val h = (w * bitmap.height / bitmap.width.toFloat()).toInt().coerceAtMost((mask.height * 0.8f).toInt())
        val ghost = ImageView(this).apply {
            setImageBitmap(bitmap); scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = glass(18)
            elevation = dp(10).toFloat(); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; pivotX = 0f; pivotY = 0f
            x = (from[0] - hostAt[0] + (mask.width - w) / 2).toFloat(); y = (from[1] - hostAt[1] + (mask.height - h) / 2).toFloat(); alpha = 0f
        }
        host.addView(ghost, FrameLayout.LayoutParams(w, h))
        val tx = (to[0] - hostAt[0]).toFloat(); val ty = (to[1] - hostAt[1]).toFloat()
        ghost.animate().alpha(1f).setDuration(90).withEndAction {
            ghost.animate().x(tx).y(ty).scaleX(thumb.width / w.toFloat()).scaleY(thumb.height / h.toFloat()).setDuration(420).setInterpolator(Glass.EASE).withEndAction {
                thumb.setImageBitmap(bitmap); thumb.scaleX = 0.86f; thumb.scaleY = 0.86f
                thumb.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
                ghost.animate().alpha(0f).setDuration(110).withEndAction { host.removeView(ghost) }.start()
            }.start()
        }.start()
    }
    private fun loadThumb() {
        luma.fileIo.execute {
            val uri=luma.shots.latest()?.uri ?: return@execute
            if(uri==lastThumbUri) return@execute
            val fly=lastThumbUri!=null&&android.os.SystemClock.elapsedRealtime()-lastCaptureAt<20_000&&!Glass.reduced(this)
            val bitmap=PhotoMedia.thumbnail(this,uri,if(fly)480 else 160)
            runOnUiThread { if(!isDestroyed && bitmap!=null) {lastThumbUri=uri;if(fly)flyToThumb(bitmap) else thumb.setImageBitmap(bitmap);thumb.visibility=View.VISIBLE} }
        }
    }
}

