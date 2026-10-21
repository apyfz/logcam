package com.apyfz.logcam.gl

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import com.apyfz.logcam.color.LutData
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Draws the camera preview through a monitoring LUT.
 *
 * This is a display-only transform, exactly like a monitor LUT on a cinema camera: the encoder is
 * fed straight from the ISP and never sees any of this. Framing a log image is otherwise guesswork,
 * because the flat picture on screen is not what the footage will look like graded.
 *
 * Samples a supplied .cube directly in the selected profile's preview space. There is no
 * implicit Apple Log decode, gamut conversion, or guarantee that the LUT matches this camera.
 */
class MonitorRenderer(
    private val onSurfaceReady: (Surface) -> Unit,
) : GLSurfaceView.Renderer {

    /** Mirrors GL errors somewhere readable, since MagicOS filters third-party logcat. */
    var diagnostics: ((String) -> Unit)? = null

    private fun report(line: String) {
        Log.e(TAG, line)
        diagnostics?.invoke(line)
    }

    private var program = 0
    private var oesTexture = 0
    private var lutTexture = 0
    private var surfaceTexture: SurfaceTexture? = null

    private val transform = FloatArray(16)
    private val rotation = FloatArray(16)
    private val textureRotation = FloatArray(16)
    private val combined = FloatArray(16)

    /**
     * How far to rotate the sampled image so it appears upright.
     *
     * The sensor is mounted at a fixed angle to the body, so the camera always delivers frames in
     * its own orientation regardless of how the phone is held. A SurfaceView fed directly by
     * Camera2 gets this applied for it; a GL pipeline sampling the texture has to do it itself,
     * which is why the preview came out sideways once the shader was introduced.
     */
    @Volatile
    var rotationDegrees: Int = 0
    @Volatile
    var displayRotationDegrees: Int = 0
    private lateinit var vertices: FloatBuffer

    @Volatile
    private var pendingLut: LutData? = null

    @Volatile
    private var lutSize = 0

    /** Whether the bound cube is the user's, as opposed to the identity placeholder. */
    @Volatile
    private var hasUserLut = false

    @Volatile
    var enabled: Boolean = true


    /** Queues a LUT for upload on the GL thread. Null clears it. */
    fun setLut(lut: LutData?) {
        pendingLut = lut ?: identityLut()
        hasUserLut = lut != null
    }

    /** A 2x2x2 pass-through cube, used to keep the sampler bound when no LUT is loaded. */
    private fun identityLut(): LutData {
        val n = 2
        val rgb = FloatArray(n * n * n * 3)
        var i = 0
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            rgb[i++] = r.toFloat(); rgb[i++] = g.toFloat(); rgb[i++] = b.toFloat()
        }
        return LutData(size = n, rgb = rgb)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        vertices = ByteBuffer.allocateDirect(QUAD.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(QUAD); position(0) }

        program = buildProgram()

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        oesTexture = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE,
        )

        // Every sampler a shader declares must have a complete texture bound at draw time, even one
        // that a branch never reaches — otherwise the draw fails with GL_INVALID_OPERATION and
        // nothing appears. So the LUT unit always holds at least an identity cube.
        uploadLut(identityLut())
        hasUserLut = false

        // A rebuilt GL context means the old texture is dead; releasing it here stops each surface
        // recreation leaking one.
        surfaceTexture?.release()

        surfaceTexture = SurfaceTexture(oesTexture).also {
            it.setDefaultBufferSize(PREVIEW_WIDTH, PREVIEW_HEIGHT)
            onSurfaceReady(Surface(it))
        }
    }

    private var viewWidth = 1
    private var viewHeight = 1

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewWidth = width.coerceAtLeast(1)
        viewHeight = height.coerceAtLeast(1)
    }

    /**
     * Scales the quad so the frame fits inside the view without distortion.
     *
     * Doing this in GL rather than by constraining the view's aspect ratio keeps one source of
     * truth: rotation changes which way round the frame's 16:9 sits, and the letterboxing has to
     * follow that. Fixing an aspect ratio on the Compose side cannot know about the rotation and
     * ends up stretching or cropping.
     */
    private fun fitScale(): FloatArray {
        val contentAspect = PREVIEW_WIDTH.toFloat() / PREVIEW_HEIGHT
        val viewAspect = viewWidth.toFloat() / viewHeight
        // Fit the image after rotation, then invert the two axes because the vertex quad is
        // rotated after this scale is applied.
        val displayedAspect =
            if (rotationDegrees % 180 != 0) 1f / contentAspect else contentAspect
        val displayed = if (displayedAspect > viewAspect) {
            floatArrayOf(1f, viewAspect / displayedAspect)
        } else {
            floatArrayOf(displayedAspect / viewAspect, 1f)
        }
        return if (rotationDegrees % 180 != 0) {
            floatArrayOf(displayed[1], displayed[0])
        } else displayed
    }

    override fun onDrawFrame(gl: GL10?) {
        val texture = surfaceTexture ?: return
        texture.updateTexImage()
        texture.getTransformMatrix(transform)

        // Keep the camera texture in its native 16:9 coordinates and rotate the displayed quad.
        // Rotating UVs leaves the quad landscape-shaped and compresses it when the view is
        // portrait; rotating geometry swaps the fitted dimensions without distorting the frame.
        android.opengl.Matrix.setIdentityM(rotation, 0)
        // GL's positive angle is counter-clockwise, while the back-camera correction is expressed
        // clockwise from the user's point of view.
        android.opengl.Matrix.rotateM(rotation, 0, -rotationDegrees.toFloat(), 0f, 0f, 1f)
        android.opengl.Matrix.setIdentityM(textureRotation, 0)
        android.opengl.Matrix.translateM(textureRotation, 0, 0.5f, 0.5f, 0f)
        android.opengl.Matrix.rotateM(
            textureRotation, 0,
            (rotationDegrees + displayRotationDegrees).toFloat(), 0f, 0f, 1f,
        )
        android.opengl.Matrix.translateM(textureRotation, 0, -0.5f, -0.5f, 0f)
        android.opengl.Matrix.multiplyMM(combined, 0, textureRotation, 0, transform, 0)

        pendingLut?.let { uploadLut(it); pendingLut = null }

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uCamera"), 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uLut"), 1)

        GLES20.glUniformMatrix4fv(
            GLES20.glGetUniformLocation(program, "uTransform"), 1, false, combined, 0,
        )
        GLES20.glUniformMatrix4fv(
            GLES20.glGetUniformLocation(program, "uRotation"), 1, false, rotation, 0,
        )
        GLES20.glUniform1i(
            GLES20.glGetUniformLocation(program, "uLutEnabled"),
            if (enabled && hasUserLut && lutSize > 0) 1 else 0,
        )
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uLutSize"), lutSize.toFloat())
        val fit = fitScale()
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uFit"), fit[0], fit[1])

        val position = GLES20.glGetAttribLocation(program, "aPosition")
        vertices.position(0)
        GLES20.glVertexAttribPointer(position, 4, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
    }

    /** Uploads a parsed cube as an RGB 3D texture with hardware trilinear interpolation. */
    private fun uploadLut(lut: LutData) {
        if (lutTexture == 0) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            lutTexture = ids[0]
        }
        val n = lut.size
        val buffer = ByteBuffer.allocateDirect(n * n * n * 3 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(lut.rgb).position(0)

        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES20.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F, n, n, n, 0,
            GLES20.GL_RGB, GLES20.GL_FLOAT, buffer,
        )
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            report("LUT upload failed 0x${error.toString(16)}")
            lutSize = 0
        } else {
            lutSize = n
        }
    }

    private fun buildProgram(): Int {

        val fragment = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float;

            uniform samplerExternalOES uCamera;
            uniform sampler3D uLut;
            uniform int   uLutEnabled;
            uniform float uLutSize;

            in vec2 vTex;
            out vec4 outColor;

            vec3 sampleLut(vec3 c) {
              // Texel-centre offsets, so the endpoints of the cube land exactly rather than being
              // pulled inward by half a texel.
              vec3 uvw = (clamp(c, 0.0, 1.0) * (uLutSize - 1.0) + 0.5) / uLutSize;
              return texture(uLut, uvw).rgb;
            }

            void main() {
              vec3 c = texture(uCamera, vTex).rgb;

              if (uLutEnabled == 1) {
                // A .cube declares no reliable camera input profile. Apply it directly to the
                // selected capture's preview samples, without an invented gamut conversion.
                c = sampleLut(c);
              }

              outColor = vec4(c, 1.0);
            }
        """.trimIndent()

        val vertex = """
            #version 300 es
            in vec4 aPosition;
            uniform mat4 uTransform;
            uniform mat4 uRotation;
            uniform vec2 uFit;
            out vec2 vTex;
            void main() {
              gl_Position = uRotation * vec4(aPosition.xy * uFit, 0.0, 1.0);
              vTex = (uTransform * vec4(aPosition.zw, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) report("link failed: ${GLES20.glGetProgramInfoLog(program)}")

        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) report("compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
        return shader
    }

    companion object {
        private const val TAG = "MonitorRenderer"
        const val PREVIEW_WIDTH = 1920
        const val PREVIEW_HEIGHT = 1080

        /** Full-screen triangle strip: clip-space xy followed by texture-space uv. */
        private val QUAD = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )
    }
}
