package th.ogay.luma

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Finishing effects of the video editor, drawn on the GPU for every exported frame: dark edges ([vignette]),
 * film grain that changes from frame to frame ([grain]) and a light sharpening ([sharpen]). Each runs from 0 (off) to 1.
 */
@UnstableApi
class StudioFx(private val vignette: Float, private val grain: Float, private val sharpen: Float) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Shader(vignette, grain, sharpen, useHdr)

    private class Shader(vignette: Float, grain: Float, sharpen: Float, useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
        private val program: GlProgram
        init {
            try {
                program = GlProgram(VERTEX, FRAGMENT)
                program.setBufferAttribute("aPosition", floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 0f, 1f, -1f, 1f, 0f, 1f, 1f, 1f, 0f, 1f), 4)
                program.setBufferAttribute("aTexCoord", floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f), 2)
                program.setFloatUniform("uVig", vignette.coerceIn(0f, 1f) * 0.85f)
                program.setFloatUniform("uGrain", grain.coerceIn(0f, 1f) * 0.22f)
                program.setFloatUniform("uSharp", sharpen.coerceIn(0f, 1f) * 0.9f)
            } catch (e: Exception) { throw VideoFrameProcessingException(e) }
        }
        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            program.setFloatsUniform("uPx", floatArrayOf(1f / inputWidth.coerceAtLeast(1), 1f / inputHeight.coerceAtLeast(1)))
            return Size(inputWidth, inputHeight)
        }
        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                program.use(); program.setSamplerTexIdUniform("uTex", inputTexId, 0)
                program.setFloatUniform("uTime", (presentationTimeUs % 1_000_000L) / 1_000_000f)
                program.bindAttributesAndUniforms(); GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            } catch (e: Exception) { throw VideoFrameProcessingException(e) }
        }
        override fun release() { try { super.release(); program.delete() } catch (e: Exception) { throw VideoFrameProcessingException(e) } }
    }

    private companion object {
        const val VERTEX = "attribute vec4 aPosition; attribute vec2 aTexCoord; varying vec2 vTex; void main(){gl_Position=aPosition;vTex=aTexCoord;}"
        // The frame arrives in linear light; the effects are worked out on display values (gamma) so they look like the preview.
        const val FRAGMENT = """precision highp float; uniform sampler2D uTex; uniform vec2 uPx; uniform float uVig; uniform float uGrain; uniform float uSharp; uniform float uTime; varying vec2 vTex;
            vec3 gamma(vec3 v){v=clamp(v,0.0,1.0);return mix(v*12.92,1.055*pow(v,vec3(1.0/2.4))-0.055,step(vec3(0.0031308),v));}
            vec3 linear(vec3 v){v=clamp(v,0.0,1.0);return mix(v/12.92,pow((v+0.055)/1.055,vec3(2.4)),step(vec3(0.04045),v));}
            float rnd(vec2 p){return fract(sin(dot(p,vec2(12.9898,78.233))+uTime*37.0)*43758.5453);}
            void main(){
                vec4 c=texture2D(uTex,vTex); vec3 v=gamma(c.rgb);
                if(uSharp>0.0){
                    vec3 n=gamma(texture2D(uTex,vTex+vec2(uPx.x,0.0)).rgb)+gamma(texture2D(uTex,vTex-vec2(uPx.x,0.0)).rgb)+gamma(texture2D(uTex,vTex+vec2(0.0,uPx.y)).rgb)+gamma(texture2D(uTex,vTex-vec2(0.0,uPx.y)).rgb);
                    v=v+(v*4.0-n)*uSharp;
                }
                v*=1.0-uVig*smoothstep(0.35,0.85,distance(vTex,vec2(0.5)));
                if(uGrain>0.0) v+=(rnd(floor(vTex/uPx/2.0))-0.5)*uGrain;
                gl_FragColor=vec4(linear(v),c.a);
            }"""
    }
}
