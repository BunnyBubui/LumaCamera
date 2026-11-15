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
import th.ogay.luma.ocr.ThaiOcrEngine
import th.ogay.luma.ocr.ThaiOcrException
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
    private lateinit var pageCount:TextView;private var colorLabel:TextView?=null
    @Volatile private var ocrCancelled=false
    private val addPage=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)load(uri)}
    override fun onCreate(state:Bundle?){super.onCreate(state)
        // Scanner layout like a phone camera's document mode: a slim top bar, the page as large as possible,
        // round tools with labels, then the two main actions.
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};installRoot(root)
        val top=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),dp(10),dp(14),dp(6))}
        top.addView(FrameLayout(this).apply{background=glass(22,0x40ffffff);contentDescription="ย้อนกลับ";isClickable=true;glassTouch();setOnClickListener{finish()}
            addView(IconView(this@DocumentActivity,Icons.CHEVRON_LEFT,2.1f),FrameLayout.LayoutParams(dp(20),dp(20),Gravity.CENTER))},dp(48),dp(48))
        top.addView(text("สแกนเอกสาร",20f,Glass.TEXT,true).apply{setPadding(dp(12),0,dp(6),0);maxLines=3;ellipsize=null},LinearLayout.LayoutParams(0,-2,1f))
        pageCount=text("0 หน้า",13f,Glass.ACCENT,true).apply{gravity=Gravity.CENTER;background=glass(18,Glass.ACCENT_SOFT);setPadding(dp(14),0,dp(14),0);minHeight=dp(40)}
        top.addView(pageCount,-2,-2);root.addView(top,-1,-2)
        status=text("ลากจุดมุมทั้ง 4 เพื่อแก้ perspective",13f,Glass.TEXT2).apply{gravity=Gravity.CENTER;maxLines=3;setPadding(dp(16),dp(4),dp(16),dp(8))};root.addView(status,-1,-2)
        editor=PageCorners(this);root.addView(editor,LinearLayout.LayoutParams(-1,0,1f).apply{setMargins(dp(12),0,dp(12),0)})
        val tools=LinearLayout(this).apply{gravity=Gravity.CENTER;setPadding(dp(8),dp(12),dp(8),dp(4))}
        fun tool(icon:String,label:String,desc:String=label,act:()->Unit)=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;isClickable=true;contentDescription=desc;setOnClickListener{act()}
            addView(FrameLayout(this@DocumentActivity).apply{background=glass(26);addView(IconView(this@DocumentActivity,icon,2f),FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER))},dp(52),dp(52))
            addView(text(label,12f,Glass.TEXT2).apply{gravity=Gravity.CENTER;setPadding(dp(2),dp(6),dp(2),0);maxLines=3;ellipsize=null},-1,-2);glassTouch()
        }.also{tools.addView(it,LinearLayout.LayoutParams(0,-2,1f))}
        tool(Icons.FRAME,"ตรวจขอบ"){detect()}
        val colorTool=tool(Icons.SUN,"สี","สลับสีและขาวดำ"){bw=!bw;colorLabel?.text=if(bw)"ขาวดำ" else "สี";status.text=if(bw)"ส่งออกแบบขาวดำ" else "ส่งออกแบบสี"}
        colorLabel=colorTool.getChildAt(1) as TextView
        tool(Icons.ALBUM,"เพิ่มภาพ"){if(!busy)addPage.launch(arrayOf("image/*"))}
        tool(Icons.DOC,"อ่านข้อความ","อ่านข้อความไทยและอังกฤษบนเครื่อง"){ocr()}
        root.addView(tools,-1,-2)
        val actions=LinearLayout(this).apply{setPadding(dp(14),dp(8),dp(14),dp(14))}
        actions.addView(text("เก็บหน้านี้",15f,Glass.TEXT,true).apply{gravity=Gravity.CENTER;background=glass(24,0x33ffffff);isClickable=true;glassTouch();setOnClickListener{savePage()}},LinearLayout.LayoutParams(0,dp(52),1f).apply{rightMargin=dp(10)})
        actions.addView(text("สร้าง PDF",15f,Glass.ON_ACCENT,true).apply{gravity=Gravity.CENTER;background=glass(24,Glass.ACCENT);isClickable=true;glassTouch();setOnClickListener{pdf()}},LinearLayout.LayoutParams(0,dp(52),1f))
        root.addView(actions,-1,-2)
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
        return try{val b=rectify();val file=File(project,"page-${pages.size+1}.jpg");PhotoEffects.write(b,file);b.recycle();pages+=file;runOnUiThread{pageCount.text="${pages.size} หน้า"};MediaLedger.rememberFile(this,file,"image/jpeg","เอกสารแก้ perspective หน้า ${pages.size}");status.text="เก็บ ${pages.size} หน้าแล้ว · เพิ่มภาพหรือสร้าง PDF";true}catch(e:Exception){status.text=e.localizedMessage;false}
    }
    /** Thai + English OCR on the device (th.ogay.luma.ocr); the rectified page never leaves the phone. */
    private fun ocr(){if(busy)return;val bitmap=runCatching{rectify()}.getOrElse{status.text=it.localizedMessage;return};busy=true;ocrCancelled=false
        status.text="กำลังอ่านข้อความไทย/อังกฤษบนเครื่อง…"
        Thread{
            val result=runCatching{thaiOcr.recognize(this,bitmap,60_000){ocrCancelled||isDestroyed}}
            bitmap.recycle()
            runOnUiThread{
                busy=false
                if(isDestroyed)return@runOnUiThread
                result.onSuccess{text->
                    val file=File(project,"ocr-${System.currentTimeMillis()}.txt");file.writeText(text);MediaLedger.rememberFile(this,file,"text/plain","OCR ไทย/อังกฤษบนเครื่อง · ตรวจทานข้อความก่อนใช้")
                    val view=TextView(this).apply{setPadding(dp(18),dp(16),dp(18),dp(16));this.text=text.ifBlank{"ไม่พบข้อความในภาพ ลองถ่ายให้ใกล้ขึ้นหรือเพิ่มแสง"};setTextIsSelectable(true)}
                    val footer=label("อ่านบนเครื่อง ไม่ส่งภาพออกไป · ตรวจทานข้อความก่อนใช้",12f,MUTED).apply{setPadding(dp(18),0,dp(18),dp(12))}
                    val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;addView(view);addView(footer)}
                    AlertDialog.Builder(this).setTitle("ข้อความที่อ่านได้").setView(ScrollView(this).apply{addView(body)}).setPositiveButton("ส่งออก"){_,_->MediaLedger.share(this,MediaLedger.privateUri(this,file),"text/plain")}.setNegativeButton("ปิด",null).show()
                    status.text=if(text.isBlank())"ไม่พบข้อความ" else "อ่านข้อความแล้ว"
                }.onFailure{e->status.text=(e as? ThaiOcrException)?.message?:"อ่านข้อความไม่สำเร็จ · ${e.localizedMessage}"}
            }
        }.apply{name="luma-ocr"}.start()
    }
    private fun pdf(){if(busy)return;if(pages.isEmpty()&&!savePage())return;busy=true;status.text="กำลังสร้าง PDF ${pages.size} หน้า…";luma.fileIo.execute{runCatching{
        val document=PdfDocument();try{pages.forEachIndexed{i,file->val b=PhotoEffects.read(file,4_000_000);val page=document.startPage(PdfDocument.PageInfo.Builder(595,842,i+1).create());page.canvas.drawColor(Color.WHITE);val scale=min(555f/b.width,802f/b.height);val w=b.width*scale;val h=b.height*scale;page.canvas.drawBitmap(b,null,RectF((595-w)/2,(842-h)/2,(595+w)/2,(842+h)/2),Paint(Paint.FILTER_BITMAP_FLAG));document.finishPage(page);b.recycle()};val file=File(project,"Lumora-Document-${System.currentTimeMillis()}.pdf");file.outputStream().use{document.writeTo(it)};MediaLedger.rememberFile(this,file,"application/pdf","${pages.size} หน้า");file}finally{document.close()}
    }.onSuccess{file->runOnUiThread{busy=false;status.text="สร้าง PDF แล้ว · ${pages.size} หน้า";MediaLedger.view(this,MediaLedger.privateUri(this,file),"application/pdf")}}.onFailure{runOnUiThread{busy=false;status.text="สร้าง PDF ไม่สำเร็จ · ${it.localizedMessage}"}}}}
    override fun onDestroy(){ocrCancelled=true;editor.bitmap=null;image?.recycle();super.onDestroy()}
    private companion object { val thaiOcr = ThaiOcrEngine() }
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
