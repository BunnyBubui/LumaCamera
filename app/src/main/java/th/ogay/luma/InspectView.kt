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
    private val MAX=12f
    private var cx=0.5f
    private var cy=0.5f
    private var aspect=1f
    /** [area] is where the pixels sat on screen when they were decoded for the view box [frame]. */
    private data class Tile(val image:Bitmap,val area:RectF,val frame:RectF,val owned:Boolean=true){fun free(){if(owned&&!image.isRecycled)image.recycle()}}
    private var tile:Tile?=null
    /** The whole picture at fit-to-screen: shown under the sharp tile so zooming and panning never show a gap. */
    private var base:Tile?=null
    private var stale:Tile?=null
    private var dec:BitmapRegionDecoder?=null;private var decUri:Uri?=null;private var decRot=0
    private var dragX0=0f;private var dragY0=0f;private var paging=false;private var tracker:android.view.VelocityTracker?=null
    private val slop=android.view.ViewConfiguration.get(context).scaledTouchSlop
    private val scale=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d:ScaleGestureDetector):Boolean{motion?.cancel();return true}
        override fun onScaleEnd(d:ScaleGestureDetector){if(zoom<1f)glide(1f,0.5f,0.5f) else if(zoom>MAX)glide(MAX,cx,cy)}
        override fun onScale(d:ScaleGestureDetector):Boolean {
            val old=zoom;val oldBox=box(width,height,aspect,old,cx,cy)
            // Past either limit the picture still follows the fingers, but stiffly, and springs back when they lift.
            val f=d.scaleFactor;val soft=if((zoom<1f&&f<1f)||(zoom>MAX&&f>1f))1f+(f-1f)*0.3f else f
            zoom=(zoom*soft).coerceIn(0.6f,MAX*1.6f)
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
            if(scale.isInProgress)return false
            // Zoomed in: the picture keeps sliding after a flick and slows to a stop.
            if(zoom>1.05f){coast(vx,vy);return true}
            return false
        }
        override fun onDoubleTap(e:MotionEvent):Boolean {
            val rect=box(width,height,aspect,zoom,cx,cy)
            if(zoom>1.1f)glide(1f,0.5f,0.5f) else glide(3f,(e.x-rect.left)/rect.width(),(e.y-rect.top)/rect.height())
            return true
        }
    })
    /** Page turn at fit-to-screen: +1 next, −1 previous. Called once the picture has slid away. */
    var onSwipe:((Int)->Unit)?=null
    /** The neighbouring picture (fit-to-screen copy) for +1 / −1, drawn beside this one while a page is turned; null at either end. */
    var neighbour:((Int)->Bitmap?)?=null
    /** Whether there is a picture in that direction at all (it may still be loading). */
    var hasNeighbour:((Int)->Boolean)?=null
    private var pageX=0f;private var pageAnim:android.animation.ValueAnimator?=null
    private val gap get()=24*resources.displayMetrics.density
    private fun pageTo(x:Float,ms:Long,end:(()->Unit)?=null){
        pageAnim?.cancel()
        pageAnim=android.animation.ValueAnimator.ofFloat(pageX,x).apply{duration=ms;interpolator=android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener{pageX=it.animatedValue as Float;invalidate()}
            addListener(object:android.animation.AnimatorListenerAdapter(){var cancelled=false
                override fun onAnimationCancel(a:android.animation.Animator){cancelled=true}
                override fun onAnimationEnd(a:android.animation.Animator){if(!cancelled)end?.invoke()}})
            start()}
    }
    /** Slides this picture out and the neighbour in, then reports the turn. */
    private fun turn(dir:Int){pageTo(-dir*(width+gap),((1f-kotlin.math.abs(pageX)/(width+gap)).coerceIn(0.25f,1f)*240).toLong()){onSwipe?.invoke(dir)}}
    /** The neighbour has slid in: it becomes this view's picture without a blank frame; sharp pixels follow. */
    fun show(uri:Uri,placeholder:Bitmap?){
        if(source==uri){pageX=0f;invalidate();return}
        show(uri);stale?.free();stale=null
        if(placeholder!=null&&!placeholder.isRecycled&&width>0&&height>0){aspect=placeholder.width.toFloat()/placeholder.height;val f=box(width,height,aspect,1f,0.5f,0.5f);base=Tile(placeholder,RectF(f),RectF(f),false);tile=base;glowFrom(placeholder)}
        pageAnim?.cancel();pageX=0f;invalidate()
    }
    /** No picture that way: back to rest. */
    fun stay(){pageTo(0f,220)}
    private var motion:android.animation.ValueAnimator?=null
    /** Zoom and centre move together along a soft curve instead of jumping. */
    private fun glide(z:Float,x:Float,y:Float){
        motion?.cancel();val z0=zoom;val x0=cx;val y0=cy
        motion=android.animation.ValueAnimator.ofFloat(0f,1f).apply{duration=300;interpolator=th.ogay.luma.Glass.EASE
            addUpdateListener{val t=it.animatedValue as Float
                // Zoom runs on a log scale so each moment of the move looks like the same step.
                zoom=kotlin.math.exp(kotlin.math.ln(z0)+(kotlin.math.ln(z)-kotlin.math.ln(z0))*t);cx=x0+(x-x0)*t;cy=y0+(y-y0)*t;clamp();change()}
            start()}
    }
    private fun coast(vx:Float,vy:Float){
        motion?.cancel();var last=0f
        // Exponential decay: distance left = v·τ, like a scroll that loses speed evenly.
        val tau=0.32f
        motion=android.animation.ValueAnimator.ofFloat(0f,1f).apply{duration=900;interpolator=android.view.animation.LinearInterpolator()
            addUpdateListener{val t=(it.animatedValue as Float)*0.9f;val done=1f-kotlin.math.exp(-t/tau);val step=done-last;last=done
                val rect=box(width,height,aspect,zoom,cx,cy);cx-=vx*tau*step/rect.width();cy-=vy*tau*step/rect.height();clamp();change()}
            start()}
    }
    init { contentDescription="ภาพถ่าย บีบเพื่อซูม ลากเพื่อเลื่อน หรือแตะสองครั้ง" }
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
        if(e.actionMasked==MotionEvent.ACTION_DOWN)motion?.cancel()
        if(zoom<=1.05f&&onDismiss!=null&&!paging&&dismissTouch(e))return true
        if(zoom<=1.05f&&onSwipe!=null) when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{dragX0=e.rawX-pageX;dragY0=e.rawY;paging=pageAnim?.isRunning==true;pageAnim?.cancel();tracker?.recycle();tracker=android.view.VelocityTracker.obtain()}
            MotionEvent.ACTION_POINTER_DOWN->if(paging){paging=false;stay()}
            MotionEvent.ACTION_MOVE->if(e.pointerCount==1&&!scale.isInProgress){
                val dx=e.rawX-dragX0;val dy=e.rawY-dragY0
                if(!paging&&kotlin.math.abs(dx)>slop&&kotlin.math.abs(dx)>kotlin.math.abs(dy)*1.3f){paging=true;dragX0=e.rawX;parent?.requestDisallowInterceptTouchEvent(true)}
                if(paging){val m=MotionEvent.obtain(e);m.setLocation(e.rawX,e.rawY);tracker?.addMovement(m);m.recycle()
                    val d=e.rawX-dragX0;val dir=if(d<0)1 else -1
                    // At the first or last picture the drag resists, so the end can be felt.
                    pageX=if(hasNeighbour?.invoke(dir)==false)d*0.3f else d;invalidate();return true}
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->if(paging){
                paging=false;tracker?.computeCurrentVelocity(1000);val vx=tracker?.xVelocity?:0f;tracker?.recycle();tracker=null
                val dx=pageX;val dir=if(dx<0)1 else -1
                val far=kotlin.math.abs(dx)>width*0.22f||(kotlin.math.abs(vx)>500*resources.displayMetrics.density&&(vx<0)==(dx<0))
                if(e.actionMasked==MotionEvent.ACTION_UP&&far&&hasNeighbour?.invoke(dir)!=false)turn(dir) else stay()
                return true
            }
        }
        gestures.onTouchEvent(e);if(e.action==MotionEvent.ACTION_UP) performClick();return true
    }
    override fun performClick():Boolean {super.performClick();return true}
    fun show(uri:Uri) {
        if(source==uri) return
        motion?.cancel()
        setCompare(null);otherShown=0f
        // The picture on screen stays as an underlay until the new one has pixels, so changing pictures never shows a blank frame.
        source=uri; tile?.takeIf{it!==base}?.free();stale?.takeIf{it!==base}?.free();stale=base;tile=null;base=null;zoom=1f;cx=0.5f;cy=0.5f;invalidate();change()
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
                    if(next!=null){aspect=ratio;tile?.takeIf{it!==base}?.free();tile=next;if(base==null&&z<=1.001f){base=next;glowFrom(next.image)};stale?.free();stale=null;invalidate()}
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
    /** Heights of the shades under the floating bars (set by the gallery from its bar sizes). */
    var shadeTop=110*context.resources.displayMetrics.density
    var shadeBottom=170*context.resources.displayMetrics.density
    private val bgPaint=Paint().apply{color=0xff05070f.toInt()}
    // ---- Reflection: a tiny copy of the picture, stretched (which blurs it) and mirrored past each edge.
    private var glow:Bitmap?=null
    private val glowPaint=Paint(Paint.FILTER_BITMAP_FLAG).apply{colorFilter=ColorMatrixColorFilter(ColorMatrix().apply{setSaturation(1.15f);postConcat(ColorMatrix().apply{setScale(0.5f,0.5f,0.5f,1f)})})}
    private fun glowFrom(b:Bitmap){
        if(b.isRecycled||b.width<=0)return
        val w=24;val h=(24f*b.height/b.width).toInt().coerceIn(4,96)
        val small=runCatching{Bitmap.createScaledBitmap(b,w,h,true)}.getOrNull()?:return
        // Scaled down twice more and back up: a cheap blur that keeps only the broad colours.
        val tiny=runCatching{Bitmap.createScaledBitmap(small,(w/3).coerceAtLeast(2),(h/3).coerceAtLeast(2),true)}.getOrNull()
        val soft=tiny?.let{runCatching{Bitmap.createScaledBitmap(it,w,h,true)}.getOrNull()}?:small
        if(soft!==small)small.recycle();if(tiny!=null&&tiny!==soft)tiny.recycle()
        glow?.recycle();glow=soft
    }
    private fun reflect(c:Canvas,r:RectF){
        val g=glow?:return;if(g.isRecycled||r.width()<=0f)return
        glowPaint.alpha=(255*(1f-dismissShown)).toInt()
        val reach=maxOf(height.toFloat(),width.toFloat())
        if(r.top>0.5f){c.save();c.clipRect(0f,0f,width.toFloat(),r.top);c.scale(1f,-1f,0f,r.top);c.drawBitmap(g,null,RectF(r.left-reach,r.top,r.right+reach,r.bottom),glowPaint);c.restore()}
        if(r.bottom<height-0.5f){c.save();c.clipRect(0f,r.bottom,width.toFloat(),height.toFloat());c.scale(1f,-1f,0f,r.bottom);c.drawBitmap(g,null,RectF(r.left-reach,r.top,r.right+reach,r.bottom),glowPaint);c.restore()}
        if(r.left>0.5f){c.save();c.clipRect(0f,r.top,r.left,r.bottom);c.scale(-1f,1f,r.left,0f);c.drawBitmap(g,null,r,glowPaint);c.restore()}
        if(r.right<width-0.5f){c.save();c.clipRect(r.right,r.top,width.toFloat(),r.bottom);c.scale(-1f,1f,r.right,0f);c.drawBitmap(g,null,r,glowPaint);c.restore()}
    }
    // ---- Swipe down at fit-to-screen to close the photo, following the finger and springing back if let go early.
    /** Called once the picture has been flicked away (the gallery goes back to the grid). */
    var onDismiss:(()->Unit)?=null
    /** 0..1 while dragging down, for fading the bars. */
    var onDismissDrag:((Float)->Unit)?=null
    private var dismissY=0f;private var dismissing=false;private var dY0=0f;private var dX0=0f;private var dTracker:android.view.VelocityTracker?=null
    private var dismissAnim:android.animation.ValueAnimator?=null
    private val dismissShown get()=(dismissY/(height.coerceAtLeast(1)*0.5f)).coerceIn(0f,1f)
    private fun setDismiss(y:Float){dismissY=y;onDismissDrag?.invoke(dismissShown);invalidate()}
    private fun dismissTouch(e:MotionEvent):Boolean{
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{dismissing=false;dY0=e.rawY;dX0=e.rawX;dTracker?.recycle();dTracker=android.view.VelocityTracker.obtain();dismissAnim?.cancel()}
            MotionEvent.ACTION_POINTER_DOWN->if(dismissing){dismissing=false;settleDismiss(false)}
            MotionEvent.ACTION_MOVE->{
                if(e.pointerCount>1||scale.isInProgress)return dismissing
                val dy=e.rawY-dY0;val dx=e.rawX-dX0
                if(!dismissing&&dy>slop*1.5f&&dy>kotlin.math.abs(dx)*1.4f){dismissing=true;dY0=e.rawY;parent?.requestDisallowInterceptTouchEvent(true)}
                if(dismissing){val m=MotionEvent.obtain(e);m.setLocation(e.rawX,e.rawY);dTracker?.addMovement(m);m.recycle()
                    val d=e.rawY-dY0;setDismiss(if(d>0)d else d*0.25f);return true}
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->if(dismissing){
                dismissing=false;dTracker?.computeCurrentVelocity(1000);val vy=dTracker?.yVelocity?:0f;dTracker?.recycle();dTracker=null
                settleDismiss(e.actionMasked==MotionEvent.ACTION_UP&&(dismissY>height*0.16f||vy>900*resources.displayMetrics.density/3f*2f)&&vy>-200);return true}
        }
        return dismissing
    }
    private fun settleDismiss(go:Boolean){
        dismissAnim?.cancel()
        if(go){
            th.ogay.luma.Glass.tick(this)
            dismissAnim=android.animation.ValueAnimator.ofFloat(dismissY,height*0.55f).apply{duration=220;interpolator=th.ogay.luma.Glass.EASE
                addUpdateListener{setDismiss(it.animatedValue as Float)}
                addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(a:android.animation.Animator){onDismiss?.invoke()}})
                start()}
        } else dismissAnim=android.animation.ValueAnimator.ofFloat(dismissY,0f).apply{duration=420;interpolator=th.ogay.luma.Glass.SPRING
            addUpdateListener{setDismiss(it.animatedValue as Float)};start()}
    }
    private fun place(t:Tile,cur:RectF):RectF{val k=cur.width()/t.frame.width().coerceAtLeast(1f)
        placed.set(cur.left+(t.area.left-t.frame.left)*k,cur.top+(t.area.top-t.frame.top)*k,cur.left+(t.area.right-t.frame.left)*k,cur.top+(t.area.bottom-t.frame.top)*k);return placed}
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){super.onSizeChanged(w,h,oldw,oldh)
        fade.shader=android.graphics.LinearGradient(0f,0f,0f,shadeTop,0x8c000000.toInt(),0,android.graphics.Shader.TileMode.CLAMP)
        fadeBottom.shader=android.graphics.LinearGradient(0f,h-shadeBottom,0f,h.toFloat(),0,0xa6000000.toInt(),android.graphics.Shader.TileMode.CLAMP)
        change()}
    /** Draws what is already decoded at the current zoom and position straight away; sharper pixels replace it when they arrive. */
    override fun onDraw(c:Canvas){
        bgPaint.alpha=(255*(1f-0.75f*dismissShown)).toInt();c.drawRect(0f,0f,width.toFloat(),height.toFloat(),bgPaint)
        super.onDraw(c)
        val cur=box(width,height,aspect,zoom,cx,cy)
        if(dismissY!=0f){val k=1f-0.35f*dismissShown;c.save();c.translate(0f,dismissY*0.85f);c.scale(k,k,width/2f,cur.centerY())}
        // Around the picture, a soft blurred mirror of its own edges, so it never ends in a hard cut.
        if(pageX==0f&&zoom<=1.05f)reflect(c,cur)
        if(pageX!=0f){
            // Page turn: this picture and its neighbour move together, like two prints side by side.
            val dir=if(pageX<0)1 else -1
            neighbour?.invoke(dir)?.takeIf{!it.isRecycled}?.let{b->val f=box(width,height,b.width.toFloat()/b.height,1f,0.5f,0.5f);f.offset(pageX+dir*(width+gap),0f);c.drawBitmap(b,null,f,paint)}
            c.save();c.translate(pageX,0f)
        }
        if(tile==null&&base==null)stale?.let{if(!it.image.isRecycled)c.drawBitmap(it.image,null,box(width,height,it.image.width.toFloat()/it.image.height,1f,0.5f,0.5f),paint)}
        base?.let{if(it!==tile&&!it.image.isRecycled)c.drawBitmap(it.image,null,place(it,cur),paint)}
        tile?.let{if(!it.image.isRecycled)c.drawBitmap(it.image,null,place(it,cur),paint)}
        other?.let{o->if(otherShown>0f){otherPaint.alpha=(255*otherShown).toInt();c.drawBitmap(o,null,cur,otherPaint)}}
        if(pageX!=0f)c.restore()
        if(dismissY!=0f)c.restore()
        // Soft shade under the floating top bar and bottom actions, so they read on any picture.
        if(dismissShown<1f){fade.alpha=(255*(1f-dismissShown)).toInt();fadeBottom.alpha=fade.alpha
            c.drawRect(0f,0f,width.toFloat(),shadeTop,fade);c.drawRect(0f,height-shadeBottom,width.toFloat(),height.toFloat(),fadeBottom)}}
    override fun onDetachedFromWindow(){dead=true;glow?.recycle();glow=null;dismissAnim?.cancel();motion?.cancel();pageAnim?.cancel();otherAnim?.cancel();other?.recycle();other=null;runCatching{io.execute{dec?.recycle();dec=null}};io.shutdown();tile?.takeIf{it!==base}?.free();base?.free();stale?.free();stale=null;tile=null;base=null;super.onDetachedFromWindow()}
}
