package th.ogay.luma

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.camera.view.PreviewView

class CompositionOverlay(context: Context) : View(context), SensorEventListener {
    var movie=false
    private var roll=0f
    private var focusX=-1f;private var focusY=-1f;private var locked=false
    private var histogram=IntArray(64);private var samples:IntArray?=null
    private var source:PreviewView?=null
    private val prefs=context.getSharedPreferences("camera",0)
    private val sensorManager=context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    fun focus(x:Float,y:Float,lock:Boolean=false){focusX=x;focusY=y;locked=lock;invalidate();if(!lock)postDelayed({focusX=-1f;invalidate()},1200)}
    fun monitor(preview:PreviewView){source=preview}
    private val poll=object:Runnable{override fun run(){
        if(isShown&&source!=null&&(prefs.getBoolean("histogram",false)||prefs.getBoolean("peaking",false)||prefs.getBoolean("zebra",false))){
            val b=source?.bitmap
            if(b!=null){val s=android.graphics.Bitmap.createScaledBitmap(b,160,120,true);val a=IntArray(160*120);s.getPixels(a,0,160,0,0,160,120);if(s!==b)s.recycle();b.recycle();histogram=IntArray(64);a.forEach{histogram[FrameFusionEngine.luma(it)/4]++};samples=a;invalidate()}
        };postDelayed(this,500)
    }}
    override fun onAttachedToWindow(){super.onAttachedToWindow();sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let{sensorManager.registerListener(this,it,SensorManager.SENSOR_DELAY_UI)};post(poll)}
    override fun onDetachedFromWindow(){removeCallbacks(poll);sensorManager.unregisterListener(this);super.onDetachedFromWindow()}
    override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int){}
    override fun onSensorChanged(e:SensorEvent){if(prefs.getBoolean("level",false)){roll=Math.toDegrees(kotlin.math.atan2(-e.values[0].toDouble(),e.values[1].toDouble())).toFloat();if(resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE)roll+=90;invalidate()}}
    var grid = false
        set(value) {
            field = value
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66ffffff
        strokeWidth = context.resources.displayMetrics.density
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (grid) for (i in 1..2) {
            canvas.drawLine(width * i / 3f, 0f, width * i / 3f, height.toFloat(), paint)
            canvas.drawLine(0f, height * i / 3f, width.toFloat(), height * i / 3f, paint)
        }
        val density=resources.displayMetrics.density
        if(movie){paint.style=Paint.Style.FILL;paint.color=0xaa000000.toInt();val h=(height-width/2.39f)/2;canvas.drawRect(0f,0f,width.toFloat(),h,paint);canvas.drawRect(0f,height-h,width.toFloat(),height.toFloat(),paint)}
        if(prefs.getBoolean("level",false)){
            paint.style=Paint.Style.STROKE;paint.strokeWidth=2*density;paint.color=if(kotlin.math.abs(roll%180)<2)LIME else WHITE
            canvas.save();canvas.rotate(-roll,width/2f,height/2f);canvas.drawLine(width/2f-40*density,height/2f,width/2f+40*density,height/2f,paint);canvas.restore()
        }
        if(focusX>=0){paint.style=Paint.Style.STROKE;paint.color=LIME;paint.strokeWidth=1.5f*density;canvas.drawRoundRect(focusX-28*density,focusY-28*density,focusX+28*density,focusY+28*density,5*density,5*density,paint);if(locked){paint.textSize=12*density;paint.style=Paint.Style.FILL;canvas.drawText("AE/AF LOCK",focusX-28*density,focusY-34*density,paint)}}
        if(prefs.getBoolean("histogram",false)){
            val x=12*density;val y=12*density;val w=112*density;val h=52*density
            paint.style=Paint.Style.FILL;paint.color=0x99000000.toInt();canvas.drawRoundRect(x,y,x+w,y+h,6*density,6*density,paint)
            paint.color=0xccffffff.toInt();val max=histogram.maxOrNull()?.coerceAtLeast(1)?:1
            histogram.forEachIndexed{i,v->canvas.drawRect(x+i*w/64,y+h-v.toFloat()/max*h,x+(i+1)*w/64,y+h,paint)}
        }
        val a=samples
        if(a!=null&&(prefs.getBoolean("peaking",false)||prefs.getBoolean("zebra",false))){
            paint.style=Paint.Style.FILL
            for(y in 1..118 step 2)for(x in 1..158 step 2){val i=y*160+x;val l=FrameFusionEngine.luma(a[i]);val high=prefs.getBoolean("zebra",false)&&l>240&&(x+y)%8<4;val edge=prefs.getBoolean("peaking",false)&&kotlin.math.abs(FrameFusionEngine.luma(a[i+1])-FrameFusionEngine.luma(a[i-1]))+kotlin.math.abs(FrameFusionEngine.luma(a[i+160])-FrameFusionEngine.luma(a[i-160]))>100
                if(high||edge){paint.color=if(edge)0x99ff5757.toInt() else 0x88ffffff.toInt();canvas.drawRect(x*width/160f,y*height/120f,(x+2)*width/160f,(y+2)*height/120f,paint)}
            }
        }
        paint.style=Paint.Style.STROKE;paint.color=0x66ffffff;paint.strokeWidth=density
    }
}
