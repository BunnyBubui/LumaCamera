package th.ogay.luma

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import androidx.camera.core.Camera
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import java.io.File
import java.util.UUID
import th.ogay.luma.hdr.HdrFusionQuality
import th.ogay.luma.hdr.HdrNative
import androidx.exifinterface.media.ExifInterface
import kotlin.math.*

/** Exposure-bracket fusion to SDR. The JPEG reference is retained in the system gallery. */
class HdrCapture(private val c:Context,private val camera:Camera,private val capture:ImageCapture,private val progress:(Int)->Unit,private val done:(File?,String?)->Unit){
    private val folder=File(c.filesDir,"hdr/${UUID.randomUUID()}").apply{mkdirs()}
    private val files=mutableListOf<File>();@Volatile private var stopped=false;private var finished=false
    private val main=Handler(Looper.getMainLooper());private val initial=camera.cameraInfo.exposureState.exposureCompensationIndex
    private val range=camera.cameraInfo.exposureState.exposureCompensationRange
    private val step=camera.cameraInfo.exposureState.exposureCompensationStep.toFloat()
    var usedFrames=0;private set
    /** Frames are decoded while the next one is being shot, so the merge starts with them ready. */
    private val decoder=java.util.concurrent.Executors.newSingleThreadExecutor()
    private val decoded=java.util.concurrent.ConcurrentHashMap<Int,java.util.concurrent.Future<Bitmap>>()
    private val target:Long by lazy{val r=Runtime.getRuntime();val available=r.maxMemory()-(r.totalMemory()-r.freeMemory());(available/40).coerceIn(1_000_000L,8_000_000L)}
    /** Gallery label of the engine that actually produced the file. */
    var label="HDR EF1 · Exposure fusion SDR";private set
    val requestedFrames get()=levels.size
    private val spread=(c.getSharedPreferences("camera",0).getString("hdr_spread","2")?.toFloatOrNull()?:2f).coerceIn(1f,3f)
    private val levels=listOf(initial,if(step>0)(initial+(-spread/step).toInt()).coerceIn(range.lower,range.upper) else 0,if(step>0)(initial+(spread/step).toInt()).coerceIn(range.lower,range.upper) else 0).distinct()
    fun cancel(){stopped=true;decoder.shutdownNow()}
    fun start(){if(levels.size<2){finish(null,"กล้องไม่เปิดการชดเชยแสงสำหรับ HDR");return};next()}
    private fun next(){
        if(stopped){finish(null,"หยุดถ่าย HDR แล้ว");return}
        if(files.size==levels.size){c.luma.fileIo.execute{runCatching{merge()}.onSuccess{finish(it,null)}.onFailure{finish(null,it.localizedMessage)}};return}
        progress(files.size+1)
        val future=camera.cameraControl.setExposureCompensationIndex(levels[files.size])
        val index=files.size
        future.addListener({
            try{future.get()}catch(e:Exception){finish(null,e.localizedMessage);return@addListener}
            main.postDelayed({if(!stopped&&!finished){val file=File(folder,"$index.jpg");capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),c.luma.fileIo,object:ImageCapture.OnImageSavedCallback{
                override fun onImageSaved(result:ImageCapture.OutputFileResults){files+=file;decoded[index]=decoder.submit<Bitmap>{PhotoEffects.read(file,target)};main.post{next()}}
                override fun onError(e:ImageCaptureException){finish(null,e.localizedMessage)}
            })}},70)
        },androidx.core.content.ContextCompat.getMainExecutor(c))
        main.postDelayed({if(!finished&&files.size==index)finish(null,"ถ่าย HDR ใช้เวลานานเกินไป")},30_000)
    }
    private fun merge():File{
        val runtime=Runtime.getRuntime()
        val available=runtime.maxMemory()-(runtime.totalMemory()-runtime.freeMemory())
        fun frame(i:Int):Bitmap=decoded[i]?.let{runCatching{it.get()}.getOrNull()}?:PhotoEffects.read(files[i],target)
        // Only the merged picture is saved: the bracketed frames are working material and are deleted afterwards.
        val base=frame(0);val width=base.width;val height=base.height
        val bitmaps=mutableListOf(base)
        try{
            files.indices.drop(1).forEach{fi->if(stopped)throw java.util.concurrent.CancellationException("หยุด HDR");val read=frame(fi);val scaled=Bitmap.createScaledBitmap(read,width,height,true);if(scaled!==read)read.recycle();bitmaps+=scaled}
            val smallW=minOf(192,width);val smallH=(smallW.toFloat()*height/width).toInt().coerceAtLeast(1)
            fun luma(b:Bitmap):IntArray{val t=Bitmap.createScaledBitmap(b,smallW,smallH,true);val a=IntArray(smallW*smallH);t.getPixels(a,0,smallW,0,0,smallW,smallH);if(t!==b)t.recycle();return IntArray(a.size){FrameFusionEngine.luma(a[it])}}
            val reference=luma(base);val indices=mutableListOf(0);val dx=mutableListOf(0);val dy=mutableListOf(0)
            bitmaps.drop(1).forEachIndexed{i,image->
                val matched=HdrFusionQuality.matchHistogram(reference,luma(image))
                val alignment=FrameFusionEngine.align(reference,matched,smallW,smallH)
                if(alignment.error<=22){indices+=i+1;dx+=(alignment.dx*width.toFloat()/smallW).roundToInt();dy+=(alignment.dy*height.toFloat()/smallH).roundToInt()}
            }
            val pixels=indices.map{i->IntArray(width*height).also{bitmaps[i].getPixels(it,0,width,0,0,width,height)}}.toTypedArray()
            bitmaps.forEach{it.recycle()};bitmaps.clear()
            val dose=files.map{file->val exif=ExifInterface(file);val time=exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME,0.0);val iso=exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,0);time*iso}
            val measured=dose.all{it>0&&it.isFinite()}
            val ev=indices.map{i->if(measured)(ln(dose[i]/dose[0])/ln(2.0)).toFloat()else (levels[i]-levels[0])*step}.toFloatArray()
            val budget=(available/6).coerceIn(4L*1024*1024,48L*1024*1024)
            usedFrames=indices.size
            val cancellation=HdrFusionQuality.Cancellation{stopped||Thread.currentThread().isInterrupted}
            fun ef1():Bitmap=Bitmap.createBitmap(HdrFusionQuality.merge(pixels,width,height,dx.toIntArray(),dy.toIntArray(),ev,budget,cancellation),width,height,Bitmap.Config.ARGB_8888)
            // EF2 (native OpenCV) is opt-in from settings while it is being compared with EF1 on real scenes.
            val wantNative=c.getSharedPreferences("camera",0).getBoolean(NATIVE_KEY,false)
            var engine="EF1";var note=""
            val result=if(!wantNative)ef1() else if(!HdrNative.available){note=", EF2 unavailable: ${HdrNative.loadError?.javaClass?.simpleName}";ef1()} else try{
                val r=HdrNative.fuse(pixels,width,height,dx.toIntArray(),dy.toIntArray(),cancel=cancellation)
                engine="EF2";note=", fuse ${r.fuseMs.roundToInt()} ms, tone ${r.toneMs.roundToInt()} ms";r.bitmap
            }catch(e:java.util.concurrent.CancellationException){throw e}catch(e:Throwable){note=", EF2 failed: ${e.javaClass.simpleName}";ef1()}
            label=if(engine=="EF2")"HDR EF2 · OpenCV fusion + local tone SDR" else "HDR EF1 · Exposure fusion SDR"
            val file=File(folder,"hdr-result.jpg");try{PhotoEffects.write(result,file)}finally{result.recycle()}
            ExifCarry.copy(files.first(),file)
            PhotoMedia.tag(file,0,ExifInterface(files.first()).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
            val software=if(engine=="EF2")"Lumora HDR EF2 · OpenCV Mertens + L* detail SDR" else "Lumora HDR EF1 · exposure fusion SDR"
            ExifInterface(file).apply{setAttribute(ExifInterface.TAG_SOFTWARE,software);setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION,"HDR $engine: captured ${files.size}/${levels.size}, aligned $usedFrames, EV ${if(measured)"EXIF exposure×ISO" else "requested compensation"}$note");saveAttributes()}
            return file
        }finally{bitmaps.forEach{if(!it.isRecycled)it.recycle()};files.forEach{it.delete()};decoder.shutdown()}
    }
    companion object{const val NATIVE_KEY="hdr_native"}
    private fun finish(file:File?,error:String?){if(finished)return;finished=true;main.post{camera.cameraControl.setExposureCompensationIndex(initial);done(file,error)}}
}
