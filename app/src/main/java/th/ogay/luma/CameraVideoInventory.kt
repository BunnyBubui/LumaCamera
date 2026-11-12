package th.ogay.luma

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.media.CamcorderProfile
import android.media.MediaCodecInfo
import android.media.MediaRecorder
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject

/** Advertised data only: neither timing nor codec support proves a working camera session. */
object CameraVideoInventory {
    fun timing(characteristics: CameraCharacteristics): JSONArray {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return JSONArray()
        val common = listOf(Size(1280,720), Size(1920,1080), Size(3840,2160))
        return JSONArray(common.map { size ->
            fun duration(type: Class<*>) = runCatching { map.getOutputMinFrameDuration(type, size) }.getOrNull()
            val recorder = duration(MediaRecorder::class.java)
            val preview = duration(SurfaceTexture::class.java)
            JSONObject().put("size",size.toString()).put("recorderMinFrameNs",recorder ?: JSONObject.NULL)
                .put("previewMinFrameNs",preview ?: JSONObject.NULL)
                .put("recorderMaxFpsTheoretical",if(recorder != null && recorder > 0)1e9/recorder else JSONObject.NULL)
                .put("status","advertised_timing_not_session_tested")
        })
    }
    fun highSpeed(characteristics: CameraCharacteristics): JSONArray {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return JSONArray()
        return JSONArray(runCatching { map.highSpeedVideoSizes.flatMap { size ->
            map.getHighSpeedVideoFpsRangesFor(size).map { range ->
                JSONObject().put("size",size.toString()).put("lower",range.lower).put("upper",range.upper)
            }
        } }.getOrDefault(emptyList()))
    }
    @Suppress("DEPRECATION")
    fun profiles(cameraId: String): JSONArray {
        val id = cameraId.toIntOrNull() ?: return JSONArray()
        return JSONArray(listOf(CamcorderProfile.QUALITY_720P,CamcorderProfile.QUALITY_1080P,CamcorderProfile.QUALITY_2160P,
            CamcorderProfile.QUALITY_HIGH_SPEED_720P,CamcorderProfile.QUALITY_HIGH_SPEED_1080P).mapNotNull { quality ->
            runCatching {
                if(!CamcorderProfile.hasProfile(id,quality))return@runCatching null
                val p=CamcorderProfile.get(id,quality)
                JSONObject().put("quality",quality).put("size","${p.videoFrameWidth}x${p.videoFrameHeight}")
                    .put("fps",p.videoFrameRate).put("codec",p.videoCodec).put("bitrate",p.videoBitRate)
            }.getOrNull()
        })
    }
    fun encoder(info: MediaCodecInfo): JSONArray = JSONArray(info.supportedTypes.filter { it=="video/avc" || it=="video/hevc" }.flatMap { mime ->
        listOf(Size(1280,720),Size(1920,1080),Size(3840,2160)).flatMap { size -> listOf(30,60,120,240).map { fps ->
            val result=runCatching { info.getCapabilitiesForType(mime).videoCapabilities?.areSizeAndRateSupported(size.width,size.height,fps.toDouble()) }
            JSONObject().put("mime",mime).put("size",size.toString()).put("fps",fps)
                .put("supported",result.getOrNull() ?: JSONObject.NULL).put("error",result.exceptionOrNull()?.message ?: JSONObject.NULL)
        } }
    })
}
