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
        val b=read(file,13_000_000);val ext=if(format==CaptureOutput.PNG)"png" else "webp";val out=File(file.parentFile,file.nameWithoutExtension+"-conv."+ext)
        val fmt=if(format==CaptureOutput.PNG)Bitmap.CompressFormat.PNG else if(android.os.Build.VERSION.SDK_INT>=30)Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION")Bitmap.CompressFormat.WEBP
        try{out.outputStream().buffered(1 shl 20).use{check(b.compress(fmt,if(format==CaptureOutput.PNG)100 else quality.coerceIn(60,92),it))}}finally{b.recycle()};return out
    }
    /**
     * Vector watermark drawn at the photo's own resolution (sharp at any size): the Lumora ring-and-spark mark,
     * the name in a light wide-spaced face and the date beneath, bottom-left with a soft shadow for any background.
     */
    fun drawWatermark(canvas:Canvas,w:Int,h:Int,name:String){
        val unit=minOf(w,h)/100f
        val shadow=0x66000000
        val x=unit*4.2f;val base=h-unit*4.2f
        val mark=unit*4.6f
        val cx=x+mark/2;val cy=base-mark*0.62f
        val ring=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=unit*0.42f;strokeCap=Paint.Cap.ROUND;color=Color.WHITE;setShadowLayer(unit*0.5f,0f,unit*0.12f,shadow)}
        canvas.drawArc(RectF(cx-mark/2,cy-mark/2,cx+mark/2,cy+mark/2),-60f,300f,false,ring)
        val dot=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;setShadowLayer(unit*0.5f,0f,unit*0.12f,shadow)}
        canvas.drawCircle(cx-mark*0.42f,cy+mark*0.25f,unit*0.5f,dot)
        // Four-point spark at the ring's open end.
        val sx=cx+mark*0.36f;val sy=cy-mark*0.42f;val r=unit*1.15f;val k=r*0.28f
        canvas.drawPath(Path().apply{moveTo(sx,sy-r);lineTo(sx+k,sy-k);lineTo(sx+r,sy);lineTo(sx+k,sy+k);lineTo(sx,sy+r);lineTo(sx-k,sy+k);lineTo(sx-r,sy);lineTo(sx-k,sy-k);close()},dot)
        val title=Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply{color=Color.WHITE;typeface=Typeface.create("sans-serif-light",Typeface.NORMAL);textSize=unit*3.3f;letterSpacing=0.12f;setShadowLayer(unit*0.5f,0f,unit*0.12f,shadow)}
        val tx=x+mark+unit*2f
        canvas.drawText(name,tx,cy+unit*0.2f,title)
        val small=Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply{color=0xe6ffffff.toInt();typeface=Typeface.create("sans-serif-condensed",Typeface.NORMAL);textSize=unit*1.7f;letterSpacing=0.18f;setShadowLayer(unit*0.4f,0f,unit*0.1f,shadow)}
        canvas.drawText(java.text.SimpleDateFormat("dd.MM.yyyy   HH:mm",java.util.Locale.US).format(java.util.Date()),tx,cy+unit*2.9f,small)
    }
    fun write(bitmap:Bitmap,file:File,quality:Int=95){file.outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.JPEG,quality,it))}}
    /** Re-encodes [file] in place but keeps the camera's EXIF (exposure, ISO, lens, time, GPS), which a plain compress drops. */
    fun rewrite(bitmap:Bitmap,file:File,quality:Int=95){val kept=ExifCarry.read(file);write(bitmap,file,quality);ExifCarry.write(file,kept)}
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
            if(watermark)drawWatermark(canvas,w,h,p.getString("watermark_name","Lumora")?:"Lumora")
            rewrite(image,file,p.getString("jpeg_quality","95")!!.toInt());image.recycle();if(cropped!==source)cropped.recycle();source.recycle();PhotoMedia.tag(file,0)
        }
        if(p.getBoolean("gps",false)&&ContextCompat.checkSelfPermission(c,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED){
            runCatching{val manager=c.getSystemService(Context.LOCATION_SERVICE) as LocationManager;val location=manager.getProviders(true).mapNotNull{manager.getLastKnownLocation(it)}.maxByOrNull{it.time};if(location!=null&&System.currentTimeMillis()-location.time<15*60*1000)ExifInterface(file).apply{setGpsInfo(location);saveAttributes()}}
        }
    }
    /** [aperture] sets the simulated blur: f/1.4 ≈ the old fixed strength, f/16 almost none. */
    fun portrait(file:File,aperture:Float=2.8f){
        check(OpenCVLoader.initLocal()){ "เปิดระบบภาพ Portrait ไม่สำเร็จ" }
        val image=read(file,8_000_000);val processor=Segmentation.getClient(SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build())
        try{
            val mask=Tasks.await(processor.process(InputImage.fromBitmap(image,0)),30,TimeUnit.SECONDS)
            val source=Mat();val blurred=Mat();Utils.bitmapToMat(image,source);Imgproc.GaussianBlur(source,blurred,Size(0.0,0.0),(22.0*1.4/aperture.coerceIn(1f,22f)*image.width/2000.0).coerceIn(1.0,60.0))
            val background=Bitmap.createBitmap(image.width,image.height,Bitmap.Config.ARGB_8888);Utils.matToBitmap(blurred,background);source.release();blurred.release()
            val a=IntArray(image.width*image.height);val b=IntArray(a.size);image.getPixels(a,0,image.width,0,0,image.width,image.height);background.getPixels(b,0,image.width,0,0,image.width,image.height)
            val confidence=mask.buffer;confidence.rewind();val weights=FloatArray(mask.width*mask.height);confidence.asFloatBuffer().get(weights)
            for(y in 0 until image.height)for(x in 0 until image.width){val i=y*image.width+x;val f=((weights[(y*mask.height/image.height)*mask.width+x*mask.width/image.width]-0.15f)/0.7f).coerceIn(0f,1f);var color=0xff000000.toInt();for(shift in intArrayOf(16,8,0)){val v=(((a[i] ushr shift)and 255)*f+((b[i] ushr shift)and 255)*(1-f)).toInt();color=color or(v shl shift)};a[i]=color}
            image.setPixels(a,0,image.width,0,0,image.width,image.height);rewrite(image,file);PhotoMedia.tag(file,0);ExifInterface(file).apply{setAttribute(ExifInterface.TAG_SOFTWARE,"Lumora Portrait · simulated f/$aperture · ML Kit selfie segmentation beta");saveAttributes()};background.recycle()
        }finally{processor.close();image.recycle()}
    }
}
