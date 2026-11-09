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
    private var selectedCameraId:String?=null
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
        selectedCameraId=prefs.getString(if(slow)"slow_camera"else "video_camera",null)
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
        config=pill(if(slow)"Slow-motion · ตัวเลือก"else "1080p · 30",::settings);head.addView(config,-2,-2);panel.addView(head,-1,-2)
        val frame=FrameLayout(this);root.addView(frame,if(landscape)LinearLayout.LayoutParams(0,-1,1f) else LinearLayout.LayoutParams(-1,0,1f))
        preview=PreviewView(this).apply{implementationMode=PreviewView.ImplementationMode.COMPATIBLE;scaleType=PreviewView.ScaleType.FIT_CENTER};frame.addView(preview,-1,-1)
        val overlay=CompositionOverlay(this).apply{grid=prefs.getBoolean("grid",false);movie=kind=="MOVIE"};frame.addView(overlay,-1,-1)
        status=label("กำลังเปิดกล้อง…",14f).apply{gravity=Gravity.CENTER;background=shape(0x88000000.toInt());setPadding(dp(10),dp(4),dp(10),dp(4));minHeight=dp(40)}
        frame.addView(status,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply{topMargin=dp(12);leftMargin=dp(12);rightMargin=dp(12)})
        ruler=ZoomRuler(this){animator?.cancel();camera?.cameraControl?.setZoomRatio(it)}
        frame.addView(ruler,FrameLayout.LayoutParams(-1,dp(64),Gravity.BOTTOM).apply{leftMargin=dp(28);rightMargin=dp(28);bottomMargin=dp(16)})
        val tools=LinearLayout(this).apply{gravity=Gravity.CENTER}
        tools.addView(pill(if(kind=="MOVIE")"MOVIE · PRO" else "PRO"){if(slow)notice("Slow-motion ใช้ค่าแสงอัตโนมัติของ high-speed session")else manual?.show()},0,-2);(tools.getChildAt(0).layoutParams as LinearLayout.LayoutParams).weight=1f
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
        actions.addView(Glyph(this,"flip","สลับกล้องวิดีโอ"){if(recording==null&&!starting&&!finalizing&&!dual){rear=!rear;selectedCameraId=null;bind()}},dp(56),dp(56));actions.space();panel.addView(actions,-1,dp(112))
        panel.addView(pill("คลังวิดีโอ / แต่งคลิป"){if(recording==null&&!starting)startActivity(Intent(this,StudioLibraryActivity::class.java))},-1,-2)
        if(landscape)root.addView(ScrollView(this).apply{isFillViewport=true;addView(panel)},LinearLayout.LayoutParams(dp(300),-1))
        val pinch=android.view.ScaleGestureDetector(this,object:android.view.ScaleGestureDetector.SimpleOnScaleGestureListener(){override fun onScale(d:android.view.ScaleGestureDetector):Boolean{val s=camera?.cameraInfo?.zoomState?.value?:return false;animator?.cancel();camera?.cameraControl?.setZoomRatio((s.zoomRatio*d.scaleFactor).coerceIn(s.minZoomRatio,s.maxZoomRatio));return true}})
        preview.setOnTouchListener{_,e->pinch.onTouchEvent(e);if(e.actionMasked==MotionEvent.ACTION_UP)preview.performClick();true}
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
        val p=provider?:return;shutter.isEnabled=false;status.text="กำลังตรวจชุดวิดีโอ…"
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
            camera!!.cameraInfo.zoomState.observe(this){ruler.update(it.minZoomRatio,it.maxZoomRatio,it.zoomRatio)}
            saveSettings()
            config.text="${qualityName(quality)} · ${if(mode.lower<mode.upper)"≤"else ""}$fps${if(dynamic!=DynamicRange.SDR)" HDR" else ""}"
            status.text=if(dual)"หน้า + หลัง · ตรวจไฟล์หลังอัด" else "${if(mic)"ไมค์เปิด" else "ไมค์ปิด"} · ${if(stabilization)"กันสั่นเปิด" else "พร้อมบันทึก"}"
            shutter.isEnabled=true
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
                    is VideoRecordEvent.Start->{diagnostics.active(true);status.announceForAccessibility("เริ่มบันทึกวิดีโอ");starting=false;shutter.isEnabled=true;shutter.recording=true;shutter.contentDescription="หยุดบันทึกวิดีโอ";pause.isEnabled=true}
                    is VideoRecordEvent.Status->{val seconds=event.recordingStats.recordedDurationNanos/1_000_000_000;status.text=String.format(Locale.US,"%02d:%02d  ·  %.1f MB%s",seconds/60,seconds%60,event.recordingStats.numBytesRecorded/1e6,if(paused)" · พัก" else "")}
                    is VideoRecordEvent.Pause->{diagnostics.active(false);paused=true;pause.text="ต่อ"}
                    is VideoRecordEvent.Resume->{diagnostics.active(true);paused=false;pause.text="พัก"}
                    is VideoRecordEvent.Finalize->{if(event.hasError())VideoDiagnostics.save(this,if(slow)"high-speed-finalize"else "normal-finalize",boundConfig,event.cause?:IllegalStateException("Recorder finalize code ${event.error}"));diagnostics.active(false);val sensor=diagnostics.snapshot();val requested=fps;val playback=if(slow)30 else fps;requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;recording=null;starting=false;finalizing=false;paused=false;shutter.recording=false;shutter.contentDescription="เริ่มบันทึกวิดีโอ";pause.isEnabled=false;pause.text="พัก";shutter.isEnabled=true;config.isEnabled=true
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
        box.addView(pill("กำหนด A ที่ระยะปัจจุบัน"){zoomA=camera?.cameraInfo?.zoomState?.value?.zoomRatio?:1f;notice("A = ${zoomText(zoomA)}")},-1,-2)
        box.addView(pill("กำหนด B ที่ระยะปัจจุบัน"){zoomB=camera?.cameraInfo?.zoomState?.value?.zoomRatio?:3f;notice("B = ${zoomText(zoomB)}")},-1,-2)
        val row=LinearLayout(this);listOf(2L,4L,8L).forEach{seconds->row.addView(pill("${seconds}s"){zoomDuration=seconds},LinearLayout.LayoutParams(0,-2,1f))};box.addView(row)
        box.addView(Switch(this).apply{text="เริ่มและจบแบบนุ่ม";setTextColor(WHITE);isChecked=smooth;setOnCheckedChangeListener{_,v->smooth=v}},-1,-2)
        box.addView(pill("เล่น ${zoomText(zoomA)} → ${zoomText(zoomB)}"){d.dismiss();val cam=camera?:return@pill;val z=cam.cameraInfo.zoomState.value?:return@pill;animator?.cancel();animator=ValueAnimator.ofFloat(ln(zoomA.coerceIn(z.minZoomRatio,z.maxZoomRatio)),ln(zoomB.coerceIn(z.minZoomRatio,z.maxZoomRatio))).apply{duration=zoomDuration*1000;interpolator=if(smooth)android.view.animation.AccelerateDecelerateInterpolator()else android.view.animation.LinearInterpolator();addUpdateListener{cam.cameraControl.setZoomRatio(exp(it.animatedValue as Float))};start()}},-1,-2)
    }}
    private fun saveSettings(){prefs.edit().putString(if(slow)"slow_camera"else "video_camera",selectedCameraId).apply();if(slow){prefs.edit().putInt("slow_fps",fps).putString("slow_quality",when(quality){Quality.UHD->"UHD";Quality.HD->"HD";Quality.SD->"SD";else->"FHD"}).apply();return};prefs.edit().putString("video_quality",when(quality){Quality.UHD->"UHD";Quality.HD->"HD";Quality.SD->"SD";else->"FHD"}).putInt("video_fps",fps).putInt("video_bitrate",bitrate).putBoolean("video_eis",stabilization).putString("video_dynamic",when(dynamic){DynamicRange.HLG_10_BIT->"HLG";DynamicRange.HDR10_10_BIT->"HDR10";else->"SDR"}).apply()}
    override fun onResume(){super.onResume();orientation.enable();if(recording==null&&!starting&&!finalizing&&useCase!=null){shutter.isEnabled=true;config.isEnabled=true}}
    override fun onPause(){orientation.disable();if(starting&&recording==null){requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED};starting=false;animator?.cancel();handler.removeCallbacksAndMessages(null);recording?.stop();super.onPause()}
    override fun onDestroy(){sounds.release();super.onDestroy()}
    override fun onKeyDown(keyCode:Int,event:KeyEvent):Boolean{if(keyCode==KeyEvent.KEYCODE_VOLUME_DOWN||keyCode==KeyEvent.KEYCODE_VOLUME_UP){if(event.repeatCount==0)shutter.performClick();return true};return super.onKeyDown(keyCode,event)}
    private fun qualityName(q:Quality)=when(q){Quality.UHD->"4K";Quality.FHD->"1080p";Quality.HD->"720p";else->"SD"}
}
