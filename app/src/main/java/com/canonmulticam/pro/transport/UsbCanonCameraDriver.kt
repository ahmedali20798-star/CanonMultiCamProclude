package com.canonmulticam.pro.transport

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.canonmulticam.pro.domain.CameraHandshakeInfo
import com.canonmulticam.pro.domain.ExposureSettings
import com.canonmulticam.pro.domain.LatestFrameSlot
import com.canonmulticam.pro.domain.ProtocolType
import com.canonmulticam.pro.domain.StreamQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

@Suppress("TooManyFunctions")
class UsbCanonCameraDriver(
    context: Context,
    private val device: UsbDevice
) : ICameraDriver {

    override val ipAddress: String = "usb://${device.deviceName}"

    private val usbManager = context.applicationContext
        .getSystemService(Context.USB_SERVICE) as UsbManager
    private val busMutex = Mutex()
    private val priorityPending = AtomicBoolean(false)
    private val txCounter = AtomicInteger(1)

    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var bulkOut: UsbEndpoint? = null
    private var bulkIn: UsbEndpoint? = null

    private val packetScratch = ByteArray(256 * 1024)
    private var frameScratchBuffer = ByteArray(1536 * 1024)
    @Volatile private var lastKnownRecordingState = false
    @Volatile private var cachedIso = 800
    @Volatile private var cachedKelvin = 5600

    companion object {
        fun isoToCanonCode(iso: Int): Int = when (iso) {
            100 -> 0x48
            125 -> 0x4B
            160 -> 0x4D
            200 -> 0x50
            250 -> 0x53
            320 -> 0x55
            400 -> 0x58
            500 -> 0x5B
            640 -> 0x5D
            800 -> 0x60
            1000 -> 0x63
            1250 -> 0x65
            1600 -> 0x68
            2000 -> 0x6B
            2500 -> 0x6D
            3200 -> 0x70
            4000 -> 0x73
            5000 -> 0x75
            6400 -> 0x78
            8000 -> 0x7B
            10000 -> 0x7D
            12800 -> 0x80
            else -> 0x60
        }

        private const val TYPE_COMMAND = 0x0001
        private const val TYPE_DATA = 0x0002
        private const val TYPE_RESPONSE = 0x0003

        private const val OP_OPEN_SESSION = 0x1002
        private const val OP_CLOSE_SESSION = 0x1003
        private const val OP_GET_DEVICE_PROP_VALUE = 0x1015
        private const val OP_EOS_SET_DEVICE_PROP = 0x9110
        private const val OP_EOS_SET_REMOTE_MODE = 0x9114
        private const val OP_EOS_SET_EVENT_MODE = 0x9115
        private const val OP_EOS_REMOTE_RELEASE_ON = 0x9128
        private const val OP_EOS_REMOTE_RELEASE_OFF = 0x9129
        private const val OP_EOS_GET_VIEWFINDER_DATA = 0x9153
        private const val OP_EOS_SET_TOUCH_AF = 0x915B

        private const val RESP_OK = 0x2001
        private const val RESP_DEVICE_PROP_NOT_SUPPORTED = 0x200A
        private const val RESP_DEVICE_BUSY = 0x2019
        private const val RESP_SESSION_ALREADY_OPEN = 0x201E

        private const val PROP_BATTERY_LEVEL = 0x5001
        private const val PROP_EOS_ISO = 0xD103
        private const val PROP_EOS_COLOR_TEMP = 0xD10A
        private const val PROP_EOS_EVF_OUTPUT = 0xD1B0
        private const val PROP_EOS_EVF_MODE = 0xD1B1
        private const val PROP_EOS_MOVIE_RECORD = 0xD1B8
    }

    private suspend fun <T> withPriorityBus(block: () -> T): T {
        priorityPending.set(true)
        return try {
            busMutex.withLock { block() }
        } finally {
            priorityPending.set(false)
        }
    }

    override suspend fun handshake(): Result<CameraHandshakeInfo> = withContext(Dispatchers.IO) {
        withPriorityBus {
            runCatching {
                close()
                val (iface, outEp, inEp) = findStillImageEndpoints(device)
                    ?: error("No PTP bulk endpoints found on USB device")

                val conn = usbManager.openDevice(device)
                    ?: error("Failed to open USB device connection")
                check(conn.claimInterface(iface, true)) { "Failed to claim USB PTP interface" }

                connection = conn
                usbInterface = iface
                bulkOut = outEp
                bulkIn = inEp
                txCounter.set(1)

                val openCode = executeSimpleOp(OP_OPEN_SESSION, intArrayOf(1))
                if (openCode == RESP_SESSION_ALREADY_OPEN) {
                    executeSimpleOp(OP_CLOSE_SESSION, intArrayOf(1))
                    val retry = executeSimpleOp(OP_OPEN_SESSION, intArrayOf(1))
                    check(retry == RESP_OK) { "USB OpenSession retry failed: 0x${retry.toString(16)}" }
                } else {
                    check(openCode == RESP_OK) { "USB OpenSession failed: 0x${openCode.toString(16)}" }
                }

                executeSimpleOp(OP_EOS_SET_REMOTE_MODE, intArrayOf(1))
                executeSimpleOp(OP_EOS_SET_EVENT_MODE, intArrayOf(1))
                setEosIntProperty(PROP_EOS_EVF_MODE, 1)
                setEosIntProperty(PROP_EOS_EVF_OUTPUT, 3)

                val serial = runCatching { device.serialNumber }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: "usb_${device.vendorId}_${device.productId}"
                val name = runCatching { device.productName }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: "Canon USB"

                CameraHandshakeInfo(
                    uuid = serial,
                    modelName = "$name (USB)",
                    protocol = ProtocolType.USB_PTP,
                    supportedApertures = emptyList()
                )
            }.onFailure { close() }
        }
    }

    override suspend fun setStreamQuality(quality: StreamQuality): Result<Unit> =
        withContext(Dispatchers.IO) {
            withPriorityBus {
                runCatching {
                    setEosIntProperty(PROP_EOS_EVF_OUTPUT, 3)
                    Unit
                }
            }
        }

    override suspend fun pullNextLiveFrame(slot: LatestFrameSlot): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (priorityPending.get()) return@withContext Result.success(Unit)
            busMutex.withLock {
                runCatching {
                    val txId = txCounter.getAndIncrement()
                    sendContainer(
                        TYPE_COMMAND, OP_EOS_GET_VIEWFINDER_DATA, txId, intArrayOf(0x00100000)
                    )

                    var frameLength = 0
                    while (true) {
                        val header = readUsbChunk(12)
                        val totalLen = header.getInt(0)
                        val containerType = header.getShort(4).toInt() and 0xFFFF
                        val payloadLen = (totalLen - 12).coerceAtLeast(0)

                        when (containerType) {
                            TYPE_DATA -> {
                                if (payloadLen > frameScratchBuffer.size &&
                                    payloadLen <= 4 * 1024 * 1024
                                ) {
                                    frameScratchBuffer = ByteArray(payloadLen + 256 * 1024)
                                }
                                if (payloadLen in 1..frameScratchBuffer.size) {
                                    readExactBytes(frameScratchBuffer, 0, payloadLen)
                                    frameLength = payloadLen
                                } else if (payloadLen > 0) {
                                    discardBytes(payloadLen)
                                }
                            }
                            TYPE_RESPONSE -> {
                                val respCode = header.getShort(6).toInt() and 0xFFFF
                                if (payloadLen > 0) discardBytes(payloadLen)
                                if (respCode == RESP_OK && frameLength > 512) {
                                    val jpegStart = findJpegStartOffset(frameScratchBuffer, frameLength)
                                    if (jpegStart >= 0) {
                                        val cleanLen = frameLength - jpegStart
                                        if (jpegStart > 0) {
                                            System.arraycopy(
                                                frameScratchBuffer, jpegStart,
                                                frameScratchBuffer, 0, cleanLen
                                            )
                                        }
                                        slot.writeFrame(frameScratchBuffer, cleanLen)
                                    }
                                } else if (respCode != RESP_OK && respCode != RESP_DEVICE_BUSY) {
                                    error("USB LiveView error: 0x${respCode.toString(16)}")
                                }
                                break
                            }
                            else -> if (payloadLen > 0) discardBytes(payloadLen)
                        }
                    }
                }
            }
        }

    override suspend fun setRecording(record: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        withPriorityBus {
            runCatching {
                val recVal = if (record) 4 else 0
                var code = RESP_DEVICE_BUSY
                repeat(3) { attempt ->
                    code = setEosIntProperty(PROP_EOS_MOVIE_RECORD, recVal)
                    if (code != RESP_DEVICE_BUSY) return@repeat
                    if (attempt < 2) delay(60L)
                }
                if (code == RESP_DEVICE_PROP_NOT_SUPPORTED) {
                    code = if (record) {
                        executeSimpleOp(OP_EOS_REMOTE_RELEASE_ON, intArrayOf(3, 0))
                    } else {
                        executeSimpleOp(OP_EOS_REMOTE_RELEASE_OFF, intArrayOf(3))
                    }
                }
                check(code == RESP_OK) { "USB REC failed: 0x${code.toString(16)}" }
                lastKnownRecordingState = record
            }
        }
    }

    override suspend fun queryActualRecordingState(): Result<Boolean> =
        Result.success(lastKnownRecordingState)

    override suspend fun triggerTouchAf(normX: Float, normY: Float): Result<Unit> =
        withContext(Dispatchers.IO) {
            withPriorityBus {
                runCatching {
                    val x = (normX.coerceIn(0f, 1f) * 10000).roundToInt()
                    val y = (normY.coerceIn(0f, 1f) * 10000).roundToInt()
                    val code = executeSimpleOp(OP_EOS_SET_TOUCH_AF, intArrayOf(x, y))
                    check(code == RESP_OK) { "USB TouchAF failed: 0x${code.toString(16)}" }
                }
            }
        }

    override suspend fun getCurrentExposure(): Result<ExposureSettings> =
        withContext(Dispatchers.IO) {
            if (priorityPending.get()) {
                return@withContext Result.success(
                    ExposureSettings("USB", "AUTO", cachedIso, cachedKelvin)
                )
            }
            busMutex.withLock {
                runCatching {
                    val rawIso = readIntDeviceProp(PROP_EOS_ISO)
                    if (rawIso != null) cachedIso = decodeCanonIsoCode(rawIso)
                    val rawKelvin = readIntDeviceProp(PROP_EOS_COLOR_TEMP)
                    if (rawKelvin != null && rawKelvin in 2000..12000) cachedKelvin = rawKelvin
                    ExposureSettings(
                        aperture = "USB",
                        shutterSpeed = "PTP",
                        iso = cachedIso,
                        kelvin = cachedKelvin
                    )
                }
            }
        }

    override suspend fun applyExposure(
        target: ExposureSettings,
        supportedApertures: List<String>,
        supportedShutters: List<String>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        withPriorityBus {
            runCatching {
                setEosIntProperty(PROP_EOS_COLOR_TEMP, target.kelvin)
                setEosIntProperty(PROP_EOS_ISO, mapIsoToCanonCode(target.iso))
                cachedKelvin = target.kelvin
                cachedIso = target.iso
                Unit
            }
        }
    }

    override suspend fun getBatteryStatus(): Result<Pair<String, Boolean>> =
        withContext(Dispatchers.IO) {
            if (priorityPending.get()) return@withContext Result.success("USB" to false)
            busMutex.withLock {
                runCatching {
                    val rawBat = readByteDeviceProp(PROP_BATTERY_LEVEL) ?: -1
                    if (rawBat in 0..100) "$rawBat%" to (rawBat <= 20)
                    else "USB" to false
                }
            }
        }

    override suspend fun getRemainingRecordMinutes(): Result<Int?> = Result.success(null)

    private fun readIntDeviceProp(propCode: Int): Int? {
        val txId = txCounter.getAndIncrement()
        sendContainer(TYPE_COMMAND, OP_GET_DEVICE_PROP_VALUE, txId, intArrayOf(propCode))
        var result: Int? = null
        while (true) {
            val header = readUsbChunk(12)
            val totalLen = header.getInt(0)
            val type = header.getShort(4).toInt() and 0xFFFF
            val payloadLen = (totalLen - 12).coerceAtLeast(0)
            if (type == TYPE_DATA && payloadLen >= 4) {
                val data = readUsbChunk(payloadLen)
                result = data.getInt(0)
            } else if (type == TYPE_RESPONSE) {
                if (payloadLen > 0) discardBytes(payloadLen)
                break
            } else if (payloadLen > 0) {
                discardBytes(payloadLen)
            }
        }
        return result
    }

    private fun readByteDeviceProp(propCode: Int): Int? {
        val txId = txCounter.getAndIncrement()
        sendContainer(TYPE_COMMAND, OP_GET_DEVICE_PROP_VALUE, txId, intArrayOf(propCode))
        var result: Int? = null
        while (true) {
            val header = readUsbChunk(12)
            val totalLen = header.getInt(0)
            val type = header.getShort(4).toInt() and 0xFFFF
            val payloadLen = (totalLen - 12).coerceAtLeast(0)
            if (type == TYPE_DATA && payloadLen >= 1) {
                val data = readUsbChunk(payloadLen)
                result = data.get(0).toInt() and 0xFF
            } else if (type == TYPE_RESPONSE) {
                if (payloadLen > 0) discardBytes(payloadLen)
                break
            } else if (payloadLen > 0) {
                discardBytes(payloadLen)
            }
        }
        return result
    }

    private fun setEosIntProperty(propCode: Int, value: Int): Int {
        val txId = txCounter.getAndIncrement()
        sendContainer(TYPE_COMMAND, OP_EOS_SET_DEVICE_PROP, txId, intArrayOf())

        val dataBuf = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(20)
            putShort(TYPE_DATA.toShort())
            putShort(OP_EOS_SET_DEVICE_PROP.toShort())
            putInt(txId)
            putInt(propCode)
            putInt(value)
        }
        writeBulk(dataBuf.array(), 20)
        return awaitUsbResponse()
    }

    private fun executeSimpleOp(opCode: Int, params: IntArray): Int {
        val txId = txCounter.getAndIncrement()
        sendContainer(TYPE_COMMAND, opCode, txId, params)
        return awaitUsbResponse()
    }

    private fun sendContainer(type: Int, code: Int, txId: Int, params: IntArray) {
        val len = 12 + params.size * 4
        val buf = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(len)
            putShort(type.toShort())
            putShort(code.toShort())
            putInt(txId)
            for (p in params) putInt(p)
        }
        writeBulk(buf.array(), len)
    }

    private fun awaitUsbResponse(): Int {
        while (true) {
            val header = readUsbChunk(12)
            val totalLen = header.getInt(0)
            val type = header.getShort(4).toInt() and 0xFFFF
            val code = header.getShort(6).toInt() and 0xFFFF
            val rest = (totalLen - 12).coerceAtLeast(0)
            if (rest > 0) discardBytes(rest)
            if (type == TYPE_RESPONSE) return code
        }
    }

    private fun writeBulk(data: ByteArray, length: Int) {
        val conn = connection ?: error("USB connection closed")
        val ep = bulkOut ?: error("USB bulkOut missing")
        val sent = conn.bulkTransfer(ep, data, length, 800)
        check(sent == length) { "USB bulkTransfer write failed: sent $sent / $length" }
    }

    private fun readUsbChunk(length: Int): ByteBuffer {
        readExactBytes(packetScratch, 0, length)
        return ByteBuffer.wrap(packetScratch, 0, length).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun readExactBytes(dest: ByteArray, offset: Int, length: Int) {
        val conn = connection ?: error("USB connection closed")
        val ep = bulkIn ?: error("USB bulkIn missing")
        var total = 0
        while (total < length) {
            val toRead = (length - total).coerceAtMost(16384)
            val n = conn.bulkTransfer(ep, dest, offset + total, toRead, 800)
            check(n > 0) { "USB bulkTransfer read timeout/error ($n)" }
            total += n
        }
    }

    private fun discardBytes(length: Int) {
        var remaining = length
        while (remaining > 0) {
            val step = remaining.coerceAtMost(packetScratch.size)
            readExactBytes(packetScratch, 0, step)
            remaining -= step
        }
    }

    private fun findStillImageEndpoints(
        dev: UsbDevice
    ): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? {
        for (i in 0 until dev.interfaceCount) {
            val iface = dev.getInterface(i)
            var outEp: UsbEndpoint? = null
            var inEp: UsbEndpoint? = null
            for (j in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(j)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_OUT) outEp = ep
                    else if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep
                }
            }
            if (outEp != null && inEp != null) return Triple(iface, outEp, inEp)
        }
        return null
    }

    private fun mapIsoToCanonCode(iso: Int): Int = when {
        iso <= 100 -> 0x48
        iso <= 200 -> 0x50
        iso <= 400 -> 0x58
        iso <= 800 -> 0x60
        iso <= 1600 -> 0x68
        iso <= 3200 -> 0x70
        iso <= 6400 -> 0x78
        else -> 0x80
    }

    private fun decodeCanonIsoCode(code: Int): Int = when (code) {
        0x48 -> 100
        0x50 -> 200
        0x58 -> 400
        0x60 -> 800
        0x68 -> 1600
        0x70 -> 3200
        0x78 -> 6400
        0x80 -> 12800
        else -> cachedIso
    }

    private fun findJpegStartOffset(buf: ByteArray, length: Int): Int {
        val searchLimit = (length - 1).coerceAtMost(64)
        for (i in 0 until searchLimit) {
            if ((buf[i].toInt() and 0xFF) == 0xFF && (buf[i + 1].toInt() and 0xFF) == 0xD8) {
                return i
            }
        }
        return -1
    }

    override fun close() {
        runCatching { usbInterface?.let { connection?.releaseInterface(it) } }
        runCatching { connection?.close() }
        connection = null
        usbInterface = null
        bulkOut = null
        bulkIn = null
    }
}
