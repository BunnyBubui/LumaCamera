package th.ogay.luma

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.os.*
import android.util.Range
import android.util.Size
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.math.*

/** Camera2 constrained high-speed capture and interval recording, both explicitly silent. */
@androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
class SpecialVideoActivity:ComponentActivity(){
    private val slow get()=intent.getStringExtra("mode")=="SLOW"
    private lateinit var texture:TextureView;private lateinit var status:TextView;private lateinit var shutter:Glyph;private lateinit var config:TextView
    private val manager by lazy{getSystemService(Context.CAMERA_SERVICE) as CameraManager}
    private val thread=HandlerThread("LumaSpecialVideo").apply{start()};private val handler=Handler(thread.looper)
    private var camera:CameraDevice?=null;private var session:CameraCaptureSession?=null;private var recorder:MediaRecorder?=null
    private var size=Size(1280,720);private var fps=120;private var interval=1.0;private var cameraId=""
    private var recording=false;private var working=false;private var visible=false;private var file:File?=null;private var started=0L
    private var previewSurface:Surface?=null
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){if(it[Manifest.permission.CAMERA]==true)open()else status.text="ต้องอนุญาตกล้องเพื่อใช้โหมดนี้"}
    override fun onCreate(state:Bundle?){super.onCreate(state)
        if(slow){startActivity(android.content.Intent(this,VideoActivity::class.java).putExtra("mode","SLOW"));finish();return}
        // Same layout as the video screen: full preview, the format pill top-right, a status pill, then the mode strip
        // and the shutter row, so time-lapse feels like one more video mode rather than a separate page.
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};installRoot(root)
        val stage=FrameLayout(this).apply{setBackgroundColor(Color.BLACK);clipChildren=true}
        root.addView(stage,LinearLayout.LayoutParams(-1,0,1f))
        texture=TextureView(this);stage.addView(texture,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
        // Keep the 16:9 stream undistorted: the preview is as wide as the screen and as tall as its aspect asks.
        stage.addOnLayoutChangeListener{_,l,t,r,b,_,_,_,_->val w=r-l;val h=(w.toLong()*size.width/size.height.coerceAtLeast(1)).toInt().coerceAtMost(b-t);(texture.layoutParams as FrameLayout.LayoutParams).let{p->if(p.height!=h){p.height=h;texture.post{texture.layoutParams=p}}}}
        texture.surfaceTextureListener=object:TextureView.SurfaceTextureListener{override fun onSurfaceTextureAvailable(s:SurfaceTexture,w:Int,h:Int){if(visible)open()};override fun onSurfaceTextureSizeChanged(s:SurfaceTexture,w:Int,h:Int){};override fun onSurfaceTextureUpdated(s:SurfaceTexture){};override fun onSurfaceTextureDestroyed(s:SurfaceTexture):Boolean{handler.post{close()};return true}}
        status=text("กำลังตรวจกล้อง…",13f).apply{gravity=Gravity.CENTER;background=glass(18);setPadding(dp(14),dp(7),dp(14),dp(7));maxLines=4}
        stage.addView(status,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply{topMargin=dp(62);leftMargin=dp(16);rightMargin=dp(16)})
        config=text("ทุก 1 วิ · 30 fps",13f,Color.WHITE,true).apply{gravity=Gravity.CENTER;minHeight=dp(48);background=glass(22);setPadding(dp(14),0,dp(14),0);isClickable=true;contentDescription="ช่วงห่างเฟรม";glassTouch();setOnClickListener{settings()}}
        stage.addView(config,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END).apply{setMargins(0,dp(6),dp(12),0)})
        stage.addView(text("Time-lapse · ไม่มีเสียง",12.5f,Glass.TEXT2,true).apply{gravity=Gravity.CENTER;minHeight=dp(48);background=glass(22);setPadding(dp(14),0,dp(14),0)},FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.START).apply{setMargins(dp(12),dp(6),0,0)})
        val dock=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(BG)}
        val strip=CenterStrip(this).apply{contentDescription="แถบโหมด"}
        ModeBar.fill(this,strip,"TIMELAPSE"){key->if(recording||working){notice("หยุดคลิปก่อนเปลี่ยนโหมด");false}else{ModeBar.go(this,key);true}}
        dock.addView(strip,-1,dp(58))
        shutter=Glyph(this,"shutter","เริ่มบันทึก Time-lapse"){if(!working){if(recording)stop()else start()}}.apply{video=true;isEnabled=false}
        val actions=FrameLayout(this);actions.addView(shutter,FrameLayout.LayoutParams(dp(82),dp(82),Gravity.CENTER))
        actions.addView(FrameLayout(this).apply{background=glass(28);contentDescription="แกลเลอรีวิดีโอ Luma";isClickable=true;glassTouch()
            addView(IconView(this@SpecialVideoActivity,Icons.LIBRARY),FrameLayout.LayoutParams(dp(24),dp(24),Gravity.CENTER))
            setOnClickListener{if(!recording&&!working)startActivity(android.content.Intent(this@SpecialVideoActivity,GalleryActivity::class.java).putExtra("tab","luma").putExtra("category","วิดีโอ"))}},FrameLayout.LayoutParams(dp(56),dp(56),Gravity.CENTER_VERTICAL or Gravity.START).apply{leftMargin=dp(36)})
        dock.addView(actions,-1,dp(104))
        root.addView(dock,-1,-2)
        cameraId=manager.cameraIdList.firstOrNull{manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING)==CameraCharacteristics.LENS_FACING_BACK}?:manager.cameraIdList.firstOrNull().orEmpty()
    }
    override fun onResume(){super.onResume();if(slow)return;visible=true;if(texture.isAvailable)open()}
    private fun settings(){if(recording||working)return
        if(slow){
            val choices=manager.cameraIdList.flatMap{id->Capabilities.highSpeed(this,id).map{Triple(id,it.first,it.second)}}.filter{(_,s,f)->MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any{codec->codec.isEncoder&&codec.supportedTypes.contains("video/avc")&&runCatching{codec.getCapabilitiesForType("video/avc").videoCapabilities?.areSizeAndRateSupported(s.width,s.height,f.toDouble())==true}.getOrDefault(false)}}
            if(choices.isEmpty()){notice("Android ไม่เปิดชุด high-speed ที่ encoder รองรับบนเครื่องนี้");return}
            AlertDialog.Builder(this).setTitle("Slow-motion ที่ Android รายงาน").setItems(choices.map{"กล้อง ${it.first} · ${it.second} · ${it.third} fps"}.toTypedArray()){_,i->val x=choices[i];cameraId=x.first;size=x.second;fps=x.third;open()}.show()
        }else AlertDialog.Builder(this).setTitle("ช่วงห่างเฟรม").setItems(arrayOf("0.5 วินาที","1 วินาที","2 วินาที","5 วินาที","10 วินาที")){_,i->interval=listOf(.5,1.0,2.0,5.0,10.0)[i];status.text="ทุก $interval วินาที · 30 เฟรมต่อ 1 วินาทีของคลิป";config.text="ทุก ${if(interval%1.0==0.0)interval.toInt().toString() else interval.toString()} วิ · 30 fps"}.show()
    }
    @SuppressLint("MissingPermission")
    private fun open(){
        if(!visible||!texture.isAvailable||recording||working)return
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){permissions.launch(if(Build.VERSION.SDK_INT<29)arrayOf(Manifest.permission.CAMERA,Manifest.permission.WRITE_EXTERNAL_STORAGE)else arrayOf(Manifest.permission.CAMERA));return}
        working=true;shutter.isEnabled=false
        handler.post{try{
            close()
            if(slow){val options=Capabilities.highSpeed(this,cameraId);check(options.isNotEmpty()){ "กล้องนี้ไม่เปิด High-speed video ให้แอป · ลองเลือกกล้องอื่น" };if(options.none{it.first==size&&it.second==fps}){size=options.first().first;fps=options.first().second}}
            else{val sizes=manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.getOutputSizes(MediaRecorder::class.java);size=sizes.firstOrNull{it.width==1920&&it.height==1080}?:sizes.firstOrNull{it.width==1280&&it.height==720}?:sizes.minBy{abs(it.width*it.height-1280*720)}}
            manager.openCamera(cameraId,object:CameraDevice.StateCallback(){override fun onOpened(d:CameraDevice){camera=d;if(!visible){close();return};preview()};override fun onDisconnected(d:CameraDevice){d.close();camera=null;showError("กล้องถูกตัดการเชื่อมต่อ")};override fun onError(d:CameraDevice,e:Int){d.close();camera=null;showError("กล้องผิดพลาด $e")}},handler)
        }catch(e:Exception){showError(e.localizedMessage?:"เปิดกล้องไม่ได้")}}
    }
    private fun surface():Surface{previewSurface?.release();val st=texture.surfaceTexture?:kotlin.error("พรีวิวยังไม่พร้อม");st.setDefaultBufferSize(size.width,size.height);return Surface(st).also{previewSurface=it}}
    @Suppress("DEPRECATION")
    private fun preview(){try{val d=camera?:return;val target=surface();d.createCaptureSession(listOf(target),object:CameraCaptureSession.StateCallback(){override fun onConfigured(s:CameraCaptureSession){session=s;try{val req=d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply{addTarget(target);set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO)};s.setRepeatingRequest(req.build(),null,handler);runOnUiThread{working=false;shutter.isEnabled=true;status.text="${size} · "+if(slow)"${fps} fps → 30 fps" else "ทุก $interval วินาที"}}catch(e:Exception){showError(e.localizedMessage?:"เปิดพรีวิวไม่ได้")}};override fun onConfigureFailed(s:CameraCaptureSession){s.close();showError("ชุดพรีวิวนี้ไม่รองรับ")}},handler)}catch(e:Exception){showError(e.localizedMessage?:"เปิดพรีวิวไม่ได้")}}
    @Suppress("DEPRECATION")
    private fun start(){if(camera==null||working)return;if(filesDir.usableSpace<96L*1024*1024){notice("พื้นที่ไม่พอ");return};requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED;working=true;shutter.isEnabled=false;status.text="กำลังเตรียมไฟล์…"
        handler.post{try{
            session?.close();session=null
            val output=File(filesDir,"clips/LUMA_${if(slow)"SLOW" else "TIMELAPSE"}_${System.currentTimeMillis()}.mp4").apply{parentFile?.mkdirs()};file=output
            val r=if(Build.VERSION.SDK_INT>=31)MediaRecorder(this)else MediaRecorder();recorder=r
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE);r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);r.setOutputFile(output.path);r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setVideoSize(size.width,size.height);r.setVideoEncodingBitRate(if(slow)24_000_000 else 12_000_000);r.setVideoFrameRate(30);r.setCaptureRate(if(slow)fps.toDouble()else 1.0/interval)
            val sensor=manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION)?:90
            val rotation=when(windowManager.defaultDisplay.rotation){Surface.ROTATION_90->90;Surface.ROTATION_180->180;Surface.ROTATION_270->270;else->0};r.setOrientationHint((sensor-rotation+360)%360)
            r.setMaxFileSize(filesDir.usableSpace-32L*1024*1024);r.setOnInfoListener{_,what,_->if(what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)stop()};r.prepare()
            val d=camera?:kotlin.error("กล้องปิดแล้ว");val target=surface();val surfaces=listOf(target,r.surface)
            val callback=object:CameraCaptureSession.StateCallback(){override fun onConfigured(s:CameraCaptureSession){if(!visible){s.close();close();return};session=s;try{val req=d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply{surfaces.forEach{addTarget(it)};set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO);if(slow)set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,Range(fps,fps))}
                    if(slow){val high=s as CameraConstrainedHighSpeedCaptureSession;high.setRepeatingBurst(high.createHighSpeedRequestList(req.build()),null,handler)}else s.setRepeatingRequest(req.build(),null,handler)
                    r.start();recording=true;started=SystemClock.elapsedRealtime();runOnUiThread{working=false;shutter.recording=true;shutter.isEnabled=true;status.text=if(slow)"กำลังบันทึก $fps fps · ไม่มีเสียง"else "กำลังเก็บเฟรมทุก $interval วินาที"}
                }catch(e:Exception){showError(e.localizedMessage?:"เริ่มอัดไม่ได้");close()}}
                override fun onConfigureFailed(s:CameraCaptureSession){s.close();showError("กล้องและ encoder ไม่รองรับชุดที่เลือก");close()}}
            if(slow)d.createConstrainedHighSpeedCaptureSession(surfaces,callback,handler)else d.createCaptureSession(surfaces,callback,handler)
        }catch(e:Exception){close();showError(e.localizedMessage?:"เริ่มอัดไม่ได้")}}
    }
    private fun stop(){if(!recording||working)return;working=true;runOnUiThread{shutter.isEnabled=false;status.text="กำลังปิดไฟล์…"};handler.post{
        val output=file;val wall=SystemClock.elapsedRealtime()-started
        val success=runCatching{session?.stopRepeating();recorder?.stop()}.isSuccess
        recording=false;recorder?.release();recorder=null;session?.close();session=null
        if(output!=null&&success&&output.length()>0)luma.fileIo.execute{runCatching{val uri=MediaLedger.publishVideo(this,output,output.name,"${if(slow)"Slow-motion $fps → 30 fps" else "Time-lapse ทุก $interval วินาที"} · เวลาถ่าย ${wall/1000}s");ClipInspector.record(this,uri,output.name);output.delete()}.onSuccess{runOnUiThread{status.text="บันทึกคลิปแล้ว"}}.onFailure{runOnUiThread{status.text="ไฟล์ยังอยู่ในแอป · ${it.localizedMessage}"};MediaLedger.rememberFile(this,output,"video/mp4")}}
        else{output?.delete();runOnUiThread{status.text="คลิปสั้นเกินไปหรือ recorder ไม่ส่งไฟล์ที่สมบูรณ์"}}
        runOnUiThread{working=false;shutter.recording=false;requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT};if(visible)preview()else close()
    }}
    private fun showError(message:String){runOnUiThread{working=false;requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;shutter.recording=false;shutter.isEnabled=false;status.text=message}}
    private fun close(){session?.close();session=null;camera?.close();camera=null;runCatching{recorder?.reset()};recorder?.release();recorder=null;previewSurface?.release();previewSurface=null}
    override fun onPause(){visible=false;if(recording&&!working)stop()else handler.post{close()};super.onPause()}
    override fun onDestroy(){handler.post{close();thread.quitSafely()};super.onDestroy()}
}
