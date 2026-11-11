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
    /** [area] is where the pixels sat on screen when they were decoded for the view box [frame]. */
    private data class Tile(val image:Bitmap,val area:RectF,val frame:RectF)
    private var tile:Tile?=null
    /** The whole picture at fit-to-screen: shown under the sharp tile so zooming and panning never show a gap. */
    private var base:Tile?=null
    private var dec:BitmapRegionDecoder?=null;private var decUri:Uri?=null;private var decRot=0
    private var dragX0=0f;private var dragY0=0f;private var paging=false;private var tracker:android.view.VelocityTracker?=null
    private val slop=android.view.ViewConfiguration.get(context).scaledTouchSlop
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
            if(!paging&&translationX==0f&&kotlin.math.abs(vx)>kotlin.math.abs(vy)*1.5f&&kotlin.math.abs(vx)>900*resources.displayMetrics.density/2.75f){onSwipe?.invoke(if(vx<0)1 else -1);return true}
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
    override fun onTouchEvent(e:MotionEvent):Boolean {
        scale.onTouchEvent(e)
        // At fit-to-screen a sideways drag moves the picture with the finger; letting go past a fifth of the width (or a flick) turns the page.
        if(zoom<=1.05f&&onSwipe!=null) when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{dragX0=e.rawX;dragY0=e.rawY;paging=false;animate().cancel();tracker?.recycle();tracker=android.view.VelocityTracker.obtain()}
            MotionEvent.ACTION_POINTER_DOWN->if(paging){paging=false;animate().translationX(0f).alpha(1f).setDuration(160).start()}
            MotionEvent.ACTION_MOVE->if(e.pointerCount==1&&!scale.isInProgress){
                val dx=e.rawX-dragX0;val dy=e.rawY-dragY0
                if(!paging&&kotlin.math.abs(dx)>slop&&kotlin.math.abs(dx)>kotlin.math.abs(dy)*1.3f){paging=true;parent?.requestDisallowInterceptTouchEvent(true)}
                if(paging){val m=MotionEvent.obtain(e);m.setLocation(e.rawX,e.rawY);tracker?.addMovement(m);m.recycle();translationX=dx;alpha=1f-(kotlin.math.abs(dx)/width.coerceAtLeast(1)).coerceAtMost(0.45f);return true}
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->if(paging){
                paging=false;tracker?.computeCurrentVelocity(1000);val vx=tracker?.xVelocity?:0f;tracker?.recycle();tracker=null
                val dx=translationX;val far=kotlin.math.abs(dx)>width*0.2f||kotlin.math.abs(vx)>700*resources.displayMetrics.density
                if(e.actionMasked==MotionEvent.ACTION_UP&&far&&(vx==0f||(vx<0)==(dx<0)||kotlin.math.abs(dx)>width*0.35f))onSwipe?.invoke(if(dx<0)1 else -1)
                settle();return true
            }
        }
        gestures.onTouchEvent(e);if(e.action==MotionEvent.ACTION_UP) performClick();return true
    }
    /** Springs back to rest unless a page turn has already taken the view over. */
    fun settle(){post{if(!turning)animate().translationX(0f).alpha(1f).setInterpolator(th.ogay.luma.Glass.SPRING).setDuration(300).start()}}
    var turning=false
    override fun performClick():Boolean {super.performClick();return true}
    fun show(uri:Uri) {
        if(source==uri) return
        setCompare(null);otherShown=0f
        source=uri; tile?.takeIf{it!==base}?.image?.recycle();base?.image?.recycle();tile=null;base=null;zoom=1f;cx=0.5f;cy=0.5f;invalidate();change()
    }
    private fun change(){version++;invalidate();schedule()}
    private fun schedule() {
        val uri=source ?: return
        if(working || width==0 || height==0 || dead) return
        working=true
        val job=version;val w=width;val h=height;val z=zoom;val x=cx;val y=cy
        io.execute {
            var ratio=1f
            val next=try {
                val resolver=context.contentResolver
                // DNG cannot be region-decoded: decode it whole, sampled to the screen (Android reads DNG since 7.0).
                val type=resolver.getType(uri)?:""
                if(type.contains("dng")||uri.toString().endsWith(".dng",true)){
                    val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                    resolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,bounds)}
                    var sample=1;while(bounds.outWidth/(sample*2)>=w&&bounds.outHeight/(sample*2)>=h)sample*=2
                    resolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply{inSampleSize=sample})}?.let{b->ratio=b.width.toFloat()/b.height;box(w,h,ratio,z,x,y).let{f->Tile(b,f,RectF(f))}}
                } else {
                if(decUri!=uri){dec?.recycle();dec=null;decUri=uri
                    decRot=resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
                    @Suppress("DEPRECATION") run{dec=resolver.openInputStream(uri)?.use { BitmapRegionDecoder.newInstance(it,false) }}}
                val rotation=decRot
                dec?.let { decoder ->
                    run {
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
                        val decoded=decoder.decodeRegion(r, BitmapFactory.Options().apply { inSampleSize=sample }) ?: return@let null
                        val bitmap=if(rotation==0) decoded else Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,
                            Matrix().apply {setRotate(rotation.toFloat())},true).also { if(it!==decoded) decoded.recycle() }
                        val dst=RectF(r);transform.mapRect(dst)
                        Tile(bitmap,dst,frame)
                    }
                }
                }
            } catch(_:Exception){null} catch(_:OutOfMemoryError){null}
            post {
                working=false
                if(!dead && uri==source) {
                    // A tile decoded for an older position is still used: it is drawn mapped to where the picture is now.
                    if(next!=null){aspect=ratio;tile?.takeIf{it!==base}?.image?.recycle();tile=next;if(base==null&&z<=1.001f)base=next;invalidate()}
                    else if(job==version&&tile==null) context.notice("เปิดภาพไม่ได้ อาจถูกลบออกจากแกลเลอรี")
                } else next?.image?.recycle()
                if(job!=version) schedule()
            }
        }
    }
    // ---- Hold-to-compare: a second picture (the original) fitted once, cross-faded over the shown one, no reload flicker.
    private var other:Bitmap?=null;private var otherUri:Uri?=null;private var otherShown=0f
    private val otherPaint=Paint(Paint.FILTER_BITMAP_FLAG)
    private var otherAnim:android.animation.ValueAnimator?=null
    fun setCompare(uri:Uri?){
        if(uri==otherUri)return
        otherUri=uri;other?.recycle();other=null
        if(uri==null||dead)return
        val longSide=2*maxOf(width,height,1080)
        io.execute{
            val b=runCatching{
                val r=context.contentResolver
                val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};r.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,bounds)}
                var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/(sample*2)>=longSide)sample*=2
                val rot=r.openInputStream(uri)?.use{ExifInterface(it).rotationDegrees}?:0
                val d=r.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply{inSampleSize=sample})}
                if(d==null||rot==0)d else Bitmap.createBitmap(d,0,0,d.width,d.height,Matrix().apply{setRotate(rot.toFloat())},true).also{if(it!==d)d.recycle()}
            }.getOrNull()
            post{if(!dead&&otherUri==uri){other=b;invalidate()}else b?.recycle()}
        }
    }
    fun holdCompare(on:Boolean){
        otherAnim?.cancel()
        otherAnim=android.animation.ValueAnimator.ofFloat(otherShown,if(on)1f else 0f).apply{duration=140;addUpdateListener{otherShown=it.animatedValue as Float;invalidate()};start()}
    }
    private val fade=Paint();private val fadeBottom=Paint();private val placed=RectF()
    private fun place(t:Tile,cur:RectF):RectF{val k=cur.width()/t.frame.width().coerceAtLeast(1f)
        placed.set(cur.left+(t.area.left-t.frame.left)*k,cur.top+(t.area.top-t.frame.top)*k,cur.left+(t.area.right-t.frame.left)*k,cur.top+(t.area.bottom-t.frame.top)*k);return placed}
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){super.onSizeChanged(w,h,oldw,oldh)
        val e=30*resources.displayMetrics.density;val bg=th.ogay.luma.Glass.NAVY_DEEP;val clear=bg and 0x00ffffff
        fade.shader=android.graphics.LinearGradient(0f,0f,0f,e,bg,clear,android.graphics.Shader.TileMode.CLAMP)
        fadeBottom.shader=android.graphics.LinearGradient(0f,h-e,0f,h.toFloat(),clear,bg,android.graphics.Shader.TileMode.CLAMP)
        change()}
    /** Draws what is already decoded at the current zoom and position straight away; sharper pixels replace it when they arrive. */
    override fun onDraw(c:Canvas){super.onDraw(c)
        val cur=box(width,height,aspect,zoom,cx,cy)
        base?.let{if(it!==tile)c.drawBitmap(it.image,null,place(it,cur),paint)}
        tile?.let{c.drawBitmap(it.image,null,place(it,cur),paint)}
        other?.let{o->if(otherShown>0f){otherPaint.alpha=(255*otherShown).toInt();c.drawBitmap(o,null,cur,otherPaint)}}
        val e=30*resources.displayMetrics.density
        c.drawRect(0f,0f,width.toFloat(),e,fade);c.drawRect(0f,height-e,width.toFloat(),height.toFloat(),fadeBottom)}
    override fun onDetachedFromWindow(){dead=true;otherAnim?.cancel();other?.recycle();other=null;runCatching{io.execute{dec?.recycle();dec=null}};io.shutdown();tile?.takeIf{it!==base}?.image?.recycle();base?.image?.recycle();tile=null;base=null;super.onDetachedFromWindow()}
}
