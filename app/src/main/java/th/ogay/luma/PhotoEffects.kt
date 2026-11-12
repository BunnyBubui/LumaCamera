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
     * Watermark, each part optional: the Lumora logo in the chosen corner, the time and date in the other corner of
     * the same edge, and (for those who want it) the camera settings in the middle of that edge with small icons.
     * Drawn at the photo's own resolution with a soft shadow so it reads on any background.
     */
    fun drawWatermark(c:Context,canvas:Canvas,w:Int,h:Int,exif:ExifInterface?=null){
        val p=c.getSharedPreferences("camera",0)
        val corner=p.getString("watermark_corner","BL")?:"BL"
        val top=corner.startsWith("T");val logoLeft=corner.endsWith("L")
        val unit=minOf(w,h)/100f
        val shade=0x73000000
        val margin=unit*3.6f
        // Every part is laid out against [base]: the bottom edge of the row, or its top edge when it sits at the top.
        val rowH=unit*4.6f
        val rowTop=if(top)margin else h-margin-rowH
        if(p.getBoolean("watermark_logo",true)){
            val logo=android.graphics.BitmapFactory.decodeResource(c.resources,R.drawable.lumora_mark)
            if(logo!=null){
                val lw=rowH*logo.width/logo.height;val x=if(logoLeft)margin else w-margin-lw
                val dst=RectF(x,rowTop,x+lw,rowTop+rowH)
                val glow=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply{colorFilter=PorterDuffColorFilter(shade,PorterDuff.Mode.SRC_IN);maskFilter=BlurMaskFilter(unit*0.45f,BlurMaskFilter.Blur.NORMAL)}
                canvas.drawBitmap(logo,null,RectF(dst).apply{offset(0f,unit*0.15f)},glow)
                canvas.drawBitmap(logo,null,dst,Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                logo.recycle()
            }
        }
        if(p.getBoolean("watermark_time",true)){
            val now=java.util.Date()
            val align=if(logoLeft)Paint.Align.RIGHT else Paint.Align.LEFT
            val time=Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply{color=Color.WHITE;typeface=Typeface.create("sans-serif-thin",Typeface.NORMAL);textSize=unit*3.3f;letterSpacing=0.04f;textAlign=align;setShadowLayer(unit*0.45f,0f,unit*0.12f,shade)}
            val date=Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply{color=0xebffffff.toInt();typeface=Typeface.create("sans-serif-condensed",Typeface.NORMAL);textSize=unit*1.25f;letterSpacing=0.32f;textAlign=align;setShadowLayer(unit*0.35f,0f,unit*0.1f,shade)}
            val x=if(logoLeft)w-margin else margin
            val dateText=java.text.SimpleDateFormat("EEE  ·  dd MMM yyyy",java.util.Locale.US).format(now).uppercase(java.util.Locale.US)
            val dateBase=rowTop+rowH-unit*0.1f
            // Letter spacing adds room after the last letter too; shift by it so the right edges line up.
            canvas.drawText(dateText,if(logoLeft)x+date.letterSpacing*date.textSize else x,dateBase,date)
            val timeBase=dateBase-date.textSize*2.1f
            canvas.drawText(java.text.SimpleDateFormat("HH:mm",java.util.Locale.US).format(now),x,timeBase,time)
            val rule=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=0xccff9f43.toInt();strokeWidth=unit*0.16f;strokeCap=Paint.Cap.ROUND;setShadowLayer(unit*0.3f,0f,0f,shade)}
            val ry=timeBase+date.textSize*0.7f;if(logoLeft)canvas.drawLine(x-unit*5.5f,ry,x,ry,rule) else canvas.drawLine(x,ry,x+unit*5.5f,ry,rule)
        }
        if(p.getBoolean("watermark_camera",false)&&exif!=null)drawCameraLine(canvas,w,rowTop+rowH*0.5f,unit,shade,exif)
    }
    /** Lens, aperture, shutter and ISO from the photo's EXIF, each with a small icon, centred on the watermark row. */
    private fun drawCameraLine(canvas:Canvas,w:Int,cy:Float,unit:Float,shade:Int,exif:ExifInterface){
        val parts=ArrayList<Pair<String,String>>()
        exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,0).takeIf{it>0}?.let{parts+="M12 4a8 8 0 1 0 0 16a8 8 0 1 0 0-16 M12 8.5a3.5 3.5 0 1 0 0 7a3.5 3.5 0 1 0 0-7" to "${it}mm"}
        exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER,0.0).takeIf{it>0}?.let{parts+="M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 1 0 0-17 M12 3.5l2.5 7.5 M20.5 12l-7.5 2.5 M12 20.5l-2.5-7.5 M3.5 12l7.5-2.5" to String.format(java.util.Locale.US,"f/%.1f",it)}
        exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME,0.0).takeIf{it>0}?.let{t->parts+="M12 6.5a7 7 0 1 0 0 14a7 7 0 1 0 0-14 M12 10v3.5l2.2 1.5 M10 3h4" to (if(t<1)"1/${Math.round(1/t)}" else String.format(java.util.Locale.US,"%.1fs",t))}
        exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,0).takeIf{it>0}?.let{parts+="M3.5 6.5h17v11h-17z M7 10v4 M10.5 10h2l-2 4h2 M16 10a1.5 2 0 1 0 0 4a1.5 2 0 1 0 0-4" to "ISO $it"}
        if(parts.isEmpty())return
        val text=Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply{color=0xf2ffffff.toInt();typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);textSize=unit*1.55f;letterSpacing=0.06f;setShadowLayer(unit*0.4f,0f,unit*0.1f,shade)}
        val stroke=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;color=0xf2ffffff.toInt();strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND;setShadowLayer(unit*0.35f,0f,unit*0.1f,shade)}
        val icon=unit*1.9f;val gap=unit*0.7f;val sep=unit*2.2f
        val widths=parts.map{(_,t)->icon+gap+text.measureText(t)}
        var x=w/2f-(widths.sum()+sep*(parts.size-1))/2f
        val divider=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=0x66ffffff;strokeWidth=unit*0.12f}
        parts.forEachIndexed{i,(d,t)->
            val path=Path(SvgPath.parse(d));val m=Matrix();m.setScale(icon/24f,icon/24f);m.postTranslate(x,cy-icon/2f);path.transform(m)
            stroke.strokeWidth=1.7f*icon/24f;canvas.drawPath(path,stroke)
            canvas.drawText(t,x+icon+gap,cy+text.textSize*0.36f,text)
            x+=widths[i]
            if(i<parts.size-1){canvas.drawLine(x+sep/2f,cy-icon*0.45f,x+sep/2f,cy+icon*0.45f,divider);x+=sep}
        }
    }
    fun write(bitmap:Bitmap,file:File,quality:Int=95){file.outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.JPEG,quality,it))}}
    /** Re-encodes [file] in place but keeps the camera's EXIF (exposure, ISO, lens, time, GPS), which a plain compress drops. */
    fun rewrite(bitmap:Bitmap,file:File,quality:Int=95){val kept=ExifCarry.read(file);write(bitmap,file,quality);ExifCarry.write(file,kept)}
    fun decorate(c:Context,file:File,rear:Boolean){
        val p=c.getSharedPreferences("camera",0);val aspect=p.getString("aspect","4:3")!!
        val mirror=!rear&&p.getBoolean("mirror_selfie",false);val watermark=p.getBoolean("watermark",true)
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
            if(watermark)drawWatermark(c,canvas,w,h,runCatching{ExifInterface(file)}.getOrNull())
            rewrite(image,file,p.getString("jpeg_quality","95")!!.toInt());image.recycle();if(cropped!==source)cropped.recycle();source.recycle();PhotoMedia.tag(file,0)
        }
        if(p.getBoolean("gps",false)&&ContextCompat.checkSelfPermission(c,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED){
            runCatching{val manager=c.getSystemService(Context.LOCATION_SERVICE) as LocationManager;val location=manager.getProviders(true).mapNotNull{manager.getLastKnownLocation(it)}.maxByOrNull{it.time};if(location!=null&&System.currentTimeMillis()-location.time<15*60*1000)ExifInterface(file).apply{setGpsInfo(location);saveAttributes()}}
        }
    }
    /** [aperture] sets the simulated blur: f/1.4 ≈ the old fixed strength, f/16 almost none. */
    fun portrait(file:File,aperture:Float=2.8f,focus:Pair<Float,Float>?=null){
        check(OpenCVLoader.initLocal()){ "เปิดระบบภาพ Portrait ไม่สำเร็จ" }
        val image=read(file,8_000_000);val processor=Segmentation.getClient(SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build())
        try{
            val mask=Tasks.await(processor.process(InputImage.fromBitmap(image,0)),30,TimeUnit.SECONDS)
            val source=Mat();val blurred=Mat();Utils.bitmapToMat(image,source);Imgproc.GaussianBlur(source,blurred,Size(0.0,0.0),(22.0*1.4/aperture.coerceIn(1f,22f)*image.width/2000.0).coerceIn(1.0,60.0))
            val background=Bitmap.createBitmap(image.width,image.height,Bitmap.Config.ARGB_8888);Utils.matToBitmap(blurred,background);source.release();blurred.release()
            val a=IntArray(image.width*image.height);val b=IntArray(a.size);image.getPixels(a,0,image.width,0,0,image.width,image.height);background.getPixels(b,0,image.width,0,0,image.width,image.height)
            val confidence=mask.buffer;confidence.rewind();val weights=FloatArray(mask.width*mask.height);confidence.asFloatBuffer().get(weights)
            // Same subject mask as the live view: a feathered person, or a focus falloff when nobody is in the picture.
            PortraitMask.subject(weights,mask.width,mask.height,focus)
            for(y in 0 until image.height)for(x in 0 until image.width){val i=y*image.width+x;val f=weights[(y*mask.height/image.height)*mask.width+x*mask.width/image.width];var color=0xff000000.toInt();for(shift in intArrayOf(16,8,0)){val v=(((a[i] ushr shift)and 255)*f+((b[i] ushr shift)and 255)*(1-f)).toInt();color=color or(v shl shift)};a[i]=color}
            image.setPixels(a,0,image.width,0,0,image.width,image.height);rewrite(image,file);PhotoMedia.tag(file,0);ExifInterface(file).apply{setAttribute(ExifInterface.TAG_SOFTWARE,"Lumora Portrait · simulated f/$aperture · ML Kit selfie segmentation beta");saveAttributes()};background.recycle()
        }finally{processor.close();image.recycle()}
    }
}
