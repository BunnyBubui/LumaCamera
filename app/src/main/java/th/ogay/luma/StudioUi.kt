package th.ogay.luma

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import kotlin.math.*

enum class PhotoMode(val title: String) {
    NIGHT("กลางคืน"), PORTRAIT("บุคคล"), PHOTO("ภาพถ่าย"), VIDEO("วิดีโอ"), PRO("โปร"), MORE("เพิ่มเติม"),
    HDR("HDR"), ULTRA_HDR("Ultra HDR"), HIRES("ความละเอียดสูง"), DOCUMENT("เอกสาร"), PANORAMA("พาโนรามา"), FLASH_NIGHT("แฟลชเร็ว"), FILM("ฟิล์ม")
}

fun Context.studioSheet(title: String, build: (LinearLayout, Dialog) -> Unit): Dialog {
    val dialog = Dialog(this)
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(24))
        background = glass(28, Glass.FILL_POP)
    }
    val handle = FrameLayout(this).apply { contentDescription = "ปัดลงเพื่อปิด"; addView(View(this@studioSheet).apply { background = glass(3, 0x59ffffff) }, FrameLayout.LayoutParams(dp(40), dp(5), Gravity.CENTER)) }
    var down = 0f
    handle.setOnTouchListener { _, e ->
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> down = e.rawY
            MotionEvent.ACTION_MOVE -> container.translationY = max(0f, e.rawY-down)
            MotionEvent.ACTION_UP -> { if(e.rawY-down>dp(60)) dialog.dismiss() else container.animate().translationY(0f).setInterpolator(Glass.SPRING).setDuration(320).start(); handle.performClick() }
        }; true
    }
    // TalkBack cannot drag; a double tap on the handle closes the sheet instead.
    androidx.core.view.ViewCompat.replaceAccessibilityAction(handle, androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK, "ปิดแผง") { _, _ -> dialog.dismiss(); true }
    container.addView(handle, -1, dp(26))
    container.addView(label(title, 22f).apply { typeface=android.graphics.Typeface.DEFAULT_BOLD; minHeight=dp(54) }, -1, -2)
    build(container, dialog)
    val scroll = ScrollView(this).apply { isFillViewport = true; addView(container) }
    dialog.setContentView(scroll)
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    dialog.window?.setGravity(Gravity.BOTTOM)
    dialog.window?.setDimAmount(0.35f)
    dialog.show()
    if (!Glass.reduced(this)) { container.alpha = 0f; container.post { Liquid.show(container, container.width / 2f, container.height.toFloat(), true) } }
    dialog.window?.setLayout(-1, min(resources.displayMetrics.heightPixels*0.8f, container.measuredHeight.toFloat().takeIf{it>dp(160)} ?: (resources.displayMetrics.heightPixels*0.75f)).toInt())
    return dialog
}

fun Context.modeStrip(selected: PhotoMode, select: (PhotoMode) -> Unit): HorizontalScrollView {
    val row = LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(12),0,dp(12),0) }
    val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; addView(row) }
    val modes = listOf(PhotoMode.NIGHT,PhotoMode.PORTRAIT,PhotoMode.PHOTO,PhotoMode.VIDEO,PhotoMode.PRO,PhotoMode.MORE)
    var active: View?=null
    modes.forEach { mode ->
        val v=label(mode.title,15f,if(mode==selected) LIME else MUTED).apply {
            gravity=Gravity.CENTER; setPadding(dp(16),0,dp(16),0); minHeight=dp(52)
            typeface=if(mode==selected) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            selectionStyle(mode==selected)
            contentDescription="โหมด ${mode.title}"; isFocusable=true; setOnClickListener{select(mode)}; pressFeedback()
        }
        row.addView(v,-2,-2);if(mode==selected)active=v
    }
    scroll.post { active?.let{if(getSharedPreferences("camera",0).getBoolean("reduce_motion",false))scroll.scrollTo((it.left-(scroll.width-it.width)/2).coerceAtLeast(0),0) else scroll.smoothScrollTo((it.left-(scroll.width-it.width)/2).coerceAtLeast(0),0)} }
    return scroll
}

/** Logarithmic one-finger ruler with a fixed centre marker and favourite detents. */
class ZoomRuler(context: Context, private val zoom: (Float)->Unit): View(context) {
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private var lo=1f; private var hi=8f;private var current=1f;private var start=0f;private var base=0f;private var downY=0f
    private var detent=-1
    init{ contentDescription="ไม้บรรทัดซูม ลากซ้ายขวา";isFocusable=true }
    fun update(minimum:Float,maximum:Float,value:Float){lo=minimum;hi=maximum;current=value;invalidate()}
    override fun onDraw(c:Canvas){
        val density=resources.displayMetrics.density
        p.textAlign=Paint.Align.CENTER;p.textSize=android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,11f,resources.displayMetrics);p.strokeWidth=density
        val unit=width/3f
        for(i in -60..60){
            val log=i/12f; val value=exp(log.toDouble()).toFloat()
            if(value<lo||value>hi)continue
            val x=width/2f+(log-ln(current))*unit
            if(x<0||x>width)continue
            val major=i%6==0;p.color=0x88ffffff.toInt();c.drawLine(x,height*0.5f,x,height*(if(major)0.82f else 0.68f),p)
            if(major)c.drawText(zoomText(value),x,height*0.3f,p)
        }
        p.color=LIME;p.strokeWidth=2*density;c.drawLine(width/2f,height*0.43f,width/2f,height.toFloat(),p)
    }
    override fun onTouchEvent(e:MotionEvent):Boolean{
        if(!isEnabled)return false
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{parent.requestDisallowInterceptTouchEvent(true);start=e.x;downY=e.y;base=ln(current)}
            MotionEvent.ACTION_MOVE->{
                val fine=1f+abs(e.y-downY)/(80*resources.displayMetrics.density)
                val v=exp((base+(start-e.x)/(width/3f)/fine).toDouble()).toFloat().coerceIn(lo,hi)
                val index=listOf(0.5f,1f,2f,3.5f,5f,10f).indexOfFirst{abs(ln(it/v))<0.025f}
                if(index>=0&&index!=detent&&context.getSharedPreferences("camera",0).getBoolean("haptic",true)) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                detent=index;zoom(v)
            }
            MotionEvent.ACTION_UP->{performClick();parent.requestDisallowInterceptTouchEvent(false)}
        };return true
    }
    override fun getAccessibilityClassName():CharSequence="android.widget.SeekBar"
    override fun onInitializeAccessibilityNodeInfo(info:android.view.accessibility.AccessibilityNodeInfo){super.onInitializeAccessibilityNodeInfo(info);info.rangeInfo=android.view.accessibility.AccessibilityNodeInfo.RangeInfo.obtain(android.view.accessibility.AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,lo,hi,current);info.addAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);info.addAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)}
    override fun performAccessibilityAction(action:Int,args:android.os.Bundle?):Boolean{
        if(isEnabled&&(action==android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD||action==android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)){val sign=if(action==android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)1 else -1;zoom((current*exp(sign*ln(hi/lo)/20)).coerceIn(lo,hi));return true};return super.performAccessibilityAction(action,args)
    }
    override fun performClick():Boolean{super.performClick();return true}
}

