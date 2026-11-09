package th.ogay.luma

import android.content.Context
import android.hardware.camera2.CameraCharacteristics as CC
import android.hardware.camera2.CaptureRequest as CR
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera

@ExperimentalCamera2Interop
class ManualControls(private val context:Context, val camera:Camera, private val video:Boolean=false) {
    private val info=Camera2CameraInfo.from(camera.cameraInfo)
    private val prefs=context.getSharedPreferences("camera",0)
    private var iso:Int?=null;private var ns:Long?=null;private var wb=CR.CONTROL_AWB_MODE_AUTO;private var focus:Float?=null
    var locked=false;private set
    val canManual get()=info.getCameraCharacteristic(CC.REQUEST_AVAILABLE_CAPABILITIES)?.contains(CC.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)==true
    fun apply(){
        val b=CaptureRequestOptions.Builder()
        if(canManual&&iso!=null){
            val r=info.getCameraCharacteristic(CC.SENSOR_INFO_SENSITIVITY_RANGE)!!
            val e=info.getCameraCharacteristic(CC.SENSOR_INFO_EXPOSURE_TIME_RANGE)!!
            b.setCaptureRequestOption(CR.CONTROL_AE_MODE,CR.CONTROL_AE_MODE_OFF)
            b.setCaptureRequestOption(CR.SENSOR_SENSITIVITY,iso!!.coerceIn(r.lower,r.upper))
            b.setCaptureRequestOption(CR.SENSOR_EXPOSURE_TIME,(ns?:16_666_666L).coerceIn(e.lower,minOf(e.upper,if(video)33_333_333L else e.upper)))
        }
        b.setCaptureRequestOption(CR.CONTROL_AWB_MODE,wb)
        if(focus!=null){b.setCaptureRequestOption(CR.CONTROL_AF_MODE,CR.CONTROL_AF_MODE_OFF);b.setCaptureRequestOption(CR.LENS_FOCUS_DISTANCE,focus!!)}
        if(locked&&info.getCameraCharacteristic(CC.CONTROL_AE_LOCK_AVAILABLE)==true)b.setCaptureRequestOption(CR.CONTROL_AE_LOCK,true)
        if(locked&&info.getCameraCharacteristic(CC.CONTROL_AWB_LOCK_AVAILABLE)==true)b.setCaptureRequestOption(CR.CONTROL_AWB_LOCK,true)
        Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(b.build())
    }
    fun lock(value:Boolean){locked=value;apply()}
    fun reset(){iso=null;ns=null;focus=null;wb=CR.CONTROL_AWB_MODE_AUTO;locked=false;apply()}
    fun show(){context.studioSheet(if(video)"PRO VIDEO" else "PRO · ควบคุมกล้อง"){box,_->
        fun options(title:String,values:List<String>,selected:Int,choose:(Int)->Unit){
            box.addView(context.label(title,15f,LIME),-1,context.dp(36))
            val row=LinearLayout(context);val scroll=android.widget.HorizontalScrollView(context).apply{isHorizontalScrollBarEnabled=false;addView(row)}
            values.forEachIndexed{i,v->row.addView(context.pill(v){choose(i);apply()}.apply{setTextColor(if(i==selected)LIME else WHITE)},-2,context.dp(48))};box.addView(scroll)
        }
        if(canManual){
            val r=info.getCameraCharacteristic(CC.SENSOR_INFO_SENSITIVITY_RANGE)!!
            val values=listOf(50,100,200,400,800,1600,3200,6400).filter{it in r.lower..r.upper}
            options("ISO",listOf("AUTO")+values.map{it.toString()},0){i->iso=if(i==0)null else values[i-1]}
            val range=info.getCameraCharacteristic(CC.SENSOR_INFO_EXPOSURE_TIME_RANGE)!!
            val shutter=listOf("1/8000" to 125_000L,"1/1000" to 1_000_000L,"1/250" to 4_000_000L,"1/60" to 16_666_666L,"1/30" to 33_333_333L,"1/8" to 125_000_000L,"1/2" to 500_000_000L,"1s" to 1_000_000_000L).filter{it.second>=range.lower&&it.second<=minOf(range.upper,if(video)33_333_333L else range.upper)}
            options("ชัตเตอร์ · เลือกแล้วใช้ค่าแสงแบบ Manual",shutter.map{it.first},-1){i->ns=shutter[i].second;if(iso==null)iso=100.coerceIn(r.lower,r.upper)}
        }else box.addView(context.label("กล้องนี้ไม่เปิด Manual sensor ให้แอป",14f,MUTED),-1,context.dp(48))
        val modes=info.getCameraCharacteristic(CC.CONTROL_AWB_AVAILABLE_MODES)?:intArrayOf()
        val white=listOf("AUTO" to CR.CONTROL_AWB_MODE_AUTO,"แดด" to CR.CONTROL_AWB_MODE_DAYLIGHT,"เมฆ" to CR.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,"หลอดไฟ" to CR.CONTROL_AWB_MODE_INCANDESCENT,"ฟลูออเรสเซนต์" to CR.CONTROL_AWB_MODE_FLUORESCENT).filter{it.second in modes}
        options("White balance",white.map{it.first},white.indexOfFirst{it.second==wb}){wb=white[it].second}
        val distance=info.getCameraCharacteristic(CC.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?:0f
        if(distance>0){
            box.addView(context.label("โฟกัสมือ · ∞ → ใกล้",15f,LIME),-1,context.dp(38))
            box.addView(SeekBar(context).apply{max=1000;contentDescription="โฟกัสมือ";setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onStartTrackingTouch(b:SeekBar){};override fun onStopTrackingTouch(b:SeekBar){}
                override fun onProgressChanged(b:SeekBar,p:Int,user:Boolean){if(user){focus=distance*p/1000;apply()}}
            })},-1,context.dp(52))
        }
        val exposure=camera.cameraInfo.exposureState
        if(exposure.isExposureCompensationSupported){
            box.addView(context.label("EV · ใช้เมื่อ ISO อยู่ AUTO",15f,LIME),-1,context.dp(38))
            box.addView(SeekBar(context).apply{val r=exposure.exposureCompensationRange;max=r.upper-r.lower;progress=exposure.exposureCompensationIndex-r.lower;contentDescription="ชดเชยแสง EV";setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onStartTrackingTouch(b:SeekBar){};override fun onStopTrackingTouch(b:SeekBar){}
                override fun onProgressChanged(b:SeekBar,p:Int,user:Boolean){if(user&&iso==null)camera.cameraControl.setExposureCompensationIndex(p+r.lower)}
            })},-1,context.dp(52))
        }
        fun toggle(title:String,key:String){val sw=android.widget.Switch(context).apply{text=title;setTextColor(WHITE);isChecked=prefs.getBoolean(key,false);setOnCheckedChangeListener{_,v->prefs.edit().putBoolean(key,v).apply()}};box.addView(sw,-1,context.dp(48))}
        toggle("Histogram จากภาพพรีวิว","histogram");toggle("Focus peaking จากภาพพรีวิว","peaking");toggle("Zebra ส่วนสว่างบนพรีวิว","zebra")
        box.addView(context.pill("คืนค่า AUTO"){reset();context.notice("ใช้ค่าแสงและโฟกัสอัตโนมัติแล้ว")},-1,context.dp(52))
    }}
}
