package th.ogay.luma

enum class CaptureOutput(val label: String, val needsRaw: Boolean, val enhance: Boolean) {
    JPEG("JPEG", false, false),
    RAW("RAW", true, false),
    AI("AI", false, true),
    BOTH("RAW + AI", true, true)
}

fun burstCount(value: Int): Int = value.coerceIn(1, 32)
