package th.ogay.luma

import android.graphics.SurfaceTexture
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import androidx.core.util.Consumer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor

/**
 * Digital zoom past the camera's own limit for video: the camera frame goes through one OpenGL pass that crops
 * the centre by [zoom] before it reaches the preview and the recorder. At 1 it is a plain copy.
 * Only bound when the user turns on "ซูมวิดีโอเกิน 20×" and the clip is SDR (8-bit).
 */
class GlZoomEffect private constructor(private val p: ZoomProcessor) :
    CameraEffect(CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE, p.executor, p, Consumer<Throwable> { e -> Log.e("Lumora", "zoom effect", e) }) {
    constructor() : this(ZoomProcessor())
    var zoom: Float get() = p.zoom; set(v) { p.zoom = v.coerceAtLeast(1f) }
    fun release() = p.release()
}

class ZoomProcessor : SurfaceProcessor, SurfaceTexture.OnFrameAvailableListener {
    @Volatile var zoom = 1f
    private val thread = HandlerThread("luma-gl").apply { start() }
    private val handler = Handler(thread.looper)
    val executor = Executor { handler.post(it) }
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer = EGL14.EGL_NO_SURFACE
    private var program = 0; private var texture = 0
    private var aPos = 0; private var aTex = 0; private var uTex = 0
    private val outputs = LinkedHashMap<SurfaceOutput, EGLSurface>()
    private var input: SurfaceTexture? = null
    private val st = FloatArray(16); private val out = FloatArray(16); private val zoomM = FloatArray(16); private val final = FloatArray(16)
    private val quad: FloatBuffer = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val uv: FloatBuffer = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    private var released = false

    private fun floats(vararg v: Float) = ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(v); position(0) }

    private fun setup() {
        if (display != EGL14.EGL_NO_DISPLAY) return
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2); check(EGL14.eglInitialize(display, v, 0, v, 1)) { "eglInitialize" }
        val attrs = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, 0x3142 /* EGL_RECORDABLE_ANDROID */, 1,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
        check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, n, 0) && n[0] > 0) { "eglChooseConfig" }
        config = configs[0]
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
        program = link(
            "attribute vec4 aPos; attribute vec4 aTex; uniform mat4 uTex; varying vec2 v; void main(){ gl_Position = aPos; v = (uTex * aTex).xy; }",
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES s; varying vec2 v; void main(){ gl_FragColor = texture2D(s, v); }")
        aPos = GLES20.glGetAttribLocation(program, "aPos"); aTex = GLES20.glGetAttribLocation(program, "aTex"); uTex = GLES20.glGetUniformLocation(program, "uTex")
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0); texture = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }
    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type); GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
        val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "shader: " + GLES20.glGetShaderInfoLog(s) }; return s
    }
    private fun link(vs: String, fs: String): Int {
        val p = GLES20.glCreateProgram(); GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs)); GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs)); GLES20.glLinkProgram(p)
        val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0); check(ok[0] != 0) { "link: " + GLES20.glGetProgramInfoLog(p) }; return p
    }

    override fun onInputSurface(request: SurfaceRequest) {
        if (released) { request.willNotProvideSurface(); return }
        setup()
        val texture = SurfaceTexture(this.texture).apply { setDefaultBufferSize(request.resolution.width, request.resolution.height) }
        val surface = Surface(texture)
        request.provideSurface(surface, executor) { texture.setOnFrameAvailableListener(null); if (input === texture) input = null; texture.release(); surface.release() }
        input = texture
        texture.setOnFrameAvailableListener(this, handler)
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        if (released) { surfaceOutput.close(); return }
        setup()
        val surface = surfaceOutput.getSurface(executor) {
            outputs.remove(surfaceOutput)?.let { EGL14.eglDestroySurface(display, it) }
            surfaceOutput.close()
        }
        outputs[surfaceOutput] = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
    }

    override fun onFrameAvailable(texture: SurfaceTexture) {
        if (released || texture !== input) return
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
        texture.updateTexImage(); texture.getTransformMatrix(st)
        // Zoom about the centre in texture space, applied before the camera/target transform.
        val z = 1f / zoom
        Matrix.setIdentityM(zoomM, 0); Matrix.translateM(zoomM, 0, 0.5f, 0.5f, 0f); Matrix.scaleM(zoomM, 0, z, z, 1f); Matrix.translateM(zoomM, 0, -0.5f, -0.5f, 0f)
        for ((o, egl) in outputs) {
            runCatching {
                EGL14.eglMakeCurrent(display, egl, egl, context)
                GLES20.glViewport(0, 0, o.size.width, o.size.height)
                o.updateTransformMatrix(out, st)
                Matrix.multiplyMM(final, 0, out, 0, zoomM, 0)
                GLES20.glUseProgram(program)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, this.texture)
                GLES20.glUniformMatrix4fv(uTex, 1, false, final, 0)
                GLES20.glEnableVertexAttribArray(aPos); GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
                GLES20.glEnableVertexAttribArray(aTex); GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, uv)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                EGLExt.eglPresentationTimeANDROID(display, egl, texture.timestamp)
                EGL14.eglSwapBuffers(display, egl)
            }.onFailure { Log.w("Lumora", "zoom draw", it) }
        }
    }

    fun release() = handler.post {
        if (released) return@post
        released = true
        outputs.values.forEach { EGL14.eglDestroySurface(display, it) }; outputs.keys.forEach { runCatching { it.close() } }; outputs.clear()
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
            EGL14.eglDestroyContext(display, context); EGL14.eglTerminate(display)
        }
        thread.quitSafely()
    }
}
