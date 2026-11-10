package com.example.videocam

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Off-screen GLES2 renderer for still-photo pixel effects.
 *
 * ML Kit segmentation and JPEG encoding remain on the CPU. Full-resolution
 * compositing and background grading run in a GPU fragment shader. This class
 * is only called by the still-photo pipeline; video rendering is untouched.
 */
internal object PhotoGpuProcessor {
    private const val VERTEX_SHADER = """
        attribute vec2 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = vec4(aPosition, 0.0, 1.0);
            vTexCoord = aTexCoord;
        }
    """

    private const val FRAGMENT_SHADER = """
        precision highp float;
        varying vec2 vTexCoord;
        uniform sampler2D uSource;
        uniform sampler2D uMask;
        uniform sampler2D uBlur;
        uniform int uMode;
        uniform float uHue;
        uniform float uSaturation;
        uniform float uWarmth;
        uniform vec4 uM0;
        uniform vec4 uM1;
        uniform vec4 uM2;
        uniform vec4 uM3;
        uniform vec4 uOffset;

        vec3 rgbToHsv(vec3 c) {
            vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
            vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
            vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
            float d = q.x - min(q.w, q.y);
            float e = 1.0e-10;
            return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
        }
        vec3 hsvToRgb(vec3 c) {
            vec3 p = abs(fract(c.xxx + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
            return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
        }
        void main() {
            vec4 original = texture2D(uSource, vTexCoord);
            float confidence = texture2D(uMask, vTexCoord).r;
            float t = clamp((confidence - 0.16) / 0.68, 0.0, 1.0);
            float foreground = t * t * (3.0 - 2.0 * t);
            if (uMode == 0) {
                vec3 softened = texture2D(uBlur, vTexCoord).rgb;
                gl_FragColor = vec4(mix(softened, original.rgb, foreground), original.a);
            } else if (uMode == 1) {
                vec3 hsv = rgbToHsv(original.rgb);
                hsv.x = fract(hsv.x + uHue);
                hsv.y = clamp(hsv.y * uSaturation, 0.0, 1.0);
                vec3 shifted = hsvToRgb(hsv);
                shifted.r = clamp(shifted.r + uWarmth, 0.0, 1.0);
                shifted.b = clamp(shifted.b - uWarmth, 0.0, 1.0);
                gl_FragColor = vec4(mix(shifted, original.rgb, foreground), original.a);
            } else if (uMode == 2) {
                vec4 over = texture2D(uBlur, vTexCoord);
                gl_FragColor = vec4(over.rgb + original.rgb * (1.0 - over.a), original.a);
            } else {
                vec4 c = original;
                vec4 graded = vec4(dot(uM0, c), dot(uM1, c), dot(uM2, c), dot(uM3, c)) + uOffset;
                gl_FragColor = clamp(graded, 0.0, 1.0);
            }
        }
    """

    private val quad = floatArrayOf(
        -1f, -1f, 0f, 0f,
         1f, -1f, 1f, 0f,
        -1f,  1f, 0f, 1f,
         1f,  1f, 1f, 1f
    )

    fun blendPortrait(source: Bitmap, blurred: Bitmap, mask: FloatArray, maskWidth: Int, maskHeight: Int): Bitmap? =
        render(source, blurred, mask, maskWidth, maskHeight, 0, 0f, 1f, 0f)

    fun shiftBackground(
        source: Bitmap,
        mask: FloatArray,
        maskWidth: Int,
        maskHeight: Int,
        hueDegrees: Int,
        saturationPercent: Int,
        temperature: Int
    ): Bitmap? = render(
        source, null, mask, maskWidth, maskHeight, 1,
        hueDegrees.coerceIn(-180, 180) / 360f,
        saturationPercent.coerceIn(0, 200) / 100f,
        temperature.coerceIn(-100, 100) * 0.22f / 255f
    )

    fun compositeOverlay(source: Bitmap, overlay: Bitmap): Bitmap? =
        render(source, overlay, FloatArray(1), 1, 1, 2, 0f, 1f, 0f)

    fun applyColorMatrix(source: Bitmap, values: FloatArray): Bitmap? {
        if (values.size < 20) return null
        return render(source, null, FloatArray(1), 1, 1, 3, 0f, 1f, 0f, values)
    }

    private fun render(
        source: Bitmap,
        blurred: Bitmap?,
        mask: FloatArray,
        maskWidth: Int,
        maskHeight: Int,
        mode: Int,
        hue: Float,
        saturation: Float,
        warmth: Float,
        matrixValues: FloatArray? = null
    ): Bitmap? {
        if (source.isRecycled ||
            (mode == 0 && (blurred == null || blurred.isRecycled ||
                blurred.width != source.width || blurred.height != source.height)) ||
            (mode == 2 && (blurred == null || blurred.isRecycled ||
                blurred.width != source.width || blurred.height != source.height)) ||
            (mode == 3 && (matrixValues == null || matrixValues.size < 20)) ||
            (mode <= 1 && (maskWidth < 1 || maskHeight < 1 || mask.size < maskWidth * maskHeight))
        ) return null

        val width = source.width
        val height = source.height
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        var program = 0
        var framebuffer = 0
        val textures = IntArray(3)
        var maskBitmap: Bitmap? = null
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return null
            val versions = IntArray(2)
            if (!EGL14.eglInitialize(display, versions, 0, versions, 1)) return null

            val configAttributes = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val configCount = IntArray(1)
            if (!EGL14.eglChooseConfig(
                    display, configAttributes, 0, configs, 0, 1, configCount, 0
                ) || configCount[0] == 0
            ) return null

            val contextAttributes = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE
            )
            context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttributes, 0
            )
            val surfaceAttributes = intArrayOf(
                EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE
            )
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfaceAttributes, 0)
            if (context == EGL14.EGL_NO_CONTEXT || surface == EGL14.EGL_NO_SURFACE ||
                !EGL14.eglMakeCurrent(display, surface, surface, context)
            ) return null

            val maxTexture = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTexture, 0)
            if (width > maxTexture[0] || height > maxTexture[0]) return null

            if (mode <= 1) {
                val maskPixels = IntArray(maskWidth * maskHeight)
                for (i in maskPixels.indices) {
                    val v = (mask[i].coerceIn(0f, 1f) * 255f).toInt()
                    maskPixels[i] = Color.rgb(v, v, v)
                }
                maskBitmap = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888)
                maskBitmap.setPixels(maskPixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)
            } else {
                maskBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            }

            program = linkProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            if (program == 0) return null
            GLES20.glGenTextures(3, textures, 0)
            uploadTexture(textures[0], source)
            uploadTexture(textures[1], maskBitmap)
            uploadTexture(textures[2], blurred ?: source)

            val outputTexture = IntArray(1)
            GLES20.glGenTextures(1, outputTexture, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, outputTexture[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
            )

            val fb = IntArray(1)
            GLES20.glGenFramebuffers(1, fb, 0)
            framebuffer = fb[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, outputTexture[0], 0
            )
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                GLES20.glDeleteTextures(1, outputTexture, 0)
                return null
            }

            GLES20.glViewport(0, 0, width, height)
            GLES20.glUseProgram(program)
            bindSampler(program, "uSource", textures[0], 0)
            bindSampler(program, "uMask", textures[1], 1)
            bindSampler(program, "uBlur", textures[2], 2)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uMode"), mode)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uHue"), hue)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uSaturation"), saturation)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uWarmth"), warmth)
            if (mode == 3 && matrixValues != null) {
                GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, "uM0"), 1, matrixValues, 0)
                GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, "uM1"), 1, matrixValues, 5)
                GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, "uM2"), 1, matrixValues, 10)
                GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, "uM3"), 1, matrixValues, 15)
                GLES20.glUniform4f(
                    GLES20.glGetUniformLocation(program, "uOffset"),
                    matrixValues[4] / 255f, matrixValues[9] / 255f,
                    matrixValues[14] / 255f, matrixValues[19]
                )
            }

            val vertexBuffer = ByteBuffer.allocateDirect(quad.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            vertexBuffer.put(quad).position(0)
            val position = GLES20.glGetAttribLocation(program, "aPosition")
            val texCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
            vertexBuffer.position(2)
            GLES20.glEnableVertexAttribArray(texCoord)
            GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            val bytes = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
            if (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
                GLES20.glDeleteTextures(1, outputTexture, 0)
                return null
            }

            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val row = IntArray(width)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val r = bytes.get().toInt() and 255
                    val g = bytes.get().toInt() and 255
                    val b = bytes.get().toInt() and 255
                    val a = bytes.get().toInt() and 255
                    row[x] = Color.argb(a, r, g, b)
                }
                output.setPixels(row, 0, width, 0, y, width, 1)
            }
            GLES20.glDeleteTextures(1, outputTexture, 0)
            return output
        } catch (_: Throwable) {
            return null
        } finally {
            maskBitmap?.recycle()
            if (framebuffer != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            if (textures.any { it != 0 }) GLES20.glDeleteTextures(3, textures, 0)
            if (program != 0) GLES20.glDeleteProgram(program)
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
        }
    }

    private fun uploadTexture(id: Int, bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    private fun bindSampler(program: Int, name: String, texture: Int, unit: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, name), unit)
    }

    private fun linkProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (vertex == 0 || fragment == 0) return 0
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        if (status[0] == 0) {
            GLES20.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }
}
