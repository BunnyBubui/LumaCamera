package th.ogay.luma

enum class CaptureOutput(val label: String, val needsRaw: Boolean, val enhance: Boolean) {
    JPEG("JPEG", false, false),
    RAW("RAW", true, false),
    AI("AI", false, true),
    BOTH("RAW + AI", true, true),
    /** DNG like RAW, with a flat Log look on the live preview so highlights and shadows can be judged while shooting. */
    RAW_LOG("RAW Log", true, false);
    /** Saves only sensor data (no processed photo of its own). */
    val rawOnly get() = needsRaw && !enhance
}

fun burstCount(value: Int): Int = value.coerceIn(1, 32)
