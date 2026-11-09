package th.ogay.luma

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.util.Range
import android.util.Size
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** A separate Camera2 experiment; no production capture/UI method is replaced. */
class CameraProbeActivity : ComponentActivity() {
    private data class Choice(val id:String,val size:Size,val fps:Int,val highSpeed:Boolean) {
        fun title()="กล้อง $id · $size · $fps fps"+(if(highSpeed)" → เล่น 30 fps"else " · ชุดควบคุม")
    }
    private val manager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    private val worker=HandlerThread("LumaCameraProbe").apply{start()}
    private val handler=Handler(worker.looper)
    private lateinit var texture:TextureView
    private lateinit var status:TextView
    private lateinit var startButton:TextView
    private lateinit var choiceButton:TextView
    private lateinit var reportButton:TextView
    private lateinit var clipButton:TextView
    private lateinit var orientation:OrientationEventListener
    @Volatile private var foreground=false
    @Volatile private var busy=false
    @Volatile private var targetRotation=Surface.ROTATION_0
    @Volatile private var textureSurface:SurfaceTexture?=null
    private var choices=emptyList<Choice>()
    private var choice:Choice?=null
    private var generation=0
    private var device:CameraDevice?=null
    private var session:CameraCaptureSession?=null
    private var recorder:MediaRecorder?=null
    private var previewSurface:Surface?=null
    private var output:File?=null
    private var report=JSONObject()
    private var events=JSONArray()
    private var inventory=JSONObject()
    private var startedNs=0L
    private var recording=false
    private var stage="idle"
    private var reportFile:File?=null
    private var clipUri:Uri?=null
    private var sensor=VideoDiagnostics()
    private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()){allowed->if(allowed)launchProbe()else status.text="ต้องอนุญาตกล้องเพื่อทดสอบ"}
    private fun ui(action:()->Unit)=runOnUiThread{if(!isDestroyed)action()}
    private fun stage(value:String){stage=value;events.put(JSONObject().put("stage",value).put("elapsedNs",SystemClock.elapsedRealtimeNanos()));
        val message=when(value){"prepare"->"กำลังเตรียมไฟล์…";"open"->"กำลังเปิดกล้อง…";"configure"->"กำลังเปิดชุดวิดีโอ…";"start"->"กำลังเริ่มบันทึก…";"stop"->"กำลังปิดไฟล์…";else->null}
        if(message!=null)ui{status.text=message}
    }
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),0,dp(16),dp(20))}
        installRoot(ScrollView(this).apply{addView(body)})
        body.addView(pill("‹ กลับ"){if(!busy)finish()},-1,-2)
        body.addView(label("ทดสอบกล้องจริง",24f),-1,-2)
        status=label("กำลังอ่านชุดวิดีโอ…",15f).apply{minHeight=dp(64)};body.addView(status,-1,-2)
        choiceButton=pill("เลือกชุดทดสอบ"){if(!busy)AlertDialog.Builder(this).setTitle("ชุดที่ Android รายงาน").setItems(choices.map{it.title()}.toTypedArray()){_,i->choice=choices[i];choiceButton.text=choice!!.title()}.show()}.apply{isEnabled=false};body.addView(choiceButton,-1,-2)
        startButton=pill("เริ่มทดสอบ 10 วินาที"){if(busy)handler.post{finishProbe(true)}else if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)permission.launch(Manifest.permission.CAMERA)else launchProbe()}.apply{contentDescription="เริ่มทดสอบกล้อง 10 วินาที";isEnabled=false};body.addView(startButton,-1,-2)
        body.addView(label("ทดสอบแบบไม่มีเสียง · โหมด 120/240 บันทึกเป็นคลิปเล่น 30 fps",14f,MUTED),-1,-2)
        texture=TextureView(this);body.addView(texture,-1,dp(200))
        texture.surfaceTextureListener=object:TextureView.SurfaceTextureListener{
            override fun onSurfaceTextureAvailable(s:SurfaceTexture,w:Int,h:Int){textureSurface=s}
            override fun onSurfaceTextureSizeChanged(s:SurfaceTexture,w:Int,h:Int){}
            override fun onSurfaceTextureUpdated(s:SurfaceTexture){}
            override fun onSurfaceTextureDestroyed(s:SurfaceTexture):Boolean{textureSurface=null;handler.post{if(busy)abort("surface-destroyed",null)};return true}
        }
        reportButton=pill("บันทึกและส่งรายงาน"){if(!busy)handler.post{saveReport(true)}}.apply{contentDescription="ส่งรายงานทดสอบกล้อง";isEnabled=false};body.addView(reportButton,-1,-2)
        clipButton=pill("เปิดคลิปทดสอบ"){clipUri?.let{MediaLedger.view(this,it,"video/mp4")}}.apply{isEnabled=false};body.addView(clipButton,-1,-2)
        targetRotation=windowManager.defaultDisplay.rotation
        orientation=object:OrientationEventListener(this){override fun onOrientationChanged(degrees:Int){VideoSessionPolicy.rotation(degrees)?.let{targetRotation=it}}}
        handler.post{
            runCatching{
                val ids=manager.cameraIdList.toList()
                choices=ids.flatMap{id->Capabilities.highSpeed(this,id).map{Choice(id,it.first,it.second,true)}}
                    .sortedWith(compareBy<Choice>{it.id}.thenByDescending{it.size.width.toLong()*it.size.height}.thenBy{it.fps})
                if(choices.isEmpty()){
                    choices=ids.mapNotNull{id->val c=manager.getCameraCharacteristics(id);val map=c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?:return@mapNotNull null
                        val size=map.getOutputSizes(MediaRecorder::class.java)?.firstOrNull{it==Size(1280,720)}?:return@mapNotNull null
                        if(c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.contains(Range(30,30))==true)Choice(id,size,30,false)else null}
                }
                choice=choices.firstOrNull{it.id=="0"&&it.size==Size(1920,1080)&&it.fps==120}?:choices.firstOrNull()
                inventory=Capabilities.report(this)
                report=JSONObject().put("schema",1).put("stage","capability_only").put("inventory",inventory)
                ui{choiceButton.text=choice?.title()?:"ไม่มีชุดทดสอบที่ใช้ได้";choiceButton.isEnabled=choices.isNotEmpty();startButton.isEnabled=choice!=null;reportButton.isEnabled=true;status.text="ถือเครื่องให้นิ่ง หันไปยังวัตถุที่ขยับในฉากสว่าง แล้วกดเริ่ม"}
            }.onFailure{abort("inventory",it)}
        }
    }
    private fun launchProbe(){
        val selected=choice?:return
        if(busy)return
        if(textureSurface==null){status.text="รอช่องพรีวิวพร้อม แล้วลองอีกครั้ง";return}
        if(filesDir.usableSpace<256L*1024*1024){status.text="ต้องมีพื้นที่ว่างอย่างน้อย 256 MB";return}
        busy=true;requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LOCKED
        choiceButton.isEnabled=false;reportButton.isEnabled=false;startButton.isEnabled=false;clipButton.isEnabled=false;clipUri=null
        val rotation=targetRotation
        // Let the preceding CameraX screen finish releasing its hardware before Camera2 opens it.
        handler.post{val pending=++generation;handler.postDelayed({if(pending==generation&&busy){if(foreground)begin(selected,rotation)else abort("cancelled-before-open",null)}},400)}
    }
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun begin(selected:Choice,rotation:Int){
        if(!foreground){abort("cancelled-before-open",null);return}
        val ticket=++generation
        events=JSONArray();reportFile=null;sensor=VideoDiagnostics()
        report=JSONObject().put("schema",1).put("timeMs",System.currentTimeMillis()).put("model",Build.MODEL).put("sdk",Build.VERSION.SDK_INT)
            .put("cameraId",selected.id).put("size",selected.size.toString()).put("requestedCaptureFps",selected.fps)
            .put("expectedPlaybackFps",if(selected.highSpeed)30 else selected.fps).put("sessionType",if(selected.highSpeed)"constrained_high_speed"else "normal")
            .put("inventory",inventory).put("events",events)
        try{
            stage("prepare")
            val st=textureSurface?:error("ไม่มี surface")
            st.setDefaultBufferSize(selected.size.width,selected.size.height);previewSurface=Surface(st)
            val c=manager.getCameraCharacteristics(selected.id);val sensorRotation=c.get(CameraCharacteristics.SENSOR_ORIENTATION)?:90
            val facing=c.get(CameraCharacteristics.LENS_FACING);val degrees=rotation*90
            val hint=if(facing==CameraCharacteristics.LENS_FACING_FRONT)(sensorRotation+degrees)%360 else (sensorRotation-degrees+360)%360
            report.put("orientationHint",hint)
            ui{texture.post{val w=texture.width.toFloat();val h=texture.height.toFloat();if(w>0&&h>0){val sw=selected.size.width.toFloat();val sh=selected.size.height.toFloat();val scale=minOf(w/(if(hint%180==0)sw else sh),h/(if(hint%180==0)sh else sw));texture.setTransform(Matrix().apply{setScale(scale*sw/w,scale*sh/h,w/2,h/2);postRotate(hint.toFloat(),w/2,h/2)})}}}
            output=File(filesDir,"clips/LUMA_PROBE_${System.currentTimeMillis()}.mp4").apply{parentFile?.mkdirs()}
            val r=if(Build.VERSION.SDK_INT>=31)MediaRecorder(this)else MediaRecorder();recorder=r
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE);r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);r.setOutputFile(output!!.path)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264);r.setVideoSize(selected.size.width,selected.size.height)
            r.setVideoEncodingBitRate(if(selected.highSpeed)20_000_000 else 8_000_000);r.setVideoFrameRate(30)
            if(selected.highSpeed)r.setCaptureRate(selected.fps.toDouble())
            r.setOrientationHint(hint);r.setMaxFileSize(filesDir.usableSpace-64L*1024*1024)
            r.setOnErrorListener{_,what,extra->handler.post{if(ticket==generation&&busy)abort("recorder-error",IllegalStateException("$what/$extra"))}}
            r.setOnInfoListener{_,what,_->if(what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)handler.post{if(ticket==generation)finishProbe(true)}}
            r.prepare();stage("open")
            manager.openCamera(selected.id,object:CameraDevice.StateCallback(){
                override fun onOpened(camera:CameraDevice){if(ticket!=generation||!foreground){camera.close();return};device=camera;configure(camera,selected,ticket)}
                override fun onDisconnected(camera:CameraDevice){camera.close();if(ticket==generation)abort("disconnected",null)}
                override fun onError(camera:CameraDevice,error:Int){camera.close();if(ticket==generation)abort("open",IllegalStateException("camera error $error"))}
            },handler)
            handler.postDelayed({if(ticket==generation&&busy&&!recording)abort(stage,IllegalStateException("เตรียมกล้องเกิน 15 วินาที"))},15000)
        }catch(e:Exception){abort(stage,e)}
    }
    @Suppress("DEPRECATION")
    private fun configure(camera:CameraDevice,selected:Choice,ticket:Int){
        try{
            stage("configure")
            val surfaces=listOf(previewSurface?:error("ไม่มี preview"),recorder?.surface?:error("ไม่มี recorder"))
            val callback=object:CameraCaptureSession.StateCallback(){
                override fun onConfigured(s:CameraCaptureSession){
                    if(ticket!=generation||!foreground){s.close();return};session=s
                    try{
                        val request=camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply{surfaces.forEach{addTarget(it)};set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO);set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,Range(selected.fps,selected.fps))}.build()
                        sensor.active(true)
                        if(selected.highSpeed){val high=s as CameraConstrainedHighSpeedCaptureSession;high.setRepeatingBurst(high.createHighSpeedRequestList(request),sensor,handler)}else s.setRepeatingRequest(request,sensor,handler)
                        stage("start");recorder!!.start();startedNs=SystemClock.elapsedRealtimeNanos();recording=true;stage("recording")
                        ui{startButton.isEnabled=true;startButton.text="หยุดทดสอบ";startButton.contentDescription="หยุดทดสอบกล้อง"}
                        tick(ticket)
                    }catch(e:Exception){abort(stage,e)}
                }
                override fun onConfigureFailed(s:CameraCaptureSession){s.close();if(ticket==generation)abort("configure",IllegalStateException("กล้องไม่ยอมรับชุด surface / FPS นี้"))}
            }
            if(selected.highSpeed)camera.createConstrainedHighSpeedCaptureSession(surfaces,callback,handler)else camera.createCaptureSession(surfaces,callback,handler)
        }catch(e:Exception){abort(stage,e)}
    }
    private fun tick(ticket:Int){
        if(ticket!=generation||!recording)return
        val elapsed=(SystemClock.elapsedRealtimeNanos()-startedNs)/1e9
        if(elapsed>=10){finishProbe(false);return}
        ui{status.text=String.format(Locale.US,"กำลังอัด · เหลือ %.1f วินาที",10-elapsed)}
        handler.postDelayed({tick(ticket)},200)
    }
    private fun finishProbe(cancelled:Boolean){
        if(!recording)return
        stage("stop");val elapsed=SystemClock.elapsedRealtimeNanos()-startedNs
        try{session?.stopRepeating();recorder?.stop()}catch(e:Exception){abort("stop",e);return}
        recording=false;sensor.active(false);val file=output;output=null
        report.put("captureWallNs",elapsed).put("cancelled",cancelled).put("sensorTiming",sensor.snapshot())
        release();stage("inspect");ui{startButton.isEnabled=false;status.text="กำลังตรวจเฟรมและบันทึกรายงาน…"}
        try{
            check(file!=null&&file.length()>0){"ไฟล์วิดีโอว่าง"}
            val uri=MediaLedger.rememberFile(this,file,"video/mp4","คลิปทดสอบกล้อง · ไม่มีเสียง")
            val result=ClipInspector.inspect(this,uri)
            report.put("file",result).put("captureFpsEstimateFromFile",if(elapsed>0)result.optLong("frames")*1e9/elapsed else JSONObject.NULL)
                .put("measurementNote","File frames / capture wall time is an estimate; sensor callbacks may be batched. Not a unique-frame analysis.")
            stage("complete");report.put("stage","complete");clipUri=uri;saveReport(false)
            ui{clipButton.isEnabled=true;status.text="ทดสอบเสร็จ · กดบันทึกและส่งรายงาน\nไฟล์เล่น %.1f fps · ค่าประมาณขณะถ่าย %.1f fps".format(Locale.US,result.optDouble("measuredFps"),report.optDouble("captureFpsEstimateFromFile"))}
        }catch(e:Exception){if(file!=null&&file.length()>0)runCatching{MediaLedger.rememberFile(this,file,"video/mp4","ไฟล์ทดสอบที่ตรวจไม่สำเร็จ")};report.put("stage","inspect-error").put("error",e.toString());saveReport(false);ui{status.text="ตรวจคลิปไม่สำเร็จ · ส่งรายงานให้ตรวจได้"}}
        ready()
    }
    private fun abort(where:String,error:Throwable?){
        if(!busy&&stage!="idle"&&where!="inventory")return
        recording=false;sensor.active(false);report.put("stage",where).put("errorType",error?.javaClass?.name?:JSONObject.NULL).put("error",error?.message?:"ยกเลิกหรือกล้องถูกตัดการเชื่อมต่อ")
        release();output?.delete();output=null;saveReport(false);ready()
        ui{status.text="ทดสอบไม่สำเร็จที่ $where\n${error?.message?:"การทดสอบถูกยกเลิก"}\nกดบันทึกและส่งรายงานได้"}
    }
    private fun release(){generation++;runCatching{session?.close()};session=null;runCatching{device?.close()};device=null;runCatching{recorder?.reset()};runCatching{recorder?.release()};recorder=null;previewSurface?.release();previewSurface=null}
    private fun ready(){busy=false;ui{requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;choiceButton.isEnabled=choices.isNotEmpty();reportButton.isEnabled=true;startButton.isEnabled=choice!=null;startButton.text="เริ่มทดสอบ 10 วินาที";startButton.contentDescription="เริ่มทดสอบกล้อง 10 วินาที"}}
    private fun saveReport(share:Boolean){
        try{
            val file=reportFile?:File(filesDir,"reports/camera-probe-${System.currentTimeMillis()}.json").also{reportFile=it}
            file.parentFile?.mkdirs();file.writeText(report.toString(2));val uri=MediaLedger.rememberFile(this,file,"application/json","ผลทดสอบกล้องจริง · ${report.optString("stage")}")
            if(share)ui{MediaLedger.share(this,uri,"application/json")}
        }catch(e:Exception){ui{status.text="บันทึกรายงานไม่ได้ · ${e.message}"}}
    }
    override fun onResume(){super.onResume();foreground=true;orientation.enable()}
    override fun onPause(){foreground=false;orientation.disable();handler.post{if(recording)finishProbe(true)else if(busy)abort("cancelled",null)};super.onPause()}
    override fun onDestroy(){handler.post{if(recording)finishProbe(true);release();worker.quitSafely()};super.onDestroy()}
}
