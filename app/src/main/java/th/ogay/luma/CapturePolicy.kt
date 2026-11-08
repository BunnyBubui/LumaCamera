package th.ogay.luma

import kotlin.math.roundToInt
import kotlin.math.sqrt

const val TARGET_PIXELS = 12_000_000L
const val MODEL_VERSION = "SR1"
data class Dimensions(val w: Int, val h: Int) {
    val pixels get() = w.toLong() * h
}
fun scaledSize(size: Dimensions, pixels: Long): Dimensions {
    require(size.w > 0 && size.h > 0 && pixels > 0)
    val scale = sqrt(pixels.toDouble() / size.pixels)
    return Dimensions((size.w * scale).roundToInt().coerceAtLeast(1), (size.h * scale).roundToInt().coerceAtLeast(1))
}
enum class Decision(val description: String) {
    MANUAL_AI("ผู้ใช้เลือก AI รวมหลายเฟรม"), RAW_CAPTURE("เก็บข้อมูล RAW จากเซนเซอร์"),
    OFF("ปิด AI อัตโนมัติ"), STANDARD("ระยะมาตรฐานหรือกล้องหน้า"),
    SMALL("ภาพที่ได้รับต่ำกว่า 12 MP"), CROP("พื้นที่หลังครอปต่ำกว่า 12 MP"),
    ENOUGH("ขนาดภาพและพื้นที่ครอปถึงเกณฑ์"), UNKNOWN("ประเมินพื้นที่ต้นทางไม่ได้")
}
fun decide(auto: Boolean, rear: Boolean, zoom: Float, output: Long, cropPixels: Long?): Decision = when {
    !auto -> Decision.OFF
    !rear || zoom <= 1f -> Decision.STANDARD
    output < TARGET_PIXELS -> Decision.SMALL
    cropPixels == null || cropPixels <= 0 -> Decision.UNKNOWN
    cropPixels < TARGET_PIXELS -> Decision.CROP
    else -> Decision.ENOUGH
}
val Decision.startsAi get() = this == Decision.SMALL || this == Decision.CROP
