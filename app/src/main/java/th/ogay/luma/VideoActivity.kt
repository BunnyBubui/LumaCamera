package th.ogay.luma

import android.Manifest
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.MediaActionSound
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Range
import android.util.Rational
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import org.json.JSONObject
import androidx.camera.camera2.interop.Camera2CameraInfo
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.*

@ExperimentalCamera2Interop
class VideoActivity:ComponentActivity(){
    private val main by lazy{ContextCompat.getMainExecutor(this)}
    private val handler=Handler(Looper.getMainLooper())
    private val prefs by lazy{getSharedPreferences("camera",0)}
    private var provider:ProcessCameraProvider?=null;private var camera:Camera?=null
    private var recorder:Recorder?=null;private var useCase:VideoCapture<Recorder>?=null;private var recording:Recording?=null
    private var paused=false;private var starting=false;private var finalizing=false;private var rear=true
    private var quality=Quality.FHD;private var dynamic=DynamicRange.SDR;private var fps=30
    private var bitrate=20_000_000;private var hevc=false;private var stabilization=false
    private var mic=true;private var zoomA=1f;private var zoomB=3f;private var zoomDuration=4L;private var smooth=true
    private var animator:ValueAnimator?=null;private var manual:ManualControls?=null
    private lateinit var preview:PreviewView;private lateinit var status:TextView;private lateinit var config:TextView
    private lateinit var shutter:Glyph;private lateinit var pause:TextView;private lateinit var ruler:ZoomRuler
    private val sessions=VideoSessions()
    private val diagnostics=VideoDiagnostics()
    private var boundConfig=JSONObject()
    private var targetRotation=android.view.Surface.ROTATION_0
    private lateinit var orientation:OrientationEventListener
    private var rateLabel="30 fps"
    private val sounds=MediaActionSound()
    private val kind get()=intent.getStringExtra("mode")?:"VIDEO"
    private val dual get()=kind=="DUAL"
    private val slow get()=kind=="SLOW"
    private fun capabilities(info:CameraInfo)=if(slow)Recorder.getHighSpeedVideoCapabilities(info)?:error("กล้องนี้ไม่เปิด high-speed ให้แอป · ดูรายงานความสามารถ")else Recorder.getVideoCapabilities(info)
    private val permission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)bind()else status.text="อนุญาตกล้องเพื่อเริ่มวิดีโอ"}
    private val audioPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){allowed->if(!allowed){mic=false;notice("ปิดไมค์สำหรับคลิปนี้ เพราะยังไม่ได้อนุญาตเสียง")};startRecording()}
    override fun onCreate(state:Bundle?){
        super.onCreate(state);mic=prefs.getBoolean("video_mic",true)
        quality=when(prefs.getString("video_quality","FHD")){"UHD"->Quality.UHD;"HD"->Quality.HD;"SD"->Quality.SD;else->Quality.FHD}
        fps=prefs.getInt("video_fps",30);bitrate=prefs.getInt("video_bitrate",20_000_000);stabilization=prefs.getBoolean("video_eis",false)
        dynamic=when(prefs.getString("video_dynamic","SDR")){"HLG"->DynamicRange.HLG_10_BIT;"HDR10"->DynamicRange.HDR10_10_BIT;else->DynamicRange.SDR}
        if(slow){mic=false;dynamic=DynamicRange.SDR;stabilization=false;fps=prefs.getInt("slow_fps",120);quality=when(prefs.getString("slow_quality","FHD")){"UHD"->Quality.UHD;"HD"->Quality.HD;"SD"->Quality.SD;else->Quality.FHD}}
        targetRotation=windowManager.defaultDisplay.rotation
        orientation=object:OrientationEventListener(this){override fun onOrientationChanged(degrees:Int){
            VideoSessionPolicy.rotation(degrees)?.let{rotation->targetRotation=rotation;if(recording==null&&!starting&&!finalizing)useCase?.targetRotation=rotation}
        }}
        sounds.load(MediaActionSound.START_VIDEO_RECORDING);sounds.load(MediaActionSound.STOP_VIDEO_RECORDING)
        // Landscape puts the preview beside a scrolling control column; portrait stacks them.
        val landscape=resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val root=LinearLayout(this).apply{orientation=if(landscape)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL};installRoot(root)
        val panel=if(landscape)LinearLayout(this).apply{orientation=LinearLayout.VERTICAL} else root
        val head=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),0,dp(12),0);minimumHeight=dp(60)}
        head.addView(pill("‹ ภาพถ่าย"){if(recording==null&&!starting&&!finalizing)finish()},-2,-2);head.space()
        config=pill("1080p · 30",::settings);head.addView(config,-2,-2);panel.addView(head,-1,-2)
        val frame=FrameLayout(this);root.addView(frame,if(landscape)LinearLayout.LayoutParams(0,-1,1f) else LinearLayout.LayoutParams(-1,0,1f))
        preview=PreviewView(this).apply{implementationMode=PreviewView.ImplementationMode.COMPATIBLE;scaleType=PreviewView.ScaleType.FIT_CENTER};frame.addView(preview,-1,-1)
        val overlay=CompositionOverlay(this).apply{grid=prefs.getBoolean("grid",false);movie=kind=="MOVIE"};frame.addView(overlay,-1,-1)
        status=label("กำลังเปิดกล้อง…",14f).apply{gravity=Gravity.CENTER;background=shape(0x88000000.toInt());setPadding(dp(10),dp(4),dp(10),dp(4));minHeight=dp(40)}
        frame.addView(status,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply{topMargin=dp(12);leftMargin=dp(12);rightMargin=dp(12)})
        ruler=ZoomRuler(this){animator?.cancel();camera?.cameraControl?.setZoomRatio(it)}
        frame.addView(ruler,FrameLayout.LayoutParams(-1,dp(64),Gravity.BOTTOM).apply{leftMargin=dp(28);rightMargin=dp(28);bottomMargin=dp(16)})
        val tools=LinearLayout(this).apply{gravity=Gravity.CENTER}
        tools.addView(pill(if(kind=="MOVIE")"MOVIE · PRO" else "PRO"){manual?.show()},0,-2);(tools.getChildAt(0).layoutParams as LinearLayout.LayoutParams).weight=1f
        tools.addView(pill("ซูม A → B",::zoomPanel),LinearLayout.LayoutParams(0,-2,1f))
        val lever=pill("◁ เลื่อนซูม ▷"){}
        lever.contentDescription="คันโยกซูมวิดีโอ";var origin=0f;var velocity=0f
        // The lever only works by dragging; TalkBack gets zoom in/out actions instead of an empty click.
        fun nudge(factor:Float){val z=camera?.cameraInfo?.zoomState?.value?:return;animator?.cancel();camera?.cameraControl?.setZoomRatio((z.zoomRatio*factor).coerceIn(z.minZoomRatio,z.maxZoomRatio))}
        androidx.core.view.ViewCompat.addAccessibilityAction(lever,"ซูมเข้า"){_,_->nudge(1.25f);true}
        androidx.core.view.ViewCompat.addAccessibilityAction(lever,"ซูมออก"){_,_->nudge(0.8f);true}
        val tick=object:Runnable{override fun run(){val state=camera?.cameraInfo?.zoomState?.value;if(state!=null&&velocity!=0f)camera?.cameraControl?.setZoomRatio((state.zoomRatio*exp(velocity*0.025f)).coerceIn(state.minZoomRatio,state.maxZoomRatio));handler.postDelayed(this,32)}}
        lever.setOnTouchListener{_,e->when(e.actionMasked){MotionEvent.ACTION_DOWN->{animator?.cancel();origin=e.x;handler.post(tick)};MotionEvent.ACTION_MOVE->{val d=e.x-origin;velocity=if(abs(d)<dp(10))0f else(d/dp(90)).coerceIn(-1f,1f)};MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{velocity=0f;handler.removeCallbacks(tick);lever.performClick()}};true}
        tools.addView(lever,LinearLayout.LayoutParams(0,-2,1f));panel.addView(tools,-1,-2)
        panel.addView(label(when(kind){"DUAL"->"DUAL VIEW";"MOVIE"->"MOVIE · 2.39:1";"SLOW"->"SLOW MOTION · ไม่มีเสียง · เล่น 30 fps";else->"VIDEO"},16f,LIME).apply{gravity=Gravity.CENTER;minHeight=dp(42)},-1,-2)
        val actions=LinearLayout(this).apply{gravity=Gravity.CENTER};actions.space()
        pause=pill("พัก"){val r=recording?:return@pill;if(paused)r.resume()else r.pause()}.apply{contentDescription="พักหรือต่อวิดีโอ";isEnabled=false};pause.minWidth=dp(70);actions.addView(pause,-2,-2);actions.space()
        shutter=Glyph(this,"shutter","เริ่มบันทึกวิดีโอ"){if(recording!=null)stopRecording()else if(!starting&&!finalizing){if(mic&&ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)audioPermission.launch(Manifest.permission.RECORD_AUDIO)else startRecording()}}.apply{video=true;isEnabled=false}
        actions.addView(shutter,dp(82),dp(82));actions.space()
        actions.addView(Glyph(this,"flip","สลับกล้องวิดีโอ"){if(recording==null&&!starting&&!finalizing&&!dual){rear=!rear;bind()}},dp(56),dp(56));actions.space();panel.addView(actions,-1,dp(112))
        panel.addView(pill("คลังวิดีโอ / แต่งคลิป"){if(recording==null&&!starting)startActivity(Intent(this,StudioLibraryActivity::class.java))},-1,-2)
        if(landscape)root.addView(ScrollView(this).apply{isFillViewport=true;addView(panel)},LinearLayout.LayoutParams(dp(300),-1))
        val pinch=android.view.ScaleGestureDetector(this,object:android.view.ScaleGestureDetector.SimpleOnScaleGestureListener(){override fun onScale(d:android.view.ScaleGestureDetector):Boolean{val s=camera?.cameraInfo?.zoomState?.value?:return false;animator?.cancel();camera?.cameraControl?.setZoomRatio((s.zoomRatio*d.scaleFactor).coerceIn(s.minZoomRatio,s.maxZoomRatio));return true}})
        preview.setOnTouchListener{_,e->pinch.onTouchEvent(e);if(e.actionMasked==MotionEvent.ACTION_UP)preview.performClick();true}
        ProcessCameraProvider.getInstance(this).also{f->f.addListener({runCatching{provider=f.get();if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)bind()else permission.launch(arrayOf(Manifest.permission.CAMERA))}.onFailure{status.text=it.localizedMessage}},main)}
    }
    private fun selector()=if(rear)CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
    private fun info()=provider?.availableCameraInfos?.let{selector().filter(it).firstOrNull()}
    @Suppress("DEPRECATION")
    private fun bind(){
        if(recording!=null||starting||finalizing)return
        val p=provider?:return;shutter.isEnabled=false;status.text="กำลังตรวจชุดวิดีโอ…"
        try{
            val info=info()?:error("ไม่พบกล้อง")
            val caps=capabilities(info)
            check(dynamic in caps.supportedDynamicRanges){"กล้องนี้ไม่รองรับ HDR ที่เลือก"}
            val supported=caps.getSupportedQualities(dynamic)
            if(quality !in supported){quality=supported.firstOrNull{it==Quality.FHD}?:supported.firstOrNull()?:error("ไม่พบโปรไฟล์วิดีโอ")}
            val modes=sessions.rates(info,quality,dynamic,stabilization,slow)
            if(slow&&modes.isNotEmpty()&&modes.none{it.upper==fps}){val before=fps;fps=modes.minBy{it.upper}.upper;notice("ชุด $before fps ไม่รองรับ · เลือก $fps fps แล้ว")}
            val mode=modes.firstOrNull{it.upper==fps}?:error("${qualityName(quality)} ไม่รองรับ $fps fps ร่วมกับ HDR/กันสั่นที่เลือก · เลือกชุดใหม่")
            rateLabel=sessions.label(mode)
            boundConfig=JSONObject().put("cameraId",Camera2CameraInfo.from(info).cameraId).put("quality",qualityName(quality)).put("requestedFps",fps)
                .put("advertisedRange",mode.range.toString()).put("source",mode.source).put("dynamicRange",dynamic.toString()).put("eis",stabilization)
            val rb=Recorder.Builder().setVideoCapabilitiesSource(mode.source).setQualitySelector(QualitySelector.from(quality)).setTargetVideoEncodingBitRate(bitrate)
            val r=rb.build();val b=VideoCapture.Builder(r).setDynamicRange(dynamic).setTargetRotation(targetRotation)
            if(stabilization){check(caps.isStabilizationSupported){"กันสั่นวิดีโอไม่รองรับบนกล้องนี้"};b.setVideoStabilizationEnabled(true)}
            if(dual)b.setTargetFrameRate(mode.range)
            val v=b.build();val pb=Preview.Builder()
            Camera2Interop.Extender(pb).setSessionCaptureCallback(diagnostics)
            val previewCase=pb.build().apply{setSurfaceProvider(preview.surfaceProvider)}
            camera?.cameraInfo?.zoomState?.removeObservers(this)
            p.unbindAll()
            if(dual){
                val pair=p.availableConcurrentCameraInfos.firstOrNull{set->set.any{it.lensFacing==CameraSelector.LENS_FACING_FRONT}&&set.any{it.lensFacing==CameraSelector.LENS_FACING_BACK}}?:error("เครื่องนี้ไม่เปิดกล้องหน้าและหลังพร้อมกันให้แอป")
                val front=pair.first{it.lensFacing==CameraSelector.LENS_FACING_FRONT};val back=pair.first{it.lensFacing==CameraSelector.LENS_FACING_BACK}
                fun select(i:CameraInfo)=CameraSelector.Builder().addCameraFilter{list->list.filter{it==i}}.build()
                val group=UseCaseGroup.Builder().addUseCase(previewCase).addUseCase(v).build()
                val concurrent=p.bindToLifecycle(listOf(
                    ConcurrentCamera.SingleCameraConfig(select(back),group,CompositionSettings.Builder().build(),this),
                    ConcurrentCamera.SingleCameraConfig(select(front),group,CompositionSettings.Builder().setScale(0.32f,0.32f).setOffset(0.63f,-0.6f).build(),this)))
                camera=concurrent.cameras.first()
            }else{
                val session=if(slow)HighSpeedVideoSessionConfig.Builder(v).setPreview(previewCase).setFrameRateRange(mode.range).setSlowMotionEnabled(true).build() else {
                    val group=SessionConfig.Builder(previewCase,v).setFrameRateRange(mode.range)
                    if(kind=="MOVIE")group.setViewPort(ViewPort.Builder(Rational(239,100),targetRotation).build())
                    group.build()
                }
                camera=p.bindToLifecycle(this,selector(),session)
            }
            recorder=r;useCase=v;manual=ManualControls(this,camera!!,true,true,fps)
            camera!!.cameraInfo.zoomState.observe(this){ruler.update(it.minZoomRatio,it.maxZoomRatio,it.zoomRatio)}
            saveSettings()
            config.text="${qualityName(quality)} · $rateLabel${if(dynamic!=DynamicRange.SDR)" HDR" else ""}"
            status.text=if(dual)"หน้า + หลัง · ตรวจไฟล์หลังอัด" else "${if(mic)"ไมค์เปิด" else "ไมค์ปิด"} · ${if(stabilization)"กันสั่นเปิด" else "พร้อมบันทึก"}"
            shutter.isEnabled=true
        }catch(e:Exception){VideoDiagnostics.save(this,"normal-bind",boundConfig,e);recorder=null;useCase=null;status.text="ชุดนี้ใช้ไม่ได้ · ${e.localizedMessage}\nแตะค่าด้านบนเพื่อเปลี่ยน"}
    }
    private fun settings(){if(recording!=null||starting||finalizing){notice("หยุดคลิปก่อนเปลี่ยนรูปแบบวิดีโอ");return}
        val i=info()?:return
        if(slow&&Recorder.getHighSpeedVideoCapabilities(i)==null){Capabilities.show(this);return}
        studioSheet("วิดีโอ"){box,d->
            fun choose(title:String,items:List<String>,action:(Int)->Unit){box.addView(pill(title){AlertDialog.Builder(this).setTitle(title).setItems(items.toTypedArray()){_,n->action(n);d.dismiss();bind()}.setNegativeButton("ปิด",null).show()},-1,-2)}
            val caps=capabilities(i);val qualities=caps.getSupportedQualities(dynamic)
            choose("ความละเอียด · ${qualityName(quality)}",qualities.map{qualityName(it)}){quality=qualities[it]}
            val frames=sessions.rates(i,quality,dynamic,stabilization,slow)
            choose("FPS · $fps",frames.map{sessions.label(it)}){fps=frames[it].upper}
            val ranges=listOf(DynamicRange.SDR,DynamicRange.HLG_10_BIT,DynamicRange.HDR10_10_BIT).filter{it in caps.supportedDynamicRanges}
            if(!slow)choose("SDR / HDR",ranges.map{when(it){DynamicRange.SDR->"SDR 8-bit";DynamicRange.HLG_10_BIT->"HLG10";else->"HDR10"}}){dynamic=ranges[it]}
            choose("Bitrate · ${bitrate/1_000_000} Mbps",listOf("8 Mbps","20 Mbps","40 Mbps","80 Mbps")){bitrate=listOf(8,20,40,80)[it]*1_000_000}
            box.addView(label("Codec กล้องเลือกตามโปรไฟล์ · เลือก H.264/HEVC ได้ตอนส่งออกคลิป",13f,MUTED),-1,-2)
            if(!slow&&caps.isStabilizationSupported)choose("กันสั่น EIS · $stabilization",listOf("ปิด","เปิด")){stabilization=it==1;prefs.edit().putBoolean("video_eis",stabilization).apply()}
            choose("ไมโครโฟ