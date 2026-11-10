package th.ogay.luma

import android.content.Context
import android.hardware.camera2.CameraCharacteristics as CC
import android.hardware.camera2.CaptureRequest as CR
import android.widget.LinearLayout
import android.widget.SeekBar
import kotlin.math.pow
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera

@ExperimentalCamera2Interop
class ManualControls(private val context:Context, val camera:Camera, private val video:Boolean=false, private val persistent:Boolean=video, private val videoFps:Int=30) {
    private val info=Camera2CameraInfo.from(camera.cameraInfo)
    private val prefs=context.getSharedPreferences("camera",0)
    private var iso:Int?=null;private var ns:Long?=null;private var wb=CR.CONTROL_AWB_MODE_AUTO;private var focus:Float?=null
    private var tint=0;private var edge:Int?=null;private var nr:Int?=null;private var contrast=0
    var locked=false;private set
    /** RAW sessions: leave the scene mode alone, some phones shift the colour badly when it is switched there. */
    var plainColor=false
    val canManual get()=info.getCameraCharacteristic(CC.REQUEST_AVAILABLE_CAPABILITIES)?.contains(CC.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)==true
    private val key="manual.${info.cameraId}.${if(video)"video" else "photo"}."
    fun apply(){
        if(persistent)prefs.edit().putInt(key+"iso",iso?:-1).putLong(key+"ns",ns?:-1).putInt(key+"wb",wb).putFloat(key+"focus",focus?:-1f).apply()
        prefs.edit().putInt(key+"tint",tint).putInt(key+"edge",edge?:-1).putInt(key+"nr",nr?:-1).putInt(key+"contrast",contrast).apply()
        val b=CaptureRequestOptions.Builder()
        if(canManual&&iso!=null){
            val r=info.getCameraCharacteristic(CC.SENSOR_INFO_SENSITIVITY_RANGE)!!
            val e=info.getCameraCharacteristic(CC.SENSOR_INFO_EXPOSURE_TIME_RANGE)!!
            b.setCaptureRequestOption(CR.CONTROL_AE_MODE,CR.CONTROL_AE_MODE_OFF)
            val real=(ns?:16_666_666L).coerceIn(e.lower,minOf(e.upper,if(video)(1_000_000_000L/videoFps) else e.upper))
            // Live view stops at 1/13 s so it stays fluid; the gain goes up by the same factor so brightness matches.
            // The real (longer) time is used only for the picture itself, see longExposure().
            val shown=if(!video&&!longActive&&real>PREVIEW_MAX_NS)PREVIEW_MAX_NS else real
            val gain=real.toDouble()/shown
            b.setCaptureRequestOption(CR.SENSOR_SENSITIVITY,(iso!!*gain).toInt().coerceIn(r.lower,r.upper))
            b.setCaptureRequestOption(CR.SENSOR_EXPOSURE_TIME,shown)
        }
        if(wb>=KELVIN_MIN&&canKelvin&&!kelvinRejected){
            // Manual white balance: AWB off, gains and colour matrix for this temperature from the sensor calibration.
            val (gains,ccm)=kelvinColor(wb.toDouble(),tint.toDouble())
            b.setCaptureRequestOption(CR.CONTROL_AWB_MODE,CR.CONTROL_AWB_MODE_OFF)
            b.setCaptureRequestOption(CR.COLOR_CORRECTION_MODE,CR.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            b.setCaptureRequestOption(CR.COLOR_CORRECTION_GAINS,gains)
            b.setCaptureRequestOption(CR.COLOR_CORRECTION_TRANSFORM,ccm)
            checkKelvin()
        }else b.setCaptureRequestOption(CR.CONTROL_AWB_MODE,if(wb>=KELVIN_MIN)nearestPreset(wb) else wb)
        // Faces: report them for the overlay, and when exposure, white balance and focus are all automatic let the
        // camera weight them (scene mode FACE_PRIORITY), in every photo and video mode.
        val fd=(info.getCameraCharacteristic(CC.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)?:intArrayOf()).toList()
        if(prefs.getBoolean("face_detect",true)&&fd.any{it!=CR.STATISTICS_FACE_DETECT_MODE_OFF}){
            b.setCaptureRequestOption(CR.STATISTICS_FACE_DETECT_MODE,if(CR.STATISTICS_FACE_DETECT_MODE_SIMPLE in fd)CR.STATISTICS_FACE_DETECT_MODE_SIMPLE else fd.max())
            val scenes=(info.getCameraCharacteristic(CC.CONTROL_AVAILABLE_SCENE_MODES)?:intArrayOf()).toList()
            if(!plainColor&&iso==null&&wb==CR.CONTROL_AWB_MODE_AUTO&&focus==null&&CR.CONTROL_SCENE_MODE_FACE_PRIORITY in scenes){
                b.setCaptureRequestOption(CR.CONTROL_MODE,CR.CONTROL_MODE_USE_SCENE_MODE);b.setCaptureRequestOption(CR.CONTROL_SCENE_MODE,CR.CONTROL_SCENE_MODE_FACE_PRIORITY)
            }
        }
        edge?.let{if(it in edgeModes)b.setCaptureRequestOption(CR.EDGE_MODE,it)}
        nr?.let{if(it in nrModes)b.setCaptureRequestOption(CR.NOISE_REDUCTION_MODE,it)}
        if(contrast!=0&&canCurve&&!plainColor){b.setCaptureRequestOption(CR.TONEMAP_MODE,CR.TONEMAP_MODE_CONTRAST_CURVE);val pts=ToneCurves.contrast(contrast);b.setCaptureRequestOption(CR.TONEMAP_CURVE,android.hardware.camera2.params.TonemapCurve(pts,pts,pts))}
        val ois=info.getCameraCharacteristic(CC.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?:intArrayOf()
        if(CR.LENS_OPTICAL_STABILIZATION_MODE_ON in ois && (!video || !prefs.getBoolean("video_eis",false)))b.setCaptureRequestOption(CR.LENS_OPTICAL_STABILIZATION_MODE,if(prefs.getBoolean("ois",true))CR.LENS_OPTICAL_STABILIZATION_MODE_ON else CR.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        if(focus!=null){b.setCaptureRequestOption(CR.CONTROL_AF_MODE,CR.CONTROL_AF_MODE_OFF);b.setCaptureRequestOption(CR.LENS_FOCUS_DISTANCE,focus!!)}
        if(locked&&info.getCameraCharacteristic(CC.CONTROL_AE_LOCK_AVAILABLE)==true)b.setCaptureRequestOption(CR.CONTROL_AE_LOCK,true)
        if(locked&&info.getCameraCharacteristic(CC.CONTROL_AWB_LOCK_AVAILABLE)==true)b.setCaptureRequestOption(CR.CONTROL_AWB_LOCK,true)
        Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(b.build())
    }
    fun lock(value:Boolean){locked=value;apply()}
    // Direct setters for the glass UI chips and edge rails (values are applied immediately).
    /** Closest focus in diopters. Some logical cameras report 0 although the lens focuses; then a physical lens value or a safe 10 (10 cm) is used. */
    val minFocus:Float get(){
        val own=info.getCameraCharacteristic(CC.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?:0f
        if(own>0f)return own
        val af=(info.getCameraCharacteristic(CC.CONTROL_AF_AVAILABLE_MODES)?:intArrayOf()).any{it!=CR.CONTROL_AF_MODE_OFF}
        if(!af)return 0f
        val physical=runCatching{val cm=context.getSystemService(android.hardware.camera2.CameraManager::class.java)!!
            (if(android.os.Build.VERSION.SDK_INT>=28)cm.getCameraCharacteristics(info.cameraId).physicalCameraIds else emptySet()).mapNotNull{cm.getCameraCharacteristics(it).get(CC.LENS_INFO_MINIMUM_FOCUS_DISTANCE)}.maxOrNull()}.getOrNull()?:0f
        return if(physical>0f)physical else 10f
    }
    /** ISO in 1/3-stop steps inside the sensor range (OPPO-style fine dial). */
    val isoValues get()=if(!canManual)emptyList() else info.getCameraCharacteristic(CC.SENSOR_INFO_SENSITIVITY_RANGE)!!.let{r->ISO_THIRDS.filter{it in r.lower..r.upper}}
    /** Shutter in 1/3-stop steps inside the sensor range; video is capped at one frame time. */
    val shutterValues get()=if(!canManual)emptyList() else info.getCameraCharacteristic(CC.SENSOR_INFO_EXPOSURE_TIME_RANGE)!!.let{r->SHUTTER_THIRDS.filter{it.second>=r.lower&&it.second<=minOf(r.upper,if(video)(1_000_000_000L/videoFps) else r.upper)}}
    /** Manual white balance needs AWB off, the colour-correction controls of MANUAL_POST_PROCESSING and the sensor calibration matrices. */
    val canKelvin get()=(info.getCameraCharacteristic(CC.CONTROL_AWB_AVAILABLE_MODES)?:intArrayOf()).contains(CR.CONTROL_AWB_MODE_OFF)&&
        info.getCameraCharacteristic(CC.SENSOR_FORWARD_MATRIX1)!=null&&info.getCameraCharacteristic(CC.SENSOR_COLOR_TRANSFORM1)!=null&&
        info.getCameraCharacteristic(CC.REQUEST_AVAILABLE_CAPABILITIES)?.contains(CC.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)==true
    private fun m(t:android.hardware.camera2.params.ColorSpaceTransform?)=t?.let{x->DoubleArray(9){i->x.getElement(i%3,i/3).toDouble()}}
    /** Gains and camera→sRGB matrix for [kelvin], both interpolated between the two calibration illuminants like a DNG converter. */
    private fun kelvinColor(kelvin:Double,tint:Double):Pair<android.hardware.camera2.params.RggbChannelVector,android.hardware.camera2.params.ColorSpaceTransform>{
        val k1=WhiteBalance.illuminantKelvin(info.getCameraCharacteristic(CC.SENSOR_REFERENCE_ILLUMINANT1)?:21)
        val k2=info.getCameraCharacteristic(CC.SENSOR_REFERENCE_ILLUMINANT2)?.toInt()?.let{WhiteBalance.illuminantKelvin(it)}?:k1
        val w=WhiteBalance.weight(kelvin,k1,k2)
        val cm=WhiteBalance.mix(m(info.getCameraCharacteristic(CC.SENSOR_COLOR_TRANSFORM1))!!,m(info.getCameraCharacteristic(CC.SENSOR_COLOR_TRANSFORM2)),w)
        val cal=m(info.getCameraCharacteristic(CC.SENSOR_CALIBRATION_TRANSFORM1))?.let{c1->WhiteBalance.mix(c1,m(info.getCameraCharacteristic(CC.SENSOR_CALIBRATION_TRANSFORM2)),w)}
        val fm=WhiteBalance.mix(m(info.getCameraCharacteristic(CC.SENSOR_FORWARD_MATRIX1))!!,m(info.getCameraCharacteristic(CC.SENSOR_FORWARD_MATRIX2)),w)
        val g=WhiteBalance.gains(cm,cal,kelvin,tint)
        val ccm=WhiteBalance.cameraToSrgb(fm)
        val out=IntArray(18){i->if(i%2==1)10000 else Math.round(ccm[i/2]*10000).toInt()}
        return android.hardware.camera2.params.RggbChannelVector(g[0].toFloat(),1f,1f,g[2].toFloat()) to android.hardware.camera2.params.ColorSpaceTransform(out)
    }
    val edgeModes get()=(info.getCameraCharacteristic(CC.EDGE_AVAILABLE_EDGE_MODES)?:intArrayOf()).toList()
    val nrModes get()=(info.getCameraCharacteristic(CC.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?:intArrayOf()).toList()
    val canCurve get()=(info.getCameraCharacteristic(CC.TONEMAP_AVAILABLE_TONE_MAP_MODES)?:intArrayOf()).contains(CR.TONEMAP_MODE_CONTRAST_CURVE)
    /** Tint −50 (green) … +50 (magenta) in steps of 5; only with Kelvin white balance. */
    val tintValues get()=(-50..50 step 5).map{(if(it>0)"M" else if(it<0)"G" else "")+kotlin.math.abs(it) to it}
    /** Sharpening and noise reduction: AUTO plus the modes this camera lists. */
    val edgeValues:List<Pair<String,Int?>> get()=listOf<Pair<String,Int?>>("AUTO" to null)+listOf("ปิด" to CR.EDGE_MODE_OFF,"เบา" to CR.EDGE_MODE_FAST,"คมสูง" to CR.EDGE_MODE_HIGH_QUALITY).filter{it.second in edgeModes}
    val nrValues:List<Pair<String,Int?>> get()=listOf<Pair<String,Int?>>("AUTO" to null)+listOf("ปิด" to CR.NOISE_REDUCTION_MODE_OFF,"น้อยที่สุด" to CR.NOISE_REDUCTION_MODE_MINIMAL,"เบา" to CR.NOISE_REDUCTION_MODE_FAST,"สูง" to CR.NOISE_REDUCTION_MODE_HIGH_QUALITY).filter{it.second in nrModes}
    /** Contrast −3 … +3 as a tone curve; 0 leaves the camera's own curve. */
    val contrastValues get()=if(!canCurve)emptyList() else (-3..3).map{(if(it>0)"+" else if(it==0)"±" else "")+it to it}
    val currentTint get()=tint
    val currentEdge get()=edge
    val currentNr get()=nr
    val currentContrast get()=contrast
    fun setTint(v:Int){tint=v.coerceIn(-50,50);apply()}
    fun setEdge(v:Int?){edge=v;apply()}
    fun setNr(v:Int?){nr=v;apply()}
    fun setContrast(v:Int){contrast=v.coerceIn(-3,3);apply()}
    /** AUTO, then Kelvin 2000–10000 in 50 K steps when manual WB is possible, otherwise the camera's presets. Values ≥ [KELVIN_MIN] are Kelvin. */
    var onNotice:((String)->Unit)?=null
    private var longActive=false
    private val realNs get()=ns?:0L
    /** Turns the real long shutter on for one picture (true when it was needed), or back to the live-view cap. */
    fun longExposure(on:Boolean):Boolean{
        if(video||iso==null||(on&&realNs<=PREVIEW_MAX_NS)||longActive==on)return false
        longActive=on;apply();return true
    }
    /** Time for the sensor to run a couple of frames at the long shutter before the picture is taken. */
    val settleMs get()=(realNs/1_000_000L*2+250).coerceAtMost(8000)
    val shutterLabel get()=shutterValues.firstOrNull{it.second==ns}?.first?:"${realNs/1_000_000} ms"
    private val awbModes get()=(info.getCameraCharacteristic(CC.CONTROL_AWB_AVAILABLE_MODES)?:intArrayOf()).toList()
    /** Camera presets with their usual colour temperatures, for cameras that ignore manual gains. */
    private val presetKelvin=listOf(CR.CONTROL_AWB_MODE_INCANDESCENT to 2850,CR.CONTROL_AWB_MODE_WARM_FLUORESCENT to 3200,CR.CONTROL_AWB_MODE_FLUORESCENT to 4000,CR.CONTROL_AWB_MODE_DAYLIGHT to 5500,CR.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to 6500,CR.CONTROL_AWB_MODE_SHADE to 7500)
    private fun nearestPreset(k:Int)=presetKelvin.filter{it.first in awbModes}.minByOrNull{kotlin.math.abs(it.second-k)}?.first?:CR.CONTROL_AWB_MODE_AUTO
    private var kelvinRejected=prefs.getBoolean("kelvin_rejected."+info.cameraId,false)
    private val main=android.os.Handler(android.os.Looper.getMainLooper())
    /**
     * Some HALs accept AWB off and the gains without error but keep running auto white balance, so the picture never
     * changes. A moment after a Kelvin value is set, the latest frame's AWB mode tells; if auto is still on, this camera
     * switches to the nearest preset from then on.
     */
    private fun checkKelvin(){
        val before=CameraState.frames
        main.removeCallbacksAndMessages(null)
        main.postDelayed({
            if(CameraState.frames>before+3&&CameraState.awbMode!=CR.CONTROL_AWB_MODE_OFF&&wb>=KELVIN_MIN&&!kelvinRejected){
                kelvinRejected=true;prefs.edit().putBoolean("kelvin_rejected."+info.cameraId,true).apply();apply()
                onNotice?.invoke("กล้องนี้ไม่รับค่า K โดยตรง · ใช้พรีเซ็ตที่ใกล้ที่สุดแทน")
            }
        },900)
    }
    /** Kelvin values whenever the camera can either take manual gains or has presets to approximate them. */
    val wbValues get()=if(canKelvin||presetKelvin.count{it.first in awbModes}>=2)listOf("AUTO" to CR.CONTROL_AWB_MODE_AUTO)+(2000..10000 step 50).map{"${it}K" to it}
        else (info.getCameraCharacteristic(CC.CONTROL_AWB_AVAILABLE_MODES)?:intArrayOf()).let{m->listOf("AUTO" to CR.CONTROL_AWB_MODE_AUTO,"แดด" to CR.CONTROL_AWB_MODE_DAYLIGHT,"เมฆ" to CR.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,"ร่ม" to CR.CONTROL_AWB_MODE_SHADE,"หลอดไฟ" to CR.CONTROL_AWB_MODE_INCANDESCENT,"ฟลูออเรสเซนต์" to CR.CONTROL_AWB_MODE_FLUORESCENT,"วอร์มฟลูออเรสเซนต์" to CR.CONTROL_AWB_MODE_WARM_FLUORESCENT,"พลบค่ำ" to CR.CONTROL_AWB_MODE_TWILIGHT).filter{it.second in m}}
    /** Manual focus: AUTO, then 48 steps from infinity to the closest distance, labelled in metres/centimetres. */
    val focusValues:List<Pair<String,Float?>> get(){
        val near=minFocus; if(near<=0f)return listOf("AUTO" to null)
        return listOf("AUTO" to null)+(0..48).map{i->val d=near*i/48f;focusLabel(d) to d}
    }
    val currentIso get()=iso
    val currentShutter get()=ns
    val currentWb get()=wb
    val currentFocus get()=focus
    fun setIso(v:Int?){iso=v;if(v==null)ns=null;apply()}
    fun setShutter(v:Long?){ns=v;if(v!=null&&iso==null)iso=isoValues.firstOrNull{it>=100}?:isoValues.firstOrNull();apply()}
    fun setWb(mode:Int){wb=mode;apply()}
    /** Diopters: 0 = infinity, [minFocus] = closest. Null returns to autofocus. */
    fun setFocus(v:Float?){focus=v?.coerceIn(0f,minFocus);apply()}
    companion object {
        /** Longest exposure the live view uses (1/13 s). */
        const val PREVIEW_MAX_NS=76_923_077L
        const val KELVIN_MIN=1000
        val ISO_THIRDS=listOf(25,32,40,50,64,80,100,125,160,200,250,320,400,500,640,800,1000,1250,1600,2000,2500,3200,4000,5000,6400,8000,10000,12800,16000,20000,25600)
        val SHUTTER_THIRDS:List<Pair<String,Long>> = listOf(8000,6400,5000,4000,3200,2500,2000,1600,1250,1000,800,640,500,400,320,250,200,160,125,100,80,60,50,40,30,25,20,15,13,10,8,6,5,4,3).map{"1/$it" to 1_000_000_000L/it}+
            listOf(0.4,0.5,0.6,0.8,1.0,1.3,1.6,2.0,2.5,3.2,4.0,5.0,6.0,8.0,10.0,13.0,15.0,20.0,25.0,30.0).map{(if(it>=1.0&&it%1.0==0.0)"${it.toInt()}s" else "${it}s") to (it*1_000_000_000L).toLong()}
        fun focusLabel(diopters:Float):String=if(diopters<0.02f)"∞" else (1f/diopters).let{m->if(m>=1f)String.format(java.util.Locale.US,"%.1f ม.",m) else "${(m*100).toInt()} ซม."}
    }
    fun reset(){iso=null;ns=null;focus=null;wb=CR.CONTROL_AWB_MODE_AUTO;tint=0;edge=null;nr=null;contrast=0;locked=false;apply()}
    fun show(){context.studioSheet(if(video)"PRO VIDEO" else "PRO · ควบคุมกล้อง"){box,_->
        fun options(title:String,values:List<String>,selected:Int,choose:(Int)->Unit){
            box.addView(context.label(title,15f,LIME),-1,-2)
            val row=LinearLayout(context);val scroll=android.widget.HorizontalScrollView(context).apply{isHorizontalScrollBarEnabled=false;addView(row)}
            values.forEachIndexed{i,v->row.addView(context.pill(v){choose(i);apply();for(j in 0 until row.childCount)(row.getChildAt(j) as android.widget.TextView).selectionStyle(j==i)}.apply{selectionStyle(i==selected)},-2,-2)};box.addView(scroll)
        }
        if(canManual){
            val r=info.getCameraCharacteristic(CC.SENSOR_INFO_SENSITIVITY_RANGE)!!
            val values=listOf(50,100,200,400,800,1600,3200,6400).filter{it in r.lower..r.upper}
            options("ISO",listOf("AUTO")+values.map{it.toString()},if(iso==null)0 else values.indexOf(iso)+1){i->iso=if(i==0)null else values[i-1]}
            val range=info.getCameraCharacteristic(CC.SENSOR_INFO_EXPOSURE_TIME_RANGE)!!
            val shutter=listOf("1/8000" to 125_000L,"1/1000" to 1_000_000L,"1/250" to 4_000_000L,"1/60" to 16_666_666L,"1/30" to 33_333_333L,"1/8" to 125_000_000L,"1/2" to 500_000_000L,"1s" to 1_000_000_000L).filter{it.second>=range.lower&&it.second<=minOf(range.upper,if(video)(1_000_000_000L/videoFps) else range.upper)}
            options("ชัตเตอร์ · เลือกแล้วใช้ค่าแสงแบบ Manual",shutter.map{it.first},-1){i->ns=shutter[i].second;if(iso==null)iso=100.coerceIn(r.lower,r.upper)}
        }else box.addView(context.label("กล้องนี้ไม่เปิด Manual sensor ให้แอป",14f,MUTED),-1,-2)
        val modes=info.getCameraCharacteristic(CC.CONTROL_AWB_AVAILABLE_MODES)?:intArrayOf()
        val white=listOf("AUTO" to CR.CONTROL_AWB_MODE_AUTO,"แดด" to CR.CONTROL_AWB_MODE_DAYLIGHT,"เมฆ" to CR.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,"หลอดไฟ" to CR.CONTROL_AWB_MODE_INCANDESCENT,"ฟลูออเรสเซนต์" to CR.CONTROL_AWB_MODE_FLUORESCENT).filter{it.second in modes}
        options("White balance",white.map{it.first},white.indexOfFirst{it.second==wb}){wb=white[it].second}
        val distance=info.getCameraCharacteristic(CC.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?:0f
        if(distance>0){
            box.addView(context.label("โฟกัสมือ · ∞ → ใกล้",15f,LIME),-1,-2)
            box.addView(SeekBar(context).apply{max=1000;contentDescription="โฟกัสมือ";setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onStartTrackingTouch(b:SeekBar){};override fun onStopTrackingTouch(b:SeekBar){}
                override fun onProgressChanged(b:SeekBar,p:Int,user:Boolean){if(user){focus=distance*p/1000;apply()}}
            })},-1,context.dp(52))
        }
        val exposure=camera.cameraInfo.exposureState
        if(exposure.isExposureCompensationSupported){
            box.addView(context.label("EV · ใช้เมื่อ ISO อยู่ AUTO",15f,LIME),-1,-2)
            box.addView(SeekBar(context).apply{val r=exposure.exposureCompensationRange;max=r.upper-r.lower;progress=exposure.exposureCompensationIndex-r.lower;contentDescription="ชดเชยแสง EV";setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onStartTrackingTouch(b:SeekBar){};override fun onStopTrackingTouch(b:SeekBar){}
                override fun onProgressChanged(b:SeekBar,p:Int,user:Boolean){if(user&&iso==null)camera.cameraControl.setExposureCompensationIndex(p+r.lower)}
            })},-1,context.dp(52))
        }
        fun toggle(title:String,key:String){val sw=android.widget.Switch(context).apply{text=title;setTextColor(WHITE);isChecked=prefs.getBoolean(key,false);setOnCheckedChangeListener{_,v->prefs.edit().putBoolean(key,v).apply();apply()}};box.addView(sw,-1,-2)}
        if((info.getCameraCharacteristic(CC.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?:intArrayOf()).contains(CR.LENS_OPTICAL_STABILIZATION_MODE_ON))toggle("กันสั่นเลนส์ OIS เมื่อไม่ใช้ EIS","ois")
        toggle("Histogram จากภาพพรีวิว","histogram");toggle("Focus peaking จากภาพพรีวิว","peaking");toggle("Zebra ส่วนสว่างบนพรีวิว","zebra")
        box.addView(context.pill("คืนค่า AUTO"){reset();context.notice("ใช้ค่าแสงและโฟกัสอัตโนมัติแล้ว")},-1,-2)
    }}
    // Runs last so every property above (presets, the Kelvin check) is initialised before the first apply().
    init {
        if(persistent){iso=prefs.getInt(key+"iso",-1).takeIf{it>0};ns=prefs.getLong(key+"ns",-1).takeIf{it>0};wb=prefs.getInt(key+"wb",CR.CONTROL_AWB_MODE_AUTO);focus=prefs.getFloat(key+"focus",-1f).takeIf{it>=0}}
        tint=prefs.getInt(key+"tint",0);edge=prefs.getInt(key+"edge",-1).takeIf{it>=0};nr=prefs.getInt(key+"nr",-1).takeIf{it>=0};contrast=prefs.getInt(key+"contrast",0)
        apply()
    }
}

