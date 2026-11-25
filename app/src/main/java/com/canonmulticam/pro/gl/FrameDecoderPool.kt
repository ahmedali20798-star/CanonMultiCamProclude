package com.canonmulticam.pro.gl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.canonmulticam.pro.domain.LatestFrameSlot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class DecodedVideoFrame(
    val width: Int,
    val height: Int,
    val rgbaBuffer: ByteBuffer
) {
    private val recycled = AtomicBoolean(false)
    fun recycle() {
        recycled.set(true)
    }
}

class FrameDecoderPool(
    private val onFrameDecoded: (Int, DecodedVideoFrame) -> Unit,
    private val onHistogramUpdated: (Int, FloatArray) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val channels = List(4) { Channel<LatestFrameSlot>(Channel.CONFLATED) }
    private val lastSeqs = LongArray(4)
    private val histCounter = IntArray(4)
    @Volatile var activeSoloSlot: Int? = null
    @Volatile var activeSelectedSlot: Int = 0

    init {
        for (slotIdx in 0..3) {
            scope.launch {
                val tempStorage = ByteArray(64 * 1024)
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inTempStorage = tempStorage
                }
                var reusableBitmap: Bitmap? = null
                val doubleBuffers = arrayOfNulls<ByteBuffer>(2)
                var pingPong = 0
                var lastDecodeStartMs = 0L

                try {
                    for (slot in channels[slotIdx]) {
                        val solo = activeSoloSlot
                        if (solo != null && solo != slotIdx) {
                            continue
                        }

                        val minIntervalMs = if (solo == slotIdx) 28L else 42L
                        val elapsed = SystemClock.elapsedRealtime() - lastDecodeStartMs
                        if (elapsed < minIntervalMs) {
                            delay(minIntervalMs - elapsed)
                        }
                        lastDecodeStartMs = SystemClock.elapsedRealtime()

                        slot.readLatestFrame(lastSeqs[slotIdx]) { bytes, len, seq ->
                            lastSeqs[slotIdx] = seq
                            val targetSample = if (solo == slotIdx) 1 else 2
                            if (opts.inSampleSize != targetSample) {
                                opts.inSampleSize = targetSample
                                reusableBitmap?.recycle()
                                reusableBitmap = null
                            }
                            opts.inBitmap = reusableBitmap
                            opts.inMutable = true

                            val bmp = runCatching {
                                BitmapFactory.decodeByteArray(bytes, 0, len, opts)
                            }.getOrElse {
                                reusableBitmap?.recycle()
                                reusableBitmap = null
                                opts.inBitmap = null
                                runCatching {
                                    BitmapFactory.decodeByteArray(bytes, 0, len, opts)
                                }.getOrNull()
                            } ?: return@readLatestFrame

                            reusableBitmap = bmp
                            val neededBytes = bmp.byteCount
                            val bufIdx = pingPong and 1
                            pingPong++

                            val buf = doubleBuffers[bufIdx]?.takeIf { it.capacity() >= neededBytes }
                                ?: ByteBuffer.allocateDirect(neededBytes)
                                    .order(ByteOrder.nativeOrder())
                                    .also { doubleBuffers[bufIdx] = it }

                            buf.clear()
                            buf.limit(neededBytes)
                            bmp.copyPixelsToBuffer(buf)
                            buf.position(0)

                            if (activeSelectedSlot == slotIdx && histCounter[slotIdx]++ % 4 == 0) {
                                val bins = LumaHistogramAnalyzer.computeNormalizedBins(
                                    buf,
                                    bmp.width,
                                    bmp.height
                                )
                                onHistogramUpdated(slotIdx, bins)
                            }

                            onFrameDecoded(slotIdx, DecodedVideoFrame(bmp.width, bmp.height, buf))
                        }
                    }
                } finally {
                    reusableBitmap?.recycle()
                    reusableBitmap = null
                }
            }
        }
    }

    fun submitEncodedFrame(slotIndex: Int, slot: LatestFrameSlot) {
        if (slotIndex in 0..3) {
            channels[slotIndex].trySend(slot)
        }
    }

    fun destroy() {
        channels.forEach { it.close() }
        scope.cancel()
    }
}
