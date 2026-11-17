package th.ogay.luma

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.C
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.MatrixTransformation

/**
 * Transitions drawn on one clip at a time: over its first [inMs] when the clip arrives and over its last [outMs] when it leaves (0 = none). Times are counted from the clip's own first frame.
 * [WIPE] reveals the clip from the left edge (and hides it towards the right edge when it leaves); [BLUR] brings it
 * into focus and lets it go out of focus.
 */
@UnstableApi
class StudioTransition(private val mode: Int, private val durationUs: Long, inMs: Long, outMs: Long) : GlEffect {
    private val inUs = inMs * 1000; private val outUs = outMs * 1000
    companion object { const val WIPE = 0; const val BLUR = 1
        /** 0..1 at [t] µs of a [durationUs] clip: 0 = fully in the transition at the cut, 1 = clear of it. [inUs]/[outUs] are the lengths at the clip's start and end (0 = none). */
        fun progress(t: Long, durationUs: Long, inUs: Long, outUs: Long): Float {
            val si = minOf(inUs, durationUs / 2); val so = minOf(outUs, durationUs / 2)
            var k = 1f
            if (si > 0 && t < si) k = minOf(k, t.toFloat() / si)
            if (so > 0 && t > durationUs - so) k = minOf(k, ((durationUs - t).toFloat() / so).coerceAtLeast(0f))
            return k
        }
        private const val VERTEX = "attribute vec4 aPosition; attribute vec2 aTexCoord; varying vec2 vTex; void main(){gl_Position=aPosition;vTex=aTexCoord;}"
        private const val FRAGMENT = """precision highp float; uniform sampler2D uTex; uniform vec2 uPx; uniform float uK; uniform float uMode; uniform float uOut; varying vec2 vTex;
            void main(){
                vec4 c=texture2D(uTex,vTex);
                if(uMode<0.5){
                    // Wipe: a soft edge moves across; arriving clips show left of it, leaving clips right of it.
                    float edge=uOut>0.5?1.0-uK:uK; float x=uOut>0.5?1.0-vTex.x:vTex.x;
                    float a=1.0-smoothstep(edge*1.1-0.05,edge*1.1+0.05,x);
                    gl_FragColor=vec4(c.rgb*a,c.a);
                } else {
                    float r=(1.0-uK)*(1.0-uK)*14.0; vec4 s=vec4(0.0);
                    for(int j=-2;j<=2;j++)for(int i=-2;i<=2;i++) s+=texture2D(uTex,vTex+vec2(float(i),float(j))*uPx*r);
                    gl_FragColor=s/25.0;
                }
            }"""
    }
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(useHdr)

    private inner class Program(useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
        private val program: GlProgram
        private var first = C.TIME_UNSET
        init {
            try {
                program = GlProgram(VERTEX, FRAGMENT)
                program.setBufferAttribute("aPosition", floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 0f, 1f, -1f, 1f, 0f, 1f, 1f, 1f, 0f, 1f), 4)
                program.setBufferAttribute("aTexCoord", floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f), 2)
                program.setFloatUniform("uMode", mode.toFloat())
            } catch (e: Exception) { throw VideoFrameProcessingException(e) }
        }
        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            program.setFloatsUniform("uPx", floatArrayOf(1f / inputWidth.coerceAtLeast(1), 1f / inputHeight.coerceAtLeast(1)))
            return Size(inputWidth, inputHeight)
        }
        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                if (first == C.TIME_UNSET) first = presentationTimeUs
                val t = presentationTimeUs - first
                val so = minOf(outUs, durationUs / 2)
                program.use(); program.setSamplerTexIdUniform("uTex", inputTexId, 0)
                program.setFloatUniform("uK", progress(t, durationUs, inUs, outUs))
                program.setFloatUniform("uOut", if (so > 0 && t > durationUs - so) 1f else 0f)
                program.bindAttributesAndUniforms(); GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            } catch (e: Exception) { throw VideoFrameProcessingException(e) }
        }
        override fun release() { try { super.release(); program.delete() } catch (e: Exception) { throw VideoFrameProcessingException(e) } }
    }
}

/** Slide: an arriving clip comes in from the right, a leaving clip goes out to the left. */
@UnstableApi
class StudioSlide(private val durationUs: Long, inMs: Long, outMs: Long) : MatrixTransformation {
    private val inUs = inMs * 1000; private val outUs = outMs * 1000
    private var first = C.TIME_UNSET
    override fun getMatrix(presentationTimeUs: Long): android.graphics.Matrix {
        if (first == C.TIME_UNSET) first = presentationTimeUs
        val t = presentationTimeUs - first; val so = minOf(outUs, durationUs / 2)
        val k = StudioTransition.progress(t, durationUs, inUs, outUs); val e = 1 - (1 - k) * (1 - k)
        val leaving = so > 0 && t > durationUs - so
        return android.graphics.Matrix().apply { setTranslate(if (leaving) -(1 - e) * 2f else (1 - e) * 2f, 0f) }
    }
}
