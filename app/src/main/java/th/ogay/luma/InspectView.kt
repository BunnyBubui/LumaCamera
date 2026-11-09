package th.ogay.luma

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.view.*
import androidx.exifinterface.media.ExifInterface
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.floor

/** Only decode the visible JPEG region. Both variants share one normalized pan/zoom position. */
@Suppress("DEPRECATION")
class InspectView(context: Context) : View(context) {
    private val io=Executors.newSingleThreadExecutor()
    private val paint=Paint(Paint.FILTER_BITMAP_FLAG)
    private var source: Uri?=null
    private var version=0
    private var working=false
    private var dead=false
    private var zoom=1f
    private var cx=0.5f
    private var cy=0.5f
    private var aspect=1f
    private data class Tile(val image:Bitmap,val area:RectF)
    private var tile:Tile?=null
    private val scale=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d:ScaleGestureDetector):Boolean {
            val old=zoom;val oldBox=box(width,height,aspect,old,cx,cy)
            zoom=(zoom*d.scaleFactor).coerceIn(1f,12f)
            val rx=(d.focusX-oldBox.left)/oldBox.width();val ry=(d.focusY-oldBox.top)/oldBox.height()
            cx=rx-(d.focusX-width/2f)/(oldBox.width()*zoom/old)
            cy=ry-(d.focusY-height/2f)/(oldBox.height()*zoom/old)
            clamp();change();return true
        }
    })
    private val gestures=GestureDetector(context,object:GestureDetector.SimpleOnGestureListener(){
        override fun onDown(e:MotionEvent)=true
        override fun onScroll(a:MotionEvent?,b:MotionEvent,dx:Float,dy:Float):Boolean {
            if(scale.isInProgress) return false
            val rect=box(width,height,aspect,zoom,cx,cy)
            cx+=dx/rect.width();cy+=dy/rect.height();clamp();change();return true
        }
        override fun onFling(a:MotionEvent?,b:MotionEvent,vx:Float,vy:Float):Boolean {
            if(zoom>1.05f||scale.isInProgress)return false
            if(kotlin.math.abs(vx)>kotlin.math.abs(vy)*1.5f&&kotlin.math.abs(vx)>900*resources.displayMetrics.density/2.75f){onSwipe?.invoke(if(vx<0)1 else -1);return true}
            return false
        }
        override fun onDoubleTap(e:MotionEvent):Boolean {
            val rect=box(width,height,aspect,zoom,cx,cy)
            cx=(e.x-rect.left)/rect.width();cy=(e.y-rect.top)/rect.height()
            zoom=if(zoom>1.1f) 1f else 3f;clamp();change();return true
        }
    })
    /** Fling sideways at fit-to-screen: +1 next, −1 previous. */
    var onSwipe:((Int)->Unit)?=null
    init { setBackgroundColor(0xff05070f.toInt()); contentDescription="ภาพถ่าย บีบเพื่อซูม ลากเพื่อเลื่อน หรือแตะสองครั้ง" }
    private fun box(w:Int,h:Int,ratio:Float,z:Float,x:Float,y:Float):RectF {
        val sw=minOf(w.toFloat(),h*ratio)*z
        val sh=sw/ratio
        fun start(screen:Int,size:Float,center:Float)=if(size<=screen) (screen-size)/2 else (screen/2f-center*size).coerceIn(screen-size,0f)
        val l=start(w,sw,x);val t=start(h,sh,y)
        return RectF(l,t,l+sw,t+sh)
    }
    private fun clamp() {
        val b=box(width,height,aspect,zoom,cx,cy)
        if(b.width()>0 && b.height()>0){cx=(width/2f-b.left)/b.width();cy=(height/2f-b.top)/b.height()}
    }
    override fun onTouchEvent(e:MotionEvent):Boolean {scale.onTouchEvent(e);gestures.onTouchEvent(e);if(e.action==MotionEvent.ACTION_UP) performClick();return true}
    override fun performClick():Boolean {super.performClick();return true}
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){super.onSizeChanged(w,h,oldw,oldh);change()}
    fun show(uri:Uri) {
        if(source==uri) return
        source=uri; tile?.image?.recycle();tile=null;invalidate();change()
    }
    private fun change(){version++;schedule()}
    private fun schedule() {
        val uri=source ?: return
        if(working || width==0 || height==0 || dead) return
        working=true
        val job=version;val w=width;val h=height;val z=zoom;val x=cx;val y=cy
        io.execute {
            var ratio=1f
            val next=try {
                val resolver=context.contentResolver
                val rotation=resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
                resolver.openInputStream(uri)?.use { input ->
                    val decoder=BitmapRegionDecoder.newInstance(input,false) ?: return@use null
                    try {
                        val raw=RectF(0f,0f,decoder.width.toFloat(),decoder.height.toFloat())
                        val transform=Matrix().apply {setRotate(rotation.toFloat())}
                        val upright=RectF(raw);transform.mapRect(upright);transform.postTranslate(-upright.left,-upright.top)
                        ratio=upright.width()/upright.height()
                        val frame=box(w,h,ratio,z,x,y)
                        val factor=frame.width()/upright.width()
                        transform.postScale(factor,factor);transform.postTranslate(frame.left,frame.top)
                        val inverse=Matrix();check(transform.invert(inverse))
                        val visible=RectF(0f,0f,w.toFloat(),h.toFloat());inverse.mapRect(visible)
                        val r=Rect(floor(visible.left).toInt().coerceIn(0,decoder.width-1),floor(visible.top).toInt().coerceIn(0,decoder.height-1),
                            ceil(visible.right).toInt().coerceIn(1,decoder.width),ceil(visible.bottom).toInt().coerceIn(1,decoder.height))
                        var sample=1
                        while(sample*2 <= 1/factor) sample*=2
                        val decoded=decoder.decodeRegion(r, BitmapFactory.Options().apply { inSampleSize=sample }) ?: return@use null
                        val bitmap=if(rotation==0) decoded else Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,
                            Matrix().apply {setRotate(rotation.toFloat())},true).also { if(it!==decoded) decoded.recycle() }
                        val dst=RectF(r);transform.mapRect(dst)
                        Tile(bitmap,dst)
                    } finally {decoder.recycle()}
                }
            } catch(_:Exception){null} catch(_:OutOfMemoryError){null}
            post {
                working=false
                if(!dead && job==version) {
                    aspect=ratio;tile?.image?.recycle();tile=next;invalidate()
                    if(next==null) context.notice("เปิดภาพไม่ได้ อาจถูกลบออกจากแกลเลอรี")
                } else next?.image?.recycle()
                if(job!=version) schedule()
            }
        }
    }
    override fun onDraw(c:Canvas){super.onDraw(c);tile?.let {c.drawBitmap(it.image,null,it.area,paint)}}
    override fun onDetachedFromWindow(){dead=true;io.shutdown();tile?.image?.recycle();tile=null;super.onDetachedFromWindow()}
}
