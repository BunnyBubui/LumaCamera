package th.ogay.luma

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.*
import android.util.Size
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Explicitly experimental, measured RAW stream. Committed DNGs and JSONL survive interruption. */
class RawLabActivity:ComponentActivity(){
    private val manager by lazy{getSystemService(Context.CAMERA_SERVICE) as CameraManager}
    private val thread=HandlerThread("LumaRawCamera").apply{start()};private val handler=Handler(thread.looper)
    private val writer=Executors.newSingleThreadExecutor();private val writing=AtomicBoolean()
    private var device:CameraDevice?=null;private var session:CameraCaptureSession?=null;private var reader:ImageReader?=null;private var previewSurface:Surface?=null
    private var cameraId="";private var size=Size(0,0);private var visible=false;private var opening=false
    @Volatile private var running=false;private var finishing=false;private var ai=false;private var requestedFrames=8;private var requestedFps=10;private var mic=false
    private var frames=0;private var received=0;private var dropped=0;private var throttled=0;private var attempts=0
    private var sensorRotation=0
    private var firstSavedSensor=0L;private var lastSavedSensor=0L
    private var firstSensor=0L;private var lastSensor=0L;private var lastWritten=0L
    private var directory:File?=null;private var audio:PcmAudio?=null;private var fusion:LinearRawFusion?=null
    private val images=linkedMapOf<Long,Image>();private val metadata=linkedMapOf<Long,TotalCaptureResult>()
    private lateinit var texture:TextureView;private lateinit var status:TextView;private lateinit var shutter:Glyph
    private val permission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){if(it[Manifest.permission.CAMERA]==true)open()else status.text="ต้องอนุญาตกล้อง"}
    private val audioPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){mic=it;if(!it)notice("โหมดทดลองนี้จะไม่บันทึกเสียง");begin()}
    override fun onCreate(state:Bundle?){super.onCreate(state)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),0,dp(12),0)};installRoot(root)
        root.addView(pill("‹ กลับ"){if(!running&&!finishing)finish()},-1,-2);root.addView(label("RAW LAB",26f,LIME).apply{minHeight=dp(56)},-1,-2)
        root.addView(label("ทดลอง · DNG sequence / RAW linear fusion\nความเร็วและเสียงต้องประเมินจาก timestamps จริง",13f,MUTED).apply{minHeight=dp(64)},-1,-2)
        status=label("กำลังตรวจ RAW…",14f).apply{gravity=Gravity.CENTER;minHeight=dp(88)};root.addView(status,-1,-2)
        texture=TextureView(this);root.addView(texture,LinearLayout.LayoutParams(-1,0,1f))
        texture.surfaceTextureListener=object:TextureView.SurfaceTextureListener{override fun onSurfaceTextureAvailable(t:SurfaceTexture,w:Int,h:Int){if(visible)open()};override fun onSurfaceTextureSizeChanged(t:SurfaceTexture,w:Int,h:Int){};override fun onSurfaceTextureUpdated(t:SurfaceTexture){};override fun onSurfaceTextureDestroyed(t:SurfaceTexture):Boolean{handler.post{closeCamera()};return true}}
        val options=LinearLayout(this);options.addView(pill("ตัวเลือก",::options),LinearLayout.LayoutParams(0,-2,1f));options.addView(pill("ส่งออกชุดล่าสุด",::exportLast),LinearLayout.LayoutParams(0,-2,1f));root.addView(options,-1,-2)
        val bottom=FrameLayout(this);shutter=Glyph(this,"shutter","เริ่ม RAW video หรือ RAW AI"){if(running)stop()else if(!finishing&&!opening){if(mic&&!ai&&ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)audioPermission.launch(Manifest.permission.RECORD_AUDIO)else begin()}}.apply{video=true;isEnabled=false};bottom.addView(shutter,FrameLayout.LayoutParams(dp(80),dp(80),Gravity.CENTER));root.addView(bottom,-1,dp(108))
        cameraId=Capabilities.rawIds(this).firstOrNull().orEmpty()
        writer.execute{recover()}
    }
    override fun onResume(){super.onResume();visible=true;if(texture.isAvailable)open()}
    private fun options(){if(running||finishing||opening)return
        studioSheet("RAW ทดลอง"){box,d->
            box.addView(pill(if(ai)"โหมด RAW AI · $requestedFrames เฟรม" else "โหมด RAW Video · ทดสอบ 10 วินาที"){AlertDialog.Builder(this).setTitle("การบันทึก").setItems(arrayOf("RAW Video · 10 วินาที","RAW AI · 4 เฟรม","RAW AI · 8 เฟรม","RAW AI · 16 เฟรม")){_,i->ai=i>0;requestedFrames=if(i>0)listOf(4,8,16)[i-1]else 8;d.dismiss();status.text=if(ai)"RAW AI · $requestedFrames เฟรม · ถือเครื่องนิ่ง"else "RAW Video · 10 วินาที"}.show()},-1,-2)
            val ids=Capabilities.rawIds(this)
            box.addView(pill("กล้อง RAW · $cameraId"){AlertDialog.Builder(this).setItems(ids.map{"Camera $it"}.toTypedArray()){_,i->cameraId=ids[i];size=Size(0,0);d.dismiss();open()}.show()},-1,-2)
            if(cameraId.isNotBlank()){
                val sizes=Capabilities.characteristics(this,cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.RAW_SENSOR).orEmpty().filter{it.width.toLong()*it.height<=16_000_000}
                box.addView(pill("ขนาด · $size"){AlertDialog.Builder(this).setTitle("RAW ≤ 16 MP ในรุ่นทดลองนี้").setItems(sizes.map{it.toString()}.toTypedArray()){_,i->size=sizes[i];d.dismiss();open()}.show()},-1,-2)
            }
            box.addView(pill("เป้าหมายสูงสุด · $requestedFps fps"){AlertDialog.Builder(this).setItems(arrayOf("1 fps","5 fps","10 fps","15 fps","24 fps")){_,i->requestedFps=listOf(1,5,10,15,24)[i];d.dismiss()}.show()},-1,-2)
            box.addView(Switch(this).apply{text="WAV แยกพร้อมข้อมูลซิงก์ · เฉพาะ RAW video";setTextColor(WHITE);isChecked=mic;setOnCheckedChangeListener{_,v->mic=v}},-1,-2)
            box.addView(label("วัด fps ที่เขียนได้จริง เฟรมที่ทิ้งเพราะเขียนไม่ทันแสดงแยกจากเฟรมที่เว้นตามเป้าหมาย หาก sensor timestamp source ไม่ใช่ REALTIME จะยังยืนยัน A/V sync ไม่ได้",13f,MUTED),-1,-2)
        }
    }
    @SuppressLint("MissingPermission") @Suppress("DEPRECATION")
    private fun open(){
        if(!visible||!texture.isAvailable||running||finishing||opening)return
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){permission.launch(arrayOf(Manifest.permission.CAMERA));return}
        if(cameraId.isBlank()){status.text="เครื่องนี้ไม่เปิด RAW ให้แอป";return}
        opening=true;shutter.isEnabled=false
        handler.post{try{
            closeCamera();val cc=manager.getCameraCharacteristics(cameraId)
            val sizes=cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.getOutputSizes(ImageFormat.RAW_SENSOR).filter{it.width.toLong()*it.height<=16_000_000}
            check(sizes.isNotEmpty()){ "RAW ที่กล้องเปิดให้เกินเพดาน 16 MP ของโหมดทดลองนี้" }
            if(size !in sizes)size=sizes.maxBy{it.width.toLong()*it.height}
            val r=ImageReader.newInstance(size.width,size.height,ImageFormat.RAW_SENSOR,4);reader=r
            r.setOnImageAvailableListener({source->runCatching{source.acquireNextImage()}.getOrNull()?.let{image->
                if(!running){image.close();return@let};received++
                val ts=image.timestamp
                val result=metadata.remove(ts)
                if(result!=null)consume(image,result)else{
                    images[ts]=image
                    if(images.size>2){val key=images.keys.first();images.remove(key)?.close();dropped++}
                    handler.postDelayed({images.remove(ts)?.let{it.close();dropped++;if(ai&&running)nextAi()}},800)
                }
            }},handler)
            manager.openCamera(cameraId,object:CameraDevice.StateCallback(){override fun onOpened(camera:CameraDevice){device=camera;if(!visible){closeCamera();return}
                val st=texture.surfaceTexture?:return;st.setDefaultBufferSize(640,480);previewSurface=Surface(st)
                camera.createCaptureSession(listOf(previewSurface!!,r.surface),object:CameraCaptureSession.StateCallback(){override fun onConfigured(s:CameraCaptureSession){session=s;preview();runOnUiThread{opening=false;shutter.isEnabled=true;status.text="Camera $cameraId · $size\n${if(ai)"RAW AI $requestedFrames เฟรม"else "RAW Video 10 วินาที · เป้าหมาย $requestedFps fps"}"}}
                    override fun onConfigureFailed(s:CameraCaptureSession){s.close();fail("ชุด RAW + preview นี้ไม่รองรับ")}},handler)
            };override fun onDisconnected(camera:CameraDevice){camera.close();device=null;stop();fail("กล้องถูกตัดการเชื่อมต่อ")};override fun onError(camera:CameraDevice,error:Int){camera.close();device=null;stop();fail("กล้อง RAW ผิดพลาด $error")}},handler)
        }catch(e:Exception){closeCamera();fail(e.localizedMessage?:"เปิด RAW ไม่ได้")}}
    }
    private fun preview(){val d=device?:return;val target=previewSurface?:return;runCatching{val req=d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply{addTarget(target);set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO)};session?.setRepeatingRequest(req.build(),null,handler)}}
    private val callback=object:CameraCaptureSession.CaptureCallback(){
        override fun onCaptureCompleted(s:CameraCaptureSession,request:CaptureRequest,result:TotalCaptureResult){if(!running||request.tag!="RAW")return;val ts=result.get(CaptureResult.SENSOR_TIMESTAMP)?:return;val image=images.remove(ts);if(image!=null)consume(image,result)else{metadata[ts]=result;while(metadata.size>8)metadata.remove(metadata.keys.first())}}
        override fun onCaptureFailed(s:CameraCaptureSession,request:CaptureRequest,failure:CaptureFailure){if(running){dropped++;if(ai)nextAi()}}
    }
    private fun rawRequest():CaptureRequest{val d=device?:error("กล้องยังไม่พร้อม");return d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply{addTarget(reader!!.surface);addTarget(previewSurface!!);setTag("RAW");set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO)}.build()}
    private fun begin(){if(running||finishing||session==null)return
        if(filesDir.usableSpace<128L*1024*1024){notice("ควรมีพื้นที่อย่างน้อย 128 MB เพื่อเริ่มทดลอง");return}
        requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
        running=true;frames=0;received=0;dropped=0;throttled=0;attempts=0;firstSensor=0;lastSensor=0;firstSavedSensor=0;lastSavedSensor=0;lastWritten=0;fusion=null
        val dir=File(filesDir,"raw-sessions/${System.currentTimeMillis()}").apply{mkdirs()};directory=dir
        val cc=manager.getCameraCharacteristics(cameraId)
        @Suppress("DEPRECATION") val display=when(windowManager.defaultDisplay.rotation){Surface.ROTATION_90->90;Surface.ROTATION_180->180;Surface.ROTATION_270->270;else->0}
        sensorRotation=((cc.get(CameraCharacteristics.SENSOR_ORIENTATION)?:0)-display+360)%360
        val manifest=JSONObject().put("schema",1).put("state","recording").put("cameraId",cameraId).put("width",size.width).put("height",size.height).put("kind",if(ai)"RAW_LINEAR_AI"else "DNG_SEQUENCE")
            .put("rotationDegrees",sensorRotation).put("requestedFps",requestedFps).put("requestedFrames",if(ai)requestedFrames else JSONObject.NULL).put("timestampSource",cc.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)).put("startElapsedRealtimeNs",SystemClock.elapsedRealtimeNanos())
        File(dir,"manifest.json").writeText(manifest.toString(2))
        if(mic&&!ai)runCatching{PcmAudio(File(dir,"audio.wav")).also{audio=it;it.start()}}.onFailure{audio=null;notice("ไมค์เปิดไม่ได้ · ${it.localizedMessage}")}
        shutter.recording=true;status.text="กำลังรับ RAW…"
        handler.post{try{if(ai)nextAi()else{session?.setRepeatingRequest(rawRequest(),callback,handler);handler.postDelayed({if(running&&!ai)stop()},10_000)}}catch(e:Exception){stop();fail(e.localizedMessage?:"เริ่ม RAW ไม่ได้")}}
    }
    private fun nextAi(){if(!running)return;if(frames>=requestedFrames||attempts>=requestedFrames*3){stop();return};attempts++;runCatching{session?.capture(rawRequest(),callback,handler)}.onFailure{stop();fail(it.localizedMessage?:"ถ่าย RAW ไม่ได้")}}
    private fun consume(image:Image,result:TotalCaptureResult){
        if(!running){image.close();return};val ts=image.timestamp
        if(firstSensor==0L)firstSensor=ts;lastSensor=ts
        if(!ai&&lastWritten>0&&ts-lastWritten<1_000_000_000L/requestedFps){throttled++;image.close();return}
        if(!writing.compareAndSet(false,true)){dropped++;image.close();return}
        if(filesDir.usableSpace<maxOf(32L*1024*1024,size.width.toLong()*size.height*3)){writing.set(false);image.close();stop();return}
        lastWritten=ts;val dir=directory!!;val index=frames
        writer.execute{
            try{
                val partial=File(dir,"%06d.part".format(java.util.Locale.US,index));val complete=File(dir,"%06d.dng".format(java.util.Locale.US,index));val cc=manager.getCameraCharacteristics(cameraId)
                DngCreator(cc,result).use{creator->creator.setOrientation(when(sensorRotation){90->6;180->3;270->8;else->1});partial.outputStream().use{creator.writeImage(it,image)}}
                check(partial.renameTo(complete)){"ปิดไฟล์ DNG ไม่ได้"}
                File(dir,"frames.jsonl").appendText(JSONObject().put("index",index).put("file",complete.name).put("sensorNs",ts).put("frameNumber",result.frameNumber).put("exposureNs",result.get(CaptureResult.SENSOR_EXPOSURE_TIME)).put("iso",result.get(CaptureResult.SENSOR_SENSITIVITY)).toString()+"\n")
                frames++
                if(firstSavedSensor==0L)firstSavedSensor=ts;lastSavedSensor=ts
                if(ai){val linear=RawBayer.sample(image,result,cc);val f=fusion;if(f==null)fusion=LinearRawFusion(linear.width,linear.height,linear.rgb)else f.add(linear.rgb)}
                runOnUiThread{status.text="${if(ai)"RAW AI $frames/$requestedFrames"else "DNG $frames เฟรม"}\nทิ้งเพราะเขียนไม่ทัน $dropped · เว้นตามเป้าหมาย $throttled"}
            }catch(e:OutOfMemoryError){handler.post{stop();fail("หน่วยความจำไม่พอ · เก็บ DNG ที่ปิดไฟล์แล้วไว้")}}catch(e:Exception){handler.post{stop();fail("RAW หยุด · ${e.localizedMessage}")}}
            finally{image.close();writing.set(false);handler.post{if(ai&&running)nextAi()}}
        }
    }
    private fun stop(){if(!running)return;running=false;finishing=true
        handler.post{preview();images.values.forEach{it.close()};images.clear();metadata.clear()}
        runOnUiThread{shutter.isEnabled=false;shutter.recording=false;status.text="กำลังปิดชุด RAW…"}
        val dir=directory?:return
        writer.execute{
            runCatching{
                val manifest=JSONObject(File(dir,"manifest.json").readText());val audioInfo=audio?.stop();audio=null
                manifest.put("state",if(frames>0)"complete"else "empty").put("frames",frames).put("received",received).put("droppedBusy",dropped).put("throttled",throttled)
                    .put("firstSensorNs",firstSensor).put("lastSensorNs",lastSensor).put("firstSavedSensorNs",firstSavedSensor).put("lastSavedSensorNs",lastSavedSensor).put("measuredWrittenFps",if(lastSavedSensor>firstSavedSensor)(frames-1).coerceAtLeast(0)*1e9/(lastSavedSensor-firstSavedSensor)else 0.0).put("audio",audioInfo?:JSONObject.NULL)
                File(dir,"manifest.json").writeText(manifest.toString(2));MediaLedger.rememberFile(this,File(dir,"manifest.json"),"application/json","RAW $frames เฟรม · dropped $dropped")
                if(ai&&fusion!=null&&frames>0){
                    val f=fusion!!;val bitmap=Bitmap.createBitmap(f.finish(),f.width(),f.height(),Bitmap.Config.ARGB_8888);val merged=File(dir,"RAW_LINEAR_FUSION.jpg");PhotoEffects.write(bitmap,merged);PhotoMedia.tag(merged,sensorRotation);bitmap.recycle()
                    val time=System.currentTimeMillis();val id=UUID.randomUUID().toString();val first=dir.listFiles{file->file.extension=="dng"}!!.sortedBy{it.name}.first()
                    val raw=PhotoMedia.publish(this,first,"Lumora_RAW_${time}.dng",time,"image/x-adobe-dng");val uri=PhotoMedia.publish(this,merged,"Lumora_RAW_FUSION_${time}.jpg",time)
                    luma.shots.insert(Shot(id,time,uri,PhotoMedia.dimensions(merged),1f,true,cameraId,null,Decision.MANUAL_AI,Stage.QUEUED,null,0,null,false,raw,CaptureOutput.RAW,frames,requestedFrames,f.used(),"ทดลอง RAW linear fusion ก่อน gamma · ลดขนาด Bayer 2×2 · ไม่มี lens shading / hot-pixel calibration"));EnhanceWorker.enqueue(this,id)
                }
                fusion=null
            }.onSuccess{runOnUiThread{status.text="เก็บ $frames เฟรมแล้ว · dropped $dropped\n${if(ai)"เข้าคิว SR1 แล้ว · DNG ต้นฉบับยังอยู่"else "ส่งออก ZIP เพื่อดู DNG / WAV / timestamps"}"}}.onFailure{runOnUiThread{status.text="ไฟล์ที่ปิดแล้วอยู่ในแอป · ${it.localizedMessage}"}}
            runOnUiThread{finishing=false;shutter.isEnabled=visible&&session!=null;requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
        }
    }
    private fun exportLast(){if(running||finishing){notice("หยุดบันทึกก่อนส่งออก");return};writer.execute{runCatching{
        val dir=directory?:File(filesDir,"raw-sessions").listFiles()?.filter{it.isDirectory}?.maxByOrNull{it.name}?:error("ยังไม่มีชุด RAW")
        val file=File(filesDir,"raw-exports/RAW-${dir.name}.zip");file.parentFile?.mkdirs();val partial=File(file.parentFile,"${file.name}.part")
        ZipOutputStream(partial.outputStream().buffered()).use{out->dir.listFiles()?.filter{it.isFile&&it.extension!="part"}?.sortedBy{it.name}?.forEach{source->out.putNextEntry(ZipEntry(source.name));source.inputStream().use{it.copyTo(out)};out.closeEntry()}}
        check(partial.renameTo(file));MediaLedger.rememberFile(this,file,"application/zip","DNG sequence + metadata + WAV เมื่อเปิดไมค์");file
    }.onSuccess{file->runOnUiThread{MediaLedger.share(this,MediaLedger.privateUri(this,file),"application/zip")}}.onFailure{runOnUiThread{notice(it.localizedMessage?:"ส่งออกไม่ได้")}}}}
    private fun recover(){File(filesDir,"raw-sessions").listFiles()?.filter{it.isDirectory}?.forEach{dir->runCatching{val file=File(dir,"manifest.json");val j=JSONObject(file.readText());if(j.optString("state")=="recording"){dir.listFiles{f->f.extension=="part"}?.forEach{it.delete()};PcmAudio.recover(File(dir,"audio.wav"));j.put("state","interrupted").put("recoveredDngFiles",dir.listFiles{f->f.extension=="dng"}?.size?:0);file.writeText(j.toString(2))}}}}
    private fun fail(message:String){runOnUiThread{opening=false;status.text=message;shutter.isEnabled=false}}
    private fun closeCamera(){session?.close();session=null;device?.close();device=null;images.values.forEach{it.close()};images.clear();metadata.clear();reader?.close();reader=null;previewSurface?.release();previewSurface=null}
    override fun onPause(){visible=false;stop();handler.post{closeCamera()};super.onPause()}
    override fun onDestroy(){handler.post{closeCamera();thread.quitSafely()};writer.shutdown();super.onDestroy()}
}
