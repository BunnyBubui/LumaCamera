package th.ogay.luma.ai

import th.ogay.luma.Dimensions

/** A scale multiplies both axes. Never silently downsample a requested export. */
enum class EnhanceProfile { NATURAL, CLASSIC }

data class EnhanceSpec(val scale: Int = 2, val strength: Int = 100,
                       val profile: EnhanceProfile = EnhanceProfile.CLASSIC) {
    val modelId: String get() = if (profile == EnhanceProfile.NATURAL) NATURAL_MODEL else MODEL
    init {
        require(scale in 2..4) { "เลือกขนาด ×2, ×3 หรือ ×4" }
        require(strength in 0..150) { "ความแรงต้องอยู่ระหว่าง 0–150" }
    }
    fun output(source: Dimensions): Dimensions {
        require(source.w > 0 && source.h > 0)
        val w = source.w.toLong() * scale
        val h = source.h.toLong() * scale
        require(w <= MAX_EDGE && h <= MAX_EDGE && w * h <= MAX_PIXELS) {
            "ภาพขนาดนี้เกินขีดจำกัดส่งออก 220 MP / ด้านละ 32768 px เลือกสเกลที่เล็กลง"
        }
        return Dimensions(w.toInt(), h.toInt())
    }
    companion object {
        const val MODEL = "DetailSR2"
        // Phase 1 Natural is a conservative profile over the existing SR2 model, not new trained weights.
        const val NATURAL_MODEL = "NaturalSR3P1"
        // Must match model/build_detail.py (the model's input is CORE + 2 * HALO square).
        const val CORE = 192
        // SRVGG: 34 consecutive 3x3 convolutions => radius 34, plus resize gutter.
        const val HALO = 36
        const val INPUT = CORE + 2 * HALO
        const val NATIVE_SCALE = 4
        const val MAX_EDGE = 32768L
        const val MAX_PIXELS = 220_000_000L
    }
}
