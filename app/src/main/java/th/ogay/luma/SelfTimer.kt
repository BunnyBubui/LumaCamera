package th.ogay.luma

/** Self-timer choices shared by the camera slider and the settings screen. 0 is off. */
object SelfTimer {
    val STEPS = listOf(0, 1, 2, 3, 5, 7, 10, 15, 20, 30)
    fun label(s: Int) = if (s == 0) "ปิด" else "$s วินาที"
}
