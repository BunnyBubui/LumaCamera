package th.ogay.luma

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Which lens took a picture: written into the photo's EXIF and into the JSON beside a video. */
object LensMeta {
    data class Lens(val name: String, val focalMm: Float, val aperture: Float, val eq35: Int, val zoom: Float, val digital: Float, val cameraId: String, val physicalId: String?)

    private fun focalOf(c: CameraCharacteristics): Float? = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
    private fun apertureOf(c: CameraCharacteristics): Float? = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()
    /** 35 mm equivalent of one lens: focal length scaled by the full-frame diagonal over the sensor diagonal. */
    fun eq35(focalMm: Float, sensorW: Float, sensorH: Float): Int = (focalMm * 43.27f / sqrt(sensorW * sensorW + sensorH * sensorH)).roundToInt()

    /**
     * [zoom] is the ratio shown in the app (1 = main lens). [hardwareZoom] is what the camera itself zoomed;
     * anything above it is the app's own crop ([digital] in the result).
     */
    fun describe(context: Context, cameraId: String, physicalId: String?, zoom: Float, hardwareZoom: Float, rear: Boolean): Lens? = runCatching {
        val m = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val logical = m.getCameraCharacteristics(cameraId)
        val phys = physicalId?.let { id -> runCatching { m.getCameraCharacteristics(id) }.getOrNull() } ?: logical
        val size = (logical.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE))
        val mainFocal = focalOf(logical) ?: return@runCatching null
        val mainEq = if (size != null) eq35(mainFocal, size.width, size.height) else 26
        val focal = focalOf(phys) ?: mainFocal
        val name = when {
            !rear -> "หน้า"
            zoom < 0.95f -> "อัลตร้าไวด์"
            physicalId != null && phys !== logical && focal > mainFocal * 1.6f -> "เทเล"
            else -> "หลัก"
        }
        Lens(name, focal, apertureOf(phys) ?: apertureOf(logical) ?: 0f, (mainEq * zoom).roundToInt(), zoom, (zoom / hardwareZoom.coerceAtLeast(0.01f)).coerceAtLeast(1f), cameraId, physicalId)
    }.getOrNull()

    private fun ascii(name: String) = when (name) { "หน้า" -> "front"; "อัลตร้าไวด์" -> "ultra-wide"; "เทเล" -> "telephoto"; else -> "main" }

    /** Writes focal length, 35 mm equivalent, f-number, digital zoom and a lens model string into a JPEG. */
    fun stamp(file: File, lens: Lens?) {
        lens ?: return
        runCatching {
            val e = ExifInterface(file)
            fun rational(v: Float) = "${(v * 1000).roundToInt()}/1000"
            e.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, rational(lens.focalMm))
            e.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, lens.eq35.toString())
            if (lens.aperture > 0f) e.setAttribute(ExifInterface.TAG_F_NUMBER, rational(lens.aperture))
            if (lens.digital > 1.001f) e.setAttribute(ExifInterface.TAG_DIGITAL_ZOOM_RATIO, rational(lens.digital))
            e.setAttribute(ExifInterface.TAG_LENS_MAKE, android.os.Build.MANUFACTURER)
            e.setAttribute(ExifInterface.TAG_LENS_MODEL, String.format(Locale.US, "%s %s lens %.1fmm f/%.1f (%d mm eq, %.1fx)", android.os.Build.MODEL, ascii(lens.name), lens.focalMm, lens.aperture, lens.eq35, lens.zoom))
            e.saveAttributes()
        }
    }

    fun json(lens: Lens?): JSONObject? = lens?.let {
        JSONObject().put("lens", ascii(it.name)).put("focalLengthMm", it.focalMm.toDouble()).put("aperture", it.aperture.toDouble())
            .put("equivalent35mm", it.eq35).put("zoom", it.zoom.toDouble()).put("digitalZoom", it.digital.toDouble())
            .put("cameraId", it.cameraId).put("physicalCameraId", it.physicalId ?: JSONObject.NULL)
    }

    /** Short label for the gallery's photo info, e.g. "หลัก · 24 มม. · f/1.8". */
    fun label(lens: Lens): String = "${lens.name} · ${lens.eq35} มม. · f/${String.format(Locale.US, "%.1f", lens.aperture)}"
}
