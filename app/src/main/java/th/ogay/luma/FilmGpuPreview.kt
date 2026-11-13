package th.ogay.luma

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.view.View
import java.util.WeakHashMap

/**
 * GPU approximation of FilmToneEngine for the existing camera TextureView and film thumbnails.
 * The UI is unchanged. On Android 13+ this is a single AGSL render pass after the live camera
 * texture is produced; on older devices the caller keeps the original ColorMatrix fallback.
 *
 * For speed, the live pass models colour, shoulder, fine grain and vignette. The expensive
 * photographic decorations (full-resolution glow, random light leak, date stamp, frames) are
 * still added only at capture/export by Films.render().
 */
internal object FilmGpuPreview {
    private val shaders = WeakHashMap<View, RuntimeShader>()

    /**
     * A single pass transforms source RGB with the *same* colour matrix as the photo renderer,
     * then applies the highlight/shadow finishing parameters from FilmToneEngine. Grain is
     * spatially irregular and luminance weighted: aesthetically similar to export grain, though
     * the GPU/CPU noise patterns are intentionally not bit-exact.
     */
    internal const val SOURCE = """
        uniform shader input;
        uniform float4 uRed;
        uniform float4 uGreen;
        uniform float4 uBlue;
        uniform float3 uShadow;
        uniform float3 uHighlight;
        uniform float uBlackLift;
        uniform float uShoulder;
        uniform float uStrength;
        uniform float uGrain;
        uniform float uVignette;
        uniform float2 uSize;

        half4 main(float2 p) {
            half4 src = input.eval(p);
            float4 sample = float4(float3(src.rgb), 1.0);
            float3 color = clamp(float3(
                dot(sample, uRed), dot(sample, uGreen), dot(sample, uBlue)
            ), 0.0, 1.0);
            float lum = dot(color, float3(77.0, 150.0, 29.0)) / 256.0;
            float scaled = lum * 255.0;
            float shadows = clamp((150.0 - scaled) / 150.0, 0.0, 1.0);
            float highlights = clamp((scaled - 130.0) / 125.0, 0.0, 1.0);
            float lift = uBlackLift * shadows * shadows;
            float shoulder = uShoulder * highlights * highlights;

            // Reduce tint changes on warm skin, matching the saved photo's conservative guard.
            float rr = color.r * 255.0;
            float gg = color.g * 255.0;
            float bb = color.b * 255.0;
            float skin = 1.0;
            if (rr >= 55.0 && rr <= 245.0 && rr > gg && gg >= bb &&
                rr - gg >= 7.0 && rr - gg <= 65.0 && gg - bb >= 4.0 && gg - bb <= 65.0) {
                skin = 0.7;
            }
            float3 tint = uShadow * shadows + uHighlight * highlights +
                float3(lift - shoulder);
            color += (tint * uStrength * skin) / 255.0;

            // Triangular-ish, non-periodic spatial noise (not a repeating noise texture).
            float n1 = fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
            float n2 = fract(sin(dot(p, float2(269.5, 183.3))) * 22578.1459);
            float envelope = 0.2 + 0.8 * clamp(1.0 - abs(scaled - 120.0) / 180.0, 0.0, 1.0);
            color += float3((n1 - n2) * uGrain * envelope / 255.0);

            // Soft edges as in export; width/height are the rendered view's pixel dimensions.
            if (uVignette > 0.0 && uSize.x > 1.0 && uSize.y > 1.0) {
                float2 xy = (p - uSize * 0.5) / max(length(uSize * 0.5), 1.0);
                float edge = clamp((length(xy) - 0.55) / 0.45, 0.0, 1.0);
                color *= 1.0 - uVignette * edge * edge;
            }
            return half4(half3(clamp(color, 0.0, 1.0)), src.a);
        }
    """

    /**
     * Returns false on unsupported devices or a shader/setup error so callers retain the old
     * matrix fallback. Clearing the effect on non-film modes is necessary to avoid leaking a
     * previous look into ordinary camera captures.
     */
    fun apply(view: View, film: Film, fx: FilmFx): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return try {
            val shader = shaders[view] ?: RuntimeShader(SOURCE).also {
                shaders[view] = it
                view.setRenderEffect(RenderEffect.createRuntimeShaderEffect(it, "input"))
            }
            val m = Films.matrix(film, fx.strength).array
            shader.setFloatUniform("uRed", m[0], m[1], m[2], m[4] / 255f)
            shader.setFloatUniform("uGreen", m[5], m[6], m[7], m[9] / 255f)
            shader.setFloatUniform("uBlue", m[10], m[11], m[12], m[14] / 255f)
            val palette = FilmToneEngine.palette(film)
            shader.setFloatUniform("uShadow", palette.shadowR.toFloat(), palette.shadowG.toFloat(), palette.shadowB.toFloat())
            shader.setFloatUniform("uHighlight", palette.highlightR.toFloat(), palette.highlightG.toFloat(), palette.highlightB.toFloat())
            shader.setFloatUniform("uBlackLift", palette.blackLift.toFloat())
            shader.setFloatUniform("uShoulder", palette.shoulder.toFloat())
            val strength = fx.strength.coerceIn(0f, 1f)
            shader.setFloatUniform("uStrength", strength)
            shader.setFloatUniform("uGrain", film.grain.coerceAtLeast(0f) * fx.grain.coerceIn(0f, 2f) * strength * 34f)
            shader.setFloatUniform("uVignette", (film.vignette * fx.vignette * strength).coerceIn(0f, 0.90f))
            shader.setFloatUniform("uSize", view.width.toFloat(), view.height.toFloat())
            view.invalidate()
            true
        } catch (_: RuntimeException) {
            clear(view)
            false
        }
    }

    fun clear(view: View) {
        shaders.remove(view)
        if (Build.VERSION.SDK_INT >= 33) view.setRenderEffect(null)
    }
}
