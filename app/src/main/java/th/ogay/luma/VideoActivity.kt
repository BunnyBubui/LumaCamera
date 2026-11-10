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
    private var mic=true
    private var animator:ValueAnimator?=null;private var manual:ManualControls?=null
    private lateinit var preview:PreviewView;private lateinit var status:TextView;private lateinit var config:TextView
    private lateinit var shutter:ShutterButton;private lateinit var pause:FrameLayout;private lateinit var pauseIcon:IconView
    private lateinit var library:FrameLayout;private lateinit var flip:FrameLayout;private lateinit var stage:FrameLayout
    private lateinit var zooms:ZoomCapsule;private lateinit var wheel:ZoomWheel
    private lateinit var tabs:FrameLayout;private lateinit var knob:View;private val tabLabels=mutableListOf<TextView>()
    private lateinit var chipFrame:FrameLayout;private lateinit var chips:CenterStrip;private val chipTexts=mutableListOf<Pair<TextView,TextView>>()
    private lateinit var dial:LinearLayout;private lateinit var dialTitle:TextView;private lateinit var dialValue:TextView;private lateinit var slider:GlassSlider
    private var category=1;private var openChip:String?=null;private var zoomStops:List<Float> = emptyList()
    private val rebind=Runnable{bind()}
    private val sounds=MediaActionSound()
    private val kind get()=(intent.getStringExtra("mode")?:"VIDEO").let{k->if(CameraActivity.ALL.any{it.key==k})k else "VIDEO"}
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
        super.onCreate(state);loadSettings()
        targetRotation=windowManager.defaultDisplay.rotation
        orientation=object:OrientationEventListener(this){override fun onOrientationChanged(degrees:Int){
            VideoSessionPolicy.rotation(degrees)?.let{rotation->targetRotation=rotation;if(recording==null&&!starting&&!finalizing)useCase?.targetRotation=rotation}
            if(degrees>=0&&::modeStrip.isInitialized)turnIcons(degrees)
        }}
        sounds.load(MediaActionSound.START_VIDEO_RECORDING);sounds.load(MediaActionSound.STOP_VIDEO_RECORDING)
        buildScreen()
        onBackPressedDispatcher.addCallback(this,object:androidx.activity.OnBackPressedCallback(true){override fun handleOnBackPressed(){
            if(openChip!=null){closeDial();return};if(busy()){notice("หยุดคลิปก่อนออก");return};finish();noAnimation()}})
        ProcessCameraProvider.getInstance(this).also{f->f.addListener({runCatching{provider=f.get();if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)bind()else permission.launch(arrayOf(Manifest.permission.CAMERA))}.onFailure{status.text=it.localizedMessage}},main)}
    }
    /** Settings of the current kind (slow-motion keeps its own resolution, fps and camera). */
    private fun loadSettings(){
        mic=prefs.getBoolean("video_mic",true);prefs.getStringSet("force60_failed_ids",emptySet())?.forEach{sessions.failed60.add(it)}
        quality=when(prefs.getString("video_quality","FHD")){"UHD"->Quality.UHD;"HD"->Quality.HD;"SD"->Quality.SD;else->Quality.FHD}
        logProfile=LogProfile.fromPrefs(prefs);fps=prefs.getInt("video_fps",30);bitrate=prefs.getInt("video_bitrate",20_000_000);stabilization=prefs.getBoolean("video_eis",false)
        dynamic=when(prefs.getString("video_dynamic","SDR")){"HLG"->DynamicRange.HLG_10_BIT;"HDR10"->DynamicRange.HDR10_10_BIT;else->DynamicRange.SDR}
        if(slow){mic=false;dynamic=DynamicRange.SDR;stabilization=false;fps=prefs.getInt("slow_fps",120);quality=when(prefs.getString("slow_quality","FHD")){"UHD"->Quality.UHD;"HD"->Quality.HD;"SD"->Quality.SD;else->Quality.FHD}}
        selectedCameraId=prefs.getString(if(slow)"slow_camera"else "video_camera",null)
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
        val p=provider?:return;stopTracking();shutter.isEnabled=false;shutter.alpha=0.45f;status.text="กำลังตรวจชุดวิดีโอ…"
        try{
            val info=info()?:error("ไม่พบกล้อง")
            boundConfig=JSONObject().put("cameraId",Camera2CameraInfo.from(info).cameraId).put("quality",qualityName(quality)).put("requestedFps",fps).put("slowMotion",slow)
            val caps=capabilities(info)
            if(dynamic !in caps.supportedDynamicRanges){dynamic=DynamicRange.SDR;notice("กล้องนี้ไม่รองรับ HDR · ใช้ SDR แทน")}
            val supported=allQualities(info)
            if(quality !in supported){val previous=qualityName(quality);quality=supported.firstOrNull{it==Quality.FHD}?:supported.firstOrNull()?:error("ไม่พบโปรไฟล์วิดีโอ");notice("ชุดนี้ไม่มี $previous · เปลี่ยนเป็น ${qualityName(quality)} แล้ว")}
            val modes=offered(info)
            boundConfig.put("sessionQuery",sessions.report(info,quality,dynamic,stabilization,slow))
            if(slow&&modes.isNotEmpty()&&modes.none{it.upper==fps}){val before=fps;fps=modes.minBy{it.upper}.upper;notice("ชุด $before fps ไม่รองรับ · เลือก $fps fps แล้ว")}
            var mode=modes.firstOrNull{it.upper==fps}?:modes.takeIf{!rear}?.minByOrNull{abs(it.upper-fps)}?.also{val before=fps;fps=it.upper;notice("กล้องนี้ไม่รองรับ $before fps · ใช้ $fps fps แทน")}?:error("${qualityName(quality)} ไม่รองรับ $fps fps ร่วมกับ HDR/กันสั่นที่เลือก · เลือกชุดใหม่")
            forced60=false
            if(mode.source==NEEDS_SDR){dynamic=DynamicRange.SDR;stabilization=false;prefs.edit().putBoolean("video_eis",false).apply();notice("60 fps ใช้ได้เมื่อปิด HDR และกันสั่น · ปิดให้แล้ว");mode=sessions.rates(info,quality,dynamic,false,false).first{it.upper==60}}
            half60=mode.source==HALF_HS
            if(half60){mode=VideoSessions.Mode(sessions.rates(info,quality,DynamicRange.SDR,false,true).first{it.upper==120}.range,HIGH_SPEED);notice("60 fps: อัดที่ 120 แล้วแปลงเป็น 60 หลังหยุด · ใช้เวลาครู่หนึ่ง")}
            highRt=mode.source==HIGH_SPEED
            if(highRt){dynamic=DynamicRange.SDR;stabilization=false;mode=VideoSessions.Mode(mode.range,Recorder.VIDEO_CAPABILITIES_SOURCE_CAMCORDER_PROFILE);boundConfig.put("fpsPath","high-speed-realtime")}
            if(mode.source==FORCED){forced60=true;mode=VideoSessions.Mode(mode.range,Recorder.VIDEO_CAPABILITIES_SOURCE_CAMCORDER_PROFILE)}
            rateLabel=sessions.label(mode)
            boundConfig.put("requestedFps",fps)
                .put("advertisedRange",mode.range.toString()).put("source",mode.source).put("dynamicRange",dynamic.toString()).put("eis",stabilization)
            val rb=Recorder.Builder().setVideoCapabilitiesSource(mode.source).setQualitySelector(QualitySelector.from(quality)).setTargetVideoEncodingBitRate(bitrate)
            val r=rb.build();val b=VideoCapture.Builder(r).setDynamicRange(dynamic).setTargetRotation(targetRotation)
            if(stabilization){check(caps.isStabilizationSupported){"กันสั่นวิดีโอไม่รองรับบนกล้องนี้"};b.setVideoStabilizationEnabled(true)}
            if(dual)b.setTargetFrameRate(mode.range)
            val pb=Preview.Builder()
            Camera2Interop.Extender(pb).setSessionCaptureCallback(diagnostics)
            if(logProfile!=LogProfile.STANDARD&&!slow&&!highRt&&logSupported(info)){applyLog(info,Camera2Interop.Extender(pb));applyLog(info,Camera2Interop.Extender(b));boundConfig.put("profile","luma-"+logProfile.key)}
            if(forced60){val r60=Range(60,60);Camera2Interop.Extender(pb).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,r60);Camera2Interop.Extender(b).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,r60);boundConfig.put("fpsPath","camera2-ae-target-60-experimental")}
            val v=b.build()
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
                val session=if(slow||highRt)HighSpeedVideoSessionConfig.Builder(v).setPreview(previewCase).setFrameRateRange(mode.range).setSlowMotionEnabled(slow).build() else {
                    val group=SessionConfig.Builder(previewCase,v).also{if(!forced60)it.setFrameRateRange(mode.range)}
                    videoRatio()?.let{group.setViewPort(ViewPort.Builder(Rational((it*1000).roundToInt(),1000),targetRotation).build())}
                    group.build()
                }
                camera=p.bindToLifecycle(this,selector(),session)
            }
            selectedCameraId=Camera2CameraInfo.from(info).cameraId;rear=info.lensFacing==CameraSelector.LENS_FACING_BACK
            recorder=r;useCase=v;manual=if(slow||highRt)null else ManualControls(this,camera!!,true,true,fps).also{it.onNotice={t->notice(t)}}
            camera!!.cameraInfo.zoomState.observe(this){refreshZoom()}
            faces.streamAspect=videoRatio()?:if(quality==Quality.SD)4f/3f else 16f/9f;configureFaces(faces,camera!!,null)
            fitPreview()
            saveSettings()
            config.text="${qualityName(quality)} · ${if(mode.lower<mode.upper)"≤"else ""}$fps${if(dynamic!=DynamicRange.SDR)" HDR" else ""}"
            status.text=if(dual)"หน้า + หลัง · ตรวจไฟล์หลังอัด" else "${if(mic)"ไมค์เปิด" else "ไมค์ปิด"} · ${if(stabilization)"กันสั่นเปิด" else "พร้อมบันทึก"}"
            shutter.isEnabled=true;shutter.alpha=1f;refreshChips()
        }catch(e:Exception){VideoDiagnostics.save(this,if(slow)"high-speed-bind"else "normal-bind",boundConfig,e);recorder=null;useCase=null;status.text="ชุดนี้ใช้ไม่ได้ · ${e.localizedMessage}\nแตะค่าด้านบนเพื่อเปลี่ยน"}
    }
    /** Frame shape chosen for the clip; null keeps the sensor's own shape. Movie mode defaults to cinema 2.39. */
    private val V_ASPECTS get()=listOf("auto")+Aspects.available(prefs)
    private fun aspectLabel(k:String)=when(k){"auto"->"ตามเซนเซอร์";"2.39"->"2.39:1";else->k}
    /** The viewfinder takes the shape of the clip (16:9, 21:9, 2.39:1…) and shows exactly the part that is recorded. */
    private fun fitPreview(){
        if(!::stage.isInitialized||!::preview.isInitialized)return
        val W=stage.width;val H=stage.height;if(W==0||H==0)return
        val land=resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val r=videoRatio()?:if(quality==Quality.SD)4f/3f else 16f/9f
        val a=if(land)r else 1f/r
        val w=minOf(W,(H*a).roundToInt());val h=(w/a).roundToInt()
        val lp=preview.layoutParams as FrameLayout.LayoutParams
        if(lp.width!=w||lp.height!=h){lp.width=w;lp.height=h;lp.gravity=Gravity.CENTER;preview.layoutParams=lp}
    }
    private fun videoRatio():Float?{val k=prefs.getString("video_aspect","auto")?:"auto"
        return if(k=="auto"||k !in Aspects.keys)null else Aspects.value(k,true)}
    private fun settings(){if(recording!=null||starting||finalizing){notice("หยุดคลิปก่อนเปลี่ยนรูปแบบวิดีโอ");return}
        val i=info()?:return
        if(slow&&Recorder.getHighSpeedVideoCapabilities(i)==null){Capabilities.show(this);return}
        studioSheet("วิดีโอ"){box,d->
            fun choose(title:String,items:List<String>,action:(Int)->Unit){box.addView(pill(title){AlertDialog.Builder(this).setTitle(title).setItems(items.toTypedArray()){_,n->action(n);d.dismiss();bind()}.setNegativeButton("ปิด",null).show()},-1,-2)}
            val candidates=provider?.availableCameraInfos.orEmpty().filter{cam->runCatching{if(slow)Recorder.getHighSpeedVideoCapabilities(cam)?.getSupportedQualities(DynamicRange.SDR)?.isNotEmpty()==true else Recorder.getVideoCapabilities(cam).getSupportedQualities(DynamicRange.SDR).isNotEmpty()}.getOrDefault(false)}
            choose("กล้อง / เลนส์ · ${Camera2CameraInfo.from(i).cameraId}",candidates.map{"กล้อง ${Camera2CameraInfo.from(it).cameraId} · ${if(it.lensFacing==CameraSelector.LENS_FACING_BACK)"หลัง"else "หน้า"}"}){selectedCameraId=Camera2CameraInfo.from(candidates[it]).cameraId}
            val caps=capabilities(i);val qualities=caps.getSupportedQualities(dynamic)
            choose("ความละเอียด · ${qualityName(quality)}",qualities.map{qualityName(it)}){quality=qualities[it]}
            val aspects=V_ASPECTS
            choose("ขนาดภาพ · ${aspectLabel(prefs.getString("video_aspect","auto")?:"auto")}",aspects.map{aspectLabel(it)}){prefs.edit().putString("video_aspect",aspects[it]).apply()}
            val frames=offered(i)
            choose("FPS · $fps",frames.map{rateName(it)}){fps=frames[it].upper}
            val ranges=listOf(DynamicRange.SDR,DynamicRange.HLG_10_BIT,DynamicRange.HDR10_10_BIT).filter{it in caps.supportedDynamicRanges}
            if(!slow)choose("SDR / HDR",ranges.map{when(it){DynamicRange.SDR->"SDR 8-bit";DynamicRange.HLG_10_BIT->"HLG10";else->"HDR10"}}){dynamic=ranges[it]}
            choose("Bitrate · ${bitrate/1_000_000} Mbps",listOf("8 Mbps","20 Mbps","40 Mbps","80 Mbps")){bitrate=listOf(8,20,40,80)[it]*1_000_000}
            box.addView(label("Codec กล้องเลือกตามโปรไฟล์ · เลือก H.264/HEVC ได้ตอนส่งออกคลิป",13f,MUTED),-1,-2)
            if(!slow&&caps.isStabilizationSupported)choose("กันสั่น EIS · $stabilization",listOf("ปิด","เปิด")){stabilization=it==1;prefs.edit().putBoolean("video_eis",stabilization).apply()}
            if(!slow)choose("ไมโครโฟน · ${if(mic)"เปิด" else "ปิด"}",listOf("ปิดไมค์","บันทึกเสียง")){mic=it==1;prefs.edit().putBoolean("video_mic",mic).apply()}
            if(!slow)box.addView(pill("คืนค่าชุดพื้นฐาน 1080p30 SDR"){dynamic=DynamicRange.SDR;fps=30;quality=Quality.FHD;hevc=false;stabilization=false;d.dismiss();bind()},-1,-2)
        }
    }
    private fun startRecording(){
        val r=recorder?:return;if(starting||finalizing||recording!=null)return
        if(filesDir.usableSpace<64L*1024*1024){notice("พื้นที่ว่างไม่พอเริ่มคลิป");return}
        starting=true;diagnostics.reset();useCase?.targetRotation=targetRotation;boundConfig.put("targetRotation",targetRotation);runCatching{val z=camera?.cameraInfo?.zoomState?.value?.zoomRatio?:1f;LensMeta.json(LensMeta.describe(this,selectedCameraId?:"0",null,z,z,rear))?.let{boundConfig.put("lens",it)}};shutter.isEnabled=false;config.isEnabled=false
        // Rotation would recreate the Activity and onPause would stop the clip, so hold the orientation until it finalises.
        requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
        val sound=prefs.getBoolean("video_sound",true)&&Sounds.enabled(prefs)
        if(sound)sounds.play(MediaActionSound.START_VIDEO_RECORDING)
        handler.postDelayed({
            if(!starting||isFinishing||isDestroyed){starting=false;requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;return@postDelayed}
            try{
                val name="LUMA_VIDEO_${System.currentTimeMillis()}.mp4"
                val values=ContentValues().apply{put(MediaStore.Video.Media.DISPLAY_NAME,name);put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");if(android.os.Build.VERSION.SDK_INT>=29)put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/Lumora/")}
                val options=MediaStoreOutputOptions.Builder(contentResolver,MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).setFileSizeLimit((filesDir.usableSpace-32L*1024*1024).coerceAtLeast(1)).build()
                var pending=r.prepareRecording(this,options)
                if(mic&&ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)pending=pending.withAudioEnabled()
                recording=pending.start(main){event->when(event){
                    is VideoRecordEvent.Start->{diagnostics.active(true);status.announceForAccessibility("เริ่มบันทึกวิดีโอ");starting=false;shutter.isEnabled=true;shutter.recording=true;shutter.contentDescription="หยุดบันทึกวิดีโอ";pause.isEnabled=true;recordingUi(true)}
                    is VideoRecordEvent.Status->{val seconds=event.recordingStats.recordedDurationNanos/1_000_000_000;status.text=String.format(Locale.US,"%02d:%02d  ·  %.1f MB%s",seconds/60,seconds%60,event.recordingStats.numBytesRecorded/1e6,if(paused)" · พัก" else "")}
                    is VideoRecordEvent.Pause->{diagnostics.active(false);paused=true;pauseIcon.path=Icons.VIDEO;pause.contentDescription="ต่อการบันทึก"}
                    is VideoRecordEvent.Resume->{diagnostics.active(true);paused=false;pauseIcon.path=PAUSE;pause.contentDescription="พักการบันทึก"}
                    is VideoRecordEvent.Finalize->{if(event.hasError())VideoDiagnostics.save(this,if(slow)"high-speed-finalize"else "normal-finalize",boundConfig,event.cause?:IllegalStateException("Recorder finalize code ${event.error}"));diagnostics.active(false);val sensor=diagnostics.snapshot();val requested=fps;val playback=if(slow)30 else fps;requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;recording=null;starting=false;finalizing=false;paused=false;shutter.recording=false;shutter.contentDescription="เริ่มบันทึกวิดีโอ";pause.isEnabled=false;pauseIcon.path=PAUSE;pause.contentDescription="พักการบันทึก";shutter.isEnabled=true;config.isEnabled=true;recordingUi(false)
                        if(sound&&!isDestroyed)sounds.play(MediaActionSound.STOP_VIDEO_RECORDING)
                        val uri=event.outputResults.outputUri
                        if(uri!=Uri.EMPTY&&event.recordingStats.numBytesRecorded>0){
                            if(half60&&!event.hasError()){
                                status.text="กำลังแปลงเป็น 60 fps…"
                                FrameRateConvert.run(this,uri,name,60f,"${qualityName(quality)} · 60 fps (แปลงจาก 120)"){converted->
                                    if(converted==null)MediaLedger.add(this,uri,name,"video/mp4","${qualityName(quality)} · 120 fps (แปลง 60 ไม่สำเร็จ)")
                                    if(!isDestroyed){status.text=if(converted!=null)"บันทึกวิดีโอ 60 fps แล้ว" else "แปลงเป็น 60 fps ไม่สำเร็จ · เก็บคลิป 120 fps ไว้แทน";status.announceForAccessibility(status.text)}
                                }
                            }else MediaLedger.add(this,uri,name,"video/mp4",if(event.hasError())"คลิปจบบางส่วน · รหัส ${event.error}" else "${qualityName(quality)} · เป้าหมาย $fps fps · ${if(dynamic==DynamicRange.SDR)"SDR" else "HDR"}")
                            if(!(half60&&!event.hasError()))status.text=if(event.hasError())"เก็บคลิปที่ได้รับแล้ว · ${event.error}" else "บันทึกวิดีโอแล้ว"
                            val capture=JSONObject(boundConfig.toString());val wasForced=forced60;val camKey=camera?.cameraInfo?.let{sessions.cameraKey(it)}?:"?";luma.fileIo.execute{ClipInspector.record(this,uri,name,requested,sensor,playback,capture)
                                if(wasForced&&!event.hasError())runCatching{val m=ClipInspector.inspect(this,uri).optDouble("measuredFps");if(m<50.0){sessions.failed60.add(camKey);prefs.edit().putStringSet("force60_failed_ids",HashSet(sessions.failed60)).apply();runOnUiThread{if(!isDestroyed)notice("คลิปนี้ได้ ${"%.0f".format(m)} fps ไม่ถึง 60 · ครั้งหน้าจะใช้แบบแปลงจาก 120")}}}}
                        }else status.text="บันทึกไม่สำเร็จ · ${event.cause?.localizedMessage?:event.error}"
                        status.announceForAccessibility(status.text)
                    }
                }}
            }catch(e:Exception){VideoDiagnostics.save(this,if(slow)"high-speed-start"else "normal-start",boundConfig,e);requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;starting=false;recording=null;shutter.isEnabled=true;config.isEnabled=true;status.text="เริ่มบันทึกไม่ได้ · ${e.localizedMessage}"}
        },if(sound)300 else 0)
    }
    // ---------- Mode strip (same items and order as the camera's) ----------
    private lateinit var modeStrip:CenterStrip;private var barKeys=listOf<String>();private val modeLabels=mutableListOf<Pair<TextView,IconView>>()
    private fun buildModeStrip(){
        val all=CameraActivity.ALL;val saved=prefs.getString("mode_bar",CameraActivity.DEFAULT_BAR)?:CameraActivity.DEFAULT_BAR
        val bar=saved.split(',').filter{k->all.any{it.key==k}}.let{if("PHOTO" in it)it else listOf("PHOTO")+it}
        barKeys=if(kind in bar)bar else bar+kind;modeLabels.clear()
        val paint=android.graphics.Paint().apply{textSize=12.5f*resources.displayMetrics.scaledDensity}
        modeStrip.setItems(barKeys.map{k->val m=all.first{it.key==k}
            val icon=IconView(this,m.icon).apply{tint=Glass.TEXT2};val label=text(m.title,12.5f,Glass.TEXT2).apply{gravity=Gravity.CENTER}
            modeLabels+=label to icon
            CenterStrip.Item(k,((paint.measureText(m.title)/resources.displayMetrics.density)+24).roundToInt().coerceAtLeast(56),LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;contentDescription="โหมด${m.title}";isClickable=true;isFocusable=true;addView(icon,dp(20),dp(20));addView(label,-2,-2)})
        },barKeys.indexOf(kind).coerceAtLeast(0))
    }
    private fun styleModes(live:Int){modeLabels.forEachIndexed{i,(t,ic)->val on=i==live;t.setTextColor(if(on)Glass.ACCENT else Glass.TEXT2);t.typeface=if(on)android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT;ic.tint=if(on)Glass.ACCENT else Glass.TEXT2}}
    private fun pickMode(i:Int){
        val key=barKeys.getOrNull(i)?:return
        if(key==kind)return
        if(busy()){notice("หยุดคลิปก่อนเปลี่ยนโหมด");modeStrip.select(barKeys.indexOf(kind),true);return}
        when(key){
            "VIDEO","MOVIE","SLOW"->{handler.removeCallbacks(rebind);intent=intent.putExtra("mode",key);loadSettings();openChip=null;category=1;provider?.unbindAll();camera=null;buildScreen();bind()}
            "TIMELAPSE"->{startActivity(Intent(this,SpecialVideoActivity::class.java).putExtra("mode",key));finish();noAnimation()}
            // Photo modes live in the camera screen: hand the choice back and close without a transition.
            else->{prefs.edit().putString("pending_mode",key).apply();finish();noAnimation()}
        }
    }
    @Suppress("DEPRECATION") private fun noAnimation()=overridePendingTransition(0,0)
    private val turner=IconTurner()
    private fun turnIcons(degrees:Int)=turner.update(degrees,{listOfNotNull(library.getChildAt(0),flip.getChildAt(0))+modeLabels.map{it.second}},Glass.reduced(this))
    private fun stopRecording(){animator?.cancel();finalizing=true;shutter.isEnabled=false;pause.isEnabled=false;status.text="กำลังปิดไฟล์…";recording?.stop()}
    private fun saveSettings(){prefs.edit().putString(if(slow)"slow_camera"else "video_camera",selectedCameraId).apply();if(slow){prefs.edit().putInt("slow_fps",fps).putString("slow_quality",when(quality){Quality.UHD->"UHD";Quality.HD->"HD";Quality.SD->"SD";else->"FHD"}).apply();return};prefs.edit().putString("video_quality",when(quality){Quality.UHD->"UHD";Quality.HD->"HD";Quality.SD->"SD";else->"FHD"}).putInt("video_fps",fps).putInt("video_bitrate",bitrate).putBoolean("video_eis",stabilization).putString("video_dynamic",when(dynamic){DynamicRange.HLG_10_BIT->"HLG";DynamicRange.HDR10_10_BIT->"HDR10";else->"SDR"}).apply()}
    override fun onResume(){super.onResume();orientation.enable();if(recording==null&&!starting&&!finalizing&&useCase!=null){shutter.isEnabled=true;config.isEnabled=true}}
    override fun onPause(){stopTracking();if(::zooms.isInitialized){zooms.finish();wheel.removeCallbacks(hideWheel);hideWheel.run()};orientation.disable();if(starting&&recording==null){requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT};starting=false;animator?.cancel();handler.removeCallbacksAndMessages(null);recording?.stop();super.onPause()}
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
        // Landscape keeps the dock beside the preview instead of under it.
        val body=LinearLayout(this).apply{orientation=if(landscape)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL};column.addView(body,LinearLayout.LayoutParams(-1,0,1f))
        stage=FrameLayout(this);body.addView(stage,if(landscape)LinearLayout.LayoutParams(0,-1,1f) else LinearLayout.LayoutParams(-1,0,1f))
        preview=PreviewView(this).apply{implementationMode=PreviewView.ImplementationMode.COMPATIBLE;scaleType=PreviewView.ScaleType.FILL_CENTER};stage.addView(preview,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));stage.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->stage.post{fitPreview()}};faces=FaceOverlay(this).apply{fill=false};stage.addView(faces,-1,-1)
        val overlay=CompositionOverlay(this).apply{gridKind=Grids.kind(prefs);movie=false};vOverlay=overlay;stage.addView(overlay,-1,-1)
        expRail=EdgeRail(this,false);stage.addView(expRail,FrameLayout.LayoutParams(dp(150),-1,Gravity.START))
        focusRail=EdgeRail(this,true);stage.addView(focusRail,FrameLayout.LayoutParams(dp(170),-1,Gravity.END))
        status=text("กำลังเปิดกล้อง…",13f).apply{gravity=Gravity.CENTER;background=glass(18);setPadding(dp(14),dp(7),dp(14),dp(7));maxLines=8;ellipsize=null;accessibilityLiveRegion=android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE}
        stage.addView(status,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply{topMargin=dp(62);leftMargin=dp(16);rightMargin=dp(16)})
        // No title bar: video uses the camera's own layout, so moving between photo and video modes reads as one screen.
        // The format pill sits where the camera shows its status icons.
        config=text(if(slow)"SLOW · ตัวเลือก" else "1080p · 30",13f,WHITE,true).apply{gravity=Gravity.CENTER;maxWidth=dp(170);maxLines=2;ellipsize=null;minHeight=dp(48);background=glass(22);setPadding(dp(14),0,dp(14),0);isClickable=true;contentDescription="รายละเอียดวิดีโอทั้งหมด";glassTouch();setOnClickListener{settings()}}
        stage.addView(config,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END).apply{setMargins(0,dp(6),dp(12),0)})
        config.visibility=android.view.View.GONE // settings open by swiping up the dock, as in photo mode
        // Slow-motion records no sound; say so where it stays visible, since the status line changes.
        if(slow)stage.addView(text("Slow-motion · ไม่มีเสียง",12.5f,Glass.TEXT2,true).apply{gravity=Gravity.CENTER;maxLines=3;ellipsize=null;maxWidth=dp(170);minHeight=dp(48);background=glass(22);setPadding(dp(14),0,dp(14),0)},FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.START).apply{setMargins(dp(12),dp(6),0,0)})
        wheel=ZoomWheel(this).apply{alpha=0f;visibility=android.view.View.VISIBLE};stage.addView(wheel,FrameLayout.LayoutParams(-1,dp(150),Gravity.BOTTOM).apply{bottomMargin=dp(4)})
        zooms=ZoomCapsule(this).apply{background=glass(28);setPadding(dp(4),dp(4),dp(4),dp(4));contentDescription="ระยะเลนส์ ลากซ้ายขวาเพื่อซูมละเอียด"}
        stage.addView(zooms,FrameLayout.LayoutParams(-2,dp(56),Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply{bottomMargin=dp(8)});zooms.visibility=android.view.View.GONE
        installZoomDrag()
        // Settings dial floats over the preview, chips sit in the dock.
        dial=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=glass(24);setPadding(dp(16),dp(12),dp(16),dp(12));visibility=android.view.View.GONE}
        val dh=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        dialTitle=text("",13f,Glass.TEXT2);dialValue=text("",20f,WHITE,true).apply{gravity=Gravity.END}
        dh.addView(dialTitle,LinearLayout.LayoutParams(0,-2,1f));dh.addView(dialValue,-2,-2);dial.addView(dh,-1,dp(30))
        slider=GlassSlider(this).apply{onChange={i->openChip?.let{applyChip(it,i)}}}
        val srow=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        fun nudge(d:Int){val k=openChip?:return;val t=(slider.index+d).coerceIn(0,slider.values.size-1);if(t!=slider.index){slider.index=t;Glass.tick(slider);applyChip(k,t)}}
        srow.addView(roundButton(IconView(this,Icons.MINUS,2.2f),48,"ลดหนึ่งขั้น"){nudge(-1)},dp(48),dp(48))
        srow.addView(slider,LinearLayout.LayoutParams(0,dp(56),1f))
        srow.addView(roundButton(IconView(this,Icons.PLUS,2.2f),48,"เพิ่มหนึ่งขั้น"){nudge(1)},dp(48),dp(48))
        dial.addView(srow,-1,-2)
        stage.addView(dial,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply{setMargins(dp(16),0,dp(16),dp(54))})

        val dock=SwipeColumn(this).apply{gravity=Gravity.CENTER_VERTICAL;onSwipeUp={settings()}};if(landscape)body.addView(dock,dp(320),-1) else body.addView(dock,-1,-2)
        androidx.core.view.ViewCompat.addAccessibilityAction(dock,"เปิดตั้งค่าวิดีโอ"){_,_->settings();true}
        if(!slow)dock.addView(IconView(this,Icons.CHEVRON_UP,1.6f).apply{tint=Glass.TEXT3;contentDescription="ตั้งค่าวิดีโอ ปัดขึ้นหรือแตะเพื่อเปิด";isClickable=true;setOnClickListener{settings()}},LinearLayout.LayoutParams(dp(120),dp(36)).apply{gravity=Gravity.CENTER_HORIZONTAL;bottomMargin=-dp(6)})
        if(!slow){
            tabs=FrameLayout(this).apply{background=glass(20,0x1fffffff)}
            knob=android.view.View(this).apply{background=glass(17,0x38ffffff)};tabs.addView(knob,FrameLayout.LayoutParams(0,-1).apply{setMargins(dp(3),dp(3),dp(3),dp(3))})
            val row=LinearLayout(this);tabLabels.clear()
            listOf("ปรับแต่ง" to "ปรับแต่งวิดีโอ","คุณภาพ" to "คุณภาพวิดีโอ").forEachIndexed{i,(t,desc)->val l=text(t,13.5f,if(i==category)WHITE else Glass.TEXT2,true).apply{gravity=Gravity.CENTER;isClickable=true;contentDescription=desc;maxLines=1;ellipsize=null;setOnClickListener{if(category!=i){category=i;slideKnob(true);closeDial();refreshChips()}}};tabLabels+=l;row.addView(l,LinearLayout.LayoutParams(0,-1,1f))}
            tabs.addView(row,-1,-1);tabs.post{slideKnob(false)}
            // Landscape has no room in the side dock, so the tabs float over the viewfinder there.
            if(landscape)stage.addView(tabs,FrameLayout.LayoutParams(dp(260),dp(48),Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply{bottomMargin=dp(72)})
            else dock.addView(tabs,LinearLayout.LayoutParams(-1,dp(48)).apply{setMargins(dp(16),dp(8),dp(16),dp(4))})
        }
        chipFrame=FrameLayout(this).apply{background=glass(24)}
        chips=CenterStrip(this).apply{capsuleHeightDp=44;onSelect={i->chipKeys().getOrNull(i)?.let{k->if(openChip==k)closeDial() else openDial(k)}};onLive={i->styleChips(i)}}
        chipFrame.addView(chips,FrameLayout.LayoutParams(-1,-1).apply{setMargins(dp(1),dp(1),dp(1),dp(1))})
        val grow=((resources.configuration.fontScale+1f)/2f).coerceAtLeast(1f)
        dock.addView(chipFrame,LinearLayout.LayoutParams(-1,(dp(56)*grow).roundToInt()).apply{setMargins(dp(16),dp(6),dp(16),dp(4))})
        modeStrip=CenterStrip(this).apply{onSelect={i->pickMode(i)};onLive={i->styleModes(i)};contentDescription="แถบโหมด"}
        dock.addView(modeStrip,-1,(dp(58)*grow).roundToInt())
        buildModeStrip()
        val actions=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(36),0,dp(36),0)}
        library=roundButton(IconView(this,Icons.LIBRARY),56,"แกลเลอรีวิดีโอ Lumora"){if(!busy())startActivity(Intent(this,GalleryActivity::class.java).putExtra("tab","luma").putExtra("category","วิดีโอ"))}
        pauseIcon=IconView(this,PAUSE,2.4f)
        pause=roundButton(pauseIcon,56,"พักการบันทึก"){val r=recording?:return@roundButton;if(paused)r.resume()else r.pause()}.apply{visibility=android.view.View.GONE;isEnabled=false}
        val left=FrameLayout(this).apply{addView(library,-1,-1);addView(pause,-1,-1)}
        actions.addView(left,dp(56),dp(56));actions.space()
        shutter=ShutterButton(this).apply{video=true;isEnabled=false;alpha=0.45f;contentDescription="เริ่มบันทึกวิดีโอ"
            // Inside apply{} a bare "recording" is ShutterButton.recording (a Boolean, never null), which made every tap stop.
            setOnClickListener{if(this@VideoActivity.recording!=null)stopRecording()else if(!starting&&!finalizing){if(mic&&ContextCompat.checkSelfPermission(this@VideoActivity,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)audioPermission.launch(Manifest.permission.RECORD_AUDIO)else startRecording()}}}
        actions.addView(shutter,dp(88),dp(88));actions.space()
        val flipIcon=IconView(this,Icons.FLIP)
        flip=roundButton(flipIcon,56,"สลับกล้องวิดีโอ"){if(!busy()&&!dual){flipIcon.animate().rotationBy(180f).setInterpolator(Glass.EASE).setDuration(420).start();rear=!rear;selectedCameraId=null;manualFocusOff();bind()}}
        if(dual)flip.alpha=0.35f
        actions.addView(flip,dp(56),dp(56));dock.addView(actions,-1,dp(104))
        val pinch=android.view.ScaleGestureDetector(this,object:android.view.ScaleGestureDetector.SimpleOnScaleGestureListener(){override fun onScale(d:android.view.ScaleGestureDetector):Boolean{if(zoomLocked())return true;val s=camera?.cameraInfo?.zoomState?.value?:return false;animator?.cancel();camera?.cameraControl?.setZoomRatio((s.zoomRatio*d.scaleFactor).coerceIn(s.minZoomRatio,s.maxZoomRatio));return true}})
        var axis=0;var zone=1;var sy=0f;var ev0=0f;var fd0=0f
        val taps=android.view.GestureDetector(this,object:android.view.GestureDetector.SimpleOnGestureListener(){
            override fun onDown(e:MotionEvent):Boolean{axis=0;sy=e.y;zone=if(e.x>preview.width*0.7f)2 else 1;ev0=vEv;fd0=focusRail.value;return true}
            override fun onScroll(first:MotionEvent?,last:MotionEvent,dx:Float,dy:Float):Boolean{if(first==null||zoomLocked()&&false)return false
                if(axis==0){if(abs(last.y-first.y)>dp(4)&&abs(last.y-first.y)>abs(last.x-first.x)){axis=1;sy=last.y}else if(abs(last.x-first.x)>dp(6))axis=2}
                if(axis!=1)return false;val move=last.y-sy
                if(zone==2){showRail(focusRail);setVideoFocus((fd0+move/dp(220)).coerceIn(0f,1f))}else{showRail(expRail);setVideoEv(((ev0-move/dp(90))*10).roundToInt()/10f)}
                return true}
            override fun onLongPress(e:MotionEvent){if(openChip==null&&!slow)startTracking(e.x,e.y)}
            override fun onSingleTapConfirmed(e:MotionEvent):Boolean{
                if(openChip!=null){closeDial();return true}
                stopTracking()
                val cam=camera?:return false;val point=preview.meteringPointFactory.createPoint(e.x,e.y)
                val op=androidx.camera.core.FocusMeteringAction.Builder(point).setAutoCancelDuration(3,java.util.concurrent.TimeUnit.SECONDS).build()
                if(cam.cameraInfo.isFocusMeteringSupported(op))cam.cameraControl.startFocusAndMetering(op)
                vOverlay?.focus(e.x,e.y);if(focusRail.value>0f)setVideoFocus(null);showRail(expRail);preview.performClick();return true}})
        // Two fingers belong to zoom only: the one-finger detector is cancelled so exposure, focus and long-press stay put.
        var multi=false
        preview.setOnTouchListener{_,e->
            if(e.actionMasked==MotionEvent.ACTION_DOWN)multi=false
            if(e.pointerCount>1&&!multi){multi=true;val c=MotionEvent.obtain(e).apply{action=MotionEvent.ACTION_CANCEL};taps.onTouchEvent(c);c.recycle()
                if(axis==1){if(zone==2)setVideoFocus(if(fd0>0f)fd0 else null) else setVideoEv(ev0)};axis=0}
            pinch.onTouchEvent(e);if(!multi)taps.onTouchEvent(e);true}
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
    /** Zoom pauses while an adjuster is open so a stray pinch never fights with it. */
    private fun zoomLocked()=openChip!=null
    private fun animateZoom(target:Float){
        if(zoomLocked())return
        val cam=camera?:return;val z=cam.cameraInfo.zoomState.value?:return;animator?.cancel()
        if(Glass.reduced(this)){cam.cameraControl.setZoomRatio(target);return}
        animator=ValueAnimator.ofFloat(ln(z.zoomRatio),ln(target)).apply{duration=280;interpolator=Glass.EASE;addUpdateListener{cam.cameraControl.setZoomRatio(exp(it.animatedValue as Float))};start()}
    }
    private val hideWheel=Runnable{wheel.show(false);zooms.animate().alpha(1f).setDuration(200).start()}
    private var vOverlay:CompositionOverlay?=null
    private fun manualFocusOff(){stopTracking()}
    private var trackBox:android.view.View?=null
    private val tracker=ObjectTracker{preview.smallFrame(96)}
    private var lastFocusX=-1f;private var lastFocusY=-1f;private var lastFocusAt=0L
    private fun focusAt(nx:Float,ny:Float){val cam=camera?:return
        val op=androidx.camera.core.FocusMeteringAction.Builder(preview.meteringPointFactory.createPoint(nx*preview.width,ny*preview.height)).disableAutoCancel().build()
        if(cam.cameraInfo.isFocusMeteringSupported(op))cam.cameraControl.startFocusAndMetering(op)
        lastFocusX=nx;lastFocusY=ny;lastFocusAt=android.os.SystemClock.uptimeMillis()}
    private val trackStep=object:Runnable{override fun run(){
        if(!tracker.active||isDestroyed)return
        if(!tracker.step()){stopTracking();notice("วัตถุหลุดจากการติดตาม");return}
        trackBox?.let{b->b.animate().translationX(preview.left+tracker.x*preview.width-b.width/2f).translationY(preview.top+tracker.y*preview.height-b.height/2f).setDuration(120).start()}
        // Refocus when the subject has moved noticeably, or now and then so a change in distance is followed too.
        val moved=abs(tracker.x-lastFocusX)+abs(tracker.y-lastFocusY)
        if(moved>0.07f||android.os.SystemClock.uptimeMillis()-lastFocusAt>1400)focusAt(tracker.x,tracker.y)
        handler.postDelayed(this,130)}}
    private fun startTracking(x:Float,y:Float){
        if(preview.width==0||!tracker.start(x/preview.width,y/preview.height)){notice("เริ่มติดตามวัตถุไม่ได้");return}
        val b=trackBox?:TrackMarker(this).apply{(preview.parent as? android.view.ViewGroup)?.addView(this,FrameLayout.LayoutParams(dp(80),dp(80)));trackBox=this}
        b.visibility=android.view.View.VISIBLE;b.translationX=preview.left+x-dp(40);b.translationY=preview.top+y-dp(40);b.scaleX=1.3f;b.scaleY=1.3f;b.animate().scaleX(1f).scaleY(1f).setDuration(220).start()
        if((manual?.minFocus?:0f)>0f&&focusRail.value>0f)setVideoFocus(null)
        focusAt(tracker.x,tracker.y);Glass.tick(preview,android.view.HapticFeedbackConstants.LONG_PRESS);notice("กำลังติดตาม · แตะเพื่อยกเลิก")
        handler.removeCallbacks(trackStep);handler.postDelayed(trackStep,130)
    }
    private fun stopTracking(){if(!tracker.active&&trackBox?.visibility!=android.view.View.VISIBLE)return;tracker.stop();handler.removeCallbacks(trackStep);trackBox?.visibility=android.view.View.GONE;camera?.cameraControl?.cancelFocusAndMetering()}
    private lateinit var expRail:EdgeRail;private lateinit var focusRail:EdgeRail
    private var vEv=0f
    private val hideRails=Runnable{expRail.show(false);focusRail.show(false)}
    private fun showRail(r:EdgeRail){r.show(true);r.removeCallbacks(hideRails);r.postDelayed(hideRails,1600)}
    private fun setVideoEv(v:Float){vEv=v.coerceIn(-2f,2f);expRail.value=vEv/2;expRail.label=(if(vEv>0)"+" else if(vEv<0)"" else "±")+String.format(java.util.Locale.US,"%.1f",vEv)
        val st=camera?.cameraInfo?.exposureState?:return;if(!st.isExposureCompensationSupported)return
        val idx=(vEv/st.exposureCompensationStep.toFloat()).roundToInt().coerceIn(st.exposureCompensationRange.lower,st.exposureCompensationRange.upper)
        if(idx!=st.exposureCompensationIndex){camera?.cameraControl?.setExposureCompensationIndex(idx);if(idx==0)Glass.tick(expRail)}}
    private fun setVideoFocus(f:Float?){val m=manual;if(f==null||m==null){m?.setFocus(null);focusRail.value=0f;focusRail.label="AF";return}
        focusRail.value=f.coerceIn(0f,1f);m.setFocus(focusRail.value*m.minFocus);focusRail.label="MF"}
    private var dragZoom0=1f
    private fun installZoomDrag(){
        fun start(){if(zoomLocked())return;dragZoom0=camera?.cameraInfo?.zoomState?.value?.zoomRatio?:1f;wheel.removeCallbacks(hideWheel);animator?.cancel();zooms.animate().alpha(0f).setDuration(200).start();wheel.show(true)}
        fun move(dx:Float){if(zoomLocked())return;val z=camera?.cameraInfo?.zoomState?.value?:return
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
        val quality=if(slow)listOf("RES","FPS","RATE") else listOf("RES","ASP","FPS","HDR","LOG","RATE","EIS","MIC")
        val adjust=buildList{val m=manual;add("ALL");if(m!=null&&m.canManual){add("ISO");add("S")};add("WB");if((m?.minFocus?:0f)>0f)add("AF");add("EV")}
        return if(!slow&&category==0)adjust else quality
    }
    private fun chipTitle(k:String)=when(k){"ALL"->"ทั้งหมด";"RES"->"ความละเอียด";"ASP"->"ขนาดภาพ";"FPS"->"FPS";"HDR"->"ช่วงไดนามิก";"RATE"->"Bitrate";"LOG"->"โปรไฟล์";"EIS"->"กันสั่น";"MIC"->"ไมค์";"ISO"->"ISO";"S"->"ชัตเตอร์";"WB"->"WB";"AF"->"โฟกัส";else->"EV"}
    private fun caps()=info()?.let{runCatching{capabilities(it)}.getOrNull()}
    /** Every quality either the maker's profiles or the device's encoders allow (the second list is often longer). */
    private fun qualities()=info()?.let{allQualities(it)}?.takeIf{it.isNotEmpty()}?:listOf(quality)
    private fun allQualities(info:CameraInfo):List<Quality>{
        if(slow)return runCatching{capabilities(info).getSupportedQualities(dynamic)}.getOrDefault(emptyList())
        val found=listOf(Recorder.VIDEO_CAPABILITIES_SOURCE_CAMCORDER_PROFILE,Recorder.VIDEO_CAPABILITIES_SOURCE_CODEC_CAPABILITIES).flatMap{src->runCatching{Recorder.getVideoCapabilities(info,src).getSupportedQualities(dynamic)}.getOrDefault(emptyList())}
        return listOf(Quality.UHD,Quality.FHD,Quality.HD,Quality.SD).filter{it in found}
    }
    /** FPS offered for the current quality/HDR/EIS set, as the session query reports it (never the camera-wide AE list). */
    private fun rateModes()=info()?.let{offered(it)}.orEmpty()
    private var forced60=false
    private lateinit var faces:FaceOverlay
    private val NEEDS_SDR=-2;private val FORCED=-1;private val HIGH_SPEED=-3;private val HALF_HS=-4;private var highRt=false;private var half60=false
    /**
     * FPS choices: what the session query reports, plus 60 fps when it is only blocked by HDR/EIS (choosing it turns
     * those off) or, failing that, when the Camera2 tables allow it (an experimental clip checked after recording).
     */
    private fun offered(info:CameraInfo):List<VideoSessions.Mode>{
        val base=sessions.rates(info,quality,dynamic,stabilization,slow)
        if(slow||dual||base.any{it.upper>=60})return base
        val plain=if(dynamic!=DynamicRange.SDR||stabilization)sessions.rates(info,quality,DynamicRange.SDR,false,false) else emptyList()
        val extra=when{plain.any{it.upper==60}->VideoSessions.Mode(Range(60,60),NEEDS_SDR);sessions.camera2Allows60(info,quality)->VideoSessions.Mode(Range(60,60),FORCED);else->null}
        // High-speed sessions recorded at normal speed: smooth 120 fps files on phones that lock 60 fps to their own app.
        val fast=if(kind=="VIDEO")sessions.rates(info,quality,DynamicRange.SDR,false,true).minByOrNull{it.upper}?.let{VideoSessions.Mode(it.range,HIGH_SPEED)} else null
        // Phones that keep plain 60 fps for their own camera: record the 120 fps high-speed stream and keep every other frame.
        val half=if(extra==null&&fast!=null&&fast.upper==120)VideoSessions.Mode(Range(60,60),HALF_HS) else null
        return (base+listOfNotNull(extra,half,fast)).sortedBy{it.upper}
    }
    private fun rateName(m:VideoSessions.Mode)=when(m.source){NEEDS_SDR->"60 fps · ปิด HDR/กันสั่นให้";HALF_HS->"60 fps · แปลงจาก 120 หลังอัด";FORCED->"60 fps";HIGH_SPEED->"${m.upper} fps · ความเร็วปกติ (high-speed)";else->sessions.label(m)}
    private fun frameRates()=rateModes().map{it.upper}.ifEmpty{listOf(fps)}
    private fun ranges()=listOf(DynamicRange.SDR,DynamicRange.HLG_10_BIT,DynamicRange.HDR10_10_BIT).filter{caps()?.supportedDynamicRanges?.contains(it)?:(it==DynamicRange.SDR)}
    private fun evValues():List<Float>{val st=camera?.cameraInfo?.exposureState?:return listOf(0f);if(!st.isExposureCompensationSupported)return listOf(0f);val step=st.exposureCompensationStep.toFloat();val r=st.exposureCompensationRange;return(r.lower..r.upper).map{it*step}.filter{abs(it)<=4.01f}}
    private fun chipValues(k:String):List<String>{val m=manual
        return when(k){
            "ALL"->listOf("AUTO");"ASP"->V_ASPECTS.map{aspectLabel(it)};"RES"->qualities().map{qualityName(it)};"FPS"->rateModes().let{m->if(m.isEmpty())listOf("$fps") else m.map{x->when(x.source){NEEDS_SDR->"60 SDR";FORCED->"60";HIGH_SPEED->"${x.upper} HS";else->if(x.lower<x.upper)"≤${x.upper}" else "${x.upper}"}}};"HDR"->ranges().map{when(it){DynamicRange.SDR->"SDR";DynamicRange.HLG_10_BIT->"HLG10";else->"HDR10"}}
            "RATE"->listOf("8","20","40","80").map{"$it Mbps"};"LOG"->if(info()?.let{logSupported(it)}==true)LogProfile.entries.map{it.label} else listOf("มาตรฐาน");"EIS"->listOf("ปิด","เปิด");"MIC"->listOf("ปิด","เปิด")
            "ISO"->listOf("AUTO")+(m?.isoValues?:emptyList()).map{"$it"};"S"->listOf("AUTO")+(m?.shutterValues?:emptyList()).map{it.first}
            "WB"->(m?.wbValues?:listOf("AUTO" to 1)).map{it.first};"AF"->(m?.focusValues?:listOf("AUTO" to null)).map{it.first}
            else->evValues().map{(if(it>0)"+" else if(it==0f)"±" else "")+String.format(Locale.US,"%.1f",it)}
        }}
    private fun chipIndex(k:String):Int{val m=manual
        return when(k){
            "ALL"->0;"ASP"->V_ASPECTS.indexOf(prefs.getString("video_aspect","auto")).coerceAtLeast(0);"RES"->qualities().indexOf(quality).coerceAtLeast(0);"FPS"->frameRates().indexOf(fps).coerceAtLeast(0);"HDR"->ranges().indexOf(dynamic).coerceAtLeast(0)
            "RATE"->listOf(8,20,40,80).indexOf(bitrate/1_000_000).coerceAtLeast(0);"LOG"->if(info()?.let{logSupported(it)}==true)logProfile.ordinal else 0;"EIS"->if(stabilization)1 else 0;"MIC"->if(mic)1 else 0
            "ISO"->m?.currentIso?.let{(m.isoValues.indexOf(it)+1).coerceAtLeast(0)}?:0
            "S"->m?.currentShutter?.let{s->(m.shutterValues.indexOfFirst{it.second==s}+1).coerceAtLeast(0)}?:0
            "WB"->(m?.wbValues?.indexOfFirst{it.second==m.currentWb}?:0).coerceAtLeast(0)
            "AF"->m?.currentFocus?.let{f->if(m.minFocus<=0f)0 else 1+((f/m.minFocus)*48).roundToInt().coerceIn(0,48)}?:0
            else->{val st=camera?.cameraInfo?.exposureState;val cur=st?.let{it.exposureCompensationIndex*it.exposureCompensationStep.toFloat()}?:0f;evValues().indexOfFirst{abs(it-cur)<0.01f}.coerceAtLeast(0)}
        }}
    private fun chipGrad(k:String)=when(k){
        "ISO"->intArrayOf(0xff5ac8fa.toInt(),0xff7be3b0.toInt(),Glass.ACCENT,0xffff9f0a.toInt(),0xffff453a.toInt())
        "S"->intArrayOf(0xff64d2ff.toInt(),0xffbf9bff.toInt(),Glass.ACCENT,0xffff9f0a.toInt())
        "WB"->intArrayOf(0xffff9a3d.toInt(),0xffffd9a8.toInt(),WHITE,0xffcfe5ff.toInt(),0xff7db8ff.toInt())
        "AF"->intArrayOf(0xff8e8e93.toInt(),0xffbbf0ff.toInt(),0xff5ac8fa.toInt())
        "ASP","RES","RATE","FPS"->intArrayOf(0xff8e8e93.toInt(),WHITE,Glass.ACCENT)
        else->intArrayOf(0xff8e8e93.toInt(),WHITE)}
    private fun refreshChips(){
        if(!::chips.isInitialized)return
        val keys=chipKeys();chipTexts.clear()
        chips.setItems(keys.map{k->val t=text(chipTitle(k),10.5f,Glass.TEXT2).apply{gravity=Gravity.CENTER;maxLines=1};val v=text(chipValues(k).getOrElse(chipIndex(k)){"–"},13f,WHITE,true).apply{gravity=Gravity.CENTER;maxLines=1}
            chipTexts+=t to v
            val wDp=(chipValues(k).plus(chipTitle(k)).maxOf{v.paint.measureText(it)}/resources.displayMetrics.density+24).roundToInt().coerceAtLeast(78)
            CenterStrip.Item(k,wDp,LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;isClickable=true;contentDescription=chipTitle(k);addView(t,-2,-2);addView(v,-2,-2)})},
            keys.indexOf(openChip).coerceAtLeast(0))
        chipFrame.alpha=if(recording!=null&&!(!slow&&category==0))0.45f else 1f
    }
    private fun styleChips(live:Int){val keys=chipKeys();chipTexts.forEachIndexed{i,(t,v)->val on=i==live;t.setTextColor(if(on)WHITE else Glass.TEXT3);v.text=chipValues(keys[i]).getOrElse(chipIndex(keys[i])){"–"}}}
    private fun openDial(k:String){
        if(k=="ALL"){closeDial();manual?.reset();camera?.cameraControl?.setExposureCompensationIndex(0);notice("ทุกค่ากลับเป็น AUTO");refreshChips();return}
        val quality=k in listOf("RES","ASP","FPS","HDR","LOG","RATE","EIS","MIC")
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
            "ASP"->prefs.edit().putString("video_aspect",V_ASPECTS.getOrElse(i){"auto"}).apply()
            "RES"->qualities().getOrNull(i)?.let{quality=it};"FPS"->frameRates().getOrNull(i)?.let{fps=it};"HDR"->ranges().getOrNull(i)?.let{dynamic=it}
            "RATE"->bitrate=listOf(8,20,40,80)[i.coerceIn(0,3)]*1_000_000
            "LOG"->{logProfile=LogProfile.entries.getOrElse(i){LogProfile.STANDARD};prefs.edit().putString("video_log_profile",logProfile.key).putBoolean("video_log",logProfile!=LogProfile.STANDARD).apply();if(logProfile!=LogProfile.STANDARD)notice(logProfile.note)}
            "EIS"->{stabilization=i==1;prefs.edit().putBoolean("video_eis",stabilization).apply()}
            "MIC"->{mic=i==1;prefs.edit().putBoolean("video_mic",mic).apply()}
            "ISO"->m?.setIso(if(i<=0)null else m.isoValues.getOrNull(i-1))
            "S"->m?.setShutter(if(i<=0)null else m.shutterValues.getOrNull(i-1)?.second)
            "WB"->m?.wbValues?.getOrNull(i)?.let{m.setWb(it.second)}
            "AF"->m?.setFocus(m.focusValues.getOrNull(i)?.second)
            else->evValues().getOrNull(i)?.let{v->val st=camera?.cameraInfo?.exposureState;if(st!=null&&st.isExposureCompensationSupported)camera?.cameraControl?.setExposureCompensationIndex((v/st.exposureCompensationStep.toFloat()).roundToInt())}
        }
        dialValue.text=chipValues(k).getOrElse(i){"–"};styleChips(chips.selected)
        if(k in listOf("RES","ASP","FPS","HDR","RATE","EIS","LOG")){configPending();handler.removeCallbacks(rebind);handler.postDelayed(rebind,450)}
        else if(k=="MIC")status.text=if(mic)"ไมค์เปิด" else "ไมค์ปิด"
    }
    // ---------- Lumora Log ----------
    /** Flat "Log" look for grading: a logarithmic tone curve, no in-camera sharpening, light noise reduction.
     *  It is not Canon C-Log or any vendor profile; HLG10 under ช่วงไดนามิก is the true 10-bit option. */
    private var logProfile=LogProfile.STANDARD
    private fun logSupported(info:CameraInfo)=runCatching{Camera2CameraInfo.from(info).getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.contains(android.hardware.camera2.CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)==true}.getOrDefault(false)
    private fun <T> applyLog(info:CameraInfo,e:Camera2Interop.Extender<T>){
        val cc=Camera2CameraInfo.from(info)
        val pts=logProfile.curve()
        e.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.TONEMAP_MODE,android.hardware.camera2.CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
        e.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.TONEMAP_CURVE,android.hardware.camera2.params.TonemapCurve(pts,pts,pts))
        // Only modes the camera lists, so an unsupported value can never fail the session.
        if(cc.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.contains(android.hardware.camera2.CaptureRequest.EDGE_MODE_OFF)==true)
            e.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.EDGE_MODE,android.hardware.camera2.CaptureRequest.EDGE_MODE_OFF)
        if(cc.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.contains(android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE_FAST)==true)
            e.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE,android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE_FAST)
    }
    /** State hook: the chosen set is not bound yet, so recording waits until bind() succeeds and re-enables the shutter. */
    private fun configPending(){shutter.isEnabled=false;shutter.alpha=0.45f;status.text="กำลังเปลี่ยนชุดวิดีโอ…"}
    private fun qualityName(q:Quality)=when(q){Quality.UHD->"4K";Quality.FHD->"1080p";Quality.HD->"720p";else->"SD"}
}

