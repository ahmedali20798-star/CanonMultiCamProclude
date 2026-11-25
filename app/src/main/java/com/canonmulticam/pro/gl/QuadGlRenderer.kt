package com.canonmulticam.pro.gl

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.SystemClock
import com.canonmulticam.pro.domain.AnamorphicRatio
import com.canonmulticam.pro.domain.PeakingColor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicReferenceArray
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

@Suppress("TooManyFunctions")
class QuadGlRenderer : GLSurfaceView.Renderer {

    private val frameTextures = IntArray(4)
    private val lutTextures = IntArray(4)
    private val textureSizes = Array(4) { IntArray(2) }
    private val hasFrame = BooleanArray(4)
    private val hasLut = BooleanArray(4)

    private val pendingFrames = AtomicReferenceArray<DecodedVideoFrame?>(4)
    private val pendingLuts = AtomicReferenceArray<CubeLutData?>(4)
    private val clearSlotFlags = AtomicBooleanArray(4)

    private val slotToolMasks = AtomicIntegerArray(4)
    private val slotAnamorphic = AtomicReferenceArray<AnamorphicRatio>(4).apply {
        for (i in 0..3) set(i, AnamorphicRatio.NONE)
    }

    private val programCache = ConcurrentHashMap<Int, Int>()
    private var viewportWidth = 1920
    private var viewportHeight = 1080
    private val startTimeMs = SystemClock.elapsedRealtime()

    @Volatile var soloSlot: Int? = null
    @Volatile var bypassAllLuts: Boolean = false
    @Volatile var peakingColor: PeakingColor = PeakingColor.RED
    @Volatile var zebraThreshold: Float = 0.92f

    private var vaoId = 0
    private var vboId = 0

    private val quadVertices: FloatBuffer = ByteBuffer.allocateDirect(16 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(
                floatArrayOf(
                    -1f, -1f, 0f, 1f,
                    1f, -1f, 1f, 1f,
                    -1f, 1f, 0f, 0f,
                    1f, 1f, 1f, 0f
                )
            )
            position(0)
        }

    fun submitDecodedFrame(slotIndex: Int, frame: DecodedVideoFrame) {
        if (slotIndex in 0..3) {
            pendingFrames.getAndSet(slotIndex, frame)?.recycle()
        } else {
            frame.recycle()
        }
    }

    fun clearSlotFrame(slotIndex: Int) {
        if (slotIndex in 0..3) {
            pendingFrames.getAndSet(slotIndex, null)?.recycle()
            clearSlotFlags.set(slotIndex, true)
        }
    }

    fun setSlotCubeLut(slotIndex: Int, lut: CubeLutData) {
        if (slotIndex in 0..3) {
            pendingLuts.set(slotIndex, lut)
        }
    }

    fun setSlotTools(slotIndex: Int, mask: Int, anamorphic: AnamorphicRatio) {
        if (slotIndex in 0..3) {
            slotToolMasks.set(slotIndex, mask)
            slotAnamorphic.set(slotIndex, anamorphic)
        }
    }

    fun mapCellTouchToSensor(
        localX: Float,
        localY: Float,
        cellWidth: Float,
        cellHeight: Float,
        slotIndex: Int
    ): Pair<Float, Float>? {
        if (cellWidth <= 0f || cellHeight <= 0f || slotIndex !in 0..3) return null
        var u = (localX / cellWidth).coerceIn(0f, 1f)
        val v = (localY / cellHeight).coerceIn(0f, 1f)
        val squeeze = slotAnamorphic.get(slotIndex)?.squeezeFactor ?: 1.0f
        if (squeeze > 1.01f) {
            u = (u - 0.5f) / squeeze + 0.5f
            if (u < 0f || u > 1f) return null
        }
        return u to v
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.067f, 0.067f, 0.067f, 1.0f)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)

        val vaos = IntArray(1)
        val vbos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        GLES30.glGenBuffers(1, vbos, 0)
        vaoId = vaos[0]
        vboId = vbos[0]

        GLES30.glBindVertexArray(vaoId)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 16 * 4, quadVertices, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)
        GLES30.glBindVertexArray(0)

        GLES30.glGenTextures(4, frameTextures, 0)
        GLES30.glGenTextures(4, lutTextures, 0)
        for (i in 0..3) {
            configure2DTexture(frameTextures[i])
            configure3DTexture(lutTextures[i])
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        for (i in 0..3) {
            if (clearSlotFlags.getAndSet(i, false)) {
                hasFrame[i] = false
            }
            pendingFrames.getAndSet(i, null)?.let { frame ->
                uploadFrameTexture(i, frame)
                frame.recycle()
            }
            pendingLuts.getAndSet(i, null)?.let { lut ->
                uploadLutTexture(i, lut)
            }
        }

        val elapsedSec = (SystemClock.elapsedRealtime() - startTimeMs) / 1000f
        val activeSolo = soloSlot
        if (activeSolo != null && activeSolo in 0..3) {
            drawSlot(activeSolo, 0, 0, viewportWidth, viewportHeight, elapsedSec)
        } else {
            val halfW = viewportWidth / 2
            val halfH = viewportHeight / 2
            drawSlot(0, 0, halfH, halfW, viewportHeight - halfH, elapsedSec)
            drawSlot(1, halfW, halfH, viewportWidth - halfW, viewportHeight - halfH, elapsedSec)
            drawSlot(2, 0, 0, halfW, halfH, elapsedSec)
            drawSlot(3, halfW, 0, viewportWidth - halfW, halfH, elapsedSec)
        }
    }

    private fun drawSlot(slot: Int, x: Int, y: Int, w: Int, h: Int, timeSec: Float) {
        if (!hasFrame[slot] || w <= 0 || h <= 0) return

        var flags = slotToolMasks.get(slot)
        if (!hasLut[slot]) {
            flags = flags and ShaderToolFlags.LUT.inv()
        }

        val program = getOrCompileProgram(flags)
        if (program == 0) return

        GLES30.glViewport(x, y, w, h)
        GLES30.glUseProgram(program)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTextures[slot])
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uFrameTex"), 0)

        if ((flags and ShaderToolFlags.LUT) != 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextures[slot])
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uLutTex"), 1)
        }

        val texW = textureSizes[slot][0].coerceAtLeast(1)
        val texH = textureSizes[slot][1].coerceAtLeast(1)
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(program, "uTexelSize"),
            1.0f / texW,
            1.0f / texH
        )
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uTimeSec"), timeSec)
        GLES30.glUniform1i(
            GLES30.glGetUniformLocation(program, "uBypassLut"),
            if (bypassAllLuts) 1 else 0
        )

        val pk = peakingColor
        GLES30.glUniform3f(
            GLES30.glGetUniformLocation(program, "uPeakingColor"),
            pk.r,
            pk.g,
            pk.b
        )

        val squeeze = slotAnamorphic.get(slot)?.squeezeFactor ?: 1.0f
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(program, "uAnamorphicSqueeze"),
            squeeze
        )

        val zebraLoc = GLES30.glGetUniformLocation(program, "uZebraThreshold")
        if (zebraLoc >= 0) {
            GLES30.glUniform1f(zebraLoc, zebraThreshold)
        }

        GLES30.glBindVertexArray(vaoId)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
    }

    private fun uploadFrameTexture(slot: Int, frame: DecodedVideoFrame) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTextures[slot])
        if (textureSizes[slot][0] != frame.width || textureSizes[slot][1] != frame.height) {
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D,
                0,
                GLES30.GL_RGBA,
                frame.width,
                frame.height,
                0,
                GLES30.GL_RGBA,
                GLES30.GL_UNSIGNED_BYTE,
                frame.rgbaBuffer
            )
            textureSizes[slot][0] = frame.width
            textureSizes[slot][1] = frame.height
        } else {
            GLES30.glTexSubImage2D(
                GLES30.GL_TEXTURE_2D,
                0,
                0,
                0,
                frame.width,
                frame.height,
                GLES30.GL_RGBA,
                GLES30.GL_UNSIGNED_BYTE,
                frame.rgbaBuffer
            )
        }
        hasFrame[slot] = true
    }

    private fun uploadLutTexture(slot: Int, lut: CubeLutData) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextures[slot])
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D,
            0,
            GLES30.GL_RGB8,
            lut.size,
            lut.size,
            lut.size,
            0,
            GLES30.GL_RGB,
            GLES30.GL_UNSIGNED_BYTE,
            lut.buffer
        )
        hasLut[slot] = true
    }

    private fun getOrCompileProgram(flags: Int): Int {
        programCache[flags]?.let { return it }
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, ShaderVariantCompiler.VERTEX_SHADER)
        val fs = compileShader(
            GLES30.GL_FRAGMENT_SHADER,
            ShaderVariantCompiler.buildFragmentShader(flags)
        )
        if (vs == 0 || fs == 0) return 0

        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)

        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            GLES30.glDeleteProgram(prog)
            return 0
        }
        programCache[flags] = prog
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun configure2DTexture(texId: Int) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
    }

    private fun configure3DTexture(texId: Int) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, texId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_3D,
            GLES30.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_3D,
            GLES30.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_3D,
            GLES30.GL_TEXTURE_WRAP_R,
            GLES20.GL_CLAMP_TO_EDGE
        )
    }

    fun releaseGlResources() {
        for (i in 0..3) {
            pendingFrames.getAndSet(i, null)?.recycle()
            hasFrame[i] = false
        }
        programCache.values.forEach { GLES30.glDeleteProgram(it) }
        programCache.clear()
        GLES30.glDeleteTextures(4, frameTextures, 0)
        GLES30.glDeleteTextures(4, lutTextures, 0)
        if (vaoId != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vaoId), 0)
        if (vboId != 0) GLES30.glDeleteBuffers(1, intArrayOf(vboId), 0)
    }
}

private class AtomicBooleanArray(size: Int) {
    private val flags = Array(size) { AtomicBoolean(false) }
    fun set(index: Int, value: Boolean) = flags[index].set(value)
    fun getAndSet(index: Int, newValue: Boolean): Boolean = flags[index].getAndSet(newValue)
}
