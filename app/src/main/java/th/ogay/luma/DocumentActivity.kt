package th.ogay.luma

import android.app.AlertDialog
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.UUID
import kotlin.math.*

class DocumentActivity:ComponentActivity(){
    private val project by lazy{File(filesDir,"documents/${UUID.randomUUID()}").apply{mkdirs()}}
    private var image:Bitmap?=null;private val pages=mutableListOf<File>();private lateinit var editor:PageCorners;private lateinit var status:TextView
    private var bw=false;private var busy=false
    private val addPage=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)load(uri)}
    override fun onCreate(state:Bundle?){super.onCreate(state)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),0,dp(12),0)};installRoot(root)
        root.addView(pill("‹ กลับ"){finish()},-1,dp(48));root.addView(label("เอกสาร",26f),-1,dp(54))
        status=label("ลากจุดมุมทั้ง 4 เพื่อแก้ perspective",14f,MUTED);root.addView(status,-1,dp(52))
        editor=PageCorners(this);root.addView(editor,LinearLayout.LayoutParams(-1,0,1f))
        val row=LinearLayout(this)
        row.addView(pill("ตรวจขอบ"){detect()},LinearLayout.LayoutParams(0,dp(52),1f))
        row.addView(pill("สี / ขาวดำ"){bw=!bw;status.text=if(bw)"ส่งออกแบบขาวดำ" else "ส่งออกแบบสี"},LinearLayout.LayoutParams(0,dp(52),1f))
        row.addView(pill("เก็บหน้านี้"){savePage()},LinearLayout.LayoutParams(0,dp(52),1f));root.addView(row)
        val more=LinearLayout(this)
        more.addView(pill("เพิ่มภาพ"){if(!busy)addPage.launch(arrayOf("image/*"))},LinearLayout.LayoutParams(0,dp(52),1f))
        more.addView(pill("OCR Latin"){ocr()},LinearLayout.LayoutParams(0,dp(52),1f))
        more.addView(pill("สร้าง PDF"){pdf()},LinearLayout.LayoutParams(0,dp(52),1f));root.addView(more)
        intent.data?.let{load(it)}?:addPage.launch(arrayOf("image/*"))
    }
    private fun load(uri:Uri){if(busy)return;busy=true;status.text="กำลังอ่านภาพ…";luma.fileIo.execute{runCatching{
        val file=File(project,"input-${System.currentTimeMillis()}.jpg");contentResolver.openInputStream(uri)!!.use{input->file.outputStream().use{input.copyTo(it)}};PhotoEffects.read(file,4_000_000)
    }.onSuccess{b->runOnUiThread{image?.recycle();image=b;editor.bitmap=b;busy=false;status.text="ลากมุมแล้วเก็บหน้า · มี ${pages.size} หน้าในชุดนี้";detect()}}.onFailure{runOnUiThread{busy=false;status.text="อ่านภาพไม่ได้ · ${it.localizedMessage}"}}}}
    private fun detect(){val b=image?:return;if(busy)return;busy=true;status.text="กำลังตรวจขอบ…";luma.fileIo.execute{val result=runCatching{
        check(OpenCVLoader.initLocal());val m=Mat();val gray=Mat();val edge=Mat();Utils.bitmapToMat(b,m);Imgproc.cvtColor(m,gray,Imgproc.COLOR_RGBA2GRAY);Imgproc.GaussianBlur(gray,gray,Size(5.0,5.0),0.0);Imgproc.Canny(gray,edge,60.0,160.0)
        val contours=ArrayList<MatOfPoint>();val hierarchy=Mat();Imgproc.findContours(edge,contours,hierarchy,Imgproc.RETR_LIST,Imgproc.CHAIN_APPROX_SIMPLE)
        val found=contours.sortedByDescending{Imgproc.contourArea(it)}.take(12).firstNotNullOfOrNull{c->val curve=MatOfPoint2f(*c.toArray());val approx=MatOfPoint2f();Imgproc.approxPolyDP(curve,approx,Imgproc.arcLength(curve,true)*0.02,true);val arr=approx.toArray();curve.release();approx.release();if(arr.size==4&&Imgproc.contourArea(c)>b.width*b.height*0.1)arr else null}
        contours.forEach{it.release()};m.release();gray.release();edge.release();hierarchy.release();found?.let{a->val sorted=a.sortedBy{it.y};val top=sorted.take(2).sortedBy{it.x};val bottom=sorted.takeLast(2).sortedByDescending{it.x};(top+bottom).flatMap{listOf((it.x/b.width).toFloat(),(it.y/b.height).toFloat())}.toFloatArray()}
    }.getOrNull();runOnUiThread{busy=false;if(result!=null){editor.corners=result;editor.invalidate();status.text="พบขอบแล้ว · ปรับจุดมุมก่อนเก็บหน้า"}else status.text="ไม่พบขอบชัดเจน · ลากจุดมุมด้วยตนเองได้"}}}
    private fun rectify():Bitmap{
        val b=image?:error("ยังไม่มีภาพ");val a=editor.corners;val source=FloatArray(8){i->a[i]*if(i%2==0)b.width else b.height}
        fun distance(i:Int,j:Int)=hypot(source[i*2]-source[j*2],source[i*2+1]-source[j*2+1])
        val w=max(distance(0,1),distance(2,3)).toInt().coerceAtLeast(1);val h=max(distance(0,3),distance(1,2)).toInt().coerceAtLeast(1)
        val factor=min(1f,2400f/max(w,h));val width=(w*factor).toInt().coerceAtLeast(1);val height=(h*factor).toInt().coerceAtLeast(1)
        val target=floatArrayOf(0f,0f,width.toFloat(),0f,width.toFloat(),height.toFloat(),0f,height.toFloat());val matrix=Matrix();check(matrix.setPolyToPoly(source,0,target,0,4)){"มุมเอกสารซ้อนกัน"}
        val output=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);val canvas=Canvas(output);canvas.drawColor(Color.WHITE);val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if(bw)paint.colorFilter=ColorMatrixColorFilter(ColorMatrix().apply{setSaturation(0f)})
        canvas.drawBitmap(b,matrix,paint);return output
    }
    private fun savePage():Boolean{if(busy||image==null)return false
        return try{val b=rectify();val file=File(project,"page-${pages.size+1}.jpg");PhotoEffects.write(b,file);b.recycle();pages+=file;MediaLedger.rememberFile(this,file,"image/jpeg","เอกสารแก้ perspective หน้า ${pages.size}");status.text="เก็บ ${pages.size} หน้าแล้ว · เพิ่มภาพหรือสร้าง PDF";true}catch(e:Exception){status.text=e.localizedMessage;false}
    }
    private fun ocr(){if(busy)return;val bitmap=runCatching{rectify()}.getOrElse{status.text=it.localizedMessage;return};busy=true;status.text="กำลังอ่านข้อความ Latin / อังกฤษ…"
        val recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(InputImage.fromBitmap(bitmap,0)).addOnSuccessListener{result->
            val file=File(project,"ocr-${System.currentTimeMillis()}.txt");file.writeText(result.text);MediaLedger.rememberFile(this,file,"text/plain","OCR Latin · ตรวจทานข้อความก่อนใช้")
            val text=TextView(this).apply{setPadding(dp(18),dp(16),dp(18),dp(16));this.text=result.text.ifBlank{"ไม่พบข้อความ Latin · โมเดลนี้ยังไม่รองรับ OCR ภาษาไทย"};setTextIsSelectable(true)}
            AlertDialog.Builder(this).setTitle("ข้อความ OCR").setView(ScrollView(this).apply{addView(text)}).setPositiveButton("ส่งออก"){_,_->MediaLedger.share(this,MediaLedger.privateUri(this,file),"text/plain")}.setNegativeButton("ปิด",null).show();status.text="อ่านข้อความแล้ว"
        }.addOnFailureListener{status.text="OCR ไม่สำเร็จ · ${it.localizedMessage}"}.addOnCompleteListener{bitmap.recycle();recognizer.close();busy=false}
    }
    private fun pdf(){if(busy)return;if(pages.isEmpty()&&!savePage())return;busy=true;status.text="กำลังสร้าง PDF ${pages.size} หน้า…";luma.fileIo.execute{runCatching{
        val document=PdfDocument();try{pages.forEachIndexed{i,file->val b=PhotoEffects.read(file,4_000_000);val page=document.startPage(PdfDocument.PageInfo.Builder(595,842,i+1).create());page.canvas.drawColor(Color.WHITE);val scale=min(555f/b.width,802f/b.height);val w=b.width*scale;val h=b.height*scale;page.canvas.drawBitmap(b,null,RectF((595-w)/2,(842-h)/2,(595+w)/2,(842+h)/2),Paint(Paint.FILTER_BITMAP_FLAG));document.finishPage(page);b.recycle()};val file=File(project,"Luma-Document-${System.currentTimeMillis()}.pdf");file.outputStream().use{document.writeTo(it)};MediaLedger.rememberFile(this,file,"application/pdf","${pages.size} หน้า");file}finally{document.close()}
    }.onSuccess{file->runOnUiThread{busy=false;status.text="สร้าง PDF แล้ว · ${pages.size} หน้า";MediaLedger.view(this,MediaLedger.privateUri(this,file),"application/pdf")}}.onFailure{runOnUiThread{busy=false;status.text="สร้าง PDF ไม่สำเร็จ · ${it.localizedMessage}"}}}}
    override fun onDestroy(){editor.bitmap=null;image?.recycle();super.onDestroy()}
}

private class PageCorners(c:android.content.Context):View(c){
    var bitmap:Bitmap?=null;set(v){field=v;corners=floatArrayOf(.08f,.08f,.92f,.08f,.92f,.92f,.08f,.92f);invalidate()}
    var corners=floatArrayOf(.08f,.08f,.92f,.08f,.92f,.92f,.08f,.92f)
    private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val rect=RectF();private var active=-1
    override fun onDraw(canvas:Canvas){val b=bitmap?:return;val scale=min(width.toFloat()/b.width,height.toFloat()/b.height);val w=b.width*scale;val h=b.height*scale;rect.set((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2);p.style=Paint.Style.FILL;canvas.drawBitmap(b,null,rect,p)
        val path=Path();for(i in 0..3){val x=rect.left+corners[i*2]*w;val y=rect.top+corners[i*2+1]*h;if(i==0)path.moveTo(x,y)else path.lineTo(x,y)};path.close();p.color=LIME;p.style=Paint.Style.STROKE;p.strokeWidth=2*resources.displayMetrics.density;canvas.drawPath(path,p);p.style=Paint.Style.FILL
        for(i in 0..3)canvas.drawCircle(rect.left+corners[i*2]*w,rect.top+corners[i*2+1]*h,9*resources.displayMetrics.density,p)
    }
    override fun onTouchEvent(e:MotionEvent):Boolean{if(rect.isEmpty)return false
        when(e.actionMasked){MotionEvent.ACTION_DOWN->{active=(0..3).minByOrNull{hypot(rect.left+corners[it*2]*rect.width()-e.x,rect.top+corners[it*2+1]*rect.height()-e.y)}?:-1}
            MotionEvent.ACTION_MOVE->{if(active>=0){val candidate=corners.clone();candidate[active*2]=((e.x-rect.left)/rect.width()).coerceIn(0f,1f);candidate[active*2+1]=((e.y-rect.top)/rect.height()).coerceIn(0f,1f);var convex=true;for(i in 0..3){val j=(i+1)%4;val k=(i+2)%4;val cross=(candidate[j*2]-candidate[i*2])*(candidate[k*2+1]-candidate[j*2+1])-(candidate[j*2+1]-candidate[i*2+1])*(candidate[k*2]-candidate[j*2]);if(cross<.005f)convex=false};if(convex){corners=candidate;invalidate()}}}
            MotionEvent.ACTION_UP->{active=-1;performClick()}}
        return true
    }
    override fun performClick():Boolean{super.performClick();return true}
}
