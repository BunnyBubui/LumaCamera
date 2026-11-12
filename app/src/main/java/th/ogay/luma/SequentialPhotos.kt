package th.ogay.luma

import android.content.Context
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import java.io.File
import java.util.UUID

class SequentialPhotos(private val c:Context,private val capture:ImageCapture,private val rear:Boolean,private val zoom:Float,private val progress:(Int)->Unit,private val done:(String)->Unit,private val started:()->Unit={}){
    private var count=0
    fun start(){next()}
    private fun next(){
        if(count==10){android.os.Handler(android.os.Looper.getMainLooper()).post{done("บันทึก 10 ภาพต่อเนื่องแล้ว")};return}
        if(c.filesDir.usableSpace<32L*1024*1024){done("พื้นที่ไม่พอ · บันทึกแล้ว $count ภาพ");return}
        val id=UUID.randomUUID().toString();val time=System.currentTimeMillis();val file=File(c.cacheDir,"burst-$id.jpg")
        capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),c.luma.fileIo,object:ImageCapture.OnImageSavedCallback{
            override fun onCaptureStarted(){if(count==0)android.os.Handler(android.os.Looper.getMainLooper()).post(started)}
            override fun onImageSaved(result:ImageCapture.OutputFileResults){try{PhotoEffects.decorate(c,file,rear);val uri=PhotoMedia.publish(c,file,"Lumora_${time}_${id}.jpg",time);c.luma.shots.insert(Shot(id,time,uri,PhotoMedia.dimensions(file),zoom,rear,null,null,Decision.OFF,Stage.ORIGINAL,null,0,null,false));count++;android.os.Handler(android.os.Looper.getMainLooper()).post{progress(count);next()}}catch(e:Exception){done("เก็บ $count ภาพ · ${e.localizedMessage}")}finally{file.delete()}}
            override fun onError(e:ImageCaptureException){done("เก็บ $count ภาพ · ${e.localizedMessage}");file.delete()}
        })
    }
}
