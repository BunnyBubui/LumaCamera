package th.ogay.luma

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.concurrent.TimeUnit

object PhotoEffects {
    fun read(file:File,maxPixels:Long=3_000_000):Bitmap {
        val dims=PhotoMedia.dimensions(file);var sample=1
        while(dims.pixels/(sample.toLong()*sample)>maxPixels*2)sample*=2
        val raw=BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply{inSampleSize=sample})?:error("อ่านภาพไม่ได้")
        val rotation=ExifInterface(file).rotationDegrees
        val rotated=if(rotation==0)raw else Bitmap.createBitmap(raw,0,0,raw.width,raw.height,Matrix().apply{postRotate(rotation.toFloat())},true).also{raw.recycle()}
        if(rotated.width.toLong()*rotated.height<=maxPixels)return rotated
        val scale=kotlin.math.sqrt(maxPixels.toDouble()/(rotated.width.toLong()*rotated.height))
        return Bitmap.createScaledBitmap(rotated,(rotated.width*scale).toInt().coerceAtLeast(1),(rotated.height*scale).toInt().coerceAtLeast(1),true).also{if(it!==rotated)rotated.recycle()}
    }
    /** Re-saves a finished JPEG as PNG or WebP next to it; the caller deletes the returned copy after publishing. */
    fun convert(file:File,format:CaptureOutput,quality:Int):File{
        val b=read(file,60_000_000);val ext=if(format==CaptureOutput.PNG)"png" else "webp";val out=File(file.parentFile,file.nameWithoutExtension+"-conv."+ext)
        val fmt=if(format==CaptureOutput.PNG)Bitmap.CompressFormat.PNG else if(android.os.Build.VERSION.SDK_INT>=30)Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION")Bitmap.CompressFormat.WEBP
        try{out.outputStream().use{check(b.compress(fmt,quality.coerceIn(60,100),it))}}finally{b.recycle()};return out
    }
    fun write(bitmap:Bitmap,file:File,quality:Int=95){file.outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.JPEG,quality,it))}}
    fun decorate(c:Context,file:File,rear:Boolean){
        val p=c.getSharedPreferences("camera",0);val aspect=p.getString("aspect","4:3")!!
        val mirror=!rear&&p.getBoolean("mirror_selfie",false);val watermark=p.getBoolean("watermark",false)
        val look=if(p.getString("photo_mode","PHOTO")=="FILM")Films.active(p,true) else Films.active(p)
        if(aspect!="4:3"||mirror||watermark||look!=null){
            val source=read(file,12_000_000)
            val portrait=source.height>=source.width
            val ratio=Aspects.value(aspect,!portrait)
            val w=minOf(source.width,(source.height*ratio).toInt());val h=minOf(source.height,(source.width/ratio).toInt())
            val cropped=Bitmap.createBitmap(source,(source.width-w)/2,(source.height-h)/2,w,h)
            val image=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);val canvas=Canvas(image)
            if(mirror){canvas.save();canvas.scale(-1f,1f,w/2f,h/2f)}
            canvas.drawBitmap(cropped,0f,0f,null);if(mirror)canvas.restore()
            if(look!=null){val styled=Films.render(image,look.first,look.second);canvas.drawBitmap(styled,0f,0f,null);styled.recycle()}
            if(watermark){val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;textSize=w*0.026f;setShadowLayer(3f,0f,1f,Color.BLACK)};canvas.drawText(p.getString("watermark_name","Luma")+"  ·  "+java.text.SimpleDateFormat("yyyy.MM.dd HH:mm",java.util.Locale.US).format(java.util.Date()),w*0.04f,h*0.96f,paint)}
            write(image,file,p.getString("jpeg_quality","95")!!.toInt());image.recycle();if(cropped!==source)cropped.recycle();source.recycle();PhotoMedia.tag(file,0)
        }
        if(p.getBoolean("gps",false)&&ContextCompat.checkSelfPermission(c,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED){
            runCatching{val manager=c.getSystemService(Context.LOCATION_SERVICE) as LocationManager;val location=manager.getProviders(true).mapNotNull{manager.getLastKnownLocation(it)}.maxByOrNull{it.time};if(location!=null&&System.currentTimeMillis()-location.time<15*60*1000)ExifInterface(file).apply{setGpsInfo(location);saveAttributes()}}
        }
    }
    /** [aperture] sets the simulated blur: f/1.4 ≈ the old fixed strength, f/16 almost none. */
    fun portrait(file:File,aperture:Float=2.8f){
        check(OpenCVLoader.initLocal()){ "เปิดระบบภาพ Portrait ไม่สำเร็จ" }
        val image=read(file);val processor=Segmentation.getClient(SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build())
        try{
            val mask=Tasks.await(processor.process(InputImage.fromBitmap(image,0)),30,TimeUnit.SECONDS)
            val source=Mat();val blurred=Mat();Utils.bitmapToMat(image,source);Imgproc.GaussianBlur(source,blurred,Size(0.0,0.0),(22.0*1.4/aperture.coerceIn(1f,22f)).coerceIn(1.0,26.0))
            val background=Bitmap.createBitmap(image.width,image.height,Bitmap.Config.ARGB_8888);Utils.matToBitmap(blurred,background);source.release();blurred.release()
            val a=IntArray(image.width*image.height);val b=IntArray(a.size);image.getPixels(a,0,image.width,0,0,image.width,image.height);background.getPixels(b,0,image.width,0,0,image.width,image.height)
            val confidence=mask.buffer;confidence.rewind();val weights=FloatArray(mask.width*mask.height);confidence.asFloatBuffer().get(weights)
            for(y in 0 until image.height)for(x in 0 until image.width){val i=y*image.width+x;val f=((weights[(y*mask.height/image.height)*mask.width+x*mask.width/image.width]-0.15f)/0.7f).coerceIn(0f,1f);var color=0xff000000.toInt();for(shift in intArrayOf(16,8,0)){val v=(((a[i] ushr shift)and 255)*f+((b[i] ushr shift)and 255)*(1-f)).toInt();color=color or(v shl shift)};a[i]=color}
            image.setPixels(a,0,image.width,0,0,image.width,image.height);write(image,file);PhotoMedia.tag(file,0);ExifInterface(file).apply{setAttribute(ExifInterface.TAG_SOFTWARE,"Luma Portrait · simulated f/$aperture · ML Kit selfie segmentation beta");saveAttributes()};background.recycle()
        }finally{processor.close();image.recycle()}
    }
}
