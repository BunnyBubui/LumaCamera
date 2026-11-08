package th.ogay.luma

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
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
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var capture: ImageCapture? = null
    private var metadata: FrameMetadata? = null
    private var rear = true
    private var software = false
    private var ratio = 1f
    private var auto = true
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
                capture?.targetRotation = targetRotation
            }
        }
        luma.shots.changes.observe(this) { loadThumb() }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!isDestroyed) {
                try { provider = future.get(); if (granted()) bind() else showPermission() }
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
    override fun onPause() { orientation.disable(); super.onPause() }
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
        head.addView(label("Luma",26f).apply { typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL); letterSpacing=-0.035f })
        head.space()
        aiButton = pill("", ::toggleAi)
        head.addView(aiButton,-2,dp(44))
        head.addView(View(this),dp(6),1)
        flashButton = pill("",::toggleFlash)
        head.addView(flashButton,-2,dp(44))
        head.addView(Glyph(this,"menu","การตั้งค่ากล้อง",::showOptions),dp(48),dp(48))
        val body = LinearLayout(this).apply { orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        root.addView(body,LinearLayout.LayoutParams(-1,0,1f))
        val stage = FrameLayout(this).apply { setBackgroundColor(0xff080a08.toInt()) }
        body.addView(stage,if (landscape) LinearLayout.LayoutParams(0,-1,1f) else LinearLayout.LayoutParams(-1,0,1f))
        mask = FrameLayout(this).apply { clipChildren = true; clipToPadding = true }
        stage.addView(mask,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
        stage.addOnLayoutChangeListener { _,left,top,right,bottom,_,_,_,_ ->
            val aspect=if (landscape) 4f/3 else 3f/4
            val w=minOf(right-left, ((bottom-top)*aspect).roundToInt())
            val h=(w/aspect).roundToInt()
            if(mask.layoutParams.width!=w || mask.layoutParams.height!=h) mask.layoutParams=FrameLayout.LayoutParams(w,h,Gravity.CENTER)
        }
        preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FIT_CENTER
        }
        mask.addView(preview,-1,-1)
        currentZoom = label("",14f,WHITE).apply {
            gravity=Gravity.CENTER; setPadding(dp(9),0,dp(9),0)
            background=shape(0xbb1c231c.toInt(),dp(12).toFloat())
        }
        stage.addView(currentZoom,FrameLayout.LayoutParams(-2,dp(32),Gravity.TOP or Gravity.START).apply { setMargins(dp(14),dp(14),0,0) })
        zooms=LinearLayout(this).apply { gravity=Gravity.CENTER; background=shape(0xcc1c231c.toInt(),dp(24).toFloat()) }
        stage.addView(zooms,FrameLayout.LayoutParams(-2,dp(44),Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin=dp(12) })
        viewfinderNotice = label("",14f).apply { gravity=Gravity.CENTER; visibility=View.GONE; setPadding(dp(12),dp(8),dp(12),dp(8)); background=shape(PANEL) }
        stage.addView(viewfinderNotice,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
        permissionPanel = LinearLayout(this).apply {
            gravity=Gravity.CENTER; orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(14),dp(18),dp(14)); background=shape(BG)
            visibility=View.GONE
        }
        stage.addView(permissionPanel,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
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
            contentDescription="เปิดรูปถ่ายล่าสุด"; setOnClickListener { openAlbum() }; visibility=View.GONE
        }
        lastThumbUri=null
        album.addView(thumb,FrameLayout.LayoutParams(dp(44),dp(44),Gravity.CENTER))
        actions.addView(album,dp(52),dp(52)); actions.space()
        shutter=Glyph(this,"shutter","ถ่ายภาพ",::takePhoto)
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
            val selected=selector()
            val info=provider.availableCameraInfos.firstOrNull { it in selected.filter(provider.availableCameraInfos) }
                ?: throw IOException("ไม่พบกล้องที่เปิดใช้งานได้")
            val cc=Camera2CameraInfo.from(info)
            val caps=cc.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val single=Build.VERSION.SDK_INT>=28 && caps!=null && !caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
            val frames=FrameMetadata(single)
            val framing=ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            val previewCase=Preview.Builder().setResolutionSelector(framing.build()).build().apply { surfaceProvider=preview.surfaceProvider }
            val builder=ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(framing.setResolutionStrategy(ResolutionStrategy(Size(4000,3000),ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                .setTargetRotation(targetRotation)
            Camera2Interop.Extender(builder).setSessionCaptureCallback(frames)
            val useCase=builder.build()
            camera?.cameraInfo?.zoomState?.removeObservers(this)
            provider.unbindAll()
            camera=provider.bindToLifecycle(this,selected,previewCase,useCase)
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
    private var renderedZooms: List<Float> = emptyList()
    private fun refresh() {
        aiButton.text=if(auto) "AI ออโต้" else "AI ปิด"; aiButton.setTextColor(if(auto) LIME else MUTED)
        flashButton.text=if(flash==ImageCapture.FLASH_MODE_ON) "แฟลช เปิด" else "แฟลช ปิด"
        flashButton.visibility=if(camera?.cameraInfo?.hasFlashUnit()==true) View.VISIBLE else View.GONE
        shutter.isEnabled=capture!=null && granted() && !busy && !zoomSettling
        shutter.alpha=if(shutter.isEnabled) 1f else 0.45f
        flip.isEnabled=!busy; flashButton.isEnabled=!busy
        currentZoom.text=(if(rear) "หลัง" else "หน้า")+"  "+zoomText(ratio)+(if(software && rear) "  ·  ครอป" else "")
        val (lo,hi)=range()
        val stops=(listOfNotNull(lo.takeIf { it<0.98f },1f,2f,3.5f,5f).filter { it in lo..hi }).distinct()
        if(stops!=renderedZooms || zooms.childCount!=stops.size) {
            zooms.removeAllViews();renderedZooms=stops
            stops.forEach { v ->
                val button=label(zoomText(v),14f).apply {gravity=Gravity.CENTER;isFocusable=true;contentDescription="ซูม ${zoomText(v)}";setOnClickListener {applyZoom(v)}}
                zooms.addView(button,dp(49),-1)
            }
        }
        for(i in stops.indices) (zooms.getChildAt(i) as TextView).setTextColor(if(abs(stops[i]-ratio)<0.08f) LIME else WHITE)
        zooms.visibility=if(capture!=null && stops.size>1) View.VISIBLE else View.GONE
    }
    private fun installGestures() {
        val scale=ScaleGestureDetector(this,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){
            override fun onScale(detector: ScaleGestureDetector):Boolean { applyZoom(ratio*detector.scaleFactor);return true }
        })
        val taps=GestureDetector(this,object:GestureDetector.SimpleOnGestureListener(){
            override fun onDown(e:MotionEvent)=true
            override fun onSingleTapConfirmed(e:MotionEvent):Boolean {
                val cam=camera ?: return false
                val point=preview.meteringPointFactory.createPoint(e.x,e.y)
                val op=FocusMeteringAction.Builder(point).setAutoCancelDuration(3,TimeUnit.SECONDS).build()
                if(cam.cameraInfo.isFocusMeteringSupported(op)) {
                    cam.cameraControl.startFocusAndMetering(op); hint("โฟกัส",600)
                }
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
        val modes=arrayOf("ซูมด้วยระบบกล้อง", "ครอปจากกล้องหลัก 1×", "ข้อมูลเครื่องและกล้อง")
        AlertDialog.Builder(this).setTitle("โหมดซูมกล้องหลัง").setSingleChoiceItems(modes,if(software) 1 else 0) { dialog,index ->
            dialog.dismiss()
            if(index==2) {
                val (lo,hi)=range()
                val logical=camera?.cameraInfo?.let { Camera2CameraInfo.from(it).cameraId }
                AlertDialog.Builder(this).setTitle("ข้อมูลเครื่อง").setMessage("${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}\nCamera ID ${logical ?: "—"}\nช่วงซูมในโหมดนี้ ${zoomText(lo)} – ${zoomText(hi)}\nAI $MODEL_VERSION · CPU ออฟไลน์").setPositiveButton("ปิด",null).show()
            } else if(!busy) {
                software=index==1;prefs.edit().putBoolean("software",software).apply()
                applyZoom(1f)
            }
        }.setNegativeButton("ปิด",null).show()
    }

    private fun takePhoto() {
        val useCase=capture ?: return
        if(busy || zoomSettling) return
        busy=true;refresh()
        val isRear=rear;val useSoftware=software && isRear;val zoom=ratio;val allowAuto=auto;val frames=metadata
        val time=System.currentTimeMillis();val id=UUID.randomUUID().toString()
        useCase.targetRotation=targetRotation
        useCase.takePicture(luma.fileIo,object:ImageCapture.OnImageCapturedCallback(){
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
                    val size=PhotoMedia.dimensions(original)
                    val crop=if(useSoftware && zoom>1f) size.pixels else frame?.crop
                    val actualZoom=if(!useSoftware && frame?.ratio!=null && frame.ratio!=1f) frame.ratio else zoom
                    val reason=decide(allowAuto,isRear,actualZoom,size.pixels,crop)
                    val uri=PhotoMedia.publish(this@CameraActivity,original,"LUMA_${time}_${id}.jpg",time)
                    val stage=if(reason.startsAi) Stage.QUEUED else Stage.ORIGINAL
                    luma.shots.insert(Shot(id,time,uri,size,actualZoom,isRear,frame?.lens,crop,reason,stage,null,0,null,useSoftware))
                    if(reason.startsAi) EnhanceWorker.enqueue(applicationContext,id)
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
    private fun loadThumb() {
        luma.fileIo.execute {
            val uri=luma.shots.latest()?.uri ?: return@execute
            if(uri==lastThumbUri) return@execute
            val bitmap=PhotoMedia.thumbnail(this,uri,160)
            runOnUiThread { if(!isDestroyed && bitmap!=null) {lastThumbUri=uri;thumb.setImageBitmap(bitmap);thumb.visibility=View.VISIBLE} }
        }
    }
}
