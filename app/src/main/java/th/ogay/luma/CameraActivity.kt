package th.ogay.luma

import android.Manifest
import android.animation.ValueAnimator
import android.media.MediaActionSound
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.ImageFormat
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
import android.os.Handler
import android.os.Looper
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
import kotlin.math.roundToInt

@ExperimentalCamera2Interop
class CameraActivity : ComponentActivity() {
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
    private lateinit var aux: TextView
    private lateinit var ruler: ZoomRuler
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
    private lateinit var outputButtons: LinearLayout
    private lateinit var overlay: CompositionOverlay
    private lateinit var zoomSlider: SeekBar
    private var flash = ImageCapture.FLASH_MODE_OFF
    private var busy = false
    private var zoomSettling = false
    private var zoomTicket = 0
    private var binding = 0
    private var pendingBind = false
    private var targetRotation = Surface.ROTATION_0
    private var lastThumbUri: Uri? = null
    private lateinit var preview: PreviewView
    private lateinit var mask: FrameLayout
    private lateinit var permissionPanel: LinearLayout
    private lateinit var viewfinderNotice: TextView
    private lateinit var zooms: LinearLayout
    private lateinit var shutter: Glyph
    private lateinit var flip: Glyph
    private lateinit var thumb: ImageView
    private lateinit var flashButton: TextView
    private lateinit var aiButton: TextView
    private lateinit var currentZoom: TextView
    private lateinit var orientation: OrientationEventListener
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
        if (granted() && provider != null && capture == null) bind()
        if (!granted()) showPermission()
    }
    override fun onPause() {
        orientation.disable()
        zoomAnimation?.cancel()
        burst?.cancel()
        hdr?.cancel()
        timerGeneration++;timerRunning=false
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

    private fun buildScreen() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        installRoot(root)
        val head = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16),0,dp(10),0)
        }
        root.addView(head,-1,dp(60))
        head.addView(pill(prefs.getString("aspect","4:3")!!) {
            if(!busy){val values=listOf("4:3","1:1","16:9");val next=values[(values.indexOf(prefs.getString("aspect","4:3"))+1)%3];prefs.edit().putString("aspect",next).apply();buildScreen();bind()}
        },-2,dp(48))
        head.addView(pill("HDR") { if(!busy)selectMode(if(mode==PhotoMode.HDR)PhotoMode.PHOTO else PhotoMode.HDR) }.apply{setTextColor(if(mode==PhotoMode.HDR)LIME else WHITE)},-2,dp(48))
        head.space()
        aiButton = pill("", ::toggleAi)
        head.addView(aiButton,-2,dp(44))
        head.addView(View(this),dp(6),1)
        flashButton = pill("",::toggleFlash)
        head.addView(flashButton,-2,dp(44))
        head.addView(Glyph(this,"menu","การตั้งค่ากล้อง",::showOptions),dp(48),dp(48))
        outputButtons = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val outputScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            addView(outputButtons)
        }
        root.addView(outputScroll, -1, dp(46))
        for (choice in CaptureOutput.entries) {
            val button = pill(choice.label) {
                if (!busy) {
                    if (choice.needsRaw && !rawAvailable) notice("กล้องนี้ยังไม่เปิด RAW + JPEG ให้แอป")
                    else if (choice != output) {
                        if(choice.needsRaw || (mode==PhotoMode.ULTRA_HDR && choice!=CaptureOutput.JPEG))mode=PhotoMode.PHOTO
                        output = choice
                        prefs.edit().putString("output", choice.name).apply()
                        buildScreen();bind()
                    }
                }
            }.apply { contentDescription = "รูปแบบ ${choice.label}" }
            outputButtons.addView(button, LinearLayout.LayoutParams(-2, dp(44)).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        val body = LinearLayout(this).apply { orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        root.addView(body,LinearLayout.LayoutParams(-1,0,1f))
        val stage = FrameLayout(this).apply { setBackgroundColor(0xff080a08.toInt()) }
        body.addView(stage,if (landscape) LinearLayout.LayoutParams(0,-1,1f) else LinearLayout.LayoutParams(-1,0,1f))
        mask = FrameLayout(this).apply { clipChildren = true; clipToPadding = true }
        stage.addView(mask,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
        stage.addOnLayoutChangeListener { _,left,top,right,bottom,_,_,_,_ ->
            val format=prefs.getString("aspect","4:3")
            val aspect=when(format){"1:1"->1f;"16:9"->if(landscape)16f/9 else 9f/16;else->if (landscape) 4f/3 else 3f/4}
            val w=minOf(right-left, ((bottom-top)*aspect).roundToInt())
            val h=(w/aspect).roundToInt()
            if(mask.layoutParams.width!=w || mask.layoutParams.height!=h) mask.layoutParams=FrameLayout.LayoutParams(w,h,Gravity.CENTER)
        }
        preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FIT_CENTER
        }
        mask.addView(preview,-1,-1)
        overlay = CompositionOverlay(this).apply { grid = prefs.getBoolean("grid", false) }
        mask.addView(overlay, -1, -1)
        overlay.monitor(preview)
        currentZoom = label("",14f,WHITE).apply {
            gravity=Gravity.CENTER; setPadding(dp(9),0,dp(9),0)
            background=shape(0xbb1c231c.toInt(),dp(12).toFloat())
        }
        stage.addView(currentZoom,FrameLayout.LayoutParams(-2,dp(32),Gravity.TOP or Gravity.START).apply { setMargins(dp(14),dp(14),0,0) })
        zooms=LinearLayout(this).apply { gravity=Gravity.CENTER; background=shape(0xcc1c231c.toInt(),dp(24).toFloat()) }
        zoomSlider = SeekBar(this).apply {
            max = 1000
            visibility = View.GONE
            contentDescription = "เลื่อนซูม"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) { zoomAnimation?.cancel() }
                override fun onStopTrackingTouch(bar: SeekBar) {}
                override fun onProgressChanged(bar: SeekBar, value: Int, user: Boolean) {
                    if (user) {
                        val (lo, hi) = range()
                        applyZoom((lo * kotlin.math.exp(kotlin.math.ln((hi / lo).toDouble()) * value / 1000)).toFloat())
                    }
                }
            })
        }
        stage.addView(zoomSlider, FrameLayout.LayoutParams(-1, dp(48), Gravity.BOTTOM).apply {
            bottomMargin = dp(62)
            leftMargin = dp(26)
            rightMargin = dp(26)
        })
        ruler=ZoomRuler(this){zoomAnimation?.cancel();applyZoom(it)}.apply{visibility=View.GONE}
        stage.addView(ruler,FrameLayout.LayoutParams(-1,dp(58),Gravity.BOTTOM).apply{bottomMargin=dp(62);leftMargin=dp(22);rightMargin=dp(22)})
        currentZoom.setOnLongClickListener { ruler.visibility = if (ruler.visibility == View.VISIBLE) View.GONE else View.VISIBLE; true }
        stage.addView(zooms,FrameLayout.LayoutParams(-2,dp(44),Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin=dp(12) })
        viewfinderNotice = label("",14f).apply { gravity=Gravity.CENTER; visibility=View.GONE; setPadding(dp(12),dp(8),dp(12),dp(8)); background=shape(PANEL) }
        viewfinderNotice.setOnClickListener { burst?.cancel() }
        stage.addView(viewfinderNotice,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
        permissionPanel = LinearLayout(this).apply {
            gravity=Gravity.CENTER; orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(14),dp(18),dp(14)); background=shape(BG)
            visibility=View.GONE
        }
        stage.addView(permissionPanel,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
        aux=pill(if(mode==PhotoMode.PANORAMA)"ต่อภาพ (${panoFiles.size})" else "ISO · S · WB · AF · EV") {
            if(mode==PhotoMode.PANORAMA)finishPanorama() else manual?.show()
        }.apply{visibility=if(mode in listOf(PhotoMode.PRO,PhotoMode.PANORAMA))View.VISIBLE else View.GONE}
        if(!landscape){body.addView(aux,-1,dp(44));body.addView(modeStrip(mode,::selectMode),-1,dp(52))}
        else root.addView(modeStrip(mode,::selectMode),-1,dp(52))
        val actions = LinearLayout(this).apply {
            orientation=if (landscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; gravity=Gravity.CENTER
        }
        body.addView(actions,if (landscape) LinearLayout.LayoutParams(dp(92),-1) else LinearLayout.LayoutParams(-1,dp(102)))
        actions.space()
        val album=FrameLayout(this)
        val openAlbum = { startActivity(Intent(this,GalleryActivity::class.java)); Unit }
        album.addView(Glyph(this,"album","เปิดรูปถ่าย",openAlbum),-1,-1)
        thumb=ImageView(this).apply {
            scaleType=ImageView.ScaleType.CENTER_CROP; clipToOutline=true; background=shape(PANEL,dp(16).toFloat())
            contentDescription="เปิดรูปถ่ายล่าสุด"; setOnClickListener { openAlbum() }; setOnLongClickListener{startActivity(Intent(this@CameraActivity,StudioLibraryActivity::class.java));true}; visibility=View.GONE
        }
        lastThumbUri=null
        album.addView(thumb,FrameLayout.LayoutParams(dp(44),dp(44),Gravity.CENTER))
        actions.addView(album,dp(52),dp(52)); actions.space()
        shutter=Glyph(this,"shutter","ถ่ายภาพ",::takePhoto)
        shutter.setOnLongClickListener { if(!busy&&!timerRunning){if(output==CaptureOutput.JPEG&&mode==PhotoMode.PHOTO)takeSequence() else notice("เลือก JPEG ในโหมดภาพถ่ายเพื่อถ่ายต่อเนื่อง 10 ภาพ")};true }
        actions.addView(shutter,dp(76),dp(76)); actions.space()
        flip=Glyph(this,"flip","สลับกล้องหน้าและหลัง") { if (!busy) { rear=!rear;ratio=1f;bind() } }
        actions.addView(flip,dp(52),dp(52));actions.space()
        installGestures()
        refresh()
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
            manual=ManualControls(this,camera!!)
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
    private var renderedZooms: List<Float> = emptyList()
    private fun refresh() {
        for (i in 0 until outputButtons.childCount) {
            val choice = CaptureOutput.entries[i]
            val button = outputButtons.getChildAt(i) as TextView
            button.setTextColor(if (choice == output) LIME else WHITE)
            button.alpha = if (choice.needsRaw && !rawAvailable) 0.4f else 1f
            button.isEnabled = !busy
        }
        aiButton.visibility = if (output == CaptureOutput.JPEG) View.VISIBLE else View.GONE
        aiButton.text=if(auto) "AI ออโต้" else "AI ปิด"; aiButton.setTextColor(if(auto) LIME else MUTED)
        flashButton.text=if(flash==ImageCapture.FLASH_MODE_ON) "แฟลช เปิด" else "แฟลช ปิด"
        flashButton.visibility=if(camera?.cameraInfo?.hasFlashUnit()==true) View.VISIBLE else View.GONE
        shutter.isEnabled=capture!=null && granted() && !busy && !zoomSettling && (!output.needsRaw || rawAvailable)
        shutter.alpha=if(shutter.isEnabled) 1f else 0.45f
        flip.isEnabled=!busy; flashButton.isEnabled=!busy
        currentZoom.text="${mode.title}  ·  ${zoomText(ratio)}"+(if(software && rear) " · ครอป" else "")+(if(activeExtension)" · OEM" else "")
        aux.isEnabled=!busy
        aux.text=if(mode==PhotoMode.PANORAMA)"ต่อภาพ (${panoFiles.size})" else "ISO · S · WB · AF · EV"
        val zr=range();ruler.update(zr.first,zr.second,ratio)
        val (lo,hi)=range()
        val stops=(listOfNotNull(lo.takeIf { it<0.98f },1f,2f,3.5f,5f).filter { it in lo..hi }).distinct()
        if(stops!=renderedZooms || zooms.childCount!=stops.size) {
            zooms.removeAllViews();renderedZooms=stops
            stops.forEach { v ->
                val button=label(zoomText(v),14f).apply {gravity=Gravity.CENTER;isFocusable=true;contentDescription="ซูม ${zoomText(v)}";setOnClickListener {animateZoom(v)}; pressFeedback(); setOnLongClickListener { ruler.visibility = View.VISIBLE; true }}
                zooms.addView(button,dp(49),-1)
            }
        }
        for(i in stops.indices) (zooms.getChildAt(i) as TextView).setTextColor(if(abs(stops[i]-ratio)<0.08f) LIME else WHITE)
        val zoomRange = range()
        if (zoomRange.second > zoomRange.first) {
            zoomSlider.progress = (1000 * kotlin.math.ln((ratio / zoomRange.first).toDouble()) /
                kotlin.math.ln((zoomRange.second / zoomRange.first).toDouble())).roundToInt().coerceIn(0, 1000)
        }
        zoomSlider.isEnabled = !busy
        zooms.visibility=if(capture!=null && stops.size>1) View.VISIBLE else View.GONE
    }
    private fun installGestures() {
        val scale=ScaleGestureDetector(this,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){
            override fun onScale(detector: ScaleGestureDetector):Boolean { applyZoom(ratio*detector.scaleFactor);return true }
        })
        val taps=GestureDetector(this,object:GestureDetector.SimpleOnGestureListener(){
            override fun onDown(e:MotionEvent)=true
            override fun onLongPress(e:MotionEvent){
                val cam=camera?:return
                val point=preview.meteringPointFactory.createPoint(e.x,e.y)
                cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).disableAutoCancel().build())
                manual?.lock(true);overlay.focus(e.x,e.y,true);hint("ล็อก AE/AF · แตะอีกครั้งเพื่อโฟกัสใหม่",1800)
            }
            override fun onScroll(first:MotionEvent?,last:MotionEvent,dx:Float,dy:Float):Boolean{
                val cam=camera?:return false
                if(first!=null&&abs(last.y-first.y)>dp(25)&&abs(last.y-first.y)>abs(last.x-first.x)*1.5){val ev=cam.cameraInfo.exposureState;if(ev.isExposureCompensationSupported){val range=ev.exposureCompensationRange;val value=((first.y-last.y)/dp(30)).roundToInt().coerceIn(range.lower,range.upper);cam.cameraControl.setExposureCompensationIndex(value);hint("EV %.1f".format(value*ev.exposureCompensationStep.toFloat()),500)};return true};return false
            }
            override fun onFling(first:MotionEvent?,last:MotionEvent,vx:Float,vy:Float):Boolean{
                if(first!=null&&abs(last.x-first.x)>dp(70)&&abs(vx)>abs(vy)*1.8&&!busy){val modes=listOf(PhotoMode.NIGHT,PhotoMode.PORTRAIT,PhotoMode.PHOTO,PhotoMode.VIDEO,PhotoMode.PRO);val index=modes.indexOf(mode).coerceAtLeast(2);selectMode(modes[(index+if(vx<0)1 else -1).coerceIn(0,modes.lastIndex)]);return true};return false
            }
            override fun onSingleTapConfirmed(e:MotionEvent):Boolean {
                val cam=camera ?: return false
                manual?.lock(false)
                val point=preview.meteringPointFactory.createPoint(e.x,e.y)
                val op=FocusMeteringAction.Builder(point).setAutoCancelDuration(3,TimeUnit.SECONDS).build()
                if(cam.cameraInfo.isFocusMeteringSupported(op)) {
                    cam.cameraControl.startFocusAndMetering(op); hint("โฟกัส",600)
                }
                overlay.focus(e.x,e.y)
                if(prefs.getBoolean("focus_sound",false))tones.startTone(ToneGenerator.TONE_PROP_BEEP,50)
                if(prefs.getBoolean("tap_shutter",false))takePhoto()
                preview.performClick();return true
            }
        })
        preview.setOnTouchListener { _,e -> scale.onTouchEvent(e);taps.onTouchEvent(e);true }
    }
    private var hintVersion=0
    private fun hint(text:String,ms:Long=1500) {
        val version=++hintVersion
        viewfinderNotice.text=text;viewfinderNotice.visibility=View.VISIBLE
        viewfinderNotice.postDelayed({if(version==hintVersion) viewfinderNotice.visibility=View.GONE},ms)
    }
    private fun toggleAi(){auto=!auto;prefs.edit().putBoolean("auto",auto).apply();refresh()}
    private fun toggleFlash(){
        flash=if(flash==ImageCapture.FLASH_MODE_OFF) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        capture?.flashMode=flash;refresh()
    }
    private fun showOptions() {
        val modes=arrayOf("ซูมด้วยระบบกล้อง", "ครอปจากกล้องหลัก 1×", "ข้อมูลเครื่องและกล้อง", "ตั้งค่าเสียง / AI / การตอบสนอง", "ตั้งค่าทั้งหมด", "คลังวิดีโอและเอกสาร")
        AlertDialog.Builder(this).setTitle("โหมดซูมกล้องหลัง").setSingleChoiceItems(modes,if(software) 1 else 0) { dialog,index ->
            dialog.dismiss()
            if(index==5){showAllSettings()}
            else if(index==6){startActivity(Intent(this,StudioLibraryActivity::class.java))}
            else if (index == 3) {
                showCaptureSettings(rawAvailable) {
                    auto = prefs.getBoolean("auto", true)
                    overlay.grid = prefs.getBoolean("grid", false)
                    refresh()
                }
            } else if(index==2) {
                Capabilities.show(this)
            } else if(!busy) {
                software=index==1;prefs.edit().putBoolean("software",software).apply()
                applyZoom(1f)
            }
        }.setNegativeButton("ปิด",null).show()
    }

    private fun captureFeedback() {
        if (isDestroyed) return
        if (prefs.getBoolean("sound", true) || CameraInfo.mustPlayShutterSound()) {
            shutterSound.play(MediaActionSound.SHUTTER_CLICK)
        }
    }

    private fun takeBurst() {
        val useCase = capture ?: return
        if (output.needsRaw && !rawAvailable) {
            notice("กล้องนี้ไม่เปิด RAW + JPEG ให้แอป")
            return
        }
        busy = true
        hintVersion++
        refresh()
        burst = BurstCapture(
            applicationContext, useCase, output,
            if (output.enhance) burstCount(if(mode==PhotoMode.NIGHT)32 else prefs.getInt("frames", 8)) else 1,
            rear, ratio, software && rear, targetRotation,
            started = { captureFeedback() },
            progress = { done, total ->
                if (!isDestroyed) {
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
        locked.addListener({
            runCatching { locked.get() }
            burst?.start()
        }, main)
    }

    private fun takePhoto() {
        if(busy||timerRunning||illuminating||zoomSettling)return
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
        val useCase=capture ?: return
        if(mode==PhotoMode.HDR&&!activeExtension){takeHdr(useCase);return}
        if(mode==PhotoMode.ULTRA_HDR){takeUltra(useCase);return}
        if(busy || zoomSettling) return
        if (output != CaptureOutput.JPEG) {
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
            if(pendingBind) {pendingBind=false;bind()}
            refresh();hint(msg,2200)
        }
    }
    private fun selectMode(next:PhotoMode){
        if(busy||timerRunning)return
        if(next==PhotoMode.MORE){showMore();return}
        if(next==PhotoMode.VIDEO){provider?.unbindAll();capture=null;startActivity(Intent(this,VideoActivity::class.java));return}
        if(panoFiles.isNotEmpty()&&next!=PhotoMode.PANORAMA){notice("ต่อภาพพาโนรามาหรือกลับมาถ่ายต่อได้ในโหมดพาโนรามา")}
        mode=next
        if(next!=PhotoMode.PHOTO&&next!=PhotoMode.PRO){output=CaptureOutput.JPEG;prefs.edit().putString("output",output.name).apply()}
        prefs.edit().putString("photo_mode",next.name).apply()
        buildScreen();bind()
    }
    private fun showMore(){studioSheet("เพิ่มเติม"){box,d->
        val options=listOf("HDR" to {selectMode(PhotoMode.HDR)},"Ultra HDR" to {selectMode(PhotoMode.ULTRA_HDR)},"ความละเอียดสูง" to {selectMode(PhotoMode.HIRES)},"เอกสาร / PDF / OCR" to {selectMode(PhotoMode.DOCUMENT)},"พาโนรามา" to {selectMode(PhotoMode.PANORAMA)},"Time-lapse" to {openSpecial("TIMELAPSE")},"Slow-motion" to {openSpecial("SLOW")},"Dual-view" to {openVideo("DUAL")},"Movie / PRO Video" to {openVideo("MOVIE")},"RAW Video / RAW AI ทดลอง" to {provider?.unbindAll();capture=null;startActivity(Intent(this,RawLabActivity::class.java))},"คลัง / แต่งคลิป / AI Video" to {startActivity(Intent(this,StudioLibraryActivity::class.java))})
        options.chunked(2).forEach{pair->val row=LinearLayout(this);pair.forEach{(name,action)->row.addView(pill(name){d.dismiss();action()},LinearLayout.LayoutParams(0,dp(72),1f).apply{setMargins(dp(3),dp(3),dp(3),dp(3))})};box.addView(row)}
        box.addView(pill("ตั้งค่าทั้งหมด"){d.dismiss();showAllSettings()},-1,dp(54))
    }}
    private fun openVideo(kind:String){provider?.unbindAll();capture=null;startActivity(Intent(this,VideoActivity::class.java).putExtra("mode",kind))}
    private fun openSpecial(kind:String){provider?.unbindAll();capture=null;startActivity(Intent(this,SpecialVideoActivity::class.java).putExtra("mode",kind))}
    private fun showAllSettings(){showStudioSettings(rawAvailable){auto=prefs.getBoolean("auto",true);overlay.grid=prefs.getBoolean("grid",false)
        if(prefs.getBoolean("gps",false)&&ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED)locationRequest.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION))
        if(!busy)bind()
    }}
    private val locationRequest=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){if(it.values.none{v->v}){prefs.edit().putBoolean("gps",false).apply();notice("ปิด GPS เพราะยังไม่ได้อนุญาตตำแหน่ง")}}
    private fun takeHdr(useCase:ImageCapture){val cam=camera?:return;busy=true;hintVersion++;refresh();captureFeedback()
        hdr=HdrCapture(this,cam,useCase,{frame->hint("HDR · $frame/3 เฟรม",32000)},{file,error->hdr=null;if(file!=null)luma.fileIo.execute{runCatching{PhotoEffects.decorate(this,file,rear);publishSpecial(file,"HDR · Exposure fusion SDR")}.onSuccess{complete("บันทึก HDR แล้ว")}.onFailure{complete(it.localizedMessage?:"HDR ไม่สำเร็จ")}}else complete(error?:"HDR ไม่สำเร็จ")});hdr!!.start()
    }
    private fun takeUltra(useCase:ImageCapture){busy=true;refresh();val file=File(cacheDir,"ultra-${System.currentTimeMillis()}.jpg");useCase.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),luma.fileIo,object:ImageCapture.OnImageSavedCallback{
        override fun onCaptureStarted(){runOnUiThread{captureFeedback()}}
        override fun onImageSaved(result:ImageCapture.OutputFileResults){try{publishSpecial(file,"Ultra HDR · gain map จากกล้อง");complete("บันทึก Ultra HDR แล้ว")}catch(e:Exception){complete(e.localizedMessage?:"บันทึกไม่ได้")}finally{file.delete()}}
        override fun onError(e:ImageCaptureException){complete(e.localizedMessage?:"ถ่ายไม่ได้");file.delete()}
    })}
    private fun publishSpecial(file:File,note:String){val id=UUID.randomUUID().toString();val time=System.currentTimeMillis();val uri=PhotoMedia.publish(this,file,"LUMA_${time}_${id}.jpg",time);luma.shots.insert(Shot(id,time,uri,PhotoMedia.dimensions(file),ratio,rear,null,null,Decision.OFF,Stage.ORIGINAL,null,0,null,false,captureNote=note))}
    private fun takeSequence(){val useCase=capture?:return;busy=true;refresh();SequentialPhotos(this,useCase,rear,ratio,{n->hint("ถ่ายต่อเนื่อง $n/10",3000)},{message->complete(message)}).start()}
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
