package com.canonmulticam.pro

import com.canonmulticam.pro.gl.CubeLutParser
import com.canonmulticam.pro.gl.LumaHistogramAnalyzer
import com.canonmulticam.pro.gl.ShaderToolFlags
import com.canonmulticam.pro.gl.ShaderVariantCompiler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer

class GraphicsMathProofTest {

    @Test
    fun `prove CubeLutParser parses valid 2x2x2 3D LUT and LumaHistogramAnalyzer normalizes bins`() {
        val cubeText = """
            TITLE "Test LUT"
            LUT_3D_SIZE 2
            0.0 0.0 0.0
            1.0 0.0 0.0
            0.0 1.0 0.0
            1.0 1.0 0.0
            0.0 0.0 1.0
            1.0 0.0 1.0
            0.0 1.0 1.0
            1.0 1.0 1.0
        """.trimIndent()

        val lut = CubeLutParser.parse(ByteArrayInputStream(cubeText.toByteArray())).getOrThrow()
        assertEquals(2, lut.size)
        assertEquals(24, lut.buffer.capacity())

        val rgba = ByteBuffer.allocate(64 * 4)
        for (i in 0 until 64) {
            rgba.put(255.toByte())
            rgba.put(255.toByte())
            rgba.put(255.toByte())
            rgba.put(255.toByte())
        }
        rgba.flip()

        val bins = LumaHistogramAnalyzer.computeNormalizedBins(rgba, 8, 8)
        assertEquals(64, bins.size)
        assertEquals(1.0f, bins[63], 0.001f)
    }

    @Test
    fun `prove ShaderVariantCompiler injects all cinematic guide defines cleanly`() {
        val flags = ShaderToolFlags.LUT or
            ShaderToolFlags.PEAKING or
            ShaderToolFlags.GUIDE_THIRDS or
            ShaderToolFlags.GUIDE_9_16
        val glsl = ShaderVariantCompiler.buildFragmentShader(flags)
        assertTrue(glsl.contains("#define USE_LUT"))
        assertTrue(glsl.contains("#define USE_PEAKING"))
        assertTrue(glsl.contains("#define USE_GUIDE_THIRDS"))
        assertTrue(glsl.contains("#define USE_GUIDE_9_16"))
    }

    @Test
    fun `prove ShaderVariantCompiler injects dynamic uZebraThreshold uniform`() {
        val glsl = ShaderVariantCompiler.buildFragmentShader(ShaderToolFlags.ZEBRA)
        assertTrue(glsl.contains("#define USE_ZEBRA"))
        assertTrue(glsl.contains("uniform float uZebraThreshold;"))
        assertTrue(glsl.contains("rawLuma >= uZebraThreshold"))
    }
}
