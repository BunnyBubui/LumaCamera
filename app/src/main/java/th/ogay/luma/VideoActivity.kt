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
import android.view.View
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
    private lateinit var shutter:ShutterButton;private lateinit var pause:FrameLayout;private lateinit var pauseIcon:IconView
    private lateinit var library:FrameLayout;private lateinit var flip:FrameLayout;private lateinit var stage:FrameLayout
    private lateinit var zooms:ZoomCapsule;private lateinit var wheel:ZoomWheel
    private lateinit var tabs:FrameLayout;private lateinit var knob:View;private val tabLabels=mutableListOf<TextView>()
    private lateinit var chipFrame:FrameLayout;private lateinit var chips:CenterStrip;private val chipTexts=mutableListOf<Pair<TextView,TextView>>()
    private lateinit var dial:LinearLayout;private lateinit var dialTitle:TextView;private lateinit var dialValue:TextView;private lateinit var slider:GlassSlider
    private var category=0;private var openChip:String?=null;private var zoomStops:List<Float> = emptyList()
    private val rebind=Runnable{bind()}
    private val sounds=MediaActionSound()
    private val kind get()=intent.getStringExtra("mode")?:"VIDEO"
    private val dual get()=kind=="DUAL"
    private val slow get()=kind=="SLOW"
    // Core session state (codex/video-session-recovery).
    private val sessions=VideoSessions()
    private val diagnostics=VideoDiagnostics()
    private var boundConfig=JSONObject()
    private var targetRotation=android.view.Surface.ROTATION_0
    private lateinit var orientation:OrientationEventListener
    private var rateLabel="30 fps"
    private var selectedCameraId:String?=null
    private fun capabilities(info:CameraInfo)=if(slow)Recorder.getHighSpeedVideoCapabilities(info)?:error("กล้องนี้ไม่เปิด high-speed ให้แอป · ดูรายงานความสามารถ")else Recorder.getVideoCapabilities(info)
    private val permission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)bind()else status.text="อนุญาตกล้องเพื่อเริ่มวิดีโอ"}
    private val audioPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){allowed->if(!allowed){mic=false;notice("ปิดไมค์สำหรับคลิปนี้ เพราะยังไม่ได้อนุญาตเสียง")};startRecording()}
    override fun onCreate(state:Bundle?){
        super.onCreate(state);mic=prefs.getBoolean("video_mic",true)
        quality=when(prefs.getString("video_quality","FHD")){"UHD"->Quality.UHD;"HD"->Quality.HD;"SD"->Quality.SD;else->Quality.FHD}
        fps=prefs.getInt("video_fps",30);bitrate=prefs.getInt("video_bitrate",20_000_000);stabilization=prefs.getBoolean("video_eis",false)
        dynamic=when(prefs.getString("video_dynamic","SDR")){"HLG"->DynamicRange.HLG_10_BIT;"HDR10"->DynamicRange.HDR10_10_BIT;else->DynamicRange.SDR}
        if(slow){mic=false;dynamic=DynamicRange.SDR;stabilization=false;fps=prefs.getInt("slow_fps",120);quality=when(prefs.getString("slow_quality","FHD")){"UHD"->Quality.UHD;"HD"->Quality.HD;"SD"->Quality.SD;else->Quality.FHD}}
        selectedCameraId=prefs.getString(if(slow)"slow_camera"else "video_camera",null)
        targetRotation=windowManager.defaultDisplay.rotation
        orientation=object:OrientationEventListener(this){override fun onOrientationChanged(degrees:Int){
            VideoSessionPolicy.rotation(degrees)?.let{rotation->targetRotation=rotation;if(recording==null&&!starting&&!finalizing)useCase?.targetRotation=rotation}
        }}
        sounds.load(MediaActionSound.START_VIDEO_RECORDING);sounds.load(MediaActionSound.STOP_VIDEO_RECORDING)
        buildScreen()
        onBackPressedDispatcher.addCallback(this,object:androidx.activity.OnBackPressedCallback(true){override fun handleOnBackPressed(){
            if(openChip!=null){closeDial();return};isEnabled=false;onBackPressedDispatcher.onBackPressed();isEnabled=true}})
        ProcessCameraProvider.getInstance(this).also{f->f.addListener({runCatching{provider=f.get();if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)bind()else permission.launch(arrayOf(Manifest.permission.CAMERA))}.onFailure{status.text=it.localizedMessage}},main)}
    }
    private fun selector():CameraSelector{val selected=info()?:return if(rear)CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA;return CameraSelector.Builder().addCameraFilter{items->items.filter{it==selected}}.build()}
    private fun info():CameraInfo?{
        val infos=provider?.availableCameraInfos?:return null
        val selected=infos.firstOrNull{Camera2CameraInfo.from(it).cameraId==selectedCameraId}
        if(selected!=null)return selected
        val facing=if(rear)CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
        if(slow)return infos.sortedBy{if(it.lensFacing==facing)0 else 1}.firstOrNull{runCatching{Recorder.getHighSpeedVideoCapabilities(it)?.getSupportedQualities(DynamicRange.SDR)?.isNotEmpty()==true}.getOrDefault(false)}?:infos.firstOrNull{it.lensFacing==facing}
        return infos.firstOrNull{it.lensFacing==facing}
    }
    @Suppress("DEPRECATION")
    private fun bind(){
        if(recording!=null||starting||finalizing)return
        val p=provider?:return;shutter.isEnabled=false;shutter.alpha=0.45f;status.text="กำลังตรวจชุดวิดีโอ…"
        try{
            val info=info()?:error("ไม่พบกล้อง")
            boundConfig=JSONObject().put("cameraId",Camera2CameraInfo.from(info).cameraId).put("quality",qualityName(quality)).put("requestedFps",fps).put("slowMotion",slow)
            val caps=capabilities(info)
            check(dynamic in caps.supportedDynamicRanges){"กล้องนี้ไม่รองรับ HDR ที่เลือก"}
            val supported=caps.getSupportedQualities(dynamic)
            if(quality !in supported){val previous=qualityName(quality);quality=supported.firstOrNull{it==Quality.FHD}?:supported.firstOrNull()?:error("ไม่พบโปรไฟล์วิดีโอ");notice("ชุดนี้ไม่มี $previous · เปลี่ยนเป็น ${qualityName(quality)} แล้ว")}
            val modes=sessions.rates(info,quality,dynamic,stabilization,slow)
            boundConfig.put("sessionQuery",sessions.report(info,quality,dynamic,stabilization,slow))
            if(slow&&modes.isNotEmpty()&&modes.none{it.upper==fps}){val before=fps;fps=modes.minBy{it.upper}.upper;notice("ชุด $before fps ไม่รองรับ · เลือก $fps fps แล้ว")}
            val mode=modes.firstOrNull{it.upper==fps}?:error("${qualityName(quality)} ไม่รองรับ $fps fps ร่วมกับ HDR/กันสั่นที่เลือก · เลือกชุดใหม่")
            rateLabel=sessions.label(mode)
            boundConfig.put("requestedFps",fps)
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
            selectedCameraId=Camera2CameraInfo.from(info).cameraId;rear=info.lensFacing==CameraSelector.LENS_FACING_BACK
            recorder=r;useCase=v;manual=if(slow)null else ManualControls(this,camera!!,true,true,fps)
            camera!!.cameraInfo.zoomState.observe(this){refreshZoom()}
            saveSettings()
            config.text="${qualityName(quality)} · ${if(mode.lower<mode.upper)"≤"else ""}$fps${if(dynamic!=DynamicRange.SDR)" HDR" else ""}"
            status.text=if(dual)"หน้า + หลัง · ตรวจไฟล์หลังอัด" else "${if(mic)"ไมค์เปิด" else "ไมค์ปิด"} · ${if(stabilization)"กันสั่นเปิด" else "พร้อมบันทึก"}"
            shutter.isEnabled=true;shutter.alpha=1f;refreshChips()
        }catch(e:Exception){VideoDiagnostics.save(this,if(slow)"high-speed-bind"else "normal-bind",boundConfig,e);recorder=null;useCase=null;status.text="ชุดนี้ใช้ไม่ได้ · ${e.localizedMessage}\nแตะค่าด้านบนเพื่อเปลี่ยน"}
    }
    private fun settings(){if(recording!=null||starting||finalizing){notice("หยุดคลิปก่อนเปลี่ยนรูปแบบวิดีโอ");return}
        val i=info()?:return
        if(slow&&Recorder.getHighSpeedVideoCapabilities(i)==null){Capabilities.show(this);return}
        studioSheet("วิดีโอ"){box,d->
            fun choose(title:String,items:List<String>,action:(Int)->Unit){box.addView(pill(title){AlertDialog.Builder(this).setTitle(title).setItems(items.toTypedArray()){_,n->action(n);d.dismiss();bind()}.setNegativeButton("ปิด",null).show()},-1,-2)}
            val candidates=provider?.availableCameraInfos.orEmpty().filter{cam->runCatching{if(slow)Recorder.getHighSpeedVideoCapabilities(cam)?.getSupportedQualities(DynamicRange.SDR)?.isNotEmpty()==true else Recorder.getVideoCapabilities(cam).getSupportedQualities(DynamicRange.SDR).isNotEmpty()}.getOrDefault(false)}
            choose("กล้อง / เลนส์ · ${Camera2CameraInfo.from(i).cameraId}",candidates.map{"กล้อง ${Camera2CameraInfo.from(it).cameraId} · ${if(it.lensFacing==CameraSelector.LENS_FACING_BACK)"หลัง"else "หน้า"}"}){selectedCameraId=Camera2CameraInfo.from(candidates[it]).cameraId}
            val caps=capabilities(i);val qualities=caps.getSupportedQualities(dynamic)
            choose("ความละเอียด · ${qualityName(quality)}",qualities.map{qualityName(it)}){quality=qualities[it]}
            val frames=sessions.rates(i,quality,dynamic,stabilization,slow)
            choose("FPS · $fps",frames.map{sessions.label(it)}){fps=frames[it].upper}
            val ranges=listOf(DynamicRange.SDR,DynamicRange.HLG_10_BIT,DynamicRange.HDR10_10_BIT).filter{it in caps.supportedDynamicRanges}
            if(!slow)choose("SDR / HDR",ranges.map{when(it){DynamicRange.SDR->"SDR 8-bit";DynamicRange.HLG_10_BIT->"HLG10";else->"HDR10"}}){dynamic=ranges[it]}
            choose("Bitrate · ${bitrate/1_000_000} Mbps",listOf("8 Mbps","20 Mbps","40 Mbps","80 Mbps")){bitrate=listOf(8,20,40,80)[it]*1_000_000}
            box.addView(label("Codec กล้องเลือกตามโปรไฟล์ · เลือก H.264/HEVC ได้ตอนส่งออกคลิป",13f,MUTED),-1,-2)
            if(!slow&&caps.isStabilizationSupported)choose("กันสั่น EIS · $stabilization",listOf("ปิด","เปิด")){stabilization=it==1;prefs.edit().putBoolean("video_eis",stabilization).apply()}
            if(!slow)choose("ไมโครโฟน · ${if(mic)"เปิด" else "ปิด"}",listOf("ปิดไมค์","บันทึกเสียง")){mic=it==1;prefs.edit().putBoolean("video_mic",mic).apply()}
            if(!slow)box.addView(pill("คืนค่าชุดพื้นฐาน 1080p30 SDR"){dynamic=DynamicRange.SDR;fps=30;quality=Quality.FHD;hevc=false;stabilization=false;d.dismiss();bind()},-1,-2)
            box.addView(pill("รายงานความสามารถ"){Capabilities.show(this)},-1,-2)
        }
    }
    private fun startRecording(){
        val r=recorder?:return;if(starting||finalizing||recording!=null)return
        if(filesDir.usableSpace<64L*1024*1024){notice("พื้นที่ว่างไม่พอเริ่มคลิป");return}
        starting=true;diagnostics.reset();useCase?.targetRotation=targetRotation;boundConfig.put("targetRotation",targetRotation);shutter.isEnabled=false;config.isEnabled=false
        // Rotation would recreate the Activity and onPause would stop the clip, so hold the orientation until it finalises.
        requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
        val sound=prefs.getBoolean("video_sound",true)
        if(sound)sounds.play(MediaActionSound.START_VIDEO_RECORDING)
        handler.postDelayed({
            if(!starting||isFinishing||isDestroyed){starting=false;requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;return@postDelayed}
            try{
                val name="LUMA_VIDEO_${System.currentTimeMillis()}.mp4"
                val values=ContentValues().apply{put(MediaStore.Video.Media.DISPLAY_NAME,name);put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");if(android.os.Build.VERSION.SDK_INT>=29)put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/Luma/")}
                val options=MediaStoreOutputOptions.Builder(contentResolver,MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).setFileSizeLimit((filesDir.usableSpace-32L*1024*1024).coerceAtLeast(1)).build()
                var pending=r.prepareRecording(this,options)
                if(mic&&ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)pending=pending.withAudioEnabled()
                recording=pending.start(main){event->when(event){
                    is VideoRecordEvent.Start->{diagnostics.active(true);status.announceForAccessibility("เริ่มบันทึกวิดีโอ");starting=false;shutter.isEnabled=true;shutter.recording=true;shutter.contentDescription="หยุดบันทึกวิดีโอ";pause.isEnabled=true;recordingUi(true)}
                    is VideoRecordEvent.Status->{val seconds=event.recordingStats.recordedDurationNanos/1_000_000_000;status.text=String.format(Locale.US,"%02d:%02d  ·  %.1f MB%s",seconds/60,seconds%60,event.recordingStats.numBytesRecorded/1e6,if(paused)" · พัก" else "")}
                    is VideoRecordEvent.Pause->{diagnostics.active(false);paused=true;pauseIcon.path=Icons.VIDEO;pause.contentDescription="ต่อการบันทึก"}
                    is VideoRecordEvent.Resume->{diagnostics.active(true);paused=false;pauseIcon.path=PAUSE;pause.contentDescription="พักการบันทึก"}
                    is VideoRecordEvent.Finalize->{if(event.hasError())VideoDiagnostics.save(this,if(slow)"high-speed-finalize"else "normal-finalize",boundConfig,event.cause?:IllegalStateException("Recorder finalize code ${event.error}"));diagnostics.active(false);val sensor=diagnostics.snapshot();val requested=fps;val playback=if(slow)30 else fps;requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;recording=null;starting=false;finalizing=false;paused=false;shutter.recording=false;shutter.contentDescription="เริ่มบันทึกวิดีโอ";pause.isEnabled=false;pauseIcon.path=PAUSE;pause.contentDescription="พักการบันทึก";shutter.isEnabled=true;config.isEnabled=true;recordingUi(false)
                        if(sound&&!isDestroyed)sounds.play(MediaActionSound.STOP_VIDEO_RECORDING)
                        val uri=event.outputResults.outputUri
                        if(uri!=Uri.EMPTY&&event.recordingStats.numBytesRecorded>0){
                            MediaLedger.add(this,uri,name,"video/mp4",if(event.hasError())"คลิปจบบางส่วน · รหัส ${event.error}" else "${qualityName(quality)} · เป้าหมาย $fps fps · ${if(dynamic==DynamicRange.SDR)"SDR" else "HDR"}")
                            status.text=if(event.hasError())"เก็บคลิปที่ได้รับแล้ว · ${event.error}" else "บันทึกวิดีโอแล้ว"
                            luma.fileIo.execute{ClipInspector.record(this,uri,name,requested,sensor,playback)}
                        }else status.text="บันทึกไม่สำเร็จ · ${event.cause?.localizedMessage?:event.error}"
                        status.announceForAccessibility(status.text)
                    }
                }}
            }catch(e:Exception){VideoDiagnostics.save(this,if(slow)"high-speed-start"else "normal-start",boundConfig,e);requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;starting=false;recording=null;shutter.isEnabled=true;config.isEnabled=true;status.text="เริ่มบันทึกไม่ได้ · ${e.localizedMessage}"}
        },if(sound)300 else 0)
    }
    private fun stopRecording(){animator?.cancel();finalizing=true;shutter.isEnabled=false;pause.isEnabled=false;status.text="กำลังปิดไฟล์…";recording?.stop()}
    private fun zoomPanel(){studioSheet("ซูมอัตโนมัติ A → B"){box,d->
        box.addView(pill("กำหนด A ที่ระยะปัจจุบัน"){zoomA=camera?.cameraInfo?.zoomState?.value?.zoomRatio?:1f;notice("A = ${zoomText(zoomA)}")},-1,dp(52))
        box.addView(pill("กำหนด B ที่ระยะปัจจุบัน"){zoomB=camera?.cameraInfo?.zoomState?.value?.zoomRatio?:3f;notice("B = ${zoomText(zoomB)}")},-1,dp(52))
        val row=LinearLayout(this);listOf(2L,4L,8L).forEach{seconds->row.addView(pill("${seconds}s"){zoomDuration=seconds},LinearLayout.LayoutParams(0,dp(48),1f))};box.addView(row)
        box.addView(Switch(this).apply{text="เริ่มและจบแบบนุ่ม";setTextColor(WHITE);isChecked=smooth;setOnCheckedChangeListener{_,v->smooth=v}},-1,dp(48))
        box.addView(pill("เล่น ${zoomText(zoomA)} → ${zoomText(zoomB)}"){d.dismiss();val cam=camera?:return@pill;val z=cam.cameraInfo.zoomState.value?:return@pill;animator?.cancel();animator=ValueAnimator.ofFloat(ln(zoomA.coerceIn(z.minZoomRatio,z.maxZoomRatio)),ln(zoomB.coerceIn(z.minZoomRatio,z.maxZoomRatio))).apply{duration=zoomDuration*1000;interpolator=if(smooth)android.view.animation.AccelerateDecelerateInterpolator()else android.view.animation.LinearInterpolator();addUpdateListener{cam.cameraControl.setZoomRatio(exp(it.animatedValue as Float))};start()}},-1,dp(56))
    }}
    private fun saveSettings(){prefs.edit().putString(if(slow)"slow_camera"else "video_camera",selectedCameraId).apply();if(slow){prefs.edit().putInt("slow_fps",fps).putString("slow_quality",when(quality){Quality.UHD->"UHD";Quality.HD->"HD";Quality.SD->"SD";else->"FHD"}).apply();return};prefs.edit().putString("video_quality",when(quality){Quality.UHD->"UHD";Quality.HD->"HD";Quality.SD->"SD";else->"FHD"}).putInt("video_fps",fps).putInt("video_bitrate",bitrate).putBoolean("video_eis",stabilization).putString("video_dynamic",when(dynamic){DynamicRange.HLG_10_BIT->"HLG";DynamicRange.HDR10_10_BIT->"HDR10";else->"SDR"}).apply()}
    override fun onResume(){super.onResume();orientation.enable();if(recording==null&&!starting&&!finalizing&&useCase!=null){shutter.isEnabled=true;config.isEnabled=true}}
    override fun onPause(){if(::zooms.isInitialized){zooms.finish();wheel.removeCallbacks(hideWheel);hideWheel.run()};orientation.disable();if(starting&&recording==null){requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED};starting=false;animator?.cancel();handler.removeCallbacksAndMessages(null);recording?.stop();super.onPause()}
    override fun onDestroy(){sounds.release();super.onDestroy()}
    override fun onKeyDown(keyCode:Int,event:KeyEvent):Boolean{if(keyCode==KeyEvent.KEYCODE_VOLUME_DOWN||keyCode==KeyEvent.KEYCODE_VOLUME_UP){if(event.repeatCount==0)shutter.performClick();return true};return super.onKeyDown(keyCode,event)}
    // ---------- Glass screen ----------
    private val PAUSE="M8 5v14 M16 5v14"
    private fun roundButton(icon:IconView,size:Int,desc:String,click:()->Unit)=FrameLayout(this).apply{
        background=glass(size/2);contentDescription=desc;isClickable=true;isFocusable=true;glassTouch()
        addView(icon,FrameLayout.LayoutParams(dp(size*22/44),dp(size*22/44),Gravity.CENTER));setOnClickListener{click()}}
    private fun busy()=recording!=null||starting||finalizing
    private fun buildScreen(){
        val host=FrameLayout(this);installRoot(host)
        val landscape=resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val column=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};host.addView(column,-1,-1)
        val head=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),dp(8),dp(14),dp(6))}
        head.addView(roundButton(IconView(this,Icons.CHEVRON_LEFT,2.2f),48,"กลับไปถ่ายภาพ"){if(!busy())finish()},dp(48),dp(48))
        head.addView(text(when(kind){"DUAL"->"Dual-view";"MOVIE"->"วิดีโอโปร";"SLOW"->"Slow-motion · ไม่มีเสียง";else->"วิดีโอ"},16f,WHITE,true).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,-2,1f))
        config=text(if(slow)"SLOW · ตัวเลือก" else "1080p · 30",13f,WHITE,true).apply{gravity=Gravity.CENTER;background=glass(22);setPadding(dp(14),0,dp(14),0);isClickable=true;contentDescription="รายละเอียดวิดีโอทั้งหมด";glassTouch();setOnClickListener{settings()}}
        head.addView(config,-2,dp(48));column.addView(head,-1,-2)
        // Landscape keeps the dock beside the preview instead of under it.
        val body=LinearLayout(this).apply{orientation=if(landscape)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL};column.addView(body,LinearLayout.LayoutParams(-1,0,1f))
        stage=FrameLayout(this);body.addView(stage,if(landscape)LinearLayout.LayoutParams(0,-1,1f) else LinearLayout.LayoutParams(-1,0,1f))
        preview=PreviewView(this).apply{implementationMode=PreviewView.ImplementationMode.COMPATIBLE;scaleType=PreviewView.ScaleType.FIT_CENTER};stage.addView(preview,-1,-1)
        val overlay=CompositionOverlay(this).apply{grid=prefs.getBoolean("grid",false);movie=kind=="MOVIE"};stage.addView(overlay,-1,-1)
        status=text("กำลังเปิดกล้อง…",13f).apply{gravity=Gravity.CENTER;background=glass(18);setPadding(dp(14),dp(7),dp(14),dp(7));maxLines=3;accessibilityLiveRegion=android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE}
        stage.addView(status,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply{topMargin=dp(10)})
        wheel=ZoomWheel(this).apply{alpha=0f};stage.addView(wheel,FrameLayout.LayoutParams(-1,dp(150),Gravity.BOTTOM).apply{bottomMargin=dp(4)})
        zooms=ZoomCapsule(this).apply{background=glass(28);setPadding(dp(4),dp(4),dp(4),dp(4));contentDescription="ระยะเลนส์ ลากซ้ายขวาเพื่อซูมละเอียด"}
        stage.addView(zooms,FrameLayout.LayoutParams(-2,dp(56),Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply{bottomMargin=dp(8)})
        installZoomDrag()
        stage.addView(roundButton(IconView(this,"M4 17l5-5 4 4 7-8 M15 8h5v5",2f),48,"ซูมอัตโนมัติจาก A ไป B"){closeDial();zoomPanel()},FrameLayout.LayoutParams(dp(48),dp(48),Gravity.BOTTOM or Gravity.START).apply{setMargins(dp(14),0,0,dp(12))})
        // Settings dial floats over the preview, chips sit in the dock.
        dial=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=glass(24);setPadding(dp(16),dp(12),dp(16),dp(12));visibility=android.view.View.GONE}
        val dh=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        dialTitle=text("",13f,Glass.TEXT2);dialValue=text("",20f,WHITE,true).apply{gravity=Gravity.END}
        dh.addView(dialTitle,LinearLayout.LayoutParams(0,-2,1f));dh.addView(dialValue,-2,-2);dial.addView(dh,-1,dp(30))
        slider=GlassSlider(this).apply{onChange={i->openChip?.let{applyChip(it,i)}}};dial.addView(slider,-1,dp(56))
        stage.addView(dial,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply{setMargins(dp(16),0,dp(16),dp(66))})

        val dock=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_VERTICAL};if(landscape)body.addView(dock,dp(320),-1) else body.addView(dock,-1,-2)
        if(kind=="MOVIE"){
            tabs=FrameLayout(this).apply{background=glass(20,0x1fffffff)}
            knob=android.view.View(this).apply{background=glass(17,0x38ffffff)};tabs.addView(knob,FrameLayout.LayoutParams(0,-1).apply{setMargins(dp(3),dp(3),dp(3),dp(3))})
            val row=LinearLayout(this);tabLabels.clear()
            listOf("ปรับแต่งวิดีโอ","คุณภาพวิดีโอ").forEachIndexed{i,t->val l=text(t,13.5f,if(i==category)WHITE else Glass.TEXT2,true).apply{gravity=Gravity.CENTER;isClickable=true;contentDescription=t;setOnClickListener{if(category!=i){category=i;slideKnob(true);closeDial();refreshChips()}}};tabLabels+=l;row.addView(l,LinearLayout.LayoutParams(0,-1,1f))}
            tabs.addView(row,-1,-1);tabs.post{slideKnob(false)}
            dock.addView(tabs,LinearLayout.LayoutParams(-1,dp(48)).apply{setMargins(dp(16),dp(8),dp(16),dp(4))})
        }
        chipFrame=FrameLayout(this).apply{background=glass(24)}
        chips=CenterStrip(this).apply{capsuleHeightDp=44;onSelect={i->chipKeys().getOrNull(i)?.let{k->if(openChip==k)closeDial() else openDial(k)}};onLive={i->styleChips(i)}}
        chipFrame.addView(chips,FrameLayout.LayoutParams(-1,-1).apply{setMargins(dp(1),dp(1),dp(1),dp(1))})
        val grow=((resources.configuration.fontScale+1f)/2f).coerceAtLeast(1f)
        dock.addView(chipFrame,LinearLayout.LayoutParams(-1,(dp(56)*grow).roundToInt()).apply{setMargins(dp(16),dp(6),dp(16),dp(4))})
        val actions=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(36),0,dp(36),0)}
        library=roundButton(IconView(this,Icons.LIBRARY),56,"คลังวิดีโอและแต่งคลิป"){if(!busy())startActivity(Intent(this,StudioLibraryActivity::class.java))}
        pauseIcon=IconView(this,PAUSE,2.4f)
        pause=roundButton(pauseIcon,56,"พักการบันทึก"){val r=recording?:return@roundButton;if(paused)r.resume()else r.pause()}.apply{visibility=android.view.View.GONE;isEnabled=false}
        val left=FrameLayout(this).apply{addView(library,-1,-1);addView(pause,-1,-1)}
        actions.addView(left,dp(56),dp(56));actions.space()
        shutter=ShutterButton(this).apply{video=true;isEnabled=false;alpha=0.45f;contentDescription="เริ่มบันทึกวิดีโอ"
            setOnClickListener{if(recording!=null)stopRecording()else if(!starting&&!finalizing){if(mic&&ContextCompat.checkSelfPermission(this@VideoActivity,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)audioPermission.launch(Manifest.permission.RECORD_AUDIO)else startRecording()}}}
        actions.addView(shutter,dp(88),dp(88));actions.space()
        val flipIcon=IconView(this,Icons.FLIP)
        flip=roundButton(flipIcon,56,"สลับกล้องวิดีโอ"){if(!busy()&&!dual){flipIcon.animate().rotationBy(180f).setInterpolator(Glass.EASE).setDuration(420).start();Glass.blurPulse(stage,10f,300);rear=!rear;bind()}}
        if(dual)flip.alpha=0.35f
        actions.addView(flip,dp(56),dp(56));dock.addView(actions,-1,dp(112))
        val pinch=android.view.ScaleGestureDetector(this,object:android.view.ScaleGestureDetector.SimpleOnScaleGestureListener(){override fun onScale(d:android.view.ScaleGestureDetector):Boolean{val s=camera?.cameraInfo?.zoomState?.value?:return false;animator?.cancel();camera?.cameraControl?.setZoomRatio((s.zoomRatio*d.scaleFactor).coerceIn(s.minZoomRatio,s.maxZoomRatio));return true}})
        preview.setOnTouchListener{_,e->pinch.onTouchEvent(e);if(e.actionMasked==MotionEvent.ACTION_UP){if(openChip!=null)closeDial();preview.performClick()};true}
        refreshChips()
    }
    private fun slideKnob(animate:Boolean){
        if(!::tabs.isInitialized)return
        val w=(tabs.width-dp(6))/2;knob.layoutParams=(knob.layoutParams as FrameLayout.LayoutParams).apply{width=w}
        val x=(category*w).toFloat();if(animate&&!Glass.reduced(this))knob.animate().translationX(x).setInterpolator(Glass.SPRING).setDuration(420).start() else knob.translationX=x
        tabLabels.forEachIndexed{i,l->l.setTextColor(if(i==category)WHITE else Glass.TEXT2)}
    }
    private fun recordingUi(on:Boolean){
        library.visibility=if(on)android.view.View.GONE else android.view.View.VISIBLE;pause.visibility=if(on)android.view.View.VISIBLE else android.view.View.GONE
        if(on)Liquid.show(pause,pause.width/2f,pause.height/2f,false)
        flip.animate().alpha(if(on||dual)0.35f else 1f).setDuration(200).start();config.animate().alpha(if(on)0.45f else 1f).setDuration(200).start()
        if(on)closeDial();refreshChips()
    }

    // ---------- Zoom ----------
    private fun refreshZoom(){
        val z=camera?.cameraInfo?.zoomState?.value?:return
        val stops=listOfNotNull(z.minZoomRatio.takeIf{it<0.98f},1f,2f,3.5f).filter{it in z.minZoomRatio..z.maxZoomRatio}.distinct()
        if(stops!=zoomStops){zoomStops=stops;zooms.removeAllViews()
            stops.forEach{v->val cell=FrameLayout(this).apply{isClickable=true;contentDescription="ซูม ${zoomText(v)} ลากซ้ายขวาเพื่อซูมละเอียด"}
                cell.addView(text(zoomText(v),12f,WHITE,true).apply{setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP,12f);gravity=Gravity.CENTER;background=GlassDrawable(0x1affffff,dp(20).toFloat(),resources.displayMetrics.density)},FrameLayout.LayoutParams(dp(32),dp(32),Gravity.CENTER))
                cell.setOnClickListener{animateZoom(v)};zooms.addView(cell,LinearLayout.LayoutParams(dp(48),dp(48)).apply{setMargins(dp(1),0,dp(1),0)})}}
        zooms.visibility=if(stops.size>1&&!dual)android.view.View.VISIBLE else android.view.View.GONE
        val active=stops.indexOfLast{z.zoomRatio>=it-0.001f}.coerceAtLeast(0)
        stops.forEachIndexed{i,v->val t=(zooms.getChildAt(i) as FrameLayout).getChildAt(0) as TextView;val on=i==active
            t.text=if(on)zoomText(z.zoomRatio) else zoomText(v).removeSuffix("×").replace("0.",".");t.setTextColor(if(on)Glass.ACCENT else WHITE)
            val lp=t.layoutParams as FrameLayout.LayoutParams;val size=dp(if(on)40 else 32);if(lp.width!=size){lp.width=size;lp.height=size;t.layoutParams=lp}
            (t.background as? GlassDrawable)?.let{it.fill=if(on)0x8c000000.toInt() else 0x1affffff;it.invalidateSelf()}}
        wheel.minZoom=z.minZoomRatio;wheel.maxZoom=z.maxZoomRatio;wheel.lenses=stops;wheel.zoom=z.zoomRatio
    }
    private fun animateZoom(target:Float){
        val cam=camera?:return;val z=cam.cameraInfo.zoomState.value?:return;animator?.cancel()
        if(Glass.reduced(this)){cam.cameraControl.setZoomRatio(target);return}
        animator=ValueAnimator.ofFloat(ln(z.zoomRatio),ln(target)).apply{duration=280;interpolator=Glass.EASE;addUpdateListener{cam.cameraControl.setZoomRatio(exp(it.animatedValue as Float))};start()}
    }
    private val hideWheel=Runnable{wheel.show(false);zooms.animate().alpha(1f).setDuration(200).start()}
    private var dragZoom0=1f
    private fun installZoomDrag(){
        fun start(){dragZoom0=camera?.cameraInfo?.zoomState?.value?.zoomRatio?:1f;wheel.removeCallbacks(hideWheel);animator?.cancel();zooms.animate().alpha(0f).setDuration(200).start();wheel.show(true)}
        fun move(dx:Float){val z=camera?.cameraInfo?.zoomState?.value?:return
            var t=(dragZoom0*2f.pow(-dx/dp(95))).coerceIn(z.minZoomRatio,z.maxZoomRatio)
            zoomStops.firstOrNull{abs(ln(t/it)/ln(2f))<0.075f}?.let{lens->if(abs(z.zoomRatio-lens)>0.001f)Glass.tick(wheel);t=lens}
            camera?.cameraControl?.setZoomRatio(t);wheel.zoom=t}
        fun end(){wheel.removeCallbacks(hideWheel);wheel.postDelayed(hideWheel,1300)}
        zooms.onStart={start()};zooms.onDrag={move(it)};zooms.onEnd={end()}
        var sx=0f;var on=false
        wheel.setOnTouchListener{v,e->when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{on=wheel.alpha>0.5f;if(on){sx=e.rawX;start();v.parent.requestDisallowInterceptTouchEvent(true)}}
            MotionEvent.ACTION_MOVE->if(on)move(e.rawX-sx)
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->if(on){on=false;end()}};on}
    }

    // ---------- Chips and dial ----------
    private fun chipKeys():List<String>{
        // Slow-motion is SDR, silent and without EIS, so those chips are not offered there.
        val quality=if(slow)listOf("RES","FPS","RATE") else listOf("RES","FPS","HDR","RATE","EIS","MIC")
        val adjust=buildList{val m=manual;if(m!=null&&m.canManual){add("ISO");add("S")};add("WB");if((m?.minFocus?:0f)>0f)add("AF");add("EV")}
        return if(kind=="MOVIE"&&category==0)adjust else quality
    }
    private fun chipTitle(k:String)=when(k){"RES"->"ความละเอียด";"FPS"->"FPS";"HDR"->"ช่วงไดนามิก";"RATE"->"Bitrate";"EIS"->"กันสั่น";"MIC"->"ไมค์";"ISO"->"ISO";"S"->"ชัตเตอร์";"WB"->"WB";"AF"->"โฟกัส";else->"EV"}
    private fun caps()=info()?.let{runCatching{capabilities(it)}.getOrNull()}
    private fun qualities()=caps()?.getSupportedQualities(dynamic)?:listOf(quality)
    /** FPS offered for the current quality/HDR/EIS set, as the session query reports it (never the camera-wide AE list). */
    private fun rateModes()=info()?.let{sessions.rates(it,quality,dynamic,stabilization,slow)}.orEmpty()
    private fun frameRates()=rateModes().map{it.upper}.ifEmpty{listOf(fps)}
    private fun ranges()=listOf(DynamicRange.SDR,DynamicRange.HLG_10_BIT,DynamicRange.HDR10_10_BIT).filter{caps()?.supportedDynamicRanges?.contains(it)?:(it==DynamicRange.SDR)}
    private fun evValues():List<Float>{val st=camera?.cameraInfo?.exposureState?:return listOf(0f);if(!st.isExposureCompensationSupported)return listOf(0f);val step=st.exposureCompensationStep.toFloat();val r=st.exposureCompensationRange;return(r.lower..r.upper).map{it*step}.filter{abs(it)<=2.01f}}
    private fun chipValues(k:String):List<String>{val m=manual
        return when(k){
            "RES"->qualities().map{qualityName(it)};"FPS"->rateModes().let{m->if(m.isEmpty())listOf("$fps") else m.map{x->if(x.lower<x.upper)"≤${x.upper}" else "${x.upper}"}};"HDR"->ranges().map{when(it){DynamicRange.SDR->"SDR";DynamicRange.HLG_10_BIT->"HLG10";else->"HDR10"}}
            "RATE"->listOf("8","20","40","80").map{"$it Mbps"};"EIS"->listOf("ปิด","เปิด");"MIC"->listOf("ปิด","เปิด")
            "ISO"->listOf("AUTO")+(m?.isoValues?:emptyList()).map{"$it"};"S"->listOf("AUTO")+(m?.shutterValues?:emptyList()).map{it.first}
            "WB"->(m?.wbValues?:listOf("AUTO" to 1)).map{it.first};"AF"->listOf("AUTO","∞","ไกล","กลาง","ใกล้","มาโคร")
            else->evValues().map{(if(it>0)"+" else if(it==0f)"±" else "")+String.format(Locale.US,"%.1f",it)}
        }}
    private fun chipIndex(k:String):Int{val m=manual
        return when(k){
            "RES"->qualities().indexOf(quality).coerceAtLeast(0);"FPS"->frameRates().indexOf(fps).coerceAtLeast(0);"HDR"->ranges().indexOf(dynamic).coerceAtLeast(0)
            "RATE"->listOf(8,20,40,80).indexOf(bitrate/1_000_000).coerceAtLeast(0);"EIS"->if(stabilization)1 else 0;"MIC"->if(mic)1 else 0
            "ISO"->m?.currentIso?.let{(m.isoValues.indexOf(it)+1).coerceAtLeast(0)}?:0
            "S"->m?.currentShutter?.let{s->(m.shutterValues.indexOfFirst{it.second==s}+1).coerceAtLeast(0)}?:0
            "WB"->(m?.wbValues?.indexOfFirst{it.second==m.currentWb}?:0).coerceAtLeast(0)
            "AF"->m?.currentFocus?.let{f->if(m.minFocus<=0f)0 else 1+((f/m.minFocus)*4).roundToInt().coerceIn(0,4)}?:0
            else->{val st=camera?.cameraInfo?.exposureState;val cur=st?.let{it.exposureCompensationIndex*it.exposureCompensationStep.toFloat()}?:0f;evValues().indexOfFirst{abs(it-cur)<0.01f}.coerceAtLeast(0)}
        }}
    private fun chipGrad(k:String)=when(k){
        "ISO"->intArrayOf(0xff5ac8fa.toInt(),0xff7be3b0.toInt(),Glass.ACCENT,0xffff9f0a.toInt(),0xffff453a.toInt())
        "S"->intArrayOf(0xff64d2ff.toInt(),0xffbf9bff.toInt(),Glass.ACCENT,0xffff9f0a.toInt())
        "WB"->intArrayOf(0xffff9a3d.toInt(),0xffffd9a8.toInt(),WHITE,0xffcfe5ff.toInt(),0xff7db8ff.toInt())
        "AF"->intArrayOf(0xff8e8e93.toInt(),0xffbbf0ff.toInt(),0xff5ac8fa.toInt())
        "RES","RATE","FPS"->intArrayOf(0xff8e8e93.toInt(),WHITE,Glass.ACCENT)
        else->intArrayOf(0xff8e8e93.toInt(),WHITE)}
    private fun refreshChips(){
        if(!::chips.isInitialized)return
        val keys=chipKeys();chipTexts.clear()
        chips.setItems(keys.map{k->val t=text(chipTitle(k),10.5f,Glass.TEXT2).apply{gravity=Gravity.CENTER;maxLines=1};val v=text(chipValues(k).getOrElse(chipIndex(k)){"–"},13f,WHITE,true).apply{gravity=Gravity.CENTER;maxLines=1}
            chipTexts+=t to v
            val wDp=(chipValues(k).plus(chipTitle(k)).maxOf{v.paint.measureText(it)}/resources.displayMetrics.density+24).roundToInt().coerceAtLeast(78)
            CenterStrip.Item(k,wDp,LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;isClickable=true;contentDescription=chipTitle(k);addView(t,-2,-2);addView(v,-2,-2)})},
            keys.indexOf(openChip).coerceAtLeast(0))
        chipFrame.alpha=if(recording!=null&&!(kind=="MOVIE"&&category==0))0.45f else 1f
    }
    private fun styleChips(live:Int){val keys=chipKeys();chipTexts.forEachIndexed{i,(t,v)->val on=i==live;t.setTextColor(if(on)WHITE else Glass.TEXT3);v.text=chipValues(keys[i]).getOrElse(chipIndex(keys[i])){"–"}}}
    private fun openDial(k:String){
        val quality=k in listOf("RES","FPS","HDR","RATE","EIS","MIC")
        if(quality&&busy()){notice("หยุดคลิปก่อนเปลี่ยนรูปแบบวิดีโอ");return}
        val first=openChip==null;openChip=k
        dialTitle.text=chipTitle(k);slider.lo=if(k in listOf("ISO","S","AF"))1 else 0;slider.values=chipValues(k);slider.grad=chipGrad(k);slider.index=chipIndex(k);dialValue.text=chipValues(k).getOrElse(chipIndex(k)){"–"}
        if(first)dial.visibility=android.view.View.VISIBLE
        Liquid.show(dial,dial.width/2f,dial.height.toFloat(),false);zooms.animate().alpha(0f).setDuration(160).start()
    }
    private fun closeDial(){if(openChip==null)return;openChip=null;Liquid.hide(dial,dial.width/2f,dial.height.toFloat(),false);zooms.animate().alpha(1f).setDuration(200).start()}
    private fun applyChip(k:String,i:Int){
        val m=manual
        when(k){
            "RES"->qualities().getOrNull(i)?.let{quality=it};"FPS"->frameRates().getOrNull(i)?.let{fps=it};"HDR"->ranges().getOrNull(i)?.let{dynamic=it}
            "RATE"->bitrate=listOf(8,20,40,80)[i.coerceIn(0,3)]*1_000_000
            "EIS"->{stabilization=i==1;prefs.edit().putBoolean("video_eis",stabilization).apply()}
            "MIC"->{mic=i==1;prefs.edit().putBoolean("video_mic",mic).apply()}
            "ISO"->m?.setIso(if(i<=0)null else m.isoValues.getOrNull(i-1))
            "S"->m?.setShutter(if(i<=0)null else m.shutterValues.getOrNull(i-1)?.second)
            "WB"->m?.wbValues?.getOrNull(i)?.let{m.setWb(it.second)}
            "AF"->m?.setFocus(if(i<=0)null else m.minFocus*(i-1)/4f)
            else->evValues().getOrNull(i)?.let{v->val st=camera?.cameraInfo?.exposureState;if(st!=null&&st.isExposureCompensationSupported)camera?.cameraControl?.setExposureCompensationIndex((v/st.exposureCompensationStep.toFloat()).roundToInt())}
        }
        dialValue.text=chipValues(k).getOrElse(i){"–"};styleChips(chips.selected)
        if(k in listOf("RES","FPS","HDR","RATE","EIS")){configPending();handler.removeCallbacks(rebind);handler.postDelayed(rebind,450)}
        else if(k=="MIC")status.text=if(mic)"ไมค์เปิด" else "ไมค์ปิด"
    }
    /** State hook: the chosen set is not bound yet, so recording waits until bind() succeeds and re-enables the shutter. */
    private fun configPending(){shutter.isEnabled=false;shutter.alpha=0.45f;status.text="กำลังเปลี่ยนชุดวิดีโอ…"}
    private fun qualityName(q:Quality)=when(q){Quality.UHD->"4K";Quality.FHD->"1080p";Quality.HD->"720p";else->"SD"}
}

