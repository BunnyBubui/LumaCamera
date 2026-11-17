package th.ogay.luma

/**
 * Slow camera moves over a whole clip (the "Ken Burns" effect): zoom in, zoom out, or a slow pan left or right while
 * slightly zoomed so no edge shows. The same numbers drive the preview and the export, so both move alike.
 */
object ClipMotion {
    const val NONE = 0; const val ZOOM_IN = 1; const val ZOOM_OUT = 2; const val PAN_LEFT = 3; const val PAN_RIGHT = 4
    val NAMES = listOf("ไม่เคลื่อน", "ซูมเข้า", "ซูมออก", "แพนซ้าย", "แพนขวา")
    val ICONS = listOf("M5 5h14v14H5z", "M10 10h4v4h-4z M4 4l5 5 M20 4l-5 5 M4 20l5-5 M20 20l-5-5", "M4 4h16v16H4z M9 9l-4-4 M15 9l4-4 M9 15l-4 4 M15 15l4 4",
        "M19 12H5 M10 7l-5 5 5 5", "M5 12h14 M14 7l5 5-5 5")
    private const val ZOOM = 0.15f

    /** Scale and sideways shift (as a share of half the picture, + = right) at [f] (0..1) through the clip. */
    fun at(motion: Int, f: Float): Pair<Float, Float> {
        val x = f.coerceIn(0f, 1f)
        // A gentle ease in and out, so the move does not start or stop with a jolt.
        val e = x * x * (3 - 2 * x)
        return when (motion) {
            ZOOM_IN -> (1f + ZOOM * e) to 0f
            ZOOM_OUT -> (1f + ZOOM * (1 - e)) to 0f
            PAN_LEFT -> (1f + ZOOM) to (ZOOM * (1 - 2 * e))
            PAN_RIGHT -> (1f + ZOOM) to (-ZOOM * (1 - 2 * e))
            else -> 1f to 0f
        }
    }
}
