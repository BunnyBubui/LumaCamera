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
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The same SR1 weights as still capture, applied independently to SDR frames on the GPU. */
@UnstableApi
class LumaVideoEffect(private val sr:Boolean):GlEffect {
    override fun toGlShaderProgram(context:Context,useHdr:Boolean):GlShaderProgram {
        if(useHdr)throw VideoFrameProcessingException("SR1 / Luma Log รองรับ SDR เท่านั้น")
        return Shader(context,sr)
    }
    private class Shader(context:Context,private val sr:Boolean):BaseGlShaderProgram(false,1){
        private val program:GlProgram
        private var modelTexture=0
        init{
            try{
                program=GlProgram(VERTEX,if(sr)SR_FRAGMENT else LOG_FRAGMENT)
                program.setBufferAttribute("aPosition",floatArrayOf(-1f,-1f,0f,1f,1f,-1f,0f,1f,-1f,1f,0f,1f,1f,1f,0f,1f),4)
                program.setBufferAttribute("aTexCoord",floatArrayOf(0f,0f,1f,0f,0f,1f,1f,1f),2)
                if(sr){
                    val raw=context.assets.open("luma-sr1.bin").use{it.readBytes()};check(raw.size==3400)
                    val weights=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);val data=ByteBuffer.allocateDirect(25*68*4)
                    repeat(1700){val w=weights.short.toInt()+32768;data.put((w and 255).toByte());data.put((w ushr 8).toByte());data.put(0);data.put(255.toByte())};data.position(0)
                    val ids=IntArray(1);GLES20.glGenTextures(1,ids,0);modelTexture=ids[0];GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,modelTexture)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_NEAREST);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_NEAREST)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,25,68,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,data)
                    program.setSamplerTexIdUniform("uModel",modelTexture,1)
                }
            }catch(e:Exception){throw VideoFrameProcessingException(e)}
        }
        override fun configure(inputWidth:Int,inputHeight:Int):Size{
            if(sr){if(inputWidth.toLong()*inputHeight>2_100_000)throw VideoFrameProcessingException("SR1 Video 2× รองรับต้นฉบับไม่เกิน 1080p ในรุ่นทดลองนี้");program.setFloatsUniform("uSize",floatArrayOf(inputWidth.toFloat(),inputHeight.toFloat()));return Size(inputWidth*2,inputHeight*2)}
            return Size(inputWidth,inputHeight)
        }
        override fun drawFrame(inputTexId:Int,presentationTimeUs:Long){try{program.use();program.setSamplerTexIdUniform("uTex",inputTexId,0);program.bindAttributesAndUniforms();GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);val error=GLES20.glGetError();check(error==GLES20.GL_NO_ERROR){"GPU error $error"}}catch(e:Exception){throw VideoFrameProcessingException(e)}}
        override fun release(){try{super.release();program.delete();if(modelTexture!=0)GLES20.glDeleteTextures(1,intArrayOf(modelTexture),0)}catch(e:Exception){throw VideoFrameProcessingException(e)}}
    }
    companion object{
        private const val VERTEX="""attribute vec4 aPosition; attribute vec2 aTexCoord; varying vec2 vTex; void main(){gl_Position=aPosition;vTex=aTexCoord;}"""
        private const val TRANSFER="""
            vec3 gamma(vec3 v){v=clamp(v,0.0,1.0);return mix(v*12.92,1.055*pow(v,vec3(1.0/2.4))-0.055,step(vec3(0.0031308),v));}
            vec3 linear(vec3 v){v=clamp(v,0.0,1.0);return mix(v/12.92,pow((v+0.055)/1.055,vec3(2.4)),step(vec3(0.04045),v));}
        """
        private const val LOG_FRAGMENT="precision highp float; uniform sampler2D uTex; varying vec2 vTex;"+TRANSFER+"void main(){vec3 v=clamp(texture2D(uTex,vTex).rgb,0.0,1.0);vec3 encoded=log(1.0+9.0*v)/log(10.0);gl_FragColor=vec4(linear(encoded),1.0);}"
        private const val SR_FRAGMENT="""precision highp float; uniform sampler2D uTex; uniform sampler2D uModel; uniform vec2 uSize; varying vec2 vTex;
        """+TRANSFER+"""
            vec3 rgb(vec2 pixel){return gamma(texture2D(uTex,(clamp(pixel,vec2(0.0),uSize-1.0)+0.5)/uSize).rgb);}
            float y(vec2 pixel){return dot(rgb(pixel),vec3(77.0,150.0,29.0))/256.0*255.0;}
            void main(){
                vec2 centre=floor(vTex*uSize);vec2 phase=mod(floor(vTex*uSize*2.0),2.0);
                float dx=y(centre+vec2(1.0,0.0))-y(centre-vec2(1.0,0.0));float dy=y(centre+vec2(0.0,1.0))-y(centre-vec2(0.0,1.0));
                float strength=abs(dx)+abs(dy);float angle=mod(floor(atan(dy,dx)*(8.0/3.14159265359)+0.5)+8.0,8.0);
                float bank=strength<10.0?0.0:1.0+angle+(strength>=48.0?8.0:0.0);float row=bank*4.0+phase.y*2.0+phase.x;
                float predicted=0.0;
                for(int j=-2;j<=2;j++)for(int i=-2;i<=2;i++){
                    float k=float((j+2)*5+i+2);vec2 w=texture2D(uModel,vec2((k+0.5)/25.0,(row+0.5)/68.0)).rg;
                    float weight=(floor(w.r*255.0+0.5)+floor(w.g*255.0+0.5)*256.0-32768.0)/4096.0;
                    predicted+=weight*y(centre+vec2(float(i),float(j)));
                }
                vec2 offset=phase*2.0-1.0;vec3 color=(9.0*rgb(centre)+3.0*rgb(centre+vec2(offset.x,0.0))+3.0*rgb(centre+vec2(0.0,offset.y))+rgb(centre+offset))/16.0;
                float delta=clamp(predicted/255.0-dot(color,vec3(77.0,150.0,29.0))/256.0,-24.0/255.0,24.0/255.0);
                gl_FragColor=vec4(linear(color+delta),1.0);
            }
        """
    }
}
