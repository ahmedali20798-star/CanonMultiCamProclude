package com.canonmulticam.pro.gl

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class CubeLutData(
    val size: Int,
    val buffer: ByteBuffer
)

object CubeLutParser {
    fun parse(inputStream: InputStream): Result<CubeLutData> = runCatching {
        var lutSize = 0
        val values = ArrayList<Float>(33 * 33 * 33 * 3)

        BufferedReader(InputStreamReader(inputStream)).useLines { lines ->
            lines.forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") ||
                    line.startsWith("TITLE") || line.startsWith("DOMAIN_")
                ) {
                    return@forEach
                }
                if (line.startsWith("LUT_3D_SIZE")) {
                    lutSize = line.substringAfter("LUT_3D_SIZE").trim().toInt()
                    return@forEach
                }
                val parts = line.split(Regex("\\s+"))
                if (parts.size >= 3) {
                    val r = parts[0].toFloatOrNull()
                    val g = parts[1].toFloatOrNull()
                    val b = parts[2].toFloatOrNull()
                    if (r != null && g != null && b != null) {
                        values.add(r.coerceIn(0f, 1f))
                        values.add(g.coerceIn(0f, 1f))
                        values.add(b.coerceIn(0f, 1f))
                    }
                }
            }
        }

        val expectedFloats = lutSize * lutSize * lutSize * 3
        check(lutSize in 2..65 && values.size == expectedFloats) {
            "Invalid .cube LUT: size=$lutSize, entries=${values.size / 3}"
        }

        val byteBuf = ByteBuffer.allocateDirect(expectedFloats).order(ByteOrder.nativeOrder())
        for (v in values) {
            byteBuf.put((v * 255f + 0.5f).toInt().coerceIn(0, 255).toByte())
        }
        byteBuf.position(0)
        CubeLutData(lutSize, byteBuf)
    }
}
