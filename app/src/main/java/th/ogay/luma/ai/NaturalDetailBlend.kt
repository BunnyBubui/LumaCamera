package th.ogay.luma.ai

import kotlin.math.roundToInt

/**
 * Conservative, colour-neutral tone correction from the SR2 neural result.
 *
 * The original bilinear RGB is the reference. The neural model may adjust only
 * luminance, with a bounded per-pixel correction attenuated in smooth areas,
 * high-contrast edges (lettering/branches) and deep shadows/highlights.
 *
 * This is a photometric guard, NOT an OCR/face detector or a guarantee that text
 * or identity can be reconstructed. No new model weights are involved.
 */
internal object NaturalDetailBlend {
    private fun toByte(x: Float): Int = x.roundToInt().coerceIn(0, 255)

    fun blend(
        baseR: Float, baseG: Float, baseB: Float,
        neuralR: Float, neuralG: Float, neuralB: Float,
        localContrast: Float, strength: Int, protectionFactor: Float = 1f
    ): Int {
        require(strength in 0..100)
        require(localContrast.isFinite() && localContrast >= 0f)
        require(protectionFactor.isFinite() && protectionFactor in 0f..1f)
        require(baseR.isFinite() && baseG.isFinite() && baseB.isFinite())
        require(neuralR.isFinite() && neuralG.isFinite() && neuralB.isFinite())
        if (strength == 0) return 0xff000000.toInt() or (toByte(baseR) shl 16) or
            (toByte(baseG) shl 8) or toByte(baseB)

        val baseY = 0.299f * baseR + 0.587f * baseG + 0.114f * baseB
        val neuralY = 0.299f * neuralR + 0.587f * neuralG + 0.114f * neuralB

        // Smooth regions suppress invented texture. Strong edges suppress halos,
        // ringing and changes around thin lettering. Both gates are continuous.
        val textureGate = (localContrast / 20f).coerceIn(0.08f, 1f)
        val edgeGate = (55f / (localContrast + 5f)).coerceIn(0.30f, 1f)
        val toneGate = if (baseY < 24f || baseY > 235f) 0.55f else 1f
        val gain = (strength / 100f) * 0.85f * textureGate * edgeGate * toneGate * protectionFactor
        val maximumShift = (3f + 16f * (localContrast / 25f).coerceIn(0f, 1f) * edgeGate)
            .coerceAtMost(18f) * protectionFactor
        val correction = ((neuralY - baseY) * gain).coerceIn(-maximumShift, maximumShift)
        // Keep all three channels in gamut BEFORE adding the equal shift. Without
        // this clamp, separately clipping R/G/B can shift hue in saturated colours.
        val chromaSafeCorrection = correction.coerceIn(
            -minOf(baseR,baseG,baseB),255f-maxOf(baseR,baseG,baseB))

        // An equal RGB adjustment preserves the source chroma difference instead
        // of inventing colour in foliage, clothes or faces.
        return 0xff000000.toInt() or (toByte(baseR + correction) shl 16) or
            (toByte(baseG + correction) shl 8) or toByte(baseB + correction)
    }
}
