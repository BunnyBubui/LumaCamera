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
    private lateinit var zooms:LinearLayout;private lateinit var wheel:ZoomWheel
    private lateinit var tabs:FrameLayout;private lateinit var knob:View;private val tabLabels=mutableListOf<TextView>()
    private lateinit var chipFrame:FrameLayout;private lateinit var chips:CenterStrip;private val chipTexts=mutableListOf<Pair<TextView,TextView>>()
    private lateinit var dial:LinearLayout;private lateinit var dialTitle:TextView;private lateinit var dialValue:TextView;private lateinit var slider:GlassSlider
    private var category=0;private var openChip:String?=null;private var zoomStops:List<Float> = emptyList()
    private val rebind=Runnable{bind()}
    private val sounds=MediaActionSound()
    private val kind get()=intent.getStringExtra("mode")?:"VIDEO"
    private val dual get()=kind=="DUAL"
    private val permission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)bind()else status.text="อนุญาตกล้องเพื่อเริ่มวิดีโอ"}
    private val audioPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){allowed->if(!allowed){mic=false;notice("ปิดไมค์สำหรับคลิปนี้ เพราะยังไม่ได้อนุญาตเสียง")};startRecording()}
    override fun onCreate(state:Bundle?){
        super.onCreate(state);mic=prefs.getBoolean("video_mic",true)
        sounds.load(MediaActionSound.START_VIDEO_RECORDING);sounds.load(MediaActionSound.STOP_VIDEO_RECORDING)
        stabilization=prefs.getBoolean("video_eis",false)
        buildScreen()
        ProcessCameraProvider.getInstance(this).also{f->f.addListener({runCatching{provider=f.get();if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)bind()else permission.launch(arrayOf(Manifest.permission.CAMERA))}.onFailure{status.text=it.localizedMessage}},main)}
    }
    private fun selector()=if(rear)CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
    private fun info()=provider?.availableCameraInfos?.let{selector().filter(it).firstOrNull()}
    @Suppress("DEPRECATION")
    private fun bind(){
        if(recording!=null||starting||finalizing)return
        val p=provider?:return;shutter.isEnabled=false;shutter.alpha=0.45f;status.text="กำลังตรวจชุดวิดีโอ…"
        try{
            val info=info()?:error("ไม่พบกล้อง")
            val caps=Recorder.getVideoCapabilities(info)
            check(dynamic in caps.supportedDynamicRanges){"กล้องนี้ไม่รองรับ HDR ที่เลือก"}
            val supported=caps.getSupportedQualities(dynamic)
            if(quality !in supported){quality=supported.firstOrNull{it==Quality.FHD}?:supported.firstOrNull()?:error("ไม่พบโปรไฟล์วิดีโอ")}
            val rb=Recorder.Builder().setQualitySelector(QualitySelector.from(quality)).setTargetVideoEncodingBitRate(bitrate)
            val r=rb.build();val b=VideoCapture.Builder(r).setDynamicRange(dynamic).setTargetFrameRate(Range(fps,fps)).setTargetRotation(windowManager.defaultDisplay.rotation)
            if(stabilization){check(caps.isStabilizationSupported){"กันสั่นวิดีโอไม่รองรับบนกล้องนี้"};b.setVideoStabilizationEnabled(true)}
            val v=b.build();val previewCase=Preview.Builder().build().apply{setSurfaceProvider(preview.surfaceProvider)}
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
                val group=UseCaseGroup.Builder().addUseCase(previewCase).addUseCase(v)
                if(kind=="MOVIE")group.setViewPort(ViewPort.Builder(Rational(239,100),windowManager.defaultDisplay.rotation).build())
                camera=p.bindToLifecycle(this,selector(),group.build())
            }
            recorder=r;useCase=v;manual=ManualControls(this,camera!!,true,true,fps)
            camera!!.cameraInfo.zoomState.observe(this){refreshZoom()}
            config.text="${qualityName(quality)} · $fps${if(dynamic!=DynamicRange.SDR)" HDR" else ""}";refreshChips()
            status.text=if(dual)"หน้า + หลัง · ตรวจไฟล์หลังอัด" else "${if(mic)"ไมค์เปิด" else "ไมค์ปิด"} · ${if(stabilization)"กันสั่นเปิด" else "พร้อมบันทึก"}"
            shutter.isEnabled=true;shutter.alpha=1f
        }catch(e:Exception){recorder=null;useCase=null;status.text="ชุดนี้ใช้ไม่ได้ · ${e.localizedMessage}\nแตะค่าด้านบนเพื่อเปลี่ยน"}
    }
    private fun settings(){if(recording!=null||starting||finalizing){notice("หยุดคลิปก่อนเปลี่ยนรูปแบบวิดีโอ");return}
        val i=info()?:return
        studioSheet("วิดีโอ"){box,d->
            fun choose(title:String,items:List<String>,action:(Int)->Unit){box.addView(pill(title){AlertDialog.Builder(this).setTitle(title).setItems(items.toTypedArray()){_,n->action(n);d.dismiss();bind()}.setNegativeButton("ปิด",null).show()},-1,dp(52))}
            val caps=Recorder.getVideoCapabilities(i);val qualities=caps.getSupportedQualities(dynamic)
            choose("ความละเอียด · ${qualityName(quality)}",qualities.map{qualityName(it)}){quality=qualities[it]}
            val frames=i.supportedFrameRateRanges.flatMap{r->listOf(24,25,30,50,60).filter{it in r.lower..r.upper}}.distinct().sorted()
            choose("FPS · $fps",frames.map{"$it fps · ต้องตรวจชุดร่วมกับความละเอียด"}){fps=frames[it]}
            val ranges=listOf(DynamicRange.SDR,DynamicRange.HLG_10_BIT,DynamicRange.HDR10_10_BIT).filter{it in caps.supportedDynamicRanges}
            choose("SDR / HDR",ranges.map{when(it){DynamicRange.SDR->"SDR 8-bit";DynamicRange.HLG_10_BIT->"HLG10";else->"HDR10"}}){dynamic=ranges[it]}
            choose("Bitrate · ${bitrate/1_000_000} Mbps",listOf("8 Mbps","20 Mbps","40 Mbps","80 Mbps")){bitrate=listOf(8,20,40,80)[it]*1_000_000}
            box.addView(label("Codec กล้องเลือกตามโปรไฟล์ · เลือก H.264/HEVC ได้ตอนส่งออกคลิป",13f,MUTED),-1,dp(52))
            if(caps.isStabilizationSupported)choose("กันสั่น EIS · $stabilization",listOf("ปิด","เปิด")){stabilization=it==1;prefs.edit().putBoolean("video_eis",stabilization).apply()}
            choose("ไมโครโฟน · ${if(mic)"เปิด" else "ปิด"}",listOf("ปิดไมค์","บันทึกเสียง")){mic=it==1;prefs.edit().putBoolean("video_mic",mic).apply()}
            box.addView(pill("คืนค่าชุดพื้นฐาน 1080p30 SDR"){dynamic=DynamicRange.SDR;fps=30;quality=Quality.FHD;hevc=false;stabilization=false;d.dismiss();bind()},-1,dp(56))
            box.addView(pill("รายงานความสามารถ"){Capabilities.show(this)},-1,dp(52))
        }
    }
    private fun startRecording(){
        val r=recorder?:return;if(starting||finalizing||recording!=null)return
        if(filesDir.usableSpace<64L*1024*1024){notice("พื้นที่ว่างไม่พอเริ่มคลิป");return}
        starting=true;shutter.isEnabled=false;config.isEnabled=false
        val sound=prefs.getBoolean("video_sound",true)
        if(sound)sounds.play(MediaActionSound.START_VIDEO_RECORDING)
        handler.postDelayed({
            if(!starting||isFinishing||isDestroyed){starting=false;return@postDelayed}
            try{
                val name="LUMA_VIDEO_${System.currentTimeMillis()}.mp4"
                val values=ContentValues().apply{put(MediaStore.Video.Media.DISPLAY_NAME,name);put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");if(android.os.Build.VERSION.SDK_INT>=29)put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/Luma/")}
                val options=MediaStoreOutputOptions.Builder(contentResolver,MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).setFileSizeLimit((filesDir.usableSpace-32L*1024*1024).coerceAtLeast(1)).build()
                var pending=r.prepareRecording(this,options)
                if(mic&&ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)pending=pending.withAudioEnabled()
                recording=pending.start(main){event->when(event){
                    is VideoRecordEvent.Start->{starting=false;shutter.isEnabled=true;shutter.recording=true;shutter.contentDescription="หยุดบันทึกวิดีโอ";pause.isEnabled=true;recordingUi(true)}
                    is VideoRecordEvent.Status->{val seconds=event.recordingStats.recordedDurationNanos/1_000_000_000;status.text=String.format(Locale.US,"%02d:%02d  ·  %.1f MB%s",seconds/60,seconds%60,event.recordingStats.numBytesRecorded/1e6,if(paused)" · พัก" else "")}
                    is VideoRecordEvent.Pause->{paused=true;pauseIcon.path=Icons.VIDEO;pause.contentDescription="ต่อการบันทึก"}
                    is VideoRecordEvent.Resume->{paused=false;pauseIcon.path=PAUSE;pause.contentDescription="พักการบันทึก"}
                    is VideoRecordEvent.Finalize->{recording=null;starting=false;finalizing=false;paused=false;shutter.recording=false;shutter.contentDescription="เริ่มบันทึกวิดีโอ";pause.isEnabled=false;pauseIcon.path=PAUSE;shutter.isEnabled=true;config.isEnabled=true;recordingUi(false)
                        if(sound&&!isDestroyed)sounds.play(MediaActionSound.STOP_VIDEO_RECORDING)
                        val uri=event.outputResults.outputUri
                        if(uri!=Uri.EMPTY&&event.recordingStats.numBytesRecorded>0){
                            MediaLedger.add(this,uri,name,"video/mp4",if(event.hasError())"คลิปจบบางส่วน · รหัส ${event.error}" else "${qualityName(quality)} · เป้าหมาย $fps fps · ${if(dynamic==DynamicRange.SDR)"SDR" else "HDR"}")
                            status.text=if(event.hasError())"เก็บคลิปที่ได้รับแล้ว · ${event.error}" else "บันทึกวิดีโอแล้ว"
                            luma.fileIo.execute{ClipInspector.record(this,uri,name)}
                        }else status.text="บันทึกไม่สำเร็จ · ${event.cause?.localizedMessage?:event.error}"
                    }
                }}
            }catch(e:Exception){starting=false;recording=null;shutter.isEnabled=true;config.isEnabled=true;status.text="เริ่มบันทึกไม่ได้ · ${e.localizedMessage}"}
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
    override fun onPause(){starting=false;animator?.cancel();handler.removeCallbacksAndMessages(null);recording?.stop();super.onPause()}
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
        val column=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};host.addView(column,-1,-1)
        val head=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),dp(8),dp(14),dp(6))}
        head.addView(roundButton(IconView(this,Icons.CHEVRON_LEFT,2.2f),44,"กลับไปถ่ายภาพ"){if(!busy())finish()},dp(44),dp(44))
        head.addView(text(when(kind){"DUAL"->"Dual-view";"MOVIE"->"วิดีโอโปร";else->"วิดีโอ"},16f,WHITE,true).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,-2,1f))
        config=text("1080p · 30",13f,WHITE,true).apply{gravity=Gravity.CENTER;background=glass(22);setPadding(dp(14),0,dp(14),0);isClickable=true;contentDescription="รายละเอียดวิดีโอทั้งหมด";glassTouch();setOnClickListener{settings()}}
        head.addView(config,-2,dp(44));column.addView(head,-1,-2)
        stage=FrameLayout(this);column.addView(stage,LinearLayout.LayoutParams(-1,0,1f))
        preview=PreviewView(this).apply{implementationMode=PreviewView.ImplementationMode.COMPATIBLE;scaleType=PreviewView.ScaleType.FIT_CENTER};stage.addView(preview,-1,-1)
        val overlay=CompositionOverlay(this).apply{grid=prefs.getBoolean("grid",false);movie=kind=="MOVIE"};stage.addView(overlay,-1,-1)
        status=text("กำลังเปิดกล้อง…",13f).apply{gravity=Gravity.CENTER;background=glass(18);setPadding(dp(14),dp(7),dp(14),dp(7));maxLines=3;accessibilityLiveRegion=android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE}
        stage.addView(status,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply{topMargin=dp(10)})
        wheel=ZoomWheel(this).apply{alpha=0f};stage.addView(wheel,FrameLayout.LayoutParams(-1,dp(150),Gravity.BOTTOM).apply{bottomMargin=dp(4)})
        zooms=LinearLayout(this).apply{gravity=Gravity.CENTER;background=glass(26);setPadding(dp(4),dp(4),dp(4),dp(4))}
        stage.addView(zooms,FrameLayout.LayoutParams(-2,dp(52),Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply{bottomMargin=dp(8)})
        installZoomDrag()
        stage.addView(roundButton(IconView(this,"M4 17l5-5 4 4 7-8 M15 8h5v5",2f),44,"ซูมอัตโนมัติจาก A ไป B"){zoomPanel()},FrameLayout.LayoutParams(dp(44),dp(44),Gravity.BOTTOM or Gravity.START).apply{setMargins(dp(16),0,0,dp(12))})
        // Settings dial floats over the preview, chips sit in the dock.
        dial=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=glass(24);setPadding(dp(16),dp(12),dp(16),dp(12));visibility=android.view.View.GONE}
        val dh=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        dialTitle=text("",13f,Glass.TEXT2);dialValue=text("",20f,WHITE,true).apply{gravity=Gravity.END}
        dh.addView(dialTitle,LinearLayout.LayoutParams(0,-2,1f));dh.addView(dialValue,-2,-2);dial.addView(dh,-1,dp(30))
        slider=GlassSlider(this).apply{onChange={i->openChip?.let{applyChip(it,i)}}};dial.addView(slider,-1,dp(56))
        stage.addView(dial,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply{setMargins(dp(16),0,dp(16),dp(66))})

        val dock=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};column.addView(dock,-1,-2)
        if(kind=="MOVIE"){
            tabs=FrameLayout(this).apply{background=glass(20,0x1fffffff)}
            knob=android.view.View(this).apply{background=glass(17,0x38ffffff)};tabs.addView(knob,FrameLayout.LayoutParams(0,-1).apply{setMargins(dp(3),dp(3),dp(3),dp(3))})
            val row=LinearLayout(this);tabLabels.clear()
            listOf("ปรับแต่งวิดีโอ","คุณภาพวิดีโอ").forEachIndexed{i,t->val l=text(t,13.5f,if(i==category)WHITE else Glass.TEXT2,true).apply{gravity=Gravity.CENTER;isClickable=true;contentDescription=t;setOnClickListener{if(category!=i){category=i;slideKnob(true);closeDial();refreshChips()}}};tabLabels+=l;row.addView(l,LinearLayout.LayoutParams(0,-1,1f))}
            tabs.addView(row,-1,-1);tabs.post{slideKnob(false)}
            dock.addView(tabs,LinearLayout.LayoutParams(-1,dp(38)).apply{setMargins(dp(16),dp(8),dp(16),dp(4))})
        }
        chipFrame=FrameLayout(this).apply{background=glass(24)}
        chips=CenterStrip(this).apply{capsuleHeightDp=44;onSelect={i->chipKeys().getOrNull(i)?.let{k->if(openChip==k)closeDial() else openDial(k)}};onLive={i->styleChips(i)}}
        chipFrame.addView(chips,FrameLayout.LayoutParams(-1,-1).apply{setMargins(dp(1),dp(1),dp(1),dp(1))})
        dock.addView(chipFrame,LinearLayout.LayoutParams(-1,dp(52)).apply{setMargins(dp(16),dp(6),dp(16),dp(4))})
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
                cell.addView(text(zoomText(v),12f,WHITE,true).apply{gravity=Gravity.CENTER;background=GlassDrawable(0x1affffff,dp(20).toFloat(),resources.displayMetrics.density)},FrameLayout.LayoutParams(dp(32),dp(32),Gravity.CENTER))
                cell.setOnClickListener{animateZoom(v)};zooms.addView(cell,LinearLayout.LayoutParams(dp(44),dp(44)).apply{setMargins(dp(2),0,dp(2),0)})}}
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
    private fun installZoomDrag(){
        var sx=0f;var z0=1f;var moved=false
        zooms.setOnTouchListener{_,e->
            val z=camera?.cameraInfo?.zoomState?.value
            when(e.actionMasked){
                MotionEvent.ACTION_DOWN->{sx=e.rawX;z0=z?.zoomRatio?:1f;moved=false;wheel.removeCallbacks(hideWheel);animator?.cancel()}
                MotionEvent.ACTION_MOVE->{if(z!=null){val dx=e.rawX-sx
                    if(!moved&&abs(dx)>=dp(6)){moved=true;zooms.animate().alpha(0f).setDuration(200).start();wheel.show(true);zooms.parent.requestDisallowInterceptTouchEvent(true)}
                    if(moved){var t=(z0*2f.pow(-dx/dp(95))).coerceIn(z.minZoomRatio,z.maxZoomRatio)
                        zoomStops.firstOrNull{abs(ln(t/it)/ln(2f))<0.075f}?.let{lens->if(abs(z.zoomRatio-lens)>0.001f)Glass.tick(wheel);t=lens}
                        camera?.cameraControl?.setZoomRatio(t);wheel.zoom=t}}}
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->if(moved)wheel.postDelayed(hideWheel,1300)
            }
            moved
        }
    }

    // ---------- Chips and dial ----------
    private fun chipKeys():List<String>{
        val quality=listOf("RES","FPS","HDR","RATE","EIS","MIC")
        val adjust=buildList{val m=manual;if(m!=null&&m.canManual){add("ISO");add("S")};add("WB");if((m?.minFocus?:0f)>0f)add("AF");add("EV")}
        return if(kind=="MOVIE"&&category==0)adjust else quality
    }
    private fun chipTitle(k:String)=when(k){"RES"->"ความละเอียด";"FPS"->"FPS";"HDR"->"ช่วงไดนามิก";"RATE"->"Bitrate";"EIS"->"กันสั่น";"MIC"->"ไมค์";"ISO"->"ISO";"S"->"ชัตเตอร์";"WB"->"WB";"AF"->"โฟกัส";else->"EV"}
    private fun caps()=info()?.let{runCatching{Recorder.getVideoCapabilities(it)}.getOrNull()}
    private fun qualities()=caps()?.getSupportedQualities(dynamic)?:listOf(quality)
    private fun frameRates()=info()?.supportedFrameRateRanges?.flatMap{r->listOf(24,25,30,50,60).filter{it in r.lower..r.upper}}?.distinct()?.sorted()?.ifEmpty{null}?:listOf(fps)
    private fun ranges()=listOf(DynamicRange.SDR,DynamicRange.HLG_10_BIT,DynamicRange.HDR10_10_BIT).filter{caps()?.supportedDynamicRanges?.contains(it)?:(it==DynamicRange.SDR)}
    private fun evValues():List<Float>{val st=camera?.cameraInfo?.exposureState?:return listOf(0f);if(!st.isExposureCompensationSupported)return listOf(0f);val step=st.exposureCompensationStep.toFloat();val r=st.exposureCompensationRange;return(r.lower..r.upper).map{it*step}.filter{abs(it)<=2.01f}}
    private fun chipValues(k:String):List<String>{val m=manual
        return when(k){
            "RES"->qualities().map{qualityName(it)};"FPS"->frameRates().map{"$it"};"HDR"->ranges().map{when(it){DynamicRange.SDR->"SDR";DynamicRange.HLG_10_BIT->"HLG10";else->"HDR10"}}
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
            CenterStrip.Item(k,78,LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;isClickable=true;contentDescription=chipTitle(k);addView(t,-2,-2);addView(v,-2,-2)})},
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
        if(k in listOf("RES","FPS","HDR","RATE","EIS")){handler.removeCallbacks(rebind);handler.postDelayed(rebind,450)}
        else if(k=="MIC")status.text=if(mic)"ไมค์เปิด" else "ไมค์ปิด"
    }
    private fun qualityName(q:Quality)=when(q){Quality.UHD->"4K";Quality.FHD->"1080p";Quality.HD->"720p";else->"SD"}
}
