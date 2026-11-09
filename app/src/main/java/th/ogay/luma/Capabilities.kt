package th.ogay.luma

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics as CC
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.DynamicRangeProfiles
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.os.Build
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object Capabilities {
    fun characteristics(c:Context,id:String)= (c.getSystemService(Context.CAMERA_SERVICE) as CameraManager).getCameraCharacteristics(id)
    fun rawIds(c:Context):List<String>{val m=c.getSystemService(Context.CAMERA_SERVICE) as CameraManager;return m.cameraIdList.filter{id->m.getCameraCharacteristics(id).get(CC.REQUEST_AVAILABLE_CAPABILITIES)?.contains(CC.REQUEST_AVAILABLE_CAPABILITIES_RAW)==true}}
    fun highSpeed(c:Context,id:String):List<Pair<Size,Int>> = runCatching{
        val map=characteristics(c,id).get(CC.SCALER_STREAM_CONFIGURATION_MAP)!!
        map.highSpeedVideoSizes.flatMap{s->map.getHighSpeedVideoFpsRangesFor(s).filter{it.lower==it.upper&&it.upper>60}.map{s to it.upper}}.distinct()
    }.getOrDefault(emptyList())
    fun report(c:Context):JSONObject {
        val m=c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameras=JSONArray()
        m.cameraIdList.forEach{id->runCatching{
            val p=m.getCameraCharacteristics(id);val cap=p.get(CC.REQUEST_AVAILABLE_CAPABILITIES)?:intArrayOf();val map=p.get(CC.SCALER_STREAM_CONFIGURATION_MAP)
            fun sizes(format:Int)=JSONArray(map?.getOutputSizes(format)?.sortedByDescending{it.width.toLong()*it.height}?.map{it.toString()}.orEmpty())
            val j=JSONObject().put("id",id).put("facing",p.get(CC.LENS_FACING)).put("hardwareLevel",p.get(CC.INFO_SUPPORTED_HARDWARE_LEVEL))
                .put("raw",cap.contains(CC.REQUEST_AVAILABLE_CAPABILITIES_RAW)).put("manualSensor",cap.contains(CC.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR))
                .put("focusDiopters",p.get(CC.LENS_INFO_MINIMUM_FOCUS_DISTANCE)).put("isoRange",p.get(CC.SENSOR_INFO_SENSITIVITY_RANGE)?.toString())
                .put("exposureNs",p.get(CC.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.toString()).put("jpegSizes",sizes(ImageFormat.JPEG)).put("rawSizes",sizes(ImageFormat.RAW_SENSOR))
                .put("aeFpsRanges",JSONArray(p.get(CC.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.map{it.toString()}.orEmpty()))
                .put("videoSizes",JSONArray(map?.getOutputSizes(MediaRecorder::class.java)?.map{it.toString()}.orEmpty()))
                .put("highSpeed",JSONArray(highSpeed(c,id).map{"${it.first}@${it.second}"}))
                .put("opticalStabilization",JSONArray(p.get(CC.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toList().orEmpty()))
                .put("electronicStabilization",JSONArray(p.get(CC.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.toList().orEmpty()))
                .put("sessionStatus","needs_combination_test").put("rawVideoStatus","experimental_measurement_required")
            if(Build.VERSION.SDK_INT>=30)j.put("zoomRange",p.get(CC.CONTROL_ZOOM_RATIO_RANGE)?.toString())
            if(Build.VERSION.SDK_INT>=33)j.put("dynamicRangeProfiles",JSONArray(p.get(CC.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles?.toList().orEmpty()))
            if(Build.VERSION.SDK_INT>=34)j.put("jpegR",sizes(ImageFormat.JPEG_R))
            cameras.put(j)
        }}
        val codecs=JSONArray(MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter{it.isEncoder}.map{JSONObject().put("name",it.name).put("types",JSONArray(it.supportedTypes.toList()))})
        val result=JSONObject().put("schema",1).put("app","0.3.0").put("manufacturer",Build.MANUFACTURER).put("model",Build.MODEL).put("sdk",Build.VERSION.SDK_INT)
            .put("timeMs",System.currentTimeMillis()).put("freeBytes",c.filesDir.usableSpace).put("cameras",cameras).put("encoders",codecs)
        if(Build.VERSION.SDK_INT>=30)result.put("concurrentSets",JSONArray(m.concurrentCameraIds.map{JSONArray(it.toList())}))
        return result
    }
    fun show(activity:Activity){
        activity.luma.fileIo.execute {
            val json=runCatching{report(activity)}
            activity.runOnUiThread {
                json.onSuccess{j->
                    val text="${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n\n"+j.getJSONArray("cameras").let{a->(0 until a.length()).joinToString("\n\n"){i->val x=a.getJSONObject(i);"กล้อง ${x.getString("id")}\nRAW ${x.getBoolean("raw")} · Manual ${x.getBoolean("manualSensor")}\nJPEG ${x.getJSONArray("jpegSizes").optString(0)}\nFPS ${x.getJSONArray("aeFpsRanges")}"}}+"\n\nค่าข้างต้นเป็นสิ่งที่ Android รายงาน ต้องทดสอบชุดความละเอียด / fps / HDR / กันสั่นร่วมกัน"
                    AlertDialog.Builder(activity).setTitle("ความสามารถกล้อง").setMessage(text).setPositiveButton("ส่งออก JSON"){_,_->
                        val file=File(activity.filesDir,"reports/camera-${System.currentTimeMillis()}.json");file.parentFile?.mkdirs();file.writeText(j.toString(2));MediaLedger.rememberFile(activity,file,"application/json");MediaLedger.share(activity,MediaLedger.privateUri(activity,file),"application/json")
                    }.setNegativeButton("ปิด",null).show()
                }.onFailure{activity.notice(it.localizedMessage?:"อ่านข้อมูลกล้องไม่ได้")}
            }
        }
    }
}
