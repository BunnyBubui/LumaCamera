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

/** Exposure-bracket fusion to SDR. The JPEG reference is retained in the system gallery. */
class HdrCapture(private val c:Context,private val camera:Camera,private val capture:ImageCapture,private val progress:(Int)->Unit,private val done:(File?,String?)->Unit){
    private val folder=File(c.filesDir,"hdr/${UUID.randomUUID()}").apply{mkdirs()}
    private val files=mutableListOf<File>();private var stopped=false;private var finished=false
    private val main=Handler(Looper.getMainLooper());private val initial=camera.cameraInfo.exposureState.exposureCompensationIndex
    private val range=camera.cameraInfo.exposureState.exposureCompensationRange
    private val step=camera.cameraInfo.exposureState.exposureCompensationStep.toFloat()
    private val levels=listOf(0,if(step>0)(-2/step).toInt().coerceIn(range.lower,range.upper) else 0,if(step>0)(2/step).toInt().coerceIn(range.lower,range.upper) else 0).distinct()
    fun cancel(){stopped=true}
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
                override fun onImageSaved(result:ImageCapture.OutputFileResults){files+=file;main.post{next()}}
                override fun onError(e:ImageCaptureException){finish(null,e.localizedMessage)}
            })}},200)
        },androidx.core.content.ContextCompat.getMainExecutor(c))
        main.postDelayed({if(!finished&&files.size==index)finish(null,"ถ่าย HDR ใช้เวลานานเกินไป")},30_000)
    }
    private fun merge():File{
        val base=PhotoEffects.read(files.first(),3_000_000);val width=base.width;val height=base.height
        PhotoMedia.publish(c,files.first(),"LUMA_HDR_REFERENCE_${System.currentTimeMillis()}.jpg",System.currentTimeMillis())
        val bitmaps=mutableListOf(base)
        files.drop(1).forEach{val read=PhotoEffects.read(it,3_000_000);val scaled=Bitmap.createScaledBitmap(read,width,height,true);if(scaled!==read)read.recycle();bitmaps+=scaled}
        try{
            val pixels=bitmaps.map{b->IntArray(width*height).also{b.getPixels(it,0,width,0,0,width,height)}}
            val smallW=192;val smallH=(192f*height/width).toInt().coerceAtLeast(1)
            fun luma(b:Bitmap):IntArray{val t=Bitmap.createScaledBitmap(b,smallW,smallH,true);val a=IntArray(smallW*smallH);t.getPixels(a,0,smallW,0,0,smallW,smallH);t.recycle();return IntArray(a.size){FrameFusionEngine.luma(a[it])}}
            val reference=luma(base);val offsets=bitmaps.mapIndexed{i,b->if(i==0)0 to 0 else FrameFusionEngine.align(reference,luma(b),smallW,smallH).let{(it.dx*width.toFloat()/smallW).toInt() to (it.dy*height.toFloat()/smallH).toInt()}}
            val out=IntArray(width*height)
            for(y in 0 until height)for(x in 0 until width){var weight=0.0;val sum=DoubleArray(3);val idx=y*width+x
                for(i in pixels.indices){val xx=x+offsets[i].first;val yy=y+offsets[i].second;if(xx !in 0 until width||yy !in 0 until height)continue
                    val v=pixels[i][yy*width+xx];val lum=FrameFusionEngine.luma(v)/255.0
                    val w=kotlin.math.exp(-Math.pow((lum-0.5)/0.22,2.0))+0.01
                    weight+=w;for(ch in 0..2)sum[ch]+=w*((v ushr(16-ch*8))and 255)
                }
                out[idx]=0xff000000.toInt() or((sum[0]/weight).toInt().coerceIn(0,255) shl 16) or((sum[1]/weight).toInt().coerceIn(0,255) shl 8) or(sum[2]/weight).toInt().coerceIn(0,255)
            }
            val result=Bitmap.createBitmap(out,width,height,Bitmap.Config.ARGB_8888);val file=File(folder,"hdr-result.jpg");PhotoEffects.write(result,file);result.recycle();PhotoMedia.tag(file,0);return file
        }finally{bitmaps.forEach{it.recycle()};files.forEach{it.delete()}}
    }
    private fun finish(file:File?,error:String?){if(finished)return;finished=true;main.post{camera.cameraControl.setExposureCompensationIndex(initial);done(file,error)}}
}
