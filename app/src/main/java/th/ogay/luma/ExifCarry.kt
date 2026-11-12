package th.ogay.luma

import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * Camera EXIF survives every step that re-encodes a photo (aspect crop, film look, watermark, portrait, HDR,
 * in-app zoom crop). Without it the system gallery shows "no camera information".
 */
object ExifCarry {
    private val TAGS = listOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_LENS_SPECIFICATION,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_APERTURE_VALUE, ExifInterface.TAG_MAX_APERTURE_VALUE,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_SHUTTER_SPEED_VALUE, ExifInterface.TAG_BRIGHTNESS_VALUE,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_EXPOSURE_MODE, ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
        ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_LIGHT_SOURCE, ExifInterface.TAG_FLASH, ExifInterface.TAG_SCENE_CAPTURE_TYPE,
        ExifInterface.TAG_SUBJECT_DISTANCE, ExifInterface.TAG_SUBJECT_DISTANCE_RANGE,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_IMAGE_DESCRIPTION, ExifInterface.TAG_SOFTWARE)

    fun read(file: File): Map<String, String> = runCatching {
        val e = ExifInterface(file); TAGS.mapNotNull { t -> e.getAttribute(t)?.let { t to it } }.toMap()
    }.getOrDefault(emptyMap())

    fun write(file: File, values: Map<String, String>) {
        if (values.isEmpty()) return
        runCatching { ExifInterface(file).apply { values.forEach { (k, v) -> if (getAttribute(k) == null) setAttribute(k, v) }; saveAttributes() } }
    }

    fun copy(from: File, to: File) = write(to, read(from))
    /** The same tags read from a picture in the media store (the original of an AI result). */
    fun read(context: android.content.Context, uri: android.net.Uri): Map<String, String> = runCatching {
        (if (uri.scheme == "file") uri.path?.let { java.io.FileInputStream(it) } else context.contentResolver.openInputStream(uri))?.use { input ->
            val e = ExifInterface(input); TAGS.mapNotNull { t -> e.getAttribute(t)?.let { t to it } }.toMap()
        } ?: emptyMap()
    }.getOrDefault(emptyMap())

    /** What the system gallery shows as the camera; the phone itself stays in Make and in the lens model. */
    const val MODEL = "Lumora Camera"
}
