package com.canonmulticam.pro.gl

import java.nio.ByteBuffer

object LumaHistogramAnalyzer {
    fun computeNormalizedBins(rgbaBuffer: ByteBuffer, width: Int, height: Int): FloatArray {
        val bins = FloatArray(64)
        val totalPixels = width * height
        if (totalPixels <= 0) return bins

        val dup = rgbaBuffer.duplicate()
        dup.position(0)
        val limit = dup.limit().coerceAtMost(totalPixels * 4)
        var maxCount = 1f

        var i = 0
        while (i + 3 < limit) {
            val r = dup.get(i).toInt() and 0xFF
            val g = dup.get(i + 1).toInt() and 0xFF
            val b = dup.get(i + 2).toInt() and 0xFF
            val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b).toInt().coerceIn(0, 255)
            val binIdx = luma shr 2
            bins[binIdx] += 1f
            if (bins[binIdx] > maxCount) maxCount = bins[binIdx]
            i += 16
        }

        for (b in 0 until 64) {
            bins[b] = (bins[b] / maxCount).coerceIn(0f, 1f)
        }
        return bins
    }
}
