package th.ogay.luma

/** Pure policy: never invent a fixed FPS range from a device-wide AE range. */
object VideoSessionPolicy {
    data class Rate(val lower: Int, val upper: Int)
    fun normal(ranges: Collection<Rate>) = ranges.filter { it.lower > 0 && it.lower <= it.upper && it.upper in 24..60 }
        .distinct().groupBy { it.upper }.values.map { group -> group.maxBy { it.lower } }.sortedBy { it.upper }
    fun select(ranges: Collection<Rate>, requested: Int): Rate? = normal(ranges).firstOrNull { it.upper == requested }
    fun rotation(degrees: Int): Int? = if (degrees !in 0..359) null else when (degrees) {
        in 45..134 -> 3 // Surface.ROTATION_270
        in 135..224 -> 2
        in 225..314 -> 1
        else -> 0
    }
}
